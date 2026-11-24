// Draft-Assistent: Eingabe der Picks und Bans, die fünf besten nächsten Picks und die Gesamteinschätzung.
import { html, useState, useEffect, useMemo, useRef } from '/vendor/htm-preact.js';
import { store, useStore, useViewState, useSourceLabel, api, normalize, percent, sortRows, activeSort, useFavorites, useFavoriteInfo, toggleFavorite, setFavoriteRoles, setFavoritePractice, setFavoriteOffMeta, useAvoid, toggleAvoid, clearAvoid, openChampion, toast, gamePatch } from './core.js';
import { Icon, Img, Button, Select, Segmented, Switch, Card, Empty, ErrorNote, Loading, Tag, Spinner, FavoriteMark, MoreToggle, MenuButton, confirmDialog } from './ui.js';

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

const AUTO_MIN_GAMES = 'auto';
/** Ohne „Spiele“: Die Auswahl steht hinter der Beschriftung „Games“ und muss neben „Draft leeren“ in die Zeile passen. */
const MIN_GAMES = [0, 1000, 2500, 4300, 10000, 25000].map((n) => ({ value: String(n), label: `ab ${n.toLocaleString('de-DE')}` }));
/** Pickrate auf der Rolle in Prozent; „Auto“ ist der Standard des Backends. */
const MIN_PICK_RATES = [0.25, 0.5, 1, 1.5, 2, 3].map((p) => ({ value: String(p / 100), label: `ab ${p} %` }));
/** Eine Profil-Aussage über ein Team braucht mehr als ein oder zwei Picks. */
const MIN_PICKS_FOR_PROFILE = 3;
/** Kennzeichnet dieses Fenster, damit das Backend nur seine eigenen überholten Anfragen abbricht. */
const CLIENT_ID = crypto.randomUUID();

const DEFAULTS = {
  ally: Array(TEAM_SIZE).fill(null), enemy: Array(TEAM_SIZE).fill(null), bans: [],
  side: 'ally', role: '', minGames: AUTO_MIN_GAMES, minPickRate: AUTO_MIN_GAMES, ignoreChampionWinRates: false, rarePicks: false,
  tab: 'picks', picker: null, avoidPicker: false, favoritePicker: false, favoritesOnly: false,
  // Sperren eines aus dem Archiv geladenen Serienspiels ({ seriesId, label, game, locked }); null = keine.
  fearlessContext: null, archiveMode: 'drafts',
  // Aus „Drafts“ geladener Eintrag ({ id, ally, enemy }), dessen Rollen sich überschreiben lassen; null = keiner.
  loadedDraft: null,
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
  // Kurz gehalten: Die breiteste Option bestimmt die Breite der Auswahl neben „Optionen“.
  return rarePicks ? `Auto ${result.minGames.toLocaleString('de-DE')}` : `Auto ${percent(result.defaultMinPickRate, 2)}`;
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
 * Plätze der zuletzt berechneten Analyse (`result.slots`), solange der Draft-Tab offen ist und die Analyse nicht lädt;
 * sonst `null`. Die Archiv-Aufnahme läuft auch bei geschlossenem Tab und holt sich hier nur ab, was das Brett ohnehin
 * zeigt: Es gibt dafür keine eigene Anfrage, keine Vorhersage und keinen Zugriff auf den Client.
 */
let shownSlots = null;

/**
 * Auto-Rollen, die das Brett für Picks ohne feste Rolle zeigt (Champion → Rolle), nur zum Sortieren Top bis Support.
 * Leer, wenn keine Analyse vorliegt oder sie nicht zu genau diesen zehn Champions gehört.
 */
function shownRolesOf(view, slots) {
  const board = completeDraftKey(view);
  const ids = (team) => (slots?.[team] || []).map((s) => s.champion).sort((a, b) => a - b);
  if (!slots || board === null || JSON.stringify([ids('ally'), ids('enemy')]) !== board) {
    return {};
  }
  const shown = {};
  ['ally', 'enemy'].forEach((team) => slots[team].forEach((s) => {
    const slot = view[team].find((x) => x && x.champion === s.champion);
    if (slot && !slot.role && s.role) {
      shown[s.champion] = s.role;
    }
  }));
  return shown;
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
  // Während einer Fearless-Serie nimmt die Aufzeichnung nichts auf: Erst „Spiel abschließen“ legt das Spiel (im Backend)
  // auch unter „Drafts“ an. Ein geladenes Serienspiel ebenso wenig.
  const view = store.get().views.draft;
  const key = store.get().fearless?.active || view?.fearlessContext || isSeriesGame(view) ? null : completeDraftKey(view);
  if (key === archiveKey) {
    return;
  }
  archiveKey = key;
  clearTimeout(archiveTimer);
  archiveTimer = key ? setTimeout(() => archiveDraft(key), ARCHIVE_DELAY_MS) : null;
});

/**
 * Ob das volle Brett einem Spiel einer Fearless-Serie entspricht (laufend, beendet oder im Papierkorb). Solche Drafts
 * gehören zur Serie: Ohne diese Prüfung käme z. B. ein wieder geöffnetes oder geladenes Serienspiel nach dem Beenden
 * erneut als neuer Draft oben ins Archiv, und nach dem Löschen der Serie stünde es dort weiter.
 */
function isSeriesGame(view) {
  const state = store.get().fearless;
  if (!state || !view || completeDraftKey(view) === null) {
    return false;
  }
  const board = { ally: view.ally.filter(Boolean), enemy: view.enemy.filter(Boolean) };
  return [...(state.active ? [state.active] : []), ...state.finished, ...(state.trash || [])]
    .some((series) => series.games.some((game) => draftChanges(game, board) === 0));
}

function archiveDraft(key) {
  const view = store.get().views.draft;
  if (completeDraftKey(view) !== key || store.get().fearless?.active || view.fearlessContext || isSeriesGame(view)) {
    return;
  }
  api.post('/api/draft-archive', { ally: view.ally.filter(Boolean), enemy: view.enemy.filter(Boolean), bans: view.bans || [],
    shownRoles: shownRolesOf(view, shownSlots) })
    .then((outcome) => {
      if (outcome.saved) {
        store.set({ draftArchiveRev: (store.get().draftArchiveRev || 0) + 1 });
        toast('Draft im Archiv gespeichert');
      }
    })
    .catch(() => {});
}

/**
 * Vollständige Antworten je Anfrage (Schlüssel: Anfrage-Text). Zurück zu einem schon berechneten Brett, etwa beim
 * Wechsel „Für mein Team“/„Für den Gegner“ oder beim erneuten Öffnen des Tabs, zeigt sofort das gemerkte Ergebnis.
 * Die Anfrage geht trotzdem genau wie ohne Speicher hinaus und ersetzt es; an den Abrufen ändert sich nichts.
 */
const draftResults = new Map();
const DRAFT_RESULT_LIMIT = 20;
const DRAFT_DEBOUNCE_MS = 150;

function rememberDraftResult(body, result) {
  draftResults.delete(body);
  draftResults.set(body, result);
  if (draftResults.size > DRAFT_RESULT_LIMIT) {
    draftResults.delete(draftResults.keys().next().value);
  }
}

/**
 * Die Felder, nach denen das Backend entscheidet, was es lädt (`DraftDataLoader`: Picks samt Rollen, Seite, Rang-Bereich,
 * Zeitraum). Bleiben sie gleich und ändern sich nur Bans, Sperren oder Filter, lädt die Anfrage nichts Neues; dann
 * braucht sie keine Wartezeit, die schnelle Klickfolgen zusammenfasst.
 */
function loadKey(body) {
  const { ally, enemy, side, tier, period } = JSON.parse(body);
  return JSON.stringify([ally, enemy, side, tier, period]);
}

function useDraftResult(view, dataSource, locked) {
  const body = JSON.stringify({
    ally: view.ally.filter(Boolean), enemy: view.enemy.filter(Boolean), bans: view.bans, side: view.side,
    // Fearless: in früheren Spielen der Serie gespielte Champions fehlen in den Vorschlägen.
    locked: [...locked.keys()],
    // Spielzahlen gelten nur für seltene Picks, sonst die Pickrate auf der Rolle.
    rarePicks: Boolean(view.rarePicks),
    minGames: !view.rarePicks || view.minGames === AUTO_MIN_GAMES ? null : Number(view.minGames),
    minPickRate: view.rarePicks || !view.minPickRate || view.minPickRate === AUTO_MIN_GAMES ? null : Number(view.minPickRate),
    ignoreChampionWinRates: view.ignoreChampionWinRates,
    tier: dataSource.tier, period: dataSource.period,
  });
  // Beim erneuten Öffnen des Tabs steht ein gemerktes Ergebnis schon im ersten Bild.
  const [state, setState] = useState(() => {
    const remembered = draftResults.get(body);
    return { result: remembered || null, error: null, loading: !remembered, completing: false };
  });
  const sequence = useRef(0);
  const lastLoadKey = useRef(null);
  // Zwei Schritte: Die schnelle Anfrage lädt nur, was die Vorschläge bestimmen, und zeigt sie sofort. Fehlt danach noch
  // etwas für die Gesamtanalyse ("complete": false), lädt eine zweite Anfrage den Rest (completing).
  useEffect(() => {
    const requestId = ++sequence.current;
    const controller = new AbortController();
    const post = (id, quick) => api.post('/api/draft', { ...JSON.parse(body), client: CLIENT_ID, requestId: id, quick }, controller.signal);
    const key = loadKey(body);
    const delay = key === lastLoadKey.current ? 0 : DRAFT_DEBOUNCE_MS;
    lastLoadKey.current = key;
    // Mit gemerktem Ergebnis gilt das Brett nicht als ladend: Die Antwort ersetzt es still.
    const remembered = draftResults.get(body);
    setState((previous) => (remembered
      ? { result: remembered, error: null, loading: false, completing: false, requestId }
      : { ...previous, error: null, loading: true, completing: false, requestId }));
    const show = (result, id) => {
      if (result.complete !== false) {
        rememberDraftResult(body, result);
      }
      setState({ result, error: null, loading: false, completing: result.complete === false, requestId: id });
    };
    const timer = setTimeout(() => {
      post(requestId, true)
        .then((result) => {
          if (controller.signal.aborted) {
            return null;
          }
          if (result.complete !== false) {
            show(result, requestId);
            return null;
          }
          const fullId = ++sequence.current;
          // Ein gemerktes vollständiges Ergebnis bleibt stehen, bis die vollständige Antwort da ist.
          if (!remembered) {
            show(result, fullId);
          }
          return post(fullId, false).then((full) => !controller.signal.aborted && show(full, fullId));
        })
        .catch((error) => !controller.signal.aborted
          && setState((previous) => ({ ...previous, error, loading: false, completing: false })));
    }, delay);
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

/**
 * Kachel für einen Modus des Drafts (Fearless-Serie, Client-Abgleich): Titel, Stand in einer Zeile und ein Schalter-Symbol.
 * Beide Modi verändern, woher das Brett kommt bzw. was darauf erlaubt ist; deshalb stehen sie groß und nebeneinander
 * unter den Bans statt als kleine Schalter zwischen den Filtern.
 */
function ModeTile({ kind, icon, title, status, on, live = false, tip, onClick }) {
  return html`<button type="button" class=${`draft-mode ${kind} ${on ? 'on' : ''} ${live ? 'live' : ''}`} role="switch" aria-checked=${on}
    data-tip=${tip} onClick=${onClick}>
    <span class="draft-mode-icon"><${Icon} name=${icon} size=${18} /></span>
    <span class="draft-mode-text"><strong>${title}</strong><span class="draft-mode-status">${status}</span></span>
    <span class="draft-mode-switch" aria-hidden="true"></span>
  </button>`;
}

function ClientTile({ lcu, onToggle }) {
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const enabled = Boolean(lcu) && lcu.status !== 'OFF';
  const live = enabled && lcu.status === 'CHAMP_SELECT';
  return html`<${ModeTile} kind="client" icon="plug" title="League-Client" on=${enabled} live=${live}
    status=${enabled ? CLIENT_STATUS[lcu.status] || lcu.message || 'An' : 'Picks und Bans live übernehmen'}
    tip=${advancedTips ? 'Liest während der Champion-Auswahl Picks, Bans und eure Rollen aus dem League-Client (nur lesend, wie DraftGap). Keine Namen, keine Aktionen im Client.' : undefined}
    onClick=${() => onToggle(!enabled)} />`;
}

// ------------------------------------------------------------------ Fearless-Serie

const FEARLESS_FORMATS = [{ value: 'BO3', label: 'Bo3' }, { value: 'BO5', label: 'Bo5' }, { value: 'OPEN', label: 'Offen' }];
const formatLabel = (format) => FEARLESS_FORMATS.find((f) => f.value === format)?.label || format;
/** Höchstzahl der Spiele je Serie wie `FearlessSeries.MAX_GAMES`; „Offen“ ohne gewählte Spielzahl nutzt sie. */
const MAX_SERIES_GAMES = 10;
const GAME_COUNT_OPTIONS = Array.from({ length: MAX_SERIES_GAMES }, (_, i) => ({ value: String(i + 1), label: `${i + 1} ${i ? 'Games' : 'Game'}` }));
/** Ob die Serie eine feste Spielzahl hat: Bo3/Bo5 oder „Offen“ mit beim Start gewählter Zahl. */
const limitedSeries = (series) => series.format !== 'OPEN' || series.maxGames < MAX_SERIES_GAMES;
/** „Bo3“, „Offen“ oder mit gewählter Spielzahl „Offen, max. 4“. */
const seriesFormatLabel = (series) => (series.format === 'OPEN' && limitedSeries(series)
  ? `${formatLabel(series.format)}, max. ${series.maxGames}` : formatLabel(series.format));
const NO_LOCKS = [];
const NO_PICKER_LOCKS = new Map(); // Auswahl ohne Fearless-Sperren (Marvins-Liste)
const NO_AVOID = new Set();
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

/** Nur Champion und feste Rolle eines Teams, für den Vergleich und zum Speichern. */
const teamRoles = (team) => team.filter(Boolean).map((s) => ({ champion: s.champion, role: s.role || null }));

/**
 * Der gespeicherte Draft, aus dem das Brett geladen wurde: ein Archiv-Eintrag (`view.loadedDraft`, Stand beim Laden bzw.
 * letzten Speichern) oder ein Serienspiel (`view.fearlessContext`, Stand aus `store.fearless`). `sameChampions`: jede
 * Seite hat genau seine Champions, nur dann lassen sich die Rollen überschreiben; mit anderen Champions bleibt alles
 * bei der normalen Aufnahme. `rolesChanged`: das Brett weicht in festen Rollen ab.
 */
function storedTarget(view, fearlessState) {
  let target = null;
  if (view.fearlessContext && fearlessState) {
    const { seriesId, game: number } = view.fearlessContext;
    const series = [...(fearlessState.active ? [fearlessState.active] : []), ...fearlessState.finished].find((s) => s.id === seriesId);
    const game = series?.games.find((g) => g.number === number);
    if (game) {
      target = { kind: 'game', seriesId, number, ally: game.ally, enemy: game.enemy };
    }
  } else if (view.loadedDraft) {
    target = { kind: 'draft', ...view.loadedDraft };
  }
  return target;
}

/** Ob das Brett (`ally`/`enemy` als Brett-Plätze) dieselben Champions je Seite hat und ob feste Rollen abweichen. */
function compareStored(target, ally, enemy) {
  const board = { ally: teamRoles(ally), enemy: teamRoles(enemy) };
  const same = (side) => board[side].length === target[side].length
    && target[side].every((s) => board[side].some((b) => b.champion === s.champion));
  const sameChampions = same('ally') && same('enemy');
  const rolesChanged = sameChampions && ['ally', 'enemy'].some((side) => target[side]
    .some((s) => (board[side].find((b) => b.champion === s.champion).role || null) !== (s.role || null)));
  return { sameChampions, rolesChanged, board };
}

/**
 * Überschreibt die festen Rollen des geladenen gespeicherten Drafts mit denen vom Brett. Nur das eigene Backend,
 * Champions und Bans bleiben unverändert. Liefert ein Promise mit einem Zusatz für die Meldung (z. B. „, auch unter „Drafts““).
 */
function saveStoredRoles(target, board, setView) {
  if (target.kind === 'game') {
    return api.put(`/api/fearless/${target.seriesId}/game/${target.number}`, board).then((next) => {
      store.set({ fearless: next });
      // Der gleiche Draft unter „Drafts“ hat die Rollen mitbekommen oder wurde neu angelegt: die Liste dort neu laden.
      if (next.draftsUpdated || next.draftsRecreated) {
        store.set({ draftArchiveRev: (store.get().draftArchiveRev || 0) + 1 });
      }
      return next.draftsRecreated ? ', unter „Drafts“ neu angelegt' : next.draftsUpdated ? ', auch unter „Drafts“' : '';
    });
  }
  return api.put(`/api/draft-archive/${target.id}`, board).then(async (next) => {
    setView({ loadedDraft: { id: next.entry.id, ally: next.entry.ally, enemy: next.entry.enemy } });
    store.set({ draftArchiveRev: (store.get().draftArchiveRev || 0) + 1 });
    // Gleiche Spiele einer Fearless-Serie haben die Rollen mitbekommen: deren Stand neu holen.
    if (next.seriesUpdated) {
      store.set({ fearless: await api.get('/api/fearless') });
      return ', auch in der Fearless-Serie';
    }
    return '';
  });
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
    start: (format, label, maxGames) => call(api.post('/api/fearless', { format, label, ...(format === 'OPEN' ? { maxGames: Number(maxGames) } : {}) })),
    finish: (draft, result) => call(api.post('/api/fearless/game', { ...draft, result })),
    addDraftTo: (id, draftId) => call(api.post(`/api/fearless/${id}/game`, { draftId })),
    undo: () => call(api.del('/api/fearless/game')),
    end: () => call(api.post('/api/fearless/end')),
    rename: (id, label, maxGames) => call(api.put(`/api/fearless/${id}`, { label, ...(maxGames ? { maxGames: Number(maxGames) } : {}) })),
    reorder: (id, order) => call(api.put(`/api/fearless/${id}`, { order })),
    remove: (id) => call(api.del(`/api/fearless/${id}`)),
    removeGame: (id, number) => call(api.del(`/api/fearless/${id}/game/${number}`)),
    replaceGame: (id, number, board) => call(api.put(`/api/fearless/${id}/game/${number}`, { replace: true, ...board })),
    setResult: (id, number, result) => call(api.put(`/api/fearless/${id}/game/${number}`, { result })),
    restore: (id) => call(api.post(`/api/fearless/${id}/restore`)),
    purge: (id) => call(api.del(`/api/fearless/trash/${id}`)),
    emptyTrash: () => call(api.del('/api/fearless/trash')),
  };
}

/**
 * Das Brett zum Speichern als Serienspiel (Abschließen oder geladenes Spiel überschreiben): Picks je Team in
 * Rollen-Reihenfolge (Top bis Support), damit Sperrleiste und Serienansicht danach geordnet sind. Gespeichert wird nur
 * die Reihenfolge; die Rolle bleibt fest oder leer wie auf dem Brett. Die gerade angezeigten Auto-Rollen gehen als
 * `shownRoles` mit: Damit ordnet das Backend auch Gegner ohne feste Rolle, ohne neu vorherzusagen.
 */
function seriesBoard(view, draftResult) {
  const ordered = (team) => {
    const predicted = new Map((draftResult?.slots[team] || []).map((s) => [s.champion, s.role]));
    return view[team].filter(Boolean).map((slot, i) => ({ slot, i, order: roleOrder(slot.role || predicted.get(slot.champion)) }))
      .sort((a, b) => a.order - b.order || a.i - b.i).map(({ slot }) => slot);
  };
  const shownRoles = {};
  ['ally', 'enemy'].forEach((team) => (draftResult?.slots[team] || []).forEach((s) => {
    const slot = view[team].find((x) => x && x.champion === s.champion);
    if (slot && !slot.role && s.role) {
      shownRoles[s.champion] = s.role;
    }
  }));
  return { ally: ordered('ally'), enemy: ordered('enemy'), bans: view.bans, shownRoles };
}

/** Meldung nach geänderter Spielzahl: beendet, wieder geöffnet oder blockiert durch eine andere laufende Serie; sonst `null`. */
function maxGamesNote(next, series, maxGames) {
  const name = series.label ? `„${series.label}“` : 'Die Serie';
  return next.ended ? `${name} ist beendet: alle ${maxGames} Games gespielt`
    : next.reopened ? `${name} läuft wieder, Platz für ${maxGames} Games`
      : next.reopenBlocked ? `${name} bleibt beendet, weil schon eine andere Serie läuft` : null;
}

/** „Spiel 3/5 · 2:0“; ohne eingetragene Ergebnisse ohne Stand. */
function seriesProgress(series) {
  const game = limitedSeries(series) ? `Game ${series.nextGame}/${series.maxGames}` : `Game ${series.nextGame}`;
  return series.wins + series.losses > 0 ? `${game} · ${series.wins}:${series.losses}` : game;
}

/** Titel des Bearbeiten-Knopfs: Bei offenen Serien lässt sich dort auch die Spielzahl ändern. */
const editTitle = (series) => (series.format === 'OPEN' ? 'Bezeichnung und Anzahl Games ändern' : 'Bezeichnung ändern');

/**
 * Bezeichnung ändern: Eingabe mit Enter/Escape, leer entfernt die Bezeichnung. Bei offenen Serien dazu die Spielzahl,
 * nicht unter der Zahl gespielter Spiele; `onSave(label, maxGames)`, `maxGames` nur bei offenen Serien.
 * Eine laufende offene Serie mit mindestens einem Game lässt sich hier vorzeitig beenden: Die Anzahl Games wird auf die
 * gespielten gesetzt, damit ist sie voll und endet (mehr Games öffnen sie später wieder). Bo3/Bo5 nicht.
 */
function LabelEditor({ series, maxLength, onSave, onCancel }) {
  const [text, setText] = useState(series.label || '');
  const open = series.format === 'OPEN';
  const [count, setCount] = useState(String(series.maxGames));
  const input = useRef();
  useEffect(() => input.current.focus(), []);
  const save = () => onSave(text, open ? count : undefined);
  const played = series.games.length;
  const canEnd = open && !series.endedAt && played > 0;
  const endNow = async () => {
    if (await confirmDialog({ title: 'Serie jetzt beenden?', icon: 'lock', confirmLabel: 'Beenden',
      message: `Die Anzahl Games wird auf ${played} gesetzt. ${played === 1 ? 'Das gespielte Game bleibt' : `Die ${played} gespielten Games bleiben`} unter Archiv › Fearless erhalten; danach gibt es keine Sperren mehr. Mehr Games öffnen die Serie später wieder.` })) {
      onSave(text, String(played));
    }
  };
  const countOptions = GAME_COUNT_OPTIONS.filter((o) => Number(o.value) >= Math.max(1, played));
  return html`<div class="draft-fearless-rename">
    <input ref=${input} class="input" type="text" maxlength=${maxLength} spellcheck="false" value=${text} aria-label="Bezeichnung"
      placeholder="Bezeichnung (optional)" onInput=${(e) => setText(e.currentTarget.value)}
      onKeyDown=${(e) => (e.key === 'Enter' ? save() : e.key === 'Escape' ? onCancel() : null)} />
    ${open ? html`<${Select} label="Anzahl Games" cls="draft-fearless-count" value=${count} options=${countOptions} onChange=${setCount} />` : null}
    <${Button} icon="ok" kind="icon" title=${open ? 'Speichern' : 'Bezeichnung speichern'} onClick=${save} />
    ${canEnd ? html`<${Button} icon="lock" kind="ghost" onClick=${endNow}
      title=${`Serie nach ${played} ${played === 1 ? 'Game' : 'Games'} beenden`}>Jetzt beenden<//>` : null}
    <${Button} icon="x" kind="icon" title="Abbrechen" onClick=${onCancel} />
  </div>`;
}

/** Schalter an: Start-Formular öffnen; aus: Formular schließen oder die laufende Serie (nach Rückfrage) beenden. */
async function toggleFearless(on, fearless, form, setForm) {
  const active = fearless.state?.active || null;
  if (on) {
    setForm({ format: 'BO3', label: '', maxGames: String(MAX_SERIES_GAMES) });
  } else if (form) {
    setForm(null);
  } else if (active) {
    const games = active.games.length;
    if (games > 0 && !(await confirmDialog({ title: 'Fearless-Serie beenden?', icon: 'lock', confirmLabel: 'Serie beenden',
      message: `${games === 1 ? 'Das gespielte Game bleibt' : `Die ${games} gespielten Games bleiben`} unter Archiv › Fearless erhalten. Danach gibt es keine Sperren mehr.` }))) {
      return;
    }
    fearless.end().then((next) => next && toast(games > 0 ? 'Fearless-Serie beendet' : 'Fearless-Serie verworfen'));
  }
}

/**
 * Kachel der Fearless-Serie unter den Bans: zeigt ohne Serie, wofür sie da ist, und mit Serie Format und Fortschritt.
 * Formular und Stand zeigt `FearlessPanel` unter dem Brett.
 */
function FearlessTile({ fearless, form, setForm }) {
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const active = fearless.state?.active || null;
  // Sperren und Bezeichnung stehen in der Serienzeile unter dem Brett; die Kachel zeigt nur, wo die Serie steht.
  const status = active ? `${seriesFormatLabel(active)} · ${seriesProgress(active)}`
    : form ? 'Format wählen und starten ↓' : 'Picks für Folge-Games sperren';
  return html`<${ModeTile} kind="fearless" icon="lock" title="Fearless-Serie" on=${Boolean(active || form)} status=${status}
    tip=${advancedTips ? 'Hard Fearless für Bo3, Bo5 oder offene Scrim-Blöcke: Champions, die ein Team in einem früheren Game der Serie gepickt hat, sind danach für beide Teams gesperrt. Bans werden nicht übertragen. Nur lokal, ohne Abfrage beim League-Client.' : undefined}
    onClick=${() => toggleFearless(!(active || form), fearless, form, setForm)} />`;
}

/**
 * Stand der Fearless-Serie (Hard Fearless): Picks beider Teams aus abgeschlossenen Spielen sind in den
 * folgenden Spielen gesperrt, Bans nicht. Ein Spiel zählt erst nach „Spiel abschließen“, nie automatisch.
 */
function FearlessPanel({ fearless, form, setForm, view, setView, data, locked, draftResult, boardComplete, lcuStatus, conflicts }) {
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const staleAck = useStore((s) => s.fearlessStaleAck);
  const { state, busy } = fearless;
  const active = state?.active || null;
  const context = view.fearlessContext || null;
  const maxLength = state?.maxLabelLength || 60;
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

  const toggle = (on) => toggleFearless(on, fearless, form, setForm);
  const start = () => fearless.start(form.format, form.label, form.maxGames).then((next) => {
    if (next) {
      setForm(null);
      setView({ fearlessContext: null });
      toast('Fearless-Serie gestartet: Der aktuelle Draft ist Game 1');
    }
  });
  // Aus dem Archiv geladenes Spiel einer Serie (laufend oder beendet): Es ist kein neues Spiel, sondern lässt sich mit
  // geänderten Champions, Rollen oder Bans überschreiben. Normale Archiv-Drafts bekommen das nicht (nur Rollen speichern).
  const allSeries = state ? [...(active ? [active] : []), ...state.finished] : [];
  const contextSeries = context ? allSeries.find((s) => s.id === context.seriesId) || null : null;
  const contextGame = contextSeries ? contextSeries.games.find((g) => g.number === context.game) || null : null;
  const loaded = Boolean(contextGame);
  const head = active || (loaded ? contextSeries : null);
  const sameKeys = (a, b) => a.length === b.length && a.every((key) => b.includes(key));
  const keys = (slots) => slots.filter(Boolean).map((s) => s.champion);
  const gameChanged = loaded && !(sameKeys(keys(view.ally), keys(contextGame.ally)) && sameKeys(keys(view.enemy), keys(contextGame.enemy))
    && sameKeys(view.bans, contextGame.bans));
  // Hard Fearless über die ganze Serie: Ein Champion aus einem anderen Spiel (auch einem späteren) darf nicht hinein.
  const clashes = loaded ? [...view.ally, ...view.enemy].filter(Boolean).map((slot) => ({ slot,
    other: contextSeries.games.find((g) => g.number !== contextGame.number && [...g.ally, ...g.enemy].some((s) => s.champion === slot.champion)) }))
    .filter((c) => c.other) : [];
  const gameName = loaded ? `Game ${contextGame.number}${contextSeries.id !== head.id ? ` von ${contextSeries.label ? `„${contextSeries.label}“` : 'der beendeten Serie'}` : ''}` : '';
  const saveGame = () => fearless.replaceGame(contextSeries.id, contextGame.number, seriesBoard(view, draftResult)).then((next) => {
    if (next) {
      if (next.draftsUpdated || next.draftsRecreated) {
        store.set({ draftArchiveRev: (store.get().draftArchiveRev || 0) + 1 });
      }
      toast(`${gameName} gespeichert${next.draftsUpdated ? ', auch unter „Drafts“' : ''}`);
    }
  });
  const closeGame = async () => {
    if (gameChanged && !(await confirmDialog({ title: 'Änderungen verwerfen?', icon: 'x', confirmLabel: 'Verwerfen',
      message: `${gameName} bleibt, wie es gespeichert ist.` }))) {
      return;
    }
    setView({ ally: DEFAULTS.ally, enemy: DEFAULTS.enemy, bans: [], picker: null, fearlessContext: null, loadedDraft: null });
  };
  const saveTip = !gameChanged ? 'Champions und Bans entsprechen dem gespeicherten Game (Rollen: „Rollen speichern“)'
    : !boardComplete ? 'Erst alle zehn Champions eintragen'
      : clashes.length ? `In der Serie schon gespielt: ${clashes.map((c) => `${data.championByKey.get(c.slot.champion)?.name} (Game ${c.other.number})`).join(', ')}`
        : advancedTips ? 'Überschreibt Champions, Rollen und Bans dieses Games; Ergebnis und Datum bleiben' : undefined;

  const finish = (result) => {
    fearless.finish(seriesBoard(view, draftResult), result).then((next) => {
      if (!next) {
        return;
      }
      setView({ ally: DEFAULTS.ally, enemy: DEFAULTS.enemy, bans: [], picker: null });
      // Das Spiel steht jetzt auch unter „Drafts“: die Liste dort neu laden.
      if (next.draftRecorded) {
        store.set({ draftArchiveRev: (store.get().draftArchiveRev || 0) + 1 });
      }
      const series = next.series;
      toast(next.ended ? `Serie beendet nach Game ${next.game}${series.wins + series.losses > 0 ? ` (${series.wins}:${series.losses})` : ''}`
        : `Game ${next.game} abgeschlossen, ${next.locked.length} Champions gesperrt`);
    });
  };
  const undo = async () => {
    const board = view.ally.some(Boolean) || view.enemy.some(Boolean) || view.bans.length > 0;
    if (board && !(await confirmDialog({ title: `Game ${active.games.length} zurücknehmen?`, icon: 'undo', confirmLabel: 'Zurücknehmen',
      message: 'Sein Draft ersetzt das aktuelle Brett, seine Champions sind wieder frei.' }))) {
      return;
    }
    fearless.undo().then((next) => {
      if (next && next.undone) {
        const game = next.undone;
        setView({ ally: boardSlots(game.ally, data), enemy: boardSlots(game.enemy, data),
          bans: game.bans.filter((key) => data.championByKey.has(key)).slice(0, MAX_BANS), picker: null, fearlessContext: null });
        toast(`Game ${game.number} zurückgenommen`);
      }
    });
  };
  const rename = (label, maxGames) => fearless.rename(head.id, label, maxGames).then((next) => {
    if (next) {
      setRenaming(false);
      const note = maxGamesNote(next, head, maxGames);
      if (note) {
        toast(note);
      }
    }
  });

  // Eigene Zeile unter dem Brett, alles nebeneinander: In der mittleren Spalte würde das Panel (und die mit jedem Spiel
  // wachsende Sperrleiste) die Team-Spalten in die Höhe strecken.
  // Ein- und ausgeschaltet wird über die Kachel unter den Bans (`FearlessTile`); ohne Serie und Formular bleibt die Zeile leer.
  return html`<div class="draft-fearless">
    ${head || form ? html`<div class="draft-fearless-main open">
      ${form ? html`
        <${Segmented} label="Format" value=${form.format} options=${FEARLESS_FORMATS} onChange=${(format) => setForm({ ...form, format })} />
        ${form.format === 'OPEN' ? html`<span data-tip=${advancedTips ? 'Nach so vielen Games endet die Serie von selbst; vorher lässt sie sich jederzeit beenden' : undefined}>
          <${Select} label="Anzahl Games" cls="draft-fearless-count" value=${form.maxGames} options=${GAME_COUNT_OPTIONS}
            onChange=${(maxGames) => setForm({ ...form, maxGames })} />
        </span>` : null}
        <input class="input draft-fearless-label" type="text" maxlength=${maxLength} spellcheck="false" value=${form.label} aria-label="Bezeichnung"
          placeholder="Bezeichnung (optional), z. B. Scrim Dienstag" onInput=${(e) => setForm({ ...form, label: e.currentTarget.value })}
          onKeyDown=${(e) => e.key === 'Enter' && start()} />
        <div class="draft-fearless-actions">
          <${Button} kind="primary" icon="ok" onClick=${start} disabled=${busy}>Serie starten<//>
          <${Button} kind="ghost" onClick=${() => setForm(null)}>Abbrechen<//>
        </div>` : null}
      ${head ? html`
        ${renaming ? html`<${LabelEditor} series=${head} maxLength=${maxLength} onSave=${rename} onCancel=${() => setRenaming(false)} />`
          : html`<div class="draft-fearless-head">
            <strong class="draft-fearless-title">${head.label || 'Fearless-Serie'}</strong>
            <${Button} icon="edit" kind="ghost" title=${editTitle(head)} onClick=${() => setRenaming(true)}
              tip=${advancedTips ? 'Freie Bezeichnung, nur lokal gespeichert' : undefined} />
          </div>`}
        <span class="muted small">${active ? `${seriesFormatLabel(active)} · ${seriesProgress(active)} · ${state.locked.length} gesperrt`
          : `${seriesFormatLabel(head)} · ${head.games.length} ${head.games.length === 1 ? 'Game' : 'Games'} · beendet`}</span>
        <div class="draft-fearless-actions">
          ${loaded ? html`
            <span class="small">${gameName} geladen</span>
            <${Button} kind="primary" icon="ok" onClick=${saveGame} disabled=${busy || !gameChanged || !boardComplete || clashes.length > 0}
              tip=${saveTip}>Änderungen in Game ${contextGame.number} speichern<//>
            <${Button} kind="ghost" icon="x" onClick=${closeGame} disabled=${busy}
              tip=${advancedTips ? `Leert das Brett${active ? '; danach geht es mit der laufenden Serie weiter' : ''}` : undefined}>Schließen<//>`
          : !active ? null
          : finishing ? html`
            <span class="small">Ergebnis für dein Team:</span>
            <${Button} kind="primary" onClick=${() => finish('WIN')} disabled=${busy}>Gewonnen<//>
            <${Button} onClick=${() => finish('LOSS')} disabled=${busy}>Verloren<//>
            <${Button} kind="ghost" onClick=${() => finish(null)} disabled=${busy}>Ohne Ergebnis<//>
            <${Button} icon="x" kind="icon" title="Abbrechen" onClick=${() => setFinishing(false)} />`
          : html`
            <${Button} icon="lock" onClick=${() => setFinishing(true)} disabled=${!boardComplete || busy || Boolean(context)}
              tip=${context ? 'Erst das geladene Serien-Game schließen' : !boardComplete ? 'Erst alle zehn Champions eintragen' : advancedTips ? 'Sperrt die zehn Picks für die folgenden Games und leert das Brett' : undefined}>Game ${active.nextGame} abschließen<//>
            <${Button} icon="undo" kind="icon" title="Letztes Game zurücknehmen" onClick=${undo} disabled=${busy || active.games.length === 0}
              tip=${active.games.length ? `Game ${active.games.length} zurücknehmen` : undefined} />`}
        </div>` : null}
    </div>` : null}
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
        Läuft gerade kein Fearless-Game?</span>
      <div class="draft-fearless-actions"><${Button} onClick=${() => toggle(false)} disabled=${busy}>Serie beenden<//></div>
    </div>` : null}
    ${offer && active && !finishing ? html`<div class="draft-fearless-notice offer">
      <${Icon} name="lock" size=${15} />
      <span>Champion-Auswahl beendet. Draft als Game ${active.nextGame} der Serie übernehmen?</span>
      <div class="draft-fearless-actions">
        <${Button} kind="primary" onClick=${() => { setOffer(false); setFinishing(true); }}>Übernehmen<//>
        <${Button} kind="ghost" onClick=${() => setOffer(false)}>Nicht übernehmen<//>
      </div>
    </div>` : null}
    <${FearlessLocks} locked=${locked} data=${data} />
    ${fearless.error ? html`<${ErrorNote} prefix="Fearless" error=${fearless.error} />` : null}
  </div>`;
}

/** Gesperrte Champions nach Spiel, nebeneinander in der Fearless-Zeile; einklappbar, der Stand wird gespeichert. */
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
    ${open ? html`<div class="draft-locks-games">${[...games].map(([game, champions]) => {
      // Je Spiel zuerst die fünf Picks deines Teams, dann die des Gegners, jeweils Top bis Support (so gespeichert).
      // Die Spiele stehen untereinander in festen Spalten, damit Top unter Top steht; Unbekanntes hält seinen Platz frei.
      const icons = (keys) => keys.map((key, i) => {
        const champion = data.championByKey.get(key);
        return champion ? html`<span key=${key} class="draft-lock-icon" data-tip=${`${champion.name}: in Game ${game} gespielt`}>
          <${Img} src=${champion.icon} size=${24} alt=${champion.name} />
        </span>` : html`<span key=${`empty-${i}`} class="draft-lock-icon empty"></span>`;
      });
      return html`<div class="draft-locks-game" key=${game}>
        <span class="muted small">Game ${game}</span>
        <span class="draft-locks-team ally" aria-label="Dein Team">${icons(champions.slice(0, TEAM_SIZE))}</span>
        ${champions.length > TEAM_SIZE ? html`<span class="draft-locks-team enemy" aria-label="Gegner">${icons(champions.slice(TEAM_SIZE))}</span>` : null}
      </div>`;
    })}</div>` : null}
  </div>`;
}

/**
 * „Beste Picks“ beginnt für dein Team: beim Start der App (auch wenn zuletzt der Gegner gewählt war) und bei jedem Wechsel
 * auf den Reiter. Zum Gegner wechselt die Seite nur von selbst, wenn ein Gegner-Pick entfernt wird (am Brett oder per
 * Klick im Archiv), oder von Hand.
 */
let sideResetDone = false;

export function DraftView() {
  const data = useStore((s) => s.data);
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const dataSource = useStore((s) => s.dataSource);
  const [view, setView] = useViewState('draft', DEFAULTS);
  useEffect(() => {
    if (!sideResetDone) {
      sideResetDone = true;
      if (view.side !== 'ally') {
        setView({ side: 'ally' });
      }
    }
  }, []);
  const fearless = useFearless();
  // Start-Formular der Serie: geöffnet über die Fearless-Kachel, angezeigt in der Fearless-Zeile.
  const [fearlessForm, setFearlessForm] = useState(null);
  const fearlessActive = fearless.state?.active || null;
  const fearlessContext = view.fearlessContext || null;
  // Champion-Key → Spiel, in dem er gespielt wurde. Ein geladenes Serienspiel bringt seine eigenen Sperren mit.
  const lockedList = fearlessContext ? fearlessContext.locked : fearlessActive ? fearless.state.locked : NO_LOCKS;
  const locked = useMemo(() => new Map(lockedList.map((l) => [l.champion, l.game])), [lockedList]);
  const { result, error, loading, completing, requestId } = useDraftResult(view, dataSource, locked);
  // Für die Archiv-Aufnahme (Modulebene) merken, was das Brett an Auto-Rollen zeigt; ohne offenen Tab gibt es nichts.
  useEffect(() => {
    shownSlots = loading || !result ? null : result.slots;
    return () => {
      shownSlots = null;
    };
  }, [result, loading]);
  const sourceLabel = useSourceLabel();
  const ownMatchups = useOwnMatchups();
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
  // Auch bei gemerktem Ergebnis: Dann bleibt "loading" false, nur das Ergebnis wechselt.
  useEffect(() => {
    if (!loading) {
      setPendingRole(null);
    }
  }, [loading, result]);

  const setSlotRole = (teamName, index, role) => {
    setPendingRole(`${teamName}:${view[teamName][index].champion}`);
    // Eine fest vergebene Rolle wandert: Der bisherige Champion dieser Rolle wird wieder automatisch zugeteilt.
    const team = view[teamName].map((slot, i) => {
      if (i === index) {
        return { ...slot, role: role || null };
      }
      return slot && role && slot.role === role ? { ...slot, role: null } : slot;
    });
    // Ein geladener gespeicherter Draft wird erst mit „Rollen speichern“ überschrieben.
    setView({ [teamName]: team });
  };

  const stored = storedTarget(view, fearless.state);
  const storedState = stored ? compareStored(stored, view.ally, view.enemy) : null;
  const [savingRoles, setSavingRoles] = useState(false);
  const saveRoles = (board) => {
    setSavingRoles(true);
    // Die jetzt angezeigten Auto-Rollen gehen mit (nur zum Sortieren); ohne passende Analyse bleibt die Liste leer.
    saveStoredRoles(stored, { ...board, shownRoles: shownRolesOf(view, loading ? null : result?.slots) }, setView)
      .then((also) => toast(`Rollen ${stored.kind === 'game' ? `in Game ${stored.number} der Serie` : 'im gespeicherten Draft'} übernommen${also}`))
      .catch((e) => toast(`Rollen nicht gespeichert: ${e.message || e}`))
      .finally(() => setSavingRoles(false));
  };

  // Ein entfernter Gegner-Pick schaltet „Beste Picks“ auf den Gegner um (was an seiner Stelle am stärksten wäre);
  // sonst bleibt die Seite, wie sie ist.
  const clearSlot = (teamName, index) => {
    const team = [...view[teamName]];
    team[index] = null;
    setView({ [teamName]: team, ...(teamName === 'enemy' ? { side: 'enemy' } : {}) });
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

  const reset = () => setView({ ally: DEFAULTS.ally, enemy: DEFAULTS.enemy, bans: [], fearlessContext: null, loadedDraft: null });
  const picks = view.ally.filter(Boolean).length + view.enemy.filter(Boolean).length;

  return html`<div class="page draft">
    <section class="draft-board">
      <${TeamColumn} title="Dein Team" team="ally" slots=${view.ally} result=${result} data=${data} pending=${pendingRole} hints=${hints.ally}
        onPick=${(index) => setView({ picker: { team: 'ally', index } })} onRole=${setSlotRole} onClear=${clearSlot} />
      <div class="draft-center">
        <${WinPanel} analysis=${result?.analysis} picks=${picks} loading=${loading} completing=${completing} />
        <${BanRow} bans=${view.bans} data=${data} onAdd=${() => setView({ picker: { team: 'bans' } })}
          onRemove=${(key) => setView({ bans: view.bans.filter((b) => b !== key) })} />
        <div class="draft-modes">
          <${FearlessTile} fearless=${fearless} form=${fearlessForm} setForm=${setFearlessForm} />
          <${ClientTile} lcu=${client.lcu} onToggle=${client.toggle} />
        </div>
        <div class="draft-actions">
          <div class="draft-actions-main">
            <${Button} icon="trash" onClick=${reset} disabled=${picks === 0 && view.bans.length === 0 && !fearlessContext}>Draft leeren<//>
            ${storedState && storedState.sameChampions ? html`<${Button} icon="ok" onClick=${() => saveRoles(storedState.board)}
              disabled=${savingRoles || !storedState.rolesChanged || (loading && stored.kind === 'draft')}
              title=${storedState.rolesChanged ? 'Rollen in den gespeicherten Draft übernehmen' : 'Rollen sind gespeichert'}
              tip=${advancedTips ? `Überschreibt nur die Rollen ${stored.kind === 'game' ? `von Game ${stored.number} der Fearless-Serie` : 'des geladenen Drafts im Archiv'}; mit anderen Champions entsteht wie bisher ein neuer Draft` : undefined}>
              ${storedState.rolesChanged ? 'Rollen speichern' : 'Gespeichert'}<//>` : null}
          </div>
          <div class="draft-actions-side">
            <${QuickFilter} view=${view} setView=${setView} result=${result} />
            <${DraftOptions} view=${view} setView=${setView} sourceLabel=${sourceLabel} data=${data} />
          </div>
        </div>
      </div>
      <${TeamColumn} title="Gegner" team="enemy" slots=${view.enemy} result=${result} data=${data} pending=${pendingRole} hints=${hints.enemy}
        onPick=${(index) => setView({ picker: { team: 'enemy', index } })} onRole=${setSlotRole} onClear=${clearSlot} />
    </section>
    <${FearlessPanel} fearless=${fearless} form=${fearlessForm} setForm=${setFearlessForm} view=${view} setView=${setView} data=${data} locked=${locked} draftResult=${result}
      boardComplete=${picks === 2 * TEAM_SIZE} lcuStatus=${client.lcu?.status} conflicts=${client.champSelect ? lockedOnBoard : NO_LOCKS} />

    ${error ? html`<${ErrorNote} prefix="Draft konnte nicht berechnet werden" error=${error} />` : null}
    ${loading || completing ? html`<${ProgressNote} requestId=${requestId} completing=${completing} />` : null}
    ${result && result.warnings.length ? html`<ul class="draft-warnings">
      ${result.warnings.map((w, i) => html`<li key=${i}><${Icon} name="alert" size=${14} /> ${w}</li>`)}
    </ul>` : null}

    <div class="draft-tabs">
      <${Segmented} label="Bereich" value=${view.tab}
        onChange=${(tab) => setView(tab === 'picks' && view.tab !== 'picks' ? { tab, side: 'ally' } : { tab })}
        options=${[{ value: 'picks', label: 'Beste Picks' }, { value: 'analysis', label: 'Draft-Analyse' }, { value: 'archive', label: 'Archiv' }]} />
      ${view.tab === 'archive' ? null : html`<${ActiveOptions} view=${view} setView=${setView} />`}
    </div>

    ${view.tab !== 'archive' ? html`<${OwnMatchupCard} own=${ownMatchups} view=${view} result=${loading ? null : result} hints=${hints.ally} data=${data} />` : null}
    ${view.tab === 'archive' ? html`<${Archive} data=${data} view=${view} setView=${setView} />`
      : !result ? (loading ? null : html`<${Empty} title="Noch keine Daten" />`)
      : view.tab === 'picks'
        ? html`<${Suggestions} result=${result} data=${data} view=${view} setView=${setView} onTake=${takeSuggestion} busy=${loading} owned=${owned} />`
        : html`<${Analysis} result=${result} data=${data} picks=${picks} />`}

    ${view.picker ? html`<${ChampionPicker} data=${data} used=${used} locked=${locked} markAvoid=${view.picker.team === 'ally'}
      title=${view.picker.team === 'bans' ? 'Champion bannen' : view.picker.team === 'ally' ? 'Pick für dein Team' : 'Pick des Gegners'}
      onChoose=${(champion) => assign(view.picker, champion)} onClose=${() => setView({ picker: null })} />` : null}
    ${view.avoidPicker ? html`<${AvoidPicker} data=${data} onClose=${() => setView({ avoidPicker: false })} />` : null}
    ${view.favoritePicker ? html`<${FavoritePicker} data=${data} onClose=${() => setView({ favoritePicker: false })} />` : null}
  </div>`;
}

// ------------------------------------------------------------------ Draft-Optionen

/**
 * Schalter des Menüs abseits des Standards, je mit Text und Rücksetzung. Das Menü zählt sie, die Tags in der
 * Bereichszeile zeigen sie über der Liste, auf die sie wirken: So bleibt nichts unbemerkt eingeschaltet.
 * Pickrate bzw. Mindestspiele fehlen hier, sie stehen ohnehin sichtbar neben dem Menü (`QuickFilter`).
 */
function changedOptions(view) {
  const changed = [];
  if (view.ignoreChampionWinRates) {
    changed.push({ key: 'synergy', label: 'Nur Synergien und Matchups', reset: { ignoreChampionWinRates: false } });
  }
  if (view.rarePicks) {
    changed.push({ key: 'rare', label: 'Seltene Picks', reset: { rarePicks: false } });
  }
  if (view.favoritesOnly) {
    changed.push({ key: 'favorites', label: 'Nur Favoriten', reset: { favoritesOnly: false } });
  }
  return changed;
}

/**
 * Mindest-Pickrate (bzw. mit „Seltene Picks zeigen“ die Mindestspiele) direkt neben „Draft-Optionen“: Der Filter wird
 * während eines Drafts am häufigsten nachgestellt und soll ohne Aufklappen erreichbar sein.
 */
function QuickFilter({ view, setView, result }) {
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  // Eigener Pfeil statt des Browser-Pfeils: schmaler und an fester Stelle, der Fokus umrahmt den ganzen Kasten.
  const caret = html`<span class="draft-quick-caret" aria-hidden="true"><${Icon} name="chevron" size=${14} /></span>`;
  return view.rarePicks ? html`<label class="draft-quick-filter" data-tip=${advancedTips ? 'Mindestspiele eines Vorschlags auf seiner Rolle in den letzten 30 Tagen. Auto richtet sich nach der Spielmenge des Rang-Bereichs; in Emerald+ entspricht das etwa dem Standard von DraftGap.' : undefined}>
      <span class="draft-quick-label">Games</span>
      <${Select} label="Mindestspiele" value=${view.minGames} onChange=${(minGames) => setView({ minGames })}
        options=${[{ value: AUTO_MIN_GAMES, label: autoLabel(result, true) }, ...MIN_GAMES]} />
      ${caret}
    </label>`
    : html`<label class="draft-quick-filter" data-tip=${advancedTips ? 'Mindest-Pickrate eines Vorschlags auf seiner Rolle in den letzten 30 Tagen, in jedem Rang-Bereich gleich streng. Höher heißt weniger exotische Picks.' : undefined}>
      <span class="draft-quick-label">Pickrate</span>
      <${Select} label="Mindest-Pickrate" value=${view.minPickRate || AUTO_MIN_GAMES} onChange=${(minPickRate) => setView({ minPickRate })}
        options=${[{ value: AUTO_MIN_GAMES, label: autoLabel(result, false) }, ...MIN_PICK_RATES]} />
      ${caret}
    </label>`;
}

/** Champion-Keys der Favoriten zum Üben (Set). */
function usePractice() {
  const info = useFavoriteInfo();
  return useMemo(() => new Set(Object.entries(info).filter(([, i]) => i.practice).map(([key]) => Number(key))), [info]);
}

/** Champion-Keys der Off-Meta-Favoriten (Set). */
function useOffMeta() {
  const info = useFavoriteInfo();
  return useMemo(() => new Set(Object.entries(info).filter(([, i]) => i.offMeta).map(([key]) => Number(key))), [info]);
}

/** Schilder „Üben“ (gold) und „Off-Meta“ (violett) am Namen eines Favoriten. */
function FavTags({ practice = false, offMeta = false }) {
  return html`${practice ? html`<span class="draft-fav-tag practice">Üben</span>` : null}${offMeta ? html`<span class="draft-fav-tag offmeta">Off-Meta</span>` : null}`;
}

/** Stern für Favoriten; zum Üben hohl statt gefüllt. */
function FavMark({ practice = false, size = 14, tip }) {
  return practice
    ? html`<${FavoriteMark} size=${size} tip=${tip === '' ? '' : 'Favorit zum Üben'} cls="practice" />`
    : html`<${FavoriteMark} size=${size} tip=${tip} />`;
}

/** Kurzform der Rollen für das Icon in der Favoritenliste. */
const ROLE_LETTER = { TOP: 'T', JUNGLE: 'J', MIDDLE: 'M', BOTTOM: 'A', UTILITY: 'S' };

/**
 * Favoriten in den Draft-Optionen: alle als Icons, „+“ fügt hinzu (Auswahl bleibt offen). Ein Klick aufs Icon öffnet
 * darunter Rollen (Mehrfachwahl; ohne Rolle gilt jede), „Üben“ und „Off-Meta“. Mit Rollen zeigt der Draft den
 * Favoriten fürs eigene Team nur auf diesen Rollen. Wirkt sofort, nur lokal.
 */
function FavoriteList({ data, setView }) {
  const favorites = useStore((s) => s.favorites);
  const info = useFavoriteInfo();
  const [editing, setEditing] = useState(null);
  const practiceCount = favorites.filter((key) => info[key]?.practice).length;
  const offMetaCount = favorites.filter((key) => info[key]?.offMeta).length;
  const counts = [practiceCount ? `${practiceCount} zum Üben` : '', offMetaCount ? `${offMetaCount} Off-Meta` : ''].filter(Boolean).join(', ');
  const current = editing !== null && favorites.includes(editing) ? data.championByKey.get(editing) : null;
  const roles = current ? info[editing]?.roles || [] : [];
  const toggleRole = (role) => setFavoriteRoles(editing, roles.includes(role) ? roles.filter((r) => r !== role) : [...roles, role]);
  return html`<div class="draft-avoid draft-favs">
    <span class="menu-row-text"><span>Favoriten (${favorites.length}${counts ? `, davon ${counts}` : ''})</span>
      <small class="muted">Klick aufs Icon: Rollen, Üben, Off-Meta</small></span>
    <div class="draft-avoid-list">
      ${favorites.map((key) => {
        const champion = data.championByKey.get(key);
        if (!champion) {
          return null;
        }
        const own = info[key] || {};
        const letters = (own.roles || []).map((r) => ROLE_LETTER[r]).join('');
        return html`<button type="button" key=${key} aria-pressed=${editing === key}
          class=${`draft-avoid-item draft-fav-item ${own.practice ? 'practice' : ''} ${own.offMeta ? 'offmeta' : ''} ${editing === key ? 'active' : ''}`}
          aria-label=${`${champion.name}${own.practice ? ', zum Üben' : ''}${own.offMeta ? ', Off-Meta' : ''}${letters ? `, Rollen: ${own.roles.map(roleLabel).join(', ')}` : ''}: bearbeiten`}
          onClick=${() => setEditing(editing === key ? null : key)}>
          <${Img} src=${champion.icon} size=${30} />${letters ? html`<span class="draft-fav-roles">${letters}</span>` : null}
        </button>`;
      })}
      <button type="button" class="draft-avoid-item add" aria-label="Champion zu den Favoriten hinzufügen" onClick=${() => setView({ favoritePicker: true })}>
        <${Icon} name="plus" size=${15} />
      </button>
    </div>
    ${current ? html`<div class="draft-fav-editor">
      <strong>${current.name}</strong>
      <div class="draft-fav-role-pick" role="group" aria-label=${`Rollen von ${current.name}`}>
        ${ROLES.map((r) => html`<button type="button" key=${r.value} class=${`chip ${roles.includes(r.value) ? 'active' : ''}`}
          aria-pressed=${roles.includes(r.value)} onClick=${() => toggleRole(r.value)}>${r.label}</button>`)}
      </div>
      <small class="muted">${roles.length ? 'Vorschläge fürs eigene Team nur auf diesen Rollen' : 'Keine Rolle gewählt: Vorschlag auf jeder Rolle'}</small>
      <div class="draft-fav-editor-foot">
        <div class="draft-fav-flags">
          <${Switch} checked=${Boolean(info[editing]?.practice)} onChange=${(practice) => setFavoritePractice(editing, practice)}>Üben<//>
          <${Switch} checked=${Boolean(info[editing]?.offMeta)} onChange=${(offMeta) => setFavoriteOffMeta(editing, offMeta)}>Off-Meta<//>
        </div>
        <${Button} kind="ghost" icon="trash" onClick=${() => { toggleFavorite(editing); setEditing(null); }}>Entfernen<//>
      </div>
    </div>` : null}
  </div>`;
}

/** Rotes Kennzeichen der Marvins-Liste: Champion solltest du besser nicht spielen. */
function AvoidTag() {
  return html`<span class="draft-avoid-tag"><${Icon} name="x" size=${10} />Besser nicht spielen</span>`;
}

/**
 * Marvins-Liste in den Draft-Optionen: Champions, die man besser nicht spielt. Anzeigen, per „+“ (Auswahl wie beim Pick)
 * hinzufügen, per Klick aufs Icon entfernen. Wirkt sofort und nur lokal, wie die Favoriten.
 */
function AvoidList({ data, setView }) {
  const avoid = useStore((s) => s.avoid);
  const clear = async () => {
    if (await confirmDialog({ title: `Alle ${avoid.length} Champions von der Marvins-Liste entfernen?`, confirmLabel: 'Entfernen', danger: true,
      message: 'Im Draft wird danach kein Champion mehr als „Besser nicht spielen“ markiert.' })) {
      clearAvoid();
    }
  };
  return html`<div class="draft-avoid">
    <span class="menu-row-text"><span>Besser nicht spielen (Marvins-Liste)</span>
      <small class="muted">Im Draft rot markiert: dein Team, Picks, Vorschläge</small></span>
    <div class="draft-avoid-list">
      ${avoid.map((key) => {
        const champion = data.championByKey.get(key);
        return champion ? html`<button type="button" key=${key} class="draft-avoid-item" aria-label=${`${champion.name} von der Marvins-Liste entfernen`}
          onClick=${() => toggleAvoid(key)}>
          <${Img} src=${champion.icon} size=${30} /><span class="draft-avoid-remove"><${Icon} name="x" size=${14} /></span>
        </button>` : null;
      })}
      <button type="button" class="draft-avoid-item add" aria-label="Champion zur Marvins-Liste hinzufügen" onClick=${() => setView({ avoidPicker: true })}>
        <${Icon} name="plus" size=${15} />
      </button>
    </div>
    ${avoid.length ? html`<${Button} kind="ghost" icon="trash" onClick=${clear}>Marvins-Liste leeren<//>` : null}
  </div>`;
}

/**
 * Menü „Draft-Optionen“ an der Stelle der früheren Quellenangabe: Schalter für die Vorschläge, Favoriten (mit Rollen
 * und Üben) und Marvins-Liste. Gilt nur im Draft-Tab, Zeitraum und Rang bleiben in der Kopfleiste.
 */
function DraftOptions({ view, setView, sourceLabel, data }) {
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const changed = changedOptions(view);
  const resetAll = () => setView(Object.assign({}, ...changed.map((c) => c.reset)));
  // Kurz „Optionen“: Mit der Pickrate davor passt die Zeile so neben „Draft leeren“; die Überschrift im Menü bleibt lang.
  // Auswahl für Favoriten oder Marvins-Liste liegt außerhalb des Menüs: Es bleibt offen, bis sie geschlossen ist.
  return html`<${MenuButton} cls="draft-options-menu" icon="sliders" label="Optionen" badge=${changed.length} title="Draft-Optionen"
    keepOpen=${Boolean(view.favoritePicker || view.avoidPicker)}
    tip=${advancedTips ? `Filter und Bewertung der Vorschläge. Daten: ${sourceLabel}` : undefined}>
    <div class="menu-row">
      <${Switch} checked=${view.ignoreChampionWinRates} onChange=${(ignoreChampionWinRates) => setView({ ignoreChampionWinRates })}
        tip=${advancedTips ? 'Bewertet nur Synergien und Matchups, nicht die allgemeine Stärke der Champions' : undefined}>
        <span class="menu-row-text"><span>Nur Synergien und Matchups</span><small class="muted">Ohne die allgemeine Stärke der Champions</small></span>
      <//>
    </div>
    <div class="menu-row">
      <${Switch} checked=${Boolean(view.rarePicks)} onChange=${(rarePicks) => setView({ rarePicks })}
        tip=${advancedTips ? 'Bisheriges Verhalten: auch Off-Role-Picks wie Tahm Kench ADC, Filter nur nach Spielzahl. Sonst braucht ein Vorschlag mindestens 10 % der Spiele seines Champions auf dieser Rolle und die gewählte Pickrate.' : undefined}>
        <span class="menu-row-text"><span>Seltene Picks zeigen</span><small class="muted">Auch Off-Role-Picks, Filter nach Spielzahl</small></span>
      <//>
    </div>
    <div class="menu-row">
      <${Switch} checked=${Boolean(view.favoritesOnly)} onChange=${(favoritesOnly) => setView({ favoritesOnly })}
        tip=${advancedTips ? 'Beste Picks für dein Team zeigen nur Favoriten, auch die zum Üben, auf ihren Rollen. Die Champion-Auswahl bleibt vollständig.' : undefined}>
        <span class="menu-row-text"><span>Nur Favoriten</span><small class="muted">Vorschläge fürs eigene Team nur aus deinen Favoriten</small></span>
      <//>
    </div>
    <${FavoriteList} data=${data} setView=${setView} />
    <${AvoidList} data=${data} setView=${setView} />
    <div class="menu-foot">
      <span class="muted small" data-tip=${advancedTips ? 'Zeitraum und Rang-Bereich stellst du oben in der Kopfleiste ein' : undefined}>${sourceLabel}</span>
      ${changed.length ? html`<${Button} kind="ghost" icon="undo" onClick=${resetAll}>Standard<//>` : null}
    </div>
  <//>`;
}

/** Tags der geänderten Draft-Optionen rechts in der Bereichszeile; ✕ setzt die jeweilige Option zurück. */
function ActiveOptions({ view, setView }) {
  const changed = changedOptions(view);
  if (!changed.length) {
    return null;
  }
  return html`<div class="draft-active-options" aria-label="Geänderte Draft-Optionen">
    ${changed.map((c) => html`<button type="button" key=${c.key} class="tag draft-option-tag" aria-label=${`${c.label} zurücksetzen`}
      data-tip=${`${c.label} zurücksetzen`} onClick=${() => setView(c.reset)}>
      <span>${c.label}</span><${Icon} name="x" size=${12} />
    </button>`)}
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
  const practice = usePractice();
  const offMeta = useOffMeta();
  const avoid = useAvoid();
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
      // Marvins-Liste gilt nur für dich, also nur im eigenen Team.
      const avoided = ally && avoid.has(slot.champion);
      const practicing = favorite && practice.has(slot.champion);
      const unusual = favorite && offMeta.has(slot.champion);
      const tagged = avoided || practicing || unusual;
      return html`<div key=${index} class=${`draft-slot ${favorite ? 'favorite' : ''} ${practicing ? 'practice' : ''} ${unusual ? 'offmeta' : ''} ${avoided ? 'avoid' : ''}`}>
        <button type="button" class="icon-link draft-slot-icon" onClick=${() => openChampion(champion, ally ? 'stats' : 'counters', role)}
          aria-label=${`${champion.name}: ${ally ? 'Match-Statistik' : 'Counters'} öffnen`}
          data-tip=${!advancedTips ? undefined : ally ? `Build von ${champion.name} öffnen (Match-Statistik)` : `Counters gegen ${champion.name} öffnen`}>
          <${Img} src=${champion.icon} size=${40} />
        </button>
        <button type="button" class="draft-slot-champion" onClick=${() => onPick(index)} data-tip=${advancedTips ? 'Champion ändern' : undefined}>
          ${tagged ? html`<span class="draft-name-stack"><span class="draft-name">${champion.name}</span>
              <span class="draft-tag-row">${avoided ? html`<${AvoidTag} />` : null}<${FavTags} practice=${practicing} offMeta=${unusual} /></span></span>`
            : html`<span class="draft-name">${champion.name}</span>`}
          ${favorite ? html`<${FavMark} size=${14} practice=${practicing} />` : null}
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

/** Auswahl für die Marvins-Liste: bleibt offen, damit sich mehrere Champions nacheinander hinzufügen lassen. */
function AvoidPicker({ data, onClose }) {
  const avoid = useAvoid();
  return html`<${ChampionPicker} data=${data} used=${avoid} locked=${NO_PICKER_LOCKS} title="Besser nicht spielen: zur Marvins-Liste"
    onChoose=${(key) => toggleAvoid(key)} onClose=${onClose} />`;
}

/** Auswahl für neue Favoriten; bleibt offen wie die der Marvins-Liste. */
function FavoritePicker({ data, onClose }) {
  const favorites = useFavorites();
  return html`<${ChampionPicker} data=${data} used=${favorites} locked=${NO_PICKER_LOCKS} title="Zu den Favoriten"
    onChoose=${(key) => toggleFavorite(key)} onClose=${onClose} />`;
}

function ChampionPicker({ data, used, locked, title, onChoose, onClose, markAvoid = false }) {
  const [query, setQuery] = useState('');
  const favorites = useFavorites();
  const practice = usePractice();
  const offMeta = useOffMeta();
  const avoidAll = useAvoid();
  const avoid = markAvoid ? avoidAll : NO_AVOID;
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
            onClick=${() => !game && onChoose(c.key)}
            class=${`${favorites.has(c.key) ? 'favorite' : ''} ${practice.has(c.key) ? 'practice' : ''} ${offMeta.has(c.key) ? 'offmeta' : ''} ${avoid.has(c.key) ? 'avoid' : ''} ${game ? 'locked' : ''}`}
            data-tip=${game ? `${c.name}: in Game ${game} gespielt (Fearless)` : undefined}>
            <${Img} src=${c.icon} size=${48} /><span>${c.name}</span>
            ${favorites.has(c.key) ? html`<${FavMark} size=${13} practice=${practice.has(c.key)} />` : null}
            ${practice.has(c.key) || offMeta.has(c.key) ? html`<span class="draft-picker-tags"><${FavTags} practice=${practice.has(c.key)} offMeta=${offMeta.has(c.key)} /></span>` : null}
            ${avoid.has(c.key) ? html`<span class="avoid-mark" aria-label="Besser nicht spielen"><${Icon} name="x" size=${12} /></span>` : null}
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
  const favorites = useFavorites();
  const favoriteInfo = useFavoriteInfo();
  const practice = usePractice();
  const offMeta = useOffMeta();
  // Nur für dein Team: Favoriten mit festen Rollen erscheinen nur dort; „Nur Favoriten“ blendet alle anderen aus.
  const ally = result.side === 'ally';
  const favoritesOnly = ally && Boolean(view.favoritesOnly);
  const rows = useMemo(() => result.suggestions.filter((s) => {
    const roles = ally && favorites.has(s.champion) ? favoriteInfo[s.champion]?.roles : null;
    return (!role || s.role === role) && (!ownedOnly || owned.has(s.champion))
      && (!roles || roles.length === 0 || roles.includes(s.role)) && (!favoritesOnly || favorites.has(s.champion));
  }), [result, role, ownedOnly, owned, ally, favorites, favoriteInfo, favoritesOnly]);
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
  const avoidAll = useAvoid();
  // Marvins-Liste nur bei Vorschlägen für dein Team: Gegner-Picks spielst nicht du.
  const avoid = result.side === 'ally' ? avoidAll : NO_AVOID;
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
      ${result.openRoles.length === 0 ? 'Dieses Team ist vollständig.'
        : favoritesOnly ? 'Kein Favorit unter den Vorschlägen. Schalte „Nur Favoriten“ in den Optionen aus, senke die Pickrate oder prüfe die Rollen deiner Favoriten.'
        : view.rarePicks ? 'Senke die Mindestspiele oder wähle eine andere Rolle.' : 'Senke die Pickrate, zeige seltene Picks oder wähle eine andere Rolle.'}<//>` : html`
      <h3 class="draft-section-title">Top ${top.length} für ${sideText}${role ? ` auf ${roleLabel(role)}` : ''}</h3>
      <div class="draft-top">
        ${top.map((s, i) => html`<${TopCard} key=${`${s.champion}-${s.role}`} rank=${i + 1} suggestion=${s} data=${data} onTake=${onTake} baseText=${baseText}
          favorite=${favorites.has(s.champion)} practicing=${practice.has(s.champion)} unusual=${offMeta.has(s.champion)} avoided=${avoid.has(s.champion)} />`)}
      </div>
      ${rest.length ? html`<${Card} title="Weitere Picks">
        <div class="grid-scroll"><table class="data draft-table">
          <thead><tr>${columns.map(headCell)}</tr></thead>
          <tbody>${rest.map((s) => {
            const champion = data.championByKey.get(s.champion);
            const favorite = favorites.has(s.champion);
            const avoided = avoid.has(s.champion);
            const practicing = favorite && practice.has(s.champion);
            const unusual = favorite && offMeta.has(s.champion);
            return html`<tr key=${`${s.champion}-${s.role}`} class=${`draft-row ${favorite ? 'favorite' : ''} ${practicing ? 'practice' : ''} ${unusual ? 'offmeta' : ''} ${avoided ? 'avoid' : ''}`} onClick=${() => onTake(s)} data-tip=${advancedTips ? 'Pick übernehmen' : undefined}>
              <td class="muted">${s.rank}</td>
              <td><span class="draft-cell-champion">${champion ? html`<${Img} src=${champion.icon} size=${26} />` : null}${champion?.name || s.champion}${favorite ? html`<${FavMark} size=${13} practice=${practicing} />` : null}<${FavTags} practice=${practicing} offMeta=${unusual} />${avoided ? html`<${AvoidTag} />` : null}</span></td>
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

function TopCard({ rank, suggestion: s, data, onTake, baseText, favorite, practicing = false, unusual = false, avoided = false }) {
  const advancedTips = useStore((st) => Boolean(st.prefs.advancedTips));
  const champion = data.championByKey.get(s.champion);
  const favorites = useFavorites();
  const details = [...s.details].sort((a, b) => Math.abs(b.rating) - Math.abs(a.rating)).slice(0, 3);
  const practice = favorite && practicing;
  const offMeta = favorite && unusual;
  return html`<button type="button" class=${`draft-top-card ${rank === 1 ? 'best' : ''} ${favorite ? 'favorite' : ''} ${practice ? 'practice' : ''} ${offMeta ? 'offmeta' : ''} ${avoided ? 'avoid' : ''}`} onClick=${() => onTake(s)}
    data-tip=${advancedTips ? `Pick übernehmen · Winrate ${baseText} mit diesem Pick` : undefined}>
    <span class="draft-rank">${rank}</span>
    <span class="draft-top-head">
      ${champion ? html`<${Img} src=${champion.icon} size=${48} />` : null}
      <span class="draft-top-name"><strong>${champion?.name || s.champion}${favorite ? html` <${FavMark} size=${14} practice=${practice} />` : null}</strong><${Tag} kind="soft">${roleLabel(s.role)}<//>
        ${practice || offMeta || avoided ? html`<span class="draft-tag-row"><${FavTags} practice=${practice} offMeta=${offMeta} />${avoided ? html`<${AvoidTag} />` : null}</span>` : null}</span>
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
// ------------------------------------------------------------------ Eigene Bilanz (Mein Konto)

/**
 * Eigene Matchup-Bilanz aus den gespeicherten Matches des eigenen Kontos (Summoner-Tab). Nur lesen, kein Riot-Abruf
 * und keine Daten anderer Spieler: Gezeigt werden allein deine Ergebnisse gegen die Champions auf dem Brett.
 */
function useOwnMatchups() {
  const [own, setOwn] = useState(null);
  useEffect(() => {
    api.get('/api/summoner/own').then(setOwn).catch(() => setOwn(null));
  }, []);
  return own;
}

const OWN_TOP_CHAMPIONS = 3;

function record(games, wins) {
  return games ? `${wins}S ${games - wins}N` : '–';
}

function OwnRecord({ games, wins }) {
  if (!games) {
    return html`<span class="muted">keine Spiele</span>`;
  }
  const wr = wins / games;
  return html`<span><strong class=${wr >= 0.52 ? 'good' : wr < 0.48 ? 'bad' : ''}>${percent(wr, 0)}</strong> <span class="muted">${record(games, wins)}</span></span>`;
}

function OwnMatchupCard({ own, view, result, hints, data }) {
  if (!own || !own.accounts || own.accounts.length === 0 || !own.matchups) {
    return null;
  }
  const { matchups, accounts } = own;
  const accountLabel = accounts.length === 1 ? accounts[0].riotId : `${accounts.length} Konten`;
  const roleOf = (team, slot) => slot && (slot.role || (result?.slots[team] || []).find((s) => s.champion === slot.champion)?.role) || null;
  const localIndex = hints.findIndex((h) => h && h.local);
  const mySlot = localIndex >= 0 ? view.ally[localIndex] : null;
  const myChampion = mySlot ? mySlot.champion : localIndex >= 0 ? hints[localIndex].intent || null : null;
  const myRole = roleOf('ally', mySlot);
  const enemies = view.enemy.filter(Boolean).map((slot) => ({ slot, role: roleOf('enemy', slot) }));
  const label = (key) => data.championByKey.get(key)?.name || `Champion ${key}`;
  const icon = (key, size = 26) => {
    const c = data.championByKey.get(key);
    return c ? html`<${Img} src=${c.icon} size=${size} cls="round" tip=${c.name} />` : html`<span>${label(key)}</span>`;
  };
  const ownTotal = myChampion ? matchups.own[myChampion] : null;
  return html`<${Card} cls="draft-own-card" title="Deine Bilanz"
    aside=${html`<span class="muted" data-tip=${`Aus den gespeicherten Matches deiner eigenen Konten (Summoner-Tab), alle zusammengezählt: ${accounts.map((a) => a.riotId).join(', ')}. Gezählt werden Spiele auf Summoner's Rift gegen den direkten Lane-Gegner, ohne Remakes. Neue Spiele kommen mit der nächsten Abfrage im Summoner-Tab dazu.`}>
      ${accountLabel} · ${matchups.games} Spiele mit Lane-Gegner</span>`}>
    ${myChampion ? html`<p class="own-line">${icon(myChampion, 22)} <span>Du mit <strong>${label(myChampion)}</strong>${myRole ? ` (${roleLabel(myRole)})` : ''}:</span>
      ${ownTotal ? html`<${OwnRecord} games=${ownTotal.games} wins=${ownTotal.wins} />` : html`<span class="muted">noch keine Spiele</span>`}</p>` : null}
    ${enemies.length === 0 ? html`<p class="muted small">Sobald Gegner gewählt sind, steht hier deine Bilanz gegen jeden von ihnen.</p>` : html`<table class="data compact">
      <thead><tr><th>Gegner</th><th>Deine Bilanz dagegen</th>${myChampion ? html`<th>mit ${label(myChampion)}</th>` : null}<th>Deine Champions dagegen</th></tr></thead>
      <tbody>${enemies.map(({ slot, role }) => {
        const vs = matchups.vs[slot.champion];
        const lane = myRole && role === myRole;
        const mine = vs && myChampion ? vs.champions.find((c) => c.key === myChampion) : null;
        return html`<tr key=${slot.champion} class=${lane ? 'own-lane' : ''}>
          <td><span class="own-enemy">${icon(slot.champion)}<span>${label(slot.champion)}${role ? html`<small class="muted"> ${roleLabel(role)}</small>` : null}${lane ? html` <${Tag}>Lane<//>` : null}</span></span></td>
          <td><${OwnRecord} games=${vs ? vs.games : 0} wins=${vs ? vs.wins : 0} /></td>
          ${myChampion ? html`<td>${mine ? html`<${OwnRecord} games=${mine.games} wins=${mine.wins} />` : html`<span class="muted">–</span>`}</td>` : null}
          <td><span class="own-champs">${vs ? vs.champions.slice(0, OWN_TOP_CHAMPIONS).map((c) => html`<span key=${c.key} class="own-champ"
            data-tip=${`${label(c.key)} gegen ${label(slot.champion)}: ${record(c.games, c.wins)}, KDA ${c.deaths ? ((c.kills + c.assists) / c.deaths).toFixed(2) : 'Perfekt'}`}>
            ${icon(c.key, 20)}<small class=${c.wins / c.games >= 0.52 ? 'good' : c.wins / c.games < 0.48 ? 'bad' : ''}>${c.wins}:${c.games - c.wins}</small></span>`) : html`<span class="muted">–</span>`}</span></td>
        </tr>`;
      })}</tbody>
    </table>`}
  <//>`;
}

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
/** Drafts je Seite in Archiv-Listen: Bei bis zu 1000 gespeicherten Drafts würde die ganze Liste Zehntausende Icons zeichnen. */
const ARCHIVE_PAGE = 50;

/** Knopf unter einer Liste, die nur die ersten `shown` von `total` Einträgen zeigt. */
function ShowMore({ shown, total, onMore }) {
  if (shown >= total) {
    return null;
  }
  return html`<button type="button" class="more-toggle" onClick=${onMore}>
    ${Math.min(ARCHIVE_PAGE, total - shown)} weitere anzeigen (${shown} von ${total})<span class="more-arrow" aria-hidden="true">▼</span>
  </button>`;
}
const patchLabel = (patch) => (patch ? `Patch ${gamePatch(patch)}` : ''); // Spiel-Patch wie im Client (16.23.1 → 26.23)

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
  const [shown, setShown] = useState(ARCHIVE_PAGE);
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
  const fearlessState = useStore((s) => s.fearless);
  // Randfall: Ein voller Draft wurde archiviert, bevor die Serie startete, und ist dann auch deren Spiel 1.
  const seriesGames = useMemo(() => (fearlessState ? [...(fearlessState.active ? [fearlessState.active] : []), ...fearlessState.finished] : [])
    .flatMap((series) => series.games.map((game) => ({ series, game }))), [fearlessState]);
  const fearlessBadge = (entry) => {
    const match = seriesGames.find(({ game }) => draftChanges(entry, game) === 0);
    if (!match) {
      return null;
    }
    const { series, game } = match;
    const name = series.label ? `„${series.label}“` : `der Serie vom ${new Date(series.startedAt).toLocaleString('de-DE', ARCHIVE_DATE)}`;
    // Klick springt zum Spiel unter „Archiv › Fearless“.
    return html`<button type="button" class="tag fearless"
      data-tip=${`Game ${game.number} ${name} (${formatLabel(series.format)}): unter „Fearless“ öffnen`}
      onClick=${() => setView({ archiveMode: 'series', seriesFocus: { seriesId: series.id, game: game.number } })}>
      <${Icon} name="lock" size=${11} />${series.label ? html`<span class="tag-text">${series.label}</span><span>·</span>` : null}<span class="tag-fixed">Game ${game.number} (Fearless)</span>
    </button>`;
  };

  const setResult = (entry, result) => {
    api.put(`/api/draft-archive/${entry.id}`, { result }).then((next) => setState({ list: next, error: null }))
      .catch((error) => setState((previous) => ({ ...previous, error })));
  };
  // Gehört der Draft zu einem Fearless-Game, gilt dessen Ergebnis (dort geändert); sonst das eigene, per Klick wechselnd.
  const resultDot = (entry) => {
    const match = seriesGames.find(({ game }) => draftChanges(entry, game) === 0);
    if (!match) {
      return html`<${ResultDot} result=${entry.result} advancedTips=${advancedTips} onChange=${(result) => setResult(entry, result)} />`;
    }
    const result = sameSides(entry, match.game) ? match.game.result : flipResult(match.game.result);
    return html`<${ResultDot} result=${result} advancedTips=${advancedTips}
      tip=${result ? `${RESULT_LABEL[result]} (aus Fearless-Game ${match.game.number}, dort änderbar)` : null} />`;
  };

  const load = (entry, without) => {
    // Gemerkt, damit spätere Rollen-Änderungen diesen Eintrag überschreiben können (nur bei gleichen Champions).
    const loadedDraft = { id: entry.id, ally: entry.ally, enemy: entry.enemy };
    // Ein Spiel einer Fearless-Serie (gleiche zehn Champions) lädt wie unter „Fearless“ mit den Sperren der Spiele davor.
    const match = seriesGames.find(({ game }) => draftChanges(entry, game) === 0);
    if (match) {
      const context = seriesGameContext(match.series, match.game);
      setView({ ...boardPatch(entry, data, without), loadedDraft, fearlessContext: context });
      toast(seriesGameToast(context, without, data));
      return;
    }
    setView({ ...boardPatch(entry, data, without), loadedDraft });
    toast(without ? `Draft ohne ${data.championByKey.get(without.champion).name} geladen` : 'Draft aus dem Archiv geladen');
  };

  const remove = async (entry) => {
    if (!(await confirmDialog({ title: 'Draft in den Papierkorb legen?', icon: 'trash', confirmLabel: 'In den Papierkorb',
      message: `Er bleibt ${state.list?.trashDays || 7} Tage im Papierkorb und lässt sich bis dahin wiederherstellen.` }))) {
      return;
    }
    api.del(`/api/draft-archive/${entry.id}`).then((next) => {
      setState({ list: next, error: null });
      toast('Draft im Papierkorb');
    }).catch((error) => setState((previous) => ({ ...previous, error })));
  };
  const restore = (item) => api.post(`/api/draft-archive/${item.id}/restore`).then((next) => {
    setState({ list: next, error: null });
    toast('Draft wiederhergestellt');
  }).catch((error) => setState((previous) => ({ ...previous, error })));
  const purge = async (item) => {
    if (!(await confirmDialog({ title: 'Draft endgültig löschen?', danger: true, confirmLabel: 'Endgültig löschen',
      message: 'Er wird aus dem Papierkorb entfernt. Das lässt sich nicht rückgängig machen.' }))) {
      return;
    }
    api.del(`/api/draft-archive/trash/${item.id}`).then((next) => setState({ list: next, error: null }))
      .catch((error) => setState((previous) => ({ ...previous, error })));
  };
  const emptyTrash = async () => {
    const count = state.list?.trash?.length || 0;
    if (!(await confirmDialog({ title: 'Papierkorb leeren?', danger: true, confirmLabel: 'Papierkorb leeren',
      message: `${count === 1 ? 'Ein Draft wird' : `${count} Drafts werden`} endgültig gelöscht. Das lässt sich nicht rückgängig machen.` }))) {
      return;
    }
    api.del('/api/draft-archive/trash').then((next) => {
      setState({ list: next, error: null });
      toast('Papierkorb geleert');
    }).catch((error) => setState((previous) => ({ ...previous, error })));
  };

  const mode = view.archiveMode === 'series' ? 'series' : 'drafts';
  const switcher = html`<${Segmented} label="Archiv" value=${mode} onChange=${(archiveMode) => setView({ archiveMode })}
    options=${[{ value: 'drafts', label: 'Drafts' }, { value: 'series', label: 'Fearless' }]} />`;
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
        ${list.entries.slice(0, shown).map((entry) => html`<${ArchiveEntry} key=${entry.id} entry=${entry} data=${data} advancedTips=${advancedTips}
          badge=${fearlessBadge(entry)} result=${resultDot(entry)}
          current=${boardComplete && draftChanges(entry, board) === 0} onLoad=${load} onRemove=${remove} />`)}
      </ul>
      <${ShowMore} shown=${shown} total=${list.entries.length} onMore=${() => setShown(shown + ARCHIVE_PAGE)} />`}
    ${list.trash && list.trash.length ? html`<${TrashSection} items=${list.trash} data=${data} days=${list.trashDays || 7}
      noun="Drafts" storeKey="draftTrashOpen" title=${(item) => new Date(item.savedAt).toLocaleString('de-DE', ARCHIVE_DATE)}
      body=${TrashedDraft} onRestore=${restore} onPurge=${purge} onEmpty=${emptyTrash} />` : null}
  </div>`;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** „noch 6 Tage“ bis zum endgültigen Löschen. */
function remainingText(expiresAt) {
  const days = Math.ceil((Date.parse(expiresAt) - Date.now()) / DAY_MS);
  return days <= 1 ? 'läuft heute ab' : `noch ${days} Tage`;
}

/** Kleine, ausgegraute Champion-Icons eines Teams für den Papierkorb. */
function trashIcons(team, data) {
  return team.map((slot) => {
    const champion = data.championByKey.get(slot.champion);
    return champion ? html`<${Img} key=${slot.champion} src=${champion.icon} size=${26} alt=${champion.name} />` : null;
  });
}

/** Papierkorb-Inhalt eines Drafts: beide Teams. */
function TrashedDraft({ item, data }) {
  return html`<div class="draft-archive-teams">
    <div class="draft-archive-team ally" aria-label="Dein Team">${trashIcons(item.ally, data)}</div>
    <span class="muted small">vs</span>
    <div class="draft-archive-team enemy" aria-label="Gegner">${trashIcons(item.enemy, data)}</div>
  </div>`;
}

/** Papierkorb-Inhalt einer Serie: Format, Spiele, Stand und die Picks deines Teams je Spiel. */
function TrashedSeries({ item, data }) {
  const score = item.wins + item.losses > 0 ? ` · ${item.wins}:${item.losses}` : '';
  return html`<div class="draft-trash-series">
    <span class="muted small">${seriesFormatLabel(item)} · ${item.games.length} ${item.games.length === 1 ? 'Game' : 'Games'}${score}</span>
    <div class="draft-trash-games">
      ${item.games.map((game) => html`<div key=${game.number} class="draft-archive-team ally" data-tip=${`Game ${game.number}, dein Team`}>
        ${trashIcons(game.ally, data)}</div>`)}
    </div>
  </div>`;
}

/**
 * Papierkorb (Drafts oder Fearless-Serien): Gelöschtes bleibt `days` Tage wiederherstellbar, danach räumt das Backend
 * es automatisch auf. Einklappbar (nur für die Sitzung, je `storeKey`), anfangs zu. `title` und `body` liefern Titel
 * und Inhalt eines Eintrags.
 */
function TrashSection({ items, data, days, noun, storeKey, title, body: Body, onRestore, onPurge, onEmpty }) {
  const open = useStore((s) => Boolean(s[storeKey]));
  return html`<section class="draft-trash" aria-label="Papierkorb">
    <div class="draft-trash-head">
      <button type="button" class="draft-trash-toggle" aria-expanded=${open} onClick=${() => store.set({ [storeKey]: !open })}>
        <${Icon} name="trash" size=${14} /><span>Papierkorb</span><span class="muted small">${items.length}</span>
        <span class=${`draft-locks-chevron ${open ? 'open' : ''}`}><${Icon} name="chevron" size=${14} /></span>
      </button>
      <span class="muted small">Gelöschte ${noun} bleiben ${days} Tage wiederherstellbar und werden danach automatisch entfernt.</span>
      ${open ? html`<${Button} kind="ghost" icon="trash" onClick=${onEmpty}>Papierkorb leeren<//>` : null}
    </div>
    ${open ? html`<ul class="draft-trash-list">
      ${items.map((item) => html`<li key=${item.id} class="draft-trash-entry">
        <div class="draft-archive-meta">
          <strong class="draft-trash-title">${title(item)}</strong>
          <span class="muted small" data-tip=${`Endgültig gelöscht am ${new Date(item.expiresAt).toLocaleString('de-DE', ARCHIVE_DATE)}`}>
            ${remainingText(item.expiresAt)}</span>
        </div>
        <${Body} item=${item} data=${data} />
        <div class="draft-archive-actions">
          <${Button} icon="undo" onClick=${() => onRestore(item)}>Wiederherstellen<//>
          <${Button} icon="trash" kind="icon" title="Endgültig löschen" onClick=${() => onPurge(item)} />
        </div>
      </li>`)}
    </ul>` : null}
  </section>`;
}

/** Sitzungs-Kontext eines geladenen Serienspiels: Picks beider Teams aus den Spielen davor sind gesperrt. */
function seriesGameContext(series, game) {
  const locked = series.games.filter((g) => g.number < game.number)
    .flatMap((g) => [...g.ally, ...g.enemy].map((s) => ({ champion: s.champion, game: g.number })));
  return { seriesId: series.id, label: series.label, game: game.number, locked };
}

const seriesGameToast = (context, without, data) => (without
  ? `Game ${context.game} ohne ${data.championByKey.get(without.champion).name} geladen, mit ${context.locked.length} Sperren`
  : `Game ${context.game} geladen, mit ${context.locked.length} Sperren`);

/** Brett-Stand eines gespeicherten Drafts; `without` lädt ihn ohne einen Pick („Beste Picks“ für dessen Platz). */
function boardPatch(entry, data, without = null) {
  const skip = (team) => (without && without.team === team ? without.champion : null);
  const patch = {
    ally: boardSlots(entry.ally, data, skip('ally')), enemy: boardSlots(entry.enemy, data, skip('enemy')),
    bans: entry.bans.filter((key) => data.championByKey.has(key)).slice(0, MAX_BANS), picker: null, fearlessContext: null, loadedDraft: null,
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
/** Ergebnis eines Serienspiels nachträglich ändern (Sicht „Dein Team“); leer = kein Ergebnis. */
const RESULT_OPTIONS = [{ value: '', label: 'Ohne Ergebnis' }, { value: 'WIN', label: 'Sieg' }, { value: 'LOSS', label: 'Niederlage' }];
/** So lange bleibt ein angesprungenes Serienspiel hervorgehoben. */
const FOCUS_MS = 2500;
const seriesGameId = (seriesId, game) => `fearless-${seriesId}-${game}`;

/**
 * Laufende und beendete Fearless-Serien mit ihren Spielen. Laden und der Klick auf einen Champion wirken wie im Archiv,
 * zusätzlich gelten die Sperren, die in diesem Spiel galten (Picks der Spiele davor).
 */
function SeriesList({ data, view, setView }) {
  const fearless = useFearless();
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const [renaming, setRenaming] = useState(null);
  // Serie, für die gerade ein Draft zum Hinzufügen gewählt wird („+“ im Kopf).
  const [adding, setAdding] = useState(null);
  const { state } = fearless;
  // Sprung aus dem Etikett unter „Drafts“: zum Spiel scrollen, kurz hervorheben, dann vergessen.
  const focus = view.seriesFocus || null;
  // Klappstand je Serie (nur Sitzung); ohne Eintrag sind die laufende und die neueste Serie offen.
  const openState = useStore((s) => s.fearlessSeriesOpen) || {};
  const setOpen = (id, open) => store.set({ fearlessSeriesOpen: { ...(store.get().fearlessSeriesOpen || {}), [id]: open } });
  useEffect(() => {
    if (!focus || !state) {
      return undefined;
    }
    // Das Ziel eines Sprungs muss aufgeklappt sein, bevor gescrollt wird.
    if (!(store.get().fearlessSeriesOpen || {})[focus.seriesId]) {
      setOpen(focus.seriesId, true);
    }
    const scroll = setTimeout(() => document.getElementById(seriesGameId(focus.seriesId, focus.game))
      ?.scrollIntoView({ block: 'center', behavior: 'smooth' }), 50);
    const timer = setTimeout(() => setView({ seriesFocus: null }), FOCUS_MS);
    return () => {
      clearTimeout(scroll);
      clearTimeout(timer);
    };
  }, [focus?.seriesId, focus?.game, Boolean(state)]);
  if (!state) {
    return html`<${Loading} />`;
  }
  const list = [...(state.active ? [state.active] : []), ...state.finished];
  const seriesName = (series) => (series.label ? `„${series.label}“` : 'Die Serie');
  // Gleiche Drafts unter „Drafts“ gehen mit in den Papierkorb und kommen mit zurück: die Liste dort neu laden.
  const draftsChanged = () => store.set({ draftArchiveRev: (store.get().draftArchiveRev || 0) + 1 });
  const alsoDrafts = (count, verb) => (count ? `, ${count === 1 ? 'ein Draft' : `${count} Drafts`} unter „Drafts“ ${verb}` : '');
  const restore = (item) => fearless.restore(item.id).then((next) => {
    if (next) {
      draftsChanged();
      toast(`${seriesName(item)} wiederhergestellt${alsoDrafts(next.draftsRestored, 'ebenfalls')}`);
    }
  });
  const purge = async (item) => {
    if (await confirmDialog({ title: 'Serie endgültig löschen?', danger: true, confirmLabel: 'Endgültig löschen',
      message: `${seriesName(item)} wird mit ${item.games.length === 1 ? 'ihrem Game' : `allen ${item.games.length} Games`} aus dem Papierkorb entfernt. Das lässt sich nicht rückgängig machen.` })) {
      fearless.purge(item.id);
    }
  };
  const emptyTrash = async () => {
    const count = state.trash?.length || 0;
    if (await confirmDialog({ title: 'Papierkorb leeren?', danger: true, confirmLabel: 'Papierkorb leeren',
      message: `${count === 1 ? 'Ein Eintrag wird' : `${count} Einträge werden`} endgültig gelöscht. Das lässt sich nicht rückgängig machen.` })) {
      fearless.emptyTrash().then((next) => next && toast('Papierkorb geleert'));
    }
  };
  const trash = state.trash && state.trash.length ? html`<${TrashSection} items=${state.trash} data=${data} days=${state.trashDays || 7}
    noun="Serien" storeKey="fearlessTrashOpen" body=${TrashedSeries}
    title=${(item) => item.label || `Serie vom ${new Date(item.startedAt).toLocaleDateString('de-DE', { dateStyle: 'short' })}`}
    onRestore=${restore} onPurge=${purge} onEmpty=${emptyTrash} />` : null;
  if (list.length === 0) {
    return html`<div class="draft-series-list">
      <${Empty} title="Noch keine Fearless-Serien">Eine Serie erscheint hier, sobald ihr erstes Game abgeschlossen ist.<//>
      ${trash}
    </div>`;
  }
  const board = { ally: view.ally.filter(Boolean), enemy: view.enemy.filter(Boolean) };
  const boardComplete = board.ally.length === TEAM_SIZE && board.enemy.length === TEAM_SIZE;
  const context = view.fearlessContext;

  const loadGame = (series, game, without) => {
    const context = seriesGameContext(series, game);
    setView({ ...boardPatch(game, data, without), fearlessContext: context });
    toast(seriesGameToast(context, without, data));
  };
  const rename = (series, label, maxGames) => fearless.rename(series.id, label, maxGames).then((next) => {
    if (next) {
      setRenaming(null);
      const note = maxGamesNote(next, series, maxGames);
      if (note) {
        toast(note);
      }
    }
  });
  // Verwechselte Reihenfolge korrigieren: Spiel um eine Stelle verschieben, die Nummern laufen danach wieder ab 1.
  const move = (series, number, step) => {
    const order = series.games.map((g) => g.number);
    const from = number - 1;
    const to = from + step;
    [order[from], order[to]] = [order[to], order[from]];
    fearless.reorder(series.id, order).then((next) => {
      if (!next) {
        return;
      }
      // Ein geladenes Spiel dieser Serie trüge sonst Nummer und Sperren der alten Reihenfolge.
      setView({ seriesFocus: { seriesId: series.id, game: to + 1 },
        ...(view.fearlessContext?.seriesId === series.id ? { fearlessContext: null } : {}) });
      toast(`Game ${number} ist jetzt Game ${to + 1}`);
    });
  };
  // Auch die laufende Serie (z. B. versehentlich gestartet): Sie endet dabei, ohne Spiel wird sie ohne Papierkorb verworfen.
  const remove = async (series) => {
    const running = !series.endedAt;
    if (running && series.games.length === 0) {
      if (await confirmDialog({ title: 'Serie löschen?', icon: 'trash', confirmLabel: 'Löschen',
        message: `${seriesName(series)} hat noch kein Game und wird verworfen. Es gibt danach keine Fearless-Sperren mehr.` })) {
        fearless.remove(series.id).then((next) => next && toast('Fearless-Serie gelöscht'));
      }
      return;
    }
    if (await confirmDialog({ title: 'Serie in den Papierkorb legen?', icon: 'trash', confirmLabel: 'In den Papierkorb',
      message: `${seriesName(series)} ${running ? 'endet und ' : ''}bleibt mit allen Games ${state.trashDays || 7} Tage im Papierkorb und lässt sich bis dahin wiederherstellen. Gleiche Drafts unter „Drafts“ gehen mit.` })) {
      fearless.remove(series.id).then((next) => {
        if (next) {
          draftsChanged();
          toast(`Serie im Papierkorb${alsoDrafts(next.draftsMoved, 'ebenfalls')}`);
        }
      });
    }
  };
  // Ergebnis korrigieren: Ist eine beendete Serie danach nicht mehr entschieden (z. B. 2:0 → 1:1), läuft sie wieder.
  const setResult = (series, game, value) => fearless.setResult(series.id, game.number, value || null).then((next) => {
    if (!next) {
      return;
    }
    const label = value ? RESULT_LABELS[value] : 'kein Ergebnis';
    if (next.reopened) {
      toast(`Game ${game.number}: ${label}. Die Serie ist nicht mehr entschieden und läuft wieder`);
    } else if (next.reopenBlocked) {
      toast(`Game ${game.number}: ${label}. Die Serie ist nicht mehr entschieden, bleibt aber beendet, weil schon eine andere läuft`);
    } else if (!series.endedAt && next.ended) {
      toast(`Game ${game.number}: ${label}. Die Serie ist damit entschieden und beendet`);
    } else {
      toast(`Game ${game.number}: ${label}`);
    }
  });
  // Falsch zugeordnetes Spiel aus der Serie lösen (x): ohne Papierkorb, der Draft bleibt unter „Drafts“ und wird dort
  // bei Bedarf gelöscht.
  const removeGame = async (series, game) => {
    if (!(await confirmDialog({ title: `Game ${game.number} aus der Serie entfernen?`, icon: 'x', confirmLabel: 'Entfernen',
      message: 'Die übrigen Games rücken nach, seine Sperren fallen weg. Der Draft bleibt unter „Drafts“ und lässt sich dort löschen oder mit „+“ an der Serie wieder hinzufügen.' }))) {
      return;
    }
    fearless.removeGame(series.id, game.number).then((next) => {
      if (next) {
        // Nummern und Sperren eines geladenen Spiels dieser Serie stimmen danach nicht mehr.
        if (view.fearlessContext?.seriesId === series.id) {
          setView({ fearlessContext: null });
        }
        if (next.draftRecreated) {
          draftsChanged();
        }
        toast(`Game ${game.number} aus der Serie entfernt, der Draft ${next.draftRecreated ? 'ist wieder' : 'bleibt'} unter „Drafts“`);
      }
    });
  };
  // Draft aus „Drafts“ als letztes Spiel nachtragen (z. B. nach versehentlichem Entfernen), solange die Serie nicht voll ist.
  const addDraft = (series, entry) => fearless.addDraftTo(series.id, entry.id).then((next) => {
    if (!next) {
      return;
    }
    setAdding(null);
    setOpen(series.id, true);
    setView({ seriesFocus: { seriesId: series.id, game: next.game },
      ...(view.fearlessContext?.seriesId === series.id ? { fearlessContext: null } : {}) });
    toast(`Als Game ${next.game} hinzugefügt${next.ended ? ', die Serie ist damit voll und beendet' : ''}; die Pfeile schieben es an die richtige Stelle`);
  });
  const reopen = async () => {
    const filled = view.ally.some(Boolean) || view.enemy.some(Boolean) || view.bans.length > 0;
    if (!(await confirmDialog({ title: 'Serie wieder öffnen?', icon: 'undo', confirmLabel: 'Wieder öffnen',
      message: `Ihr letztes Game wird zurückgenommen${filled ? ', sein Draft ersetzt das aktuelle Brett' : ' und kommt aufs Brett'}.` }))) {
      return;
    }
    fearless.undo().then((next) => {
      if (next && next.undone) {
        setView({ ...boardPatch(next.undone, data), tab: 'picks' });
        toast(`Serie läuft wieder, Game ${next.undone.number} ist zurück auf dem Brett`);
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
      // Das einzige Spiel einer beendeten Serie entfernt man mit der Serie.
      const removable = running || series.games.length > 1;
      const full = series.games.length >= series.maxGames;
      const open = openState[series.id] ?? (running || index === 0 || (Boolean(focus) && focus.seriesId === series.id));
      return html`<section key=${series.id} class=${`draft-series ${running ? 'running' : ''} ${open ? 'open' : 'collapsed'}`}>
        <header class="draft-series-head">
          <button type="button" class="draft-series-toggle" aria-expanded=${open}
            aria-label=${`${series.label || `Serie vom ${date}`} ${open ? 'einklappen' : 'aufklappen'}`} onClick=${() => setOpen(series.id, !open)}>
            <span class=${`draft-locks-chevron ${open ? 'open' : ''}`}><${Icon} name="chevron" size=${16} /></span>
          </button>
          ${renaming === series.id
            ? html`<${LabelEditor} series=${series} maxLength=${state.maxLabelLength || 60}
              onSave=${(label, maxGames) => rename(series, label, maxGames)} onCancel=${() => setRenaming(null)} />`
            : html`<div class="draft-fearless-head">
              <strong class="draft-fearless-title">${series.label || `Serie vom ${date}`}</strong>
              <${Button} icon="edit" kind="ghost" title=${editTitle(series)} onClick=${() => setRenaming(series.id)} />
            </div>`}
          <span class="muted small">${seriesFormatLabel(series)} · ${series.games.length} ${series.games.length === 1 ? 'Game' : 'Games'}${score}${series.label ? ` · ${date}` : ''}</span>
          ${running ? html`<${Tag}>Läuft<//>` : null}
          <div class="draft-archive-actions">
            ${reopenable ? html`<${Button} icon="undo" kind="icon" title="Wieder öffnen, letztes Game zurücknehmen" onClick=${reopen}
              tip=${advancedTips ? 'Für ein falsch eingetragenes letztes Game oder Ergebnis' : undefined} />` : null}
            <${Button} icon="plus" kind="icon" disabled=${full || fearless.busy}
              title=${full ? `Serie ist voll (${series.games.length} von ${series.maxGames} Games)` : 'Game aus „Drafts“ hinzufügen'}
              onClick=${() => setAdding(adding === series.id ? null : series.id)}
              tip=${advancedTips && !full ? 'Z. B. ein versehentlich entferntes Game; es kommt ans Ende, ohne Ergebnis' : undefined} />
            <${Button} icon="trash" kind="icon" title=${running && series.games.length === 0 ? 'Serie löschen' : 'Serie in den Papierkorb'}
              onClick=${() => remove(series)} disabled=${fearless.busy} />
          </div>
        </header>
        ${adding === series.id && !full ? html`<${AddGamePicker} series=${series} allSeries=${list} data=${data} busy=${fearless.busy}
          onAdd=${(entry) => addDraft(series, entry)} onClose=${() => setAdding(null)} />` : null}
        ${!open ? null : series.games.length === 0 ? html`<p class="muted small">Noch kein Game abgeschlossen.</p>` : html`<ul class="draft-archive-list">
          ${series.games.map((game) => html`<${ArchiveEntry} key=${game.number} data=${data} advancedTips=${advancedTips}
            domId=${seriesGameId(series.id, game.number)} focus=${Boolean(focus) && focus.seriesId === series.id && focus.game === game.number}
            entry=${{ ...game, savedAt: game.playedAt }} heading=${`Game ${game.number}`} result=${html`<${ResultDot} result=${game.result} advancedTips=${advancedTips} />`}
            badge=${html`<${Select} label=${`Ergebnis von Game ${game.number}`} cls="draft-result-select" value=${game.result || ''}
              options=${RESULT_OPTIONS} onChange=${(value) => setResult(series, game, value)} />`}
            current=${boardComplete && Boolean(context) && context.seriesId === series.id && context.game === game.number && draftChanges(game, board) === 0}
            actions=${html`${removable ? html`<${Button} icon="x" kind="icon" title=${`Game ${game.number} aus der Serie entfernen`}
              disabled=${fearless.busy} onClick=${() => removeGame(series, game)}
              tip=${advancedTips ? 'Für ein falsch zugeordnetes Game: nur aus der Serie, der Draft bleibt unter „Drafts“' : undefined} />` : null}
            ${series.games.length > 1 ? html`<div class="draft-move" role="group" aria-label=${`Game ${game.number} verschieben`}>
              <${Button} icon="up" kind="icon" title=${`Game ${game.number} nach vorn`} disabled=${fearless.busy || game.number === 1}
                onClick=${() => move(series, game.number, -1)} tip=${advancedTips ? 'Bei verwechselter Reihenfolge: Sperren je Game folgen der neuen Reihenfolge' : undefined} />
              <${Button} icon="down" kind="icon" title=${`Game ${game.number} nach hinten`} disabled=${fearless.busy || game.number === series.games.length}
                onClick=${() => move(series, game.number, 1)} />
            </div>` : null}`}
            onLoad=${(entry, without) => loadGame(series, game, without)} />`)}
        </ul>`}
      </section>`;
    })}
    ${trash}
  </div>`;
}

/**
 * Auswahl für „+“ an einer Serie: Drafts aus „Drafts“, die zu keiner Serie gehören und keinen hier schon gespielten
 * Champion enthalten. Der gewählte kommt als letztes Spiel ohne Ergebnis dazu.
 */
function AddGamePicker({ series, allSeries, data, busy, onAdd, onClose }) {
  const [state, setState] = useState({ list: null, error: null });
  useEffect(() => {
    let active = true;
    api.get('/api/draft-archive').then((list) => active && setState({ list, error: null }))
      .catch((error) => active && setState({ list: null, error }));
    return () => {
      active = false;
    };
  }, []);
  const { list, error } = state;
  const [shown, setShown] = useState(ARCHIVE_PAGE);
  const body = () => {
    if (error) {
      return html`<${ErrorNote} prefix="Drafts nicht verfügbar" error=${error} />`;
    }
    if (!list) {
      return html`<${Loading} />`;
    }
    const taken = new Set(series.games.flatMap((g) => [...g.ally, ...g.enemy].map((s) => s.champion)));
    const seriesGames = allSeries.flatMap((s) => s.games);
    const fits = list.entries.filter((entry) => !seriesGames.some((game) => draftChanges(entry, game) === 0)
      && ![...entry.ally, ...entry.enemy].some((s) => taken.has(s.champion)));
    if (fits.length === 0) {
      return html`<p class="muted small">Kein passender Draft unter „Drafts“. Es fehlen Drafts, die schon zu einer Serie gehören
        oder einen hier schon gespielten Champion enthalten.</p>`;
    }
    const team = (slots) => slots.map((slot) => {
      const champion = data.championByKey.get(slot.champion);
      return champion ? html`<${Img} key=${slot.champion} src=${champion.icon} size=${30} alt=${champion.name} />` : null;
    });
    return html`<ul class="draft-archive-list">
      ${fits.slice(0, shown).map((entry) => html`<li key=${entry.id} class="draft-archive-entry">
        <div class="draft-archive-meta">
          <strong>${new Date(entry.savedAt).toLocaleString('de-DE', ARCHIVE_DATE)}</strong>
          <span class="muted small">${patchLabel(entry.patch)}</span>
        </div>
        <div class="draft-archive-teams">
          <div class="draft-archive-team ally" aria-label="Dein Team">${team(entry.ally)}</div>
          <span class="muted small">vs</span>
          <div class="draft-archive-team enemy" aria-label="Gegner">${team(entry.enemy)}</div>
        </div>
        <div class="draft-archive-bans"></div>
        <div class="draft-archive-actions">
          <${Button} icon="plus" disabled=${busy} onClick=${() => onAdd(entry)}>Als Game ${series.nextGame}<//>
        </div>
      </li>`)}
    </ul>
    <${ShowMore} shown=${shown} total=${fits.length} onMore=${() => setShown(shown + ARCHIVE_PAGE)} />`;
  };
  return html`<div class="draft-series-add" role="region" aria-label="Game hinzufügen">
    <div class="draft-series-add-head">
      <strong>Game aus „Drafts“ hinzufügen</strong>
      <span class="muted small">${series.games.length} von ${series.maxGames} Games</span>
      <${Button} icon="x" kind="icon" title="Schließen" onClick=${onClose} />
    </div>
    ${body()}
  </div>`;
}

const RESULT_LABEL = { WIN: 'Sieg', LOSS: 'Niederlage' };
const NEXT_RESULT = { '': 'WIN', WIN: 'LOSS', LOSS: '' };
const flipResult = (result) => (result === 'WIN' ? 'LOSS' : result === 'LOSS' ? 'WIN' : null);
/** Gleiche Seiten wie {@code b} (nicht vertauscht): Dann gilt dessen Ergebnis unverändert. */
const sameSides = (a, b) => {
  const champions = new Set(a.ally.map((s) => s.champion));
  return b.ally.every((s) => champions.has(s.champion));
};

const RESULT_TEXT = { WIN: 'Win', LOSS: 'Loss' };

/**
 * Ergebnis neben dem Patch: „Win“ grün, „Loss“ rot (Sicht „Dein Team“). Mit {@code onChange} ein Knopf, der
 * offen → Win → Loss → offen wechselt (offen = „Offen“, ausgegraut); ohne nur Anzeige, offen dann unsichtbar.
 * Tooltip nur bei „Show Advanced Tooltips“.
 */
function ResultDot({ result, onChange = null, tip = null, advancedTips = false }) {
  const label = result ? RESULT_LABEL[result] : 'Ohne Ergebnis';
  const cls = `draft-result-dot ${result ? result.toLowerCase() : 'open'}`;
  const text = result ? RESULT_TEXT[result] : 'Offen';
  if (!onChange) {
    return result ? html`<span class=${cls} aria-label=${label} data-tip=${advancedTips ? tip || label : undefined}>${text}</span>` : null;
  }
  const next = NEXT_RESULT[result || ''];
  const action = `${label} – Klick: ${next ? RESULT_LABEL[next] : 'ohne Ergebnis'}`;
  return html`<button type="button" class=${cls} aria-label=${`Ergebnis: ${action}`} data-tip=${advancedTips ? action : undefined}
    onClick=${() => onChange(next || null)}>${text}</button>`;
}

function ArchiveEntry({ entry, data, advancedTips, current, onLoad, onRemove, heading = null, badge = null, result = null, domId, focus = false, actions = null }) {
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
  return html`<li id=${domId} class=${`draft-archive-entry ${current ? 'current' : ''} ${focus ? 'focus' : ''}`}>
    <div class="draft-archive-meta">
      <strong>${heading || new Date(entry.savedAt).toLocaleString('de-DE', ARCHIVE_DATE)}</strong>
      <span class="draft-archive-patch"><span class="muted small">${patchLabel(entry.patch)}</span>${result}</span>
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
      ${actions}
      <${Button} icon="download" onClick=${() => onLoad(entry)} tip=${advancedTips ? 'Ganzen Draft aufs Brett laden' : undefined}>Laden<//>
      ${onRemove ? html`<${Button} icon="trash" title="Aus dem Archiv löschen" onClick=${() => onRemove(entry)}
        tip=${advancedTips ? 'Aus dem Archiv löschen' : undefined} />` : null}
    </div>
  </li>`;
}
