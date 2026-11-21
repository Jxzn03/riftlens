// Kern der Oberfläche: Store, API-Zugriff, Routing, Formatierung.
import { useState, useEffect, useLayoutEffect, useRef, useReducer, useMemo } from '/vendor/htm-preact.js';

// ------------------------------------------------------------------ Store

function createStore(initial) {
  let state = initial;
  const listeners = new Set();
  return {
    get: () => state,
    set(patch) {
      state = { ...state, ...patch };
      listeners.forEach((l) => l());
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export const store = createStore({
  data: null,
  bootError: null,
  route: { view: 'champions', id: null },
  theme: 'dark',
  filter: { role: '' },
  dataSource: { period: '30', tier: 'emerald_plus' },
  avgWinRate: null,
  summonerRecent: [],
  summonerProgress: null,
  draftProgress: null,
  build: [],
  buildMode: '', // '' | 'adc' | 'mid' | 'support', siehe BUILD_MODES
  buildBoots: null, // Boots-Platz im ADC-Modus
  buildChampion: null, // Champion-Key, auf den der Build angewendet wird (bestimmt die Rolle)
  buildRole: '', // zuletzt angewendete Rolle des Build-Champions (setzt den Modus)
  buildAuto: { known: true, role: '' }, // automatische Rolle des Build-Champions aus dem Cache (BuildRoleSync)
  runePage: null, // wird unten mit EMPTY_RUNE_PAGE belegt
  split: {},
  prefs: { level: 1, haste: 0, previewLevel: 11, advancedTips: false },
  favorites: [], // Champion-Keys des eigenen Pools, nur lokal gespeichert
  views: {},
  meta: null,
  busy: 0,
  status: '',
  dialog: null,
  palette: false,
  connected: true,
  toast: null,
  update: null, // Stand der Update-Prüfung (`/api/update`)
  releaseNotes: null, // geöffnete Versionshinweise: { mode: 'new' | 'history', current, releases }
});

/** Abonniert einen Ausschnitt des Stores; rendert nur neu, wenn sich dieser Ausschnitt ändert. */
export function useStore(selector) {
  const [, force] = useReducer((x) => x + 1, 0);
  const selectorRef = useRef(selector);
  selectorRef.current = selector;
  const valueRef = useRef();
  valueRef.current = selector(store.get());
  useLayoutEffect(() => {
    const check = () => {
      const next = selectorRef.current(store.get());
      if (!Object.is(next, valueRef.current)) {
        valueRef.current = next;
        force();
      }
    };
    const unsubscribe = store.subscribe(check);
    // Änderungen zwischen Rendern und Abonnieren nachholen (z. B. sehr schnelle lokale Antworten).
    check();
    return unsubscribe;
  }, []);
  return valueRef.current;
}

/** Zustand einer Ansicht (Suche, Filter, Sortierung), der beim Wechsel der Ansicht erhalten bleibt. */
export function useViewState(name, defaults) {
  const state = useStore((s) => s.views[name]) || defaults;
  const update = (patch) => {
    const views = store.get().views;
    store.set({ views: { ...views, [name]: { ...(views[name] || defaults), ...patch } } });
  };
  return [state, update];
}

// ------------------------------------------------------------------ API

let pending = 0;

function setBusy(delta) {
  pending += delta;
  store.set({ busy: pending });
}

async function request(method, url, body, signal) {
  setBusy(1);
  try {
    const options = { method, signal };
    if (body !== undefined) {
      options.headers = { 'Content-Type': 'application/json' };
      options.body = JSON.stringify(body);
    }
    const response = await fetch(url, options);
    const text = await response.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = null;
    }
    if (!response.ok) {
      const error = new Error((json && json.error) || `HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
    return json;
  } finally {
    setBusy(-1);
  }
}

export const api = {
  get: (url, signal) => request('GET', url, undefined, signal),
  post: (url, body = {}, signal) => request('POST', url, body, signal),
  put: (url, body) => request('PUT', url, body),
  del: (url) => request('DELETE', url),
};

// Antworten bleiben im Speicher: Zurückwechseln in eine Ansicht braucht keine Anfrage.
const responseCache = new Map();
const CACHE_LIMIT = 300;

function clearResponseCache() {
  responseCache.clear();
}

function remember(url, value) {
  if (responseCache.size >= CACHE_LIMIT) {
    responseCache.delete(responseCache.keys().next().value);
  }
  responseCache.set(url, value);
}

/**
 * Lädt {@code url} und verwirft überholte Antworten.
 * keep: zeigt bis zum Eintreffen der neuen Antwort weiter die alten Daten (kein Flackern).
 */
export function useFetch(url, { keep = false } = {}) {
  const [state, setState] = useState(() => ({
    data: url && responseCache.has(url) ? responseCache.get(url) : null,
    error: null,
    loading: Boolean(url) && !responseCache.has(url),
  }));
  useEffect(() => {
    if (!url) {
      setState({ data: null, error: null, loading: false });
      return undefined;
    }
    if (responseCache.has(url)) {
      setState({ data: responseCache.get(url), error: null, loading: false });
      return undefined;
    }
    const controller = new AbortController();
    setState((previous) => ({ data: keep ? previous.data : null, error: null, loading: true }));
    api.get(url, controller.signal).then((data) => {
      remember(url, data);
      if (!controller.signal.aborted) {
        setState({ data, error: null, loading: false });
      }
    }).catch((error) => {
      if (!controller.signal.aborted) {
        setState({ data: null, error, loading: false });
      }
    });
    return () => controller.abort();
  }, [url]);
  return state;
}

/**
 * Aktuelle Statistik-URL, die sich mit Rolle, Zeitraum und Rang-Bereich ändert. Die URL ist auch der Cache-Schlüssel
 * von {@link useFetch}: Reihenfolge der Parameter nicht ändern.
 *
 * @param role Rolle statt des Rollenfilters (z. B. die Rolle eines Champions); weggelassen = Rollenfilter
 */
export function useStatsUrl(path, role) {
  const filter = useStore((s) => s.filter);
  const dataSource = useStore((s) => s.dataSource);
  if (!path) {
    return null;
  }
  const params = new URLSearchParams({ role: role === undefined ? filter.role : role, period: dataSource.period, laTier: dataSource.tier });
  return `${path}?${params}`;
}

const CHAMPION_ROLES = 'champion-roles';

/**
 * Rolle eines Champions für Vorschau und Fähigkeiten. Von Hand gewählt (je Champion gespeichert) oder automatisch:
 * dann gilt der Rollenfilter und ohne Filter die häufigste Rolle laut Lolalytics.
 *
 * @returns {{ role: string, chosen: string, setRole: (role: string) => void }} role leer = häufigste Rolle
 */
export function useChampionRole(championKey) {
  const roles = useStore((s) => s.views[CHAMPION_ROLES]) || {};
  const filterRole = useStore((s) => s.filter.role);
  const chosen = roles[championKey] || '';
  const setRole = (role) => {
    const views = store.get().views;
    const next = { ...(views[CHAMPION_ROLES] || {}) };
    if (role) {
      next[championKey] = role;
    } else {
      delete next[championKey];
    }
    store.set({ views: { ...views, [CHAMPION_ROLES]: next } });
  };
  return { role: chosen || filterRole || '', chosen, setRole };
}

/** Wie {@link useStatsUrl}, aber mit der Rolle des Champions statt des Rollenfilters. */
export function useChampionStatsUrl(path, role) {
  return useStatsUrl(path, role || '');
}

/**
 * Farbklasse einer Winrate: um den Durchschnitt des gewählten Rang-Bereichs, ±1 Punkt, weil die Winrates in den
 * großen Datensätzen eng beieinanderliegen.
 */
export function useWinRateClass() {
  const average = useStore((s) => s.avgWinRate);
  return (value) => {
    if (average === null) {
      return '';
    }
    return value >= average + 0.01 ? 'good' : value < average - 0.01 ? 'bad' : '';
  };
}

/** Rang-Bereiche von Lolalytics, von niedrig nach hoch. */
export const TIERS = [
  { value: 'all', label: 'Alle Ränge' },
  { value: 'iron', label: 'Iron' },
  { value: 'bronze', label: 'Bronze' },
  { value: 'silver', label: 'Silver' },
  { value: 'gold', label: 'Gold' },
  { value: 'gold_plus', label: 'Gold+' },
  { value: 'platinum', label: 'Platinum' },
  { value: 'platinum_plus', label: 'Platinum+' },
  { value: 'emerald', label: 'Emerald' },
  { value: 'emerald_plus', label: 'Emerald+' },
  { value: 'diamond', label: 'Diamond' },
  { value: 'diamond_plus', label: 'Diamond+' },
  { value: 'd2_plus', label: 'Diamond 2+' },
  { value: 'master_plus', label: 'Master+' },
  { value: 'grandmaster', label: 'Grandmaster' },
  { value: 'challenger', label: 'Challenger' },
];

/** Kurzbeschreibung des gewählten Datensatzes für Kartenköpfe. */
export function useSourceLabel() {
  const { period, tier } = useStore((s) => s.dataSource);
  const tierLabel = (TIERS.find((t) => t.value === tier) || TIERS[9]).label;
  return `Lolalytics · ${tierLabel} · ${period === '30' ? '30 Tage' : 'letzter Patch'}`;
}

/**
 * Verzögert das Laden noch nicht vorhandener Antworten. So löst schnelles Durchklicken der Liste keine Abrufe für
 * jeden Zwischenstand aus (Lolalytics wird höchstens alle 2 Sekunden angefragt). Liefert bis dahin {@code null}.
 *
 * cachedUrl: optional die URL einer Anfrage, die nur aus dem Cache des Backends antwortet (ohne Abruf, sonst
 * `{ cached: false }`). Sie läuft parallel zur unveränderten Wartezeit; trifft sie, gilt ihre Antwort als die von url.
 */
export function useDeferredUrl(url, delay, cachedUrl) {
  const ready = !delay || !url || responseCache.has(url);
  const [value, setValue] = useState(ready ? url : null);
  useEffect(() => {
    if (ready) {
      setValue(url);
      return undefined;
    }
    setValue(null);
    const controller = new AbortController();
    const timer = setTimeout(() => {
      controller.abort();
      setValue(url);
    }, delay);
    if (cachedUrl) {
      api.get(cachedUrl, controller.signal).then((data) => {
        if (!controller.signal.aborted && data && data.cached !== false) {
          clearTimeout(timer);
          remember(url, data);
          setValue(url);
        }
      }).catch(() => {});
    }
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [url, delay]);
  return value;
}

// ------------------------------------------------------------------ Programm-Update

let manualUpdateCheck = false;

/** Update-Stand aus `/api/update` oder dem Ereignis `update`; ältere Stände (kleinere `seq`) werden verworfen. */
export function setUpdate(update) {
  const previous = store.get().update;
  if (!update || (previous && update.seq < previous.seq)) {
    return;
  }
  store.set({ update });
  if (previous?.phase === 'checking' && update.phase === 'idle' && manualUpdateCheck) {
    manualUpdateCheck = false;
    if (update.errorKind === 'check') {
      toast(update.error, 'error');
    } else {
      toast(update.available ? `RiftLens ${update.latest.version} ist verfügbar.` : `RiftLens ${update.current} ist die neueste Version.`);
    }
  } else if (previous?.phase !== 'idle' && update.phase === 'idle' && update.errorKind === 'install' && previous?.error !== update.error) {
    toast(update.error, 'error');
  }
}

// ------------------------------------------------------------------ Versionshinweise

/**
 * Zeigt beim Start einmal die Hinweise der Versionen, die seit dem letzten Start neu sind (nach einem Update). Wird
 * vor den Icons angefragt, sonst wartet die Anfrage hinter ihnen; der Dialog erscheint mit der fertigen Oberfläche.
 */
export function showNewReleaseNotes() {
  return api.get('/api/release-notes').then((notes) => {
    const fresh = notes.releases.filter((r) => notes.unseen.includes(r.version));
    if (fresh.length > 0) {
      store.set({ releaseNotes: { mode: 'new', current: notes.current, releases: fresh } });
    }
  }).catch(() => {});
}

/** Öffnet den Verlauf aller Versionshinweise (aus dem Popup heraus gelten dessen Hinweise als gezeigt). */
export function showReleaseHistory() {
  if (store.get().releaseNotes?.mode === 'new') {
    api.post('/api/release-notes/seen').catch(() => {});
  }
  return api.get('/api/release-notes').then((notes) => {
    store.set({ releaseNotes: { mode: 'history', current: notes.current, releases: notes.releases } });
  }).catch((e) => toast(`Versionshinweise nicht verfügbar: ${e.message}`, 'error'));
}

/** Schließt die Versionshinweise; nach dem Popup gelten sie als gezeigt. */
export function closeReleaseNotes() {
  const open = store.get().releaseNotes;
  store.set({ releaseNotes: null });
  if (open?.mode === 'new') {
    api.post('/api/release-notes/seen').catch(() => {});
  }
}

/** Sucht von Hand nach einem Update; das Ergebnis kommt als Hinweis. */
export function checkUpdate() {
  manualUpdateCheck = true;
  return api.post('/api/update/check').then((update) => {
    setUpdate(update);
    if (update.phase === 'idle' && update.errorKind === 'check') {
      manualUpdateCheck = false;
      toast(update.error, 'error');
    }
  }).catch((e) => {
    manualUpdateCheck = false;
    toast(e.message, 'error');
  });
}

/** Kurztext zum Update-Stand für Statusleiste und Einstellungen. */
export function updateLabel(update) {
  if (!update) {
    return '';
  }
  switch (update.phase) {
    case 'checking': return 'Suche nach Updates …';
    case 'downloading': return `Lade RiftLens ${update.latest?.version} … ${Math.round(update.progress * 100)} %`;
    case 'extracting': return `Prüfe RiftLens ${update.latest?.version} …`;
    case 'restarting': return `Installiere RiftLens ${update.latest?.version}, startet neu …`;
    default:
      if (update.available) {
        return `Update auf ${update.latest.version} verfügbar`;
      }
      return update.latest && update.checkedAt ? `Neueste Version (geprüft ${new Date(update.checkedAt).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })})` : '';
  }
}

/** Erster Abruf beim Start (das Ergebnis eines gerade installierten Updates meldet die Statusleiste). */
export async function loadUpdate() {
  try {
    setUpdate(await api.get('/api/update'));
  } catch (e) {
    // ohne Update-Anzeige weiter
  }
}

/** Startet Download und Installation nach Rückfrage; die App startet danach neu. */
export async function installUpdate(confirm) {
  const update = store.get().update;
  if (!update?.latest) {
    return;
  }
  const ok = await confirm({
    title: `Auf RiftLens ${update.latest.version} aktualisieren?`,
    message: `${update.latest.name}. RiftLens lädt das Update von GitHub, prüft Signatur und Inhalt, schließt sich und startet in der neuen Version neu. Startet sie nicht, kommt die bisherige Version zurück. Deine Daten und Einstellungen bleiben erhalten.`,
    confirmLabel: 'Jetzt aktualisieren',
    icon: 'download',
  });
  if (ok) {
    api.post('/api/update/install').then(setUpdate).catch((e) => toast(e.message, 'error'));
  }
}

// ------------------------------------------------------------------ Spieldaten

let announcedPatchChange = null;

export async function loadData() {
  try {
    const data = await api.get('/api/bootstrap');
    data.championById = new Map(data.champions.map((c) => [c.id, c]));
    data.championByKey = new Map(data.champions.map((c) => [c.key, c]));
    data.itemById = new Map(data.items.map((i) => [i.id, i]));
    markBoots(data);
    data.treeById = new Map(data.runeTrees.map((t) => [t.id, t]));
    data.runeById = new Map();
    data.runeTrees.forEach((t) => t.slots.flat().forEach((r) => data.runeById.set(r.id, r)));
    data.spellById = new Map(data.spells.map((s) => [s.id, s]));
    data.spellByKey = new Map();
    data.spells.forEach((s) => {
      const existing = data.spellByKey.get(s.key);
      if (!existing || (s.classic && !existing.classic)) {
        data.spellByKey.set(s.key, s);
      }
    });
    data.shardById = new Map(data.shards.map((s) => [s.id, s.name]));
    data.runeEffectById = new Map((data.runeEffects || []).map((e) => [e.id, e]));
    clearResponseCache();
    // Erster Start nach einem Patchwechsel: einmal je Programmlauf melden.
    if (data.patchChange && announcedPatchChange !== data.patchChange.at) {
      announcedPatchChange = data.patchChange.at;
      toast(`Neuer Patch ${gamePatch(data.patchChange.to)}${data.patchChange.from ? ` (vorher ${gamePatch(data.patchChange.from)})` : ''}: Item- und Runenwerte abgeglichen, Wiki- und Statistikdaten werden neu geladen.`);
    }
    store.set({
      data,
      bootError: null,
      // Unbekannte IDs (nach einem Patch) fallen weg, ein Jungle-Pet außerhalb des Jungle-Modus auch.
      build: store.get().build.filter((id) => data.itemById.has(id)
        && (store.get().buildMode === 'jungle' || !isJunglePet(data.itemById.get(id)))),
      buildBoots: data.itemById.has(store.get().buildBoots) ? store.get().buildBoots : null,
      runePage: sanitizeRunePage(store.get().runePage, data),
      status: `Patch ${gamePatch(data.patch)} geladen: ${data.champions.length} Champions`
        + (data.offline ? ' (offline aus dem Cache)' : ''),
    });
  } catch (error) {
    if (store.get().data) {
      store.set({ status: `Fehler: ${error.message}` });
    } else {
      store.set({ bootError: error.message });
    }
  }
}

/**
 * Spiel-Patch wie im Wiki und im Client zur Data-Dragon-Version: {@code 16.23.1} → {@code 26.23}. Data Dragon zählt die
 * Hauptversion seit 2025 zehn niedriger (wie {@code WikiPatchNotes.wikiPatchTitle}); unbekannte Formen bleiben stehen.
 */
export function gamePatch(version) {
  const m = /^(\d+)\.(\d+)/.exec(version || '');
  return m ? `${Number(m[1]) + 10}.${m[2].padStart(2, '0')}` : version;
}

export const MAX_BUILD = 6;

/**
 * Kennzeichnet Boots ({@code item.boots}): Kategorie „Boots“ oder aus Boots gebaut. Data Dragon lässt die Kategorie
 * bei manchen aufgewerteten Boots weg (z. B. Gunmetal Greaves).
 */
export function markBoots(data) {
  const tagged = (id) => {
    const item = data.itemById.get(id);
    return Boolean(item && item.tags.includes('Boots'));
  };
  data.items.forEach((item) => {
    item.boots = item.tags.includes('Boots') || item.from.some(tagged);
  });
  // Zweite Stufe: aus Boots gebaut, deren Kategorie fehlt (aufgewertete Boots aus ungetaggten Boots).
  data.items.forEach((item) => {
    item.boots = item.boots || item.from.some((id) => (data.itemById.get(id) || {}).boots);
  });
}

export const isBoots = (item) => Boolean(item && (item.boots ?? item.tags.includes('Boots')));

/**
 * Modus des Build-Rechners, höchstens einer aktiv (`store.buildMode`):
 * - `adc`: Boots auf einem festen 7. Platz (`buildBoots`), daneben 6 normale Items.
 * - `mid`: Boots aufgewertet (Stufe 3), beim Ausschalten wieder Stufe 2.
 * - `support`: Support-Item aufgewertet (World Atlas/Runic Compass → Bounty of Worlds), beim Ausschalten zurück
 *   zu World Atlas; eine gewählte Endstufe (z. B. Bloodsong) bleibt.
 * - `jungle`: Nur hier darf ein Jungle-Pet in den Build (eins). Es kommt nie von selbst hinein, weil es sich im
 *   Spielverlauf auflöst und den Platz freigibt; beim Ausschalten wird es entfernt.
 */
export const BUILD_MODES = [
  { value: 'jungle', label: 'Jungle' }, { value: 'mid', label: 'Mid' }, { value: 'adc', label: 'ADC' },
  { value: 'support', label: 'Support' },
];

/** Jungle-Pet (Scorchclaw Pup, Gustwalker Hatchling, Mosstomper Seedling): Rift-Item nur mit der Kategorie „Jungle“. */
export function isJunglePet(item) {
  return Boolean(item && item.rift && item.tags.length === 1 && item.tags[0] === 'Jungle');
}

/** Alle Items des Builds für die Berechnung: die normalen Plätze, beim ADC dazu der Boots-Platz. */
export function buildItems(state) {
  return state.buildMode === 'adc' && state.buildBoots !== null ? [...state.build, state.buildBoots] : state.build;
}

/** Aufgewertete Boots (Stufe 3 auf Summoner's Rift) zu Stufe-2-Boots, sonst {@code null}. */
export function bootsUpgrade(data, id) {
  const item = data.itemById.get(id);
  if (!isBoots(item)) {
    return null;
  }
  const upgrades = item.into.map((i) => data.itemById.get(i)).filter((i) => i && i.rift);
  return upgrades.length === 1 ? upgrades[0].id : null; // einfache Boots bauen in viele Boots, das ist kein Upgrade
}

/** Stufe-2-Boots zu aufgewerteten Boots, sonst {@code null}. */
export function bootsDowngrade(data, id) {
  const item = data.itemById.get(id);
  if (!item || item.into.length) {
    return null;
  }
  const base = item.from.find((i) => bootsUpgrade(data, i) === id);
  return base === undefined ? null : base;
}

// Stufen des Support-Items. Data Dragon verknüpft sie nicht (sie werten sich im Spiel über Gold auf), darum feste
// IDs; die Endstufen erkennt man daran, dass sie aus Bounty of Worlds gebaut werden.
const SUPPORT_STAGES = [3865, 3866, 3867]; // World Atlas, Runic Compass, Bounty of Worlds
const SUPPORT_TOP = SUPPORT_STAGES[SUPPORT_STAGES.length - 1];

/** Support-Item: eine der Stufen oder eine Endstufe aus Bounty of Worlds. */
export function isSupportItem(item) {
  return Boolean(item && (SUPPORT_STAGES.includes(item.id) || item.from.includes(SUPPORT_TOP)));
}

/** World Atlas oder Runic Compass zu Bounty of Worlds, sonst {@code null}. */
export function supportUpgrade(data, id) {
  const index = SUPPORT_STAGES.indexOf(id);
  return index >= 0 && id !== SUPPORT_TOP && data.itemById.has(SUPPORT_TOP) ? SUPPORT_TOP : null;
}

/** Runic Compass oder Bounty of Worlds zurück zu World Atlas, sonst {@code null} (Endstufen bleiben). */
export function supportDowngrade(data, id) {
  const index = SUPPORT_STAGES.indexOf(id);
  return index > 0 && data.itemById.has(SUPPORT_STAGES[0]) ? SUPPORT_STAGES[0] : null;
}

/** Item-ID passend zum Modus: Boots als Mid aufgewertet, sonst Stufe 2; Support-Item als Support aufgewertet. */
function itemForMode(data, mode, id) {
  const boots = (mode === 'mid' ? bootsUpgrade(data, id) : bootsDowngrade(data, id)) ?? id;
  return (mode === 'support' ? supportUpgrade(data, boots) : supportDowngrade(data, boots)) ?? boots;
}

/** Modus nach dem Hinzufügen: ein Support-Item schaltet Support ein (aufgewertete Boots gibt es nur mit Mid). */
function modeAfterAdding(state, item) {
  return isSupportItem(item) ? 'support' : state.buildMode;
}

/** Aufgewertete Boots (Stufe 3) nur im Modus Mid, egal ob von der Rolle oder von Hand eingeschaltet. */
export const upgradedBootsLocked = (state, item) => Boolean(state.data && item)
  && state.buildMode !== 'mid' && bootsDowngrade(state.data, item.id) !== null;

/** Mit Build-Champion nur als Support Support-Items (unbekannte Rolle zählt nicht als Support); ohne Champion frei. */
export const supportLocked = (state) => Boolean(state.buildChampion) && state.buildRole !== 'UTILITY';

/** Ob das Item noch in den Build passt (beim ADC kommen Boots auf ihren eigenen Platz). */
export function canAddToBuild(state, item) {
  // Boots, Support-Item und Jungle-Pet je höchstens einmal (auch über den Boots-Platz des ADC hinweg).
  const has = (test) => buildItems(state).some((id) => test(state.data.itemById.get(id)));
  // Trinkets und Verbrauchsgegenstände nie; fertige Items je höchstens einmal (Komponenten dürfen doppelt sein).
  if (!item || item.tags.includes('Trinket') || item.tags.includes('Consumable')
    || (item.completed && has((other) => other && other.id === item.id))) {
    return false;
  }
  if ((isBoots(item) && has(isBoots)) || (isSupportItem(item) && has(isSupportItem))) {
    return false;
  }
  if ((isSupportItem(item) && supportLocked(state)) || upgradedBootsLocked(state, item)) {
    return false;
  }
  if (isJunglePet(item)) {
    return state.buildMode === 'jungle' && state.build.length < MAX_BUILD && !has(isJunglePet);
  }
  const mode = modeAfterAdding(state, item);
  if (mode === 'adc' && isBoots(item)) {
    return state.buildBoots === null;
  }
  // Verlässt das Hinzufügen den ADC-Modus, wandern die Boots vom 7. Platz auf einen normalen Platz.
  const carried = state.buildMode === 'adc' && mode !== 'adc' && state.buildBoots !== null ? 1 : 0;
  return state.build.length + carried < MAX_BUILD;
}

/**
 * Setzt den Modus ({@code ''} = keiner) und gleicht die Items an: Boots und Support-Item in die passende Stufe,
 * beim ADC die ersten Boots auf den 7. Platz. Ohne ADC kommen Boots vom 7. Platz auf einen freien normalen Platz,
 * sonst fallen sie weg (Hinweis).
 */
export function setBuildMode(mode) {
  const state = store.get();
  const { data } = state;
  if (!data) {
    store.set({ buildMode: mode });
    return;
  }
  let build = state.build.map((id) => itemForMode(data, mode, id))
    .filter((id) => mode === 'jungle' || !isJunglePet(data.itemById.get(id))); // Pet nur im Jungle-Modus
  let boots = state.buildBoots === null ? null : itemForMode(data, mode, state.buildBoots);
  let dropped = null;
  if (mode === 'adc' && boots === null) {
    const index = build.findIndex((id) => isBoots(data.itemById.get(id)));
    if (index >= 0) {
      boots = build[index];
      build = build.filter((_, i) => i !== index);
    }
  } else if (mode !== 'adc' && boots !== null) {
    if (build.length < MAX_BUILD) {
      build = [...build, boots];
    } else {
      dropped = boots;
    }
    boots = null;
  }
  store.set({ buildMode: mode, build, buildBoots: boots });
  if (dropped !== null) {
    toast(`${data.itemById.get(dropped).name} entfernt: Ohne ADC ist kein Platz für ein 7. Item.`);
  }
}

export const BUILD_ROLES = [
  { value: 'TOP', label: 'Top' }, { value: 'JUNGLE', label: 'Jungle' }, { value: 'MIDDLE', label: 'Mid' },
  { value: 'BOTTOM', label: 'ADC' }, { value: 'UTILITY', label: 'Support' },
];

const ROLE_MODES = { TOP: '', JUNGLE: 'jungle', MIDDLE: 'mid', BOTTOM: 'adc', UTILITY: 'support' };

/** Modus des Build-Rechners zu einer Rolle (Top und unbekannt: keiner). */
export const modeForRole = (role) => ROLE_MODES[role] || '';

/** Rolle zu einem Modus; ohne Modus leer (Top oder keine Rolle). */
export const roleForMode = (mode) => (mode ? Object.keys(ROLE_MODES).find((role) => ROLE_MODES[role] === mode) || '' : '');

/**
 * Wendet die Rolle des Build-Champions an, nur wenn sie sich geändert hat (so bleibt ein von Hand umgelegter
 * Schalter stehen): ADC, Mid, Support und Jungle schalten ihren Modus ein, Top alle aus. Leer = Rolle unbekannt:
 * mit Champion alle aus, ohne Champion bleibt der Modus. Ist der Champion danach kein Support, fliegen Support-Items aus dem Build.
 */
export function applyBuildRole(role) {
  const state = store.get();
  if (!state.data) {
    return;
  }
  // Unbekannte Rolle mit Champion wird bei jedem Champion- oder Rollenwechsel angewendet (Schalter aus).
  if (role !== state.buildRole || (!role && state.buildChampion)) {
    store.set({ buildRole: role });
    // Mit Champion, aber unbekannter Rolle gehen alle Schalter aus; ohne Champion bleibt der Modus.
    const mode = role ? ROLE_MODES[role] : state.buildChampion ? '' : state.buildMode;
    if (mode !== state.buildMode) {
      setBuildMode(mode);
    }
  }
  const current = store.get();
  if (supportLocked(current)) {
    const removed = current.build.filter((id) => isSupportItem(current.data.itemById.get(id)));
    if (removed.length) {
      store.set({ build: current.build.filter((id) => !removed.includes(id)) });
      toast(`${removed.map((id) => current.data.itemById.get(id).name).join(', ')} entfernt: Support-Items nur in der Rolle Support.`);
    }
  }
}

export function addToBuild(rawId) {
  const state = store.get();
  const raw = state.data && state.data.itemById.get(rawId);
  if (!raw || !canAddToBuild(state, raw)) {
    return;
  }
  const mode = modeAfterAdding(state, raw);
  if (mode !== state.buildMode) {
    setBuildMode(mode);
  }
  const current = store.get();
  const id = itemForMode(current.data, mode, rawId);
  if (mode === 'adc' && isBoots(current.data.itemById.get(id))) {
    store.set({ buildBoots: id });
  } else {
    store.set({ build: [...current.build, id] });
  }
}

/** Entfernt das Item an Position {@code index} der normalen Plätze oder mit {@code 'boots'} den Boots-Platz. */
export function removeFromBuild(index) {
  if (index === 'boots') {
    store.set({ buildBoots: null });
  } else {
    store.set({ build: store.get().build.filter((_, i) => i !== index) });
  }
}

/** Verschiebt das Item an Position {@code from} auf Position {@code to} (hinter das Ende = ans Ende). */
export function moveInBuild(from, to) {
  const build = [...store.get().build];
  if (from < 0 || from >= build.length) {
    return;
  }
  const [id] = build.splice(from, 1);
  build.splice(Math.max(0, Math.min(to, build.length)), 0, id);
  store.set({ build });
}

// ------------------------------------------------------------------ Runenseite des Build-Rechners

/**
 * Leere Runenseite: Primärbaum mit Keystone und drei Reihen, Sekundärbaum mit zwei Runen aus verschiedenen Reihen,
 * drei Shards und Stapel/Schalter je Rune (Werte siehe calc.RuneEffects).
 */
export const EMPTY_RUNE_PAGE = Object.freeze({
  primary: null, keystone: null, rows: [null, null, null], secondary: null, secondaryRunes: [],
  shards: [null, null, null], stacks: {},
});
store.set({ runePage: EMPTY_RUNE_PAGE });

export function setRunePage(patch) {
  store.set({ runePage: { ...store.get().runePage, ...patch } });
}

/** Alle gewählten Runen-IDs (ohne Shards). */
export function runePageRunes(page) {
  return [page.keystone, ...page.rows, ...page.secondaryRunes].filter((id) => id !== null && id !== undefined);
}

/** Entfernt nach einem Patch unbekannte Runen und unpassende Shards; ältere oder kaputte Stände werden geleert. */
function sanitizeRunePage(page, data) {
  if (!page || typeof page !== 'object') {
    return EMPTY_RUNE_PAGE;
  }
  const treeOk = (id) => data.treeById.has(id);
  const runeIn = (id, tree, slot) => {
    const rune = data.runeById.get(id);
    return Boolean(rune) && rune.treeId === tree && (slot === undefined || rune.slot === slot);
  };
  const primary = treeOk(page.primary) ? page.primary : null;
  const secondary = treeOk(page.secondary) && page.secondary !== primary ? page.secondary : null;
  const rows = [0, 1, 2].map((i) => (Array.isArray(page.rows) && runeIn(page.rows[i], primary, i + 1) ? page.rows[i] : null));
  const secondaryRunes = (Array.isArray(page.secondaryRunes) ? page.secondaryRunes : [])
    .filter((id) => runeIn(id, secondary) && data.runeById.get(id).slot > 0).slice(0, 2);
  const rowsData = data.shardRows || [];
  const shards = [0, 1, 2].map((i) => (Array.isArray(page.shards) && (rowsData[i] || []).includes(page.shards[i]) ? page.shards[i] : null));
  const stacks = page.stacks && typeof page.stacks === 'object' ? page.stacks : {};
  return {
    primary, keystone: runeIn(page.keystone, primary, 0) ? page.keystone : null, rows, secondary, secondaryRunes,
    shards, stacks,
  };
}

/** Nur die Form prüfen (vor dem Laden der Spieldaten); die Inhalte prüft {@link sanitizeRunePage}. */
function shapeRunePage(page) {
  if (!page || typeof page !== 'object') {
    return EMPTY_RUNE_PAGE;
  }
  const ids = (list, length) => Array.from({ length }, (_, i) => (Array.isArray(list) && Number.isInteger(list[i]) ? list[i] : null));
  return {
    primary: Number.isInteger(page.primary) ? page.primary : null,
    keystone: Number.isInteger(page.keystone) ? page.keystone : null,
    rows: ids(page.rows, 3),
    secondary: Number.isInteger(page.secondary) ? page.secondary : null,
    secondaryRunes: ids(page.secondaryRunes, 2).filter((id) => id !== null),
    shards: ids(page.shards, 3),
    stacks: page.stacks && typeof page.stacks === 'object' ? page.stacks : {},
  };
}

/**
 * Übernimmt eine Runenseite aus der Statistik ({@code primary}: Keystone und Reihen, {@code sub}: zwei Runen,
 * {@code shardIds}) in den Build-Rechner. Eingestellte Stapel bleiben erhalten. Mit {@code champion} wird er zum
 * Build-Champion; seine Rolle folgt daraus wie in der Statistik (Hand-Rolle, Rollenfilter, häufigste Rolle).
 */
export function importRunePage(lines, champion) {
  if (champion) {
    store.set({ buildChampion: String(champion.key) });
  }
  const { data, runePage } = store.get();
  const rune = (id) => data.runeById.get(id);
  const primaryRunes = (lines.primary || []).map(rune).filter(Boolean);
  const keystone = primaryRunes.find((r) => r.slot === 0);
  const primary = keystone ? keystone.treeId : primaryRunes.length ? primaryRunes[0].treeId : null;
  const rows = [1, 2, 3].map((slot) => (primaryRunes.find((r) => r.treeId === primary && r.slot === slot) || { id: null }).id);
  const secondaryRunes = (lines.sub || []).map(rune).filter((r) => r && r.treeId !== primary && r.slot > 0).map((r) => r.id).slice(0, 2);
  const secondary = secondaryRunes.length ? rune(secondaryRunes[0]).treeId : null;
  store.set({
    runePage: sanitizeRunePage({
      primary, keystone: keystone ? keystone.id : null, rows, secondary, secondaryRunes,
      shards: lines.shardIds || [], stacks: runePage.stacks,
    }, data),
  });
  toast(champion ? `Runenseite und ${champion.name} in den Build-Rechner übernommen` : 'Runenseite in den Build-Rechner übernommen');
}

/** Abfrage für Build-Endpunkte: Items plus Runenseite ({@code runes}, {@code shards}, {@code stacks}). */
export function buildQuery(build, page) {
  const runes = runePageRunes(page);
  const shards = page.shards.filter((id) => id !== null);
  const stacks = runes.filter((id) => page.stacks[id] > 0).map((id) => `${id}:${page.stacks[id]}`);
  const parts = [`items=${build.join(',')}`];
  if (runes.length) {
    parts.push(`runes=${runes.join(',')}`);
  }
  // Shards nur vollständig: die Reihenfolge entscheidet über die Reihe.
  if (shards.length === 3) {
    parts.push(`shards=${shards.join(',')}`);
  }
  if (stacks.length) {
    parts.push(`stacks=${stacks.join(',')}`);
  }
  return parts.join('&');
}

/** {@link buildQuery} mit dem aktuellen Build und der Runenseite aus dem Store. */
export function useBuildQuery() {
  const page = useStore((s) => s.runePage);
  return buildQuery(useBuildItems(), page);
}

/** Item-IDs des Builds für Berechnung und Anzeige (beim ADC mit Boots-Platz am Ende). */
export function useBuildItems() {
  const build = useStore((s) => s.build);
  const mode = useStore((s) => s.buildMode);
  const boots = useStore((s) => s.buildBoots);
  return buildItems({ build, buildMode: mode, buildBoots: boots });
}

export function setPref(patch) {
  store.set({ prefs: { ...store.get().prefs, ...patch } });
}

// ------------------------------------------------------------------ Favoriten (eigener Champion-Pool)

/** Obergrenze gegen kaputte gespeicherte Stände; mehr Champions gibt es nicht. */
const MAX_FAVORITES = 400;

/** Favorisierte Champion-Keys als Set; ändert sich nur, wenn sich die Favoriten ändern. */
export function useFavorites() {
  const favorites = useStore((s) => s.favorites);
  return useMemo(() => new Set(favorites), [favorites]);
}

export function toggleFavorite(key) {
  const favorites = store.get().favorites;
  store.set({ favorites: favorites.includes(key) ? favorites.filter((k) => k !== key) : [...favorites, key] });
}

export function clearFavorites() {
  store.set({ favorites: [] });
}

function sanitizeFavorites(value) {
  return Array.isArray(value) ? [...new Set(value.filter((k) => Number.isInteger(k)))].slice(0, MAX_FAVORITES) : [];
}

// ------------------------------------------------------------------ Routing

const VIEWS = ['champions', 'items', 'runes', 'abilities', 'summoner', 'draft', 'patch'];
const lastIds = {};

function parseHash() {
  const [, view, id] = location.hash.split('/');
  return { view: VIEWS.includes(view) ? view : 'champions', id: id ? decodeURIComponent(id) : null };
}

function applyRoute() {
  const route = parseHash();
  if (route.id !== null) {
    lastIds[route.view] = route.id;
  } else if (lastIds[route.view] !== undefined) {
    route.id = lastIds[route.view];
  }
  store.set({ route });
}

function hashFor(view, id) {
  return id === null || id === undefined ? `#/${view}` : `#/${view}/${encodeURIComponent(id)}`;
}

/** Wechselt die Ansicht (neuer Verlaufseintrag). */
export function go(view, id) {
  const target = hashFor(view, id);
  if (location.hash === target) {
    applyRoute();
  } else {
    location.hash = target;
  }
}

/** Ändert nur die Auswahl innerhalb der Ansicht (kein Verlaufseintrag, z. B. für Pfeiltasten); `null` hebt sie auf. */
export function select(view, id) {
  if (id === null) {
    delete lastIds[view]; // sonst stellt applyRoute die vorherige Auswahl wieder her
  }
  history.replaceState(null, '', hashFor(view, id));
  applyRoute();
}

/**
 * Springt zu einem Champion im Champions-Tab mit einer bestimmten Detail-Ansicht (`overview|stats|counters`).
 * Mit `role` wird die Rolle des Champions gesetzt (wie die Auswahl im Detail), damit Statistik und Counters passen.
 */
export function openChampion(champion, tab, role) {
  const views = store.get().views;
  const next = { ...views, 'champion-detail': { ...(views['champion-detail'] || {}), tab } };
  if (role) {
    next[CHAMPION_ROLES] = { ...(views[CHAMPION_ROLES] || {}), [champion.key]: role };
  }
  store.set({ views: next });
  go('champions', champion.id);
}

export function startRouter() {
  window.addEventListener('hashchange', applyRoute);
  applyRoute();
}

// ------------------------------------------------------------------ Zustand speichern

const PERSISTED = ['theme', 'filter', 'dataSource', 'build', 'buildMode', 'buildBoots', 'buildChampion', 'buildRole', 'runePage', 'split', 'prefs', 'summonerRecent', 'favorites', 'window', 'draftDetailsOpen', 'draftOverviewOpen', 'draftFearlessLocksOpen'];
let saveTimer = null;
let lastSaved = null;

export async function loadUiState() {
  try {
    const saved = await api.get('/api/ui-state');
    const patch = {};
    PERSISTED.forEach((key) => {
      if (saved && saved[key] !== undefined) {
        // Objekte zusammenführen: In älteren Ständen fehlende Felder behalten so ihren Standardwert.
        patch[key] = key === 'prefs' || key === 'dataSource' ? { ...store.get()[key], ...saved[key] } : saved[key];
      }
    });
    if (patch.favorites !== undefined) {
      patch.favorites = sanitizeFavorites(patch.favorites);
    }
    if (Array.isArray(patch.build)) {
      patch.build = patch.build.filter((id) => Number.isInteger(id)).slice(0, MAX_BUILD);
    }
    // Ältere Stände kannten nur den ADC-Schalter (`buildAdc`).
    if (patch.buildMode === undefined && saved && saved.buildAdc === true) {
      patch.buildMode = 'adc';
    }
    if (patch.buildMode !== undefined && !BUILD_MODES.some((m) => m.value === patch.buildMode)) {
      patch.buildMode = '';
    }
    if (patch.buildBoots !== undefined && !Number.isInteger(patch.buildBoots)) {
      patch.buildBoots = null;
    }
    if (patch.buildChampion !== undefined && typeof patch.buildChampion !== 'string') {
      patch.buildChampion = null;
    }
    if (patch.buildRole !== undefined && !BUILD_ROLES.some((r) => r.value === patch.buildRole)) {
      patch.buildRole = '';
    }
    if (patch.runePage !== undefined) {
      const { data } = store.get();
      patch.runePage = data ? sanitizeRunePage(patch.runePage, data) : shapeRunePage(patch.runePage);
    }
    store.set(patch);
  } catch {
    // Ladefehler (z. B. beschädigte Datei): nichts speichern, sonst würde der Standardzustand die Datei überschreiben
    return;
  }
  lastSaved = snapshot();
  store.subscribe(scheduleSave);
  trackWindow();
}

/** Toleranz in Pixeln: Ein maximiertes Fenster ragt unter Windows ein paar Pixel über den Arbeitsbereich. */
const MAXIMIZED_SLACK = 12;
const WINDOW_CHECK_MS = 2000;

/** Aktuelle Fensterlage; bei maximiertem Fenster bleibt die zuletzt freie Größe für das Wiederherstellen erhalten. */
function windowPlacement(previous) {
  const s = window.screen;
  const maximized = window.outerWidth >= s.availWidth - MAXIMIZED_SLACK && window.outerHeight >= s.availHeight - MAXIMIZED_SLACK;
  if (maximized) {
    return { ...(previous || {}), maximized: true };
  }
  return { maximized: false, x: window.screenX, y: window.screenY, width: window.outerWidth, height: window.outerHeight };
}

/**
 * Merkt sich Größe, Position und Maximierung des App-Fensters in `store.window`; `AppWindow` öffnet es beim
 * nächsten Start wieder so. Verschieben löst kein Ereignis aus, daher zusätzlich eine Prüfung alle 2 s; beim
 * Schließen wird sofort gespeichert, weil der Server danach endet.
 */
function trackWindow() {
  const update = () => {
    const previous = store.get().window;
    const next = windowPlacement(previous);
    if (JSON.stringify(next) !== JSON.stringify(previous)) {
      store.set({ window: next });
    }
  };
  update();
  window.addEventListener('resize', update);
  setInterval(update, WINDOW_CHECK_MS);
  window.addEventListener('pagehide', () => {
    update();
    const current = snapshot();
    if (current !== lastSaved) {
      lastSaved = current;
      fetch('/api/ui-state', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: current, keepalive: true }).catch(() => {});
    }
  });
}

function snapshot() {
  const state = store.get();
  return JSON.stringify(Object.fromEntries(PERSISTED.map((key) => [key, state[key]])));
}

function scheduleSave() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    const current = snapshot();
    if (current !== lastSaved) {
      lastSaved = current;
      api.put('/api/ui-state', JSON.parse(current)).catch(() => {});
    }
  }, 500);
}

// ------------------------------------------------------------------ Hinweise

let toastTimer = null;

export function toast(text, kind = 'info') {
  store.set({ toast: { text, kind } });
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => store.set({ toast: null }), 5000);
}

// ------------------------------------------------------------------ Formatierung

export function percent(fraction, decimals = 1) {
  return fraction === null || fraction === undefined || Number.isNaN(fraction) ? '–' : `${(fraction * 100).toFixed(decimals)} %`;
}

export function normalize(text) {
  return (text || '').trim().toLowerCase();
}

const collator = new Intl.Collator('de', { sensitivity: 'base', numeric: true });

/** Die Sortierung, falls ihre Spalte in dieser Ansicht existiert, sonst nach Name. */
export function activeSort(columns, sort) {
  return columns.some((c) => c.key === sort.key && c.value) ? sort : { key: 'name', dir: 1 };
}

/** Sortiert nach {@code column.value}; fehlende Werte stehen immer am Ende. */
export function sortRows(rows, columns, sort) {
  const column = columns.find((c) => c.key === sort.key && c.value);
  if (!column) {
    return rows;
  }
  const direction = sort.dir;
  const decorated = rows.map((row) => [row, column.value(row)]);
  decorated.sort(([, a], [, b]) => {
    const missingA = a === null || a === undefined;
    const missingB = b === null || b === undefined;
    if (missingA || missingB) {
      return missingA === missingB ? 0 : missingA ? 1 : -1;
    }
    const result = typeof a === 'string' ? collator.compare(a, b) : a - b;
    return result * direction;
  });
  return decorated.map(([row]) => row);
}
