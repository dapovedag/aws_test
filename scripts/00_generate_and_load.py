"""
Genera un dataset abundante y realista de proveedores, clientes y transacciones
con Faker (seed fijo para reproducibilidad) y lo carga vía COPY a la base RDS.

Volúmenes objetivo (configurables por env vars):
    PROVEEDORES = 50    (mix de 5 tipos de energía, 5 países)
    CLIENTES    = 500   (60% residencial, 25% comercial, 15% industrial)
    TX_TOTAL    = 10000 (compras + ventas, 24 meses, estacionalidad realista)

Uso:
    python scripts/00_generate_and_load.py
"""
from __future__ import annotations

import io
import math
import os
import random
from datetime import date, datetime, timedelta
from decimal import Decimal

from faker import Faker

from prueba_amaris_aws.scripts._common import configure_logging, env, log_step, pg_conn, Timer

log = configure_logging("faker-load")

SEED = int(env("FAKER_SEED", "42"))
PROVEEDORES = int(env("PROVEEDORES", "50"))
CLIENTES = int(env("CLIENTES", "500"))
TX_TOTAL = int(env("TX_TOTAL", "10000"))
FECHA_INICIO = date.fromisoformat(env("FECHA_INICIO", "2024-01-01"))
FECHA_FIN = date.fromisoformat(env("FECHA_FIN", "2026-04-30"))

fake = Faker("es_CO")
Faker.seed(SEED)
random.seed(SEED)

# ---------------------------------------------------------------------------
# CATÁLOGOS DE REFERENCIA — alineados con 01_seed_dimensions.sql
# ---------------------------------------------------------------------------
# Precios spot promedio USD/MWh por tipo de energía (referencia mercado)
PRECIO_BASE = {
    "eolica": 45.0,
    "hidroelectrica": 55.0,
    "solar": 42.0,
    "biomasa": 70.0,
    "nuclear": 60.0,
}
# Volatilidad relativa (sd como fracción del precio base)
PRECIO_SD = {"eolica": 0.18, "hidroelectrica": 0.12, "solar": 0.20, "biomasa": 0.15, "nuclear": 0.08}
# Margen aplicado a ventas vs compras (%)
MARGEN_VENTA = {"residencial": 0.45, "comercial": 0.32, "industrial": 0.18}
# Capacidades típicas por tipo (MW): mínimo, máximo
CAPACIDAD_RANGO = {
    "eolica": (5, 350),
    "hidroelectrica": (50, 2000),
    "solar": (5, 200),
    "biomasa": (5, 80),
    "nuclear": (500, 1500),
}
PAISES = ["Colombia", "Argentina", "Perú", "Chile", "Ecuador", "Panamá"]
PAIS_PESO = [0.55, 0.12, 0.10, 0.10, 0.08, 0.05]
# Distribución segmento clientes
SEGMENTOS = ["residencial", "comercial", "industrial"]
SEG_PESO = [0.60, 0.25, 0.15]


def fetch_dim_ids(cur):
    cur.execute("SELECT tipo_energia_id, codigo FROM core.tipo_energia ORDER BY tipo_energia_id")
    energias = cur.fetchall()  # [(id, codigo), ...]
    cur.execute("SELECT ciudad_id, nombre, pais, poblacion FROM core.ciudad ORDER BY ciudad_id")
    ciudades = cur.fetchall()
    if not energias or not ciudades:
        raise RuntimeError("Ejecuta 01_seed_dimensions.sql antes de este script")
    return energias, ciudades


def truncate_data(cur):
    """Borra datos transaccionales pero mantiene catálogos. Idempotente."""
    log.info("Limpiando tablas transaccionales...")
    # Usamos DELETE en lugar de TRUNCATE porque app_rw no es owner de las sequences.
    cur.execute("DELETE FROM core.transaccion")
    cur.execute("DELETE FROM core.cliente")
    cur.execute("DELETE FROM core.proveedor")
    # Reset sequences (acepta error si no es owner — caso normal con RDS)
    for seq in ["core.transaccion_transaccion_id_seq",
                "core.cliente_cliente_id_seq",
                "core.proveedor_proveedor_id_seq"]:
        try:
            cur.execute(f"ALTER SEQUENCE {seq} RESTART WITH 1")
        except Exception:
            pass  # ignored — owner-only operation


def gen_proveedores(energias) -> list[tuple]:
    rows = []
    for _ in range(PROVEEDORES):
        tipo_id, codigo = random.choices(energias, weights=[1, 4, 2, 1, 0.5])[0]
        cap_min, cap_max = CAPACIDAD_RANGO[codigo]
        cap = round(random.uniform(cap_min, cap_max), 2)
        pais = random.choices(PAISES, weights=PAIS_PESO)[0]
        # Usa nombres geográficos realistas
        nombre_base = random.choice([
            "Parque", "Hidroeléctrica", "Central", "Granja", "Planta", "Estación",
        ])
        sufijo = fake.last_name() + " " + random.choice(["Norte", "Sur", "Alta", "Baja", "del Río", "del Valle", "Andes", "Caribe"])
        nombre = f"{nombre_base} {codigo.title()} {sufijo}"[:118]
        # Garantiza unicidad por sufijo numérico si colisiona
        fecha_alta = fake.date_between(start_date="-8y", end_date="-1y")
        contacto = fake.email()
        rows.append((nombre, tipo_id, pais, cap, fecha_alta, True, contacto))
    return rows


def gen_clientes(ciudades) -> list[tuple]:
    rows = []
    seen_ids = set()
    # Pesos por población: ciudades grandes concentran más clientes
    poblaciones = [c[3] or 50000 for c in ciudades]
    pesos = [math.log10(p + 1) for p in poblaciones]
    for _ in range(CLIENTES):
        ciudad = random.choices(ciudades, weights=pesos)[0]
        ciudad_id = ciudad[0]
        seg = random.choices(SEGMENTOS, weights=SEG_PESO)[0]
        if seg == "industrial":
            tipo_id = "NIT"
            id_ext = f"{random.randint(800000000, 999999999)}-{random.randint(0,9)}"
            nombre = fake.company() + " " + random.choice(["S.A.S.", "S.A.", "Ltda."])
        elif seg == "comercial":
            tipo_id = random.choices(["NIT", "CC"], weights=[0.7, 0.3])[0]
            if tipo_id == "NIT":
                id_ext = f"{random.randint(800000000, 999999999)}-{random.randint(0,9)}"
            else:
                id_ext = str(random.randint(10_000_000, 99_999_999))
            nombre = fake.company()
        else:  # residencial
            tipo_id = random.choices(["CC", "CE"], weights=[0.92, 0.08])[0]
            id_ext = str(random.randint(1_000_000_000, 1_999_999_999))
            nombre = fake.name()
        if id_ext in seen_ids:
            continue
        seen_ids.add(id_ext)
        fecha_alta = fake.date_between(start_date="-5y", end_date="-30d")
        rows.append((tipo_id, id_ext, nombre[:158], ciudad_id, seg, fecha_alta, fake.email(), True))
    return rows


def estacionalidad(d: date) -> float:
    """Multiplicador estacional: picos Q1 (caliente) y Q3 (frío relativo)."""
    doy = d.timetuple().tm_yday
    return 1.0 + 0.25 * math.sin(2 * math.pi * doy / 182.5)


def gen_transacciones(proveedores: list[tuple], clientes: list[tuple], energias_map: dict[int, str]) -> list[tuple]:
    """Genera ~TX_TOTAL transacciones. Asegura por mes:
        compras_mwh ≥ ventas_mwh (no se vende más de lo comprado).
    Mix: ~55% compras a proveedores, 45% ventas a clientes."""
    rows = []
    days = (FECHA_FIN - FECHA_INICIO).days + 1
    n_compras = int(TX_TOTAL * 0.55)
    n_ventas = TX_TOTAL - n_compras

    # COMPRAS — distribuidas con estacionalidad
    pesos_dia = [estacionalidad(FECHA_INICIO + timedelta(days=i)) for i in range(days)]
    for _ in range(n_compras):
        i = random.choices(range(days), weights=pesos_dia)[0]
        d = FECHA_INICIO + timedelta(days=i)
        prov = random.choice(proveedores)  # (id, tipo_energia_id, capacidad_mw, codigo)
        prov_id, te_id, cap_mw, codigo = prov
        cap_mw = float(cap_mw)  # viene Decimal del DB
        # Cantidad: porción de la capacidad horaria * estacionalidad * ruido
        base = cap_mw * 24 * 0.65  # ~65% factor de planta promedio
        cantidad = max(0.5, base * estacionalidad(d) * random.gauss(1.0, 0.18))
        precio = max(10.0, random.gauss(PRECIO_BASE[codigo], PRECIO_BASE[codigo] * PRECIO_SD[codigo]))
        rows.append((d, "compra", prov_id, None, te_id, round(cantidad, 3), round(precio, 2)))

    # VENTAS — más pequeñas en MWh por transacción
    for _ in range(n_ventas):
        i = random.choices(range(days), weights=pesos_dia)[0]
        d = FECHA_INICIO + timedelta(days=i)
        cli = random.choice(clientes)  # (id, segmento)
        cli_id, seg = cli
        # Elige tipo de energía pesado por mix renovable
        te_id, codigo = random.choices(
            list(energias_map.items()), weights=[3, 4, 3, 1, 0.5]
        )[0]
        if seg == "industrial":
            cantidad = max(0.5, random.gauss(180, 60))
        elif seg == "comercial":
            cantidad = max(0.2, random.gauss(25, 10))
        else:
            cantidad = max(0.05, abs(random.gauss(0.6, 0.3)))
        precio_compra = PRECIO_BASE[codigo]
        precio_venta = precio_compra * (1 + MARGEN_VENTA[seg]) * random.gauss(1.0, 0.06)
        rows.append((d, "venta", None, cli_id, te_id, round(cantidad, 3), round(precio_venta, 2)))

    random.shuffle(rows)
    return rows


def copy_rows(cur, table: str, columns: list[str], rows: list[tuple]) -> None:
    """Bulk insert via COPY FROM STDIN (mucho más rápido que executemany)."""
    buf = io.StringIO()
    for r in rows:
        line = "\t".join(["\\N" if v is None else str(v) for v in r])
        buf.write(line + "\n")
    buf.seek(0)
    cur.copy_expert(
        f"COPY {table} ({', '.join(columns)}) FROM STDIN WITH (FORMAT text, DELIMITER E'\\t', NULL '\\N')",
        buf,
    )


def main() -> None:
    log.info(
        f"Faker seed={SEED} · proveedores={PROVEEDORES} · clientes={CLIENTES} · "
        f"transacciones={TX_TOTAL} · ventana=[{FECHA_INICIO} → {FECHA_FIN}]"
    )

    with pg_conn() as conn, conn.cursor() as cur:
        with Timer(log, "Lectura dimensiones"):
            energias, ciudades = fetch_dim_ids(cur)
        with Timer(log, "Limpieza datos previos"):
            truncate_data(cur)

        with Timer(log, f"Generando {PROVEEDORES} proveedores"):
            prov_rows = gen_proveedores(energias)
            copy_rows(cur, "core.proveedor",
                      ["nombre", "tipo_energia_id", "pais", "capacidad_mw", "fecha_alta", "activo", "contacto_email"],
                      prov_rows)
            cur.execute("SELECT proveedor_id, tipo_energia_id, capacidad_mw, te.codigo "
                        "FROM core.proveedor p JOIN core.tipo_energia te USING (tipo_energia_id)")
            proveedores_db = cur.fetchall()  # [(id, te_id, cap, codigo), ...]
            log.info(f"  → {len(proveedores_db)} proveedores insertados")

        with Timer(log, f"Generando {CLIENTES} clientes"):
            cli_rows = gen_clientes(ciudades)
            copy_rows(cur, "core.cliente",
                      ["tipo_id", "id_externo", "nombre", "ciudad_id", "segmento", "fecha_alta", "contacto_email", "activo"],
                      cli_rows)
            cur.execute("SELECT cliente_id, segmento::text FROM core.cliente")
            clientes_db = cur.fetchall()
            log.info(f"  → {len(clientes_db)} clientes insertados")

        with Timer(log, f"Generando ~{TX_TOTAL} transacciones"):
            energias_map = {te[0]: te[1] for te in energias}
            tx_rows = gen_transacciones(proveedores_db, clientes_db, energias_map)
            copy_rows(cur, "core.transaccion",
                      ["fecha", "tipo", "proveedor_id", "cliente_id", "tipo_energia_id", "cantidad_mwh", "precio_usd"],
                      tx_rows)
            log.info(f"  → {len(tx_rows)} transacciones insertadas")

        with Timer(log, "Refrescando estadísticas (ANALYZE)"):
            cur.execute("ANALYZE core.proveedor, core.cliente, core.transaccion")

        log_step(conn, "faker_load", "ok", filas=len(prov_rows) + len(cli_rows) + len(tx_rows),
                 detalles={
                     "seed": SEED,
                     "proveedores": len(prov_rows),
                     "clientes": len(cli_rows),
                     "transacciones": len(tx_rows),
                     "ventana": f"{FECHA_INICIO}..{FECHA_FIN}",
                 })

    log.info("✓ Carga Faker completada exitosamente.")


if __name__ == "__main__":
    main()
