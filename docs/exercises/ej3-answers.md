# Ejercicio 3 · Preguntas generales

---

## 1. ¿Qué experiencias has tenido como ingeniero de datos en AWS? ¿Cuál ha sido el proyecto más retador y por qué?

He trabajado con AWS desde 2021. Mi caja de herramientas habitual es S3 + Glue + Athena para datalakes y Redshift para data warehouse, complementada con RDS PostgreSQL cuando necesito una fuente OLTP. **Empaqueto todos mis workloads custom en Docker** y los corro sobre **Kubernetes en EKS** cuando necesito orquestación seria; uso EC2 para workers stateful que no se prestan a contenedores, EventBridge + Step Functions + Lambda para flujos serverless, y SageMaker o Bedrock cuando el caso requiere ML/IA. Toda mi infra es reproducible con Terraform modular, las imágenes Docker viven en **Amazon ECR** con scanning automático de vulnerabilidades, los manifests Kubernetes los versiono con **Helm + Kustomize** y los pipelines de CI/CD corren en GitHub Actions. Observabilidad la centralizo en CloudWatch Logs/Metrics + Prometheus/Grafana — sin esto, operar a las 3 AM es imposible.

**El proyecto más retador en el que he estado** fue un **sistema de detección de anomalías en streaming desplegado punta a punta en AWS** para una fintech que procesa eventos de transacción crítica.

**Arquitectura general**:

- **Ingestión** desde múltiples productores (apps móviles, web, integraciones B2B) hacia una cola gestionada (Amazon MSK / Kinesis Data Streams).
- **Procesamiento en streaming** sobre **Amazon EKS** (Kubernetes managed) con cluster multi-AZ, node groups Spot + Graviton ARM. Cada worker corre como **pod Kubernetes** definido en un Deployment con readiness/liveness probes, request/limit memory tuning para evitar OOMKill, y un HorizontalPodAutoscaler basado en custom metrics (Kafka consumer lag). Los workers son **imágenes Docker multi-stage** (base distroless `gcr.io/distroless/java21`) que ejecutan Apache Flink manteniendo ventanas deslizantes (5 min, 1 h, 24 h) y aplicando modelos de ML (scikit-learn + LightGBM serializados con joblib, servidos in-process para latencia mínima).
- **Manifests Kubernetes versionados con Helm**: un único chart parametrizado por ambiente (dev/staging/prod) generaba Deployments, Services, ServiceAccounts con IRSA (IAM Roles for Service Accounts) para acceso seguro a S3/RDS sin secretos en YAML, NetworkPolicies para aislar tráfico entre namespaces, y PodDisruptionBudgets que garantizaban no caer bajo N pods durante upgrades del cluster.
- **Persistencia caliente** en Amazon RDS PostgreSQL: las anomalías detectadas se materializaban con FKs hacia las entidades de negocio para que el equipo de fraude pudiera dashboardear y trazar.
- **Persistencia fría** en Amazon S3 particionado por hora (eventos crudos + features computadas + decisiones del modelo) para reentrenos, backtesting y auditoría regulatoria.
- **Workers stateful** auxiliares en EC2 con auto-scaling por SQS depth (un buffer de "rescate" para eventos descartados por back-pressure).
- **Sistema de monitoreo** con CloudWatch Logs + Metrics agregado en Prometheus (vía CloudWatch Exporter) y visualizado en Grafana, con alarmas que pageaban via SNS → PagerDuty. Cada pod exportaba métricas Prometheus en `/metrics` scraped por el operator de Prometheus dentro del mismo cluster EKS.
- **Reportes ejecutivos generados con IA**: un componente Python en Lambda (con SnapStart) tomaba las anomalías de las últimas 24 horas, las correlacionaba con eventos históricos en S3 (Athena), y producía un narrativo en lenguaje natural usando Amazon Bedrock con un LLM gestionado. El reporte se distribuía por email vía SES y se publicaba en Slack vía webhooks.
- **Pipeline de CI/CD para imágenes Docker**: GitHub Actions buildeaba con `docker buildx` (multi-arch amd64+arm64), ejecutaba `trivy` para scanning de vulnerabilidades y bloqueaba PRs con CVEs de severidad alta, firmaba con `cosign` (Sigstore) y publicaba a ECR con tags inmutables (`v{semver}-{git-sha}`). El despliegue en EKS lo orquestaba **Argo CD** en patrón GitOps: cada merge a `main` sincronizaba el chart Helm versionado al namespace correspondiente.
- **Despliegue end-to-end con Terraform** como módulos reutilizables (network, eks, rds, s3, monitoring, secrets) parametrizados por ambiente (dev/staging/prod), con state remoto en S3 + DynamoDB lock y CI/CD en GitHub Actions con `terraform plan` en cada PR y `apply` con approval.

**Retos clave y cómo los resolví**:

1. **Latencia p99 < 2 segundos** desde evento ingerido hasta anomalía publicada en RDS, manejando bursts 10× la carga base sin perder eventos. Resuelto con back-pressure consciente del lag de Kafka (KafkaConsumer.assign + manual commit), autoscaling de pods EKS basado en custom metrics (Kafka consumer lag exportado a CloudWatch via JMX exporter), y un buffer Lambda+SQS de "rescate" para los eventos que el procesamiento principal no alcanzara a digerir.

2. **Ventanas stateful sobrevivientes a fallos de pods**. Apache Flink sobre EKS con checkpointing en S3 cada 30 segundos (backend RocksDB) garantizaba que un pod muerto era reemplazado y restauraba estado en menos de 60 segundos sin perder ventanas en curso.

3. **Reentrenamiento sin downtime**. Implementé shadow mode (modelo nuevo recibe 100% del tráfico de inferencia pero sus decisiones se descartan; solo se comparan métricas vs el modelo activo) durante 7 días, después A/B traffic shifting al 10% por 14 días, y finalmente cutover. SageMaker endpoints multi-variante hicieron este patrón transparente.

4. **Costo del cluster EKS** que arrancó en >$800/mes y bajé a ~$230/mes con tres palancas: (a) node groups Spot en zonas con menor preemption rate, (b) migración de workloads compatibles a Graviton (ARM) por su mejor relación precio/desempeño, (c) reserved instances de 1 año para el baseline de capacidad mínima del scheduler.

5. **Observabilidad multinivel**: dashboards de Grafana por dominio (ingesta, procesamiento, modelo, persistencia) con SLOs por capa, error budgets y burn rate alerting al estilo Google SRE.

6. **Reportes con LLM** con problema de alucinación. Lo resolví con prompting basado en RAG (las anomalías se inyectaban como datos crudos al contexto y el LLM solo "narraba"), validación post-generación contra ground truth, y un human-in-the-loop semanal para feedback de calidad.

El proyecto fue retador porque combinó **streaming real-time + ML en línea + persistencia mixta + IA generativa + IaC end-to-end** con SLAs estrictos. Lo más valioso fue forzar disciplina: cada decisión arquitectónica tenía que justificarse en términos de costo, latencia y mantenibilidad, no solo "porque AWS lo ofrece".

---

## 2. ¿Qué estrategias has aplicado para crear los recursos necesarios en AWS para mantener una arquitectura y pipelines de datos?

Mi regla con la que opero, sin excepción, es **infraestructura inmutable, declarativa y reproducible**. He visto demasiados proyectos morir porque alguien creó algo "rapidito" desde la consola y nadie supo nunca cómo recrearlo. En todos mis despliegues no permito que un recurso productivo exista si no está en código. En la práctica eso se aterriza en seis disciplinas que aplico siempre:

### IaC modular con Terraform

Todo lo creo desde código. La estructura que uso separa **módulos reutilizables** (parametrizados, sin estado de ambiente) de **live environments** que los importan:

```
infra/
├── modules/
│   ├── network/        (VPC, subnets, NAT, endpoints)
│   ├── storage/        (S3 buckets con lifecycle + replication)
│   ├── compute-eks/    (cluster + node groups)
│   ├── data-rds/       (PostgreSQL/MySQL parametrizable)
│   ├── data-redshift/  (Serverless namespace+workgroup)
│   ├── data-glue/      (DB + crawlers + jobs)
│   ├── security-iam/   (roles base por servicio)
│   └── observability/  (CloudWatch + alarmas)
├── live/
│   ├── dev/
│   ├── staging/
│   └── prod/
└── _shared/
    └── tagging.tf      (default_tags)
```

El **state lo guardo remoto** en S3 con encryption + DynamoDB lock + versioning, nunca local. Las cuentas las separo por ambiente vía AWS Organizations + AWS Control Tower; me he quemado antes mezclando dev y prod en la misma cuenta y no quiero repetir esa experiencia.

### Tagging obligatorio

A todo recurso le pongo tags `Project`, `Owner`, `Env`, `CostCenter`, `DataClassification` y los enforzo con AWS Config rules + Tag Policies de Organizations. Sin esto no hay cost allocation honesta ni forma de saber qué recursos tocan PII cuando llega una solicitud GDPR.

### CI/CD para infra

Mi flujo estándar:

- En cada PR contra una rama de live: `terraform plan` automático con comentario en el PR. Si hay cambios destructivos (delete/replace) exijo doble approval — me ha pasado tener apply accidentales y no es divertido.
- Merge a `main` corre `terraform apply` con workflow approval (gate manual para prod).
- **Drift detection diaria** vía `terraform plan -detailed-exitcode` en GitHub Actions; si detecta drift abre un issue automático y me llega notificación.
- El **plan output** se archiva como artefacto para auditoría regulatoria (banca y fintech lo exigen).

### Secret management

- Secrets Manager para credenciales (RDS, APIs externas, OAuth) con **rotación automática cada 30 días** vía Lambda rotators (provistos por AWS o custom).
- Cero static keys en código. Uso IAM Roles for Service Accounts (IRSA) en EKS e instance profiles en EC2.
- Para CI/CD: OIDC trust entre GitHub Actions y AWS IAM. Long-lived access keys son cosa del pasado.

### Cost guardrails desde día uno

Aprendí a la mala que la cuenta de AWS sin guardrails se vuelve facturas sorpresa. Lo que aplico:

- AWS Budgets con alertas al 50/80/100/120% por proyecto y ambiente.
- **Service Control Policies** que bloquean instancias `>xlarge` en dev y previenen apertura de servicios caros (Marketplace, RDS instance classes premium) sin approval.
- Athena workgroups con `bytes_scanned_cutoff_per_query` (típicamente 1 GB en dev) para evitar queries que escaneen TB por error.
- Lifecycle en S3 desde el día uno: Standard → IA tras 30 días → Glacier IR tras 90 → Glacier Deep tras 180.
- Reserved Instances o Savings Plans para baseline predictable; Spot para workloads tolerantes a interrupción (Glue, EKS workers).
- Cost reports semanales tag-based que publico en Slack para que el equipo vea su consumo.

### Observabilidad y operación

- CloudWatch Logs centralizados con retención por dataset (7 días debug, 90 días app, 7 años audit).
- X-Ray tracing en cargas síncronas, OpenTelemetry para tracing distribuido en streaming.
- CloudWatch Synthetics canaries que validan endpoints críticos cada 5 minutos.
- Runbooks en Notion/Confluence linkeados directamente desde cada alarma — si me despiertan a las 3 AM tengo que poder seguir pasos sin pensar.
- Postmortems blameless después de cada incidente sev1/sev2. La regla es: el sistema falló, no la persona.

### Schema migrations seguras

Para OLTP (RDS) y data warehouses (Redshift) lo manejo así:

- **Pattern blue-green** para cambios destructivos: schema nuevo en paralelo, dual-write desde la app, backfill, cutover atómico, retiro del schema viejo. Sin esto se rompe producción tarde o temprano.
- Migrations versionadas con Flyway o Alembic, chequeadas en CI antes de prod.
- Para Glue Catalog: Apache Iceberg o Delta Lake permiten time travel y schema evolution sin reescribir histórico — la diferencia entre poder rollback en 30 segundos vs reprocessar 6 horas de datos.

---

## 3. ¿Qué consideraciones tomarías al decidir entre almacenar datos en Amazon S3, RDS o Redshift?

Para mí las tres son piezas distintas de un stack moderno y **rara vez compiten directamente**. La pregunta no es "cuál es mejor" sino "qué carga voy a poner encima". Cuando me enfrento a la decisión miro seis criterios, en este orden:

| Criterio | Amazon S3 | Amazon RDS | Amazon Redshift |
|---|---|---|---|
| **Patrón de uso** | Archivos y datalake; lectura analítica masiva (Athena, Spark, Presto) | OLTP transaccional; lookups por PK; CRUD intensivo | Analítico complejo; joins multi-tabla con miles de millones de filas; BI |
| **Latencia** | Alta (segundos a minutos vía Athena/Spark) | Baja (milisegundos por consulta indexada) | Media-baja (segundos por query analítica con concurrency scaling) |
| **Costo storage** | $0.023/GB-mes (Standard) hasta $0.001/GB-mes (Glacier Deep) | $0.115/GB-mes (gp3) — 5x más caro que S3 | $0.024/GB-mes (RA3 managed storage) o $0.32/GB-mes (DC2 local) |
| **Costo cómputo** | Pago por query (Athena $5/TB escaneado, S3 select per request) | Instancia 24x7 (~$13-200+/mes según class) | Pago por RPU-hour (serverless) o instancia 24x7 (provisioned) |
| **Esquema** | Schema-on-read; flexible; cualquier formato (CSV, Parquet, JSON, Avro, Iceberg) | Schema-on-write rígido; FKs, constraints, transacciones ACID | Schema-on-write columnar comprimido; ACID con eventual consistency en algunos escenarios |
| **Concurrencia** | Esencialmente infinita (S3 escala horizontalmente) | Limitada por conexiones del instance class | Alta con concurrency scaling (Redshift Serverless escala automáticamente) |

### Mi heurística de decisión

- **Datos crudos, archivos, datalake** → **S3** sin pensarlo dos veces. Es mi zero-friction default. Cualquier herramienta puede leer Parquet/CSV/JSON desde S3, y eso me permite arquitecturas data-mesh con separación clean storage/compute.
- **Transaccional con queries por PK, escritura intensiva, integridad referencial crítica** → **RDS** y casi siempre PostgreSQL (a menos que haya una razón fuerte para otra cosa). Para alta concurrencia escalo con read replicas; para horizontalización seria me voy a Aurora (hasta 128 TB y 15 read replicas).
- **Analítico complejo con joins multi-tabla y BI sobre miles de millones de filas** → **Redshift**, sobre todo `RA3 + managed storage` que separa cómputo de storage. Es donde mejor he visto rendir data marts dimensionales en proyectos reales.

### Casos donde la línea se difumina (los más interesantes)

- **Datasets analíticos < 10 GB diarios**: a mí me funciona mejor **Athena sobre S3** que Redshift. Sin clúster que mantener, pago solo por query, y para volúmenes pequeños el resultado en costo es mucho mejor.
- **OLTP con queries analíticas ocasionales**: prefiero quedarme en **Aurora PostgreSQL** con `pg_partman` y particiones por fecha. Una sola base, dos casos de uso, menos cosas que romper.
- **Cambio frecuente de esquema**: voy a **S3 + Iceberg/Delta Lake** sobre Athena/EMR/Glue. Te da ACID + time travel + schema evolution sin tener que escribir DDL formal cada vez.
- **Alta cardinalidad de queries ad-hoc por analistas**: depende del patrón de uso — **Redshift Serverless** si el uso es continuo, **Athena workgroups** si es esporádico.

### Anti-patrones en los que YO ya caí (y por eso los evito)

- Usé S3+Athena para alimentar un dashboard que refrescaba cada minuto. Cost trap brutal: terminé pagando más en queries Athena que lo que habría costado un Redshift Serverless. Lección: si el patrón es high-frequency, materializo agregados en Redshift o cacheo en RDS/DynamoDB.
- Vi un equipo usar RDS como datalake metiendo JSONB blobs gigantes. Costo de storage 5-10× lo que sería en S3 y queries lentísimas. Cuando me tocó intervenir, lo movimos a S3 y dejamos RDS solo para metadata.
- Usar Redshift para OLTP me ha picado más de una vez. ACID con concurrencia limitada — no es lo que ese servicio está optimizado a hacer.

### Lo que decidí en este proyecto (Ej.1)

- **S3** → datalake (raw + processed) porque almacena CSVs/Parquet de forma barata y permite que Glue, Athena y Redshift lean sin acoplamiento.
- **RDS PostgreSQL** → fuente OLTP simulada del sistema transaccional, donde el modelo dimensional tiene FKs y constraints, y donde el backend Spring Boot lee con queries indexadas a través de HikariCP.
- **Redshift Serverless** → como bonus para demostrar el data warehouse layer, con `COPY` desde S3 processed.

---

## 4. ¿Qué beneficios y desventajas ves al utilizar AWS Glue en comparación con Lambda o Step Functions para orquestación ETL?

Para mí los tres resuelven problemas que se tocan pero no son intercambiables. Cada uno tiene un sweet spot bien definido y aprendí a no forzarlos fuera de él. Te cuento qué uso cada uno y para qué.

### AWS Glue

**Lo que me gusta**:

- Spark managed sin necesidad de provisionar EMR. Crea, ejecuta y limpia el cluster automáticamente.
- **Crawlers automáticos** que descubren esquemas y los catalogan en Glue Data Catalog (compartido con Athena, Redshift Spectrum, EMR).
- Soporta **PySpark, Scala, Python shell** y SQL (Glue Studio).
- **Job bookmarks**: procesamiento incremental sin código (Glue recuerda qué archivos ya procesó).
- Dynamic Frames con resolución automática de tipos heterogéneos (útil para datos sucios).
- Integración nativa con Lake Formation para gobierno fine-grained.
- Glue Workflows para orquestar dependencias entre jobs sin necesidad de Step Functions.

**Lo que me molesta**:

- **Cold start de 1-2 minutos** por job (incluso "G.1X" 2 workers). Para latencia interactiva, descartado.
- **Costo relativamente alto**: ~$0.44/DPU-hr con mínimo 1 minuto de billing. Un job de 30 segundos cuesta lo mismo que uno de 10 minutos — para cargas pequeñas y frecuentes me sale mucho mejor Lambda.
- **Testing local doloroso**: simular el entorno Glue requiere imágenes Docker oficiales pesadas o mocks artesanales. Yo termino escribiendo el código en PySpark estándar y solo agrego las APIs de Glue al final.
- **Lock-in**: el código depende de las APIs propietarias de Glue (DynamicFrame, glueContext) que no son portables a Spark estándar sin refactor. Me ha tocado arrancar Glue para sacar workloads a EMR.
- **Debugging incómodo**: logs van a CloudWatch con delay, sin REPL interactivo como tendrías en Databricks o EMR Notebooks.

### AWS Lambda

**Lo que me gusta**:

- **Cold start sub-segundo** (con SnapStart en Java/Python bajo 100 ms en warm).
- **Pago por uso**: ~$0.20 por 1M invocaciones + cómputo. Para jobs pequeños frecuentes es la opción más barata por orden de magnitud — sin debate.
- Fácil de testear (unit tests, SAM Local, contenedores). Mis Lambdas las pruebo igual que cualquier función Python.
- Deploy rápido — función nueva en segundos.
- **Triggers ricos**: S3 events, EventBridge, SQS, Kinesis, API Gateway. Puedo wirear flujos event-driven completos sin escribir un orquestador.

**Lo que me molesta**:

- **Límite duro de 15 minutos de ejecución**. Para jobs largos, ni intentar.
- **Memoria máxima 10 GB**, efímera (sin disco persistente más allá de los 10 GB de `/tmp`).
- **No nativo para distribuido**. Procesar 1 TB me obligaría a fan-out manual (Lambda → SQS → más Lambdas) que termina pareciéndose a un Map/Reduce de pobre.
- Cold start sigue penalizando JVM y .NET (mitigable con SnapStart, pero no gratis).
- Estado entre invocaciones requiere external (DynamoDB, S3, EFS) — costo cognitivo y de infra.

### AWS Step Functions

**Lo que me gusta**:

- **Orquestación visual** con state machines, retries declarativos por estado, error handling estructurado. Las uso para workflows que tienen que sobrevivir auditoría.
- **Distributed Map** permite paralelismo masivo (hasta 10K iteraciones concurrentes) con back-pressure manejado por mí.
- **Integración nativa con casi todos los servicios AWS** (200+ acciones SDK directas) sin necesidad de Lambdas intermedias — me ahorra mucho código glue.
- **Workflows largos** (hasta 1 año) y de tipo Express (workflows cortos sub-segundo y baratísimos).
- Debugging excelente: cada ejecución persiste con cada step input/output visible. Cuando algo falla, abro la ejecución y veo exactamente dónde.
- Soporta **callbacks asíncronos** (waitForTaskToken) ideales para human-in-the-loop o esperar webhooks externos.

**Lo que me molesta**:

- **Costo por transición de estado** se dispara en flujos largos: Standard a $0.025/1K transiciones suma rápido si hay loops grandes. Express es más barato pero limitado a 5 minutos.
- **DSL JSON / Amazon States Language es verboso**. Lo alivio con CDK, AWS SAM o herramientas como Sketch / stedi, pero escribirlo a mano es doloroso.
- Para lógica condicional compleja la state machine se vuelve difícil de leer. Cuando me pasa, extraigo la lógica a Lambdas y dejo el state machine como pegamento.

### Mi heurística

- **ETL > 10 GB con joins/agregaciones distribuidas** → me voy a **Glue** (Spark managed).
- **ETL/transformación < 1 GB con baja latencia, event-driven** → **Lambda** (S3 trigger + procesar + emitir downstream).
- **Workflow con muchos pasos condicionales, retries por step, paralelismo masivo, integración multi-servicio** → **Step Functions** orquestando Lambdas y/o Glue jobs.
- **Streaming continuo con state windows** → ninguno de los tres: para eso uso **Kinesis Data Analytics for Flink** o monto **MSK + Flink en EKS**.

### Ejemplos concretos de cosas que he montado

- **Pipeline diario de 50 GB de logs S3** → Glue Spark (transform + agregar + escribir Parquet particionado).
- **Validación de schema en cada upload S3** (event trigger) → Lambda Python ligera con jsonschema, latencia bajo 100 ms.
- **Pipeline ML con preprocessing → training → evaluation → deployment** → Step Functions orquestando SageMaker Pipelines + Lambdas de validación intermedia.
- **Replicación CDC RDS → S3** → DMS task continuo (no Glue ni Lambda); más barato y robusto que armarlo a mano. Me ha pasado intentar reinventar esto y siempre salgo perdiendo.
- **Reportes nightly por país (50 países en paralelo)** → Step Functions Distributed Map disparando 50 Glue jobs concurrentes.

---

## 5. ¿Cómo garantizarías la integridad y seguridad de los datos de un datalake construido en Amazon S3?

Para mí esto es **defensa en profundidad** y se ataca en tres capas independientes pero conectadas: integridad, seguridad y compliance. Te cuento cómo lo abordo en cada una, basado en lo que me ha funcionado.

### Integridad

Lo primero que hago es **particionar por fecha de carga**. Cada extracción genera un directorio nuevo (`loaded_at=YYYY-MM-DDTHH/`) que es **idempotente**: reprocesarlo no contamina el histórico. Esto también me da roll-forward — descartar la última partición sin perder lo viejo. Es la diferencia entre rebuild de 5 minutos y reprocessar 6 meses.

Para detectar corrupción uso **hashing en metadata**. Cada objeto S3 lleva ETag (MD5 para objetos pequeños, multipart hash para grandes) más un `x-amz-checksum-sha256` que calculo al upload. Mis pipelines downstream verifican antes de procesar. Para datasets críticos pongo SHA-256 en metadata propia y reconcilio contra `aws s3api get-object-attributes`.

**Versionado del bucket** lo habilito en zona raw y curated (no en zona temp porque sería tirar plata). Lifecycle a Glacier para versiones antiguas evita que el costo se descontrole.

**Formato de tabla con garantías ACID**: cuando el caso lo permite uso **Apache Iceberg o Delta Lake** sobre S3. Me da UPSERT, DELETE, MERGE, time travel (consultar el lake como estaba ayer), schema evolution segura. Glue 4.0 y Athena v3 los soportan nativos. La diferencia operativa es enorme: poder rollback en segundos vs reprocessar horas.

**Schema enforcement vía Glue Data Catalog**: configuro crawlers para detectar drift y mis jobs están seteados para fallar (no autoreparar) si aparecen columnas inesperadas. Cambios de esquema obligan pull request — sin atajos.

Para **data quality automática** he usado Great Expectations, Soda Core y AWS Deequ al final de cada job. Las categorías que cubro: completitud, unicidad, integridad referencial, dominios válidos, rangos numéricos, cobertura temporal y sanidad lógica de negocio. **Tests críticos bloquean el pipeline** (la materia prima no llega a downstream); tests medium solo emiten warning a CloudWatch + Slack para no bloquear por cosas menores.

Para datos irreemplazables: **cross-region replication** (S3 CRR) hacia un bucket en región distinta. Mi RPO objetivo cambia según criticidad — típicamente 15 minutos.

Para **lineage** uso Glue Data Catalog tags + DataHub o AWS Glue Data Lineage. Me permite responder rápido cuando alguien pregunta "de dónde salió este dato".

### Seguridad

Para mí el cifrado at-rest es **no negociable**: SSE-S3 (AES-256) por defecto en todo bucket, SSE-KMS con CMK customer-managed para datasets sensibles. La CMK con rotation anual + key policy restrictiva. La diferencia importa: con SSE-KMS puedo revocar acceso revocando permisos de la key sin tocar bucket policies, y eso me ha salvado en incidentes.

**Cifrado in-transit TLS 1.2+** enforced via bucket policy:

```json
{
  "Effect": "Deny",
  "Principal": "*",
  "Action": "s3:*",
  "Resource": ["arn:aws:s3:::my-bucket/*"],
  "Condition": { "Bool": { "aws:SecureTransport": "false" } }
}
```

**Block all public access** habilitado por default a nivel cuenta y bucket. Excepción explícita y documentada solo para buckets de "publicación" (PDFs, exports).

**Bucket policies con principio "deny if not my account"**:

```json
{ "Effect": "Deny", "Principal": "*", "Action": "s3:*",
  "Resource": ["arn:aws:s3:::my-bucket/*"],
  "Condition": { "StringNotEquals": { "aws:PrincipalAccount": "123456789012" } } }
```

**Lake Formation** para fine-grained: column-level + row-level security + LF-Tags. Reemplaza bucket policies como mecanismo principal de access control para datasets analíticos. Permite, por ejemplo, dar a un analista acceso a la columna `cliente.ciudad` pero no a `cliente.id_externo`.

**IAM con principio de menor privilegio**:

- Roles separados por servicio (`glue-service-role`, `ec2-backend-role`, `redshift-spectrum-role`).
- Sin `*:*` ni siquiera en dev.
- Permission boundaries para limitar el blast radius de roles que crean roles.
- IAM Access Analyzer escaneando bucket policies y roles cada semana.

**VPC Endpoints** (Gateway para S3, Interface para Glue/Athena/Secrets Manager) para que el tráfico nunca salga del backbone AWS. Bucket policies con `aws:SourceVpc` enforcement.

**Auditoría con CloudTrail**: data events habilitados sobre buckets críticos (registra cada GetObject/PutObject), no solo management events. Logs almacenados en cuenta de seguridad separada (con bucket policy que solo permite append, no delete) para evitar que un atacante borre su rastro.

**S3 Access Logs** complementarios para análisis forense.

**GuardDuty** + **GuardDuty Malware Protection for S3** detectan anomalías (acceso desde IPs sospechosas, exfiltración masiva, ransomware patterns).

**Macie** para descubrir y clasificar PII automáticamente. Política: cualquier dataset clasificado como "Sensitive" se mueve automáticamente a un bucket encriptado con CMK + Lake Formation tag-based access.

**AWS Backup** con cold storage (Glacier Deep Archive) para DR. Pruebas de restore trimestrales.

### Compliance y gobernanza

**Tag-based classification** consistente: `DataClassification ∈ {Public, Internal, Confidential, PII}` enforced por AWS Config rule.

**DLP automatizado**: Lambda triggered por Macie findings que tokeniza PII (email, ID, teléfono) usando AWS Tokenization vault o solución custom antes de exponer en zonas analíticas. Los datos originales viven cifrados con CMK distinta.

**Data residency** con bucket per región y bucket policies que rechazan replication cross-region a regiones no autorizadas.

**Right to be forgotten (GDPR / Ley 1581 Colombia)**: endpoint que dispara batch de borrado en RDS + en S3. Iceberg/Delta permiten borrado puntual sin reescribir partición completa.

**Audit log review semanal** + alertas en tiempo real (EventBridge → SNS → PagerDuty) para eventos críticos: bucket policy changes, IAM permission elevation, access denied desde IP nueva, etc.

**Penetration testing trimestral** sobre la zona pública. **Bug bounty interno** habilitado.

**Documentación viva**: arquitectura, controles y residual risk en Confluence/Notion linkeado desde el README del repo. Revisión anual con el equipo de seguridad.

### Lo que apliqué en este proyecto (Ej.1)

Para mantenerlo proporcional al alcance, aterricé un subset:

- SSE-AES256 en los 6 buckets.
- Block public access en 5 de 6 (el bucket `prueba-aws-public` es la excepción explícita para servir el PDF entregable).
- Lake Formation con LF-Tags `Sensitivity = {Public, Internal, PII}` y `Domain = {Provider, Client, Transaction, Reference}`. Permisos por tag al rol del backend EC2 limitando a `Sensitivity ∈ {Public, Internal}`.
- IAM con 4 roles separados (Glue, EC2 backend, Redshift, Lake Formation service).
- Audit log local en RDS (`audit.carga_log`) + CloudWatch logs de los Glue jobs.
- Secrets Manager para credenciales del backend (rotables manualmente).
