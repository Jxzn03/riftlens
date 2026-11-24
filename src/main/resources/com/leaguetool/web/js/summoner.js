// Ansicht "Summoner": Abfrage eines Spielers über die Riot-API mit Rängen, Champions, Spielmodi und Match-Verlauf.
import { html, useEffect, useMemo, useState } from '/vendor/htm-preact.js';
import { store, useStore, useViewState, api, go, percent } from './core.js';
import { Icon, Img, Button, Select, Segmented, Card, Spinner, Empty, Tag, SortGrid, ItemIcons, SpellIcons, MoreToggle, StepToggle } from './ui.js';
import { ChampionsSection, StyleSection, MasterySection } from './summoner-stats.js';
import { LiveSection } from './summoner-live.js';

const TOP_N = 5; // Standardansicht Mitspieler: nur die ersten 5 Einträge
const TOP_CHAMPS = 5; // Standardansicht Champions
const TOP_MASTERY = 7; // Mastery Points: Top 5, dann 20 weitere, dann alle
const MASTERY_STEP = 20;
/**
 * Match-Liste schrittweise: Jede Zeile hat rund zehn Bilder, 1000 Matches auf einmal kosteten eine halbe Sekunde beim
 * Anzeigen und bei jedem Moduswechsel. Statistik, Champions und Mitspieler zählen weiter alle Matches.
 */
const MATCH_PAGE = 50;
const DEFAULTS = { query: '', platform: '', count: '50', result: null, error: null, loading: false, mode: [], last: '', section: 'overview' };
/** Auswertungsfenster: nur die letzten N gewerteten Spiele; angeboten werden nur Stufen unter der verfügbaren Anzahl. */
const LAST_STEPS = [10, 20, 30, 50, 100, 200, 300, 500];
const SECTIONS = [
  { value: 'overview', label: 'Übersicht' },
  { value: 'champions', label: 'Champions' },
  { value: 'style', label: 'Spielstil' },
  { value: 'mastery', label: 'Mastery Points' },
  { value: 'live', label: 'Live-Spiel' },
];
const COUNTS = [20, 50, 100, 200, 300, 500, 1000].map((n) => ({ value: String(n), label: `${n} Matches` }));
// Virtueller Modus-Filter: alle Spiele mit direktem Lane-Gegner (Summoner's Rift, feste Positionen).
const RIFT = '__rift';
const MAX_RECENT = 8;
// Wiederherstellen des zuletzt gesuchten Summoners nur einmal je App-Start.
let restoreTried = false;
// Dev-Key: 100 Anfragen in 2 Minuten, also gut 1,2 s je Match, sobald das Kontingent aufgebraucht ist.
const SECONDS_PER_MATCH = 1.2;
const SLOW_COUNT = 200;
const HUGE_COUNT = 1000;

// Rang-Karten: erst Solo/Duo, dann Flex.
const queueOrder = (queue) => (queue === 'Solo/Duo' ? 0 : queue === 'Flex' ? 1 : 2);
const wrClass = (value) => (value >= 0.52 ? 'good' : value < 0.48 ? 'bad' : '');
const number = (value) => value.toLocaleString('de-DE');
const kda = (kills, deaths, assists) => (deaths === 0 ? 'Perfekt' : ((kills + assists) / deaths).toFixed(2));
// Sortwerte: „Perfekt“ (keine Tode) zählt als höchste KDA; Champion-Namen wie angezeigt.
const kdaValue = (g) => (g.deaths === 0 ? Infinity : (g.kills + g.assists) / g.deaths);
const champName = (data, key, name) => data.championByKey.get(key)?.name || name || `Champion ${key}`;

function duration(seconds) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

function ago(timestamp) {
  const minutes = Math.max(0, Math.round((Date.now() - timestamp) / 60000));
  if (minutes < 60) {
    return `vor ${minutes} Min.`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `vor ${hours} Std.`;
  }
  const days = Math.round(hours / 24);
  return days === 1 ? 'vor 1 Tag' : `vor ${days} Tagen`;
}

function emptyGroup(extra) {
  return { ...extra, games: 0, wins: 0, kills: 0, deaths: 0, assists: 0, cs: 0, seconds: 0 };
}

function add(group, match) {
  group.games += 1;
  group.wins += match.win ? 1 : 0;
  group.kills += match.kills;
  group.deaths += match.deaths;
  group.assists += match.assists;
  group.cs += match.cs;
  group.seconds += match.durationSec;
}

/** Summen über die Matches; Remakes zählen nicht mit. */
function aggregate(matches) {
  const total = emptyGroup({});
  const champions = new Map();
  const roles = new Map();
  matches.filter((m) => !m.remake).forEach((m) => {
    add(total, m);
    if (!champions.has(m.championKey)) {
      champions.set(m.championKey, emptyGroup({ key: m.championKey, name: m.championName }));
    }
    add(champions.get(m.championKey), m);
    if (m.position) {
      if (!roles.has(m.position)) {
        roles.set(m.position, emptyGroup({ position: m.position }));
      }
      add(roles.get(m.position), m);
    }
  });
  const byGames = (a, b) => b.games - a.games || b.wins / b.games - a.wins / a.games;
  return { total, champions: [...champions.values()].sort(byGames), roles: [...roles.values()].sort(byGames) };
}

function modesOf(matches) {
  const modes = new Map();
  matches.filter((m) => !m.remake).forEach((m) => {
    if (!modes.has(m.mode)) {
      modes.set(m.mode, emptyGroup({ mode: m.mode, positioned: 0 }));
    }
    add(modes.get(m.mode), m);
    modes.get(m.mode).positioned += m.position ? 1 : 0;
  });
  // rift: Modus mit festen Positionen (Summoner's Rift), sonst Arena, ARAM, URF u. a.
  return [...modes.values()].map((g) => ({ ...g, rift: g.positioned * 2 > g.games })).sort((a, b) => b.games - a.games);
}

/**
 * Häufige Mitspieler in der aktuellen Auswahl (ohne Remakes): Winrate zusammen und die eigene Winrate in den
 * übrigen Spielen zum Vergleich.
 */
function matesOf(mates, matches, minGames) {
  const valid = matches.filter((m) => !m.remake);
  const rows = mates.map((mate) => ({ ...mate, games: 0, wins: 0 }));
  let wins = 0;
  valid.forEach((m) => {
    wins += m.win ? 1 : 0;
    (m.mates || []).forEach((index) => {
      rows[index].games += 1;
      rows[index].wins += m.win ? 1 : 0;
    });
  });
  return rows.filter((r) => r.games >= minGames)
    .map((r) => ({ ...r, otherGames: valid.length - r.games, otherWins: wins - r.wins }))
    .sort((a, b) => b.games - a.games || b.wins / b.games - a.wins / a.games);
}

/**
 * Die neuesten Matches bis einschließlich des n-ten gewerteten Spiels (Remakes zählen nicht mit, bleiben im Zeitraum
 * aber in der Liste). Ohne n alle Matches.
 */
function lastGames(matches, n) {
  if (!n) {
    return matches;
  }
  const newest = [...matches].sort((a, b) => (b.startedAt || 0) - (a.startedAt || 0));
  const result = [];
  let counted = 0;
  for (const m of newest) {
    if (counted >= n) {
      break;
    }
    result.push(m);
    counted += m.remake ? 0 : 1;
  }
  return result;
}

/** Auswahl für das Auswertungsfenster, passend zur Zahl der gewerteten Spiele in der aktuellen Auswahl. */
function lastOptions(available) {
  return [{ value: '', label: `Alle ${available} Spiele` },
    ...LAST_STEPS.filter((n) => n < available).map((n) => ({ value: String(n), label: `Letzte ${n} Spiele` }))];
}

/** Ist das Ergebnis eines der gespeicherten eigenen Konten? */
function isOwnResult(result, accounts) {
  const id = result ? `${result.gameName}#${result.tagLine}`.toLowerCase() : '';
  return Boolean(result && accounts.some((a) => a.platform === result.platform && a.riotId.toLowerCase() === id));
}

/**
 * Der gerade angezeigte Summoner für das Live-Spiel; bei einem eigenen Konto mit dessen Nummer (spart den
 * account-v1-Aufruf).
 */
function currentSummoner(result, accounts) {
  // Die Übersicht aller Konten hat kein einzelnes Konto für das Live-Spiel.
  if (!result || result.combined) {
    return null;
  }
  const riotId = `${result.gameName}#${result.tagLine}`;
  const account = accounts.findIndex((a) => sameAccount(a, { riotId, platform: result.platform }));
  return { riotId, platform: result.platform, account };
}

const sameAccount = (a, b) => a.platform === b.platform && a.riotId.toLowerCase() === b.riotId.toLowerCase();

function minutes(seconds) {
  return Math.max(1, Math.ceil(seconds / 60));
}

export function SummonerView() {
  const data = useStore((s) => s.data);
  const dialog = useStore((s) => s.dialog);
  const recent = useStore((s) => s.summonerRecent);
  const [view, setView] = useViewState('summoner', DEFAULTS);
  const [settings, setSettings] = useState(null);

  // Bei jedem Schließen der Optionen neu lesen: Key oder Region können sich geändert haben.
  useEffect(() => {
    if (!dialog) {
      api.get('/api/settings').then(setSettings).catch(() => setSettings(null));
    }
  }, [dialog]);

  // Eigene Konten (nur lokal gespeichert, z. B. Haupt- und Zweitkonto): Schnellzugriff, Live-Spiel, Bilanz im Draft.
  const [accounts, setAccounts] = useState([]);
  useEffect(() => {
    api.get('/api/summoner/own').then((r) => setAccounts(r.accounts || [])).catch(() => setAccounts([]));
  }, []);
  const saveOwn = async (riotId, region, remove = false) => {
    try {
      const r = await api.post('/api/summoner/own', { riotId, platform: region, remove });
      setAccounts(r.accounts || []);
    } catch (error) {
      setView({ error: error.message });
    }
  };

  const platform = view.platform || (settings ? settings.platform : '');
  const lastSearch = useStore((s) => s.summonerLast);
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips)); // Hinweise nur bei „Show Advanced Tooltips“

  // Zuletzt gesuchten Summoner mit Matchanzahl und Auswertungsfenster nach einem Neustart einmal wieder abfragen
  // (Riot-API; bereits geladene Matches kommen aus dem Cache).
  useEffect(() => {
    // lastSearch kommt aus ui-state.json, das beim Start nachgeladen werden kann.
    if (restoreTried || !settings || view.result || view.loading || !lastSearch) {
      return;
    }
    restoreTried = true;
    if (lastSearch.combined) {
      // Alle eigenen Konten: Matches kommen aus dem Speicher, das geht auch ohne Key.
      setView({ last: lastSearch.last || '' });
      searchCombined();
    } else if (settings.riotReady) {
      setView({ count: String(lastSearch.count), last: lastSearch.last || '', platform: lastSearch.platform });
      search(lastSearch.riotId, lastSearch.platform, String(lastSearch.count));
    }
  }, [settings, lastSearch]);
  const section = view.section || 'overview';
  const search = async (riotId, region, count = view.count) => {
    const query = riotId.trim();
    if (!query || view.loading) {
      return;
    }
    setView({ query, platform: region, loading: true, error: null, mode: [], section: view.section === 'live' ? 'overview' : view.section || 'overview' });
    store.set({ summonerProgress: null });
    try {
      const params = new URLSearchParams({ riotId: query, platform: region, count, laTier: store.get().dataSource.tier });
      const result = await api.get(`/api/summoner?${params}`);
      const name = `${result.gameName}#${result.tagLine}`;
      setView({ result, query: name, loading: false });
      const others = store.get().summonerRecent.filter((r) => r.riotId !== name || r.platform !== region);
      store.set({ summonerRecent: [{ riotId: name, platform: region }, ...others].slice(0, MAX_RECENT) });
      store.set({ summonerLast: { riotId: name, platform: region, count, last: store.get().views.summoner?.last || '' } });
    } catch (error) {
      setView({ error: error.message, loading: false });
    }
  };

  // Alle eigenen Konten zusammen: Matches und Mitspieler aus dem Speicher, Ränge und Meisterschaft per Riot-API
  // (falls eingerichtet). Neue Spiele kommen erst hinzu, wenn ein Konto einzeln abgefragt wird.
  const searchCombined = async () => {
    if (view.loading) {
      return;
    }
    setView({ loading: true, error: null, mode: [], section: view.section === 'live' ? 'overview' : view.section || 'overview' });
    store.set({ summonerProgress: null });
    try {
      const params = new URLSearchParams({ laTier: store.get().dataSource.tier });
      const result = await api.get(`/api/summoner/combined?${params}`);
      setView({ result, loading: false });
      store.set({ summonerLast: { combined: true, last: store.get().views.summoner?.last || '' } });
    } catch (error) {
      setView({ error: error.message, loading: false });
    }
  };

  return html`<div class="page">
    <section class="card summoner-search">
      <div class="summoner-form">
        <label class="search">
          <${Icon} name="search" size=${16} />
          <input type="search" value=${view.query} placeholder="Riot-ID, z. B. Name#EUW" spellcheck="false" autofocus
            onInput=${(e) => setView({ query: e.currentTarget.value })}
            onKeyDown=${(e) => e.key === 'Enter' && search(view.query, platform)} />
        </label>
        ${settings ? html`<${Select} label="Region" value=${platform} onChange=${(value) => setView({ platform: value })}
          options=${settings.platforms.map((p) => ({ value: p, label: p.toUpperCase() }))} />` : null}
        <${Select} label="Anzahl Matches" value=${view.count} onChange=${(count) => setView({ count })} options=${COUNTS} />
        <${Button} kind="primary" icon="search" disabled=${view.loading || !view.query.trim() || !platform} onClick=${() => search(view.query, platform)}>Abfragen<//>
      </div>
      ${Number(view.count) >= HUGE_COUNT ? html`<div class="huge-hint">
        <strong><${Icon} name="alert" size=${15} /> 1000 Matches: sehr langer erster Abruf</strong>
        <ul>
          <li>Rund ${minutes(HUGE_COUNT * SECONDS_PER_MATCH)} Minuten mit einem Dev- oder Personal-Key (100 Anfragen in 2 Minuten). Die App muss so lange offen bleiben.</li>
          <li>Ein Abbruch verliert nichts: alle 50 Matches wird gespeichert, ein neuer Versuch lädt nur den Rest.</li>
          <li>Riot hält Matches nur etwa zwei Jahre vor. Bei wenig gespielten Konten kommen weniger als 1000 zurück.</li>
          <li>Speicher: etwa 200–300 KB für diesen Spieler.</li>
        </ul>
      </div>` : Number(view.count) >= SLOW_COUNT ? html`<p class="muted small slow-hint"><${Icon} name="alert" size=${14} />
        Der erste Abruf von ${view.count} Matches dauert wegen Riots Rate-Limit (100 Anfragen in 2 Minuten) bis zu
        ca. ${minutes(Number(view.count) * SECONDS_PER_MATCH)} Min. Geladene Matches werden gespeichert, danach kommen nur neue Spiele hinzu.</p>` : null}
      ${recent.length || accounts.length ? html`<div class="recent-chips">
        ${accounts.length >= 2 ? html`<button type="button" class=${`chip-link recent own all${view.result?.combined ? ' active' : ''}`}
          disabled=${view.loading} onClick=${searchCombined}><${Icon} name="user" size=${13} /> Alle meine Konten<small>${accounts.length}</small></button>` : null}
        ${accounts.map((a) => html`<button type="button" key=${`own-${a.riotId}-${a.platform}`} class="chip-link recent own" disabled=${view.loading}
          data-tip=${advancedTips ? 'Eigenes Konto abfragen' : null} onClick=${() => search(a.riotId, a.platform)}><${Icon} name="user" size=${13} /> ${a.riotId}<small>${a.platform.toUpperCase()}</small></button>`)}
        ${recent.filter((r) => !accounts.some((a) => sameAccount(a, r))).map((r) => html`<button type="button" key=${`${r.riotId}-${r.platform}`} class="chip-link recent"
          disabled=${view.loading} onClick=${() => search(r.riotId, r.platform)}>${r.riotId}<small>${r.platform.toUpperCase()}</small></button>`)}
      </div>` : null}
    </section>

    ${settings && !settings.riotReady ? html`<section class="card notice">
      <p>Für die Abfrage in den Optionen die Riot-API aktivieren und einen API-Key eintragen. Kostenlose Dev-Keys gibt es unter developer.riotgames.com.</p>
      <${Button} icon="settings" onClick=${() => store.set({ dialog: 'settings' })}>Optionen öffnen<//>
    </section>` : null}
    ${view.loading ? html`<${Progress} />` : null}
    ${view.error ? html`<section class="card notice bad"><${Icon} name="alert" size=${16} /><p>${view.error}</p></section>
    <section class="card key-help">
      <strong>Neuen API-Key erstellen</strong>
      <ol>
        <li>Auf <strong><a href="https://developer.riotgames.com/" target="_blank" rel="noopener noreferrer">developer.riotgames.com</a></strong> mit dem Riot-Konto anmelden.</li>
        <li>Auf <strong>Regenerate API Key</strong> klicken.</li>
        <li><strong>Show</strong> -> <strong>Copy API Key</strong> kopieren und in den Optionen einfügen.</li>
      </ol>
      <small>Dev-Keys laufen nach 24 Stunden ab.</small>
    </section>` : null}
    ${view.result || accounts.length ? html`<nav class="summoner-tabs"><${Segmented} label="Bereich" value=${section} options=${SECTIONS}
      onChange=${(value) => setView({ section: value })} /></nav>` : null}
    ${section === 'live' ? html`<${LiveSection} accounts=${accounts} platform=${platform} data=${data} current=${currentSummoner(view.result, accounts)} />`
      : view.result ? html`<${Result} key=${`${view.result.gameName}${view.result.tagLine}`} result=${view.result} data=${data} mode=${view.mode}
        last=${view.last || ''} section=${section} accounts=${accounts} onOwn=${saveOwn}
        onMode=${(mode) => setView({ mode })} onLast=${(last) => { setView({ last }); const s = store.get().summonerLast; if (s) store.set({ summonerLast: { ...s, last } }); }} onSearch=${(riotId, region) => search(riotId, region || view.result.platform || platform)} />`
      : !view.loading && !view.error ? html`<${Empty} title="Summoner abfragen">Gib eine Riot-ID ein, um Ränge, Champions mit Matchups, Spielstil, Meisterschaften und Spielmodi zu sehen. Ein abgefragtes Konto lässt sich als eigenes festlegen; dann zeigt der Draft deine Matchup-Bilanz.<//>` : null}
  </div>`;
}

function Progress() {
  const progress = useStore((s) => s.summonerProgress);
  const fraction = progress && progress.total ? (progress.done / progress.total) * 100 : 0;
  const remaining = progress ? progress.total - progress.done : 0;
  let text = 'Suche Summoner …';
  if (progress && progress.total === 0) {
    text = `${progress.cached} Matches aus dem Cache, werte aus …`;
  } else if (progress) {
    text = `Lade neue Matches ${progress.done} von ${progress.total}${progress.cached ? ` · ${progress.cached} aus dem Cache` : ''} …`;
  }
  return html`<section class="card progress-block">
    <div class="loading"><${Spinner} small /> <span>${text}</span></div>
    <div class="progress"><span style=${{ width: `${fraction}%` }}></span></div>
    ${remaining > 50 ? html`<small class="muted">Noch bis zu ca. ${minutes(remaining * SECONDS_PER_MATCH)} Min.: Riot erlaubt mit einem Dev-Key nur 100 Anfragen in 2 Minuten. Bereits geladene Matches bleiben gespeichert.</small>` : null}
  </section>`;
}

function Result({ result, data, mode, last, section, accounts, onOwn, onMode, onLast, onSearch }) {
  const isOwn = isOwnResult(result, accounts);
  const advanced = useStore((s) => Boolean(s.prefs.advancedTips)); // Hinweise nur bei „Show Advanced Tooltips“
  const [matesOpen, setMatesOpen] = useState(false);
  const [champsOpen, setChampsOpen] = useState(false);
  const [masteryShown, setMasteryShown] = useState(TOP_MASTERY);
  const modes = useMemo(() => modesOf(result.matches), [result]);
  // Mehrere Modi zusammen (Vereinigung); „Summoner's Rift“ = alle Spiele mit direktem Lane-Gegner. Alt: ein Modus als Text.
  const selected = useMemo(() => (Array.isArray(mode) ? mode : mode ? [mode] : []), [mode]);
  const inMode = useMemo(() => (selected.length === 0 ? result.matches
    : result.matches.filter((m) => selected.includes(m.mode) || (selected.includes(RIFT) && m.opponent))), [result, selected]);
  const riftGames = useMemo(() => result.matches.filter((m) => !m.remake && m.opponent).length, [result]);
  // „Summoner's Rift“ schaltet alle Rift-Modi mit an bzw. aus und behält dabei die eigene Markierung. Wird danach ein
  // einzelner Rift-Modus abgewählt, gilt nicht mehr „ganz Summoner's Rift“: dann fällt auch die Markierung weg.
  const riftModes = modes.filter((m) => m.rift).map((m) => m.mode);
  const toggleMode = (value) => {
    const on = !selected.includes(value);
    if (value === RIFT) {
      onMode(on ? [...new Set([...selected, RIFT, ...riftModes])] : selected.filter((v) => v !== RIFT && !riftModes.includes(v)));
    } else if (on) {
      onMode([...selected, value]);
    } else {
      onMode(selected.filter((v) => v !== value && !(riftModes.includes(value) && v === RIFT)));
    }
  };
  // Gewertete Spiele in der Modus-Auswahl; ein gemerktes Fenster ab dieser Größe wirkt wie „Alle“.
  const available = useMemo(() => inMode.filter((m) => !m.remake).length, [inMode]);
  const lastN = Number(last) > 0 && Number(last) < available ? Number(last) : 0;
  const matches = useMemo(() => lastGames(inMode, lastN), [inMode, lastN]);
  const stats = useMemo(() => aggregate(matches), [matches]);
  const minShared = result.minSharedGames || 3;
  const mates = useMemo(() => matesOf(result.mates || [], matches, minShared), [result, matches]);
  // Angezeigte Matches je Auswahl; ein Wechsel von Modus oder Fenster beginnt wieder mit der ersten Seite.
  const pageKey = `${selected.join(",")}|${lastN}`;
  const [paging, setPaging] = useState({ key: pageKey, shown: MATCH_PAGE });
  const shown = paging.key === pageKey ? paging.shown : MATCH_PAGE;
  // Auch zurück zur vorigen Auswahl nur die erste Seite, sonst stünden nach „Alle“ wieder alle Zeilen da.
  useEffect(() => {
    if (paging.key !== pageKey) {
      setPaging({ key: pageKey, shown: MATCH_PAGE });
    }
  }, [pageKey]);
  // Gemerkt: Tippen im Suchfeld rendert die Seite neu, die Zeilen bleiben dabei unberührt.
  // In der Übersicht aller Konten steht an jedem Match, zu welchem Konto es gehört.
  const matchRows = useMemo(() => matches.slice(0, shown).map((m) => html`<${MatchRow} key=${m.id} match=${m} data=${data}
    account=${result.combined ? result.accounts[m.account]?.riotId : null} />`), [matches, shown, data, result]);
  const hiddenMatches = matches.length - matchRows.length;
  const allGames = modes.reduce((sum, m) => sum + m.games, 0);
  const allWins = modes.reduce((sum, m) => sum + m.wins, 0);
  const { total } = stats;
  const remakes = matches.length - total.games;

  return html`<div class="summoner-result">
    ${result.combined ? html`<header class="plain-hero">
      <div class="hero-icon combined-icon"><${Icon} name="user" size=${34} /></div>
      <div class="hero-text">
        <h1>${result.gameName}</h1>
        <p class="hero-sub">${result.accounts.length} Konten · ${number(result.matches.length)} Matches aus dem Speicher</p>
        <div class="tags">${result.accounts.map((a) => html`<button type="button" key=${`${a.riotId}-${a.platform}`} class="chip-link recent own"
          onClick=${() => onSearch(a.riotId, a.platform)}>${a.riotId}<small>${a.platform.toUpperCase()} · ${a.matches}</small></button>`)}</div>
        <small class="muted">Neue Spiele kommen hinzu, wenn du ein Konto einzeln abfragst.${result.riotNote ? ` ${result.riotNote}` : ''}</small>
      </div>
    </header>` : html`<header class="plain-hero">
      <${Img} src=${result.icon} size=${72} cls="hero-icon" />
      <div class="hero-text">
        <h1>${result.gameName}<span class="muted"> #${result.tagLine}</span></h1>
        <p class="hero-sub">Level ${number(result.level)}</p>
        <div class="tags"><${Tag} kind="soft">${result.platform.toUpperCase()}<//>${isOwn ? html`<${Tag}>Mein Konto<//>` : null}</div>
      </div>
      <div class="hero-actions">
        ${isOwn ? html`<${Button} icon="x" tip="Dieses Konto aus deinen Konten entfernen; seine Spiele zählen dann nicht mehr zur Bilanz im Draft"
            onClick=${() => onOwn(`${result.gameName}#${result.tagLine}`, result.platform, true)}>Nicht mehr mein Konto<//>`
          : html`<${Button} icon="user" tip=${`Zu deinen Konten hinzufügen (nur lokal, z. B. Zweitkonto${accounts.length ? `; bisher ${accounts.length}` : ''}): Schnellzugriff, Live-Spiel und gemeinsame Matchup-Bilanz im Draft`}
            onClick=${() => onOwn(`${result.gameName}#${result.tagLine}`, result.platform)}>Als mein Konto festlegen<//>`}
      </div>
    </header>`}

    ${section === 'overview' ? html`<div class="rank-cards">
      ${result.ranked.length === 0 ? html`<div class="rank-card"><span class="muted">Keine Ranked-Platzierung</span></div>` : null}
      ${[...result.ranked].sort((a, b) => queueOrder(a.queue) - queueOrder(b.queue) || (a.account || '').localeCompare(b.account || ''))
        .map((r) => html`<div class=${`rank-card${r.tier ? ` tinted rank-${r.tier.toLowerCase()}` : ""}`} key=${`${r.queue}-${r.account || ''}`}>
        <small class="muted">Ranked ${r.queue}${r.account ? ` · ${r.account}` : ''}</small>
        <strong>${r.tier} ${r.division}</strong>
        <span>${r.lp} LP</span>
        <span class=${wrClass(r.winRate)}>${percent(r.winRate)} WR</span>
        <small class="muted">${number(r.wins)} S / ${number(r.losses)} N</small>
      </div>`)}
    </div>` : null}

    ${section === 'mastery' ? html`<${MasterySection} mastery=${result.mastery} data=${data} />` : null}
    ${modes.length && section !== 'mastery' ? html`<div class="summoner-filter">
      <div class="mode-chips" role="group" aria-label="Spielmodus (mehrere wählbar)">
        <button type="button" class=${selected.length === 0 ? 'active' : ''} aria-pressed=${selected.length === 0} onClick=${() => onMode([])}>Alle <small>${allGames}</small></button>
        ${riftGames ? html`<button type="button" class=${`rift${selected.includes(RIFT) ? ' active' : ''}`} aria-pressed=${selected.includes(RIFT)}
          data-tip="Alle Spiele auf Summoner's Rift mit direktem Lane-Gegner, egal in welcher Queue" onClick=${() => toggleMode(RIFT)}>Summoner's Rift <small>${riftGames}</small></button>` : null}
        ${[...modes].sort((a, b) => Number(a.rift) - Number(b.rift) || b.games - a.games).map((m) => html`<button type="button" key=${m.mode} class=${selected.includes(m.mode) ? 'active' : ''} aria-pressed=${selected.includes(m.mode)} onClick=${() => toggleMode(m.mode)}>${m.mode} <small>${m.games}</small></button>`)}
      </div>
      ${available > LAST_STEPS[0] ? html`<${Select} label="Auswertung" value=${lastN ? String(lastN) : ''} onChange=${onLast} options=${lastOptions(available)}
        tip="Übersicht, Champions und Spielstil nur aus den neuesten Spielen der Auswahl rechnen; abgerufen bleiben alle Matches" />` : null}
    </div>` : null}

    ${section === 'champions' ? html`<${ChampionsSection} matches=${matches} data=${data} recommendations=${result.recommendations} />` : null}
    ${section === 'style' ? html`<${StyleSection} matches=${matches} data=${data} totalChampions=${data.champions.length} />` : null}
    ${section === 'overview' ? html`<div class="report-head">
      <div class="kpi"><strong>${total.games}</strong><span>${lastN ? `der letzten ${lastN} von ${available} Spielen ausgewertet` : 'Matches ausgewertet'}${remakes > 0 ? ` (${remakes} Remakes ohne Wertung)` : ''}</span></div>
      <div class="kpi"><strong class=${total.games ? wrClass(total.wins / total.games) : ''}>${total.games ? percent(total.wins / total.games) : '–'}</strong><span>${total.wins} S / ${total.games - total.wins} N</span></div>
      <div class="kpi"><strong>${total.games ? kda(total.kills, total.deaths, total.assists) : '–'}</strong><span>Ø KDA</span></div>
      <div class="kpi"><strong>${total.games ? duration(Math.round(total.seconds / total.games)) : '–'}</strong><span>Ø Spieldauer</span></div>
    </div>` : null}

    ${section === 'overview' ? html`<div class="summoner-grid">
      <div class="summoner-main">
      <${Card} title="Meistgespielte Champions">
        ${stats.champions.length === 0 ? html`<p class="muted">Keine Matches in dieser Auswahl.</p>` : html`<${SortGrid} rank
          head=${['Champion', 'Spiele', 'Anteil', 'WR', 'S / N', 'KDA', 'Ø K / D / A', 'CS/Min']}
          items=${stats.champions} limit=${champsOpen ? 0 : TOP_CHAMPS}
          values=${[(c) => champName(data, c.key, c.name), (c) => c.games, (c) => c.games, (c) => c.wins / c.games, (c) => c.wins, (c) => kdaValue(c),
            (c) => c.kills / c.games, (c) => (c.seconds ? c.cs / (c.seconds / 60) : null)]}
          cells=${(c) => [
            html`<${ChampionCell} data=${data} championKey=${c.key} name=${c.name} />`,
            c.games,
            html`<span class="muted">${percent(c.games / total.games, 0)}</span>`,
            html`<strong class=${wrClass(c.wins / c.games)}>${percent(c.wins / c.games)}</strong>`,
            `${c.wins} / ${c.games - c.wins}`,
            kda(c.kills, c.deaths, c.assists),
            `${(c.kills / c.games).toFixed(1)} / ${(c.deaths / c.games).toFixed(1)} / ${(c.assists / c.games).toFixed(1)}`,
            c.seconds ? (c.cs / (c.seconds / 60)).toFixed(1) : '–',
          ]} />`}
        ${stats.champions.length > TOP_CHAMPS ? html`<${MoreToggle} open=${champsOpen} total=${stats.champions.length} top=${TOP_CHAMPS} onToggle=${() => setChampsOpen(!champsOpen)} />` : null}
      <//>
      <${Card} title="Letzte Matches" aside=${html`<span class="muted">${hiddenMatches > 0 ? `${matchRows.length} von ${matches.length}` : matches.length} angezeigt</span>`}>
        <div class="match-list">${matchRows}</div>
        ${hiddenMatches > 0 ? html`<div class="more-row">
          <button type="button" class="more-toggle" onClick=${() => setPaging({ key: pageKey, shown: shown + MATCH_PAGE })}>
            ${Math.min(MATCH_PAGE, hiddenMatches)} weitere anzeigen<span class="more-arrow" aria-hidden="true">▼</span>
          </button>
          ${hiddenMatches > MATCH_PAGE ? html`<button type="button" class="more-toggle" onClick=${() => setPaging({ key: pageKey, shown: matches.length })}>
            Alle ${matches.length} anzeigen</button>` : null}
        </div>` : null}
        ${matches.length === 0 ? html`<p class="muted">Keine Matches in dieser Auswahl.</p>` : null}
      <//>
      </div>
      <div class="summoner-side">
        <${Card} title="Spielmodi" aside=${lastN ? html`<span class="muted">alle abgerufenen</span>` : null}>
          ${modes.length === 0 ? html`<p class="muted">Keine Matches gefunden.</p>` : html`<${SortGrid} head=${['Modus', 'Spiele', 'WR']} items=${modes}
            values=${[(m) => m.mode, (m) => m.games, (m) => m.wins / m.games]}
            cells=${(m) => [m.mode, m.games, html`<strong class=${wrClass(m.wins / m.games)}>${percent(m.wins / m.games)}</strong>`]} />`}
        <//>
        ${stats.roles.length ? html`<${Card} title="Rollen"><${SortGrid} head=${['Rolle', 'Spiele', 'WR']} items=${stats.roles}
          values=${[(r) => r.position, (r) => r.games, (r) => r.wins / r.games]}
          cells=${(r) => [r.position, r.games, html`<strong class=${wrClass(r.wins / r.games)}>${percent(r.wins / r.games)}</strong>`]} /><//>` : null}
        <${Card} title="Mastery Points" aside=${html`<span class="muted">gesamtes Konto</span>`}>
          ${result.mastery.length === 0 ? html`<p class="muted">Keine Daten.</p>` : html`<${SortGrid} head=${['Champion', 'Level', 'Punkte']} items=${result.mastery} limit=${masteryShown}
            values=${[(m) => champName(data, m.championKey), (m) => m.level, (m) => m.points]}
            cells=${(m) => [html`<${ChampionCell} data=${data} championKey=${m.championKey} />`, m.level, number(m.points)]} />`}
          ${result.mastery.length > TOP_MASTERY ? html`<${StepToggle} shown=${Math.min(masteryShown, result.mastery.length)} total=${result.mastery.length} top=${TOP_MASTERY} step=${MASTERY_STEP} onChange=${setMasteryShown} />` : null}
        <//>
        <${Card} title="Häufige Mitspieler">
          ${mates.length === 0 ? html`<p class="muted">Keine Mitspieler mit mindestens ${minShared} gemeinsamen Spielen in dieser Auswahl.</p>` : html`<${SortGrid}
            head=${['Mitspieler', 'Spiele', 'WR', 'S / N']} items=${mates} limit=${matesOpen ? 0 : TOP_N}
            values=${[(m) => (m.name ? `${m.name}#${m.tag}` : null), (m) => m.games, (m) => m.wins / m.games, (m) => m.wins]}
            cells=${(m) => {
              const together = m.wins / m.games;
              const without = m.otherGames ? m.otherWins / m.otherGames : null;
              const diff = without === null ? null : (together - without) * 100;
              return [
                m.name ? html`<button type="button" class="mate-link" onClick=${() => onSearch(`${m.name}#${m.tag}`)}>${m.name}<span class="muted">#${m.tag}</span></button>`
                  : html`<span class="muted">Unbekannter Spieler</span>`,
                m.games,
                html`<strong class=${wrClass(together)} data-tip=${without === null ? 'Keine Spiele ohne diesen Mitspieler' : `Ohne diesen Mitspieler: ${percent(without)} WR (${diff > 0 ? '+' : ''}${diff.toLocaleString('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} Pp.)`}>${percent(together)}</strong>`,
                html`<span class="nowrap">${m.wins} / ${m.games - m.wins}</span>`,
              ];
            }} />`}
          ${mates.length > TOP_N ? html`<${MoreToggle} open=${matesOpen} total=${mates.length} top=${TOP_N} onToggle=${() => setMatesOpen(!matesOpen)} />` : null}
          <p class="muted small">Riot meldet keine Premades. Als Mitspieler zählt, wer in dieser Auswahl mehrfach im eigenen Team war; die WR ist die gemeinsame, beim Darüberfahren steht deine Winrate ohne diesen Mitspieler.</p>
        <//>
      </div>
    </div>` : null}

    ${advanced ? html`<p class="muted small">Winrates und meistgespielte Champions beziehen sich auf die abgerufenen Matches bzw. das gewählte Auswertungsfenster. Riot bietet keine Gesamtstatistik je Champion an; über das ganze Konto gibt es nur die Meisterschaft.</p>` : null}
  </div>`;
}

function ChampionCell({ data, championKey, name }) {
  const champion = data.championByKey.get(championKey);
  if (!champion) {
    return html`<span>${name || `Champion ${championKey}`}</span>`;
  }
  return html`<button type="button" class="champion-link" onClick=${() => go('champions', champion.id)}>
    <${Img} src=${champion.icon} size=${28} cls="round" /><span>${champion.name}</span>
  </button>`;
}

function MatchRow({ match: m, data, account }) {
  const outcome = m.remake ? 'remake' : m.win ? 'win' : 'loss';
  const champion = data.championByKey.get(m.championKey);
  return html`<div class=${`match-row ${outcome}`}>
    <div class="match-outcome"><strong>${m.remake ? 'Remake' : m.win ? 'Sieg' : 'Niederlage'}</strong><small>${duration(m.durationSec)}</small></div>
    ${champion ? html`<button type="button" class="icon-link" onClick=${() => go('champions', champion.id)}><${Img} src=${champion.icon} size=${38} cls="round" tip=${champion.name} /></button>` : html`<span>${m.championName}</span>`}
    <${SpellIcons} data=${data} keys=${m.spells} size=${19} />
    <div class="match-info"><strong>${m.mode}</strong><small class="muted">${[m.position, ago(m.endedAt), account].filter(Boolean).join(' · ')}</small></div>
    <div class="match-kda"><strong>${m.kills} / ${m.deaths} / ${m.assists}</strong><small class="muted">${kda(m.kills, m.deaths, m.assists)} KDA · ${m.cs} CS</small></div>
    <${ItemIcons} data=${data} ids=${m.items} size=${26} />
  </div>`;
}
