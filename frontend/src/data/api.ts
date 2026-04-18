/**
 * Cliente HTTP del backend Spring Boot. Cae a mocks si la API no responde,
 * para que el front sea funcional incluso sin AWS al alcance.
 */
import { providers as mockProveedores, clients as mockClientes, athenaQuery } from './mock-data';

const BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

export const repoUrl = import.meta.env.VITE_GITHUB_REPO_URL ?? 'https://github.com/dapovedag/aws_test';

async function get<T>(path: string, fallback: T): Promise<{ data: T; live: boolean }> {
  if (!BASE) return { data: fallback, live: false };
  try {
    const res = await fetch(`${BASE}${path}`, { headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(`${res.status}`);
    return { data: (await res.json()) as T, live: true };
  } catch (e) {
    console.warn(`[api] ${path} → fallback (${(e as Error).message})`);
    return { data: fallback, live: false };
  }
}

export type Proveedor = { proveedorId: number; nombre: string; tipoEnergia: string; pais: string;
                          capacidadMw: string | number; fechaAlta: string; activo: boolean };
export type Cliente = { clienteId: number; tipoId: string; idExterno: string; nombre: string;
                        ciudad: string; segmento: string; fechaAlta: string; activo: boolean };
export type Transaccion = { transaccionId: number; fecha: string; tipo: string;
                            proveedorId: number | null; clienteId: number | null;
                            tipoEnergia: string; cantidadMwh: string | number;
                            precioUsd: string | number; montoUsd: string | number };
export type Kpis = { proveedoresActivos: number; clientesActivos: number; ciudades: number;
                     tiposEnergia: number; transaccionesTotales: number;
                     mwhTransados: string | number; montoUsdTotal: string | number };
export type Datasource = { engine: string; version: string; host: string; database: string;
                           schemas: { schema: string; tables: { table: string; rowCount: number }[] }[];
                           checkedAt: string };
export type DataModel = { version: string; style: string; tables: any[]; mermaid: string };
export type DqCheck = { id: number; category: string; name: string; description: string;
                        expected: string; severity: string; actual: any; passed: boolean | null;
                        message: string };
export type DqSummary = { generatedAt: string | null; total: number; passed: number; failed: number;
                          passRate: number; checks: DqCheck[]; runtimeSec: number; schemaVersion: string };
export type AthenaQueryDef = { id: string; title: string; description: string; sql: string };
export type AthenaResult = { state: string; columns: string[]; rows: any[];
                              dataScannedBytes: number; executionId?: string; error?: string };
export type CostRow = { resource: string; mode: string; costMonth1: string; costSteadyState: string };
export type CostReport = { rows: CostRow[]; totalMonth1: string; totalSteady: string; generatedAt: string };
export type ExerciseDoc = { id: string; title: string; content: string; sha?: string; url?: string };

// ----- Fallbacks (cuando no hay API) ----------------------------------
const proveedoresFallback: Proveedor[] = mockProveedores.map((p, i) => ({
  proveedorId: i + 1, nombre: p.nombre, tipoEnergia: p.tipoEnergia, pais: p.pais,
  capacidadMw: p.capacidadMW, fechaAlta: '2024-01-01', activo: true,
}));
const clientesFallback: Cliente[] = mockClientes.map((c, i) => ({
  clienteId: i + 1, tipoId: c.tipoId, idExterno: c.id, nombre: c.nombre,
  ciudad: c.ciudad, segmento: c.segmento, fechaAlta: '2024-01-01', activo: true,
}));
const kpisFallback: Kpis = {
  proveedoresActivos: 5, clientesActivos: 6, ciudades: 6, tiposEnergia: 3,
  transaccionesTotales: 8, mwhTransados: '2434', montoUsdTotal: '167500',
};

// ----- Public API ------------------------------------------------------
export const api = {
  async health() { return get<any>('/api/health', { status: 'offline' }); },
  async kpis() { return get<Kpis>('/api/kpis', kpisFallback); },
  async datasource() {
    return get<Datasource>('/api/datasource', {
      engine: 'PostgreSQL', version: 'mock', host: 'offline',
      database: 'datalake', schemas: [], checkedAt: new Date().toISOString(),
    });
  },
  async proveedores(limit = 200) { return get<Proveedor[]>(`/api/proveedores?limit=${limit}`, proveedoresFallback); },
  async clientes(limit = 200) { return get<Cliente[]>(`/api/clientes?limit=${limit}`, clientesFallback); },
  async transacciones(limit = 50) { return get<Transaccion[]>(`/api/transacciones?limit=${limit}`, []); },
  async dataModel() {
    return get<DataModel>('/api/data-model', {
      version: 'fallback', style: 'Estrella + OLTP', tables: [],
      mermaid: 'erDiagram\n  proveedor ||--o{ transaccion : "vende"',
    });
  },
  async dataQuality() {
    return get<DqSummary>('/api/data-quality', {
      generatedAt: null, total: 0, passed: 0, failed: 0, passRate: 0,
      checks: [], runtimeSec: 0, schemaVersion: 'n/a',
    });
  },
  async athenaQueries() {
    return get<AthenaQueryDef[]>('/api/athena/queries', [
      { id: 'Q1', title: 'Resumen mensual de ventas', description: '(offline mock)', sql: athenaQuery },
    ]);
  },
  async athenaRun(id: string): Promise<AthenaResult> {
    if (!BASE) {
      return { state: 'OFFLINE', columns: [], rows: [], dataScannedBytes: 0,
               error: 'API no configurada (VITE_API_BASE_URL).' };
    }
    const res = await fetch(`${BASE}/api/athena/run/${id}`, { method: 'POST' });
    return res.json();
  },
  async cost() {
    return get<CostReport>('/api/cost', {
      rows: [], totalMonth1: '~$2', totalSteady: '~$25/mes', generatedAt: new Date().toISOString(),
    });
  },
  async downloads() {
    return get<{ report: { url: string } }>('/api/downloads', {
      report: { url: '#' },
    });
  },
  async exercise(id: string): Promise<ExerciseDoc> {
    if (!BASE) return { id, title: id, content: '## Offline\nAPI no configurada.', };
    const res = await fetch(`${BASE}/api/exercises/${id}`);
    return res.json();
  },
  async saveExercise(id: string, content: string, token: string): Promise<any> {
    if (!BASE) throw new Error('API no configurada');
    const res = await fetch(`${BASE}/api/exercises/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-Edit-Token': token },
      body: JSON.stringify({ content, message: `edit: actualizar ${id} desde el front` }),
    });
    if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`);
    return res.json();
  },
  exportCsvUrl(schema: string, table: string, max = 10000) {
    return `${BASE}/api/export/${schema}/${table}.csv?max=${max}`;
  },
  isLive: () => Boolean(BASE),
  baseUrl: () => BASE,
  repoUrl,
};
