// Start der Oberfläche.
import { html, render } from '/vendor/htm-preact.js';
import { store, loadData, loadUiState, startRouter, setUpdate, loadUpdate, showNewReleaseNotes } from './core.js';
import { installTooltips } from './ui.js';
import { App } from './shell.js';

function connectEvents() {
  const events = new EventSource('/api/events');
  events.addEventListener('open', () => store.set({ connected: true }));
  events.addEventListener('error', () => store.set({ connected: false }));
  events.addEventListener('data-changed', (e) => {
    const { version } = JSON.parse(e.data);
    const { data } = store.get();
    if (!data || data.version < version) {
      loadData();
    }
  });
  events.addEventListener('summoner-progress', (e) => store.set({ summonerProgress: JSON.parse(e.data) }));
  events.addEventListener('draft-progress', (e) => store.set({ draftProgress: JSON.parse(e.data) }));
  events.addEventListener('lcu', (e) => store.set({ lcu: JSON.parse(e.data) }));
  events.addEventListener('update', (e) => setUpdate(JSON.parse(e.data)));
}

function installShortcuts() {
  window.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      store.set({ palette: !store.get().palette });
    }
  });
}

async function start() {
  await loadUiState();
  document.documentElement.dataset.theme = store.get().theme;
  startRouter();
  installTooltips();
  installShortcuts();
  render(html`<${App} />`, document.getElementById('app'));
  connectEvents();
  loadData();
  loadUpdate();
  showNewReleaseNotes();
}

start();
