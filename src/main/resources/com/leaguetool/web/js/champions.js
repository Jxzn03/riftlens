// Ansicht "Champions": sortierbare Liste und Detail mit Werten, Wiki-Vergleich, Build und Match-Statistik.
import { html, useMemo, useEffect, useState } from '/vendor/htm-preact.js';
import {
  useStore, useViewState, useFetch, useStatsUrl, useSourceLabel, useDeferredUrl, useWinRateClass, select, go, normalize,
  sortRows, activeSort, percent, setPref, useChampionRole, useChampionStatsUrl,
  useBuildQuery, useBuildItems, runePageRunes, importRunePage, useFavorites, gamePatch,
} from './core.js';
import {
  Icon, Img, Card, Button, SearchInput, Segmented, Slider, Tag, Meter, DataGrid, RateCell, VirtualTable, MasterDetail, HelpTip,
  Empty, Loading, Skeleton, ErrorNote, ItemIcons, RuneIcons, SpellIcons, FavoriteToggle,
} from './ui.js';
import { ChampionPatchChanges } from './patch.js';

const DEFAULTS = { q: '', sort: { key: 'name', dir: 1 }, favoritesOnly: false };
const DETAIL_DEFAULTS = { tab: 'overview' };
const DETAIL_TABS = [
  { value: 'overview', label: 'Übersicht' },
  { value: 'stats', label: 'Match-Statistik', tip: 'Build, Runen, Skills und Spells laut Lolalytics' },
  { value: 'counters', label: 'Counters', tip: 'Beste und schlechteste Picks gegen diesen Champion und Advanced Stats (Delta-Werte), aus Sicht des Gegners' },];
/** Nachkommastellen der Winrates auf der Champions-Seite. */
const WR_DECIMALS = 2;

/** Konfidenzintervall der Winrate, z. B. „(KI 49.10–52.30 %)“. */
const ciText = (stats, decimals) => `(KI ${(stats.lower * 100).toFixed(decimals)}–${percent(stats.upper, decimals)})`;
const winRateText = (stats, decimals = 1) => `${percent(stats.winRate, decimals)} ${ciText(stats, decimals)}`;
const summary = (line, decimals = 1) =>
  `${winRateText(line, decimals)} WR · ${percent(line.pickRate)} PR · ${line.games.toLocaleString('de-DE')} Spiele`;
/** Wartezeit, bevor eine Champion-Seite bei Lolalytics angefragt wird (Durchklicken der Liste). */
const REPORT_DELAY = 350;

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
  render: (r) => html`<div class="cell-hero"><${FavoriteToggle} championKey=${r.c.key} name=${r.c.name} size=${15} />
    <${Img} src=${r.c.icon} size=${34} cls="round" /><div><strong>${r.c.name}</strong><small>${r.c.title}</small></div></div>`,
};

const banRate = (r) => (r.stats ? r.stats.banRate : null);
const gamesCell = (r) => (stat(r, 'games') === null ? html`<span class="muted">–</span>` : stat(r, 'games').toLocaleString('de-DE'));

function rateColumns(tips) {
  return [
    { key: 'wr', title: 'Winrate', width: '84px', align: 'right', firstDir: -1, tip: tips.wr, value: (r) => stat(r, 'winRate'), render: (r) => html`<${RateCell} value=${stat(r, 'winRate')} decimals=${WR_DECIMALS} />` },
    { key: 'pr', title: 'Pickrate', width: '84px', align: 'right', firstDir: -1, tip: tips.pr, value: (r) => stat(r, 'pickRate'), render: (r) => html`<${RateCell} value=${stat(r, 'pickRate')} neutral />` },
    { key: 'br', title: 'Banrate', width: '84px', align: 'right', firstDir: -1, tip: tips.br, value: banRate, render: (r) => html`<${RateCell} value=${banRate(r)} neutral />` },
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

/** Spaltenköpfe, deren Tooltip nur mit „Show Advanced Tooltips“ erscheint. */
const PLAIN_HEAD_COLUMNS = new Set(['lane', 'tier', 'wr', 'pr', 'br', 'games']);

/** Bei gefilterter Rolle zusätzlich die Stärke direkt hinter dem Tier. */
const LOLALYTICS_ROLE_COLUMNS = [...LOLALYTICS_COLUMNS.slice(0, 3), STRENGTH_COLUMN, ...LOLALYTICS_COLUMNS.slice(3)];

export function ChampionsView({ id }) {
  const data = useStore((s) => s.data);
  const [view, setView] = useViewState('champions', DEFAULTS);
  // Eigener Zustand: Der Tab bleibt beim Durchklicken der Champions erhalten.
  const [detailView, setDetailView] = useViewState('champion-detail', DETAIL_DEFAULTS);
  const role = useStore((s) => s.filter.role);
  const advancedTips = useAdvancedTips();
  const columns = useMemo(() => {
    const all = role ? LOLALYTICS_ROLE_COLUMNS : LOLALYTICS_COLUMNS;
    return advancedTips ? all : all.map((c) => (PLAIN_HEAD_COLUMNS.has(c.key) ? { ...c, tip: undefined } : c));
  }, [role, advancedTips]);
  const sort = activeSort(columns, view.sort);
  const statsUrl = useStatsUrl('/api/stats/champions');
  const statsFetch = useFetch(statsUrl, { keep: true });
  const stats = statsFetch.data;

  const rows = useMemo(() => data.champions.map((c) => ({ c, stats: stats ? stats[c.key] : null })), [data, stats]);

  // Ohne geladene Statistik (Laden, Fehler) bleibt die Liste vollständig.
  const hideRarePicks = Boolean(role && stats);
  const favorites = useFavorites();
  // Ohne Favoriten wäre der Filter eine leere Liste ohne erkennbaren Grund.
  const favoritesOnly = Boolean(view.favoritesOnly) && favorites.size > 0;

  const visible = useMemo(() => {
    const q = normalize(view.q);
    const filtered = rows.filter((r) => (!hideRarePicks || playedEnoughOnRole(r))
      && (!favoritesOnly || favorites.has(r.c.key))
      && (!q || r.c.name.toLowerCase().includes(q) || r.c.id.toLowerCase().includes(q)));
    return sortRows(filtered, columns, sort);
  }, [rows, view.q, sort.key, sort.dir, columns, hideRarePicks, favoritesOnly, favorites]);

  // Sprung aus einer anderen Ansicht: Filter nur zurücksetzen, wenn der Champion sonst ausgeblendet wäre.
  useEffect(() => {
    if (id && data.championById.has(id) && !visible.some((r) => r.c.id === id)) {
      setView({ q: '', favoritesOnly: false });
    }
  }, [id]);

  const champion = id ? data.championById.get(id) : null;

  const master = html`<div class="pane">
    <div class="toolbar">
      <${SearchInput} value=${view.q} onInput=${(q) => setView({ q })} placeholder="Champion suchen …" />
      <button type="button" class=${`btn fav-filter ${favoritesOnly ? 'active' : ''}`} aria-pressed=${favoritesOnly} disabled=${favorites.size === 0}
        data-tip=${favorites.size === 0 ? 'Noch keine Favoriten: Stern vor einem Champion anklicken' : 'Nur deinen Champion-Pool zeigen'}
        onClick=${() => setView({ favoritesOnly: !favoritesOnly })}>
        <${Icon} name="star" size=${16} /><span>Favoriten${favorites.size ? ` (${favorites.size})` : ''}</span>
      </button>
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

  return html`<${MasterDetail} name="champions" detailKey=${`${id}:${detailView.tab}`} initial=${0.6} master=${master}
    detail=${champion ? html`<${ChampionDetail} key=${champion.id} champion=${champion} tab=${detailView.tab} onTab=${(tab) => setDetailView({ tab })} />`
      : html`<${Empty} title="Champion auswählen">Wähle links einen Champion für Werte, Build und Statistik.<//>`} />`;
}

function ChampionDetail({ champion, tab, onTab }) {
  const data = useStore((s) => s.data);
  // Liegt über den Tabs, damit der Wechsel sofort und ohne neue Anfrage geht.
  const roleState = useChampionRole(champion.key);
  const report = useChampionReport(champion, roleState.role);
  const level = useStore((s) => s.prefs.level);
  const advancedTips = useAdvancedTips();
  const build = useBuildItems();
  const buildQuery = useBuildQuery();
  const wiki = useFetch(`/api/champions/${champion.id}/wiki`);
  const details = useFetch(`/api/champions/${champion.id}/detail`);
  const asRatio = wiki.data && wiki.data.asRatio !== undefined ? wiki.data.asRatio : '';
  const levels = useFetch(`/api/champions/${champion.id}/levels?${buildQuery}&asRatio=${asRatio}`, { keep: true });
  const i = champion.info;

  const overview = () => html`
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

    <${Card} title="Fähigkeiten" aside=${html`<${Button} icon="arrow" kind="ghost" onClick=${() => go('abilities', champion.id)}>Im Detail<//>`}>
      ${details.loading ? html`<${Skeleton} lines=${4} />` : null}
      ${details.error ? html`<${ErrorNote} prefix="Fähigkeiten nicht verfügbar" error=${details.error} />` : null}
      ${details.data ? html`<div class="ability-list">${details.data.abilities.map((a) => html`
        <div class="ability-row" key=${a.slot} data-tip=${advancedTips ? a.description : undefined}>
          <${Img} src=${a.icon} size=${38} />
          <div><strong><span class="slot">${a.slotLabel}</span> ${a.name}</strong><small>${a.cooldownSummary}</small></div>
        </div>`)}</div>` : null}
    <//>

    <${ChampionPatchChanges} champion=${champion} />

    <${Card} title="Basiswerte" aside=${html`<span class="muted">${levels.data && levels.data.wiki ? 'LoL-Wiki, sonst Data Dragon' : 'Data Dragon'}</span>`}>
      <div class="level-row">
        <span class="level-pill">Level <strong>${level}</strong></span>
        <${Slider} label="Level" value=${level} min=${1} max=${18} onInput=${(value) => setPref({ level: value })} />
      </div>
      ${levels.data
        ? html`<${StatTable} head=${['Wert', 'Basis', 'pro Level', `Level ${level}`]} rows=${levels.data.base}
            cells=${(r) => [r.label,
              r.dd ? html`<span class="wiki-diff" data-tip=${`Data Dragon: ${r.dd}`}>${r.base}</span>` : r.base,
              r.perLevel, html`<strong>${r.values[level - 1]}</strong>`]} />
          ${levels.data.wikiInfo ? html`<p class="muted small">${levels.data.wikiInfo}</p>` : null}
          ${levels.data.base.some((r) => r.dd) ? html`<p class="muted small">Unterstrichene Werte weichen von Data Dragon ab (Wiki gilt); der Tooltip nennt den Data-Dragon-Wert.</p>` : null}`
        : levels.error ? html`<${ErrorNote} prefix="Werte nicht verfügbar" error=${levels.error} />` : html`<${Skeleton} lines=${5} />`}
    <//>

    <${BuildValues} levels=${levels.data} level=${level} build=${build} data=${data} />`;

  return html`<div class="detail">
    <div class="detail-tabs">
      <${Segmented} label="Ansicht" value=${tab} onChange=${onTab}
        options=${advancedTips ? DETAIL_TABS : DETAIL_TABS.map(({ tip, ...tab }) => tab)} />
      <${ChampionRoleSelect} champion=${champion} roleState=${roleState} report=${report} />
    </div>
    ${tab === 'counters' ? html`<${CountersTab} champion=${champion} report=${report} data=${data} />`
      : tab === 'stats' ? html`<${StatsTab} champion=${champion} report=${report} data=${data} />` : overview()}
  </div>`;
}

/**
 * Zusatzwerte, die eine Stats-Tabelle erst nach Klick auf den Pfeil darunter zeigt (Regeneration, Reichweite,
 * Wiki-Zusatzdaten, spezielle Haste, Lebensraub usw.); die Kernwerte stehen immer da.
 */
const EXTRA_STATS = new Set(['Reichweite', 'Angriffstempo-Ratio', 'Windup', 'Haste Grundfähigkeiten', 'Haste Ultimate',
  'Haste Immobilisierungen', 'Haste Beschwörerzauber', 'Heil- und Schildstärke', 'Lebensraub', 'Zähigkeit',
  'Verlangsamungsresistenz', 'Eff. Leben (physisch)', 'Eff. Leben (magisch)']);
const isExtraStat = (row) => EXTRA_STATS.has(row.label) || row.label.includes('Reg. (5 s)');

/** Stats-Tabelle mit den Kernwerten; der Pfeil darunter erweitert sie um alle übrigen Werte. */
function StatTable({ head, rows, cells }) {
  const [all, setAll] = useState(false);
  const core = rows.filter((r) => !isExtraStat(r));
  const shown = all || core.length === rows.length ? rows : core;
  return html`<${DataGrid} head=${head} rows=${shown.map(cells)} />
    ${core.length < rows.length ? html`<button type="button" class="stat-more" aria-expanded=${all} onClick=${() => setAll(!all)}>
      ${all ? 'Weniger Werte' : `Alle Werte (${rows.length - core.length} weitere)`}<${Icon} name="chevron" size=${14} /></button>` : null}`;
}

function BuildValues({ levels, level, build, data }) {
  const runePage = useStore((s) => s.runePage);
  if (!levels) {
    return null;
  }
  const runes = runePageRunes(runePage);
  const runeText = levels.runeCount ? `Runenseite mit ${runes.length} Runen${runePage.shards.every((s) => s !== null) ? ' und Shards' : ''}` : 'ohne Runen';
  return html`<${Card} title="Werte mit Build" aside=${html`<${Button} kind="ghost" onClick=${() => go('items')}>Items bearbeiten<//>
      <${Button} kind="ghost" onClick=${() => go('runes', 'page')}>Runen bearbeiten<//>`}>
    ${build.length === 0 && !levels.runeCount
      ? html`<p class="muted">Kein Build – in der Ansicht Items Gegenstände zum Build-Rechner hinzufügen und unter Runen eine Runenseite wählen.</p>`
      : html`<div class="build-strip"><${ItemIcons} data=${data} ids=${build} size=${32} /><${RuneIcons} data=${data} ids=${runes} size=${28} />
          <span class="muted">${levels.itemCount} Items (${levels.gold.toLocaleString('de-DE')} Gold), ${runeText}, Level ${level}. Mit dauerhaften prozentualen Item-Effekten (z. B. Rabadon's), ohne sonstige Passiven.</span></div>
        <${StatTable} head=${['Wert', 'Gesamt', 'davon Bonus']} rows=${levels.finals[level - 1]}
          cells=${(r) => [r.label, html`<strong>${r.total}</strong>`, r.bonus]} />
        ${data.itemPassives ? html`<${PassiveStatus} info=${data.itemPassives} />` : null}`}
  <//>`;
}

/** Stand der automatisch je Patch geprüften Item-Effekte; Details stehen im Tooltip. */
function PassiveStatus({ info }) {
  const advancedTips = useAdvancedTips();
  const tip = [...info.changes, ...info.review.map((r) => `Nicht auswertbar: ${r}`), ...info.notes].join('\n');
  return html`<p class="muted small" data-tip=${advancedTips && tip ? tip : undefined}>
    Item-Effekte für Patch ${info.patch ? gamePatch(info.patch) : '–'} geprüft: ${info.count} aktiv${info.changes.length ? ` · ${info.changes.length} automatisch aktualisiert` : ''}${info.review.length ? ` · ${info.review.length} nicht auswertbar` : ''}
  </p>`;
}

/** So viele Einträge zeigt eine Rangliste mit {@code withMostPlayed} neben dem meistgespielten. */
const MOST_PLAYED_TOP = 3;

/** Rangliste: beste nach Bayes-Untergrenze, sonst häufigste. */
/**
 * @param withMostPlayed zeigt unter den besten zusätzlich den meistgespielten Eintrag, kompakt im selben Platz; steht
 *                       er schon unter den besten, wird nur diese Zeile markiert und ein weiterer Eintrag rückt nach,
 *                       sodass immer drei Einträge plus der meistgespielte erscheinen (Backend liefert dafür vier); die
 *                       Zeilen verteilen sich auf die Höhe des Nachbarn (bündig mit „Items nach dem Core-Build (Itemplatz 3–5)“)
 * @param columns      zwei Spalten mit je halb so vielen Zeilen (z. B. Boots: sechs Einträge in der Höhe von dreien);
 *                       die Spielzahl steht dann ohne „Spiele“, der Kartenkopf nennt die Einheit
 * Zeilen mit {@code belowMin} (Auffüller unter der Mindest-Stichprobe) sind deutlich ausgegraut, ohne Winrate-Farbe.
 */
function Ranking({ title, ranking, render, children, withMostPlayed = false, columns = false }) {
  const winRateClass = useWinRateClass();
  const advancedTips = useAdvancedTips();
  const hasLines = ranking && ranking.lines.length > 0;
  const mostPlayed = withMostPlayed && ranking ? ranking.mostPlayed : null;
  const sameKey = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const top = mostPlayed ? ranking.lines.slice(0, MOST_PLAYED_TOP) : (ranking ? ranking.lines : []);
  const mostPlayedListed = Boolean(mostPlayed) && top.some((line) => sameKey(line.key, mostPlayed.key));
  // Steht der meistgespielte unter den ersten drei, rückt der vierte nach; die Reihenfolge bleibt die der Rangliste.
  const lines = mostPlayedListed ? ranking.lines.slice(0, MOST_PLAYED_TOP + 1) : top;
  const row = (line, key, label) => html`<div class=${`rank-line ${label ? 'most-played' : ''} ${line.belowMin ? 'below-min' : ''}`} key=${key}>
    <div class="rank-key">${render(line.key)}${label ? html`<${Tag} kind="soft">${label}<//>` : null}</div>
    <div class="rank-stats" data-tip=${advancedTips ? summary(line, WR_DECIMALS) : undefined}>
      <span class=${line.belowMin ? '' : winRateClass(line.winRate)}>${percent(line.winRate, WR_DECIMALS)}</span>
      <small>${percent(line.pickRate)} PR · ${line.games.toLocaleString('de-DE')}${columns ? '' : ' Spiele'}</small>
    </div>
  </div>`;
  return html`<div class=${`ranking ${withMostPlayed ? 'compact' : ''} ${columns ? 'columns' : ''}`}>
    <h4>${title}</h4>
    ${!hasLines ? html`<p class="muted small">keine Daten</p>` : html`
      ${ranking.best ? null : html`<p class="muted small">häufigste (zu wenige Spiele für ein Wilson-Ranking)</p>`}
      <div class="rank-lines">${lines.map((line, i) => row(line, i, mostPlayedListed && sameKey(line.key, mostPlayed.key) ? 'Meistgespielt' : null))}
        ${mostPlayed && !mostPlayedListed ? row(mostPlayed, 'most-played', 'Meistgespielt') : null}</div>`}
    ${children}
  </div>`;
}

/** Schalter "Show Advanced Tooltips" aus den Einstellungen (Standard aus). */
const useAdvancedTips = () => useStore((s) => Boolean(s.prefs.advancedTips));

const SKILL_PATH_TIP = 'Level 1–6: beste Startreihenfolge laut Lolalytics. Level 7–9 sind daraus und aus der besten Max-Reihenfolge abgeleitet, Lolalytics liefert sie nicht einzeln.';

/** Skillung je Level; Level 7–18 sind abgeleitet, weil Lolalytics nur die ersten sechs Punkte liefert. */
function SkillPathGrid({ path }) {
  const advancedTips = useAdvancedTips();
  return html`<div class="skill-path">
    <p class="muted small" data-tip=${advancedTips ? SKILL_PATH_TIP : undefined}>Beste Skillung Level 1–${path.length}</p>
    <div class="skill-path-grid">${path.map((slot, i) => html`<div key=${i}><small>${i + 1}</small><kbd>${slot}</kbd></div>`)}</div>
  </div>`;
}

function SkillOrder({ order, separator }) {
  return html`<span class="skills">${order.map((slot, i) => html`<span key=${i} class="skills-part">${i > 0 ? html`<span class="skills-sep">${separator}</span>` : null}<kbd>${slot}</kbd></span>`)}</span>`;
}

/** Report des Champions auf seiner Rolle; eine neue Rolle lädt erst dann ihre Daten. */
function useChampionReport(champion, role) {
  const url = useChampionStatsUrl(`/api/stats/report/${champion.key}`, role);
  // Liegt die Seite frisch im Cache des Backends, erscheint sie sofort; sonst bleibt es bei Wartezeit und Abruf.
  const deferred = useDeferredUrl(url, REPORT_DELAY, url && `${url}&cachedOnly=1`);
  return { ...useFetch(deferred), pending: !deferred };
}

/**
 * Rollenwahl eines Champions für alle Reiter. „Auto“ nennt die gerade verwendete Rolle: den Rollenfilter oder die
 * häufigste Rolle laut Lolalytics (aus dem geladenen Report).
 */
function ChampionRoleSelect({ champion, roleState, report }) {
  const meta = useFetch(useStatsUrl('/api/stats/meta'), { keep: true });
  const advancedTips = useAdvancedTips();
  const roles = meta.data ? meta.data.roles : [];
  const { chosen, role, setRole } = roleState;
  const filterLabel = roles.find((r) => r.value === role)?.label;
  const autoLane = !chosen && report.data && report.data.counters ? report.data.counters.lane : null;
  const autoLabel = chosen ? 'Auto' : `Auto${autoLane ? ` (${autoLane})` : filterLabel ? ` (${filterLabel})` : ''}`;
  return html`<div class="champion-role" data-tip=${advancedTips ? `Rolle von ${champion.name} für Match-Statistik, Counters und Fähigkeiten. Auto: Rollenfilter, sonst die häufigste Rolle. Eine andere Rolle lädt ihre Daten erst bei Auswahl.` : undefined}>
    <${Segmented} label=${`Rolle von ${champion.name}`} value=${chosen} onChange=${setRole}
      options=${[{ value: '', label: autoLabel }, ...roles]} />
  </div>`;
}

/** Anzeige für Laden, Fehler und fehlende Spiele; {@code null}, wenn der Report vorliegt. */
function reportPlaceholder(report) {
  if (report.loading || report.pending) {
    return html`<${Skeleton} lines=${6} />`;
  }
  if (report.error) {
    return html`<${ErrorNote} prefix="Statistik nicht verfügbar" error=${report.error} />`;
  }
  if (report.data && report.data.games === 0) {
    return html`<p class="muted">Lolalytics hat für diesen Champion auf dieser Lane keine Spiele.</p>`;
  }
  return null;
}

const LATE_ITEMS_TIP = 'Häufigste Items auf den Itemplätzen 3 bis 5 laut Lolalytics, Boots zählen nicht mit. Spiele und Winrate sind über alle drei Plätze zusammengerechnet, die Winrate nach Spielen gewichtet. Die Liste gilt über alle Spiele, nicht für einen bestimmten Core-Build. Winrates späterer Itemplätze sind überhöht, weil vor allem gut laufende Spiele so weit kommen.';

function lateItemTip(line) {
  const slots = line.slots.map((s) => `Itemplatz ${s.slot}: ${s.games.toLocaleString('de-DE')}`).join(' · ');
  return `${percent(line.winRate, WR_DECIMALS)} WR · ${line.games.toLocaleString('de-DE')} Spiele (${slots})`;
}

/** Eigener Eintrag: Lolalytics kennt nur Builds mit 3 Items, danach gibt es Listen je Itemplatz. */
function LateItems({ lines, data }) {
  const advancedTips = useAdvancedTips();
  return html`<div class="ranking">
    <h4 data-tip=${advancedTips ? LATE_ITEMS_TIP : undefined}>Items nach dem Core-Build (Itemplatz 3–5)</h4>
    ${lines.length === 0 ? html`<p class="muted small">keine Daten</p>` : html`
      <div class="late-items">${lines.map((line) => html`<div class="late-item" key=${line.item}>
        <${ItemIcons} data=${data} ids=${[line.item]} />
        <div class="late-item-stats" data-tip=${advancedTips ? lateItemTip(line) : undefined}>
          <span>${percent(line.pickRate, 1)}</span>
          <small>${percent(line.winRate, 1)} WR</small>
        </div>
      </div>`)}</div>`}
  </div>`;
}

function MatchReport({ champion, report, data }) {
  const sourceLabel = useSourceLabel();
  const winRateClass = useWinRateClass();
  let body = reportPlaceholder(report);
  if (!body && report.data) {
    const r = report.data;
    const items = (ids) => html`<${ItemIcons} data=${data} ids=${ids} />`;
    // Icons fast so groß wie bei „Items nach dem Core-Build“; die vier Zeilen verteilen sich auf dessen Höhe.
    const coreItems = (ids) => html`<${ItemIcons} data=${data} ids=${ids} size=${28} />`;
    body = html`
      <div class="report-head">
        <div class="kpi"><strong>${r.games.toLocaleString('de-DE')}</strong><span>Spiele</span></div>
        <div class="kpi"><strong class=${winRateClass(r.winRate)}>${percent(r.winRate, WR_DECIMALS)}</strong><span>${ciText(r, WR_DECIMALS)}</span></div>
        <div class="kpi"><strong>${percent(r.pickRate)}</strong><span>Pickrate</span></div>
        <div class="kpi"><strong>${percent(r.banRate)}</strong><span>Banrate</span></div>
      </div>
      <div class="rankings">
        <${Ranking} title="Core-Build (erste 3 Items inkl. Boots)" ranking=${r.coreBuilds} render=${coreItems} withMostPlayed />
        <${LateItems} lines=${r.lateItems} data=${data} />
        <${Ranking} title="Boots" ranking=${r.boots} render=${items} columns />
        <${Ranking} title="Startitems" ranking=${r.starters} render=${items} />
        <${Ranking} title="Runenseite" ranking=${r.runePages} render=${(page) => html`<span class="rune-page"><${RuneIcons} data=${data} ids=${page.primary} size=${26} /><${RuneIcons} data=${data} ids=${page.sub} size=${26} /><small class="muted">${page.shards}</small>
          <button type="button" class="chip-link import" onClick=${() => importRunePage(page, champion)}>In Build-Rechner</button></span>`} />
        <${Ranking} title="Skill-Reihenfolge (Max)" ranking=${r.skillOrders} render=${(order) => html`<${SkillOrder} order=${order} separator="›" />`}>
          ${r.skillPath.length > 0 ? html`<${SkillPathGrid} path=${r.skillPath} />` : null}
        <//>
        <${Ranking} title="Beschwörerzauber" ranking=${r.spellCombos} render=${(keys) => html`<${SpellIcons} data=${data} keys=${keys} />`} />
      </div>`;
  }
  // Mindest-Stichprobe der Ranglisten (Bayes-Grenze) steht ohne Erklärung neben dem Zeitraum.
  const minGames = report.data && report.data.minGames;
  return html`<${Card} title="Match-Statistik" aside=${html`${minGames ? html`<span class="muted">≥ ${minGames.toLocaleString('de-DE')} Spiele</span>` : null}<span class="muted">${sourceLabel}</span>`}>${body}<//>`;
}

function StatsTab({ champion, report, data }) {
  return html`${NAME_COLUMN.render({ c: champion })}<${MatchReport} champion=${champion} report=${report} data=${data} />`;
}

function CountersTab({ champion, report, data }) {
  const sourceLabel = useSourceLabel();
  const placeholder = reportPlaceholder(report);
  return html`${NAME_COLUMN.render({ c: champion })}
    ${placeholder || !report.data
      ? html`<${Card} title="Counters">${placeholder}<//>`
      : html`<${Counters} champion=${champion} counters=${report.data.counters} data=${data} source=${sourceLabel} />`}`;
}

// Counters aus Sicht des Gegners: Er hat den Champion gepickt, die Zeilen sind die Picks dagegen.
const field = (name) => (line) => line[name];
const deltaColumns = (data) => [
  { key: 'name', title: 'Pick', firstDir: 1, value: (line) => data.championByKey.get(line.champion)?.name ?? null },
  { key: 'winRate', title: 'WR', value: field('winRate') },
  { key: 'pick', title: 'Pick-WR', value: field('pick') },
  { key: 'delta1', title: 'Delta 1', value: field('delta1') },
  { key: 'delta2', title: 'Delta 2', value: field('delta2') },
  { key: 'rating', title: 'Bewertung', value: field('rating') },
  { key: 'games', title: 'Spiele', value: field('games') },
];
const COUNTERS_DEFAULTS = { deltaSort: 'rating', deltaDir: -1 };

const deltaHelpRows = (name) => [
  ['WR', `Winrate des Picks gegen ${name}: 2 × Durchschnitts-WR − Matchup-WR von ${name}`, `Mit diesem Pick gewinnt man gegen ${name} so oft (Lolalytics-Daten aus Sicht von ${name}, gespiegelt).`],
  ['Delta 1', `WR des Picks minus (100 − Durchschnitts-Winrate von ${name})`, `Der Pick schlägt ${name} öfter als ein durchschnittlicher Champion.`],
  ['Delta 2', 'WR des Picks minus normalisierter Erwartungswert der Paarung', `Er gewinnt öfter gegen ${name}, als die allgemeine Stärke beider Champions erwarten lässt. Bester Hinweis auf einen echten Counter.`],
  ['Bewertung', 'Delta 2 × Spiele / (Spiele + 700)', 'Eigene Kennzahl, kein Lolalytics-Wert: geschätzter echter Counter-Effekt, zwischen Picks vergleichbar. Delta 1 fließt nicht ein, weil es sich je Champion nur um einen festen Wert von Delta 2 unterscheidet.'],
  ['Pick-WR', '2 × Durchschnitts-WR − (Matchup-WR − Delta 2 × 700 / (Spiele + 700))', `Geschätzte Winrate des Picks gegen ${name}, inklusive seiner allgemeinen Stärke. Bewertung zeigt, wer ${name} gezielt kontert; Pick-WR, mit wem man gegen ${name} am häufigsten gewinnt.`],
];

function DeltaHelp({ name }) {
  return html`<${HelpTip} label="Erklärung der Delta-Werte">
    <${DataGrid} head=${['Wert', 'Bedeutung', 'Aussage bei positivem Wert']} rows=${deltaHelpRows(name)} />
    <p class="muted small">Sicht des Gegners: ${name} ist gepickt, die Zeilen sind die Picks dagegen. Deltas sind Prozentpunkte, keine Winrate. Die Delta-Definitionen stammen aus Website-Tooltips und Community-Beiträgen, nicht aus einer offiziellen Doku.</p>
  <//>`;
}

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
  const advancedTips = useAdvancedTips();
  return html`<div class="ranking">
    <h4 data-tip=${advancedTips ? tip : undefined}>${title}</h4>
    ${lines.length === 0 ? html`<p class="muted small">keine Daten</p>` : lines.map((line) => html`<div class="rank-line" key=${line.champion}>
      <div class="rank-key"><${CounterChampion} data=${data} championKey=${line.champion} /></div>
      <div class="rank-stats">
        <span class=${winRateClass(line.winRate)}>${percent(line.winRate, WR_DECIMALS)}</span>
        <small>${line.games.toLocaleString('de-DE')} Spiele</small>
      </div>
    </div>`)}
  </div>`;
}

function DeltaRanking({ name, lines, data }) {
  const [view, setView] = useViewState('champion-counters', COUNTERS_DEFAULTS);
  const winRateClass = useWinRateClass();
  const hasDeltas = lines.some((line) => line.rating !== null);
  const columns = deltaColumns(data);
  const sort = { key: view.deltaSort, dir: view.deltaDir };
  const sorted = sortRows(lines, columns, sort);
  const headCell = (column) => {
    const active = sort.key === column.key;
    const next = active ? -sort.dir : (column.firstDir || -1);
    return html`<button type="button" class=${`sort-head ${active ? 'active' : ''}`} data-tip=${column.tip}
      onClick=${() => setView({ deltaSort: column.key, deltaDir: next })}>
      <span>${column.title}</span>${active ? html`<${Icon} name=${sort.dir === 1 ? 'up' : 'down'} size=${12} />` : null}
    </button>`;
  };
  let body;
  if (lines.length === 0) {
    body = html`<p class="muted small">keine Daten</p>`;
  } else if (!hasDeltas) {
    body = html`<p class="muted small">Lolalytics liefert für diese Matchups keine Delta-Werte.</p>`;
  } else {
    body = html`<${DataGrid}
      head=${columns.map(headCell)}
      rows=${sorted.map((line) => [
        html`<${CounterChampion} data=${data} championKey=${line.champion} />`,
        html`<span class=${winRateClass(line.winRate)}>${percent(line.winRate, WR_DECIMALS)}</span>`,
        line.pick === null ? html`<span class="muted">–</span>` : html`<strong class=${winRateClass(line.pick)}>${percent(line.pick, WR_DECIMALS)}</strong>`,
        signedPoints(line.delta1), signedPoints(line.delta2), html`<strong>${signedPoints(line.rating)}</strong>`,
        line.games.toLocaleString('de-DE'),
      ])} />`;
  }
  return html`<div class="ranking counter-delta">
    <div class="counter-delta-head">
      <h4>Advanced Stats <${DeltaHelp} name=${name} /></h4>
    </div>
    ${body}
  </div>`;
}

/**
 * Sicht des Gegners: Er hat den Champion gepickt, gesucht sind die besten Picks dagegen. Beste/Schlechteste Picks zeigen
 * nur die (gespiegelte) Winrate laut Daten, die eigene Bewertung steht ausschließlich im Eintrag Advanced Stats.
 */
function Counters({ champion, counters, data, source }) {
  return html`<${Card} title=${`Counters gegen ${champion.name}`} aside=${html`<span class="muted">${source} · Picks ${counters.lane} · ab ${counters.minGames.toLocaleString('de-DE')} Spielen</span>`}>
    <div class="rankings">
      <${CounterList} title="Beste Picks dagegen" tip=${`Höchste Winrate gegen ${champion.name}`} lines=${counters.best} data=${data} />
      <${CounterList} title="Schlechteste Picks dagegen" tip=${`Niedrigste Winrate gegen ${champion.name}`} lines=${counters.worst} data=${data} />
      <${DeltaRanking} name=${champion.name} lines=${counters.delta} data=${data} />
    </div>
  <//>`;
}
