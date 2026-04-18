resource "aws_glue_catalog_database" "datalake" {
  name        = "datalake_energia"
  description = "Catálogo del datalake de la comercializadora de energía."
}

resource "aws_glue_crawler" "raw" {
  name          = "${var.project}-crawler-raw"
  role          = aws_iam_role.glue_service.arn
  database_name = aws_glue_catalog_database.datalake.name
  description   = "Cataloga los CSVs extraídos del RDS en s3://prueba-aws-raw/."

  # Una tabla por sub-carpeta (proveedor, cliente, etc.) — sin agrupar
  dynamic "s3_target" {
    for_each = toset(["proveedor", "cliente", "ciudad", "tipo_energia", "transaccion"])
    content {
      path = "s3://${aws_s3_bucket.data["raw"].bucket}/${s3_target.value}/"
    }
  }

  schedule = "cron(0 * * * ? *)"  # cada hora
}

resource "aws_glue_crawler" "processed" {
  name          = "${var.project}-crawler-processed"
  role          = aws_iam_role.glue_service.arn
  database_name = aws_glue_catalog_database.datalake.name
  description   = "Registra las tablas Parquet generadas por los ETL en processed/."

  s3_target {
    path = "s3://${aws_s3_bucket.data["processed"].bucket}/"
  }
  schema_change_policy {
    update_behavior = "UPDATE_IN_DATABASE"
    delete_behavior = "DEPRECATE_IN_DATABASE"
  }
}

# 3 ETL jobs PySpark (las 3 transformaciones que pide el PDF)
locals {
  etl_jobs = {
    "transform-dimensions" = {
      script = "transform_dimensions.py"
      args   = { "--raw_bucket" = aws_s3_bucket.data["raw"].bucket
                 "--processed_bucket" = aws_s3_bucket.data["processed"].bucket
                 "--glue_db" = aws_glue_catalog_database.datalake.name }
    }
    "transform-fact" = {
      script = "transform_fact.py"
      args   = { "--raw_bucket" = aws_s3_bucket.data["raw"].bucket
                 "--processed_bucket" = aws_s3_bucket.data["processed"].bucket
                 "--glue_db" = aws_glue_catalog_database.datalake.name }
    }
    "transform-aggregates" = {
      script = "transform_aggregates.py"
      args   = { "--processed_bucket" = aws_s3_bucket.data["processed"].bucket
                 "--glue_db" = aws_glue_catalog_database.datalake.name }
    }
  }
}

resource "aws_glue_job" "etl" {
  for_each = local.etl_jobs
  name     = "${var.project}-${each.key}"
  role_arn = aws_iam_role.glue_service.arn
  glue_version      = "4.0"
  worker_type       = "G.1X"
  number_of_workers = 2
  timeout           = 15

  command {
    name            = "glueetl"
    script_location = "s3://${aws_s3_bucket.data["glue_scripts"].bucket}/${each.value.script}"
    python_version  = "3"
  }

  default_arguments = merge({
    "--enable-continuous-cloudwatch-log" = "true"
    "--enable-metrics"                   = "true"
    "--job-language"                     = "python"
    "--TempDir"                          = "s3://${aws_s3_bucket.data["staging"].bucket}/glue-temp/"
  }, each.value.args)
}

# ---------- Athena workgroup ----------
resource "aws_athena_workgroup" "main" {
  name = "${var.project}-wg"

  configuration {
    enforce_workgroup_configuration    = true
    publish_cloudwatch_metrics_enabled = true
    bytes_scanned_cutoff_per_query     = 1073741824  # 1 GB cap por query (cost guard)

    result_configuration {
      output_location = "s3://${aws_s3_bucket.data["athena_results"].bucket}/"
      encryption_configuration { encryption_option = "SSE_S3" }
    }
  }
  force_destroy = true
}
