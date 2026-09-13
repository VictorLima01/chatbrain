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
