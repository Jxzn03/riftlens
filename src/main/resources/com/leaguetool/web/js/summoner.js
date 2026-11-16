// Ansicht "Summoner": Abfrage eines Spielers über die Riot-API mit Rängen, Champions, Spielmodi und Match-Verlauf.
import { html, useEffect, useMemo, useState } from '/vendor/htm-preact.js';
import { store, useStore, useViewState, api, go, percent } from './core.js';
import { Icon, Img, Button, Select, Card, Spinner, Empty, Tag, DataGrid, ItemIcons, SpellIcons, MoreToggle } from './ui.js';

const TOP_N = 5; // Standardansicht Mitspieler: nur die ersten 5 Einträge
const TOP_CHAMPS = 5; // Standardansicht Champions
const DEFAULTS = { query: '', platform: '', count: '50', result: null, error: null, loading: false, mode: '' };
const COUNTS = [20, 50, 100, 200, 300, 500, 1000].map((n) => ({ value: String(n), label: `${n} Matches` }));
const MAX_RECENT = 8;
// Dev-Key: 100 Anfragen in 2 Minuten, also gut 1,2 s je Match, sobald das Kontingent aufgebraucht ist.
const SECONDS_PER_MATCH = 1.2;
const SLOW_COUNT = 200;
const HUGE_COUNT = 1000;

const wrClass = (value) => (value >= 0.52 ? 'good' : value < 0.48 ? 'bad' : '');
const number = (value) => value.toLocaleString('de-DE');
const kda = (kills, deaths, assists) => (deaths === 0 ? 'Perfekt' : ((kills + assists) / deaths).toFixed(2));

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
      modes.set(m.mode, emptyGroup({ mode: m.mode }));
    }
    add(modes.get(m.mode), m);
  });
  return [...modes.values()].sort((a, b) => b.games - a.games);
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

  const platform = view.platform || (settings ? settings.platform : '');
  const search = async (riotId, region) => {
    const query = riotId.trim();
    if (!query || view.loading) {
      return;
    }
    setView({ query, platform: region, loading: true, error: null, mode: '' });
    store.set({ summonerProgress: null });
    try {
      const params = new URLSearchParams({ riotId: query, platform: region, count: view.count });
      const result = await api.get(`/api/summoner?${params}`);
      const name = `${result.gameName}#${result.tagLine}`;
      setView({ result, query: name, loading: false });
      const others = store.get().summonerRecent.filter((r) => r.riotId !== name || r.platform !== region);
      store.set({ summonerRecent: [{ riotId: name, platform: region }, ...others].slice(0, MAX_RECENT) });
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
      ${recent.length ? html`<div class="recent-chips">
        ${recent.map((r) => html`<button type="button" key=${`${r.riotId}-${r.platform}`} class="chip-link recent"
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
    ${view.result ? html`<${Result} key=${`${view.result.gameName}${view.result.tagLine}`} result=${view.result} data=${data} mode=${view.mode}
        onMode=${(mode) => setView({ mode })} onSearch=${(riotId) => search(riotId, view.result.platform)} />`
      : !view.loading && !view.error ? html`<${Empty} title="Summoner abfragen">Gib eine Riot-ID ein, um Ränge, meistgespielte Champions, Winrates und Spielmodi zu sehen.<//>` : null}
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

function Result({ result, data, mode, onMode, onSearch }) {
  const [matesOpen, setMatesOpen] = useState(false);
  const [champsOpen, setChampsOpen] = useState(false);
  const modes = useMemo(() => modesOf(result.matches), [result]);
  const matches = useMemo(() => (mode ? result.matches.filter((m) => m.mode === mode) : result.matches), [result, mode]);
  const stats = useMemo(() => aggregate(matches), [matches]);
  const minShared = result.minSharedGames || 3;
  const mates = useMemo(() => matesOf(result.mates || [], matches, minShared), [result, matches]);
  const allGames = modes.reduce((sum, m) => sum + m.games, 0);
  const allWins = modes.reduce((sum, m) => sum + m.wins, 0);
  const { total } = stats;
  const remakes = result.matches.length - allGames;

  return html`<div class="summoner-result">
    <header class="plain-hero">
      <${Img} src=${result.icon} size=${72} cls="hero-icon" />
      <div class="hero-text">
        <h1>${result.gameName}<span class="muted"> #${result.tagLine}</span></h1>
        <p class="hero-sub">Level ${number(result.level)}</p>
        <div class="tags"><${Tag} kind="soft">${result.platform.toUpperCase()}<//></div>
      </div>
    </header>

    <div class="rank-cards">
      ${result.ranked.length === 0 ? html`<div class="rank-card"><span class="muted">Keine Ranked-Platzierung</span></div>` : null}
      ${result.ranked.map((r) => html`<div class="rank-card" key=${r.queue}>
        <small class="muted">Ranked ${r.queue}</small>
        <strong>${r.tier} ${r.division}</strong>
        <span>${r.lp} LP</span>
        <span class=${wrClass(r.winRate)}>${percent(r.winRate)} WR</span>
        <small class="muted">${number(r.wins)} S / ${number(r.losses)} N</small>
      </div>`)}
    </div>

    ${modes.length ? html`<div class="mode-chips" role="radiogroup" aria-label="Spielmodus">
      <button type="button" class=${mode === '' ? 'active' : ''} onClick=${() => onMode('')}>Alle <small>${allGames}</small></button>
      ${modes.map((m) => html`<button type="button" key=${m.mode} class=${mode === m.mode ? 'active' : ''} onClick=${() => onMode(m.mode)}>${m.mode} <small>${m.games}</small></button>`)}
    </div>` : null}

    <div class="report-head">
      <div class="kpi"><strong>${total.games}</strong><span>Matches ausgewertet${remakes > 0 && !mode ? ` (${remakes} Remakes ohne Wertung)` : ''}</span></div>
      <div class="kpi"><strong class=${total.games ? wrClass(total.wins / total.games) : ''}>${total.games ? percent(total.wins / total.games) : '–'}</strong><span>${total.wins} S / ${total.games - total.wins} N</span></div>
      <div class="kpi"><strong>${total.games ? kda(total.kills, total.deaths, total.assists) : '–'}</strong><span>Ø KDA</span></div>
      <div class="kpi"><strong>${total.games ? duration(Math.round(total.seconds / total.games)) : '–'}</strong><span>Ø Spieldauer</span></div>
    </div>

    <div class="summoner-grid">
      <div class="summoner-main">
      <${Card} title="Meistgespielte Champions">
        ${stats.champions.length === 0 ? html`<p class="muted">Keine Matches in dieser Auswahl.</p>` : html`<${DataGrid}
          head=${['Champion', 'Spiele', 'WR', 'S / N', 'KDA', 'Ø K / D / A', 'CS/Min']}
          rows=${(champsOpen ? stats.champions : stats.champions.slice(0, TOP_CHAMPS)).map((c) => [
            html`<${ChampionCell} data=${data} championKey=${c.key} name=${c.name} />`,
            c.games,
            html`<strong class=${wrClass(c.wins / c.games)}>${percent(c.wins / c.games)}</strong>`,
            `${c.wins} / ${c.games - c.wins}`,
            kda(c.kills, c.deaths, c.assists),
            `${(c.kills / c.games).toFixed(1)} / ${(c.deaths / c.games).toFixed(1)} / ${(c.assists / c.games).toFixed(1)}`,
            c.seconds ? (c.cs / (c.seconds / 60)).toFixed(1) : '–',
          ])} />`}
        ${stats.champions.length > TOP_CHAMPS ? html`<${MoreToggle} open=${champsOpen} total=${stats.champions.length} top=${TOP_CHAMPS} onToggle=${() => setChampsOpen(!champsOpen)} />` : null}
      <//>
      <${Card} title="Häufige Mitspieler">
        ${mates.length === 0 ? html`<p class="muted">Keine Mitspieler mit mindestens ${minShared} gemeinsamen Spielen in dieser Auswahl.</p>` : html`<${DataGrid}
          head=${['Mitspieler', 'Spiele', 'WR zusammen', 'S / N', 'WR ohne', 'Differenz']}
          rows=${(matesOpen ? mates : mates.slice(0, TOP_N)).map((m) => {
            const together = m.wins / m.games;
            const without = m.otherGames ? m.otherWins / m.otherGames : null;
            const diff = without === null ? null : (together - without) * 100;
            return [
              m.name ? html`<button type="button" class="mate-link" onClick=${() => onSearch(`${m.name}#${m.tag}`)}>${m.name}<span class="muted">#${m.tag}</span></button>`
                : html`<span class="muted">Unbekannter Spieler</span>`,
              m.games,
              html`<strong class=${wrClass(together)}>${percent(together)}</strong>`,
              `${m.wins} / ${m.games - m.wins}`,
              without === null ? '–' : html`<span class=${wrClass(without)}>${percent(without)}</span>`,
              diff === null ? '–' : html`<strong class=${diff >= 2 ? 'good' : diff <= -2 ? 'bad' : ''}>${diff > 0 ? '+' : ''}${diff.toLocaleString('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} Pp.</strong>`,
            ];
          })} />`}
        ${mates.length > TOP_N ? html`<${MoreToggle} open=${matesOpen} total=${mates.length} top=${TOP_N} onToggle=${() => setMatesOpen(!matesOpen)} />` : null}
        <p class="muted small">Riot meldet keine Premades. Als Mitspieler zählt, wer in dieser Auswahl mehrfach im eigenen Team war; „WR ohne“ ist deine Winrate in den übrigen Spielen.</p>
      <//>
      <${Card} title="Letzte Matches" aside=${html`<span class="muted">${matches.length} angezeigt</span>`}>
        <div class="match-list">${matches.map((m) => html`<${MatchRow} key=${m.id} match=${m} data=${data} />`)}</div>
        ${matches.length === 0 ? html`<p class="muted">Keine Matches in dieser Auswahl.</p>` : null}
      <//>
      </div>
      <div class="summoner-side">
        <${Card} title="Spielmodi">
          ${modes.length === 0 ? html`<p class="muted">Keine Matches gefunden.</p>` : html`<${DataGrid} head=${['Modus', 'Spiele', 'WR']}
            rows=${modes.map((m) => [m.mode, m.games, html`<strong class=${wrClass(m.wins / m.games)}>${percent(m.wins / m.games)}</strong>`])} />`}
        <//>
        ${stats.roles.length ? html`<${Card} title="Rollen"><${DataGrid} head=${['Rolle', 'Spiele', 'WR']}
          rows=${stats.roles.map((r) => [r.position, r.games, html`<strong class=${wrClass(r.wins / r.games)}>${percent(r.wins / r.games)}</strong>`])} /><//>` : null}
        <${Card} title="Meisterschaft" aside=${html`<span class="muted">gesamtes Konto</span>`}>
          ${result.mastery.length === 0 ? html`<p class="muted">Keine Daten.</p>` : html`<${DataGrid} head=${['Champion', 'Level', 'Punkte']}
            rows=${result.mastery.map((m) => [html`<${ChampionCell} data=${data} championKey=${m.championKey} />`, m.level, number(m.points)])} />`}
        <//>
      </div>
    </div>

    <p class="muted small">Winrates und meistgespielte Champions beziehen sich auf die abgerufenen Matches. Riot bietet keine Gesamtstatistik je Champion an; über das ganze Konto gibt es nur die Meisterschaft.</p>
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

function MatchRow({ match: m, data }) {
  const outcome = m.remake ? 'remake' : m.win ? 'win' : 'loss';
  const champion = data.championByKey.get(m.championKey);
  return html`<div class=${`match-row ${outcome}`}>
    <div class="match-outcome"><strong>${m.remake ? 'Remake' : m.win ? 'Sieg' : 'Niederlage'}</strong><small>${duration(m.durationSec)}</small></div>
    ${champion ? html`<button type="button" class="icon-link" onClick=${() => go('champions', champion.id)}><${Img} src=${champion.icon} size=${38} cls="round" tip=${champion.name} /></button>` : html`<span>${m.championName}</span>`}
    <${SpellIcons} data=${data} keys=${m.spells} size=${19} />
    <div class="match-info"><strong>${m.mode}</strong><small class="muted">${[m.position, ago(m.endedAt)].filter(Boolean).join(' · ')}</small></div>
    <div class="match-kda"><strong>${m.kills} / ${m.deaths} / ${m.assists}</strong><small class="muted">${kda(m.kills, m.deaths, m.assists)} KDA · ${m.cs} CS</small></div>
    <${ItemIcons} data=${data} ids=${m.items} size=${26} />
  </div>`;
}
