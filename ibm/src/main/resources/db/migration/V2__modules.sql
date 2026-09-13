-- ---------------------------------------------------------------------------
-- Modulos de estudo
--
-- Um modulo e um recorte fechado de material: as buscas de um modulo nunca
-- alcancam os documentos de outro. A coluna module_id em kb_document e o que
-- torna esse isolamento uma garantia do banco, e nao uma convencao do codigo.
-- ---------------------------------------------------------------------------
CREATE TABLE study_module (
    id          BIGSERIAL PRIMARY KEY,
    slug        TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    description TEXT,
    -- Papel do tutor neste modulo. As regras de fundamentacao ficam no dominio;
    -- aqui vai so o "quem voce e", que o administrador escreve.
    persona     TEXT,
    -- Modulo de fabrica: a interface nao oferece o botao de remover.
    built_in    BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_study_module_slug UNIQUE (slug)
);

-- Modulo padrao: o material de Cloudability que ja estava indexado.
INSERT INTO study_module (slug, name, description, persona, built_in)
VALUES (
    'cloudability',
    'IBM Cloudability',
    'Material oficial da certificacao IBM Cloudability (Apptio): L1, L2 e L4.',
    'Voce e um tutor especialista em IBM Cloudability (plataforma da familia Apptio), '
        || 'ajudando a usuaria a estudar para a certificacao da IBM. Mantenha os termos '
        || 'tecnicos oficiais em ingles quando forem o padrao do produto (rightsizing, '
        || 'showback, chargeback, view, widget, amortized cost, Savings Plan).',
    true
);

-- ---------------------------------------------------------------------------
-- Vinculo do material e das conversas ao modulo
--
-- Adicionado como NULL, preenchido com o modulo padrao e so entao marcado como
-- NOT NULL: e o caminho que funciona tanto num banco vazio quanto num que ja
-- tem a base de Cloudability indexada.
-- ---------------------------------------------------------------------------
ALTER TABLE kb_document ADD COLUMN module_id BIGINT REFERENCES study_module (id) ON DELETE CASCADE;
ALTER TABLE conversation ADD COLUMN module_id BIGINT REFERENCES study_module (id) ON DELETE CASCADE;

UPDATE kb_document  SET module_id = (SELECT id FROM study_module WHERE slug = 'cloudability');
UPDATE conversation SET module_id = (SELECT id FROM study_module WHERE slug = 'cloudability');

ALTER TABLE kb_document  ALTER COLUMN module_id SET NOT NULL;
ALTER TABLE conversation ALTER COLUMN module_id SET NOT NULL;

CREATE INDEX idx_kb_document_module  ON kb_document (module_id);
CREATE INDEX idx_conversation_module ON conversation (module_id, updated_at DESC);

-- ---------------------------------------------------------------------------
-- Limpeza do material indexado a partir do disco
--
-- O material passou a viver no S3 e o `source` de cada documento agora e a
-- chave do objeto (modules/<slug>/...), nao mais o caminho relativo do projeto.
-- Os registros antigos apontam para um caminho que ninguem mais le: a proxima
-- sincronizacao criaria duplicatas ao inves de atualiza-los. Some com eles e
-- deixe a base ser reconstruida do bucket (os trechos vao junto, por cascata).
-- ---------------------------------------------------------------------------
DELETE FROM kb_document WHERE source NOT LIKE 'modules/%';
