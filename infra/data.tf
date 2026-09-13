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
