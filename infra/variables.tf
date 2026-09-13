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

variable "github_repo_immutable" {
  description = <<-EOT
    Forma imutavel do repositorio, como o GitHub emite hoje no claim "sub" do
    OIDC: usuario@ID_DA_CONTA/repo@ID_DO_REPO. Os IDs numericos nao mudam se
    voce renomear a conta ou o repositorio -- e por isso que o GitHub passou a
    usa-los. Descubra o valor real no CloudTrail, no campo userName do evento
    AssumeRoleWithWebIdentity. Deixe vazio se o seu sub ainda vier no formato
    antigo; os dois formatos sao aceitos.
  EOT
  type        = string
  default     = ""
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
