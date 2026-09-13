# Fase 2 — Deploy na AWS e CI/CD

> **Status: planejamento.** Nada aqui foi provisionado. A fase 1 (local, via `docker compose`) é o
> pré-requisito — só suba para a nuvem depois que o fluxo local estiver funcionando.
>
> 👉 **Para executar de fato, há dois runbooks — escolha pelo domínio:**
> [FASE-2-PASSO-A-PASSO.md](FASE-2-PASSO-A-PASSO.md) se você vai **comprar um domínio** (Caddy +
> Let's Encrypt), ou [FASE-2-EXECUCAO-AWS.md](FASE-2-EXECUCAO-AWS.md) se quer HTTPS **sem domínio**
> (CloudFront). Os dois usam Terraform, GitHub Actions, IAM Role no EC2 e Neon.
>
> Esta doc continua valendo como o *porquê* das decisões. Os dois runbooks divergem dela em
> pontos deliberados (x86 em vez de Graviton, Terraform em vez de comandos avulsos); o
> `FASE-2-EXECUCAO-AWS.md` lista essas diferenças na seção 14.

---

## 1. Decisão central: quanto você quer pagar

A aplicação tem cinco peças: **frontend estático**, **backend Java**, **Postgres com pgvector**,
**serviço de embeddings** e o **material de estudo no S3**. A conta muda drasticamente conforme onde
cada uma roda.

### Comparação de arquiteturas

| | **A. Econômica** | **B. Equilibrada** | **C. Gerenciada AWS** |
|---|---|---|---|
| Frontend | S3 + CloudFront | S3 + CloudFront | S3 + CloudFront |
| Backend | Docker em 1 EC2 `t4g.small` | App Runner | ECS Fargate + ALB |
| Material | S3 (IAM Role no EC2) | S3 | S3 |
| Postgres | **Neon** ou **Supabase** (free/pro) | Neon Pro | RDS `db.t4g.micro` |
| Embeddings | no mesmo EC2 | **Voyage AI** (API) | SageMaker Serverless |
| **Custo/mês** | **~US$ 8–15** | **~US$ 35–50** | **~US$ 90–130** |
| Esforço | médio (você gerencia a VM) | baixo | alto |

**Recomendação para o seu caso — arquitetura A.** É um site pessoal de estudos, com poucos usuários
e tráfego baixíssimo. Pagar ALB (US$ 16/mês sozinho) e RDS (US$ 15+/mês) para isso é desperdício.

**O backend fica no EC2**, em container, e alcança o material no S3 por uma **IAM Role anexada à
instância** — sem chave de acesso nenhuma no servidor. É o desenho descrito em detalhe na seção 3.

### Por que Postgres fora da AWS

O RDS é o item mais caro da conta e o `pgvector` não exige nada que só a AWS tenha. Duas
alternativas muito mais baratas, ambas com `pgvector` nativo:

- **[Neon](https://neon.tech)** — free tier com 0,5 GB; *scale-to-zero* (não paga quando ocioso).
  Plano pago US$ 19/mês. **É a recomendação**: o scale-to-zero encaixa perfeitamente num app que
  você usa algumas horas por semana.
- **[Supabase](https://supabase.com)** — free tier com 500 MB, Postgres 15 + pgvector.

Sua base inteira (todo o `ingestao/` vetorizado) deve ficar em **menos de 100 MB** — cabe
folgadamente no free tier dos dois.

### Por que considerar embeddings via API na nuvem

O container TEI precisa de ~2 GB de RAM. Numa `t4g.small` (2 GB) ele briga com o backend Java.
Duas saídas:

1. **`t4g.medium`** (4 GB, ~US$ 24/mês) e mantém tudo local à VM.
2. **Voyage AI** (`voyage-3-lite`) — é o parceiro de embeddings recomendado pela Anthropic. Custa
   ~US$ 0,02 por milhão de tokens. Indexar todo o seu material custaria **centavos**, e as consultas
   são irrisórias. Libera a VM inteira para o backend.

A opção 2 sai mais barata e é menos trabalhosa. O código já está preparado: o adaptador de
embeddings fica atrás da porta `EmbeddingPortOut`, então é escrever outra implementação e trocar a
variável de ambiente.

---

## 2. Arquitetura recomendada (A) em detalhe

```
                       ┌──────────────────────┐
   navegador  ────────▶│  CloudFront (CDN)    │
                       └──────────┬───────────┘
                        /          \
                  /* estático       /api/*
                       │              │
              ┌────────▼──────┐  ┌────▼─────────────────────┐
              │  S3 (bucket)  │  │  EC2 t4g.small           │
              │  build do web │  │  Docker: api (Java)      │
              └───────────────┘  │  Caddy → TLS             │
                                 │  ┌────────────────────┐  │
                                 │  │ IAM Instance Profile│ │
                                 │  └─────────┬──────────┘  │
                                 └────────────┼─────────────┘
                                   │          │ (sem chave de acesso)
                    ┌──────────────┘          ▼
                    │              ┌──────────────────────────┐
                    │              │ S3: material de estudo   │
                    │              │  modules/<slug>/...      │
                    │              └──────────────────────────┘
                    ▼
        ┌────────────────────────┐   ┌──────────────┐   ┌────────────────┐
        │ Neon Postgres+pgvector │   │  Voyage AI   │   │  API Anthropic │
        │  (fora da AWS, free)   │   │  embeddings  │   │                │
        └────────────────────────┘   └──────────────┘   └────────────────┘
```

### Recursos AWS necessários

| Recurso | Especificação | Custo estimado |
|---|---|---|
| EC2 | `t4g.small` (ARM Graviton, 2 vCPU / 2 GB), Amazon Linux 2023 | ~US$ 12/mês (ou **grátis** no free tier de 12 meses com `t4g.micro`) |
| EBS | 20 GB gp3 | ~US$ 1,60/mês |
| S3 (frontend) | bucket do build, <10 MB | centavos |
| S3 (material) | material de todos os módulos, ~200 MB sem os `.mp4` | ~US$ 0,01/mês |
| IAM Role + Instance Profile | acesso do EC2 ao bucket do material | **grátis** |
| CloudFront | distribuição, tráfego baixo | ~US$ 0–1/mês |
| Route 53 | zona hospedada (se quiser domínio próprio) | US$ 0,50/mês + registro |
| ACM | certificado TLS | **grátis** |
| SSM Parameter Store | `ANTHROPIC_API_KEY` (SecureString) | **grátis** |

> Use **SSM Parameter Store (SecureString)** em vez de Secrets Manager. Faz a mesma coisa para este
> caso e não cobra.

> **Graviton (ARM):** as imagens `eclipse-temurin:21-jre-alpine`, `nginx` e `pgvector` têm build
> ARM64. Se optar por embeddings locais, confirme que a imagem do TEI tem tag ARM — senão use
> instância x86 (`t3.small`).

---

## 3. Passo a passo do provisionamento

A ordem importa: o bucket e a Role antes da instância, porque o Instance Profile é anexado na
criação (dá para anexar depois, mas é um passo a mais).

### 3.1 Banco (Neon)

1. Crie conta em neon.tech e um projeto na região `us-east-1` (perto do EC2).
2. No SQL Editor: `CREATE EXTENSION IF NOT EXISTS vector;`
3. Guarde a connection string. O Flyway cria o schema sozinho no primeiro boot — inclusive a
   migração `V2__modules.sql`, que cria a tabela de módulos e o módulo padrão `cloudability`.

### 3.2 Bucket do material

O layout é o mesmo do ambiente local: **uma pasta por módulo**.

```
s3://cloudability-material/
  modules/
    cloudability/          ← módulo de fábrica
      L1/ L2/ L4/ agents/
    <outro-modulo>/        ← criado pelo admin na interface
      enviados/
```

```bash
export BUCKET=cloudability-material
export REGION=us-east-1

aws s3api create-bucket --bucket "$BUCKET" --region "$REGION"

# Bloqueio total de acesso público: o material é lido pelo backend, nunca pelo navegador.
aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration \
  "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"

# Criptografia em repouso (SSE-S3, sem custo).
aws s3api put-bucket-encryption --bucket "$BUCKET" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

Suba o material que já existe (sem os vídeos — a ingestão lê as legendas `.vtt`, não os `.mp4`):

```bash
aws s3 sync ./ingestao "s3://$BUCKET/modules/cloudability" \
  --exclude "*.mp4" --exclude "*.MP4"
aws s3 sync ./.claude/agents "s3://$BUCKET/modules/cloudability/agents"
```

### 3.3 IAM Role para o EC2 — o acesso ao S3 sem chave nenhuma

Esta é a peça que substitui `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` no servidor. A instância
recebe credenciais temporárias, rotacionadas pela própria AWS, e o SDK as encontra sozinho pela
cadeia padrão de credenciais. **Nenhum segredo de longa duração toca o disco do EC2.**

**Trust policy** (`ec2-trust.json`) — quem pode assumir a role:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "ec2.amazonaws.com" },
    "Action": "sts:AssumeRole"
  }]
}
```

**Permission policy** (`material-access.json`) — o que ela pode fazer. Só o necessário: ler, gravar
e apagar objetos do bucket do material e listá-lo. Nada de `s3:*`, nada de outros buckets:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListarOBucketDoMaterial",
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": "arn:aws:s3:::cloudability-material"
    },
    {
      "Sid": "LerEGravarObjetos",
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::cloudability-material/*"
    }
  ]
}
```

> `PutObject` e `DeleteObject` não são opcionais: o upload do administrador grava o arquivo no
> bucket, e remover um módulo apaga a pasta dele. Uma role só de leitura quebra as duas coisas.

```bash
aws iam create-role --role-name CloudabilityAppRole \
  --assume-role-policy-document file://ec2-trust.json

aws iam put-role-policy --role-name CloudabilityAppRole \
  --policy-name MaterialBucketAccess \
  --policy-document file://material-access.json

# Opcional, recomendado: acesso de leitura ao parâmetro com a chave da Anthropic
aws iam attach-role-policy --role-name CloudabilityAppRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore

# O Instance Profile é o invólucro pelo qual o EC2 recebe a role
aws iam create-instance-profile --instance-profile-name CloudabilityAppProfile
aws iam add-role-to-instance-profile \
  --instance-profile-name CloudabilityAppProfile \
  --role-name CloudabilityAppRole
```

**Como o container enxerga isso.** O SDK dentro do container consulta o serviço de metadados da
instância (`169.254.169.254`). Isso funciona com a rede padrão do Docker (bridge) sem configuração
extra — só não funcione se você usar `--network none`. Se a instância estiver com **IMDSv2
obrigatório** (o padrão em instâncias novas), garanta o salto extra de rede que o container precisa:

```bash
aws ec2 modify-instance-metadata-options --instance-id i-xxxxx \
  --http-tokens required --http-put-response-hop-limit 2
```

> Esse `hop-limit 2` é a causa mais comum de "funciona no host, falha no container".

**Como verificar, já na instância:**

```bash
aws sts get-caller-identity          # deve mostrar .../CloudabilityAppRole/i-xxxxx
aws s3 ls s3://cloudability-material/modules/
```

### 3.4 Rede e instância

```bash
# Security group: 22 (só do seu IP), 80 e 443 (0.0.0.0/0)
aws ec2 create-security-group --group-name cloudability-sg \
  --description "Cloudability chat"

aws ec2 authorize-security-group-ingress --group-name cloudability-sg \
  --protocol tcp --port 443 --cidr 0.0.0.0/0
aws ec2 authorize-security-group-ingress --group-name cloudability-sg \
  --protocol tcp --port 80 --cidr 0.0.0.0/0
aws ec2 authorize-security-group-ingress --group-name cloudability-sg \
  --protocol tcp --port 22 --cidr SEU.IP.AQUI/32
```

Suba uma `t4g.small` com Amazon Linux 2023, **já com o Instance Profile**, associe um **Elastic IP**
(senão o IP muda a cada reboot) e instale o Docker:

```bash
aws ec2 run-instances \
  --image-id ami-xxxxxxxx \
  --instance-type t4g.small \
  --iam-instance-profile Name=CloudabilityAppProfile \
  --security-groups cloudability-sg \
  --metadata-options "HttpTokens=required,HttpPutResponseHopLimit=2"
```

```bash
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-aarch64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
```

### 3.5 Registro de imagens (ECR)

```bash
aws ecr create-repository --repository-name cloudability-api
```

O frontend **não** precisa de imagem — vai como estático para o S3.

### 3.6 TLS com Caddy e o compose de produção

Caddy obtém e renova certificado Let's Encrypt sozinho — bem mais simples que ALB+ACM e sem o
custo de US$ 16/mês do balanceador.

`/opt/cloudability/Caddyfile`:

```
api.seudominio.com.br {
    reverse_proxy api:8080 {
        flush_interval -1     # essencial: sem isso o streaming SSE trava
    }
}
```

`/opt/cloudability/docker-compose.prod.yml`:

```yaml
services:
  api:
    image: ${ECR_REGISTRY}/cloudability-api:${IMAGE_TAG}
    restart: unless-stopped
    environment:
      DB_URL: ${DB_URL}
      DB_USER: ${DB_USER}
      DB_PASSWORD: ${DB_PASSWORD}
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
      EMBEDDING_PROVIDER: voyage
      VOYAGE_API_KEY: ${VOYAGE_API_KEY}
      CORS_ORIGINS: https://seudominio.com.br

      # --------------------------------------------------------- material
      # Repare no que NÃO está aqui: nenhuma credencial da AWS. Sem
      # MATERIAL_ENDPOINT o SDK fala com o S3 de verdade, e sem
      # AWS_ACCESS_KEY_ID ele percorre a cadeia padrão até a IAM Role da
      # instância. MATERIAL_CREATE_BUCKET fica falso: criar bucket é trabalho
      # do provisionamento, e a role nem tem essa permissão.
      MATERIAL_PROVIDER: s3
      MATERIAL_BUCKET: cloudability-material
      MATERIAL_PREFIX: modules
      MATERIAL_CREATE_BUCKET: "false"
      AWS_REGION: us-east-1

      # ---------------------------------------------------------- alçadas
      # Enquanto não houver login, quem alcança a API entra com esta alçada.
      # Em produção use "student" e proteja /api/admin (ver seção 5).
      ACCESS_DEFAULT_LEVEL: student
    # Sem volume de material: ele vem do S3.

  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
volumes:
  caddy-data:
```

### 3.7 Frontend (S3 + CloudFront)

```bash
aws s3 mb s3://cloudability-chat-web
aws s3 website s3://cloudability-chat-web --index-document index.html
```

Crie a distribuição CloudFront com **dois origins**:

- **default (`/*`)** → bucket S3, com *custom error response* 403/404 → `/index.html` (200), para o
  roteamento da SPA.
- **`/api/*`** → o domínio do EC2, política de cache **`CachingDisabled`** e *origin request policy*
  **`AllViewer`**.

> ⚠️ **CloudFront e SSE:** o CloudFront faz buffering em algumas configurações e isso quebra o
> streaming token a token. Se a resposta chegar de uma vez só em vez de ir aparecendo, aponte o
> frontend direto para `https://api.seudominio.com.br` (CORS já está configurado no backend) e
> deixe o CloudFront servindo só o estático. É a rota mais segura.

### 3.8 Indexação do material

Com o material no S3, o backend em produção lê exatamente a mesma coisa que o local. Duas rotas:

1. **Sincronizar pela interface** — entre na área de administração, escolha o módulo e clique em
   *Sincronizar material*. O backend lista `modules/<slug>/`, extrai o texto e grava os vetores.
   É a rota normal, e a única para módulos criados depois.
2. **Indexar de casa e migrar só o banco** — aponte o `.env` local para o Neon e para o bucket de
   produção, rode a sincronização na sua máquina, e o servidor sobe com a base pronta. Útil no
   primeiro deploy, quando indexar todo o material de Cloudability leva um tempo considerável.

---

## 4. CI/CD com GitHub Actions

### 4.1 Pré-requisitos

Autentique via **OIDC** (sem chave de acesso estática no GitHub):

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com
```

Crie a role `GitHubActionsDeploy` com trust policy limitada ao seu repositório e permissões de
`ecr:*` (no seu repositório), `s3:PutObject` (no bucket do frontend), `cloudfront:CreateInvalidation`
e `ssm:SendCommand` (na instância).

> São **duas roles diferentes** e é bom que continuem assim: a `CloudabilityAppRole` é do EC2 e só
> enxerga o bucket do material; a `GitHubActionsDeploy` é do pipeline e não precisa do material.

**Secrets do repositório:** `AWS_ROLE_ARN`, `AWS_REGION`, `ECR_REGISTRY`, `S3_BUCKET`,
`CLOUDFRONT_ID`, `EC2_INSTANCE_ID`.

### 4.2 `.github/workflows/ci.yml` — validação em todo push

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
      - run: mvn -B verify
        working-directory: ./ibm

  frontend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: web/package-lock.json
      - run: npm ci
        working-directory: ./web
      - run: npm run build
        working-directory: ./web
```

### 4.3 `.github/workflows/deploy.yml` — deploy no push para `main`

```yaml
name: Deploy

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  id-token: write      # necessario para o OIDC
  contents: read

concurrency:
  group: deploy-production
  cancel-in-progress: false

jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: ${{ secrets.AWS_REGION }}
      - uses: aws-actions/amazon-ecr-login@v2
        id: ecr

      - name: Build e push da imagem
        working-directory: ./ibm
        run: |
          IMAGE=${{ steps.ecr.outputs.registry }}/cloudability-api:${{ github.sha }}
          docker build --platform linux/arm64 -t "$IMAGE" .
          docker push "$IMAGE"
          docker tag "$IMAGE" ${{ steps.ecr.outputs.registry }}/cloudability-api:latest
          docker push ${{ steps.ecr.outputs.registry }}/cloudability-api:latest

      - name: Atualizar container no EC2 (via SSM, sem SSH)
        run: |
          aws ssm send-command \
            --instance-ids ${{ secrets.EC2_INSTANCE_ID }} \
            --document-name AWS-RunShellScript \
            --comment "deploy ${{ github.sha }}" \
            --parameters 'commands=[
              "cd /opt/cloudability",
              "aws ecr get-login-password --region ${{ secrets.AWS_REGION }} | docker login --username AWS --password-stdin ${{ steps.ecr.outputs.registry }}",
              "IMAGE_TAG=${{ github.sha }} docker compose -f docker-compose.prod.yml up -d --pull always",
              "docker image prune -f"
            ]'

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
          aws-region: ${{ secrets.AWS_REGION }}

      - name: Build
        working-directory: ./web
        run: |
          npm ci
          npm run build

      - name: Publicar no S3
        working-directory: ./web
        run: |
          # assets com hash no nome: cache longo
          aws s3 sync dist/ s3://${{ secrets.S3_BUCKET }}/ \
            --delete --cache-control "public,max-age=31536000,immutable" \
            --exclude index.html
          # index.html nunca em cache
          aws s3 cp dist/index.html s3://${{ secrets.S3_BUCKET }}/index.html \
            --cache-control "no-cache,no-store,must-revalidate"

      - name: Invalidar CloudFront
        run: |
          aws cloudfront create-invalidation \
            --distribution-id ${{ secrets.CLOUDFRONT_ID }} \
            --paths "/index.html"
```

### 4.4 Detalhes que evitam dor de cabeça

- **`--platform linux/arm64`** no build: se o EC2 for Graviton e a imagem for x86, o container não
  sobe (`exec format error`).
- **SSM em vez de SSH**: sem chave privada no GitHub e sem porta 22 aberta para o mundo.
- **`concurrency`**: impede dois deploys simultâneos corromperem o estado.
- **Invalidação só do `index.html`**: os assets têm hash no nome; invalidar `/*` custa dinheiro
  depois das 1.000 invalidações gratuitas por mês.
- **Migrações Flyway**: rodam no boot do container. Se uma migração falhar, o backend não sobe —
  monitore o log do primeiro deploy após qualquer mudança de schema.
- **Deploy não mexe no material**: o bucket é estado, não artefato. O pipeline nunca deve dar
  `s3 sync --delete` nele.

---

## 5. Segurança

| Item | Como resolver |
|---|---|
| `ANTHROPIC_API_KEY` | SSM Parameter Store (SecureString), lido no boot pelo `docker compose`. **Nunca** no repositório |
| Credenciais da AWS no servidor | **Não existem.** IAM Role na instância (seção 3.3). Se você se pegar copiando uma chave para o EC2, algo saiu do trilho |
| Bucket do material | Acesso público bloqueado, SSE-S3 ligada, política restrita a uma role |
| Área administrativa | Hoje `/api/admin` só verifica a alçada, e a alçada vem de configuração. **Resolva antes de publicar** (ver abaixo) |
| Aplicação exposta na internet | Sem autenticação, qualquer um com a URL gasta os seus créditos da Anthropic |
| Uploads | O endpoint aceita arquivos até 50 MB. Está sob `/api/admin`, então herda o que for feito abaixo |
| Banco | Neon já força TLS. Restrinja o IP de origem ao Elastic IP do EC2 |

### Autenticação — o item mais importante desta lista

As alçadas já estão desenhadas no código: `ADMIN` e `STUDENT`, com tudo que escreve na base
concentrado sob `/api/admin` e barrado pelo `AdminAccessInterceptor`. **O que falta é a
identificação de quem chama** — hoje ela vem de `app.access.default-level`, ou seja, é a mesma para
todo mundo.

Dois caminhos, do mais simples ao mais completo:

1. **Basic Auth no Caddy, com dois escopos.** Resolve sem escrever código: o site inteiro pede
   senha, e `/api/admin` pede outra, mais forte. Ponha `ACCESS_DEFAULT_LEVEL=student` no container
   e faça o Caddy injetar o cabeçalho de alçada só no bloco protegido:

   ```
   api.seudominio.com.br {
       @admin path /api/admin/*
       handle @admin {
           basic_auth {
               admin $2a$14$hash_gerado_com_caddy_hash_password
           }
           reverse_proxy api:8080 {
               header_up X-Access-Level admin
               flush_interval -1
           }
       }
       handle {
           basic_auth {
               leticia $2a$14$outro_hash
           }
           reverse_proxy api:8080 {
               flush_interval -1
           }
       }
   }
   ```

   > Só funciona porque o Caddy é o **único** caminho até o backend. A porta 8080 não pode estar
   > aberta no security group — se estiver, qualquer um manda o cabeçalho `X-Access-Level: admin`
   > sozinho e o porteiro deixa passar.

2. **Login de verdade** (Cognito, ou usuários no próprio Postgres). O ponto de mudança é um só:
   `AccessResolver.resolve(...)` passa a ler o usuário autenticado em vez do cabeçalho, e nem o
   domínio nem os controllers mudam. Nesse dia, remova o suporte ao cabeçalho `X-Access-Level`.

---

## 6. Custo mensal estimado (arquitetura A)

| Item | Custo |
|---|---|
| EC2 `t4g.small` + EBS 20 GB | US$ 13,60 (US$ 0 no free tier do primeiro ano) |
| S3 (frontend + material) + CloudFront | ~US$ 1,00 |
| Route 53 (opcional) | US$ 0,50 |
| Neon Postgres | US$ 0 (free tier) |
| Voyage AI (embeddings) | < US$ 0,10 |
| **Infraestrutura** | **~US$ 15/mês** |
| API Anthropic | variável — ver abaixo |

**Sobre o custo da API:** é o item que realmente pesa e não tem teto fixo. Uma pergunta com RAG
consome ~4–6 mil tokens de entrada. Com `claude-opus-5`, cada 100 perguntas ficam em torno de
US$ 5–7. O cache do prompt de sistema (já implementado) corta parte disso. Se o uso crescer,
`claude-sonnet-5` reduz o custo em ~60%. Detalhes em
[CONFIGURACAO-ANTHROPIC.md](CONFIGURACAO-ANTHROPIC.md).

> **Configure um alerta de billing na AWS e um limite de gasto no console da Anthropic antes de
> publicar.** É a proteção contra a conta surpresa.

---

## 7. Ordem sugerida de execução

1. Fazer a fase 1 funcionar local, com a base indexada a partir do LocalStack.
2. Criar o Neon e apontar o `.env` local para ele — valida o banco remoto sem tocar na AWS.
3. Criar o bucket do material e a IAM Role. Validar com `aws s3 ls` de uma instância de teste.
4. Migrar os embeddings para Voyage e validar local.
5. **Implementar autenticação.**
6. Subir o EC2 com o Instance Profile, rodar o `docker-compose.prod.yml` na mão e validar — em
   especial: `aws sts get-caller-identity` de dentro do container mostrando a role.
7. Publicar o frontend no S3 + CloudFront.
8. Só então automatizar com o GitHub Actions.

Automatizar antes de ter feito o deploy manual uma vez costuma custar mais tempo do que economiza —
você acaba depurando o pipeline e a infraestrutura ao mesmo tempo.
