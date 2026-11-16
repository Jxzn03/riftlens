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

export function clearResponseCache() {
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

export function statsUrl(path) {
  const { filter, dataSource } = store.get();
  const params = new URLSearchParams({ role: filter.role, period: dataSource.period, laTier: dataSource.tier });
  return `${path}?${params}`;
}

/** Aktuelle Statistik-URL, die sich mit Rolle, Zeitraum und Rang-Bereich ändert. */
export function useStatsUrl(path) {
  useStore((s) => s.filter);
  useStore((s) => s.dataSource);
  return path ? statsUrl(path) : null;
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
  const url = useStatsUrl(path);
  if (!url) {
    return null;
  }
  const [base, query] = url.split('?');
  const params = new URLSearchParams(query);
  params.set('role', role || '');
  return `${base}?${params}`;
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
 */
export function useDeferredUrl(url, delay) {
  const ready = !delay || !url || responseCache.has(url);
  const [value, setValue] = useState(ready ? url : null);
  useEffect(() => {
    if (ready) {
      setValue(url);
      return undefined;
    }
    setValue(null);
    const timer = setTimeout(() => setValue(url), delay);
    return () => clearTimeout(timer);
  }, [url, delay]);
  return value;
}

// ------------------------------------------------------------------ Spieldaten

let announcedPatchChange = null;

export async function loadData() {
  try {
    const data = await api.get('/api/bootstrap');
    data.championById = new Map(data.champions.map((c) => [c.id, c]));
    data.championByKey = new Map(data.champions.map((c) => [c.key, c]));
    data.itemById = new Map(data.items.map((i) => [i.id, i]));
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
      toast(`Neuer Patch ${data.patchChange.to}${data.patchChange.from ? ` (vorher ${data.patchChange.from})` : ''}: Item- und Runenwerte abgeglichen, Wiki- und Statistikdaten werden neu geladen.`);
    }
    store.set({
      data,
      bootError: null,
      build: store.get().build.filter((id) => data.itemById.has(id)),
      runePage: sanitizeRunePage(store.get().runePage, data),
      status: `Patch ${data.patch} geladen: ${data.champions.length} Champions, ${data.items.length} Items`
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

export const MAX_BUILD = 6;

export function addToBuild(id) {
  const { build } = store.get();
  if (build.length < MAX_BUILD) {
    store.set({ build: [...build, id] });
  }
}

export function removeFromBuild(index) {
  store.set({ build: store.get().build.filter((_, i) => i !== index) });
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
export function sanitizeRunePage(page, data) {
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
 * {@code shardIds}) in den Build-Rechner. Eingestellte Stapel bleiben erhalten.
 */
export function importRunePage(lines) {
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
  toast('Runenseite in den Build-Rechner übernommen');
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
  const build = useStore((s) => s.build);
  const page = useStore((s) => s.runePage);
  return buildQuery(build, page);
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

const VIEWS = ['champions', 'items', 'runes', 'abilities', 'summoner', 'draft'];
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

const PERSISTED = ['theme', 'filter', 'dataSource', 'build', 'runePage', 'split', 'prefs', 'summonerRecent', 'favorites', 'window', 'draftDetailsOpen'];
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
    if (patch.runePage !== undefined) {
      const { data } = store.get();
      patch.runePage = data ? sanitizeRunePage(patch.runePage, data) : shapeRunePage(patch.runePage);
    }
    store.set(patch);
  } catch {
    // Ohne gespeicherten Zustand starten
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

export function winRateText(stats, decimals = 1) {
  return `${percent(stats.winRate, decimals)} (KI ${(stats.lower * 100).toFixed(decimals)}–${percent(stats.upper, decimals)})`;
}

export function summary(line, decimals = 1) {
  return `${winRateText(line, decimals)} WR · ${percent(line.pickRate)} PR · ${line.games.toLocaleString('de-DE')} Spiele`;
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
