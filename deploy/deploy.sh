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

# Cada comando na sua propria linha, de proposito. Em "A && B", o set -e NAO
# aborta quando A falha -- e foi assim que um deploy quebrado se declarou bem
# sucedido: o rm falhou, o tar falhou, e o script seguiu ate o fim.
#
# O chmod existe porque um tarball criado no Windows chega com o diretorio raiz
# em modo 0555. Extraido uma vez, ele deixa $APP/web sem permissao de escrita, e
# a partir dai nenhum deploy consegue trocar o index.html -- os assets mudam, o
# index.html fica para tras e o front quebra com erro de MIME type.
chmod -R u+w "$APP/web" 2>/dev/null || true
rm -rf "$APP/web"
mkdir -p "$APP/web"
tar -xzf /tmp/web.tar.gz -C "$APP/web"
rm -f /tmp/web.tar.gz

# 2. Segredos do Parameter Store viram o .env (nunca passam pelo GitHub)
PROJECT=$PROJECT AWS_REGION=$AWS_REGION "$APP/fetch-env.sh"

# O docker compose interpola $ nos valores do --env-file: um hash bcrypt
# ($2a$14$abc...) perde todo trecho que pareca nome de variavel e chega
# truncado ao container -- o basic_auth passa a recusar qualquer senha.
# Dobrar o $ e como se escapa; o compose devolve um $ literal.
sed -i 's/\$/$$/g' "$APP/.env"

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
