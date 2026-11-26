# RiftLens – Bedienung im Detail

Ergänzt die [README](../README.md). Items-Tab und Build-Rechner: [`nutzung-items.md`](nutzung-items.md), Draft-Tab: [`nutzung-draft.md`](nutzung-draft.md), Archiv: [`nutzung-archiv.md`](nutzung-archiv.md), Fearless: [`nutzung-fearless.md`](nutzung-fearless.md), Speicherorte und Grenzen: [`konfiguration.md`](konfiguration.md).

## Startparameter

| Parameter | Wirkung |
|---|---|
| `--no-window` | Nur der Server startet, die URL erscheint auf der Konsole, der Prozess beendet sich nicht von selbst |
| `--port=N` | Fester Port statt eines freien |
| `--smoke-test` | Prüft alle Endpunkte und beendet sich mit `SMOKE OK …` bzw. `SMOKE FAILED …` |

Ohne `--no-window` beendet sich der Prozess wenige Sekunden nach dem Schließen des letzten Fensters. Für Tests und den
Smoke-Test empfiehlt sich ein eigenes Home-Verzeichnis (siehe [`konfiguration.md`](konfiguration.md)).

## Funktionen

### Oberfläche

Die Tabs sind **Draft**, **Champions**, **Items**, **Runen**, **Abilities** und **Summoner**. Champions, Items, Runen und
Abilities zeigen die Daten aus Data Dragon und Wiki, dazu den Build-Rechner mit Stats, Cooldowns und Schadensvorschau.
Items-Tab und Build-Rechner (Item-Liste, Hinzufügen per „+“ oder Ziehen, Schalter, Champion und Rolle):
[`nutzung-items.md`](nutzung-items.md).

In der Übersicht eines Champions zeigen „Basiswerte“ und „Werte mit Build“ zuerst die Kernwerte; der Pfeil unter der
Tabelle („Alle Werte“) erweitert sie um alle übrigen. Die Basiswerte nehmen die Werte aus dem LoL-Wiki, ohne Wiki aus Data Dragon (Data Dragon ist z. B. beim
Angriffsschaden pro Level oft ungenau). Abweichende Werte sind unterstrichen, der Tooltip nennt den Data-Dragon-Wert.
Dazu kommen Angriffstempo-Ratio, Windup, Positionen, adaptiver Typ und Nah-/Fernkampf aus dem Wiki. „Werte mit Build“
enthält dieselben Werte (dazu Fähigkeitsstärke, Haste, Krit usw.) und führt über
„Items bearbeiten“ zum Build-Rechner, über „Runen bearbeiten“ zur Runenseite.

- Patch-Notes: Oben rechts steht der Spiel-Patch (z. B. „Patch 26.23“). Ein Klick darauf zeigt die Änderungen des
  aktuellen Patches für Summoner's Rift laut League-Wiki: Hotfixes zuerst, am Ende die gesammelten Bugfixes. Über die
  Patch-Auswahl lassen sich auch die 10 Patches davor anzeigen; sie werden erst bei Auswahl geladen. Jeder
  Block lässt sich auf- und zuklappen; allgemeine Abschnitte und die Bugfixes sind anfangs zu. Die Darstellung folgt
  dem Wiki (Icons, farbige Werte); ▲ markiert einen Buff, ▼ einen Nerf. Das wird aus dem Text geschätzt („increased“
  oder „reduced“, bei Abklingzeit und Kosten umgekehrt). Ein Klick auf Champion, Item oder Fähigkeit öffnet die
  passende Ansicht. In der Übersicht eines Champions zeigt der Block „Patch-Änderungen“ unter den Fähigkeiten die letzte
  Änderung; „Alle Änderungen“ klappt die ganze Liste auf. Die Patch-Notes werden höchstens einmal täglich neu geladen,
  damit Hotfixes erscheinen, die Änderungen eines Champions einmal je Patch.
- Statusleiste unten rechts: installierte Version und Update-Stand, ein gefundenes Update startet per Klick:
  [`nutzung-update.md`](nutzung-update.md)
- Schnellsuche über alles (`Strg`+`K`)
- Sortierbare Spalten, fehlende Werte stehen immer unten
- Pfeiltasten-Navigation, Splash-Art, helles und dunkles Design, verschiebbare Trennlinien
- Build, Filter und Layout bleiben über Neustarts erhalten (`modern-ui/ui-state.json`)
- `modern-ui/browser-profile` ist das eigene Browserprofil des App-Fensters

### Statistiken (Lolalytics)

**Auswahl:** In der Leiste oben wählst du den Zeitraum (**30 Tage** als Standard oder **Letzter Patch**) und den
Rang-Bereich von Iron bis Challenger (Standard **Emerald+**). Die Wahl bleibt gespeichert.

**Datenquellen:** Eine Tierlisten-Seite liefert alle Champions (ohne Rollenfilter je Champion die Hauptlane), eine
Champion-Seite Builds, Runen, Spells und Skill-Reihenfolgen. Der Core-Build enthält auch Boots.

**Rolle je Champion:** Über den Reitern der Vorschau wählst du die Rolle des Champions. **Auto** nimmt den
Rollenfilter und ohne Filter die häufigste Rolle. Eine andere Rolle lädt ihre Daten erst bei Auswahl und gilt für
Match-Statistik und Counters. Die Wahl bleibt je Champion gespeichert.

**Abrufregeln:** höchstens ein Abruf alle 2 Sekunden, 12 Stunden Cache, nur für privaten Eigengebrauch. Ältere
30-Tage-Statistik erscheint sofort und wird im Hintergrund einmal erneuert (wie im Draft); „Letzter Patch“ wird nach
Ablauf frisch geholt. Abrufe, auf die du wartest, haben dabei Vorrang.

**Ranglisten und Farben**

- Ranglisten berücksichtigen nur Einträge mit mindestens 1 % der Spiele des Champions, damit keine Nischen-Builds
  aus ohnehin gewonnenen Spielen oben stehen.
- Core-Builds mit denselben zwei Items in derselben Reihenfolge zählen als ein Build, egal mit welchen Boots (gezeigt
  mit den häufigsten Boots). Ausgewählt wird möglichst je ein Build pro erstem Item, damit verschiedene Starts
  sichtbar sind; angezeigt werden sie nach Rang.
- „Erstes Item“ und „Zweites Item“ zeigen je sechs Items in zwei Spalten, ohne Boots.
- Mejai's Soulstealer ist als „Snowballing“ markiert und ausgegraut: Es wird meist nur mit Vorsprung gekauft, seine
  Winrate ist dadurch überhöht. Es steht hinter den übrigen Items und nimmt keinen Platz im Core-Build.
- Der Core-Build zeigt immer vier verschiedene Builds nach Rang. Der meistgespielte ist immer dabei: an seinem Rang oder,
  falls er nicht unter den ersten vier steht, statt des vierten, unabhängig von der
  Winrate (Markierung „Meistgespielt“).
- Boots und Startitems zeigen sechs Einträge in zwei Spalten, Beschwörerzauber drei (z. B. beim Jungler auch Smite +
  Ignite). Freie Plätze füllen die häufigsten Einträge unter der Mindest-Stichprobe, deutlich ausgegraut.
- Runenseiten: Lolalytics nennt je Seite nur die Werte des Keystones. Die Winrate ist deshalb aus den einzelnen Runen
  und Shards geschätzt (die drei Shards zählen zusammen so viel wie eine Rune), die Spiele sind die kleinste
  Spielzahl davon. Winrate und Spiele jeder Rune stehen im Tooltip ihres Icons.
- Die dritte Zeile ist eine Empfehlung: die meistgespielte Seite, in der einzelne Runen oder Shards durch eine klar
  bessere Wahl derselben Reihe ersetzt sind. Keystone und Bäume bleiben gleich. Dort steht die geschätzte Winrate, in
  Klammern der Unterschied zur meistgespielten Seite in Prozentpunkten, z. B. „53.36 % (+0.38 %)“. Ausgetauschte Runen
  sind umrandet, ausgetauschte Shards farbig; was ersetzt wurde, zeigt der Tooltip bei „Show Advanced Tooltips“. Gleicht
  die Empfehlung einer der beiden anderen Seiten, entfällt sie.
- Die Skillung zeigt Level 1–15 aus der besten kompletten Skillung laut Lolalytics; Level 16–18 sind aus der besten
  Max-Reihenfolge ergänzt.
- Die Überschrift von „Items nach dem Core-Build“ nennt die durchschnittliche Kaufminute auf den Itemplätzen 3–5
  (z. B. „⌀ Min. 29“). Späte Items haben hohe Winrates vor allem, weil sie erst in gut laufenden Spielen gekauft
  werden.
- Einträge mit einer Winrate unter 48,5 % sind leicht ausgegraut, bei hoher Pickrate (ab 15 %) nur schwach.
- Counters: „Beste/Schlechteste Picks dagegen“ sortieren nach Pick-WR, der geschätzten Winrate des Picks aus seiner
  allgemeinen Stärke plus dem gedämpften Matchup-Effekt (Bewertung); darunter steht die Anzahl der Spiele.
- Grün oder Rot bei Winrates: mindestens 1 Punkt über bzw. mehr als 1 Punkt unter dem Durchschnitt des gewählten
  Datensatzes.

**Spalte „Stärke“ (nur mit Rollenfilter)**

```text
Stärke = (Winrate − Rollen-Durchschnitt) × P / (P + 1)    mit    P = Pickrate / (1 − Banrate)
```

Das ist eine eigene Gewichtung dieses Tools. Berücksichtigt werden nur Champions mit mindestens 15 % ihrer Spiele auf
der Rolle. Mit Rollenfilter werden außerdem Champions ausgeblendet, die auf dieser Rolle eine angezeigte Pickrate von
höchstens 0,2 % oder keine Spiele haben.

### Summoner-Tab (Riot-API)

Abfrage eines Spielers mit Rängen, Champions und Matchups, Spielstil, Meisterschaften und Live-Spiel; ein Konto lässt
sich als eigenes festlegen, dann zeigt der Draft deine Matchup-Bilanz: [`nutzung-summoner.md`](nutzung-summoner.md).
