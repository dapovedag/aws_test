package com.prueba.aws.service;

import com.opencsv.CSVWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Streamea CSV de cualquier tabla del schema core o dwh, con un cap configurable.
 */
@Service
public class CsvExportService {

    private static final Set<String> ALLOWED_TABLES = Set.of(
            "core.tipo_energia", "core.ciudad", "core.proveedor", "core.cliente", "core.transaccion",
            "dwh.dim_fecha", "dwh.dim_tipo_energia", "dwh.dim_ciudad", "dwh.dim_proveedor",
            "dwh.dim_cliente", "dwh.fact_transaccion",
            "dwh.vw_resumen_mensual", "dwh.vw_top_clientes", "dwh.vw_margen_energia"
    );

    private final JdbcTemplate jdbc;

    public CsvExportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAllowed(String table) {
        return ALLOWED_TABLES.contains(table);
    }

    public List<String> allowedTables() {
        return ALLOWED_TABLES.stream().sorted().toList();
    }

    public void streamCsv(String table, int max, Consumer<String[]> rowConsumer) {
        if (!isAllowed(table)) throw new IllegalArgumentException("Tabla no permitida: " + table);
        int safeMax = Math.min(Math.max(max, 100), 100_000);
        String sql = "SELECT * FROM " + table + " LIMIT " + safeMax;
        jdbc.query(sql, (ResultSet rs) -> {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            String[] header = new String[n];
            for (int i = 0; i < n; i++) header[i] = md.getColumnLabel(i + 1);
            rowConsumer.accept(header);
            while (rs.next()) {
                String[] row = new String[n];
                for (int i = 0; i < n; i++) {
                    Object v = rs.getObject(i + 1);
                    row[i] = v == null ? "" : String.valueOf(v);
                }
                rowConsumer.accept(row);
            }
        });
    }

    public void writeCsv(String table, int max, java.io.OutputStream out) {
        try (CSVWriter w = new CSVWriter(new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8)))) {
            streamCsv(table, max, w::writeNext);
        } catch (Exception e) {
            throw new RuntimeException("CSV export failed: " + e.getMessage(), e);
        }
    }
}
