// Draft-Assistent: Eingabe der Picks und Bans, die fünf besten nächsten Picks und die Gesamteinschätzung.
import { html, useState, useEffect, useMemo, useRef } from '/vendor/htm-preact.js';
import { store, useStore, useViewState, useSourceLabel, api, normalize, percent, sortRows, activeSort, useFavorites, openChampion, toast } from './core.js';
import { Icon, Img, Button, Select, Segmented, Switch, Card, Empty, ErrorNote, Loading, Tag, Spinner, FavoriteMark, MoreToggle } from './ui.js';

const ROLES = [
  { value: 'TOP', label: 'Top' }, { value: 'JUNGLE', label: 'Jungle' }, { value: 'MIDDLE', label: 'Mid' },
  { value: 'BOTTOM', label: 'ADC' }, { value: 'UTILITY', label: 'Support' },
];
const roleLabel = (role) => ROLES.find((r) => r.value === role)?.label || role;
/** Position in Top, Jungle, Mid, Bot, Support; unbekannte Rollen ans Ende. */
const roleOrder = (role) => {
  const i = ROLES.findIndex((r) => r.value === role);
  return i < 0 ? ROLES.length : i;
};
/** Spalte der Analyse-Tabellen: Änderung der Winrate deines Teams in Prozentpunkten, beim Gegner mit umgekehrtem Vorzeichen. */
const DELTA_TITLE = 'Δ Team-WR';
/** Tooltip zu „Δ Team-WR“ je Tabelle: was gemessen wird, dazu die gemeinsame Lesart. */
const DELTA_BASIS = {
  champion: 'Stärke des Champions auf seiner Rolle gegenüber dem Durchschnitt des Rang-Bereichs.',
  duo: 'Synergie: wie viel besser die beiden zusammen gewinnen, als ihre Einzelstärke erwarten lässt.',
  matchup: 'Wie dein Champion gegen diesen Gegner abschneidet, über die übliche Stärke beider hinaus.',
};
/** Die Frage hinter jedem der drei Bausteine der Siegchance; immer als Tooltip, damit die Namen klar bleiben. */
const PART_QUESTIONS = {
  champion: 'Wie gut ist der Pick vom Powerlevel her, ist er blind pickable?',
  duo: 'Passt er zu den anderen Champions im Team?',
  matchup: 'Wie gut ist der Champion gegen die Gegner?',
};
const deltaTip = (kind) => `${DELTA_BASIS[kind]} Angabe als Änderung der erwarteten Winrate deines Teams in Prozentpunkten: `
  + 'Grün hilft dir, Rot schadet dir. Werte des Gegners zählen mit umgekehrtem Vorzeichen.';
const TEAM_SIZE = 5;
const MAX_BANS = 10;
const TOP_PICKS = 5;
const MAX_TABLE_ROWS = 40;

const RISKS = [
  { value: 'VERY_LOW', label: 'Sehr niedrig (×3)' }, { value: 'LOW', label: 'Niedrig (×2)' },
  { value: 'MEDIUM', label: 'Mittel (×1)' }, { value: 'HIGH', label: 'Hoch (×½)' }, { value: 'VERY_HIGH', label: 'Sehr hoch (×¼)' },
];
const AUTO_MIN_GAMES = 'auto';
const MIN_GAMES = [0, 1000, 2500, 4300, 10000, 25000].map((n) => ({ value: String(n), label: `ab ${n.toLocaleString('de-DE')} Spiele` }));
/** Pickrate auf der Rolle in Prozent; „Auto“ ist der Standard des Backends. */
const MIN_PICK_RATES = [0.25, 0.5, 1, 1.5, 2, 3].map((p) => ({ value: String(p / 100), label: `ab ${p} %` }));
/** Eine Profil-Aussage über ein Team braucht mehr als ein oder zwei Picks. */
const MIN_PICKS_FOR_PROFILE = 3;
/** Kennzeichnet dieses Fenster, damit das Backend nur seine eigenen überholten Anfragen abbricht. */
const CLIENT_ID = crypto.randomUUID();

const DEFAULTS = {
  ally: Array(TEAM_SIZE).fill(null), enemy: Array(TEAM_SIZE).fill(null), bans: [],
  side: 'ally', role: '', risk: 'MEDIUM', minGames: AUTO_MIN_GAMES, minPickRate: AUTO_MIN_GAMES, ignoreChampionWinRates: false, rarePicks: false,
  tab: 'picks', picker: null,
  // Sperren eines aus dem Archiv geladenen Serienspiels ({ seriesId, label, game, locked }); null = keine.
  fearlessContext: null, archiveMode: 'drafts',
};

/** Winrate-Anteil eines Rating-Werts in Prozentpunkten. */
const impact = (rating) => (1 / (1 + 10 ** (-rating / 400)) - 0.5) * 100;
const signed = (value, decimals = 1) => {
  // Erst runden: Sonst erscheint ein winziger negativer Wert als "−0.0".
  const rounded = Number(value.toFixed(decimals));
  return `${rounded >= 0 ? '+' : '−'}${Math.abs(rounded).toFixed(decimals)}`;
};
const impactText = (rating) => signed(impact(rating));
const toneOf = (value) => (value >= 0.05 ? 'good' : value <= -0.05 ? 'bad' : '');
const count = (games) => Math.round(games).toLocaleString('de-DE');
/** Beschriftung von „Auto“: bei gängigen Picks die Standard-Pickrate, sonst die absoluten Mindestspiele. */
const autoLabel = (result, rarePicks) => {
  if (!result || Boolean(result.minPickRate === 0) !== Boolean(rarePicks)) {
    return 'Auto';
  }
  return rarePicks ? `Auto (ab ${result.minGames.toLocaleString('de-DE')})` : `Auto (ab ${percent(result.defaultMinPickRate, 2)})`;
};

// ------------------------------------------------------------------ Archiv: Aufzeichnung

/** So lange muss ein vollständiger Draft unverändert bleiben, bevor er ins Archiv geht (Zeit für Korrekturen). */
const ARCHIVE_DELAY_MS = 10000;

/** Kennung der Champions je Seite; null, solange nicht alle zehn feststehen. Rollen, Reihenfolge und Bans zählen nicht. */
function completeDraftKey(view) {
  if (!view) {
    return null;
  }
  const ally = view.ally.filter(Boolean);
  const enemy = view.enemy.filter(Boolean);
  if (ally.length < TEAM_SIZE || enemy.length < TEAM_SIZE) {
    return null;
  }
  const ids = (team) => team.map((s) => s.champion).sort((a, b) => a - b);
  return JSON.stringify([ids(ally), ids(enemy)]);
}

/**
 * Legt vollständige Drafts im lokalen Archiv ab, auch wenn der Tab gerade nicht offen ist. Hinaus gehen nur Champions,
 * feste Rollen und Bans vom Brett, und zwar nur an das eigene Backend (`POST /api/draft-archive`). Das Backend nimmt
 * nur Drafts auf, die sich von jedem gezeigten Eintrag in mindestens drei Champions unterscheiden: Ein geladener Draft
 * mit ein, zwei anderen Picks verdrängt so keine älteren.
 */
let archiveKey = null;
let archiveTimer = null;
store.subscribe(() => {
  // Während einer Fearless-Serie gehören vollständige Drafts in die Serie, nicht ins Archiv; ein geladenes Serienspiel
  // ebenso wenig.
  const view = store.get().views.draft;
  const key = store.get().fearless?.active || view?.fearlessContext ? null : completeDraftKey(view);
  if (key === archiveKey) {
    return;
  }
  archiveKey = key;
  clearTimeout(archiveTimer);
  archiveTimer = key ? setTimeout(() => archiveDraft(key), ARCHIVE_DELAY_MS) : null;
});

function archiveDraft(key) {
  const view = store.get().views.draft;
  if (completeDraftKey(view) !== key || store.get().fearless?.active || view.fearlessContext) {
    return;
  }
  api.post('/api/draft-archive', { ally: view.ally.filter(Boolean), enemy: view.enemy.filter(Boolean), bans: view.bans || [] })
    .then((outcome) => {
      if (outcome.saved) {
        store.set({ draftArchiveRev: (store.get().draftArchiveRev || 0) + 1 });
        toast('Draft im Archiv gespeichert');
      }
    })
    .catch(() => {});
}

function useDraftResult(view, dataSource, locked) {
  const [state, setState] = useState({ result: null, error: null, loading: true, completing: false });
  const sequence = useRef(0);
  const body = JSON.stringify({
    ally: view.ally.filter(Boolean), enemy: view.enemy.filter(Boolean), bans: view.bans, side: view.side,
    // Fearless: in früheren Spielen der Serie gespielte Champions fehlen in den Vorschlägen.
    locked: [...locked.keys()],
    // Spielzahlen gelten nur für seltene Picks, sonst die Pickrate auf der Rolle.
    risk: view.risk, rarePicks: Boolean(view.rarePicks),
    minGames: !view.rarePicks || view.minGames === AUTO_MIN_GAMES ? null : Number(view.minGames),
    minPickRate: view.rarePicks || !view.minPickRate || view.minPickRate === AUTO_MIN_GAMES ? null : Number(view.minPickRate),
    ignoreChampionWinRates: view.ignoreChampionWinRates,
    tier: dataSource.tier, period: dataSource.period,
  });
  // Zwei Schritte: Die schnelle Anfrage lädt nur, was die Vorschläge bestimmen, und zeigt sie sofort. Fehlt danach noch
  // etwas für die Gesamtanalyse ("complete": false), lädt eine zweite Anfrage den Rest (completing).
  useEffect(() => {
    const requestId = ++sequence.current;
    const controller = new AbortController();
    const post = (id, quick) => api.post('/api/draft', { ...JSON.parse(body), client: CLIENT_ID, requestId: id, quick }, controller.signal);
    setState((previous) => ({ ...previous, error: null, loading: true, completing: false, requestId }));
    const timer = setTimeout(() => {
      post(requestId, true)
        .then((result) => {
          if (controller.signal.aborted) {
            return null;
          }
          if (result.complete !== false) {
            setState({ result, error: null, loading: false, completing: false, requestId });
            return null;
          }
          const fullId = ++sequence.current;
          setState({ result, error: null, loading: false, completing: true, requestId: fullId });
          return post(fullId, false).then((full) => !controller.signal.aborted
            && setState({ result: full, error: null, loading: false, completing: false, requestId: fullId }));
        })
        .catch((error) => !controller.signal.aborted
          && setState((previous) => ({ ...previous, error, loading: false, completing: false })));
    }, 150);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [body]);
  return state;
}

/**
 * Abgleich mit dem League-Client (nur lesend, im Backend). Ändert sich die Champion-Auswahl, werden Picks, Bans und die
 * eigenen Rollen übernommen. Eine von Hand geänderte Rolle eines schon übernommenen Champions bleibt erhalten.
 */
function useClientSync(view, setView, data) {
  const lcu = useStore((s) => s.lcu);
  useEffect(() => {
    // Anfangszustand einmal holen, danach kommen Änderungen als Ereignis.
    api.get('/api/lcu').then((state) => store.set({ lcu: state })).catch(() => {});
  }, []);
  const applied = useRef(null);
  const champSelect = lcu && lcu.status === 'CHAMP_SELECT' ? lcu.champSelect : null;
  const signature = champSelect ? JSON.stringify([champSelect.gameId, champSelect.ally.map((m) => [m.champion, m.role]),
    champSelect.enemy.map((m) => m.champion), champSelect.bans]) : null;
  useEffect(() => {
    if (!champSelect || !data || signature === applied.current) {
      return;
    }
    const sameGame = applied.current !== null && JSON.parse(applied.current)[0] === champSelect.gameId;
    applied.current = signature;
    const known = (key) => data.championByKey.has(key);
    const toSlots = (members, previous) => {
      const slots = Array(TEAM_SIZE).fill(null);
      const taken = new Set();
      members.slice(0, TEAM_SIZE).forEach((m, i) => {
        if (!m.champion || !known(m.champion)) {
          return;
        }
        const before = sameGame ? previous.find((s) => s && s.champion === m.champion) : null;
        let role = before ? before.role : m.role || null;
        // Eine Rolle darf nur einmal fest vergeben sein; sonst wird der spätere Platz automatisch zugeteilt.
        if (role && taken.has(role)) {
          role = null;
        }
        if (role) {
          taken.add(role);
        }
        slots[i] = { champion: m.champion, role };
      });
      return slots;
    };
    setView({
      ally: toSlots(champSelect.ally, view.ally), enemy: toSlots(champSelect.enemy, view.enemy),
      bans: champSelect.bans.filter(known).slice(0, MAX_BANS), picker: null,
      // Eine neue Champion-Auswahl ersetzt ein aus dem Archiv geladenes Serienspiel samt dessen Sperren.
      ...(sameGame ? {} : { fearlessContext: null }),
    });
  }, [signature, data]);
  const toggle = (enabled) => api.post('/api/lcu', { enabled }).then((state) => store.set({ lcu: state }))
    .catch((error) => store.set({ lcu: { status: 'UNREACHABLE', message: error.message, pickable: [] } }));
  return { lcu, champSelect, toggle };
}

const CLIENT_STATUS = {
  CONNECTING: 'Suche den League-Client …',
  NO_CLIENT: 'League-Client nicht gefunden',
  IDLE: 'Verbunden, wartet auf die Champion-Auswahl',
  CHAMP_SELECT: 'Champion-Auswahl wird übernommen',
};

function ClientSync({ lcu, onToggle }) {
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const enabled = Boolean(lcu) && lcu.status !== 'OFF';
  const text = enabled ? CLIENT_STATUS[lcu.status] || lcu.message : '';
  return html`<div class="draft-client">
    <${Switch} checked=${enabled} onChange=${onToggle}
      tip=${advancedTips ? 'Liest während der Champion-Auswahl Picks, Bans und eure Rollen aus dem League-Client (nur lesend, wie DraftGap). Keine Namen, keine Aktionen im Client.' : undefined}>Mit League-Client abgleichen<//>
    ${text ? html`<span class=${`small draft-client-status ${lcu.status === 'CHAMP_SELECT' ? 'live' : 'muted'}`}>${text}</span>` : null}
  </div>`;
}

// ------------------------------------------------------------------ Fearless-Serie

const FEARLESS_FORMATS = [{ value: 'BO3', label: 'Bo3' }, { value: 'BO5', label: 'Bo5' }, { value: 'OPEN', label: 'Offen' }];
const formatLabel = (format) => FEARLESS_FORMATS.find((f) => f.value === format)?.label || format;
const NO_LOCKS = [];
/** Ab so langer Pause ohne neues Spiel fragt der Tab, ob die Serie noch läuft. */
const STALE_SERIES_MS = 12 * 60 * 60 * 1000;
const SERIES_DATE = { dateStyle: 'medium', timeStyle: 'short' };

/**
 * Stand der Fearless-Serien, einmal beim Start geladen: Auch die Archiv-Aufzeichnung muss wissen, ob eine Serie läuft.
 * Alles bleibt lokal (`/api/fearless`), es gibt dafür keine Abfrage beim League-Client.
 */
api.get('/api/fearless').then((state) => store.set({ fearless: state })).catch(() => {});

/** Plätze eines gespeicherten Drafts fürs Brett; unbekannte Champions fallen weg, `skip` wird zum freien Platz. */
function boardSlots(team, data, skip = null) {
  return [...team.filter((s) => data.championByKey.has(s.champion))
    .map((s) => (s.champion === skip ? null : { champion: s.champion, role: s.role || null })), ...Array(TEAM_SIZE).fill(null)]
    .slice(0, TEAM_SIZE);
}

/** Aktionen auf die Serien; jede Antwort ist der neue Gesamtstand und landet im Store. */
function useFearless() {
  const state = useStore((s) => s.fearless);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const call = (promise) => {
    setBusy(true);
    return promise
      .then((next) => {
        store.set({ fearless: next });
        setError(null);
        return next;
      })
      .catch((e) => {
        setError(e);
        return null;
      })
      .finally(() => setBusy(false));
  };
  return {
    state, error, busy,
    start: (format, label) => call(api.post('/api/fearless', { format, label })),
    finish: (draft, result) => call(api.post('/api/fearless/game', { ...draft, result })),
    undo: () => call(api.del('/api/fearless/game')),
    end: () => call(api.post('/api/fearless/end')),
    rename: (id, label) => call(api.put(`/api/fearless/${id}`, { label })),
    remove: (id) => call(api.del(`/api/fearless/${id}`)),
  };
}

/** „Spiel 3/5 · 2:0“; ohne eingetragene Ergebnisse ohne Stand. */
function seriesProgress(series) {
  const game = series.format === 'OPEN' ? `Spiel ${series.nextGame}` : `Spiel ${series.nextGame}/${series.maxGames}`;
  return series.wins + series.losses > 0 ? `${game} · ${series.wins}:${series.losses}` : game;
}

/** Bezeichnung ändern: Eingabe mit Enter/Escape, leer entfernt die Bezeichnung. */
function LabelEditor({ value, maxLength, onSave, onCancel }) {
  const [text, setText] = useState(value || '');
  const input = useRef();
  useEffect(() => input.current.focus(), []);
  return html`<div class="draft-fearless-rename">
    <input ref=${input} class="input" type="text" maxlength=${maxLength} spellcheck="false" value=${text} aria-label="Bezeichnung"
      placeholder="Bezeichnung (optional)" onInput=${(e) => setText(e.currentTarget.value)}
      onKeyDown=${(e) => (e.key === 'Enter' ? onSave(text) : e.key === 'Escape' ? onCancel() : null)} />
    <${Button} icon="ok" kind="icon" title="Bezeichnung speichern" onClick=${() => onSave(text)} />
    <${Button} icon="x" kind="icon" title="Abbrechen" onClick=${onCancel} />
  </div>`;
}

/**
 * Schalter und Stand der Fearless-Serie (Hard Fearless): Picks beider Teams aus abgeschlossenen Spielen sind in den
 * folgenden Spielen gesperrt, Bans nicht. Ein Spiel zählt erst nach „Spiel abschließen“, nie automatisch.
 */
function FearlessPanel({ fearless, view, setView, data, boardComplete, lcuStatus, conflicts }) {
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const staleAck = useStore((s) => s.fearlessStaleAck);
  const { state, busy } = fearless;
  const active = state?.active || null;
  const context = view.fearlessContext || null;
  const maxLength = state?.maxLabelLength || 60;
  const [form, setForm] = useState(null);
  const [finishing, setFinishing] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [offer, setOffer] = useState(false);
  useEffect(() => setFinishing(false), [boardComplete, active?.nextGame]);

  // Endet die Champion-Auswahl im Client mit vollem Brett, schlägt das Panel vor, das Spiel abzuschließen. Automatisch
  // zählt es nie: In der Finalisierungsphase kann noch jemand dodgen. Gelesen wird dafür nichts Zusätzliches.
  const previousStatus = useRef(lcuStatus);
  useEffect(() => {
    if (lcuStatus === 'CHAMP_SELECT') {
      setOffer(false);
    } else if (previousStatus.current === 'CHAMP_SELECT' && active && boardComplete && !context) {
      setOffer(true);
    }
    previousStatus.current = lcuStatus;
  }, [lcuStatus]);
  useEffect(() => {
    if (!active || !boardComplete) {
      setOffer(false);
    }
  }, [active?.nextGame, boardComplete]);

  // Eine vergessene Serie würde im Ranked Vorschläge wegnehmen: nach 12 h ohne neues Spiel einmal je Sitzung nachfragen.
  const lastActivity = active ? Date.parse(active.games.length ? active.games[active.games.length - 1].playedAt : active.startedAt) : 0;
  const stale = active && Date.now() - lastActivity > STALE_SERIES_MS && staleAck !== active.id;

  const toggle = (on) => {
    if (on) {
      setForm({ format: 'BO3', label: '' });
    } else if (form) {
      setForm(null);
    } else if (active) {
      const games = active.games.length;
      if (games > 0 && !window.confirm(`Fearless-Serie beenden? ${games === 1 ? 'Das gespielte Spiel bleibt' : `Die ${games} gespielten Spiele bleiben`} unter Archiv › Serien erhalten.`)) {
        return;
      }
      fearless.end().then((next) => next && toast(games > 0 ? 'Fearless-Serie beendet' : 'Fearless-Serie verworfen'));
    }
  };
  const start = () => fearless.start(form.format, form.label).then((next) => {
    if (next) {
      setForm(null);
      setView({ fearlessContext: null });
      toast('Fearless-Serie gestartet: Der aktuelle Draft ist Spiel 1');
    }
  });
  const finish = (result) => {
    const draft = { ally: view.ally.filter(Boolean), enemy: view.enemy.filter(Boolean), bans: view.bans };
    fearless.finish(draft, result).then((next) => {
      if (!next) {
        return;
      }
      setView({ ally: DEFAULTS.ally, enemy: DEFAULTS.enemy, bans: [], picker: null });
      const series = next.series;
      toast(next.ended ? `Serie beendet nach Spiel ${next.game}${series.wins + series.losses > 0 ? ` (${series.wins}:${series.losses})` : ''}`
        : `Spiel ${next.game} abgeschlossen, ${next.locked.length} Champions gesperrt`);
    });
  };
  const undo = () => {
    const board = view.ally.some(Boolean) || view.enemy.some(Boolean) || view.bans.length > 0;
    if (board && !window.confirm('Letztes Spiel zurücknehmen? Sein Draft ersetzt das aktuelle Brett.')) {
      return;
    }
    fearless.undo().then((next) => {
      if (next && next.undone) {
        const game = next.undone;
        setView({ ally: boardSlots(game.ally, data), enemy: boardSlots(game.enemy, data),
          bans: game.bans.filter((key) => data.championByKey.has(key)).slice(0, MAX_BANS), picker: null, fearlessContext: null });
        toast(`Spiel ${game.number} zurückgenommen`);
      }
    });
  };
  const rename = (label) => fearless.rename(active.id, label).then((next) => next && setRenaming(false));

  return html`<div class="draft-fearless">
    <${Switch} checked=${Boolean(active || form)} onChange=${toggle}
      tip=${advancedTips ? 'Hard Fearless für Bo3, Bo5 oder offene Scrim-Blöcke: Champions, die ein Team in einem früheren Spiel der Serie gepickt hat, sind danach für beide Teams gesperrt. Bans werden nicht übertragen. Nur lokal, ohne Abfrage beim League-Client.' : undefined}>Fearless-Serie<//>
    ${stale ? html`<div class="draft-fearless-notice">
      <${Icon} name="alert" size=${15} />
      <span>${active.label ? `„${active.label}“` : 'Die Fearless-Serie'} läuft seit ${new Date(active.startedAt).toLocaleString('de-DE', SERIES_DATE)}. Noch aktuell?</span>
      <div class="draft-fearless-actions">
        <${Button} kind="ghost" onClick=${() => store.set({ fearlessStaleAck: active.id })}>Fortsetzen<//>
        <${Button} onClick=${() => toggle(false)} disabled=${busy}>Serie beenden<//>
      </div>
    </div>` : null}
    ${active && conflicts.length ? html`<div class="draft-fearless-notice">
      <${Icon} name="alert" size=${15} />
      <span>Im League-Client gepickt, aber in der Serie gesperrt: ${conflicts.map((key) => data.championByKey.get(key)?.name).filter(Boolean).join(', ')}.
        Läuft gerade kein Fearless-Spiel?</span>
      <div class="draft-fearless-actions"><${Button} onClick=${() => toggle(false)} disabled=${busy}>Serie beenden<//></div>
    </div>` : null}
    ${offer && active && !finishing ? html`<div class="draft-fearless-notice offer">
      <${Icon} name="lock" size=${15} />
      <span>Champion-Auswahl beendet. Draft als Spiel ${active.nextGame} der Serie übernehmen?</span>
      <div class="draft-fearless-actions">
        <${Button} kind="primary" onClick=${() => { setOffer(false); setFinishing(true); }}>Übernehmen<//>
        <${Button} kind="ghost" onClick=${() => setOffer(false)}>Nicht übernehmen<//>
      </div>
    </div>` : null}
    ${form ? html`<div class="draft-fearless-form">
      <${Segmented} label="Format" value=${form.format} options=${FEARLESS_FORMATS} onChange=${(format) => setForm({ ...form, format })} />
      <input class="input" type="text" maxlength=${maxLength} spellcheck="false" value=${form.label} aria-label="Bezeichnung"
        placeholder="Bezeichnung (optional), z. B. Scrim Dienstag" onInput=${(e) => setForm({ ...form, label: e.currentTarget.value })}
        onKeyDown=${(e) => e.key === 'Enter' && start()} />
      <div class="draft-fearless-actions">
        <${Button} kind="primary" icon="ok" onClick=${start} disabled=${busy}>Serie starten<//>
        <${Button} kind="ghost" onClick=${() => setForm(null)}>Abbrechen<//>
      </div>
    </div>` : null}
    ${active ? html`<div class="draft-fearless-bar">
      ${renaming ? html`<${LabelEditor} value=${active.label} maxLength=${maxLength} onSave=${rename} onCancel=${() => setRenaming(false)} />`
        : html`<div class="draft-fearless-head">
          <strong class="draft-fearless-title">${active.label || 'Fearless-Serie'}</strong>
          <${Button} icon="edit" kind="ghost" title="Bezeichnung ändern" onClick=${() => setRenaming(true)}
            tip=${advancedTips ? 'Freie Bezeichnung, nur lokal gespeichert' : undefined} />
        </div>`}
      <span class="muted small">${formatLabel(active.format)} · ${seriesProgress(active)} · ${state.locked.length} gesperrt</span>
      <div class="draft-fearless-actions">
        ${finishing ? html`
          <span class="small">Ergebnis für dein Team:</span>
          <${Button} kind="primary" onClick=${() => finish('WIN')} disabled=${busy}>Gewonnen<//>
          <${Button} onClick=${() => finish('LOSS')} disabled=${busy}>Verloren<//>
          <${Button} kind="ghost" onClick=${() => finish(null)} disabled=${busy}>Ohne Ergebnis<//>
          <${Button} icon="x" kind="icon" title="Abbrechen" onClick=${() => setFinishing(false)} />`
        : html`
          <${Button} icon="lock" onClick=${() => setFinishing(true)} disabled=${!boardComplete || busy || Boolean(context)}
            tip=${context ? 'Erst das geladene Serienspiel schließen' : !boardComplete ? 'Erst alle zehn Champions eintragen' : advancedTips ? 'Sperrt die zehn Picks für die folgenden Spiele und leert das Brett' : undefined}>Spiel ${active.nextGame} abschließen<//>
          <${Button} icon="undo" kind="icon" title="Letztes Spiel zurücknehmen" onClick=${undo} disabled=${busy || active.games.length === 0}
            tip=${active.games.length ? `Spiel ${active.games.length} zurücknehmen` : undefined} />`}
      </div>
    </div>` : null}
    ${context ? html`<div class="draft-fearless-context small">
      <${Icon} name="lock" size=${14} />
      <span>Sperren aus ${context.label || 'Serie'}, Spiel ${context.game}</span>
      <${Button} icon="x" kind="icon" title="Sperren des Serienspiels aufheben" onClick=${() => setView({ fearlessContext: null })} />
    </div>` : null}
    ${fearless.error ? html`<${ErrorNote} prefix="Fearless" error=${fearless.error} />` : null}
  </div>`;
}

/** Gesperrte Champions nach Spiel, unter den Bans; einklappbar, der Stand wird gespeichert. */
function FearlessLocks({ locked, data }) {
  const open = useStore((s) => s.draftFearlessLocksOpen !== false);
  if (locked.size === 0) {
    return null;
  }
  const games = new Map();
  locked.forEach((game, champion) => games.set(game, [...(games.get(game) || []), champion]));
  return html`<div class="draft-locks" aria-label="Fearless-Sperren">
    <button type="button" class="draft-locks-head" aria-expanded=${open} onClick=${() => store.set({ draftFearlessLocksOpen: !open })}>
      <${Icon} name="lock" size=${14} /><span>Fearless-Sperren</span><span class="muted small">${locked.size}</span>
      <span class=${`draft-locks-chevron ${open ? 'open' : ''}`}><${Icon} name="chevron" size=${14} /></span>
    </button>
    ${open ? [...games].map(([game, champions]) => html`<div class="draft-locks-game" key=${game}>
      <span class="muted small">Spiel ${game}</span>
      ${champions.map((key) => {
        const champion = data.championByKey.get(key);
        return champion ? html`<span key=${key} class="draft-lock-icon" data-tip=${`${champion.name}: in Spiel ${game} gespielt`}>
          <${Img} src=${champion.icon} size=${24} alt=${champion.name} />
        </span>` : null;
      })}
    </div>`) : null}
  </div>`;
}

export function DraftView() {
  const data = useStore((s) => s.data);
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const dataSource = useStore((s) => s.dataSource);
  const [view, setView] = useViewState('draft', DEFAULTS);
  const fearless = useFearless();
  const fearlessActive = fearless.state?.active || null;
  const fearlessContext = view.fearlessContext || null;
  // Champion-Key → Spiel, in dem er gespielt wurde. Ein geladenes Serienspiel bringt seine eigenen Sperren mit.
  const lockedList = fearlessContext ? fearlessContext.locked : fearlessActive ? fearless.state.locked : NO_LOCKS;
  const locked = useMemo(() => new Map(lockedList.map((l) => [l.champion, l.game])), [lockedList]);
  const { result, error, loading, completing, requestId } = useDraftResult(view, dataSource, locked);
  const sourceLabel = useSourceLabel();
  const client = useClientSync(view, setView, data);
  const owned = useMemo(() => new Set(client.champSelect ? client.lcu.pickable : []), [client.champSelect, client.lcu]);
  const hints = useMemo(() => {
    const cs = client.champSelect;
    return cs ? { ally: cs.ally.map((m) => m), enemy: cs.enemy.map((m) => m) } : { ally: [], enemy: [] };
  }, [client.champSelect]);

  const used = useMemo(() => new Set([...view.ally, ...view.enemy].filter(Boolean).map((s) => s.champion).concat(view.bans)),
    [view.ally, view.enemy, view.bans]);
  // Gesperrte Champions auf dem Brett, z. B. aus dem Client-Abgleich in einer Ranked-Auswahl bei vergessener Serie.
  const lockedOnBoard = useMemo(() => [...view.ally, ...view.enemy].filter((s) => s && locked.has(s.champion))
    .map((s) => s.champion), [view.ally, view.enemy, locked]);

  const assign = (picker, champion) => {
    if (picker.team === 'bans') {
      setView({ bans: [...view.bans, champion], picker: null });
    } else {
      const team = [...view[picker.team]];
      team[picker.index] = { champion, role: picker.role || null };
      setView({ [picker.team]: team, picker: null });
    }
  };

  // Pick, dessen Rolle gerade geändert wurde: Er zeigt einen Spinner, bis seine Daten nachgeladen sind.
  const [pendingRole, setPendingRole] = useState(null);
  useEffect(() => {
    if (!loading) {
      setPendingRole(null);
    }
  }, [loading]);

  const setSlotRole = (teamName, index, role) => {
    setPendingRole(`${teamName}:${view[teamName][index].champion}`);
    // Eine fest vergebene Rolle wandert: Der bisherige Champion dieser Rolle wird wieder automatisch zugeteilt.
    const team = view[teamName].map((slot, i) => {
      if (i === index) {
        return { ...slot, role: role || null };
      }
      return slot && role && slot.role === role ? { ...slot, role: null } : slot;
    });
    setView({ [teamName]: team });
  };

  const clearSlot = (teamName, index) => {
    const team = [...view[teamName]];
    team[index] = null;
    setView({ [teamName]: team });
  };

  const takeSuggestion = (suggestion) => {
    // Während des Ladens gehören die angezeigten Vorschläge noch zum vorherigen Draft.
    const team = view.side;
    const roleTaken = view[team].some((slot) => slot && slot.role === suggestion.role);
    if (loading || !result || result.side !== team || used.has(suggestion.champion) || locked.has(suggestion.champion) || roleTaken) {
      return;
    }
    const index = view[team].findIndex((slot) => !slot);
    if (index >= 0) {
      assign({ team, index, role: suggestion.role }, suggestion.champion);
    }
  };

  const reset = () => setView({ ally: DEFAULTS.ally, enemy: DEFAULTS.enemy, bans: [], fearlessContext: null });
  const picks = view.ally.filter(Boolean).length + view.enemy.filter(Boolean).length;

  return html`<div class="page draft">
    <section class="draft-board">
      <${TeamColumn} title="Dein Team" team="ally" slots=${view.ally} result=${result} data=${data} pending=${pendingRole} hints=${hints.ally}
        onPick=${(index) => setView({ picker: { team: 'ally', index } })} onRole=${setSlotRole} onClear=${clearSlot} />
      <div class="draft-center">
        <${WinPanel} analysis=${result?.analysis} picks=${picks} loading=${loading} completing=${completing} />
        <${BanRow} bans=${view.bans} data=${data} onAdd=${() => setView({ picker: { team: 'bans' } })}
          onRemove=${(key) => setView({ bans: view.bans.filter((b) => b !== key) })} />
        <${FearlessLocks} locked=${locked} data=${data} />
        <div class="draft-actions">
          <${Button} icon="trash" onClick=${reset} disabled=${picks === 0 && view.bans.length === 0 && !fearlessContext}>Draft leeren<//>
          <span class="muted small">${sourceLabel}</span>
        </div>
        <${ClientSync} lcu=${client.lcu} onToggle=${client.toggle} />
        <${FearlessPanel} fearless=${fearless} view=${view} setView=${setView} data=${data} boardComplete=${picks === 2 * TEAM_SIZE}
          lcuStatus=${client.lcu?.status} conflicts=${client.champSelect ? lockedOnBoard : NO_LOCKS} />
      </div>
      <${TeamColumn} title="Gegner" team="enemy" slots=${view.enemy} result=${result} data=${data} pending=${pendingRole} hints=${hints.enemy}
        onPick=${(index) => setView({ picker: { team: 'enemy', index } })} onRole=${setSlotRole} onClear=${clearSlot} />
    </section>

    ${error ? html`<${ErrorNote} prefix="Draft konnte nicht berechnet werden" error=${error} />` : null}
    ${loading || completing ? html`<${ProgressNote} requestId=${requestId} completing=${completing} />` : null}
    ${result && result.warnings.length ? html`<ul class="draft-warnings">
      ${result.warnings.map((w, i) => html`<li key=${i}><${Icon} name="alert" size=${14} /> ${w}</li>`)}
    </ul>` : null}

    <div class="draft-tabs">
      <${Segmented} label="Bereich" value=${view.tab} onChange=${(tab) => setView({ tab })}
        options=${[{ value: 'picks', label: 'Beste Picks' }, { value: 'analysis', label: 'Draft-Analyse' }, { value: 'archive', label: 'Archiv' }]} />
      <div class="draft-options">
        <label class="draft-option" data-tip="Wie sehr Matchups und Synergien mit wenigen Spielen zählen (auch Champion-Werte im aktuellen Patch). Mittel entspricht DraftGap.">
          <span class="muted small">Risiko</span>
          <${Select} label="Risiko" value=${view.risk} options=${RISKS} onChange=${(risk) => setView({ risk })} />
        </label>
        ${view.rarePicks ? html`<label class="draft-option" data-tip=${advancedTips ? 'Mindestspiele eines Vorschlags auf seiner Rolle in den letzten 30 Tagen. Auto richtet sich nach der Spielmenge des Rang-Bereichs; in Emerald+ entspricht das etwa dem Standard von DraftGap.' : undefined}>
          <span class="muted small">Games</span>
          <${Select} label="Mindestspiele" value=${view.minGames} onChange=${(minGames) => setView({ minGames })}
            options=${[{ value: AUTO_MIN_GAMES, label: autoLabel(result, true) }, ...MIN_GAMES]} />
        </label>` : html`<label class="draft-option" data-tip=${advancedTips ? 'Mindest-Pickrate eines Vorschlags auf seiner Rolle in den letzten 30 Tagen, in jedem Rang-Bereich gleich streng. Höher heißt weniger exotische Picks.' : undefined}>
          <span class="muted small">Pickrate</span>
          <${Select} label="Mindest-Pickrate" value=${view.minPickRate || AUTO_MIN_GAMES} onChange=${(minPickRate) => setView({ minPickRate })}
            options=${[{ value: AUTO_MIN_GAMES, label: autoLabel(result, false) }, ...MIN_PICK_RATES]} />
        </label>`}
        <${Switch} checked=${view.ignoreChampionWinRates} onChange=${(ignoreChampionWinRates) => setView({ ignoreChampionWinRates })}
          tip=${advancedTips ? 'Bewertet nur Synergien und Matchups, nicht die allgemeine Stärke der Champions' : undefined}>Nur Synergien und Matchups<//>
        <${Switch} checked=${Boolean(view.rarePicks)} onChange=${(rarePicks) => setView({ rarePicks })}
          tip=${advancedTips ? 'Bisheriges Verhalten: auch Off-Role-Picks wie Tahm Kench ADC, Filter nur nach Spielzahl. Sonst braucht ein Vorschlag mindestens 10 % der Spiele seines Champions auf dieser Rolle und die gewählte Pickrate.' : undefined}>Seltene Picks zeigen<//>
      </div>
    </div>

    ${view.tab === 'archive' ? html`<${Archive} data=${data} view=${view} setView=${setView} />`
      : !result ? (loading ? null : html`<${Empty} title="Noch keine Daten" />`)
      : view.tab === 'picks'
        ? html`<${Suggestions} result=${result} data=${data} view=${view} setView=${setView} onTake=${takeSuggestion} busy=${loading} owned=${owned} />`
        : html`<${Analysis} result=${result} data=${data} picks=${picks} />`}

    ${view.picker ? html`<${ChampionPicker} data=${data} used=${used} locked=${locked}
      title=${view.picker.team === 'bans' ? 'Champion bannen' : view.picker.team === 'ally' ? 'Pick für dein Team' : 'Pick des Gegners'}
      onChoose=${(champion) => assign(view.picker, champion)} onClose=${() => setView({ picker: null })} />` : null}
  </div>`;
}

function ProgressNote({ requestId, completing }) {
  const progress = useStore((s) => s.draftProgress);
  const loads = progress && progress.requestId === requestId && progress.done < progress.total;
  const counter = loads ? ` ${progress.done} von ${progress.total}` : '';
  const text = completing
    ? `Vorschläge sind aktuell, die Analyse wird vervollständigt …${counter}`
    : loads
      ? `Lade Daten von Lolalytics …${counter} (neue Daten brauchen je etwa 2 Sekunden)`
      : 'Berechne Draft …';
  return html`<${Loading} text=${text} />`;
}

// ------------------------------------------------------------------ Eingabe

const sharePercent = (share) => (share > 0 && share < 0.005 ? '<1 %' : `${Math.round((share || 0) * 100)} %`);

/**
 * Rolle eines Picks: Ohne Vorgabe gilt die häufigste Rolle des Champions (bei Doppelbelegung die wahrscheinlichste
 * Verteilung im Team). Ein Klick öffnet die Auswahl; danach lädt nur dieser Pick seine Daten neu.
 */
function RolePicker({ champion, slot, prediction, shares, pending, onChoose }) {
  const [open, setOpen] = useState(false);
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const box = useRef();
  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const outside = (e) => !box.current?.contains(e.target) && setOpen(false);
    const escape = (e) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  const auto = !slot.role;
  const role = slot.role || prediction?.role || null;
  const choose = (value) => {
    setOpen(false);
    if (value !== (slot.role || '')) {
      onChoose(value);
    }
  };
  const tip = !role ? 'Rolle wird ermittelt'
    : auto ? `Automatisch: ${roleLabel(role)}${shares ? `, ${sharePercent(shares[role])} der Spiele` : ''}. Klicken, um eine andere Rolle zu wählen.`
      : `Fest gewählt: ${roleLabel(role)}. Klicken zum Ändern.`;
  return html`<div class="draft-role" ref=${box}>
    <button type="button" class=${`draft-role-chip ${auto ? 'auto' : 'fixed'}`} aria-haspopup="menu" aria-expanded=${open}
      aria-label=${`Rolle von ${champion.name}: ${role ? roleLabel(role) : 'wird ermittelt'}${auto ? ', automatisch' : ''}`}
      data-tip=${open || !advancedTips ? undefined : tip} onClick=${() => setOpen(!open)}>
      ${pending ? html`<${Spinner} small />` : null}
      <span>${role ? roleLabel(role) : '…'}</span>
      ${auto && role ? html`<span class="draft-role-auto">Auto</span>` : null}
      <span class="draft-role-caret" aria-hidden="true">▾</span>
    </button>
    ${open ? html`<div class="draft-role-menu" role="menu" aria-label=${`Rolle von ${champion.name}`}>
      ${ROLES.map((r) => html`<button type="button" key=${r.value} role="menuitemradio" aria-checked=${role === r.value}
        class=${`${role === r.value ? 'active' : ''} ${shares && shares[r.value] < 0.02 ? 'rare' : ''}`}
        onClick=${() => choose(r.value)}>
        <span>${r.label}</span>${shares ? html`<span class="small">${sharePercent(shares[r.value])}</span>` : null}
      </button>`)}
      ${!auto ? html`<button type="button" role="menuitem" class="draft-role-reset" onClick=${() => choose('')}>Automatisch</button>` : null}
    </div>` : null}
  </div>`;
}

function TeamColumn({ title, team, slots, result, data, pending, hints = [], onPick, onRole, onClear }) {
  const predicted = new Map((result?.slots[team] || []).map((s) => [s.champion, s]));
  const favorites = useFavorites();
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  return html`<section class=${`draft-team ${team}`}>
    <h3>${title}</h3>
    ${slots.map((slot, index) => {
      if (!slot) {
        // Aus dem Client: Ansage oder Hover eines eigenen Mitspielers, der noch nicht gesperrt hat.
        const hint = hints[index];
        const intent = hint && hint.intent ? data.championByKey.get(hint.intent) : null;
        const label = intent ? `${hint.picking ? 'Zeigt' : 'Ansage'}: ${intent.name}`
          : hint && hint.picking ? 'Wählt gerade …' : 'Champion wählen';
        return html`<button type="button" key=${index} class=${`draft-slot empty-slot ${hint && hint.picking ? 'picking' : ''}`}
          onClick=${() => onPick(index)} data-tip=${intent ? 'Noch nicht gesperrt, zählt nicht zur Analyse' : undefined}>
          ${intent ? html`<span class="draft-intent"><${Img} src=${intent.icon} size=${40} /></span>`
            : html`<span class="draft-plus"><${Icon} name="plus" size=${18} /></span>`}
          <span class="muted">${label}</span>${intent && favorites.has(intent.key) ? html`<${FavoriteMark} size=${13} />` : null}
          ${hint && hint.local ? html`<${Tag} kind="soft">Du<//>` : null}
        </button>`;
      }
      const champion = data.championByKey.get(slot.champion);
      // Die Antwort kann noch zum vorherigen Draft gehören: nur verwenden, wenn sie die gewählte Rolle schon kennt.
      const prediction = predicted.get(slot.champion);
      const current = prediction && (!slot.role || prediction.role === slot.role) ? prediction : null;
      const favorite = favorites.has(slot.champion);
      // Eigenes Team: Build (Match-Statistik); Gegner: beste Picks dagegen (Counters).
      const ally = team === 'ally';
      const role = slot.role || current?.role || null;
      return html`<div key=${index} class=${`draft-slot ${favorite ? 'favorite' : ''}`}>
        <button type="button" class="icon-link draft-slot-icon" onClick=${() => openChampion(champion, ally ? 'stats' : 'counters', role)}
          aria-label=${`${champion.name}: ${ally ? 'Match-Statistik' : 'Counters'} öffnen`}
          data-tip=${!advancedTips ? undefined : ally ? `Build von ${champion.name} öffnen (Match-Statistik)` : `Counters gegen ${champion.name} öffnen`}>
          <${Img} src=${champion.icon} size=${40} />
        </button>
        <button type="button" class="draft-slot-champion" onClick=${() => onPick(index)} data-tip=${advancedTips ? 'Champion ändern' : undefined}>
          <span class="draft-name">${champion.name}</span>
          ${favorite ? html`<${FavoriteMark} size=${14} />` : null}
        </button>
        <${RolePicker} champion=${champion} slot=${slot} prediction=${current} shares=${prediction?.shares}
          pending=${pending === `${team}:${slot.champion}`} onChoose=${(role) => onRole(team, index, role)} />
        <${Button} icon="x" kind="icon" title=${`${champion.name} entfernen`} onClick=${() => onClear(team, index)} />
      </div>`;
    })}
  </section>`;
}

function BanRow({ bans, data, onAdd, onRemove }) {
  const favorites = useFavorites();
  return html`<div class="draft-bans" aria-label="Bans">
    <span class="muted small">Bans</span>
    ${bans.map((key) => {
      const champion = data.championByKey.get(key);
      const favorite = favorites.has(key);
      return html`<button type="button" key=${key} class=${`draft-ban ${favorite ? 'favorite' : ''}`} onClick=${() => onRemove(key)}
        data-tip=${`${champion.name}${favorite ? ' (Favorit)' : ''}: Ban entfernen`}>
        <${Img} src=${champion.icon} size=${30} />${favorite ? html`<${FavoriteMark} size=${11} tip="" />` : null}
      </button>`;
    })}
    ${bans.length < MAX_BANS ? html`<button type="button" class="draft-ban add" onClick=${onAdd} aria-label="Ban hinzufügen"><${Icon} name="plus" size=${15} /></button>` : null}
  </div>`;
}

function ChampionPicker({ data, used, locked, title, onChoose, onClose }) {
  const [query, setQuery] = useState('');
  const favorites = useFavorites();
  const input = useRef();
  useEffect(() => input.current.focus(), []);
  const matches = useMemo(() => {
    const q = normalize(query);
    return [...data.champions]
      .filter((c) => c.name.toLowerCase().includes(q))
      .sort((a, b) => a.name.localeCompare(b.name, 'de'));
  }, [query, data]);
  const onKeyDown = (e) => {
    if (e.key === 'Escape') {
      onClose();
    } else if (e.key === 'Enter') {
      const first = matches.find((c) => !used.has(c.key) && !locked.has(c.key));
      if (first) {
        onChoose(first.key);
      }
    }
  };
  return html`<div class="overlay" onMouseDown=${(e) => e.target === e.currentTarget && onClose()}>
    <div class="modal draft-picker" role="dialog" aria-modal="true" aria-label=${title} onKeyDown=${onKeyDown}>
      <header class="modal-head"><h2>${title}</h2><${Button} icon="x" kind="icon" title="Schließen" onClick=${onClose} /></header>
      <label class="search">
        <${Icon} name="search" size=${16} />
        <input ref=${input} type="search" value=${query} spellcheck="false" placeholder="Champion suchen, Enter wählt den ersten"
          onInput=${(e) => setQuery(e.currentTarget.value)} />
      </label>
      <div class="draft-picker-grid">
        ${matches.map((c) => {
          // Fearless-Sperre: nicht wählbar, aber mit Tooltip (deaktivierte Buttons zeigen keinen).
          const game = locked.get(c.key);
          return html`<button type="button" key=${c.key} disabled=${used.has(c.key)} aria-disabled=${game ? 'true' : undefined}
            onClick=${() => !game && onChoose(c.key)} class=${`${favorites.has(c.key) ? 'favorite' : ''} ${game ? 'locked' : ''}`}
            data-tip=${game ? `${c.name}: in Spiel ${game} gespielt (Fearless)` : undefined}>
            <${Img} src=${c.icon} size=${48} /><span>${c.name}</span>
            ${favorites.has(c.key) ? html`<${FavoriteMark} size=${13} />` : null}
            ${game ? html`<span class="draft-lock-mark"><${Icon} name="lock" size=${12} /></span>` : null}
          </button>`;
        })}
      </div>
    </div>
  </div>`;
}

// ------------------------------------------------------------------ Winrate

function verdict(winRate) {
  if (winRate >= 0.58) {
    return { text: 'Deutlich im Vorteil', tone: 'good' };
  }
  if (winRate >= 0.53) {
    return { text: 'Leicht im Vorteil', tone: 'good' };
  }
  if (winRate > 0.47) {
    return { text: 'Ausgeglichen', tone: '' };
  }
  if (winRate > 0.42) {
    return { text: 'Leicht im Nachteil', tone: 'bad' };
  }
  return { text: 'Deutlich im Nachteil', tone: 'bad' };
}

function WinPanel({ analysis, picks, loading, completing }) {
  if (!analysis) {
    return html`<div class="draft-win"><p class="muted">${loading ? 'Lade …' : 'Keine Daten'}</p></div>`;
  }
  const allyShare = analysis.winRate * 100;
  const state = verdict(analysis.winRate);
  return html`<div class="draft-win">
    <div class="draft-win-numbers">
      <strong class="draft-win-value">${percent(analysis.winRate)}</strong>
      <span class="draft-win-label"><span class=${state.tone}>${state.text}</span>
        <small class="muted">${completing ? 'Analyse wird vervollständigt …' : picks === 10 ? 'Vollständiger Draft' : `Vorläufig: ${picks} von 10 Picks`}</small></span>
    </div>
    <div class="draft-win-bar" role="img" aria-label=${`Dein Team ${allyShare.toFixed(1)} Prozent, Gegner ${(100 - allyShare).toFixed(1)} Prozent`}>
      <span class="ally" style=${{ width: `${allyShare}%` }}></span>
      <span class="enemy" style=${{ width: `${100 - allyShare}%` }}></span>
    </div>
    <div class="draft-win-parts">
      <${Part} label="Champions" rating=${analysis.championRating} question=${PART_QUESTIONS.champion} tip="Allgemeine Stärke der Champions auf ihren Rollen" />
      <${Part} label="Synergien" rating=${analysis.duoRating} question=${PART_QUESTIONS.duo} tip="Eigene Team-Synergien minus die des Gegners" />
      <${Part} label="Matchups" rating=${analysis.matchupRating} question=${PART_QUESTIONS.matchup} tip="Alle Paarungen eigener Champions gegen gegnerische" />
    </div>
  </div>`;
}

/** Die Frage steht immer im Tooltip, die genaue Berechnung nur mit „Show Advanced Tooltips“. */
function Part({ label, rating, question, tip }) {
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  return html`<div class="draft-part" data-tip=${advancedTips ? `${question} ${tip}.` : question}>
    <span class="muted small">${label}</span><strong class=${toneOf(impact(rating))}>${impactText(rating)}</strong>
  </div>`;
}

// ------------------------------------------------------------------ Vorschläge

/** Spalten der Tabelle „Weitere Picks“; Klick auf den Kopf sortiert, Zahlen zuerst absteigend. */
function suggestionColumns(data) {
  return [
    { key: 'rank', title: '#', firstDir: 1, tip: 'Rang in der Gesamtwertung', value: (s) => s.rank },
    { key: 'name', title: 'Champion', firstDir: 1, value: (s) => data.championByKey.get(s.champion)?.name ?? String(s.champion) },
    { key: 'role', title: 'Rolle', firstDir: 1, value: (s) => ROLES.findIndex((r) => r.value === s.role) },
    { key: 'winRate', title: 'Team-WR', tip: 'Winrate des Teams mit diesem Pick', value: (s) => s.winRate },
    { key: 'delta', title: 'Δ', tip: 'Änderung gegenüber dem Draft ohne diesen Pick', value: (s) => s.delta },
    { key: 'championRating', title: 'Champion', tip: `${PART_QUESTIONS.champion} Beitrag der Champion-Stärke.`, value: (s) => s.championRating },
    { key: 'duoRating', title: 'Synergien', tip: `${PART_QUESTIONS.duo} Beitrag der Synergien mit dem eigenen Team.`, value: (s) => s.duoRating },
    { key: 'matchupRating', title: 'Matchups', tip: `${PART_QUESTIONS.matchup} Beitrag der Matchups gegen den Gegner.`, value: (s) => s.matchupRating },
    { key: 'pickRate', title: 'Pickrate', tip: 'Pickrate auf dieser Rolle in den letzten 30 Tagen', value: (s) => s.pickRate },
    { key: 'games', title: 'Spiele', tip: 'Spiele auf dieser Rolle in den letzten 30 Tagen', value: (s) => s.games },
  ];
}

function Suggestions({ result, data, view, setView, onTake, busy, owned }) {
  const openRoles = ROLES.filter((r) => result.openRoles.includes(r.value));
  const role = view.role && result.openRoles.includes(view.role) ? view.role : '';
  // Aus dem Client bekannt, welche Champions der eigene Account spielen darf; gilt nur für Vorschläge ans eigene Team.
  const canFilterOwned = owned.size > 0 && result.side === 'ally';
  const ownedOnly = canFilterOwned && view.ownedOnly !== false;
  const rows = useMemo(() => result.suggestions.filter((s) => (!role || s.role === role) && (!ownedOnly || owned.has(s.champion))),
    [result, role, ownedOnly, owned]);
  const top = rows.slice(0, TOP_PICKS);
  const columns = useMemo(() => suggestionColumns(data), [data]);
  const sort = activeSort(columns, { key: view.restSort || 'rank', dir: view.restDir || 1 });
  // Sortiert werden alle übrigen Vorschläge, angezeigt die ersten; der Rang bleibt der aus der Gesamtwertung.
  const rest = useMemo(() => sortRows(rows.slice(TOP_PICKS).map((s, i) => ({ ...s, rank: i + TOP_PICKS + 1 })), columns, sort)
    .slice(0, MAX_TABLE_ROWS), [rows, columns, sort.key, sort.dir]);
  const headCell = (column) => {
    const active = sort.key === column.key;
    const next = active ? -sort.dir : (column.firstDir || -1);
    return html`<th key=${column.key} aria-sort=${active ? (sort.dir === 1 ? 'ascending' : 'descending') : 'none'}>
      <button type="button" class=${`sort-head ${active ? 'active' : ''}`} data-tip=${column.tip}
        onClick=${() => setView({ restSort: column.key, restDir: next })}>
        <span>${column.title}</span>${active ? html`<${Icon} name=${sort.dir === 1 ? 'up' : 'down'} size=${12} />` : null}
      </button>
    </th>`;
  };
  const advancedTips = useStore((st) => Boolean(st.prefs.advancedTips));
  const favorites = useFavorites();
  const sideText = result.side === 'ally' ? 'dein Team' : 'den Gegner';
  const baseText = result.side === 'ally' ? 'deines Teams' : 'des Gegners';

  return html`<div class=${`draft-suggestions ${busy ? 'busy' : ''}`}>
    <div class="draft-filters">
      <${Segmented} label="Empfehlungen für" value=${view.side} onChange=${(side) => setView({ side })}
        options=${[{ value: 'ally', label: 'Für mein Team' }, { value: 'enemy', label: 'Für den Gegner' }]} />
      <${Segmented} label="Rolle" value=${role} onChange=${(value) => setView({ role: value })}
        options=${[{ value: '', label: 'Alle offenen' }, ...openRoles]} />
      ${canFilterOwned ? html`<${Switch} checked=${ownedOnly} onChange=${(value) => setView({ ownedOnly: value })}
        tip="Laut League-Client in dieser Auswahl spielbar (eigene und kostenlose Champions)">Nur spielbare Champions<//>` : null}
    </div>
    ${rows.length === 0 ? html`<${Empty} title="Keine Vorschläge">
      ${result.openRoles.length === 0 ? 'Dieses Team ist vollständig.' : view.rarePicks ? 'Senke die Mindestspiele oder wähle eine andere Rolle.' : 'Senke die Pickrate, zeige seltene Picks oder wähle eine andere Rolle.'}<//>` : html`
      <h3 class="draft-section-title">Top ${top.length} für ${sideText}${role ? ` auf ${roleLabel(role)}` : ''}</h3>
      <div class="draft-top">
        ${top.map((s, i) => html`<${TopCard} key=${`${s.champion}-${s.role}`} rank=${i + 1} suggestion=${s} data=${data} onTake=${onTake} baseText=${baseText}
          favorite=${favorites.has(s.champion)} />`)}
      </div>
      ${rest.length ? html`<${Card} title="Weitere Picks">
        <div class="grid-scroll"><table class="data draft-table">
          <thead><tr>${columns.map(headCell)}</tr></thead>
          <tbody>${rest.map((s) => {
            const champion = data.championByKey.get(s.champion);
            const favorite = favorites.has(s.champion);
            return html`<tr key=${`${s.champion}-${s.role}`} class=${`draft-row ${favorite ? 'favorite' : ''}`} onClick=${() => onTake(s)} data-tip=${advancedTips ? 'Pick übernehmen' : undefined}>
              <td class="muted">${s.rank}</td>
              <td><span class="draft-cell-champion">${champion ? html`<${Img} src=${champion.icon} size=${26} />` : null}${champion?.name || s.champion}${favorite ? html`<${FavoriteMark} size=${13} />` : null}</span></td>
              <td>${roleLabel(s.role)}</td><td><strong>${percent(s.winRate)}</strong></td>
              <td class=${toneOf(s.delta * 100)}>${signed(s.delta * 100)}</td>
              <td class=${toneOf(impact(s.championRating))}>${impactText(s.championRating)}</td>
              <td class=${toneOf(impact(s.duoRating))}>${impactText(s.duoRating)}</td>
              <td class=${toneOf(impact(s.matchupRating))}>${impactText(s.matchupRating)}</td>
              <td class="muted" data-tip=${advancedTips ? `${percent(s.laneShare)} der Spiele von ${champion?.name || s.champion} auf dieser Rolle` : undefined}>${percent(s.pickRate, 2)}</td>
              <td class="muted">${count(s.games)}</td>
            </tr>`;
          })}</tbody>
        </table></div>
      <//>` : null}`}
  </div>`;
}

function TopCard({ rank, suggestion: s, data, onTake, baseText, favorite }) {
  const advancedTips = useStore((st) => Boolean(st.prefs.advancedTips));
  const champion = data.championByKey.get(s.champion);
  const favorites = useFavorites();
  const details = [...s.details].sort((a, b) => Math.abs(b.rating) - Math.abs(a.rating)).slice(0, 3);
  return html`<button type="button" class=${`draft-top-card ${rank === 1 ? 'best' : ''} ${favorite ? 'favorite' : ''}`} onClick=${() => onTake(s)}
    data-tip=${advancedTips ? `Pick übernehmen · Winrate ${baseText} mit diesem Pick` : undefined}>
    <span class="draft-rank">${rank}</span>
    <span class="draft-top-head">
      ${champion ? html`<${Img} src=${champion.icon} size=${48} />` : null}
      <span class="draft-top-name"><strong>${champion?.name || s.champion}${favorite ? html` <${FavoriteMark} size=${14} />` : null}</strong><${Tag} kind="soft">${roleLabel(s.role)}<//></span>
    </span>
    <span class="draft-top-value">${percent(s.winRate)}<small class=${toneOf(s.delta * 100)}>${signed(s.delta * 100)}</small></span>
    <span class="draft-top-parts">
      <span data-tip=${PART_QUESTIONS.champion}>Champion <b class=${toneOf(impact(s.championRating))}>${impactText(s.championRating)}</b></span>
      <span data-tip=${PART_QUESTIONS.duo}>Synergien <b class=${toneOf(impact(s.duoRating))}>${impactText(s.duoRating)}</b></span>
      <span data-tip=${PART_QUESTIONS.matchup}>Matchups <b class=${toneOf(impact(s.matchupRating))}>${impactText(s.matchupRating)}</b></span>
    </span>
    ${details.length ? html`<span class="draft-top-details">
      ${details.map((d) => {
        const other = data.championByKey.get(d.champion);
        const otherFavorite = favorites.has(d.champion);
        return html`<span key=${`${d.kind}-${d.champion}`} class=${`draft-chip ${otherFavorite ? 'favorite' : ''}`}
          data-tip=${`${d.kind === 'duo' ? 'Mit' : 'Gegen'} ${other?.name}${otherFavorite ? ' (Favorit)' : ''}: ${d.winRate === null ? 'keine Spiele' : `${percent(d.winRate)} in ${count(d.games)} Spielen`}`}>
          ${other ? html`<${Img} src=${other.icon} size=${20} />` : null}<b class=${toneOf(impact(d.rating))}>${impactText(d.rating)}</b>
        </span>`;
      })}
    </span>` : null}
  </button>`;
}

// ------------------------------------------------------------------ Analyse

/**
 * Kompakte Gesamteinschätzung: Urteil, Winrate und Einfluss je Bereich in einer Leiste, darunter wer ab welcher
 * Minute vorne liegt. Matchups und Synergien stehen in ihren eigenen Karten.
 */
function Assessment({ result, picks }) {
  const { analysis, profiles } = result;
  const state = verdict(analysis.winRate);
  const parts = [['Champions', analysis.championRating, PART_QUESTIONS.champion], ['Synergien', analysis.duoRating, PART_QUESTIONS.duo],
    ['Matchups', analysis.matchupRating, PART_QUESTIONS.matchup]]
    .filter(([, rating]) => Math.abs(impact(rating)) >= 0.05)
    .sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]));
  const trend = Math.min(profiles.ally.champions, profiles.enemy.champions) >= MIN_PICKS_FOR_PROFILE ? lengthTrend(profiles) : null;
  const leads = trend ? lengthPhases(trend, lengthStarts(profiles, trend.length)).filter((phase) => phase.side !== 'even') : [];
  const value = (rating) => html`<strong class=${`draft-key-value ${toneOf(impact(rating))}`}>${impactText(rating)}</strong>`;
  return html`<${Card} title="Gesamteinschätzung" cls="draft-assessment">
    <div class="draft-key-head">
      <span class=${`draft-key-verdict ${state.tone}`}>${state.text}</span>
      <strong class="draft-key-winrate">${percent(analysis.winRate)}</strong>
      ${picks < 10 ? html`<span class="muted small">vorläufig, ${picks} von 10 Picks</span>` : null}
      ${parts.length ? html`<span class="draft-key-facts">${parts.map(([label, rating, question]) => html`<span key=${label} data-tip=${question}><span class="muted small">${label}</span> ${value(rating)}</span>`)}</span>` : null}
    </div>
    ${leads.length ? html`<div class="draft-key-length"><span class="muted small">Spieldauer</span>
      ${leads.map((phase, i) => html`<span key=${i} class=${`draft-key-phase ${phase.side}`}>${minuteRange(phase).replace(/^./, (c) => c.toUpperCase())}: <strong>${phase.side === 'ally' ? 'Dein Team' : 'Gegner'}</strong></span>`)}
    </div>` : null}
  <//>`;
}

function lengthTrend({ ally, enemy }) {
  const size = Math.max(ally.ratingByLength.length, enemy.ratingByLength.length);
  if (size === 0 || ally.champions === 0 || enemy.champions === 0) {
    return null;
  }
  return Array.from({ length: size }, (_, i) => (ally.ratingByLength[i] || 0) - (enemy.ratingByLength[i] || 0));
}

/** Startminute je Spieldauer-Abschnitt vom Backend; `null`, wenn die Einteilung unbekannt ist. */
function lengthStarts({ ally, enemy }, size) {
  const starts = ally.lengthStartMinutes || enemy.lengthStartMinutes;
  return starts && starts.length === size ? starts : null;
}

/** Ab diesem Vorsprung (Winrate-Prozentpunkte) gilt ein Abschnitt nicht mehr als ausgeglichen. */
const LENGTH_LEAD_MIN = 0.2;

/**
 * Fasst aufeinanderfolgende Abschnitte mit demselben vorne liegenden Team zusammen. `from`/`to` sind Game-Minuten
 * (`to` = null: offenes Ende); ohne bekannte Minuten die Abschnittsnummern.
 */
function lengthPhases(values, starts) {
  const phases = [];
  values.forEach((value, i) => {
    const lead = impact(value);
    const side = lead >= LENGTH_LEAD_MIN ? 'ally' : lead <= -LENGTH_LEAD_MIN ? 'enemy' : 'even';
    const from = starts ? starts[i] : i + 1;
    const to = starts ? (i + 1 < starts.length ? starts[i + 1] : null) : i + 1;
    const last = phases[phases.length - 1];
    if (last && last.side === side) {
      last.to = to;
      last.peak = Math.abs(lead) > Math.abs(last.peak) ? lead : last.peak;
    } else {
      phases.push({ side, from, to, peak: lead, minutes: Boolean(starts) });
    }
  });
  return phases;
}

function minuteRange({ from, to, minutes }) {
  if (!minutes) {
    return from === to ? `Abschnitt ${from}` : `Abschnitt ${from}–${to}`;
  }
  if (to == null) {
    return from === 0 ? 'über die ganze Spieldauer' : `ab Minute ${from}`;
  }
  return from === 0 ? `bis Minute ${to}` : `Minute ${from}–${to}`;
}

/** Kurze Achsenbeschriftung eines Abschnitts in Game-Minuten. */
function minuteLabel(starts, i) {
  if (!starts) {
    return String(i + 1);
  }
  if (i === 0) {
    return `<${starts[1] ?? ''}`;
  }
  return i + 1 < starts.length ? `${starts[i]}–${starts[i + 1]}` : `${starts[i]}+`;
}

/**
 * Klappbarer Abschnitt der Analyse mit beschrifteter Trennlinie. Startet nach `defaultOpen`; der Stand liegt in
 * `store[storeKey]` und gilt auch beim Wechsel zwischen Tabs. Steht der Schlüssel in PERSISTED (`core.js`), bleibt er
 * über Neustarts erhalten (so `draftDetailsOpen` und `draftOverviewOpen`).
 */
function Section({ title, storeKey, defaultOpen = false, children }) {
  const open = useStore((s) => s[storeKey] ?? defaultOpen);
  return html`
    <button type="button" class="draft-divider" aria-expanded=${open} onClick=${() => store.set({ [storeKey]: !open })}>
      <${Icon} name="chevron" size=${16} /><span>${title}</span>
    </button>
    ${open ? children : null}`;
}

function Analysis({ result, data, picks }) {
  const { analysis, profiles } = result;
  if (picks === 0) {
    return html`<${Empty} title="Noch kein Draft">Sobald Champions gewählt sind, erscheint hier die Einschätzung.<//>`;
  }
  const trend = lengthTrend(profiles);
  return html`<div class="draft-analysis">
    <${Assessment} result=${result} picks=${picks} />
    <${Section} title="Matchups & Profil" storeKey="draftOverviewOpen" defaultOpen>
      <div class="draft-analysis-grid">
        <${MatchupCard} rows=${analysis.matchups} data=${data} />
        <${Card} title="Profil der Teams">
          <div class="draft-damage-pies">
            <${DamagePie} label="Dein Team" team="ally" profile=${profiles.ally} />
            <${DamagePie} label="Gegner" team="enemy" profile=${profiles.enemy} />
          </div>
          ${trend ? html`<${LengthChart} values=${trend} starts=${lengthStarts(profiles, trend.length)} />` : null}
        <//>
      </div>
    <//>
    <${Section} title="Champions & Synergien" storeKey="draftDetailsOpen">
      <div class="draft-analysis-grid three">
        <${Card} title="Champions">
          <${ChampionTable} label="Dein Team" rows=${analysis.allyChampions} data=${data} sign=${1} />
          <${ChampionTable} label="Gegner" rows=${analysis.enemyChampions} data=${data} sign=${-1} />
        <//>
        <${PairCard} title="Synergien deines Teams" rows=${analysis.allyDuos} data=${data} />
        <${PairCard} title="Synergien des Gegners" rows=${analysis.enemyDuos} data=${data} enemy />
      </div>
    <//>
  </div>`;
}

/**
 * Champions eines Teams nach Rolle (Top bis Support). „Δ Team-WR“ ist die Stärke gegenüber dem Rang-Durchschnitt aus Sicht deines Teams: bei
 * Gegnern mit umgekehrtem Vorzeichen (`sign` = −1), ein starker Gegner ist also rot. Beide Spalten zusammen ergeben
 * den Wert „Champions“ oben.
 */
function ChampionTable({ label, rows, data, sign }) {
  if (rows.length === 0) {
    return html`<p class="muted small">${label}: keine Werte</p>`;
  }
  return html`<div class="grid-scroll"><table class="data draft-table">
    <thead><tr><th class=${sign > 0 ? 'team-ally' : 'team-enemy'}>${label}</th><th>Rolle</th><th>WR</th><th data-tip=${deltaTip('champion')}>${DELTA_TITLE}</th><th>Spiele</th></tr></thead>
    <tbody>${[...rows].sort((a, b) => roleOrder(a.role) - roleOrder(b.role)).map((row) => {
      const rating = sign * row.rating;
      return html`<tr key=${row.champion}>
        <td><${ChampionCell} data=${data} champion=${row.champion} /></td><td>${roleLabel(row.role)}</td>
        <td>${percent(row.winRate)}</td><td class=${toneOf(impact(rating))}>${impactText(rating)}</td><td class="muted">${count(row.games)}</td>
      </tr>`;
    })}</tbody>
  </table></div>`;
}

function ChampionCell({ data, champion, size = 24 }) {
  const c = data.championByKey.get(champion);
  const favorite = useFavorites().has(champion);
  return html`<span class="draft-cell-champion">${c ? html`<${Img} src=${c.icon} size=${size} />` : null}${c?.name || champion}${favorite ? html`<${FavoriteMark} size=${12} />` : null}</span>`;
}

/** Sortierbare Spalten der Matchups; `value` bekommt die Zeile und die Seite des Gegenübers. */
const MATCHUP_COLUMNS = [
  { key: 'role', title: 'Gegenüber', firstDir: 1, value: (row, other) => roleOrder(row[other].role) },
  { key: 'winRate', title: 'WR', value: (row) => row.winRate },
  { key: 'rating', title: DELTA_TITLE, value: (row) => row.rating },
  { key: 'games', title: 'Spiele', value: (row) => row.games },
];

/**
 * Die beiden Tabs der Matchup-Karte. Der Gegner-Tab zeigt dieselben Paarungen aus Sicht des Gegners: Winrate als
 * 1 − WR, Wert mit umgekehrtem Vorzeichen.
 */
const MATCHUP_SIDES = {
  first: { other: 'second', team: 'ally', label: 'Dein Team', delta: DELTA_TITLE, sign: 1 },
  second: { other: 'first', team: 'enemy', label: 'Gegner', delta: 'Δ Gegner-WR', sign: -1 },
};

const matchupTip = (sign) => (sign > 0 ? deltaTip('matchup')
  : 'Wie der Gegner-Champion gegen deinen abschneidet, über die übliche Stärke beider hinaus. Angabe als Änderung der '
    + 'erwarteten Winrate des Gegners in Prozentpunkten: Grün hilft dem Gegner, Rot schadet ihm. Gegenstück zum Tab „Dein Team“.');

/**
 * Alle Matchups in zwei Tabs: „Dein Team“ gruppiert nach deinen Champions, „Gegner“ nach den Gegner-Champions aus
 * deren Sicht. Gruppen von Top bis Support, anfangs eingeklappt mit sichtbarem Lane-Matchup; die Spalten sortieren
 * innerhalb der Gruppen. Tab und Sortierung bleiben gespeichert.
 */
function MatchupCard({ rows, data }) {
  const [view, setView] = useViewState('draft-matchups', { side: 'first', sort: 'role', dir: 1 });
  const [expanded, setExpanded] = useState(() => new Set());
  const sideKey = view.side === 'second' ? 'second' : 'first';
  const s = MATCHUP_SIDES[sideKey];
  const column = MATCHUP_COLUMNS.find((c) => c.key === view.sort) || MATCHUP_COLUMNS[0];
  const shown = rows.map((row) => (s.sign > 0 ? row
    : { ...row, rating: -row.rating, winRate: row.winRate === null || row.winRate === undefined ? row.winRate : 1 - row.winRate }));
  const sorted = [...shown].sort((a, b) => {
    const result = (column.value(a, s.other) ?? 0) - (column.value(b, s.other) ?? 0);
    return result * view.dir || roleOrder(a[s.other].role) - roleOrder(b[s.other].role);
  });
  const groups = [...new Map(shown.map((row) => [row[sideKey].champion, row[sideKey]])).values()]
    .sort((a, b) => roleOrder(a.role) - roleOrder(b.role))
    .map((pick) => ({ key: `${sideKey}-${pick.champion}`, pick, rows: sorted.filter((row) => row[sideKey].champion === pick.champion) }));
  const keys = groups.map((g) => g.key);
  const allOpen = keys.length > 0 && keys.every((key) => expanded.has(key));
  const toggle = (key) => setExpanded((previous) => {
    const next = new Set(previous);
    next.has(key) ? next.delete(key) : next.add(key);
    return next;
  });

  const headCell = (c) => {
    const active = column.key === c.key;
    const next = active ? -view.dir : (c.firstDir || -1);
    const delta = c.key === 'rating';
    return html`<th key=${c.key} aria-sort=${active ? (view.dir === 1 ? 'ascending' : 'descending') : 'none'}>
      <button type="button" class=${`sort-head ${active ? 'active' : ''}`} data-tip=${delta ? matchupTip(s.sign) : undefined}
        onClick=${() => setView({ sort: c.key, dir: next })}>
        <span>${delta ? s.delta : c.title}</span>${active ? html`<${Icon} name=${view.dir === 1 ? 'up' : 'down'} size=${12} />` : null}
      </button>
    </th>`;
  };
  const pickCell = (pick) => html`<span class="draft-matchup-pick"><${ChampionCell} data=${data} champion=${pick.champion} />
    <span class="muted small">${roleLabel(pick.role)}</span></span>`;
  const row = (r, groupKey) => {
    const lane = r.first.role === r.second.role;
    return html`<tr key=${`${groupKey}-${r[s.other].champion}`} class=${`draft-group-item ${lane ? 'lane' : ''}`}>
      <td><span class="draft-matchup-vs"><span class="muted small">vs</span>${pickCell(r[s.other])}${lane ? html`<${Tag} kind="soft">Lane<//>` : null}</span></td>
      <td>${percent(r.winRate)}</td><td class=${toneOf(impact(r.rating))}>${impactText(r.rating)}</td><td class="muted">${count(r.games)}</td>
    </tr>`;
  };
  const group = (g) => {
    const open = expanded.has(g.key);
    // Die Summe aller Matchups des Picks nur aufgeklappt, eingeklappt stünde sie neben dem einzelnen Lane-Matchup.
    const total = g.rows.reduce((sum, r) => sum + r.rating, 0);
    // Eingeklappt bleibt das direkte Lane-Matchup sichtbar.
    const visible = open ? g.rows : g.rows.filter((r) => r.first.role === r.second.role);
    return [html`<tr key=${g.key} class=${`draft-group-head ${s.team}`}>
      <td colspan="2">
        <button type="button" class="draft-group-toggle" aria-expanded=${open} onClick=${() => toggle(g.key)}>
          <${Icon} name="chevron" size=${16} />${pickCell(g.pick)}<span class="muted small">${g.rows.length} Matchups</span>
        </button>
      </td>
      ${open ? html`<td class=${toneOf(impact(total))}><strong>${impactText(total)}</strong></td>` : html`<td></td>`}<td></td>
    </tr>`, ...visible.map((r) => row(r, g.key))];
  };

  return html`<${Card} title="Matchups">
    ${rows.length === 0 ? html`<p class="muted small">Noch keine Paarungen.</p>` : html`
      <div class="draft-matchup-tools">
        <${Segmented} label="Matchups von" value=${sideKey} onChange=${(side) => setView({ side })}
          options=${[{ value: 'first', label: 'Dein Team' }, { value: 'second', label: 'Gegner' }]} />
        <${Button} kind="ghost" onClick=${() => setExpanded(allOpen ? new Set() : new Set(keys))}>${allOpen ? 'Alle zuklappen' : 'Alle aufklappen'}<//>
      </div>
      <div class="grid-scroll"><table class=${`data draft-table draft-matchups ${s.team}`}>
        <thead><tr>${MATCHUP_COLUMNS.map(headCell)}</tr></thead>
        <tbody>${groups.flatMap(group)}</tbody>
      </table></div>`}
  <//>`;
}

/** So viele Paarungen je Team zeigt die eingeklappte Karte, die übrigen über „weitere anzeigen“. */
const MAX_DUOS = 8;
/** Sortierbare Spalten der Synergie-Karten; Klick auf den Kopf sortiert, Zahlen zuerst absteigend. */
const PAIR_COLUMNS = [
  { key: 'winRate', title: 'WR', value: (row) => row.winRate },
  { key: 'rating', title: DELTA_TITLE, value: (row) => row.rating },
  { key: 'games', title: 'Spiele', value: (row) => row.games },
];
const PAIR_DEFAULTS = { sort: 'rating', dir: -1 };

const ENEMY_DUO_TIP = 'Synergie: wie viel besser die beiden Gegner-Champions zusammen gewinnen, als ihre Einzelstärke erwarten '
  + 'lässt. Angabe aus Sicht des Gegners als Änderung seiner erwarteten Winrate in Prozentpunkten: Grün = starke Synergie '
  + 'beim Gegner, die dir schadet.';

/**
 * Duos eines Teams mit Winrate und Synergie-Wert, jeweils aus Sicht dieses Teams: beim Gegner „Δ Gegner-WR“, eine
 * starke Synergie ist dort also grün und positiv (wie im Gegner-Tab der Matchups). Sortierbar (Standard: stärkste
 * Synergie zuerst, Stand je Team bis zum Neustart); eingeklappt die ersten {@link MAX_DUOS} der Sortierung, aufsteigend
 * nach Δ also die schwächsten.
 */
function PairCard({ title, rows, data, enemy = false }) {
  const [view, setView] = useViewState(enemy ? 'draft-duos-enemy' : 'draft-duos-ally', PAIR_DEFAULTS);
  const [open, setOpen] = useState(false);
  const sort = { key: view.sort, dir: view.dir };
  const sorted = sortRows(rows, PAIR_COLUMNS, sort);
  const shown = open ? sorted : sorted.slice(0, MAX_DUOS);
  const headCell = (c) => {
    const active = sort.key === c.key;
    const delta = c.key === 'rating';
    return html`<th key=${c.key} aria-sort=${active ? (sort.dir === 1 ? 'ascending' : 'descending') : 'none'}>
      <button type="button" class=${`sort-head ${active ? 'active' : ''}`} data-tip=${delta ? (enemy ? ENEMY_DUO_TIP : deltaTip('duo')) : undefined}
        onClick=${() => setView({ sort: c.key, dir: active ? -sort.dir : -1 })}>
        <span>${delta && enemy ? 'Δ Gegner-WR' : c.title}</span>${active ? html`<${Icon} name=${sort.dir === 1 ? 'up' : 'down'} size=${12} />` : null}
      </button>
    </th>`;
  };
  return html`<${Card} title=${title}>
    ${rows.length === 0 ? html`<p class="muted small">Noch keine Paarungen.</p>` : html`<div class="grid-scroll"><table class="data draft-table draft-pairs">
      <thead><tr><th>Paarung</th>${PAIR_COLUMNS.map(headCell)}</tr></thead>
      <tbody>${shown.map((row) => html`<tr key=${`${row.first.champion}-${row.second.champion}`}>
        <td><span class="draft-pair"><${ChampionCell} data=${data} champion=${row.first.champion} /><span class="muted">+</span><${ChampionCell} data=${data} champion=${row.second.champion} /></span></td>
        <td>${percent(row.winRate)}</td><td class=${toneOf(impact(row.rating))}>${impactText(row.rating)}</td><td class="muted">${count(row.games)}</td>
      </tr>`)}</tbody>
    </table></div>
    ${rows.length > MAX_DUOS ? html`<${MoreToggle} open=${open} total=${rows.length} top=${MAX_DUOS} onToggle=${() => setOpen(!open)} />` : null}`}
  <//>`;
}

const DAMAGE_TYPES = [['physical', 'Physisch'], ['magic', 'Magisch'], ['true', 'True']];

/** Schadensaufteilung eines Teams als Ring: Physisch rot, Magisch blau, True grau. */
function DamagePie({ label, team, profile }) {
  const total = DAMAGE_TYPES.reduce((sum, [type]) => sum + (profile[type] || 0), 0);
  const shares = DAMAGE_TYPES.map(([type, name]) => ({ type, name, share: total > 0 ? (profile[type] || 0) / total : 0 }));
  const text = (share) => (total > 0 ? `${Math.round(share * 100)} %` : '–');
  // Umfang 100: Anteile in Prozent sind direkt die Strichlängen. Start oben (Versatz 25), im Uhrzeigersinn.
  let offset = 25;
  return html`<div class="draft-pie">
    <svg viewBox="0 0 42 42" class="draft-pie-chart" role="img"
      aria-label=${`${label}: ${shares.map((s) => `${s.name} ${text(s.share)}`).join(', ')}`}>
      <circle class="draft-pie-track" cx="21" cy="21" r="15.915" />
      ${total > 0 ? shares.filter((s) => s.share > 0).map((s) => {
        const length = s.share * 100;
        const slice = html`<circle key=${s.type} class=${`draft-pie-slice dmg-${s.type}`} cx="21" cy="21" r="15.915"
          stroke-dasharray=${`${length} ${100 - length}`} stroke-dashoffset=${offset}><title>${s.name}: ${text(s.share)}</title></circle>`;
        offset -= length;
        return slice;
      }) : null}
    </svg>
    <div class="draft-pie-legend">
      <strong class=${`team-${team}`}>${label}</strong>
      ${shares.map((s) => html`<span key=${s.type}><i class=${`dmg-${s.type}`}></i><span class="muted small">${s.name}</span> ${text(s.share)}</span>`)}
    </div>
  </div>`;
}

/**
 * Vorsprung je Champion (Winrate-Prozentpunkte) je Spieldauer-Abschnitt in Game-Minuten; Säulen nach oben für dein
 * Team, nach unten für den Gegner. Darunter, ab welcher Minute welches Team vorne liegt.
 */
function LengthChart({ values, starts }) {
  const peak = Math.max(10, ...values.map((v) => Math.abs(v)));
  const phases = lengthPhases(values, starts);
  const sideText = { ally: 'Dein Team vorne', enemy: 'Gegner vorne', even: 'ausgeglichen' };
  const where = (i) => (starts ? `${minuteLabel(starts, i)} Min.` : `Abschnitt ${i + 1} von ${values.length}`);
  return html`<div class="draft-length">
    <div class="draft-length-head">
      <h4>Vorsprung je Champion nach Spielminute</h4>
      <span class="draft-length-legend ally"><i></i>Dein Team ▲</span>
      <span class="draft-length-legend enemy"><i></i>Gegner ▼</span>
    </div>
    <div class="draft-length-plot" role="img" aria-label=${`Vorsprung je Spieldauer in Punkten: ${values.map((v, i) => `${where(i)} ${signed(impact(v))}`).join(', ')}`}>
      ${values.map((value, i) => {
        const height = (Math.abs(value) / peak) * 50;
        const tip = `${where(i)}: ${signed(impact(value))} Punkte ${value >= 0 ? 'für dein Team' : 'für den Gegner'}`;
        return html`<div key=${i} class="draft-length-column" data-tip=${tip}>
          <span class=${value >= 0 ? 'ally' : 'enemy'} style=${{ height: `${height}%` }}></span>
        </div>`;
      })}
    </div>
    <div class="draft-length-ticks muted small">${values.map((_, i) => html`<span key=${i}>${minuteLabel(starts, i)}</span>`)}</div>
    <div class="draft-length-axis muted small"><span>${starts ? 'Spielminute' : 'kurze Spiele'}</span><span>${starts ? '' : 'lange Spiele'}</span></div>
    <ul class="draft-length-phases">
      ${phases.map((phase, i) => html`<li key=${i} class=${phase.side}>
        <span class="draft-length-range">${minuteRange(phase).replace(/^./, (c) => c.toUpperCase())}</span>
        <span>${sideText[phase.side]}${phase.side === 'even' ? '' : html` <span class="muted small">(bis ${signed(Math.abs(phase.peak))} Punkte)</span>`}</span>
      </li>`)}
    </ul>
  </div>`;
}

// ------------------------------------------------------------------ Archiv: Ansicht

const ARCHIVE_DATE = { dateStyle: 'short', timeStyle: 'short' };
const patchLabel = (patch) => (patch ? `Patch ${patch.split('.').slice(0, 2).join('.')}` : '');

/** Ausgetauschte Champions zwischen zwei Drafts, wie `DraftArchive.changes` im Backend (vertauschte Seiten zählen nicht). */
function draftChanges(a, b) {
  const missing = (from, to) => {
    const champions = new Set(from.map((s) => s.champion));
    return to.filter((s) => !champions.has(s.champion)).length;
  };
  return Math.min(missing(a.ally, b.ally) + missing(a.enemy, b.enemy), missing(a.ally, b.enemy) + missing(a.enemy, b.ally));
}

/**
 * Die zuletzt vollständigen Drafts aus dem lokalen Archiv. „Laden“ setzt den ganzen Draft aufs Brett, ein Klick auf
 * einen Champion lädt ihn ohne diesen Pick: „Beste Picks“ zeigt dann, was an seiner Stelle am stärksten gewesen wäre.
 */
function Archive({ data, view, setView }) {
  const rev = useStore((s) => s.draftArchiveRev || 0);
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const [state, setState] = useState({ list: null, error: null });
  useEffect(() => {
    let active = true;
    api.get('/api/draft-archive').then((list) => active && setState({ list, error: null }))
      .catch((error) => active && setState((previous) => ({ ...previous, error })));
    return () => {
      active = false;
    };
  }, [rev]);

  const board = { ally: view.ally.filter(Boolean), enemy: view.enemy.filter(Boolean) };
  const boardComplete = board.ally.length === TEAM_SIZE && board.enemy.length === TEAM_SIZE;

  const load = (entry, without) => {
    setView(boardPatch(entry, data, without));
    toast(without ? `Draft ohne ${data.championByKey.get(without.champion).name} geladen` : 'Draft aus dem Archiv geladen');
  };

  const remove = (entry) => {
    if (!window.confirm('Diesen Draft aus dem Archiv löschen?')) {
      return;
    }
    api.del(`/api/draft-archive/${entry.id}`).then((list) => setState({ list, error: null }))
      .catch((error) => setState((previous) => ({ ...previous, error })));
  };

  const mode = view.archiveMode === 'series' ? 'series' : 'drafts';
  const switcher = html`<${Segmented} label="Archiv" value=${mode} onChange=${(archiveMode) => setView({ archiveMode })}
    options=${[{ value: 'drafts', label: 'Drafts' }, { value: 'series', label: 'Fearless-Serien' }]} />`;
  if (mode === 'series') {
    return html`<div class="draft-archive">${switcher}<${SeriesList} data=${data} view=${view} setView=${setView} /></div>`;
  }
  const { list, error } = state;
  if (!list) {
    return error ? html`<${ErrorNote} prefix="Archiv nicht verfügbar" error=${error} />` : html`<${Loading} />`;
  }
  return html`<div class="draft-archive">
    ${switcher}
    ${advancedTips ? html`<p class="muted small draft-archive-note">
      Die letzten ${list.limit} vollständigen Drafts, nur lokal auf diesem Gerät gespeichert (Anzahl in den Einstellungen).
      Ein Draft kommt hinzu, sobald alle zehn Champions feststehen und ${ARCHIVE_DELAY_MS / 1000} Sekunden unverändert bleiben.
      Weicht er von einem gespeicherten in weniger als ${list.minChanges} Champions ab, gilt er als Variante und wird nicht
      gespeichert. Klick auf einen Champion lädt den Draft ohne ihn: „Beste Picks“ zeigt dann, was an seiner Stelle am
      stärksten gewesen wäre.
    </p>` : null}
    ${error ? html`<${ErrorNote} prefix="Aktion fehlgeschlagen" error=${error} />` : null}
    ${list.entries.length === 0
      ? html`<${Empty} title="Noch keine Drafts im Archiv">Vollständige Drafts erscheinen hier automatisch.<//>`
      : html`<ul class="draft-archive-list">
        ${list.entries.map((entry) => html`<${ArchiveEntry} key=${entry.id} entry=${entry} data=${data} advancedTips=${advancedTips}
          current=${boardComplete && draftChanges(entry, board) === 0} onLoad=${load} onRemove=${remove} />`)}
      </ul>`}
  </div>`;
}

/** Brett-Stand eines gespeicherten Drafts; `without` lädt ihn ohne einen Pick („Beste Picks“ für dessen Platz). */
function boardPatch(entry, data, without = null) {
  const skip = (team) => (without && without.team === team ? without.champion : null);
  const patch = {
    ally: boardSlots(entry.ally, data, skip('ally')), enemy: boardSlots(entry.enemy, data, skip('enemy')),
    bans: entry.bans.filter((key) => data.championByKey.has(key)).slice(0, MAX_BANS), picker: null, fearlessContext: null,
    // Ganzer Draft: die Analyse. Ohne einen Pick: die besten Picks für diese Seite und, wenn bekannt, seine Rolle.
    tab: without ? 'picks' : 'analysis',
  };
  if (without) {
    const removed = entry[without.team].find((s) => s.champion === without.champion);
    patch.side = without.team;
    patch.role = removed && removed.role ? removed.role : '';
  }
  return patch;
}

const RESULT_LABELS = { WIN: 'Sieg', LOSS: 'Niederlage' };

/**
 * Laufende und beendete Fearless-Serien mit ihren Spielen. Laden und der Klick auf einen Champion wirken wie im Archiv,
 * zusätzlich gelten die Sperren, die in diesem Spiel galten (Picks der Spiele davor).
 */
function SeriesList({ data, view, setView }) {
  const fearless = useFearless();
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const [renaming, setRenaming] = useState(null);
  const { state } = fearless;
  if (!state) {
    return html`<${Loading} />`;
  }
  const list = [...(state.active ? [state.active] : []), ...state.finished];
  if (list.length === 0) {
    return html`<${Empty} title="Noch keine Fearless-Serien">Eine Serie erscheint hier, sobald ihr erstes Spiel abgeschlossen ist.<//>`;
  }
  const board = { ally: view.ally.filter(Boolean), enemy: view.enemy.filter(Boolean) };
  const boardComplete = board.ally.length === TEAM_SIZE && board.enemy.length === TEAM_SIZE;
  const context = view.fearlessContext;

  const loadGame = (series, game, without) => {
    const locked = series.games.filter((g) => g.number < game.number)
      .flatMap((g) => [...g.ally, ...g.enemy].map((s) => ({ champion: s.champion, game: g.number })));
    setView({ ...boardPatch(game, data, without), fearlessContext: { seriesId: series.id, label: series.label, game: game.number, locked } });
    toast(without ? `Spiel ${game.number} ohne ${data.championByKey.get(without.champion).name} geladen, mit ${locked.length} Sperren`
      : `Spiel ${game.number} geladen, mit ${locked.length} Sperren`);
  };
  const rename = (series, label) => fearless.rename(series.id, label).then((next) => next && setRenaming(null));
  const remove = (series) => {
    if (window.confirm('Diese Serie mit allen Spielen löschen?')) {
      fearless.remove(series.id);
    }
  };
  const reopen = () => {
    const filled = view.ally.some(Boolean) || view.enemy.some(Boolean) || view.bans.length > 0;
    if (!window.confirm(`Serie wieder öffnen und ihr letztes Spiel zurücknehmen?${filled ? ' Sein Draft ersetzt das aktuelle Brett.' : ''}`)) {
      return;
    }
    fearless.undo().then((next) => {
      if (next && next.undone) {
        setView({ ...boardPatch(next.undone, data), tab: 'picks' });
        toast(`Serie läuft wieder, Spiel ${next.undone.number} ist zurück auf dem Brett`);
      }
    });
  };

  return html`<div class="draft-series-list">
    ${fearless.error ? html`<${ErrorNote} prefix="Aktion fehlgeschlagen" error=${fearless.error} />` : null}
    ${list.map((series, index) => {
      const running = !series.endedAt;
      const date = new Date(series.startedAt).toLocaleString('de-DE', ARCHIVE_DATE);
      const score = series.wins + series.losses > 0 ? ` · ${series.wins}:${series.losses}` : '';
      // Nur die zuletzt beendete Serie lässt sich wieder öffnen, und nur, wenn gerade keine läuft.
      const reopenable = !state.active && index === 0 && !running;
      return html`<section key=${series.id} class=${`draft-series ${running ? 'running' : ''}`}>
        <header class="draft-series-head">
          ${renaming === series.id
            ? html`<${LabelEditor} value=${series.label} maxLength=${state.maxLabelLength || 60}
              onSave=${(label) => rename(series, label)} onCancel=${() => setRenaming(null)} />`
            : html`<div class="draft-fearless-head">
              <strong class="draft-fearless-title">${series.label || `Serie vom ${date}`}</strong>
              <${Button} icon="edit" kind="ghost" title="Bezeichnung ändern" onClick=${() => setRenaming(series.id)} />
            </div>`}
          <span class="muted small">${formatLabel(series.format)} · ${series.games.length} ${series.games.length === 1 ? 'Spiel' : 'Spiele'}${score}${series.label ? ` · ${date}` : ''}</span>
          ${running ? html`<${Tag}>Läuft<//>` : null}
          <div class="draft-archive-actions">
            ${reopenable ? html`<${Button} icon="undo" kind="icon" title="Wieder öffnen, letztes Spiel zurücknehmen" onClick=${reopen}
              tip=${advancedTips ? 'Für ein falsch eingetragenes letztes Spiel oder Ergebnis' : undefined} />` : null}
            ${running ? null : html`<${Button} icon="trash" kind="icon" title="Serie löschen" onClick=${() => remove(series)} />`}
          </div>
        </header>
        ${series.games.length === 0 ? html`<p class="muted small">Noch kein Spiel abgeschlossen.</p>` : html`<ul class="draft-archive-list">
          ${series.games.map((game) => html`<${ArchiveEntry} key=${game.number} data=${data} advancedTips=${advancedTips}
            entry=${{ ...game, savedAt: game.playedAt }} heading=${`Spiel ${game.number}`}
            badge=${game.result ? html`<${Tag} kind=${game.result === 'WIN' ? '' : 'soft'}>${RESULT_LABELS[game.result]}<//>` : null}
            current=${boardComplete && Boolean(context) && context.seriesId === series.id && context.game === game.number && draftChanges(game, board) === 0}
            onLoad=${(entry, without) => loadGame(series, game, without)} />`)}
        </ul>`}
      </section>`;
    })}
  </div>`;
}

function ArchiveEntry({ entry, data, advancedTips, current, onLoad, onRemove, heading = null, badge = null }) {
  const pick = (slot, team) => {
    const champion = data.championByKey.get(slot.champion);
    if (!champion) {
      return null;
    }
    const label = `${champion.name}: Draft ohne diesen Pick laden`;
    return html`<button type="button" key=${slot.champion} class="draft-archive-pick" aria-label=${label}
      data-tip=${advancedTips ? label : undefined} onClick=${() => onLoad(entry, { team, champion: slot.champion })}>
      <${Img} src=${champion.icon} size=${34} />
    </button>`;
  };
  const bans = entry.bans.map((key) => data.championByKey.get(key)).filter(Boolean);
  return html`<li class=${`draft-archive-entry ${current ? 'current' : ''}`}>
    <div class="draft-archive-meta">
      <strong>${heading || new Date(entry.savedAt).toLocaleString('de-DE', ARCHIVE_DATE)}</strong>
      <span class="muted small">${patchLabel(entry.patch)}</span>
      ${badge}
      ${current ? html`<${Tag}>Auf dem Brett<//>` : null}
    </div>
    <div class="draft-archive-teams">
      <div class="draft-archive-team ally" aria-label="Dein Team">${entry.ally.map((s) => pick(s, 'ally'))}</div>
      <span class="muted small">vs</span>
      <div class="draft-archive-team enemy" aria-label="Gegner">${entry.enemy.map((s) => pick(s, 'enemy'))}</div>
    </div>
    <div class="draft-archive-bans" aria-label="Bans">
      ${bans.map((champion) => html`<${Img} key=${champion.key} src=${champion.icon} size=${22} alt=${champion.name} />`)}
    </div>
    <div class="draft-archive-actions">
      <${Button} icon="download" onClick=${() => onLoad(entry)} tip=${advancedTips ? 'Ganzen Draft aufs Brett laden' : undefined}>Laden<//>
      ${onRemove ? html`<${Button} icon="trash" title="Aus dem Archiv löschen" onClick=${() => onRemove(entry)}
        tip=${advancedTips ? 'Aus dem Archiv löschen' : undefined} />` : null}
    </div>
  </li>`;
}
