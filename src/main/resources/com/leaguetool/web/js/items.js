// Ansicht "Items": Liste mit Filtern, Build-Rechner und Detail mit Werten und Baupfad.
import { html, useMemo, useEffect, useState, useRef } from '/vendor/htm-preact.js';
import {
  useStore, useViewState, useFetch, select, go, normalize, sortRows, addToBuild, removeFromBuild, moveInBuild, store,
  MAX_BUILD, runePageRunes, useBuildItems, canAddToBuild, setBuildMode, isBoots, isJunglePet, isSupportItem, supportLocked, api, useStatsUrl, useChampionRole,
  applyBuildRole, BUILD_ROLES, BUILD_MODES,
} from './core.js';
import {
  Img, Card, Button, Select, SearchInput, Switch, Tag, DataGrid, VirtualTable, MasterDetail, Empty, Icon, RuneIcons, MenuButton,
} from './ui.js';

const DEFAULTS = { q: '', tag: '', stat: '', completed: false, sort: { key: 'name', dir: 1 } };

// Data-Dragon-Tags, die nur einen Wert beschreiben: Sie stehen in der Spalte „Stats“, nicht unter den Kategorien.
const STAT_TAGS = new Set([
  'Health', 'HealthRegen', 'Mana', 'ManaRegen', 'Armor', 'SpellBlock', 'MagicResist', 'Damage', 'SpellDamage',
  'AttackSpeed', 'CriticalStrike', 'LifeSteal', 'SpellVamp', 'AbilityHaste', 'CooldownReduction', 'ArmorPenetration',
  'MagicPenetration', 'NonbootsMovement', 'Tenacity',
]);
// Korrekturen an den Data-Dragon-Kategorien (nur Anzeige, Suche und Filter): falsch zugeordnete Tags entfernen,
// „Jungle“ nur für die Jungle-Pets, „Stealth“ entfällt (Vision reicht), lesbare Namen.
const CATEGORY_LABELS = { OnHit: 'On-Hit', GoldPer: 'Gold-Per' };
const WRONG_TAGS = { 4646: ['GoldPer'], 2031: ['Active'], 6697: ['Active'], 6699: ['Active'] }; // Stormsurge, Refillable Potion, Hubris, Voltaic Cyclosword
const categoriesOf = (item) => item.tags.filter((t) => !STAT_TAGS.has(t) && t !== 'Stealth'
  && !(WRONG_TAGS[item.id] || []).includes(t)
  && !(t === 'Jungle' && !isJunglePet(item))
  && !(t === 'Active' && item.tags.includes('Trinket')))
  .map((t) => CATEGORY_LABELS[t] || t);

// Stat-Namen in der Data-Dragon-Sprache (Bootstrap `statNames`, Schlüssel = StatType); Kurzname für die Tabelle.
const statName = (names, s) => (names && names[s.key]) || { label: s.label, short: s.label };

function columnsFor(stat, names, advancedTips) {
  return [
    {
      key: 'name', title: 'Item', width: '190px', value: (r) => r.item.name,
      render: (r) => html`<div class="cell-hero"><${Img} src=${r.item.icon} size=${32} /><div><strong>${r.item.name}</strong></div></div>`,
    },
    { key: 'gold', title: 'Gold', width: '64px', align: 'right', firstDir: -1, value: (r) => r.item.gold.total, render: (r) => html`<span class="gold">${r.item.gold.total}</span>` },
    {
      key: 'goldValue', title: 'Goldwert', width: '76px', align: 'right', firstDir: -1,
      value: (r) => r.value.passiveTotal ?? (r.value.total || null),
      render: (r) => (r.value.total ? html`<span class="gold">${goldText(r.value.passiveTotal ?? r.value.total)}</span>` : null),
    },
    {
      key: 'efficiency', title: 'Effizienz', width: '76px', align: 'right', firstDir: -1,
      tip: advancedTips ? 'Goldwert der bewertbaren Stats ÷ Preis, mit immer aktiven Passiven (nur dieses Item im Besitz)' : undefined,
      value: (r) => r.value.passiveEfficiency ?? r.value.efficiency,
      render: (r) => {
        const eff = r.value.passiveEfficiency ?? r.value.efficiency;
        return eff === null ? null : html`<span class=${eff >= 1 ? 'good' : 'bad'}>${percentText(eff)}</span>`;
      },
    },
    {
      key: 'stats', title: 'Stats', width: 'minmax(300px, 1fr)', firstDir: -1,
      // Tooltips nur bei „Show Advanced Tooltips“ (Nutzerwunsch).
      tip: !advancedTips ? undefined
        : stat ? 'Sortiert nach dem gewählten Stat' : 'Sortiert nach Anzahl der Stats; mit Stat-Filter nach dessen Wert',
      value: (r) => (stat ? (r.item.stats.find((s) => s.key === stat) || {}).amount : r.item.stats.length || null),
      render: (r) => html`<span class="ellipsis item-stats"
        data-tip=${advancedTips && r.item.stats.length ? r.item.stats.map((s) => `${s.value} ${statName(names, s).label}`).join(' · ') : undefined}>
        ${r.item.stats.map((s, i) => html`${i ? ' · ' : ''}<span class=${s.key === stat ? 'hit' : ''}>${s.value} ${statName(names, s).short}</span>`)}
      </span>`,
    },
  ];
}

/** Sortierung nach Kategorie ohne eigene Spalte (Knopf neben der Kategorie-Auswahl). */
const CATEGORY_SORT = { key: 'tags', value: (r) => r.categories.join(', ') || null };

export function ItemsView({ id }) {
  const data = useStore((s) => s.data);
  const [view, setView] = useViewState('items', DEFAULTS);
  const itemId = id === null ? null : Number(id);

  const rows = useMemo(() => data.items.filter((item) => item.rift)
    .map((item) => ({ item, categories: categoriesOf(item), value: goldValueOf(data, item) })), [data]);
  const tags = useMemo(() => [...new Set(rows.flatMap((r) => r.categories))].sort(), [rows]);
  // Nur Stats, die Rift-Items wirklich haben; gleiche Namen (flach/prozentual) bekommen ein „(%)“.
  const stats = useMemo(() => {
    const byKey = new Map();
    rows.forEach((r) => r.item.stats.forEach((s) => byKey.set(s.key, s)));
    const labels = [...byKey.values()].map((s) => statName(data.statNames, s).label);
    return [...byKey.values()]
      .map((s) => {
        const label = statName(data.statNames, s).label;
        return { value: s.key, label: s.percent && labels.filter((l) => l === label).length > 1 ? `${label} (%)` : label };
      })
      .sort((a, b) => a.label.localeCompare(b.label));
  }, [rows]);
  // Gespeicherte Filter, die es nicht mehr gibt (z. B. ein alter Stat-Tag als Kategorie), wirken nicht.
  const tag = tags.includes(view.tag) ? view.tag : '';
  const stat = stats.some((s) => s.value === view.stat) ? view.stat : '';
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const columns = useMemo(() => columnsFor(stat, data.statNames, advancedTips), [stat, data, advancedTips]);

  const matches = (r, v) => {
    const q = normalize(v.q);
    return (!q || r.item.name.toLowerCase().includes(q) || String(r.item.id) === q
        || r.categories.some((c) => c.toLowerCase().replace('-', '').startsWith(q.replace('-', ''))))
      && (!v.tag || r.categories.includes(v.tag)) && (!v.stat || r.item.stats.some((s) => s.key === v.stat))
      && (!v.completed || (r.item.completed && !isBoots(r.item) && !isSupportItem(r.item) && !isJunglePet(r.item)));
  };
  const filters = { ...view, tag, stat };
  const visible = useMemo(() => sortRows(rows.filter((r) => matches(r, filters)), [...columns, CATEGORY_SORT], view.sort),
    [rows, columns, view.q, tag, stat, view.completed, view.sort]);

  // Leere Liste: zeigen, welche Zusatzfilter Treffer der Suche/Kategorie ausblenden.
  const hiddenBy = useMemo(() => {
    if (visible.length || (!stat && !view.completed)) {
      return null;
    }
    const base = rows.filter((r) => matches(r, { ...filters, stat: '', completed: false }));
    return base.length ? { count: base.length, names: [stat && 'Stat-Filter', view.completed && '„Nur fertige Items“'].filter(Boolean) } : null;
  }, [visible, rows, view.q, tag, stat, view.completed]);
  const empty = hiddenBy
    ? html`<span>Keine Items gefunden: ${hiddenBy.names.join(' und ')} blendet ${hiddenBy.count} ${hiddenBy.count === 1 ? 'Item' : 'Items'} aus.
        <${Button} kind="ghost" onClick=${() => setView({ stat: '', completed: false })}>Zusatzfilter zurücksetzen<//></span>`
    : 'Keine Items gefunden';

  // Sprung aus einer anderen Ansicht: Filter zurücksetzen, damit das Item in der Liste sichtbar ist.
  useEffect(() => {
    const target = rows.find((r) => r.item.id === itemId);
    if (target && !matches(target, filters)) {
      setView({ q: '', tag: '', stat: '', completed: false });
    }
  }, [itemId]);

  const item = itemId !== null ? data.itemById.get(itemId) : null;

  // Ziehen im Build-Rechner: from = Platz des gezogenen Items oder 'list' (aus der Liste, dazu id), over = Ziel
  // (Platznummer, 'boots' oder 'table'). Ablegen auf der Liste entfernt nur Items aus dem Build.
  const [drag, setDrag] = useState(null);
  const fromBuild = Boolean(drag) && drag.from !== 'list';
  // Zeilen der Liste lassen sich in den Build ziehen; passt das Item nicht (mehr) hinein, startet kein Ziehen.
  // Kostet kaum etwas: nur die sichtbaren Zeilen sind im DOM, geprüft wird erst beim Start des Ziehens.
  const listDragProps = (r) => ({
    draggable: 'true',
    onDragStart: (e) => {
      if (!canAddToBuild(store.get(), r.item)) {
        e.preventDefault();
        return;
      }
      e.dataTransfer.effectAllowed = 'copy';
      e.dataTransfer.setData('text/plain', String(r.item.id));
      setDrag({ from: 'list', id: r.item.id, over: null });
    },
    onDragEnd: () => setDrag(null),
  });
  const overTable = (e) => {
    if (fromBuild) {
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      if (drag.over !== 'table') {
        setDrag({ ...drag, over: 'table' });
      }
    }
  };
  const leaveTable = (e) => {
    if (drag && drag.over === 'table' && !e.currentTarget.contains(e.relatedTarget)) {
      setDrag({ ...drag, over: null });
    }
  };
  const dropOnTable = (e) => {
    if (fromBuild) {
      e.preventDefault();
      removeFromBuild(drag.from);
      setDrag(null);
    }
  };

  const master = html`<div class="pane">
    <div class="toolbar">
      <${SearchInput} value=${view.q} onInput=${(q) => setView({ q })} placeholder="Item suchen …" />
      <${Select} label="Kategorie" value=${tag} onChange=${(value) => setView({ tag: value })}
        options=${[{ value: '', label: 'Alle Kategorien' }, ...tags.map((t) => ({ value: t, label: t }))]} />
      <${Select} label="Stat" value=${stat} onChange=${(value) => setView({ stat: value })}
        options=${[{ value: '', label: 'Alle Stats' }, ...stats]} />
    </div>
    <div class="count-line">
      <span>${visible.length} Items</span>
      <div class="switches">
        <${Button} kind="ghost" icon=${view.sort.key !== 'tags' ? 'sort' : view.sort.dir === -1 ? 'down' : 'up'} title="Nach Kategorie sortieren"
          tip=${advancedTips ? 'Nach Kategorie sortieren (erneut klicken kehrt die Richtung um)' : undefined}
          onClick=${() => setView({ sort: { key: 'tags', dir: view.sort.key === 'tags' ? -view.sort.dir : 1 } })}>Kategorie<//>
        <${Switch} checked=${view.completed} onChange=${(completed) => setView({ completed })}>Nur fertige Items<//>
      </div>
    </div>
    <div class=${`build-drop-zone ${fromBuild ? 'armed' : ''} ${fromBuild && drag.over === 'table' ? 'over' : ''}`}
      onDragOver=${overTable} onDragLeave=${leaveTable} onDrop=${dropOnTable}>
      <${VirtualTable} rows=${visible} columns=${columns} rowKey=${(r) => r.item.id} selected=${itemId}
        onSelect=${(r) => select('items', r.item.id)} sort=${view.sort} onSort=${(sort) => setView({ sort })}
        rowHeight=${44} empty=${empty} rowProps=${listDragProps} />
      ${fromBuild ? html`<div class="build-drop-hint"><${Icon} name="trash" size=${16} /> Hier ablegen, um aus dem Build zu entfernen</div>` : null}
    </div>
    <${BuildPanel} data=${data} selected=${itemId} drag=${drag} setDrag=${setDrag} />
  </div>`;

  return html`<${MasterDetail} name="items" detailKey=${id} initial=${0.5} master=${master}
    detail=${item ? html`<${ItemDetail} key=${item.id} item=${item} data=${data} />`
      : html`<${Empty} title="Item auswählen">Wähle links ein Item für Werte, Beschreibung und Baupfad.<//>`} />`;
}

const MODE_TIPS = {
  adc: 'ADC: 6 normale Items, Boots auf einem festen 7. Platz',
  mid: 'Mid: Boots aufgewertet; geht an, wenn aufgewertete Boots dazukommen',
  support: 'Support: World Atlas und Runic Compass werden zu Bounty of Worlds; geht an, wenn ein Support-Item dazukommt',
  jungle: 'Jungle: erlaubt ein Jungle-Pet im Build (kommt nicht von selbst hinein); beim Ausschalten wird es entfernt',
};

// Beschriftung der Lolalytics-Lane (`counters.lane` im Report) → Rollenwert.
const LANE_ROLES = Object.fromEntries(BUILD_ROLES.map((r) => [r.label, r.value]));

/** Champion, auf den der Build angewendet wird ({@code store.buildChampion}), oder {@code null}. */
function useBuildChampion(data) {
  const championKey = useStore((s) => s.buildChampion);
  return championKey && data ? data.champions.find((c) => String(c.key) === championKey) || null : null;
}

/**
 * Hält den Modus des Build-Rechners zur Rolle des Build-Champions passend, in jeder Ansicht (liegt im App-Rahmen),
 * damit eine geänderte Rolle im Champions-Tab sofort wirkt. Rolle: von Hand gewählt (geteilt mit dem
 * Champions-Tab), sonst Rollenfilter, sonst die häufigste Rolle aus dem Lolalytics-Cache. Der Cache wird nur
 * gelesen (`cachedOnly=1`, frischer Report, sonst Lane aus veralteter Tierliste): Hier geht nie ein Abruf an
 * Lolalytics (Nutzervorgabe).
 * Die automatische Rolle steht für die Anzeige in {@code store.buildAuto}.
 */
export function BuildRoleSync() {
  const data = useStore((s) => s.data);
  const champion = useBuildChampion(data);
  const roleState = useChampionRole(champion ? champion.key : null);

  const statsUrl = useStatsUrl(champion && !roleState.role ? `/api/stats/report/${champion.key}` : null, '');
  const reportUrl = statsUrl && `${statsUrl}&cachedOnly=1`;
  const [auto, setAuto] = useState({ url: null, role: '' });
  useEffect(() => {
    if (!reportUrl) {
      return undefined;
    }
    let stale = false;
    const done = (role) => !stale && setAuto({ url: reportUrl, role });
    // Ohne frischen Report ({cached: false}) gilt die häufigste Lane aus einer gecachten, auch veralteten Tierliste
    // (`lane`, ohne Abruf); ohne beides oder mit abgeschaltetem Lolalytics bleibt die Rolle unbekannt.
    api.get(reportUrl).then((report) => done(LANE_ROLES[report && ((report.counters && report.counters.lane) || report.lane)] || ''), () => done(''));
    return () => { stale = true; };
  }, [reportUrl]);
  const autoKnown = !reportUrl || auto.url === reportUrl;
  const autoRole = reportUrl && autoKnown ? auto.role : '';
  const role = roleState.role || autoRole;

  useEffect(() => {
    store.set({ buildAuto: { known: autoKnown, role: autoRole } });
  }, [autoKnown, autoRole]);
  // Erst anwenden, wenn die Rolle zum aktuellen Champion feststeht (sonst kippt der Schalter kurz um).
  useEffect(() => {
    if (autoKnown) {
      applyBuildRole(champion ? role : '');
    }
  }, [champion ? champion.key : null, role, autoKnown]);
  return null;
}

/** Auswahl des Build-Champions und seiner Rolle (die Rolle wendet {@link BuildRoleSync} an). */
function BuildChampion({ data }) {
  const champion = useBuildChampion(data);
  const championKey = champion ? String(champion.key) : '';
  const roleState = useChampionRole(champion ? champion.key : null);
  const auto = useStore((s) => s.buildAuto);
  const champions = useMemo(() => [...data.champions].sort((a, b) => a.name.localeCompare(b.name)), [data]);

  const roleLabel = (value) => (BUILD_ROLES.find((r) => r.value === value) || {}).label;
  const autoLabel = roleState.chosen ? 'Auto'
    : `Auto (${roleState.role ? roleLabel(roleState.role) : !auto.known ? '…' : auto.role ? roleLabel(auto.role) : 'unbekannt'})`;
  return html`<div class="build-champion">
    ${champion ? html`<button type="button" class="icon-link" onClick=${() => go('champions', champion.id)}>
        <${Img} src=${champion.icon} size=${28} /></button>`
      : html`<span class="build-champion-empty"><${Icon} name="user" size=${14} /></span>`}
    <${Select} label="Champion für den Build" value=${championKey} cls="build-champion-select"
      onChange=${(value) => store.set({ buildChampion: value || null })}
      options=${[{ value: '', label: 'Kein Champion' }, ...champions.map((c) => ({ value: String(c.key), label: c.name }))]} />
    ${champion ? html`<${Select} label=${`Rolle von ${champion.name}`} value=${roleState.chosen} onChange=${roleState.setRole}
      options=${[{ value: '', label: autoLabel }, ...BUILD_ROLES]} />` : null}  </div>`;
}

function BuildPanel({ data, selected, drag, setDrag }) {
  const build = useStore((s) => s.build);
  const mode = useStore((s) => s.buildMode);
  const adc = mode === 'adc';
  const boots = useStore((s) => s.buildBoots);
  const championSet = useStore((s) => Boolean(s.buildChampion));
  const all = useBuildItems();
  const runePage = useStore((s) => s.runePage);
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const runes = runePageRunes(runePage);
  const totals = useFetch(all.length ? `/api/build?items=${all.join(',')}` : null, { keep: true }).data;
  const slots = Array.from({ length: MAX_BUILD }, (_, i) => build[i]);
  const [picker, setPicker] = useState(null); // Item-Auswahl über einen leeren Platz: { query }

  // Leere Plätze stehen immer hinten: Ablegen dort schiebt das Item ans Ende.
  // Der Boots-Platz tauscht nicht mit normalen Plätzen; von dort geht es nur auf die Liste (entfernen).
  // Aus der Liste gezogene Items nimmt jeder Platz an; wohin sie kommen (Boots-Platz, Modus), entscheidet addToBuild.
  const fromList = Boolean(drag) && drag.from === 'list';
  const target = (index) => (index === 'boots' ? 'boots' : Math.min(index, fromList ? build.length : build.length - 1));
  const accepts = (index) => Boolean(drag) && (fromList || (drag.from !== 'boots' && index !== 'boots'));
  const dropFromList = (id, index) => {
    const before = store.get().build.length;
    addToBuild(id);
    const after = store.get().build;
    if (index !== 'boots' && after.length === before + 1 && index < after.length - 1) {
      moveInBuild(after.length - 1, index);
    }
  };
  const dragProps = (index) => ({
    onDragOver: (e) => {
      if (accepts(index)) {
        e.preventDefault();
        e.stopPropagation();
        e.dataTransfer.dropEffect = fromList ? 'copy' : 'move';
        if (drag.over !== target(index)) {
          setDrag({ ...drag, over: target(index) });
        }
      }
    },
    onDrop: (e) => {
      if (accepts(index)) {
        e.preventDefault();
        e.stopPropagation();
        if (fromList) {
          dropFromList(drag.id, target(index));
        } else {
          moveInBuild(drag.from, target(index));
        }
        setDrag(null);
      }
    },
  });
  const slotClass = (index, filled) => ['build-slot', filled ? 'filled' : '', index === 'boots' ? 'boots' : '',
    filled && (index === 'boots' ? boots : build[index]) === selected ? 'selected' : '',
    drag && drag.from === index ? 'dragging' : '',
    drag && drag.over === index && drag.from !== index ? 'drop-target' : ''].join(' ');
  // Zwischen den Plätzen: keine Ablage möglich, Markierung entfernen.
  const clearOver = () => {
    if (drag && drag.over !== null) {
      setDrag({ ...drag, over: null });
    }
  };
  const filledSlot = (item, index, extraProps) => html`<button key=${index} type="button" class=${slotClass(index, true)} draggable="true"
    onClick=${() => select('items', item.id)}
    onDragStart=${(e) => {
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', String(item.id));
      setDrag({ from: index, over: null });
    }}
    onDragEnd=${() => setDrag(null)} ...${extraProps}>
    <${Img} src=${item.icon} size=${40} tip=${`${item.name} (${item.gold.total} Gold)${advancedTips ? ' – Klick zeigt Details, Ziehen ordnet um, auf die Liste ziehen entfernt' : ''}`} />
  </button>`;
  const bootsItem = adc && boots !== null ? data.itemById.get(boots) : null;
  return html`<section class="build-panel" onDragOver=${clearOver}>
    <header class="card-head">
      <h3>Build-Rechner</h3>
      <div class="card-aside">
        ${all.length ? html`<strong class="gold">${(totals ? totals.gold : 0).toLocaleString('de-DE')} Gold</strong>` : null}
        <${Button} icon="x" kind="ghost" disabled=${!championSet} onClick=${() => { store.set({ buildChampion: null }); setBuildMode(''); }}>Champion entfernen<//>
        <${Button} icon="trash" kind="ghost" disabled=${!all.length} onClick=${() => { store.set({ build: [], buildBoots: null }); setBuildMode(''); }}>Leeren<//>
        <${MenuButton} title="Rollen-Modus" tip=${advancedTips ? 'Rollen-Modus (Jungle, Mid, ADC, Support)' : undefined} badge=${Boolean(mode)}>
          <div class="menu-title">Rollen-Modus</div>
          <div class="build-modes switches">
            ${BUILD_MODES.map((m) => html`<${Switch} key=${m.value} checked=${mode === m.value}
              onChange=${(on) => setBuildMode(on ? m.value : '')} tip=${advancedTips ? MODE_TIPS[m.value] : undefined}>${m.label}<//>`)}
          </div>
        <//>
      </div>
    </header>
    <${BuildChampion} data=${data} />
    <div class="build-slots">
      ${slots.map((id, index) => {
        const item = id === undefined ? null : data.itemById.get(id);
        return item ? filledSlot(item, index, dragProps(index))
          : html`<button key=${index} type="button" class=${slotClass(index, false)} aria-label="Item hinzufügen"
              onClick=${() => setPicker({ query: '' })} ...${dragProps(index)}><${Icon} name="plus" size=${14} /></button>`;
      })}
      ${adc ? html`<span class="build-slot-sep" aria-hidden="true"></span>
        ${bootsItem ? filledSlot(bootsItem, 'boots', {})
          : html`<button key="boots" type="button" class=${slotClass('boots', false)} aria-label="Boots-Platz"
              onClick=${() => setPicker({ query: 'boots' })} ...${dragProps('boots')}>Boots</button>`}` : null}
    </div>
    ${picker ? html`<${ItemPicker} data=${data} query=${picker.query} onClose=${() => setPicker(null)} />` : null}
    ${all.length === 0 ? html`<p class="muted small">Leer – Items über „+“, per Ziehen aus der Liste oder im Detail über „Zum Build hinzufügen“ ergänzen.${adc ? ' Als ADC kommen Boots auf den eigenen 7. Platz.' : ''}</p>` : null}
    <div class="build-runes">
      ${runes.length ? html`<${RuneIcons} data=${data} ids=${runes} size=${26} />` : html`<span class="muted small">Keine Runenseite</span>`}
      <${Button} kind="ghost" onClick=${() => go('runes', 'page')}>${runes.length ? 'Runen bearbeiten' : 'Runen wählen'}<//>
    </div>
    ${all.length && totals ? html`<div class="stat-chips">${totals.stats.map((s) => html`<span class="stat-chip" key=${s.label}><strong>${s.value}</strong> ${s.label}</span>`)}</div>
      ${totals.unparsed.length ? html`<details class="unparsed"><summary>Nicht verrechnet (${totals.unparsed.length})</summary>${totals.unparsed.map((u, i) => html`<p class="muted small" key=${i}>• ${u}</p>`)}</details>` : null}` : null}
  </section>`;
}

/**
 * Item-Auswahl für den Build (wie die Champion-Auswahl im Draft): Suche nach Name oder Kategorie, Enter nimmt das
 * erste passende Item. Trinkets und Verbrauchsgegenstände fehlen, Items, die gerade nicht passen, sind ausgegraut.
 */
function ItemPicker({ data, query: initial, onClose }) {
  const [query, setQuery] = useState(initial || '');
  const state = useStore((s) => s);
  const input = useRef();
  useEffect(() => input.current.focus(), []);
  const items = useMemo(() => data.items
    .filter((i) => i.rift && !i.tags.includes('Trinket') && !i.tags.includes('Consumable'))
    .map((item) => ({ item, categories: categoriesOf(item).map((c) => c.toLowerCase().replace('-', '')) }))
    .sort((a, b) => a.item.name.localeCompare(b.item.name, 'de')), [data]);
  const matches = useMemo(() => {
    const q = normalize(query);
    return items.filter((r) => !q || r.item.name.toLowerCase().includes(q)
      || r.categories.some((c) => c.startsWith(q.replace('-', ''))));
  }, [query, items]);
  const choose = (item) => {
    addToBuild(item.id);
    onClose();
  };
  const onKeyDown = (e) => {
    if (e.key === 'Escape') {
      onClose();
    } else if (e.key === 'Enter') {
      const first = matches.find((r) => canAddToBuild(state, r.item));
      if (first) {
        choose(first.item);
      }
    }
  };
  return html`<div class="overlay" onMouseDown=${(e) => e.target === e.currentTarget && onClose()}>
    <div class="modal draft-picker" role="dialog" aria-modal="true" aria-label="Item hinzufügen" onKeyDown=${onKeyDown}>
      <header class="modal-head"><h2>Item hinzufügen</h2><${Button} icon="x" kind="icon" title="Schließen" onClick=${onClose} /></header>
      <label class="search">
        <${Icon} name="search" size=${16} />
        <input ref=${input} type="search" value=${query} spellcheck="false" placeholder="Item oder Kategorie suchen, Enter wählt das erste"
          onInput=${(e) => setQuery(e.currentTarget.value)} />
      </label>
      <div class="draft-picker-grid">
        ${matches.map(({ item }) => html`<button type="button" key=${item.id} disabled=${!canAddToBuild(state, item)}
          onClick=${() => choose(item)} data-tip=${`${item.name} (${item.gold.total} Gold)`}>
          <${Img} src=${item.icon} size=${40} /><span>${item.name}</span>
        </button>`)}
      </div>
    </div>
  </div>`;
}

/**
 * Gold je Stat-Einheit, abgeleitet aus den Komponenten (Rift-Items, die in etwas weiterbauen) wie im Wiki: erst Items
 * mit nur einem Stat (z. B. Long Sword → AD), dann aufsteigend nach Preis Items, bei denen genau ein Stat noch fehlt
 * (Preis minus bekannte Stats). Folgt so jedem Patch ohne eigenen Abruf. Stat-Schlüssel → { gold, source }.
 */
const goldTables = new WeakMap();
function statGoldTable(data) {
  if (goldTables.has(data)) {
    return goldTables.get(data);
  }
  const table = {};
  const components = data.items
    .filter((i) => i.rift && i.into.length && i.stats.length && i.gold.total > 0 && !i.tags.includes('Consumable')
      && !isSupportItem(i))
    .sort((a, b) => a.gold.total - b.gold.total || a.stats.length - b.stats.length);
  // 1. Basis-Items mit genau einem Stat (Long Sword, Ruby Crystal, Glowing Mote …), je Stat das billigste Item
  //    (Long Sword vor B. F. Sword, wie im Wiki); components ist nach Preis sortiert, das erste gewinnt.
  components.filter((i) => !i.from.length && i.stats.length === 1 && i.stats[0].amount > 0).forEach((item) => {
    const s = item.stats[0];
    const gold = item.gold.total / s.amount;
    if (!table[s.key]) {
      table[s.key] = { gold, source: item.name };
    }
  });
  // 2. Restliche Stats per Abzug, nur aus gebauten Items: Basis-Items mit mehreren Stats (Dark Seal) tragen
  //    Passiven im Preis und würden die Werte verzerren.
  const built = components.filter((i) => i.from.length);
  let changed = true;
  while (changed) {
    changed = false;
    for (const item of built) {
      const open = item.stats.filter((s) => !table[s.key]);
      if (open.length !== 1 || !(open[0].amount > 0)) {
        continue;
      }
      const known = item.stats.reduce((sum, s) => sum + (table[s.key] ? table[s.key].gold * s.amount : 0), 0);
      const rest = item.gold.total - known;
      if (rest > 0) {
        table[open[0].key] = { gold: rest / open[0].amount, source: item.name };
        changed = true;
        break; // nach jedem Fund wieder beim billigsten Item beginnen
      }
    }
  }
  goldTables.set(data, table);
  return table;
}

/**
 * Immer aktive Passiven als zusätzliche Stats, unter der Annahme, dass man nur dieses Item besitzt (wie das Wiki bei
 * Rabadon's: 130 AP × 30 % = +39 AP). Bezugsgröße sind die Stats des Items selbst. `from` = Stat des Items, aus dem
 * sich der Bonus ergibt (null = flacher Wert), `to` = Stat, der dazukommt. Passiven, die vom Champion abhängen
 * (Basis-AD, Lauftempo) oder keinen Goldpreis haben (Haste nur für einzelne Fähigkeiten), fehlen hier.
 */
const PASSIVE_STATS = {
  ABILITY_POWER_AMP: { from: 'ABILITY_POWER', to: 'ABILITY_POWER' },
  ITEM_HEALTH_AMP: { from: 'HEALTH', to: 'HEALTH' },
  AP_PER_BONUS_HEALTH: { from: 'HEALTH', to: 'ABILITY_POWER' },
  AD_PER_BONUS_HEALTH: { from: 'HEALTH', to: 'ATTACK_DAMAGE' },
  AP_PER_BONUS_MANA: { from: 'MANA', to: 'ABILITY_POWER' },
  AD_PER_MAX_MANA: { from: 'MANA', to: 'ATTACK_DAMAGE' }, // nur das Mana des Items, ohne Champion-Grundmana
  HEALTH_PER_BONUS_MANA: { from: 'MANA', to: 'HEALTH' },
  HSP_PER_BONUS_MANA: { from: 'MANA', to: 'HEAL_SHIELD_POWER' },
  AP_PER_100_BASE_MANA_REGEN: { from: 'MANA_REGEN', to: 'ABILITY_POWER', per: 100 },
  HSP_PER_100_BASE_MANA_REGEN: { from: 'MANA_REGEN', to: 'HEAL_SHIELD_POWER', per: 100 },
  ABILITY_HASTE: { from: null, to: 'ABILITY_HASTE' },
  HASTE_PER_BONUS_AD_MELEE: { from: 'ATTACK_DAMAGE', to: 'ABILITY_HASTE' }, // Nahkampf-Wert
};

/** Bonus-Stats der Passiven: [{ name, key, amount, gold }]; gold = null, wenn der Stat keinen Goldpreis hat. */
function passiveStatsOf(item, table) {
  const own = (key) => (item.stats.find((s) => s.key === key) || {}).amount || 0;
  return (item.passives || []).flatMap((p) => {
    const rule = PASSIVE_STATS[p.type];
    if (!rule) {
      return [];
    }
    const amount = rule.from === null ? p.value : rule.per ? own(rule.from) / rule.per * p.value : own(rule.from) * p.value / 100;
    if (!(amount > 0)) {
      return [];
    }
    const ref = table[rule.to];
    return [{ name: p.name, key: rule.to, amount, gold: ref ? ref.gold * amount : null }];
  });
}

/**
 * Goldwert eines Items: je Stat der Wert, dazu Summe und Effizienz (nur bewertbare Stats), außerdem dieselben Werte
 * inklusive der bewertbaren Passiven (`passiveTotal`, `passiveEfficiency`; null ohne solche Passiven).
 */
function goldValueOf(data, item) {
  const table = statGoldTable(data);
  const rows = item.stats.map((s) => ({ stat: s, ref: table[s.key], gold: table[s.key] ? table[s.key].gold * s.amount : null }));
  const total = rows.reduce((sum, r) => sum + (r.gold || 0), 0);
  const passives = passiveStatsOf(item, table);
  const passiveGold = passives.reduce((sum, p) => sum + (p.gold || 0), 0);
  const ratio = (value) => (item.gold.total > 0 && value > 0 ? value / item.gold.total : null);
  return {
    rows, total, efficiency: ratio(total), passives,
    passiveTotal: passiveGold > 0 ? total + passiveGold : null,
    passiveEfficiency: passiveGold > 0 ? ratio(total + passiveGold) : null,
  };
}

const goldText = (value) => Math.round(value).toLocaleString('de-DE');
const formatAmount = (value) => `+${value.toLocaleString('de-DE', { maximumFractionDigits: 1 })}`;
const percentText = (ratio, digits = 1) => `${(ratio * 100).toLocaleString('de-DE', { minimumFractionDigits: digits, maximumFractionDigits: digits })} %`;

/**
 * Baupfad als Baum wie im Spiel: das Item oben, seine Komponenten nebeneinander darunter, verbunden durch Linien.
 * Unter jedem Icon der Gesamtpreis; Name und Kombinationskosten im Tooltip. Komponenten öffnen sich per Klick.
 */
function RecipeNode({ item, data, root, depth = 0 }) {
  const children = depth < 4 ? item.from.map((id) => data.itemById.get(id)).filter((i) => i && (!item.rift || i.rift)) : [];
  const tip = `${item.name} – ${goldText(item.gold.total)} Gold${children.length ? ` (Kombination ${goldText(item.gold.base)})` : ''}`;
  const icon = html`<${Img} src=${item.icon} size=${34} />`;
  const node = html`<li>
    <div class=${`recipe-item ${root ? 'root' : ''}`} data-tip=${tip}>
      ${root ? icon : html`<button type="button" class="icon-link" aria-label=${item.name} onClick=${() => go('items', item.id)}>${icon}</button>`}
      <span class="gold">${goldText(item.gold.total)}</span>
    </div>
    ${children.length ? html`<ul>${children.map((c, i) => html`<${RecipeNode} key=${`${c.id}-${i}`} item=${c} data=${data} depth=${depth + 1} />`)}</ul>` : null}
  </li>`;
  return root ? html`<div class="recipe-tree"><ul>${node}</ul></div>` : node;
}

/** Block „Baut aus“: Baum, Goldwert der Stats und Goldeffizienz. */
function RecipeCard({ item, data, from }) {
  // Herleitung des Goldwerts nur bei „Show Advanced Tooltips“ (Nutzerwunsch).
  const advancedTips = useStore((s) => Boolean(s.prefs.advancedTips));
  const value = useMemo(() => goldValueOf(data, item), [data, item]);
  const rated = value.rows.filter((r) => r.gold !== null);
  const unrated = value.rows.filter((r) => r.gold === null);
  if (!from.length && !rated.length) {
    return null;
  }
  return html`<${Card} title=${from.length ? 'Baut aus' : 'Goldwert'}>
    ${from.length ? html`<${RecipeNode} item=${item} data=${data} root=${true} />` : null}
    ${rated.length ? html`<div class=${from.length ? 'cost-analysis' : 'cost-analysis no-tree'}>
      ${from.length ? html`<h4>Goldwert</h4>` : null}
      <ul>
        ${rated.map((r) => html`<li key=${r.stat.key} data-tip=${advancedTips ? `${r.ref.gold.toLocaleString('de-DE', { maximumFractionDigits: 2 })} Gold je Einheit, abgeleitet aus ${r.ref.source}` : undefined}>
          ${r.stat.value} ${r.stat.label} = <span class="gold">${goldText(r.gold)}</span></li>`)}
        ${unrated.map((r) => html`<li key=${r.stat.key} class="muted">${r.stat.value} ${r.stat.label}: ohne Vergleichs-Komponente nicht bewertet</li>`)}
        <li class="cost-total"><strong>Goldwert gesamt</strong> = <span class="gold">${goldText(value.total)}</span></li>
      </ul>
      ${value.passives.length ? html`<h4>Goldwert der Passiven</h4>
        <ul>
          ${value.passives.map((p, i) => html`<li key=${i} class=${p.gold === null ? 'muted' : ''}>
            ${formatAmount(p.amount)} ${statName(data.statNames, { key: p.key, label: p.key }).label} (${p.name})
            ${p.gold === null ? ': ohne Vergleichs-Komponente nicht bewertet' : html` = <span class="gold">${goldText(p.gold)}</span>`}</li>`)}
          ${value.passiveTotal !== null ? html`<li class="cost-total"><strong>Goldwert inkl. Passiven</strong> = <span class="gold">${goldText(value.passiveTotal)}</span></li>` : null}
        </ul>` : null}
      <h4>Goldeffizienz</h4>
      <p><${Img} src=${item.icon} size=${20} /> Die Grundwerte von <strong>${item.name}</strong> sind
        <strong class=${value.efficiency >= 1 ? 'good' : 'bad'}>${percentText(value.efficiency, 2)}</strong>
        goldeffizient${unrated.length ? ' (ohne nicht bewertete Stats und Passiven)' : ' (ohne Passiven)'}.</p>
      ${value.passiveEfficiency !== null ? html`<p>Mit Passiven (nur dieses Item im Besitz):
        <strong class=${value.passiveEfficiency >= 1 ? 'good' : 'bad'}>${percentText(value.passiveEfficiency, 2)}.</strong></p>`
        : passiveText(item) ? html`<p class="muted">Passiven mit Bedingungen, Stapeln oder Champion-Bezug sind nicht bewertbar.</p>` : null}
    </div>` : null}
  <//>`;
}

function ItemLinks({ ids, data }) {
  return html`<div class="chip-links">${ids.map((id) => {
    const item = data.itemById.get(id);
    return item ? html`<button type="button" class="chip-link" key=${id} data-tip=${`${item.gold.total} Gold`} onClick=${() => go('items', id)}><${Img} src=${item.icon} size=${26} /><span>${item.name}</span></button>` : null;
  })}</div>`;
}

/**
 * Beschreibung ohne die Stat-Zeilen am Anfang (z. B. „80 Attack Damage“), die schon in der Tabelle stehen; übrig
 * bleiben Passiven und Aktive. Leer, wenn nichts übrig ist.
 */
function passiveText(item) {
  const lines = item.description.split('\n');
  let start = 0;
  while (item.stats.length && start < lines.length && /^\s*[+-]?\d/.test(lines[start])) {
    start++;
  }
  return lines.slice(start).join('\n').trim();
}

// Data Dragon setzt für Abklingzeiten und berechnete Werte leere Platzhalter ein, z. B. „Lifeline (0s)“,
// „Item Health (0)“ oder „(5(0s))“. Sie werden entfernt; die Abklingzeit kommt aus dem Wiki.
const PLACEHOLDER = /\s*\([\d.]*\(?0s?\)?\)/g;

/**
 * Zerlegt die Beschreibung in Effekt-Blöcke: erste Zeile = Name (ohne Platzhalter; „ACTIVE“ mit dem Namen in der
 * nächsten Zeile wird zu „Aktiv: Name“), `tail` = Rest der Namenszeile hinter dem Platzhalter (z. B. „per target“).
 * Data Dragon klebt manche Blöcke an den vorigen Satz („…champions.Pledge (0s)“), die werden zuerst getrennt.
 */
function effectBlocks(text) {
  return text.replace(/([.!?])([A-Z][A-Za-z' ]{1,40} \([\d.]*\(?0s?\)?\))/g, '$1\n\n$2')
    .split(/\n\s*\n/).map((b) => b.trim()).filter(Boolean).map((block) => {
      const [first, ...rest] = block.split('\n');
      const m = first.match(/^(.*?)\s*\([\d.]*\(?0s?\)?\)\s*(.*)$/);
      let name = (m ? m[1] : first).trim();
      let label = '';
      if (/^active$/i.test(name) && rest.length) {
        label = 'Aktiv: ';
        name = rest.shift().trim();
      }
      return { name, label, tail: m ? m[2].trim() : '', body: rest.join('\n').replace(PLACEHOLDER, '') };
    });
}

/** Abklingzeit laut Wiki als Text, z. B. „1,5 s“, „198–99 s“, „30 s je Ziel“. */
function cooldownText(cooldown, tail) {
  const perTarget = /per target/i.test(`${cooldown} ${tail}`);
  const value = cooldown.replace(/\s*per target/i, '').replace(/\s+to\s+/, '–').replace(/\./g, ',');
  return `${value} s Abklingzeit${perTarget ? ' je Ziel' : ''}`;
}

/**
 * Passiven und Aktive aus der Data-Dragon-Beschreibung, je Block mit der Abklingzeit laut Wiki (erst Passiven, dann
 * Aktive; `/api/items/<id>/wiki`). Zuordnung über den Namen (auch als Anfang, z. B. „Colossal Consumption per
 * target“), sonst über die Reihenfolge, wenn die Anzahl übereinstimmt (andere Sprache). Ohne Wiki keine Zeiten.
 */
function PassiveBlocks({ item }) {
  const text = passiveText(item);
  const wiki = useFetch(text ? `/api/items/${item.id}/wiki` : null).data;
  if (!text) {
    return null;
  }
  const blocks = effectBlocks(text);
  const effects = wiki ? [...(wiki.passives || []), ...(wiki.actives || [])].filter((p) => p.name) : [];
  const cooldownOf = (block, index) => {
    const name = block.name.toLowerCase();
    const byName = effects.find((p) => p.name.toLowerCase() === name)
      || effects.find((p) => name.startsWith(p.name.toLowerCase()));
    const match = byName || (effects.length === blocks.length ? effects[index] : null);
    return match && match.cooldown ? match.cooldown : '';
  };
  return html`<div class="prose item-passive">${blocks.map((block, i) => {
    const cd = cooldownOf(block, i);
    return html`<p key=${i}><strong>${block.label}${block.name}</strong>${cd ? html` <span class="passive-cd">(${cooldownText(cd, block.tail)})</span>` : null}
      ${block.body ? html`<br />${block.body}` : null}</p>`;
  })}</div>`;
}

function ItemDetail({ item, data }) {
  const canAdd = useStore((s) => canAddToBuild(s, item));
  const toBootsSlot = useStore((s) => s.buildMode === 'adc' && isBoots(item));
  const petLocked = useStore((s) => s.buildMode !== 'jungle' && isJunglePet(item));
  const notBuildable = item.tags.includes('Trinket') || item.tags.includes('Consumable');
  const inBuild = useStore((s) => item.completed && s.build.concat(s.buildBoots ?? []).includes(item.id));
  const supLocked = useStore((s) => isSupportItem(item) && supportLocked(s));
  const addLabel = notBuildable ? 'Nicht für den Build' : inBuild ? 'Schon im Build' : supLocked ? 'Nur als Support' : petLocked ? 'Nur mit „Jungle“' : toBootsSlot ? 'Auf den Boots-Platz' : 'Zum Build hinzufügen';
  // Bei SR-Items keine Modus-Varianten verlinken (Data Dragon listet sie mit im Baupfad).
  const linked = (ids) => (item.rift ? ids.filter((id) => (data.itemById.get(id) || {}).rift) : ids);
  const from = linked(item.from);
  const into = linked(item.into);
  return html`<div class="detail">
    <header class="plain-hero">
      <${Img} src=${item.icon} size=${72} cls="hero-icon" />
      <div class="hero-text">
        <h1>${item.name}</h1>
        <p class="hero-sub gold">${item.gold.total} Gold <span class="muted">(Kombination ${item.gold.base}, Verkauf ${item.gold.sell})</span></p>
        <div class="tags">${categoriesOf(item).map((t) => html`<${Tag} key=${t}>${t}<//>`)}<${Tag} kind="soft">ID ${item.id}<//></div>
      </div>
      <${Button} icon="plus" kind="primary" disabled=${!canAdd} onClick=${() => addToBuild(item.id)}>${addLabel}<//>
    </header>
    ${item.plaintext ? html`<p class="lore">${item.plaintext}</p>` : null}
    <${Card} title=${item.stats.length ? 'Werte und Beschreibung' : 'Beschreibung'}>
      ${item.stats.length ? html`<${DataGrid} cls="compact" rows=${item.stats.map((s) => [s.label, html`<strong>${s.value}</strong>`])} />` : null}
      <${PassiveBlocks} item=${item} />
    <//>
    <${RecipeCard} item=${item} data=${data} from=${from} />
    ${into.length ? html`<${Card} title="Baut zu"><${ItemLinks} ids=${into} data=${data} /><//>` : null}
  </div>`;
}
