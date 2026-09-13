CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- Base de conhecimento
-- ---------------------------------------------------------------------------
CREATE TABLE kb_document (
    id           BIGSERIAL PRIMARY KEY,
    title        TEXT        NOT NULL,
    source       TEXT        NOT NULL,
    kind         TEXT        NOT NULL,
    level        TEXT,
    checksum     TEXT        NOT NULL,
    char_count   INTEGER     NOT NULL DEFAULT 0,
    chunk_count  INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_kb_document_source UNIQUE (source)
);

CREATE TABLE kb_chunk (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT      NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
    ordinal      INTEGER     NOT NULL,
    content      TEXT        NOT NULL,
    embedding    vector(384),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_kb_chunk_document ON kb_chunk (document_id);

-- Busca vetorial por similaridade de cosseno.
CREATE INDEX idx_kb_chunk_embedding
    ON kb_chunk USING hnsw (embedding vector_cosine_ops);

-- Busca lexical, usada em conjunto com a vetorial (busca hibrida).
CREATE INDEX idx_kb_chunk_fts
    ON kb_chunk USING gin (to_tsvector('portuguese', content));

-- ---------------------------------------------------------------------------
-- Conversas
-- ---------------------------------------------------------------------------
CREATE TABLE conversation (
    id         BIGSERIAL PRIMARY KEY,
    title      TEXT        NOT NULL DEFAULT 'Nova conversa',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    role            TEXT        NOT NULL,
    content         TEXT        NOT NULL,
    sources         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_message_conversation ON chat_message (conversation_id, id);
