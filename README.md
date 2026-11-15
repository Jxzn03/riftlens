# RiftLens

Privates Desktop-Tool zum Abrufen und Anzeigen von League-of-Legends-Daten zu **Champions, Items, Runen,
Abilities und Summoner Spells**.

**Funktionen**

- Rechenmodul: Stats nach Level, Item-Summen, Cooldowns, Schadensvorschau
- Statistiken von Lolalytics (Champions, Builds, Runen, Skill-Reihenfolgen)
- Draft-Analyse mit Pick-Vorschlägen
- Summoner-Auswertung über die Riot-API

## Inhalt

1. [Technik im Überblick](#technik-im-überblick)
2. [Voraussetzungen](#voraussetzungen)
3. [Starten und Bauen](#starten-und-bauen)
4. [Funktionen im Detail](#funktionen-im-detail)
5. [Konfiguration](#konfiguration)
6. [Sicherheit](#sicherheit)
7. [Architektur](#architektur)
8. [Bekannte Einschränkungen](#bekannte-einschränkungen)

## Technik im Überblick

| Bereich | Umsetzung |
|---|---|
| Backend | Java 21, eigener HTTP-Server |
| Frontend | [Preact](https://preactjs.com/) + `htm` im Edge-/Chrome-App-Fenster, kein Node nötig |
| Datenquellen | Data Dragon, League-of-Legends-Wiki, Lolalytics, Riot-API |
| Speicherung | Dateicache im Home-Verzeichnis, keine Datenbank |

## Voraussetzungen

- JDK 21 oder neuer (getestet mit JDK 23). Maven muss nicht installiert sein: Der Maven-Wrapper
  (`mvnw` / `mvnw.cmd`) lädt Maven 3.9.9 beim ersten Aufruf selbst.
- Internet für den ersten Start (Data Dragon, Wiki). Danach funktioniert das Tool auch offline aus dem Cache.

## Starten und Bauen

```powershell
.\start-modern.cmd             # baut beim ersten Mal dist\league-tool-modern.jar und startet
.\start-modern.cmd --rebuild   # nach Code-Änderungen neu bauen (App vorher schließen)
.\mvnw.cmd clean verify        # bauen und alle Tests ausführen
```

`start-modern.cmd` baut ohne Tests (Profil `modern`) und startet die App im eigenen Prozess. Auch JavaScript und CSS
liegen im Jar, daher ist nach Änderungen daran ebenfalls `--rebuild` nötig.

### IntelliJ

Die `pom.xml` im Projektordner als Projekt öffnen (oder per *Add as Maven Project* hinzufügen) und
`com.leaguetool.ModernMain` starten.

### Startparameter

| Parameter | Wirkung |
|---|---|
| `--no-window` | Nur der Server startet, die URL erscheint auf der Konsole, der Prozess beendet sich nicht von selbst |
| `--port=N` | Fester Port statt eines freien |
| `--smoke-test` | Prüft alle Endpunkte und beendet sich mit `SMOKE OK …` bzw. `SMOKE FAILED …` |

Ohne `--no-window` beendet sich der Prozess wenige Sekunden nach dem Schließen des letzten Fensters. Für Tests und den
Smoke-Test empfiehlt sich ein eigenes Home-Verzeichnis (siehe [Konfiguration](#konfiguration)).

## Funktionen im Detail

### Oberfläche

Die Tabs sind **Draft**, **Champions**, **Items**, **Runen**, **Abilities** und **Summoner**. Champions, Items, Runen und
Abilities zeigen die Daten aus Data Dragon und Wiki, dazu den Build-Rechner mit Stats, Cooldowns und Schadensvorschau.

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

**Abrufregeln:** höchstens ein Abruf alle 2 Sekunden, 12 Stunden Cache, nur für privaten Eigengebrauch.

**Ranglisten und Farben**

- Ranglisten berücksichtigen nur Einträge mit mindestens 1 % der Spiele des Champions, damit keine Nischen-Builds
  aus ohnehin gewonnenen Spielen oben stehen.
- Grün oder Rot bei Winrates: mindestens 1 Punkt über bzw. mehr als 1 Punkt unter dem Durchschnitt des gewählten
  Datensatzes.

**Spalte „Stärke“ (nur mit Rollenfilter)**

```text
Stärke = (Winrate − Rollen-Durchschnitt) × P / (P + 1)    mit    P = Pickrate / (1 − Banrate)
```

Das ist eine eigene Gewichtung dieses Tools. Berücksichtigt werden nur Champions mit mindestens 15 % ihrer Spiele auf
der Rolle. Mit Rollenfilter werden außerdem Champions ausgeblendet, die auf dieser Rolle eine angezeigte Pickrate von
höchstens 0,2 % oder keine Spiele haben.

### Draft-Tab

**Bedienung:** Picks beider Teams und Bans eintragen. Ohne feste Rolle sagt das Tool die wahrscheinlichste Verteilung
voraus. Für jede Situation stehen die **fünf besten Picks** hervorgehoben oben, wahlweise für dein Team oder den Gegner
und je Rolle gefiltert. Ein Klick übernimmt den Pick.

**Draft-Analyse:** erwartete Winrate, Gesamteinschätzung, alle Matchups und Duos, Schadensverteilung und Vorsprung
nach Spieldauer.

**Abgleich mit dem League-Client** (Schalter im Draft-Tab, Standard aus, sein Stand bleibt über Neustarts erhalten;
ausgeschaltet wird der Client nie abgefragt): Während der Champion-Auswahl übernimmt das
Tool gesperrte Picks, Bans und eure zugewiesenen Rollen automatisch, wie DraftGap. Ansagen von Mitspielern erscheinen
als Hinweis. „Nur spielbare Champions“ zeigt nur Vorschläge, die dein Account in dieser Auswahl spielen darf.
Der Abgleich liest nur, und zwar ausschließlich die Champion-Auswahl. Er klickt nichts im Client, übernimmt keine
Spielernamen und liest nichts im laufenden Spiel. Den League-Ordner findet das Tool über Riots eigene Dateien, auch auf
anderen Laufwerken; liegt League ungewöhnlich, lässt er sich in den Optionen eintragen. Details und Bewertung des
Ban-Risikos: `docs/client-sync.md`.

**Rechenmodell**

Es folgt [DraftGap](https://github.com/vigovlugt/draftgap) (MIT-Lizenz): Winrates werden zu Rating-Punkten, Champions,
Duos und Matchups addieren sich. Kleine Stichproben werden zur Erwartung hin geglättet. Abweichungen:

- Die Glättung richtet sich je Rollenpaar nach der gemessenen Streuung echter Effekte. Lane-Matchups und das
  Botlane-Duo zählen schon bei wenigen Spielen, Duos über die Map erst bei sehr vielen. Das **Risiko** vervielfacht
  diese Glättung.
- Die Champion-Winrate wird auch bei „30 Tage“ zum Durchschnitt hin geglättet.

**Bewertung der Vorschläge**

- „Bester Pick“ heißt: höchste erwartete Winrate mit den bisher bekannten Picks. Spätere Antworten des Gegners und
  die Beherrschung des Champions fließen nicht ein.
- Champions auf Nebenrollen können wegen ihrer spezialisierten Spieler hoch stehen. Dagegen hilft der Filter
  **Vorschläge** (Mindestspiele). Sein Standard **Auto** richtet sich nach der Spielmenge des Rang-Bereichs und
  entspricht in Emerald+ etwa DraftGaps Standard.
- Auch bei „ab 0 Spiele“ braucht ein Vorschlag mindestens ein Spiel auf seiner Rolle.

**Datenbeschaffung**

- Champion-Winrates folgen dem gewählten Zeitraum, Matchups und Synergien kommen immer aus 30 Tagen.
- Jeder gewählte Champion kostet bis zu zwei Abrufe: seine Build-Seite und, je Team für höchstens vier Champions,
  die Team-Werte.
- Die Team-Werte stehen nicht in der Seite. Dafür nutzt das Tool die **nicht dokumentierte** Schnittstelle
  `a1.lolalytics.com/mega/`, die auch die Website im Browser verwendet. Es gelten dieselbe Drosselung und derselbe
  Cache.
- Schlägt eine Quelle fehl, erscheint ein Hinweis, ihr Beitrag fehlt und sie wird 10 Minuten lang nicht erneut
  angefragt. Ändert Lolalytics die Schnittstelle, fehlen die Synergien, bis der Client angepasst ist.

### Summoner-Tab (Riot-API)

**Eingabe:** Riot-ID (`Name#TAG`), Region und Anzahl der Matches (20, 50 oder 100). Ein API-Key in den Optionen ist
nötig, und die Riot-Abfragen müssen in den Einstellungen aktiviert sein (Standard: aus). Die Region ist standardmäßig
`EUW1`.

**Anzeige:** Ränge, meistgespielte Champions mit Winrate, KDA und CS/Min, Spielmodi, Rollen, Meisterschaft und
Match-Verlauf. Ein Klick auf einen Spielmodus filtert die Auswertung.

**Hinweise**

- Winrates beziehen sich auf die abgerufenen Matches, Remakes zählen nicht mit.
- Abgerufene Matches liegen gekürzt in `cache/_global/riot/match/` und kosten danach keine API-Anfrage mehr.
- Mit einem Dev-Key gelten 100 Anfragen in 2 Minuten, 100 neue Matches dauern dadurch etwas.

## Konfiguration

### Daten, Cache, Einstellungen

Alles liegt im Home-Verzeichnis des Tools, standardmäßig `~/.leaguetool` (Windows: `C:\Users\<name>\.leaguetool`).
Überschreibbar mit `-Dleaguetool.home=…` oder der Umgebungsvariable `LEAGUETOOL_HOME`.

| Pfad | Inhalt |
|---|---|
| `settings.properties` | Einstellungen (Sprache, Offline-Modus, Wiki/Riot/Lolalytics an/aus, Rang-Bereich, Plattform, optional API-Key) |
| `cache/<patch>/data/<sprache>/…` | Data-Dragon-JSON pro Patch (unveränderlich, cache-first) |
| `cache/<patch>/img/…`, `cache/_global/img/…` | Bilder (Champions, Items, Spells, Runen) |
| `cache/_global/ddragon/versions.json` | Patch-Liste (6 h frisch, sonst Netz mit Cache-Fallback) |
| `cache/_global/wiki/…` | Wiki-Zusatzdaten (3 Tage frisch, Item-Daten 1 Tag, sonst Netz mit Cache-Fallback) |
| `cache/_global/lolalytics/…` | Lolalytics-Abrufe (12 h frisch, sonst Netz mit Cache-Fallback) |
| `cache/_global/riot/match/…` | Abgerufene Matches in gekürzter Form |
| `cache/_global/item-passives/` | Stand und Verlauf der automatischen Item-Passiv-Updates |
| `modern-ui/` | UI-Zustand (`ui-state.json`) und Browserprofil |

### Offline-Betrieb

Fehlt das Netz, wird der neueste vollständig gecachte Patch verwendet. Der Offline-Modus in den Einstellungen
erzwingt das.

### Riot-API-Key

Nur für den Summoner-Tab und die optionale Verifizierung nötig.

- Den Key in den Einstellungen eintragen oder die Umgebungsvariable `RIOT_API_KEY` setzen.
- Der Key wird nie ins Repository geschrieben, nur lokal in `settings.properties`.
- Kostenlose Dev-Keys laufen nach 24 h ab. Für dauerhafte Nutzung einen Personal-Key beantragen.

## Sicherheit

- Der Server hört nur auf `127.0.0.1` mit zufälligem Port.
- Er verlangt ein Sitzungs-Token als HttpOnly-Cookie und prüft Host und Origin.
- Der Riot-API-Key wird nie an das Frontend herausgegeben.

## Architektur

Pakete unter `com.leaguetool`:

| Paket | Aufgabe |
|---|---|
| `data.ddragon` | Data-Dragon-Client und -Parser (versions, champion, champion/{id}, item, runesReforged, summoner) |
| `data.wiki` | MediaWiki-Client (`wiki.leagueoflegends.com/en-us/api.php`): Base-Stats, Ability-Details |
| `data.riot` | Riot-API-Client (league-v4, summoner-v4, account-v1, match-v5), Rate-Limiter, Verifizierung |
| `data.http` | HTTP-Abstraktion (testbar mit Fakes), Offline-Schalter |
| `data.lcu` | Lesender Abgleich mit der Champion-Auswahl des League-Clients |
| `cache` | Datei-Cache pro Patch-Version |
| `model` | Records: Champion, Ability, Item, RuneTree/Rune, SummonerSpell, … |
| `calc` | Stats nach Level, Item-Stat-Summen, Cooldowns mit Ability Haste, Schadensvorschau |
| `draft` | Draft-Bewertung und Vorschläge, Rollen-Vorhersage, Datenbeschaffung aus Lolalytics |
| `stats` | Datenformen der Statistik-Anzeige (Ranglisten, Wilson-Intervall) und Item-Einordnung |
| `web` | Oberfläche: lokaler Server und JSON-API (Frontend in `src/main/resources/com/leaguetool/web`) |
| `config`, `util` | Einstellungen, Text-/Patch-Hilfen |

## Bekannte Einschränkungen

### Rechner und Schadensvorschau

- **Werte mit Build / Schadensvorschau** rechnen immer aktive Item-Effekte ein (z. B. Rabadons +30 % AP, Riftmaker,
  Awe, adaptive Kraft von Swiftmarch, Haste nur für bestimmte Fähigkeiten).
- **Nicht berücksichtigt:** bedingte Passiven und Stacks, Champion-Passiven, Runen und gegnerische Resistenzen.
  Ratios auf Zielwerte (z. B. „% des maximalen Lebens des Ziels“) werden nicht eingerechnet.
- Ob eine Fähigkeit immobilisiert (Imperial Mandate), wird aus ihrer Wiki-Beschreibung gelesen.
- Angriffstempo-Obergrenze im Rechner: 3.003 (seit Patch V25.S1.3, laut Wiki).

### Item-Effekte je Patch

Bei jedem Laden prüft `ItemPassiveUpdater` die Passiv-Texte aus Data Dragon und `Module:ItemData/data` des Wikis
(bei neuem Patch sofort, sonst täglich) und übernimmt Änderungen automatisch. Bei Abweichungen gilt Data Dragon.

- Neue Items mit bekanntem Satzbau kommen von selbst dazu.
- Völlig neue Effektarten kann das Programm nicht selbst berechnen: Sie erscheinen als „nicht auswertbar“ unter
  „Werte mit Build“.

### Datenquellen

- **Wiki-Leveling:** Werte, die mit dem Champion-Level skalieren (`{{pp}}`), Formeln mit Champion-Daten (`{{ccd}}`)
  und Ratios ohne Prozentangabe („+ 0.5 per 100 AP“) erscheinen nur als Text.
- **Item-Werte** werden aus der englischen Item-Beschreibung gelesen. Bei anderen Sprachen greift der
  (unvollständige) Data-Dragon-Fallback.
