// Ansicht "Champions": sortierbare Liste und Detail mit Werten, Wiki-Vergleich, Build und Match-Statistik.
import { html, useMemo, useEffect } from '/vendor/htm-preact.js';
import {
  useStore, useViewState, useFetch, useStatsUrl, useSourceLabel, useDeferredUrl, useWinRateClass, select, go, normalize,
  sortRows, activeSort, percent, winRateText, summary, setPref,
} from './core.js';
import {
  Img, Card, Button, SearchInput, Segmented, Slider, Tag, Meter, DataGrid, RateCell, VirtualTable, MasterDetail,
  Empty, Loading, Skeleton, ErrorNote, ItemIcons, RuneIcons, SpellIcons,
} from './ui.js';

const DEFAULTS = { q: '', sort: { key: 'name', dir: 1 } };
/** Nachkommastellen der Winrates auf der Champions-Seite. */
const WR_DECIMALS = 2;
/** Wartezeit, bevor eine Champion-Seite bei Lolalytics angefragt wird (Durchklicken der Liste). */
export const REPORT_DELAY = 350;

/** Mit Rollenfilter fallen Champions weg, deren angezeigte Pickrate (in %) höchstens so hoch ist. */
const MAX_HIDDEN_ROLE_PICK_RATE = 0.2;

const stat = (row, field) => (row.stats && row.stats.games > 0 ? row.stats[field] : null);

/** Vergleicht den gerundeten Anzeigewert: 0,24 % steht als 0.2 in der Tabelle und fällt deshalb auch weg. */
const playedEnoughOnRole = (row) => {
  const pickRate = stat(row, 'pickRate');
  return pickRate !== null && Number((pickRate * 100).toFixed(1)) > MAX_HIDDEN_ROLE_PICK_RATE;
};

const NAME_COLUMN = {
  key: 'name', title: 'Champion', width: '230px', value: (r) => r.c.name,
  render: (r) => html`<div class="cell-hero"><${Img} src=${r.c.icon} size=${34} cls="round" /><div><strong>${r.c.name}</strong><small>${r.c.title}</small></div></div>`,
};

const banRate = (r) => (r.stats ? r.stats.banRate : null);
const gamesCell = (r) => (stat(r, 'games') === null ? html`<span class="muted">–</span>` : stat(r, 'games').toLocaleString('de-DE'));

function rateColumns(tips) {
  return [
    { key: 'wr', title: 'WR', width: '68px', align: 'right', firstDir: -1, tip: tips.wr, value: (r) => stat(r, 'winRate'), render: (r) => html`<${RateCell} value=${stat(r, 'winRate')} decimals=${WR_DECIMALS} />` },
    { key: 'pr', title: 'PR', width: '60px', align: 'right', firstDir: -1, tip: tips.pr, value: (r) => stat(r, 'pickRate'), render: (r) => html`<${RateCell} value=${stat(r, 'pickRate')} neutral />` },
    { key: 'br', title: 'BR', width: '60px', align: 'right', firstDir: -1, tip: tips.br, value: banRate, render: (r) => html`<${RateCell} value=${banRate(r)} neutral />` },
    { key: 'games', title: 'Spiele', width: '84px', align: 'right', firstDir: -1, tip: tips.games, value: (r) => stat(r, 'games'), render: gamesCell },
  ];
}

const LOLALYTICS_RATES = rateColumns({
  wr: 'Winrate laut Lolalytics. Grün/Rot: mindestens 1 Punkt über bzw. mehr als 1 Punkt unter dem Durchschnitt des Rang-Bereichs.',
  pr: 'Pickrate laut Lolalytics',
  br: 'Banrate laut Lolalytics',
  games: 'Spiele laut Lolalytics',
});

const PBI_COLUMN = {
  key: 'pbi', title: 'PBI', width: '56px', align: 'right', firstDir: -1,
  tip: 'Pick Ban Influence laut Lolalytics: (Winrate − Durchschnitt des Rang-Bereichs) × 100 × Pickrate / (100 − Banrate). Hoch = starker Pick und guter Ban.',
  value: (r) => (typeof r.stats?.pbi === 'number' ? r.stats.pbi : null),
  render: (r) => (typeof r.stats?.pbi === 'number' ? r.stats.pbi : html`<span class="muted">–</span>`),
};

const STRENGTH_COLUMN = {
  key: 'strength', title: 'Stärke', width: '68px', align: 'right', firstDir: -1,
  tip: 'Eigener Indikator je Rolle: (Winrate − Durchschnitt der Rolle) × P / (P + 1) mit P = Pickrate / (1 − Banrate). Selten gespielte Picks zählen weniger. – = unter 15 % der Spiele des Champions auf dieser Rolle.',
  // Beim Wechsel des Filters liegen kurz noch Zeilen ohne dieses Feld vor.
  value: (r) => (typeof r.stats?.strength === 'number' ? r.stats.strength : null),
  render: (r) => (typeof r.stats?.strength === 'number'
    ? `${r.stats.strength >= 0 ? '+' : ''}${r.stats.strength.toFixed(2)}` : html`<span class="muted">–</span>`),
};

const LOLALYTICS_COLUMNS = [
  NAME_COLUMN,
  { key: 'lane', title: 'Lane', width: '74px', tip: 'Lane, auf die sich die Werte beziehen (ohne Rollenfilter die Hauptlane)', value: (r) => (r.stats ? r.stats.lane : null), render: (r) => html`<span class="muted">${r.stats ? r.stats.lane : '–'}</span>` },
  {
    key: 'tier', title: 'Tier', width: '56px', align: 'center', tip: 'Tier laut Lolalytics (– = zu selten auf dieser Lane)',
    value: (r) => (r.stats ? r.stats.tierRank : null),
    render: (r) => (r.stats && r.stats.tier ? html`<span class=${`tier tier-${r.stats.tier[0]}`}>${r.stats.tier}</span>` : html`<span class="muted">–</span>`),
  },
  // Reihenfolge wie bei Lolalytics: WR, PR, BR, PBI, Spiele
  ...LOLALYTICS_RATES.slice(0, 3), PBI_COLUMN, LOLALYTICS_RATES[3],
];

/** Bei gefilterter Rolle zusätzlich die Stärke direkt hinter dem Tier. */
const LOLALYTICS_ROLE_COLUMNS = [...LOLALYTICS_COLUMNS.slice(0, 3), STRENGTH_COLUMN, ...LOLALYTICS_COLUMNS.slice(3)];

export function ChampionsView({ id }) {
  const data = useStore((s) => s.data);
  const [view, setView] = useViewState('champions', DEFAULTS);
  const role = useStore((s) => s.filter.role);
  const columns = role ? LOLALYTICS_ROLE_COLUMNS : LOLALYTICS_COLUMNS;
  const sort = activeSort(columns, view.sort);
  const statsUrl = useStatsUrl('/api/stats/champions');
  const statsFetch = useFetch(statsUrl, { keep: true });
  const stats = statsFetch.data;

  const rows = useMemo(() => data.champions.map((c) => ({ c, stats: stats ? stats[c.key] : null })), [data, stats]);

  // Ohne geladene Statistik (Laden, Fehler) bleibt die Liste vollständig.
  const hideRarePicks = Boolean(role && stats);

  const visible = useMemo(() => {
    const q = normalize(view.q);
    const filtered = rows.filter((r) => (!hideRarePicks || playedEnoughOnRole(r))
      && (!q || r.c.name.toLowerCase().includes(q) || r.c.id.toLowerCase().includes(q)));
    return sortRows(filtered, columns, sort);
  }, [rows, view.q, sort.key, sort.dir, columns, hideRarePicks]);

  // Sprung aus einer anderen Ansicht: Filter nur zurücksetzen, wenn der Champion sonst ausgeblendet wäre.
  useEffect(() => {
    if (id && data.championById.has(id) && !visible.some((r) => r.c.id === id)) {
      setView({ q: '' });
    }
  }, [id]);

  const champion = id ? data.championById.get(id) : null;

  const master = html`<div class="pane">
    <div class="toolbar">
      <${SearchInput} value=${view.q} onInput=${(q) => setView({ q })} placeholder="Champion suchen …" />
    </div>
    <div class="count-line">
      <span>${visible.length} von ${data.champions.length} Champions</span>
      ${statsFetch.loading ? html`<${Loading} text="Lolalytics wird geladen …" />` : null}
      ${statsFetch.error ? html`<span class="bad" data-tip=${statsFetch.error.message}>Statistik nicht verfügbar: ${statsFetch.error.message}</span>` : null}
    </div>
    <${VirtualTable} rows=${visible} columns=${columns} rowKey=${(r) => r.c.id} selected=${id}
      onSelect=${(r) => select('champions', r.c.id)} onDeselect=${() => select('champions', null)}
      sort=${sort} onSort=${(next) => setView({ sort: next })}
      empty="Keine Champions gefunden" />
  </div>`;

  return html`<${MasterDetail} name="champions" detailKey=${id} initial=${0.6} master=${master}
    detail=${champion ? html`<${ChampionDetail} key=${champion.id} champion=${champion} />`
      : html`<${Empty} title="Champion auswählen">Wähle links einen Champion für Werte, Build und Statistik.<//>`} />`;
}

function ChampionDetail({ champion }) {
  const data = useStore((s) => s.data);
  const level = useStore((s) => s.prefs.level);
  const build = useStore((s) => s.build);
  const wiki = useFetch(`/api/champions/${champion.id}/wiki`);
  const details = useFetch(`/api/champions/${champion.id}/detail`);
  const asRatio = wiki.data && wiki.data.asRatio !== undefined ? wiki.data.asRatio : '';
  const levels = useFetch(`/api/champions/${champion.id}/levels?items=${build.join(',')}&asRatio=${asRatio}`, { keep: true });
  const i = champion.info;

  return html`<div class="detail">
    <header class="hero">
      <img class="hero-art" src=${champion.splash} alt="" decoding="async" onError=${(e) => e.currentTarget.remove()} />
      <div class="hero-content">
        <${Img} src=${champion.icon} size=${84} cls="hero-icon" />
        <div class="hero-text">
          <h1>${champion.name}</h1>
          <p class="hero-sub">${champion.title}</p>
          ${champion.resource ? html`<div class="tags"><${Tag} kind="soft">${champion.resource}<//></div>` : null}
        </div>
      </div>
    </header>

    <div class="meters">
      <${Meter} label="Angriff" value=${i.attack} />
      <${Meter} label="Verteidigung" value=${i.defense} />
      <${Meter} label="Magie" value=${i.magic} />
    </div>
    <p class="lore">${champion.blurb}</p>

    <${Card} title="Basiswerte" aside=${html`<span class="muted">Data Dragon</span>`}>
      <div class="level-row">
        <span class="level-pill">Level <strong>${level}</strong></span>
        <${Slider} label="Level" value=${level} min=${1} max=${18} onInput=${(value) => setPref({ level: value })} />
      </div>
      ${levels.data
        ? html`<${DataGrid} head=${['Wert', 'Basis', 'pro Level', `Level ${level}`]}
            rows=${levels.data.base.map((r) => [r.label, r.base, r.perLevel, html`<strong>${r.values[level - 1]}</strong>`])} />`
        : levels.error ? html`<${ErrorNote} prefix="Werte nicht verfügbar" error=${levels.error} />` : html`<${Skeleton} lines=${5} />`}
    <//>

    <${BuildValues} levels=${levels.data} level=${level} build=${build} data=${data} />
    <${WikiCompare} wiki=${wiki} />

    <${Card} title="Fähigkeiten" aside=${html`<${Button} icon="arrow" kind="ghost" onClick=${() => go('abilities', champion.id)}>Im Detail<//>`}>
      ${details.loading ? html`<${Skeleton} lines=${4} />` : null}
      ${details.error ? html`<${ErrorNote} prefix="Fähigkeiten nicht verfügbar" error=${details.error} />` : null}
      ${details.data ? html`<div class="ability-list">${details.data.abilities.map((a) => html`
        <div class="ability-row" key=${a.slot} data-tip=${a.description}>
          <${Img} src=${a.icon} size=${38} />
          <div><strong><span class="slot">${a.slotLabel}</span> ${a.name}</strong><small>${a.cooldownSummary}</small></div>
        </div>`)}</div>` : null}
    <//>

    <${MatchReport} champion=${champion} data=${data} />
  </div>`;
}

function BuildValues({ levels, level, build, data }) {
  if (!levels) {
    return null;
  }
  return html`<${Card} title="Werte mit Build">
    ${build.length === 0
      ? html`<p class="muted">Kein Build – in der Ansicht Items Gegenstände zum Build-Rechner hinzufügen.</p>`
      : html`<div class="build-strip"><${ItemIcons} data=${data} ids=${build} size=${32} />
          <span class="muted">${levels.itemCount} Items (${levels.gold.toLocaleString('de-DE')} Gold), Level ${level}. Mit dauerhaften prozentualen Item-Effekten (z. B. Rabadon's), ohne sonstige Passiven und Runen.</span></div>
        <${DataGrid} head=${['Wert', 'Gesamt', 'davon Bonus']}
          rows=${levels.finals[level - 1].map((r) => [r.label, html`<strong>${r.total}</strong>`, r.bonus])} />
        ${data.itemPassives ? html`<${PassiveStatus} info=${data.itemPassives} />` : null}`}
  <//>`;
}

/** Stand der automatisch je Patch geprüften Item-Effekte; Details stehen im Tooltip. */
function PassiveStatus({ info }) {
  const tip = [...info.changes, ...info.review.map((r) => `Nicht auswertbar: ${r}`), ...info.notes].join('\n');
  return html`<p class="muted small" data-tip=${tip || undefined}>
    Item-Effekte für Patch ${info.patch || '–'} geprüft: ${info.count} aktiv${info.changes.length ? ` · ${info.changes.length} automatisch aktualisiert` : ''}${info.review.length ? ` · ${info.review.length} nicht auswertbar` : ''}
  </p>`;
}

function WikiCompare({ wiki }) {
  let body;
  if (wiki.loading) {
    body = html`<${Skeleton} lines=${4} />`;
  } else if (wiki.error) {
    body = html`<${ErrorNote} prefix="Wiki nicht verfügbar" error=${wiki.error} />`;
  } else if (wiki.data && wiki.data.disabled) {
    body = html`<p class="muted">In den Einstellungen deaktiviert.</p>`;
  } else if (wiki.data && !wiki.data.found) {
    body = html`<p class="muted">Kein Wiki-Eintrag gefunden.</p>`;
  } else if (wiki.data) {
    body = html`<${DataGrid} head=${['Wert', 'Data Dragon', 'Wiki']}
        rows=${wiki.data.rows.map((r) => [r.label, r.dd, html`<strong class=${r.differs ? 'bad' : ''}>${r.wiki}</strong>`])} />
      ${wiki.data.info ? html`<p class="muted small">${wiki.data.info}</p>` : null}
      <p class="muted small">Abweichungen sind rot markiert; Data Dragon ist bei einzelnen Werten (z. B. Angriffsschaden pro Level) bekanntermaßen ungenau.</p>`;
  }
  return html`<${Card} title="Zusatzdaten" aside=${html`<span class="muted">LoL-Wiki</span>`}>${body}<//>`;
}

/** Rangliste: beste nach Wilson-Untergrenze, sonst häufigste. */
function Ranking({ title, ranking, render }) {
  const winRateClass = useWinRateClass();
  const hasLines = ranking && ranking.lines.length > 0;
  return html`<div class="ranking">
    <h4>${title}</h4>
    ${!hasLines ? html`<p class="muted small">keine Daten</p>` : html`
      <p class="muted small">${ranking.best ? `beste nach Wilson-Untergrenze (≥ ${ranking.minGames.toLocaleString('de-DE')} Spiele)` : 'häufigste (zu wenige Spiele für ein Wilson-Ranking)'}</p>
      ${ranking.lines.map((line, i) => html`<div class="rank-line" key=${i}>
        <div class="rank-key">${render(line.key)}</div>
        <div class="rank-stats" data-tip=${summary(line, WR_DECIMALS)}>
          <span class=${winRateClass(line.winRate)}>${percent(line.winRate, WR_DECIMALS)}</span>
          <small>${percent(line.pickRate)} PR · ${line.games.toLocaleString('de-DE')} Spiele</small>
        </div>
      </div>`)}`}
  </div>`;
}

export function SkillOrder({ order, separator }) {
  return html`<span class="skills">${order.map((slot, i) => html`<span key=${i} class="skills-part">${i > 0 ? html`<span class="skills-sep">${separator}</span>` : null}<kbd>${slot}</kbd></span>`)}</span>`;
}

function MatchReport({ champion, data }) {
  const sourceLabel = useSourceLabel();
  const winRateClass = useWinRateClass();
  const url = useStatsUrl(`/api/stats/report/${champion.key}`);
  const deferred = useDeferredUrl(url, REPORT_DELAY);
  const report = useFetch(deferred);
  let body;
  if (report.loading || !deferred) {
    body = html`<${Skeleton} lines=${6} />`;
  } else if (report.error) {
    body = html`<${ErrorNote} prefix="Statistik nicht verfügbar" error=${report.error} />`;
  } else if (report.data && report.data.games === 0) {
    body = html`<p class="muted">Lolalytics hat für diesen Champion auf dieser Lane keine Spiele.</p>`;
  } else if (report.data) {
    const r = report.data;
    const items = (ids) => html`<${ItemIcons} data=${data} ids=${ids} />`;
    const runes = (ids) => html`<${RuneIcons} data=${data} ids=${ids} />`;
    body = html`
      <div class="report-head">
        <div class="kpi"><strong>${r.games.toLocaleString('de-DE')}</strong><span>Spiele</span></div>
        <div class="kpi"><strong class=${winRateClass(r.winRate)}>${percent(r.winRate, WR_DECIMALS)}</strong><span>${winRateText(r, WR_DECIMALS).replace(/^[^(]*/, '')}</span></div>
        <div class="kpi"><strong>${percent(r.pickRate)}</strong><span>Pickrate</span></div>
        <div class="kpi"><strong>${percent(r.banRate)}</strong><span>Banrate</span></div>
      </div>
      <div class="rankings">
        <${Ranking} title="Core-Build (erste 3 Items inkl. Boots)" ranking=${r.coreBuilds} render=${items} />
        <${Ranking} title="Boots" ranking=${r.boots} render=${items} />
        <${Ranking} title="Startitems" ranking=${r.starters} render=${items} />
        <${Ranking} title="Keystone" ranking=${r.keystones} render=${runes} />
        <${Ranking} title="Runenseite" ranking=${r.runePages} render=${(page) => html`<span class="rune-page"><${RuneIcons} data=${data} ids=${page.primary} size=${26} /><${RuneIcons} data=${data} ids=${page.sub} size=${26} /><small class="muted">${page.shards}</small></span>`} />
        <${Ranking} title="Skill-Reihenfolge (Max)" ranking=${r.skillOrders} render=${(order) => html`<${SkillOrder} order=${order} separator="›" />`} />
        <${Ranking} title="Beschwörerzauber" ranking=${r.spellCombos} render=${(keys) => html`<${SpellIcons} data=${data} keys=${keys} />`} />
      </div>`;
  }
  return html`<${Card} title="Match-Statistik" aside=${html`<span class="muted">${sourceLabel}</span>`}>${body}<//>
    ${report.data && report.data.games > 0 ? html`<${Counters} counters=${report.data.counters} data=${data} source=${sourceLabel} />` : null}`;
}

const TIP_DELTA1 = 'Delta 1 laut Lolalytics: Matchup-Winrate gegenüber dem allgemeinen Durchschnitt, in Prozentpunkten.';
const TIP_DELTA2 = 'Delta 2 laut Lolalytics: Matchup-Winrate gegenüber dem normalisierten Erwartungswert, in Prozentpunkten.';
const TIP_RATING = 'Eigene Bewertung: (Delta 1 + 2 × Delta 2) / 3, bei wenigen Spielen abgeschwächt mit Spiele / (Spiele + 500).';

const DELTA_SORTS = [
  { value: 'rating', label: 'Bewertung', tip: TIP_RATING },
  { value: 'delta1', label: 'Delta 1', tip: TIP_DELTA1 },
  { value: 'delta2', label: 'Delta 2', tip: TIP_DELTA2 },
];
const DELTA_COLUMNS = DELTA_SORTS.map((s) => ({ key: s.value, value: (line) => line[s.value] }));
const COUNTERS_DEFAULTS = { deltaSort: 'rating' };

function signedPoints(value) {
  if (value === null || value === undefined) {
    return html`<span class="muted">–</span>`;
  }
  return html`<span class=${value > 0 ? 'good' : value < 0 ? 'bad' : ''}>${value > 0 ? '+' : ''}${(value * 100).toFixed(2)}</span>`;
}

function CounterChampion({ data, championKey }) {
  const champion = data.championByKey.get(championKey);
  return champion
    ? html`<div class="cell-hero"><${Img} src=${champion.icon} size=${30} cls="round" /><strong>${champion.name}</strong></div>`
    : html`<span class="muted">#${championKey}</span>`;
}

function CounterList({ title, tip, lines, data }) {
  const winRateClass = useWinRateClass();
  return html`<div class="ranking">
    <h4 data-tip=${tip}>${title}</h4>
    ${lines.length === 0 ? html`<p class="muted small">keine Daten</p>` : lines.map((line) => html`<div class="rank-line" key=${line.champion}>
      <div class="rank-key"><${CounterChampion} data=${data} championKey=${line.champion} /></div>
      <div class="rank-stats">
        <span class=${winRateClass(line.winRate)}>${percent(line.winRate, WR_DECIMALS)}</span>
        <small>${line.games.toLocaleString('de-DE')} Spiele</small>
      </div>
    </div>`)}
  </div>`;
}

function DeltaRanking({ lines, data }) {
  const [view, setView] = useViewState('champion-counters', COUNTERS_DEFAULTS);
  const winRateClass = useWinRateClass();
  const hasDeltas = lines.some((line) => line.rating !== null);
  const sorted = sortRows(lines, DELTA_COLUMNS, { key: view.deltaSort, dir: -1 });
  let body;
  if (lines.length === 0) {
    body = html`<p class="muted small">keine Daten</p>`;
  } else if (!hasDeltas) {
    body = html`<p class="muted small">Lolalytics liefert für diese Matchups keine Delta-Werte.</p>`;
  } else {
    body = html`<div class="counter-scroll"><${DataGrid}
      head=${['Gegner', 'WR', html`<span data-tip=${TIP_DELTA1}>Delta 1</span>`, html`<span data-tip=${TIP_DELTA2}>Delta 2</span>`,
        html`<span data-tip=${TIP_RATING}>Bewertung</span>`, 'Spiele']}
      rows=${sorted.map((line) => [
        html`<${CounterChampion} data=${data} championKey=${line.champion} />`,
        html`<span class=${winRateClass(line.winRate)}>${percent(line.winRate, WR_DECIMALS)}</span>`,
        signedPoints(line.delta1), signedPoints(line.delta2), html`<strong>${signedPoints(line.rating)}</strong>`,
        line.games.toLocaleString('de-DE'),
      ])} /></div>`;
  }
  return html`<div class="ranking counter-delta">
    <div class="counter-delta-head">
      <h4>Delta</h4>
      ${hasDeltas ? html`<${Segmented} label="Delta sortieren nach" value=${view.deltaSort} options=${DELTA_SORTS}
        onChange=${(deltaSort) => setView({ deltaSort })} />` : null}
    </div>
    ${body}
  </div>`;
}

/** Strong/Weak zeigen nur die Winrates der Website, die eigene Bewertung steht ausschließlich im Eintrag Delta. */
function Counters({ counters, data, source }) {
  return html`<${Card} title="Counters" aside=${html`<span class="muted">${source} · Gegner ${counters.lane} · ab ${counters.minGames.toLocaleString('de-DE')} Spielen</span>`}>
    <div class="rankings">
      <${CounterList} title="Strong Against" tip="Höchste Winrate gegen diese Gegner" lines=${counters.strong} data=${data} />
      <${CounterList} title="Weak Against" tip="Niedrigste Winrate gegen diese Gegner" lines=${counters.weak} data=${data} />
      <${DeltaRanking} lines=${counters.delta} data=${data} />
    </div>
  <//>`;
}
