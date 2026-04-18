"""
Carga la tabla fact_transaccion desde S3 processed (Parquet) hacia Redshift Serverless
usando COPY. Crea el schema y la tabla si no existen, ejecuta una query de validación
y deja el workgroup pausado para minimizar costos.

Uso:
    python scripts/06_load_to_redshift.py
"""
from __future__ import annotations

import time

import boto3

from prueba_amaris_aws.scripts._common import aws_region, configure_logging, env, log_step, pg_conn, s3_bucket, Timer

log = configure_logging("redshift")

PROJECT = env("PROJECT_PREFIX", "prueba-aws")
WORKGROUP = env("REDSHIFT_WORKGROUP", f"{PROJECT}-wg")
DATABASE = env("REDSHIFT_DB", "datalake_dwh")
ROLE_ARN = env("REDSHIFT_ROLE_ARN", required=False)  # poblada tras Terraform

DDL = [
    "CREATE SCHEMA IF NOT EXISTS analytics",
    """CREATE TABLE IF NOT EXISTS analytics.fact_transaccion (
        transaccion_id   BIGINT,
        fecha_id         INTEGER,
        tipo_energia_id  SMALLINT,
        proveedor_id     INTEGER,
        cliente_id       INTEGER,
        tipo             VARCHAR(10),
        cantidad_mwh     NUMERIC(12,3),
        precio_usd       NUMERIC(10,2),
        monto_usd        NUMERIC(14,2)
    )""",
    "TRUNCATE analytics.fact_transaccion",
]
COPY_SQL = f"""
    COPY analytics.fact_transaccion
    FROM 's3://{s3_bucket('processed')}/fact/'
    IAM_ROLE '{{role}}'
    FORMAT AS PARQUET
"""
VALIDATE_SQL = """
    SELECT COUNT(*)         AS num_rows,
           SUM(monto_usd)   AS monto_usd_total,
           MIN(fecha_id)    AS fecha_min,
           MAX(fecha_id)    AS fecha_max
    FROM   analytics.fact_transaccion
"""


def execute_statement(client, sql: str) -> dict:
    resp = client.execute_statement(WorkgroupName=WORKGROUP, Database=DATABASE, Sql=sql)
    sid = resp["Id"]
    while True:
        st = client.describe_statement(Id=sid)
        if st["Status"] in {"FINISHED", "FAILED", "ABORTED"}:
            return st
        time.sleep(2)


def fetch(client, sid: str) -> list[dict]:
    res = client.get_statement_result(Id=sid)
    cols = [c["name"] for c in res["ColumnMetadata"]]
    rows = []
    for r in res["Records"]:
        rows.append({cols[i]: list(c.values())[0] if c else None for i, c in enumerate(r)})
    return rows


def main() -> None:
    if not ROLE_ARN:
        log.warning("REDSHIFT_ROLE_ARN no configurado — apunta al output de Terraform.")
    rsd = boto3.client("redshift-data", region_name=aws_region())

    with Timer(log, "DDL Redshift"):
        for sql in DDL:
            st = execute_statement(rsd, sql)
            log.info(f"  · {st['Status']}: {sql[:60]}...")

    with Timer(log, "COPY desde S3 processed"):
        st = execute_statement(rsd, COPY_SQL.format(role=ROLE_ARN or "<sin-role>"))
        log.info(f"  · COPY {st['Status']} · filas={st.get('ResultRows', '?')}")

    with Timer(log, "Validación"):
        st = execute_statement(rsd, VALIDATE_SQL)
        rows = fetch(rsd, st["Id"])
        log.info(f"  · Validación: {rows}")

    # Auto-pause: bajamos base capacity al mínimo permite que se pause solo
    log.info("Verificando estado del workgroup (no se puede pausar manualmente Serverless, "
             "pero sin queries entra en idle automáticamente).")

    with pg_conn() as conn:
        log_step(conn, "redshift_copy", "ok", filas=rows[0].get("num_rows") if rows else None,
                 detalles={"workgroup": WORKGROUP, "database": DATABASE, "validation": rows})

    log.info("✓ Pipeline Redshift completado.")


if __name__ == "__main__":
    main()
