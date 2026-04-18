# =====================================================================
#  Roles IAM — uno por servicio, principio de menor privilegio
# =====================================================================

# ---- Rol Glue (crawlers + jobs) -------------------------------------
data "aws_iam_policy_document" "glue_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["glue.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "glue_service" {
  name               = "${var.project}-glue-service-role"
  assume_role_policy = data.aws_iam_policy_document.glue_assume.json
}

resource "aws_iam_role_policy_attachment" "glue_managed" {
  role       = aws_iam_role.glue_service.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSGlueServiceRole"
}

resource "aws_iam_role_policy" "glue_s3" {
  role = aws_iam_role.glue_service.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket"]
        Resource = flatten([
          for k, b in aws_s3_bucket.data : [b.arn, "${b.arn}/*"]
        ])
      },
      {
        Effect   = "Allow"
        Action   = ["logs:*"]
        Resource = "*"
      }
    ]
  })
}

# ---- Rol EC2 backend -------------------------------------------------
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ec2_backend" {
  name               = "${var.project}-ec2-backend-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy" "ec2_inline" {
  role = aws_iam_role.ec2_backend.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AthenaQueries"
        Effect = "Allow"
        Action = [
          "athena:StartQueryExecution", "athena:GetQueryExecution",
          "athena:GetQueryResults",     "athena:StopQueryExecution",
          "athena:ListWorkGroups",      "athena:GetWorkGroup",
          "athena:ListQueryExecutions", "athena:BatchGetQueryExecution"
        ]
        Resource = "*"
      },
      {
        Sid    = "GlueCatalog"
        Effect = "Allow"
        Action = [
          "glue:GetDatabase*", "glue:GetTable*", "glue:GetPartition*",
          "glue:GetCrawler*",  "glue:StartCrawler",
          "glue:GetJob*",      "glue:StartJobRun"
        ]
        Resource = "*"
      },
      {
        Sid    = "S3DataAccess"
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:PutObject", "s3:ListBucket", "s3:DeleteObject"]
        Resource = flatten([
          for k, b in aws_s3_bucket.data : [b.arn, "${b.arn}/*"]
        ])
      },
      {
        Sid    = "RedshiftDataAPI"
        Effect = "Allow"
        Action = [
          "redshift-data:ExecuteStatement", "redshift-data:DescribeStatement",
          "redshift-data:GetStatementResult", "redshift-data:ListStatements",
          "redshift-serverless:GetCredentials", "redshift-serverless:GetWorkgroup"
        ]
        Resource = "*"
      },
      {
        Sid      = "SecretsRead"
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = aws_secretsmanager_secret.app.arn
      },
      {
        Sid      = "CostExplorerRead"
        Effect   = "Allow"
        Action   = [
          "ce:GetCostAndUsage",
          "ce:GetCostForecast",
          "ce:GetUsageReport",
          "ce:GetReservationUtilization",
          "ce:DescribeCostCategoryDefinition"
        ]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "ec2_backend" {
  name = "${var.project}-ec2-backend-profile"
  role = aws_iam_role.ec2_backend.name
}

# ---- Rol Redshift (lee Parquet de S3 processed) ---------------------
data "aws_iam_policy_document" "rs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["redshift.amazonaws.com", "redshift-serverless.amazonaws.com"]
    }
  }
}
resource "aws_iam_role" "redshift" {
  name               = "${var.project}-redshift-role"
  assume_role_policy = data.aws_iam_policy_document.rs_assume.json
}
resource "aws_iam_role_policy" "redshift_s3" {
  role = aws_iam_role.redshift.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:GetObject", "s3:ListBucket"]
      Resource = [
        aws_s3_bucket.data["processed"].arn,
        "${aws_s3_bucket.data["processed"].arn}/*"
      ]
    }]
  })
}
resource "aws_iam_role_policy_attachment" "redshift_glue" {
  role       = aws_iam_role.redshift.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonRedshiftAllCommandsFullAccess"
}

# ---- Secret con credenciales y tokens del backend -------------------
resource "aws_secretsmanager_secret" "app" {
  name = "${var.project}-app-config"
  recovery_window_in_days = 0  # destrucción inmediata para iterar fácil en demo
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode({
    rds_host        = aws_db_instance.postgres.address
    rds_port        = aws_db_instance.postgres.port
    rds_db          = var.rds_db_name
    rds_user        = "app_ro"
    rds_password    = var.app_password
    edit_token      = var.edit_token
    github_token    = var.github_token
    github_repo     = var.github_repo
    public_bucket   = aws_s3_bucket.data["public"].bucket
    athena_db       = aws_glue_catalog_database.datalake.name
    athena_workgroup = aws_athena_workgroup.main.name
  })
}

output "redshift_role_arn" {
  value = aws_iam_role.redshift.arn
}
