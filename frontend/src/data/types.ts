export type EnergyType = 'eolica' | 'hidroelectrica' | 'nuclear';
export type ClientSegment = 'residencial' | 'comercial' | 'industrial';
export type TxSide = 'compra' | 'venta';

export interface Provider {
  id: string;
  nombre: string;
  tipoEnergia: EnergyType;
  capacidadMW: number;
  pais: string;
}

export interface Client {
  tipoId: 'CC' | 'NIT';
  id: string;
  nombre: string;
  ciudad: string;
  segmento: ClientSegment;
}

export interface Transaction {
  id: string;
  fecha: string;
  tipo: TxSide;
  contraparte: string;
  cantidadMWh: number;
  precioUSD: number;
  tipoEnergia: EnergyType;
}

export interface PipelineStage {
  id: string;
  service: 'S3' | 'Glue' | 'Athena' | 'Lake Formation' | 'Redshift';
  layer: 'raw' | 'staging' | 'processed' | 'analytics';
  descripcion: string;
}
