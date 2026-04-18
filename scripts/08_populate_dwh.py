"""
Pobla las tablas dwh.* en RDS desde core.* mediante INSERT FROM SELECT.
Esto materializa el modelo estrella en RDS (mismo modelo que el ETL Glue
escribe en S3 processed) para que el front muestre conteos consistentes.

Uso:
    python scripts/08_populate_dwh.py
"""
from __future__ import annotations
from prueba_amaris_aws.scripts._common import configure_logging, log_step, pg_conn, Timer

log = configure_logging("dwh-populate")

SQL_TIPO_ENERGIA = """
INSERT INTO dwh.dim_tipo_energia (tipo_energia_id, codigo, nombre, factor_co2_kg_mwh, renovable)
SELECT tipo_energia_id, codigo, nombre, factor_co2_kg_mwh, renovable
FROM   core.tipo_energia
ON CONFLICT (tipo_energia_id) DO UPDATE SET
    codigo=EXCLUDED.codigo, nombre=EXCLUDED.nombre,
    factor_co2_kg_mwh=EXCLUDED.factor_co2_kg_mwh, renovable=EXCLUDED.renovable
"""

SQL_CIUDAD = """
INSERT INTO dwh.dim_ciudad (ciudad_id, nombre, pais, lat, lon, poblacion)
SELECT ciudad_id, nombre, pais, lat, lon, poblacion
FROM   core.ciudad
ON CONFLICT (ciudad_id) DO UPDATE SET
    nombre=EXCLUDED.nombre, pais=EXCLUDED.pais,
    lat=EXCLUDED.lat, lon=EXCLUDED.lon, poblacion=EXCLUDED.poblacion
"""

SQL_PROVEEDOR = """
INSERT INTO dwh.dim_proveedor (proveedor_id, nombre, pais, tipo_energia_id, capacidad_mw, activo, fecha_alta, cargado_en)
SELECT proveedor_id, nombre, pais, tipo_energia_id, capacidad_mw, activo, fecha_alta, NOW()
FROM   core.proveedor
ON CONFLICT (proveedor_id) DO UPDATE SET
    nombre=EXCLUDED.nombre, pais=EXCLUDED.pais,
    tipo_energia_id=EXCLUDED.tipo_energia_id, capacidad_mw=EXCLUDED.capacidad_mw,
    activo=EXCLUDED.activo, fecha_alta=EXCLUDED.fecha_alta, cargado_en=NOW()
"""

SQL_CLIENTE = """
INSERT INTO dwh.dim_cliente (cliente_id, nombre, segmento, ciudad_id, tipo_id, id_externo, activo, fecha_alta, cargado_en)
SELECT cliente_id, nombre, segmento::text, ciudad_id, tipo_id, id_externo, activo, fecha_alta, NOW()
FROM   core.cliente
ON CONFLICT (cliente_id) DO UPDATE SET
    nombre=EXCLUDED.nombre, segmento=EXCLUDED.segmento,
    ciudad_id=EXCLUDED.ciudad_id, tipo_id=EXCLUDED.tipo_id,
    id_externo=EXCLUDED.id_externo, activo=EXCLUDED.activo,
    fecha_alta=EXCLUDED.fecha_alta, cargado_en=NOW()
"""

SQL_FACT = """
INSERT INTO dwh.fact_transaccion (
    transaccion_id, fecha_id, tipo_energia_id, proveedor_id, cliente_id,
    tipo, cantidad_mwh, precio_usd, monto_usd, cargado_en
)
SELECT
    t.transaccion_id,
    TO_CHAR(t.fecha, 'YYYYMMDD')::INT,
    t.tipo_energia_id,
    t.proveedor_id,
    t.cliente_id,
    t.tipo::text,
    t.cantidad_mwh,
    t.precio_usd,
    t.monto_usd,
    NOW()
FROM   core.transaccion t
ON CONFLICT (transaccion_id) DO UPDATE SET
    fecha_id=EXCLUDED.fecha_id,
    tipo_energia_id=EXCLUDED.tipo_energia_id,
    proveedor_id=EXCLUDED.proveedor_id,
    cliente_id=EXCLUDED.cliente_id,
    tipo=EXCLUDED.tipo,
    cantidad_mwh=EXCLUDED.cantidad_mwh,
    precio_usd=EXCLUDED.precio_usd,
    monto_usd=EXCLUDED.monto_usd,
    cargado_en=NOW()
"""


def main():
    with pg_conn() as conn, conn.cursor() as cur:
        with Timer(log, "dim_tipo_energia"):
            cur.execute(SQL_TIPO_ENERGIA)
            log.info(f"  filas: {cur.rowcount}")
        with Timer(log, "dim_ciudad"):
            cur.execute(SQL_CIUDAD)
            log.info(f"  filas: {cur.rowcount}")
        with Timer(log, "dim_proveedor"):
            cur.execute(SQL_PROVEEDOR)
            log.info(f"  filas: {cur.rowcount}")
        with Timer(log, "dim_cliente"):
            cur.execute(SQL_CLIENTE)
            log.info(f"  filas: {cur.rowcount}")
        with Timer(log, "fact_transaccion"):
            cur.execute(SQL_FACT)
            log.info(f"  filas: {cur.rowcount}")
        with Timer(log, "ANALYZE"):
            cur.execute("ANALYZE dwh.dim_tipo_energia, dwh.dim_ciudad, dwh.dim_proveedor, dwh.dim_cliente, dwh.fact_transaccion")
        log_step(conn, "dwh_populate", "ok",
                 detalles={"materialized_in_rds": True})
    log.info("Population dwh.* completed.")


if __name__ == "__main__":
    main()
