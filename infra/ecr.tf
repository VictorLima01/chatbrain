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
