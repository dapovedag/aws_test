import type { Provider, Client, Transaction, PipelineStage } from './types';

export const providers: Provider[] = [
  { id: 'P-001', nombre: 'Parque Eólico Jepírachi', tipoEnergia: 'eolica', capacidadMW: 19.5, pais: 'Colombia' },
  { id: 'P-002', nombre: 'Hidro Betania', tipoEnergia: 'hidroelectrica', capacidadMW: 540, pais: 'Colombia' },
  { id: 'P-003', nombre: 'Central Nuclear Atucha', tipoEnergia: 'nuclear', capacidadMW: 745, pais: 'Argentina' },
  { id: 'P-004', nombre: 'Eólica Guajira Norte', tipoEnergia: 'eolica', capacidadMW: 210, pais: 'Colombia' },
  { id: 'P-005', nombre: 'Hidro Chivor', tipoEnergia: 'hidroelectrica', capacidadMW: 1000, pais: 'Colombia' },
];

export const clients: Client[] = [
  { tipoId: 'CC', id: '1032456789', nombre: 'María Fernanda Ortiz', ciudad: 'Bogotá', segmento: 'residencial' },
  { tipoId: 'NIT', id: '900123456-7', nombre: 'Textiles del Caribe SAS', ciudad: 'Barranquilla', segmento: 'industrial' },
  { tipoId: 'NIT', id: '800987123-1', nombre: 'Retail Andino S.A.', ciudad: 'Medellín', segmento: 'comercial' },
  { tipoId: 'CC', id: '1120998877', nombre: 'Juan Esteban Ríos', ciudad: 'Cali', segmento: 'residencial' },
  { tipoId: 'NIT', id: '901555222-3', nombre: 'Minera Cerro Verde', ciudad: 'Cartagena', segmento: 'industrial' },
  { tipoId: 'NIT', id: '830445556-9', nombre: 'Hospital San Rafael', ciudad: 'Bucaramanga', segmento: 'comercial' },
];

export const transactions: Transaction[] = [
  { id: 'TX-10001', fecha: '2026-04-14', tipo: 'compra', contraparte: 'Hidro Chivor', cantidadMWh: 1240, precioUSD: 58.40, tipoEnergia: 'hidroelectrica' },
  { id: 'TX-10002', fecha: '2026-04-14', tipo: 'venta', contraparte: 'Textiles del Caribe SAS', cantidadMWh: 82, precioUSD: 71.20, tipoEnergia: 'hidroelectrica' },
  { id: 'TX-10003', fecha: '2026-04-15', tipo: 'compra', contraparte: 'Parque Eólico Jepírachi', cantidadMWh: 18.2, precioUSD: 42.80, tipoEnergia: 'eolica' },
  { id: 'TX-10004', fecha: '2026-04-15', tipo: 'venta', contraparte: 'Retail Andino S.A.', cantidadMWh: 14.6, precioUSD: 79.90, tipoEnergia: 'eolica' },
  { id: 'TX-10005', fecha: '2026-04-16', tipo: 'compra', contraparte: 'Central Nuclear Atucha', cantidadMWh: 720, precioUSD: 61.10, tipoEnergia: 'nuclear' },
  { id: 'TX-10006', fecha: '2026-04-16', tipo: 'venta', contraparte: 'Minera Cerro Verde', cantidadMWh: 310, precioUSD: 82.40, tipoEnergia: 'nuclear' },
  { id: 'TX-10007', fecha: '2026-04-17', tipo: 'venta', contraparte: 'María Fernanda Ortiz', cantidadMWh: 0.42, precioUSD: 94.20, tipoEnergia: 'hidroelectrica' },
  { id: 'TX-10008', fecha: '2026-04-17', tipo: 'venta', contraparte: 'Hospital San Rafael', cantidadMWh: 48.9, precioUSD: 76.50, tipoEnergia: 'eolica' },
];

export const pipeline: PipelineStage[] = [
  { id: 'S-1', service: 'S3', layer: 'raw', descripcion: 's3://datalake-energia/raw/yyyy=2026/mm=04/dd=17/' },
  { id: 'S-2', service: 'Glue', layer: 'staging', descripcion: 'Crawler + schema catalog · staging layer' },
  { id: 'S-3', service: 'Glue', layer: 'processed', descripcion: '3 ETL jobs · output: parquet + snappy' },
  { id: 'S-4', service: 'Athena', layer: 'analytics', descripcion: 'SQL from Python · boto3 + workgroup' },
  { id: 'S-5', service: 'Lake Formation', layer: 'analytics', descripcion: 'Row-level security + governed tables' },
];

export const athenaQuery = `SELECT
    tipo_energia,
    DATE_TRUNC('day', fecha) AS dia,
    SUM(cantidad_mwh)        AS mwh_total,
    AVG(precio_usd)          AS precio_promedio
FROM   processed.transacciones
WHERE  fecha >= current_date - interval '30' day
  AND  tipo   = 'venta'
GROUP  BY 1, 2
ORDER  BY dia DESC, mwh_total DESC;`;

export const kpis = {
  mwhTransados: transactions.reduce((acc, t) => acc + t.cantidadMWh, 0),
  txTotales: transactions.length,
  proveedoresActivos: providers.length,
  clientesActivos: clients.length,
  ciudades: Array.from(new Set(clients.map((c) => c.ciudad))).length,
  tiposEnergia: Array.from(new Set(providers.map((p) => p.tipoEnergia))).length,
};
