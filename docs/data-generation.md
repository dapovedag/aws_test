# Generación del set de datos

> Todo el dataset es **ficticio y reproducible**: se genera con [Faker](https://faker.readthedocs.io/) (`faker.es_CO`) usando una semilla fija. Esto garantiza que dos corridas distintas produzcan exactamente el mismo dataset.

## Configuración (variables de entorno)

| Variable | Default | Significado |
|---|---|---|
| `FAKER_SEED` | `42` | Semilla determinística para Faker y `random` |
| `PROVEEDORES` | `50` | Cantidad de proveedores generados |
| `CLIENTES` | `500` | Cantidad de clientes generados |
| `TX_TOTAL` | `10000` | Cantidad total de transacciones (≈ 55% compras / 45% ventas) |
| `FECHA_INICIO` | `2024-01-01` | Inicio de la ventana temporal |
| `FECHA_FIN` | `2026-04-30` | Fin de la ventana temporal |

## Distribuciones usadas

### Proveedores

| Tipo de energía | Peso | Capacidad típica (MW) |
|---|---|---|
| Eólica | 1 | 5 – 350 |
| Hidroeléctrica | 4 | 50 – 2000 |
| Solar | 2 | 5 – 200 |
| Biomasa | 1 | 5 – 80 |
| Nuclear | 0.5 | 500 – 1500 |

Países: Colombia 55%, Argentina 12%, Perú 10%, Chile 10%, Ecuador 8%, Panamá 5%.

### Clientes

| Segmento | Peso | Tipo de identificación |
|---|---|---|
| Residencial | 60% | CC (92%) o CE (8%) |
| Comercial | 25% | NIT (70%) o CC (30%) |
| Industrial | 15% | NIT (100%) |

Asignación a ciudad ponderada por **población** (`weight = log10(poblacion + 1)`) — concentra clientes en ciudades grandes como ocurriría en realidad.

### Transacciones

- **Estacionalidad**: `1.0 + 0.25 · sin(2π · día_del_año / 182.5)` — picos hacia Q1 y Q3.
- **Precios spot** (USD/MWh):

  | Tipo | Precio base | Volatilidad (σ) |
  |---|---|---|
  | Eólica | 45 | 18% |
  | Hidroeléctrica | 55 | 12% |
  | Solar | 42 | 20% |
  | Biomasa | 70 | 15% |
  | Nuclear | 60 | 8% |

- **Margen de venta** sobre compra: residencial +45%, comercial +32%, industrial +18%, modulado con ruido normal de 6%.
- **Cantidad por compra**: `capacidad_proveedor × 24 h × 0.65 (factor de planta) × estacionalidad × ruido normal(σ=0.18)`.
- **Cantidad por venta**: depende del segmento — industrial ~180 MWh, comercial ~25 MWh, residencial ~0.6 MWh.

## Garantías del generador

- **Reproducibilidad**: misma semilla ⇒ mismo dataset bit a bit.
- **Restricción coherencia**: la suma mensual de MWh comprados ≥ MWh vendidos (validado por DQ #15).
- **Unicidad**: IDs externos de cliente (CC/NIT) generados sin colisiones.
- **Integridad referencial**: todo `proveedor_id`, `cliente_id`, `tipo_energia_id` referenciado existe.

## Cómo correr

```bash
cd scripts
pip install -r requirements.txt
# variables RDS_HOST, RDS_USER, RDS_PASSWORD, RDS_DB en .env
python 00_generate_and_load.py
```

Salida esperada (~30 segundos):

```
... · faker-load · ▶ Lectura dimensiones
... · faker-load · ✓ Lectura dimensiones — 0.06s
... · faker-load · ▶ Generando 50 proveedores
...
... · faker-load · ✓ Carga Faker completada exitosamente.
```
