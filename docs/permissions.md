# Permisos AWS · IAM y Lake Formation

> Resumen de los roles, políticas y configuraciones de seguridad necesarias para que el pipeline opere con principio de menor privilegio.

## Roles IAM

### `prueba-aws-glue-service-role`

Asume: `glue.amazonaws.com`. Usado por crawlers y ETL jobs.

- Managed policy: `service-role/AWSGlueServiceRole`
- Inline:
  - `s3:GetObject`, `PutObject`, `DeleteObject`, `ListBucket` sobre los 6 buckets `prueba-aws-*`
  - `logs:*` sobre `*` (CloudWatch logs de los jobs)

### `prueba-aws-ec2-backend-role`

Asume: `ec2.amazonaws.com`. Pegado a la instancia EC2 vía instance profile.

- `athena:StartQueryExecution`, `GetQueryExecution`, `GetQueryResults`, `StopQueryExecution` (workgroup configurable)
- `glue:GetDatabase*`, `GetTable*`, `GetPartition*`, `GetCrawler*`, `StartCrawler`, `GetJob*`, `StartJobRun`
- `s3:GetObject`, `PutObject`, `ListBucket` sobre buckets `prueba-aws-*`
- `redshift-data:*`, `redshift-serverless:GetCredentials`, `GetWorkgroup`
- `secretsmanager:GetSecretValue` sobre el secreto `prueba-aws-app-config`

### `prueba-aws-redshift-role`

Asume: `redshift.amazonaws.com` y `redshift-serverless.amazonaws.com`. Asociado al namespace.

- `s3:GetObject`, `ListBucket` sobre `prueba-aws-processed`
- Managed: `AmazonRedshiftAllCommandsFullAccess` (para COPY/UNLOAD)

### `prueba-aws-lakeformation-service`

Asume: `lakeformation.amazonaws.com`. Usado por LF para acceder a S3 governed locations.

- `s3:*` sobre `prueba-aws-processed`

## Lake Formation

- **Data Lake Admins**: el ARN del usuario IAM que aplica Terraform queda como admin.
- **Resource registrado**: `s3://prueba-aws-processed/` con `aws_lakeformation_resource`.
- **LF-Tags**:
  - `Sensitivity` → `{Public, Internal, PII}`
  - `Domain` → `{Provider, Client, Transaction, Reference}`
- **Permisos por tag**: el rol del backend EC2 obtiene `SELECT` y `DESCRIBE` solo sobre tablas con `Sensitivity ∈ {Public, Internal}` — no puede leer `PII`.

## Secrets Manager

`prueba-aws-app-config` contiene las credenciales que el backend lee al arrancar:

```json
{
  "rds_host": "...",
  "rds_port": 5432,
  "rds_db": "datalake",
  "rds_user": "app_ro",
  "rds_password": "...",
  "edit_token": "...",
  "github_token": "...",
  "github_repo": "dapovedag/aws_test",
  "public_bucket": "prueba-aws-public",
  "athena_db": "datalake_energia",
  "athena_workgroup": "prueba-aws-wg"
}
```

## Security Groups

- **`prueba-aws-rds-sg`** — ingress 5432 desde `prueba-aws-ec2-sg` (sg-to-sg) + admin IP /32 (opcional para `psql` directo).
- **`prueba-aws-ec2-sg`** — ingress 22 desde admin IP, 80/443 desde `0.0.0.0/0` (Caddy + Let's Encrypt).

## Cómo configurar paso a paso

1. `aws configure` con un usuario con `AdministratorAccess` (solo para apply de Terraform).
2. `terraform apply` crea todos los roles y políticas arriba.
3. `terraform output secret_arn` y `terraform output backend_url` para enchufar al backend.
4. El backend lee Secrets Manager al arrancar (vía instance profile) — sin credenciales en .env de producción.

## Auditoría

- CloudTrail captura todas las llamadas Athena/Glue/S3.
- `audit.carga_log` (en RDS) registra cada paso del pipeline (`faker_load`, `dq_test`, `s3_extract`, `glue_crawler`, `glue_etl`, `athena_query`, `redshift_copy`).
