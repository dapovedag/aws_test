"""
Utilidades compartidas para los scripts del pipeline.
Lee credenciales de .env y expone helpers de conexión, logging y rutas.
"""
from __future__ import annotations
import json
import logging
import os
import sys
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
OUTPUT = SCRIPTS / "output"
DATA_QUALITY = SCRIPTS / "data-quality"

OUTPUT.mkdir(parents=True, exist_ok=True)
DATA_QUALITY.mkdir(parents=True, exist_ok=True)

load_dotenv(ROOT / ".env")


def configure_logging(name: str) -> logging.Logger:
    log = logging.getLogger(name)
    if not log.handlers:
        h = logging.StreamHandler(sys.stdout)
        h.setFormatter(logging.Formatter("%(asctime)s · %(levelname)-7s · %(name)s · %(message)s"))
        log.addHandler(h)
    log.setLevel(os.getenv("LOG_LEVEL", "INFO"))
    return log


def env(name: str, default: str | None = None, required: bool = False) -> str:
    val = os.getenv(name, default)
    if required and not val:
        raise RuntimeError(f"Missing env var: {name}")
    return val or ""


def aws_region() -> str:
    return env("AWS_REGION", "us-east-1")


def s3_bucket(layer: str) -> str:
    """Returns the bucket name for a given layer (raw|staging|processed|public|athena|scripts)."""
    prefix = env("PROJECT_PREFIX", "prueba-aws")
    return f"{prefix}-{layer}"


def project_paths() -> dict[str, str]:
    return {
        "raw":      f"s3://{s3_bucket('raw')}/",
        "staging":  f"s3://{s3_bucket('staging')}/",
        "processed":f"s3://{s3_bucket('processed')}/",
        "public":   f"s3://{s3_bucket('public')}/",
        "athena":   f"s3://{s3_bucket('athena-results')}/",
        "scripts":  f"s3://{s3_bucket('glue-scripts')}/",
    }


@contextmanager
def pg_conn() -> Iterator[Any]:
    """Conexión PostgreSQL a RDS. Lee RDS_* del entorno."""
    import psycopg2
    conn = psycopg2.connect(
        host=env("RDS_HOST", required=True),
        port=int(env("RDS_PORT", "5432")),
        dbname=env("RDS_DB", "datalake"),
        user=env("RDS_USER", "app_rw"),
        password=env("RDS_PASSWORD", required=True),
        connect_timeout=15,
        sslmode=env("RDS_SSLMODE", "require"),
    )
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def log_step(conn: Any, proceso: str, estado: str, filas: int | None = None,
             mensaje: str | None = None, detalles: dict | None = None) -> None:
    """Registra un paso en audit.carga_log."""
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO audit.carga_log (proceso, fin, estado, filas, mensaje, detalles)
            VALUES (%s, NOW(), %s, %s, %s, %s::jsonb)
            """,
            (proceso, estado, filas, mensaje, json.dumps(detalles or {}, default=str)),
        )


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, default=str, ensure_ascii=False), encoding="utf-8")


class Timer:
    """Context manager para medir duración de un paso."""
    def __init__(self, log: logging.Logger, label: str):
        self.log = log
        self.label = label
    def __enter__(self):
        self.t0 = time.perf_counter()
        self.log.info(f"▶ {self.label}")
        return self
    def __exit__(self, exc_type, exc, tb):
        dur = time.perf_counter() - self.t0
        if exc:
            self.log.error(f"✗ {self.label} — falló en {dur:.2f}s: {exc}")
        else:
            self.log.info(f"✓ {self.label} — {dur:.2f}s")
