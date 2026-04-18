package com.prueba.aws.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class Dtos {
    private Dtos() {}

    public record Proveedor(int proveedorId, String nombre, String tipoEnergia, String pais,
                            BigDecimal capacidadMw, LocalDate fechaAlta, boolean activo) {}

    public record Cliente(int clienteId, String tipoId, String idExterno, String nombre,
                          String ciudad, String segmento, LocalDate fechaAlta, boolean activo) {}

    public record Ciudad(int ciudadId, String nombre, String pais, BigDecimal lat, BigDecimal lon, Integer poblacion) {}

    public record TipoEnergia(int tipoEnergiaId, String codigo, String nombre,
                              BigDecimal factorCo2KgMwh, boolean renovable) {}

    public record Transaccion(long transaccionId, LocalDate fecha, String tipo,
                              Integer proveedorId, Integer clienteId, String tipoEnergia,
                              BigDecimal cantidadMwh, BigDecimal precioUsd, BigDecimal montoUsd) {}

    public record Kpis(int proveedoresActivos, int clientesActivos, int ciudades,
                       int tiposEnergia, long transaccionesTotales,
                       BigDecimal mwhTransados, BigDecimal montoUsdTotal,
                       OffsetDateTime calculadoEn) {}

    public record DatasourceMeta(String engine, String version, String host, String database,
                                 List<SchemaInfo> schemas, OffsetDateTime checkedAt) {}

    public record SchemaInfo(String schema, List<TableCount> tables) {}

    public record TableCount(String table, long rowCount) {}

    public record TableSpec(String schema, String name, String description,
                            List<ColumnSpec> columns, List<Fk> foreignKeys) {}

    public record ColumnSpec(String name, String type, boolean nullable, boolean primaryKey, String description) {}

    public record Fk(String column, String references) {}

    public record DataModel(String version, String style, List<TableSpec> tables, String mermaid) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record DataQualityCheck(int id, String category, String name, String description,
                                   String expected, String severity, Object actual,
                                   Boolean passed, String message) {}

    public record DataQualitySummary(String generatedAt, double runtimeSec, String schemaVersion,
                                     int total, int passed, int failed, double passRate,
                                     List<DataQualityCheck> checks) {}

    public record AthenaQueryDef(String id, String title, String description, String sql) {}

    public record AthenaResult(String state, List<String> columns,
                               List<Map<String, Object>> rows,
                               long dataScannedBytes, String executionId, String error) {}

    public record CostRow(String resource, String mode, String costMonth1, String costSteadyState) {}

    /**
     * Vista de créditos AWS aplicados a la cuenta. listConsumed es lo que costaría
     * si no hubiera Free Tier ni créditos; freeTierSaved cubre los servicios
     * elegibles; creditsApplied absorbe el remanente; outOfPocket es el cobro
     * real a la tarjeta (cero mientras queden créditos).
     */
    public record CreditView(String startingCredits, String listConsumed,
                             String freeTierSaved, String creditsApplied,
                             String creditsRemaining, String outOfPocket) {}

    public record CostReport(List<CostRow> rows, String totalMonth1, String totalSteady,
                             String generatedAt, CreditView credits) {}

    public record HealthInfo(String status, String version, OffsetDateTime startedAt,
                             boolean dbConnected, OffsetDateTime lastReportFill) {}

    public record ExerciseDoc(String id, String title, String content, String sha,
                              String url, OffsetDateTime updatedAt) {}

    public record SaveExerciseRequest(String content, String message) {}

    /**
     * Estado del Free Tier + créditos de la cuenta AWS, computado dinámicamente:
     * - daysRemaining cae cada día
     * - creditsRemaining cae si MeteredCostService reporta consumo que excede el Free Tier
     * - outOfPocket es el cobro real a la tarjeta (cero hasta agotar créditos)
     * Refleja exactamente lo que la consola AWS muestra en "Estado del plan gratuito".
     */
    public record FreeTierStatus(
            java.time.LocalDate freeTierEndsOn,
            int daysRemaining,
            String startingCreditsUsd,
            String creditsConsumedUsd,
            String creditsRemainingUsd,
            String outOfPocketUsd,
            String listConsumedUsd,
            String freeTierSavedUsd,
            String summary,
            java.time.OffsetDateTime asOf) {}
}
