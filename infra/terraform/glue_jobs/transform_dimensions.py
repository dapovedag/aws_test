"""
Glue Job · transform_dimensions
Lee CSVs de raw para tipo_energia, ciudad, proveedor, cliente,
deduplica (SCD-1: nos quedamos con la última carga) y materializa
en s3://prueba-aws-processed/dim_*/  como Parquet+Snappy.
"""
import sys
from awsglue.context import GlueContext
from awsglue.job import Job
from awsglue.utils import getResolvedOptions
from pyspark.context import SparkContext
from pyspark.sql import functions as F
from pyspark.sql.window import Window

args = getResolvedOptions(sys.argv, ["JOB_NAME", "raw_bucket", "processed_bucket", "glue_db"])
sc = SparkContext()
glue = GlueContext(sc)
spark = glue.spark_session
job = Job(glue); job.init(args["JOB_NAME"], args)

RAW = f"s3://{args['raw_bucket']}"
OUT = f"s3://{args['processed_bucket']}"


def latest(table: str, key_col: str):
    df = spark.read.option("header", "true").csv(f"{RAW}/{table}/")
    if "loaded_at" in df.columns:
        df = df.drop("loaded_at")
    # Inferimos partition por path:
    df = df.withColumn(
        "loaded_at",
        F.regexp_extract(F.input_file_name(), r"loaded_at=([^/]+)", 1)
    )
    w = Window.partitionBy(key_col).orderBy(F.col("loaded_at").desc())
    return df.withColumn("_rn", F.row_number().over(w)).filter("_rn = 1").drop("_rn", "loaded_at")


def write(df, name: str):
    df.write.mode("overwrite").option("compression", "snappy").parquet(f"{OUT}/{name}/")
    print(f"✓ {name}: {df.count()} filas → {OUT}/{name}/")


# DIM tipo_energia
dte = (latest("tipo_energia", "tipo_energia_id")
       .selectExpr("CAST(tipo_energia_id AS SMALLINT) AS tipo_energia_id",
                   "codigo", "nombre",
                   "CAST(factor_co2_kg_mwh AS DECIMAL(8,2)) AS factor_co2_kg_mwh",
                   "CAST(renovable AS BOOLEAN) AS renovable"))
write(dte, "dim_tipo_energia")

# DIM ciudad
dci = (latest("ciudad", "ciudad_id")
       .selectExpr("CAST(ciudad_id AS INT) AS ciudad_id",
                   "nombre", "pais",
                   "CAST(lat AS DECIMAL(9,6)) AS lat",
                   "CAST(lon AS DECIMAL(9,6)) AS lon",
                   "CAST(poblacion AS INT) AS poblacion"))
write(dci, "dim_ciudad")

# DIM proveedor
dpr = (latest("proveedor", "proveedor_id")
       .selectExpr("CAST(proveedor_id AS INT) AS proveedor_id",
                   "nombre",
                   "CAST(tipo_energia_id AS SMALLINT) AS tipo_energia_id",
                   "pais",
                   "CAST(capacidad_mw AS DECIMAL(10,2)) AS capacidad_mw",
                   "CAST(activo AS BOOLEAN) AS activo",
                   "CAST(fecha_alta AS DATE) AS fecha_alta"))
write(dpr, "dim_proveedor")

# DIM cliente
dcl = (latest("cliente", "cliente_id")
       .selectExpr("CAST(cliente_id AS INT) AS cliente_id",
                   "nombre", "segmento",
                   "CAST(ciudad_id AS INT) AS ciudad_id",
                   "tipo_id", "id_externo",
                   "CAST(activo AS BOOLEAN) AS activo",
                   "CAST(fecha_alta AS DATE) AS fecha_alta"))
write(dcl, "dim_cliente")

job.commit()
