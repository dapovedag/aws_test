"""
Glue Job · transform_aggregates
Materializa pre-agregados mensuales (mes × tipo_energia × tipo_transaccion)
para que Athena/Redshift hagan queries instantáneas sin escanear el fact.
"""
import sys
from awsglue.context import GlueContext
from awsglue.job import Job
from awsglue.utils import getResolvedOptions
from pyspark.context import SparkContext
from pyspark.sql import functions as F

args = getResolvedOptions(sys.argv, ["JOB_NAME", "processed_bucket", "glue_db"])
sc = SparkContext()
glue = GlueContext(sc)
spark = glue.spark_session
job = Job(glue); job.init(args["JOB_NAME"], args)

OUT = f"s3://{args['processed_bucket']}"

fact = spark.read.parquet(f"{OUT}/fact/")
dim_te = spark.read.parquet(f"{OUT}/dim_tipo_energia/")

agg = (fact
       .join(dim_te.select(F.col("tipo_energia_id").alias("te_id"),
                           F.col("codigo").alias("tipo_energia_codigo")),
             fact.tipo_energia_id == F.col("te_id"), "left")
       .groupBy("anio", "mes", "tipo_energia_codigo", "tipo")
       .agg(
            F.count("*").alias("num_transacciones"),
            F.sum("cantidad_mwh").alias("mwh_total"),
            F.sum("monto_usd").alias("monto_usd_total"),
            F.avg("precio_usd").alias("precio_promedio"),
       ))

print(f"Filas agregadas: {agg.count()}")

(agg.write.mode("overwrite")
     .option("compression", "snappy")
     .partitionBy("anio")
     .parquet(f"{OUT}/agg_resumen_mensual/"))

print(f"✓ resumen_mensual → {OUT}/agg_resumen_mensual/")
job.commit()
