terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

provider "aws" {
  region = var.region

  # Toda tag aqui cai em todo recurso — é assim que você acha o que é seu
  # no Cost Explorer depois.
  default_tags {
    tags = {
      Project   = "cloudability-chat"
      ManagedBy = "terraform"
    }
  }
}
