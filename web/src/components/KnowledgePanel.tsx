import { useEffect, useState } from 'react'
import { api, type KnowledgeList, type StudyModule } from '../api'

/**
 * Base de conhecimento do modulo ativo — somente leitura.
 *
 * Alimentar a base e alcada de administrador e vive no AdminPanel. Esta tela
 * responde "o que o chat consegue consultar aqui?", que e uma pergunta legitima
 * de quem so estuda.
 */
export function KnowledgePanel({
  module,
  isAdmin,
  onGoToAdmin,
}: {
  module: StudyModule
  isAdmin: boolean
  onGoToAdmin: () => void
}) {
  const [data, setData] = useState<KnowledgeList | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setData(null)
    setError(null)
    api
      .knowledge(module.slug)
      .then((d) => !cancelled && setData(d))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    return () => {
      cancelled = true
    }
  }, [module.slug])

  return (
    <div className="content">
      <div className="panel-section">
        <h3>Base de {module.name}</h3>
        <p>
          Tudo o que o chat consegue consultar <strong>neste modulo</strong>. Perguntas feitas aqui
          nao alcancam o material dos outros modulos.
        </p>

        {error && <div className="alert error">{error}</div>}

        <div className="stat-grid" style={{ marginBottom: 14 }}>
          <div className="stat">
            <div className="stat-value">{data?.totalDocuments ?? '—'}</div>
            <div className="stat-label">Documentos</div>
          </div>
          <div className="stat">
            <div className="stat-value">{data?.totalChunks ?? '—'}</div>
            <div className="stat-label">Trechos</div>
          </div>
        </div>

        {isAdmin ? (
          <div className="btn-row">
            <button className="btn" onClick={onGoToAdmin}>
              Enviar material ou sincronizar
            </button>
          </div>
        ) : (
          <p style={{ fontSize: 12, color: 'var(--text-faint)' }}>
            Enviar material e sincronizar sao acoes de administrador.
          </p>
        )}
      </div>

      <div className="panel-section">
        <h3>Documentos indexados ({data?.documents.length ?? 0})</h3>
        <div className="doc-list">
          {data?.documents.map((doc) => (
            <div className="doc" key={doc.id}>
              <span className="doc-kind">{doc.kind}</span>
              <div className="doc-main">
                <div className="doc-title" title={doc.source}>
                  {doc.title}
                </div>
                <div className="doc-meta">
                  {doc.level ?? 'Geral'} · {doc.chunkCount} trechos ·{' '}
                  {doc.charCount.toLocaleString('pt-BR')} caracteres
                </div>
              </div>
            </div>
          ))}
          {data && data.documents.length === 0 && (
            <div className="alert info">
              Nenhum documento indexado neste modulo ainda.
              {isAdmin && ' Use a area de administracao para enviar o material.'}
            </div>
          )}
          {!data && !error && (
            <div className="card">
              <span className="spinner" /> carregando...
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
