variable "project" {
  type        = string
  default     = "prueba-aws"
  description = "Prefix used in all resource names."
}

variable "env" {
  type    = string
  default = "demo"
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "owner_email" {
  type    = string
  default = "dapovedag@gmail.com"
}

variable "rds_db_name" {
  type    = string
  default = "datalake"
}

variable "rds_master_user" {
  type    = string
  default = "dba"
}

variable "rds_master_password" {
  type      = string
  sensitive = true
  description = "Master password for RDS PostgreSQL. Pass via TF_VAR_rds_master_password."
}

variable "app_password" {
  type        = string
  sensitive   = true
  description = "Password for app_ro/app_rw roles inside Postgres."
}

variable "edit_token" {
  type        = string
  sensitive   = true
  description = "Token used by the backend to validate PUT /api/exercises requests."
}

variable "github_token" {
  type        = string
  sensitive   = true
  description = "GitHub PAT used by backend to commit ej.2/ej.3 markdown."
}

variable "github_repo" {
  type    = string
  default = "dapovedag/aws_test"
}

variable "admin_ip" {
  type        = string
  default     = "0.0.0.0/0"
  description = "Your public IP /32 for SSH and direct RDS access. Default opens to world (NOT recommended for prod)."
}

variable "ec2_key_name" {
  type        = string
  description = "Name of the existing EC2 key pair (created by Terraform if empty)."
  default     = ""
}

variable "redshift_admin_password" {
  type        = string
  sensitive   = true
  description = "Admin password for Redshift Serverless namespace."
}

variable "enable_redshift" {
  type        = bool
  default     = false
  description = "Crea Redshift Serverless namespace+workgroup. Cuentas AWS nuevas requieren activar Redshift en la consola primero (subscription)."
}
