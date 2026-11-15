// Ansicht "Abilities": Fähigkeiten je Rang, Abklingzeit mit Ability Haste, Wiki-Details, Schadensvorschau.
import { html, useMemo, useEffect } from '/vendor/htm-preact.js';
import {
  useStore, useViewState, useFetch, useStatsUrl, useSourceLabel, useDeferredUrl, select, go, normalize, percent, setPref,
  summary,
} from './core.js';
import { SkillOrder, REPORT_DELAY } from './champions.js';
import {
  Img, Card, Button, Select, SearchInput, Slider, DataGrid, VirtualTable, MasterDetail, Empty, Skeleton, ErrorNote,
} from './ui.js';

const DEFAULTS = { q: '' };
const LEVELS = Array.from({ length: 18 }, (_, i) => ({ value: String(i + 1), label: String(i + 1) }));

const COLUMNS = [{
  key: 'name', title: 'Champion', width: '240px',
  render: (c) => html`<div class="cell-hero"><${Img} src=${c.icon} size=${32} cls="round" /><div><strong>${c.name}</strong></div></div>`,
}];

/** Abklingzeit mit Ability Haste (Formel wie calc.CooldownCalculator). */
const withHaste = (base, haste) => (base * 100) / (100 + haste);

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
      onSelect=${(c) => select('abilities', c.id)} empty="Keine Champions gefunden" />
  </div>`;

  return html`<${MasterDetail} name="abilities" detailKey=${id} initial=${0.22} min=${0.15} max=${0.45} master=${master}
    detail=${champion ? html`<${AbilityDetail} key=${champion.id} champion=${champion} />`
      : html`<${Empty} title="Champion auswählen">Wähle links einen Champion für Fähigkeiten, Abklingzeiten und Schadensvorschau.<//>`} />`;
}

function AbilityDetail({ champion }) {
  const haste = useStore((s) => s.prefs.haste);
  const previewLevel = useStore((s) => s.prefs.previewLevel);
  const build = useStore((s) => s.build);
  const details = useFetch(`/api/champions/${champion.id}/detail`);
  const insights = useFetch(`/api/champions/${champion.id}/insights?level=${previewLevel}&items=${build.join(',')}`, { keep: true });
  const sourceLabel = useSourceLabel();
  const reportUrl = useStatsUrl(`/api/stats/report/${champion.key}`);
  const deferredReport = useDeferredUrl(reportUrl, REPORT_DELAY);
  const report = useFetch(deferredReport);

  let previewInfo = 'Lade Wiki-Daten …';
  if (insights.error) {
    previewInfo = `Wiki nicht verfügbar: ${insights.error.message}`;
  } else if (insights.data && insights.data.disabled) {
    previewInfo = 'Wiki-Zusatzdaten sind in den Einstellungen deaktiviert.';
  } else if (insights.data) {
    previewInfo = build.length === 0
      ? 'Ohne Items (Build-Rechner ist leer) – Werte vor Resistenzen.'
      : `${build.length} Items aus dem Build-Rechner – Werte vor Resistenzen.`;
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
      buildHaste=${build.length > 0 && insights.data && insights.data.haste ? insights.data.haste[a.slot] : null}
      wiki=${insights.data && insights.data.abilities ? insights.data.abilities[a.slot] : null} />`) : null}

    <${Card} title="Skill-Reihenfolge" aside=${html`<span class="muted">${sourceLabel}</span>`}>
      ${report.loading || !deferredReport ? html`<${Skeleton} lines=${3} />` : null}
      ${report.error ? html`<${ErrorNote} prefix="Statistik nicht verfügbar" error=${report.error} />` : null}
      ${report.data && report.data.games === 0 ? html`<p class="muted">Lolalytics hat für diesen Champion auf dieser Lane keine Spiele.</p>` : null}
      ${report.data && report.data.games > 0 ? html`<div class="rankings">
        <${SkillRanking} title="Max-Reihenfolge" ranking=${report.data.skillOrders} separator="›" />
        <${SkillRanking} title="Erste Skill-Punkte" ranking=${report.data.skillStarts} separator="–" />
      </div>` : null}
    <//>
  </div>`;
}

function SkillRanking({ title, ranking, separator }) {
  return html`<div class="ranking"><h4>${title}</h4>
    ${ranking.lines.length === 0 ? html`<p class="muted small">keine Daten</p>` : html`
      <p class="muted small">${ranking.best ? `beste nach Wilson-Untergrenze (≥ ${ranking.minGames.toLocaleString('de-DE')} Spiele)` : 'häufigste (zu wenige Spiele für ein Wilson-Ranking)'}</p>
      ${ranking.lines.map((line, i) => html`<div class="rank-line" key=${i}>
        <div class="rank-key"><${SkillOrder} order=${line.key} separator=${separator} /></div>
        <div class="rank-stats wide"><small>${summary(line)}</small></div>
      </div>`)}`}
  </div>`;
}

function AbilityCard({ ability: a, haste, buildHaste, wiki }) {
  const ranks = Array.from({ length: a.ranks }, (_, i) => i);
  const cooldownRow = (label, value) => [label, ...ranks.map((i) => html`<strong>${i < a.cooldowns.length ? Number(withHaste(a.cooldowns[i], value).toFixed(2)) : '–'}</strong>`)];
  const rows = [
    ['Abklingzeit Basis (s)', ...a.cooldownTexts],
    cooldownRow('Abklingzeit mit Haste (s)', haste),
    ...(typeof buildHaste === 'number' ? [cooldownRow(`Abklingzeit mit Build (${Number(buildHaste.toFixed(1))} Haste)`, buildHaste)] : []),
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
    ${wiki ? wiki.map((w, i) => html`<${WikiBox} key=${i} ability=${w} />`) : null}
  </section>`;
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
