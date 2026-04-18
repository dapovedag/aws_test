# Datalake Energía · Prueba AWS

> Datalake para una comercializadora de energía en Colombia. Stack: **RDS PostgreSQL → S3 → Glue → Athena → Redshift**, con suite de calidad de datos, backend Spring Boot + Jasper (PDF descargable) y frontend Vite + TypeScript desplegado en Vercel.

- **Live demo (Vercel):** https://aws-test-alpha.vercel.app
- **Backend API (EC2 + Caddy TLS):** https://prueba-aws-100-49-66-199.nip.io/api/health
- **PDF Jasper:** https://prueba-aws-public.s3.amazonaws.com/report.pdf
- **Source zip:** https://github.com/dapovedag/aws_test/archive/refs/heads/main.zip
- **Reporte calidad de datos (HTML):** https://prueba-aws-public.s3.amazonaws.com/data_quality_report.html
- **Athena results JSON:** https://prueba-aws-public.s3.amazonaws.com/athena_results.json

## Estructura

```
PruebaAWS/
├── database/      DDL PostgreSQL (schemas core · dwh · audit + vistas + calendario)
├── scripts/       Pipeline Python end-to-end (Faker · calidad · S3 · Glue · Athena · Redshift)
├── infra/         Terraform (RDS · S3 · Glue · LakeFormation · Redshift · EC2 · IAM)
├── backend/       Spring Boot 3.3 + Java 21 + Jasper (genera PDF, expone REST)
├── frontend/      Vite + TypeScript (Noir Press), descargas, editor Ej.2/Ej.3
├── docs/          Pipeline · modelo · calidad · permisos · despliegue · costos · ejercicios
└── README.md
```

## Documentación

- [Pipeline](docs/pipeline.md) — arquitectura E2E con Mermaid
- [Modelo de datos](docs/data-model.md) — estrella + OLTP, diccionario
- [Generación del set](docs/data-generation.md) — Faker, distribuciones, volúmenes
- [Calidad de datos](docs/data-quality.md) — 15 tests explicados
- [Permisos](docs/permissions.md) — IAM + Lake Formation
- [Despliegue](docs/deployment.md) — paso a paso
- [Costos](docs/cost.md) — free tier + estable
- Ejercicios (editables desde el front):
  - [Ej.2 · Arquitectura divisas](docs/exercises/ej2-architecture.md)
  - [Ej.3 · Preguntas AWS](docs/exercises/ej3-answers.md)

## Stack técnico

| Capa | Tech |
|---|---|
| OLTP fuente | RDS PostgreSQL 16 `db.t4g.micro` |
| Datalake | S3 (6 buckets: raw, staging, processed, public, athena-results, glue-scripts) |
| ETL | Glue 4.0 PySpark (3 jobs) + 2 crawlers |
| Catálogo | Glue Data Catalog (`datalake_energia`) |
| Gobernanza | Lake Formation (LF-Tags, permisos por tag) |
| Analítica | Athena workgroup + Redshift Serverless |
| Backend | Spring Boot 3.3, Java 21, JDBC Hikari, JasperReports 6.21, AWS SDK v2 |
| Frontend | Vite + TS, Noir Press (Bodoni Moda + IBM Plex) |
| IaC | Terraform 1.5+ |
| CI/CD | Vercel auto-deploy + GitHub Actions |
| Costo controlado | Free tier-first · cuotas Athena (1 GB/query) · Redshift auto-pause |

## Quick start (dev local)

```bash
# 1. Backend local contra RDS
cd backend
RDS_HOST=localhost RDS_USER=app_ro RDS_PASSWORD=secret RDS_DB=datalake \
    mvn spring-boot:run

# 2. Frontend local
cd frontend
echo "VITE_API_BASE_URL=http://localhost:8080" > .env.local
npm install && npm run dev
```

## Despliegue real

Ver [docs/deployment.md](docs/deployment.md). Resumen:
1. `terraform apply` crea RDS, S3, Glue, EC2, etc.
2. `python scripts/00_generate_and_load.py` puebla la base.
3. `python scripts/01_data_quality_tests.py` genera el reporte HTML+JSON.
4. `python scripts/02...06_*.py` corre el pipeline completo.
5. `mvn package` + scp a EC2, Vercel auto-deploy del front.
