# Despliegue paso a paso

> Tiempo estimado total: **45-90 min** (Terraform tarda ~15 min en crear RDS, Glue tarda ~5 min por job, EC2 boot ~3 min).

## Pre-requisitos

- Cuenta AWS con `AdministratorAccess` (un usuario IAM dedicado tipo `terraform-deployer`).
- Java 21, Maven 3.6+, Python 3.10+, Node.js 22+, Terraform 1.5+.
- Token de GitHub con scope `repo`.
- Token de Vercel con scope sobre el proyecto.

## Variables de entorno (`.env`)

```
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
AWS_REGION=us-east-1
GITHUB_TOKEN=ghp_...
GITHUB_USER=dapovedag
GITHUB_REPO=https://github.com/dapovedag/aws_test
VERCEL_TOKEN=...
PROJECT_PREFIX=prueba-aws
```

## 1 · Inicializar repo

```bash
cd PruebaAWS
git init
git add .
git commit -m "feat: initial commit · datalake energía AWS"
git remote add origin https://${GITHUB_TOKEN}@github.com/dapovedag/aws_test.git
git push -u origin main
```

## 2 · Aplicar Terraform (fase 1: RDS + S3 + IAM)

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
# Edita terraform.tfvars con passwords seguros

terraform init
terraform apply \
    -target=aws_db_instance.postgres \
    -target=aws_s3_bucket.data \
    -target=aws_secretsmanager_secret.app
```

Outputs útiles: `rds_endpoint`, `rds_jdbc_url`.

## 3 · Cargar schema y datos en RDS

```bash
# Crear schemas y dimensiones
psql "$(terraform output -raw rds_jdbc_url | sed 's/jdbc://')" \
     -U dba -W \
     -f ../../database/00_schema.sql \
     -f ../../database/01_seed_dimensions.sql \
     -f ../../database/02_calendar.sql \
     -f ../../database/views.sql

# Generar y cargar dataset Faker
cd ../../scripts
pip install -r requirements.txt
RDS_HOST=$(cd ../infra/terraform && terraform output -raw rds_endpoint) \
RDS_USER=app_rw RDS_PASSWORD=$APP_PASSWORD RDS_DB=datalake \
python 00_generate_and_load.py
```

## 4 · Correr suite de calidad

```bash
python 01_data_quality_tests.py
# Sube los outputs a S3 público:
aws s3 cp output/data_quality_summary.json s3://prueba-aws-public/
aws s3 cp output/data_quality_report.html s3://prueba-aws-public/
```

## 5 · Aplicar Terraform fase 2 (resto)

```bash
cd ../infra/terraform
terraform apply
```

## 6 · Pipeline completo

```bash
cd ../../scripts
python 02_extract_to_s3_raw.py     # CSVs → s3 raw
python 03_run_glue_crawler.py raw  # cataloga raw
python 04_run_glue_etl.py          # 3 transformaciones → processed
python 03_run_glue_crawler.py processed  # cataloga processed
python 05_athena_query.py          # 3 queries SQL desde Python
python 06_load_to_redshift.py      # COPY a Redshift
```

## 7 · Backend Spring Boot

```bash
cd ../backend
mvn -B clean package -DskipTests
# Sube el JAR a EC2:
scp -i ../infra/terraform/.secrets/ec2-key.pem \
    target/prueba-aws-backend.jar \
    ec2-user@$(cd ../infra/terraform && terraform output -raw backend_public_ip):/opt/prueba-aws/backend.jar
ssh -i ../infra/terraform/.secrets/ec2-key.pem ec2-user@... \
    "sudo systemctl restart prueba-backend"
```

El backend al arrancar:
1. Lee Secrets Manager
2. Conecta a RDS
3. Regenera el PDF Jasper y lo sube a `s3://prueba-aws-public/report.pdf`

## 8 · Vercel para el frontend

```bash
cd ../frontend
npm install
echo "VITE_API_BASE_URL=$(cd ../infra/terraform && terraform output -raw backend_url)" > .env.production
echo "VITE_GITHUB_REPO_URL=https://github.com/dapovedag/aws_test" >> .env.production

npx vercel link --yes --project aws-test
npx vercel --prod
```

## 9 · Verificación

```bash
curl -s $(terraform output -raw backend_url)/api/health | jq
curl -sI https://prueba-aws-public.s3.amazonaws.com/report.pdf | grep -i content-type
curl -s https://prueba-aws-public.s3.amazonaws.com/data_quality_summary.json | jq '.passed'
```

## Teardown

```bash
cd infra/terraform
terraform destroy
# RDS toma ~10 min en borrarse
```
