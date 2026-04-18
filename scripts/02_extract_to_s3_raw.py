"""
Extrae cada tabla del schema core (RDS) a CSV y la sube a S3 raw,
particionada por fecha de carga: s3://prueba-aws-raw/<tabla>/loaded_at=YYYY-MM-DDTHH/<tabla>.csv

Uso:
    python scripts/02_extract_to_s3_raw.py
"""
from __future__ import annotations

import csv
import datetime as dt
import io

import boto3

from prueba_amaris_aws.scripts._common import aws_region, configure_logging, log_step, pg_conn, s3_bucket, Timer

log = configure_logging("s3-extract")

TABLAS = [
    ("core.tipo_energia", "tipo_energia"),
    ("core.ciudad",        "ciudad"),
    ("core.proveedor",     "proveedor"),
    ("core.cliente",       "cliente"),
    ("core.transaccion",   "transaccion"),
]


def extract_to_csv(cur, schema_table: str) -> tuple[str, int]:
    """Devuelve (csv_text, num_rows)."""
    cur.execute(f"SELECT * FROM {schema_table}")
    rows = cur.fetchall()
    cols = [d[0] for d in cur.description]
    buf = io.StringIO()
    w = csv.writer(buf, quoting=csv.QUOTE_MINIMAL)
    w.writerow(cols)
    for r in rows:
        w.writerow([("" if v is None else str(v)) for v in r])
    return buf.getvalue(), len(rows)


def main() -> None:
    s3 = boto3.client("s3", region_name=aws_region())
    bucket = s3_bucket("raw")
    loaded_at = dt.datetime.utcnow().strftime("%Y-%m-%dT%H")
    log.info(f"Bucket destino: s3://{bucket}/  · partición loaded_at={loaded_at}")

    total_rows = 0
    detalles: dict[str, int] = {}

    with pg_conn() as conn, conn.cursor() as cur:
        for schema_table, tabla in TABLAS:
            with Timer(log, f"Extrayendo {schema_table}"):
                csv_text, n = extract_to_csv(cur, schema_table)
                key = f"{tabla}/loaded_at={loaded_at}/{tabla}.csv"
                s3.put_object(
                    Bucket=bucket, Key=key,
                    Body=csv_text.encode("utf-8"),
                    ContentType="text/csv; charset=utf-8",
                )
                log.info(f"  → s3://{bucket}/{key}  ({n} filas)")
                total_rows += n
                detalles[tabla] = n
        log_step(conn, "s3_extract", "ok", filas=total_rows,
                 detalles={"loaded_at": loaded_at, "tablas": detalles, "bucket": bucket})

    log.info(f"✓ Extracción completa — {total_rows} filas en {len(TABLAS)} tablas.")


if __name__ == "__main__":
    main()
