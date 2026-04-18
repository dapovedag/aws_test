locals {
  buckets = {
    raw            = "${var.project}-raw"
    staging        = "${var.project}-staging"
    processed      = "${var.project}-processed"
    public         = "${var.project}-public"
    athena_results = "${var.project}-athena-results"
    glue_scripts   = "${var.project}-glue-scripts"
  }
}

resource "aws_s3_bucket" "data" {
  for_each = local.buckets
  bucket   = each.value
}

# Versionado off para minimizar costo (es un demo)
resource "aws_s3_bucket_versioning" "data" {
  for_each = aws_s3_bucket.data
  bucket   = each.value.id
  versioning_configuration { status = "Suspended" }
}

# Encriptación AWS-managed (gratis)
resource "aws_s3_bucket_server_side_encryption_configuration" "data" {
  for_each = aws_s3_bucket.data
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

# Solo el bucket "public" permite ACLs y lecturas públicas — el resto block-all
resource "aws_s3_bucket_public_access_block" "private" {
  for_each = { for k, v in aws_s3_bucket.data : k => v if k != "public" }
  bucket   = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_public_access_block" "public" {
  bucket = aws_s3_bucket.data["public"].id
  block_public_acls       = false
  block_public_policy     = false
  ignore_public_acls      = false
  restrict_public_buckets = false
}

resource "aws_s3_bucket_ownership_controls" "public" {
  bucket = aws_s3_bucket.data["public"].id
  rule { object_ownership = "BucketOwnerPreferred" }
}

# Política pública de lectura solo para el bucket "public"
resource "aws_s3_bucket_policy" "public_read" {
  depends_on = [aws_s3_bucket_public_access_block.public]
  bucket = aws_s3_bucket.data["public"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "PublicReadGetObject"
      Effect    = "Allow"
      Principal = "*"
      Action    = ["s3:GetObject"]
      Resource  = ["${aws_s3_bucket.data["public"].arn}/*"]
    }]
  })
}

# CORS para que el frontend pueda hacer fetch desde el navegador
resource "aws_s3_bucket_cors_configuration" "public" {
  bucket = aws_s3_bucket.data["public"].id
  cors_rule {
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = ["*"]
    allowed_headers = ["*"]
    max_age_seconds = 3000
  }
}

# Lifecycle para eliminar resultados antiguos de Athena (cost saver)
resource "aws_s3_bucket_lifecycle_configuration" "athena" {
  bucket = aws_s3_bucket.data["athena_results"].id
  rule {
    id     = "expire-old-results"
    status = "Enabled"
    filter {}
    expiration { days = 7 }
  }
}

# Sube los scripts PySpark al bucket de scripts (consumido por los Glue jobs)
resource "aws_s3_object" "glue_scripts" {
  for_each = toset(["transform_dimensions.py", "transform_fact.py", "transform_aggregates.py"])
  bucket = aws_s3_bucket.data["glue_scripts"].id
  key    = each.value
  source = "${path.module}/glue_jobs/${each.value}"
  etag   = filemd5("${path.module}/glue_jobs/${each.value}")
}
