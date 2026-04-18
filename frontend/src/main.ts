import './styles/tokens.css';
import './styles/app.css';
import { api } from './data/api';
import { buildTerrain } from './three/terrain';
import { marked } from 'marked';
import mermaid from 'mermaid';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
const $ = <T extends HTMLElement = HTMLElement>(sel: string) => document.querySelector(sel) as T | null;

const BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

mermaid.initialize({ startOnLoad: false, theme: 'neutral', fontFamily: 'IBM Plex Sans, sans-serif' });

function updateStatus(live: boolean) {
  const el = $('#api-status');
  if (!el) return;
  el.textContent = live ? '● Live · RDS conectado' : '○ Offline (mocks)';
  el.classList.toggle('live', live);
  el.classList.toggle('offline', !live);
}

async function renderMermaid(el: HTMLElement, code: string) {
  try {
    const { svg } = await mermaid.render(`m-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, code);
    el.innerHTML = svg;
    enableZoom(el);
  } catch (e) {
    el.innerHTML = `<pre>${escapeHtml(code)}</pre>`;
  }
}

/** Habilita zoom + pan en el SVG hijo del contenedor (clase .zoomable). */
function enableZoom(container: HTMLElement) {
  if (!container.classList.contains('zoomable')) container.classList.add('zoomable');
  const svg = container.querySelector('svg');
  if (!svg) return;
  let scale = 1, tx = 0, ty = 0;
  const apply = () => { (svg as any).style.transform = `translate(${tx}px, ${ty}px) scale(${scale})`; };
  const old = container.querySelector('.zoom-controls');
  if (old) old.remove();
  const controls = document.createElement('div');
  controls.className = 'zoom-controls';
  controls.innerHTML = `
    <button data-act="in" title="Zoom in">+</button>
    <button data-act="out" title="Zoom out">−</button>
    <button data-act="reset" class="reset" title="Reset">1:1</button>`;
  container.appendChild(controls);
  controls.querySelectorAll<HTMLButtonElement>('button').forEach(b => {
    b.onclick = (e) => {
      e.stopPropagation();
      const act = b.dataset.act;
      if (act === 'in') scale = Math.min(scale * 1.25, 6);
      else if (act === 'out') scale = Math.max(scale / 1.25, 0.3);
      else { scale = 1; tx = 0; ty = 0; }
      apply();
    };
  });
  container.addEventListener('wheel', (e) => {
    e.preventDefault();
    const delta = e.deltaY < 0 ? 1.1 : 0.9;
    scale = Math.max(0.3, Math.min(6, scale * delta));
    apply();
  }, { passive: false });
  let dragging = false, sx = 0, sy = 0;
  container.addEventListener('pointerdown', (e) => {
    if ((e.target as HTMLElement).closest('.zoom-controls')) return;
    dragging = true; sx = e.clientX - tx; sy = e.clientY - ty;
    container.setPointerCapture(e.pointerId);
  });
  container.addEventListener('pointermove', (e) => {
    if (!dragging) return;
    tx = e.clientX - sx; ty = e.clientY - sy; apply();
  });
  container.addEventListener('pointerup', () => { dragging = false; });
  apply();
}

function fmtNum(v: any, max = 2): string {
  const n = Number(v);
  if (Number.isNaN(n)) return String(v ?? '');
  return new Intl.NumberFormat('es-CO', { maximumFractionDigits: max }).format(n);
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!));
}

// Render markdown via backend proxy (resuelve 404 del repo privado)
async function fetchMarkdown(name: string): Promise<string> {
  if (!BASE) return `_(API offline · doc/${name} no disponible)_`;
  try {
    const res = await fetch(`${BASE}/api/docs/${name}`);
    if (!res.ok) return `_(documento ${name} no encontrado · ${res.status})_`;
    return await res.text();
  } catch (e) {
    return `_(error de red al cargar ${name}: ${(e as Error).message})_`;
  }
}

async function renderMarkdownInto(elId: string, name: string) {
  const el = $(`#${elId}`);
  if (!el) return;
  const md = await fetchMarkdown(name);
  el.innerHTML = await marked.parse(md);
  // Re-renderiza bloques mermaid que marked dejó como <pre><code class="language-mermaid">
  const codes = el.querySelectorAll<HTMLElement>('pre code.language-mermaid');
  for (const code of Array.from(codes)) {
    const wrapper = document.createElement('div');
    wrapper.className = 'er-diagram';
    code.parentElement!.replaceWith(wrapper);
    await renderMermaid(wrapper, code.textContent || '');
  }
}

// ---------------------------------------------------------------------------
// Terreno 3D (mantiene el Noir Press)
// ---------------------------------------------------------------------------
const canvas = document.getElementById('terrain-canvas') as HTMLCanvasElement | null;
if (canvas) {
  buildTerrain({
    canvas,
    colors: {
      background: '#ffffff', fog: '#ffffff', terrain: '#ffffff',
      contour: '#0a0a0a', contourOpacity: 0.65,
      flow: '#c8321f', flowOpacity: 0.8, pole: '#0a0a0a',
      marker: { eolica: '#ffffff', hidroelectrica: '#0a0a0a', nuclear: '#c8321f', client: '#0a0a0a' },
      ambient: '#ffffff', key: '#ffffff', wireframeOnly: true,
    },
  });
}

// ---------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------
function wireTabs() {
  const tabs = document.querySelectorAll<HTMLButtonElement>('.tabs .tab');
  const panes = document.querySelectorAll<HTMLElement>('.tab-pane');
  const initial = location.hash?.replace('#', '') || localStorage.getItem('tab') || 'resumen';
  const activate = (key: string) => {
    tabs.forEach(b => b.classList.toggle('active', b.dataset.tab === key));
    panes.forEach(p => p.classList.toggle('active', p.dataset.pane === key));
    localStorage.setItem('tab', key);
    history.replaceState(null, '', '#' + key);
    window.scrollTo({ top: 0, behavior: 'instant' });
  };
  tabs.forEach(b => b.addEventListener('click', () => activate(b.dataset.tab!)));
  if (Array.from(tabs).some(t => t.dataset.tab === initial)) activate(initial);
}

// ---------------------------------------------------------------------------
// KPIs hero
// ---------------------------------------------------------------------------
async function renderKpis() {
  const { data, live } = await api.kpis();
  updateStatus(live);
  const el = $('#kpis-coords');
  if (el) {
    el.textContent = `${fmtNum(data.transaccionesTotales)} tx · ${fmtNum(data.mwhTransados)} MWh · ${fmtNum(data.clientesActivos)} clientes`;
  }
}

// ---------------------------------------------------------------------------
// Resumen · Datasource RDS
// ---------------------------------------------------------------------------
async function renderDatasource() {
  const { data } = await api.datasource();
  const el = $('#datasource-card');
  if (!el) return;
  const schemas = data.schemas ?? [];
  const totalTables = schemas.reduce((acc, s) => acc + s.tables.length, 0);
  const totalRows = schemas.reduce((acc, s) => acc + s.tables.reduce((a, t) => a + t.rowCount, 0), 0);
  el.innerHTML = `
    <div class="ds-row">
      <span><strong>Engine</strong> ${data.engine} ${data.version.split(' ').slice(0, 2).join(' ')}</span>
      <span><strong>Base</strong> ${data.database}</span>
      <span><strong>Host</strong> ${data.host}</span>
    </div>
    <div class="ds-row">
      <span><strong>Schemas</strong> ${schemas.length}</span>
      <span><strong>Tablas</strong> ${totalTables}</span>
      <span><strong>Filas totales</strong> ${fmtNum(totalRows)}</span>
      <span><strong>Checkpoint</strong> ${new Date(data.checkedAt).toLocaleString('es-CO')}</span>
    </div>
    <div class="ds-tables">
      ${schemas.map(s => `
        <h4>${s.schema}</h4>
        <ul>${s.tables.map(t => `<li>${t.table}<span class="count">${fmtNum(t.rowCount)}</span></li>`).join('')}</ul>
      `).join('')}
    </div>`;
}

// ---------------------------------------------------------------------------
// Resumen · Modelo de datos
// ---------------------------------------------------------------------------
async function renderDataModel() {
  const { data } = await api.dataModel();
  await renderMermaid($('#er-mermaid')!, data.mermaid);
  const el = $('#tables-list');
  if (!el) return;
  el.innerHTML = (data.tables ?? []).map((t: any) => `
    <div class="tbl">
      <div class="h">${t.schema}.${t.name}</div>
      <div class="desc">${t.description ?? ''}</div>
      <ul>${t.columns.map((c: any) =>
        `<li class="${c.primaryKey ? 'pk' : ''}">${c.name} <span style="color:#6a6a6a">${c.type}</span></li>`
      ).join('')}</ul>
    </div>`).join('');
}

// ---------------------------------------------------------------------------
// Resumen · Generación volúmenes
// ---------------------------------------------------------------------------
async function renderGenerationVolumes() {
  const { data } = await api.datasource();
  const tbl = data.schemas.flatMap(s => s.tables.map(t => ({ ...t, schema: s.schema })));
  const el = $('#gen-volumes');
  if (!el) return;
  const rows: [string, any][] = [
    ['Proveedores', tbl.find(t => t.schema === 'core' && t.table === 'proveedor')?.rowCount ?? '—'],
    ['Clientes', tbl.find(t => t.schema === 'core' && t.table === 'cliente')?.rowCount ?? '—'],
    ['Transacciones', tbl.find(t => t.schema === 'core' && t.table === 'transaccion')?.rowCount ?? '—'],
    ['Tipos de energía', tbl.find(t => t.schema === 'core' && t.table === 'tipo_energia')?.rowCount ?? '—'],
    ['Ciudades', tbl.find(t => t.schema === 'core' && t.table === 'ciudad')?.rowCount ?? '—'],
    ['Días (dim_fecha)', tbl.find(t => t.schema === 'dwh' && t.table === 'dim_fecha')?.rowCount ?? '—'],
  ];
  el.innerHTML = rows.map(([k, v]) => `<li><span>${k}</span><strong>${fmtNum(v)}</strong></li>`).join('');
}

// (la sección de pruebas de calidad fue removida — no es requisito de la prueba)

// ---------------------------------------------------------------------------
// Resumen · Costo MEDIDO en tiempo real (Cost Explorer)
// ---------------------------------------------------------------------------
async function renderCostRealtime() {
  if (!BASE) return;

  // 1) Banner Free Tier dinámico (días restantes + créditos restantes + cargo real)
  const banner = $('#cost-credit-banner');
  try {
    const res = await fetch(`${BASE}/api/free-tier/status`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const ft = await res.json();
    if (!banner || ft?.daysRemaining == null) throw new Error('payload inválido');
    banner.innerHTML = `
      <div class="cb-headline">
        <div class="cb-tag">Estado del plan gratuito · datos en vivo desde el backend</div>
        <div class="cb-value">${ft.outOfPocketUsd}</div>
        <div class="cb-note">${ft.summary}</div>
      </div>
      <table class="cb-table">
        <tr><td>Días restantes de Free Tier</td><td><strong>${ft.daysRemaining} días</strong></td></tr>
        <tr><td>Fin del Free Tier</td><td>${ft.freeTierEndsOn}</td></tr>
        <tr><td>Créditos AWS consumidos hasta hoy</td><td>${ft.creditsConsumedUsd}</td></tr>
        <tr class="cb-balance"><td>Créditos AWS restantes</td><td><strong>${ft.creditsRemainingUsd}</strong></td></tr>
      </table>`;
    banner.style.display = 'block';
  } catch (e) {
    console.warn('[free-tier]', (e as Error).message);
    if (banner) banner.style.display = 'none';
  }

  // 2) Inventario de servicios desplegados (sin costos)
  const SERVICES = [
    { svc: 'EC2',              name: 't3.micro',             role: 'Backend Spring Boot · Caddy reverse proxy', icon: 'server' },
    { svc: 'RDS',              name: 'db.t4g.micro',         role: 'PostgreSQL 16 · 4 schemas (core · dwh · audit · divisas)', icon: 'database' },
    { svc: 'S3',               name: '6 buckets',            role: 'raw · staging · processed · public · athena-results · glue-scripts', icon: 'bucket' },
    { svc: 'Glue Crawlers',    name: '2 crawlers',           role: 'Catalogación automática raw + processed', icon: 'spider' },
    { svc: 'Glue ETL',         name: '3 jobs PySpark',       role: 'transform_dimensions · transform_fact · transform_aggregates', icon: 'cog' },
    { svc: 'Glue Catalog',     name: 'database datalake_energia', role: '11 tablas registradas', icon: 'book' },
    { svc: 'Athena',           name: 'workgroup prueba-aws-wg', role: '3 queries SQL definidas', icon: 'magnify' },
    { svc: 'Lake Formation',   name: 'LF-Tags',              role: 'Sensitivity + Domain · gobierno fine-grained', icon: 'shield' },
    { svc: 'Redshift',         name: 'Serverless namespace', role: 'opt-in · 8 RPU workgroup auto-pause', icon: 'warehouse' },
    { svc: 'IAM',              name: '4 roles least-privilege', role: 'Glue · EC2 backend · Redshift · Lake Formation', icon: 'key' },
    { svc: 'Secrets Manager',  name: '1 secret',             role: 'Credenciales RDS rotables', icon: 'lock' },
    { svc: 'Elastic IP',       name: 'attached a EC2',       role: 'URL estable backend', icon: 'pin' },
    { svc: 'CloudWatch Logs',  name: 'basic',                role: 'Logs Glue + EC2 + RDS', icon: 'chart' },
    { svc: 'Terraform',        name: '63 recursos',          role: 'IaC · 7 archivos .tf por dominio', icon: 'blueprint' },
  ];

  const grid = $('#deployed-services');
  if (grid) {
    grid.innerHTML = SERVICES.map(s => `
      <div class="ds-card">
        <div class="ds-mark"><svg viewBox="0 0 16 16" width="14" height="14"><path fill="currentColor" d="M6.5 11.5L3 8l1-1 2.5 2.5L12 4l1 1z"/></svg></div>
        <div class="ds-body">
          <div class="ds-svc">${s.svc}</div>
          <div class="ds-name">${s.name}</div>
          <div class="ds-role">${s.role}</div>
        </div>
      </div>`).join('');
  }
}

// ---------------------------------------------------------------------------
// Ej.1 R2 · ETL jobs
// ---------------------------------------------------------------------------
function renderEtlJobs() {
  const el = $('#etl-jobs');
  if (!el) return;
  const jobs = [
    { name: 'transform_dimensions', desc: 'Lee dim_proveedor, dim_cliente, dim_ciudad, dim_tipo_energia desde raw. Aplica SCD-1 (último loaded_at gana). Materializa a Parquet+Snappy.', out: 's3://prueba-aws-processed/dim_*/' },
    { name: 'transform_fact', desc: 'Lee transacciones crudas. Deriva fecha_id (YYYYMMDD), recalcula monto_usd, particiona por (anio, mes). 10K filas → Parquet.', out: 's3://prueba-aws-processed/fact/anio=*/mes=*/' },
    { name: 'transform_aggregates', desc: 'Pre-agrega métricas mensuales (anio × mes × tipo_energia × tipo). Permite a Athena/Redshift responder sin escanear el fact completo.', out: 's3://prueba-aws-processed/agg_resumen_mensual/' },
  ];
  el.innerHTML = jobs.map(j => `
    <div class="etl-job">
      <div class="name">prueba-aws-${j.name}</div>
      <div class="desc">${j.desc}</div>
      <div class="out">→ ${j.out}</div>
    </div>`).join('');
}

// ---------------------------------------------------------------------------
// Ej.1 R3 · Glue tables
// ---------------------------------------------------------------------------
function renderGlueTables() {
  const el = $('#glue-tables');
  if (!el) return;
  const tables = [
    'ciudad', 'cliente', 'proveedor', 'tipo_energia', 'transaccion',
    'dim_ciudad', 'dim_cliente', 'dim_proveedor', 'dim_tipo_energia', 'fact', 'agg_resumen_mensual'
  ];
  el.innerHTML = tables.map(t => `<li><code>${t}</code></li>`).join('');
}

// ---------------------------------------------------------------------------
// Ej.1 R4 · Athena live
// ---------------------------------------------------------------------------
async function renderAthena() {
  const { data } = await api.athenaQueries();
  const el = $('#athena-grid');
  if (!el) return;
  el.innerHTML = data.map(q => `
    <div class="athena-card" data-id="${q.id}">
      <div class="ah"><span class="id">${q.id}</span><span class="ttl">${q.title}</span></div>
      <div class="desc">${q.description}</div>
      <pre>${escapeHtml(q.sql)}</pre>
      <button class="run-btn">Ejecutar en Athena</button>
      <div class="athena-result"></div>
      <div class="meta-line"></div>
    </div>`).join('');
  document.querySelectorAll<HTMLButtonElement>('.athena-card .run-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const card = btn.closest<HTMLElement>('.athena-card')!;
      const id = card.dataset.id!;
      const out = card.querySelector<HTMLElement>('.athena-result')!;
      const meta = card.querySelector<HTMLElement>('.meta-line')!;
      const t0 = performance.now();
      btn.disabled = true;
      btn.textContent = 'Ejecutando en Athena...';
      out.innerHTML = '<div class="loader">consultando S3 · esperando resultado...</div>';
      meta.textContent = '';
      try {
        const res = await api.athenaRun(id);
        const elapsed = ((performance.now() - t0) / 1000).toFixed(1);
        if (res.state !== 'SUCCEEDED') {
          out.innerHTML = `<p class="err">Estado: ${res.state}. ${res.error ?? ''}</p>`;
        } else {
          const limit = 50;
          const shown = res.rows.slice(0, limit);
          out.innerHTML = `
            <table><thead><tr>${res.columns.map(c => `<th>${c}</th>`).join('')}</tr></thead>
            <tbody>${shown.map(r =>
              `<tr>${res.columns.map(c => `<td>${escapeHtml(String(r[c] ?? ''))}</td>`).join('')}</tr>`
            ).join('')}</tbody></table>
            ${res.rows.length > limit ? `<p class="muted">+ ${res.rows.length - limit} filas no mostradas</p>` : ''}`;
          const kb = (res.dataScannedBytes / 1024).toFixed(1);
          meta.innerHTML = `<span>${fmtNum(res.rows.length)} filas</span> · <span>${kb} KB escaneados</span> · <span>${elapsed}s</span> · <code>${res.executionId?.slice(0, 12) ?? 'n/a'}</code>`;
        }
      } catch (e) {
        out.innerHTML = `<p class="err">${(e as Error).message}</p>`;
      } finally {
        btn.disabled = false;
        btn.textContent = 'Ejecutar en Athena';
      }
    });
  });
}

// ---------------------------------------------------------------------------
// Ej.1 R5 · Schemas separados
// ---------------------------------------------------------------------------
async function renderSchemasSeparados() {
  const el = $('#schemas-list');
  if (!el) return;
  try {
    const res = await fetch(`${BASE}/api/datasource`);
    const data = await res.json();
    const dom: Record<string, string> = {
      core: 'Ej.1 · Sistema OLTP energía (proveedor, cliente, transacción)',
      dwh: 'Ej.1 · Modelo estrella analítico (dim_*, fact_*, vw_*)',
      audit: 'Ej.1 · Trazabilidad de pipeline (carga_log)',
      divisas: 'Ej.2 · Plataforma divisas (par, ticks, portafolio, notificación, modelo)',
    };
    el.innerHTML = (data.schemas ?? []).map((s: any) => `
      <div class="schema-card">
        <div class="schema-head">
          <span class="schema-name"><code>${s.schema}</code></span>
          <span class="schema-tablas">${s.tables.length} tablas</span>
        </div>
        <div class="schema-desc">${dom[s.schema] ?? '—'}</div>
        <div class="schema-tables">${s.tables.map((t: any) =>
          `<span class="tbl-pill">${t.table} <span class="count">${fmtNum(t.rowCount)}</span></span>`
        ).join('')}</div>
      </div>`).join('');
  } catch (e) {
    el.innerHTML = `<p class="muted">No se pudo cargar schemas: ${(e as Error).message}</p>`;
  }
}

// ---------------------------------------------------------------------------
// Ej.1 Plus · Terraform recursos (lista estática textual)
// ---------------------------------------------------------------------------
function renderTfResources() {
  const el = $('#tf-recursos');
  if (!el) return;
  el.textContent = `aws_db_instance.postgres
aws_eip.backend
aws_instance.backend
aws_glue_catalog_database.datalake
aws_glue_crawler.raw
aws_glue_crawler.processed
aws_glue_job.etl["transform-dimensions"]
aws_glue_job.etl["transform-fact"]
aws_glue_job.etl["transform-aggregates"]
aws_athena_workgroup.main
aws_lakeformation_data_lake_settings.main
aws_lakeformation_resource.processed
aws_lakeformation_lf_tag.sensitivity
aws_lakeformation_lf_tag.domain
aws_lakeformation_permissions.backend_public
aws_iam_role.glue_service
aws_iam_role.ec2_backend
aws_iam_role.redshift
aws_iam_role.lakeformation_service
aws_s3_bucket.data["raw"]
aws_s3_bucket.data["staging"]
aws_s3_bucket.data["processed"]
aws_s3_bucket.data["public"]
aws_s3_bucket.data["athena_results"]
aws_s3_bucket.data["glue_scripts"]
aws_s3_bucket_policy.public_read
aws_s3_bucket_lifecycle_configuration.athena
aws_s3_bucket_cors_configuration.public
aws_s3_bucket_server_side_encryption_configuration.data (x6)
aws_s3_bucket_public_access_block.private (x5)
aws_secretsmanager_secret.app
aws_secretsmanager_secret_version.app
... 63 recursos en total`;
}

// ---------------------------------------------------------------------------
// Ej.2 · Dashboard divisas (NUEVO)
// ---------------------------------------------------------------------------
async function renderDivisasDashboard() {
  if (!BASE) return;
  try {
    const [dash, ticks, notifs, modelos, ticksFull, notifsFull] = await Promise.all([
      fetch(`${BASE}/api/divisas/dashboard`).then(r => r.json()),
      fetch(`${BASE}/api/divisas/ticks?hours=24&limit=30`).then(r => r.json()),
      fetch(`${BASE}/api/divisas/notificaciones?limit=8`).then(r => r.json()),
      fetch(`${BASE}/api/divisas/modelos`).then(r => r.json()),
      // Para charts usa más ticks
      fetch(`${BASE}/api/divisas/ticks?par=USD/COP&hours=24&limit=200`).then(r => r.json()),
      fetch(`${BASE}/api/divisas/notificaciones?limit=500`).then(r => r.json()),
    ]);

    // === CHART 1: Line de USD/COP últimas 24h ===
    const chartLineEl = $('#chart-ticks-line');
    if (chartLineEl) {
      const pts = (ticksFull as any[]).map(t => ({
        x: new Date(t.capturadoEn).getTime(),
        y: (Number(t.precioCompra) + Number(t.precioVenta)) / 2,
      })).sort((a, b) => a.x - b.x);
      chartLine(chartLineEl, pts, { color: '#c8321f' });
    }

    // === CHART 2: Donut tipos de notificación ===
    const chartDonutEl = $('#chart-notif-donut');
    if (chartDonutEl) {
      const counts: Record<string, number> = { comprar: 0, vender: 0, observar: 0 };
      (notifsFull as any[]).forEach(n => { counts[n.tipo] = (counts[n.tipo] || 0) + 1; });
      chartDonut(chartDonutEl, [
        { label: 'comprar', value: counts.comprar, color: '#1f7a3a' },
        { label: 'vender', value: counts.vender, color: '#c8321f' },
        { label: 'observar', value: counts.observar, color: '#6a6a6a' },
      ]);
    }

    // === CHART 3: Histograma score confianza ===
    const chartHistEl = $('#chart-score-hist');
    if (chartHistEl) {
      const scores = (notifsFull as any[])
        .map(n => Number(n.scoreConfianza))
        .filter(v => !Number.isNaN(v));
      chartHistogram(chartHistEl, scores, 10, '#1f4a8a');
    }

    // === CHART 4: Funnel enviadas → leídas → convertidas ===
    const chartFunnelEl = $('#chart-funnel');
    if (chartFunnelEl) {
      chartFunnel(chartFunnelEl, [
        { label: 'Enviadas', value: dash.notificacionesTotal, color: '#0a0a0a' },
        { label: 'Leídas', value: dash.notificacionesLeidas, color: '#1f4a8a' },
        { label: 'Convertidas', value: dash.notificacionesConvertidas, color: '#1f7a3a' },
      ]);
    }

    const k = $('#divisas-kpis');
    if (k) {
      k.innerHTML = `
        <div class="kpi-card"><div class="v">${fmtNum(dash.paresActivos)}</div><div class="l">Pares activos</div></div>
        <div class="kpi-card"><div class="v">${fmtNum(dash.ticksTotales)}</div><div class="l">Ticks totales</div></div>
        <div class="kpi-card"><div class="v">${fmtNum(dash.ticks24h)}</div><div class="l">Ticks últimas 24h</div></div>
        <div class="kpi-card"><div class="v">${fmtNum(dash.portafolios)}</div><div class="l">Portafolios</div></div>
        <div class="kpi-card"><div class="v">${fmtNum(dash.notificacionesTotal)}</div><div class="l">Notificaciones</div></div>
        <div class="kpi-card"><div class="v">${dash.ctrPct}%</div><div class="l">CTR (leídas/enviadas)</div></div>
        <div class="kpi-card"><div class="v">${dash.conversionPct}%</div><div class="l">Conversión (convertidas/leídas)</div></div>
        <div class="kpi-card"><div class="v">${dash.modeloActivo ?? '—'}</div><div class="l">Modelo activo</div></div>`;
    }

    const tT = $('#divisas-ticks');
    if (tT) {
      const rows = ticks as Array<any>;
      tT.innerHTML = `
        <table>
          <thead><tr><th>Par</th><th>Compra</th><th>Venta</th><th>Spread</th><th>Fuente</th><th>Capturado</th></tr></thead>
          <tbody>${rows.map(r => `
            <tr>
              <td><strong>${r.parLabel}</strong></td>
              <td><code>${fmtNum(r.precioCompra, 4)}</code></td>
              <td><code>${fmtNum(r.precioVenta, 4)}</code></td>
              <td><code>${fmtNum(r.spread, 4)}</code></td>
              <td>${r.fuente}</td>
              <td>${new Date(r.capturadoEn).toLocaleString('es-CO')}</td>
            </tr>`).join('')}</tbody></table>`;
    }

    const tN = $('#divisas-notifs');
    if (tN) {
      const rows = notifs as Array<any>;
      tN.innerHTML = `
        <table>
          <thead><tr><th>Tipo</th><th>Par</th><th>Precio ref</th><th>Score</th><th>Canal</th><th>Estado</th><th>Enviada</th></tr></thead>
          <tbody>${rows.map(r => `
            <tr>
              <td><span class="notif-tipo notif-${r.tipo}">${r.tipo}</span></td>
              <td><strong>${r.parLabel ?? '—'}</strong></td>
              <td><code>${fmtNum(r.precioReferencia, 4)}</code></td>
              <td><code>${fmtNum(r.scoreConfianza, 4)}</code></td>
              <td>${r.canal}</td>
              <td>${r.convertida ? '<span class="pill-ok">convertida</span>' : (r.leida ? '<span class="pill-info">leída</span>' : '<span class="pill-mute">enviada</span>')}</td>
              <td>${new Date(r.enviadaEn).toLocaleString('es-CO')}</td>
            </tr>`).join('')}</tbody></table>`;
    }

    const tM = $('#divisas-modelos');
    if (tM) {
      const rows = modelos as Array<any>;
      tM.innerHTML = `
        <table>
          <thead><tr><th>Versión</th><th>Algoritmo</th><th>Métricas</th><th>Activo</th><th>Entrenado</th></tr></thead>
          <tbody>${rows.map(r => `
            <tr class="${r.activo ? 'row-active' : ''}">
              <td><code>${r.version}</code></td>
              <td>${r.algoritmo}</td>
              <td><code>${escapeHtml(JSON.stringify(r.metricas))}</code></td>
              <td>${r.activo ? '<span class="pill-ok">ACTIVO</span>' : '—'}</td>
              <td>${new Date(r.entrenadoEn).toLocaleDateString('es-CO')}</td>
            </tr>`).join('')}</tbody></table>`;
    }
  } catch (e) {
    const k = $('#divisas-kpis');
    if (k) k.innerHTML = `<p class="muted">Datos divisas no disponibles: ${(e as Error).message}</p>`;
  }
}

// ---------------------------------------------------------------------------
// Ej.3 · 5 cards (parsea h2 del markdown)
// ---------------------------------------------------------------------------
async function renderEj3Questions() {
  const el = $('#ej3-questions');
  if (!el) return;
  const md = await fetchMarkdown('ej3');
  const blocks = md.split(/^##\s+/m).slice(1);
  if (blocks.length === 0) {
    el.innerHTML = '<p class="muted">No se encontraron preguntas (esperaba ## 1. ## 2. ...)</p>';
    return;
  }
  el.innerHTML = (await Promise.all(blocks.slice(0, 5).map(async (block, i) => {
    const lines = block.split('\n');
    const title = lines[0].trim();
    const body = lines.slice(1).join('\n').trim();
    const html = await marked.parse(body);
    return `<article class="ej3-q">
      <div class="qhead"><span class="qnum">Q${i + 1}</span><span class="qtitle">${title}</span></div>
      <div class="qbody exercise">${html}</div>
    </article>`;
  }))).join('');
}

// ---------------------------------------------------------------------------
// Ej.2 · render markdown read-only
// ---------------------------------------------------------------------------
async function renderEj2() {
  await renderMarkdownInto('ej2-render', 'ej2');
}

// ---------------------------------------------------------------------------
// Downloads panel
// ---------------------------------------------------------------------------
async function wireDownloads() {
  const { data: dl } = await api.downloads();
  const pdfBtn = $<HTMLButtonElement>('#dl-pdf');
  const ctaPdf = $<HTMLButtonElement>('#cta-pdf-big');
  const openPdf = () => window.open(dl.report.url, '_blank');
  if (pdfBtn) pdfBtn.onclick = openPdf;
  if (ctaPdf) ctaPdf.onclick = openPdf;

  const list = $('#export-list');
  if (list) {
    const tables = [
      ['core', 'proveedor'], ['core', 'cliente'], ['core', 'transaccion'],
      ['core', 'ciudad'], ['core', 'tipo_energia'],
      ['dwh', 'fact_transaccion'], ['dwh', 'dim_cliente'], ['dwh', 'dim_proveedor'],
      ['dwh', 'vw_resumen_mensual'], ['dwh', 'vw_top_clientes'], ['dwh', 'vw_margen_energia'],
    ];
    list.innerHTML = tables.map(([s, t]) =>
      `<li><a href="${api.exportCsvUrl(s, t)}" target="_blank">${s}.${t}.csv</a></li>`
    ).join('');
  }
}

// ---------------------------------------------------------------------------
// Charts SVG inline (sin dependencias)
// ---------------------------------------------------------------------------
function chartLine(el: HTMLElement, data: Array<{ x: number; y: number }>, opts: { ylabel?: string; color?: string } = {}) {
  if (!data.length) { el.innerHTML = '<p class="muted">sin datos</p>'; return; }
  const W = 520, H = 220, P = 36;
  const xs = data.map(d => d.x), ys = data.map(d => d.y);
  const xmin = Math.min(...xs), xmax = Math.max(...xs);
  const ymin = Math.min(...ys), ymax = Math.max(...ys);
  const sx = (x: number) => P + (x - xmin) / Math.max(1, (xmax - xmin)) * (W - 2 * P);
  const sy = (y: number) => H - P - (y - ymin) / Math.max(0.001, (ymax - ymin)) * (H - 2 * P);
  const path = data.map((d, i) => `${i === 0 ? 'M' : 'L'} ${sx(d.x).toFixed(1)} ${sy(d.y).toFixed(1)}`).join(' ');
  const color = opts.color ?? '#c8321f';
  // Y axis ticks
  const yTicks = [ymin, (ymin + ymax) / 2, ymax];
  // X axis labels (3 puntos: inicio, medio, fin)
  const xAxisLabels = [
    { x: xmin, label: new Date(xmin).toLocaleDateString('es-CO', { day: '2-digit', month: 'short' }) },
    { x: (xmin + xmax) / 2, label: new Date((xmin + xmax) / 2).toLocaleDateString('es-CO', { day: '2-digit', month: 'short' }) },
    { x: xmax, label: new Date(xmax).toLocaleDateString('es-CO', { day: '2-digit', month: 'short' }) },
  ];
  el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="xMidYMid meet">
    ${yTicks.map(yt => `<line class="grid-line" x1="${P}" x2="${W - P}" y1="${sy(yt)}" y2="${sy(yt)}" />`).join('')}
    <line class="axis-line" x1="${P}" y1="${P}" x2="${P}" y2="${H - P}" />
    <line class="axis-line" x1="${P}" y1="${H - P}" x2="${W - P}" y2="${H - P}" />
    <path d="${path}" fill="none" stroke="${color}" stroke-width="1.6"/>
    ${data.map(d => `<circle cx="${sx(d.x).toFixed(1)}" cy="${sy(d.y).toFixed(1)}" r="1.6" fill="${color}"/>`).join('')}
    ${yTicks.map(yt => `<text class="y-label" x="${P - 6}" y="${sy(yt) + 3}" text-anchor="end">${yt.toFixed(2)}</text>`).join('')}
    ${xAxisLabels.map(xl => `<text class="x-label" x="${sx(xl.x)}" y="${H - P + 14}" text-anchor="middle">${xl.label}</text>`).join('')}
  </svg>`;
}

function chartDonut(el: HTMLElement, segments: Array<{ label: string; value: number; color: string }>) {
  const total = segments.reduce((a, s) => a + s.value, 0);
  if (total === 0) { el.innerHTML = '<p class="muted">sin datos</p>'; return; }
  const W = 220, H = 220, R = 80, IR = 50, CX = 110, CY = 110;
  let acc = 0;
  const arcs = segments.map(s => {
    const start = acc / total * Math.PI * 2;
    acc += s.value;
    const end = acc / total * Math.PI * 2;
    const large = (end - start) > Math.PI ? 1 : 0;
    const x1 = CX + R * Math.sin(start), y1 = CY - R * Math.cos(start);
    const x2 = CX + R * Math.sin(end), y2 = CY - R * Math.cos(end);
    const x3 = CX + IR * Math.sin(end), y3 = CY - IR * Math.cos(end);
    const x4 = CX + IR * Math.sin(start), y4 = CY - IR * Math.cos(start);
    return `<path d="M ${x1.toFixed(1)} ${y1.toFixed(1)} A ${R} ${R} 0 ${large} 1 ${x2.toFixed(1)} ${y2.toFixed(1)} L ${x3.toFixed(1)} ${y3.toFixed(1)} A ${IR} ${IR} 0 ${large} 0 ${x4.toFixed(1)} ${y4.toFixed(1)} Z" fill="${s.color}"/>`;
  }).join('');
  const legend = segments.map((s, i) => `
    <g transform="translate(${W + 12}, ${20 + i * 22})">
      <rect width="12" height="12" fill="${s.color}"/>
      <text class="legend" x="18" y="10">${s.label} <tspan fill="#6a6a6a">(${((s.value / total) * 100).toFixed(1)}%)</tspan></text>
    </g>`).join('');
  el.innerHTML = `<svg viewBox="0 0 ${W + 180} ${H}" preserveAspectRatio="xMidYMid meet">
    ${arcs}
    <text class="legend" x="${CX}" y="${CY - 4}" text-anchor="middle" font-size="14" font-weight="600">${total}</text>
    <text class="legend" x="${CX}" y="${CY + 12}" text-anchor="middle" font-size="9" fill="#6a6a6a">total</text>
    ${legend}
  </svg>`;
}

function chartHistogram(el: HTMLElement, values: number[], bins = 10, color = '#1f4a8a') {
  if (!values.length) { el.innerHTML = '<p class="muted">sin datos</p>'; return; }
  const min = Math.min(...values), max = Math.max(...values);
  const step = (max - min) / bins || 1;
  const counts = new Array(bins).fill(0);
  values.forEach(v => {
    const i = Math.min(bins - 1, Math.floor((v - min) / step));
    counts[i]++;
  });
  const W = 520, H = 220, P = 36;
  const maxC = Math.max(...counts);
  const bw = (W - 2 * P) / bins;
  const bars = counts.map((c, i) => {
    const x = P + i * bw;
    const h = (c / maxC) * (H - 2 * P);
    const y = H - P - h;
    return `<rect x="${(x + 1).toFixed(1)}" y="${y.toFixed(1)}" width="${(bw - 2).toFixed(1)}" height="${h.toFixed(1)}" fill="${color}" opacity="0.85"/>
      <text class="x-label" x="${(x + bw / 2).toFixed(1)}" y="${(y - 3).toFixed(1)}" text-anchor="middle" font-size="9">${c}</text>`;
  }).join('');
  // X labels
  const xLabels = [0, Math.floor(bins / 2), bins].map(i => {
    const v = (min + i * step).toFixed(2);
    const x = P + i * bw;
    return `<text class="x-label" x="${x}" y="${H - P + 14}" text-anchor="middle">${v}</text>`;
  }).join('');
  el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="xMidYMid meet">
    <line class="axis-line" x1="${P}" y1="${P}" x2="${P}" y2="${H - P}" />
    <line class="axis-line" x1="${P}" y1="${H - P}" x2="${W - P}" y2="${H - P}" />
    ${bars}
    ${xLabels}
  </svg>`;
}

function chartFunnel(el: HTMLElement, stages: Array<{ label: string; value: number; color: string }>) {
  if (!stages.length) { el.innerHTML = '<p class="muted">sin datos</p>'; return; }
  const W = 480, H = 220;
  const max = Math.max(...stages.map(s => s.value));
  const stageH = (H - 20) / stages.length;
  const bars = stages.map((s, i) => {
    const w = (s.value / max) * (W - 160);
    const y = 10 + i * stageH;
    const pct = i === 0 ? 100 : (s.value / stages[i - 1].value) * 100;
    return `
      <rect x="120" y="${y}" width="${w}" height="${stageH - 6}" fill="${s.color}" opacity="0.85"/>
      <text class="legend" x="115" y="${y + stageH / 2 + 3}" text-anchor="end" font-size="11" font-weight="500">${s.label}</text>
      <text class="legend" x="${120 + w + 6}" y="${y + stageH / 2 + 3}" font-size="11" font-weight="600">${s.value}</text>
      ${i > 0 ? `<text class="legend" x="${120 + w + 60}" y="${y + stageH / 2 + 3}" font-size="10" fill="#6a6a6a">${pct.toFixed(1)}% del paso anterior</text>` : ''}`;
  }).join('');
  el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="xMidYMid meet">${bars}</svg>`;
}

// ---------------------------------------------------------------------------
// Bootstrap
// ---------------------------------------------------------------------------
const today = $('#today');
if (today) today.textContent = new Date().toLocaleDateString('es-CO', { year: 'numeric', month: 'long', day: 'numeric' });

wireTabs();

(async () => {
  await renderKpis();
  await Promise.all([
    renderDatasource(),
    renderDataModel(),
    renderCostRealtime(),
    renderEtlJobs(),
    renderGlueTables(),
    renderTfResources(),
    renderAthena(),
    renderSchemasSeparados(),
    renderEj2(),
    renderDivisasDashboard(),
    renderEj3Questions(),
    renderMarkdownInto('doc-pipeline', 'pipeline'),
    renderMarkdownInto('doc-permissions', 'permissions'),
    renderMarkdownInto('doc-decision', 'data-decision'),
    wireDownloads(),
  ]);
  await renderGenerationVolumes();
})();
