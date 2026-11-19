# RiftLens – Bedienung im Detail

Ergänzt die [README](../README.md). Items-Tab und Build-Rechner: [`nutzung-items.md`](nutzung-items.md), Draft-Tab: [`nutzung-draft.md`](nutzung-draft.md), Archiv und Fearless: [`nutzung-archiv.md`](nutzung-archiv.md), Speicherorte und Grenzen: [`konfiguration.md`](konfiguration.md).

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
- Beim Core-Build steht unter den drei besten zusätzlich der meistgespielte Build, unabhängig von der Winrate
  (Markierung „Meistgespielt“; ist er schon unter den besten, wird nur diese Zeile markiert).
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

**Eingabe:** Riot-ID (`Name#TAG`), Region und Anzahl der Matches (20, 50, 100, 200, 300, 500 oder 1000). Ein API-Key in den
Optionen ist nötig, und die Riot-Abfragen müssen in den Einstellungen aktiviert sein (Standard: aus). Die Region ist
standardmäßig `EUW1`. Schlägt eine Abfrage fehl, steht unter dem Fehler eine Anleitung für einen neuen Dev-Key.

**Anzeige:** Ränge, meistgespielte Champions mit Winrate, KDA und CS/Min, häufige Mitspieler, Spielmodi, Rollen,
Meisterschaft und Match-Verlauf. Ein Klick auf einen Spielmodus filtert die Auswertung. Der Match-Verlauf zeigt zuerst
50 Spiele, darunter laden „weitere anzeigen“ und „Alle anzeigen“ den Rest. Die Auswertung zählt immer alle abgerufenen
Matches.

**Häufige Mitspieler (Duo):** Riot meldet keine Premades. Als Mitspieler gilt, wer in der Auswahl mindestens dreimal im
eigenen Team war (Arena: im selben Zweierteam). Angezeigt werden Spiele und Winrate zusammen, die eigene Winrate in den
übrigen Spielen und die Differenz. Ein Klick auf den Namen fragt diesen Spieler ab.

**Hinweise**

- Winrates beziehen sich auf die abgerufenen Matches, Remakes zählen nicht mit.
- Riot liefert höchstens 100 Match-IDs je Aufruf; größere Mengen werden seitenweise geholt.
- Je Spieler liegt eine gzip-Datei `cache/_global/riot/history/<puuid>.json.gz` mit der eigenen Zeile je Match und
  den Teamkollegen (PUUID, Riot-ID), etwa 100–150 KB für 500 und 200–300 KB für 1000 Spiele (höchstens 1200 Matches
  je Spieler). Weitere Abfragen laden nur neue Spiele.
- Mit einem Dev- oder Personal-Key gelten 100 Anfragen in 2 Minuten. Der erste Abruf dauert dadurch bei 500 Matches
  etwa 10, bei 1000 etwa 20 Minuten; für 1000 erscheint ein eigener Warnhinweis. Die Anzeige schätzt die Restzeit.
  Zwischendurch wird gespeichert, ein Abbruch verliert nichts. Riot hält Matches nur etwa zwei Jahre vor.

