"""
Dispara los 3 jobs de Glue ETL en orden y espera completion.
Jobs (definidos en Terraform y cuyo código vive en infra/terraform/glue_jobs/):
    1. transform_dimensions  — dims SCD-1 a Parquet
    2. transform_fact        — hechos con join + cálculos + particionado
    3. transform_aggregates  — agregados mensuales pre-calculados

Uso:
    python scripts/04_run_glue_etl.py
"""
from __future__ import annotations

import time

import boto3

from prueba_amaris_aws.scripts._common import aws_region, configure_logging, env, log_step, pg_conn, Timer

log = configure_logging("glue-etl")

PROJECT = env("PROJECT_PREFIX", "prueba-aws")
JOBS = [
    f"{PROJECT}-transform-dimensions",
    f"{PROJECT}-transform-fact",
    f"{PROJECT}-transform-aggregates",
]


def run_job(glue, name: str) -> dict:
    log.info(f"Iniciando job: {name}")
    resp = glue.start_job_run(JobName=name)
    run_id = resp["JobRunId"]
    while True:
        run = glue.get_job_run(JobName=name, RunId=run_id)["JobRun"]
        state = run["JobRunState"]
        if state in {"SUCCEEDED", "FAILED", "STOPPED", "TIMEOUT"}:
            dur = run.get("ExecutionTime", 0)
            dpu = run.get("DPUSeconds", 0) / 3600
            log.info(f"  ✓ {name} → {state} · {dur}s · ~{dpu:.3f} DPU-hours")
            return {"state": state, "duration_sec": dur, "dpu_hours": dpu, "run_id": run_id}
        log.info(f"  {state}... esperando 15s")
        time.sleep(15)


def main() -> None:
    glue = boto3.client("glue", region_name=aws_region())
    results = {}
    for job in JOBS:
        with Timer(log, job):
            results[job] = run_job(glue, job)
            if results[job]["state"] != "SUCCEEDED":
                log.error(f"Job {job} no terminó OK — abortando pipeline.")
                break

    with pg_conn() as conn:
        ok = all(r["state"] == "SUCCEEDED" for r in results.values())
        log_step(conn, "glue_etl", "ok" if ok else "error", detalles=results)


if __name__ == "__main__":
    main()
