package com.prueba.aws.service;

import com.prueba.aws.dto.Dtos.*;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Devuelve el modelo dimensional como objeto + un diagrama Mermaid (para el front).
 * Los datos están hardcoded para reflejar exactamente el DDL en database/00_schema.sql.
 */
@Service
public class DataModelService {

    public DataModel describe() {
        List<TableSpec> tables = List.of(
            new TableSpec("core", "tipo_energia", "Catálogo de tipos de energía",
                List.of(
                    new ColumnSpec("tipo_energia_id", "SMALLSERIAL", false, true, "PK"),
                    new ColumnSpec("codigo", "VARCHAR(20)", false, false, "Único"),
                    new ColumnSpec("nombre", "VARCHAR(60)", false, false, ""),
                    new ColumnSpec("factor_co2_kg_mwh", "NUMERIC(8,2)", false, false, "Factor de emisión"),
                    new ColumnSpec("renovable", "BOOLEAN", false, false, "")
                ), List.of()),
            new TableSpec("core", "ciudad", "Catálogo de ciudades con geocoordenadas",
                List.of(
                    new ColumnSpec("ciudad_id", "SERIAL", false, true, "PK"),
                    new ColumnSpec("nombre", "VARCHAR(80)", false, false, ""),
                    new ColumnSpec("pais", "VARCHAR(60)", false, false, ""),
                    new ColumnSpec("lat", "NUMERIC(9,6)", false, false, ""),
                    new ColumnSpec("lon", "NUMERIC(9,6)", false, false, ""),
                    new ColumnSpec("poblacion", "INTEGER", true, false, "")
                ), List.of()),
            new TableSpec("core", "proveedor", "Generadores de energía",
                List.of(
                    new ColumnSpec("proveedor_id", "SERIAL", false, true, "PK"),
                    new ColumnSpec("nombre", "VARCHAR(120)", false, false, "Único"),
                    new ColumnSpec("tipo_energia_id", "SMALLINT", false, false, "FK"),
                    new ColumnSpec("pais", "VARCHAR(60)", false, false, ""),
                    new ColumnSpec("capacidad_mw", "NUMERIC(10,2)", false, false, "> 0"),
                    new ColumnSpec("fecha_alta", "DATE", false, false, ""),
                    new ColumnSpec("activo", "BOOLEAN", false, false, "")
                ),
                List.of(new Fk("tipo_energia_id", "core.tipo_energia.tipo_energia_id"))),
            new TableSpec("core", "cliente", "Clientes finales (residencial, comercial, industrial)",
                List.of(
                    new ColumnSpec("cliente_id", "SERIAL", false, true, "PK"),
                    new ColumnSpec("tipo_id", "VARCHAR(4)", false, false, "CC|NIT|CE|PP"),
                    new ColumnSpec("id_externo", "VARCHAR(40)", false, false, "Único"),
                    new ColumnSpec("nombre", "VARCHAR(160)", false, false, ""),
                    new ColumnSpec("ciudad_id", "INTEGER", false, false, "FK"),
                    new ColumnSpec("segmento", "ENUM", false, false, "residencial|comercial|industrial"),
                    new ColumnSpec("fecha_alta", "DATE", false, false, ""),
                    new ColumnSpec("activo", "BOOLEAN", false, false, "")
                ),
                List.of(new Fk("ciudad_id", "core.ciudad.ciudad_id"))),
            new TableSpec("core", "transaccion", "Hecho transaccional (compras + ventas)",
                List.of(
                    new ColumnSpec("transaccion_id", "BIGSERIAL", false, true, "PK"),
                    new ColumnSpec("fecha", "DATE", false, false, ""),
                    new ColumnSpec("tipo", "ENUM", false, false, "compra|venta"),
                    new ColumnSpec("proveedor_id", "INTEGER", true, false, "FK (compra)"),
                    new ColumnSpec("cliente_id", "INTEGER", true, false, "FK (venta)"),
                    new ColumnSpec("tipo_energia_id", "SMALLINT", false, false, "FK"),
                    new ColumnSpec("cantidad_mwh", "NUMERIC(12,3)", false, false, "> 0"),
                    new ColumnSpec("precio_usd", "NUMERIC(10,2)", false, false, "> 0"),
                    new ColumnSpec("monto_usd", "NUMERIC(14,2)", false, false, "GENERATED")
                ),
                List.of(
                    new Fk("proveedor_id", "core.proveedor.proveedor_id"),
                    new Fk("cliente_id", "core.cliente.cliente_id"),
                    new Fk("tipo_energia_id", "core.tipo_energia.tipo_energia_id")
                )),
            new TableSpec("dwh", "dim_fecha", "Calendario 2024-2026",
                List.of(
                    new ColumnSpec("fecha_id", "INTEGER", false, true, "YYYYMMDD"),
                    new ColumnSpec("fecha", "DATE", false, false, ""),
                    new ColumnSpec("anio", "SMALLINT", false, false, ""),
                    new ColumnSpec("mes", "SMALLINT", false, false, ""),
                    new ColumnSpec("trimestre", "SMALLINT", false, false, ""),
                    new ColumnSpec("es_finde", "BOOLEAN", false, false, ""),
                    new ColumnSpec("es_feriado", "BOOLEAN", false, false, "")
                ), List.of()),
            new TableSpec("dwh", "fact_transaccion", "Hechos denormalizados (estrella)",
                List.of(
                    new ColumnSpec("transaccion_id", "BIGINT", false, true, "PK"),
                    new ColumnSpec("fecha_id", "INTEGER", false, false, "FK dim_fecha"),
                    new ColumnSpec("tipo_energia_id", "SMALLINT", false, false, "FK"),
                    new ColumnSpec("proveedor_id", "INTEGER", true, false, "FK"),
                    new ColumnSpec("cliente_id", "INTEGER", true, false, "FK"),
                    new ColumnSpec("tipo", "VARCHAR(10)", false, false, ""),
                    new ColumnSpec("cantidad_mwh", "NUMERIC(12,3)", false, false, ""),
                    new ColumnSpec("precio_usd", "NUMERIC(10,2)", false, false, ""),
                    new ColumnSpec("monto_usd", "NUMERIC(14,2)", false, false, "")
                ),
                List.of(
                    new Fk("fecha_id", "dwh.dim_fecha.fecha_id"),
                    new Fk("tipo_energia_id", "dwh.dim_tipo_energia.tipo_energia_id"),
                    new Fk("proveedor_id", "dwh.dim_proveedor.proveedor_id"),
                    new Fk("cliente_id", "dwh.dim_cliente.cliente_id")
                ))
        );

        String mermaid = """
            erDiagram
              tipo_energia ||--o{ proveedor : "produce"
              tipo_energia ||--o{ transaccion : "clasifica"
              ciudad ||--o{ cliente : "ubica"
              proveedor ||--o{ transaccion : "vende_a"
              cliente ||--o{ transaccion : "compra_a"
              dim_fecha ||--o{ fact_transaccion : "ocurre_en"
              dim_tipo_energia ||--o{ fact_transaccion : "tipo"
              dim_proveedor ||--o{ fact_transaccion : "origen"
              dim_cliente ||--o{ fact_transaccion : "destino"
              dim_ciudad ||--o{ dim_cliente : "ubica"
            """;

        return new DataModel("1.0.0", "Estrella + OLTP", tables, mermaid);
    }
}
