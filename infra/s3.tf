locals {
  material_bucket  = "${var.project}-material-${var.bucket_suffix}"
  artifacts_bucket = "${var.project}-artifacts-${var.bucket_suffix}"
}

# ------------------------------------------------------------------ material
# Estado, não artefato. É lido e escrito pela aplicação; o pipeline não toca.
resource "aws_s3_bucket" "material" {
  bucket = local.material_bucket
}

resource "aws_s3_bucket_public_access_block" "material" {
  bucket                  = aws_s3_bucket.material.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "material" {
  bucket = aws_s3_bucket.material.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # SSE-S3: sem custo, sem KMS para gerenciar
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "material" {
  bucket = aws_s3_bucket.material.id
  rule {
    id     = "abortar-uploads-incompletos"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# ----------------------------------------------------------------- artefatos
# Build do front e arquivos de deploy. Descartável por definição.
resource "aws_s3_bucket" "artifacts" {
  bucket = local.artifacts_bucket
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    id     = "expirar-builds-antigos"
    status = "Enabled"
    filter { prefix = "web/" }
    expiration { days = 30 }
  }
  rule {
    id     = "abortar-uploads-incompletos"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}
