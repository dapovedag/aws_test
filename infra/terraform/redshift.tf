# =====================================================================
#  Amazon Redshift Serverless — datawarehouse bonus del Ej.1
#  IMPORTANTE: cuentas AWS nuevas requieren "subscription" implícito a
#  Redshift (visitar la consola una vez activa el servicio). Por defecto
#  está desactivado. Para activar:
#    1. https://console.aws.amazon.com/redshiftv2/  → Get started
#    2. Cambia `enable_redshift = true` en terraform.tfvars
#    3. terraform apply
# =====================================================================

resource "aws_redshiftserverless_namespace" "main" {
  count               = var.enable_redshift ? 1 : 0
  namespace_name      = "${var.project}-ns"
  admin_username      = "rsadmin"
  admin_user_password = var.redshift_admin_password
  db_name             = "datalake_dwh"
  iam_roles           = [aws_iam_role.redshift.arn]
  default_iam_role_arn = aws_iam_role.redshift.arn
}

resource "aws_redshiftserverless_workgroup" "main" {
  count          = var.enable_redshift ? 1 : 0
  workgroup_name = "${var.project}-wg"
  namespace_name = aws_redshiftserverless_namespace.main[0].namespace_name

  base_capacity        = 8
  max_capacity         = 8
  publicly_accessible  = false
  enhanced_vpc_routing = false

  config_parameter {
    parameter_key   = "auto_mv"
    parameter_value = "true"
  }
  config_parameter {
    parameter_key   = "datestyle"
    parameter_value = "ISO, MDY"
  }
}

output "redshift_workgroup_endpoint" {
  value = var.enable_redshift ? aws_redshiftserverless_workgroup.main[0].endpoint : null
}
output "redshift_enabled" {
  value = var.enable_redshift
}
