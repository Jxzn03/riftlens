// Ansicht "Items": Liste mit Filtern, Build-Rechner und Detail mit Werten und Baupfad.
import { html, useMemo, useEffect } from '/vendor/htm-preact.js';
import {
  useStore, useViewState, useFetch, select, go, normalize, sortRows, addToBuild, removeFromBuild, store, MAX_BUILD,
  runePageRunes,
} from './core.js';
import {
  Img, Card, Button, Select, SearchInput, Switch, Tag, DataGrid, VirtualTable, MasterDetail, Empty, Icon, RuneIcons,
} from './ui.js';

const DEFAULTS = { q: '', tag: '', completed: false, sort: { key: 'name', dir: 1 } };

const COLUMNS = [
  {
    key: 'name', title: 'Item', width: '250px', value: (r) => r.item.name,
    render: (r) => html`<div class="cell-hero"><${Img} src=${r.item.icon} size=${32} /><div><strong>${r.item.name}</strong></div></div>`,
  },
  { key: 'gold', title: 'Gold', width: '64px', align: 'right', firstDir: -1, value: (r) => r.item.gold.total, render: (r) => html`<span class="gold">${r.item.gold.total}</span>` },
  { key: 'tags', title: 'Kategorien', width: '240px', value: (r) => r.item.tags.join(', '), render: (r) => html`<span class="muted ellipsis">${r.item.tags.join(', ')}</span>` },
];

export function ItemsView({ id }) {
  const data = useStore((s) => s.data);
  const [view, setView] = useViewState('items', DEFAULTS);
  const itemId = id === null ? null : Number(id);

  const rows = useMemo(() => data.items.filter((item) => item.rift).map((item) => ({ item })), [data]);
  const tags = useMemo(() => [...new Set(rows.flatMap((r) => r.item.tags))].sort(), [rows]);

  const matches = (r, v) => {
    const q = normalize(v.q);
    return (!q || r.item.name.toLowerCase().includes(q) || String(r.item.id) === q)
      && (!v.tag || r.item.tags.includes(v.tag)) && (!v.completed || r.item.completed);
  };
  const visible = useMemo(() => sortRows(rows.filter((r) => matches(r, view)), COLUMNS, view.sort),
    [rows, view.q, view.tag, view.completed, view.sort]);

  // Sprung aus einer anderen Ansicht: Filter zurücksetzen, damit das Item in der Liste sichtbar ist.
  useEffect(() => {
    const target = rows.find((r) => r.item.id === itemId);
    if (target && !matches(target, view)) {
      setView({ q: '', tag: '', completed: false });
    }
  }, [itemId]);

  const item = itemId !== null ? data.itemById.get(itemId) : null;

  const master = html`<div class="pane">
    <div class="toolbar">
      <${SearchInput} value=${view.q} onInput=${(q) => setView({ q })} placeholder="Item suchen …" />
      <${Select} label="Kategorie" value=${view.tag} onChange=${(tag) => setView({ tag })}
        options=${[{ value: '', label: 'Alle Kategorien' }, ...tags.map((t) => ({ value: t, label: t }))]} />
    </div>
    <div class="count-line">
      <span>${visible.length} Items</span>
      <div class="switches">
        <${Switch} checked=${view.completed} onChange=${(completed) => setView({ completed })}>Nur fertige Items<//>
      </div>
    </div>
    <${VirtualTable} rows=${visible} columns=${COLUMNS} rowKey=${(r) => r.item.id} selected=${itemId}
      onSelect=${(r) => select('items', r.item.id)} sort=${view.sort} onSort=${(sort) => setView({ sort })}
      rowHeight=${44} empty="Keine Items gefunden" />
    <${BuildPanel} data=${data} />
  </div>`;

  return html`<${MasterDetail} name="items" detailKey=${id} initial=${0.5} master=${master}
    detail=${item ? html`<${ItemDetail} key=${item.id} item=${item} data=${data} />`
      : html`<${Empty} title="Item auswählen">Wähle links ein Item für Werte, Beschreibung und Baupfad.<//>`} />`;
}

function BuildPanel({ data }) {
  const build = useStore((s) => s.build);
  const runePage = useStore((s) => s.runePage);
  const runes = runePageRunes(runePage);
  const totals = useFetch(build.length ? `/api/build?items=${build.join(',')}` : null, { keep: true }).data;
  const slots = Array.from({ length: MAX_BUILD }, (_, i) => build[i]);
  return html`<section class="build-panel">
    <header class="card-head">
      <h3>Build-Rechner</h3>
      <div class="card-aside">
        ${build.length ? html`<strong class="gold">${(totals ? totals.gold : 0).toLocaleString('de-DE')} Gold</strong>` : null}
        <${Button} icon="trash" kind="ghost" disabled=${!build.length} onClick=${() => store.set({ build: [] })}>Leeren<//>
      </div>
    </header>
    <div class="build-slots">
      ${slots.map((id, index) => {
        const item = id === undefined ? null : data.itemById.get(id);
        return item
          ? html`<button key=${index} type="button" class="build-slot filled" onClick=${() => removeFromBuild(index)}><${Img} src=${item.icon} size=${40} tip=${`${item.name} (${item.gold.total} Gold) – Klick entfernt`} /></button>`
          : html`<span key=${index} class="build-slot"><${Icon} name="plus" size=${14} /></span>`;
      })}
    </div>
    ${build.length === 0 ? html`<p class="muted small">Leer – Items im Detail über „Zum Build hinzufügen“ ergänzen.</p>` : null}
    <div class="build-runes">
      ${runes.length ? html`<${RuneIcons} data=${data} ids=${runes} size=${26} />` : html`<span class="muted small">Keine Runenseite</span>`}
      <${Button} kind="ghost" onClick=${() => go('runes', 'page')}>${runes.length ? 'Runen bearbeiten' : 'Runen wählen'}<//>
    </div>
    ${build.length && totals ? html`<div class="stat-chips">${totals.stats.map((s) => html`<span class="stat-chip" key=${s.label}><strong>${s.value}</strong> ${s.label}</span>`)}</div>
      ${totals.unparsed.length ? html`<details class="unparsed"><summary>Nicht verrechnet (${totals.unparsed.length})</summary>${totals.unparsed.map((u, i) => html`<p class="muted small" key=${i}>• ${u}</p>`)}</details>` : null}` : null}
  </section>`;
}

function ItemLinks({ ids, data }) {
  return html`<div class="chip-links">${ids.map((id) => {
    const item = data.itemById.get(id);
    return item ? html`<button type="button" class="chip-link" key=${id} data-tip=${`${item.gold.total} Gold`} onClick=${() => go('items', id)}><${Img} src=${item.icon} size=${26} /><span>${item.name}</span></button>` : null;
  })}</div>`;
}

function ItemDetail({ item, data }) {
  const buildSize = useStore((s) => s.build.length);
  // Bei SR-Items keine Modus-Varianten verlinken (Data Dragon listet sie mit im Baupfad).
  const linked = (ids) => (item.rift ? ids.filter((id) => (data.itemById.get(id) || {}).rift) : ids);
  const from = linked(item.from);
  const into = linked(item.into);
  return html`<div class="detail">
    <header class="plain-hero">
      <${Img} src=${item.icon} size=${72} cls="hero-icon" />
      <div class="hero-text">
        <h1>${item.name}</h1>
        <p class="hero-sub gold">${item.gold.total} Gold <span class="muted">(Kombination ${item.gold.base}, Verkauf ${item.gold.sell})</span></p>
        <div class="tags">${item.tags.map((t) => html`<${Tag} key=${t}>${t}<//>`)}<${Tag} kind="soft">ID ${item.id}<//></div>
      </div>
      <${Button} icon="plus" kind="primary" disabled=${buildSize >= MAX_BUILD} onClick=${() => addToBuild(item.id)}>Zum Build hinzufügen<//>
    </header>
    ${item.plaintext ? html`<p class="lore">${item.plaintext}</p>` : null}
    ${item.stats.length ? html`<${Card} title="Werte"><${DataGrid} rows=${item.stats.map((s) => [s.label, html`<strong>${s.value}</strong>`])} /><//>` : null}
    <${Card} title="Beschreibung"><p class="prose">${item.description}</p><//>
    ${from.length ? html`<${Card} title="Baut aus"><${ItemLinks} ids=${from} data=${data} /><//>` : null}
    ${into.length ? html`<${Card} title="Baut zu"><${ItemLinks} ids=${into} data=${data} /><//>` : null}
  </div>`;
}
