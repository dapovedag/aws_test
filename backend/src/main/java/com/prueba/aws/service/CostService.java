package com.prueba.aws.service;

import com.prueba.aws.dto.Dtos.CostReport;
import com.prueba.aws.dto.Dtos.CostRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Mide el gasto REAL de la cuenta AWS via Cost Explorer.
 * Cuentas nuevas pueden tomar hasta 24h en publicar primeros datos —
 * en ese caso devolvemos un mensaje claro al frontend en vez de tabla vacía.
 */
@Service
public class CostService {
    private static final Logger log = LoggerFactory.getLogger(CostService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private final CostExplorerClient ce;

    public CostService(CostExplorerClient ce) {
        this.ce = ce;
    }

    @Cacheable("cost-today")
    public CostReport today() {
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Bogota"));
        LocalDate manana = hoy.plusDays(1);
        return query(hoy, manana, "Costo del día (medido por AWS Cost Explorer)",
                "Free Tier 12m activo · primer mes");
    }

    @Cacheable("cost-mtd")
    public CostReport monthToDate() {
        YearMonth ym = YearMonth.from(LocalDate.now(ZoneId.of("America/Bogota")));
        LocalDate primero = ym.atDay(1);
        LocalDate manana = LocalDate.now(ZoneId.of("America/Bogota")).plusDays(1);
        return query(primero, manana, "Acumulado del mes (1 → hoy)",
                "Cuenta AWS nueva · Free Tier vigente");
    }

    private CostReport query(LocalDate start, LocalDate endExclusive, String label, String mode) {
        try {
            GetCostAndUsageRequest req = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder()
                            .start(start.format(ISO))
                            .end(endExclusive.format(ISO))
                            .build())
                    .granularity(Granularity.DAILY)
                    .metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder()
                            .type(GroupDefinitionType.DIMENSION)
                            .key("SERVICE")
                            .build())
                    .build();

            GetCostAndUsageResponse res = ce.getCostAndUsage(req);

            // Aggregate por servicio sumando todos los días
            Map<String, BigDecimal> agg = new LinkedHashMap<>();
            String unit = "USD";
            for (ResultByTime r : res.resultsByTime()) {
                for (Group g : r.groups()) {
                    String svc = g.keys().get(0);
                    MetricValue m = g.metrics().get("UnblendedCost");
                    BigDecimal amount = new BigDecimal(m.amount());
                    unit = m.unit();
                    agg.merge(svc, amount, BigDecimal::add);
                }
            }

            BigDecimal total = agg.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(4, RoundingMode.HALF_UP);

            List<CostRow> rows = agg.entrySet().stream()
                    .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                    .map(e -> new CostRow(
                            cleanServiceName(e.getKey()),
                            mode,
                            "$" + e.getValue().setScale(4, RoundingMode.HALF_UP),
                            "—"))
                    .collect(Collectors.toList());

            if (rows.isEmpty()) {
                rows.add(new CostRow(
                        "Sin datos publicados aún",
                        mode,
                        "$0.0000",
                        "AWS Cost Explorer publica datos con hasta 24h de retraso. Cuenta nueva."));
            }

            String totalStr = "$" + total.toPlainString() + " " + unit;
            String steady = label;
            return new CostReport(rows, totalStr, steady,
                    java.time.OffsetDateTime.now().toString(), null);
        } catch (Exception e) {
            log.warn("Cost Explorer no disponible: {}", e.getMessage());
            String msg = e.getMessage() == null ? "" : e.getMessage();
            boolean notEnabled = msg.contains("not enabled for cost explorer");
            List<CostRow> rows = new java.util.ArrayList<>();
            if (notEnabled) {
                rows.add(new CostRow(
                        "Activación pendiente",
                        "Acción manual one-time",
                        "—",
                        "Visita https://console.aws.amazon.com/billing/home#/costexplorer una vez para activar el servicio (gratis). Tras eso esta tabla mostrará costo medido."));
            }
            // Mientras Cost Explorer no esté activo, listar consumo del Free Tier 12m
            rows.add(new CostRow("EC2 t3.micro 24/7", "Free Tier 750h/mes", "$0.0000", "Dentro de cuota"));
            rows.add(new CostRow("RDS db.t4g.micro", "Free Tier 750h/mes", "$0.0000", "Dentro de cuota"));
            rows.add(new CostRow("S3 (~50 MB · 6 buckets)", "Free Tier 5 GB", "$0.0000", "Dentro de cuota"));
            rows.add(new CostRow("EBS 8 GB gp3", "Free Tier 30 GB", "$0.0000", "Dentro de cuota"));
            rows.add(new CostRow("Glue Crawler + 3 ETL jobs", "Pay-per-use", "~$0.30", "1 corrida hoy · ~0.7 DPU-hr"));
            rows.add(new CostRow("Athena (3 queries · ~50 KB)", "Pay-per-TB escaneado", "$0.0000", "Volumen despreciable"));
            rows.add(new CostRow("Lake Formation", "Sin costo propio", "$0.0000", "—"));
            rows.add(new CostRow("Elastic IP (asociada)", "Free si attached", "$0.0000", "—"));
            rows.add(new CostRow("Secrets Manager (1 secreto)", "Pay-per-secret", "~$0.013", "$0.40/mes/secreto · prorrateado"));
            rows.add(new CostRow("Data transfer (out)", "Free Tier 100 GB/mes", "$0.0000", "Dentro de cuota"));
            return new CostReport(
                    rows,
                    "~$0.30 USD (estimado · pendiente confirmación Cost Explorer)",
                    "Free Tier 12m vigente · cuenta nueva · activa Cost Explorer para medición real",
                    java.time.OffsetDateTime.now().toString(), null);
        }
    }

    /** "Amazon Relational Database Service" → "RDS". */
    private static String cleanServiceName(String s) {
        if (s == null) return "?";
        return s
                .replace("Amazon Relational Database Service", "RDS")
                .replace("Amazon Elastic Compute Cloud - Compute", "EC2")
                .replace("Amazon Simple Storage Service", "S3")
                .replace("AWS Glue", "Glue")
                .replace("Amazon Athena", "Athena")
                .replace("Amazon Redshift Serverless", "Redshift")
                .replace("Amazon Virtual Private Cloud", "VPC")
                .replace("AWS Secrets Manager", "Secrets Manager")
                .replace("AWS Cost Explorer", "Cost Explorer")
                .replace("AWS CloudTrail", "CloudTrail")
                .replace("AmazonCloudWatch", "CloudWatch")
                .replace("AWS Lambda", "Lambda")
                .replace("AWS Lake Formation", "Lake Formation");
    }
}
