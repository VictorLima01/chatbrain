-- ---------------------------------------------------------------------------
-- Modulo de fabrica: IBM Apptio Costing Standard
--
-- Material do curso "Apptio Costing Standard Practitioner Advanced": 13 secoes
-- numeradas na ordem de estudo, com transcricoes de video (.vtt) e slides
-- (.PDF), mais a base curada dos agentes especialistas em `agents/`.
--
-- O material em si nao vem por aqui -- ele vive no S3, em
-- modules/apptio-costing/, e entra na base pela sincronizacao. Esta migracao
-- so cria o recorte: sem o modulo no banco, nao ha onde o material pousar.
--
-- O upsert existe porque o modulo pode ter sido criado pela tela de
-- administracao antes desta migracao chegar ao banco. Nesse caso ele e
-- promovido a modulo de fabrica em vez de o deploy quebrar no UNIQUE, e a
-- persona escrita a mao e preservada (COALESCE): o administrador que a ajustou
-- nao perde o ajuste.
-- ---------------------------------------------------------------------------
INSERT INTO study_module (slug, name, description, persona, built_in)
VALUES (
    'apptio-costing',
    'IBM Apptio Costing Standard',
    'Curso IBM Apptio Costing Standard Practitioner Advanced: modelo ATUM, model flow, '
        || 'Datalink, ADM, relatorios de servicos, TBM Studio, administracao, gestao de '
        || 'dados, solution packs e Technology Emissions Management.',
    'Voce e um tutor especialista em IBM Apptio Costing Standard, ajudando a usuaria a '
        || 'se preparar para a certificacao Practitioner Advanced (aprovacao exige 75%). '
        || 'Mantenha os termos oficiais do produto em ingles (ATUM, cost pools, resource '
        || 'towers, cost source, Datalink, DataDrop, ADM, TBM Studio, Editable Tables, '
        || 'allocation granularity, object identifiers, branching, check-in/check-out, '
        || 'solution packs, run vs. change, addressable spend, showback, chargeback). '
        || 'Boa parte do material vem de transcricao automatica de video e tem erros '
        || 'previsiveis de reconhecimento de fala: "Aptio", "Apio" e "Optio" sao Apptio; '
        || '"Adam" e "Atom" sao ATUM; "TDM Studio" e TBM Studio; "DMRV" e o processo de '
        || 'validacao. Leia o trecho pelo sentido e cite o termo correto na resposta, sem '
        || 'tratar o erro de transcricao como se fosse um conceito do produto.',
    true
)
ON CONFLICT (slug) DO UPDATE SET
    name        = EXCLUDED.name,
    description = EXCLUDED.description,
    persona     = COALESCE(study_module.persona, EXCLUDED.persona),
    built_in    = true;
