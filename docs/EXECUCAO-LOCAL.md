# Execução local

## Pré-requisitos

Situação verificada nesta máquina em **30/08/2026** — está tudo pronto.

| Ferramenta | Situação | Versão |
|---|---|---|
| **Docker Desktop** | ✅ instalado e rodando | Engine 29.7.2 / Compose v5.4.0 |
| **WSL 2** | ✅ `docker-desktop` + `Ubuntu` | WSL 2.4.11, kernel 5.15.167 |
| **JDK 21** | ✅ `JAVA_HOME` já aponta para o 21 | `21.0.12.1` em `C:\Users\Letícia Viana\Java\jdk-21.0.12.1` |
| **Maven** | ✅ | 3.9.6 |
| **Node** | ✅ | 22.19.0 / npm 11.10.1 |
| **Chave da API Anthropic** | ✅ configurada em `.env` e validada | resposta `HTTP 200` com crédito na conta |

> ✅ **A chave já foi testada contra a API** (`POST /v1/messages`, modelo `claude-opus-5`, HTTP 200).
> Isso confirma o que o [CONFIGURACAO-ANTHROPIC.md](CONFIGURACAO-ANTHROPIC.md) alerta: assinatura
> Claude Pro/Max **não** dá acesso à API — é preciso crédito no Console, e há.

---

## Segredos: `.env` vs `.env.example`

Dois arquivos, papéis diferentes — não confunda:

| Arquivo | Conteúdo | Vai para o Git? |
|---|---|---|
| `.env` | a chave **real** | ❌ não — `.gitignore` |
| `.env.example` | template com `sk-ant-...` | ✅ sim |

O `.env` da raiz já existe e está preenchido. Se precisar recriar:

```bash
cp .env.example .env
# edite .env e preencha ANTHROPIC_API_KEY
```

> ⚠️ **Só o `docker compose` lê o `.env`.** Rodando o backend pela IDE ou por `mvn spring-boot:run`,
> o Spring Boot **ignora** esse arquivo — a variável tem que vir do ambiente. Ver
> [Opção B](#opção-b--sem-docker-desenvolvimento) e [IntelliJ](#rodando-pela-intellij-idea).

Nunca coloque a chave no `application.yml`. Ele já a lê do ambiente:

```yaml
app:
  anthropic:
    api-key: ${ANTHROPIC_API_KEY:}
```

---

## Opção A — Docker (recomendado)

O `.env` já está pronto, então é um comando só:

```bash
docker compose up --build
```

Acesse **http://localhost**.

O compose repassa a chave ao backend via `ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}` — não é preciso
exportar nada no shell.

### Primeira execução

1. Menu lateral → **Administração**
2. Com o módulo **IBM Cloudability** selecionado, clique em **Sincronizar material**
3. Aguarde. A primeira indexação é demorada — são dezenas de transcrições, PDFs e apresentações,
   mais a transcrição do `Perguntas.png` pela visão do Claude.

Antes disso, o serviço `material-seed` já publicou o conteúdo de `ingestao/` no LocalStack, em
`s3://cloudability-material/modules/cloudability/`. É de lá que a sincronização lê — o backend não
enxerga mais o disco do projeto.

O container de embeddings baixa o modelo (~470 MB) no primeiro boot. O healthcheck dá 2 minutos
para isso; enquanto ele não fica saudável, o backend não sobe (é a dependência declarada no compose).

> 💡 Com 15,8 GB de RAM os containers cabem, mas o de embeddings é o pesado. Feche o que não
> estiver usando na primeira subida.

### Serviços do compose

| Serviço | Porta | Função |
|---|---|---|
| `db` | 5432 | Postgres 16 + pgvector |
| `localstack` | 4566 | S3 local — guarda o material de estudo |
| `material-seed` | — | One-shot: cria o bucket e publica `ingestao/` no módulo `cloudability` |
| `embeddings` | 8081 | Text Embeddings Inference (CPU) |
| `api` | 8080 | Backend Spring Boot |
| `web` | 80 | Frontend servido por nginx, com proxy para `/api` |

> O `material-seed` roda até o fim e sai — `docker compose ps` mostra `exited (0)`, o que é o
> esperado. O `api` só sobe depois disso (`service_completed_successfully`).
>
> Ele roda **a cada** `docker compose up` porque o LocalStack community não persiste objetos entre
> reinícios. O `s3 sync` é idempotente, então repetir não custa nada além do tempo de upload local.

### Comandos úteis

```bash
docker compose logs -f api            # acompanhar o backend
docker compose logs -f embeddings     # ver o download do modelo
docker compose logs material-seed     # conferir o que foi para o bucket
docker compose restart api            # reiniciar só o backend
docker compose down                   # parar tudo (mantém os dados)
docker compose down -v                # parar e APAGAR o banco e o cache do modelo
```

### Olhando o bucket local

```bash
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1

aws --endpoint-url http://localhost:4566 s3 ls s3://cloudability-material/modules/
aws --endpoint-url http://localhost:4566 s3 ls --recursive \
  s3://cloudability-material/modules/cloudability/ | head
```

---

## Opção B — Sem Docker (desenvolvimento)

Você ainda precisa de um Postgres com `pgvector` — e de algum lugar para o material. O caminho mais
simples é subir só a infraestrutura pelo compose:

```bash
docker compose up db embeddings localstack material-seed
```

Instalando na mão, crie o banco e habilite a extensão:

```sql
CREATE DATABASE cloudability;
\c cloudability
CREATE EXTENSION IF NOT EXISTS vector;
```

### Backend (PowerShell)

```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."   # obrigatorio: o .env NAO e lido aqui

# O material vem do LocalStack, que fora do compose atende em localhost.
$env:MATERIAL_ENDPOINT   = "http://localhost:4566"
$env:AWS_ACCESS_KEY_ID   = "test"
$env:AWS_SECRET_ACCESS_KEY = "test"

cd ibm
mvn spring-boot:run
```

Sem Docker nenhum, dá para trocar o S3 por uma pasta em disco — mesmo layout, uma pasta por módulo:

```powershell
$env:MATERIAL_PROVIDER = "filesystem"   # base-path padrao: ../material, a partir de ibm/
```

```bash
# entao copie o material para a pasta do modulo, no mesmo layout do bucket
mkdir -p material/modules/cloudability
cp -r ingestao/* material/modules/cloudability/
```

> `JAVA_HOME` já aponta para o JDK 21, então não precisa mais ajustá-lo — o `mvn -v` confirma
> `Java version: 21.0.12.1`.

Sem o container de embeddings, dá para subir com um fallback:

```powershell
$env:EMBEDDING_PROVIDER = "none"
```

> ⚠️ `EMBEDDING_PROVIDER=none` usa um vetor determinístico por *hashing*, só para a aplicação subir.
> **A busca semântica fica ruim** — serve para desenvolver a interface, não para estudar. Para uso
> real, suba o serviço (`docker compose up embeddings`) e volte para `EMBEDDING_PROVIDER=tei`.

### Rodando pela IntelliJ IDEA

Dois ajustes, ambos únicos:

1. **JDK do projeto.** O `ibm/.idea/misc.xml` está em `JDK_17`, mas o `pom.xml` exige
   `<java.version>21</java.version>`. Corrija em `File > Project Structure > Project SDK` → **21**.
2. **A chave.** Em `Run > Edit Configurations > Environment variables`, adicione
   `ANTHROPIC_API_KEY=sk-ant-...`. Melhor ainda: instale o plugin **EnvFile** e aponte para o `.env`
   da raiz — assim o segredo não fica duplicado na configuração da IDE.

### Frontend

```bash
cd web
npm install
npm run dev
```

Abre em **http://localhost:5173**, com proxy de `/api` para `localhost:8080` (configurado no
`vite.config.ts`).

---

## Variáveis de ambiente

| Variável | Padrão | Descrição |
|---|---|---|
| `ANTHROPIC_API_KEY` | — | **Obrigatória.** Sem ela a aplicação sobe mas o chat retorna erro |
| `ANTHROPIC_MODEL` | `claude-opus-5` | Modelo usado nas respostas |
| `ANTHROPIC_EFFORT` | `high` | `low`, `medium`, `high`, `xhigh` ou `max` |
| `ANTHROPIC_MAX_TOKENS` | `16000` | Teto de tokens da resposta |
| `ANTHROPIC_ECONOMY_EFFORT` | `low` | Profundidade de raciocínio no modo econômico |
| `ANTHROPIC_ECONOMY_MAX_TOKENS` | `4000` | Teto de saída no modo econômico |
| `RAG_ECONOMY_TOP_K` | `3` | Trechos no prompt no modo econômico |
| `ANTHROPIC_IMAGE_MAX_EDGE` | `1280` | Lado maior até onde um print vai sem redução. `0` desliga |
| `DB_URL` | `jdbc:postgresql://localhost:5432/cloudability` | Conexão JDBC |
| `DB_USER` / `DB_PASSWORD` | `cloudability` | Credenciais do banco |
| `EMBEDDING_PROVIDER` | `tei` | `tei` ou `none` |
| `EMBEDDING_URL` | `http://localhost:8081` | Endereço do serviço de embeddings |
| `EMBEDDING_DIMENSIONS` | `384` | Deve casar com o modelo **e** com a migração SQL |
| `RAG_TOP_K` | `8` | Quantos trechos entram no prompt |
| `MATERIAL_PROVIDER` | `s3` | `s3` ou `filesystem` |
| `MATERIAL_BUCKET` | `cloudability-material` | Bucket do material |
| `MATERIAL_PREFIX` | `modules` | Pasta raiz no bucket; abaixo dela, uma por módulo |
| `MATERIAL_ENDPOINT` | *(vazio)* | Endpoint do S3. Preenchido só para o LocalStack; **vazio na AWS** |
| `MATERIAL_CREATE_BUCKET` | `false` | Cria o bucket no boot se faltar. `true` no compose local |
| `MATERIAL_BASE_PATH` | `../material` | Raiz em disco, só quando `MATERIAL_PROVIDER=filesystem` |
| `AWS_REGION` | `us-east-1` | Região do bucket |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | *(vazio)* | Credenciais estáticas. **Vazias na AWS**: o SDK usa a IAM Role da instância |
| `ACCESS_DEFAULT_LEVEL` | `admin` | Alçada de quem chama enquanto não há autenticação: `admin` ou `student` |
| `ACCESS_AUTH_ENABLED` | `false` | Reservado para quando o login existir |
| `KB_AUTO_INGEST` | `false` | `true` sincroniza todos os módulos automaticamente no boot |
| `CORS_ORIGINS` | `localhost:5173,4173,3000` | Origens liberadas |
| `SERVER_PORT` | `8080` | Porta do backend |

> **Sobre `ACCESS_DEFAULT_LEVEL`:** não existe autenticação ainda. Este valor é a alçada de **todo
> mundo** que alcança a API. `admin` é conveniente em `localhost` e perigoso em qualquer outro
> lugar — ver a seção de segurança em [FASE-2-AWS.md](FASE-2-AWS.md).

> **Sobre `EMBEDDING_DIMENSIONS`:** trocar o modelo de embeddings normalmente muda a dimensão do
> vetor. A coluna é `vector(384)` na migração `V1__init.sql` — mudar a dimensão exige uma migração
> nova e **reindexar todo o material**.

---

## Testes

```powershell
cd ibm
mvn test
```

Cobrem as peças com lógica não trivial, todas sem Spring e sem banco: a limpeza de legendas VTT
(remoção de timestamps e das linhas repetidas das legendas automáticas), o chunking com
sobreposição, o fluxo de RAG de ponta a ponta — **incluindo o isolamento entre módulos** — as regras
de criação e remoção de módulos, e o guarda-corpo que impede o domínio de importar framework, SDK ou
banco.

```bash
cd web
npm run build     # roda tsc em modo strict + build do Vite
```

---

## Problemas comuns

| Sintoma | Causa provável |
|---|---|
| Chat responde "ANTHROPIC_API_KEY não configurada" | A variável não chegou ao backend. Procure o WARN de `AnthropicConfig` no log. No Docker, confira o `.env`; fora dele, a variável do shell / Run Configuration |
| A resposta aparece de uma vez, sem efeito de digitação | Buffering em algum proxy. Ver `proxy_buffering off` no `nginx.conf` |
| "Falha ao gerar embeddings" | Container de embeddings fora do ar ou ainda baixando o modelo |
| Sincronização indexa 0 arquivos | O bucket está vazio. Rode `docker compose up material-seed` e confira o log dele |
| `NoSuchBucket` no log do backend | O `material-seed` falhou ou o `MATERIAL_BUCKET` do `api` não bate com o dele |
| Backend não sobe, esperando `material-seed` | O one-shot falhou. `docker compose logs material-seed` mostra o erro do `aws` |
| "Modulo desconhecido: X" | Slug errado na URL, ou o módulo foi removido. `GET /api/modules` lista os válidos |
| Menu **Administração** não aparece | `ACCESS_DEFAULT_LEVEL` está como `student` |
| `mvn clean` falha ao apagar `target/` | Arquivo travado pelo OneDrive. Feche a IDE, ou apague `ibm/target` manualmente |
| Nenhum trecho recuperado | Base vazia — rode **Sincronizar material** |
| IntelliJ acusa erro em código Java 21 válido | `misc.xml` em `JDK_17`. Ver [Rodando pela IntelliJ](#rodando-pela-intellij-idea) |
| `wsl` falha com `OOBE command "/usr/lib/wsl/wsl-setup" failed, exiting` | Bug do Ubuntu com perfil Windows acentuado — ver abaixo |

### O erro de OOBE do WSL (resolvido)

O `wsl-setup` do Ubuntu 24.04 lê `$Env:LocalAppData` via `powershell.exe` e recebe o caminho na
codepage do console. O `í` de `C:\Users\Letícia Viana` chega ao bash como byte inválido em UTF-8, o
`mkdir -p` do diretório de fontes falha, e como o script roda sob `set -euo pipefail` **sem tratar
essa linha**, ele aborta — antes mesmo de chegar na criação do usuário.

Ou seja: falha ao instalar uma *fonte*. Nada a ver com Docker, que usa a própria distro
`docker-desktop` e nunca dependeu da Ubuntu.

Contorno aplicado (criar o usuário manualmente, pulando o OOBE):

```bash
wsl -d Ubuntu -u root -e bash -c "adduser --disabled-password --gecos '' leticia && usermod -aG adm,cdrom,sudo,dip,plugdev leticia && printf '\n[user]\ndefault=leticia\n' >> /etc/wsl.conf"

wsl --terminate Ubuntu
wsl -u root passwd leticia   # define a senha — sem ela o sudo nao funciona
```
