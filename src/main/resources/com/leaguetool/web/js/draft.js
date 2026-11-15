// Draft-Assistent: Eingabe der Picks und Bans, die fünf besten nächsten Picks und die Gesamteinschätzung.
import { html, useState, useEffect, useMemo, useRef } from '/vendor/htm-preact.js';
import { store, useStore, useViewState, useSourceLabel, api, normalize, percent, sortRows, activeSort } from './core.js';
import { Icon, Img, Button, Select, Segmented, Switch, Card, Empty, ErrorNote, Loading, Tag, Spinner } from './ui.js';

const ROLES = [
  { value: 'TOP', label: 'Top' }, { value: 'JUNGLE', label: 'Jungle' }, { value: 'MIDDLE', label: 'Mid' },
  { value: 'BOTTOM', label: 'Bot' }, { value: 'UTILITY', label: 'Support' },
];
const roleLabel = (role) => ROLES.find((r) => r.value === role)?.label || role;
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
/** Eine Profil-Aussage über ein Team braucht mehr als ein oder zwei Picks. */
const MIN_PICKS_FOR_PROFILE = 3;
/** Kennzeichnet dieses Fenster, damit das Backend nur seine eigenen überholten Anfragen abbricht. */
const CLIENT_ID = crypto.randomUUID();

const DEFAULTS = {
  ally: Array(TEAM_SIZE).fill(null), enemy: Array(TEAM_SIZE).fill(null), bans: [],
  side: 'ally', role: '', risk: 'MEDIUM', minGames: AUTO_MIN_GAMES, ignoreChampionWinRates: false, tab: 'picks', picker: null,
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

function useDraftResult(view, dataSource) {
  const [state, setState] = useState({ result: null, error: null, loading: true });
  const sequence = useRef(0);
  const body = JSON.stringify({
    ally: view.ally.filter(Boolean), enemy: view.enemy.filter(Boolean), bans: view.bans, side: view.side,
    risk: view.risk, minGames: view.minGames === AUTO_MIN_GAMES ? null : Number(view.minGames),
    ignoreChampionWinRates: view.ignoreChampionWinRates, tier: dataSource.tier, period: dataSource.period,
  });
  useEffect(() => {
    const requestId = ++sequence.current;
    const controller = new AbortController();
    setState((previous) => ({ ...previous, error: null, loading: true, requestId }));
    const timer = setTimeout(() => {
      api.post('/api/draft', { ...JSON.parse(body), client: CLIENT_ID, requestId }, controller.signal)
        .then((result) => !controller.signal.aborted && setState({ result, error: null, loading: false, requestId }))
        .catch((error) => !controller.signal.aborted && setState((previous) => ({ ...previous, error, loading: false })));
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
  const enabled = Boolean(lcu) && lcu.status !== 'OFF';
  const text = enabled ? CLIENT_STATUS[lcu.status] || lcu.message : '';
  return html`<div class="draft-client">
    <${Switch} checked=${enabled} onChange=${onToggle}
      tip="Liest während der Champion-Auswahl Picks, Bans und eure Rollen aus dem League-Client (nur lesend, wie DraftGap). Keine Namen, keine Aktionen im Client.">Mit League-Client abgleichen<//>
    ${text ? html`<span class=${`small draft-client-status ${lcu.status === 'CHAMP_SELECT' ? 'live' : 'muted'}`}>${text}</span>` : null}
  </div>`;
}

export function DraftView() {
  const data = useStore((s) => s.data);
  const dataSource = useStore((s) => s.dataSource);
  const [view, setView] = useViewState('draft', DEFAULTS);
  const { result, error, loading, requestId } = useDraftResult(view, dataSource);
  const sourceLabel = useSourceLabel();
  const client = useClientSync(view, setView, data);
  const owned = useMemo(() => new Set(client.champSelect ? client.lcu.pickable : []), [client.champSelect, client.lcu]);
  const hints = useMemo(() => {
    const cs = client.champSelect;
    return cs ? { ally: cs.ally.map((m) => m), enemy: cs.enemy.map((m) => m) } : { ally: [], enemy: [] };
  }, [client.champSelect]);

  const used = useMemo(() => new Set([...view.ally, ...view.enemy].filter(Boolean).map((s) => s.champion).concat(view.bans)),
    [view.ally, view.enemy, view.bans]);

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
    if (loading || !result || result.side !== team || used.has(suggestion.champion) || roleTaken) {
      return;
    }
    const index = view[team].findIndex((slot) => !slot);
    if (index >= 0) {
      assign({ team, index, role: suggestion.role }, suggestion.champion);
    }
  };

  const reset = () => setView({ ally: DEFAULTS.ally, enemy: DEFAULTS.enemy, bans: [] });
  const picks = view.ally.filter(Boolean).length + view.enemy.filter(Boolean).length;

  return html`<div class="page draft">
    <section class="draft-board">
      <${TeamColumn} title="Dein Team" team="ally" slots=${view.ally} result=${result} data=${data} pending=${pendingRole} hints=${hints.ally}
        onPick=${(index) => setView({ picker: { team: 'ally', index } })} onRole=${setSlotRole} onClear=${clearSlot} />
      <div class="draft-center">
        <${WinPanel} analysis=${result?.analysis} picks=${picks} loading=${loading} />
        <${BanRow} bans=${view.bans} data=${data} onAdd=${() => setView({ picker: { team: 'bans' } })}
          onRemove=${(key) => setView({ bans: view.bans.filter((b) => b !== key) })} />
        <div class="draft-actions">
          <${Button} icon="trash" onClick=${reset} disabled=${picks === 0 && view.bans.length === 0}>Draft leeren<//>
          <span class="muted small">${sourceLabel}</span>
        </div>
        <${ClientSync} lcu=${client.lcu} onToggle=${client.toggle} />
      </div>
      <${TeamColumn} title="Gegner" team="enemy" slots=${view.enemy} result=${result} data=${data} pending=${pendingRole} hints=${hints.enemy}
        onPick=${(index) => setView({ picker: { team: 'enemy', index } })} onRole=${setSlotRole} onClear=${clearSlot} />
    </section>

    ${error ? html`<${ErrorNote} prefix="Draft konnte nicht berechnet werden" error=${error} />` : null}
    ${loading ? html`<${ProgressNote} requestId=${requestId} />` : null}
    ${result && result.warnings.length ? html`<ul class="draft-warnings">
      ${result.warnings.map((w, i) => html`<li key=${i}><${Icon} name="alert" size=${14} /> ${w}</li>`)}
    </ul>` : null}

    <div class="draft-tabs">
      <${Segmented} label="Bereich" value=${view.tab} onChange=${(tab) => setView({ tab })}
        options=${[{ value: 'picks', label: 'Beste Picks' }, { value: 'analysis', label: 'Draft-Analyse' }]} />
      <div class="draft-options">
        <label class="draft-option" data-tip="Wie stark kleine Stichproben zur Erwartung gezogen werden. Die Prior-Spiele je Rollenpaar kommen aus der gemessenen Streuung (Lane-Matchup etwa 700, Duo Top+Mid etwa 14.000); der Faktor vervielfacht sie. Mittel entspricht der Stärke von DraftGap bei Champions.">
          <span class="muted small">Risiko</span>
          <${Select} label="Risiko" value=${view.risk} options=${RISKS} onChange=${(risk) => setView({ risk })} />
        </label>
        <label class="draft-option" data-tip="Mindestspiele eines Vorschlags auf seiner Rolle in den letzten 30 Tagen. Auto richtet sich nach der Spielmenge des Rang-Bereichs; in Emerald+ entspricht das etwa dem Standard von DraftGap.">
          <span class="muted small">Vorschläge</span>
          <${Select} label="Mindestspiele" value=${view.minGames} onChange=${(minGames) => setView({ minGames })}
            options=${[{ value: AUTO_MIN_GAMES, label: result ? `Auto (ab ${result.minGames.toLocaleString('de-DE')})` : 'Auto' }, ...MIN_GAMES]} />
        </label>
        <${Switch} checked=${view.ignoreChampionWinRates} onChange=${(ignoreChampionWinRates) => setView({ ignoreChampionWinRates })}
          tip="Bewertet nur Duos und Matchups, nicht die allgemeine Stärke der Champions">Nur Synergien und Matchups<//>
      </div>
    </div>

    ${!result ? (loading ? null : html`<${Empty} title="Noch keine Daten" />`)
      : view.tab === 'picks'
        ? html`<${Suggestions} result=${result} data=${data} view=${view} setView=${setView} onTake=${takeSuggestion} busy=${loading} owned=${owned} />`
        : html`<${Analysis} result=${result} data=${data} picks=${picks} />`}

    ${view.picker ? html`<${ChampionPicker} data=${data} used=${used}
      title=${view.picker.team === 'bans' ? 'Champion bannen' : view.picker.team === 'ally' ? 'Pick für dein Team' : 'Pick des Gegners'}
      onChoose=${(champion) => assign(view.picker, champion)} onClose=${() => setView({ picker: null })} />` : null}
  </div>`;
}

function ProgressNote({ requestId }) {
  const progress = useStore((s) => s.draftProgress);
  const text = progress && progress.requestId === requestId && progress.done < progress.total
    ? `Lade Daten von Lolalytics … ${progress.done} von ${progress.total} (neue Daten brauchen je etwa 2 Sekunden)`
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
      data-tip=${open ? undefined : tip} onClick=${() => setOpen(!open)}>
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
          <span class="muted">${label}</span>
          ${hint && hint.local ? html`<${Tag} kind="soft">Du<//>` : null}
        </button>`;
      }
      const champion = data.championByKey.get(slot.champion);
      // Die Antwort kann noch zum vorherigen Draft gehören: nur verwenden, wenn sie die gewählte Rolle schon kennt.
      const prediction = predicted.get(slot.champion);
      const current = prediction && (!slot.role || prediction.role === slot.role) ? prediction : null;
      return html`<div key=${index} class="draft-slot">
        <button type="button" class="draft-slot-champion" onClick=${() => onPick(index)} data-tip="Champion ändern">
          <${Img} src=${champion.icon} size=${40} /><span class="draft-name">${champion.name}</span>
        </button>
        <${RolePicker} champion=${champion} slot=${slot} prediction=${current} shares=${prediction?.shares}
          pending=${pending === `${team}:${slot.champion}`} onChoose=${(role) => onRole(team, index, role)} />
        <${Button} icon="x" kind="icon" title=${`${champion.name} entfernen`} onClick=${() => onClear(team, index)} />
      </div>`;
    })}
  </section>`;
}

function BanRow({ bans, data, onAdd, onRemove }) {
  return html`<div class="draft-bans" aria-label="Bans">
    <span class="muted small">Bans</span>
    ${bans.map((key) => {
      const champion = data.championByKey.get(key);
      return html`<button type="button" key=${key} class="draft-ban" onClick=${() => onRemove(key)} data-tip=${`${champion.name}: Ban entfernen`}>
        <${Img} src=${champion.icon} size=${30} />
      </button>`;
    })}
    ${bans.length < MAX_BANS ? html`<button type="button" class="draft-ban add" onClick=${onAdd} aria-label="Ban hinzufügen"><${Icon} name="plus" size=${15} /></button>` : null}
  </div>`;
}

function ChampionPicker({ data, used, title, onChoose, onClose }) {
  const [query, setQuery] = useState('');
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
      const first = matches.find((c) => !used.has(c.key));
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
        ${matches.map((c) => html`<button type="button" key=${c.key} disabled=${used.has(c.key)} onClick=${() => onChoose(c.key)}>
          <${Img} src=${c.icon} size=${48} /><span>${c.name}</span>
        </button>`)}
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

function WinPanel({ analysis, picks, loading }) {
  if (!analysis) {
    return html`<div class="draft-win"><p class="muted">${loading ? 'Lade …' : 'Keine Daten'}</p></div>`;
  }
  const allyShare = analysis.winRate * 100;
  const state = verdict(analysis.winRate);
  return html`<div class="draft-win">
    <div class="draft-win-numbers">
      <strong class="draft-win-value">${percent(analysis.winRate)}</strong>
      <span class="draft-win-label"><span class=${state.tone}>${state.text}</span>
        <small class="muted">${picks === 10 ? 'Vollständiger Draft' : `Vorläufig: ${picks} von 10 Picks`}</small></span>
    </div>
    <div class="draft-win-bar" role="img" aria-label=${`Dein Team ${allyShare.toFixed(1)} Prozent, Gegner ${(100 - allyShare).toFixed(1)} Prozent`}>
      <span class="ally" style=${{ width: `${allyShare}%` }}></span>
      <span class="enemy" style=${{ width: `${100 - allyShare}%` }}></span>
    </div>
    <div class="draft-win-parts">
      <${Part} label="Champions" rating=${analysis.championRating} tip="Allgemeine Stärke der Champions auf ihren Rollen" />
      <${Part} label="Duos" rating=${analysis.duoRating} tip="Synergien im eigenen Team minus Synergien im Gegnerteam" />
      <${Part} label="Matchups" rating=${analysis.matchupRating} tip="Alle Paarungen eigener Champions gegen gegnerische" />
    </div>
  </div>`;
}

function Part({ label, rating, tip }) {
  return html`<div class="draft-part" data-tip=${tip}>
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
    { key: 'championRating', title: 'Champion', tip: 'Beitrag der Champion-Stärke', value: (s) => s.championRating },
    { key: 'duoRating', title: 'Duos', tip: 'Beitrag der Synergien mit dem eigenen Team', value: (s) => s.duoRating },
    { key: 'matchupRating', title: 'Matchups', tip: 'Beitrag der Matchups gegen den Gegner', value: (s) => s.matchupRating },
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
      ${result.openRoles.length === 0 ? 'Dieses Team ist vollständig.' : 'Senke die Mindestspiele oder wähle eine andere Rolle.'}<//>` : html`
      <h3 class="draft-section-title">Top ${top.length} für ${sideText}${role ? ` auf ${roleLabel(role)}` : ''}</h3>
      <div class="draft-top">
        ${top.map((s, i) => html`<${TopCard} key=${`${s.champion}-${s.role}`} rank=${i + 1} suggestion=${s} data=${data} onTake=${onTake} baseText=${baseText} />`)}
      </div>
      ${rest.length ? html`<${Card} title="Weitere Picks">
        <div class="grid-scroll"><table class="data draft-table">
          <thead><tr>${columns.map(headCell)}</tr></thead>
          <tbody>${rest.map((s) => {
            const champion = data.championByKey.get(s.champion);
            return html`<tr key=${`${s.champion}-${s.role}`} class="draft-row" onClick=${() => onTake(s)} data-tip="Pick übernehmen">
              <td class="muted">${s.rank}</td>
              <td><span class="draft-cell-champion">${champion ? html`<${Img} src=${champion.icon} size=${26} />` : null}${champion?.name || s.champion}</span></td>
              <td>${roleLabel(s.role)}</td><td><strong>${percent(s.winRate)}</strong></td>
              <td class=${toneOf(s.delta * 100)}>${signed(s.delta * 100)}</td>
              <td class=${toneOf(impact(s.championRating))}>${impactText(s.championRating)}</td>
              <td class=${toneOf(impact(s.duoRating))}>${impactText(s.duoRating)}</td>
              <td class=${toneOf(impact(s.matchupRating))}>${impactText(s.matchupRating)}</td>
              <td class="muted">${count(s.games)}</td>
            </tr>`;
          })}</tbody>
        </table></div>
      <//>` : null}`}
  </div>`;
}

function TopCard({ rank, suggestion: s, data, onTake, baseText }) {
  const champion = data.championByKey.get(s.champion);
  const details = [...s.details].sort((a, b) => Math.abs(b.rating) - Math.abs(a.rating)).slice(0, 3);
  return html`<button type="button" class=${`draft-top-card ${rank === 1 ? 'best' : ''}`} onClick=${() => onTake(s)}
    data-tip=${`Pick übernehmen · Winrate ${baseText} mit diesem Pick`}>
    <span class="draft-rank">${rank}</span>
    <span class="draft-top-head">
      ${champion ? html`<${Img} src=${champion.icon} size=${48} />` : null}
      <span class="draft-top-name"><strong>${champion?.name || s.champion}</strong><${Tag} kind="soft">${roleLabel(s.role)}<//></span>
    </span>
    <span class="draft-top-value">${percent(s.winRate)}<small class=${toneOf(s.delta * 100)}>${signed(s.delta * 100)}</small></span>
    <span class="draft-top-parts">
      <span>Champion <b class=${toneOf(impact(s.championRating))}>${impactText(s.championRating)}</b></span>
      <span>Duos <b class=${toneOf(impact(s.duoRating))}>${impactText(s.duoRating)}</b></span>
      <span>Matchups <b class=${toneOf(impact(s.matchupRating))}>${impactText(s.matchupRating)}</b></span>
    </span>
    ${details.length ? html`<span class="draft-top-details">
      ${details.map((d) => {
        const other = data.championByKey.get(d.champion);
        return html`<span key=${`${d.kind}-${d.champion}`} class="draft-chip"
          data-tip=${`${d.kind === 'duo' ? 'Mit' : 'Gegen'} ${other?.name}: ${d.winRate === null ? 'keine Spiele' : `${percent(d.winRate)} in ${count(d.games)} Spielen`}`}>
          ${other ? html`<${Img} src=${other.icon} size=${20} />` : null}<b class=${toneOf(impact(d.rating))}>${impactText(d.rating)}</b>
        </span>`;
      })}
    </span>` : null}
  </button>`;
}

// ------------------------------------------------------------------ Analyse

function assessment(result, data, picks) {
  const { analysis, profiles } = result;
  const name = (key) => data.championByKey.get(key)?.name || key;
  const lines = [];
  const state = verdict(analysis.winRate);
  const parts = [['Champions', analysis.championRating], ['Duos', analysis.duoRating], ['Matchups', analysis.matchupRating]]
    .filter(([, rating]) => Math.abs(impact(rating)) >= 0.05)
    .sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]));
  lines.push(`${state.text} mit ${percent(analysis.winRate)} erwarteter Winrate${picks < 10 ? `, vorläufig bei ${picks} von 10 Picks` : ''}.`);
  if (parts.length) {
    lines.push(`Größter Einfluss: ${parts.map(([label, rating]) => `${label} ${impactText(rating)} Punkte`).join(', ')}.`);
  }
  const usable = (pair) => pair.games > 0;
  const matchups = analysis.matchups.filter(usable).sort((a, b) => b.rating - a.rating);
  if (matchups.length) {
    const best = matchups[0];
    const worst = matchups[matchups.length - 1];
    if (best.rating > 0) {
      lines.push(`Bestes Matchup: ${name(best.first.champion)} gegen ${name(best.second.champion)} (${impactText(best.rating)}).`);
    }
    if (worst.rating < 0) {
      lines.push(`Schwierigstes Matchup: ${name(worst.first.champion)} gegen ${name(worst.second.champion)} (${impactText(worst.rating)}).`);
    }
  }
  const allyDuo = analysis.allyDuos.filter(usable).sort((a, b) => b.rating - a.rating)[0];
  if (allyDuo && allyDuo.rating > 0) {
    lines.push(`Stärkstes Duo im Team: ${name(allyDuo.first.champion)} und ${name(allyDuo.second.champion)} (${impactText(allyDuo.rating)}).`);
  }
  const enemyDuo = analysis.enemyDuos.filter(usable).sort((a, b) => b.rating - a.rating)[0];
  if (enemyDuo && enemyDuo.rating > 0) {
    lines.push(`Gefährlichstes Duo beim Gegner: ${name(enemyDuo.first.champion)} und ${name(enemyDuo.second.champion)} (${impactText(enemyDuo.rating)}).`);
  }
  [['Dein Team', profiles.ally], ['Der Gegner', profiles.enemy]].forEach(([label, profile]) => {
    const total = profile.physical + profile.magic + profile.true;
    if (profile.champions >= MIN_PICKS_FOR_PROFILE && total > 0 && Math.max(profile.physical, profile.magic) / total >= 0.75) {
      lines.push(`${label} macht fast nur ${profile.physical > profile.magic ? 'physischen' : 'magischen'} Schaden und lässt sich gezielt mit Resistenzen kontern.`);
    }
  });
  const trend = Math.min(profiles.ally.champions, profiles.enemy.champions) >= MIN_PICKS_FOR_PROFILE ? lengthTrend(profiles) : null;
  if (trend) {
    const late = trend.slice(-3).reduce((sum, v) => sum + v, 0);
    const early = trend.slice(0, 3).reduce((sum, v) => sum + v, 0);
    if (Math.abs(late - early) >= 10) {
      lines.push(late > early ? 'Dein Team wird mit längerer Spieldauer relativ stärker.' : 'Dein Team ist in kurzen Spielen relativ stärker und sollte früh Druck machen.');
    }
  }
  return lines;
}

function lengthTrend({ ally, enemy }) {
  const size = Math.max(ally.ratingByLength.length, enemy.ratingByLength.length);
  if (size === 0 || ally.champions === 0 || enemy.champions === 0) {
    return null;
  }
  return Array.from({ length: size }, (_, i) => (ally.ratingByLength[i] || 0) - (enemy.ratingByLength[i] || 0));
}

function Analysis({ result, data, picks }) {
  const { analysis, profiles } = result;
  if (picks === 0) {
    return html`<${Empty} title="Noch kein Draft">Sobald Champions gewählt sind, erscheint hier die Einschätzung.<//>`;
  }
  const trend = lengthTrend(profiles);
  const byImpact = (a, b) => Math.abs(b.rating) - Math.abs(a.rating);
  return html`<div class="draft-analysis">
    <${Card} title="Gesamteinschätzung" cls="draft-assessment">
      <ul>${assessment(result, data, picks).map((line, i) => html`<li key=${i}>${line}</li>`)}</ul>
    <//>
    <div class="draft-analysis-grid">
      <${Card} title="Champions">
        <${ChampionTable} label="Dein Team" rows=${analysis.allyChampions} data=${data} />
        <${ChampionTable} label="Gegner" rows=${analysis.enemyChampions} data=${data} />
      <//>
      <${Card} title="Profil der Teams">
        <${DamageRow} label="Dein Team" profile=${profiles.ally} />
        <${DamageRow} label="Gegner" profile=${profiles.enemy} />
        ${trend ? html`<${LengthChart} values=${trend} />` : null}
      <//>
    </div>
    <${PairCard} title="Matchups" hint="Aus Sicht deines Teams" rows=${[...analysis.matchups].sort(byImpact)} data=${data} separator="gegen" />
    <div class="draft-analysis-grid">
      <${PairCard} title="Duos deines Teams" rows=${[...analysis.allyDuos].sort(byImpact)} data=${data} separator="+" />
      <${PairCard} title="Duos des Gegners" rows=${[...analysis.enemyDuos].sort(byImpact)} data=${data} separator="+" />
    </div>
  </div>`;
}

function ChampionTable({ label, rows, data }) {
  if (rows.length === 0) {
    return html`<p class="muted small">${label}: keine Werte</p>`;
  }
  return html`<div class="grid-scroll"><table class="data draft-table">
    <thead><tr><th>${label}</th><th>Rolle</th><th>WR</th><th>Einfluss</th><th>Spiele</th></tr></thead>
    <tbody>${rows.map((row) => html`<tr key=${row.champion}>
      <td><${ChampionCell} data=${data} champion=${row.champion} /></td><td>${roleLabel(row.role)}</td>
      <td>${percent(row.winRate)}</td><td class=${toneOf(impact(row.rating))}>${impactText(row.rating)}</td><td class="muted">${count(row.games)}</td>
    </tr>`)}</tbody>
  </table></div>`;
}

function ChampionCell({ data, champion }) {
  const c = data.championByKey.get(champion);
  return html`<span class="draft-cell-champion">${c ? html`<${Img} src=${c.icon} size=${24} />` : null}${c?.name || champion}</span>`;
}

function PairCard({ title, hint, rows, data, separator }) {
  return html`<${Card} title=${title} aside=${hint}>
    ${rows.length === 0 ? html`<p class="muted small">Noch keine Paarungen.</p>` : html`<div class="grid-scroll"><table class="data draft-table">
      <thead><tr><th>Paarung</th><th>WR</th><th>Einfluss</th><th>Spiele</th></tr></thead>
      <tbody>${rows.map((row) => html`<tr key=${`${row.first.champion}-${row.second.champion}`}>
        <td><span class="draft-pair"><${ChampionCell} data=${data} champion=${row.first.champion} /><span class="muted">${separator}</span><${ChampionCell} data=${data} champion=${row.second.champion} /></span></td>
        <td>${percent(row.winRate)}</td><td class=${toneOf(impact(row.rating))}>${impactText(row.rating)}</td><td class="muted">${count(row.games)}</td>
      </tr>`)}</tbody>
    </table></div>`}
  <//>`;
}

function DamageRow({ label, profile }) {
  const total = profile.physical + profile.magic + profile.true;
  const share = (value) => (total > 0 ? `${Math.round((value / total) * 100)} %` : '–');
  return html`<div class="draft-damage">
    <span>${label}</span>
    <span><span class="muted small">Physisch</span> ${share(profile.physical)}</span>
    <span><span class="muted small">Magisch</span> ${share(profile.magic)}</span>
    <span><span class="muted small">True</span> ${share(profile.true)}</span>
  </div>`;
}

/** Vorsprung je Champion in Rating-Punkten je Spieldauer-Abschnitt; eine Reihe, oben dein Team, unten der Gegner. */
function LengthChart({ values }) {
  const peak = Math.max(10, ...values.map((v) => Math.abs(v)));
  return html`<div class="draft-length">
    <h4>Vorsprung je Champion nach Spieldauer</h4>
    <div class="draft-length-plot" role="img" aria-label=${`Vorsprung je Spieldauer-Abschnitt in Punkten: ${values.map((v) => signed(impact(v))).join(', ')}`}>
      ${values.map((value, i) => {
        const height = (Math.abs(value) / peak) * 50;
        const tip = `Abschnitt ${i + 1} von ${values.length}: ${signed(impact(value))} Punkte ${value >= 0 ? 'für dein Team' : 'für den Gegner'}`;
        return html`<div key=${i} class="draft-length-column" data-tip=${tip}>
          <span class=${value >= 0 ? 'ally' : 'enemy'} style=${{ height: `${height}%` }}></span>
        </div>`;
      })}
    </div>
    <div class="draft-length-axis muted small"><span>kurze Spiele</span><span>lange Spiele</span></div>
  </div>`;
}
