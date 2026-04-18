package com.prueba.aws.service;

import com.prueba.aws.config.AppProperties;
import com.prueba.aws.dto.Dtos.AthenaQueryDef;
import com.prueba.aws.dto.Dtos.AthenaResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AthenaService {

    private static final Logger log = LoggerFactory.getLogger(AthenaService.class);
    private final AthenaClient athena;
    private final AppProperties props;

    public AthenaService(AthenaClient athena, AppProperties props) {
        this.athena = athena;
        this.props = props;
    }

    public List<AthenaQueryDef> queries() {
        String db = props.getAws().getAthenaDatabase();
        return List.of(
                new AthenaQueryDef("Q1", "Resumen mensual de ventas",
                        "MWh y monto USD totales por mes y tipo de energía (solo ventas).",
                        """
                        SELECT f.anio, f.mes, te.codigo AS tipo_energia,
                               SUM(f.cantidad_mwh) AS mwh_total,
                               SUM(f.monto_usd)    AS monto_usd_total
                        FROM   "%s"."fact" f
                        JOIN   "%s"."dim_tipo_energia" te ON te.tipo_energia_id = f.tipo_energia_id
                        WHERE  f.tipo = 'venta'
                        GROUP  BY f.anio, f.mes, te.codigo
                        ORDER  BY f.anio, f.mes, te.codigo
                        LIMIT 200
                        """.formatted(db, db)),
                new AthenaQueryDef("Q2", "Top 5 clientes por volumen comprado",
                        "Clientes con mayor MWh adquirido históricamente.",
                        """
                        SELECT c.nombre, c.segmento, ci.nombre AS ciudad,
                               COUNT(*)             AS num_compras,
                               SUM(f.cantidad_mwh)  AS mwh_total,
                               SUM(f.monto_usd)     AS monto_usd_total
                        FROM   "%s"."fact" f
                        JOIN   "%s"."dim_cliente"      c  ON c.cliente_id = f.cliente_id
                        JOIN   "%s"."dim_ciudad"       ci ON ci.ciudad_id = c.ciudad_id
                        WHERE  f.tipo = 'venta'
                        GROUP  BY c.nombre, c.segmento, ci.nombre
                        ORDER  BY mwh_total DESC
                        LIMIT 5
                        """.formatted(db, db, db)),
                new AthenaQueryDef("Q3", "Margen promedio por tipo de energía",
                        "Diferencia entre precio promedio de venta y de compra por tipo.",
                        """
                        WITH agg AS (
                          SELECT te.codigo AS tipo_energia, f.tipo, AVG(f.precio_usd) AS pp
                          FROM   "%s"."fact" f
                          JOIN   "%s"."dim_tipo_energia" te ON te.tipo_energia_id = f.tipo_energia_id
                          GROUP  BY te.codigo, f.tipo
                        )
                        SELECT tipo_energia,
                               MAX(CASE WHEN tipo='compra' THEN pp END) AS precio_compra_avg,
                               MAX(CASE WHEN tipo='venta'  THEN pp END) AS precio_venta_avg,
                               MAX(CASE WHEN tipo='venta'  THEN pp END)
                                 - MAX(CASE WHEN tipo='compra' THEN pp END) AS margen_absoluto
                        FROM   agg
                        GROUP  BY tipo_energia
                        ORDER  BY margen_absoluto DESC NULLS LAST
                        """.formatted(db, db))
        );
    }

    @Cacheable(cacheNames = "athena", key = "#queryId")
    public AthenaResult run(String queryId) {
        AthenaQueryDef def = queries().stream()
                .filter(q -> q.id().equalsIgnoreCase(queryId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Query desconocida: " + queryId));

        try {
            String execId = athena.startQueryExecution(StartQueryExecutionRequest.builder()
                    .queryString(def.sql())
                    .workGroup(props.getAws().getAthenaWorkgroup())
                    .queryExecutionContext(QueryExecutionContext.builder()
                            .database(props.getAws().getAthenaDatabase()).build())
                    .resultConfiguration(ResultConfiguration.builder()
                            .outputLocation("s3://" + props.getAws().getAthenaResultsBucket() + "/")
                            .build())
                    .build()).queryExecutionId();

            QueryExecutionState state;
            QueryExecution exec;
            do {
                Thread.sleep(1500);
                exec = athena.getQueryExecution(GetQueryExecutionRequest.builder()
                        .queryExecutionId(execId).build()).queryExecution();
                state = exec.status().state();
            } while (state == QueryExecutionState.QUEUED || state == QueryExecutionState.RUNNING);

            if (state != QueryExecutionState.SUCCEEDED) {
                return new AthenaResult(state.toString(), List.of(), List.of(),
                        0, execId, exec.status().stateChangeReason());
            }

            GetQueryResultsResponse res = athena.getQueryResults(GetQueryResultsRequest.builder()
                    .queryExecutionId(execId).build());
            List<Row> rows = res.resultSet().rows();
            List<String> cols = rows.get(0).data().stream()
                    .map(Datum::varCharValue).toList();
            List<Map<String, Object>> dataRows = rows.subList(1, rows.size()).stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < cols.size(); i++) {
                    Datum d = r.data().get(i);
                    m.put(cols.get(i), d == null ? null : d.varCharValue());
                }
                return m;
            }).collect(Collectors.toList());
            long scanned = exec.statistics() == null ? 0 : (exec.statistics().dataScannedInBytes() != null ? exec.statistics().dataScannedInBytes() : 0L);
            return new AthenaResult("SUCCEEDED", cols, dataRows, scanned, execId, null);
        } catch (Exception e) {
            log.error("Athena run failed for {}: {}", queryId, e.getMessage(), e);
            return new AthenaResult("FAILED", List.of(), List.of(), 0, null, e.getMessage());
        }
    }
}
