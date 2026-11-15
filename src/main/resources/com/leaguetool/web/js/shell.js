// App-Rahmen: Navigation, Filterleiste, Schnellsuche, Dialoge, Statuszeile.
import { html, useState, useEffect, useMemo, useRef } from '/vendor/htm-preact.js';
import { store, useStore, useFetch, useStatsUrl, api, go, normalize, percent, toast, setPref, TIERS } from './core.js';
import { Icon, Img, Button, Select, Segmented, Switch, Spinner, Loading, ErrorNote } from './ui.js';
import { ChampionsView } from './champions.js';
import { ItemsView } from './items.js';
import { RunesView } from './runes.js';
import { AbilitiesView } from './abilities.js';
import { SummonerView } from './summoner.js';
import { DraftView } from './draft.js';

const NAV = [
  { view: 'draft', label: 'Draft', icon: 'target' },
  { view: 'champions', label: 'Champions', icon: 'swords' },
  { view: 'items', label: 'Items', icon: 'box' },
  { view: 'runes', label: 'Runen', icon: 'gem' },
  { view: 'abilities', label: 'Abilities', icon: 'wand' },
  { view: 'summoner', label: 'Summoner', icon: 'user' },
];

/** Nur diese Ansichten zeigen Lolalytics-Statistiken und brauchen die Filter der Kopfleiste. */
const STAT_VIEWS = ['champions', 'abilities', 'draft'];
/** Der Draft wählt Rollen pro Champion, der Rollenfilter der Kopfleiste gilt dort nicht. */
const ROLE_FILTER_VIEWS = ['champions', 'abilities'];

const VIEW_COMPONENTS = {
  champions: ChampionsView, items: ItemsView, runes: RunesView, abilities: AbilitiesView, summoner: SummonerView, draft: DraftView,
};

export function App() {
  const data = useStore((s) => s.data);
  const bootError = useStore((s) => s.bootError);
  const route = useStore((s) => s.route);
  const theme = useStore((s) => s.theme);
  const dialog = useStore((s) => s.dialog);
  const palette = useStore((s) => s.palette);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  if (bootError && !data) {
    return html`<div class="boot boot-error">
      <${Icon} name="alert" size=${36} />
      <h2>Spieldaten konnten nicht geladen werden</h2>
      <p>${bootError}</p>
      <${Button} kind="primary" icon="refresh" onClick=${() => retryBoot()}>Erneut versuchen<//>
    </div>`;
  }
  if (!data) {
    return html`<div class="boot"><div class="boot-mark"></div><p>Lade Spieldaten …</p></div>`;
  }
  const View = VIEW_COMPONENTS[route.view];
  return html`<div class="app">
    <${Rail} active=${route.view} />
    <div class="main">
      <${TopBar} data=${data} view=${route.view} />
      <main class="content"><${View} key=${route.view} id=${route.id} /></main>
      <${StatusBar} />
    </div>
    ${palette ? html`<${Palette} data=${data} />` : null}
    ${dialog === 'settings' ? html`<${SettingsDialog} />` : null}
    <${Toast} />
  </div>`;
}

function retryBoot() {
  store.set({ bootError: null });
  api.post('/api/reload').finally(() => import('./core.js').then((core) => core.loadData()));
}

function Rail({ active }) {
  return html`<nav class="rail" aria-label="Hauptnavigation">
    <div class="rail-logo"><svg viewBox="0 0 32 32" width="30" height="30" aria-hidden="true"><path d="M16 3 28 10v12l-12 7-12-7V10z" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 10v12h9" fill="none" stroke="var(--accent-2)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg></div>
    <div class="rail-group">
      ${NAV.map((n) => html`<button type="button" key=${n.view} class=${`rail-btn ${active === n.view ? 'active' : ''}`} aria-current=${active === n.view ? 'page' : undefined} onClick=${() => go(n.view)}>
        <${Icon} name=${n.icon} size=${20} /><span>${n.label}</span>
      </button>`)}
    </div>
    <div class="rail-group rail-bottom">
      <button type="button" class="rail-btn" onClick=${() => store.set({ dialog: 'settings' })}><${Icon} name="settings" size=${19} /><span>Optionen</span></button>
    </div>
  </nav>`;
}

const PERIODS = [
  { value: '30', label: '30 Tage', tip: 'Die letzten 30 Tage (größte Stichprobe, mehrere Patches)' },
  { value: 'patch', label: 'Letzter Patch', tip: 'Nur der aktuelle Patch' },
];

function TopBar({ data, view }) {
  const filter = useStore((s) => s.filter);
  const dataSource = useStore((s) => s.dataSource);
  const theme = useStore((s) => s.theme);
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const metaUrl = useStatsUrl('/api/stats/meta');
  const meta = useFetch(metaUrl, { keep: true });
  const setFilter = (patch) => store.set({ filter: { ...store.get().filter, ...patch } });
  const setSource = (patch) => store.set({ dataSource: { ...store.get().dataSource, ...patch } });

  // Basis der Winrate-Färbung: Durchschnitt des aktuell gewählten Lolalytics-Datensatzes.
  useEffect(() => {
    store.set({ avgWinRate: meta.data && meta.data.avgWinRate !== undefined ? meta.data.avgWinRate : null });
  }, [meta.data]);

  let count = '';
  if (meta.error) {
    count = meta.error.message;
  } else if (meta.data) {
    count = `${(meta.data.analysed / 1e6).toLocaleString('de-DE', { maximumFractionDigits: 1 })} Mio. Picks · Ø ${percent(meta.data.avgWinRate)} WR`;
  }

  return html`<header class="topbar">
    <button type="button" class="palette-trigger" onClick=${() => store.set({ palette: true })}>
      <${Icon} name="search" size=${16} /><span>Alles durchsuchen …</span><kbd>Strg K</kbd>
    </button>
    ${STAT_VIEWS.includes(view) ? html`<div class="filters">
      <${Segmented} label="Zeitraum" value=${dataSource.period} options=${advancedTips ? PERIODS : PERIODS.map(({ tip, ...option }) => option)} onChange=${(period) => setSource({ period })} />
      <${Select} label="Rang-Bereich" value=${dataSource.tier} options=${TIERS} onChange=${(tier) => setSource({ tier })} />
      ${ROLE_FILTER_VIEWS.includes(view) ? html`<${Select} label="Rolle" value=${filter.role} onChange=${(role) => setFilter({ role })}
        options=${[{ value: '', label: 'Alle Rollen' }, ...(meta.data ? meta.data.roles : [])]} />` : null}
      <span class="match-count" data-tip=${advancedTips ? 'Analysierte Champion-Picks und durchschnittliche Winrate laut Lolalytics' : undefined}>${count}</span>
    </div>` : null}
    <div class="topbar-end">
      <span class=${`patch-chip ${data.offline ? 'offline' : ''}`} data-tip=${advancedTips ? (data.offline ? 'Offline aus dem Cache' : 'Aktueller Patch von Data Dragon') : undefined}>
        <i></i>Patch ${data.patch}
      </span>
      <${Button} icon="refresh" kind="icon" title="Neu laden" tip="Spieldaten neu laden"
        onClick=${() => { store.set({ status: 'Lade Spieldaten …' }); api.post('/api/reload').catch((e) => toast(e.message, 'error')); }} />
      <${Button} icon=${theme === 'dark' ? 'sun' : 'moon'} kind="icon" title=${theme === 'dark' ? 'Helles Design' : 'Dunkles Design'}
        tip=${theme === 'dark' ? 'Helles Design' : 'Dunkles Design'} onClick=${() => store.set({ theme: theme === 'dark' ? 'light' : 'dark' })} />
    </div>
  </header>`;
}

function StatusBar() {
  const status = useStore((s) => s.status);
  const busy = useStore((s) => s.busy);
  const connected = useStore((s) => s.connected);
  return html`<footer class="statusbar">
    <span class="status-busy">${busy > 0 ? html`<${Spinner} small />` : null}</span>
    <span class="status-text">${status}</span>
    ${connected ? null : html`<span class="bad">Verbindung zum Programm getrennt</span>`}
  </footer>`;
}

function Toast() {
  const toastState = useStore((s) => s.toast);
  return toastState ? html`<div class=${`toast ${toastState.kind}`} role="status">${toastState.text}</div>` : null;
}

// ------------------------------------------------------------------ Schnellsuche

function Palette({ data }) {
  const [q, setQ] = useState('');
  const [index, setIndex] = useState(0);
  const input = useRef();
  const close = () => store.set({ palette: false });

  useEffect(() => input.current.focus(), []);

  const results = useMemo(() => {
    const query = normalize(q);
    if (!query) {
      return data.champions.slice(0, 8).map((c) => ({ kind: 'Champion', name: c.name, sub: c.title, icon: c.icon, view: 'champions', id: c.id }));
    }
    const hit = (name) => name.toLowerCase().includes(query);
    const score = (name) => (name.toLowerCase().startsWith(query) ? 0 : 1);
    const all = [
      ...data.champions.filter((c) => hit(c.name)).map((c) => ({ kind: 'Champion', name: c.name, sub: c.title, icon: c.icon, view: 'champions', id: c.id })),
      ...data.items.filter((i) => i.rift && hit(i.name)).map((i) => ({ kind: 'Item', name: i.name, sub: `${i.gold.total} Gold`, icon: i.icon, view: 'items', id: i.id })),
      ...data.runeTrees.flatMap((t) => t.slots.flat()).filter((r) => hit(r.name)).map((r) => ({ kind: 'Rune', name: r.name, sub: data.treeById.get(r.treeId).name, icon: r.icon, view: 'runes', id: r.id })),
    ];
    return all.sort((a, b) => score(a.name) - score(b.name)).slice(0, 12);
  }, [q, data]);

  const choose = (result) => {
    close();
    go(result.view, result.id);
  };
  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setIndex((i) => Math.min(results.length - 1, i + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setIndex((i) => Math.max(0, i - 1));
    } else if (e.key === 'Enter' && results[index]) {
      choose(results[index]);
    } else if (e.key === 'Escape') {
      close();
    }
  };

  return html`<div class="overlay palette-overlay" onMouseDown=${(e) => e.target === e.currentTarget && close()}>
    <div class="palette" role="dialog" aria-label="Schnellsuche" onKeyDown=${onKeyDown}>
      <label class="palette-input"><${Icon} name="search" size=${18} />
        <input ref=${input} value=${q} spellcheck="false" placeholder="Champion, Item oder Rune …" onInput=${(e) => { setQ(e.currentTarget.value); setIndex(0); }} />
      </label>
      <div class="palette-results">
        ${results.map((r, i) => html`<button type="button" key=${`${r.view}-${r.id}`} class=${`palette-row ${i === index ? 'active' : ''}`}
          onMouseEnter=${() => setIndex(i)} onClick=${() => choose(r)}>
          <${Img} src=${r.icon} size=${30} /><span class="palette-name">${r.name}</span><small>${r.sub}</small><em>${r.kind}</em>
        </button>`)}
        ${results.length === 0 ? html`<p class="palette-empty">Keine Treffer</p>` : null}
      </div>
    </div>
  </div>`;
}

// ------------------------------------------------------------------ Dialoge

function Modal({ title, children, onClose, footer }) {
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  return html`<div class="overlay" onMouseDown=${(e) => e.target === e.currentTarget && onClose()}>
    <div class="modal" role="dialog" aria-modal="true" aria-label=${title}>
      <header class="modal-head"><h2>${title}</h2><${Button} icon="x" kind="icon" title="Schließen" onClick=${onClose} /></header>
      <div class="modal-body">${children}</div>
      ${footer ? html`<footer class="modal-foot">${footer}</footer>` : null}
    </div>
  </div>`;
}

const closeDialog = () => store.set({ dialog: null });

function SettingsDialog() {
  const loaded = useFetch('/api/settings?fresh=' + useRef(Date.now()).current);
  const [form, setForm] = useState(null);
  const [key, setKey] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));

  useEffect(() => {
    if (loaded.data && !form) {
      setForm(loaded.data);
    }
  }, [loaded.data]);

  const set = (patch) => setForm({ ...form, ...patch });
  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      const body = {
        locale: form.locale, offline: form.offline, wikiEnabled: form.wikiEnabled,
        lolalyticsEnabled: form.lolalyticsEnabled,
        riotEnabled: form.riotEnabled, platform: form.platform, lcuFolder: form.lcuFolder || '',
      };
      if (key.trim() !== '' || form.clearKey) {
        body.riotApiKey = key.trim();
      }
      await api.post('/api/settings', body);
      store.set({ dialog: null, status: 'Einstellungen gespeichert. Lade Spieldaten …' });
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  };

  const footer = html`<${Button} onClick=${closeDialog}>Abbrechen<//><${Button} kind="primary" disabled=${!form || saving} onClick=${save}>${saving ? 'Speichert …' : 'Speichern'}<//>`;
  return html`<${Modal} title="Einstellungen" onClose=${closeDialog} footer=${footer}>
    ${loaded.error ? html`<${ErrorNote} prefix="Einstellungen nicht verfügbar" error=${loaded.error} />` : null}
    ${error ? html`<${ErrorNote} prefix="Speichern fehlgeschlagen" error=${error} />` : null}
    ${!form && !loaded.error ? html`<${Loading} />` : null}
    ${form ? html`<div class="form">
      <label class="field"><span>Data-Dragon-Sprache</span>
        <${Select} label="Sprache" value=${form.locale} onChange=${(locale) => set({ locale })} options=${form.locales.map((l) => ({ value: l, label: l }))} /></label>
      <${Switch} checked=${form.offline} onChange=${(offline) => set({ offline })}>Offline-Modus (nur Cache verwenden)<//>
      <${Switch} checked=${form.wikiEnabled} onChange=${(wikiEnabled) => set({ wikiEnabled })}>Zusatzdaten aus dem LoL-Wiki laden<//>
      <${Switch} checked=${form.lolalyticsEnabled} onChange=${(lolalyticsEnabled) => set({ lolalyticsEnabled })}>Lolalytics-Statistiken erlauben (öffentliche Seiten, gecacht)<//>
      <${Switch} checked=${advancedTips} onChange=${(on) => setPref({ advancedTips: on })}>Show Advanced Tooltips (zusätzliche Hover-Infos in der Champions-Statistik)<//>
      <div class="divider"></div>
      <${Switch} checked=${form.riotEnabled} onChange=${(riotEnabled) => set({ riotEnabled })}>Riot-API verwenden (Summoner-Abfrage, Verifizierung)<//>
      <label class="field"><span>Riot-API-Key</span>
        <input class="input" type="password" autocomplete="off" value=${key} onInput=${(e) => setKey(e.currentTarget.value)}
          placeholder=${form.hasStoredKey ? 'Gespeichert – leer lassen, um ihn zu behalten' : `leer = Umgebungsvariable ${form.envKeyName}`} /></label>
      ${form.hasStoredKey ? html`<${Switch} checked=${Boolean(form.clearKey)} onChange=${(clearKey) => set({ clearKey })}>Gespeicherten Key entfernen<//>` : null}
      <label class="field"><span>Plattform</span>
        <${Select} label="Plattform" value=${form.platform} onChange=${(platform) => set({ platform })} options=${form.platforms.map((p) => ({ value: p, label: p.toUpperCase() }))} /></label>
      <p class="muted small">Der Key wird nur lokal in ${form.file} gespeichert. Dev-Keys laufen nach 24 Stunden ab.</p>
      <${RiotCheck} ready=${form.riotReady} />
      <div class="divider"></div>
      <label class="field"><span>League-Ordner für den Client-Abgleich (optional)</span>
        <input class="input" type="text" spellcheck="false" value=${form.lcuFolder || ''}
          onInput=${(e) => set({ lcuFolder: e.currentTarget.value })}
          placeholder=${form.lcuDetected ? `leer = automatisch (${form.lcuDetected})` : 'leer = automatisch, z. B. D:\\Spiele\\Riot Games\\League of Legends'} /></label>
      <p class="muted small">${form.lcuDetected
        ? `Automatisch gefunden: ${form.lcuDetected}. Nur ausfüllen, wenn League woanders liegt.`
        : 'Automatisch kein League gefunden. Ordner mit LeagueClient.exe eintragen; auch der Riot-Games-Ordner oder die Datei selbst gehen.'}</p>
    </div>` : null}
    <p class="legal-note">RiftLens isn't endorsed by Riot Games and doesn't reflect the views or opinions of Riot Games or anyone
      officially involved in producing or managing Riot Games properties. Riot Games, and all associated properties are
      trademarks or registered trademarks of Riot Games, Inc.</p>
  <//>`;
}

function RiotCheck({ ready }) {
  const [state, setState] = useState({});
  const run = () => {
    setState({ loading: true });
    api.post('/api/verify').then((report) => setState({ report })).catch((error) => setState({ error }));
  };
  return html`<div class="riot-check">
    <div class="riot-check-head">
      <${Button} icon="check" disabled=${!ready || state.loading} onClick=${run}
        tip="Prüft API-Key und vergleicht einen aktuellen Match mit den Data-Dragon-Daten">Riot prüfen<//>
      <span class="muted small">${ready ? 'Prüft die zuletzt gespeicherten Einstellungen.' : 'Zum Prüfen Riot-API aktivieren, Key eintragen und speichern.'}</span>
    </div>
    ${state.loading ? html`<${Loading} text="Riot-Verifizierung läuft …" />` : null}
    ${state.error ? html`<${ErrorNote} prefix="Prüfung fehlgeschlagen" error=${state.error} />` : null}
    ${state.report ? html`
      <p class=${state.report.allOk ? 'good' : 'bad'}><strong>${state.report.allOk ? 'Alle Prüfungen bestanden' : 'Abweichungen gefunden'}</strong></p>
      <ul class="checks">${state.report.checks.map((c, i) => html`<li key=${i} class=${c.ok ? 'good' : 'bad'}>
        <${Icon} name=${c.ok ? 'ok' : 'x'} size=${15} /><span><strong>${c.name}</strong>${c.detail ? html`<small>${c.detail}</small>` : null}</span>
      </li>`)}</ul>` : null}
  </div>`;
}
