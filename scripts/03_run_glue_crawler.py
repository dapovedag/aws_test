"""
Dispara el crawler de Glue sobre la capa 'raw' (y opcionalmente sobre 'processed')
y espera a que termine. Reporta tablas catalogadas.

Uso:
    python scripts/03_run_glue_crawler.py [raw|processed|all]
"""
from __future__ import annotations

import sys
import time

import boto3
from botocore.exceptions import ClientError

from prueba_amaris_aws.scripts._common import aws_region, configure_logging, env, log_step, pg_conn, Timer

log = configure_logging("glue-crawler")

PROJECT = env("PROJECT_PREFIX", "prueba-aws")
GLUE_DB = env("GLUE_DB", "datalake_energia")


def trigger_and_wait(glue, name: str) -> dict:
    log.info(f"Disparando crawler '{name}'...")
    try:
        glue.start_crawler(Name=name)
    except ClientError as e:
        if "CrawlerRunningException" in str(e):
            log.warning(f"  Crawler ya estaba corriendo — esperando a que termine.")
        else:
            raise
    while True:
        info = glue.get_crawler(Name=name)["Crawler"]
        state = info["State"]
        if state == "READY":
            last = info.get("LastCrawl", {})
            log.info(f"  ✓ {name} terminó · status={last.get('Status')} · "
                     f"tablas creadas/actualizadas={last.get('TablesCreated', '?')}/{last.get('TablesUpdated', '?')} · "
                     f"runtime={last.get('DurationInSeconds', '?')}s")
            return last
        log.info(f"  estado: {state}, esperando 10s...")
        time.sleep(10)


def list_tables(glue, db: str) -> list[dict]:
    paginator = glue.get_paginator("get_tables")
    tables = []
    for page in paginator.paginate(DatabaseName=db):
        tables.extend(page.get("TableList", []))
    return tables


def main() -> None:
    target = sys.argv[1] if len(sys.argv) > 1 else "all"
    glue = boto3.client("glue", region_name=aws_region())

    runs: dict[str, dict] = {}
    if target in ("raw", "all"):
        with Timer(log, "Crawler raw"):
            runs["raw"] = trigger_and_wait(glue, f"{PROJECT}-crawler-raw")
    if target in ("processed", "all"):
        with Timer(log, "Crawler processed"):
            runs["processed"] = trigger_and_wait(glue, f"{PROJECT}-crawler-processed")

    log.info(f"\nCatálogo Glue '{GLUE_DB}':")
    tables = list_tables(glue, GLUE_DB)
    for t in tables:
        loc = t.get("StorageDescriptor", {}).get("Location", "")
        cols = t.get("StorageDescriptor", {}).get("Columns", [])
        log.info(f"  • {t['Name']:30s} → {loc} ({len(cols)} columnas)")

    with pg_conn() as conn:
        log_step(conn, "glue_crawler", "ok", filas=len(tables),
                 detalles={"runs": runs, "tables_count": len(tables)})


if __name__ == "__main__":
    main()
