export type Conversation = {
  id: number
  title: string
  updatedAt: string
  messageCount: number
}

export type Source = {
  title: string
  source: string
  level: string
  score: number
}

/** O backend ja devolve as fontes desserializadas (ver WebDtos.MessageResponse). */
export type StoredMessage = {
  role: 'user' | 'assistant'
  content: string
  sources: Source[] | null
}

/**
 * Print anexado a uma pergunta.
 *
 * `data` e o data URL que o FileReader produz; o backend descarta o prefixo.
 * Vale so para o turno em que foi enviado — nao entra na base de conhecimento.
 */
export type ChatImage = {
  filename: string
  mediaType: string
  data: string
}

/** Formatos que a visao do Claude entende. */
export const SUPPORTED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']

/** 5 MB de bytes crus — a API aceita 10 MB ja em base64, e base64 infla ~33%. */
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024

export const MAX_IMAGES_PER_MESSAGE = 4

/**
 * Quanto gastar numa resposta.
 *
 * `full` e o padrao: resposta explicada, 8 trechos de contexto, raciocinio
 * profundo. `economy` corta os tres ao mesmo tempo — resposta direta, 3 trechos,
 * raciocinio raso — sem afrouxar citacao de fonte nem o limite do modulo.
 */
export type AnswerMode = 'full' | 'economy'

/**
 * Converte um arquivo escolhido, colado ou arrastado em anexo pronto para envio.
 *
 * Valida aqui o que o backend tambem valida — o backend e a autoridade, mas
 * avisar antes de subir 5 MB pela rede e mais gentil do que depois.
 */
export function toChatImage(file: File): Promise<ChatImage> {
  const type = file.type.toLowerCase()
  if (!SUPPORTED_IMAGE_TYPES.includes(type)) {
    return Promise.reject(
      new Error(`${file.name}: formato ${file.type || 'desconhecido'} nao suportado.`),
    )
  }
  if (file.size > MAX_IMAGE_BYTES) {
    const mb = (file.size / (1024 * 1024)).toFixed(1)
    return Promise.reject(new Error(`${file.name} tem ${mb} MB; o limite e 5 MB.`))
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(new Error(`Nao foi possivel ler ${file.name}.`))
    reader.onload = () =>
      resolve({
        filename: file.name || 'print.png',
        mediaType: type,
        data: String(reader.result),
      })
    reader.readAsDataURL(file)
  })
}

export type StudyModule = {
  slug: string
  name: string
  description: string | null
  persona: string | null
  builtIn: boolean
  documentCount: number
  chunkCount: number
  createdAt: string
}

export type AccessLevel = 'admin' | 'student'

export type Access = {
  level: AccessLevel
  /** Falso enquanto nao houver login: a area de admin fica aberta a quem tem a URL. */
  authEnabled: boolean
}

export type KnowledgeDocument = {
  id: number
  title: string
  source: string
  kind: string
  level: string | null
  charCount: number
  chunkCount: number
  createdAt: string
}

export type KnowledgeList = {
  module: string
  documents: KnowledgeDocument[]
  totalDocuments: number
  totalChunks: number
}

export type Status = {
  anthropicConfigured: boolean
  model: string
  effort: string
  embeddingProvider: string
  embeddingDimensions: number
  materialProvider: string
  topK: number
  totalDocuments: number
  totalChunks: number
}

export type IngestReport = {
  indexed: number
  skipped: number
  failed: number
  totalDocuments: number
  totalChunks: number
  errors: string[]
}

/**
 * Le a resposta e, no erro, extrai a mensagem que o backend mandou.
 *
 * O RestExceptionHandler devolve `{"message": "..."}` — mostrar isso e bem mais
 * util do que "400 Bad Request".
 */
async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.text()
    try {
      const parsed = JSON.parse(body)
      throw new Error(parsed.message ?? body)
    } catch (e) {
      if (e instanceof Error && e.message !== body) throw e
      throw new Error(body || `${res.status} ${res.statusText}`)
    }
  }
  return res.json() as Promise<T>
}

const jsonHeaders = { 'Content-Type': 'application/json' }

export const api = {
  // --------------------------------------------------------------- modulos
  modules: () => fetch('/api/modules').then(json<StudyModule[]>),

  access: () => fetch('/api/access').then(json<Access>),

  // -------------------------------------------------------------- conversas
  listConversations: (module: string) =>
    fetch(`/api/conversations?module=${encodeURIComponent(module)}`).then(json<Conversation[]>),

  createConversation: (module: string) =>
    fetch(`/api/conversations?module=${encodeURIComponent(module)}`, { method: 'POST' }).then(
      json<{ id: number; title: string }>,
    ),

  deleteConversation: (id: number) =>
    fetch(`/api/conversations/${id}`, { method: 'DELETE' }).then(json<{ deleted: boolean }>),

  messages: (id: number) => fetch(`/api/conversations/${id}/messages`).then(json<StoredMessage[]>),

  // ------------------------------------------------------ base (so leitura)
  knowledge: (module: string) =>
    fetch(`/api/knowledge?module=${encodeURIComponent(module)}`).then(json<KnowledgeList>),

  status: () => fetch('/api/knowledge/status').then(json<Status>),
}

/**
 * Rotas administrativas.
 *
 * Separadas de proposito: sao as que o backend protege sob /api/admin. Manter a
 * divisao visivel no cliente evita que uma tela de consulta chame sem querer uma
 * operacao de escrita.
 */
export const adminApi = {
  createModule: (body: {
    slug?: string
    name: string
    description?: string
    persona?: string
  }) =>
    fetch('/api/admin/modules', {
      method: 'POST',
      headers: jsonHeaders,
      body: JSON.stringify(body),
    }).then(json<StudyModule>),

  updateModule: (
    slug: string,
    body: { name?: string; description?: string; persona?: string },
  ) =>
    fetch(`/api/admin/modules/${encodeURIComponent(slug)}`, {
      method: 'PUT',
      headers: jsonHeaders,
      body: JSON.stringify(body),
    }).then(json<StudyModule>),

  deleteModule: (slug: string) =>
    fetch(`/api/admin/modules/${encodeURIComponent(slug)}`, { method: 'DELETE' }).then(
      json<{ deleted: boolean }>,
    ),

  sync: (module: string, force: boolean) =>
    fetch(`/api/admin/modules/${encodeURIComponent(module)}/sync?force=${force}`, {
      method: 'POST',
    }).then(json<IngestReport>),

  uploadFile: (module: string, file: File, title: string, level: string) => {
    const form = new FormData()
    form.append('file', file)
    if (title) form.append('title', title)
    if (level) form.append('level', level)
    return fetch(`/api/admin/modules/${encodeURIComponent(module)}/material`, {
      method: 'POST',
      body: form,
    }).then(json<IngestReport>)
  },

  addText: (module: string, title: string, content: string, level: string) =>
    fetch(`/api/admin/modules/${encodeURIComponent(module)}/text`, {
      method: 'POST',
      headers: jsonHeaders,
      body: JSON.stringify({ title, content, level }),
    }).then(json<IngestReport>),

  deleteDocument: (id: number) =>
    fetch(`/api/admin/knowledge/${id}`, { method: 'DELETE' }).then(json<{ deleted: boolean }>),
}

/**
 * Envia a pergunta e consome a resposta em streaming (SSE sobre POST).
 * O EventSource nativo so faz GET, entao lemos o corpo manualmente.
 */
export async function streamChat(
  conversationId: number | null,
  message: string,
  module: string,
  images: ChatImage[],
  mode: AnswerMode,
  handlers: {
    onMeta?: (conversationId: number) => void
    onSources?: (sources: Source[]) => void
    onDelta: (text: string) => void
    onDone: () => void
    onError: (message: string) => void
  },
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/chat', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify({ conversationId, message, module, images, mode }),
    signal,
  })

  if (!response.ok || !response.body) {
    // Erros de validacao (formato, tamanho, quantidade de imagens) chegam aqui
    // como JSON do RestExceptionHandler, antes de o stream comecar.
    const body = await response.text().catch(() => '')
    let detail = `${response.status} ${response.statusText}`
    try {
      detail = JSON.parse(body).message ?? detail
    } catch {
      if (body) detail = body
    }
    handlers.onError(detail)
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    const frames = buffer.split('\n\n')
    buffer = frames.pop() ?? ''

    for (const frame of frames) {
      let event = 'message'
      const dataLines: string[] = []
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
      }
      if (dataLines.length === 0) continue
      const raw = dataLines.join('\n')

      try {
        switch (event) {
          case 'meta':
            handlers.onMeta?.(JSON.parse(raw).conversationId)
            break
          case 'sources':
            handlers.onSources?.(JSON.parse(raw) as Source[])
            break
          case 'delta':
            handlers.onDelta(JSON.parse(raw).text)
            break
          case 'done':
            handlers.onDone()
            return
          case 'error':
            handlers.onError(JSON.parse(raw).message)
            return
        }
      } catch {
        // frame parcial ou malformado: ignora e segue
      }
    }
  }
  handlers.onDone()
}
