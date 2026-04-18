import type { Provider, Client } from '../data/types';

export function renderProviders(el: HTMLElement | null, providers: Provider[]) {
  if (!el) return;
  el.innerHTML = providers
    .map(
      (p, i) => `<li>
        <div class="idx">${String(i + 1).padStart(2, '0')}</div>
        <div class="name">${p.nombre}
          <small>${p.tipoEnergia} · ${p.pais}</small>
        </div>
        <div class="cap">${p.capacidadMW}<small>MW</small></div>
      </li>`,
    )
    .join('');
}

const cityCoords: Record<string, string> = {
  Bogotá: '04°35′N · 74°04′W',
  Barranquilla: '10°59′N · 74°47′W',
  Medellín: '06°14′N · 75°34′W',
  Cali: '03°25′N · 76°31′W',
  Cartagena: '10°25′N · 75°32′W',
  Bucaramanga: '07°07′N · 73°07′W',
};

export function renderCities(el: HTMLElement | null, clients: Client[]) {
  if (!el) return;
  const byCity = clients.reduce<Record<string, Client[]>>((acc, c) => {
    (acc[c.ciudad] ||= []).push(c);
    return acc;
  }, {});
  el.innerHTML = Object.entries(byCity)
    .map(
      ([city, cs]) => `<div class="city">
        <h3>${city}</h3>
        <div class="coord">${cityCoords[city] ?? ''}</div>
        <ul>${cs.map((c) => `<li>${c.nombre}<br/><span>${c.segmento} · ${c.tipoId} ${c.id}</span></li>`).join('')}</ul>
      </div>`,
    )
    .join('');
}
