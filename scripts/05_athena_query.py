"""
Ejecuta las 3 queries analíticas requeridas por el PDF desde Python sobre Athena.
Exporta resultados a output/athena_results.json y los sube a S3 público para
que el frontend los muestre en vivo.

Las 3 queries:
    Q1 — MWh y monto USD por mes × tipo_energia (ventas)
    Q2 — Top 5 clientes por volumen comprado (MWh)
    Q3 — Margen promedio (precio venta vs precio compra) por tipo_energia

Uso:
    python scripts/05_athena_query.py
"""
from __future__ import annotations

import json
import time

import boto3

from prueba_amaris_aws.scripts._common import OUTPUT, aws_region, configure_logging, env, log_step, pg_conn, s3_bucket, write_json, Timer

log = configure_logging("athena")

PROJECT = env("PROJECT_PREFIX", "prueba-aws")
GLUE_DB = env("GLUE_DB", "datalake_energia")
WORKGROUP = env("ATHENA_WORKGROUP", f"{PROJECT}-wg")

QUERIES = {
    "Q1_resumen_mensual_ventas": f"""
        SELECT f.anio, f.mes, te.codigo AS tipo_energia,
               SUM(f.cantidad_mwh) AS mwh_total,
               SUM(f.monto_usd)    AS monto_usd_total
        FROM   "{GLUE_DB}"."fact" f
        JOIN   "{GLUE_DB}"."dim_tipo_energia" te ON te.tipo_energia_id = f.tipo_energia_id
        WHERE  f.tipo = 'venta'
        GROUP  BY f.anio, f.mes, te.codigo
        ORDER  BY f.anio, f.mes, te.codigo
        LIMIT 200
    """,
    "Q2_top_clientes": f"""
        SELECT c.nombre, c.segmento, ci.nombre AS ciudad,
               COUNT(*)             AS num_compras,
               SUM(f.cantidad_mwh)  AS mwh_total,
               SUM(f.monto_usd)     AS monto_usd_total
        FROM   "{GLUE_DB}"."fact" f
        JOIN   "{GLUE_DB}"."dim_cliente"      c  ON c.cliente_id = f.cliente_id
        JOIN   "{GLUE_DB}"."dim_ciudad"       ci ON ci.ciudad_id = c.ciudad_id
        WHERE  f.tipo = 'venta'
        GROUP  BY c.nombre, c.segmento, ci.nombre
        ORDER  BY mwh_total DESC
        LIMIT 5
    """,
    "Q3_margen_energia": f"""
        WITH agg AS (
          SELECT te.codigo AS tipo_energia, f.tipo, AVG(f.precio_usd) AS pp
          FROM   "{GLUE_DB}"."fact" f
          JOIN   "{GLUE_DB}"."dim_tipo_energia" te ON te.tipo_energia_id = f.tipo_energia_id
          GROUP  BY te.codigo, f.tipo
        )
        SELECT tipo_energia,
               MAX(CASE WHEN tipo='compra' THEN pp END) AS precio_compra_avg,
               MAX(CASE WHEN tipo='venta'  THEN pp END) AS precio_venta_avg,
               MAX(CASE WHEN tipo='venta'  THEN pp END)
                 - MAX(CASE WHEN tipo='compra' THEN pp END) AS margen_absoluto
        FROM   agg
        GROUP  BY tipo_energia
        ORDER  BY margen_absoluto DESC NULLS LAST
    """,
}


def run_query(athena, sql: str) -> dict:
    output = f"s3://{s3_bucket('athena-results')}/"
    resp = athena.start_query_execution(
        QueryString=sql,
        QueryExecutionContext={"Database": GLUE_DB},
        WorkGroup=WORKGROUP,
        ResultConfiguration={"OutputLocation": output},
    )
    qid = resp["QueryExecutionId"]
    while True:
        st = athena.get_query_execution(QueryExecutionId=qid)["QueryExecution"]
        state = st["Status"]["State"]
        if state in {"SUCCEEDED", "FAILED", "CANCELLED"}:
            break
        time.sleep(2)
    if state != "SUCCEEDED":
        return {"state": state, "error": st["Status"].get("StateChangeReason", ""), "rows": []}

    paginator = athena.get_paginator("get_query_results")
    rows: list[dict] = []
    cols: list[str] = []
    for i, page in enumerate(paginator.paginate(QueryExecutionId=qid)):
        result_rows = page["ResultSet"]["Rows"]
        if i == 0:
            cols = [c["VarCharValue"] for c in result_rows[0]["Data"]]
            data_rows = result_rows[1:]
        else:
            data_rows = result_rows
        for r in data_rows:
            rows.append({cols[j]: (cell.get("VarCharValue") if cell else None)
                         for j, cell in enumerate(r["Data"])})
    return {"state": "SUCCEEDED", "rows": rows, "columns": cols, "execution_id": qid,
            "data_scanned_bytes": st.get("Statistics", {}).get("DataScannedInBytes", 0)}


def main() -> None:
    athena = boto3.client("athena", region_name=aws_region())
    s3 = boto3.client("s3", region_name=aws_region())
    results = {}
    for name, sql in QUERIES.items():
        with Timer(log, name):
            results[name] = run_query(athena, sql)
            log.info(f"  → {results[name].get('state')} · {len(results[name].get('rows', []))} filas")

    out = {
        "generated_at": __import__("datetime").datetime.utcnow().isoformat() + "Z",
        "workgroup": WORKGROUP,
        "database": GLUE_DB,
        "queries": results,
    }
    json_path = OUTPUT / "athena_results.json"
    write_json(json_path, out)
    log.info(f"✓ Resultados → {json_path}")

    # Sube al bucket público para que el front los pueda leer directamente
    public_bucket = s3_bucket("public")
    s3.put_object(
        Bucket=public_bucket, Key="athena_results.json",
        Body=json.dumps(out, ensure_ascii=False, default=str).encode("utf-8"),
        ContentType="application/json",
        CacheControl="public, max-age=60",
    )
    log.info(f"✓ Subido a s3://{public_bucket}/athena_results.json")

    with pg_conn() as conn:
        log_step(conn, "athena_query", "ok", filas=sum(len(r.get('rows', [])) for r in results.values()),
                 detalles={k: {"state": v.get("state"), "rows": len(v.get("rows", [])),
                               "scanned_bytes": v.get("data_scanned_bytes", 0)} for k, v in results.items()})


if __name__ == "__main__":
    main()
