output "project" { value = var.project }
output "region"  { value = var.aws_region }
output "account" { value = data.aws_caller_identity.current.account_id }

output "buckets" {
  value = { for k, b in aws_s3_bucket.data : k => b.bucket }
}

output "glue_database" { value = aws_glue_catalog_database.datalake.name }
output "athena_workgroup" { value = aws_athena_workgroup.main.name }

output "secret_arn" { value = aws_secretsmanager_secret.app.arn }
output "secret_name" { value = aws_secretsmanager_secret.app.name }

output "public_pdf_url" {
  value = "https://${aws_s3_bucket.data["public"].bucket}.s3.amazonaws.com/report.pdf"
}
output "public_athena_results_url" {
  value = "https://${aws_s3_bucket.data["public"].bucket}.s3.amazonaws.com/athena_results.json"
}
output "public_data_quality_url" {
  value = "https://${aws_s3_bucket.data["public"].bucket}.s3.amazonaws.com/data_quality_report.html"
}

output "github_repo" { value = var.github_repo }
output "source_zip_url" {
  value = "https://github.com/${var.github_repo}/archive/refs/heads/main.zip"
}
