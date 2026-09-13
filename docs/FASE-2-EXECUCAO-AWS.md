# Fase 2 — Execução: subir na AWS com Terraform e GitHub Actions

> **O que esta doc é.** O passo a passo executável da fase 2: provisionar a infraestrutura com
> **Terraform**, automatizar o deploy com **GitHub Actions** e manter a conta **dentro do free
> tier** (ou muito perto dele). Tudo aqui foi escrito para ser copiado e rodado na ordem.
>
> **Relação com [FASE-2-AWS.md](FASE-2-AWS.md).** Aquela doc é o *planejamento* — compara
> arquiteturas e justifica escolhas. Esta é a *execução*, e diverge dela em três pontos
> deliberados, explicados na [seção 14](#14-o-que-muda-em-relação-à-fase-2-awsmd):
> **x86 em vez de Graviton**, **sem domínio próprio nem Caddy** (o TLS vem do CloudFront) e
> **Terraform em vez de cliques no console**.

---

## Índice

1. [Como ler esta doc](#1-como-ler-esta-doc)
2. [A arquitetura free tier, peça por peça](#2-a-arquitetura-free-tier-peça-por-peça)
3. [O que é grátis, o que não é, e onde o dinheiro vaza](#3-o-que-é-grátis-o-que-não-é-e-onde-o-dinheiro-vaza)
4. [A decisão a tomar antes de tudo: embeddings](#4-a-decisão-a-tomar-antes-de-tudo-embeddings)
5. [Pré-requisitos](#5-pré-requisitos)
6. [Passo 1 — Banco no Neon](#6-passo-1--banco-no-neon)
7. [Passo 2 — Entender o Terraform antes de escrever Terraform](#7-passo-2--entender-o-terraform-antes-de-escrever-terraform)
8. [Passo 3 — Bootstrap: o bucket do state](#8-passo-3--bootstrap-o-bucket-do-state)
9. [Passo 4 — Os arquivos do Terraform](#9-passo-4--os-arquivos-do-terraform)
10. [Passo 5 — `plan` e `apply`](#10-passo-5--plan-e-apply)
11. [Passo 6 — O primeiro deploy, na mão](#11-passo-6--o-primeiro-deploy-na-mão)
12. [Passo 7 — GitHub Actions](#12-passo-7--github-actions)
13. [Passo 8 — Validação fim a fim](#13-passo-8--validação-fim-a-fim)
14. [O que muda em relação à FASE-2-AWS.md](#14-o-que-muda-em-relação-à-fase-2-awsmd)
15. [Operação do dia a dia](#15-operação-do-dia-a-dia)
16. [Troubleshooting](#16-troubleshooting)
17. [Como desligar tudo sem deixar rastro cobrando](#17-como-desligar-tudo-sem-deixar-rastro-cobrando)

---

## 1. Como ler esta doc

São **oito passos em ordem**, e a ordem importa — cada um depende do anterior:

```
1. Neon ──────────► banco pronto e testado do seu laptop
2. conceitos ─────► você entende o que o Terraform vai fazer antes de rodá-lo
3. bootstrap ─────► bucket que guarda o state do Terraform
4. arquivos .tf ──► a infraestrutura descrita em código
5. apply ─────────► a infraestrutura existe de verdade
6. deploy manual ─► a aplicação roda na nuvem (uma vez, na mão)
7. GitHub Actions ► o deploy manual vira automático
8. validação ─────► checklist do que precisa estar funcionando
```

**Não pule o passo 6.** Automatizar antes de ter feito o deploy manual uma vez significa depurar o
pipeline e a infraestrutura ao mesmo tempo, sem saber qual dos dois está quebrado.

Tempo realista: **um fim de semana**. O `apply` leva ~15 min (o CloudFront demora a propagar), o
primeiro deploy manual leva ~1 h, o resto é leitura e conferência.

---

## 2. A arquitetura free tier, peça por peça

```
                    ┌──────────────────────────────────────┐
  navegador ───────►│  CloudFront   d1a2b3c4.cloudfront.net│
   (HTTPS)          │  TLS grátis · 1 TB/mês grátis        │
                    └───────┬──────────────────┬───────────┘
                            │ /*               │ /api/*
                            │ (cache longo)    │ (CachingDisabled)
                   ┌────────▼────────┐   ┌─────▼──────────────────────┐
                   │ S3  bucket web  │   │ EC2 t3.micro (free tier)   │
                   │ build do Vite   │   │ ┌────────────────────────┐ │
                   │ PRIVADO (OAC)   │   │ │ docker: api (Java 21)  │ │
                   └─────────────────┘   │ │ porta 80 → 8080        │ │
                                         │ └────────────────────────┘ │
                                         │ IAM Instance Profile       │
                                         │ Security Group: entrada 80 │
                                         │   SÓ da prefix list do     │
                                         │   CloudFront               │
                                         └──┬────────────┬────────────┘
                                            │            │
                    ┌───────────────────────┘            └──────────────┐
                    ▼                                                   ▼
        ┌────────────────────────┐  ┌──────────────────┐   ┌────────────────────┐
        │ S3 material            │  │ SSM Parameter    │   │ Neon Postgres      │
        │ modules/<slug>/...     │  │ Store            │   │ + pgvector         │
        │ config/compose.yml     │  │ (SecureString)   │   │ FORA da AWS, free  │
        │ + ECR (imagem docker)  │  │ chaves de API    │   │ scale-to-zero      │
        └────────────────────────┘  └──────────────────┘   └────────────────────┘
```

### Por que cada escolha

| Escolha | Motivo |
|---|---|
| **CloudFront na frente de tudo** | Dá **HTTPS de graça sem domínio próprio** (`*.cloudfront.net`). Sem ele você precisaria de domínio + Route 53 (US$ 0,50/mês) + Caddy/Let's Encrypt. E como o frontend chama `/api/...` em **caminho relativo** (veja `web/src/api.ts`), ter front e API no mesmo host **elimina o CORS inteiro**. |
| **Frontend no S3, não no EC2** | O S3 serve estático por centavos e não consome a RAM da instância — que é escassa. Bucket **privado**, exposto só pelo CloudFront via OAC. |
| **EC2 `t3.micro` x86** | É o que o free tier cobre (750 h/mês). **x86, não ARM**: a promoção do `t4g.small` grátis acabou. Isso obriga `--platform linux/amd64` no build da imagem. |
| **Porta 80 do EC2 fechada para a internet** | O security group só aceita entrada da *managed prefix list* `com.amazonaws.global.cloudfront.origin-facing`. Ninguém alcança a API sem passar pelo CloudFront. |
| **Sem SSH, sem porta 22** | Acesso ao shell pelo **SSM Session Manager** (grátis, sem chave privada, sem porta aberta). |
| **Banco no Neon** | RDS `db.t4g.micro` é free tier por 12 meses e depois custa ~US$ 15/mês. O Neon é grátis para sempre no tamanho que você precisa e faz *scale-to-zero*. |
| **SSM Parameter Store, não Secrets Manager** | Faz a mesma coisa aqui e o tier Standard é **gratuito**. O Secrets Manager cobra US$ 0,40 por segredo/mês. |
| **ECR, não Docker Hub** | Free tier de 500 MB/mês nos 12 primeiros meses, e o `docker pull` vem da mesma região (rápido, sem custo de saída). Com *lifecycle policy* para não estourar o limite. |
| **Nada de ALB, NAT Gateway ou RDS** | São os três maiores buracos na conta de quem está aprendendo: ~US$ 16, ~US$ 32 e ~US$ 15 por mês, **nenhum deles no free tier de forma útil**. |

---

## 3. O que é grátis, o que não é, e onde o dinheiro vaza

### 3.1 Descubra primeiro em qual regime a sua conta está

A AWS mudou o free tier em **15 de julho de 2025**, e os dois regimes convivem:

| | **Contas criadas antes de 15/jul/2025** | **Contas criadas depois** |
|---|---|---|
| Modelo | Free tier de **12 meses** por serviço | **Plano gratuito com créditos**: US$ 100 ao abrir + até US$ 100 completando atividades |
| EC2 | 750 h/mês de `t2.micro`/`t3.micro` | sai dos créditos |
| Validade | 12 meses da abertura da conta | até 6 meses ou até os créditos acabarem |

**Confira no console** em `Billing and Cost Management → Free tier`. A página mostra o uso de cada
item contra a cota. Se a sua conta é nova, o raciocínio muda: em vez de "isto é grátis", pense
"isto consome X dos meus US$ 200". A arquitetura desta doc gasta ~US$ 13/mês cheios — daria mais
de um ano de créditos.

### 3.2 Cotas que importam para este projeto

| Serviço | Cota gratuita | O que este projeto usa | Folga |
|---|---|---|---|
| EC2 | 750 h/mês `t3.micro` (12 meses) | 1 instância ligada 24×7 = **744 h** | 6 h — **não suba uma segunda instância** |
| EBS | 30 GB gp3 (12 meses) | 1 volume de 20 GB | ok |
| **IPv4 público** | 750 h/mês (12 meses) | 1 Elastic IP anexado = 744 h | **depois dos 12 meses: ~US$ 3,60/mês** |
| S3 | 5 GB + 20 k GET + 2 k PUT/mês (12 meses) | material ~200 MB + web ~5 MB | ok |
| CloudFront | **1 TB saída + 10 M requisições/mês — sempre grátis** | alguns MB | ok |
| ECR privado | 500 MB/mês (12 meses) | imagem Java ~250 MB × 3 tags retidas | apertado → *lifecycle policy* obrigatória |
| SSM Parameter Store | Standard ilimitado — **sempre grátis** | 4 parâmetros | ok |
| AWS Budgets | 2 budgets — **sempre grátis** | 1 budget de alerta | ok |
| Saída EC2 → internet | 100 GB/mês — **sempre grátis** | pouquíssimo | ok |
| Neon | 0,5 GB de storage + ~190 h de compute/mês | base < 100 MB | ok |

### 3.3 As sete regras de ouro para a conta não surpreender

1. **Crie o budget de alerta antes de criar qualquer recurso** (seção 5.3).
2. **Nunca crie um NAT Gateway.** Ele aparece sozinho se você criar uma VPC pelo assistente
   "VPC and more". Esta doc usa a **VPC default**, que tem só Internet Gateway (grátis).
3. **Nunca crie um Load Balancer** para um app de um usuário. O CloudFront faz o papel de entrada.
4. **Elastic IP cobra.** Solto cobra, e anexado a instância ligada também cobra desde fev/2024 —
   está no free tier por 12 meses e depois vira US$ 3,60/mês. Se destruir a instância, **libere o
   EIP**.
5. **`terraform destroy` quando parar de estudar.** Instância parada (`stopped`) não cobra CPU, mas
   **EBS e EIP continuam cobrando**.
6. **Lifecycle policy no ECR.** Sem ela, cada deploy empilha 250 MB e você estoura os 500 MB em
   dois dias.
7. **Ponha um limite de gasto no console da Anthropic.** A conta da AWS aqui é de ~US$ 5–13/mês; a
   da API do Claude não tem teto natural. É o maior risco financeiro do projeto, não a AWS.

### 3.4 Custo realista

| Item | Nos 12 meses de free tier | Depois |
|---|---|---|
| EC2 `t3.micro` 24×7 | US$ 0 | ~US$ 7,60 |
| EBS 20 GB gp3 | US$ 0 | ~US$ 1,60 |
| IPv4 público | US$ 0 | ~US$ 3,60 |
| S3 + CloudFront + ECR + SSM | ~US$ 0,10 | ~US$ 0,60 |
| Neon | US$ 0 | US$ 0 |
| **Total AWS** | **~US$ 0,10/mês** | **~US$ 13,40/mês** |
| API Anthropic | variável | variável |

> Quer cortar quase tudo depois do free tier? **Desligue a instância quando não estiver estudando.**
> Com `stop`/`start`, 4 h/dia derruba o EC2 para ~US$ 1,30/mês. EBS e IPv4 continuam correndo — dá
> ~US$ 6,50/mês no total.

---

## 4. A decisão a tomar antes de tudo: embeddings

Esta é a única peça da arquitetura que **não cabe confortavelmente no free tier**, e é melhor
decidir agora do que descobrir com a instância de pé.

**O problema.** O `t3.micro` tem **1 GB de RAM**. O backend Java sozinho ocupa ~400 MB. O container
de embeddings (TEI com `multilingual-e5-small`) quer ~800 MB. Não cabem juntos.

**E o banco não te salva:** mesmo com todo o material já indexado, **cada pergunta gera um embedding
da consulta**. Produção precisa de um gerador de embeddings ativo, sempre.

### As três saídas

| | **A. TEI no mesmo EC2, com swap** | **B. API externa (Voyage)** | **C. Instância maior** |
|---|---|---|---|
| Custo AWS | **US$ 0** (free tier) | US$ 0 (free tier) | ~US$ 15/mês (`t3.small`) |
| Custo extra | — | centavos/mês | — |
| Trabalho de código | nenhum | **escrever o adaptador** | nenhum |
| Latência da 1ª consulta | 3–10 s (swap) | ~200 ms | ~200 ms |
| Risco | OOM se apertar | nenhum | nenhum |

**Recomendação: comece pela A**, que é 100% free tier e não exige código. Se a latência incomodar,
migre para a B com calma.

### Se for a A — o que a doc já prepara

O `user_data` do Terraform (seção 9.10) cria **2 GB de swap** e o compose de produção limita a
memória de cada container. Ajustes que fazem diferença:

```yaml
api:
  environment:
    JAVA_OPTS: "-Xms128m -Xmx384m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC"
embeddings:
  command: ["--model-id","intfloat/multilingual-e5-small","--port","80","--max-batch-tokens","2048"]
```

`UseSerialGC` economiza ~40 MB frente ao G1 em heaps pequenos, e `--max-batch-tokens` baixo impede
o TEI de alocar buffers grandes.

### Se for a B — atenção à dimensão do vetor

`V1__init.sql` declara `embedding vector(384)`, que é a dimensão do `multilingual-e5-small`. Se
trocar por um modelo de outra dimensão (o `voyage-3-lite` devolve 512), **é migração Flyway nova e
reindexação completa do material**. Nada disso é difícil, mas não é uma troca de variável de
ambiente — planeje como uma tarefa de código:

1. novo adaptador implementando `EmbeddingPortOut` (o hexágono já isola isso);
2. `V3__embedding_dim.sql` recriando a coluna e o índice;
3. reindexar pela área de administração.

> Enquanto o adaptador não existir, `EMBEDDING_PROVIDER=voyage` não faz nada: o valor cai no
> fallback do `application.yml`. Confira `ibm/src/main/java/.../adapter/out/embedding/` antes de
> apostar nessa rota.

---

## 5. Pré-requisitos

### 5.1 Ferramentas na sua máquina

| Ferramenta | Versão | Como checar |
|---|---|---|
| AWS CLI | v2 | `aws --version` |
| Terraform | **≥ 1.11** (o *lockfile* nativo no S3 depende disso) | `terraform version` |
| Docker Desktop | qualquer recente | `docker version` |
| Git + conta no GitHub | — | `git --version` |
| `gh` (opcional, facilita os secrets) | — | `gh --version` |

No Windows, tudo isso instala com:

```powershell
winget install Amazon.AWSCLI
winget install HashiCorp.Terraform
winget install GitHub.cli
```

### 5.2 Credenciais da AWS no seu laptop

Crie um usuário IAM só para você, com MFA, e uma chave de acesso **apenas para o Terraform rodar do
seu laptop** (o GitHub Actions não vai usar chave nenhuma — usa OIDC).

```bash
aws configure --profile estudos
# Access key, Secret key, region: us-east-1, output: json

export AWS_PROFILE=estudos           # Linux/macOS/Git Bash
$env:AWS_PROFILE = "estudos"         # PowerShell

aws sts get-caller-identity          # tem que mostrar a sua conta
```

> **Por que `us-east-1`.** É a região mais barata, onde o Neon tem free tier, e a única onde o ACM
> emite certificado para CloudFront (não usamos ACM aqui, mas é bom hábito). A latência extra do
> Brasil é irrelevante para um app de estudo, e o CloudFront atende do POP de São Paulo mesmo assim.

### 5.3 Budget de alerta — **faça isto antes de qualquer outra coisa**

```bash
cat > budget.json <<'EOF'
{
  "BudgetName": "estudos-ibm",
  "BudgetLimit": { "Amount": "10", "Unit": "USD" },
  "TimeUnit": "MONTHLY",
  "BudgetType": "COST"
}
EOF

cat > notifications.json <<'EOF'
[
  {
    "Notification": {
      "NotificationType": "ACTUAL",
      "ComparisonOperator": "GREATER_THAN",
      "Threshold": 50,
      "ThresholdType": "PERCENTAGE"
    },
    "Subscribers": [
      { "SubscriptionType": "EMAIL", "Address": "SEU-EMAIL@exemplo.com" }
    ]
  }
]
EOF

aws budgets create-budget \
  --account-id "$(aws sts get-caller-identity --query Account --output text)" \
  --budget file://budget.json \
  --notifications-with-subscribers file://notifications.json
```

Também habilite, no console, `Billing → Billing preferences → Free tier usage alerts`.

### 5.4 Repositório no GitHub

O projeto precisa estar em um repositório (pode ser privado) — o GitHub Actions e o OIDC dependem
disso. Se ainda não está:

```bash
cd "C:/Users/Letícia Viana/OneDrive/Documentos/Estudos_IBM"
git init -b main
git add .
git commit -m "Fase 1 completa: aplicação rodando local"
gh repo create estudos-ibm --private --source=. --push
```

> **Antes do primeiro commit, confira o `.gitignore`.** `.env`, `ibm/target/`, `web/node_modules/`,
> `web/dist/` e `infra/terraform/*.tfvars` não podem subir. Chave da Anthropic no histórico do Git
> é rotação de chave imediata.

---

## 6. Passo 1 — Banco no Neon

O banco é o primeiro porque é o único passo que você valida **sem tocar na AWS**: aponta o `.env`
local para o Neon e vê a aplicação inteira funcionando com o banco remoto.

### 6.1 Criar o projeto

1. Conta em [neon.tech](https://neon.tech) (o free tier não pede cartão).
2. Novo projeto: região **AWS US East (N. Virginia)** — a mesma do EC2, para a latência ser de
   ~1 ms em vez de ~120 ms.
3. Nome do banco: `cloudability`.
4. Copie a connection string do dashboard. Ela vem no formato:
   `postgresql://<user>:<senha>@ep-nome-1a2b3c.us-east-1.aws.neon.tech/cloudability?sslmode=require`

### 6.2 Habilitar o pgvector

No **SQL Editor** do Neon:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
SELECT extversion FROM pg_extension WHERE extname = 'vector';
```

Sem isso a migração `V1__init.sql` falha no boot e o backend nem sobe.

### 6.3 Converter para o formato que o Spring espera

O `application.yml` usa três variáveis separadas, e o JDBC não aceita a URL com usuário e senha
embutidos. Quebre a string assim:

```bash
DB_URL=jdbc:postgresql://ep-nome-1a2b3c.us-east-1.aws.neon.tech/cloudability?sslmode=require
DB_USER=<user>
DB_PASSWORD=<senha>
```

> **`sslmode=require` não é opcional** — o Neon recusa conexão sem TLS.
>
> Use o endpoint **pooled** (tem `-pooler` no host) se aparecer nos detalhes da conexão: ele segura
> melhor o pool do Hikari contra o scale-to-zero.

### 6.4 Validar do seu laptop

Edite o `.env` local trocando só as três variáveis do banco, e suba **sem** o serviço `db`:

```bash
docker compose up -d localstack material-seed embeddings api web
docker compose logs -f api
```

O que você quer ver no log:

```
Flyway ... Successfully applied 2 migrations
Started IbmApplication in ...
```

Depois abra `http://localhost` e faça uma pergunta. Se responder, o banco remoto está validado — e
esse é o maior risco do deploy já eliminado.

### 6.5 O scale-to-zero e o primeiro acesso do dia

O Neon free hiberna o compute após ~5 min de ociosidade. A primeira conexão depois disso leva de
500 ms a ~3 s, e o Hikari pode estourar o timeout padrão. Se vir `Connection is not available,
request timed out` no primeiro acesso do dia, aumente o timeout no `application.yml`:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 5          # o Neon free tem poucas conexões; 10 é exagero
      connection-timeout: 30000     # 30 s, para cobrir o cold start
      idle-timeout: 240000          # 4 min, solta as conexões antes do Neon hibernar
```

> **Restrição de IP não existe no free do Neon.** A lista de IPs permitidos (*IP Allow*) é recurso
> dos planos pagos. A proteção real do banco é a senha — então ela mora no SSM Parameter Store, não
> em arquivo commitado. (A [FASE-2-AWS.md](FASE-2-AWS.md) sugere restringir por IP; no plano free
> isso não está disponível.)

---

## 7. Passo 2 — Entender o Terraform antes de escrever Terraform

Se você já domina Terraform, pule para a seção 8. Se não, cinco minutos aqui economizam horas
depois.

### 7.1 O modelo mental

O Terraform lê arquivos `.tf` que **descrevem o estado final desejado**, compara com o que existe
de fato na AWS e calcula a diferença. Você nunca diz "crie"; diz "deve existir".

```
   arquivos .tf          state (terraform.tfstate)          AWS de verdade
  "deve existir       "da última vez, eu criei          "o que está lá agora"
   1 EC2 t3.micro"     o i-0abc com t3.micro"
         │                        │                             │
         └────────────► terraform plan ◄──────────────────────┘
                                 │
                      "vou mudar isto, isto e isto"
                                 │
                          terraform apply
```

### 7.2 Os cinco blocos que você vai encontrar

| Bloco | O que é | Exemplo nesta doc |
|---|---|---|
| `terraform { }` | versões e onde o state fica | `versions.tf` |
| `provider "aws" { }` | com qual conta/região falar | `providers.tf` |
| `resource "X" "nome" { }` | **algo que o Terraform cria e passa a ser dono** | `aws_instance.app` |
| `data "X" "nome" { }` | **algo que já existe e você só consulta** | `data.aws_vpc.default` |
| `variable` / `output` / `locals` | entrada, saída e apelidos | `variables.tf` |

A diferença entre `resource` e `data` é a que mais confunde no começo: **`data` só lê.** A VPC
default já existe na sua conta — você não quer que o Terraform a gerencie nem a destrua, então ela
entra como `data`.

### 7.3 O state, e por que ele vai para o S3

O state é o caderninho do Terraform: o mapa entre "o recurso que descrevi" e "o ID real na AWS".
Sem ele, um `apply` criaria tudo de novo.

- **Local (`terraform.tfstate` no disco)** funciona para um computador só. Se o laptop formatar, o
  Terraform perde o rastro de tudo que criou.
- **Remoto (S3)** é o que esta doc usa, por duas razões: sobrevive ao laptop, e permite que o
  GitHub Actions rode `plan` no mesmo state que você.

O state **contém valores sensíveis em texto claro** (por isso senha de banco e chave de API entram
por SSM, criados fora do Terraform, e o bucket do state tem criptografia e bloqueio público).

Antes o *lock* exigia uma tabela DynamoDB. Do **Terraform 1.11** em diante o S3 faz *lock* nativo
com `use_lockfile = true` — um recurso a menos para criar e pagar.

### 7.4 O ciclo que você vai repetir

```bash
terraform init      # baixa providers e conecta no backend  (1× por máquina/mudança de backend)
terraform fmt       # formata
terraform validate  # erro de sintaxe/tipo, sem falar com a AWS
terraform plan      # o que mudaria — LEIA antes de aplicar
terraform apply     # aplica
terraform destroy   # apaga tudo que ele criou
```

**Como ler um `plan`:** `+` cria, `~` altera no lugar, `-/+` **destrói e recria** (cuidado!), `-`
destrói. Se aparecer `-/+` num recurso com dados (bucket, volume), pare e entenda por quê antes de
continuar.

---

## 8. Passo 3 — Bootstrap: o bucket do state

Problema do ovo e da galinha: o state vive num bucket S3, mas o bucket precisa existir antes do
primeiro `init`. Resolve-se criando esse único recurso na mão. Rode uma vez:

```bash
export AWS_PROFILE=estudos
export REGION=us-east-1
export ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
export TFSTATE_BUCKET="tfstate-estudos-ibm-${ACCOUNT}"

aws s3api create-bucket --bucket "$TFSTATE_BUCKET" --region "$REGION"

# Versionamento: permite voltar a um state anterior se algo corromper.
aws s3api put-bucket-versioning --bucket "$TFSTATE_BUCKET" \
  --versioning-configuration Status=Enabled

# Criptografia em repouso (SSE-S3, sem custo).
aws s3api put-bucket-encryption --bucket "$TFSTATE_BUCKET" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

# O state tem segredos: nada de acesso público.
aws s3api put-public-access-block --bucket "$TFSTATE_BUCKET" \
  --public-access-block-configuration \
  "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"

echo "Bucket do state: $TFSTATE_BUCKET"
```

> O nome inclui o número da conta porque **nome de bucket S3 é global** — `tfstate-estudos-ibm`
> provavelmente já é de outra pessoa no mundo.

---

## 9. Passo 4 — Os arquivos do Terraform

Crie a pasta e os arquivos abaixo. Cada um tem uma responsabilidade — dá para juntar tudo num
`main.tf`, mas você vai se perder na primeira vez que precisar mudar algo.

```
infra/terraform/
├── versions.tf          versões e backend do state
├── providers.tf         provider AWS + tags padrão
├── variables.tf         entradas
├── terraform.tfvars     seus valores  (NÃO commitar)
├── network.tf           VPC default (data) + security group
├── s3.tf                buckets do site e do material
├── ecr.tf               registro da imagem + lifecycle
├── iam_app.tf           role do EC2 (acesso ao S3 e ao SSM)
├── iam_github.tf        OIDC + role do GitHub Actions
├── ec2.tf               instância, EIP, user_data
├── cloudfront.tf        distribuição com dois origins
├── budget.tf            alerta de gasto
├── outputs.tf           o que você precisa depois
└── templates/
    ├── user_data.sh     o que roda no primeiro boot
    └── compose.prod.yml o compose de produção (vai para o S3)
```

### 9.1 `versions.tf`

```hcl
terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Onde o state mora. Preencha o bucket com o nome que saiu do passo 3.
  # `use_lockfile` = lock nativo do S3 (dispensa a tabela DynamoDB dos tutoriais antigos).
  backend "s3" {
    bucket       = "tfstate-estudos-ibm-SEU-ACCOUNT-ID"
    key          = "estudos-ibm/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
```

> O bloco `backend` **não aceita variáveis** — é o único lugar onde você digita o valor na mão.
> (Se o seu provider AWS for `5.x`, tudo nesta doc funciona igual; só ajuste o `version`.)

### 9.2 `providers.tf`

```hcl
provider "aws" {
  region = var.region

  # Toda tag aqui é aplicada a todo recurso que suportar tags. Vale ouro no
  # Cost Explorer: você filtra por Project e vê exatamente o que este projeto gasta.
  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
      Owner     = var.owner
    }
  }
}
```

### 9.3 `variables.tf`

```hcl
variable "project" {
  description = "Prefixo de todos os nomes de recurso."
  type        = string
  default     = "cloudability-chat"
}

variable "owner" {
  description = "Tag de dono, para o Cost Explorer."
  type        = string
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "instance_type" {
  description = "t3.micro = free tier. t3.small (2 GB) se o swap não der conta."
  type        = string
  default     = "t3.micro"
}

variable "root_volume_gb" {
  description = "Free tier: 30 GB no total. 20 sobra para imagens docker e swap."
  type        = number
  default     = 20
}

variable "github_repo" {
  description = "owner/repo — limita quem pode assumir a role de deploy."
  type        = string
}

variable "create_github_oidc_provider" {
  description = "false se a conta já tiver o provider do GitHub criado."
  type        = bool
  default     = true
}

variable "budget_email" {
  description = "E-mail que recebe o alerta de gasto."
  type        = string
}

variable "material_bucket_name" {
  description = "Precisa ser único no mundo. Sufixe com algo seu."
  type        = string
}

variable "web_bucket_name" {
  description = "Idem. Este é o do site."
  type        = string
}
```

### 9.4 `terraform.tfvars` — **não commite**

```hcl
owner                = "leticia"
github_repo          = "seu-usuario/estudos-ibm"
budget_email         = "voce@exemplo.com"
material_bucket_name = "cloudability-material-leticia-2026"
web_bucket_name      = "cloudability-web-leticia-2026"
```

Adicione ao `.gitignore` da raiz:

```gitignore
# Terraform
infra/terraform/.terraform/
infra/terraform/*.tfstate
infra/terraform/*.tfstate.*
infra/terraform/*.tfvars
infra/terraform/.terraform.lock.hcl   # opcional: alguns times commitam este
```

### 9.5 `network.tf`

```hcl
# A VPC default já existe e é gratuita (tem Internet Gateway, não tem NAT).
# Como `data`, o Terraform apenas a consulta — nunca a destrói.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# A lista de faixas de IP que o CloudFront usa para buscar conteúdo na origem.
# A AWS mantém essa lista atualizada sozinha — é o jeito certo de deixar entrar
# "só o CloudFront" sem colar centenas de CIDRs na mão.
data "aws_ec2_managed_prefix_list" "cloudfront_origin" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "app" {
  name        = "${var.project}-app"
  description = "Backend: entrada apenas do CloudFront"
  vpc_id      = data.aws_vpc.default.id
}

# HTTP na 80 — só de quem vem do CloudFront. Sem porta 22: o shell é por SSM.
resource "aws_vpc_security_group_ingress_rule" "http_from_cloudfront" {
  security_group_id = aws_security_group.app.id
  description       = "HTTP vindo do CloudFront"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront_origin.id
}

# Saída liberada: a instância precisa alcançar ECR, S3, SSM, Neon e a API da Anthropic.
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.app.id
  description       = "Saida liberada"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
```

> **O detalhe que vale a segurança inteira.** Sem a regra da prefix list, qualquer um descobre o IP
> da instância e fala direto com a API — e como o backend ainda não tem autenticação, isso é a sua
> chave da Anthropic sendo gastada por estranhos. Com ela, a única porta de entrada é o CloudFront.

### 9.6 `s3.tf`

```hcl
# ------------------------------------------------------------------ site
resource "aws_s3_bucket" "web" {
  bucket = var.web_bucket_name
}

# O bucket é PRIVADO. Quem serve o site é o CloudFront, via OAC (cloudfront.tf).
# Nada de "static website hosting", que exigiria bucket público.
resource "aws_s3_bucket_public_access_block" "web" {
  bucket                  = aws_s3_bucket.web.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "web" {
  bucket = aws_s3_bucket.web.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# -------------------------------------------------------------- material
resource "aws_s3_bucket" "material" {
  bucket = var.material_bucket_name
}

resource "aws_s3_bucket_public_access_block" "material" {
  bucket                  = aws_s3_bucket.material.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "material" {
  bucket = aws_s3_bucket.material.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Versionamento no material: ele é ESTADO, não artefato. Um `sync --delete`
# distraído não pode ser irreversível.
resource "aws_s3_bucket_versioning" "material" {
  bucket = aws_s3_bucket.material.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Versão antiga vira arquivo barato em 30 dias e some em 90 — mantém o bucket
# dentro dos 5 GB do free tier mesmo com muitos uploads.
resource "aws_s3_bucket_lifecycle_configuration" "material" {
  bucket = aws_s3_bucket.material.id

  rule {
    id     = "expira-versoes-antigas"
    status = "Enabled"
    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}
```

> O **compose de produção** também mora neste bucket, em `config/`. Assim o arquivo fica versionado
> no Git, o pipeline publica a versão nova e o EC2 baixa dali no deploy — sem recriar a instância a
> cada mudança de compose.

### 9.7 `ecr.tf`

```hcl
resource "aws_ecr_repository" "api" {
  name                 = "${var.project}-api"
  image_tag_mutability = "MUTABLE" # precisamos sobrescrever a tag `latest`

  image_scanning_configuration {
    scan_on_push = true # grátis no scan básico
  }
}

# SEM ISTO VOCÊ ESTOURA O FREE TIER. Cada imagem tem ~250 MB e a cota é 500 MB.
resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Mantem apenas as 2 imagens mais recentes"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 2
        }
        action = { type = "expire" }
      }
    ]
  })
}
```

### 9.8 `iam_app.tf` — a role do EC2

Esta é a peça que substitui chaves de acesso no servidor. A instância recebe credenciais
temporárias, rotacionadas pela AWS, e o SDK da aplicação as encontra sozinho.

```hcl
data "aws_caller_identity" "current" {}

resource "aws_iam_role" "app" {
  name = "${var.project}-app"

  # Quem pode assumir esta role: o serviço EC2, e mais ninguém.
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "app" {
  name = "material-e-config"
  role = aws_iam_role.app.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ListarBucketDoMaterial"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.material.arn
      },
      {
        # Put e Delete NÃO são opcionais: o upload do admin grava no bucket e
        # remover um módulo apaga a pasta dele.
        Sid      = "LerEGravarObjetos"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.material.arn}/*"
      },
      {
        # Os segredos. Path fixo, nada de ssm:* solto.
        Sid    = "LerSegredos"
        Effect = "Allow"
        Action = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
        Resource = "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${var.project}/*"
      },
      {
        Sid      = "DecriptarSecureString"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = "*"
        Condition = {
          StringEquals = { "kms:ViaService" = "ssm.${var.region}.amazonaws.com" }
        }
      },
      {
        Sid      = "PullDoECR"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchCheckLayerAvailability"
        ]
        Resource = aws_ecr_repository.api.arn
      }
    ]
  })
}

# Session Manager (shell sem SSH) e o agente que executa os comandos de deploy.
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# O invólucro pelo qual uma instância EC2 recebe uma role.
resource "aws_iam_instance_profile" "app" {
  name = "${var.project}-app"
  role = aws_iam_role.app.name
}
```

### 9.9 `iam_github.tf` — OIDC, o deploy sem chave

**Como funciona, em uma frase:** o GitHub assina um token dizendo "sou o workflow do repo X na
branch main", a AWS confia nessa assinatura e devolve credenciais temporárias — **nenhuma chave
permanente é guardada no GitHub**.

```hcl
# O provider OIDC é por conta: se já existir (outro projeto criou), ponha
# create_github_oidc_provider = false e ele será apenas consultado.
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # `thumbprint_list` não é mais necessário: a AWS passou a validar o endpoint do
  # GitHub pela cadeia de CA pública. Se o seu provider exigir o campo, qualquer
  # valor de 40 caracteres passa — ele é ignorado.
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  # Os parênteses são obrigatórios: o HCL não aceita quebra de linha depois do `?`.
  github_oidc_arn = (
    var.create_github_oidc_provider
    ? aws_iam_openid_connect_provider.github[0].arn
    : data.aws_iam_openid_connect_provider.github[0].arn
  )
}

resource "aws_iam_role" "github_deploy" {
  name = "${var.project}-github-deploy"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_oidc_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        # A trava que importa: SÓ a branch main DO SEU repositório.
        # Sem esta condição, qualquer repositório do GitHub assume a sua role.
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:ref:refs/heads/main"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "github_deploy" {
  name = "deploy"
  role = aws_iam_role.github_deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "LoginNoECR"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "PushDaImagem"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:CompleteLayerUpload",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer"
        ]
        Resource = aws_ecr_repository.api.arn
      },
      {
        Sid      = "PublicarOSite"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:DeleteObject", "s3:ListBucket", "s3:GetObject"]
        Resource = [aws_s3_bucket.web.arn, "${aws_s3_bucket.web.arn}/*"]
      },
      {
        # Só o compose, dentro de config/. O pipeline NÃO enxerga o material:
        # ele é estado do usuário, não artefato de build.
        Sid      = "PublicarOCompose"
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = "${aws_s3_bucket.material.arn}/config/*"
      },
      {
        # GetDistribution entra porque o passo de health check do workflow
        # descobre o domínio da distribuição em tempo de execução.
        Sid    = "InvalidarCache"
        Effect = "Allow"
        Action = [
          "cloudfront:CreateInvalidation",
          "cloudfront:GetInvalidation",
          "cloudfront:GetDistribution"
        ]
        Resource = aws_cloudfront_distribution.site.arn
      },
      {
        Sid      = "MandarODeployParaAInstancia"
        Effect   = "Allow"
        Action   = ["ssm:SendCommand"]
        Resource = [
          "arn:aws:ssm:${var.region}::document/AWS-RunShellScript",
          aws_instance.app.arn
        ]
      },
      {
        Sid      = "AcompanharOResultado"
        Effect   = "Allow"
        Action   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
        Resource = "*"
      }
    ]
  })
}
```

> **Duas roles, de propósito.** `app` é do EC2 e enxerga o material; `github-deploy` é do pipeline e
> não enxerga. Se um dia o pipeline for comprometido, ele não apaga a sua base de estudo.

### 9.10 `ec2.tf`

```hcl
# A AMI mais recente do Amazon Linux 2023, resolvida na hora pelo parâmetro
# público da AWS. Melhor que um ID fixo, que fica velho e é diferente por região.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64"
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.app.name

  root_block_device {
    volume_size           = var.root_volume_gb
    volume_type           = "gp3" # mais barato e mais rápido que gp2, mesmo free tier
    encrypted             = true
    delete_on_termination = true
  }

  metadata_options {
    http_tokens = "required" # IMDSv2 obrigatório
    # 2 saltos: o container precisa de um salto a mais para alcançar o serviço
    # de metadados do host. É a causa nº 1 de "funciona no host, falha no container".
    http_put_response_hop_limit = 2
  }

  user_data_replace_on_change = false # editar o script não recria a instância

  user_data = templatefile("${path.module}/templates/user_data.sh", {
    region          = var.region
    project         = var.project
    ecr_repo_url    = aws_ecr_repository.api.repository_url
    material_bucket = aws_s3_bucket.material.id
  })

  tags = { Name = "${var.project}-app" }
}

# IP fixo: sem ele, o DNS público muda a cada stop/start e o CloudFront perde
# a origem. Grátis nos 12 meses de free tier; ~US$ 3,60/mês depois.
resource "aws_eip" "app" {
  instance = aws_instance.app.id
  domain   = "vpc"
}
```

### 9.11 `templates/user_data.sh`

Roda **uma vez**, no primeiro boot. Deixa a instância pronta e cria o script de deploy que o
GitHub Actions vai chamar depois.

```bash
#!/bin/bash
set -euxo pipefail
# Log deste script: /var/log/cloud-init-output.log

# ---------------------------------------------------------------- swap
# 2 GB de swap. Num t3.micro (1 GB) é o que permite o TEI e o Java conviverem.
# Sem isto, o kernel mata o container maior no meio de uma consulta.
if [ ! -f /swapfile ]; then
  dd if=/dev/zero of=/swapfile bs=1M count=2048
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
  # Só usa swap quando realmente faltar RAM.
  sysctl -w vm.swappiness=10
  echo 'vm.swappiness=10' >> /etc/sysctl.d/99-swap.conf
fi

# -------------------------------------------------------------- docker
dnf install -y docker
systemctl enable --now docker
usermod -aG docker ec2-user

mkdir -p /usr/local/lib/docker/cli-plugins
curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# O agente do SSM já vem no AL2023; garante que está de pé.
systemctl enable --now amazon-ssm-agent

mkdir -p /opt/app
cd /opt/app

# ------------------------------------------------------------- deploy.sh
# Chamado no primeiro deploy manual e, depois, pelo GitHub Actions via SSM.
# Recebe a tag da imagem como argumento.
cat > /opt/app/deploy.sh <<'SCRIPT'
#!/bin/bash
set -euo pipefail

REGION="${region}"
PROJECT="${project}"
ECR_REPO="${ecr_repo_url}"
MATERIAL_BUCKET="${material_bucket}"
IMAGE_TAG="$${1:-latest}"

cd /opt/app

# 1. Busca o compose mais recente publicado pelo pipeline.
aws s3 cp "s3://$$MATERIAL_BUCKET/config/compose.prod.yml" /opt/app/compose.prod.yml --region "$$REGION"

# 2. Monta o .env a partir do SSM Parameter Store. Os segredos existem
#    em memória e num arquivo 600 — nunca no Git, nunca no state do Terraform.
umask 077
{
  echo "ECR_REPO=$$ECR_REPO"
  echo "IMAGE_TAG=$$IMAGE_TAG"
  echo "AWS_REGION=$$REGION"
  echo "MATERIAL_BUCKET=$$MATERIAL_BUCKET"
  aws ssm get-parameters-by-path \
    --path "/$$PROJECT/" --with-decryption --recursive \
    --region "$$REGION" \
    --query "Parameters[].[Name,Value]" --output text |
    while IFS=$$'\t' read -r name value; do
      echo "$$(basename "$$name")=$$value"
    done
} > /opt/app/.env

# 3. Autentica no ECR e sobe.
aws ecr get-login-password --region "$$REGION" |
  docker login --username AWS --password-stdin "$${ECR_REPO%%/*}"

docker compose -f compose.prod.yml --env-file .env pull
docker compose -f compose.prod.yml --env-file .env up -d --remove-orphans

# 4. Limpa camadas órfãs — o disco é de 20 GB.
docker image prune -af --filter "until=72h"

echo "deploy concluido: $$IMAGE_TAG"
SCRIPT

chmod 700 /opt/app/deploy.sh
echo "instancia pronta"
```

> **Sobre os `$$`.** Este arquivo passa pelo `templatefile()` do Terraform, que interpreta `${...}`.
> `$${VAR}` vira `${VAR}` no arquivo final — é assim que as variáveis de shell sobrevivem. As do
> Terraform (`${region}`, `${project}`…) ficam com um cifrão só e são substituídas no `apply`.
>
> **Limitação conhecida do `.env` gerado:** a leitura do SSM usa `--output text`, que separa colunas
> por tabulação. Valor de parâmetro com quebra de linha ou tabulação dentro sairia truncado. Chaves
> de API e connection strings não têm isso — mas não guarde um certificado PEM por esse caminho.

### 9.12 `templates/compose.prod.yml`

Este arquivo **não** é interpolado pelo Terraform: ele é publicado no S3 pelo pipeline e lido pelo
`deploy.sh`. Guarde-o versionado em `infra/compose.prod.yml`.

```yaml
services:
  api:
    image: ${ECR_REPO}:${IMAGE_TAG}
    restart: unless-stopped
    # A API atende na 80 do host. O security group só deixa o CloudFront entrar.
    ports:
      - "80:8080"
    environment:
      # ------------------------------------------------------------ banco
      DB_URL: ${DB_URL}
      DB_USER: ${DB_USER}
      DB_PASSWORD: ${DB_PASSWORD}

      # ------------------------------------------------------------ claude
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
      ANTHROPIC_MODEL: claude-sonnet-5
      ANTHROPIC_EFFORT: medium

      # -------------------------------------------------------- embeddings
      EMBEDDING_PROVIDER: tei
      EMBEDDING_URL: http://embeddings:80
      EMBEDDING_DIMENSIONS: "384"

      # --------------------------------------------------------- material
      # Repare no que NÃO está aqui: nenhuma credencial da AWS. Sem
      # MATERIAL_ENDPOINT o SDK fala com o S3 de verdade, e sem chave ele
      # percorre a cadeia padrão até a IAM Role da instância.
      MATERIAL_PROVIDER: s3
      MATERIAL_BUCKET: ${MATERIAL_BUCKET}
      MATERIAL_PREFIX: modules
      MATERIAL_CREATE_BUCKET: "false"
      AWS_REGION: ${AWS_REGION}

      # ----------------------------------------------------------- CORS
      # Front e API no mesmo host (CloudFront), então CORS nem entra em jogo.
      # Mantido só para o caso de você acessar de outro lugar em teste.
      CORS_ORIGINS: ${PUBLIC_URL}

      # ---------------------------------------------------------- alçadas
      # NÃO publique com admin: enquanto não houver login, esta é a alçada
      # de qualquer pessoa que abrir a URL. Ver seção 15.4.
      ACCESS_DEFAULT_LEVEL: student
      KB_AUTO_INGEST: "false"

      # 1 GB de RAM: heap curto, metaspace limitado, GC serial.
      JAVA_OPTS: "-Xms128m -Xmx384m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC"
    mem_limit: 560m
    depends_on:
      embeddings:
        condition: service_healthy

  embeddings:
    image: ghcr.io/huggingface/text-embeddings-inference:cpu-1.8
    restart: unless-stopped
    command: ["--model-id", "intfloat/multilingual-e5-small", "--port", "80", "--max-batch-tokens", "2048"]
    volumes:
      - embeddings-cache:/data
    mem_limit: 900m
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:80/health"]
      interval: 15s
      timeout: 5s
      retries: 30
      start_period: 300s   # baixar o modelo na primeira vez é lento

volumes:
  embeddings-cache:
```

> Não há serviço `web` aqui: o frontend está no S3. E não há `db`: está no Neon. É exatamente por
> isso que 1 GB de RAM dá conta.

### 9.13 `cloudfront.tf`

```hcl
# OAC: o jeito atual de dar ao CloudFront (e só a ele) acesso a um bucket privado.
# Substitui a antiga Origin Access Identity.
resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "${var.project}-web"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# Políticas gerenciadas pela AWS — melhor que decorar IDs mágicos.
data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

resource "aws_cloudfront_distribution" "site" {
  enabled             = true
  default_root_object = "index.html"
  comment             = var.project
  # PriceClass_All inclui os POPs da América do Sul. O 1 TB gratuito vale em
  # qualquer classe, então economizar aqui só pioraria a latência.
  price_class = "PriceClass_All"

  # ---------------------------------------------------------- origem: site
  origin {
    origin_id                = "s3-web"
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  # ----------------------------------------------------------- origem: API
  origin {
    origin_id = "ec2-api"
    # Nome DNS, não IP: o CloudFront não aceita endereço IP como origem.
    # Com o EIP anexado, este nome é estável.
    domain_name = "ec2-${replace(aws_eip.app.public_ip, ".", "-")}.compute-1.amazonaws.com"

    custom_origin_config {
      http_port  = 80
      https_port = 443
      # http-only: entre CloudFront e EC2 o tráfego é HTTP. Aceitável porque a
      # porta só aceita o CloudFront; para TLS fim a fim seria preciso domínio
      # próprio + certificado na instância (ver seção 14).
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
      # O chat responde em stream. 60 s é o teto sem pedir aumento à AWS —
      # e cada chunk do SSE reinicia a contagem.
      origin_read_timeout      = 60
      origin_keepalive_timeout = 60
    }
  }

  # ----------------------------------------- comportamento padrão: estático
  default_cache_behavior {
    target_origin_id       = "s3-web"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.optimized.id
    compress               = true
  }

  # ------------------------------------------------ comportamento: /api/*
  ordered_cache_behavior {
    path_pattern           = "/api/*"
    target_origin_id       = "ec2-api"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods         = ["GET", "HEAD"]
    # Resposta de chat JAMAIS pode ser cacheada.
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
    compress                 = false # compressão atrapalha o streaming SSE
  }

  # SPA: rota do React que não é arquivo no bucket devolve 403/404 no S3.
  # Traduzimos para o index.html com status 200 e o roteador resolve.
  custom_error_response {
    error_code            = 403
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }

  custom_error_response {
    error_code            = 404
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    # Certificado do domínio *.cloudfront.net: HTTPS sem domínio próprio e sem custo.
    cloudfront_default_certificate = true
  }
}

# A política que autoriza SÓ esta distribuição a ler o bucket do site.
resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "PermitirCloudFront"
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.web.arn}/*"
      Condition = {
        StringEquals = {
          "AWS:SourceArn" = aws_cloudfront_distribution.site.arn
        }
      }
    }]
  })
}
```

> **Sobre o nome DNS da origem.** O formato `ec2-<ip-com-hifens>.compute-1.amazonaws.com` vale para
> `us-east-1`. Em outras regiões é `.<região>.compute.amazonaws.com`. Se preferir não depender do
> formato, troque por `aws_instance.app.public_dns` — mas aí o CloudFront não se atualiza sozinho se
> o DNS mudar, e é justamente por isso que o EIP existe.

### 9.14 `budget.tf`

```hcl
resource "aws_budgets_budget" "mensal" {
  name         = "${var.project}-mensal"
  budget_type  = "COST"
  limit_amount = "10"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Avisa quando passar de 50% do previsto...
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 50
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_email]
  }

  # ...e quando a PREVISÃO do mês estourar 100%, que chega antes do estrago.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_email]
  }
}
```

### 9.15 `outputs.tf`

```hcl
output "url_publica" {
  description = "Abra isto no navegador."
  value       = "https://${aws_cloudfront_distribution.site.domain_name}"
}

output "cloudfront_distribution_id" {
  description = "Secret CLOUDFRONT_ID do GitHub."
  value       = aws_cloudfront_distribution.site.id
}

output "ecr_repository_url" {
  value = aws_ecr_repository.api.repository_url
}

output "instance_id" {
  description = "Secret EC2_INSTANCE_ID do GitHub."
  value       = aws_instance.app.id
}

output "instance_public_ip" {
  value = aws_eip.app.public_ip
}

output "github_role_arn" {
  description = "Secret AWS_ROLE_ARN do GitHub."
  value       = aws_iam_role.github_deploy.arn
}

output "web_bucket" {
  value = aws_s3_bucket.web.id
}

output "material_bucket" {
  value = aws_s3_bucket.material.id
}
```

---

## 10. Passo 5 — `plan` e `apply`

### 10.1 Os segredos, primeiro

O `deploy.sh` lê tudo que estiver em `/<project>/` no Parameter Store. Crie **antes** do apply —
pela CLI, não pelo Terraform, para que os valores nunca entrem no state:

```bash
export PROJECT=cloudability-chat

aws ssm put-parameter --name "/$PROJECT/ANTHROPIC_API_KEY" \
  --type SecureString --value "sk-ant-..." --overwrite

aws ssm put-parameter --name "/$PROJECT/DB_URL" \
  --type SecureString \
  --value "jdbc:postgresql://ep-nome.us-east-1.aws.neon.tech/cloudability?sslmode=require" --overwrite

aws ssm put-parameter --name "/$PROJECT/DB_USER" \
  --type SecureString --value "seu_user_neon" --overwrite

aws ssm put-parameter --name "/$PROJECT/DB_PASSWORD" \
  --type SecureString --value "sua_senha_neon" --overwrite

# Preenchido depois do primeiro apply, quando a URL do CloudFront existir.
aws ssm put-parameter --name "/$PROJECT/PUBLIC_URL" \
  --type String --value "https://PREENCHER.cloudfront.net" --overwrite

aws ssm get-parameters-by-path --path "/$PROJECT/" --query "Parameters[].Name"
```

> O nome do parâmetro vira o nome da variável de ambiente (`basename`), então
> `/cloudability-chat/DB_URL` → `DB_URL`. Para adicionar uma variável nova ao container, basta criar
> o parâmetro e rodar o deploy — sem mexer no Terraform.

### 10.2 Aplicar

```bash
cd infra/terraform

terraform init        # baixa o provider e conecta no bucket do state
terraform fmt -check
terraform validate
terraform plan -out=tfplan
```

Leia o plano. Você deve ver **~25 recursos para criar** e **nenhum para destruir**. Confira em
especial:

- nenhum `aws_nat_gateway`, `aws_lb` ou `aws_db_instance` na lista;
- `aws_instance.app` com `instance_type = "t3.micro"`;
- os dois buckets com `block_public_access` ligado.

```bash
terraform apply tfplan
```

O CloudFront leva de 5 a 15 minutos. Ao final:

```bash
terraform output
terraform output -raw url_publica
```

Guarde a URL e atualize o parâmetro:

```bash
aws ssm put-parameter --name "/$PROJECT/PUBLIC_URL" --type String \
  --value "$(terraform output -raw url_publica)" --overwrite
```

---

## 11. Passo 6 — O primeiro deploy, na mão

Agora a infraestrutura existe e está vazia. Vamos preenchê-la uma vez manualmente — é assim que
você descobre problemas de verdade antes de o pipeline escondê-los.

### 11.1 Material para o S3

```bash
cd "C:/Users/Letícia Viana/OneDrive/Documentos/Estudos_IBM"
BUCKET=$(cd infra/terraform && terraform output -raw material_bucket)

# Os .mp4 ficam de fora: a ingestão lê as legendas .vtt, não os vídeos.
aws s3 sync ./ingestao "s3://$BUCKET/modules/cloudability" \
  --exclude "*.mp4" --exclude "*.MP4"

aws s3 sync ./.claude/agents "s3://$BUCKET/modules/cloudability/agents"

# O compose de produção
aws s3 cp infra/compose.prod.yml "s3://$BUCKET/config/compose.prod.yml"

aws s3 ls "s3://$BUCKET/modules/cloudability/"
```

### 11.2 Imagem do backend no ECR

```bash
ECR=$(cd infra/terraform && terraform output -raw ecr_repository_url)
REGISTRY="${ECR%%/*}"

aws ecr get-login-password --region us-east-1 |
  docker login --username AWS --password-stdin "$REGISTRY"

cd ibm
# --platform linux/amd64 é OBRIGATÓRIO se você builda num Mac ARM.
docker build --platform linux/amd64 -t "$ECR:v1" -t "$ECR:latest" .
docker push "$ECR:v1"
docker push "$ECR:latest"
```

### 11.3 Frontend no S3

```bash
WEB=$(cd infra/terraform && terraform output -raw web_bucket)

cd web
npm ci
npm run build

# Assets têm hash no nome → cache eterno.
aws s3 sync dist/ "s3://$WEB/" --delete \
  --cache-control "public,max-age=31536000,immutable" \
  --exclude index.html

# index.html nunca em cache: é ele que aponta para os assets novos.
aws s3 cp dist/index.html "s3://$WEB/index.html" \
  --cache-control "no-cache,no-store,must-revalidate"
```

### 11.4 Subir a aplicação na instância

Abra um shell **sem SSH**, pelo Session Manager:

```bash
INSTANCE=$(cd infra/terraform && terraform output -raw instance_id)
aws ssm start-session --target "$INSTANCE"
```

Dentro da instância:

```bash
sudo -i
tail -50 /var/log/cloud-init-output.log     # o user_data terminou bem?

# O teste que prova que a IAM Role funciona — nenhuma chave envolvida.
aws sts get-caller-identity                  # deve mostrar .../cloudability-chat-app/i-...
aws s3 ls s3://SEU-BUCKET-MATERIAL/modules/

/opt/app/deploy.sh v1

docker compose -f /opt/app/compose.prod.yml ps
docker compose -f /opt/app/compose.prod.yml logs -f api
```

O que você quer ver no log do `api`:

```
Flyway ... Successfully applied 2 migrations to schema "public"
Started IbmApplication in 18.4 seconds
```

E o teste local dentro da instância:

```bash
curl -s localhost/api/knowledge/status
```

> **A primeira subida é lenta**: o container de embeddings baixa ~500 MB de modelo. Acompanhe com
> `docker compose logs -f embeddings` e espere o `Ready`. Do segundo boot em diante o modelo já está
> no volume.

### 11.5 Indexar o material

Duas rotas:

1. **Pela interface** (normal): abra a URL do CloudFront, entre na administração, escolha o módulo
   `cloudability` e clique em *Sincronizar material*. Num `t3.micro` isso leva bastante tempo — é
   CPU de crédito e o TEI está com swap.
2. **Do seu laptop** (recomendada no primeiro deploy): aponte o `.env` local para o **Neon** e para
   o **bucket real**, rode a sincronização na sua máquina (que tem CPU e RAM de sobra) e o servidor
   sobe com a base já pronta. Os vetores estão no banco, não na instância.

Para a rota 2, no `.env` local:

```bash
DB_URL=jdbc:postgresql://ep-nome.us-east-1.aws.neon.tech/cloudability?sslmode=require
DB_USER=...
DB_PASSWORD=...
MATERIAL_BUCKET=seu-bucket-material
# MATERIAL_ENDPOINT vazio/removido = S3 de verdade
AWS_REGION=us-east-1
ACCESS_DEFAULT_LEVEL=admin
```

e suba só `embeddings` + `api` (sem `db`, sem `localstack`).

### 11.6 Abrir no navegador

```bash
cd infra/terraform && terraform output -raw url_publica
```

Se o chat responder **token a token** (e não de uma vez só no final), o streaming está passando pelo
CloudFront corretamente — que é o ponto mais frágil desta arquitetura.

---

## 12. Passo 7 — GitHub Actions

### 12.1 Como o OIDC funciona, sem mistério

```
push na main
     │
     ▼
GitHub Actions ──── "me dá um token" ───► GitHub OIDC
     │                                        │
     │◄──── token assinado: "sou o workflow do repo X, branch main"
     │
     ├──── AssumeRoleWithWebIdentity(token) ──► AWS STS
     │                                             │ confere a assinatura
     │                                             │ confere a condição `sub`
     │◄──── credenciais temporárias (1 h) ─────────┘
     ▼
aws ecr push / s3 sync / ssm send-command
```

O que você precisa garantir no workflow:

```yaml
permissions:
  id-token: write   # sem isto o GitHub não emite o token e o erro é confuso
  contents: read
```

### 12.2 Secrets e variables do repositório

Rode isto na raiz do projeto (precisa do `gh` autenticado):

```bash
cd infra/terraform

gh secret set AWS_ROLE_ARN     --body "$(terraform output -raw github_role_arn)"
gh secret set EC2_INSTANCE_ID  --body "$(terraform output -raw instance_id)"
gh secret set CLOUDFRONT_ID    --body "$(terraform output -raw cloudfront_distribution_id)"

gh variable set AWS_REGION       --body "us-east-1"
gh variable set ECR_REPOSITORY   --body "$(terraform output -raw ecr_repository_url)"
gh variable set WEB_BUCKET       --body "$(terraform output -raw web_bucket)"
gh variable set MATERIAL_BUCKET  --body "$(terraform output -raw material_bucket)"
```

> `secrets` são mascarados no log; `variables` não. IDs de recurso não são segredo, mas o ARN da
> role e o ID da instância ficam melhores como secret — menos informação exposta em log público.

### 12.3 `.github/workflows/ci.yml` — roda em todo push e PR

```yaml
name: CI

on:
  push:
    branches: [main, develop]
  pull_request:

jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: temurin
          cache: maven          # ~2 min a menos por execução
      - name: Testes e empacotamento
        working-directory: ./ibm
        run: mvn -B verify

  frontend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: web/package-lock.json
      - working-directory: ./web
        run: |
          npm ci
          npm run build    # `tsc -b` roda junto: erro de tipo reprova o PR

  terraform:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: hashicorp/setup-terraform@v3
      # Sem credenciais: `fmt` e `validate` não falam com a AWS.
      - working-directory: ./infra/terraform
        run: |
          terraform fmt -check -recursive
          terraform init -backend=false
          terraform validate
```

### 12.4 `.github/workflows/deploy.yml` — roda no push para `main`

```yaml
name: Deploy

on:
  push:
    branches: [main]
  workflow_dispatch:        # permite disparar na mão pela aba Actions

permissions:
  id-token: write
  contents: read

# Dois deploys ao mesmo tempo na mesma instância dão estado inconsistente.
concurrency:
  group: deploy-production
  cancel-in-progress: false

jobs:
  # ------------------------------------------------------------------ backend
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: ${{ vars.AWS_REGION }}

      - uses: aws-actions/amazon-ecr-login@v2

      - name: Build e push da imagem
        working-directory: ./ibm
        run: |
          set -euo pipefail
          IMAGE="${{ vars.ECR_REPOSITORY }}"
          TAG="${GITHUB_SHA::7}"
          # linux/amd64: o EC2 é t3.micro (x86). Imagem ARM não sobe lá.
          docker build --platform linux/amd64 -t "$IMAGE:$TAG" -t "$IMAGE:latest" .
          docker push "$IMAGE:$TAG"
          docker push "$IMAGE:latest"
          echo "TAG=$TAG" >> "$GITHUB_ENV"

      - name: Publicar o compose de producao
        run: |
          aws s3 cp infra/compose.prod.yml \
            "s3://${{ vars.MATERIAL_BUCKET }}/config/compose.prod.yml"

      - name: Atualizar a instancia (SSM, sem SSH)
        run: |
          set -euo pipefail
          CMD_ID=$(aws ssm send-command \
            --instance-ids "${{ secrets.EC2_INSTANCE_ID }}" \
            --document-name AWS-RunShellScript \
            --comment "deploy ${GITHUB_SHA::7}" \
            --parameters "commands=[\"/opt/app/deploy.sh ${TAG}\"]" \
            --query "Command.CommandId" --output text)

          echo "comando: $CMD_ID"

          # Espera terminar e FALHA o job se o deploy falhar. Sem isto, o
          # workflow fica verde mesmo com o container quebrado no servidor.
          aws ssm wait command-executed \
            --command-id "$CMD_ID" \
            --instance-id "${{ secrets.EC2_INSTANCE_ID }}" || true

          aws ssm get-command-invocation \
            --command-id "$CMD_ID" \
            --instance-id "${{ secrets.EC2_INSTANCE_ID }}" \
            --query "{status:Status,out:StandardOutputContent,err:StandardErrorContent}" \
            --output text

          STATUS=$(aws ssm get-command-invocation \
            --command-id "$CMD_ID" \
            --instance-id "${{ secrets.EC2_INSTANCE_ID }}" \
            --query "Status" --output text)
          [ "$STATUS" = "Success" ] || exit 1

      - name: Health check
        run: |
          set -euo pipefail
          URL="https://$(aws cloudfront get-distribution \
            --id ${{ secrets.CLOUDFRONT_ID }} \
            --query 'Distribution.DomainName' --output text)/api/knowledge/status"
          for i in $(seq 1 20); do
            if curl -fsS "$URL" > /dev/null; then echo "no ar"; exit 0; fi
            echo "tentativa $i..."; sleep 15
          done
          echo "backend nao respondeu depois do deploy"; exit 1

  # ----------------------------------------------------------------- frontend
  frontend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: web/package-lock.json

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: ${{ vars.AWS_REGION }}

      - working-directory: ./web
        run: |
          npm ci
          npm run build

      - name: Publicar no S3
        working-directory: ./web
        run: |
          aws s3 sync dist/ "s3://${{ vars.WEB_BUCKET }}/" --delete \
            --cache-control "public,max-age=31536000,immutable" \
            --exclude index.html
          aws s3 cp dist/index.html "s3://${{ vars.WEB_BUCKET }}/index.html" \
            --cache-control "no-cache,no-store,must-revalidate"

      - name: Invalidar o cache do index.html
        run: |
          # Só o index.html: os assets têm hash no nome e nunca precisam de
          # invalidação. Invalidar "/*" queima a cota de 1.000 caminhos/mês.
          aws cloudfront create-invalidation \
            --distribution-id "${{ secrets.CLOUDFRONT_ID }}" \
            --paths "/index.html"
```

### 12.5 `.github/workflows/terraform.yml` — opcional, e com cuidado

Para um projeto de uma pessoa, **rodar o Terraform do seu laptop é mais simples e mais seguro**. O
que vale automatizar é o `plan` em pull request, para você ver o impacto antes de mergear:

```yaml
name: Terraform Plan

on:
  pull_request:
    paths: ['infra/terraform/**']

permissions:
  id-token: write
  contents: read
  pull-requests: write

jobs:
  plan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: hashicorp/setup-terraform@v3
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          # Role SEPARADA, só de leitura (ReadOnlyAccess + leitura do bucket
          # do state). NUNCA dê `apply` automático a um workflow.
          role-to-assume: ${{ secrets.AWS_PLAN_ROLE_ARN }}
          aws-region: ${{ vars.AWS_REGION }}

      - working-directory: ./infra/terraform
        run: |
          terraform init
          terraform plan -no-color -lock=false | tee plan.txt

      - uses: actions/github-script@v7
        with:
          script: |
            const fs = require('fs');
            const plan = fs.readFileSync('infra/terraform/plan.txt', 'utf8');
            await github.rest.issues.createComment({
              issue_number: context.issue.number,
              owner: context.repo.owner,
              repo: context.repo.repo,
              body: '```\n' + plan.slice(0, 60000) + '\n```'
            });
```

> **Nunca automatize `terraform apply`** num projeto pessoal. Um `plan` mal lido em um pipeline
> automático destrói o bucket com o seu material — e o `destroy` não pergunta.

---

## 13. Passo 8 — Validação fim a fim

Marque um por um. Se algum falhar, a seção 16 tem o remédio.

| # | Verificação | Como |
|---|---|---|
| 1 | A infra existe e bate com o código | `terraform plan` → *No changes* |
| 2 | A instância está sob o SSM | `aws ssm describe-instance-information` lista o ID |
| 3 | A IAM Role funciona (sem chave) | na instância: `aws sts get-caller-identity` mostra `.../app/i-...` |
| 4 | Role visível **dentro** do container | `docker compose exec api sh -c 'echo $AWS_REGION'` + log sem `Unable to load credentials` |
| 5 | O banco migrou | log do `api`: `Successfully applied 2 migrations` |
| 6 | pgvector ativo | no Neon: `SELECT count(*) FROM kb_chunk;` |
| 7 | O site abre | `https://<dist>.cloudfront.net` |
| 8 | Rota de SPA funciona | abrir uma rota interna e dar F5 — não pode dar 404 |
| 9 | A API responde pelo CloudFront | `curl https://<dist>.cloudfront.net/api/knowledge/status` |
| 10 | **A API NÃO responde direto** | `curl http://<EIP>/api/knowledge/status` → tem que dar **timeout** |
| 11 | O streaming chega token a token | perguntar algo no chat e observar o texto aparecendo |
| 12 | Deploy automático | `git push` na main → Actions verde → mudança no ar |
| 13 | Nenhum segredo no repositório | `git log -p | grep -i "sk-ant"` → vazio |
| 14 | O budget existe | e-mail de confirmação da AWS recebido |

O item **10** é o mais importante da lista: se a API responde direto pelo IP, qualquer pessoa pode
usar a sua chave da Anthropic.

---

## 14. O que muda em relação à FASE-2-AWS.md

| Tema | FASE-2-AWS.md (planejamento) | Esta doc (execução) | Por quê |
|---|---|---|---|
| Instância | `t4g.small` ARM (Graviton) | **`t3.micro` x86** | A promoção de `t4g.small` grátis acabou; `t3.micro` é o que o free tier cobre hoje. Consequência: `--platform linux/amd64` no build. |
| TLS | Caddy + Let's Encrypt + domínio próprio | **CloudFront `*.cloudfront.net`** | Sem domínio não há Let's Encrypt. O CloudFront dá HTTPS de graça e ainda elimina o CORS. |
| Provisionamento | comandos `aws` avulsos | **Terraform** | Reprodutível, versionado, e `destroy` de uma vez quando quiser parar de pagar. |
| Embeddings | Voyage AI | **TEI local com swap** (Voyage como evolução) | O adaptador Voyage ainda não existe no código, e `vector(384)` está fixo na migração. |
| Banco | Neon, com restrição de IP | **Neon, sem restrição de IP** | *IP Allow* é recurso pago no Neon; a proteção é a senha, guardada no SSM. |
| Porta 80 aberta | `0.0.0.0/0` | **prefix list do CloudFront** | Impede acesso direto à API, que hoje não tem autenticação. |
| Acesso ao shell | SSH na porta 22 | **SSM Session Manager** | Sem porta aberta, sem chave privada para vazar. |

Se um dia você comprar um domínio, o caminho de volta ao desenho original é curto: Route 53 +
certificado ACM em `us-east-1` + `aliases` na distribuição. O resto não muda.

---

## 15. Operação do dia a dia

### 15.1 Comandos que você vai usar sempre

```bash
# Shell na instância (sem SSH)
aws ssm start-session --target $(terraform output -raw instance_id)

# Logs sem entrar na instância
aws ssm send-command --instance-ids <ID> --document-name AWS-RunShellScript \
  --parameters 'commands=["docker compose -f /opt/app/compose.prod.yml logs --tail 100 api"]' \
  --query "Command.CommandId" --output text

# Redeploy da última imagem
aws ssm send-command --instance-ids <ID> --document-name AWS-RunShellScript \
  --parameters 'commands=["/opt/app/deploy.sh latest"]'

# Desligar para economizar / religar
aws ec2 stop-instances  --instance-ids <ID>
aws ec2 start-instances --instance-ids <ID>
```

> Depois de um `stop`/`start`, o **EIP continua o mesmo** — por isso o CloudFront não quebra. Sem
> EIP, o DNS público mudaria e o site cairia.

### 15.2 Backup

- **Banco:** o Neon faz *point-in-time restore* de 24 h no free tier. Para um dump seu:
  `pg_dump "postgresql://user:senha@host/cloudability?sslmode=require" -Fc -f backup.dump`
- **Material:** o bucket tem versionamento. Cópia local:
  `aws s3 sync s3://<material> ./backup-material`
- **Infra:** está no Git. O state está no S3 versionado.

### 15.3 Onde olhar o custo

`Cost Explorer → Group by: Tag → Project`. Como o `default_tags` marca tudo, você vê este projeto
isolado do resto da conta. Filtre por `Service` para achar o vilão quando algo subir.

### 15.4 O item de segurança ainda em aberto

O backend **não tem autenticação**. Nesta arquitetura, isso significa: quem tiver a URL do
CloudFront usa a sua chave da Anthropic.

Mitigações, da mais rápida à mais correta:

1. **Não divulgue a URL** e mantenha `ACCESS_DEFAULT_LEVEL=student` (o compose de produção já faz
   isso) — assim ninguém apaga seu material, mas ainda gasta seus créditos.
2. **CloudFront Function** verificando um cabeçalho/cookie compartilhado — algumas linhas de
   JavaScript, grátis até 2 M invocações/mês.
3. **Login de verdade** (Cognito ou usuários no Postgres). O ponto de mudança no código é um só:
   `AccessResolver.resolve(...)` passa a ler o usuário autenticado.

Enquanto (3) não existe, **um limite de gasto no console da Anthropic é a rede de proteção real**.

---

## 16. Troubleshooting

| Sintoma | Causa provável | Solução |
|---|---|---|
| `exec format error` no container | Imagem ARM num EC2 x86 | Rebuild com `--platform linux/amd64` |
| `Unable to load credentials` no log da API | IMDSv2 sem salto extra para o container | `http_put_response_hop_limit = 2` (já está no `ec2.tf`); confira com `aws ec2 describe-instances` |
| CloudFront devolve **502/504** em `/api/*` | Container caiu, ou o SG não deixa o CloudFront entrar | `docker compose ps` na instância; confira a regra da prefix list |
| CloudFront devolve **403** no site | Bucket policy / OAC | A `aws_s3_bucket_policy.web` precisa citar o ARN da distribuição |
| F5 numa rota interna dá **404** | Falta o `custom_error_response` | Confira os blocos 403/404 → `/index.html` com status 200 |
| Resposta do chat chega **toda de uma vez** | Buffering/compressão no caminho | `compress = false` no behavior `/api/*` e `CachingDisabled`; confira se o backend manda `text/event-stream` |
| Stream corta em ~60 s | `origin_read_timeout` do CloudFront | Faça o backend emitir heartbeat SSE (`:` a cada 15 s); o teto sem pedir aumento é 60 s |
| Container morre sozinho (`OOMKilled`) | 1 GB de RAM | Confira o swap (`free -h`), baixe `-Xmx`, ou vá de `t3.small` |
| Instância muito lenta depois de um tempo | Créditos de CPU do `t3` esgotados | `CPUCreditBalance` no CloudWatch; considere `t3.small` ou modo *unlimited* (cobra!) |
| `Connection is not available, request timed out` | Cold start do Neon | Aumente `connection-timeout` do Hikari (seção 6.5) |
| Flyway: `type "vector" does not exist` | `CREATE EXTENSION vector` não rodou | Rode no SQL Editor do Neon |
| Actions: `Not authorized to perform sts:AssumeRoleWithWebIdentity` | Condição `sub` não bate | O `sub` tem que ser exatamente `repo:owner/repo:ref:refs/heads/main`. Deploy a partir de tag ou outra branch exige outra condição |
| Actions: `Credentials could not be loaded` | Falta `permissions: id-token: write` | Adicione no workflow |
| `ECR: no space left` / erro de cota | 500 MB do free tier estourados | A lifecycle policy resolve daqui pra frente; limpe as antigas com `aws ecr batch-delete-image` |
| `terraform apply`: `Error acquiring the state lock` | Um apply anterior morreu | `terraform force-unlock <ID>` — só se tiver certeza de que ninguém está aplicando |
| Site atualiza mas mostra versão antiga | Cache do `index.html` | Confira o `--cache-control no-cache` e a invalidação |

### Um comando que resolve metade dos casos

```bash
aws ssm start-session --target <ID>
sudo -i
free -h                                                   # swap em uso? OOM?
docker compose -f /opt/app/compose.prod.yml ps
docker compose -f /opt/app/compose.prod.yml logs --tail 200 api
cat /opt/app/.env | sed 's/=.*/=***/'                     # as variáveis chegaram?
curl -s localhost/api/knowledge/status
```

---

## 17. Como desligar tudo sem deixar rastro cobrando

```bash
cd infra/terraform
terraform destroy
```

O `destroy` **falha de propósito** em buckets com conteúdo — é uma proteção, não um bug. Esvazie
antes, e só depois de ter uma cópia local:

```bash
aws s3 sync s3://<material> ./backup-material      # primeiro, o backup
aws s3 rm s3://<material> --recursive
aws s3 rm s3://<web> --recursive
terraform destroy
```

O que **não** é destruído e você precisa cuidar à mão:

- o **bucket do state** (criado fora do Terraform) — mantenha, é barato e salva você;
- o **projeto no Neon** — apague pelo dashboard se não for mais usar;
- os **parâmetros no SSM**, se quiser limpar:
  `aws ssm delete-parameters --names /cloudability-chat/ANTHROPIC_API_KEY ...`;
- as **imagens no ECR** são apagadas junto com o repositório;
- **revogue a chave da Anthropic** se o projeto acabou de vez.

Depois, confirme em `Billing → Bills` no mês seguinte que a conta zerou.

---

## Apêndice — mapa rápido de variáveis de ambiente

| Variável | Local (`docker-compose.yml`) | Produção (SSM → `compose.prod.yml`) |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://db:5432/cloudability` | Neon, com `?sslmode=require` |
| `MATERIAL_ENDPOINT` | `http://localstack:4566` | **ausente** (S3 de verdade) |
| `AWS_ACCESS_KEY_ID` | `test` | **ausente** (IAM Role da instância) |
| `MATERIAL_CREATE_BUCKET` | `true` | `false` (a role nem tem permissão) |
| `EMBEDDING_PROVIDER` | `tei` | `tei` (ver seção 4) |
| `CORS_ORIGINS` | `http://localhost,...` | URL do CloudFront |
| `ACCESS_DEFAULT_LEVEL` | `admin` | **`student`** |
| `ANTHROPIC_MODEL` | `claude-opus-5` | `claude-sonnet-5` (~60% mais barato) |
| `KB_AUTO_INGEST` | `false` | `false` |
