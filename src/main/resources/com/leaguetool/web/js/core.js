// Kern der Oberfläche: Store, API-Zugriff, Routing, Formatierung.
import { useState, useEffect, useLayoutEffect, useRef, useReducer } from '/vendor/htm-preact.js';

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
  split: {},
  prefs: { level: 1, haste: 0, previewLevel: 11 },
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
    clearResponseCache();
    store.set({
      data,
      bootError: null,
      build: store.get().build.filter((id) => data.itemById.has(id)),
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

export function setPref(patch) {
  store.set({ prefs: { ...store.get().prefs, ...patch } });
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

/** Ändert nur die Auswahl innerhalb der Ansicht (kein Verlaufseintrag, z. B. für Pfeiltasten). */
export function select(view, id) {
  history.replaceState(null, '', hashFor(view, id));
  applyRoute();
}

export function startRouter() {
  window.addEventListener('hashchange', applyRoute);
  applyRoute();
}

// ------------------------------------------------------------------ Zustand speichern

const PERSISTED = ['theme', 'filter', 'dataSource', 'build', 'split', 'prefs', 'summonerRecent'];
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
    if (Array.isArray(patch.build)) {
      patch.build = patch.build.filter((id) => Number.isInteger(id)).slice(0, MAX_BUILD);
    }
    store.set(patch);
  } catch {
    // Ohne gespeicherten Zustand starten
  }
  lastSaved = snapshot();
  store.subscribe(scheduleSave);
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
