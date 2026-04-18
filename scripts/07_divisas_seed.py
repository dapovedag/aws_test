"""
Pobla el schema divisas (Ej.2) con datos sintéticos:
  - 3 pares: USD/COP, USD/MXN, EUR/USD
  - ~30 días de ticks generados con random walk realista (~1000 ticks/día/par)
  - 100 portafolios (sample de core.cliente del Ej.1)
  - ~500 notificaciones distribuidas en los últimos 30 días
  - 3 modelos registrados (uno activo)

Uso:
    python scripts/07_divisas_seed.py
"""
from __future__ import annotations

import io
import json
import math
import random
from datetime import datetime, timedelta, timezone

from prueba_amaris_aws.scripts._common import configure_logging, env, log_step, pg_conn, Timer

log = configure_logging("divisas-seed")

SEED = int(env("FAKER_SEED", "42"))
random.seed(SEED)

PARES = [
    ("USD", "COP", "Dólar estadounidense / Peso colombiano", 4150.0, 0.012),
    ("USD", "MXN", "Dólar estadounidense / Peso mexicano",     17.30, 0.008),
    ("EUR", "USD", "Euro / Dólar estadounidense",                1.085, 0.005),
]
TICKS_POR_DIA = 1000
DIAS = 30
N_PORTAFOLIOS = 100
N_NOTIFICACIONES = 500
PERFILES = ("conservador", "moderado", "agresivo")
PERFIL_PESOS = (0.30, 0.55, 0.15)
TIPOS_NOTIF = ("comprar", "vender", "observar")
CANALES = ("push", "email", "sms")
CANAL_PESOS = (0.70, 0.20, 0.10)


def truncate_all(cur):
    log.info("Limpiando schema divisas...")
    for tbl in ("divisas.notificacion", "divisas.tipo_cambio_tick",
                "divisas.usuario_portafolio", "divisas.modelo_recomendacion",
                "divisas.par_divisa"):
        try:
            cur.execute(f"DELETE FROM {tbl}")
        except Exception as e:
            log.warning(f"  no se pudo limpiar {tbl}: {e}")


def insert_pares(cur):
    rows = []
    for base, cot, desc, _ref, _vol in PARES:
        rows.append((base, cot, desc, True))
    cur.executemany(
        "INSERT INTO divisas.par_divisa (base, cotizada, descripcion, activo) VALUES (%s,%s,%s,%s)",
        rows,
    )
    cur.execute("SELECT par_id, base, cotizada FROM divisas.par_divisa ORDER BY par_id")
    return cur.fetchall()


def gen_ticks(par_ids: list[tuple]) -> list[tuple]:
    """Genera ~1000 ticks/día por par usando random walk geométrico."""
    rows = []
    now = datetime.now(timezone.utc)
    inicio = now - timedelta(days=DIAS)
    by_pair = {(b, c): pid for pid, b, c in par_ids}
    for base, cot, _desc, ref, vol in PARES:
        par_id = by_pair[(base, cot)]
        precio = ref
        for d in range(DIAS):
            for t in range(TICKS_POR_DIA):
                # Random walk con mean-reversion suave hacia ref
                drift = (ref - precio) * 0.002
                shock = random.gauss(0, vol * precio * 0.05)
                precio = max(0.01, precio + drift + shock)
                spread_pct = random.uniform(0.001, 0.004)
                pc = round(precio * (1 - spread_pct / 2), 4)
                pv = round(precio * (1 + spread_pct / 2), 4)
                # Distribuye dentro del día con jitter
                offset_seg = int((d * 86400 + t * (86400 / TICKS_POR_DIA))
                                 + random.randint(-30, 30))
                ts = inicio + timedelta(seconds=offset_seg)
                fuente = random.choice(["bloomberg", "reuters", "interbancario", "spot-market"])
                rows.append((par_id, pc, pv, fuente, ts))
    return rows


def gen_portafolios(cur) -> list[tuple]:
    cur.execute("SELECT cliente_id FROM core.cliente ORDER BY random() LIMIT %s", (N_PORTAFOLIOS,))
    cli_ids = [r[0] for r in cur.fetchall()]
    rows = []
    for cid in cli_ids:
        usd = round(random.uniform(50, 50000), 2)
        cop = round(usd * random.uniform(3500, 4500) * random.uniform(0.5, 3.0), 2)
        perfil = random.choices(PERFILES, weights=PERFIL_PESOS)[0]
        rows.append((cid, usd, cop, perfil))
    return rows, cli_ids


def gen_notificaciones(par_ids: list[tuple], cli_ids: list[int]) -> list[tuple]:
    rows = []
    by_pair = {pid: (b, c, ref) for (pid, b, c), (_, _, _, ref, _)
               in zip(par_ids, PARES)}
    now = datetime.now(timezone.utc)
    for _ in range(N_NOTIFICACIONES):
        cid = random.choice(cli_ids)
        tipo = random.choices(TIPOS_NOTIF, weights=(0.4, 0.4, 0.2))[0]
        par_id = random.choice(list(by_pair.keys()))
        ref = by_pair[par_id][2]
        precio = round(ref * random.uniform(0.96, 1.04), 4)
        score = round(random.betavariate(6, 2), 4)  # sesgado hacia alta confianza
        canal = random.choices(CANALES, weights=CANAL_PESOS)[0]
        if tipo == "comprar":
            msg = f"Recomendamos comprar {by_pair[par_id][0]} a {precio} (score {score}). Tu portafolio se beneficiaría según tu perfil."
        elif tipo == "vender":
            msg = f"Recomendamos vender {by_pair[par_id][0]} a {precio} (score {score}). Capturamos margen frente a tu base reciente."
        else:
            msg = f"Mercado lateral en {by_pair[par_id][0]}/{by_pair[par_id][1]}. Mantén observación, score {score}."
        delta = timedelta(seconds=random.randint(0, DIAS * 86400))
        ts = now - delta
        leida = random.random() < 0.45
        convertida = leida and random.random() < 0.18
        rows.append((cid, tipo, par_id, precio, score, canal, msg, ts, leida, convertida))
    return rows


def insert_modelos(cur):
    modelos = [
        ("v0.1", "logistic-regression",
         {"window_min": 5, "features": ["spread", "vol_5m", "return_1h", "user_avg_hold"]},
         {"auc": 0.71, "precision": 0.62, "recall": 0.55}, False,
         datetime.now(timezone.utc) - timedelta(days=90)),
        ("v0.2", "lightgbm",
         {"window_min": 5, "features": ["spread", "vol_5m", "return_1h", "user_avg_hold",
                                         "portafolio_skew", "tick_velocity"]},
         {"auc": 0.83, "precision": 0.74, "recall": 0.68}, False,
         datetime.now(timezone.utc) - timedelta(days=30)),
        ("v0.3-prod", "lightgbm + temporal-fusion",
         {"window_min": [1, 5, 60], "features": ["spread", "vol_*", "return_*", "portafolio_*",
                                                  "tick_velocity", "macro_index"]},
         {"auc": 0.89, "precision": 0.81, "recall": 0.76, "ctr_uplift": 0.22}, True,
         datetime.now(timezone.utc) - timedelta(days=7)),
    ]
    cur.executemany(
        """INSERT INTO divisas.modelo_recomendacion
           (version, algoritmo, features, metricas, activo, entrenado_en)
           VALUES (%s,%s,%s::jsonb,%s::jsonb,%s,%s)""",
        [(v, a, json.dumps(f), json.dumps(m), act, t) for v, a, f, m, act, t in modelos]
    )


def copy_rows(cur, table: str, columns: list[str], rows: list[tuple]) -> None:
    if not rows:
        return
    buf = io.StringIO()
    for r in rows:
        line = "\t".join(["\\N" if v is None else str(v) for v in r])
        buf.write(line + "\n")
    buf.seek(0)
    cur.copy_expert(
        f"COPY {table} ({', '.join(columns)}) FROM STDIN WITH (FORMAT text, DELIMITER E'\\t', NULL '\\N')",
        buf,
    )


def main():
    log.info(f"Seed divisas · seed={SEED} · pares={len(PARES)} · "
             f"ticks objetivo={TICKS_POR_DIA * DIAS * len(PARES):,} · "
             f"portafolios={N_PORTAFOLIOS} · notificaciones={N_NOTIFICACIONES}")

    with pg_conn() as conn, conn.cursor() as cur:
        with Timer(log, "Limpieza"):
            truncate_all(cur)

        with Timer(log, "Inserción de pares"):
            par_ids = insert_pares(cur)
            log.info(f"  {len(par_ids)} pares insertados")

        with Timer(log, "Inserción de modelos"):
            insert_modelos(cur)

        with Timer(log, "Generación de ticks"):
            ticks = gen_ticks(par_ids)
            log.info(f"  {len(ticks):,} ticks generados, copiando...")
            copy_rows(cur, "divisas.tipo_cambio_tick",
                      ["par_id", "precio_compra", "precio_venta", "fuente", "capturado_en"],
                      ticks)

        with Timer(log, "Inserción de portafolios"):
            portafolios, cli_ids = gen_portafolios(cur)
            copy_rows(cur, "divisas.usuario_portafolio",
                      ["cliente_id", "saldo_usd", "saldo_cop", "perfil_riesgo"],
                      portafolios)
            log.info(f"  {len(portafolios)} portafolios")

        with Timer(log, "Generación de notificaciones"):
            notifs = gen_notificaciones(par_ids, cli_ids)
            copy_rows(cur, "divisas.notificacion",
                      ["cliente_id", "tipo", "par_id", "precio_referencia",
                       "score_confianza", "canal", "mensaje", "enviada_en", "leida", "convertida"],
                      notifs)
            log.info(f"  {len(notifs)} notificaciones")

        with Timer(log, "ANALYZE"):
            cur.execute("ANALYZE divisas.tipo_cambio_tick, divisas.usuario_portafolio, divisas.notificacion")

        log_step(conn, "divisas_seed", "ok",
                 filas=len(ticks) + len(portafolios) + len(notifs),
                 detalles={"pares": len(par_ids), "ticks": len(ticks),
                           "portafolios": len(portafolios), "notificaciones": len(notifs)})

    log.info("Seed divisas completado.")


if __name__ == "__main__":
    main()
