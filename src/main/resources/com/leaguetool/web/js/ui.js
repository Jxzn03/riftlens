// Wiederverwendbare Bausteine: Icons, Bilder, Karten, Regler, virtualisierte Tabelle, Geteilte Ansicht.
import { html, useState, useEffect, useLayoutEffect, useRef } from '/vendor/htm-preact.js';
import { store, useStore, useWinRateClass, go } from './core.js';

// ------------------------------------------------------------------ Icons

const ICONS = {
  swords: 'M14.5 17.5 3 6V3h3l11.5 11.5M13 19l6-6M16 16l4 4M19 21l2-2M9.5 6.5 14 2h3v3l-4.5 4.5M5 14l-2 2 2 2M8 17l-3 3M3 21l1-1',
  box: 'M21 8 12 3 3 8v8l9 5 9-5zM3.3 8 12 13l8.7-5M12 22v-9',
  gem: 'M6 3h12l4 6-10 12L2 9zM2 9h20M12 21 8 9l3-6M12 21l4-12-3-6',
  wand: 'M15 4V2M15 16v-2M8 9h2M20 9h2M17.8 11.8 19 13M17.8 6.2 19 5M12.2 6.2 11 5M3 21l9-9M12 12l1.5-1.5',
  settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-2.9-1.2l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0-1.2-2.9H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.2-2.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3h.1a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9v.1a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z',
  download: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3',
  check: 'M22 11.1V12a10 10 0 1 1-5.9-9.1M22 4 12 14l-3-3',
  refresh: 'M21 12a9 9 0 1 1-2.6-6.4L21 8M21 3v5h-5',
  sun: 'M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  moon: 'M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z',
  search: 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.3-4.3',
  x: 'M18 6 6 18M6 6l12 12',
  plus: 'M12 5v14M5 12h14',
  trash: 'M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6',
  arrow: 'M5 12h14M12 5l7 7-7 7',
  up: 'M12 19V5M5 12l7-7 7 7',
  down: 'M12 5v14M19 12l-7 7-7-7',
  alert: 'M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z',
  ok: 'M20 6 9 17l-5-5',
  user: 'M20 21a8 8 0 0 0-16 0M12 13a5 5 0 1 0 0-10 5 5 0 0 0 0 10z',
  target: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 18a6 6 0 1 0 0-12 6 6 0 0 0 0 12zM12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4z',
};

export function Icon({ name, size = 18 }) {
  return html`<svg class="icon" width=${size} height=${size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
    stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d=${ICONS[name]} /></svg>`;
}

// ------------------------------------------------------------------ Einfache Bausteine

export function Img({ src, size, alt = '', cls = '', tip }) {
  // Kein loading="lazy": Die Tabellen rendern ohnehin nur sichtbare Zeilen, Lazy Loading verzögert dort nur.
  return html`<img class=${`img ${cls}`} src=${src} alt=${alt} width=${size} height=${size}
    decoding="async" draggable="false" data-tip=${tip}
    onError=${(e) => e.currentTarget.classList.add('img-broken')} />`;
}

export function Spinner({ small = false }) {
  return html`<span class=${small ? 'spinner small' : 'spinner'} role="status" aria-label="Lädt"></span>`;
}

export function Loading({ text = 'Lade …' }) {
  return html`<div class="loading"><${Spinner} small /> <span>${text}</span></div>`;
}

export function Skeleton({ lines = 3 }) {
  return html`<div class="skeleton">${Array.from({ length: lines }, (_, i) => html`<span key=${i} style=${{ width: `${92 - i * 17}%` }}></span>`)}</div>`;
}

export function Empty({ title, children }) {
  return html`<div class="empty"><strong>${title}</strong>${children ? html`<p>${children}</p>` : null}</div>`;
}

export function ErrorNote({ prefix, error }) {
  return html`<p class="note bad"><${Icon} name="alert" size=${15} /> ${prefix}: ${error.message || String(error)}</p>`;
}

export function Card({ title, aside, children, cls = '' }) {
  return html`<section class=${`card ${cls}`}>
    ${title ? html`<header class="card-head"><h3>${title}</h3>${aside ? html`<div class="card-aside">${aside}</div>` : null}</header>` : null}
    ${children}
  </section>`;
}

export function Button({ icon, children, onClick, kind = '', disabled, tip, title }) {
  return html`<button type="button" class=${`btn ${kind}`} onClick=${onClick} disabled=${disabled} data-tip=${tip} aria-label=${title}>
    ${icon ? html`<${Icon} name=${icon} size=${16} />` : null}${children ? html`<span>${children}</span>` : null}
  </button>`;
}

export function Select({ value, onChange, options, label, cls = '' }) {
  return html`<select class=${`select ${cls}`} value=${value} aria-label=${label} onChange=${(e) => onChange(e.currentTarget.value)}>
    ${options.map((o) => html`<option key=${o.value} value=${o.value}>${o.label}</option>`)}
  </select>`;
}

/** Auswahl aus wenigen Optionen als zusammenhängende Schaltfläche. */
export function Segmented({ value, options, onChange, label }) {
  return html`<div class="segmented" role="radiogroup" aria-label=${label}>
    ${options.map((o) => html`<button type="button" key=${o.value} role="radio" aria-checked=${value === o.value}
      class=${value === o.value ? 'active' : ''} data-tip=${o.tip} onClick=${() => onChange(o.value)}>${o.label}</button>`)}
  </div>`;
}

export function SearchInput({ value, onInput, placeholder, autoFocus }) {
  const ref = useRef();
  useEffect(() => {
    if (autoFocus && ref.current) {
      ref.current.focus();
    }
  }, []);
  return html`<label class="search">
    <${Icon} name="search" size=${16} />
    <input ref=${ref} type="search" value=${value} placeholder=${placeholder} spellcheck="false"
      onInput=${(e) => onInput(e.currentTarget.value)} />
    ${value ? html`<button type="button" class="search-clear" aria-label="Suche löschen" onClick=${() => onInput('')}><${Icon} name="x" size=${14} /></button>` : null}
  </label>`;
}

export function Switch({ checked, onChange, children, tip }) {
  return html`<label class="switch" data-tip=${tip}>
    <input type="checkbox" checked=${checked} onChange=${(e) => onChange(e.currentTarget.checked)} />
    <span class="switch-track"></span><span>${children}</span>
  </label>`;
}

export function Slider({ value, min, max, step = 1, onInput, label, ticks }) {
  const fill = ((value - min) / (max - min)) * 100;
  return html`<div class="slider-wrap">
    <input class="slider" type="range" min=${min} max=${max} step=${step} value=${value} aria-label=${label}
      style=${{ '--fill': `${fill}%` }} onInput=${(e) => onInput(Number(e.currentTarget.value))} />
    ${ticks ? html`<div class="slider-ticks">${ticks.map((t) => html`<span key=${t}>${t}</span>`)}</div>` : null}
  </div>`;
}

export function Tag({ children, kind = '' }) {
  return html`<span class=${`tag ${kind}`}>${children}</span>`;
}

export function Meter({ label, value, max = 10 }) {
  return html`<div class="meter">
    <div class="meter-label"><span>${label}</span><strong>${value}</strong></div>
    <div class="meter-track"><span style=${{ width: `${(value / max) * 100}%` }}></span></div>
  </div>`;
}

/** Zeile-Spalte-Tabelle für Kennwerte. */
export function DataGrid({ head, rows, cls = '' }) {
  return html`<div class="grid-scroll"><table class=${`data ${cls}`}>
    ${head ? html`<thead><tr>${head.map((h, i) => html`<th key=${i}>${h}</th>`)}</tr></thead>` : null}
    <tbody>${rows.map((row, i) => html`<tr key=${i}>${row.map((cell, j) => html`<td key=${j}>${cell}</td>`)}</tr>`)}</tbody>
  </table></div>`;
}

/** Winrate als Zahl mit Farbe (über/unter 50 %). */
export function RateCell({ value, neutral = false, decimals = 1 }) {
  const winRateClass = useWinRateClass();
  if (value === null || value === undefined) {
    return html`<span class="muted">–</span>`;
  }
  return html`<span class=${neutral ? '' : winRateClass(value)}>${(value * 100).toFixed(decimals)}</span>`;
}

// ------------------------------------------------------------------ Tooltips

/** Ein einziges Tooltip-Element für alle {@code data-tip}-Attribute. */
export function installTooltips() {
  const tip = document.getElementById('tip');
  let current = null;
  const show = (target) => {
    current = target;
    const rich = target.querySelector(':scope > .tip-content');
    tip.classList.toggle('tip-rich', Boolean(rich));
    tip.textContent = rich ? '' : target.dataset.tip;
    if (rich) {
      const content = rich.cloneNode(true);
      content.hidden = false;
      tip.append(content);
    }
    tip.hidden = false;
    const rect = target.getBoundingClientRect();
    const box = tip.getBoundingClientRect();
    const left = Math.min(Math.max(8, rect.left + rect.width / 2 - box.width / 2), innerWidth - box.width - 8);
    const above = rect.top - box.height - 8;
    tip.style.left = `${left}px`;
    tip.style.top = `${above > 8 ? above : rect.bottom + 8}px`;
  };
  const hide = () => {
    current = null;
    tip.hidden = true;
  };
  document.addEventListener('mouseover', (e) => {
    const target = e.target.closest ? e.target.closest('[data-tip]') : null;
    if (target && target.dataset.tip) {
      if (target !== current) {
        show(target);
      }
    } else if (current) {
      hide();
    }
  });
  document.addEventListener('scroll', hide, true);
  document.addEventListener('mousedown', hide);
}

/** „?“, das beim Hovern beliebigen Inhalt (z. B. eine Tabelle) als Tooltip zeigt. */
export function HelpTip({ label, children }) {
  return html`<span class="help-tip" data-tip=${label} aria-label=${label}>?<div class="tip-content" hidden>${children}</div></span>`;
}

// ------------------------------------------------------------------ Virtualisierte Tabelle

const OVERSCAN = 6;

/**
 * Tabelle, die nur die sichtbaren Zeilen rendert. Scrollen und Tippen bleiben dadurch unabhängig von der
 * Datenmenge flüssig; Bilder werden nur für sichtbare Zeilen geladen.
 */
export function VirtualTable({ rows, columns, rowKey, selected, onSelect, onDeselect, sort, onSort, rowHeight = 48, empty }) {
  const scroller = useRef();
  const frame = useRef(0);
  const [viewport, setViewport] = useState({ top: 0, height: 600 });
  const template = columns.map((c) => c.width).join(' ');
  const minWidth = columns.reduce((sum, c) => sum + (parseInt(c.width, 10) || 0), 0);
  const headerHeight = 38;

  useLayoutEffect(() => {
    const element = scroller.current;
    const observer = new ResizeObserver(() => setViewport((v) => ({ ...v, height: element.clientHeight })));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => () => cancelAnimationFrame(frame.current), []);

  const onScroll = () => {
    if (!frame.current) {
      frame.current = requestAnimationFrame(() => {
        frame.current = 0;
        if (scroller.current) {
          setViewport((v) => ({ ...v, top: scroller.current.scrollTop }));
        }
      });
    }
  };

  // Ausgewählte Zeile in den sichtbaren Bereich holen (Pfeiltasten, Sprünge aus anderen Ansichten).
  const selectedIndex = selected === null || selected === undefined ? -1 : rows.findIndex((r) => rowKey(r) === selected);
  useLayoutEffect(() => {
    const element = scroller.current;
    if (selectedIndex < 0 || !element) {
      return;
    }
    const top = selectedIndex * rowHeight;
    const bottom = top + rowHeight;
    const visibleHeight = element.clientHeight - headerHeight;
    if (top < element.scrollTop) {
      element.scrollTop = top;
    } else if (bottom > element.scrollTop + visibleHeight) {
      element.scrollTop = bottom - visibleHeight;
    }
  }, [selectedIndex, rows.length]);

  const onKeyDown = (e) => {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') {
      return;
    }
    e.preventDefault();
    const next = Math.max(0, Math.min(rows.length - 1, selectedIndex + (e.key === 'ArrowDown' ? 1 : -1)));
    if (rows[next]) {
      onSelect(rows[next]);
    }
  };

  const first = Math.max(0, Math.floor(viewport.top / rowHeight) - OVERSCAN);
  const last = Math.min(rows.length, Math.ceil((viewport.top + viewport.height) / rowHeight) + OVERSCAN);
  const visible = rows.slice(first, last);

  return html`<div class="vt" ref=${scroller} onScroll=${onScroll} onKeyDown=${onKeyDown} tabindex="0" role="grid" aria-rowcount=${rows.length}>
    <div class="vt-head" style=${{ gridTemplateColumns: template, minWidth: `${minWidth}px`, height: `${headerHeight}px` }} role="row">
      ${columns.map((c, i) => {
        const active = sort && sort.key === c.key;
        return html`<button key=${c.key} type="button" role="columnheader" disabled=${!c.value}
          class=${`vt-th ${c.align || ''} ${i === 0 ? 'sticky' : ''} ${active ? 'active' : ''}`} data-tip=${c.tip}
          onClick=${() => c.value && onSort(active && sort.dir === -1 ? { key: c.key, dir: 1 } : { key: c.key, dir: active ? -1 : (c.firstDir || 1) })}>
          <span>${c.title}</span>${active ? html`<${Icon} name=${sort.dir === 1 ? 'up' : 'down'} size=${12} />` : null}
        </button>`;
      })}
    </div>
    ${rows.length === 0 ? html`<div class="vt-empty">${empty}</div>` : html`
      <div class="vt-body" style=${{ height: `${rows.length * rowHeight}px`, minWidth: `${minWidth}px` }}>
        ${visible.map((row, offset) => {
          const key = rowKey(row);
          return html`<div key=${key} role="row" class=${`vt-row ${key === selected ? 'selected' : ''}`}
            style=${{ gridTemplateColumns: template, height: `${rowHeight}px`, transform: `translateY(${(first + offset) * rowHeight}px)` }}
            onClick=${() => {
              if (key === selected && onDeselect) {
                // Erneuter Klick hebt die Auswahl auf; die Tabelle soll danach auch nicht mehr fokussiert wirken.
                onDeselect();
                scroller.current.blur();
              } else {
                onSelect(row);
              }
            }}>
            ${columns.map((c, i) => html`<div key=${c.key} role="gridcell" class=${`vt-td ${c.align || ''} ${i === 0 ? 'sticky' : ''}`}>${c.render(row)}</div>`)}
          </div>`;
        })}
      </div>`}
  </div>`;
}

// ------------------------------------------------------------------ Geteilte Ansicht

/** Liste links, Detail rechts, mit verschiebbarer und gemerkter Trennlinie. */
export function MasterDetail({ name, master, detail, detailKey, initial = 0.5, min = 0.2, max = 0.8 }) {
  const fraction = useStore((s) => s.split[name]) ?? initial;
  const container = useRef();
  const detailPane = useRef();

  // Neue Auswahl beginnt oben, statt die Scrollposition der vorherigen zu übernehmen.
  useLayoutEffect(() => {
    if (detailPane.current) {
      detailPane.current.scrollTop = 0;
    }
  }, [detailKey]);

  const startDrag = (e) => {
    e.preventDefault();
    const element = container.current;
    const rect = element.getBoundingClientRect();
    element.classList.add('dragging');
    const onMove = (move) => {
      const value = Math.min(max, Math.max(min, (move.clientX - rect.left) / rect.width));
      element.style.setProperty('--split', `${value * 100}%`);
      element.dataset.value = value;
    };
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      element.classList.remove('dragging');
      if (element.dataset.value) {
        store.set({ split: { ...store.get().split, [name]: Number(element.dataset.value) } });
      }
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  return html`<div class="md" ref=${container} style=${{ '--split': `${fraction * 100}%` }}>
    <div class="md-master">${master}</div>
    <div class="md-handle" onPointerDown=${startDrag} role="separator" aria-orientation="vertical"></div>
    <div class="md-detail" ref=${detailPane}>${detail}</div>
  </div>`;
}

// ------------------------------------------------------------------ Verweise auf Spieldaten

export function ItemIcons({ data, ids, size = 30 }) {
  return html`<span class="icon-row">${ids.map((id, i) => {
    const item = data.itemById.get(id);
    return item
      ? html`<button key=${i} type="button" class="icon-link" onClick=${() => go('items', id)}><${Img} src=${item.icon} size=${size} tip=${`${item.name} · ${item.gold.total} Gold`} /></button>`
      : html`<span key=${i} class="muted">Item ${id}</span>`;
  })}</span>`;
}

export function RuneIcons({ data, ids, size = 30 }) {
  return html`<span class="icon-row">${ids.map((id, i) => {
    const rune = data.runeById.get(id);
    if (rune) {
      return html`<button key=${i} type="button" class="icon-link" onClick=${() => go('runes', id)}><${Img} src=${rune.icon} size=${size} tip=${rune.name} /></button>`;
    }
    const tree = data.treeById.get(id);
    if (tree) {
      return html`<${Img} key=${i} src=${tree.icon} size=${size} tip=${tree.name} />`;
    }
    return html`<span key=${i} class="muted">${data.shardById.get(id) || `Shard ${id}`}</span>`;
  })}</span>`;
}

export function SpellIcons({ data, keys, size = 30 }) {
  return html`<span class="icon-row">${keys.map((key, i) => {
    const spell = data.spellByKey.get(key);
    return spell
      ? html`<${Img} key=${i} src=${spell.icon} size=${size} tip=${spell.name} />`
      : html`<span key=${i} class="muted">Spell ${key}</span>`;
  })}</span>`;
}
