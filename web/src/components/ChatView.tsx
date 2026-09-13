import { useEffect, useRef, useState } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import {
  api,
  streamChat,
  toChatImage,
  MAX_IMAGES_PER_MESSAGE,
  SUPPORTED_IMAGE_TYPES,
  type AnswerMode,
  type ChatImage,
  type Source,
  type StudyModule,
} from '../api'
import { ThinkingBlob } from './ThinkingBlob'

type ChatMessage = {
  role: 'user' | 'assistant'
  content: string
  sources?: Source[]
  /** Miniaturas do que foi anexado, só para a usuária reconhecer o turno. */
  images?: ChatImage[]
}

/**
 * O modo fica no localStorage, e nao no estado da conversa: e uma preferencia de
 * como voce quer ser respondida agora, nao um atributo do que ja foi perguntado.
 * Trocar o modo no meio de uma conversa vale a partir da proxima pergunta.
 */
const MODE_KEY = 'cloudability-chat:mode'

function storedMode(): AnswerMode {
  try {
    return localStorage.getItem(MODE_KEY) === 'economy' ? 'economy' : 'full'
  } catch {
    return 'full'
  }
}

const SUGGESTIONS = [
  'Como o Cloudability define "idle" para cada tipo de recurso?',
  'Qual a diferenca entre Reserved Instance e Savings Plan?',
  'O que dispara uma anomalia no Anomaly Detection?',
  'Me faca um simulado de 5 questoes sobre Rightsizing',
]

export function ChatView({
  module,
  initialConversationId,
  onConversationCreated,
  onTitleChanged,
}: {
  module: StudyModule
  /**
   * Conversa aberta no momento da montagem.
   *
   * Deliberadamente "initial": depois de montado, quem manda na conversa e o
   * proprio ChatView (ver `conversationRef`). O App troca a `key` quando quer
   * de fato reiniciar a tela.
   */
  initialConversationId: number | null
  onConversationCreated: (id: number) => void
  onTitleChanged: () => void
}) {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [pending, setPending] = useState<ChatImage[]>([])
  const [streaming, setStreaming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
  const [mode, setMode] = useState<AnswerMode>(storedMode)
  const abortRef = useRef<AbortController | null>(null)
  const endRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  /**
   * A conversa viva desta tela.
   *
   * Num ref, e nao num state, porque `send` precisa enxergar o id que o evento
   * `meta` acabou de trazer — e sem provocar re-render no meio do streaming.
   */
  const conversationRef = useRef<number | null>(initialConversationId)

  // Carrega o historico uma vez, na montagem. Nao depende de estado que muda
  // durante o streaming: o App remonta a tela (via key) quando troca de conversa.
  useEffect(() => {
    const id = conversationRef.current
    if (id === null) return
    let cancelled = false
    api
      .messages(id)
      .then((stored) => {
        if (cancelled) return
        setMessages(
          stored.map((m) => ({
            role: m.role,
            content: m.content,
            sources: m.sources && m.sources.length > 0 ? m.sources : undefined,
          })),
        )
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, streaming])

  const autoGrow = () => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`
  }

  /** Caminho único para anexar, venha de onde vier: botão, Ctrl+V ou arrastar. */
  const attach = async (files: FileList | File[] | null) => {
    const incoming = Array.from(files ?? []).filter((f) =>
      SUPPORTED_IMAGE_TYPES.includes(f.type.toLowerCase()),
    )
    if (incoming.length === 0) return

    const room = MAX_IMAGES_PER_MESSAGE - pending.length
    if (room <= 0) {
      setError(`Maximo de ${MAX_IMAGES_PER_MESSAGE} imagens por mensagem.`)
      return
    }

    const results = await Promise.allSettled(incoming.slice(0, room).map(toChatImage))
    const accepted = results
      .filter((r): r is PromiseFulfilledResult<ChatImage> => r.status === 'fulfilled')
      .map((r) => r.value)
    const rejected = results.filter((r) => r.status === 'rejected') as PromiseRejectedResult[]

    if (accepted.length > 0) setPending((prev) => [...prev, ...accepted])
    setError(
      rejected.length > 0
        ? rejected.map((r) => (r.reason as Error).message).join(' ')
        : incoming.length > room
          ? `Só cabiam mais ${room} imagem(ns) nesta mensagem.`
          : null,
    )
  }

  const removePending = (index: number) =>
    setPending((prev) => prev.filter((_, i) => i !== index))

  const toggleMode = () => {
    const next: AnswerMode = mode === 'economy' ? 'full' : 'economy'
    setMode(next)
    try {
      localStorage.setItem(MODE_KEY, next)
    } catch {
      /* modo anonimo: vale so para esta aba */
    }
  }

  const send = async (text: string) => {
    const question = text.trim()
    const images = pending
    if ((!question && images.length === 0) || streaming) return

    setError(null)
    setInput('')
    setPending([])
    if (textareaRef.current) textareaRef.current.style.height = 'auto'
    setMessages((prev) => [
      ...prev,
      { role: 'user', content: question, images: images.length > 0 ? images : undefined },
      { role: 'assistant', content: '' },
    ])
    setStreaming(true)

    const controller = new AbortController()
    abortRef.current = controller

    try {
      await streamChat(
        conversationRef.current,
        question,
        module.slug,
        images,
        mode,
        {
          onMeta: (id) => {
            // A conversa acabou de nascer no backend. Guarda o id aqui primeiro:
            // avisar o App e o que atualiza a barra lateral, e essa atualizacao
            // nao pode interferir no streaming em andamento.
            const isNew = conversationRef.current === null
            conversationRef.current = id
            if (isNew) onConversationCreated(id)
          },
          onSources: (sources) =>
            setMessages((prev) => {
              const next = [...prev]
              next[next.length - 1] = { ...next[next.length - 1], sources }
              return next
            }),
          onDelta: (delta) =>
            setMessages((prev) => {
              const next = [...prev]
              const last = next[next.length - 1]
              next[next.length - 1] = { ...last, content: last.content + delta }
              return next
            }),
          onDone: () => {
            setStreaming(false)
            onTitleChanged()
          },
          onError: (message) => {
            setError(message)
            setStreaming(false)
            setMessages((prev) => prev.filter((m, i) => !(i === prev.length - 1 && m.content === '')))
          },
        },
        controller.signal,
      )
    } catch (e) {
      if (!controller.signal.aborted) {
        setError(e instanceof Error ? e.message : String(e))
      }
      setStreaming(false)
    }
  }

  const stop = () => {
    abortRef.current?.abort()
    setStreaming(false)
  }

  const isEmpty = messages.length === 0
  const suggestions = module.builtIn ? SUGGESTIONS : []
  const canSend = Boolean(input.trim()) || pending.length > 0

  return (
    <>
      <div className="scroll">
        {isEmpty ? (
          <div className="empty">
            <div className="blob" style={{ width: 44, height: 44, flexBasis: 44 }} />
            <h2>Em que posso ajudar nos estudos?</h2>
            <p style={{ margin: 0, maxWidth: 460 }}>
              {module.description
                ? module.description
                : `Pergunte sobre o material do modulo ${module.name}.`}{' '}
              As respostas citam a fonte e usam so o material deste modulo.
            </p>
            {module.chunkCount === 0 && (
              <div className="alert info" style={{ maxWidth: 460, marginBottom: 0 }}>
                Este modulo ainda nao tem material indexado. Um administrador precisa enviar os
                arquivos e sincronizar.
              </div>
            )}
            <div className="suggestions">
              {suggestions.map((s) => (
                <button key={s} className="suggestion" onClick={() => void send(s)}>
                  {s}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <div className="content">
            {messages.map((message, index) => (
              <div className="message" key={index}>
                <div className={`avatar ${message.role}`}>{message.role === 'user' ? 'Voce' : 'C'}</div>
                <div className="bubble">
                  {message.images && message.images.length > 0 && (
                    <div className="attachments sent">
                      {message.images.map((img, i) => (
                        <img key={i} src={img.data} alt={img.filename} title={img.filename} />
                      ))}
                    </div>
                  )}
                  {message.role === 'assistant' && message.content === '' && streaming ? (
                    <ThinkingBlob />
                  ) : (
                    <Markdown remarkPlugins={[remarkGfm]}>{message.content}</Markdown>
                  )}
                  {message.sources && message.sources.length > 0 && message.content !== '' && (
                    <div className="sources">
                      {dedupe(message.sources).map((s) => (
                        <span className="source-chip" key={s} title={s}>
                          {shortName(s)}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            ))}
            {error && <div className="alert error">{error}</div>}
            <div ref={endRef} />
          </div>
        )}
      </div>

      <div className="composer-wrap">
        <div
          className={`composer ${dragging ? 'dragging' : ''}`}
          onDragOver={(e) => {
            e.preventDefault()
            setDragging(true)
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault()
            setDragging(false)
            void attach(e.dataTransfer.files)
          }}
        >
          {pending.length > 0 && (
            <div className="attachments">
              {pending.map((img, i) => (
                <div className="attachment" key={i} title={img.filename}>
                  <img src={img.data} alt={img.filename} />
                  <button
                    className="attachment-remove"
                    onClick={() => removePending(i)}
                    title="Remover"
                    disabled={streaming}
                  >
                    ×
                  </button>
                </div>
              ))}
            </div>
          )}

          <div className="composer-row">
            <input
              ref={fileRef}
              type="file"
              accept={SUPPORTED_IMAGE_TYPES.join(',')}
              multiple
              hidden
              onChange={(e) => {
                void attach(e.target.files)
                e.target.value = ''
              }}
            />
            <button
              className="attach-btn"
              onClick={() => fileRef.current?.click()}
              disabled={streaming || pending.length >= MAX_IMAGES_PER_MESSAGE}
              title="Anexar print (ou cole com Ctrl+V)"
            >
              📎
            </button>

            <button
              className={`mode-btn ${mode === 'economy' ? 'on' : ''}`}
              onClick={toggleMode}
              disabled={streaming}
              aria-pressed={mode === 'economy'}
              title={
                mode === 'economy'
                  ? 'Modo economico ligado: resposta curta, menos contexto, menos tokens. Clique para voltar ao completo.'
                  : 'Modo completo: resposta explicada, contexto largo. Clique para economizar tokens.'
              }
            >
              <span className="mode-dot" />
              {mode === 'economy' ? 'Econômico' : 'Completo'}
            </button>

            <textarea
              ref={textareaRef}
              rows={1}
              value={input}
              placeholder={`Pergunte sobre ${module.name} — ou cole um print com Ctrl+V`}
              onChange={(e) => {
                setInput(e.target.value)
                autoGrow()
              }}
              onPaste={(e) => {
                const files = Array.from(e.clipboardData.files)
                if (files.length > 0) {
                  e.preventDefault()
                  void attach(files)
                }
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  void send(input)
                }
              }}
            />

            {streaming ? (
              <button className="send stop" onClick={stop} title="Parar">
                ■
              </button>
            ) : (
              <button
                className="send"
                disabled={!canSend}
                onClick={() => void send(input)}
                title="Enviar"
              >
                ↑
              </button>
            )}
          </div>
        </div>
        <div className="hint">
          {mode === 'economy'
            ? 'Modo econômico: respostas curtas e diretas, com menos contexto — a fonte continua citada'
            : 'Enter envia · Shift+Enter quebra linha · prints vao só nesta pergunta, não entram na base'}
        </div>
      </div>
    </>
  )
}

function dedupe(sources: Source[]): string[] {
  return [...new Set(sources.map((s) => s.source))]
}

function shortName(path: string): string {
  const parts = path.split('/')
  return parts[parts.length - 1] ?? path
}
