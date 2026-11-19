// Patch-Notes aus dem League-Wiki (nur Summoner's Rift): Unterseite des aktuellen Patches (Klick auf den Patch in der
// Kopfleiste) und die Patch-Änderungen eines Champions (Block in der Übersicht, aufklappbar zur ganzen Historie).
import { html, useEffect, useMemo, useState } from '/vendor/htm-preact.js';
import { useStore, useFetch, useViewState, go, normalize, gamePatch } from './core.js';
import { Img, Card, Button, SearchInput, Segmented, Empty, ErrorNote, Skeleton, Tag } from './ui.js';

const HISTORY_PAGE = 10; // Patches je Schritt in der Historie eines Champions

/** Namensschlüssel ohne Satzzeichen und Groß-/Kleinschreibung (wie im Backend für Fähigkeiten). */
const nameKey = (name) => (name || '').toLowerCase().replace(/[^a-z0-9]/g, '');

/** Champions, Items, Runen und Beschwörerzauber nach Namen, für Icons und Links in den Patch-Notes. */
function useLookups() {
  const data = useStore((s) => s.data);
  return useMemo(() => {
    const runes = new Map();
    data.runeTrees.forEach((t) => t.slots.flat().forEach((r) => runes.set(nameKey(r.name), r)));
    return {
      champion: new Map(data.champions.map((c) => [nameKey(c.name), c])),
      item: new Map(data.items.filter((i) => i.rift).map((i) => [nameKey(i.name), i])),
      rune: runes,
      spell: new Map((data.spells || []).map((s) => [nameKey(s.name), s])),
    };
  }, [data]);
}

/** Ziel eines Champions, Items oder einer Rune (Klick öffnet die passende Ansicht), sonst {@code null}. */
function refTarget(kind, entity) {
  if (!entity) {
    return null;
  }
  if (kind === 'champion') {
    return () => go('champions', entity.id);
  }
  if (kind === 'item') {
    return () => go('items', entity.id);
  }
  return kind === 'rune' ? () => go('runes', entity.id) : null;
}

/** Verweis mit Icon wie im Wiki; ohne Ziel nur Icon und Name. */
function RefLink({ icon, text, onClick, size = 20 }) {
  const content = html`${icon ? html`<${Img} src=${icon} size=${size} />` : null}<span>${text}</span>`;
  return onClick
    ? html`<button type="button" class="pn-ref" onClick=${onClick}>${content}</button>`
    : html`<span class="pn-ref static">${content}</span>`;
}

/** Ein Teil einer Zeile: Fähigkeit (Icon, öffnet Abilities), Champion/Item/Rune/Zauber, Wertfarbe, Hervorhebung. */
function PatchPart({ part, lookups }) {
  const { text, style, ref } = part;
  if (!style) {
    return text;
  }
  if (style === 'label') {
    return html`<span class="pn-label">${text}</span>`;
  }
  if (style === 'ability') {
    const champion = lookups.champion.get(nameKey((ref || '').split('|')[0]));
    return html`<${RefLink} icon=${part.icon} text=${text} onClick=${champion ? () => go('abilities', champion.id) : null} />`;
  }
  if (lookups[style]) {
    const entity = lookups[style].get(nameKey(ref || text));
    return html`<${RefLink} icon=${entity && entity.icon} text=${text} onClick=${refTarget(style, entity)} />`;
  }
  return html`<span class=${`pn-v pn-${style}`}>${text}</span>`;
}

/** Änderungszeilen wie im Wiki: eingerückte Liste, Buff ▲ / Nerf ▼ statt Punkt. */
export function PatchLines({ lines }) {
  const lookups = useLookups();
  return html`<ul class="pn-lines">
    ${lines.map((l, i) => html`<li key=${i} class=${`pn-d${Math.min(l.depth, 4)}${l.trend ? ` pn-${l.trend}` : ''}`}>
      ${l.parts ? l.parts.map((p, j) => html`<${PatchPart} key=${j} part=${p} lookups=${lookups} />`) : l.text}</li>`)}
  </ul>`;
}

const LONG_GROUP = 8; // ab so vielen Zeilen ist ein Eintrag anfangs eingeklappt
const GROUP_PREVIEW = 5; // eingeklappt sichtbare Zeilen

/**
 * Eintrag einer Gruppe (z. B. ein Champion), über die volle Breite. Lange Einträge sind anfangs eingeklappt und zeigen
 * die ersten Zeilen; der Zustand bleibt je Eintrag in der Sitzung. Bei einer Suche ist alles offen.
 */
function PatchGroup({ group, head, id, searching }) {
  const [open, setOpen] = useViewState('patch-groups', {});
  const long = group.lines.length > LONG_GROUP;
  const expanded = searching || !long || Boolean(open[id]);
  const lines = expanded ? group.lines : group.lines.slice(0, GROUP_PREVIEW);
  return html`<div class=${`pn-group${long && !expanded ? ' collapsed' : ''}`}>
    ${head}
    <${PatchLines} lines=${lines} />
    ${long && !searching ? html`<button type="button" class="pn-more" onClick=${() => setOpen({ [id]: !expanded })}>
      ${expanded ? '▴ Einklappen' : `▾ Alle ${group.lines.length} Zeilen anzeigen`}</button>` : null}
  </div>`;
}

/** Gesamturteil über Zeilen: nur Buffs, nur Nerfs, beides oder {@code null}. */
function trendOf(lines) {
  const buff = lines.some((l) => l.trend === 'buff');
  const nerf = lines.some((l) => l.trend === 'nerf');
  return buff && nerf ? 'mixed' : buff ? 'buff' : nerf ? 'nerf' : null;
}

const TREND_LABEL = { buff: '▲ Buff', nerf: '▼ Nerf', mixed: '▲▼ Angepasst' };

/** Kennzeichen Buff/Nerf/Angepasst, aus dem Text der Zeilen geschätzt. */
export function TrendBadge({ lines }) {
  const trend = trendOf(lines);
  return trend ? html`<span class=${`pn-trend ${trend}`}>${TREND_LABEL[trend]}</span>` : null;
}

/** Hinweis bei abgeschaltetem Wiki, Fehler oder Laden; {@code null}, wenn Daten vorliegen. */
function wikiPlaceholder(result, lines = 4) {
  if (result.loading && !result.data) {
    return html`<${Skeleton} lines=${lines} />`;
  }
  if (result.error) {
    return html`<${ErrorNote} prefix="Patch-Notes nicht verfügbar" error=${result.error} />`;
  }
  if (result.data && result.data.disabled) {
    return html`<p class="muted">Das Wiki ist in den Einstellungen abgeschaltet.</p>`;
  }
  return result.data ? null : html`<${Skeleton} lines=${lines} />`;
}

/**
 * Standard je Block: Hotfixes und Abschnitte mit Champions, Items, Runen oder Zaubern offen; allgemeine Abschnitte
 * (z. B. „Game“, oft lang) und die Bugfixes zugeklappt.
 */
function sectionOpenByDefault(section) {
  return section.kind === 'hotfix' || (section.kind !== 'bugfix' && section.groups.some((g) => g.kind));
}

const staleNote = (data) => (data && data.stale ? html`<span class="muted small">offline, älterer Stand</span>` : null);

/** Unterseite: Summoner's-Rift-Änderungen des aktuellen Patches. */
export function PatchNotesView() {
  const data = useStore((s) => s.data);
  // Gewählter Patch ('' = aktueller); ältere Patches werden erst bei Auswahl geladen.
  const [page, setPage] = useViewState('patch-page', { patch: '' });
  const notes = useFetch(page.patch ? `/api/patch-notes?patch=${encodeURIComponent(page.patch)}` : '/api/patch-notes', { keep: true });
  // Die Auswahl merkt sich die Liste der letzten Antwort, damit sie beim Laden eines älteren Patches stehen bleibt.
  const [patches, setPatches] = useState(null);
  const [query, setQuery] = useState('');
  const n = notes.data;
  useEffect(() => {
    if (n && n.patches) {
      setPatches(n.patches);
    }
  }, [n]);
  const shownPatch = page.patch || (n && n.current) || '';
  // Mit „keep“ steht beim Wechsel kurz noch der vorige Patch da: dann den Ladezustand zeigen statt falscher Inhalte.
  const stale = n && n.patch && shownPatch && n.patch !== shownPatch;

  const lookups = useLookups();

  // Auf-/zugeklappte Blöcke je Titel (bleibt in der Sitzung); ohne Eintrag gilt sectionOpenByDefault.
  const [view, setView] = useViewState('patch-sections', {});
  const placeholder = stale ? html`<${Skeleton} lines=${8} />` : wikiPlaceholder(notes, 8);
  const q = normalize(query);
  const sections = n && n.sections ? n.sections
    .map((s) => ({ ...s, groups: s.groups.filter((g) => !q || (g.heading || '').toLowerCase().includes(q)
      || g.lines.some((l) => l.text.toLowerCase().includes(q))) }))
    .filter((s) => s.groups.length) : [];

  // Überschrift einer Gruppe: Champion/Item/Rune mit Icon und Link (Champion → Champion-Tab), dazu Buff/Nerf. Gesucht
  // wird über den Namen aus der Vorlage (ref), damit auch „Mel - Gameplay Update“ Icon und Link bekommt.
  const heading = (g) => {
    const entity = g.kind && lookups[g.kind] ? lookups[g.kind].get(nameKey(g.ref || g.heading)) : null;
    const onClick = refTarget(g.kind, entity);
    const title = entity
      ? html`<button type="button" class="pn-target" disabled=${!onClick} onClick=${onClick}>
          <${Img} src=${entity.icon} size=${28} /><strong>${g.heading}</strong></button>`
      : g.heading ? html`<strong class="pn-heading">${g.heading}</strong>` : null;
    return html`<div class="pn-group-head">${title}<${TrendBadge} lines=${g.lines} /></div>`;
  };

  return html`<div class="page patch-page">
    <header class="pn-head">
      <div>
        <h1>Patch ${shownPatch ? shownPatch.replace(/^V/, '') : gamePatch(data.patch)}</h1>
        <p class="muted">Änderungen für Summoner's Rift laut League-Wiki${shownPatch && n && shownPatch === n.current ? ` · Data Dragon ${n.version}` : ''} ${staleNote(n)}</p>
        <p class="muted small pn-legend"><span class="up">▲ Buff</span><span class="down">▼ Nerf</span>
          <span>aus dem Text geschätzt („increased“/„reduced“, bei Abklingzeit und Kosten umgekehrt)</span></p>
      </div>
      <${SearchInput} value=${query} onInput=${setQuery} placeholder="Champion, Item oder Text …" />
    </header>
    ${patches && patches.length > 1 ? html`<div class="pn-patches">
      <span class="muted small">Patch:</span>
      <${Segmented} label="Patch" value=${shownPatch} onChange=${(value) => setPage({ patch: value === patches[0] ? '' : value })}
        options=${patches.map((p, i) => ({ value: p, label: `${p.replace(/^V/, '')}${i === 0 ? ' (aktuell)' : ''}` }))} />
    </div>` : null}
    ${placeholder || (n.sections.length === 0
      ? html`<${Empty} title="Noch keine Patch-Notes">Das Wiki hat für Patch ${n.patch.replace(/^V/, '')} noch keine Seite oder keine Änderungen für Summoner's Rift.<//>`
      : sections.length === 0 ? html`<${Empty} title="Nichts gefunden">Keine Änderung passt zur Suche.<//>`
        : sections.map((s) => {
          // Bei einer Suche sind alle Treffer-Blöcke offen; Umschalten merkt sich der Block nur ohne Suche.
          const open = q ? true : view[s.title] ?? sectionOpenByDefault(s);
          return html`<details key=${s.title} class=${`card pn-section pn-${s.kind}`} open=${open}
            onToggle=${(e) => { if (!q && e.currentTarget.open !== open) { setView({ [s.title]: e.currentTarget.open }); } }}>
            <summary class="card-head"><h3>${s.title}</h3>
              <span class="muted">${s.kind === 'hotfix' ? 'Hotfix · ' : ''}${s.groups.length}</span></summary>
            <div class="pn-groups">${s.groups.map((g, i) => html`<${PatchGroup} key=${i} group=${g} head=${heading(g)}
              id=${`${s.title}|${g.heading || i}`} searching=${Boolean(q)} />`)}</div>
          </details>`;
        }))}
  </div>`;
}

/** „Patch 25.24“ bzw. mit Hotfix „Patch 25.24 - December 17th Hotfix“, dazu „aktuell“ und Buff/Nerf. */
function patchLabel(entry, current) {
  const name = `${entry.patch.replace(/^V/, 'Patch ')}${entry.hotfix ? ` - ${entry.hotfix}` : ''}`;
  return html`${name}${entry.patch === current ? html` <${Tag} kind="soft">aktuell<//>` : null}
    <${TrendBadge} lines=${entry.lines} />`;
}

/**
 * Übersicht eines Champions, direkt unter den Fähigkeiten: Änderungen laut Wiki (nur Summoner's Rift, ohne Skins).
 * Standard ist nur die letzte Änderung; aufgeklappt folgen alle Patches, je {@link HISTORY_PAGE} auf einmal. Das
 * Backend lädt die Historie einmal je Patch und Champion.
 */
export function ChampionPatchChanges({ champion }) {
  const history = useFetch(`/api/champions/${champion.id}/patch-history`, { keep: true });
  const [expanded, setExpanded] = useState(false);
  const [shown, setShown] = useState(HISTORY_PAGE);
  const data = history.data;
  const entries = data && data.entries ? data.entries : [];
  const aside = html`<span class="muted small">League-Wiki ${staleNote(data)}</span>`;

  let body = wikiPlaceholder(history, 3);
  if (!body && !entries.length) {
    body = html`<p class="muted">Das Wiki führt für ${champion.name} keine Änderungen.</p>`;
  }
  if (!body) {
    const visible = expanded ? entries.slice(0, shown) : entries.slice(0, 1);
    body = html`
      <div class="pn-history">${visible.map((e, i) => html`<div class="pn-entry" key=${`${e.patch}#${e.hotfix || ''}#${i}`}>
        <h4>${patchLabel(e, data.current)}</h4><${PatchLines} lines=${e.lines} /></div>`)}</div>
      <div class="pn-actions">
        ${entries.length > 1 ? html`<${Button} kind="ghost" onClick=${() => { setExpanded(!expanded); setShown(HISTORY_PAGE); }}>
          ${expanded ? 'Nur letzte Änderung' : `Alle Änderungen (${entries.length} Patches)`}<//>` : null}
        ${expanded && entries.length > shown ? html`<${Button} kind="ghost" onClick=${() => setShown(shown + HISTORY_PAGE)}>
          Ältere Patches (${entries.length - shown} weitere)<//>` : null}
      </div>`;
  }
  return html`<${Card} title="Patch-Änderungen" aside=${aside} cls="pn-champion">${body}<//>`;
}