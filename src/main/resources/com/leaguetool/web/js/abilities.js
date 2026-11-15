// Ansicht "Abilities": Fähigkeiten je Rang, Abklingzeit mit Ability Haste, Wiki-Details, Schadensvorschau.
import { html, useMemo, useEffect, useState } from '/vendor/htm-preact.js';
import {
  useStore, useViewState, useFetch, select, go, normalize, percent, setPref, useBuildQuery, runePageRunes,
} from './core.js';
import {
  Img, Card, Button, Select, SearchInput, Slider, DataGrid, VirtualTable, MasterDetail, Empty, Skeleton, ErrorNote,
} from './ui.js';

const DEFAULTS = { q: '' };
const LEVELS = Array.from({ length: 18 }, (_, i) => ({ value: String(i + 1), label: String(i + 1) }));

const COLUMNS = [{
  key: 'name', title: 'Champion', width: '240px',
  render: (c) => html`<div class="cell-hero"><${Img} src=${c.icon} size=${32} cls="round" /><div><strong>${c.name}</strong></div></div>`,
}];

/** Obergrenze für Ability Haste samt Basic/Ultimate Haste laut Wiki (83,33 % kürzer). */
const HASTE_CAP = 500;
/** Abklingzeit mit Haste (Formel wie calc.CooldownCalculator): Basis × 100 / (100 + Haste). */
const withHaste = (base, haste, cap = HASTE_CAP) => (base * 100) / (100 + Math.min(cap, Math.max(0, haste)));
const seconds = (value) => Number(value.toFixed(2)).toLocaleString('de-DE');

export function AbilitiesView({ id }) {
  const data = useStore((s) => s.data);
  const [view, setView] = useViewState('abilities', DEFAULTS);
  const sorted = useMemo(() => [...data.champions].sort((a, b) => a.name.localeCompare(b.name, 'de')), [data]);
  const visible = useMemo(() => {
    const q = normalize(view.q);
    return sorted.filter((c) => !q || c.name.toLowerCase().includes(q) || c.id.toLowerCase().includes(q));
  }, [sorted, view.q]);

  useEffect(() => {
    if (id && data.championById.has(id) && !visible.some((c) => c.id === id)) {
      setView({ q: '' });
    }
  }, [id]);

  const champion = id ? data.championById.get(id) : null;
  const master = html`<div class="pane">
    <div class="toolbar"><${SearchInput} value=${view.q} onInput=${(q) => setView({ q })} placeholder="Champion suchen …" /></div>
    <${VirtualTable} rows=${visible} columns=${COLUMNS} rowKey=${(c) => c.id} selected=${id} rowHeight=${44}
      onSelect=${(c) => select('abilities', c.id)} onDeselect=${() => select('abilities', null)} empty="Keine Champions gefunden" />
  </div>`;

  return html`<${MasterDetail} name="abilities" detailKey=${id} initial=${0.22} min=${0.15} max=${0.45} master=${master}
    detail=${champion ? html`<${AbilityDetail} key=${champion.id} champion=${champion} />`
      : html`<div class="detail"><${Empty} title="Champion auswählen">Wähle links einen Champion für Fähigkeiten, Abklingzeiten und Schadensvorschau.<//>
        <${SummonerSpells} data=${data} haste=${0} startTable=${true} /></div>`} />`;
}

function AbilityDetail({ champion }) {
  const data = useStore((s) => s.data);
  const haste = useStore((s) => s.prefs.haste);
  const previewLevel = useStore((s) => s.prefs.previewLevel);
  const build = useStore((s) => s.build);
  const runeCount = useStore((s) => runePageRunes(s.runePage).length);
  const buildQuery = useBuildQuery();
  const details = useFetch(`/api/champions/${champion.id}/detail`);
  const insights = useFetch(`/api/champions/${champion.id}/insights?level=${previewLevel}&${buildQuery}`, { keep: true });
  const hasBuild = build.length > 0 || runeCount > 0;

  let previewInfo = 'Lade Wiki-Daten …';
  const source = [build.length ? `${build.length} Items` : '', runeCount ? `${runeCount} Runen` : ''].filter(Boolean).join(' und ');
  if (insights.error) {
    previewInfo = `Wiki nicht verfügbar: ${insights.error.message}`;
  } else if (insights.data && insights.data.disabled) {
    previewInfo = 'Wiki-Zusatzdaten sind in den Einstellungen deaktiviert.';
  } else if (insights.data) {
    previewInfo = hasBuild
      ? `${source} aus dem Build-Rechner – Werte vor Resistenzen.`
      : 'Ohne Items und Runen (Build-Rechner ist leer) – Werte vor Resistenzen.';
  }

  return html`<div class="detail">
    <header class="plain-hero">
      <${Img} src=${champion.icon} size=${72} cls="hero-icon" />
      <div class="hero-text">
        <h1>${champion.name}</h1>
        <p class="hero-sub">${champion.title}</p>
      </div>
      <${Button} icon="arrow" onClick=${() => go('champions', champion.id)}>Zum Champion<//>
    </header>

    <div class="control-bar">
      <div class="control-grow">
        <div class="control-label">Ability Haste <strong>${haste}</strong> <span class="muted">(${percent(haste / (100 + haste))} weniger Abklingzeit)</span></div>
        <${Slider} label="Ability Haste" value=${haste} min=${0} max=${200} step=${5} onInput=${(value) => setPref({ haste: value })} ticks=${[0, 50, 100, 150, 200]} />
      </div>
      <div>
        <div class="control-label">Vorschau-Level</div>
        <${Select} label="Vorschau-Level" value=${String(previewLevel)} options=${LEVELS} onChange=${(value) => setPref({ previewLevel: Number(value) })} />
      </div>
    </div>
    <p class="muted small">Schadensvorschau: ${previewInfo}</p>

    ${details.loading ? html`<${Skeleton} lines=${8} />` : null}
    ${details.error ? html`<${ErrorNote} prefix="Fähigkeiten nicht verfügbar" error=${details.error} />` : null}
    ${details.data ? details.data.abilities.map((a) => html`<${AbilityCard} key=${a.slot} ability=${a} haste=${haste}
      buildHaste=${hasBuild && insights.data && insights.data.haste ? insights.data.haste[a.slot] : null}
      staticCooldown=${Boolean(insights.data && (insights.data.staticCooldowns || []).includes(a.slot))}
      wiki=${insights.data && insights.data.abilities ? insights.data.abilities[a.slot] : null} />`) : null}

    <${SummonerSpells} data=${data} haste=${insights.data ? insights.data.summonerSpellHaste || 0 : 0} />
  </div>`;
}

function AbilityCard({ ability: a, haste, buildHaste, staticCooldown, wiki }) {
  const ranks = Array.from({ length: a.ranks }, (_, i) => i);
  const cooldownRow = (label, value) => [label, ...ranks.map((i) => html`<strong>${i < a.cooldowns.length ? seconds(withHaste(a.cooldowns[i], value)) : '–'}</strong>`)];
  // Statische Abklingzeiten verringert Haste laut Wiki nicht.
  const hasteRows = staticCooldown || a.cooldowns.length === 0 ? [] : [
    cooldownRow('Abklingzeit mit Haste (s)', haste),
    ...(typeof buildHaste === 'number' ? [cooldownRow(`Abklingzeit mit Build (${Number(buildHaste.toFixed(1))} Haste)`, buildHaste)] : []),
  ];
  const rows = [
    [staticCooldown ? 'Abklingzeit statisch (s)' : 'Abklingzeit Basis (s)', ...a.cooldownTexts],
    ...hasteRows,
    [a.costType ? `Kosten (${a.costType})` : 'Kosten', ...a.costs],
    ['Reichweite', ...a.ranges],
  ];
  return html`<section class="card ability-card">
    <header class="ability-head">
      <${Img} src=${a.icon} size=${52} />
      <div>
        <h3><span class="slot">${a.slotLabel}</span> ${a.name}</h3>
        ${!a.passive ? html`<small class="muted">Max. Rang ${a.maxRank}${a.costType ? ` · ${a.costType}` : ''}</small>` : null}
      </div>
    </header>
    <p class="prose">${a.description}</p>
    ${a.hasRankTable ? html`<${DataGrid} head=${['Wert', ...ranks.map((i) => `Rang ${i + 1}`)]} rows=${rows} />` : null}
    ${staticCooldown ? html`<p class="muted small">Statische Abklingzeit laut Wiki: Ability Haste verringert sie nicht.</p>` : null}
    ${wiki ? wiki.map((w, i) => html`<${WikiBox} key=${i} ability=${w} />`) : null}
  </section>`;
}

/** Typische Quellen von Summoner Spell Haste (Wiki): Boots, Cosmic Insight, Crimson Lucidity und Kombinationen. */
const SPELL_HASTE_STEPS = [
  { label: 'CDR-Boots (+10)', haste: 10 },
  { label: 'Cosmic Insight (+18)', haste: 18 },
  { label: 'CDR-Boots + Insight (+28)', haste: 28 },
  { label: 'Upgraded CDR-Boots (+20)', haste: 20 },
  { label: 'Upgraded CDR-Boots + Insight (+38)', haste: 38 },
];

/** Beschwörerzauber von Summoner's Rift mit Summoner Spell Haste aus dem Build (eigene Haste-Art, kein Cap). */
function SummonerSpells({ data, haste, startTable = false }) {
  const [table, setTable] = useState(startTable);
  const spells = useMemo(() => [...data.spellByKey.values()].filter((s) => s.classic && s.cooldown > 0)
    .sort((a, b) => a.name.localeCompare(b.name, 'de')), [data]);
  if (spells.length === 0) {
    return null;
  }
  const toggle = html`<${Button} onClick=${() => setTable(!table)}>${table ? 'Kacheln' : 'Tabelle mit Reduzierungen'}<//>`;
  const aside = html`<span class="muted">Summoner Spell Haste: ${Number(haste.toFixed(1))}</span> ${toggle}`;
  const note = html`<p class="muted small">Wirkt nur auf Beschwörerzauber, nicht Ability Haste. Zählt: CDR-Boots (Ionian Boots of Lucidity, +10), Upgraded CDR-Boots (Crimson Lucidity, +20) und Cosmic Insight (+18) aus der Runenseite; der ARAM-Bonus nicht.</p>`;
  if (table) {
    const head = ['Beschwörerzauber', 'Basis', ...SPELL_HASTE_STEPS.map((x) => x.label), ...(haste > 0 ? [`Build (${Number(haste.toFixed(1))})`] : [])];
    return html`<${Card} title="Beschwörerzauber" aside=${aside}>
      <${DataGrid} cls="compact" head=${head} rows=${spells.map((s) => [
        html`<div class="cell-hero"><${Img} src=${s.icon} size=${24} /><span>${s.name}</span></div>`,
        `${seconds(s.cooldown)} s`,
        ...SPELL_HASTE_STEPS.map((x) => `${seconds(withHaste(s.cooldown, x.haste, Infinity))} s`),
        ...(haste > 0 ? [html`<strong>${seconds(withHaste(s.cooldown, haste, Infinity))} s</strong>`] : []),
      ])} />
      ${note}
    <//>`;
  }
  return html`<${Card} title="Beschwörerzauber" aside=${aside}>
    <div class="spell-grid">
      ${spells.map((s) => html`<div class="spell-chip" key=${s.key || s.name}>
        <${Img} src=${s.icon} size=${24} />
        <span class="spell-name">${s.name}</span>
        <span class="spell-cd">${seconds(s.cooldown)} s${haste > 0 ? html` <strong>→ ${seconds(withHaste(s.cooldown, haste, Infinity))} s</strong>` : null}</span>
      </div>`)}
    </div>
    ${note}
  <//>`;
}

function WikiBox({ ability: w }) {
  const ranks = Array.from({ length: w.ranks }, (_, i) => i);
  const rows = [];
  w.levelings.forEach((l) => {
    if (!l.values) {
      rows.push([l.label, html`<span class="muted">${l.text}</span>`]);
      return;
    }
    rows.push([l.label, ...ranks.map((r) => l.values[r] ?? ''), html`<span class="muted">${l.ratios}</span>`]);
    if (l.previews.length) {
      rows.push([html`<strong class="preview-label">mit Build</strong>`, ...ranks.map((r) => html`<strong>${l.previews[r] ?? ''}</strong>`),
        l.complete ? '' : html`<span class="muted">(Ratios auf Zielwerte nicht eingerechnet)</span>`]);
    }
  });
  return html`<div class="wiki-box">
    <h4>Wiki: ${w.name}</h4>
    ${w.facts ? html`<p class="muted small">${w.facts}</p>` : null}
    ${w.levelings.length ? html`<${DataGrid} cls="compact" head=${['Wert', ...ranks.map((r) => `R${r + 1}`), 'Ratios']} rows=${rows} />` : null}
  </div>`;
}
