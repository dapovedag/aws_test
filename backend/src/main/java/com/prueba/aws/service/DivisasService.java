package com.prueba.aws.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class DivisasService {

    private final NamedParameterJdbcTemplate jdbc;

    public DivisasService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Par(int parId, String base, String cotizada, String descripcion, boolean activo) {}
    public record Tick(long tickId, int parId, String parLabel,
                       BigDecimal precioCompra, BigDecimal precioVenta, BigDecimal spread,
                       String fuente, OffsetDateTime capturadoEn) {}
    public record Notificacion(long notificacionId, int clienteId, String tipo,
                               int parId, String parLabel,
                               BigDecimal precioReferencia, BigDecimal scoreConfianza,
                               String canal, String mensaje, OffsetDateTime enviadaEn,
                               boolean leida, boolean convertida) {}
    public record Modelo(int modeloId, String version, String algoritmo,
                         Map<String, Object> features, Map<String, Object> metricas,
                         boolean activo, OffsetDateTime entrenadoEn) {}
    public record Dashboard(long paresActivos, long ticksTotales, long ticks24h,
                            long portafolios, long notificacionesTotal,
                            long notificacionesLeidas, long notificacionesConvertidas,
                            BigDecimal scorePromedio, String modeloActivo,
                            BigDecimal ctrPct, BigDecimal conversionPct) {}

    public List<Par> pares() {
        return jdbc.query(
                "SELECT par_id, base, cotizada, descripcion, activo FROM divisas.par_divisa ORDER BY par_id",
                (rs, i) -> new Par(rs.getInt("par_id"), rs.getString("base"),
                        rs.getString("cotizada"), rs.getString("descripcion"), rs.getBoolean("activo"))
        );
    }

    public List<Tick> ticks(String parLabel, int hours, int limit) {
        return jdbc.query(
                """
                SELECT t.tick_id, t.par_id, p.base || '/' || p.cotizada AS par_label,
                       t.precio_compra, t.precio_venta, t.spread, t.fuente, t.capturado_en
                FROM   divisas.tipo_cambio_tick t
                JOIN   divisas.par_divisa p USING (par_id)
                WHERE  (p.base || '/' || p.cotizada) = COALESCE(:par, p.base || '/' || p.cotizada)
                  AND  t.capturado_en >= NOW() - (:hours || ' hours')::interval
                ORDER  BY t.capturado_en DESC
                LIMIT  :limit
                """,
                new MapSqlParameterSource()
                        .addValue("par", parLabel)
                        .addValue("hours", String.valueOf(hours))
                        .addValue("limit", limit),
                (rs, i) -> new Tick(rs.getLong("tick_id"), rs.getInt("par_id"),
                        rs.getString("par_label"), rs.getBigDecimal("precio_compra"),
                        rs.getBigDecimal("precio_venta"), rs.getBigDecimal("spread"),
                        rs.getString("fuente"),
                        rs.getObject("capturado_en", OffsetDateTime.class))
        );
    }

    public List<Notificacion> notificaciones(int limit) {
        return jdbc.query(
                """
                SELECT n.notificacion_id, n.cliente_id, n.tipo,
                       n.par_id, p.base || '/' || p.cotizada AS par_label,
                       n.precio_referencia, n.score_confianza, n.canal,
                       n.mensaje, n.enviada_en, n.leida, n.convertida
                FROM   divisas.notificacion n
                LEFT   JOIN divisas.par_divisa p USING (par_id)
                ORDER  BY n.enviada_en DESC
                LIMIT  :limit
                """,
                new MapSqlParameterSource("limit", limit),
                (rs, i) -> new Notificacion(rs.getLong("notificacion_id"), rs.getInt("cliente_id"),
                        rs.getString("tipo"), rs.getInt("par_id"), rs.getString("par_label"),
                        rs.getBigDecimal("precio_referencia"), rs.getBigDecimal("score_confianza"),
                        rs.getString("canal"), rs.getString("mensaje"),
                        rs.getObject("enviada_en", OffsetDateTime.class),
                        rs.getBoolean("leida"), rs.getBoolean("convertida"))
        );
    }

    public List<Modelo> modelos() {
        return jdbc.query(
                "SELECT modelo_id, version, algoritmo, features, metricas, activo, entrenado_en " +
                "FROM divisas.modelo_recomendacion ORDER BY entrenado_en DESC",
                (rs, i) -> {
                    try {
                        var om = new com.fasterxml.jackson.databind.ObjectMapper();
                        @SuppressWarnings("unchecked")
                        var f = (Map<String, Object>) om.readValue(rs.getString("features"), Map.class);
                        @SuppressWarnings("unchecked")
                        var m = (Map<String, Object>) om.readValue(rs.getString("metricas"), Map.class);
                        return new Modelo(rs.getInt("modelo_id"), rs.getString("version"),
                                rs.getString("algoritmo"), f, m, rs.getBoolean("activo"),
                                rs.getObject("entrenado_en", OffsetDateTime.class));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }

    public Dashboard dashboard() {
        var r = jdbc.queryForMap("SELECT * FROM divisas.vw_dashboard", new MapSqlParameterSource());
        long total = ((Number) r.get("notificaciones_total")).longValue();
        long leidas = ((Number) r.get("notificaciones_leidas")).longValue();
        long conv = ((Number) r.get("notificaciones_convertidas")).longValue();
        BigDecimal ctr = total > 0
                ? BigDecimal.valueOf(leidas * 100.0 / total).setScale(2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal conversionPct = leidas > 0
                ? BigDecimal.valueOf(conv * 100.0 / leidas).setScale(2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new Dashboard(
                ((Number) r.get("pares_activos")).longValue(),
                ((Number) r.get("ticks_totales")).longValue(),
                ((Number) r.get("ticks_24h")).longValue(),
                ((Number) r.get("portafolios")).longValue(),
                total, leidas, conv,
                (BigDecimal) r.get("score_promedio"),
                (String) r.get("modelo_activo"),
                ctr, conversionPct
        );
    }
}
