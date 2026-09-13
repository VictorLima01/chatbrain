# Base de conhecimento

Como o material de estudo vira conteúdo pesquisável pelo chat.

## Módulos: o recorte que isola o material

A base é dividida em **módulos**. Um módulo é um conjunto fechado de material, e é a fronteira de
isolamento do sistema: uma pergunta feita no módulo X só recupera trechos de documentos do módulo X.
O material de Cloudability não aparece num módulo novo, e o contrário também não.

Isso não é uma convenção do código — é uma garantia do banco (`kb_document.module_id`) aplicada
dentro da consulta de recuperação. Ver *Como a busca funciona*, abaixo.

| Módulo | Origem |
|---|---|
| `cloudability` | De fábrica, criado pela migração `V2__modules.sql`. Não pode ser removido |
| qualquer outro | Criado por um administrador na área de administração |

Cada módulo tem uma **persona** — o "quem você é" do tutor naquele assunto, editável pelo
administrador. As regras de comportamento (citar a fonte, não afirmar que algo não existe, não sair
do material do módulo) vêm do domínio e valem para todos, sempre.

## Onde o material fica

No **S3**, numa pasta por módulo:

```
s3://cloudability-material/
  modules/
    cloudability/
      L1/  L2/  L4/     material sincronizado da pasta ingestao/ do projeto
      agents/           base curada do agente especialista
      enviados/         uploads feitos pela interface
    <outro-modulo>/
      enviados/
```

Local, quem serve esse S3 é o **LocalStack** (ver `docker-compose.yml`); o serviço `material-seed`
publica o conteúdo de `ingestao/` no módulo `cloudability` a cada `docker compose up`. Na AWS é um
bucket de verdade, acessado pela IAM Role da instância EC2 — mesma API, mesmo código.

> Os arquivos `.mp4` são **ignorados** e nem chegam ao bucket: o conteúdo dos vídeos entra pelas
> legendas `.vtt`, que trazem a mesma informação em texto e ocupam alguns KB em vez de centenas de MB.

Para rodar o backend sem Docker, `MATERIAL_PROVIDER=filesystem` troca o S3 por
`<MATERIAL_BASE_PATH>/modules/<slug>/`, com o mesmo layout.

## Formatos suportados

| Extensão | Como é extraído |
|---|---|
| `.vtt` | Parser próprio: remove cabeçalho, índices, timestamps e as **linhas repetidas** que legendas automáticas produzem |
| `.pdf` | Apache PDFBox, com ordenação por posição |
| `.pptx` | Apache POI, slide a slide, marcando o número de cada um |
| `.md` / `.txt` | Lidos direto |
| `.png` / `.jpg` | Enviados à **visão do Claude**, que devolve a transcrição em Markdown |

> A transcrição de imagem consome tokens da API. É por isso que o `Perguntas.png` só é reprocessado
> quando o arquivo muda.

## O pipeline

```
objeto no S3 ──▶ extração ──▶ chunking ──▶ embeddings ──▶ Postgres
                    │            │             │              │
                texto puro   ~1200 chars   vetor 384d    kb_document (module_id)
                             + 200 overlap                 kb_chunk
```

1. **Extração** — `ContentExtractor` converte o formato em texto puro.
2. **Chunking** — `TextChunker` quebra em ~1200 caracteres com 200 de sobreposição, procurando
   fronteira de parágrafo ou fim de frase antes de cortar.
3. **Embeddings** — cada trecho vira um vetor de 384 dimensões, em lotes de 16.
4. **Persistência** — `kb_document` guarda o arquivo **e o módulo a que pertence**; `kb_chunk`, os
   trechos com seus vetores.

### Controle por checksum

Cada documento guarda o SHA-256 do arquivo. **Sincronizar** pula o que não mudou; só o conteúdo novo
ou alterado é reprocessado. Isso importa porque reprocessar tudo custa tempo (e, no caso de imagens,
tokens da API).

Para forçar o reprocessamento completo, use **Reindexar tudo**
(`POST /api/admin/modules/{slug}/sync?force=true`).

## Adicionando conteúdo

Tudo o que escreve na base é **alçada de administrador** e vive no menu **Administração**:

| Ação | Para quê |
|---|---|
| **Criar módulo** | Abrir um assunto novo, com material próprio e isolado |
| **Sincronizar material** | Depois de subir arquivos direto para a pasta do módulo no bucket |
| **Reindexar tudo** | Depois de mudar o modelo de embeddings ou o tamanho do chunk |
| **Enviar arquivo** | PDF, VTT, PPTX, MD, TXT ou imagem avulsa — vai para `modules/<slug>/enviados/` |
| **Colar texto** | Anotações suas, questões soltas, resumos |
| **remover** | Tira um documento da base (o arquivo continua no bucket) |

O menu **Base de conhecimento** mostra o que está indexado no módulo ativo, mas só lê.

### Colocando arquivos direto no bucket

```bash
# local (LocalStack)
aws --endpoint-url http://localhost:4566 s3 sync ./novo-material \
  s3://cloudability-material/modules/meu-modulo/

# AWS
aws s3 sync ./novo-material s3://cloudability-material/modules/meu-modulo/
```

Depois, **Sincronizar material** no módulo correspondente.

A categoria (`level`) é deduzida do caminho **dentro do módulo**:
`L4/Optimization Features/rigthsizing.vtt` vira `L4 / Optimization Features`. A busca pelo segmento
`L<dígito>` acontece em qualquer posição, então reorganizar as pastas não quebra a categorização. Um
módulo que não siga essa convenção cai na primeira pasta do caminho, ou em `Geral`.

## Como a busca funciona

A recuperação é **híbrida** — duas buscas independentes fundidas por Reciprocal Rank Fusion:

- **Semântica** — proximidade de significado, via distância de cosseno entre vetores (índice HNSW).
- **Lexical** — full-text em português (índice GIN).

Cada busca traz até 40 candidatos e contribui com `1/(60 + posição)` para a nota final. Os `RAG_TOP_K`
melhores (padrão: 8) entram no prompt.

**O filtro de módulo entra dentro de cada uma das duas buscas**, e não no fim. Se ficasse só no
`SELECT` final, os 40 candidatos de cada ramo poderiam vir todos de outros módulos e a pergunta
acabaria respondida com menos contexto do que existe — ou com nenhum.

**Os termos da busca lexical são ligados por OU, não por E.** O `plainto_tsquery` do Postgres liga
tudo com `&`: a pergunta *"o que é o Cloudability Financial Planning e qual o ciclo de vida de um
plano?"* virava `cloudability & financial & planning & cicl & vid & plan` e exigia que um único
trecho contivesse **todos** esses termos. Nenhum continha — o ramo lexical devolvia zero linhas para
qualquer pergunta escrita como frase, e a busca "híbrida" era, na prática, só vetorial. Com OU, o
`ts_rank` volta a ordenar por quantos termos casaram e com que densidade.

**Um mesmo documento ocupa no máximo 3 das vagas.** Sem esse teto, um arquivo grande e genérico
monopoliza o contexto: a base de Cloudability tem um resumo curado de 144 trechos que casa bem com
quase qualquer pergunta em português e levava as 8 vagas sozinho, deixando de fora justamente o
material específico que responderia melhor.

**Por que não só vetorial:** o material é cheio de termos exatos — `maxRecsPerResources`, `us-west-2`,
ISF, "Snooze Mode", `Apptio-Open-Token`. Embeddings capturam sentido, mas erram siglas e nomes
literais. A busca lexical resgata exatamente isso, que é o que as questões da prova costumam cobrar.

## Esquema no banco

```sql
study_module (id, slug, name, description, persona, built_in, created_at)
kb_document  (id, module_id → study_module, title, source, kind, level,
              checksum, char_count, chunk_count, created_at)
kb_chunk     (id, document_id → kb_document, ordinal, content, embedding vector(384), created_at)
conversation (id, module_id → study_module, title, created_at, updated_at)
```

`source` é a chave do objeto no S3 (`modules/<slug>/...`) e é única na base inteira. Índices: HNSW em
`embedding` (cosseno), GIN em `to_tsvector('portuguese', content)`, e comuns em `document_id` e
`module_id`. Apagar um módulo remove seus documentos, trechos e conversas em cascata.

## Consultando direto no banco

```bash
docker compose exec db psql -U cloudability -d cloudability
```

```sql
-- o que está indexado, por módulo e nível
SELECT m.slug, d.level, COUNT(*) AS docs, SUM(d.chunk_count) AS trechos
FROM kb_document d JOIN study_module m ON m.id = d.module_id
GROUP BY m.slug, d.level ORDER BY m.slug, d.level;

-- documentos maiores
SELECT title, kind, chunk_count FROM kb_document
ORDER BY chunk_count DESC LIMIT 10;

-- trechos sem vetor (indicam falha na geração de embeddings)
SELECT COUNT(*) FROM kb_chunk WHERE embedding IS NULL;

-- confirmação do isolamento: nenhum trecho sem módulo
SELECT COUNT(*) FROM kb_document WHERE module_id IS NULL;
```

## Limitações conhecidas

- **PDFs escaneados** (imagem, sem camada de texto) saem vazios do PDFBox. Se acontecer, converta a
  página em PNG e envie pelo upload — aí a visão do Claude resolve.
- **Tabelas em PDF** perdem a estrutura na extração; viram texto corrido.
- **Legendas automáticas** trazem erros de transcrição (o material tem "business meeting" onde o
  instrutor disse "business mapping", e "PERS" onde disse "Params"). O chat herda esses erros — vale
  conferir a fonte quando algo soar estranho.
- **Sem OCR local**: toda imagem passa pela API, o que consome tokens.
- **Pergunta em português, material em inglês.** O e5 é multilíngue e atravessa os dois, mas uma
  pergunta em português sobre um tema que os resumos curados (também em português) cobrem pode
  ranquear esses resumos acima do documento específico em inglês. Usar o termo oficial na pergunta
  ("Container Insights", "TrueCost Explorer") resolve na prática; subir `RAG_TOP_K` também.
- **Apagar um documento não apaga o arquivo** no bucket: uma sincronização posterior o traz de volta.
  Para sumir de vez, remova também o objeto no S3.
- **LocalStack community não persiste objetos** entre reinícios. Por isso o `material-seed` roda a
  cada `docker compose up`. Os vetores, esses ficam no Postgres e sobrevivem.
