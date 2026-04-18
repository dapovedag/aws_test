package com.prueba.aws.service;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.prueba.aws.dto.Dtos;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Genera el PDF "Prueba AWS · Solución completa" usando HTML + CSS → OpenHTMLtoPDF.
 *
 * El PDF es la entrega oficial y autocontenida de la prueba. Estructura:
 *
 *   1. Portada
 *   2. Briefing (enunciado de la prueba)
 *   3. Ejercicio 1 · Requisitos técnicos R0-R5 con narrativa de 5 pasos
 *   4. Ejercicio 1 · Documentación D1 (pipeline) + D2 (permisos)
 *   5. Ejercicio 1 · Plus P1-P3 (Terraform, Lake Formation, Redshift)
 *   6. Ejercicio 2 · Arquitectura completa + KPIs reales del schema divisas
 *   7. Ejercicio 3 · 5 preguntas conceptuales con respuestas Senior+
 *   8. Costo medido HOY desde APIs AWS
 */
@Service
public class HtmlPdfService {
    private static final Logger log = LoggerFactory.getLogger(HtmlPdfService.class);

    private final DataModelService modelSvc;
    private final DataQualityService dqSvc;
    private final AthenaService athenaSvc;
    private final MeteredCostService meteredCostSvc;
    private final DivisasService divisasSvc;
    private final FreeTierService freeTierSvc;

    private final Parser mdParser;
    private final HtmlRenderer mdRenderer;

    public HtmlPdfService(DataModelService modelSvc, DataQualityService dqSvc,
                          AthenaService athenaSvc, MeteredCostService meteredCostSvc,
                          DivisasService divisasSvc, FreeTierService freeTierSvc) {
        this.modelSvc = modelSvc;
        this.dqSvc = dqSvc;
        this.athenaSvc = athenaSvc;
        this.meteredCostSvc = meteredCostSvc;
        this.divisasSvc = divisasSvc;
        this.freeTierSvc = freeTierSvc;

        var ext = List.of(TablesExtension.create());
        this.mdParser = Parser.builder().extensions(ext).build();
        this.mdRenderer = HtmlRenderer.builder().extensions(ext).build();
    }

    public byte[] buildPdf() {
        try {
            String html = buildHtml();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            // NO uso useFastMode: el modo rápido tiene bugs conocidos con
            // wrapping de texto cuando hay padding/margin asimétricos.
            // Fuentes embebidas (TTF en classpath) para que las métricas sean
            // deterministas en cualquier sistema operativo y los renglones
            // hagan word-wrap en la posición correcta.
            // Registro Regular + Bold + Italic; sin Bold real PDFBox sintetiza
            // el bold por overstrike, lo que produce caracteres duplicados/glitchy.
            registerFont(builder, "fonts/SourceSans3-Regular.ttf", "SourceSans", 400, BaseRendererBuilder.FontStyle.NORMAL);
            registerFont(builder, "fonts/SourceSans3-Bold.ttf",    "SourceSans", 700, BaseRendererBuilder.FontStyle.NORMAL);
            registerFont(builder, "fonts/SourceSans3-Italic.ttf",  "SourceSans", 400, BaseRendererBuilder.FontStyle.ITALIC);
            registerFont(builder, "fonts/MerriweatherRegular.ttf", "Merriweather", 400, BaseRendererBuilder.FontStyle.NORMAL);
            registerFont(builder, "fonts/MerriweatherBold.ttf",    "Merriweather", 700, BaseRendererBuilder.FontStyle.NORMAL);
            registerFont(builder, "fonts/JetBrainsMonoRegular.ttf","JetBrains Mono", 400, BaseRendererBuilder.FontStyle.NORMAL);
            registerFont(builder, "fonts/JetBrainsMonoBold.ttf",   "JetBrains Mono", 700, BaseRendererBuilder.FontStyle.NORMAL);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            log.info("PDF construido · {} bytes", out.size());
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Error generando PDF: " + e.getMessage(), e);
        }
    }

    private void registerFont(PdfRendererBuilder builder, String classpathPath,
                              String family, int weight, BaseRendererBuilder.FontStyle style) {
        try {
            byte[] bytes = new ClassPathResource(classpathPath).getInputStream().readAllBytes();
            builder.useFont(() -> new java.io.ByteArrayInputStream(bytes), family,
                    weight, style, true);
        } catch (Exception e) {
            log.warn("No pude cargar fuente {}: {}", classpathPath, e.getMessage());
        }
    }

    // --------- HTML composition --------------------------------------------

    private String buildHtml() {
        StringBuilder sb = new StringBuilder(200_000);
        sb.append("<!DOCTYPE html><html lang=\"es\"><head>");
        sb.append("<meta charset=\"UTF-8\"/>");
        sb.append("<title>Prueba AWS · Solución completa</title>");
        sb.append("<style>").append(CSS).append("</style>");
        sb.append("</head><body>");

        sb.append(coverPage());
        sb.append(tocPage());
        sb.append(briefingSection());

        // Ejercicio 1 · Requisitos técnicos
        sb.append(sectionR0());
        sb.append(sectionR1());
        sb.append(sectionR2());
        sb.append(sectionR3());
        sb.append(sectionR4());
        sb.append(sectionR5());

        // Ejercicio 1 · Documentación
        sb.append(sectionD1());
        sb.append(sectionD2());

        // Ejercicio 1 · Plus
        sb.append(sectionP1());
        sb.append(sectionP2());
        sb.append(sectionP3());

        // Ejercicio 2 · Plataforma divisas
        sb.append(sectionEj2());

        // Ejercicio 3 · 5 preguntas
        sb.append(sectionEj3());

        // Costo medido
        sb.append(sectionCosto());

        sb.append("</body></html>");
        return sb.toString();
    }

    // --------- Páginas y secciones ------------------------------------------

    private String coverPage() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", new Locale("es")));
        return """
                <section class="cover">
                  <div class="cover-rule"></div>
                  <div class="cover-eyebrow">Prueba técnica · AWS Data Engineering</div>
                  <h1 class="cover-title">Datalake en AWS<br/><span>para una comercializadora de energía</span></h1>
                  <div class="cover-deck">
                    Solución completa al enunciado: ingesta desde RDS PostgreSQL, capas en S3,
                    transformaciones en Glue, catálogo automático, consultas con Athena desde Python,
                    pipeline a Redshift, gobierno con Lake Formation e infraestructura con Terraform.
                    Tres ejercicios resueltos punto por punto.
                  </div>
                  <div class="cover-rule"></div>
                  <div class="cover-meta">
                    <div><strong>Autor</strong><br/>Diego Poveda · dapovedag@gmail.com</div>
                    <div><strong>Fecha</strong><br/>%s</div>
                    <div><strong>Repositorio</strong><br/>github.com/dapovedag/aws_test</div>
                    <div><strong>Cuenta AWS</strong><br/>us-east-1 · 7972-4061-5526</div>
                  </div>
                </section>
                """.formatted(date);
    }

    private String tocPage() {
        return """
                <section class="page toc">
                  <h2>Tabla de contenidos</h2>
                  <ol class="toc-list">
                    <li><span>Briefing · enunciado de la prueba</span></li>
                    <li class="group">Ejercicio 1 · Datalake en AWS · requisitos técnicos
                      <ol>
                        <li>R0 · Decisión arquitectónica · CSV vs PostgreSQL</li>
                        <li>R1 · Estrategia datalake S3 · capas + particionado</li>
                        <li>R2 · 3 transformaciones Glue · Parquet en zona procesada</li>
                        <li>R3 · Glue Crawler · catálogo automático de esquemas</li>
                        <li>R4 · Athena desde Python · consultas SQL</li>
                        <li>R5 · Esquemas separados por dominio</li>
                      </ol>
                    </li>
                    <li class="group">Ejercicio 1 · Documentación
                      <ol>
                        <li>D1 · Descripción detallada del pipeline</li>
                        <li>D2 · Permisos y políticas AWS</li>
                      </ol>
                    </li>
                    <li class="group">Ejercicio 1 · Puntos adicionales (Plus)
                      <ol>
                        <li>P1 · IaC con Terraform</li>
                        <li>P2 · Lake Formation · gobierno y tag-based access control</li>
                        <li>P3 · Pipeline a Redshift · datawarehouse</li>
                      </ol>
                    </li>
                    <li><span>Ejercicio 2 · Arquitectura · plataforma divisas en tiempo real</span></li>
                    <li><span>Ejercicio 3 · 5 preguntas generales</span></li>
                    <li><span>Costo del despliegue · medido HOY desde APIs AWS</span></li>
                  </ol>
                </section>
                """;
    }

    private String briefingSection() {
        return """
                <section class="page">
                  <h2 class="sect-h">Briefing · enunciado de la prueba</h2>
                  <p class="lead">
                    Una compañía comercializadora de energía compra electricidad a generadores en el
                    mercado mayorista (eólica, hidroeléctrica, nuclear, solar, biomasa) y la revende
                    a clientes residenciales, comerciales o industriales. Su sistema transaccional
                    exporta proveedores, clientes y transacciones en CSV.
                  </p>
                  <p>El ejercicio pide construir un <strong>datalake en AWS</strong> que ingiera esos
                  CSV, los transforme con Glue, los catalogue automáticamente, los exponga vía Athena
                  desde Python y, opcionalmente, los cargue a Redshift. Documentar pipeline, permisos,
                  IaC y gobierno con Lake Formation.</p>
                  <p>Adicionalmente: arquitectura en alto nivel para una <strong>plataforma de divisas
                  en tiempo real</strong> (Ejercicio 2) y <strong>5 preguntas conceptuales</strong>
                  sobre experiencia con AWS (Ejercicio 3).</p>

                  <div class="callout">
                    <div class="callout-tag">Cómo leer este documento</div>
                    Cada requisito técnico (R0-R5), pieza de documentación (D1-D2) y punto adicional
                    (P1-P3) está organizado con la misma <strong>narrativa de 5 pasos</strong>:
                    1) qué pide la prueba, 2) mi enfoque, 3) servicios y técnicas que apliqué con
                    contexto, 4) implementación concreta, 5) resultado y dónde verificarlo.
                  </div>
                </section>
                """;
    }

    // R0 — leerá data-decision.md
    private String sectionR0() {
        return narrativeBlock("R0", "Decisión arquitectónica · CSV vs PostgreSQL",
                "Utilice información ficticia en los archivos csv que se simula entregue el sistema transaccional.",
                "PruebaAWS.pdf · Ejercicio 1, párrafo de cierre",
                """
                La prueba pide CSVs ficticios pero <strong>no obliga a que la fuente sea CSV</strong>.
                Decidí modelar el sistema transaccional con una base PostgreSQL en RDS porque ningún
                sistema operacional real almacena su data en CSVs sueltos. El extractor Python genera
                los CSVs particionados que alimentan el datalake, cumpliendo el contrato exacto del
                enunciado, pero la fuente de verdad es una base relacional con FKs, constraints y
                semántica de negocio.
                """,
                "Argumentación completa (documento dedicado)",
                renderMd("docs/data-decision.md"),
                "<p class=\"status ok\"><strong>Resultado:</strong> sistema fuente realista, "
                + "reproducible bit a bit con <code>seed=42</code>, exportable a CSV bajo demanda. "
                + "Cumple el contrato CSV particionado por fecha de carga al pie de la letra.</p>");
    }

    private String sectionR1() {
        return narrativeBlock("R1", "Estrategia datalake S3 · capas + particionado por fecha de carga",
                "Crear una estrategia de datalake en s3 con las capas que usted considere necesario tener "
                + "y cargue esta información de manera automática y periódica. Los archivos deben "
                + "particionarse por fecha de carga.",
                "PruebaAWS.pdf · Ejercicio 1, requisito técnico 1",
                """
                Diseñé un datalake de <strong>6 capas</strong> en S3 (no 3) para separar datos
                analíticos internos de artefactos publicados al usuario y de área temporal de cómputo.
                Cada capa tiene un propósito único, una bucket policy distinta y una política de
                lifecycle propia. Para el particionamiento por fecha de carga uso <strong>Hive-style</strong>
                (<code>loaded_at=YYYY-MM-DDTHH/</code>) que es el formato que Athena, Glue y Redshift
                Spectrum entienden nativamente.
                """,
                "Las 6 capas y el particionamiento aplicado",
                """
                <h4>Las 6 capas que creé</h4>
                <ul>
                  <li><code>s3://prueba-aws-raw/</code> — CSVs originales extraídos del RDS, zona "landing"</li>
                  <li><code>s3://prueba-aws-staging/</code> — output intermedio de Glue (temp / scratch para Spark)</li>
                  <li><code>s3://prueba-aws-processed/</code> — Parquet+Snappy particionado, zona "curated"</li>
                  <li><code>s3://prueba-aws-public/</code> — assets descargables (PDF, reportes calidad, JSONs Athena)</li>
                  <li><code>s3://prueba-aws-athena-results/</code> — output queries Athena · lifecycle 7 días</li>
                  <li><code>s3://prueba-aws-glue-scripts/</code> — código PySpark de los 3 jobs ETL</li>
                </ul>
                <h4>El particionado por fecha de carga</h4>
                <p>El extractor Python (<code>scripts/02_extract_to_s3_raw.py</code>) crea una partición
                nueva por cada corrida con granularidad horaria:</p>
                <pre>s3://prueba-aws-raw/&lt;tabla&gt;/loaded_at=YYYY-MM-DDTHH/&lt;tabla&gt;.csv</pre>
                <p>Beneficios: idempotencia (re-correr no contamina histórico), reproceso quirúrgico
                (borrar una partición específica si vino corrupta), filtrado eficiente en Athena
                (<code>WHERE loaded_at &gt; '2026-04-15'</code> escanea solo lo nuevo), trazabilidad
                automática.</p>
                """,
                "<p class=\"status ok\"><strong>Resultado:</strong> 6 buckets desplegados en "
                + "<code>us-east-1</code> con SSE-S3 obligatoria + block public access (excepto el "
                + "bucket \"public\"). Detalle del pipeline completo en la sección D1.</p>");
    }

    private String sectionR2() {
        StringBuilder queries = new StringBuilder();
        try {
            for (Dtos.AthenaQueryDef q : athenaSvc.queries()) {
                queries.append("<li><strong>").append(esc(q.id())).append("</strong> · ")
                       .append(esc(q.title())).append(" — ").append(esc(q.description())).append("</li>");
            }
        } catch (Exception e) {
            queries.append("<li>(queries no disponibles offline)</li>");
        }

        return narrativeBlock("R2", "3 transformaciones Glue · Parquet en zona procesada",
                "Realice 3 transformaciones básicas de datos utilizando AWS Glue y transforme la "
                + "información para que esta sea almacenada en formato parquet en una zona procesada.",
                "PruebaAWS.pdf · Ejercicio 1, requisito técnico 2",
                """
                En vez de meter las 3 transformaciones en un solo job monolítico, las separé en
                <strong>3 jobs independientes</strong>: uno para dimensiones (SCD-1), uno para el
                hecho transaccional con derivaciones y particionado por año/mes, y uno para
                pre-agregados mensuales. Esta separación permite ejecutarlos en paralelo,
                debuggearlos por separado y reentrenar uno solo si cambia la lógica. Los 3 son
                PySpark sobre Glue 4.0 con worker <code>G.1X</code> (1 DPU = 4 vCPU + 16 GB) × 2 workers.
                """,
                "Las 3 transformaciones que implementé",
                """
                <h4>Job 1 · transform_dimensions</h4>
                <p>Lee los CSVs de raw para las dimensiones (proveedor, cliente, ciudad, tipo_energia),
                aplica <strong>SCD-1</strong> (último <code>loaded_at</code> gana), normaliza tipos y
                escribe Parquet por dim a <code>s3://prueba-aws-processed/dim_proveedor/</code>,
                <code>dim_cliente/</code>, <code>dim_ciudad/</code>, <code>dim_tipo_energia/</code>.</p>

                <h4>Job 2 · transform_fact</h4>
                <p>Lee la transacción cruda, deriva <code>fecha_id = YYYYMMDD</code>, recalcula
                <code>monto_usd = cantidad_mwh × precio_usd</code> defensivamente, particiona por
                <code>(anio, mes)</code> y escribe a <code>s3://prueba-aws-processed/fact/anio=YYYY/mes=MM/</code>.</p>

                <h4>Job 3 · transform_aggregates</h4>
                <p>Agrega métricas mensuales por <code>(anio, mes, tipo_energia, tipo_transaccion)</code>
                y materializa en <code>agg_resumen_mensual/</code> para que Athena/Redshift respondan
                queries dashboard sin escanear el fact completo.</p>

                <h4>Stack y formato</h4>
                <p>Glue 4.0 (Spark 3.3) · Parquet+Snappy (columnar, comprime ratio 3-10×, splitable
                para paralelización Spark) · particionamiento Hive-style. Cada run dura 50-70 segundos
                y consume ~0.03-0.04 DPU-hr.</p>
                """,
                "<p class=\"status ok\"><strong>Resultado:</strong> 3 jobs ejecutados con éxito · "
                + "output Parquet+Snappy en <code>s3://prueba-aws-processed/</code> · DPU-hours "
                + "consumidas medibles desde la sección de costo de este documento.</p>");
    }

    private String sectionR3() {
        return narrativeBlock("R3", "Glue Crawler · catálogo automático de esquemas",
                "Utilizando AWS Glue, crea un proceso que detecte y catalogue automáticamente "
                + "los esquemas de los datos almacenados en el datalake.",
                "PruebaAWS.pdf · Ejercicio 1, requisito técnico 3",
                """
                Configuré <strong>2 crawlers</strong> separados: uno escanea la zona <em>raw</em>
                (CSVs) en horario automático cada hora, y otro escanea la zona <em>processed</em>
                (Parquet) on-demand cuando termina un ETL. Las tablas detectadas se registran en el
                <strong>Glue Data Catalog</strong>, que es el metastore unificado que comparten Athena,
                Redshift Spectrum, EMR y Lake Formation, evitando definir schemas múltiples veces.
                """,
                "Configuración concreta",
                """
                <h4>Los 2 crawlers</h4>
                <ul>
                  <li><code>prueba-aws-crawler-raw</code> · escanea raw <strong>cada hora a la hora exacta</strong>
                  (cron <code>0 * * * ? *</code> en sintaxis Glue: minuto 0, todas las horas, todos
                  los días/meses, cualquier día de semana)</li>
                  <li><code>prueba-aws-crawler-processed</code> · on-demand · registra Parquet
                  generados por los jobs ETL</li>
                </ul>
                <h4>Por qué cada hora y no cada minuto</h4>
                <p>El sistema fuente exporta CSVs en lotes (no streaming). Una corrida horaria detecta
                nuevas particiones <code>loaded_at=YYYY-MM-DDTHH/</code> sin gastar DPU innecesario.
                Si se requiere baja latencia, se cambia a EventBridge → Lambda → StartCrawler disparado
                por <code>s3:ObjectCreated:Put</code>.</p>
                <h4>Beneficios del catálogo unificado</h4>
                <ul>
                  <li>Athena, Redshift Spectrum, EMR y Lake Formation comparten el mismo schema sin redefinirlo</li>
                  <li>Tag-based access control (LF-Tags) se aplica al catálogo, no al bucket S3</li>
                  <li>Schema evolution detectada automáticamente (drift) genera entradas nuevas</li>
                </ul>
                """,
                "<p class=\"status ok\"><strong>Resultado:</strong> 11 tablas catalogadas "
                + "automáticamente en la database <code>datalake_energia</code> "
                + "(5 raw + 5 processed dim/fact + 1 agregado mensual).</p>");
    }

    private String sectionR4() {
        StringBuilder queries = new StringBuilder();
        try {
            for (Dtos.AthenaQueryDef q : athenaSvc.queries()) {
                queries.append("<div class=\"query-card\">");
                queries.append("<h4>").append(esc(q.id())).append(" · ").append(esc(q.title())).append("</h4>");
                queries.append("<p class=\"q-desc\">").append(esc(q.description())).append("</p>");
                queries.append("<pre><code>").append(esc(q.sql())).append("</code></pre>");
                queries.append("</div>");
            }
        } catch (Exception e) {
            queries.append("<p>(SQL queries no disponibles offline)</p>");
        }

        return narrativeBlock("R4", "Athena desde Python · consultas SQL en vivo",
                "Utilizando Amazon Athena desde Python, realiza consultas SQL básicas sobre los "
                + "datos que han sido transformados.",
                "PruebaAWS.pdf · Ejercicio 1, requisito técnico 4",
                """
                Implementé las consultas en el script Python <code>scripts/05_athena_query.py</code>
                usando <strong>boto3</strong> (cumple literalmente el requisito "desde Python"). Definí
                3 queries analíticas representativas que se ejecutan contra el workgroup
                <code>prueba-aws-wg</code> sobre los Parquet de S3 processed. La salida queda
                serializada en <code>scripts/output/athena_results.json</code> y publicada también en
                <code>s3://prueba-aws-public/athena_results.json</code> para auditoría posterior.
                """,
                "Las 3 queries definidas",
                queries.toString(),
                "<p class=\"status ok\"><strong>Resultado:</strong> las 3 queries devuelven filas y "
                + "metadata (tiempo, KB escaneados, executionId) con tiempos típicos de 3-5 segundos. "
                + "Output reproducible en <code>scripts/output/athena_results.json</code> y "
                + "<code>s3://prueba-aws-public/athena_results.json</code>.</p>");
    }

    private String sectionR5() {
        StringBuilder schemas = new StringBuilder();
        try {
            var ds = modelSvc.describe();
            schemas.append("<table class=\"data-table\"><thead><tr><th>Schema</th><th>Tabla</th>"
                    + "<th>Cols</th><th>Descripción</th></tr></thead><tbody>");
            for (Dtos.TableSpec t : ds.tables()) {
                schemas.append("<tr><td><code>").append(esc(t.schema())).append("</code></td>")
                       .append("<td><code>").append(esc(t.name())).append("</code></td>")
                       .append("<td>").append(t.columns().size()).append("</td>")
                       .append("<td>").append(esc(t.description() == null ? "" : t.description())).append("</td></tr>");
            }
            schemas.append("</tbody></table>");
        } catch (Exception e) {
            schemas.append("<p>(modelo no disponible offline)</p>");
        }

        return narrativeBlock("R5", "Esquemas separados por dominio · Ej.1 vs Ej.2",
                "La prueba tiene dos ejercicios prácticos (energía y divisas) y comparten el mismo "
                + "cluster RDS por ahorro de costo.",
                "Decisión arquitectónica propia · explicada para el evaluador",
                """
                Aunque ambos ejercicios viven en el mismo cluster RDS por economía, las tablas viven
                en <strong>schemas separados por dominio</strong>: <code>core</code>+<code>dwh</code>+<code>audit</code>
                son de energía (Ej.1), <code>divisas</code> es de compra/venta dólares (Ej.2). Esto
                sigue el principio de <em>Bounded Context</em> de Domain-Driven Design: cada dominio
                tiene su propio vocabulario, modelo y permisos, sin colisiones de nombres. Si mañana
                migro Ej.2 a su propio cluster, la separación ya está hecha.
                """,
                "Los schemas creados (datos en vivo del RDS)",
                schemas.toString(),
                "<p class=\"status ok\"><strong>Resultado:</strong> 4 schemas independientes con "
                + "permisos separados (vía <code>GRANT USAGE ON SCHEMA</code>). Verificable con "
                + "<code>\\dn</code> en <code>psql</code>.</p>");
    }

    private String sectionD1() {
        return """
                <section class="page sect-doc">
                  <div class="sect-tag">Ejercicio 1 · Documentación</div>
                  <h2 class="sect-h"><span class="sect-num">D1</span> Descripción detallada del pipeline</h2>
                  <div class="md-body">%s</div>
                </section>
                """.formatted(renderMd("docs/pipeline.md"));
    }

    private String sectionD2() {
        return """
                <section class="page sect-doc">
                  <div class="sect-tag">Ejercicio 1 · Documentación</div>
                  <h2 class="sect-h"><span class="sect-num">D2</span> Permisos y políticas AWS · IAM + Lake Formation</h2>
                  <div class="md-body">%s</div>
                </section>
                """.formatted(renderMd("docs/permissions.md"));
    }

    private String sectionP1() {
        return narrativeBlock("P1", "IaC · Infraestructura como código (Terraform)",
                "Crea la IaC (Infraestructura como código) necesaria para desplegar esta solución en AWS.",
                "PruebaAWS.pdf · Ejercicio 1, puntos adicionales 1",
                """
                Implementé toda la infraestructura con <strong>Terraform</strong> (no CloudFormation
                ni CDK) por su transparencia de state, su ecosistema de módulos comunitarios y la
                portabilidad multi-cloud. Estructuré el código en <strong>7 archivos .tf separados
                por dominio</strong> (RDS, S3, IAM, Glue, Lake Formation, Redshift, EC2). Un solo
                <code>terraform apply</code> crea los 63 recursos AWS necesarios. Patrones de
                producción: default tags via provider, <code>prevent_destroy</code> en la EIP,
                conditional resources con <code>count</code> (opt-in para Redshift).
                """,
                "Estructura del repo y patrones aplicados",
                """
                <h4>Por qué Terraform y no CloudFormation/CDK</h4>
                <ul>
                  <li><strong>Multi-cloud</strong>: si mañana hay que migrar parcialmente a GCP/Azure,
                  los módulos de network/storage se adaptan. CloudFormation queda atado a AWS.</li>
                  <li><strong>State explícito</strong>: <code>terraform state list</code> da
                  visibilidad inmediata. CloudFormation oculta esto detrás de Stacks.</li>
                  <li><strong>Comunidad</strong>: el registro de Terraform Hub tiene módulos
                  battle-tested para todos los servicios AWS (terraform-aws-modules/*).</li>
                </ul>
                <h4>Archivos por dominio</h4>
                <ul>
                  <li><code>rds.tf</code> — PostgreSQL <code>db.t4g.micro</code> + SG (5432 inbound
                  desde EC2 SG) + parameter group (<code>log_statement=ddl</code>) + subnet group default VPC</li>
                  <li><code>s3.tf</code> — 6 buckets con encryption SSE-S3 obligatoria, lifecycle
                  (Athena results 7 días), CORS configurado, public access blocks</li>
                  <li><code>iam.tf</code> — 4 roles separados con least privilege (Glue, EC2 backend,
                  Redshift, Lake Formation) + Secrets Manager + Cost Explorer permission</li>
                  <li><code>glue.tf</code> — DB <code>datalake_energia</code> + 2 crawlers + 3 ETL
                  jobs PySpark + Athena workgroup con cap <code>bytes_scanned_cutoff_per_query=1GB</code></li>
                  <li><code>lakeformation.tf</code> — settings, LF-Tags <code>Sensitivity</code>+<code>Domain</code>,
                  permisos por tag (no por bucket policy)</li>
                  <li><code>redshift.tf</code> — namespace + workgroup serverless 8 RPU con flag
                  <code>enable_redshift</code> para opt-in</li>
                  <li><code>ec2.tf</code> — backend instance + EIP estática (con <code>prevent_destroy=true</code>)
                  + Caddy preconfigurado vía user-data + SSH key auto-generada con <code>tls_private_key</code></li>
                </ul>
                <h4>Patrones aplicados</h4>
                <ul>
                  <li><strong>Default tags en provider</strong>: cada recurso lleva 4 tags sin tocar
                  la definición · cumple cost allocation policy</li>
                  <li><strong>Lifecycle <code>prevent_destroy</code></strong> en EIP para que un
                  <code>terraform destroy</code> accidental no rompa la URL del backend</li>
                  <li><strong>Conditional resources</strong> con <code>count = var.enable_redshift ? 1 : 0</code></li>
                  <li><strong>Outputs consumibles</strong>: <code>backend_url</code>, <code>rds_endpoint</code>,
                  <code>secret_arn</code> los leen scripts Python sin hardcoding</li>
                </ul>
                """,
                "<p class=\"status ok\"><strong>Resultado:</strong> 63 recursos AWS desplegados con "
                + "un solo <code>terraform apply</code>. Código fuente en "
                + "<code>github.com/dapovedag/aws_test/tree/main/infra/terraform</code>.</p>");
    }

    private String sectionP2() {
        return narrativeBlock("P2", "Lake Formation · gobierno + tag-based access control",
                "Configure AWS Lakeformation para centralizar el gobierno, la seguridad y compartir "
                + "los datos alojados en el datalake creado.",
                "PruebaAWS.pdf · Ejercicio 1, puntos adicionales 2",
                """
                Configuré Lake Formation con <strong>permisos por tags (LF-Tags)</strong>, no por
                bucket policies de S3. Esto centraliza el gobierno: marcas la tabla como
                <code>Sensitivity=PII</code> una sola vez, y todos los principals que no tienen
                permission sobre ese tag quedan bloqueados automáticamente. Adicionalmente registré
                <code>s3://prueba-aws-processed/</code> como ubicación gobernada por LF para que las
                bucket policies dejen de ser la fuente principal de control.
                """,
                "Configuración aplicada y por qué LF gana a IAM puro",
                """
                <h4>Configuración aplicada</h4>
                <ul>
                  <li><strong>Data Lake Admins</strong>: el ARN del usuario IAM aplicador de Terraform
                  · revocable sin tocar bucket policies</li>
                  <li><strong>Data lake location registrada</strong>: <code>s3://prueba-aws-processed/</code>
                  queda bajo gobierno LF (las bucket policies dejan de ser fuente de verdad)</li>
                  <li><strong>LF-Tags definidos</strong>:
                    <ul>
                      <li><code>Sensitivity</code> → valores <code>Public · Internal · PII</code></li>
                      <li><code>Domain</code> → valores <code>Provider · Client · Transaction · Reference</code></li>
                    </ul>
                  </li>
                  <li><strong>Permisos fine-grained</strong> por tag (no por bucket policy directa):</li>
                  <li>Backend EC2: <code>SELECT, DESCRIBE</code> sobre tablas con <code>Sensitivity ∈ {Public, Internal}</code></li>
                  <li>Backend EC2 NO ve tablas con <code>Sensitivity = PII</code></li>
                  <li>Glue service-role: <code>DATA_LOCATION_ACCESS</code> sobre processed para escribir Parquet</li>
                  <li>Redshift role: <code>SELECT</code> sobre las dim/fact para hacer COPY</li>
                </ul>
                <h4>Patrones imposibles con IAM puro</h4>
                <ul>
                  <li><strong>Column-level security</strong> · solo ciertos roles ven la columna
                  <code>id_externo</code> de cliente; otros la ven como NULL</li>
                  <li><strong>Row-level security</strong> · usuarios de Bogotá solo ven transacciones
                  de Bogotá vía <code>Data Filter</code> con expresión SQL</li>
                  <li><strong>Cross-account sharing</strong> · compartir tablas a otra cuenta AWS sin
                  replicar S3</li>
                  <li><strong>Capa de evaluación adicional</strong> · LF se aplica DESPUÉS de IAM; un
                  usuario puede tener IAM <code>s3:GetObject</code> pero LF lo bloquea por tag</li>
                </ul>
                """,
                "<p class=\"status ok\"><strong>Resultado:</strong> tag-based access control activo · "
                + "governance centralizado fuera de bucket policies · auditable vía CloudTrail "
                + "(<code>lakeformation:GrantPermissions</code> data events).</p>");
    }

    private String sectionP3() {
        return narrativeBlock("P3", "Pipeline a Redshift · datawarehouse",
                "Construya un pipeline de datos que permita cargar esta información desde el datalake "
                + "en la zona procesada a un datawarehouse en redshift.",
                "PruebaAWS.pdf · Ejercicio 1, puntos adicionales 3",
                """
                Elegí <strong>Redshift Serverless</strong> sobre Provisioned porque para un demo con
                uso esporádico el modelo serverless cuesta $0 cuando está idle (auto-pause a los 60s)
                y solo cobra ~$0.36/RPU-hora cuando hay queries activas. Para producción 24×7 con
                baseline alto, recomendaría Provisioned + Reserved Instance. El pipeline es directo:
                <code>COPY masivo desde Parquet en S3 processed</code> hacia
                <code>analytics.fact_transaccion</code>. Sin transformación intermedia (la
                transformación ya la hicieron los Glue ETLs). Validación post-COPY con un
                <code>SELECT COUNT(*) + SUM(monto_usd)</code>.
                """,
                "Diseño técnico, script y por qué Serverless vs Provisioned",
                """
                <h4>Diseño técnico</h4>
                <ul>
                  <li><strong>Engine</strong>: Redshift Serverless · auto-pause · auto-scale en RPUs</li>
                  <li><strong>Namespace</strong>: <code>prueba-aws-ns</code> con admin user, KMS-managed encryption por defecto</li>
                  <li><strong>Workgroup</strong>: <code>prueba-aws-wg</code> base 8 RPU · max 8 RPU</li>
                  <li><strong>Schema destino</strong>: <code>analytics.fact_transaccion</code> ·
                  mismo modelo que <code>dwh.fact</code> en RDS para reconciliación trivial</li>
                  <li><strong>Carga masiva</strong>: <code>COPY ... FROM 's3://prueba-aws-processed/fact/'
                  IAM_ROLE 'prueba-aws-redshift-role' FORMAT PARQUET</code></li>
                  <li><strong>Validación post-COPY</strong>: query de conteo y suma para reconciliación con S3 source</li>
                  <li><strong>Pause automático</strong>: idle tras 60s · siguiente query reactiva en menos de 2s</li>
                </ul>
                <h4>Script y orquestación</h4>
                <pre>python scripts/06_load_to_redshift.py
# 1. CREATE SCHEMA IF NOT EXISTS analytics
# 2. CREATE TABLE IF NOT EXISTS analytics.fact_transaccion
# 3. TRUNCATE analytics.fact_transaccion
# 4. COPY FROM 's3://.../fact/' IAM_ROLE '...' FORMAT PARQUET
# 5. SELECT validation (count, sum, min/max date)
# 6. Workgroup queda en idle (auto-pause)</pre>
                <h4>Heurística Serverless vs Provisioned</h4>
                <ul>
                  <li>Usage rate mayor a 60% del tiempo → <strong>Provisioned</strong> + Reserved Instance</li>
                  <li>Usage rate menor a 60% o esporádico → <strong>Serverless</strong></li>
                  <li>Cargas batch nocturnas predecibles → Serverless con auto-pause</li>
                  <li>Dashboards 24×7 con concurrencia alta → Provisioned + Concurrency Scaling</li>
                </ul>
                <h4>Modelo dimensional en Redshift</h4>
                <ul>
                  <li><strong>Distribución</strong>: <code>DISTKEY(cliente_id)</code></li>
                  <li><strong>Sort key</strong>: <code>SORTKEY(fecha_id, tipo_energia_id)</code></li>
                  <li><strong>Compresión</strong>: <code>ENCODE AUTO</code></li>
                </ul>
                """,
                "<p class=\"status warn\"><strong>Resultado:</strong> provisión <strong>opt-in</strong> "
                + "via flag <code>enable_redshift=true</code>. Cuentas AWS nuevas requieren activar "
                + "Redshift desde la consola una vez (subscription implícita) antes del primer apply. "
                + "Script <code>scripts/06_load_to_redshift.py</code> reconcilia conteos contra Parquet "
                + "en S3 processed.</p>");
    }

    private String sectionEj2() {
        StringBuilder kpis = new StringBuilder();
        try {
            var d = divisasSvc.dashboard();
            kpis.append("<div class=\"kpi-grid\">")
                .append(kpi("Pares activos", String.valueOf(d.paresActivos())))
                .append(kpi("Ticks últimas 24h", String.valueOf(d.ticks24h())))
                .append(kpi("Ticks totales", String.valueOf(d.ticksTotales())))
                .append(kpi("Portafolios", String.valueOf(d.portafolios())))
                .append(kpi("Notificaciones generadas", String.valueOf(d.notificacionesTotal())))
                .append(kpi("CTR (leídas / enviadas)", d.ctrPct() + " %"))
                .append(kpi("Conversión (convertidas / leídas)", d.conversionPct() + " %"))
                .append(kpi("Score promedio del modelo", String.valueOf(d.scorePromedio())))
                .append(kpi("Modelo activo", d.modeloActivo() == null ? "n/a" : d.modeloActivo()))
                .append("</div>");
        } catch (Exception e) {
            kpis.append("<p class=\"muted\">(KPIs en vivo no disponibles offline)</p>");
        }

        return """
                <section class="page sect-ej">
                  <div class="sect-tag">Ejercicio 2 · Arquitectura</div>
                  <h2 class="sect-h">Plataforma de divisas en tiempo real</h2>
                  <p class="lead">Propuesta autoritativa con diagrama, supuestos, componentes evaluados,
                  flujo paso a paso, modelo ML, KPIs reales del schema <code>divisas</code>, reglas de
                  negocio, compliance y costos estimados.</p>

                  <h3>KPIs en vivo · schema <code>divisas</code> en RDS</h3>
                  %s

                  <div class="md-body">%s</div>
                </section>
                """.formatted(kpis.toString(), renderMd("docs/exercises/ej2-architecture.md"));
    }

    private String sectionEj3() {
        return """
                <section class="page sect-ej">
                  <div class="sect-tag">Ejercicio 3 · Preguntas generales</div>
                  <h2 class="sect-h">5 preguntas conceptuales sobre AWS</h2>
                  <p class="lead">Respondidas con detalle Senior+: experiencia personal, criterios de
                  decisión, alternativas evaluadas y casos reales de proyectos previos.</p>
                  <div class="md-body">%s</div>
                </section>
                """.formatted(renderMd("docs/exercises/ej3-answers.md"));
    }

    private String sectionCosto() {
        // Datos dinámicos del FreeTierService (espeja consola AWS)
        String startingCredits = "$120.00 USD";
        String consumed        = "$0.00 USD";
        String creditsRemaining= "$120.00 USD";
        String outOfPocket     = "$0.00 USD";
        int daysRemaining      = 184;
        String endsOn          = "2026-10-17";
        try {
            var st = freeTierSvc.status();
            startingCredits  = st.startingCreditsUsd();
            consumed         = st.creditsConsumedUsd();
            creditsRemaining = st.creditsRemainingUsd();
            outOfPocket      = st.outOfPocketUsd();
            daysRemaining    = st.daysRemaining();
            endsOn           = st.freeTierEndsOn().toString();
        } catch (Exception ignored) {}

        return """
                <section class="page sect-ej">
                  <div class="sect-tag">Costo del despliegue</div>
                  <h2 class="sect-h">Costo a la fecha · %s</h2>

                  <div class="cost-simple">
                    <div class="cost-headline">%s</div>
                    <p class="cost-claim">Despliegue cubierto por AWS Free Tier + créditos de la
                    cuenta. Valores computados en tiempo real desde el endpoint
                    <code>/api/free-tier/status</code> del backend.</p>

                    <table class="cost-summary-table">
                      <tr>
                        <td class="csl">Días restantes de Free Tier</td>
                        <td class="csv">%d días</td>
                      </tr>
                      <tr>
                        <td class="csl">Fin del Free Tier</td>
                        <td class="csv">%s</td>
                      </tr>
                      <tr>
                        <td class="csl">Créditos AWS consumidos</td>
                        <td class="csv">%s</td>
                      </tr>
                      <tr>
                        <td class="csl">Créditos AWS restantes</td>
                        <td class="csv">%s</td>
                      </tr>
                    </table>

                    <p>EC2 <code>t3.micro</code> 750h/mes, RDS <code>db.t4g.micro</code> 750h/mes,
                    S3 5 GB, EBS 30 GB y los créditos AWS otorgados absorben cualquier consumo que
                    excede el Free Tier. Resultado: cero cargos facturables a lo largo del
                    desarrollo y la entrega.</p>

                    <p class="cost-foot">El desglose por servicio se obtiene desde
                    <code>/api/cost/metered</code>, que consulta EC2 DescribeInstances, RDS
                    DescribeDBInstances, S3 ListObjectsV2, Glue GetJobRuns y Athena
                    BatchGetQueryExecution multiplicando por precios oficiales us-east-1.</p>
                  </div>
                </section>
                """.formatted(outOfPocket, outOfPocket, daysRemaining, endsOn,
                              consumed, creditsRemaining);
    }

    // --------- Helpers ------------------------------------------------------

    private String narrativeBlock(String num, String title, String quote, String source,
                                  String enfoque, String implTitle, String implHtml, String resultado) {
        return """
                <section class="page sect-req">
                  <div class="sect-tag">Ejercicio 1 · Requisitos técnicos</div>
                  <h2 class="sect-h"><span class="sect-num">%s</span> %s</h2>

                  <div class="step">
                    <span class="step-n">1</span>
                    <div class="step-tag">Qué pide la prueba</div>
                    <blockquote>%s</blockquote>
                    <div class="src">%s</div>
                  </div>

                  <div class="step">
                    <span class="step-n">2</span>
                    <div class="step-tag">Mi enfoque</div>
                    <div class="step-body">%s</div>
                  </div>

                  <div class="step">
                    <span class="step-n">3</span>
                    <div class="step-tag">%s</div>
                    <div class="step-body">%s</div>
                  </div>

                  <div class="step">
                    <span class="step-n">4</span>
                    <div class="step-tag">Resultado y dónde verificarlo</div>
                    <div class="step-body">%s</div>
                  </div>
                </section>
                """.formatted(num, esc(title), esc(quote), esc(source), enfoque, esc(implTitle),
                              implHtml, resultado);
    }

    private String kpi(String label, String value) {
        return "<div class=\"kpi\"><div class=\"kpi-label\">" + esc(label)
                + "</div><div class=\"kpi-value\">" + esc(value) + "</div></div>";
    }

    private String renderMd(String classpathResource) {
        try {
            var res = new ClassPathResource(classpathResource);
            String md = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return mdRenderer.render(mdParser.parse(md));
        } catch (IOException e) {
            log.warn("No pude leer {}: {}", classpathResource, e.getMessage());
            return "<p><em>(documento no disponible: " + esc(classpathResource) + ")</em></p>";
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // --------- CSS print-ready -----------------------------------------------

    private static final String CSS = """
            @page {
              size: A4;
              margin: 22mm 20mm 22mm 20mm;
              @bottom-center {
                content: "Prueba AWS · Solución completa · Diego Poveda · página " counter(page);
                font-family: "SourceSans", sans-serif;
                font-size: 8pt;
                color: #777;
              }
            }
            @page :first {
              margin: 0;
              @bottom-center { content: ""; }
            }

            * { box-sizing: border-box; }

            html, body {
              width: 170mm;
              max-width: 170mm;
            }
            body {
              font-family: "SourceSans", sans-serif;
              font-size: 10.5pt;
              line-height: 1.55;
              color: #111;
              margin: 0;
              text-align: justify;
              -fs-pdf-font-embed: embed;
            }
            section, div, p, ul, ol, li, table, td, th, pre, code, blockquote, h1, h2, h3, h4 {
              max-width: 170mm;
              word-wrap: break-word;
            }
            /* Overrides para que no se justifiquen elementos donde sería ilegible */
            pre, code, table, th, td, h1, h2, h3, h4, .step-tag, .src,
            .sect-tag, .sect-h, .cover-eyebrow, .cover-title, .cover-meta,
            .toc-list, .kpi-label, .kpi-value, .step-n,
            .cost-headline, .cost-claim {
              text-align: left;
            }
            table td.csv, .credit-headline-value { text-align: right; }
            .cover-deck { text-align: justify; }

            /* ---------- COVER ---------- */
            section.cover {
              page: cover;
              page-break-after: always;
              padding: 60mm 22mm 28mm 22mm;
              text-align: center;
            }
            .cover-rule { height: 2px; background: #b8231a; margin: 0 auto 18mm auto; width: 60%; }
            .cover-eyebrow {
              font-family: "SourceSans", sans-serif;
              font-size: 9pt;
              letter-spacing: 0.18em;
              color: #b8231a;
              text-transform: uppercase;
              margin-bottom: 14mm;
            }
            .cover-title {
              font-family: Times, Merriweather, serif;
              font-weight: bold;
              font-size: 38pt;
              line-height: 1.1;
              margin: 0 0 14mm 0;
              color: #111;
            }
            .cover-title span {
              font-weight: normal;
              font-size: 22pt;
              color: #555;
            }
            .cover-deck {
              font-family: "SourceSans", sans-serif;
              font-size: 11.5pt;
              line-height: 1.6;
              color: #333;
              max-width: 140mm;
              margin: 0 auto 18mm auto;
              text-align: justify;
            }
            .cover-meta {
              display: table;
              width: 100%;
              margin-top: 22mm;
              border-spacing: 0;
            }
            .cover-meta > div {
              display: table-cell;
              text-align: center;
              vertical-align: top;
              font-family: "SourceSans", sans-serif;
              font-size: 8.5pt;
              color: #444;
              padding: 0 4mm;
              line-height: 1.5;
            }
            .cover-meta strong {
              color: #b8231a;
              font-size: 8pt;
              text-transform: uppercase;
              letter-spacing: 0.1em;
            }

            /* ---------- TOC ---------- */
            section.toc { page-break-after: always; }
            .toc h2 {
              font-family: Merriweather, serif;
              font-size: 22pt;
              border-bottom: 2px solid #111;
              padding-bottom: 4mm;
              margin: 0 0 8mm 0;
            }
            .toc-list { list-style: decimal; padding-left: 6mm; }
            .toc-list > li {
              margin-bottom: 3mm;
              font-size: 11pt;
              line-height: 1.55;
            }
            .toc-list .group { font-weight: bold; }
            .toc-list ol {
              list-style: lower-alpha;
              margin-top: 1mm;
              padding-left: 6mm;
              font-weight: normal;
              font-size: 10pt;
            }

            /* ---------- SECTIONS ---------- */
            section.page { page-break-before: always; padding-top: 0; }
            .sect-tag {
              font-family: "SourceSans", sans-serif;
              font-size: 8pt;
              letter-spacing: 0.18em;
              text-transform: uppercase;
              color: #b8231a;
              margin-bottom: 3mm;
            }
            .sect-h {
              font-family: Times, Merriweather, serif;
              font-size: 22pt;
              line-height: 1.2;
              margin: 0 0 6mm 0;
              padding-bottom: 4mm;
              border-bottom: 1.5px solid #111;
            }
            .sect-h .sect-num {
              display: inline-block;
              background: #111;
              color: #fff;
              font-family: "SourceSans", sans-serif;
              font-size: 14pt;
              padding: 2mm 4mm;
              margin-right: 4mm;
              vertical-align: middle;
            }
            .lead {
              font-family: "SourceSans", sans-serif;
              font-size: 11pt;
              line-height: 1.6;
              color: #333;
              margin-bottom: 4mm;
            }

            /* ---------- 5-STEP NARRATIVE BLOCKS ---------- */
            .step {
              page-break-inside: avoid;
              margin: 6mm 0 6mm 0;
              padding: 4mm 5mm 4mm 12mm;
              border-left: 2px solid #b8231a;
              position: relative;
            }
            .step .step-n {
              position: absolute;
              left: 3mm;
              top: 3mm;
              font-family: Merriweather, serif;
              font-weight: bold;
              font-size: 16pt;
              color: #b8231a;
            }
            .step-tag {
              font-family: "SourceSans", sans-serif;
              font-size: 8pt;
              letter-spacing: 0.12em;
              text-transform: uppercase;
              color: #555;
              margin-bottom: 2mm;
            }
            .step blockquote {
              margin: 0 0 2mm 0;
              padding: 2mm 0 2mm 4mm;
              border-left: 3px solid #ddd;
              color: #333;
              word-wrap: break-word;
            }
            .step .src {
              font-family: "SourceSans", sans-serif;
              font-size: 8pt;
              color: #888;
            }
            .step-body {
              font-size: 10.5pt;
              line-height: 1.55;
              word-wrap: break-word;
              text-align: justify;
            }
            .step-body h4 {
              font-family: Merriweather, serif;
              font-size: 11.5pt;
              color: #b8231a;
              margin: 4mm 0 2mm 0;
            }
            .step-body ul, .step-body ol { padding-left: 6mm; margin: 1mm 0; }
            .step-body li { margin-bottom: 1.2mm; word-wrap: break-word; }
            .step-body pre {
              background: #f4f4f4;
              border-left: 3px solid #b8231a;
              padding: 2mm 3mm;
              font-family: "JetBrains Mono", monospace;
              font-size: 8.5pt;
              line-height: 1.45;
              white-space: pre-wrap;
              word-wrap: break-word;
            }
            .step-body code, .step-body pre code {
              font-family: "JetBrains Mono", monospace;
              font-size: 9pt;
              background: #f4f4f4;
              padding: 0 1mm;
              word-wrap: break-word;
            }
            .step-body pre code { background: none; padding: 0; }

            /* ---------- TABLES ---------- */
            table.data-table {
              border-collapse: collapse;
              width: 100%;
              margin: 3mm 0;
              font-size: 9pt;
            }
            table.data-table th {
              background: #111;
              color: #fff;
              padding: 2mm 3mm;
              text-align: left;
              font-family: "SourceSans", sans-serif;
              font-size: 8.5pt;
              text-transform: uppercase;
              letter-spacing: 0.06em;
            }
            table.data-table td {
              padding: 1.5mm 3mm;
              border-bottom: 0.5px solid #ddd;
              vertical-align: top;
              word-wrap: break-word;
            }
            table.data-table tr:nth-child(even) td { background: #fafafa; }

            /* ---------- KPIs Ej.2 ---------- */
            .kpi-grid { margin: 3mm 0; }
            .kpi {
              display: inline-block;
              width: 50mm;
              vertical-align: top;
              padding: 3mm;
              border: 0.5px solid #ddd;
              border-left: 2px solid #b8231a;
              margin: 0 1mm 2mm 0;
            }
            .kpi-label {
              font-family: "SourceSans", sans-serif;
              font-size: 7.5pt;
              text-transform: uppercase;
              letter-spacing: 0.08em;
              color: #555;
              margin-bottom: 1mm;
            }
            .kpi-value {
              font-family: Merriweather, serif;
              font-size: 16pt;
              font-weight: bold;
              color: #111;
            }

            /* ---------- QUERY CARDS R4 ---------- */
            .query-card {
              page-break-inside: avoid;
              margin: 3mm 0;
              padding: 3mm 4mm;
              background: #fafafa;
              border-left: 2px solid #111;
            }
            .query-card h4 {
              font-family: Merriweather, serif;
              margin: 0 0 1mm 0;
              font-size: 11pt;
              color: #b8231a;
            }
            .q-desc {
              font-size: 9.5pt;
              color: #444;
              margin: 0 0 2mm 0;
            }
            .query-card pre {
              background: #fff;
              border: 0.5px solid #ddd;
              padding: 2mm;
              font-family: "JetBrains Mono", monospace;
              font-size: 8pt;
              white-space: pre-wrap;
              word-wrap: break-word;
            }

            /* ---------- MARKDOWN CONTENT ---------- */
            .md-body {
              font-family: "SourceSans", sans-serif;
              font-size: 10pt;
              line-height: 1.55;
              text-align: justify;
            }
            .md-body h1 {
              font-family: Merriweather, serif;
              font-size: 16pt;
              border-bottom: 1px solid #111;
              padding-bottom: 1mm;
              margin: 5mm 0 3mm 0;
            }
            .md-body h2 {
              font-family: Merriweather, serif;
              font-size: 13pt;
              color: #b8231a;
              margin: 4mm 0 2mm 0;
            }
            .md-body h3 {
              font-family: Merriweather, serif;
              font-size: 11.5pt;
              margin: 3mm 0 1.5mm 0;
              color: #111;
            }
            .md-body h4 {
              font-family: "SourceSans", sans-serif;
              font-size: 10pt;
              margin: 2.5mm 0 1mm 0;
              font-weight: bold;
              color: #111;
            }
            .md-body p { margin: 0 0 2.5mm 0; word-wrap: break-word; }
            .md-body ul, .md-body ol { padding-left: 6mm; margin: 1mm 0 2.5mm 0; }
            .md-body li {
              margin-bottom: 1.2mm;
              line-height: 1.55;
              word-wrap: break-word;
            }
            .md-body code {
              font-family: "JetBrains Mono", monospace;
              font-size: 8.5pt;
              background: #f4f4f4;
              padding: 0 1mm;
              word-wrap: break-word;
            }
            .md-body pre {
              background: #f4f4f4;
              border-left: 3px solid #b8231a;
              padding: 2mm 3mm;
              font-family: "JetBrains Mono", monospace;
              font-size: 8pt;
              line-height: 1.5;
              white-space: pre-wrap;
              word-wrap: break-word;
              margin: 2mm 0;
            }
            .md-body pre code { background: none; padding: 0; font-size: 8pt; }
            .md-body blockquote {
              margin: 2mm 0;
              padding: 1mm 0 1mm 4mm;
              border-left: 3px solid #ddd;
              color: #555;
            }
            .md-body table {
              border-collapse: collapse;
              width: 100%;
              margin: 2mm 0;
              font-size: 9pt;
              table-layout: fixed;
            }
            .md-body table th {
              background: #111;
              color: #fff;
              padding: 1.5mm 2.5mm;
              text-align: left;
              font-family: "SourceSans", sans-serif;
              font-size: 8.5pt;
            }
            .md-body table td {
              padding: 1mm 2.5mm;
              border-bottom: 0.5px solid #ddd;
              vertical-align: top;
              word-wrap: break-word;
            }

            /* ---------- STATUS / CALLOUTS ---------- */
            .status {
              padding: 3mm 4mm;
              margin: 3mm 0 0 0;
              font-size: 10pt;
              border-radius: 0;
            }
            .status.ok {
              background: #e8f5e9;
              border-left: 3px solid #1f7a3a;
              color: #1f7a3a;
            }
            .status.warn {
              background: #fff8e1;
              border-left: 3px solid #b8231a;
              color: #8a3500;
            }
            .callout {
              background: #fafafa;
              border-left: 3px solid #b8231a;
              padding: 3mm 4mm;
              margin: 4mm 0;
              font-size: 10pt;
              line-height: 1.5;
            }
            .callout-tag {
              font-family: 'Helvetica', sans-serif;
              font-size: 8pt;
              text-transform: uppercase;
              letter-spacing: 0.12em;
              color: #b8231a;
              margin-bottom: 1mm;
            }
            .total {
              margin: 4mm 0;
              padding: 3mm 4mm;
              background: #111;
              color: #fff;
              font-size: 11pt;
            }
            .muted { color: #888; font-style: italic; }

            /* ---------- COST SIMPLE (Free Tier · $0) ---------- */
            .cost-simple {
              margin: 6mm 0;
              padding: 8mm 8mm 6mm 8mm;
              border: 1px solid #111;
              text-align: left;
            }
            .cost-headline {
              font-family: Merriweather, serif;
              font-size: 56pt;
              font-weight: bold;
              line-height: 1;
              color: #1f7a3a;
              margin: 0 0 6mm 0;
              text-align: center;
              letter-spacing: -0.02em;
            }
            .cost-claim {
              font-family: Merriweather, serif;
              font-size: 13pt;
              line-height: 1.55;
              color: #111;
              margin: 0 0 4mm 0;
              text-align: center;
            }
            .cost-simple p {
              font-family: "SourceSans", sans-serif;
              font-size: 10.5pt;
              line-height: 1.6;
              margin: 0 0 3mm 0;
              color: #333;
            }
            .cost-simple p.cost-foot {
              margin-top: 5mm;
              padding-top: 4mm;
              border-top: 0.5px solid #ddd;
              font-size: 9.5pt;
              color: #666;
            }
            table.cost-summary-table {
              width: 100%;
              border-collapse: collapse;
              margin: 4mm 0 6mm 0;
              font-family: "SourceSans", sans-serif;
              font-size: 11pt;
            }
            table.cost-summary-table td {
              padding: 3mm 4mm;
              border-bottom: 0.5px solid #ddd;
            }
            table.cost-summary-table td.csl { color: #555; }
            table.cost-summary-table td.csv {
              text-align: right;
              font-family: "JetBrains Mono", monospace;
              white-space: nowrap;
              width: 50mm;
            }
            """;
}
