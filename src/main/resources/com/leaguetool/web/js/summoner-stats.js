// Summoner-Tab: Champions (eigene und gegnerische mit Matchups, Empfehlungen), Spielstil und Meisterschaft.
// Aufbau und Kennzahlen nach op.gg; gerechnet wird aus den abgerufenen Matches (Riot-API), ohne weitere Abrufe.
import { html, useMemo, useState } from '/vendor/htm-preact.js';
import { go, percent, useStore } from './core.js';
import { Img, Card, DataGrid, Segmented, Select, Tag, MoreToggle, SortTh, useSort } from './ui.js';

const wrClass = (value) => (value >= 0.52 ? 'good' : value < 0.48 ? 'bad' : '');
const number = (value) => Math.round(value).toLocaleString('de-DE');
const fixed = (value, digits = 1) => value.toLocaleString('de-DE', { minimumFractionDigits: digits, maximumFractionDigits: digits });
const kdaRatio = (g) => (g.deaths === 0 ? 'Perfekt' : fixed((g.kills + g.assists) / g.deaths, 2));
const TOP_CHAMPIONS = 10;
const TOP_MATCHUPS = 8;
const TOP_MATCHUPS_PREVIEW = 5; // automatisch aufgeklappter Platz 1: nur eine kurze Vorschau
const TOP_MASTERY = 7; // eine volle Kartenreihe

/** Leere Summe für eine Gruppe von Matches; Detailwerte nur aus Matches mit erweiterten Daten (Cache v2). */
export function statGroup(extra = {}) {
  return {
    ...extra, games: 0, wins: 0, kills: 0, deaths: 0, assists: 0, cs: 0, seconds: 0,
    details: 0, detailSeconds: 0, kp: 0, kpGames: 0, lane: 0, laneGames: 0, damage: 0, share: 0, shareGames: 0,
    taken: 0, vision: 0, control: 0, placed: 0, cleared: 0, gold: 0, multi: [0, 0, 0, 0], solo: 0,
  };
}

export function addStat(g, m) {
  g.games += 1;
  g.wins += m.win ? 1 : 0;
  g.kills += m.kills;
  g.deaths += m.deaths;
  g.assists += m.assists;
  g.cs += m.cs;
  g.seconds += m.durationSec;
  if (m.side === undefined) {
    return;
  }
  g.details += 1;
  g.detailSeconds += m.durationSec;
  if (m.kp !== null) { g.kp += m.kp; g.kpGames += 1; }
  if (m.laneLead !== null) { g.lane += m.laneLead ? 1 : 0; g.laneGames += 1; }
  if (m.damageShare !== null) { g.share += m.damageShare; g.shareGames += 1; }
  g.damage += m.damage;
  g.taken += m.damageTaken;
  g.vision += m.vision;
  g.control += m.controlWards;
  g.placed += m.wardsPlaced;
  g.cleared += m.wardsKilled;
  g.gold += m.gold;
  g.solo += m.soloKills || 0;
  m.multi.forEach((n, i) => { g.multi[i] += n; });
}

const perGame = (g, value) => (g.details ? value / g.details : null);
const perMinute = (g, value) => (g.detailSeconds ? value / (g.detailSeconds / 60) : null);
const byGames = (a, b) => b.games - a.games || b.wins / b.games - a.wins / a.games;

/** Eigene Champions mit Matchups je Gegner oder umgekehrt Gegner-Champions mit den eigenen Champions dagegen. */
function championTable(matches, opponents) {
  const groups = new Map();
  matches.filter((m) => !m.remake).forEach((m) => {
    const key = opponents ? m.opponent?.key : m.championKey;
    if (!key) {
      return;
    }
    if (!groups.has(key)) {
      groups.set(key, { ...statGroup({ key, name: opponents ? m.opponent.name : m.championName }), sub: new Map(), noLane: {} });
    }
    const group = groups.get(key);
    addStat(group, m);
    const subKey = opponents ? m.championKey : m.opponent?.key;
    if (subKey) {
      if (!group.sub.has(subKey)) {
        group.sub.set(subKey, statGroup({ key: subKey, name: opponents ? m.championName : m.opponent.name }));
      }
      addStat(group.sub.get(subKey), m);
    } else {
      // Spiele ohne Lane-Gegner (ARAM, Arena, URF …) je Modus, damit die Matchups erklärbar bleiben.
      group.noLane[m.mode] = (group.noLane[m.mode] || 0) + 1;
    }
  });
  return [...groups.values()].map((g) => ({ ...g, sub: [...g.sub.values()].sort(byGames) })).sort(byGames);
}

function Champ({ data, championKey, name, size = 28 }) {
  const champion = data.championByKey.get(championKey);
  if (!champion) {
    return html`<span>${name || `Champion ${championKey}`}</span>`;
  }
  return html`<button type="button" class="champion-link" onClick=${() => go('champions', champion.id)}>
    <${Img} src=${champion.icon} size=${size} cls="round" /><span>${champion.name}</span>
  </button>`;
}

// Hinweise nur bei „Show Advanced Tooltips“ (Nutzerwunsch: Gespielt und Siege/Niederlagen ohne Tooltip).
const useAdvancedTips = () => useStore((s) => Boolean(s.prefs.advancedTips));
const ADVANCED_ONLY = new Set(['Gespielt']);
const headTip = (label, tip, advanced) => (ADVANCED_ONLY.has(label) && !advanced ? null : tip || null);

function Played({ g }) {
  const advanced = useAdvancedTips();
  const wr = g.wins / g.games;
  return html`<div class="played-cell">
    <div class=${`wr-bar${g.wins === g.games ? " all-win" : g.wins === 0 ? " all-loss" : ""}`} data-tip=${advanced ? `${g.wins} Siege, ${g.games - g.wins} Niederlagen` : null}>
      <span class="win" style=${{ width: `${wr * 100}%` }}>${g.wins}S</span><span class="loss">${g.games - g.wins}N</span>
    </div>
    <strong class=${wrClass(wr)}>${percent(wr, 0)}</strong>
  </div>`;
}

/** Zellen wie op.gg: Gespielt, KDA (Kill-Beteiligung), Schaden, Wards, CS, Gold, Mehrfachkills. */
function statCells(g, compact = false) {
  const kp = g.kpGames ? g.kp / g.kpGames : null;
  const dpm = perMinute(g, g.damage);
  const cells = [
    html`<${Played} g=${g} />`,
    html`<div class="two-line"><strong>${kdaRatio(g)}</strong><small class="muted">${fixed(g.kills / g.games)} / ${fixed(g.deaths / g.games)} / ${fixed(g.assists / g.games)}${kp === null ? '' : ` (${percent(kp, 0)})`}</small></div>`,
  ];
  if (compact) {
    cells.push(html`<div class="two-line"><strong>${fixed(g.cs / g.games)}</strong><small class="muted">${g.seconds ? fixed(g.cs / (g.seconds / 60)) : '–'}/Min</small></div>`);
    return cells;
  }
  cells.push(
    dpm === null ? html`<span class="muted">–</span>` : html`<div class="two-line"><strong>${number(dpm)}</strong><small class="muted">${g.shareGames ? percent(g.share / g.shareGames, 1) : '–'}</small></div>`,
    g.details ? html`<div class="two-line"><strong>${fixed(perGame(g, g.vision), 0)}</strong><small class="muted">${fixed(perGame(g, g.control), 0)} (${fixed(perGame(g, g.placed), 0)}/${fixed(perGame(g, g.cleared), 0)})</small></div>` : html`<span class="muted">–</span>`,
    html`<div class="two-line"><strong>${fixed(g.cs / g.games, 0)}</strong><small class="muted">${g.seconds ? fixed(g.cs / (g.seconds / 60)) : '–'}</small></div>`,
    g.details ? html`<div class="two-line"><strong>${number(perGame(g, g.gold))}</strong><small class="muted">${fixed(perMinute(g, g.gold))}</small></div>` : html`<span class="muted">–</span>`,
    ...g.multi.map((n) => (n ? html`<strong>${n}</strong>` : html`<span class="muted">0</span>`)),
  );
  return cells;
}

const FULL_HEAD = [
  ['Gespielt', 'Siege und Niederlagen, Winrate'],
  ['KDA', 'Durchschnittliche KDA\nØ Kills / Tode / Assists (Ø Kill-Beteiligung)'],
  ['Schaden', 'Ø Schaden an Champions pro Minute\nØ Schadensanteil im Team'],
  ['Wards', 'Ø Vision Score\nØ Control Wards (Ø platziert / Ø zerstört)'],
  ['CS', 'Ø CS\nØ CS pro Minute'],
  ['Gold', 'Ø Gold\nØ Gold pro Minute'],
  ['Double', ''], ['Triple', ''], ['Quadra', ''], ['Penta', ''],
];
const COMPACT_HEAD = [FULL_HEAD[0], FULL_HEAD[1], FULL_HEAD[4]];

function Head({ cols, first }) {
  const advanced = useAdvancedTips();
  return html`<thead><tr><th>${first}</th>${cols.map(([label, tip]) => html`<th key=${label} data-tip=${headTip(label, tip, advanced)}>${label}</th>`)}</tr></thead>`;
}

/** Sortwerte zu FULL_HEAD, davor der Champion-Name (Spalte 0). */
const champValues = (data) => [
  (g) => data.championByKey.get(g.key)?.name || g.name || '',
  (g) => g.games,
  (g) => (g.deaths === 0 ? Infinity : (g.kills + g.assists) / g.deaths),
  (g) => perMinute(g, g.damage),
  (g) => perGame(g, g.vision),
  (g) => g.cs / g.games,
  (g) => perGame(g, g.gold),
  ...[0, 1, 2, 3].map((i) => (g) => g.multi[i]),
];

export function ChampionsSection({ matches, data, recommendations }) {
  const [side, setSide] = useState('mine');
  const [open, setOpen] = useState(undefined); // undefined: Platz 1 aufgeklappt, bis der Nutzer selbst auf- oder zuklappt
  const [all, setAll] = useState(false);
  const opponents = side === 'opponents';
  const recommended = side === 'recommended';
  const rows = useMemo(() => championTable(matches, opponents), [matches, opponents]);
  const withDetails = matches.filter((m) => !m.remake && m.side !== undefined).length;
  const valid = matches.filter((m) => !m.remake).length;
  const advanced = useAdvancedTips();
  // Spalte „Anteil“ (Anteil an den gewerteten Spielen) direkt nach „Gespielt“.
  const values = champValues(data);
  values.splice(2, 0, (g) => g.games);
  const sorting = useSort(rows, values);
  const shown = all ? sorting.sorted : sorting.sorted.slice(0, TOP_CHAMPIONS);
  const heads = [[opponents ? 'Gegner' : 'Champion', ''], FULL_HEAD[0], ['Anteil', ''], ...FULL_HEAD.slice(1)];
  const withShare = (g) => { const cells = statCells(g); cells.splice(1, 0, html`<span class="muted">${percent(valid ? g.games / valid : 0, 0)}</span>`); return cells; };
  const openKey = open === undefined ? (shown[0]?.sub.length ? shown[0].key : null) : open;
  return html`<div class="summoner-section">
    <${Card} title=${recommended ? 'Empfohlene Champions' : opponents ? 'Gegnerische Champions' : 'Meine Champions'}
      aside=${html`<${Segmented} label="Ansicht" value=${side} onChange=${(v) => { setSide(v); setOpen(undefined); }}
        options=${[{ value: 'mine', label: 'Meine Champions' }, { value: 'opponents', label: 'Gegnerische Champions', tip: 'Deine Bilanz gegen den Champion deines direkten Lane-Gegners' },
          { value: 'recommended', label: 'Empfohlene Champions', tip: REC_TIP }]} />`}>
      ${recommended ? html`<${Recommendations} rec=${recommendations} data=${data} />` : html`
      ${opponents && rows.length ? html`<p class="muted small">Lane-Gegner bekannt in ${rows.reduce((s, g) => s + g.games, 0)} von ${valid} Spielen (ARAM, Arena, URF u. a. haben keinen).</p>` : null}
      ${withDetails < valid ? html`<p class="muted small">${valid - withDetails} von ${valid} Matches stammen aus dem alten Cache ohne Lane-Gegner und erweiterte Werte. Eine neue Abfrage lädt sie einmal nach.</p>` : null}
      ${rows.length === 0 ? html`<p class="muted">${opponents ? 'Keine Matches mit bekanntem Lane-Gegner in dieser Auswahl.' : 'Keine Matches in dieser Auswahl.'}</p>` : html`<div class="grid-scroll">
        <table class="data champ-stats">
          <thead><tr><th class="rank-col">#</th>${heads.map(([label, tip], i) => html`<${SortTh} key=${label} label=${label} tip=${headTip(label, tip, advanced)} col=${i} state=${sorting} />`)}</tr></thead>
          <tbody>${shown.map((g, index) => html`
            <tr key=${g.key} class=${`champ-row${openKey === g.key ? ' open' : ''}`}>
              <td class="rank-col">${index + 1}</td>
              <td><div class="champ-cell">
                <button type="button" class="expand" aria-expanded=${openKey === g.key} disabled=${g.sub.length === 0 && !Object.keys(g.noLane || {}).length}
                  data-tip=${g.sub.length ? (opponents ? 'Deine Champions gegen diesen Gegner' : 'Matchups gegen Lane-Gegner') : 'Nur Spiele ohne Lane-Gegner (z. B. Arena, URF)'}
                  onClick=${() => setOpen(openKey === g.key ? null : g.key)}>${openKey === g.key ? '▾' : '▸'}</button>
                <${Champ} data=${data} championKey=${g.key} name=${g.name} />
              </div></td>
              ${withShare(g).map((cell, i) => html`<td key=${i}>${cell}</td>`)}
            </tr>
            ${openKey === g.key ? html`<tr class="matchup-row" key=${`${g.key}-sub`}><td colspan=${FULL_HEAD.length + 3}>
              <${Matchups} group=${g} data=${data} opponents=${opponents} top=${open === undefined ? TOP_MATCHUPS_PREVIEW : TOP_MATCHUPS} />
            </td></tr>` : null}`)}
          </tbody>
        </table></div>
        ${rows.length > TOP_CHAMPIONS ? html`<${MoreToggle} open=${all} total=${rows.length} top=${TOP_CHAMPIONS} onToggle=${() => setAll(!all)} />` : null}`}
      ${advanced ? html`<p class="muted small">Lane-Gegner ist wie bei op.gg der Spieler des anderen Teams auf derselben Position. Werte aus den abgerufenen Matches. Die OP-Wertung ist ein eigenes Verfahren von op.gg und fehlt deshalb.</p>` : null}`}
    <//>
  </div>`;
}

function Matchups({ group, data, opponents, top = TOP_MATCHUPS }) {
  const [all, setAll] = useState(false);
  const shown = all ? group.sub : group.sub.slice(0, top);
  const noLane = Object.values(group.noLane || {}).reduce((sum, n) => sum + n, 0);
  return html`<div class="matchups">
    ${group.sub.length ? html`<table class="data compact">
      <${Head} first=${opponents ? 'Dein Champion' : 'vs. Lane-Gegner'} cols=${COMPACT_HEAD} />
      <tbody>${shown.map((s) => html`<tr key=${s.key}>
        <td><${Champ} data=${data} championKey=${s.key} name=${s.name} size=${22} /></td>
        ${statCells(s, true).map((cell, i) => html`<td key=${i}>${cell}</td>`)}
      </tr>`)}</tbody>
    </table>` : null}
    ${group.sub.length > top ? html`<${MoreToggle} open=${all} total=${group.sub.length} top=${top} onToggle=${() => setAll(!all)} />` : null}
    ${noLane ? html`<p class="muted small no-lane">Ohne Lane-Gegner: ${noLane} von ${group.games} Spielen (${Object.entries(group.noLane).sort((a, b) => b[1] - a[1]).map(([mode, n]) => `${mode} ${n}`).join(', ')}); ARAM, Arena, URF u. a. haben keine Lane.</p>` : null}
  </div>`;
}

const TAG_KIND = { honey: 'honey', positive: 'good-tag', negative: 'bad-tag', neutral: 'soft' };

const REC_TIP = 'Wie bei op.gg: kaum gespielte Champions deiner Hauptposition, gewichtet nach Stärke (Lolalytics, nur gecacht), passendem Spielstil und Bann-Druck.';

function Recommendations({ rec, data }) {
  if (!rec) {
    return html`<p class="muted">Keine Empfehlungen verfügbar.</p>`;
  }
  let body;
  if (rec.status === 'no-games') {
    body = html`<p class="muted">Noch nicht genug Spiele auf Summoner's Rift mit fester Position für eine Empfehlung.</p>`;
  } else if (!rec.lolalytics) {
    body = html`<p class="muted">Für Empfehlungen in den Optionen Lolalytics aktivieren. Es werden nur bereits geladene Tierlisten genutzt.</p>`;
  } else if (rec.status === 'no-meta') {
    body = html`<p class="muted">Für deine Hauptposition ${rec.mainRoleLabel} liegt noch keine Lolalytics-Tierliste im Cache. Einmal im Champions-Tab den Rollenfilter ${rec.mainRoleLabel} wählen, dann erscheinen hier Empfehlungen. Der Summoner-Tab ruft Lolalytics selbst nie ab.</p>`;
  } else if (rec.items.length === 0) {
    body = html`<p class="muted">Keine passenden Champions gefunden.</p>`;
  } else {
    // Gründe, die jeder Vorschlag wörtlich gleich hat, nur einmal über der Liste (z. B. nicht „Starker Pick #n“).
    const common = rec.items.length > 1
      ? rec.items[0].tags.filter((t) => rec.items.every((r) => r.tags.some((o) => o.code === t.code && o.label === t.label))) : [];
    const tag = (t) => html`<span key=${t.code} data-tip=${t.tip}><${Tag} kind=${TAG_KIND[t.tone] || 'soft'}>${t.label}<//></span>`;
    body = html`${common.length ? html`<div class="tags rec-common"><small class="muted">Bei allen:</small>${common.map(tag)}</div>` : null}
    <div class="rec-grid">${[...rec.items, ...(rec.bans || []).map((b) => ({ ...b, ban: true }))].map((r) => {
      const own = r.ban ? r.tags : r.tags.filter((t) => !common.some((c) => c.code === t.code && c.label === t.label));
      const champion = data.championByKey.get(r.championKey);
      return html`<div class=${`rec-card${r.ban ? ' ban' : ''}`} key=${`${r.ban ? 'b' : 'p'}${r.championKey}`}>
        <div class="rec-head">
          <span class="rec-rank" data-tip=${r.ban ? 'Empfohlener Bann' : null}>${r.ban ? 'B' : r.rank}</span>
          <${Champ} data=${data} championKey=${r.championKey} size=${28} />
        </div>
        <div class="rec-positions">${r.positions.map((p) => html`<span key=${p.role} data-tip=${`${p.label}: Winrate ${percent(p.winRate)}, Pickrate ${percent(p.pickRate)}, Bannrate ${percent(p.banRate)}`}>
          ${p.label} <strong>${p.tier || '–'}</strong> <span class=${wrClass(p.winRate)}>${percent(p.winRate)}</span></span>`)}</div>
        ${own.length ? html`<div class="tags">${own.map(tag)}</div>` : null}
        ${champion ? null : html`<small class="muted">Champion ${r.championKey}</small>`}
      </div>`;
    })}</div>`;
  }
  return html`${body}<p class="muted small">Basierend auf deinen meistgespielten Champions und Ergebnissen. ${REC_TIP}</p>`;
}

// ------------------------------------------------------------------ Spielstil

const WEEKDAYS = ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag'];
const WEEK_ORDER = [1, 2, 3, 4, 5, 6, 0];
const RECENT = 20;
const FOCUS_TOP = 3;

function hoursAndMinutes(seconds) {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.round((seconds % 3600) / 60);
  return hours ? `${hours} Std. ${minutes} Min.` : `${minutes} Min.`;
}

function clock(seconds) {
  return `${Math.floor(seconds / 60)}:${String(Math.round(seconds % 60)).padStart(2, '0')}`;
}

function summary(list) {
  const g = statGroup();
  list.forEach((m) => addStat(g, m));
  return g;
}

export function StyleSection({ matches, data, totalChampions }) {
  const valid = useMemo(() => matches.filter((m) => !m.remake), [matches]);
  const all = useMemo(() => summary(valid), [valid]);
  const recent = useMemo(() => summary(valid.slice(0, RECENT)), [valid]);
  const sides = useMemo(() => ['blue', 'red'].map((side) => ({ side, ...summary(valid.filter((m) => m.side === side)) })), [valid]);
  const weekdays = useMemo(() => {
    const days = WEEKDAYS.map((label) => statGroup({ label }));
    valid.forEach((m) => addStat(days[new Date(m.startedAt || m.endedAt).getDay()], m));
    return WEEK_ORDER.map((i) => days[i]);
  }, [valid]);
  const hours = useMemo(() => {
    const list = Array.from({ length: 24 }, (_, hour) => statGroup({ hour }));
    valid.forEach((m) => addStat(list[new Date(m.startedAt || m.endedAt).getHours()], m));
    return list;
  }, [valid]);
  const champions = useMemo(() => {
    const map = new Map();
    valid.forEach((m) => {
      if (!map.has(m.championKey)) map.set(m.championKey, statGroup({ key: m.championKey, name: m.championName }));
      addStat(map.get(m.championKey), m);
    });
    return [...map.values()].sort(byGames);
  }, [valid]);
  if (valid.length === 0) {
    return html`<p class="muted">Keine Matches in dieser Auswahl.</p>`;
  }
  const focus = champions.slice(0, FOCUS_TOP).reduce((sum, c) => sum + c.games, 0) / all.games;
  const now = new Date().getHours();
  const around = [-3, -2, -1, 0, 1, 2, 3].map((d) => hours[(now + d + 24) % 24]);
  const maxHour = Math.max(1, ...hours.map((h) => h.games));
  const maxWindow = Math.max(1, ...around.map((h) => h.games));
  const maxWeekday = Math.max(1, ...weekdays.map((d) => d.games));
  const sideGames = sides[0].games + sides[1].games;

  return html`<div class="summoner-section style-grid">
    <${Card} title="Spielstil" aside=${html`<span class="muted">${all.games} Spiele in der Auswahl</span>`} cls="style-wide">
      <div class="report-head six">
        <div class="kpi"><strong>${all.games}</strong><span>Spiele</span></div>
        <div class="kpi"><strong class=${wrClass(all.wins / all.games)}>${percent(all.wins / all.games)}</strong><span>${all.wins}S ${all.games - all.wins}N</span></div>
        <div class="kpi"><strong>${kdaRatio(all)}</strong><span>Ø KDA</span></div>
        <div class="kpi"><strong>${all.kpGames ? percent(all.kp / all.kpGames, 0) : '–'}</strong><span>Killbeteiligung</span></div>
        <div class="kpi"><strong>${hoursAndMinutes(all.seconds)}</strong><span>Gesamtspielzeit</span></div>
        <div class="kpi"><strong>${clock(all.seconds / all.games)}</strong><span>Ø Spiellänge</span></div>
      </div>
    <//>
    <${Card} title="Letzte 20 / Gesamtvergleich">
      <${DataGrid} head=${['', `Letzte ${Math.min(RECENT, valid.length)}`, 'Gesamt']} rows=${[
        ['Gewinnrate', html`<strong class=${wrClass(recent.wins / recent.games)}>${percent(recent.wins / recent.games)}</strong>`, html`<span class=${wrClass(all.wins / all.games)}>${percent(all.wins / all.games)}</span>`],
        ['Bilanz', `${recent.wins}S ${recent.games - recent.wins}N`, `${all.wins}S ${all.games - all.wins}N`],
        ['Ø KDA', kdaRatio(recent), kdaRatio(all)],
        ['Killbeteiligung', recent.kpGames ? percent(recent.kp / recent.kpGames, 0) : '–', all.kpGames ? percent(all.kp / all.kpGames, 0) : '–'],
        ['Ø Spiellänge', clock(recent.seconds / recent.games), clock(all.seconds / all.games)],
        ['Gesamtspielzeit', hoursAndMinutes(recent.seconds), hoursAndMinutes(all.seconds)],
      ]} />
    <//>
    <${Card} title="Leistungsmetriken" aside=${all.details < all.games ? html`<span class="muted">aus ${all.details} Spielen mit erweiterten Daten</span>` : null}>
      ${all.details === 0 ? html`<p class="muted">Erst nach einer neuen Abfrage verfügbar (alter Cache).</p>` : html`<${DataGrid} rows=${[
        ['Schaden pro Minute', number(perMinute(all, all.damage))],
        ['Schadensanteil', all.shareGames ? percent(all.share / all.shareGames) : '–'],
        ['Erlittener Schaden', number(perGame(all, all.taken))],
        ['Gold pro Minute', fixed(perMinute(all, all.gold))],
        ['CS pro Minute', fixed(all.cs / (all.seconds / 60))],
        ['Vision Score pro Minute', fixed(perMinute(all, all.vision), 2)],
        ['Control Wards', fixed(perGame(all, all.control))],
        ['Solo-Kills', fixed(perGame(all, all.solo))],
        ['Double / Triple / Quadra / Penta', all.multi.join(' / ')],
      ]} />`}
    <//>
    <${Card} title="Seiten">
      ${sideGames === 0 ? html`<p class="muted">Keine Matches mit Seitenangabe (alter Cache).</p>` : html`<div class="side-bars">
        ${sides.map((s) => html`<div class=${`side-bar ${s.side}`} key=${s.side}>
          <span>${s.side === 'blue' ? 'Blaue Seite' : 'Rote Seite'}</span>
          <div class="bar"><i style=${{ width: `${sideGames ? (s.games / sideGames) * 100 : 0}%` }}></i></div>
          <small>${s.games} Spiele (${percent(s.games / sideGames, 0)})</small>
          <strong class=${s.games ? wrClass(s.wins / s.games) : ''}>${s.games ? percent(s.wins / s.games) : '–'}</strong>
        </div>`)}
        ${sides[0].games && sides[1].games ? html`<small class="muted">Gewinnratenlücke: ${fixed(Math.abs(sides[0].wins / sides[0].games - sides[1].wins / sides[1].games) * 100)} Pp. zugunsten ${sides[0].wins / sides[0].games >= sides[1].wins / sides[1].games ? 'Blau' : 'Rot'}</small>` : null}
      </div>`}
      <div class="time-head pool-head"><strong>Champion-Pool</strong></div>
      <div class="report-head two">
        <div class="kpi" data-tip="Gespielte Champions im Verhältnis zu allen Champions"><strong>${champions.length} / ${totalChampions}</strong><span>Gespielte Champions</span></div>
        <div class="kpi" data-tip=${`Anteil der Spiele mit den ${FOCUS_TOP} meistgespielten Champions; höher = engerer Pool`}><strong>${percent(focus, 0)}</strong><span>Champion-Fokus</span></div>
      </div>
    <//>
    <${Card} title="Wochentag und Uhrzeit" cls="style-wide">
      <div class="time-block">
      <section class="time-main">
      <div class="time-head"><strong>Wochentag</strong><small class="muted">Balken = Spiele · Farbe = Winrate</small></div>
      <div class="col-chart tall">${weekdays.map((d) => html`<div class="col" key=${d.label}
        data-tip=${`${d.label} · ${d.games} Spiele · ${d.wins}S ${d.games - d.wins}N${d.games ? ` · ${percent(d.wins / d.games)} WR` : ''}`}>
        <div class="col-track"><i class=${d.games ? wrClass(d.wins / d.games) : ''} style=${{ height: `${(d.games / maxWeekday) * 100}%` }}></i></div>
        <small>${d.label.slice(0, 2)} · ${d.games}</small><small class=${d.games ? wrClass(d.wins / d.games) : 'muted'}>${d.games ? percent(d.wins / d.games, 0) : '–'}</small>
      </div>`)}</div>
      </section>
      <section class="time-side">
      <div class="time-head"><strong>Stündlich</strong><small class="muted">±3 Std. um ${now}:00 · Balken = Spiele</small></div>
      <div class="col-chart">${around.map((h) => html`<div class=${`col${h.hour === now ? ' now' : ''}`} key=${h.hour}
        data-tip=${`${h.hour}:00 · ${h.games} Spiele · ${h.wins}S ${h.games - h.wins}N${h.games ? ` · ${percent(h.wins / h.games)} WR` : ''}`}>
        <div class="col-track"><i style=${{ height: `${(h.games / maxWindow) * 100}%` }}></i></div>
        <small>${h.hour}:00</small><small class=${h.games ? wrClass(h.wins / h.games) : 'muted'}>${h.games ? percent(h.wins / h.games, 0) : '–'}</small>
      </div>`)}</div>
      <div class="heatmap" aria-label="Spiele je Stunde">${hours.map((h) => html`<i key=${h.hour}
        style=${{ opacity: 0.12 + (h.games / maxHour) * 0.88 }} data-tip=${`${h.hour}:00 · ${h.games} Spiele${h.games ? ` · ${percent(h.wins / h.games)} WR` : ''}`}></i>`)}</div>
      <div class="heatmap-legend"><small class="muted">0:00</small><small class="muted">Farbintensität = Spiele</small><small class="muted">23:00</small></div>
      </section>
      </div>
    <//>
  </div>`;
}

// ------------------------------------------------------------------ Meisterschaft

const MASTERY_SORTS = [
  { value: 'points', label: 'Punkte' },
  { value: 'level', label: 'Stufe' },
  { value: 'recent', label: 'Zuletzt gespielt' },
  { value: 'name', label: 'Name' },
];

function lastPlayed(timestamp) {
  if (!timestamp) return '–';
  return new Date(timestamp).toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' });
}

export function MasterySection({ mastery, data }) {
  const advanced = useAdvancedTips();
  const [sort, setSort] = useState('points');
  const [filter, setFilter] = useState('');
  const [shown, setShown] = useState(TOP_MASTERY);
  const name = (m) => data.championByKey.get(m.championKey)?.name || `Champion ${m.championKey}`;
  const rows = useMemo(() => {
    const list = mastery.filter((m) => !filter || name(m).toLowerCase().includes(filter.toLowerCase()));
    const compare = {
      points: (a, b) => b.points - a.points,
      level: (a, b) => b.level - a.level || b.points - a.points,
      recent: (a, b) => b.lastPlayed - a.lastPlayed,
      name: (a, b) => name(a).localeCompare(name(b), 'de'),
    }[sort];
    return [...list].sort(compare);
  }, [mastery, sort, filter, data]);
  const totalPoints = mastery.reduce((sum, m) => sum + m.points, 0);
  const totalLevel = mastery.reduce((sum, m) => sum + m.level, 0);
  const tenPlus = mastery.filter((m) => m.level >= 10).length;
  return html`<div class="summoner-section">
    <div class="report-head">
      <div class="kpi"><strong>${mastery.length}</strong><span>Champions mit Meisterschaft</span></div>
      <div class="kpi"><strong>${number(totalLevel)}</strong><span>Gesamte Meisterschaftsstufe</span></div>
      <div class="kpi"><strong>${number(totalPoints)}</strong><span>Gesamtpunkte</span></div>
      <div class="kpi"><strong>${tenPlus}</strong><span>Champions ab Stufe 10</span></div>
    </div>
    <${Card} title="Mastery Points" aside=${html`<div class="mastery-tools">
      <input type="search" class="mini-search" placeholder="Champion suchen" value=${filter} onInput=${(e) => setFilter(e.currentTarget.value)} />
      <${Select} label="Sortierung" value=${sort} onChange=${setSort} options=${MASTERY_SORTS} />
    </div>`}>
      ${rows.length === 0 ? html`<p class="muted">Keine Meisterschaften.</p>` : html`<div class="mastery-grid">${(filter ? rows : rows.slice(0, shown)).map((m) => {
        const champion = data.championByKey.get(m.championKey);
        const progress = m.untilNext > 0 ? m.sinceLevel / (m.sinceLevel + m.untilNext) : 1;
        return html`<div class="mastery-card" key=${m.championKey}>
          <button type="button" class="icon-link" onClick=${() => champion && go('champions', champion.id)}>
            <${Img} src=${champion?.icon} size=${56} cls="round" tip=${advanced ? name(m) : null} />
          </button>
          <span class=${`mastery-level${m.level >= 10 ? ' high' : ''}`}>${m.level}</span>
          <strong class="mastery-name">${name(m)}</strong>
          <span class="mastery-points">${number(m.points)} Pkt.</span>
          <div class="progress small" data-tip=${m.untilNext > 0 ? `${number(m.sinceLevel)} von ${number(m.sinceLevel + m.untilNext)} Punkten bis Stufe ${m.level + 1}` : `${number(-m.untilNext)} Punkte über der nächsten Stufe`}>
            <span style=${{ width: `${Math.min(100, progress * 100)}%` }}></span></div>
          <small class="muted" data-tip=${advanced ? 'Noten der laufenden Saison-Meilensteine' : null}>${m.grades.length ? m.grades.join(' ') : '–'}${m.seasonMilestone ? ` · Meilenstein ${m.seasonMilestone}` : ''}</small>
          <small class="muted">Zuletzt ${lastPlayed(m.lastPlayed)}</small>
        </div>`;
      })}</div>`}
      ${!filter && rows.length > TOP_MASTERY ? html`<${MoreToggle} open=${shown >= rows.length} total=${rows.length} top=${TOP_MASTERY} onToggle=${() => setShown(shown >= rows.length ? TOP_MASTERY : rows.length)} />` : null}
    <//>
  </div>`;
}
