# Fase 2 — Passo a passo do deploy (Terraform · GitHub Actions · Neon · domínio)

> Este é o **runbook executável** da fase 2. O [FASE-2-AWS.md](FASE-2-AWS.md) compara arquiteturas e
> explica *por quê*; aqui as decisões já estão fechadas e o que existe é a sequência de comandos, na
> ordem certa, para sair do `docker compose` local e chegar em `https://chatbrain.app.br` gastando
> perto de zero.
>
> Leia a seção 1 antes de executar qualquer coisa: ela contém as três decisões que mudam tudo o que
> vem depois.

---

## Índice

| # | Etapa | Onde | Tempo |
|---|---|---|---|
| [1](#1-as-decisoes-fechadas) | As decisões fechadas | leitura | 10 min |
| [2](#2-o-que-e-gratis-de-verdade) | O que é grátis de verdade | leitura | 10 min |
| [3](#3-pre-requisitos) | Pré-requisitos (contas e CLIs) | sua máquina | 30 min |
| [4](#4-passo-1--banco-no-neon) | **Passo 1** — Banco no Neon | neon.tech | 20 min |
| [5](#5-passo-2--dominio-e-dns) | **Passo 2** — Domínio e DNS | Registro.br | 30 min + espera |
| [6](#6-passo-3--o-que-subir-para-o-s3) | **Passo 3** — O que subir para o S3 | sua máquina | 30 min |
| [7](#7-passo-4--terraform) | **Passo 4** — Terraform (S3, IAM Role, EC2, ECR) | sua máquina | 1 h |
| [8](#8-passo-5--segredos-no-ssm) | **Passo 5** — Segredos no SSM | AWS CLI | 10 min |
| [9](#9-passo-6--primeiro-deploy-na-mao) | **Passo 6** — Primeiro deploy na mão | EC2 | 1 h |
| [10](#10-passo-7--indexar-o-material) | **Passo 7** — Indexar o material | sua máquina | 30 min |
| [11](#11-passo-8--github-actions) | **Passo 8** — GitHub Actions (CI + CD) | GitHub | 1 h |
| [12](#12-passo-9--autenticacao) | **Passo 9** — Autenticação | EC2 | 20 min |
| [13](#13-passo-10--alarmes-de-custo) | **Passo 10** — Alarmes de custo | AWS + Anthropic | 15 min |
| [14](#14-troubleshooting) | Troubleshooting | — | — |
| [15](#15-checklist-final) | Checklist final | — | — |

---

<a id="1-as-decisoes-fechadas"></a>
## 1. As decisões fechadas

### 1.1 Uma máquina só — o Caddy serve o front e faz proxy da API

A restrição que manda no desenho está no código:

```ts
// web/src/api.ts
modules: () => fetch('/api/modules').then(json<StudyModule[]>),
```

Todas as chamadas usam **caminho relativo**. Elas só funcionam se o HTML e a API chegarem ao
navegador pela **mesma origem** — mesmo host, mesma porta. Qualquer desenho que ponha o front num
domínio e a API em outro exige reescrever o `api.ts` com uma base URL configurável e ligar CORS.

Há **duas** formas de manter a mesma origem, e as duas são legítimas:

| | **Caddy no EC2** (este documento) | **CloudFront** ([FASE-2-EXECUCAO-AWS.md](FASE-2-EXECUCAO-AWS.md)) |
|---|---|---|
| Como mantém a origem única | o Caddy serve `/srv/web` e faz proxy de `/api/*` | uma distribuição com dois *behaviors*: `/*` → bucket S3, `/api/*` → EC2 |
| Endereço | **o seu domínio** | `d111111abcdef8.cloudfront.net` |
| TLS | Let's Encrypt, automático, grátis | certificado do CloudFront, grátis |
| Domínio próprio | é o objetivo | exige ACM em `us-east-1` + alias na distribuição |
| Teto de streaming SSE | nenhum | `origin_read_timeout` de **60 s** sem pedir aumento de cota — cada chunk reinicia a contagem, mas uma pausa longa do modelo corta a resposta |
| Peças para operar | 1 (a instância) | 3 (bucket, distribuição, instância) + invalidação a cada deploy |

**Decisão deste documento: Caddy.** Porque o requisito aqui é **ter domínio próprio**, e com domínio
próprio o CloudFront deixa de ser o atalho para o HTTPS e passa a ser uma camada a mais para
configurar, com um teto de 60 s no caminho do streaming. O Caddy resolve TLS sozinho, sem CORS, sem
invalidação e sem custo.

> **Se você desistir de comprar o domínio**, o CloudFront volta a ser a melhor escolha — é ele que
> dá HTTPS sem domínio nenhum. Nesse caso siga o [FASE-2-EXECUCAO-AWS.md](FASE-2-EXECUCAO-AWS.md),
> que documenta esse caminho inteiro.

> O S3 continua no desenho, com dois papéis bem distintos. Ver [1.3](#13-dois-buckets-com-papeis-diferentes).

### 1.2 A instância: `t3.micro` e o problema de RAM

O free tier cobre `t2.micro`/`t3.micro` — **1 GB de RAM**. Nessa 1 GB precisariam caber:

| Container | RAM típica |
|---|---|
| `api` (Spring Boot + PDFBox + POI) | 400–600 MB |
| `embeddings` (TEI, `multilingual-e5-small`) | 700–900 MB |
| `caddy` | ~20 MB |

Não cabe — **se você tentar indexar o material no servidor**. A saída é fazer a ingestão pesada **da
sua máquina** ([passo 7](#10-passo-7--indexar-o-material)), com o `.env` local apontando para o Neon
e para o bucket real. Aí o servidor só precisa gerar o vetor de **uma frase por pergunta**, o que é
barato. Com um **swap de 4 GB** (criado pelo `user_data` do Terraform) e limites de memória
explícitos no compose, a `t3.micro` aguenta.

| Opção | Custo | Quando escolher |
|---|---|---|
| **`t3.micro` + swap 4 GB** ← recomendada | **US$ 0** no free tier · ~US$ 7,60/mês depois | é o caminho deste documento |
| `t3.small` (2 GB) | ~US$ 15/mês, nunca grátis | se a API reiniciar sozinha por falta de memória. É **uma linha** no Terraform: `instance_type = "t3.small"` |

> **A terceira opção — embeddings por API (Voyage, OpenAI) — exige código novo.** Hoje só existe o
> `TeiEmbeddingAdapter`, e a migração `V1__init.sql` fixa `vector(384)`. Trocar de provedor significa
> escrever um adaptador, criar uma `V3__` com a nova dimensão e **reindexar tudo**. Não faça agora:
> é otimização para depois de estar no ar.

<a id="13-dois-buckets-com-papeis-diferentes"></a>
### 1.3 Dois buckets, com papéis diferentes

Não misture os dois. É o que mantém o pipeline longe do seu material:

| Bucket | Conteúdo | Quem lê | Quem escreve |
|---|---|---|---|
| `<prefixo>-material` | o material de estudo, em `modules/<slug>/...` | a **IAM Role do EC2** | a Role do EC2 (upload pela tela de admin) e você, do seu terminal |
| `<prefixo>-artifacts` | o `dist/` do front e os arquivos de deploy | a Role do EC2 | a **Role do GitHub Actions** |

> **O bucket do material é estado, não artefato.** O pipeline nunca escreve nele. Um
> `aws s3 sync --delete` disparado por engano no CI apagaria o material inteiro.

### 1.4 O desenho final

```
   navegador
       │  https://chatbrain.app.br
       ▼
 ┌─────────────────────────────────────────────┐
 │  EC2 t3.micro · Amazon Linux 2023 · Docker  │
 │                                             │
 │  ┌────────┐   /*      ┌──────────────────┐  │
 │  │ Caddy  │──────────▶│ /srv/web (dist)  │  │
 │  │ TLS    │           └──────────────────┘  │
 │  │ auto   │   /api/*  ┌──────────────────┐  │
 │  │        │──────────▶│ api :8080 (Java) │  │
 │  └────────┘           └────────┬─────────┘  │
 │   :80 :443                     │            │
 │                       ┌────────▼─────────┐  │
 │                       │ embeddings (TEI) │  │
 │                       └──────────────────┘  │
 │  ┌───────────────────────────────────────┐  │
 │  │  IAM Instance Profile                 │  │
 │  │  (nenhuma chave de acesso no disco)   │  │
 │  └────────────────┬──────────────────────┘  │
 └───────────────────┼─────────────────────────┘
                     │
      ┌──────────────┼──────────────┬──────────────────┐
      ▼              ▼              ▼                  ▼
 ┌──────────┐  ┌───────────┐  ┌────────────┐   ┌─────────────────┐
 │ S3       │  │ S3        │  │ SSM Param  │   │ ECR             │
 │ material │  │ artifacts │  │ (segredos) │   │ imagem da API   │
 └──────────┘  └───────────┘  └────────────┘   └─────────────────┘

      Fora da AWS:  Neon (Postgres + pgvector)  ·  API da Anthropic
      DNS:          zona do próprio Registro.br (grátis)
```

---

<a id="2-o-que-e-gratis-de-verdade"></a>
## 2. O que é grátis de verdade (e onde mora a conta surpresa)

### 2.1 Descubra em qual free tier você está — isso mudou

A AWS trocou o modelo em **15 de julho de 2025**:

| | **Conta criada ANTES de 15/07/2025** | **Conta criada DEPOIS** |
|---|---|---|
| Modelo | free tier clássico, **12 meses** | **Free Plan**: US$ 100 em créditos (até US$ 200 completando tarefas de onboarding), válidos por **6 meses ou até acabarem** |
| EC2 | 750 h/mês de `t2.micro`/`t3.micro` grátis | consome dos créditos |
| IPv4 público | 750 h/mês grátis | consome dos créditos |
| EBS | 30 GB grátis | consome dos créditos |
| S3 | 5 GB + 20 mil GET + 2 mil PUT | consome dos créditos |
| Quando acaba | você passa a pagar pelo uso | a conta é encerrada **ou** você migra para o plano pago |

**Como saber:** console AWS → **Billing and Cost Management** → **Free tier**. Se aparecer saldo de
créditos e uma data de expiração, você está no modelo novo.

Nos dois casos a infraestrutura deste documento cabe folgada. No modelo novo, US$ 100 em 6 meses para
um gasto de ~US$ 11/mês significa que **os créditos sobram** — mas marque no calendário a data em que
eles expiram.

**Sempre grátis, nos dois modelos:** IAM e IAM Roles, SSM Parameter Store (Standard), Session
Manager, Security Groups, a VPC padrão, e 100 GB/mês de tráfego de saída.

### 2.2 As cinco armadilhas que geram cobrança

| Armadilha | Custo | Como este documento evita |
|---|---|---|
| **IPv4 público** — desde 01/02/2024 todo IP público custa US$ 0,005/h (~US$ 3,65/mês), inclusive Elastic IP em uso | ~US$ 3,65/mês | coberto pelo free tier nos 12 meses. Depois é inevitável — você precisa de IP fixo. **Um EIP alocado e não associado cobra igual**: se destruir a instância, destrua o EIP |
| **NAT Gateway** | ~US$ 32/mês | **nunca crie um.** O Terraform usa a **VPC padrão**, cujas subnets já são públicas. Se você se pegar criando VPC nova com subnet privada, parou o barco |
| **Route 53 Hosted Zone** | US$ 0,50/mês | a zona DNS do **Registro.br**, que já vem com o domínio |
| **Load Balancer (ALB/NLB)** | ~US$ 16/mês | não existe aqui: o Caddy faz o TLS |
| **ECR sem lifecycle** | US$ 0,10/GB-mês, crescendo a cada deploy | política que mantém só as **3 últimas** imagens (está no Terraform) |

### 2.3 A conta realista

| Item | Nos 12 meses (conta antiga) | Depois |
|---|---|---|
| EC2 `t3.micro` | US$ 0 | US$ 7,60 |
| IPv4 público | US$ 0 | US$ 3,65 |
| EBS 30 GB gp3 | US$ 0 | US$ 2,40 |
| S3 (material ~70 MB + artefatos) | US$ 0 | ~US$ 0,05 |
| ECR (3 imagens) | US$ 0 | ~US$ 0,07 |
| SSM, IAM, tráfego de saída | US$ 0 | US$ 0 |
| Neon (Free plan) | US$ 0 | US$ 0 |
| Domínio `.app.br` | R$ 40/ano ≈ US$ 0,60/mês | idem |
| **Infraestrutura** | **≈ US$ 0,60/mês** | **≈ US$ 14/mês** |
| API da Anthropic | **variável — é o item que pesa** | idem |

> **A infraestrutura não é o seu risco financeiro; a API da Anthropic é.** Uma pergunta com RAG
> consome 4–6 mil tokens de entrada. Sem autenticação e com a URL pública, qualquer um gasta os seus
> créditos. O [passo 9](#12-passo-9--autenticacao) não é opcional.

---

<a id="3-pre-requisitos"></a>
## 3. Pré-requisitos

### 3.1 Contas

| Conta | Para quê | Custo |
|---|---|---|
| **AWS** | EC2, S3, IAM, ECR, SSM | free tier / créditos |
| **Neon** ([neon.tech](https://neon.tech)) | Postgres + pgvector | Free plan |
| **Registro.br** (ou outra registradora) | o domínio | R$ 40/ano no `.app.br` |
| *(Cloudflare)* | DNS — **opcional**, ver [5.3](#5-passo-2--dominio-e-dns) | grátis |
| **GitHub** | repositório + Actions | grátis em repositório público; 2.000 min/mês no privado |
| **Anthropic Console** | a API key | pré-pago |

> **O repositório precisa existir no GitHub antes do passo 8.** Este projeto ainda não é um
> repositório git (`git init` não foi rodado). Faça isso antes — e confira que o `.gitignore` já
> barra o `.env`, o que ele faz.

### 3.2 Ferramentas na sua máquina

```powershell
# Windows, via winget
winget install Amazon.AWSCLI
winget install Hashicorp.Terraform
winget install Amazon.SessionManagerPlugin
```

Confira:

```powershell
aws --version          # >= 2.15
terraform -version     # >= 1.9
docker --version
```

### 3.3 Credenciais da AWS para o Terraform

O Terraform roda **da sua máquina**, com credenciais suas. O mínimo aceitável é **não usar a conta
root**:

1. Console AWS → **IAM** → **Users** → **Create user** → nome `terraform-admin`.

   Ao marcar **"Fornecer acesso ao console"**, a AWS pergunta o tipo de usuário e **recomenda o
   Centro de Identidade**. Para este projeto, escolha **"Quero criar um usuário do IAM"** — e a
   justificativa está no próprio texto da tela: usuário do IAM é indicado justamente quando você
   precisa de **acesso programático por chaves de acesso**, que é exatamente o que o Terraform usa.

   | Opção | Quando é a certa |
   |---|---|
   | **Quero criar um usuário do IAM** ← use esta | conta única, uma pessoa, e você precisa de access key para uma ferramenta (Terraform, CLI) |
   | Especificar um usuário no Centro de Identidade | várias contas AWS, ou mais de uma pessoa, ou quando você quer eliminar a chave de longa duração da máquina |

   Em seguida vem a tela **Senha do console**:

   | Campo | O que escolher |
   |---|---|
   | Senha gerada automaticamente / **personalizada** | **Personalizada**, gerada no seu gerenciador de senhas (16+ caracteres). A automática só aparece uma vez, na tela final — se você fechar antes de copiar, tem que resetar |
   | *"Os usuários devem criar uma nova senha na próxima sessão"* | **desmarque** — essa opção existe para quando um admin cria o usuário **para outra pessoa** e não deve saber a senha final. Aqui o usuário é seu |

   > Esta senha é só do **login no console**. Ela não tem relação com a access key que o Terraform
   > usa — essa vem depois, no passo 4.

   > **O Centro de Identidade é de fato mais seguro** — ele entrega credenciais temporárias por
   > `aws sso login`, então nenhuma chave permanente fica no seu disco. O custo é montar
   > Organizations + *permission set* + atribuição à conta, e rodar `aws sso login` a cada sessão.
   > Vale a pena no dia em que houver uma segunda conta ou uma segunda pessoa; para a fase 2, o
   > usuário do IAM com MFA, e a chave apagada no fim, é proporcional ao risco.
2. **Set permissions** → **Attach policies directly** → `AdministratorAccess` (o Terraform cria IAM
   Roles; políticas mais estreitas dão trabalho desproporcional aqui) → **Create user**.

3. **Agora sim, o MFA — ele não existe no assistente de criação.** É por isso que a tela de revisão
   não mostra nada sobre MFA: o assistente tem só três etapas (detalhes, permissões, revisão). O MFA
   se adiciona **depois que o usuário existe**:

   **IAM → Users → `terraform-admin` → aba `Security credentials` → seção *Multi-factor
   authentication (MFA)* → `Assign MFA device`.**

   | Campo | O que fazer |
   |---|---|
   | Device name | um apelido, ex.: `celular-leticia` |
   | MFA device | **Authenticator app** (Google Authenticator, Authy, Microsoft Authenticator) |
   | Em seguida | escaneie o QR code e digite **dois códigos consecutivos** — o segundo só depois que o app trocar |

4. **Criar a access key** — é ela que o `aws configure` pede.

   **IAM → Users → `terraform-admin` → aba `Security credentials` → seção *Access keys* →
   `Create access key`.**

   | Tela | O que fazer |
   |---|---|
   | Caso de uso | **Command Line Interface (CLI)** — a lista é só informativa: a chave gerada é a mesma em qualquer opção, muda apenas o aviso que a AWS exibe |
   | Aviso de alternativa recomendada | marque a caixa de confirmação e siga — é o mesmo nudge para o Centro de Identidade |
   | Tag de descrição | opcional, ex.: `terraform-fase-2` |
   | Tela final | **`Access key`** (começa com `AKIA`) e **`Secret access key`** (escondida atrás de *Mostrar*) |

   > **Não procure região aqui: o IAM é global.** Usuários, roles e access keys não pertencem a
   > região nenhuma — o seletor no topo do console é ignorado enquanto você está no IAM. A região só
   > começa a importar no `aws configure` e nos recursos que o Terraform cria.

   > ### ⛔ A secret aparece **uma única vez**
   >
   > Saiu dessa tela, acabou: não existe "ver de novo". Copie as duas agora — ou clique em
   > **`Download .csv file`**. Se já perdeu, não tem conserto nem problema: apague a chave e crie
   > outra (*Actions → Delete*), leva 30 segundos.

> ### O que o MFA protege — e o que não protege
>
> O MFA cobre o **login no console**. Ele **não** cobre a access key que o Terraform usa: uma chave
> programática funciona sozinha, sem segundo fator. Exigir MFA também na API dá para fazer (política
> com condição `aws:MultiFactorAuthPresent` + `aws sts get-session-token`), mas aí cada `terraform
> apply` passa a exigir um código — trabalhoso demais para o porte deste projeto.
>
> Na prática, para este caso, o que reduz risco de verdade é:
>
> - **MFA na conta root** — essa é a mais importante das duas, e mora em outro lugar: canto superior
>   direito → nome da conta → **Security credentials** → *Multi-factor authentication*;
> - **nunca commitar a access key** — ela vive em `~/.aws/credentials`, fora do repositório;
> - **apagar a chave** em *Security credentials* quando terminar a fase 2. Criar outra leva 30
>   segundos, e chave que não existe não vaza.

```powershell
aws configure --profile cloudability
# AWS Access Key ID:     ...
# AWS Secret Access Key: ...
# Default region name:   us-east-1
# Default output format: json

$env:AWS_PROFILE = "cloudability"
aws sts get-caller-identity
```

> **Qual região usar.** Regra: **a região do Neon manda** — ela é escolhida na criação do projeto e
> **não pode ser alterada depois** ([4.1](#4-passo-1--banco-no-neon)), enquanto a da AWS é uma
> variável do Terraform. Então crie o projeto no Neon primeiro, veja onde ele ficou, e use a mesma
> região aqui (`aws configure`, `variables.tf` e `terraform.tfvars`).
>
> Este documento usa `us-east-1` nos exemplos. Se o seu projeto no Neon estiver em Ohio, troque tudo
> por `us-east-2` — o free tier vale em qualquer região dos EUA e o preço da `t3.micro` é o mesmo.
>
> **Por que não São Paulo:** você quer banco e aplicação na mesma região, e o `sa-east-1` é ~30% mais
> caro por hora de EC2. Os ~120 ms entre o Brasil e os EUA são irrelevantes perto dos segundos que o
> Claude leva para responder.

---

<a id="4-passo-1--banco-no-neon"></a>
## 4. Passo 1 — Banco no Neon

### 4.1 Criar o projeto

1. Entre em [neon.tech](https://neon.tech) e crie a conta (login pelo GitHub serve).

2. **Onde ficam a região e a versão do Postgres.** Os dois campos existem **só no diálogo de criação
   do projeto**, e o fluxo de cadastro não pergunta: ele provisiona o primeiro projeto sozinho, numa
   região padrão. Para escolher, crie um projeto **novo** pelo console:

   **Neon Console → botão `New Project`** (canto superior direito da página de projetos). O diálogo
   tem quatro campos, nesta ordem:

   | Campo | O que preencher |
   |---|---|
   | **Project Name** | `chat-study` (ou o que preferir — o nome não afeta nada) |
   | **Postgres version** | **16** ou **17**; o Neon suporta de 14 a 18 |
   | **Cloud service provider** | **AWS** (não Azure) |
   | **Region** | a mesma que você vai usar na AWS — ex.: `AWS US East (N. Virginia)` ou `AWS US East (Ohio)` |

   Depois **Create Project**.

3. Anote a connection string que aparece na criação. Ela some depois; se perder, **Dashboard →
   Connection Details → Reset password**.

> ### ⛔ Região e versão do Postgres **não** mudam depois
>
> As duas são fixadas na criação. Não existe "trocar a região" nem "atualizar a major version" de um
> projeto existente: o Neon trata os dois casos como **migração para um projeto novo** (Import Data
> Assistant, replicação lógica ou `pg_dump`/`pg_restore`).
>
> **Se o seu projeto nasceu em Ohio**, há duas saídas — e a primeira é quase sempre a certa:
>
> 1. **Deixe o Neon em Ohio e leve a AWS para lá.** Troque `us-east-1` por `us-east-2` no
>    `aws configure`, no `variables.tf` e no `terraform.tfvars`. É uma variável; o free tier vale
>    igual e a `t3.micro` custa o mesmo. **Nada se perde.**
> 2. **Recrie o projeto na Virgínia.** Só vale a pena enquanto o banco está vazio: `New Project`
>    escolhendo a região, e apague o de Ohio depois (**Project settings → Delete project**). Com
>    dados dentro, isso vira migração.
>
> **A versão do Postgres não é crítica aqui.** As migrações `V1__init.sql` e `V2__modules.sql` são
> SQL comum; 16, 17 ou 18 servem. O `pgvector/pgvector:pg16` local é só uma conveniência — divergir
> do Neon não quebra nada.

> ### ⚠️ Ignore o "Set up this Neon project in the current working directory"
>
> Ao criar o projeto, o console oferece uma sequência de comandos — `neon skills`, `neon mcp`,
> `neon link`, `neon config init`, um arquivo `neon.ts` e `neon deploy`. **Nada disso se aplica a
> este projeto.** É o onboarding do Neon para aplicações **JavaScript/TypeScript** que usam os
> serviços deles (Auth, Data API, Functions, Object Storage) e querem configurá-los como código.
>
> Aqui o backend é **Java com Spring Boot e Flyway**, e o que ele precisa do Neon são exatamente
> duas coisas: a **connection string** e a **extensão `vector`**. O schema é criado pelas migrações
> do Flyway, não por um `neon deploy`.
>
> Dois motivos concretos para não rodar:
>
> - **`neon config init`** instala pacotes npm (`@neon/config`, `@neon/env`) e cria um `neon.ts` na
>   pasta atual. Na raiz do repositório isso planta uma toolchain JavaScript num projeto Java; dentro
>   de `web/`, acrescenta dependências de banco a um frontend que nunca fala com o Postgres.
> - **`neon mcp -y`** gera uma **API key do Neon** e a escreve na configuração do seu agente. Essa
>   chave administra a **conta inteira** — criar e apagar projetos, trocar senhas —, não só ler este
>   banco. Se for usar, confirme antes em que arquivo ela caiu e que esse arquivo está no
>   `.gitignore`.
>
> **O que é opcional e pode valer a pena:** instalar só a CLI (`npm i -g neon@latest && neon login`)
> dá acesso a atalhos como `neon connection-string`. E `neon mcp` / `neon skills` deixam o Claude
> Code consultar o banco direto — útil, mas é uma decisão à parte do deploy, com a ressalva da chave
> acima.

### 4.2 Habilitar o pgvector

**Dashboard → SQL Editor**:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
SELECT extversion FROM pg_extension WHERE extname = 'vector';
```

O resto do schema é do Flyway: `V1__init.sql` e `V2__modules.sql` rodam sozinhas no primeiro boot da
API e criam as tabelas, os índices e o módulo `cloudability`.

### 4.3 Montar a URL JDBC — use o endpoint DIRETO

> ### ⛔ O **Project ID** não entra na URL
>
> O identificador que aparece em *Project settings* — algo como `snowy-band-24144134` — serve para a
> **CLI e a API** do Neon. O host da conexão usa outro identificador, o **endpoint ID**, no formato
> `ep-<duas-palavras>-<números>`. Os dois são gerados separadamente: **não dá para deduzir o host a
> partir do Project ID**. Copie a string pronta do console.

**Onde copiar:** no dashboard do projeto, botão **`Connect`** (ou o painel *Connection Details*).
Confira os quatro seletores antes de copiar:

| Seletor | Valor |
|---|---|
| Branch | `production` |
| Database | `neondb` |
| Role | `neondb_owner` |
| **Connection pooling** | **desligado** — veja abaixo |

O console vem com o pooling **ligado** por padrão. Se esquecer, o host copiado tem `-pooler` no meio:

| Endpoint | Formato | Use? |
|---|---|---|
| **Direto** | `ep-xxx-123456.us-east-2.aws.neon.tech` | ✅ **sim** |
| Pooled (PgBouncer) | `ep-xxx-123456-pooler.us-east-2.aws.neon.tech` | ❌ não |

O pooled roda PgBouncer em modo *transaction*, que **não suporta os advisory locks de sessão que o
Flyway usa** para serializar migrações. Com um pool de 10 conexões você não precisa de PgBouncer.

**A conversão.** O Neon entrega uma URL no formato do `psql`; o Spring quer JDBC, com usuário e senha
em campos separados:

```
# o que o Neon te dá
postgresql://neondb_owner:npg_AbC123xyz@ep-xxx-123456.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require
            └──── user ────┘└─ senha ─┘└──────────────── host ───────────────┘└ db ┘
```

Três mudanças:

1. troque `postgresql://` por **`jdbc:postgresql://`**;
2. **tire `usuario:senha@`** da URL — eles viram `DB_USER` e `DB_PASSWORD`;
3. **remova `channel_binding=require`** — é um parâmetro do `libpq`, não do driver JDBC. Mantenha só
   o `sslmode=require`.

```bash
DB_URL=jdbc:postgresql://ep-xxx-123456.us-east-2.aws.neon.tech/neondb?sslmode=require
DB_USER=neondb_owner
DB_PASSWORD=npg_AbC123xyz
```

> Perdeu a senha? Ela só aparece uma vez. **Dashboard → Connection Details → Reset password** gera
> outra — e aí lembre de atualizar o `.env` local **e** o parâmetro `/cloudability/db_password` no
> SSM ([passo 5](#8-passo-5--segredos-no-ssm)).

### 4.4 O ajuste que evita queimar a cota — HikariCP e o scale-to-zero

O Free plan dá **0,5 GB de armazenamento** e **100 CU-horas de compute por mês**, e o compute
**suspende após 5 minutos sem atividade** (não dá para desligar isso).

Aqui está a pegadinha: o `application.yml` configura `maximum-pool-size: 10` e **não** configura
`minimum-idle`. O padrão do HikariCP é `minimum-idle = maximum-pool-size`, ou seja, **ele mantém 10
conexões abertas para sempre**. Conexão aberta é atividade: o Neon nunca suspende, e o compute roda
24/7. A 0,25 CU isso consome as 100 CU-horas em **cerca de 16 dias** — a cota acaba na metade do mês,
todo mês.

**A correção vai no `application.yml`** — diferente dos segredos ([8.1](#8-passo-5--segredos-no-ssm)),
isto é *configuração*, e configuração pertence ao arquivo de configuração. Edite o bloco `hikari`
seguindo o idioma que o arquivo já usa em todo lugar, `${VARIAVEL:padrão}`:

```yaml
# ibm/src/main/resources/application.yml
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/cloudability}
    username: ${DB_USER:cloudability}
    password: ${DB_PASSWORD:cloudability}
    hikari:
      maximum-pool-size: ${DB_POOL_MAX:10}
      # 0 = o pool pode esvaziar. É o que deixa o Neon suspender.
      minimum-idle: ${DB_POOL_MIN_IDLE:0}
      # Devolve a conexão ociosa em 30 s; o Neon suspende 5 min depois.
      idle-timeout: ${DB_POOL_IDLE_TIMEOUT:30000}
      max-lifetime: ${DB_POOL_MAX_LIFETIME:280000}
```

**Por que no arquivo e não só como variável de ambiente.** As três também funcionam como
`SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` etc., pela ligação relaxada do Spring Boot — mas se você
errar uma letra do nome, **nada acontece**: o Spring ignora a variável desconhecida, não há erro, e
você só descobre quando a cota do Neon acabar no dia 16. Com o padrão correto dentro do
`application.yml`, o comportamento certo é o que vem de fábrica, e a variável de ambiente passa a
ser apenas um ajuste opcional.

> Não faz mal no ambiente local: o Postgres do compose não suspende, e abrir uma conexão em
> `localhost` custa milissegundos. Uma configuração só, para os dois ambientes.

Efeito colateral aceitável em produção: a primeira pergunta depois de um período ocioso leva ~1 s a
mais, enquanto o Neon acorda.

> **Dica:** acompanhe em **Neon → Monitoring → Compute hours**. Se o gráfico for uma linha reta em
> vez de picos, alguma coisa está mantendo conexão aberta.

### 4.5 Validar da sua máquina, antes de existir qualquer coisa na AWS

Este é o teste que separa "problema de banco" de "problema de AWS" mais tarde.

> **Antes: o `docker-compose.yml` precisa deixar você apontar para fora.** Na versão original o
> serviço `api` tinha `DB_URL` e `DB_USER` **fixos** no arquivo — só a senha vinha do `.env`. O
> resultado de preencher o `.env` com os dados do Neon era a API continuar falando com o Postgres
> local, agora com a senha errada, e o Flyway morrer com
> `FATAL: password authentication failed for user "cloudability"`. As três linhas precisam aceitar
> substituição:
>
> ```yaml
> # docker-compose.yml, serviço api
>       DB_URL: ${DB_URL:-jdbc:postgresql://db:5432/cloudability}
>       DB_USER: ${DB_USER:-cloudability}
>       DB_PASSWORD: ${DB_PASSWORD:-cloudability}
> ```
>
> Sem nada no `.env`, o padrão continua sendo o banco do compose.

No seu `.env` local:

```bash
DB_URL=jdbc:postgresql://ep-xxx-123456.us-east-1.aws.neon.tech/neondb?sslmode=require
DB_USER=neondb_owner
DB_PASSWORD=sua-senha-do-neon
```

Suba só a API e o resto do compose apontando para o Neon:

```bash
docker compose up -d --build api embeddings localstack material-seed web
docker compose logs -f api | grep -i flyway
```

Você deve ver o Flyway aplicando `V1` e `V2`. Confirme no SQL Editor do Neon:

```sql
SELECT table_name FROM information_schema.tables WHERE table_schema = 'public';
-- espere: flyway_schema_history, kb_chunk, kb_document, conversation, message, study_module
```

> **Guarde a senha do Neon.** Ela vai para o SSM no [passo 5](#8-passo-5--segredos-no-ssm).

---

<a id="5-passo-2--dominio-e-dns"></a>
## 5. Passo 2 — Domínio e DNS

### 5.1 Comprar o domínio

**Opção recomendada — Registro.br** (`.br` — `.com.br`, `.app.br` e afins, R$ 40/ano):

1. [registro.br](https://registro.br) → consulte o nome desejado.
2. Crie a conta com **CPF ou CNPJ** (obrigatório para `.br`; exige titular no Brasil).
3. Finalize o pagamento (boleto ou cartão). **O domínio só fica utilizável depois de ativado** — o
   Registro.br informa a data por e-mail, e pode levar de minutos a alguns dias conforme a forma de
   pagamento. Enquanto está pendente, a aba de DNS costuma vir bloqueada e nenhum serviço externo
   (Cloudflare inclusive) consegue adotar a zona.
4. **O preço é o mesmo na renovação** — R$ 40 no primeiro ano e no décimo. É a diferença para as
   registradoras que vendem o primeiro ano a R$ 29,99 e renovam a R$ 79,99.

**Alternativa — `.com` internacional:** Cloudflare Registrar vende a preço de custo (~US$ 10,44/ano,
sem markup na renovação) e já deixa DNS e domínio no mesmo lugar. Não vende `.br`.

> Compre um nome curto: ele vai aparecer no certificado TLS, nos logs e na sua barra de endereços
> todo dia. Domínio usado daqui em diante: `chatbrain.app.br`.

> ### ⏳ Esperando a ativação? Siga o resto assim mesmo
>
> O domínio só é **realmente necessário** no [passo 6](#9-passo-6--primeiro-deploy-na-mao), quando o
> Caddy pede o certificado ao Let's Encrypt. Tudo antes disso independe dele — e um passo é
> **pré-requisito** do DNS, não o contrário: o Terraform é quem cria o Elastic IP que vai no registro
> A.
>
> Ordem para não ficar parada:
>
> | Enquanto o domínio não ativa | Quando ativar |
> |---|---|
> | [Passo 1](#4-passo-1--banco-no-neon) — Neon validado do local | adotar a zona (Cloudflare ou Registro.br) |
> | [Passo 3](#6-passo-3--o-que-subir-para-o-s3) — enxugar o material | criar os registros A com o Elastic IP |
> | [Passo 4](#7-passo-4--terraform) — Terraform: **sai o Elastic IP** | [Passo 6](#9-passo-6--primeiro-deploy-na-mao) — deploy e certificado |
> | [Passo 5](#8-passo-5--segredos-no-ssm) — segredos no SSM | |
>
> Deixar a instância ligada um ou dois dias antes do deploy não muda a conta: as 750 h/mês do free
> tier cobrem um mês inteiro de uma `t3.micro`.

### 5.2 DNS — use o do próprio Registro.br

**O Registro.br já hospeda DNS de graça**, e para este projeto isso basta. Você precisa de dois
registros A apontando para um IP fixo — nada além. Não crie conta em lugar nenhum.

Depois do [passo 4](#7-passo-4--terraform), quando o Terraform imprimir o Elastic IP:

1. [registro.br](https://registro.br) → entre → clique no seu domínio.
2. Seção **DNS** → **`CONFIGURAR ZONA DNS`** (ou **`Editar zona`**).
3. No **modo Básico**, adicione os dois registros:

   | Tipo | Nome | Valor | TTL |
   |---|---|---|---|
   | A | *(vazio, ou `@`)* | `<ELASTIC_IP>` | padrão |
   | A | `www` | `<ELASTIC_IP>` | padrão |

4. Salvar. A propagação costuma levar de minutos a uma hora.

> **Só os contatos Administrativo e Técnico do domínio podem editar a zona** — se os campos vierem
> bloqueados, é isso.

### 5.3 Cloudflare — opcional, e provavelmente desnecessária aqui

A versão anterior deste documento mandava usar a Cloudflare. **Reveja:** a Cloudflare só ganha do
DNS do Registro.br quando você liga o proxy (a nuvem laranja) — CDN, WAF, cache, analytics. E o
proxy é exatamente o que você **não** pode ligar aqui:

- o plano Free corta a conexão em **100 segundos** (erro 524), e uma resposta longa do Claude com
  raciocínio passa disso, quebrando o streaming no meio;
- o desafio HTTP-01 do Let's Encrypt, que o Caddy usa para emitir o certificado, fica mais chato de
  fazer funcionar.

Com o proxy desligado (nuvem cinza), a Cloudflare vira só um servidor de DNS — o mesmo papel do
Registro.br, com uma conta e uma delegação de nameservers a mais no caminho.

Se ainda assim quiser usá-la (a interface de DNS é melhor, e as mudanças propagam mais rápido):

1. [dash.cloudflare.com](https://dash.cloudflare.com) → sidebar **Domains** → **`Onboard a domain`**
   (o dashboard novo renomeou o antigo *Add a site*; numa conta vazia o botão pode aparecer como
   **Add a Site**) → digite o domínio → plano **Free**.
2. A Cloudflare sorteia **dois nameservers próprios da sua zona** e os mostra na tela de Overview.
   Os desta conta são:

   ```
   holly.ns.cloudflare.com
   ruben.ns.cloudflare.com
   ```

   > Esses nomes são sorteados por zona — nenhum tutorial pode adivinhá-los. Use sempre os que o
   > **seu** painel mostrar; com os de outra pessoa o domínio não resolve.
3. **Registro.br** → seu domínio → **DNS** → **Alterar servidores DNS** → substitua
   `a.auto.dns.br` e `b.auto.dns.br` pelos dois da Cloudflare → salvar.
   **Desligue o DNSSEC** antes de sair: o Registro.br costuma deixá-lo ativo quando a zona é dele, e
   apontando para a Cloudflare com ele ligado o domínio quebra (SERVFAIL). Dá para religar depois,
   pela própria Cloudflare.
4. Espere o e-mail de confirmação (15 min a algumas horas) e crie os dois registros A da tabela
   acima, com **Proxy status: DNS only** (nuvem **cinza**).

### 5.4 Verificar antes de seguir

```powershell
nslookup chatbrain.app.br
nslookup www.chatbrain.app.br
```

Os dois têm de devolver o Elastic IP. **O Caddy não consegue emitir o certificado antes disso** — o
Let's Encrypt precisa alcançar o seu servidor pelo nome.

---

<a id="6-passo-3--o-que-subir-para-o-s3"></a>
## 6. Passo 3 — O que subir para o S3 (e o que não subir)

**A pergunta era: subo todos os dados de ingestão?** A resposta curta é **não** — e não por causa de
espaço.

### 6.1 O que você tem hoje

```
ingestao/  total: 1,1 GB
├── .mp4 (vídeos)                    ~840 MB   ← a ingestão NUNCA lê
└── o resto                          260 MB
    ├── 4 arquivos .PPTX             190 MB   ← o problema está aqui
    └── PDF + VTT + TXT + MD + PNG    70 MB
```

Os quatro `.PPTX` respondem por **73% do que sobra**:

| Arquivo | Tamanho | Tem PDF equivalente? |
|---|---|---|
| `L2/Cloudability Level 2_ Client presentation.PPTX` | 64 MB | não |
| `L1/03 FinOps Level 1 - Seller presentation.PPTX` | 53 MB | **sim** (6,9 MB) |
| `L1/02 FinOps Level 1 - Client presentation.PPTX` | 37 MB | **sim** (5,2 MB) |
| `L2/Cloudability Level 2_ Seller presentation.PPTX` | 36 MB | não |

### 6.2 As três regras

**Regra 1 — vídeo não sobe.** O `DocumentContentExtractor` suporta `.vtt`, `.pdf`, `.pptx`, `.md` e
`.txt`; o `S3MaterialCatalog` lista também `.png`, `.jpg` e `.jpeg` (que a visão do Claude lê). Não
há `.mp4` em nenhuma das duas listas. O conteúdo dos vídeos já está nas legendas `.vtt`, que estão
todas lá. Subir 840 MB de vídeo gastaria banda, tempo e cota de PUT para nada.

**Regra 2 — onde existe PDF e PPTX do mesmo material, suba só o PDF.** Dois motivos, e o segundo é o
importante:

- economiza 90 MB;
- **evita conteúdo duplicado na base**. Duas cópias do mesmo slide viram dois chunks quase idênticos.
  A busca híbrida devolve os dois no `top-k` de 8, você paga tokens por texto repetido e perde duas
  das oito vagas que poderiam trazer contexto novo. Duplicata piora a resposta, não melhora.

**Regra 3 — os dois PPTX do L2 que não têm PDF: exporte para PDF e suba o PDF.** Um `.pptx` de 64 MB
é grande porque carrega imagens e mídia embutidas; o texto dos slides são alguns KB. O Apache POI
precisa abrir o arquivo inteiro na memória para extrair esse texto — numa instância de 1 GB isso é
exatamente o que você não quer. Exportado como PDF, o mesmo material vira ~8 MB e o PDFBox lê sem
esforço.

No PowerPoint: **Arquivo → Exportar → Criar documento PDF/XPS**. Salve ao lado do `.pptx` original,
com o mesmo nome.

> Não quer converter? Suba os dois `.pptx` do L2 assim mesmo: eles cabem no free tier e a ingestão
> funciona — desde que você a faça **da sua máquina**, como manda o [passo 7](#10-passo-7--indexar-o-material).
> O que não pode é a `t3.micro` tentar parsear 64 MB de PPTX.

### 6.3 O resultado

| | Antes | Depois |
|---|---|---|
| Tamanho | 1,1 GB | **~70 MB** |
| Arquivos | ~180 | ~140 |
| Free tier do S3 (5 GB) | — | **1,4% usado** |
| Tempo do `s3 sync` | ~40 min | ~2 min |

### 6.4 Os comandos (depois do Terraform ter criado o bucket)

```powershell
$env:AWS_PROFILE = "cloudability"
$BUCKET = "cloudability-material-lv7421"

# Material do modulo de fabrica, sem video e sem os PPTX do L1 (tem PDF equivalente)
aws s3 sync ./ingestao "s3://$BUCKET/modules/cloudability" `
  --exclude "*.mp4" --exclude "*.MP4" `
  --exclude "L1/*.PPTX" --exclude "L1/*.pptx"

# Base do agente especialista
aws s3 sync ./.claude/agents "s3://$BUCKET/modules/cloudability/agents"

# Conferir
aws s3 ls "s3://$BUCKET/modules/cloudability/" --recursive --summarize | Select-Object -Last 5
```

Se você converteu os PPTX do L2 em PDF, acrescente `--exclude "L2/*.PPTX"`.

### 6.5 Detalhes que economizam dor

- **O layout é uma pasta por módulo** — `modules/<slug>/...`. É o mesmo do LocalStack; o backend não
  percebe diferença.
- **Não ligue versionamento** no bucket do material: cada `sync` guardaria uma cópia a mais e o
  tamanho cresceria sem você notar.
- **Cota de PUT do free tier: 2.000/mês.** Um sync completo de ~140 arquivos é 7% disso. Rodar
  `sync` dez vezes no mês continua tranquilo — o `sync` só envia o que mudou.
- **Nunca use `--delete` num script automatizado** apontando para este bucket. Material enviado pela
  tela de administração não existe na sua pasta local; um `--delete` o apagaria.

---

<a id="7-passo-4--terraform"></a>
## 7. Passo 4 — Terraform

### 7.1 O que o Terraform cria (e o que ele não toca)

| Cria | Não cria |
|---|---|
| 2 buckets S3, com acesso público bloqueado e criptografia | **VPC** — usa a padrão (evita NAT Gateway) |
| IAM Role + Instance Profile do EC2 | **Os valores dos segredos** — ficariam no `terraform.tfstate` em texto puro |
| IAM Role do GitHub Actions (OIDC) | **Registros DNS** — ficam no painel do registrador |
| Security Group (80, 443) | **Certificado TLS** — o Caddy resolve sozinho |
| EC2 `t3.micro` com swap e Docker | |
| Elastic IP | |
| Repositório ECR com lifecycle | |

### 7.2 Estrutura de arquivos

```
infra/
├── versions.tf
├── variables.tf
├── data.tf
├── s3.tf
├── iam-ec2.tf
├── iam-github.tf
├── ecr.tf
├── ec2.tf
├── outputs.tf
├── user_data.sh
└── terraform.tfvars        ← seu, não versionado
```

Crie a pasta e acrescente ao `.gitignore` da raiz:

```gitignore
# Terraform
infra/.terraform/
infra/*.tfstate
infra/*.tfstate.*
infra/terraform.tfvars
infra/.terraform.lock.hcl
```

> **Sobre o `terraform.tfstate`:** ele fica na sua máquina e contém identificadores da sua
> infraestrutura. **Não versione.** Faça backup (ele é a única fonte de verdade sobre o que existe).
> Migrar para um backend S3 é possível depois — veja [7.10](#710-quando-migrar-o-state-para-o-s3).

### 7.3 `versions.tf`

```hcl
terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

provider "aws" {
  region = var.region

  # Toda tag aqui cai em todo recurso — é assim que você acha o que é seu
  # no Cost Explorer depois.
  default_tags {
    tags = {
      Project   = "cloudability-chat"
      ManagedBy = "terraform"
    }
  }
}
```

### 7.4 `variables.tf`

```hcl
variable "region" {
  description = "Região da AWS. Mantenha igual à do projeto no Neon."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Prefixo de nome de todos os recursos."
  type        = string
  default     = "cloudability"
}

variable "bucket_suffix" {
  description = "Sufixo que torna o nome do bucket único no mundo (ex.: suas iniciais + 4 dígitos)."
  type        = string
}

variable "instance_type" {
  description = "t3.micro está no free tier. Suba para t3.small se a API morrer por falta de RAM."
  type        = string
  default     = "t3.micro"
}

variable "root_volume_gb" {
  description = "30 GB é o teto do free tier de EBS."
  type        = number
  default     = 30
}

variable "swap_gb" {
  description = "Arquivo de swap. Sem ele, 1 GB de RAM não segura Java + TEI."
  type        = number
  default     = 4
}

variable "github_repo" {
  description = "usuario/repositorio — limita quem pode assumir a role do pipeline."
  type        = string
}

variable "create_github_oidc_provider" {
  description = "false se a conta já tiver o provider do GitHub (só pode existir um)."
  type        = bool
  default     = true
}

variable "domain" {
  description = "Domínio que o Caddy vai usar no certificado."
  type        = string
}
```

### 7.5 `data.tf`

```hcl
# VPC padrão: já tem subnets públicas e internet gateway, e não custa nada.
# Criar uma VPC nova levaria a criar NAT Gateway — US$ 32/mês.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# AMI mais recente do Amazon Linux 2023, x86_64. Vem pelo Parameter Store
# público da AWS, então não envelhece no código.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

data "aws_caller_identity" "current" {}
```

> **Se a conta não tiver VPC padrão** (algumas contas novas não têm), rode uma vez:
> `aws ec2 create-default-vpc`.

### 7.6 `s3.tf`

```hcl
locals {
  material_bucket  = "${var.project}-material-${var.bucket_suffix}"
  artifacts_bucket = "${var.project}-artifacts-${var.bucket_suffix}"
}

# ------------------------------------------------------------------ material
# Estado, não artefato. É lido e escrito pela aplicação; o pipeline não toca.
resource "aws_s3_bucket" "material" {
  bucket = local.material_bucket
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
      sse_algorithm = "AES256" # SSE-S3: sem custo, sem KMS para gerenciar
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "material" {
  bucket = aws_s3_bucket.material.id
  rule {
    id     = "abortar-uploads-incompletos"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# ----------------------------------------------------------------- artefatos
# Build do front e arquivos de deploy. Descartável por definição.
resource "aws_s3_bucket" "artifacts" {
  bucket = local.artifacts_bucket
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    id     = "expirar-builds-antigos"
    status = "Enabled"
    filter { prefix = "web/" }
    expiration { days = 30 }
  }
  rule {
    id     = "abortar-uploads-incompletos"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}
```

<a id="77-iam-ec2"></a>
### 7.7 `iam-ec2.tf` — o acesso ao S3 sem chave nenhuma

Esta é a peça central da pergunta sobre IAM Role. A instância recebe credenciais **temporárias**,
rotacionadas pela própria AWS, que o SDK encontra sozinho pela cadeia padrão. **Nenhum segredo de
longa duração toca o disco do EC2.** É por isso que, no `docker-compose.prod.yml`, você não vai ver
`AWS_ACCESS_KEY_ID` — e a ausência dele é o que faz o SDK procurar a role.

```hcl
# Quem pode assumir esta role: o serviço EC2, e mais ninguém.
data "aws_iam_policy_document" "ec2_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "${var.project}-app-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_trust.json
}

# O que a instância pode fazer. Cada bloco existe por um motivo concreto.
data "aws_iam_policy_document" "app" {

  # 1. Listar o bucket do material — a sincronização precisa enumerar os arquivos do módulo.
  statement {
    sid       = "ListarBucketDoMaterial"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.material.arn]
  }

  # 2. Ler, gravar e apagar objetos do material.
  #    PutObject e DeleteObject NÃO são opcionais: o upload pela tela de admin grava
  #    no bucket, e remover um módulo apaga a pasta dele. Uma role só de leitura
  #    quebra as duas coisas.
  statement {
    sid       = "LerEGravarMaterial"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.material.arn}/*"]
  }

  # 3. Baixar o build do front e os arquivos de deploy. Só leitura: quem escreve
  #    aqui é o pipeline, com outra role.
  statement {
    sid       = "LerArtefatos"
    actions   = ["s3:GetObject", "s3:ListBucket"]
    resources = [aws_s3_bucket.artifacts.arn, "${aws_s3_bucket.artifacts.arn}/*"]
  }

  # 4. Ler os segredos do Parameter Store — só os do caminho deste projeto.
  statement {
    sid       = "LerSegredos"
    actions   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
    resources = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${var.project}/*"]
  }

  # 5. Descriptografar SecureString. A condição amarra o uso da chave ao SSM:
  #    esta role não consegue usar a chave para mais nada.
  statement {
    sid       = "DescriptografarSegredos"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${var.region}.amazonaws.com"]
    }
  }

  # 6. Baixar a imagem do ECR. GetAuthorizationToken não aceita recurso específico.
  statement {
    sid       = "LoginNoECR"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "BaixarImagem"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
    ]
    resources = [aws_ecr_repository.api.arn]
  }
}

resource "aws_iam_role_policy" "app" {
  name   = "${var.project}-app-policy"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.app.json
}

# Session Manager (shell sem SSH, sem porta 22) e o canal que o deploy usa.
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# O Instance Profile é o invólucro pelo qual o EC2 recebe a role.
resource "aws_iam_instance_profile" "app" {
  name = "${var.project}-app-profile"
  role = aws_iam_role.app.name
}
```

### 7.8 `iam-github.tf` — o pipeline, sem chave de acesso no GitHub

```hcl
# Só pode existir UM provider do GitHub por conta AWS. Se já existir,
# ponha create_github_oidc_provider = false no tfvars.
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  # A AWS valida o certificado do GitHub por conta própria desde 2023; esta lista
  # é mantida por compatibilidade com a API.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  # Os parênteses não são enfeite: em HCL, uma expressão ternária quebrada em
  # várias linhas só é válida dentro deles.
  github_oidc_arn = (
    var.create_github_oidc_provider
    ? aws_iam_openid_connect_provider.github[0].arn
    : data.aws_iam_openid_connect_provider.github[0].arn
  )
}

# A confiança é amarrada ao SEU repositório e ao branch main. Sem a condição do
# "sub", qualquer repositório do GitHub assumiria esta role.
data "aws_iam_policy_document" "github_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "github" {
  name               = "${var.project}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_trust.json
}

data "aws_iam_policy_document" "github" {
  statement {
    sid       = "LoginNoECR"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PublicarImagem"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [aws_ecr_repository.api.arn]
  }

  # Repare: SÓ o bucket de artefatos. O pipeline não enxerga o material.
  statement {
    sid       = "PublicarArtefatos"
    actions   = ["s3:PutObject", "s3:GetObject", "s3:ListBucket", "s3:DeleteObject"]
    resources = [aws_s3_bucket.artifacts.arn, "${aws_s3_bucket.artifacts.arn}/*"]
  }

  # Disparar o deploy na instância — e só naquela instância.
  statement {
    sid       = "DispararDeploy"
    actions   = ["ssm:SendCommand"]
    resources = [
      aws_instance.app.arn,
      "arn:aws:ssm:${var.region}::document/AWS-RunShellScript",
    ]
  }

  statement {
    sid       = "AcompanharDeploy"
    actions   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github" {
  name   = "${var.project}-github-policy"
  role   = aws_iam_role.github.id
  policy = data.aws_iam_policy_document.github.json
}
```

> **São duas roles, e é bom que continuem assim.** A `app-role` é do EC2 e enxerga o material; a
> `github-deploy` é do pipeline e não enxerga. Se um dia o pipeline for comprometido, o material não
> vai junto.

### 7.9 `ecr.tf`

```hcl
resource "aws_ecr_repository" "api" {
  name                 = "${var.project}-api"
  image_tag_mutability = "MUTABLE" # a tag "latest" é reapontada a cada deploy

  image_scanning_configuration {
    scan_on_push = true # grátis no scanning básico
  }
}

# Sem isto o repositório cresce indefinidamente: uma imagem de ~250 MB por deploy.
resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Manter apenas as 3 imagens mais recentes"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 3
      }
      action = { type = "expire" }
    }]
  })
}
```

### 7.10 `ec2.tf`

```hcl
resource "aws_security_group" "app" {
  name        = "${var.project}-sg"
  description = "HTTP e HTTPS publicos. Sem SSH: o shell vem pelo Session Manager."
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "HTTP - necessario para o desafio ACME do Lets Encrypt"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # A porta 8080 NAO esta aberta. Se estivesse, qualquer um mandaria o cabecalho
  # X-Access-Level: admin direto na API e pularia o porteiro do Caddy.
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "app" {
  ami                    = nonsensitive(data.aws_ssm_parameter.al2023.value)
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.app.name

  associate_public_ip_address = true

  # IMDSv2 obrigatório. O hop limit 2 é o detalhe que faz a IAM Role funcionar
  # DENTRO do container: o pacote sai do container, passa pelo bridge do Docker
  # (1 salto) e só então alcança o serviço de metadados (2º salto). Com o padrão
  # de 1 salto, o SDK funciona no host e falha no container.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_size           = var.root_volume_gb
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  user_data = templatefile("${path.module}/user_data.sh", {
    swap_gb = var.swap_gb
    region  = var.region
    project = var.project
  })

  # Sem isto, toda vez que a AWS publicar uma AMI nova o Terraform quer DESTRUIR
  # e recriar a instância — levando junto tudo que estiver no disco.
  lifecycle {
    ignore_changes = [ami]
  }

  tags = { Name = "${var.project}-app" }
}

# IP fixo. Sem ele o endereço muda a cada stop/start e o DNS aponta para o vazio.
resource "aws_eip" "app" {
  domain   = "vpc"
  instance = aws_instance.app.id
}
```

### 7.11 `user_data.sh`

```bash
#!/bin/bash
set -euxo pipefail

# ---------------------------------------------------------------------- swap
# 1 GB de RAM nao segura Java + TEI. O swap nao deixa rapido: deixa vivo.
if [ ! -f /swapfile ]; then
  dd if=/dev/zero of=/swapfile bs=1M count=$(( ${swap_gb} * 1024 ))
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
# Usar swap so quando faltar mesmo — nao trocar paginas quentes por habito.
sysctl -w vm.swappiness=10
echo 'vm.swappiness=10' > /etc/sysctl.d/99-swap.conf

# -------------------------------------------------------------------- docker
dnf update -y
dnf install -y docker
systemctl enable --now docker
usermod -aG docker ec2-user

# Plugin do compose v2 (nao vem no repositorio do AL2023)
mkdir -p /usr/libexec/docker/cli-plugins
curl -SL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
  -o /usr/libexec/docker/cli-plugins/docker-compose
chmod +x /usr/libexec/docker/cli-plugins/docker-compose

# Limitar o log do Docker: sem isto, um container falando muito enche o disco de 30 GB.
cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
JSON
systemctl restart docker

# ----------------------------------------------------------------- aplicacao
mkdir -p /opt/${project}/web
chown -R ec2-user:ec2-user /opt/${project}

# Monta o .env a partir do Parameter Store. Roda a cada deploy: os segredos
# nunca ficam num arquivo versionado nem passam pelo GitHub.
cat > /opt/${project}/fetch-env.sh <<'SCRIPT'
#!/bin/bash
set -euo pipefail
REGION="$${AWS_REGION:-us-east-2}"
PROJECT="$${PROJECT:-cloudability}"
OUT=/opt/$${PROJECT}/.env

umask 077
: > "$OUT"
aws ssm get-parameters-by-path \
  --path "/$${PROJECT}/" --with-decryption --recursive \
  --region "$REGION" --query 'Parameters[].[Name,Value]' --output text |
while IFS=$'\t' read -r name value; do
  key=$(basename "$name" | tr '[:lower:]-' '[:upper:]_')
  printf '%s=%s\n' "$key" "$value" >> "$OUT"
done
chmod 600 "$OUT"
SCRIPT
chmod +x /opt/${project}/fetch-env.sh
chown ec2-user:ec2-user /opt/${project}/fetch-env.sh

echo "user_data concluido" > /var/log/user-data-done
```

> **Sobre o `$$` no script:** o `templatefile` do Terraform trata `${...}` como interpolação dele.
> `$${VAR}` é como se escreve "quero um `$` literal aqui, é variável do bash". `${swap_gb}` e
> `${project}` são do Terraform; `$${AWS_REGION}` é do shell.

### 7.12 `outputs.tf`

```hcl
output "elastic_ip" {
  description = "Aponte os registros A da zona DNS para este IP."
  value       = aws_eip.app.public_ip
}

output "instance_id" {
  description = "Use no aws ssm start-session e nos secrets do GitHub."
  value       = aws_instance.app.id
}

output "material_bucket" {
  value = aws_s3_bucket.material.bucket
}

output "artifacts_bucket" {
  value = aws_s3_bucket.artifacts.bucket
}

output "ecr_repository_url" {
  value = aws_ecr_repository.api.repository_url
}

output "ecr_registry" {
  value = split("/", aws_ecr_repository.api.repository_url)[0]
}

output "github_role_arn" {
  description = "Secret AWS_ROLE_ARN do repositório."
  value       = aws_iam_role.github.arn
}
```

### 7.13 `terraform.tfvars`

```hcl
region        = "us-east-2"                   # a MESMA do projeto no Neon
project       = "cloudability"
bucket_suffix = "lv7421"                      # troque: precisa ser único no mundo
instance_type = "t3.micro"
github_repo   = "seu-usuario/estudos-ibm"
domain        = "chatbrain.app.br"
```

### 7.14 Aplicar

```powershell
cd infra
$env:AWS_PROFILE = "cloudability"

terraform init
terraform plan -out=tfplan       # LEIA a saída antes de seguir
terraform apply tfplan
```

Deve terminar com algo assim:

```
elastic_ip          = "54.87.123.45"
instance_id         = "i-0c90602040e3c30b0"
material_bucket     = "cloudability-material-lv7421"
artifacts_bucket    = "cloudability-artifacts-lv7421"
ecr_repository_url  = "647558542138.dkr.ecr.us-east-2.amazonaws.com/cloudability-api"
github_role_arn     = "arn:aws:iam::123456789012:role/cloudability-github-deploy"
```

**Agora volte ao [passo 2](#5-passo-2--dominio-e-dns)** e crie os registros A na zona DNS apontando
para o `elastic_ip`. E ao [passo 3](#6-passo-3--o-que-subir-para-o-s3) para subir o material.

### 7.15 Verificar que a IAM Role realmente funciona

Abra um shell na instância **sem SSH**:

```powershell
aws ssm start-session --target i-0c90602040e3c30b0
```

Dentro dela, **troque de usuário primeiro**. O Session Manager loga como `ssm-user`, e quem está no
grupo `docker` é o `ec2-user` — sem isso o teste 3 falha com *permission denied* no
`/var/run/docker.sock`:

```bash
sudo -i -u ec2-user
```

```bash
# 1. Quem eu sou? Tem de mostrar a role, não um usuário.
aws sts get-caller-identity
# "Arn": ".../cloudability-app-role/i-0c90602040e3c30b0"

# 2. Enxergo o material?
aws s3 ls s3://cloudability-material-lv7421/modules/

# 3. E DENTRO de um container? Este é o teste que importa.
docker run --rm amazon/aws-cli:latest sts get-caller-identity
# Se falhar aqui e funcionar no host, o problema é o hop limit do IMDSv2.
```

<a id="710-quando-migrar-o-state-para-o-s3"></a>
### 7.16 Quando migrar o state para o S3

Enquanto for só você aplicando, do seu computador, o state local basta. Migre quando **uma segunda
pessoa** for aplicar, ou quando você quiser aplicar de outra máquina:

```hcl
terraform {
  backend "s3" {
    bucket       = "cloudability-artifacts-lv7421"
    key          = "terraform/state.tfstate"
    region       = "us-east-2"
    encrypt      = true
    use_lockfile = true   # trava no próprio S3 — não precisa de DynamoDB
  }
}
```

Depois: `terraform init -migrate-state`.

---

<a id="8-passo-5--segredos-no-ssm"></a>
## 8. Passo 5 — Segredos no SSM Parameter Store

### 8.1 Onde cada segredo vive — e o que **nunca** se edita

O `application.yml` **não se toca.** Ele já está certo: cada valor é um marcador de variável de
ambiente, e o que vem depois dos dois-pontos é só o padrão do ambiente local.

```yaml
# ibm/src/main/resources/application.yml — deixe exatamente assim
datasource:
  url:      ${DB_URL:jdbc:postgresql://localhost:5432/cloudability}
  username: ${DB_USER:cloudability}
  password: ${DB_PASSWORD:cloudability}
```

Escrever a senha do Neon aí a colocaria **dentro do repositório e dentro da imagem Docker** — de
onde ela não sai mais, nem com um commit depois: fica no histórico do Git e em toda camada da imagem
já publicada.

| Onde você está | Onde o segredo mora | Está protegido por |
|---|---|---|
| Sua máquina | `.env` na raiz do projeto | o `.gitignore`, que já barra `.env` |
| GitHub Actions | **nenhum segredo de banco** — o pipeline não precisa deles | — |
| EC2 (produção) | **SSM Parameter Store**, lido no deploy pelo `fetch-env.sh` | a IAM Role da instância + KMS |

Repare no meio da tabela: o banco nunca passa pelo GitHub. O pipeline manda a instância se atualizar;
quem busca os segredos é a própria instância, com a role dela.

> **Uma honestidade sobre o `.env` do servidor:** o `fetch-env.sh` materializa os valores em
> `/opt/cloudability/.env` com permissão `600`, e o compose os injeta como variáveis de ambiente.
> Ou seja, eles **existem em disco** na instância e aparecem num `docker inspect`. Quem tem shell na
> máquina lê — e é por isso que o acesso a ela é só por Session Manager, com IAM. É o compromisso
> normal para este porte; o passo seguinte em rigor seria montar os segredos como arquivos e ensinar
> o Spring a lê-los, o que não se paga aqui.

### 8.2 Criar os parâmetros

**Por que o Terraform não cria estes parâmetros:** o valor ficaria em texto puro no
`terraform.tfstate`. Criptografar no SSM e deixar a senha legível no state ao lado seria teatro.
Crie-os pela CLI, uma vez:

```powershell
$env:AWS_PROFILE = "cloudability"

aws ssm put-parameter --name "/cloudability/anthropic_api_key" `
  --type SecureString --value "sk-ant-..." --overwrite

aws ssm put-parameter --name "/cloudability/db_url" `
  --type String `
  --value "jdbc:postgresql://ep-xxx-123456.us-east-1.aws.neon.tech/neondb?sslmode=require" --overwrite

aws ssm put-parameter --name "/cloudability/db_user" `
  --type String --value "neondb_owner" --overwrite

aws ssm put-parameter --name "/cloudability/db_password" `
  --type SecureString --value "sua-senha-do-neon" --overwrite

# ATENCAO: o Caddy exige um hash bcrypt VALIDO aqui. Um texto qualquer
# ("placeholder") faz o basic_auth falhar na largada, com
# "base64-decoding password: illegal base64 data", e o container entra em
# loop de reinicio. Gere o hash de verdade agora -- e o mesmo comando do
# passo 9, so que antecipado:
#
#   docker run --rm caddy:2-alpine caddy hash-password --plaintext "SUA-SENHA"
#
aws ssm put-parameter --name "/cloudability/admin_password_hash" `
  --type SecureString --value "$2a$14$..." --overwrite
```

Conferir (sem revelar os valores):

```powershell
aws ssm get-parameters-by-path --path "/cloudability/" --query "Parameters[].Name"
```

Esperado — cinco parâmetros, e repare nos **tipos**: os três sensíveis como `SecureString`, os dois
restantes como `String`.

```
/cloudability/admin_password_hash   SecureString
/cloudability/anthropic_api_key     SecureString
/cloudability/db_password           SecureString
/cloudability/db_url                String
/cloudability/db_user               String
```

### 8.3 Verificar que a INSTÂNCIA consegue ler os segredos

O teste acima prova que os parâmetros existem — prova isso **da sua máquina**, com as credenciais do
`terraform-admin`, que pode tudo. Não é a mesma pergunta. O que o deploy precisa é que o **EC2**
consiga ler, com a role dele. Vale testar antes do passo 6: se falhar aqui, falha lá no meio do
deploy, junto com outras cinco coisas acontecendo.

Dá para fazer sem abrir sessão, da sua máquina:

```powershell
aws ssm send-command --instance-ids i-0c90602040e3c30b0 `
  --document-name AWS-RunShellScript `
  --parameters 'commands=["sudo -u ec2-user AWS_REGION=us-east-2 PROJECT=cloudability /opt/cloudability/fetch-env.sh && echo FETCH_OK","ls -l /opt/cloudability/.env","grep -o \"^[A-Z_]*=\" /opt/cloudability/.env"]' `
  --query 'Command.CommandId' --output text
```

Com o `CommandId` que ele devolver:

```powershell
aws ssm get-command-invocation --command-id <COMMAND_ID> `
  --instance-id i-0c90602040e3c30b0 --query 'StandardOutputContent' --output text
```

Saída esperada — só os **nomes** das variáveis, nunca os valores:

```
FETCH_OK
-rw-------. 1 ec2-user ec2-user 307 /opt/cloudability/.env
ADMIN_PASSWORD_HASH=
ANTHROPIC_API_KEY=
DB_PASSWORD=
DB_URL=
DB_USER=
```

Três coisas ficam provadas de uma vez, e cada uma quebraria o deploy sozinha:

| O que aparece | O que prova |
|---|---|
| `FETCH_OK` | a role leu o Parameter Store e o KMS descriptografou os `SecureString` — valida os blocos `LerSegredos` e `DescriptografarSegredos` da role |
| `ANTHROPIC_API_KEY=` | o mapeamento nome→variável funcionou (`anthropic_api_key` → maiúsculas com `_`) |
| `ec2-user ec2-user` | o arquivo tem o dono certo |

> **Rode como `ec2-user`, não como root.** O `send-command` executa como root por padrão, e o
> `fetch-env.sh` cria o `.env` com permissão `600` — dono exclusivo. Um `.env` `root:root` faz o
> deploy, que roda como `ec2-user`, falhar ao sobrescrevê-lo. Daí o `sudo -u ec2-user` no comando.

> **Sobre a região no comando.** O `AWS_REGION=us-east-2` não é decorativo: o `fetch-env.sh` tem um
> valor padrão embutido, e instâncias criadas antes desta correção carregam `us-east-1` ali. Como
> `user_data` só roda no primeiro boot, corrigir o arquivo no repositório não muda uma instância que
> já existe. O `deploy.sh` sempre exporta a variável, então o padrão nunca decide nada na prática.

> **Por que SSM e não Secrets Manager.** O Secrets Manager cobra US$ 0,40 por segredo por mês — com
> cinco segredos, US$ 2/mês para fazer o que o Parameter Store Standard faz de graça. Rotação
> automática você não usa aqui.

**O mapeamento do nome para a variável de ambiente** é o `fetch-env.sh` do `user_data`:
`/cloudability/anthropic_api_key` → `ANTHROPIC_API_KEY`. Para acrescentar um segredo novo, basta
criar o parâmetro com o nome em minúsculas; nada mais muda.

---

<a id="9-passo-6--primeiro-deploy-na-mao"></a>
## 9. Passo 6 — Primeiro deploy na mão

> **Faça este passo antes do GitHub Actions.** Automatizar antes de ter feito o deploy manual uma vez
> significa depurar o pipeline e a infraestrutura ao mesmo tempo — e você não saberá de qual dos dois
> veio o erro.

### 9.1 Os arquivos de deploy — versione-os

Crie uma pasta `deploy/` na raiz do repositório. Ela é o que o pipeline vai copiar para o servidor.

**`deploy/Caddyfile`**

```caddyfile
{$SITE_DOMAIN} {
	encode zstd gzip

	# ---------------------------------------------------------------- admin
	# Tudo que escreve na base está sob /api/admin. É aqui que a senha forte mora.
	@admin path /api/admin/*
	handle @admin {
		basic_auth {
			admin {$ADMIN_PASSWORD_HASH}
		}
		reverse_proxy api:8080 {
			header_up X-Access-Level admin
			flush_interval -1
		}
	}

	# ------------------------------------------------------------------ api
	# flush_interval -1 desliga o buffering. Sem isso o SSE chega de uma vez só
	# no fim, em vez de token a token, e a bolinha animada fica girando à toa.
	handle /api/* {
		reverse_proxy api:8080 {
			# Apaga o cabeçalho vindo do cliente. Sem esta linha, qualquer um
			# manda "X-Access-Level: admin" e vira administrador — o
			# AccessResolver confia no cabeçalho.
			header_up -X-Access-Level
			flush_interval -1
		}
	}

	# --------------------------------------------------------------- estático
	# O build do Vite. try_files manda toda rota desconhecida para o index.html,
	# que é o que uma SPA precisa.
	handle {
		root * /srv/web
		try_files {path} /index.html
		file_server
	}

	header {
		Strict-Transport-Security "max-age=31536000"
		X-Content-Type-Options "nosniff"
		X-Frame-Options "DENY"
		Referrer-Policy "strict-origin-when-cross-origin"
	}
}
```

**`deploy/docker-compose.prod.yml`**

```yaml
services:
  api:
    image: ${ECR_REGISTRY}/cloudability-api:${IMAGE_TAG}
    restart: unless-stopped
    environment:
      DB_URL: ${DB_URL}
      DB_USER: ${DB_USER}
      DB_PASSWORD: ${DB_PASSWORD}

      # O pool que deixa o Neon suspender já vem correto do application.yml
      # (seção 4.4). Estas variáveis existem só para ajustar sem rebuild —
      # descomente se precisar.
      # DB_POOL_MIN_IDLE: "0"
      # DB_POOL_IDLE_TIMEOUT: "30000"
      # DB_POOL_MAX_LIFETIME: "280000"

      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
      ANTHROPIC_MODEL: claude-opus-5
      ANTHROPIC_EFFORT: high

      EMBEDDING_PROVIDER: tei
      EMBEDDING_URL: http://embeddings:80
      EMBEDDING_DIMENSIONS: "384"

      # Mesma origem: o Caddy serve o front e a API. CORS não entra em jogo,
      # mas a lista fica aqui para o caso de você abrir um cliente externo.
      CORS_ORIGINS: https://${SITE_DOMAIN}

      # ---------------------------------------------------------- material
      # Repare no que NÃO está aqui: nenhuma credencial da AWS. Sem
      # MATERIAL_ENDPOINT o SDK fala com o S3 de verdade; sem AWS_ACCESS_KEY_ID
      # ele percorre a cadeia padrão até a IAM Role da instância.
      MATERIAL_PROVIDER: s3
      MATERIAL_BUCKET: ${MATERIAL_BUCKET}
      MATERIAL_PREFIX: modules
      MATERIAL_CREATE_BUCKET: "false"
      AWS_REGION: ${AWS_REGION}

      # ----------------------------------------------------------- alçadas
      # student é o piso. Quem alcança /api/admin passa pelo basic_auth do
      # Caddy, que injeta o cabeçalho de admin.
      ACCESS_DEFAULT_LEVEL: student
      KB_AUTO_INGEST: "false"

      # Heap apertado de propósito: sobra RAM para o TEI. Serial GC porque, em
      # heap pequeno, ele gasta menos memória que o G1.
      JAVA_OPTS: "-Xms128m -Xmx420m -XX:+UseSerialGC -XX:MaxMetaspaceSize=180m"
    mem_limit: 700m
    expose: ["8080"]
    depends_on:
      embeddings:
        condition: service_healthy

  embeddings:
    image: ghcr.io/huggingface/text-embeddings-inference:cpu-1.8
    restart: unless-stopped
    # --max-batch-tokens: o padrao (16384) faz o "Warming up model" alocar um
    # lote enorme e estourar a RAM da t3.micro (OOM, exit 137). Em producao o
    # servidor so vetoriza UMA frase por pergunta.
    command: ["--model-id", "intfloat/multilingual-e5-small", "--port", "80",
              "--max-client-batch-size", "4",
              "--max-batch-tokens", "1024",
              "--tokenization-workers", "1"]
    volumes:
      - embeddings-cache:/data
    mem_limit: 750m
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:80/health"]
      interval: 15s
      timeout: 5s
      retries: 30
      start_period: 180s

  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    environment:
      SITE_DOMAIN: ${SITE_DOMAIN}
      ADMIN_PASSWORD_HASH: ${ADMIN_PASSWORD_HASH}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - ./web:/srv/web:ro
      - caddy-data:/data
      - caddy-config:/config

volumes:
  embeddings-cache:
  caddy-data:
  caddy-config:
```

**`deploy/deploy.sh`** — o que o SSM executa na instância:

```bash
#!/bin/bash
set -euo pipefail

PROJECT=cloudability
APP=/opt/$PROJECT
cd "$APP"

: "${IMAGE_TAG:?IMAGE_TAG é obrigatório}"
: "${ARTIFACTS_BUCKET:?ARTIFACTS_BUCKET é obrigatório}"

export AWS_REGION="${AWS_REGION:-us-east-2}"

# 1. Arquivos de deploy e build do front, vindos do bucket de artefatos
aws s3 cp "s3://$ARTIFACTS_BUCKET/deploy/Caddyfile"                ./Caddyfile
aws s3 cp "s3://$ARTIFACTS_BUCKET/deploy/docker-compose.prod.yml"  ./docker-compose.prod.yml
aws s3 cp "s3://$ARTIFACTS_BUCKET/web/web-$IMAGE_TAG.tar.gz"       /tmp/web.tar.gz

rm -rf "$APP/web" && mkdir -p "$APP/web"
tar -xzf /tmp/web.tar.gz -C "$APP/web" && rm -f /tmp/web.tar.gz

# 2. Segredos do Parameter Store viram o .env (nunca passam pelo GitHub)
PROJECT=$PROJECT AWS_REGION=$AWS_REGION "$APP/fetch-env.sh"

# 3. Variáveis que não são segredo
{
  echo "IMAGE_TAG=$IMAGE_TAG"
  echo "ECR_REGISTRY=$ECR_REGISTRY"
  echo "MATERIAL_BUCKET=$MATERIAL_BUCKET"
  echo "SITE_DOMAIN=$SITE_DOMAIN"
  echo "AWS_REGION=$AWS_REGION"
} >> "$APP/.env"

# 4. Subir
aws ecr get-login-password --region "$AWS_REGION" |
  docker login --username AWS --password-stdin "$ECR_REGISTRY"

docker compose --env-file "$APP/.env" -f docker-compose.prod.yml up -d --pull always
docker image prune -f

# 5. Esperar a API responder antes de declarar sucesso
for i in $(seq 1 30); do
  if docker compose -f docker-compose.prod.yml exec -T api \
       wget -qO- http://localhost:8080/api/knowledge/status >/dev/null 2>&1; then
    echo "API no ar"
    exit 0
  fi
  sleep 10
done

echo "API não respondeu em 5 minutos"
docker compose -f docker-compose.prod.yml logs --tail 100 api
exit 1
```

### 9.2 Construir e publicar a primeira imagem (da sua máquina)

```powershell
$env:AWS_PROFILE = "cloudability"
$REGISTRY = "647558542138.dkr.ecr.us-east-2.amazonaws.com"

aws ecr get-login-password --region us-east-2 |
  docker login --username AWS --password-stdin $REGISTRY

docker build -t "$REGISTRY/cloudability-api:manual-1" ./ibm
docker push "$REGISTRY/cloudability-api:manual-1"
```

### 9.3 Construir e publicar o front

```powershell
cd web
npm ci
npm run build
tar -czf ../web-manual-1.tar.gz -C dist .
cd ..

aws s3 cp web-manual-1.tar.gz s3://cloudability-artifacts-lv7421/web/web-manual-1.tar.gz
aws s3 cp deploy/ s3://cloudability-artifacts-lv7421/deploy/ --recursive
```

> `tar -C dist .` empacota o **conteúdo** do `dist/`, não a pasta. É o que faz o `index.html` cair em
> `/srv/web/index.html`, e não em `/srv/web/dist/index.html`.

### 9.4 Rodar o deploy

```powershell
aws ssm start-session --target i-0c90602040e3c30b0
```

Na instância:

```bash
sudo -i -u ec2-user
cd /opt/cloudability

aws s3 cp s3://cloudability-artifacts-lv7421/deploy/deploy.sh ./deploy.sh
chmod +x deploy.sh

IMAGE_TAG=manual-1 \
ECR_REGISTRY=647558542138.dkr.ecr.us-east-2.amazonaws.com \
ARTIFACTS_BUCKET=cloudability-artifacts-lv7421 \
MATERIAL_BUCKET=cloudability-material-lv7421 \
SITE_DOMAIN=chatbrain.app.br \
AWS_REGION=us-east-2 \
./deploy.sh
```

### 9.5 Validar — quatro testes, nesta ordem

```bash
# 1. Containers de pé
docker compose -f docker-compose.prod.yml ps

# 2. O certificado saiu? Procure "certificate obtained successfully"
docker compose -f docker-compose.prod.yml logs caddy | grep -i certificate

# 3. A API alcança o S3 pela IAM Role?
docker compose -f docker-compose.prod.yml logs api | grep -i -E "s3|material|flyway"

# 4. Memória — é aqui que a t3.micro mostra se aguentou
free -h
docker stats --no-stream
```

Do seu navegador: `https://chatbrain.app.br` deve abrir a aplicação com cadeado. Faça uma pergunta
e confira que a resposta **aparece token a token**. Se ela chegar de uma vez, o `flush_interval -1`
não pegou.

> **A primeira subida é lenta:** o TEI baixa ~500 MB de modelo. Os 180 s de `start_period` no
> healthcheck existem por isso. A partir da segunda vez o modelo vem do volume `embeddings-cache`.

---

<a id="10-passo-7--indexar-o-material"></a>
## 10. Passo 7 — Indexar o material

A base vetorial fica no Neon, que é o **mesmo banco** nos dois casos. Isso permite indexar de onde
houver RAM sobrando: a sua máquina.

### 10.1 A rota recomendada — indexar de casa

Aponte o `.env` local para o banco e o bucket **de produção**:

```bash
DB_URL=jdbc:postgresql://ep-xxx-123456.us-east-1.aws.neon.tech/neondb?sslmode=require
DB_USER=neondb_owner
DB_PASSWORD=sua-senha-do-neon

MATERIAL_PROVIDER=s3
MATERIAL_BUCKET=cloudability-material-lv7421
MATERIAL_PREFIX=modules
MATERIAL_CREATE_BUCKET=false
AWS_REGION=us-east-2
# Credenciais do seu usuário terraform-admin — aqui elas são legítimas: é a SUA
# máquina falando com a AWS, não um servidor.
AWS_ACCESS_KEY_ID=AKIA...
AWS_SECRET_ACCESS_KEY=...
MATERIAL_ENDPOINT=
ACCESS_DEFAULT_LEVEL=admin
```

```bash
docker compose up -d --build api embeddings web
```

Abra `http://localhost` → **Administração** → módulo `cloudability` → **Sincronizar material**.
A barra mostra o progresso; com o material enxuto do passo 3 leva de 10 a 25 minutos.

Ao terminar, confira no Neon:

```sql
SELECT module_id, count(*) AS chunks FROM kb_chunk GROUP BY module_id;
SELECT pg_size_pretty(pg_database_size(current_database()));  -- muito abaixo de 0,5 GB
```

**O servidor não precisa fazer nada**: ele lê a mesma base, já pronta.

> **Restaure o `.env` local depois** — volte para o LocalStack e o Postgres do compose. Deixar o
> ambiente de desenvolvimento apontando para o banco de produção é receita para apagar a base numa
> tarde distraída.

### 10.2 A rota pela tela, direto no servidor

Funciona para material novo e pequeno (um PDF, uma transcrição) que você envia pela área de
administração. Para o carregamento inicial dos 70 MB, **não use** na `t3.micro`: o pico de memória do
POI e do PDFBox derruba a API.

Se precisar, acompanhe pelo Session Manager com `docker stats` aberto. Se a API sumir da lista, foi
o OOM killer.

---

<a id="11-passo-8--github-actions"></a>
## 11. Passo 8 — GitHub Actions

### 11.1 Configurar o repositório

**Settings → Secrets and variables → Actions → Secrets:**

| Secret | Valor |
|---|---|
| `AWS_ROLE_ARN` | output `github_role_arn` |

**→ Variables** (não são segredos; ficam legíveis nos logs, e tudo bem):

| Variable | Valor |
|---|---|
| `AWS_REGION` | `us-east-2` |
| `ECR_REGISTRY` | output `ecr_registry` |
| `ARTIFACTS_BUCKET` | output `artifacts_bucket` |
| `MATERIAL_BUCKET` | output `material_bucket` |
| `EC2_INSTANCE_ID` | output `instance_id` |
| `SITE_DOMAIN` | `chatbrain.app.br` |

> **Nenhuma chave de acesso da AWS no GitHub.** O OIDC troca o token do próprio Actions por
> credenciais temporárias, e a trust policy da role só aceita esse token vindo do seu repositório, no
> branch `main`.

### 11.2 `.github/workflows/ci.yml` — roda em todo push e PR

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
          cache: maven
      - name: Compilar e testar
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
      - name: Build (TypeScript strict + Vite)
        working-directory: ./web
        run: |
          npm ci
          npm run build
```

### 11.3 `.github/workflows/deploy.yml` — roda no push para `main`

```yaml
name: Deploy

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  id-token: write   # obrigatório para o OIDC
  contents: read

# Dois deploys ao mesmo tempo deixariam a instância num estado indefinido.
concurrency:
  group: deploy-production
  cancel-in-progress: false

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: ${{ vars.AWS_REGION }}

      - uses: aws-actions/amazon-ecr-login@v2
        id: ecr

      # ---------------------------------------------------------------- API
      - name: Imagem da API
        working-directory: ./ibm
        run: |
          IMAGE="${{ steps.ecr.outputs.registry }}/cloudability-api:${{ github.sha }}"
          docker build -t "$IMAGE" .
          docker push "$IMAGE"

      # ------------------------------------------------------------ frontend
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: web/package-lock.json

      - name: Build do front e envio para o S3
        run: |
          cd web
          npm ci
          npm run build
          tar -czf ../web.tar.gz -C dist .
          cd ..
          aws s3 cp web.tar.gz \
            "s3://${{ vars.ARTIFACTS_BUCKET }}/web/web-${{ github.sha }}.tar.gz"

      - name: Arquivos de deploy para o S3
        run: |
          aws s3 cp deploy/ "s3://${{ vars.ARTIFACTS_BUCKET }}/deploy/" --recursive

  release:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: ${{ vars.AWS_REGION }}

      # SSM em vez de SSH: sem chave privada no GitHub e sem porta 22 aberta.
      - name: Disparar o deploy na instância
        id: send
        run: |
          CMD=$(aws ssm send-command \
            --instance-ids "${{ vars.EC2_INSTANCE_ID }}" \
            --document-name AWS-RunShellScript \
            --comment "deploy ${{ github.sha }}" \
            --parameters commands='[
              "set -e",
              "cd /opt/cloudability",
              "aws s3 cp s3://${{ vars.ARTIFACTS_BUCKET }}/deploy/deploy.sh ./deploy.sh",
              "chmod +x deploy.sh",
              "IMAGE_TAG=${{ github.sha }} ECR_REGISTRY=${{ vars.ECR_REGISTRY }} ARTIFACTS_BUCKET=${{ vars.ARTIFACTS_BUCKET }} MATERIAL_BUCKET=${{ vars.MATERIAL_BUCKET }} SITE_DOMAIN=${{ vars.SITE_DOMAIN }} AWS_REGION=${{ vars.AWS_REGION }} sudo -E -u ec2-user ./deploy.sh"
            ]' \
            --query "Command.CommandId" --output text)
          echo "command_id=$CMD" >> "$GITHUB_OUTPUT"

      # send-command devolve na hora. Sem esta espera, o job ficaria verde
      # mesmo com o deploy falhando na instância.
      - name: Acompanhar até o fim
        run: |
          CMD="${{ steps.send.outputs.command_id }}"
          for i in $(seq 1 60); do
            STATUS=$(aws ssm get-command-invocation \
              --command-id "$CMD" \
              --instance-id "${{ vars.EC2_INSTANCE_ID }}" \
              --query Status --output text 2>/dev/null || echo Pending)
            echo "[$i] $STATUS"
            case "$STATUS" in
              Success) break ;;
              Failed|Cancelled|TimedOut) echo "::error::deploy falhou"; FAIL=1; break ;;
            esac
            sleep 10
          done

          echo "----- saída do servidor -----"
          aws ssm get-command-invocation \
            --command-id "$CMD" --instance-id "${{ vars.EC2_INSTANCE_ID }}" \
            --query "StandardOutputContent" --output text
          aws ssm get-command-invocation \
            --command-id "$CMD" --instance-id "${{ vars.EC2_INSTANCE_ID }}" \
            --query "StandardErrorContent" --output text

          exit "${FAIL:-0}"
```

### 11.4 Os detalhes que evitam dor de cabeça

- **Sem `--platform`**: a instância é `t3.micro`, que é x86_64, igual ao runner do GitHub. Se um dia
  você trocar para Graviton (`t4g`), acrescente `--platform linux/arm64` ao `docker build` — senão o
  container morre com `exec format error`.
- **Tag = `github.sha`**, nunca só `latest`. É o que permite saber qual commit está no ar e voltar
  atrás: `IMAGE_TAG=<sha antigo> ./deploy.sh`.
- **O deploy não toca no material.** O `deploy.sh` só lê o bucket de artefatos — e a role do pipeline
  nem tem permissão sobre o outro.
- **Migrações Flyway rodam no boot do container.** Se uma falhar, a API não sobe e o `deploy.sh` sai
  com erro depois de 5 minutos, já despejando os logs no output do Actions.
- **Custo de Actions:** em repositório público, ilimitado. Em privado, 2.000 min/mês no plano grátis
  — este pipeline gasta ~6 min por deploy.

### 11.5 Testar sem quebrar nada

Mude uma palavra no `AboutPanel.tsx`, `git push` para `main`, e acompanhe em **Actions**. Em ~6
minutos a mudança deve estar em `https://chatbrain.app.br` (Ctrl+Shift+R para furar o cache).

---

<a id="12-passo-9--autenticacao"></a>
## 12. Passo 9 — Autenticação (antes de abrir para a internet)

Hoje não existe login. O `AccessResolver` lê o cabeçalho `X-Access-Level` e, na falta dele, usa
`app.access.default-level`. Publicar assim significa: **qualquer um com a URL usa a sua API key da
Anthropic**, e se a alçada padrão for `admin`, também apaga módulos.

A solução do Caddyfile da seção 9.1 já resolve, e depende de três coisas juntas:

1. `ACCESS_DEFAULT_LEVEL: student` no container — o piso é consulta;
2. `header_up -X-Access-Level` no bloco público — apaga o cabeçalho que o cliente mandar;
3. `basic_auth` + `header_up X-Access-Level admin` no bloco `/api/admin/*` — só quem passa pela senha
   vira administrador.

**Isso só funciona porque o Caddy é o único caminho até a API.** A porta 8080 não está no Security
Group. Se você abri-la "para testar", a proteção inteira cai.

### Gerar o hash da senha

```powershell
docker run --rm caddy:2-alpine caddy hash-password --plaintext "uma-senha-longa-e-aleatoria"
# $2a$14$...
```

```powershell
aws ssm put-parameter --name "/cloudability/admin_password_hash" `
  --type SecureString --value '$2a$14$...' --overwrite
```

Redeploy (push para `main`, ou rode o `deploy.sh` na mão) e teste:

```bash
curl -i https://chatbrain.app.br/api/modules              # 200 — consulta liberada
curl -i https://chatbrain.app.br/api/admin/modules        # 401 — pede senha
curl -i -H "X-Access-Level: admin" https://chatbrain.app.br/api/modules  # o cabeçalho é ignorado
```

> **Quer fechar o site inteiro?** Acrescente um `basic_auth` também no bloco `handle` do estático,
> com outro usuário. Enquanto não houver login de verdade, é a barreira mais honesta entre a sua fatura
> da Anthropic e a internet.
>
> **O caminho definitivo** é trocar `AccessResolver.resolve(...)` para ler um usuário autenticado, em
> vez do cabeçalho. Nesse dia, remova o suporte ao `X-Access-Level` — e nem o domínio nem os
> controllers precisam mudar.

---

<a id="13-passo-10--alarmes-de-custo"></a>
## 13. Passo 10 — Alarmes de custo

### 13.1 AWS Budget (faça antes de dormir tranquila)

```powershell
$account = (aws sts get-caller-identity --query Account --output text)

@'
{
  "BudgetName": "cloudability-mensal",
  "BudgetLimit": { "Amount": "20", "Unit": "USD" },
  "TimeUnit": "MONTHLY",
  "BudgetType": "COST"
}
'@ | Out-File -Encoding utf8 budget.json

@'
[{
  "Notification": {
    "NotificationType": "ACTUAL",
    "ComparisonOperator": "GREATER_THAN",
    "Threshold": 50,
    "ThresholdType": "PERCENTAGE"
  },
  "Subscribers": [{ "SubscriptionType": "EMAIL", "Address": "voce@exemplo.com" }]
}]
'@ | Out-File -Encoding utf8 notifications.json

aws budgets create-budget --account-id $account `
  --budget file://budget.json `
  --notifications-with-subscribers file://notifications.json
```

Também ligue, no console: **Billing → Billing preferences → Free tier usage alerts** (avisa quando
você chega a 85% de qualquer cota do free tier).

### 13.2 Limite na Anthropic

Console da Anthropic → **Plans & Billing** → **Usage limits**. Defina um teto mensal. É o único
freio de verdade contra uma fatura surpresa da API — a AWS Budget avisa, mas não bloqueia.

### 13.3 Onde olhar quando algo parecer caro

- **AWS → Cost Explorer**, agrupando por *Service* e filtrando pela tag `Project=cloudability-chat`
  (o `default_tags` do Terraform marcou tudo).
- **Neon → Monitoring → Compute hours** — se a curva for uma linha reta, reveja a seção 4.4.
- **Anthropic Console → Usage** — tokens por dia.

---

<a id="14-troubleshooting"></a>
## 14. Troubleshooting

| Sintoma | Causa provável | Correção |
|---|---|---|
| `aws sts get-caller-identity` funciona no host e falha no container | hop limit do IMDSv2 é 1 | `aws ec2 modify-instance-metadata-options --instance-id i-xxx --http-tokens required --http-put-response-hop-limit 2` (o Terraform já põe 2) |
| Caddy não emite certificado | DNS ainda não propagou, ou porta 80 fechada, ou proxy laranja (se usar Cloudflare) | `nslookup chatbrain.app.br` tem de devolver o Elastic IP; porta 80 aberta no SG; nuvem **cinza** |
| Resposta do chat chega de uma vez, não token a token | buffering | `flush_interval -1` no `reverse_proxy`; se usar Cloudflare, proxy desligado |
| API reinicia sozinha, some do `docker ps` | OOM killer | `dmesg -T \| grep -i oom`. Reduza `-Xmx`, confirme o swap com `free -h`, ou troque para `t3.small` |
| TEI nunca fica *healthy* | está baixando o modelo (500 MB) | espere 3–5 min no primeiro boot; `docker compose logs embeddings` |
| Flyway falha no boot | extensão `vector` ausente no Neon | `CREATE EXTENSION IF NOT EXISTS vector;` no SQL Editor |
| `FATAL: password authentication failed for user "cloudability"` (local) | o `.env` tem os dados do Neon, mas o compose fixa `DB_URL`/`DB_USER` — a API está tentando o **Postgres local** com a senha do Neon | torne as três variáveis substituíveis no `docker-compose.yml` ([4.5](#4-passo-1--banco-no-neon)). O nome de usuário no erro denuncia: `cloudability` é o padrão local, `neondb_owner` é o do Neon |
| `FATAL: password authentication failed for user "neondb_owner"` | senha do Neon trocada ou endpoint pooled | use o endpoint **direto**; regenere a senha e atualize o `.env` e o SSM |
| `The endpoint ID is not specified` | o host da URL está errado — provavelmente montado à mão a partir do Project ID | copie a string do botão **Connect** do console; o host é `ep-...`, não o Project ID ([4.3](#4-passo-1--banco-no-neon)) |
| Neon consumiu as 100 CU-horas | HikariCP mantendo conexões abertas | as três variáveis da seção 4.4 |
| Actions: `Not authorized to perform sts:AssumeRoleWithWebIdentity` | trust policy não bate com o repositório/branch | confira `github_repo` no tfvars e que o push foi para `main` |
| Actions: `An error occurred (AccessDeniedException) when calling the SendCommand` | `EC2_INSTANCE_ID` errado, ou a instância sem SSM Agent registrado | `aws ssm describe-instance-information` tem de listar a instância |
| `/api/admin` responde 200 sem pedir senha | porta 8080 exposta, ou `header_up -X-Access-Level` faltando | revise o SG e o Caddyfile |
| Sincronizar material não acha nada | prefixo ou nome do bucket divergente | `aws s3 ls s3://<bucket>/modules/cloudability/` de dentro da instância |

### Comandos de socorro

```bash
# Shell na instância, sem SSH
aws ssm start-session --target i-0c90602040e3c30b0

# Dentro dela
cd /opt/cloudability
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail 200 api
docker compose -f docker-compose.prod.yml restart api
free -h && docker stats --no-stream

# Voltar para uma imagem anterior
IMAGE_TAG=<sha-anterior> ECR_REGISTRY=... ARTIFACTS_BUCKET=... \
MATERIAL_BUCKET=... SITE_DOMAIN=... ./deploy.sh
```

### Desligar tudo

```powershell
cd infra
terraform destroy
```

Isso apaga a instância, o EIP, o ECR e — atenção — **os buckets** (o Terraform recusa buckets com
objetos; esvazie antes, ou faça backup do material). O Neon e o domínio não são tocados: cancele o
projeto no Neon e deixe o domínio expirar, se for o caso.

> Para só parar de pagar por um tempo: `aws ec2 stop-instances --instance-ids i-xxx`. **O Elastic IP
> continua cobrando** enquanto existir. Se a pausa for longa, libere o EIP e refaça o registro A
> quando voltar.

---

<a id="15-checklist-final"></a>
## 15. Checklist final

**Antes de começar**
- [ ] Fase 1 rodando local, com material indexado
- [ ] `git init` feito, repositório criado no GitHub, `.env` fora do versionamento
- [ ] Conta AWS criada e free tier identificado (seção 2.1)
- [ ] Usuário IAM `terraform-admin` com MFA; `aws sts get-caller-identity` funcionando

**Infraestrutura**
- [ ] Neon: projeto em `us-east-2`, `CREATE EXTENSION vector`, Flyway validado do local
- [ ] Domínio comprado e zona DNS acessível no painel do Registro.br
- [ ] `terraform apply` concluído; outputs anotados
- [ ] Registros A (`@` e `www`) criados, **nuvem cinza**
- [ ] Material enxuto sincronizado no bucket (`.mp4` e PPTX redundantes de fora)
- [ ] `aws sts get-caller-identity` dentro de um container mostrando `cloudability-app-role`

**Aplicação**
- [ ] Segredos no SSM (5 parâmetros)
- [ ] Deploy manual feito; `https://chatbrain.app.br` com cadeado
- [ ] Streaming SSE chegando token a token
- [ ] Material indexado (rota "de casa")
- [ ] `free -h` mostrando folga — se não, `t3.small`

**Proteção**
- [ ] `ACCESS_DEFAULT_LEVEL=student` no container
- [ ] `basic_auth` no `/api/admin/*` com hash real no SSM
- [ ] `header_up -X-Access-Level` no bloco público
- [ ] Porta 8080 **fechada** no Security Group
- [ ] AWS Budget de US$ 20 com alerta em 50%
- [ ] Limite de gasto configurado no console da Anthropic

**Automação**
- [ ] Secret `AWS_ROLE_ARN` e as 6 variables no repositório
- [ ] `ci.yml` verde num PR
- [ ] `deploy.yml` verde num push para `main`, com mudança visível no site

---

## Ordem de execução, em uma linha

Neon → domínio e DNS → enxugar o material → Terraform → registros A → sync do material → segredos no
SSM → deploy manual → indexar de casa → **autenticação** → alarmes de custo → GitHub Actions.

> Automatizar antes de ter feito o deploy manual uma vez custa mais tempo do que economiza: você
> acaba depurando o pipeline e a infraestrutura ao mesmo tempo, sem saber de qual dos dois veio o
> erro.
