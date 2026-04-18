# =====================================================================
#  AWS Lake Formation — gobierno + tag-based access control
# =====================================================================

# Configura mi user IAM como data lake admin
resource "aws_lakeformation_data_lake_settings" "main" {
  admins = [
    data.aws_caller_identity.current.arn,
  ]
  trusted_resource_owners = [data.aws_caller_identity.current.account_id]

  # Permite a IAM users leer del Glue Catalog (para que Athena funcione "out of the box")
  create_database_default_permissions {
    permissions = ["ALL"]
    principal   = "IAM_ALLOWED_PRINCIPALS"
  }
  create_table_default_permissions {
    permissions = ["ALL"]
    principal   = "IAM_ALLOWED_PRINCIPALS"
  }
}

# Registra processed/ como Data Lake location
resource "aws_lakeformation_resource" "processed" {
  arn      = aws_s3_bucket.data["processed"].arn
  role_arn = aws_iam_role.lakeformation_service.arn
}

# Rol que LakeFormation asume al acceder a S3
data "aws_iam_policy_document" "lf_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lakeformation.amazonaws.com"]
    }
  }
}
resource "aws_iam_role" "lakeformation_service" {
  name               = "${var.project}-lakeformation-service"
  assume_role_policy = data.aws_iam_policy_document.lf_assume.json
}
resource "aws_iam_role_policy" "lf_s3" {
  role = aws_iam_role.lakeformation_service.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:GetObject", "s3:PutObject", "s3:ListBucket", "s3:DeleteObject"]
      Resource = [
        aws_s3_bucket.data["processed"].arn,
        "${aws_s3_bucket.data["processed"].arn}/*"
      ]
    }]
  })
}

# LF-Tags (depende de los settings para que el caller sea data lake admin)
resource "aws_lakeformation_lf_tag" "sensitivity" {
  key        = "Sensitivity"
  values     = ["Public", "Internal", "PII"]
  depends_on = [aws_lakeformation_data_lake_settings.main]
}
resource "aws_lakeformation_lf_tag" "domain" {
  key        = "Domain"
  values     = ["Provider", "Client", "Transaction", "Reference"]
  depends_on = [aws_lakeformation_data_lake_settings.main]
}

# Permisos por tag para el rol del backend EC2 (lee solo Sensitivity:Public)
resource "aws_lakeformation_permissions" "backend_public" {
  principal   = aws_iam_role.ec2_backend.arn
  permissions = ["SELECT", "DESCRIBE"]

  lf_tag_policy {
    resource_type = "TABLE"
    expression {
      key    = "Sensitivity"
      values = ["Public", "Internal"]
    }
  }
  depends_on = [
    aws_lakeformation_data_lake_settings.main,
    aws_lakeformation_lf_tag.sensitivity,
  ]
}
