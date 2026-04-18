# Pruebas de calidad de datos

> Suite de **15 chequeos** que se ejecuta sobre el RDS poblado. La salida queda en:
> - `scripts/output/data_quality_summary.json` — consumido por el front (`/api/data-quality`)
> - `scripts/output/data_quality_report.html` — vista visual, subida a S3 público
>
> Implementado con SQL puro (no Great Expectations completo) para minimizar dependencias y mantener la suite simple, auditable y rápida (~3 segundos).

## Categorías

| # | Categoría | Para qué sirve |
|---|---|---|
| Completitud | Asegura que los campos esenciales no estén nulos |
| Unicidad | Detecta duplicados en claves naturales |
| Integridad referencial | Verifica que toda FK apunte a una fila válida |
| Dominio | Verifica que enums y catálogos contengan solo valores conocidos |
| Rango | Verifica que valores numéricos estén dentro de bandas razonables |
| Cobertura temporal | Verifica que el dataset cubra todos los meses sin huecos grandes |
| Sanidad lógica | Verifica que el dominio de negocio se cumpla (compras ≥ ventas por mes) |

## Listado de tests

| # | Categoría | Test | Severidad | Umbral |
|---|---|---|---|---|
| 1 | Completitud | `proveedor.nombre` no nulo | critical | 0 nulos |
| 2 | Completitud | `cliente.id_externo` no nulo | critical | 0 nulos |
| 3 | Unicidad | `proveedor.nombre` único | high | 0 duplicados |
| 4 | Unicidad | `cliente.id_externo` único | critical | 0 duplicados |
| 5 | Unicidad | `transaccion.transaccion_id` único | critical | 0 duplicados |
| 6 | Integridad | FK `transaccion → proveedor o cliente` válida | critical | 0 huérfanas |
| 7 | Integridad | FK `transaccion → tipo_energia` válida | critical | 0 inválidas |
| 8 | Integridad | FK `cliente → ciudad` válida | critical | 0 inválidas |
| 9 | Dominio | `cliente.segmento` ∈ {residencial,comercial,industrial} | high | 100% válidos |
| 10 | Dominio | `tipo_energia.codigo` ∈ catálogo esperado | high | 100% válidos |
| 11 | Rango | `proveedor.capacidad_mw` en `(0, 5000]` | medium | 100% en rango |
| 12 | Rango | `transaccion.cantidad_mwh > 0` | high | 100% positivos |
| 13 | Rango | `transaccion.precio_usd` entre 10 y 500 | medium | ≥ 99% en rango |
| 14 | Cobertura | sin huecos > 7 días en transacciones | medium | 0 huecos |
| 15 | Sanidad lógica | `compras_mwh ≥ ventas_mwh` por mes | high | 0 meses inválidos |

## Severidades

- **critical** → bloquea el pipeline (en producción haría rollback automático)
- **high** → no bloquea pero requiere revisión humana antes del próximo deploy
- **medium** → tolerable; queda como warning
- **low** → informativo

## Cómo correr

```bash
python scripts/01_data_quality_tests.py
# → output/data_quality_summary.json + output/data_quality_report.html
```

## Próximos pasos sugeridos

- Migrar a [Great Expectations](https://greatexpectations.io/) o [Soda Core](https://www.soda.io/) para una suite versionada con expectativas declarativas.
- Integrar con CloudWatch Alarms para alertar en falla.
- Añadir tests post-ETL contra Athena (`AVG(monto_usd)` esperado, recuento de filas vs RDS, etc.).
