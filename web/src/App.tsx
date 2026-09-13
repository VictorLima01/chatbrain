import { useCallback, useEffect, useState } from 'react'
import { api, type Access, type Conversation, type Status, type StudyModule } from './api'
import { AboutPanel } from './components/AboutPanel'
import { AdminPanel } from './components/AdminPanel'
import { ChatView } from './components/ChatView'
import { KnowledgePanel } from './components/KnowledgePanel'

type View = 'chat' | 'knowledge' | 'admin' | 'about'

const LAST_MODULE_KEY = 'cloudability-chat:module'

export default function App() {
  const [view, setView] = useState<View>('chat')
  const [modules, setModules] = useState<StudyModule[]>([])
  const [moduleSlug, setModuleSlug] = useState<string | null>(null)
  const [access, setAccess] = useState<Access | null>(null)
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [activeId, setActiveId] = useState<number | null>(null)
  const [status, setStatus] = useState<Status | null>(null)

  /**
   * Identidade da tela de chat.
   *
   * A `key` do ChatView vem daqui — e nao do id da conversa. Essa distincao e o
   * que conserta o bug do "primeiro envio nao faz nada": quando a primeira
   * mensagem criava a conversa, o id mudava de null para um numero, a key mudava
   * junto e o React desmontava o ChatView no meio do streaming. A resposta ia
   * embora com ele. So trocamos a key quando a intencao e mesmo reiniciar a tela:
   * nova conversa, abrir outra conversa, trocar de modulo.
   */
  const [chatKey, setChatKey] = useState(0)

  const activeModule = modules.find((m) => m.slug === moduleSlug) ?? null
  const isAdmin = access?.level === 'admin'

  const loadModules = useCallback(() => api.modules().then(setModules).catch(() => undefined), [])

  const loadConversations = useCallback(() => {
    if (!moduleSlug) return
    api.listConversations(moduleSlug).then(setConversations).catch(() => setConversations([]))
  }, [moduleSlug])

  const loadStatus = useCallback(() => {
    api.status().then(setStatus).catch(() => setStatus(null))
  }, [])

  // Modulos e alcada primeiro: sem saber qual modulo esta ativo nao da para
  // carregar conversa nenhuma.
  useEffect(() => {
    void api.access().then(setAccess).catch(() => setAccess(null))
    void loadModules().then(() => undefined)
    loadStatus()
  }, [loadModules, loadStatus])

  // Escolhe o modulo ativo na primeira carga e reescolhe se o atual sumir —
  // o administrador pode ter acabado de remover justamente o que estava aberto.
  useEffect(() => {
    if (modules.length === 0) return
    if (moduleSlug !== null && modules.some((m) => m.slug === moduleSlug)) return
    const remembered = localStorage.getItem(LAST_MODULE_KEY)
    const chosen = modules.find((m) => m.slug === remembered) ?? modules[0]
    setModuleSlug(chosen.slug)
    setActiveId(null)
    setChatKey((k) => k + 1)
  }, [modules, moduleSlug])

  useEffect(() => {
    loadConversations()
  }, [loadConversations])

  const selectModule = (slug: string) => {
    if (slug === moduleSlug) return
    localStorage.setItem(LAST_MODULE_KEY, slug)
    setModuleSlug(slug)
    setActiveId(null)
    setConversations([])
    setChatKey((k) => k + 1)
    setView('chat')
  }

  const newChat = () => {
    setActiveId(null)
    setChatKey((k) => k + 1)
    setView('chat')
  }

  const openConversation = (id: number) => {
    setActiveId(id)
    setChatKey((k) => k + 1)
    setView('chat')
  }

  const removeConversation = async (id: number) => {
    await api.deleteConversation(id)
    if (activeId === id) newChat()
    loadConversations()
  }

  const headerTitle =
    view === 'chat'
      ? conversations.find((c) => c.id === activeId)?.title ?? 'Nova conversa'
      : view === 'knowledge'
        ? 'Base de conhecimento'
        : view === 'admin'
          ? 'Administracao'
          : 'Sobre'

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark" />
          <div className="brand-text">
            <strong>Cloudability Chat</strong>
            <span>Assistente de estudos IBM</span>
          </div>
        </div>

        <div className="module-picker">
          <label htmlFor="module-select">Modulo</label>
          <select
            id="module-select"
            value={moduleSlug ?? ''}
            onChange={(e) => selectModule(e.target.value)}
            disabled={modules.length === 0}
          >
            {modules.length === 0 && <option value="">carregando...</option>}
            {modules.map((m) => (
              <option key={m.slug} value={m.slug}>
                {m.name}
              </option>
            ))}
          </select>
          {activeModule && (
            <span className="module-meta">
              {activeModule.documentCount} documento(s) · {activeModule.chunkCount} trechos
            </span>
          )}
        </div>

        <button className="new-chat" onClick={newChat}>
          <span style={{ fontSize: 16, lineHeight: 1 }}>+</span> Nova conversa
        </button>

        <nav className="nav">
          <button
            className={`nav-item ${view === 'knowledge' ? 'active' : ''}`}
            onClick={() => setView('knowledge')}
          >
            <span>▤</span> Base de conhecimento
          </button>
          {isAdmin && (
            <button
              className={`nav-item ${view === 'admin' ? 'active' : ''}`}
              onClick={() => setView('admin')}
            >
              <span>⚙</span> Administracao
            </button>
          )}
          <button
            className={`nav-item ${view === 'about' ? 'active' : ''}`}
            onClick={() => setView('about')}
          >
            <span>◈</span> Sobre
          </button>
        </nav>

        <div className="section-label">Conversas</div>
        <div className="conversations">
          {conversations.map((conversation) => (
            <div
              className={`conversation ${activeId === conversation.id && view === 'chat' ? 'active' : ''}`}
              key={conversation.id}
            >
              <button
                className="conversation-title"
                onClick={() => openConversation(conversation.id)}
                title={conversation.title}
              >
                {conversation.title}
              </button>
              <button
                className="conversation-delete"
                title="Apagar conversa"
                onClick={() => void removeConversation(conversation.id)}
              >
                ×
              </button>
            </div>
          ))}
          {conversations.length === 0 && (
            <div style={{ padding: '6px 12px', fontSize: 12.5, color: 'var(--text-faint)' }}>
              Nenhuma conversa neste modulo ainda.
            </div>
          )}
        </div>
      </aside>

      <main className="main">
        <header className="main-header">
          <h1>{headerTitle}</h1>
          <div className="header-badges">
            {activeModule && <span className="badge">{activeModule.name}</span>}
            {status && (
              <span className={`badge ${status.anthropicConfigured ? 'ok' : 'warn'}`}>
                {status.anthropicConfigured
                  ? `${status.model} · ${status.totalChunks} trechos`
                  : 'ANTHROPIC_API_KEY ausente'}
              </span>
            )}
          </div>
        </header>

        {view === 'chat' &&
          (activeModule ? (
            <ChatView
              key={chatKey}
              module={activeModule}
              initialConversationId={activeId}
              onConversationCreated={(id) => {
                setActiveId(id)
                loadConversations()
              }}
              onTitleChanged={() => {
                loadConversations()
                void loadModules()
              }}
            />
          ) : (
            <div className="scroll">
              <div className="content">
                <div className="card">
                  <span className="spinner" /> carregando modulos...
                </div>
              </div>
            </div>
          ))}

        {view === 'knowledge' && activeModule && (
          <div className="scroll">
            <KnowledgePanel module={activeModule} isAdmin={isAdmin} onGoToAdmin={() => setView('admin')} />
          </div>
        )}

        {view === 'admin' && isAdmin && (
          <div className="scroll">
            <AdminPanel
              modules={modules}
              activeSlug={moduleSlug}
              access={access}
              onChanged={async () => {
                await loadModules()
                loadStatus()
              }}
            />
          </div>
        )}

        {view === 'about' && (
          <div className="scroll">
            <AboutPanel status={status} modules={modules} access={access} />
          </div>
        )}
      </main>
    </div>
  )
}
