// Summoner-Tab: aktuelles Spiel (Riot Spectator-v5). Zeigt Name, Rang und auf Knopfdruck die Champion-Winrate.
// Nur auf Anfrage: einmal beim Wechsel auf den Bereich für den gerade angezeigten Summoner, sonst per Knopf. Nie aus der
// Champion-Auswahl, kein regelmäßiges Nachladen.
import { html, useEffect, useState } from '/vendor/htm-preact.js';
import { api, go, percent } from './core.js';
import { Icon, Img, Button, Card, Spinner, SpellIcons } from './ui.js';

const wrClass = (value) => (value >= 0.52 ? 'good' : value < 0.48 ? 'bad' : '');

function elapsed(startedAt) {
  if (!startedAt) return '';
  const seconds = Math.max(0, Math.round((Date.now() - startedAt) / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

/**
 * `current`: der gerade angezeigte Summoner ({@code riotId}, {@code platform}, bei eigenen Konten {@code account}). Er wird
 * beim Öffnen des Bereichs einmal abgefragt; jeder weitere Abruf nur per Knopf.
 */
export function LiveSection({ accounts, platform, current, data }) {
  const [riotId, setRiotId] = useState('');
  const [state, setState] = useState({ loading: false, error: null, result: null });
  const [champ, setChamp] = useState({});

  // `account`: Nummer des eigenen Kontos, wenn keine Riot-ID angegeben ist.
  const query = async (id, account = 0, region = platform) => {
    setState({ loading: true, error: null, result: null });
    setChamp({});
    try {
      const params = new URLSearchParams({ riotId: id.trim(), platform: region, account: String(account) });
      setState({ loading: false, error: null, result: await api.get(`/api/summoner/live?${params}`) });
    } catch (error) {
      setState({ loading: false, error: error.message, result: null });
    }
  };
  const queryCurrent = () => (current.account >= 0 ? query('', current.account) : query(current.riotId, 0, current.platform));
  // Nur beim Öffnen (Montieren) des Bereichs, nicht bei jedem Rendern.
  useEffect(() => {
    if (current) {
      queryCurrent();
    }
  }, []);
  const loadChampion = async (slot) => {
    setChamp((c) => ({ ...c, [slot]: { loading: true } }));
    try {
      const stats = await api.get(`/api/summoner/live/${slot}/champion`);
      setChamp((c) => ({ ...c, [slot]: stats }));
    } catch (error) {
      setChamp((c) => ({ ...c, [slot]: { error: error.message } }));
    }
  };
  const result = state.result;

  return html`<div class="summoner-section">
    <${Card} title="Live-Spiel">
      <div class="live-form">
        ${accounts.length === 0 ? html`<${Button} kind="primary" icon="user" disabled tip="Erst im Summoner-Tab ein Konto als eigenes festlegen">Kein eigenes Konto<//>`
          : accounts.map((a, i) => html`<${Button} key=${`${a.riotId}-${a.platform}`} kind=${i === 0 ? 'primary' : ''} icon="user" disabled=${state.loading}
            tip=${`Laufendes Spiel von ${a.riotId} (${a.platform.toUpperCase()}) abfragen`} onClick=${() => query('', i)}>${a.riotId}<//>`)}
        <span class="muted small">oder</span>
        <label class="search">
          <${Icon} name="search" size=${16} />
          <input type="search" value=${riotId} placeholder="Andere Riot-ID, z. B. Name#EUW" spellcheck="false"
            onInput=${(e) => setRiotId(e.currentTarget.value)} onKeyDown=${(e) => e.key === 'Enter' && riotId.trim() && query(riotId.trim())} />
        </label>
        <${Button} icon="search" disabled=${state.loading || !riotId.trim()} onClick=${() => query(riotId.trim())}>Abfragen<//>
        ${current ? html`<${Button} icon="refresh" disabled=${state.loading} tip=${`Laufendes Spiel von ${current.riotId} erneut abfragen`}
          onClick=${queryCurrent}>${current.riotId} neu abfragen<//>` : null}
      </div>
      <p class="muted small">Beim Öffnen dieses Bereichs wird der gerade angezeigte Summoner einmal abgefragt, danach nur auf Knopfdruck: das laufende Spiel und den Rang jedes Spielers (rund 12 Anfragen). Die Champion-Winrate lädt je Spieler die letzten 20 Ranked-Spiele (bis zu 21 Anfragen); beim eigenen Konto kommt sie aus den gespeicherten Matches.</p>
    <//>
    ${state.loading ? html`<section class="card"><div class="loading"><${Spinner} small /> <span>Frage das aktuelle Spiel ab …</span></div></section>` : null}
    ${state.error ? html`<section class="card notice bad"><${Icon} name="alert" size=${16} /><p>${state.error}</p></section>` : null}
    ${result && !result.inGame ? html`<section class="card notice"><p>„${result.riotId}“ ist gerade in keinem aktiven Spiel. Spiele gegen KI liefert die Riot-API nicht.</p></section>` : null}
    ${result && result.inGame ? html`<div class="live-game">
      <div class="live-head"><strong>${result.mode}</strong><span class="muted">${result.platform.toUpperCase()} · läuft seit ${elapsed(result.startedAt)}</span></div>
      ${result.teams.map((team) => html`<${Card} key=${team.side} cls=${`live-team ${team.side}`} title=${team.side === 'blue' ? 'Blaues Team' : 'Rotes Team'}
        aside=${team.bans.length ? html`<span class="live-bans"><small class="muted">Banns</small>${team.bans.map((key) => {
          const c = data.championByKey.get(key);
          return c ? html`<${Img} key=${key} src=${c.icon} size=${22} cls="round banned" tip=${c.name} />` : null;
        })}</span>` : null}>
        <div class="live-players">${team.players.map((p) => html`<${LivePlayer} key=${p.slot} player=${p} data=${data}
          stats=${p.champion || champ[p.slot]} onLoad=${() => loadChampion(p.slot)} matches=${result.championMatches} />`)}</div>
      <//>`)}
    </div>` : null}
  </div>`;
}

function Rank({ rank, label }) {
  if (!rank) {
    return html`<span class="muted">${label}: Unranked</span>`;
  }
  const games = rank.wins + rank.losses;
  return html`<span><strong class=${rank.tier ? `rank-text rank-${rank.tier.toLowerCase()}` : ""}>${rank.tier} ${rank.division}</strong> <span class="muted">${rank.lp} LP</span>
    <span class=${wrClass(rank.winRate)} data-tip=${`${label}: ${rank.wins} Siege, ${rank.losses} Niederlagen in dieser Saison`}> · ${percent(rank.winRate, 0)} (${games})</span></span>`;
}

function LivePlayer({ player: p, data, stats, onLoad, matches }) {
  const champion = data.championByKey.get(p.championKey);
  let championCell;
  if (p.bot) {
    championCell = html`<span class="muted">KI</span>`;
  } else if (!stats) {
    championCell = html`<button type="button" class="chip-link" onClick=${onLoad} data-tip=${`Lädt die letzten ${matches} Ranked-Spiele dieses Spielers (bis zu ${matches + 1} Riot-Anfragen)`}>Champion-WR laden</button>`;
  } else if (stats.loading) {
    championCell = html`<${Spinner} small />`;
  } else if (stats.error) {
    championCell = html`<span class="bad small">${stats.error}</span>`;
  } else if (stats.games === 0) {
    championCell = html`<span class="muted" data-tip=${stats.source === 'history' ? 'Aus deinen gespeicherten Matches' : `In den letzten ${stats.recent} Ranked-Spielen`}>
      Keine ${stats.queue}-Spiele mit ${champion?.name || 'diesem Champion'}</span>`;
  } else {
    const wr = stats.wins / stats.games;
    championCell = html`<span data-tip=${stats.source === 'history' ? `${stats.queue}, aus deinen gespeicherten Matches` : `${stats.queue}, in den letzten ${stats.recent} Ranked-Spielen (gesamt ${stats.recentWins}S ${stats.recent - stats.recentWins}N)`}>
      <strong class=${wrClass(wr)}>${percent(wr, 0)}</strong> <span class="muted">${stats.wins}S ${stats.games - stats.wins}N</span></span>`;
  }
  return html`<div class=${`live-player${p.self ? ' self' : ''}`}>
    ${champion ? html`<button type="button" class="icon-link" onClick=${() => go('champions', champion.id)}><${Img} src=${champion.icon} size=${38} cls="round" tip=${champion.name} /></button>` : html`<span></span>`}
    <${SpellIcons} data=${data} keys=${p.spells} size=${18} />
    <div class="live-name"><strong>${p.name || 'Unbekannt'}</strong>${p.tag ? html`<span class="muted">#${p.tag}</span>` : null}</div>
    <div class="live-rank"><${Rank} rank=${p.solo} label="Solo/Duo" />${p.flex ? html`<small class="muted">Flex: <span class=${`rank-text rank-${p.flex.tier.toLowerCase()}`}>${p.flex.tier} ${p.flex.division}</span></small>` : null}</div>
    <div class="live-champ">${championCell}</div>
  </div>`;
}
