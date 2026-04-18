"""
Glue Job · transform_fact
Lee transacciones desde raw, deriva fecha_id (YYYYMMDD), recalcula
monto_usd y particiona por (anio, mes) en Parquet+Snappy.
"""
import sys
from awsglue.context import GlueContext
from awsglue.job import Job
from awsglue.utils import getResolvedOptions
from pyspark.context import SparkContext
from pyspark.sql import functions as F

args = getResolvedOptions(sys.argv, ["JOB_NAME", "raw_bucket", "processed_bucket", "glue_db"])
sc = SparkContext()
glue = GlueContext(sc)
spark = glue.spark_session
job = Job(glue); job.init(args["JOB_NAME"], args)

RAW = f"s3://{args['raw_bucket']}"
OUT = f"s3://{args['processed_bucket']}"

df = spark.read.option("header", "true").csv(f"{RAW}/transaccion/")
# Toma la última partición de carga si hay múltiples
df = df.withColumn("loaded_at",
                   F.regexp_extract(F.input_file_name(), r"loaded_at=([^/]+)", 1))
last_load = df.agg(F.max("loaded_at")).first()[0]
print(f"Última partición de carga: {last_load}")
df = df.filter(F.col("loaded_at") == last_load).drop("loaded_at")

fact = (df
        .withColumn("fecha_d", F.to_date("fecha"))
        .withColumn("fecha_id", F.expr("CAST(date_format(fecha_d, 'yyyyMMdd') AS INT)"))
        .withColumn("anio", F.year("fecha_d"))
        .withColumn("mes",  F.month("fecha_d"))
        .selectExpr(
            "CAST(transaccion_id AS BIGINT)  AS transaccion_id",
            "fecha_id",
            "CAST(tipo_energia_id AS SMALLINT) AS tipo_energia_id",
            "CAST(NULLIF(proveedor_id, '') AS INT) AS proveedor_id",
            "CAST(NULLIF(cliente_id, '') AS INT)   AS cliente_id",
            "tipo",
            "CAST(cantidad_mwh AS DECIMAL(12,3)) AS cantidad_mwh",
            "CAST(precio_usd   AS DECIMAL(10,2)) AS precio_usd",
            "CAST(cantidad_mwh AS DECIMAL(12,3)) * CAST(precio_usd AS DECIMAL(10,2)) AS monto_usd",
            "anio",
            "mes"
        ))

print(f"Filas a escribir: {fact.count()}")

(fact.write.mode("overwrite")
     .option("compression", "snappy")
     .partitionBy("anio", "mes")
     .parquet(f"{OUT}/fact/"))

print(f"✓ fact_transaccion → {OUT}/fact/")
job.commit()
