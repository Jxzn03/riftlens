// Ansicht "Runen": Runenseite für den Build-Rechner, Bäume mit Keystones und Reihen, Suche über alle Runen, Shards.
import { html, useMemo } from '/vendor/htm-preact.js';
import {
  useStore, useViewState, select, go, normalize, setRunePage, runePageRunes, EMPTY_RUNE_PAGE, store, gamePatch,
} from './core.js';
import { Img, Card, SearchInput, DataGrid, MasterDetail, Empty, Tag, Button, Slider, Switch, Icon } from './ui.js';

const DEFAULTS = { q: '' };
const PAGE_ID = 'page';
const rowLabel = (slot) => (slot === 0 ? 'Keystone' : `Reihe ${slot}`);
const SHARD_ROW_LABELS = ['Offensive', 'Flex', 'Defensive'];

/** Route: "page" (oder leer) für die Runenseite, Runen-ID oder "t<Baum-ID>" für einen Baum ohne gewählte Rune. */
function resolve(data, id) {
  if (!id || id === PAGE_ID) {
    return { page: true, tree: null, rune: null };
  }
  if (id.startsWith('t')) {
    return { tree: data.treeById.get(Number(id.slice(1))) || data.runeTrees[0], rune: null };
  }
  const rune = data.runeById.get(Number(id));
  return rune ? { tree: data.treeById.get(rune.treeId), rune } : { page: true, tree: null, rune: null };
}

export function RunesView({ id }) {
  const data = useStore((s) => s.data);
  const runePage = useStore((s) => s.runePage);
  const [view, setView] = useViewState('runes', DEFAULTS);
  const { page, tree, rune } = resolve(data, id);
  const chosen = runePageRunes(runePage).length;

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
      ${results === null ? html`
        <button type="button" class=${`list-row ${page ? 'selected' : ''}`} onClick=${() => select('runes', PAGE_ID)}>
          <span class="list-icon"><${Icon} name="settings" size=${18} /></span>
          <span><strong>Runenseite (Build-Rechner)</strong><small>${chosen ? `${chosen} Runen gewählt` : 'noch leer'}</small></span>
        </button>
        ${data.runeTrees.map((t) => html`
        <button type="button" key=${t.id} class=${`list-row ${tree && t.id === tree.id ? 'selected' : ''}`} onClick=${() => select('runes', `t${t.id}`)}>
          <${Img} src=${t.icon} size=${30} /><span><strong>${t.name}</strong></span>
        </button>`)}` : null}
      ${results !== null ? results.map((r) => html`
        <button type="button" key=${r.id} class=${`list-row ${rune && r.id === rune.id ? 'selected' : ''}`} onClick=${() => select('runes', r.id)}>
          <${Img} src=${r.icon} size=${32} /><span><strong>${r.name}</strong><small>${data.treeById.get(r.treeId).name} · ${rowLabel(r.slot)}</small></span>
        </button>`) : null}
      ${results !== null && results.length === 0 ? html`<${Empty} title="Keine Runen gefunden" />` : null}
    </div>
  </div>`;

  let detail;
  if (page) {
    detail = html`<${RunePageEditor} data=${data} />`;
  } else if (tree) {
    detail = html`<${TreeDetail} key=${tree.id} tree=${tree} rune=${rune} data=${data} />`;
  } else {
    detail = html`<${Empty} title="Keine Runen geladen" />`;
  }
  return html`<${MasterDetail} name="runes" detailKey=${page ? PAGE_ID : tree ? tree.id : null} initial=${0.25} min=${0.15} max=${0.5}
    master=${master} detail=${detail} />`;
}

// ------------------------------------------------------------------ Runenseite (Build-Rechner)

function RunePageEditor({ data }) {
  const page = useStore((s) => s.runePage);
  const build = useStore((s) => s.build);
  const primary = page.primary ? data.treeById.get(page.primary) : null;
  const secondary = page.secondary ? data.treeById.get(page.secondary) : null;
  const shardRows = data.shardRows || [];

  // Immer vom aktuellen Stand ausgehen, nicht von dem beim letzten Rendern (schnelle Klicks hintereinander).
  const current = () => store.get().runePage;
  const choosePrimary = (id) => {
    const p = current();
    if (id !== p.primary) {
      setRunePage({
        primary: id, keystone: null, rows: [null, null, null],
        ...(p.secondary === id ? { secondary: null, secondaryRunes: [] } : {}),
      });
    }
  };
  const chooseSecondary = (id) => {
    if (id !== current().secondary) {
      setRunePage({ secondary: id, secondaryRunes: [] });
    }
  };
  const pickKeystone = (id) => setRunePage({ keystone: current().keystone === id ? null : id });
  const pickRow = (slot, id) => {
    const rows = [...current().rows];
    rows[slot - 1] = rows[slot - 1] === id ? null : id;
    setRunePage({ rows });
  };
  // Wie im Client: zwei Runen aus verschiedenen Reihen; eine dritte verdrängt die älteste.
  const pickSecondary = (rune) => {
    const chosen = current().secondaryRunes;
    if (chosen.includes(rune.id)) {
      setRunePage({ secondaryRunes: chosen.filter((id) => id !== rune.id) });
      return;
    }
    const others = chosen.filter((id) => data.runeById.get(id).slot !== rune.slot);
    setRunePage({ secondaryRunes: [...others, rune.id].slice(-2) });
  };
  const pickShard = (row, id) => {
    const shards = [...current().shards];
    shards[row] = shards[row] === id ? null : id;
    setRunePage({ shards });
  };

  return html`<div class="detail">
    <header class="plain-hero">
      <div class="hero-text">
        <h1>Runenseite</h1>
        <p class="hero-sub">Fließt in den Build-Rechner ein: Werte mit Build (Champions) und Abklingzeiten (Abilities).</p>
      </div>
      <${Button} icon="trash" kind="ghost" onClick=${() => setRunePage(EMPTY_RUNE_PAGE)}>Leeren<//>
    </header>

    <div class="rune-page-grid">
      <${Card} title="Primärbaum">
        <${TreePicker} data=${data} value=${page.primary} onPick=${choosePrimary} />
        ${primary ? html`<div class="rune-tree">
          ${primary.slots.map((row, slot) => html`<${RuneRow} key=${slot} row=${row} slot=${slot}
            selected=${(r) => (slot === 0 ? page.keystone === r.id : page.rows[slot - 1] === r.id)}
            onPick=${(r) => (slot === 0 ? pickKeystone(r.id) : pickRow(slot, r.id))} />`)}
        </div>` : html`<p class="muted small">Primärbaum wählen: ein Keystone und je eine Rune aus drei Reihen.</p>`}
      <//>

      <${Card} title="Sekundärbaum">
        <${TreePicker} data=${data} value=${page.secondary} onPick=${chooseSecondary} exclude=${page.primary} />
        ${secondary ? html`<div class="rune-tree">
          ${secondary.slots.slice(1).map((row, i) => html`<${RuneRow} key=${i} row=${row} slot=${i + 1}
            selected=${(r) => page.secondaryRunes.includes(r.id)} onPick=${pickSecondary} />`)}
        </div>` : html`<p class="muted small">Sekundärbaum wählen: zwei Runen aus verschiedenen Reihen.</p>`}

        <h4 class="shard-title">Shards</h4>
        <div class="shard-rows">
          ${shardRows.map((row, i) => html`<div class="shard-row" key=${i}>
            <span class="rune-row-label">${SHARD_ROW_LABELS[i]}</span>
            <div class="shard-options">
              ${row.map((id) => html`<button type="button" key=${id} class=${`shard-btn ${page.shards[i] === id ? 'selected' : ''}`}
                onClick=${() => pickShard(i, id)}>
                <strong>${data.shardById.get(id) || id}</strong>
                <small>${(data.runeEffectById.get(id) || {}).summary || ''}</small>
              </button>`)}
            </div>
          </div>`)}
        </div>
      <//>
    </div>

    <${RuneEffectsCard} data=${data} page=${page} build=${build} />
  </div>`;
}

function TreePicker({ data, value, onPick, exclude }) {
  return html`<div class="tree-picker" role="radiogroup">
    ${data.runeTrees.map((t) => html`<button type="button" key=${t.id} role="radio" aria-checked=${value === t.id}
      class=${`tree-btn ${value === t.id ? 'selected' : ''}`} disabled=${t.id === exclude} onClick=${() => onPick(t.id)}>
      <${Img} src=${t.icon} size=${30} /><span>${t.name}</span>
    </button>`)}
  </div>`;
}

function RuneRow({ row, slot, selected, onPick }) {
  return html`<div class="rune-row">
    <span class="rune-row-label">${slot === 0 ? 'Keystone' : `Reihe ${slot}`}</span>
    <div class="rune-row-items">
      ${row.map((r) => html`<button type="button" key=${r.id} data-tip=${r.short}
        class=${`rune-btn ${slot === 0 ? 'keystone' : ''} ${selected(r) ? 'selected' : ''}`} onClick=${() => onPick(r)}>
        <${Img} src=${r.icon} size=${slot === 0 ? 50 : 36} /><span>${r.name}</span>
      </button>`)}
    </div>
  </div>`;
}

/** Was von der Seite in den Rechner geht; Stapel und Bedingungen stellt der Nutzer ein. */
function RuneEffectsCard({ data, page, build }) {
  const runes = runePageRunes(page).map((id) => data.runeById.get(id)).filter(Boolean);
  const shards = page.shards.map((id, i) => (id === null ? null : { id, row: i })).filter(Boolean);
  const setStacks = (id, value) => setRunePage({ stacks: { ...store.get().runePage.stacks, [id]: value } });
  const complete = shards.length === 3;

  return html`<${Card} title="Verrechnete Werte" aside=${html`<${Button} icon="arrow" kind="ghost"
      onClick=${() => go('champions')}>Werte mit Build ansehen<//>`}>
    ${runes.length === 0 && shards.length === 0 ? html`<p class="muted">Noch keine Runen gewählt.</p>` : null}
    <div class="rune-effects">
      ${runes.map((r) => {
        const effect = data.runeEffectById.get(r.id);
        const stacks = Number(page.stacks[r.id]) || 0;
        return html`<div class=${`rune-effect ${effect ? '' : 'none'}`} key=${r.id}>
          <${Img} src=${r.icon} size=${32} />
          <div class="rune-effect-text">
            <strong>${r.name}</strong>
            <small class="muted">${effect ? effect.summary : 'keine dauerhaften Werte, nicht verrechnet'}</small>
          </div>
          ${effect && effect.kind === 'stacks' ? html`<div class="rune-effect-control">
            <span class="control-label">${effect.stackLabel}: <strong>${stacks}</strong> / ${effect.maxStacks}</span>
            <${Slider} label=${`${r.name} Stapel`} value=${stacks} min=${0} max=${effect.maxStacks} onInput=${(v) => setStacks(r.id, v)} />
          </div>` : null}
          ${effect && effect.kind === 'toggle' ? html`<div class="rune-effect-control">
            <${Switch} checked=${stacks > 0} onChange=${(on) => setStacks(r.id, on ? 1 : 0)}>${effect.stackLabel}<//>
          </div>` : null}
          ${effect && effect.kind === 'always' ? html`<${Tag} kind="soft">immer aktiv<//>` : null}
        </div>`;
      })}
      ${shards.map((s) => html`<div class="rune-effect" key=${`s${s.row}`}>
        <span class="shard-dot">${SHARD_ROW_LABELS[s.row][0]}</span>
        <div class="rune-effect-text">
          <strong>${data.shardById.get(s.id) || s.id}</strong>
          <small class="muted">${(data.runeEffectById.get(s.id) || {}).summary || ''}</small>
        </div>
        ${complete ? html`<${Tag} kind="soft">Shard<//>` : null}
      </div>`)}
    </div>
    ${shards.length > 0 && !complete ? html`<p class="muted small">Shards zählen erst, wenn alle drei Reihen gewählt sind.</p>` : null}
    <p class="muted small">Werte laut Data Dragon (Runen) und Wiki (Shards). Effekte wie Schaden von Keystones, Heilung oder Item Haste
      rechnet der Build-Rechner nicht. ${build.length ? `Zusammen mit ${build.length} Items im Build.` : 'Der Build ist noch ohne Items.'}</p>
    ${data.runeEffectStatus ? html`<${RuneEffectStatus} info=${data.runeEffectStatus} />` : null}
  <//>`;
}

/** Stand des automatischen Abgleichs beim ersten Start nach einem Patchwechsel; Details nur mit „Show Advanced Tooltips“. */
function RuneEffectStatus({ info }) {
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const tip = [...info.changes, ...info.review, ...info.notes].join('\n');
  return html`<p class="muted small" data-tip=${advancedTips && tip ? tip : undefined}>
    Runenwerte für Patch ${info.patch ? gamePatch(info.patch) : '–'} abgeglichen${info.checkedAt ? ` (${info.checkedAt})` : ''}${info.changes.length ? ` · ${info.changes.length} automatisch aktualisiert` : ''}${info.review.length ? ` · ${info.review.length} nicht auswertbar` : ''}
  </p>`;
}

// ------------------------------------------------------------------ Bäume und Runen ansehen

function TreeDetail({ tree, rune, data }) {
  const count = tree.slots.flat().length;
  const effect = rune ? data.runeEffectById.get(rune.id) : null;
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
        ${rune.text ? html`<p class="prose">${rune.text}</p>` : html`<p class="muted">Keine Beschreibung vorhanden</p>`}
        <p class="muted small">Build-Rechner: ${effect ? effect.summary : 'keine dauerhaften Werte, wird nicht verrechnet'}</p>`
        : html`<p class="muted">Rune anklicken, um Details zu sehen.</p>`}
    <//>

    <${Card} title="Rune-Shards">
      <p class="muted small">Nicht in Data Dragon enthalten; IDs wie in Match-Daten (perks.statPerks). Werte laut Wiki.</p>
      <${DataGrid} head=${['ID', 'Shard', 'Wert']} rows=${data.shards.map((s) => [html`<span class="muted">${s.id}</span>`, s.name,
        data.runeEffectById.has(s.id) ? data.runeEffectById.get(s.id).summary : html`<span class="muted">nicht mehr wählbar</span>`])} />
    <//>
  </div>`;
}
