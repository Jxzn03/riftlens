// Ansicht "Runen": Bäume mit Keystones und Reihen, Suche über alle Runen, Shards.
import { html, useMemo } from '/vendor/htm-preact.js';
import { useStore, useViewState, select, normalize } from './core.js';
import { Img, Card, SearchInput, DataGrid, MasterDetail, Empty, Tag } from './ui.js';

const DEFAULTS = { q: '' };
const rowLabel = (slot) => (slot === 0 ? 'Keystone' : `Reihe ${slot}`);

/** Route: Runen-ID oder "t<Baum-ID>" für einen Baum ohne gewählte Rune. */
function resolve(data, id) {
  if (id && id.startsWith('t')) {
    return { tree: data.treeById.get(Number(id.slice(1))) || data.runeTrees[0], rune: null };
  }
  const rune = id ? data.runeById.get(Number(id)) : null;
  return rune ? { tree: data.treeById.get(rune.treeId), rune } : { tree: data.runeTrees[0], rune: null };
}

export function RunesView({ id }) {
  const data = useStore((s) => s.data);
  const [view, setView] = useViewState('runes', DEFAULTS);
  const { tree, rune } = resolve(data, id);

  const results = useMemo(() => {
    const q = normalize(view.q);
    if (!q) {
      return null;
    }
    return data.runeTrees.flatMap((t) => t.slots.flat())
      .filter((r) => r.name.toLowerCase().includes(q) || r.key.toLowerCase().includes(q));
  }, [data, view.q]);

  const master = html`<div class="pane">
    <div class="toolbar"><${SearchInput} value=${view.q} onInput=${(q) => setView({ q })} placeholder="Rune suchen …" /></div>
    <div class="list">
      ${results === null ? data.runeTrees.map((t) => html`
        <button type="button" key=${t.id} class=${`list-row ${tree && t.id === tree.id ? 'selected' : ''}`} onClick=${() => select('runes', `t${t.id}`)}>
          <${Img} src=${t.icon} size=${30} /><span><strong>${t.name}</strong></span>
        </button>`) : null}
      ${results !== null ? results.map((r) => html`
        <button type="button" key=${r.id} class=${`list-row ${rune && r.id === rune.id ? 'selected' : ''}`} onClick=${() => select('runes', r.id)}>
          <${Img} src=${r.icon} size=${32} /><span><strong>${r.name}</strong><small>${data.treeById.get(r.treeId).name} · ${rowLabel(r.slot)}</small></span>
        </button>`) : null}
      ${results !== null && results.length === 0 ? html`<${Empty} title="Keine Runen gefunden" />` : null}
    </div>
  </div>`;

  return html`<${MasterDetail} name="runes" detailKey=${tree ? tree.id : null} initial=${0.25} min=${0.15} max=${0.5} master=${master}
    detail=${tree ? html`<${TreeDetail} key=${tree.id} tree=${tree} rune=${rune} data=${data} />`
      : html`<${Empty} title="Keine Runen geladen" />`} />`;
}

function TreeDetail({ tree, rune, data }) {
  const count = tree.slots.flat().length;
  return html`<div class="detail">
    <header class="plain-hero">
      <${Img} src=${tree.icon} size=${60} />
      <div class="hero-text">
        <h1>${tree.name}</h1>
        <p class="hero-sub">Runenbaum · ${count} Runen</p>
        <div class="tags"><${Tag} kind="soft">ID ${tree.id}<//><${Tag} kind="soft">${tree.key}<//></div>
      </div>
    </header>

    <div class="rune-tree">
      ${tree.slots.map((row, slot) => html`<div class="rune-row" key=${slot}>
        <span class="rune-row-label">${slot === 0 ? 'Keystones' : `Reihe ${slot}`}</span>
        <div class="rune-row-items">
          ${row.map((r) => html`<button type="button" key=${r.id} data-tip=${r.short}
            class=${`rune-btn ${slot === 0 ? 'keystone' : ''} ${rune && r.id === rune.id ? 'selected' : ''}`}
            onClick=${() => select('runes', r.id)}>
            <${Img} src=${r.icon} size=${slot === 0 ? 58 : 40} /><span>${r.name}</span>
          </button>`)}
        </div>
      </div>`)}
    </div>

    <${Card} title="Rune-Detail">
      ${rune ? html`<div class="rune-detail">
          <${Img} src=${rune.icon} size=${64} />
          <div>
            <h2>${rune.name}</h2>
            <p class="hero-sub">${tree.name} · ${rowLabel(rune.slot)}</p>
            <p class="muted small">ID ${rune.id} · ${rune.key}</p>
          </div>
        </div>
        ${rune.text ? html`<p class="prose">${rune.text}</p>` : html`<p class="muted">Keine Beschreibung vorhanden</p>`}`
        : html`<p class="muted">Rune anklicken, um Details zu sehen.</p>`}
    <//>

    <${Card} title="Rune-Shards">
      <p class="muted small">Nicht in Data Dragon enthalten; IDs wie in Match-Daten (perks.statPerks).</p>
      <${DataGrid} head=${['ID', 'Shard']} rows=${data.shards.map((s) => [html`<span class="muted">${s.id}</span>`, s.name])} />
    <//>
  </div>`;
}
