import type { Access, Status, StudyModule } from '../api'

export function AboutPanel({
  status,
  modules,
  access,
}: {
  status: Status | null
  modules: StudyModule[]
  access: Access | null
}) {
  return (
    <div className="content">
      <div className="panel-section">
        <h3>Sobre este projeto</h3>
        <p>
          Assistente de estudos para a certificacao <strong>IBM Cloudability</strong> — e para
          qualquer outro assunto que um administrador queira ensinar. As respostas sao geradas pelo
          Claude a partir do material enviado (transcricoes, PDFs, apresentacoes, anotacoes) usando
          RAG (busca no material antes de responder), para que o modelo cite a fonte em vez de
          inventar.
        </p>
      </div>

      <div className="panel-section">
        <h3>Modulos</h3>
        <p>
          Cada modulo e um conjunto fechado de material. A busca que alimenta uma resposta e
          filtrada pelo modulo da conversa, entao um modulo novo nunca enxerga o material de
          Cloudability — nem o contrario.
        </p>
        <div className="doc-list">
          {modules.map((m) => (
            <div className="doc" key={m.slug}>
              <span className="doc-kind">{m.builtIn ? 'fabrica' : 'proprio'}</span>
              <div className="doc-main">
                <div className="doc-title">{m.name}</div>
                <div className="doc-meta">
                  {m.documentCount} documento(s) · {m.chunkCount} trechos
                  {m.description ? ` · ${m.description}` : ''}
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="panel-section">
        <h3>Como funciona</h3>
        <div className="card">
          <ol style={{ margin: 0, paddingLeft: 20, color: 'var(--text-dim)' }}>
            <li>O material vive no S3, numa pasta por modulo.</li>
            <li>E lido e convertido em texto (VTT, PDF, PPTX, MD e imagens).</li>
            <li>O texto e quebrado em trechos com sobreposicao, respeitando frases.</li>
            <li>Cada trecho vira um vetor, guardado no Postgres com a extensao pgvector.</li>
            <li>
              Sua pergunta tambem vira vetor. A busca e <strong>hibrida</strong>: similaridade
              semantica + busca lexical, fundidas por Reciprocal Rank Fusion — o lexical resgata
              siglas e nomes de tela que o vetor perde. Ela e sempre restrita ao modulo da conversa.
            </li>
            <li>Os trechos mais relevantes vao no prompt, e o Claude responde citando as fontes.</li>
          </ol>
        </div>
      </div>

      <div className="panel-section">
        <h3>Alcadas</h3>
        <div className="card" style={{ fontSize: 13.5, color: 'var(--text-dim)' }}>
          <p style={{ margin: '0 0 10px' }}>
            <strong style={{ color: 'var(--text)' }}>Estudante</strong> — conversa com os modulos e
            consulta a base.
            <br />
            <strong style={{ color: 'var(--text)' }}>Administrador</strong> — tambem cria modulos e
            envia o material que os alimenta.
          </p>
          {access && !access.authEnabled && (
            <div className="alert info" style={{ marginBottom: 0 }}>
              <strong>A autenticacao ainda nao foi implementada.</strong> Todo mundo que alcanca a
              aplicacao entra como <code>{access.level}</code>. As rotas administrativas ja estao
              separadas sob <code>/api/admin</code> e protegidas por um interceptor — quando o login
              existir, basta ele passar a identificar o usuario.
            </div>
          )}
        </div>
      </div>

      <div className="panel-section">
        <h3>Configuracao atual</h3>
        {status ? (
          <>
            {!status.anthropicConfigured && (
              <div className="alert error">
                <strong>ANTHROPIC_API_KEY nao configurada.</strong> Defina a variavel de ambiente no
                backend e reinicie — sem ela o chat nao responde.
              </div>
            )}
            <div className="stat-grid">
              <div className="stat">
                <div className="stat-value">{status.totalDocuments}</div>
                <div className="stat-label">Documentos (todos os modulos)</div>
              </div>
              <div className="stat">
                <div className="stat-value">{status.totalChunks}</div>
                <div className="stat-label">Trechos indexados</div>
              </div>
              <div className="stat">
                <div className="stat-value">{status.topK}</div>
                <div className="stat-label">Trechos por resposta</div>
              </div>
              <div className="stat">
                <div className="stat-value">{status.embeddingDimensions}</div>
                <div className="stat-label">Dimensoes do vetor</div>
              </div>
            </div>
            <div className="card" style={{ marginTop: 12, fontSize: 13.5 }}>
              <div style={{ display: 'grid', gap: 6 }}>
                <Row label="Modelo" value={status.model} />
                <Row label="Effort" value={status.effort} />
                <Row label="Motor de embeddings" value={status.embeddingProvider} />
                <Row label="Armazenamento do material" value={status.materialProvider} />
                <Row
                  label="Chave da Anthropic"
                  value={status.anthropicConfigured ? 'configurada' : 'ausente'}
                />
              </div>
            </div>
          </>
        ) : (
          <div className="card">
            <span className="spinner" /> carregando...
          </div>
        )}
      </div>

      <div className="panel-section">
        <h3>Stack</h3>
        <div className="card" style={{ fontSize: 13.5, color: 'var(--text-dim)' }}>
          React + Vite no front · Spring Boot (Java 21) no back · Postgres + pgvector como base
          vetorial · S3 (LocalStack no ambiente local) para o material · embeddings multilingues
          locais via container · Claude pela API da Anthropic.
        </div>
      </div>
    </div>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
      <span style={{ color: 'var(--text-faint)' }}>{label}</span>
      <code>{value}</code>
    </div>
  )
}
