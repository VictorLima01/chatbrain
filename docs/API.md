# API

Base: `http://localhost:8080` (direto) ou `http://localhost/api` (via nginx no compose).

Quase tudo é escopado por **módulo**: um módulo é um conjunto fechado de material, e uma pergunta
feita nele nunca alcança os documentos de outro. O módulo de fábrica é `cloudability`, usado como
padrão quando o parâmetro é omitido.

As rotas dividem-se em duas alçadas:

| Prefixo | Alçada | O que faz |
|---|---|---|
| `/api/...` | qualquer uma | conversar, listar módulos, consultar a base |
| `/api/admin/...` | `admin` | criar módulos, enviar material, sincronizar, apagar |

---

## Alçada e módulos

### `GET /api/access`

```json
{ "level": "admin", "authEnabled": false }
```

Diz com que alçada **esta** requisição foi tratada. Usado pelo front para mostrar ou esconder a área
de administração.

> `authEnabled: false` significa que **não há autenticação**: a alçada vem de
> `app.access.default-level` (ou do cabeçalho `X-Access-Level`, útil em desenvolvimento) e é a mesma
> para todo mundo. Ver a seção de segurança em [FASE-2-AWS.md](FASE-2-AWS.md).

### `GET /api/modules`

```json
[
  { "slug": "cloudability", "name": "IBM Cloudability",
    "description": "Material oficial da certificacao IBM Cloudability (Apptio): L1, L2 e L4.",
    "persona": "Voce e um tutor especialista em IBM Cloudability...",
    "builtIn": true, "documentCount": 87, "chunkCount": 1042,
    "createdAt": "2026-09-09T12:00:00Z" }
]
```

`builtIn: true` marca o módulo de fábrica — ele não pode ser removido.

---

## Chat

### `POST /api/chat`

Envia uma pergunta e recebe a resposta em **Server-Sent Events**.

```json
{
  "conversationId": 12,
  "message": "Como o Cloudability define idle?",
  "module": "cloudability",
  "images": [
    { "filename": "print.png", "mediaType": "image/png", "data": "data:image/png;base64,iVBORw0K..." }
  ]
}
```

- `conversationId` pode ser `null` ou omitido — nesse caso uma conversa nova é criada, dentro do
  módulo informado, e o id volta no evento `meta`.
- `module` omitido cai em `cloudability`. Um slug inexistente devolve **400**, e não o material do
  módulo padrão: responder com a base errada seria pior do que não responder.
- `images` é opcional: prints anexados **a esta pergunta**. Ver abaixo.
- `mode` é opcional: `"full"` (padrão) ou `"economy"`. Ver abaixo.

#### Modo de resposta

| | `full` (padrão) | `economy` |
|---|---|---|
| Trechos no prompt | 8 | 3 |
| Profundidade de raciocínio | `effort: high` | `effort: low` |
| Formato pedido | explicação completa | até 5 linhas, direto ao ponto |
| Teto de saída | 16 000 tokens | 4 000 tokens |

As três alavancas agem juntas — mexer só numa economiza pouco. Medido na mesma
pergunta: **90–93% menos texto de saída** e resposta em 2–4 s em vez de 19–20 s.

O que **não** muda: a citação de fonte entre colchetes e o limite do módulo continuam valendo.
Econômico é responder com menos palavras, não com menos rigor.

Um `mode` irreconhecível cai em `full` — um cliente desatualizado deve receber a resposta completa,
não um erro.

#### Prints anexados

| Regra | Valor |
|---|---|
| Formatos | `image/png`, `image/jpeg`, `image/gif`, `image/webp` |
| Tamanho por imagem | 5 MB de bytes crus (a API da Anthropic aceita 10 MB já em base64, e a codificação infla ~33%) |
| Imagens por pergunta | 4 |
| Redução automática | prints com lado maior acima de `ANTHROPIC_IMAGE_MAX_EDGE` (padrão 1280 px) são reduzidos antes de ir à API |
| `data` | base64 puro ou o data URL que o `FileReader` do navegador produz — o prefixo `data:...;base64,` é descartado |

`message` pode vir vazio quando há imagem: colar um print já é a pergunta, e o backend usa
*"Analise a imagem anexada e explique o que ela mostra."* no lugar.

> **As imagens valem só para o turno em que foram enviadas.** Não entram na base de conhecimento e
> não são gravadas no banco — a mensagem guardada fica com uma marca
> `[imagens anexadas: print.png]` para o histórico não ter lacuna. Para que um print passe a fazer
> parte do material consultável, envie por `POST /api/admin/modules/{slug}/material`.
>
> Consequência prática: numa pergunta de acompanhamento, o modelo tem a resposta anterior como
> contexto, mas **não vê mais a imagem**. Reanexe se a dúvida for sobre um detalhe dela.

**Eventos, na ordem em que chegam:**

| Evento | Payload | Quando |
|---|---|---|
| `meta` | `{"conversationId": 12}` | Início, sempre |
| `sources` | `[{"title","source","level","score"}]` | Depois da recuperação, antes do texto |
| `delta` | `{"text": "pedaço"}` | Repetido, conforme o modelo gera |
| `done` | `{}` | Fim normal |
| `error` | `{"message": "..."}` | Falha; o stream encerra |

```bash
curl -N -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"O que e ISF?","module":"cloudability"}'
```

> O `EventSource` do navegador só faz `GET`. Como aqui é `POST`, o front lê o corpo manualmente —
> ver `streamChat` em `web/src/api.ts`.

---

## Conversas

| Método | Rota | Resposta |
|---|---|---|
| `GET` | `/api/conversations?module=cloudability` | `[{id, title, updatedAt, messageCount}]` — as 100 mais recentes **do módulo** |
| `POST` | `/api/conversations?module=cloudability` | `{id, title}` |
| `GET` | `/api/conversations/{id}/messages` | `[{role, content, sources}]` |
| `DELETE` | `/api/conversations/{id}` | `{deleted: true}` |

O título é definido automaticamente pela primeira pergunta (truncado em 60 caracteres) e só é
sobrescrito enquanto for `"Nova conversa"`.

---

## Base de conhecimento (leitura)

### `GET /api/knowledge?module=cloudability`

```json
{
  "module": "cloudability",
  "documents": [
    { "id": 1, "title": "rigthsizing",
      "source": "modules/cloudability/L4/Optimization Features/rigthsizing.vtt",
      "kind": "vtt", "level": "L4 / Optimization Features",
      "charCount": 24518, "chunkCount": 23, "createdAt": "2026-08-29T22:41:00Z" }
  ],
  "totalDocuments": 87,
  "totalChunks": 1042
}
```

`source` é o endereço do arquivo no armazenamento — a chave do objeto no S3.

### `GET /api/knowledge/status`

```json
{
  "anthropicConfigured": true,
  "model": "claude-opus-5",
  "effort": "high",
  "embeddingProvider": "tei",
  "embeddingDimensions": 384,
  "materialProvider": "s3",
  "topK": 8,
  "totalDocuments": 87,
  "totalChunks": 1042
}
```

Contagens somadas de **todos** os módulos. Usado pela tela **Sobre** e pelo selo no cabeçalho.
Também serve de healthcheck — é o endpoint que o `HEALTHCHECK` do Dockerfile consulta.

---

## Administração

Tudo abaixo exige alçada `admin` (`AdminAccessInterceptor`). Sem ela, **403** com
`{"message": "Esta operacao exige alcada de administrador"}`.

### `GET /api/admin/modules`

Mesmo payload de `GET /api/modules`.

### `POST /api/admin/modules`

```json
{ "name": "FinOps para Kubernetes",
  "description": "Custos de workloads em cluster",
  "persona": "Voce e um instrutor de FinOps focado em Kubernetes." }
```

- `slug` é opcional: derivado do nome (`finops-para-kubernetes`) quando ausente.
- `persona` define só o papel do tutor. As regras de citar a fonte e de não sair do material do
  módulo são do domínio e valem sempre — não são editáveis.

Devolve o módulo criado. **400** se o slug já existir ou se o nome estiver em branco.

### `PUT /api/admin/modules/{slug}`

Atualiza nome, descrição e persona. **O slug não muda**: ele é o endereço do material no
armazenamento, e trocá-lo desligaria o módulo dos arquivos já enviados.

### `DELETE /api/admin/modules/{slug}`

Remove o módulo, seus documentos, seus trechos, suas conversas (cascata no banco) e a pasta dele no
S3. **400** para um módulo de fábrica.

### `POST /api/admin/modules/{slug}/sync?force=false`

Varre `modules/{slug}/` no armazenamento e indexa o que mudou. Com `force=true`, reprocessa tudo.

```json
{ "indexed": 12, "skipped": 75, "failed": 0,
  "totalDocuments": 87, "totalChunks": 1042, "errors": [] }
```

As contagens são **do módulo**, não da base inteira.

> Chamada síncrona e demorada na primeira execução. O cliente HTTP precisa de timeout generoso.

### `POST /api/admin/modules/{slug}/material`

`multipart/form-data` — campos: `file` (obrigatório), `title`, `level`.

```bash
curl -X POST http://localhost:8080/api/admin/modules/cloudability/material \
  -F 'file=@resumo.pdf' -F 'title=Resumo L4' -F 'level=L4'
```

O arquivo é **guardado** em `modules/{slug}/enviados/` e depois indexado — não só o texto extraído.
Assim um `force=true` futuro reencontra a mesma fonte. Limite de 50 MB
(`spring.servlet.multipart`).

### `POST /api/admin/modules/{slug}/text`

```json
{ "title": "Pegadinhas de Commitment", "content": "...", "level": "L4" }
```

Vira um `.md` guardado em `modules/{slug}/enviados/` e indexado.

### `DELETE /api/admin/knowledge/{id}`

Remove o documento e seus trechos da base. O arquivo permanece no armazenamento — uma sincronização
posterior o traz de volta. Para sumir de vez, apague também o objeto no S3.

---

## Erros

No `/api/chat`, as falhas voltam como evento `error` no stream. Nos demais endpoints, como JSON:

```json
{ "message": "Modulo desconhecido: kubernets" }
```

| Status | Mensagem | Causa |
|---|---|---|
| 400 | `Modulo desconhecido: X` | Slug inexistente |
| 400 | `Ja existe um modulo com o identificador X` | Colisão de slug |
| 400 | `O modulo X e de fabrica e nao pode ser removido` | Tentativa de apagar o módulo padrão |
| 400 | `Nao foi possivel extrair texto de X` | Formato não suportado ou arquivo vazio/escaneado |
| 400 | `Formato nao suportado em X: image/bmp` | Print num formato que a visão do Claude não lê |
| 400 | `Imagem X tem N MB; o limite e 5 MB` | Print acima do teto por imagem |
| 400 | `No maximo 4 imagens por pergunta` | Prints demais num único turno |
| 403 | `Esta operacao exige alcada de administrador` | Rota `/api/admin` sem alçada |
| 409 | `ANTHROPIC_API_KEY nao configurada` | Variável ausente no backend |
| 409 | `Falha ao gerar embeddings em http://...` | Serviço de embeddings fora do ar |

---

## Autenticação

**Não há.** As alçadas existem e são verificadas, mas quem define a alçada de uma requisição é a
configuração — não um login. Na prática, qualquer um com acesso à porta consegue conversar, criar
módulos, indexar e apagar, e gastar os créditos da API. Aceitável em `localhost`; **inaceitável
exposto na internet**. Ver a seção de segurança em [FASE-2-AWS.md](FASE-2-AWS.md).
