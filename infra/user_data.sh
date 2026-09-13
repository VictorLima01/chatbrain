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
