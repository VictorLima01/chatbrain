# Cloudability Study Chat

Chat com RAG sobre o material de estudo da certificação **IBM Cloudability** — e sobre qualquer
outro assunto que um administrador queira ensinar. As respostas são geradas pelo Claude a partir do
material enviado, com citação de fonte, em vez de resposta inventada.

O material é organizado em **módulos**: cada módulo é um conjunto fechado, e uma pergunta feita nele
nunca alcança o material de outro.

```
├── ibm/        backend   · Spring Boot 4 · Java 21 (ports & adapters)
├── web/        frontend  · React · Vite · TypeScript
├── docs/       documentação do projeto
├── ingestao/   material do módulo `cloudability` — publicado no S3 pelo compose
│   ├── L1/ L2/ L4/
│   └── Perguntas.png
├── ingestao-apptio-costing/   material do módulo `apptio-costing`
│   ├── 00_Visao_Geral_Curso/ ... 12_Technology_Emissions_Management/
│   ├── 13_Desafio_Burgubras/  desafio prático: guia, roteiro, dados de carga
│   └── agents/   base curada dos agentes do curso
└── scripts/    utilitários — ver `dicionario-de-dados.py`
```

Dois módulos vêm de fábrica:

| Módulo | Assunto |
|---|---|
| `cloudability` | Certificação IBM Cloudability (Apptio) — L1, L2 e L4 |
| `apptio-costing` | Curso IBM Apptio Costing Standard Practitioner Advanced — ATUM, model flow, Datalink, TBM Studio, solution packs, TEM |

## Começando

```bash
cp .env.example .env      # preencha ANTHROPIC_API_KEY
docker compose up --build
```

Acesse **http://localhost** → menu **Administração** → **Sincronizar material**.

O compose sobe um S3 local (LocalStack) e publica cada pasta de material na pasta do seu módulo —
`ingestao/` em `s3://cloudability-material/modules/cloudability/` e `ingestao-apptio-costing/` em
`.../modules/apptio-costing/` — antes do backend subir. É de lá que a sincronização lê, um módulo
por vez.

> Para rodar sem Docker, veja a Opção B em [docs/EXECUCAO-LOCAL.md](docs/EXECUCAO-LOCAL.md).

## Documentação

| Documento | Assunto |
|---|---|
| [docs/CONFIGURACAO-ANTHROPIC.md](docs/CONFIGURACAO-ANTHROPIC.md) | API key, cobrança e custos — **o plano Pro não serve aqui** |
| [docs/ARQUITETURA.md](docs/ARQUITETURA.md) | Como as peças se encaixam e por que cada escolha |
| [docs/ARQUITETURA-HEXAGONAL.md](docs/ARQUITETURA-HEXAGONAL.md) | Ports & adapters no backend, com a regra de dependência |
| [docs/EXECUCAO-LOCAL.md](docs/EXECUCAO-LOCAL.md) | Pré-requisitos, execução, variáveis, troubleshooting |
| [docs/BASE-DE-CONHECIMENTO.md](docs/BASE-DE-CONHECIMENTO.md) | Como o material vira base pesquisável |
| [docs/API.md](docs/API.md) | Referência dos endpoints |
| [docs/FASE-2-AWS.md](docs/FASE-2-AWS.md) | Deploy na AWS — comparação de arquiteturas e custos (o *porquê*) |
| [docs/FASE-2-PASSO-A-PASSO.md](docs/FASE-2-PASSO-A-PASSO.md) | Runbook da fase 2 **com domínio próprio** — Terraform, IAM Role, Neon, Caddy e GitHub Actions |
| [docs/FASE-2-EXECUCAO-AWS.md](docs/FASE-2-EXECUCAO-AWS.md) | Runbook da fase 2 **sem domínio** — mesmo objetivo, com CloudFront no lugar do Caddy |

## Em uma linha cada

- **Módulos isolados** — a busca filtra por `module_id` dentro de cada ramo da recuperação; material
  de um módulo não vaza para outro.
- **Alçadas** — `student` consulta, `admin` cria módulos e envia material. Tudo que escreve fica sob
  `/api/admin`.
- **Material no S3** — LocalStack no local, bucket real na AWS com IAM Role no EC2 (sem chave de
  acesso no servidor).
- **Busca híbrida** — vetorial + lexical, fundidas por Reciprocal Rank Fusion, porque o material é
  cheio de siglas e nomes de tela que embeddings puros perdem.
- **Embeddings locais** — a API da Anthropic não tem endpoint de embeddings; o Claude responde, um
  container gera os vetores.
- **Postgres + pgvector** — um banco só para vetores e dados relacionais.
- **Streaming SSE** — a resposta aparece token a token, com a bolinha animada enquanto o modelo pensa.

## Estado atual

| | |
|---|---|
| Backend | ✅ compila · 34 testes passando |
| Frontend | ✅ build limpo (TypeScript strict) |
| Execução ponta a ponta | ⚠️ não validada nesta máquina |
| Autenticação | ❌ não implementada — as alçadas existem e são verificadas, mas a identidade vem de configuração. **Resolver antes de publicar** |
