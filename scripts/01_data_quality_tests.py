"""
Suite de calidad de datos sobre el RDS poblado.
Ejecuta 15 chequeos en 5 categorías (completitud, unicidad, integridad referencial,
dominios, rangos, cobertura temporal, sanidad lógica) y exporta:
    - output/data_quality_summary.json   (consumido por el frontend)
    - output/data_quality_report.html    (visual, subido a S3 público)

Uso:
    python scripts/01_data_quality_tests.py
"""
from __future__ import annotations

import datetime as dt
import json
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any, Callable

from prueba_amaris_aws.scripts._common import OUTPUT, configure_logging, log_step, pg_conn, Timer

log = configure_logging("dq")


@dataclass
class Check:
    id: int
    category: str
    name: str
    description: str
    expected: str
    sql: str
    severity: str  # 'critical' | 'high' | 'medium' | 'low'
    actual: Any = None
    passed: bool | None = None
    message: str = ""


CHECKS: list[Check] = [
    Check(1, "completitud", "proveedor.nombre no nulo",
          "Todo proveedor debe tener nombre", "0 nulos",
          "SELECT COUNT(*) FROM core.proveedor WHERE nombre IS NULL",
          "critical"),
    Check(2, "completitud", "cliente.id_externo no nulo",
          "Todo cliente debe tener identificación externa", "0 nulos",
          "SELECT COUNT(*) FROM core.cliente WHERE id_externo IS NULL",
          "critical"),
    Check(3, "unicidad", "proveedor.nombre único",
          "No deben existir nombres duplicados de proveedor", "0 duplicados",
          "SELECT COUNT(*) FROM (SELECT nombre FROM core.proveedor GROUP BY nombre HAVING COUNT(*) > 1) d",
          "high"),
    Check(4, "unicidad", "cliente.id_externo único",
          "No deben existir IDs externos duplicados", "0 duplicados",
          "SELECT COUNT(*) FROM (SELECT id_externo FROM core.cliente GROUP BY id_externo HAVING COUNT(*) > 1) d",
          "critical"),
    Check(5, "unicidad", "transaccion.transaccion_id único",
          "PK debe ser única (validación tautológica que prueba pipeline)", "0 duplicados",
          "SELECT COUNT(*) FROM (SELECT transaccion_id FROM core.transaccion GROUP BY transaccion_id HAVING COUNT(*) > 1) d",
          "critical"),
    Check(6, "integridad", "FK transaccion → proveedor o cliente",
          "Toda transacción tiene proveedor (compra) o cliente (venta)",
          "0 huérfanas",
          """SELECT COUNT(*) FROM core.transaccion t
             WHERE (t.tipo='compra' AND NOT EXISTS (SELECT 1 FROM core.proveedor p WHERE p.proveedor_id = t.proveedor_id))
                OR (t.tipo='venta'  AND NOT EXISTS (SELECT 1 FROM core.cliente   c WHERE c.cliente_id   = t.cliente_id))""",
          "critical"),
    Check(7, "integridad", "FK transaccion → tipo_energia",
          "Todo tipo_energia_id existe en la dimensión", "0 inválidas",
          """SELECT COUNT(*) FROM core.transaccion t
             WHERE NOT EXISTS (SELECT 1 FROM core.tipo_energia te WHERE te.tipo_energia_id = t.tipo_energia_id)""",
          "critical"),
    Check(8, "integridad", "FK cliente → ciudad",
          "Toda ciudad_id de cliente existe en la dimensión", "0 inválidas",
          """SELECT COUNT(*) FROM core.cliente c
             WHERE NOT EXISTS (SELECT 1 FROM core.ciudad ci WHERE ci.ciudad_id = c.ciudad_id)""",
          "critical"),
    Check(9, "dominio", "cliente.segmento ∈ {residencial,comercial,industrial}",
          "Sólo valores definidos por el ENUM",
          "100% válidos",
          """SELECT COUNT(*) FROM core.cliente
             WHERE segmento::text NOT IN ('residencial','comercial','industrial')""",
          "high"),
    Check(10, "dominio", "tipo_energia.codigo ∈ catálogo esperado",
           "Códigos válidos: eolica/hidroelectrica/solar/biomasa/nuclear",
           "100% válidos",
           """SELECT COUNT(*) FROM core.tipo_energia
              WHERE codigo NOT IN ('eolica','hidroelectrica','solar','biomasa','nuclear')""",
           "high"),
    Check(11, "rango", "proveedor.capacidad_mw en (0, 5000]",
           "Capacidad debe ser positiva y razonable",
           "100% en rango",
           "SELECT COUNT(*) FROM core.proveedor WHERE capacidad_mw <= 0 OR capacidad_mw > 5000",
           "medium"),
    Check(12, "rango", "transaccion.cantidad_mwh > 0",
           "Cantidad debe ser estrictamente positiva", "100% positivos",
           "SELECT COUNT(*) FROM core.transaccion WHERE cantidad_mwh <= 0",
           "high"),
    Check(13, "rango", "transaccion.precio_usd entre 10 y 500",
           "Precio en rango razonable spot energético",
           "≥ 99% en rango",
           "SELECT COUNT(*) FROM core.transaccion WHERE precio_usd < 10 OR precio_usd > 500",
           "medium"),
    Check(14, "cobertura", "transacciones cubren todos los meses sin huecos > 7 días",
           "Continuidad temporal del dataset",
           "0 huecos > 7 días",
           """WITH dias AS (
                  SELECT DISTINCT fecha FROM core.transaccion
              ), gaps AS (
                  SELECT fecha,
                         LAG(fecha) OVER (ORDER BY fecha) AS prev,
                         (fecha - LAG(fecha) OVER (ORDER BY fecha))::int AS gap_dias
                  FROM   dias
              )
              SELECT COUNT(*) FROM gaps WHERE gap_dias > 7""",
           "medium"),
    Check(15, "sanidad_logica", "compras_mwh ≥ ventas_mwh por mes",
           "No se vende más de lo comprado en ningún mes",
           "0 meses inválidos",
           """WITH mensual AS (
                  SELECT date_trunc('month', fecha) AS mes,
                         SUM(CASE WHEN tipo='compra' THEN cantidad_mwh ELSE 0 END) AS compras,
                         SUM(CASE WHEN tipo='venta'  THEN cantidad_mwh ELSE 0 END) AS ventas
                  FROM   core.transaccion GROUP BY 1
              )
              SELECT COUNT(*) FROM mensual WHERE ventas > compras""",
           "high"),
]


def evaluate(check: Check, value: int) -> tuple[bool, str]:
    """Decide si el check pasa según su umbral."""
    if check.id == 13:  # rango precio: tolera hasta 1% fuera
        # value es count fuera; necesitamos total
        with pg_conn() as conn, conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM core.transaccion")
            total = cur.fetchone()[0]
        pct = value / total if total else 0
        passed = pct <= 0.01
        return passed, f"{value} fuera de rango ({pct:.2%}); umbral 1%"
    passed = value == 0
    return passed, ("0 incidencias" if passed else f"{value} incidencias detectadas")


def run_checks() -> list[Check]:
    with pg_conn() as conn, conn.cursor() as cur:
        for c in CHECKS:
            with Timer(log, f"Check #{c.id:02d} {c.name}"):
                cur.execute(c.sql)
                value = cur.fetchone()[0] or 0
                c.actual = int(value)
                c.passed, c.message = evaluate(c, value)
    return CHECKS


def render_html(checks: list[Check], summary: dict) -> str:
    rows = "\n".join(
        f"""<tr class="{'ok' if c.passed else 'fail'}">
          <td>{c.id:02d}</td>
          <td>{c.category}</td>
          <td>{c.name}</td>
          <td>{c.expected}</td>
          <td>{c.actual}</td>
          <td><span class="sev sev-{c.severity}">{c.severity}</span></td>
          <td><span class="badge {'pass' if c.passed else 'fail'}">{'PASS' if c.passed else 'FAIL'}</span></td>
        </tr>"""
        for c in checks
    )
    return f"""<!doctype html>
<html lang="es"><head><meta charset="utf-8"><title>Data Quality · Datalake Energía</title>
<style>
  :root {{ --paper:#fff; --ink:#0a0a0a; --line:#e3e3e3; --red:#c8321f; --green:#1f7a3a; --mute:#6a6a6a; }}
  body {{ font-family: 'IBM Plex Sans', system-ui; background: var(--paper); color: var(--ink); padding: 2rem; max-width: 1100px; margin: 0 auto; }}
  h1 {{ font-family: 'Bodoni Moda', serif; font-weight: 600; font-size: 2.4rem; border-bottom: 4px double var(--ink); padding-bottom: 0.6rem; }}
  .meta {{ font-family: monospace; color: var(--mute); margin: 0.6rem 0 2rem; }}
  table {{ width: 100%; border-collapse: collapse; margin-top: 1rem; font-size: 0.92rem; }}
  th,td {{ padding: 0.7rem 0.6rem; border-bottom: 1px solid var(--line); text-align: left; vertical-align: top; }}
  th {{ font-family: 'Bodoni Moda', serif; font-weight: 600; border-bottom: 2px solid var(--ink); }}
  .badge {{ display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 0.75rem; font-weight: 600; }}
  .badge.pass {{ background: #e8f4ec; color: var(--green); }}
  .badge.fail {{ background: #fde9e5; color: var(--red); }}
  .sev {{ font-family: monospace; font-size: 0.72rem; padding: 1px 6px; border: 1px solid var(--line); border-radius: 4px; }}
  .sev-critical {{ background: #fde9e5; color: var(--red); border-color: var(--red); }}
  .sev-high {{ background: #fff3d6; color: #8a5a00; border-color: #d8a93a; }}
  .sev-medium {{ background: #f0f0f0; }}
  .sev-low {{ background: #f6f6f6; color: var(--mute); }}
  .summary {{ display: flex; gap: 2rem; margin-bottom: 2rem; flex-wrap: wrap; }}
  .stat {{ border: 1px solid var(--ink); padding: 1rem 1.4rem; min-width: 140px; }}
  .stat .v {{ font-family: 'Bodoni Moda', serif; font-size: 2rem; font-weight: 600; }}
  .stat .l {{ font-family: monospace; font-size: 0.7rem; text-transform: uppercase; color: var(--mute); }}
</style></head><body>
<h1>Data Quality Report — Datalake Energía</h1>
<div class="meta">Generado: {summary['generated_at']} · Ejecución: {summary['runtime_sec']:.2f}s · Versión schema: {summary['schema_version']}</div>
<div class="summary">
  <div class="stat"><div class="v">{summary['total']}</div><div class="l">Tests ejecutados</div></div>
  <div class="stat"><div class="v" style="color:var(--green)">{summary['passed']}</div><div class="l">Pasaron</div></div>
  <div class="stat"><div class="v" style="color:var(--red)">{summary['failed']}</div><div class="l">Fallaron</div></div>
  <div class="stat"><div class="v">{summary['pass_rate']:.0%}</div><div class="l">Tasa de éxito</div></div>
</div>
<table>
<thead><tr><th>#</th><th>Categoría</th><th>Test</th><th>Esperado</th><th>Actual</th><th>Severidad</th><th>Estado</th></tr></thead>
<tbody>{rows}</tbody>
</table>
</body></html>"""


def main() -> None:
    t0 = dt.datetime.now()
    checks = run_checks()
    runtime = (dt.datetime.now() - t0).total_seconds()
    passed = sum(1 for c in checks if c.passed)
    failed = len(checks) - passed
    summary = {
        "generated_at": dt.datetime.now().isoformat(timespec="seconds"),
        "runtime_sec": runtime,
        "schema_version": "1.0.0",
        "total": len(checks),
        "passed": passed,
        "failed": failed,
        "pass_rate": passed / len(checks),
        "checks": [asdict(c) for c in checks],
    }

    json_path = OUTPUT / "data_quality_summary.json"
    html_path = OUTPUT / "data_quality_report.html"
    json_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    html_path.write_text(render_html(checks, summary), encoding="utf-8")

    log.info(f"Resultado: {passed}/{len(checks)} pasaron · reporte → {html_path.relative_to(OUTPUT.parent)}")
    with pg_conn() as conn:
        log_step(conn, "dq_test", "ok" if failed == 0 else "warning", filas=len(checks),
                 detalles={"passed": passed, "failed": failed, "rate": summary["pass_rate"]})

    if failed > 0:
        log.warning(f"{failed} chequeos fallaron — revisa el reporte HTML")
        # No exit 1: queremos publicar el reporte aunque haya warnings


if __name__ == "__main__":
    main()
