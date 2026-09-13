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
