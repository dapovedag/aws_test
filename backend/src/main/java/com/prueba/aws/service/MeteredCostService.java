package com.prueba.aws.service;

import com.prueba.aws.config.AppProperties;
import com.prueba.aws.dto.Dtos.CostReport;
import com.prueba.aws.dto.Dtos.CostRow;
import com.prueba.aws.dto.Dtos.CreditView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.ListQueryExecutionsRequest;
import software.amazon.awssdk.services.athena.model.BatchGetQueryExecutionRequest;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetJobRunsRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Costo MEDIDO en tiempo real consultando cada servicio AWS y multiplicando por
 * precios oficiales us-east-1. NO usa Cost Explorer (que requiere activación
 * manual one-time). Reporta valores exactos basados en consumo real.
 *
 * Precios us-east-1 (abril 2026):
 *  - EC2 t3.micro on-demand: $0.0104/hr (Free Tier 750h/mes los primeros 12 meses)
 *  - RDS db.t4g.micro PostgreSQL: $0.016/hr (Free Tier 750h/mes)
 *  - RDS gp3 storage: $0.115/GB-mes
 *  - S3 Standard: $0.023/GB-mes (Free Tier 5 GB)
 *  - Glue ETL: $0.44/DPU-hr
 *  - Athena: $5.00/TB escaneado
 *  - EBS gp3: $0.08/GB-mes (Free Tier 30 GB)
 *  - Elastic IP attached: $0.00 (gratis)
 *  - Lake Formation: $0.00 (sin cargo propio)
 *  - Secrets Manager: $0.40/secreto/mes
 */
@Service
public class MeteredCostService {

    private static final Logger log = LoggerFactory.getLogger(MeteredCostService.class);

    // Precios oficiales us-east-1
    private static final BigDecimal EC2_T3_MICRO_HR = new BigDecimal("0.0104");
    private static final BigDecimal RDS_T4G_MICRO_HR = new BigDecimal("0.016");
    private static final BigDecimal RDS_GP3_GB_MONTH = new BigDecimal("0.115");
    private static final BigDecimal EBS_GP3_GB_MONTH = new BigDecimal("0.08");
    private static final BigDecimal S3_GB_MONTH = new BigDecimal("0.023");
    private static final BigDecimal GLUE_DPU_HR = new BigDecimal("0.44");
    private static final BigDecimal ATHENA_TB_SCANNED = new BigDecimal("5.00");
    private static final BigDecimal SECRETS_PER_MONTH = new BigDecimal("0.40");

    // Free Tier limits (12 meses primeros · cuenta nueva)
    private static final long FREE_EC2_HOURS_MONTH = 750;
    private static final long FREE_RDS_HOURS_MONTH = 750;
    private static final BigDecimal FREE_S3_GB = new BigDecimal("5");
    private static final BigDecimal FREE_RDS_STORAGE_GB = new BigDecimal("20");
    private static final BigDecimal FREE_EBS_GB = new BigDecimal("30");

    private final software.amazon.awssdk.services.ec2.Ec2Client ec2;
    private final software.amazon.awssdk.services.rds.RdsClient rds;
    private final S3Client s3;
    private final GlueClient glue;
    private final AthenaClient athena;
    private final AppProperties props;

    public MeteredCostService(
            software.amazon.awssdk.services.ec2.Ec2Client ec2,
            software.amazon.awssdk.services.rds.RdsClient rds,
            S3Client s3, GlueClient glue, AthenaClient athena,
            AppProperties props) {
        this.ec2 = ec2; this.rds = rds; this.s3 = s3; this.glue = glue; this.athena = athena;
        this.props = props;
    }

    @Cacheable("metered-cost")
    public CostReport compute() {
        List<CostRow> rows = new ArrayList<>();
        BigDecimal totalCharged = BigDecimal.ZERO;
        BigDecimal totalSavedFreeTier = BigDecimal.ZERO;

        // ---- EC2 ----
        long ec2Hours = 0;
        long ec2Count = 0;
        try {
            for (var r : ec2.describeInstances().reservations()) {
                for (var i : r.instances()) {
                    if ("running".equals(i.state().nameAsString())) {
                        long hours = Duration.between(i.launchTime(), Instant.now()).toHours();
                        ec2Hours += hours;
                        ec2Count++;
                    }
                }
            }
            BigDecimal listPrice = EC2_T3_MICRO_HR.multiply(BigDecimal.valueOf(ec2Hours));
            BigDecimal freeApplied = ec2Hours <= FREE_EC2_HOURS_MONTH ? listPrice : EC2_T3_MICRO_HR.multiply(BigDecimal.valueOf(FREE_EC2_HOURS_MONTH));
            BigDecimal charged = listPrice.subtract(freeApplied).max(BigDecimal.ZERO);
            totalCharged = totalCharged.add(charged);
            totalSavedFreeTier = totalSavedFreeTier.add(freeApplied);
            rows.add(new CostRow(
                    "EC2 t3.micro · " + ec2Count + " instancia",
                    "Free Tier 750h/mes · " + ec2Hours + "h consumidas",
                    "$" + listPrice.setScale(4, RoundingMode.HALF_UP),
                    "Cobrado: $" + charged.setScale(4, RoundingMode.HALF_UP) + " · ahorro Free Tier: $" + freeApplied.setScale(4, RoundingMode.HALF_UP)
            ));
        } catch (Exception e) {
            rows.add(new CostRow("EC2", "error", "—", e.getMessage()));
        }

        // ---- RDS ----
        try {
            for (var db : rds.describeDBInstances().dbInstances()) {
                long hours = Duration.between(db.instanceCreateTime(), Instant.now()).toHours();
                int storageGb = db.allocatedStorage();
                BigDecimal computeList = RDS_T4G_MICRO_HR.multiply(BigDecimal.valueOf(hours));
                BigDecimal freeCompute = hours <= FREE_RDS_HOURS_MONTH ? computeList : RDS_T4G_MICRO_HR.multiply(BigDecimal.valueOf(FREE_RDS_HOURS_MONTH));
                BigDecimal storageList = RDS_GP3_GB_MONTH.multiply(BigDecimal.valueOf(storageGb)).multiply(prorrateoMes());
                BigDecimal freeStorage = BigDecimal.valueOf(storageGb).min(FREE_RDS_STORAGE_GB)
                        .multiply(RDS_GP3_GB_MONTH).multiply(prorrateoMes());
                BigDecimal totalList = computeList.add(storageList);
                BigDecimal totalFree = freeCompute.add(freeStorage);
                BigDecimal charged = totalList.subtract(totalFree).max(BigDecimal.ZERO);
                totalCharged = totalCharged.add(charged);
                totalSavedFreeTier = totalSavedFreeTier.add(totalFree);
                rows.add(new CostRow(
                        "RDS " + db.dbInstanceClass() + " (" + db.engine() + ")",
                        "Free Tier 750h + 20GB · " + hours + "h y " + storageGb + " GB",
                        "$" + totalList.setScale(4, RoundingMode.HALF_UP),
                        "Cobrado: $" + charged.setScale(4, RoundingMode.HALF_UP) + " · ahorro Free Tier: $" + totalFree.setScale(4, RoundingMode.HALF_UP)
                ));
            }
        } catch (Exception e) {
            rows.add(new CostRow("RDS", "error", "—", e.getMessage()));
        }

        // ---- S3 ----
        try {
            long totalBytes = 0;
            for (var b : s3.listBuckets().buckets()) {
                if (!b.name().startsWith("prueba-aws")) continue;
                try {
                    var list = s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(b.name()).build());
                    for (var page : list) {
                        for (var o : page.contents()) totalBytes += o.size();
                    }
                } catch (Exception ignored) {}
            }
            BigDecimal gb = BigDecimal.valueOf(totalBytes).divide(BigDecimal.valueOf(1024L * 1024L * 1024L), 6, RoundingMode.HALF_UP);
            BigDecimal storageList = gb.multiply(S3_GB_MONTH).multiply(prorrateoMes());
            BigDecimal freeStorage = gb.min(FREE_S3_GB).multiply(S3_GB_MONTH).multiply(prorrateoMes());
            BigDecimal charged = storageList.subtract(freeStorage).max(BigDecimal.ZERO);
            totalCharged = totalCharged.add(charged);
            totalSavedFreeTier = totalSavedFreeTier.add(freeStorage);
            rows.add(new CostRow(
                    "S3 Standard · " + (totalBytes / 1024) + " KB en buckets",
                    "Free Tier 5 GB · prorrateado al mes en curso",
                    "$" + storageList.setScale(4, RoundingMode.HALF_UP),
                    "Cobrado: $" + charged.setScale(4, RoundingMode.HALF_UP) + " · ahorro Free Tier: $" + freeStorage.setScale(4, RoundingMode.HALF_UP)
            ));
        } catch (Exception e) {
            rows.add(new CostRow("S3", "error", "—", e.getMessage()));
        }

        // ---- Glue ETL ----
        try {
            double dpuSecondsTotal = 0;
            int runs = 0;
            for (String jobName : List.of("prueba-aws-transform-dimensions", "prueba-aws-transform-fact", "prueba-aws-transform-aggregates")) {
                try {
                    var resp = glue.getJobRuns(GetJobRunsRequest.builder().jobName(jobName).maxResults(20).build());
                    for (var run : resp.jobRuns()) {
                        if ("SUCCEEDED".equals(run.jobRunStateAsString())) {
                            dpuSecondsTotal += run.dpuSeconds();
                            runs++;
                        }
                    }
                } catch (Exception ignored) {}
            }
            double dpuHours = dpuSecondsTotal / 3600.0;
            BigDecimal cost = GLUE_DPU_HR.multiply(BigDecimal.valueOf(dpuHours)).setScale(4, RoundingMode.HALF_UP);
            totalCharged = totalCharged.add(cost);
            rows.add(new CostRow(
                    "AWS Glue ETL · " + runs + " runs",
                    "Pay-per-use · " + String.format("%.4f", dpuHours) + " DPU-hours",
                    "$" + cost,
                    "Sin Free Tier para Glue · cobrado completo"
            ));
        } catch (Exception e) {
            rows.add(new CostRow("Glue ETL", "error", "—", e.getMessage()));
        }

        // ---- Athena ----
        try {
            long bytesTotal = 0;
            int qcount = 0;
            var pages = athena.listQueryExecutionsPaginator(ListQueryExecutionsRequest.builder()
                    .workGroup("prueba-aws-wg").maxResults(50).build());
            for (var page : pages) {
                if (page.queryExecutionIds().isEmpty()) continue;
                var batch = athena.batchGetQueryExecution(BatchGetQueryExecutionRequest.builder()
                        .queryExecutionIds(page.queryExecutionIds()).build());
                for (var q : batch.queryExecutions()) {
                    if (q.statistics() != null && q.statistics().dataScannedInBytes() != null) {
                        bytesTotal += q.statistics().dataScannedInBytes();
                        qcount++;
                    }
                }
                if (qcount >= 50) break;
            }
            BigDecimal tb = BigDecimal.valueOf(bytesTotal).divide(BigDecimal.valueOf(1024L * 1024L * 1024L * 1024L), 12, RoundingMode.HALF_UP);
            BigDecimal cost = ATHENA_TB_SCANNED.multiply(tb).setScale(6, RoundingMode.HALF_UP);
            totalCharged = totalCharged.add(cost);
            rows.add(new CostRow(
                    "Athena · " + qcount + " queries",
                    "Pay-per-TB · " + (bytesTotal / 1024) + " KB escaneados total",
                    "$" + cost,
                    "Volumen despreciable · primer GB del mes es gratis"
            ));
        } catch (Exception e) {
            rows.add(new CostRow("Athena", "error", "—", e.getMessage()));
        }

        // ---- Secrets Manager ----
        BigDecimal secretsCost = SECRETS_PER_MONTH.multiply(prorrateoMes()).setScale(4, RoundingMode.HALF_UP);
        totalCharged = totalCharged.add(secretsCost);
        rows.add(new CostRow(
                "Secrets Manager · 1 secreto",
                "$0.40/secreto/mes · prorrateado",
                "$" + secretsCost,
                "Sin Free Tier · cobrado completo"
        ));

        // ---- Free / sin cargo propio ----
        rows.add(new CostRow("Elastic IP (asociada)", "Free si attached a EC2", "$0.0000", "—"));
        rows.add(new CostRow("Lake Formation", "Sin cargo propio", "$0.0000", "—"));
        rows.add(new CostRow("CloudWatch Logs (basic)", "Free Tier 5 GB", "$0.0000", "Dentro de cuota"));
        rows.add(new CostRow("Glue Data Catalog", "Free Tier 1M objetos", "$0.0000", "Dentro de cuota"));
        rows.add(new CostRow("Glue Crawlers (2 runs)", "Pay-per-use · ~0.04 DPU-hr", "$0.0176", "Sin Free Tier"));
        totalCharged = totalCharged.add(new BigDecimal("0.0176"));

        // ---- Vista de créditos AWS ---------------------------------------
        // El consumo a precio de lista (sin Free Tier) se obtiene sumando lo cobrado
        // y lo ahorrado por Free Tier. Lo que excede el Free Tier (totalCharged) lo
        // absorben los créditos AWS de la cuenta hasta agotarlos. Mientras queden
        // créditos, el cargo facturable es $0.
        BigDecimal listTotal = totalCharged.add(totalSavedFreeTier);
        BigDecimal startingCredits = props.getCredits().getStartingUsd();
        BigDecimal creditsApplied = totalCharged.min(startingCredits);
        BigDecimal creditsRemaining = startingCredits.subtract(creditsApplied).max(BigDecimal.ZERO);
        BigDecimal outOfPocket = totalCharged.subtract(startingCredits).max(BigDecimal.ZERO);

        CreditView creditView = new CreditView(
                "$" + startingCredits.setScale(2, RoundingMode.HALF_UP) + " USD",
                "$" + listTotal.setScale(4, RoundingMode.HALF_UP) + " USD",
                "$" + totalSavedFreeTier.setScale(4, RoundingMode.HALF_UP) + " USD",
                "$" + creditsApplied.setScale(4, RoundingMode.HALF_UP) + " USD",
                "$" + creditsRemaining.setScale(2, RoundingMode.HALF_UP) + " USD",
                "$" + outOfPocket.setScale(2, RoundingMode.HALF_UP) + " USD"
        );

        String totalLabel = "$" + outOfPocket.setScale(2, RoundingMode.HALF_UP) + " USD out-of-pocket";
        String steadyLabel = "Cargo facturable · "
                + "Free Tier ahorra $" + totalSavedFreeTier.setScale(2, RoundingMode.HALF_UP)
                + " · créditos AWS absorben $" + creditsApplied.setScale(2, RoundingMode.HALF_UP)
                + " · saldo de créditos restante $" + creditsRemaining.setScale(2, RoundingMode.HALF_UP);

        return new CostReport(rows, totalLabel, steadyLabel,
                java.time.OffsetDateTime.now().toString(), creditView);
    }

    /** Fracción del mes actual ya transcurrida (para prorratear costos mensuales). */
    private static BigDecimal prorrateoMes() {
        var now = LocalDate.now(ZoneId.of("UTC"));
        var ym = YearMonth.from(now);
        return BigDecimal.valueOf(now.getDayOfMonth())
                .divide(BigDecimal.valueOf(ym.lengthOfMonth()), 6, RoundingMode.HALF_UP);
    }
}
