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
