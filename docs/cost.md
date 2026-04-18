# Costo del despliegue

> Cuenta AWS nueva, free tier vigente. Todos los precios son estimados oficiales `us-east-1` a 2026-04.
> El endpoint `GET /api/cost` devuelve esta tabla parseada para el frontend.

| Recurso | Modo | Costo mes 1 (free tier) | Costo estable post free-tier |
|---|---|---|---|
| EC2 t3.micro 24/7 | Free Tier 12m | $0 | ~$8.50/mes |
| Elastic IP (asociada) | — | $0 | $0 |
| EBS 8 GB gp3 | Free Tier 30 GB | $0 | $0.64/mes |
| RDS PostgreSQL `db.t4g.micro` 24/7 | Free Tier 12m | $0 | ~$13/mes |
| RDS storage 20 GB gp3 | Free Tier 20 GB | $0 | $2.30/mes |
| RDS backups 1 día retention | Free Tier (≤ DB size) | $0 | <$0.10/mes |
| S3 (~50-100 MB en 6 buckets) | Free Tier 5 GB | $0 | <$0.01/mes |
| S3 requests (PUTs+GETs) | Free Tier | $0 | <$0.01/mes |
| Glue Crawler (1-2 runs/día) | — | ~$0.07-0.14/día | igual |
| Glue ETL (3 jobs · 2 DPU · 5 min cada uno) | — | ~$0.22 / corrida | igual |
| Athena (queries pequeñas <10 MB) | — | <$0.01/mes | igual |
| Lake Formation | — | $0 (sin cargo propio) | $0 |
| Redshift Serverless (idle) | — | $0 | $0 |
| Redshift Serverless (1 query demo · ~1 min · 8 RPU) | — | ~$0.05 una vez | igual |
| Data transfer out | Free Tier 100 GB | $0 | <$1/mes |
| CloudWatch logs | Free Tier | $0 | <$0.50/mes |
| Vercel Hobby (frontend) | Free | $0 | $0 |
| GitHub repo público | Free | $0 | $0 |
| Dominio nip.io | Free | $0 | $0 |
| **Total estimado primer mes** | | **~$0.50-2.00** | — |
| **Total estado estable (post free-tier)** | | — | **~$25-30/mes** |

## Cómo lo controlamos

- **Athena**: workgroup tiene `bytes_scanned_cutoff_per_query = 1 GB` — cualquier query mayor falla.
- **Redshift**: Serverless con base capacity 8 RPU mínima; se pausa solo en idle.
- **Glue**: jobs corren on-demand, no programados. Workers `G.1X` con 2 workers (mínimo).
- **S3**: lifecycle en `prueba-aws-athena-results` borra resultados a los 7 días.
- **EC2**: `t3.micro` (free tier 12m). Después se puede pasar a `t4g.micro` ARM (más barato).
- **RDS**: 1 día retention de backups. No multi-AZ. No enhanced monitoring.

## Mitigaciones recomendadas tras free tier

1. Sacar EC2 a `t4g.micro` ARM (~$6.50/mes) si no necesitamos Java x86.
2. Considerar Lambda + API Gateway para reemplazar EC2 si el tráfico es bajo.
3. Apagar RDS en horarios sin uso (`stop-db-instance` cron) — ahorra ~50%.
4. Usar S3 Intelligent-Tiering si los datos crecen.
