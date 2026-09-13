# Documentação

Índice da documentação do **Cloudability Study Chat**.

| Documento | Para quando você quer... |
|---|---|
| [CONFIGURACAO-ANTHROPIC.md](CONFIGURACAO-ANTHROPIC.md) | obter a API key, entender a cobrança e os custos — **comece por aqui** |
| [ARQUITETURA.md](ARQUITETURA.md) | entender como as peças se encaixam e por que cada tecnologia foi escolhida |
| [ARQUITETURA-HEXAGONAL.md](ARQUITETURA-HEXAGONAL.md) | a organização do backend em ports & adapters, e por quê |
| [EXECUCAO-LOCAL.md](EXECUCAO-LOCAL.md) | subir o projeto na sua máquina (com ou sem Docker) |
| [BASE-DE-CONHECIMENTO.md](BASE-DE-CONHECIMENTO.md) | saber como o material vira base pesquisável e como adicionar conteúdo |
| [API.md](API.md) | consultar os endpoints do backend |
| [FASE-2-AWS.md](FASE-2-AWS.md) | **planejar** o deploy na AWS: comparar arquiteturas, entender custos e decisões |
| [FASE-2-EXECUCAO-AWS.md](FASE-2-EXECUCAO-AWS.md) | **executar** o deploy **sem domínio próprio**: Terraform, GitHub Actions, EC2 + S3 + CloudFront (`*.cloudfront.net`) e Neon |
| [FASE-2-PASSO-A-PASSO.md](FASE-2-PASSO-A-PASSO.md) | **executar** o deploy **com domínio próprio**: Terraform, GitHub Actions, EC2 + S3 + Caddy/Let's Encrypt e Neon |

> **Os dois últimos fazem a mesma coisa por caminhos diferentes — escolha um.** Se você vai comprar
> um domínio, use o `FASE-2-PASSO-A-PASSO.md`. Se quer HTTPS sem comprar nada, use o
> `FASE-2-EXECUCAO-AWS.md`. A comparação está na seção 1.1 do primeiro.

## Estrutura do repositório

```
Estudos_IBM/
├── ibm/                  backend — Spring Boot 4 · Java 21 (ports & adapters)
│   ├── src/main/java/com/chat/cloudability/ibm/
│   │   ├── domain/
│   │   │   ├── model/    entidades (módulo, conversa, documento, alçada)
│   │   │   ├── port/in/  casos de uso: perguntar, ingerir, gerir módulos
│   │   │   ├── port/out/ o que o domínio precisa: LLM, embeddings, repos, material
│   │   │   └── service/  interactors — a regra de negócio, sem framework
│   │   ├── adapter/in/web/       controllers, DTOs, alçadas
│   │   ├── adapter/out/anthropic/ Claude (respostas e visão)
│   │   ├── adapter/out/embedding/ TEI
│   │   ├── adapter/out/filesystem/ extração de conteúdo · material em disco
│   │   ├── adapter/out/persistence/ Postgres + pgvector
│   │   ├── adapter/out/s3/       material no S3
│   │   └── config/       montagem do hexágono, propriedades, CORS
│   ├── src/main/resources/db/migration/   migrações Flyway
│   └── Dockerfile
│
├── web/                  frontend — React · Vite · TypeScript
│   ├── src/
│   │   ├── components/   ChatView, KnowledgePanel, AdminPanel, AboutPanel, ThinkingBlob
│   │   ├── api.ts        cliente HTTP (api + adminApi) e leitor do stream SSE
│   │   └── App.tsx       layout, seletor de módulo e navegação
│   ├── Dockerfile
│   └── nginx.conf
│
├── docs/                 esta pasta — documentação do projeto
│
├── ingestao/             material de estudo do módulo `cloudability`
│   ├── L1/ L2/ L4/       transcrições, PDFs e apresentações
│   └── Perguntas.png     questões do quiz, lidas pela visão do Claude
│
├── docker-compose.yml    Postgres+pgvector · LocalStack (S3) · embeddings · api · web
├── .env.example          variáveis de ambiente
└── README.md             visão geral
```

> `ingestao/` é **conteúdo**, não código. Ele não é lido pelo backend: o serviço `material-seed` do
> compose publica essa pasta no S3 local, em `modules/cloudability/`, e é de lá que a ingestão lê. O
> mesmo vale para a base do agente especialista, em `.claude/agents/`.
>
> Material de outros módulos não passa por `ingestao/` — vai direto para `modules/<slug>/` no bucket,
> pela área de administração ou por `aws s3 sync`.
>
> A categorização por nível (`L1`, `L4 / Plan`…) não depende do caminho — procura o segmento
> `L<dígito>` em qualquer posição dentro do módulo.
