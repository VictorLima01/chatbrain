import { useEffect, useState } from 'react'
import {
  adminApi,
  api,
  type Access,
  type IngestReport,
  type KnowledgeDocument,
  type StudyModule,
} from '../api'

type Feedback = { kind: 'success' | 'error' | 'info'; text: string } | null

/**
 * Area do administrador.
 *
 * Duas coisas moram aqui: criar e editar modulos, e alimentar o modulo escolhido
 * com material. Toda chamada vai para /api/admin — e o backend que decide se a
 * alcada permite, nao esta tela.
 */
export function AdminPanel({
  modules,
  activeSlug,
  access,
  onChanged,
}: {
  modules: StudyModule[]
  activeSlug: string | null
  access: Access | null
  onChanged: () => Promise<void>
}) {
  const [target, setTarget] = useState<string | null>(activeSlug)
  const [busy, setBusy] = useState<string | null>(null)
  const [feedback, setFeedback] = useState<Feedback>(null)
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([])

  // formulario de novo modulo
  const [newName, setNewName] = useState('')
  const [newDescription, setNewDescription] = useState('')
  const [newPersona, setNewPersona] = useState('')

  // material
  const [file, setFile] = useState<File | null>(null)
  const [textTitle, setTextTitle] = useState('')
  const [textLevel, setTextLevel] = useState('')
  const [textContent, setTextContent] = useState('')

  const targetModule = modules.find((m) => m.slug === target) ?? null

  useEffect(() => {
    if (target === null || modules.some((m) => m.slug === target)) return
    setTarget(modules[0]?.slug ?? null)
  }, [modules, target])

  useEffect(() => {
    if (!target) {
      setDocuments([])
      return
    }
    let cancelled = false
    api
      .knowledge(target)
      .then((data) => !cancelled && setDocuments(data.documents))
      .catch(() => !cancelled && setDocuments([]))
    return () => {
      cancelled = true
    }
  }, [target, busy])

  const run = async (key: string, action: () => Promise<void>) => {
    setBusy(key)
    setFeedback(null)
    try {
      await action()
    } catch (e) {
      setFeedback({ kind: 'error', text: e instanceof Error ? e.message : String(e) })
    } finally {
      setBusy(null)
    }
  }

  const report = async (r: IngestReport) => {
    setFeedback({
      kind: r.failed > 0 ? 'error' : 'success',
      text:
        `${r.indexed} indexado(s), ${r.skipped} sem mudanca, ${r.failed} falha(s). ` +
        `Modulo: ${r.totalDocuments} documentos / ${r.totalChunks} trechos.` +
        (r.errors.length ? ` Erros: ${r.errors.slice(0, 3).join(' | ')}` : ''),
    })
    await onChanged()
  }

  return (
    <div className="content">
      {access && !access.authEnabled && (
        <div className="alert info">
          <strong>Sem autenticacao.</strong> A alcada de administrador esta liberada para quem
          alcanca a aplicacao — util agora, inaceitavel quando ela estiver publica. O login entra em
          <code> AccessResolver</code>, no backend.
        </div>
      )}

      {feedback && <div className={`alert ${feedback.kind}`}>{feedback.text}</div>}

      {/* ------------------------------------------------------------ modulos */}
      <div className="panel-section">
        <h3>Modulos de estudo</h3>
        <p>
          Cada modulo e um conjunto fechado de material. Uma pergunta feita num modulo so consulta os
          documentos dele — o material de Cloudability nao aparece num modulo novo, e vice-versa.
        </p>

        <div className="doc-list" style={{ marginBottom: 14 }}>
          {modules.map((m) => (
            <div className="doc" key={m.slug}>
              <span className="doc-kind">{m.builtIn ? 'fabrica' : 'proprio'}</span>
              <div className="doc-main">
                <div className="doc-title">{m.name}</div>
                <div className="doc-meta">
                  <code>{m.slug}</code> · {m.documentCount} documento(s) · {m.chunkCount} trechos
                  {m.description ? ` · ${m.description}` : ''}
                </div>
              </div>
              <button
                className="btn"
                style={{ padding: '5px 10px', fontSize: 12 }}
                disabled={busy !== null || m.slug === target}
                onClick={() => setTarget(m.slug)}
              >
                {m.slug === target ? 'selecionado' : 'gerenciar'}
              </button>
              <button
                className="btn"
                style={{ padding: '5px 10px', fontSize: 12 }}
                disabled={busy !== null || m.builtIn}
                title={
                  m.builtIn
                    ? 'Modulo de fabrica nao pode ser removido'
                    : 'Remove o modulo, seu material e suas conversas'
                }
                onClick={() =>
                  run(`del-mod-${m.slug}`, async () => {
                    if (
                      !confirm(
                        `Remover o modulo "${m.name}"? Os documentos, os arquivos guardados e as ` +
                          'conversas dele serao apagados.',
                      )
                    ) {
                      return
                    }
                    await adminApi.deleteModule(m.slug)
                    setFeedback({ kind: 'success', text: `Modulo "${m.name}" removido.` })
                    await onChanged()
                  })
                }
              >
                remover
              </button>
            </div>
          ))}
        </div>

        <div className="card">
          <h4 style={{ margin: '0 0 12px', fontSize: 13 }}>Criar modulo</h4>
          <div className="field">
            <label htmlFor="mod-name">Nome</label>
            <input
              id="mod-name"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder="Ex.: FinOps para Kubernetes"
            />
          </div>
          <div className="field">
            <label htmlFor="mod-desc">Descricao</label>
            <input
              id="mod-desc"
              value={newDescription}
              onChange={(e) => setNewDescription(e.target.value)}
              placeholder="Uma linha sobre o que este modulo cobre"
            />
          </div>
          <div className="field">
            <label htmlFor="mod-persona">
              Persona do tutor <span style={{ color: 'var(--text-faint)' }}>(opcional)</span>
            </label>
            <textarea
              id="mod-persona"
              value={newPersona}
              onChange={(e) => setNewPersona(e.target.value)}
              style={{ minHeight: 80 }}
              placeholder="Ex.: Voce e um instrutor de FinOps focado em cargas em Kubernetes..."
            />
            <span style={{ fontSize: 11.5, color: 'var(--text-faint)' }}>
              Define so o papel do tutor. As regras de citar a fonte e de nao sair do material do
              modulo sao do sistema e valem sempre.
            </span>
          </div>
          <button
            className="btn primary"
            disabled={busy !== null || !newName.trim()}
            onClick={() =>
              run('create-mod', async () => {
                const created = await adminApi.createModule({
                  name: newName.trim(),
                  description: newDescription.trim() || undefined,
                  persona: newPersona.trim() || undefined,
                })
                setNewName('')
                setNewDescription('')
                setNewPersona('')
                setTarget(created.slug)
                setFeedback({
                  kind: 'success',
                  text: `Modulo "${created.name}" criado. Envie material para ele abaixo.`,
                })
                await onChanged()
              })
            }
          >
            {busy === 'create-mod' ? (
              <>
                <span className="spinner" /> Criando...
              </>
            ) : (
              'Criar modulo'
            )}
          </button>
        </div>
      </div>

      {/* ----------------------------------------------------------- material */}
      {targetModule && (
        <>
          <div className="panel-section">
            <h3>Material de {targetModule.name}</h3>
            <p>
              Os arquivos ficam no S3, em <code>modules/{targetModule.slug}/</code>. A sincronizacao
              varre essa pasta e indexa o que mudou; envios manuais vao para a mesma pasta.
            </p>

            <div className="btn-row">
              <button
                className="btn primary"
                disabled={busy !== null}
                onClick={() =>
                  run('sync', async () => report(await adminApi.sync(targetModule.slug, false)))
                }
              >
                {busy === 'sync' ? (
                  <>
                    <span className="spinner" /> Indexando...
                  </>
                ) : (
                  'Sincronizar material'
                )}
              </button>
              <button
                className="btn"
                disabled={busy !== null}
                onClick={() =>
                  run('force', async () => report(await adminApi.sync(targetModule.slug, true)))
                }
              >
                {busy === 'force' ? (
                  <>
                    <span className="spinner" /> Reindexando...
                  </>
                ) : (
                  'Reindexar tudo'
                )}
              </button>
            </div>
            <p style={{ fontSize: 12, color: 'var(--text-faint)', marginTop: 8 }}>
              A sincronizacao pula arquivos sem alteracao (comparacao por checksum). A primeira
              execucao demora — sao muitas transcricoes e PDFs.
            </p>
          </div>

          <div className="panel-section">
            <h3>Enviar arquivo</h3>
            <p>Aceita PDF, VTT, PPTX, MD, TXT e imagens (PNG/JPG — transcritas pela visao do Claude).</p>
            <div className="card">
              <input
                type="file"
                accept=".pdf,.vtt,.pptx,.md,.txt,.png,.jpg,.jpeg"
                style={{ marginBottom: 12, color: 'var(--text-dim)' }}
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
              <button
                className="btn primary"
                disabled={busy !== null || file === null}
                onClick={() =>
                  run('upload', async () => {
                    if (!file) return
                    await report(
                      await adminApi.uploadFile(targetModule.slug, file, file.name, 'Enviado manualmente'),
                    )
                    setFile(null)
                  })
                }
              >
                {busy === 'upload' ? (
                  <>
                    <span className="spinner" /> Enviando...
                  </>
                ) : (
                  'Enviar e indexar'
                )}
              </button>
            </div>
          </div>

          <div className="panel-section">
            <h3>Colar texto</h3>
            <p>Para anotacoes, questoes soltas ou trechos que voce quer que o chat conheca.</p>
            <div className="card">
              <div className="field">
                <label htmlFor="kb-title">Titulo</label>
                <input
                  id="kb-title"
                  value={textTitle}
                  onChange={(e) => setTextTitle(e.target.value)}
                  placeholder="Ex.: Pegadinhas de Commitment"
                />
              </div>
              <div className="field">
                <label htmlFor="kb-level">Categoria</label>
                <input
                  id="kb-level"
                  value={textLevel}
                  onChange={(e) => setTextLevel(e.target.value)}
                  placeholder="Ex.: L4 / Optimization Features"
                />
              </div>
              <div className="field">
                <label htmlFor="kb-content">Conteudo</label>
                <textarea
                  id="kb-content"
                  value={textContent}
                  onChange={(e) => setTextContent(e.target.value)}
                  placeholder="Cole aqui o texto..."
                />
              </div>
              <button
                className="btn primary"
                disabled={busy !== null || !textTitle.trim() || !textContent.trim()}
                onClick={() =>
                  run('text', async () => {
                    await report(
                      await adminApi.addText(
                        targetModule.slug,
                        textTitle.trim(),
                        textContent,
                        textLevel.trim(),
                      ),
                    )
                    setTextTitle('')
                    setTextContent('')
                  })
                }
              >
                {busy === 'text' ? (
                  <>
                    <span className="spinner" /> Salvando...
                  </>
                ) : (
                  'Adicionar a base'
                )}
              </button>
            </div>
          </div>

          <div className="panel-section">
            <h3>Documentos indexados ({documents.length})</h3>
            <div className="doc-list">
              {documents.map((doc) => (
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
                  <button
                    className="btn"
                    style={{ padding: '5px 10px', fontSize: 12 }}
                    disabled={busy !== null}
                    onClick={() =>
                      run(`del-${doc.id}`, async () => {
                        await adminApi.deleteDocument(doc.id)
                        await onChanged()
                      })
                    }
                  >
                    remover
                  </button>
                </div>
              ))}
              {documents.length === 0 && (
                <div className="alert info" style={{ marginBottom: 0 }}>
                  Nenhum documento indexado neste modulo. Envie arquivos ou clique em{' '}
                  <strong>Sincronizar material</strong>.
                </div>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  )
}
