# Arquitetura

> Este documento cobre a **arquitetura do sistema** — as peças, o fluxo entre elas e as escolhas de
> tecnologia. A organização interna do backend em ports & adapters está em
> [ARQUITETURA-HEXAGONAL.md](ARQUITETURA-HEXAGONAL.md).

## Visão geral

```
┌─────────────┐   SSE    ┌──────────────────┐        ┌─────────────────┐
│  React SPA  │◀────────▶│  Spring Boot API │───────▶│  API Anthropic  │
│   (web/)    │  /api/*  │      (ibm/)      │        │  claude-opus-5  │
└─────────────┘          └────────┬─────────┘        └─────────────────┘
                                  │
             ┌────────────────────┼────────────────────┐
             ▼                    ▼                    ▼
   ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────────┐
   │ Postgres+pgvector│ │ Embeddings (TEI) │ │  S3 — material       │
   │ vetores + dados  │ │ multilingual-e5  │ │  modules/<slug>/...  │
   └──────────────────┘ └──────────────────┘ └──────────────────────┘
                                              LocalStack no local,
                                              bucket real na AWS
```

## Módulos e alçadas

Duas divisões atravessam o sistema inteiro:

**Módulos** — cada módulo é um conjunto fechado de material, com pasta própria no S3
(`modules/<slug>/`) e linha própria em `study_module`. Conversas e documentos carregam `module_id`, e
a recuperação filtra por ele **dentro** de cada ramo da busca híbrida. Uma pergunta feita num módulo
novo não alcança o material de Cloudability, nem o contrário. É a garantia central do desenho.

**Alçadas** — `STUDENT` consulta (conversa, lista módulos, vê a base); `ADMIN` também administra
(cria módulos, envia material, sincroniza, apaga). Tudo que escreve na base vive sob `/api/admin`,
barrado pelo `AdminAccessInterceptor`.

> ⚠️ **A autenticação não foi implementada.** A alçada de uma requisição vem de
> `AccessResolver` — hoje, do cabeçalho `X-Access-Level` ou de `app.access.default-level`, igual para
> todo mundo. A fronteira está desenhada e verificada; falta a identidade. É um ponto só de mudança,
> e está marcado como tal no código. Ver [FASE-2-AWS.md](FASE-2-AWS.md), seção 5.

## O fluxo de uma pergunta

1. O front envia `POST /api/chat` com a pergunta e o **slug do módulo**, e passa a ler a resposta
   como stream.
2. `AskQuestionInteractor` resolve o módulo. Slug desconhecido falha aqui — responder com o material
   do módulo errado seria pior do que não responder.
3. A pergunta vira vetor (`EmbeddingPortOut`).
4. `KnowledgeRepositoryPortOut.search` faz a **busca híbrida, restrita ao módulo**, e devolve os
   trechos mais relevantes.
5. O interactor monta o prompt: persona do módulo + regras de fundamentação + trechos recuperados +
   histórico da conversa.
6. A resposta do Claude chega em streaming e é repassada token a token via SSE.
7. Pergunta e resposta são gravadas no Postgres; a conversa ganha título na primeira mensagem.

Eventos SSE emitidos, nesta ordem: `meta` (id da conversa) → `sources` (fontes recuperadas) →
`delta` (repetido, cada pedaço de texto) → `done`. Em caso de falha, `error` com a mensagem.

## Decisões técnicas

### Embeddings não vêm da Anthropic

**A API da Anthropic não tem endpoint de embeddings.** O Claude gera as respostas; os vetores vêm de
outro motor. Por padrão usamos o [Text Embeddings Inference](https://github.com/huggingface/text-embeddings-inference)
com `intfloat/multilingual-e5-small` — 384 dimensões, roda em CPU, entende português e inglês (o
material mistura os dois: transcrições em inglês, suas anotações em português).

O modelo e5 exige prefixos diferentes para documento (`passage: `) e consulta (`query: `) — isso está
em `app.embedding` no `application.yml` e é aplicado automaticamente.

`EmbeddingPortOut` isola o provedor. Trocar por Voyage AI ou Bedrock Titan na fase 2 é escrever
outro adaptador; nada mais na aplicação muda.

### O material no S3, não no disco do servidor

O material é **estado**, não artefato de build: cresce quando um administrador envia arquivos e
precisa sobreviver a uma troca de container ou de instância. Botá-lo na imagem Docker significaria
rebuild a cada arquivo novo; num volume do EC2, significaria backup manual e perda numa troca de
instância.

No S3, a mesma API serve os dois ambientes — LocalStack no local, bucket real na AWS. E na AWS o
acesso vem de uma **IAM Role anexada à instância**, sem chave de acesso nenhuma no servidor: o
`MaterialConfig` só define endpoint e credenciais quando eles existem no ambiente, e na ausência
deles o SDK percorre a cadeia padrão até a role. Detalhes em [FASE-2-AWS.md](FASE-2-AWS.md).

`MaterialCatalogPortOut` isola tudo isso. `MATERIAL_PROVIDER=filesystem` troca o bucket por uma pasta
em disco, com o mesmo layout, para rodar sem Docker.

### Postgres + pgvector em vez de banco vetorial dedicado

Um banco só guarda vetores, conversas e metadados. Menos peças para operar e, na fase 2, cabe no
free tier de provedores gerenciados. Um Qdrant ou Pinecone traria ganho de performance irrelevante
nesta escala (milhares de trechos, não milhões) e mais um serviço para manter.

### Busca híbrida, não só vetorial

`JdbcKnowledgeRepository.search` combina duas buscas e funde os resultados com **Reciprocal Rank
Fusion**:

- **Semântica** — `embedding <=> query` (distância de cosseno), índice HNSW.
- **Lexical** — `to_tsvector('portuguese')` + `tsquery` com os termos em **OU**, índice GIN.

Três detalhes que parecem menores e não são:

1. O filtro `module_id` entra **dentro de cada ramo**, e não no `SELECT` final. Se ficasse só no fim,
   os 40 candidatos de cada busca poderiam vir todos de outros módulos e a pergunta acabaria
   respondida com menos contexto do que existe — ou com nenhum.
2. Os termos lexicais são ligados por **OU**. O `plainto_tsquery` liga com `&`, exigindo que um único
   trecho contenha todos os termos da pergunta; com perguntas escritas como frase isso dá zero
   resultados, e o ramo lexical simplesmente não existia.
3. Um mesmo documento ocupa no máximo **3** das vagas, para que um arquivo grande e genérico não
   monopolize o contexto e afogue o material específico.

O motivo é o vocabulário deste material: `maxRecsPerResources`, `us-west-2`, ISF, `Apptio-Open-Token`,
"Snooze Mode". Embeddings capturam sentido, mas erram em termos exatos e siglas — justamente o que
as questões da prova cobram. A RRF resolve sem precisar afinar pesos: cada busca contribui com
`1/(60 + posição)`, e o que aparece bem colocado nas duas sobe.

### Chunking que respeita fronteira de frase

`TextChunker` quebra em ~1200 caracteres com 200 de sobreposição, mas procura para trás uma quebra
de parágrafo ou fim de frase antes de cortar. Cortar no meio de uma frase degrada bastante a
recuperação — o trecho perde o sujeito ou o complemento e deixa de casar com a pergunta.

### Modo de resposta: três alavancas, um botão

O botão **Completo / Econômico** no composer não troca de modelo — troca quanto se gasta dentro do
mesmo modelo, mexendo em três coisas de uma vez:

| | `full` | `economy` |
|---|---|---|
| Trechos recuperados | 8 | 3 |
| `effort` | `high` | `low` |
| Formato pedido no prompt | explicação completa | até 5 linhas, direto |

Mexer numa alavanca só renderia pouco. E a ordem importa: na tabela de preços a **saída custa
várias vezes a entrada**, então encurtar a resposta economiza mais do que encurtar o contexto —
por isso a instrução de brevidade é a peça central, e o corte de contexto vem junto.

O `thinking` continua **adaptativo nos dois modos**. Desligá-lo na Opus 5 tem efeitos colaterais
conhecidos — a chamada de ferramenta pode sair como texto visível, tags internas podem vazar — e
baixar o `effort` economiza sem esse risco.

O teto de `max_tokens` menor no modo econômico é rede de segurança, não a meta: quem encurta a
resposta é a instrução no prompt. Cortar por `max_tokens` truncaria no meio de uma frase.

**O que o modo econômico não afrouxa:** citar a fonte e não sair do material do módulo. Se
economizar token custasse rigor, o modo seria só uma forma mais barata de errar.

Onde cada peça mora segue a regra de dependência: o domínio declara a **intenção** (`AnswerMode`) e
decide o que muda no prompt e no `topK`; o adaptador traduz isso em `effort` e `max_tokens`, que são
vocabulário da API da Anthropic.

### Cache do prompt de sistema

O prompt de sistema é longo e idêntico entre chamadas, então leva `CacheControlEphemeral`. Em
conversas longas isso corta bastante o custo de entrada. O contexto recuperado vai **depois**, na
mensagem do usuário, porque muda a cada pergunta e invalidaria o cache se viesse antes.

O modo de resposta produz **dois prompts estáveis** — um por modo — em vez de um prompt remontado a
cada pergunta. Assim cada modo mantém a própria entrada de cache quente. Trocar o modo no meio de
uma conversa custa um *cache miss*, o que é aceitável para uma ação que a usuária dispara de vez em
quando.

### Streaming com virtual threads

`ChatController` usa `Executors.newVirtualThreadPerTaskExecutor()` (Java 21). Cada resposta em
streaming ocupa uma thread por vários segundos; com threads de plataforma isso limitaria a
concorrência rapidamente.

⚠️ O streaming exige buffering desligado em todo o caminho. Já está configurado no `nginx.conf`
(`proxy_buffering off`); num proxy adicional (CloudFront, ALB, Caddy) é preciso repetir o cuidado —
ver [FASE-2-AWS.md](FASE-2-AWS.md).

### Prompt de sistema: persona é dado, regras são domínio

O prompt de sistema tem duas metades. A **persona** ("você é um tutor de Cloudability...") vem da
coluna `study_module.persona` e o administrador edita à vontade — é o que muda de módulo para módulo.
As **regras de fundamentação** (`AskQuestionInteractor.GROUNDING_RULES`) vêm do domínio, valem para
todos e não são editáveis pela interface.

Isso importa porque as regras são o contrato do sistema, não preferência de quem criou o módulo. Elas
exigem citação de fonte, afirmam explicitamente que o modelo não tem acesso ao material de outros
módulos, e proíbem uma armadilha específica: dizer que um recurso "não existe" quando ele apenas não
está no material. São coisas diferentes, e confundi-las produz respostas confiantes e erradas.

## Stack

| Camada | Tecnologia | Versão |
|---|---|---|
| Frontend | React + Vite + TypeScript | 18 / 5 / 5.6 |
| Backend | Spring Boot + Java | 4.1.1 / 21 |
| Banco | Postgres + pgvector | 16 |
| Migrações | Flyway | via Spring Boot |
| Embeddings | TEI + multilingual-e5-small | 384 dimensões |
| LLM | Claude via `anthropic-java` | `claude-opus-5` / SDK 2.34.0 |
| Extração | PDFBox · Apache POI · visão do Claude | 3.0.3 / 5.3.0 |
| Material | S3 via AWS SDK v2 (LocalStack no local) | 2.31.0 |
