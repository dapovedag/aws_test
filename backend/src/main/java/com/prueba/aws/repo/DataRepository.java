package com.prueba.aws.repo;

import com.prueba.aws.dto.Dtos.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Repository
public class DataRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DataRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Proveedor> PROV_MAPPER = (ResultSet rs, int i) -> new Proveedor(
            rs.getInt("proveedor_id"),
            rs.getString("nombre"),
            rs.getString("tipo_energia"),
            rs.getString("pais"),
            rs.getBigDecimal("capacidad_mw"),
            rs.getDate("fecha_alta") != null ? rs.getDate("fecha_alta").toLocalDate() : null,
            rs.getBoolean("activo"));

    private static final RowMapper<Cliente> CLI_MAPPER = (ResultSet rs, int i) -> new Cliente(
            rs.getInt("cliente_id"),
            rs.getString("tipo_id"),
            rs.getString("id_externo"),
            rs.getString("nombre"),
            rs.getString("ciudad"),
            rs.getString("segmento"),
            rs.getDate("fecha_alta") != null ? rs.getDate("fecha_alta").toLocalDate() : null,
            rs.getBoolean("activo"));

    private static final RowMapper<Ciudad> CIU_MAPPER = (ResultSet rs, int i) -> new Ciudad(
            rs.getInt("ciudad_id"),
            rs.getString("nombre"),
            rs.getString("pais"),
            rs.getBigDecimal("lat"),
            rs.getBigDecimal("lon"),
            (Integer) rs.getObject("poblacion"));

    private static final RowMapper<TipoEnergia> TE_MAPPER = (ResultSet rs, int i) -> new TipoEnergia(
            rs.getInt("tipo_energia_id"),
            rs.getString("codigo"),
            rs.getString("nombre"),
            rs.getBigDecimal("factor_co2_kg_mwh"),
            rs.getBoolean("renovable"));

    private static final RowMapper<Transaccion> TX_MAPPER = (ResultSet rs, int i) -> new Transaccion(
            rs.getLong("transaccion_id"),
            rs.getDate("fecha").toLocalDate(),
            rs.getString("tipo"),
            (Integer) rs.getObject("proveedor_id"),
            (Integer) rs.getObject("cliente_id"),
            rs.getString("tipo_energia"),
            rs.getBigDecimal("cantidad_mwh"),
            rs.getBigDecimal("precio_usd"),
            rs.getBigDecimal("monto_usd"));

    public List<Proveedor> proveedores(int limit, int offset) {
        return jdbc.query(
                """
                SELECT p.proveedor_id, p.nombre, te.codigo AS tipo_energia, p.pais,
                       p.capacidad_mw, p.fecha_alta, p.activo
                FROM   core.proveedor p
                JOIN   core.tipo_energia te USING (tipo_energia_id)
                ORDER BY p.proveedor_id
                LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource(Map.of("limit", limit, "offset", offset)),
                PROV_MAPPER);
    }

    public List<Cliente> clientes(int limit, int offset) {
        return jdbc.query(
                """
                SELECT c.cliente_id, c.tipo_id, c.id_externo, c.nombre,
                       ci.nombre AS ciudad, c.segmento::text AS segmento,
                       c.fecha_alta, c.activo
                FROM   core.cliente c
                JOIN   core.ciudad ci USING (ciudad_id)
                ORDER BY c.cliente_id
                LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource(Map.of("limit", limit, "offset", offset)),
                CLI_MAPPER);
    }

    public List<Ciudad> ciudades() {
        return jdbc.query(
                "SELECT ciudad_id, nombre, pais, lat, lon, poblacion FROM core.ciudad ORDER BY pais, nombre",
                CIU_MAPPER);
    }

    public List<TipoEnergia> tiposEnergia() {
        return jdbc.query(
                "SELECT tipo_energia_id, codigo, nombre, factor_co2_kg_mwh, renovable FROM core.tipo_energia ORDER BY tipo_energia_id",
                TE_MAPPER);
    }

    public List<Transaccion> transacciones(int limit, int offset) {
        return jdbc.query(
                """
                SELECT t.transaccion_id, t.fecha, t.tipo::text AS tipo,
                       t.proveedor_id, t.cliente_id,
                       te.codigo AS tipo_energia,
                       t.cantidad_mwh, t.precio_usd, t.monto_usd
                FROM   core.transaccion t
                JOIN   core.tipo_energia te USING (tipo_energia_id)
                ORDER BY t.transaccion_id DESC
                LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource(Map.of("limit", limit, "offset", offset)),
                TX_MAPPER);
    }

    public Kpis kpis() {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM dwh.vw_kpis", new MapSqlParameterSource());
        return new Kpis(
                ((Number) r.get("proveedores_activos")).intValue(),
                ((Number) r.get("clientes_activos")).intValue(),
                ((Number) r.get("ciudades")).intValue(),
                ((Number) r.get("tipos_energia")).intValue(),
                ((Number) r.get("transacciones_totales")).longValue(),
                (java.math.BigDecimal) r.get("mwh_transados"),
                (java.math.BigDecimal) r.get("monto_usd_total"),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    public long countTable(String schemaTable) {
        return jdbc.getJdbcOperations().queryForObject("SELECT COUNT(*) FROM " + schemaTable, Long.class);
    }

    public DatasourceMeta datasource() {
        Map<String, Object> v = jdbc.getJdbcOperations()
                .queryForMap("SELECT version() AS v, current_database() AS db, inet_server_addr()::text AS host");
        String rawVersion = String.valueOf(v.get("v"));
        // Limpia "PostgreSQL 16.6 on aarch64-..." → "PostgreSQL 16.6 (Linux ARM64)"
        String version = rawVersion;
        if (rawVersion.contains("on aarch64")) version = "PostgreSQL " + rawVersion.split(" ")[1] + " (Linux ARM64)";
        else if (rawVersion.contains("on x86_64")) version = "PostgreSQL " + rawVersion.split(" ")[1] + " (Linux x86_64)";

        String db = String.valueOf(v.get("db"));
        String host = v.get("host") == null ? null : String.valueOf(v.get("host"));
        // Host enmascarado: si es IP RFC1918 o null, reportar "VPC privada"
        String safeHost;
        if (host == null || "null".equals(host) || host.isBlank()) {
            safeHost = "RDS dentro de VPC privada (sin IP pública)";
        } else if (host.startsWith("10.") || host.startsWith("172.") || host.startsWith("192.168.")) {
            safeHost = "RDS interno (VPC privada · " + host.replaceAll("\\d+\\.\\d+\\.\\d+\\.", "***.***.***.") + ")";
        } else {
            safeHost = "AWS RDS endpoint (us-east-1)";
        }

        List<String> schemas = List.of("core", "dwh", "audit", "divisas");
        List<SchemaInfo> infos = schemas.stream().map(s -> {
            List<String> tables = jdbc.getJdbcOperations().queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type='BASE TABLE' ORDER BY table_name",
                    String.class, s);
            List<TableCount> tc = tables.stream()
                    .map(t -> new TableCount(t, countTable(s + "." + t)))
                    .toList();
            return new SchemaInfo(s, tc);
        }).toList();

        return new DatasourceMeta("PostgreSQL", version, safeHost, db, infos, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
