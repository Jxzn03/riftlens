# RiftLens – Konfiguration, Sicherheit, Architektur und Grenzen

Ergänzt die [README](../README.md).

## Konfiguration

### Daten, Cache, Einstellungen

Alles liegt im Home-Verzeichnis des Tools, standardmäßig `~/.leaguetool` (Windows: `C:\Users\<name>\.leaguetool`).
Überschreibbar mit `-Dleaguetool.home=…` oder der Umgebungsvariable `LEAGUETOOL_HOME`.

**Patchwechsel:** Beim ersten Start nach einem neuen Patch erneuert die App alles Patch-Abhängige selbst und meldet das
kurz: Data-Dragon-Daten des neuen Patches, Item-Passiven, Runen- und Shard-Werte (abgeglichen und gespeichert), die
Wiki-Championdaten sofort, Wiki-Fähigkeiten und Lolalytics-Statistiken beim nächsten Aufruf. Spätere Starts mit
demselben Patch nutzen den gespeicherten Stand. Ohne Netz bleiben die alten Daten nutzbar. Die Lolalytics-Team-Werte
für die Draft-Synergien behalten ihre 12 Stunden Cache.

| Pfad | Inhalt |
|---|---|
| `settings.properties` | Einstellungen (Sprache, Offline-Modus, Wiki/Riot/Lolalytics an/aus, Rang-Bereich, Plattform, Größe von Draft-Archiv und Fearless-Serien, optional API-Key) |
| `cache/<patch>/data/<sprache>/…` | Data-Dragon-JSON pro Patch (unveränderlich, cache-first) |
| `cache/<patch>/img/…`, `cache/_global/img/…` | Bilder (Champions, Items, Spells, Runen) |
| `cache/_global/ddragon/versions.json` | Patch-Liste (beim Programmstart frisch geprüft, danach 6 h, sonst Netz mit Cache-Fallback) |
| `cache/_global/patch/state.json` | Zuletzt geladener Patch und Zeitpunkt des letzten Patchwechsels |
| `cache/_global/wiki/…` | Wiki-Zusatzdaten (3 Tage frisch, Item-Daten 1 Tag, höchstens bis zum Patchwechsel, sonst Netz mit Cache-Fallback) |
| `cache/_global/lolalytics/…` | Lolalytics-Abrufe (12 h frisch, Statistiken höchstens bis zum Patchwechsel, sonst Netz mit Cache-Fallback) |
| `cache/_global/rune-effects/` | Stand und Verlauf der automatisch abgeglichenen Runen- und Shard-Werte |
| `cache/_global/riot/history/…` | Match-Historie je Spieler (gzip, Summoner-Tab) |
| `cache/_global/riot/match/…` | Früherer Match-Cache; wird nur noch gelesen und in die Historie übernommen |
| `cache/_global/item-passives/` | Stand und Verlauf der automatischen Item-Passiv-Updates |
| `modern-ui/` | UI-Zustand (`ui-state.json`) und Browserprofil |
| `draft-archive.json` | Draft-Archiv (nur Champions, Rollen, Bans; Anzahl in den Einstellungen) |

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
| `stats` | Datenformen der Statistik-Anzeige (Ranglisten, Wilson-Intervall, Bayes-Ranking) und Item-Einordnung |
| `web` | Oberfläche: lokaler Server und JSON-API (Frontend in `src/main/resources/com/leaguetool/web`) |
| `config`, `util` | Einstellungen, Text-/Patch-Hilfen |

## Bekannte Einschränkungen

### Rechner und Schadensvorschau

- **Werte mit Build / Schadensvorschau** rechnen immer aktive Item-Effekte ein (z. B. Rabadons +30 % AP, Riftmaker,
  Awe, adaptive Kraft von Swiftmarch, Haste nur für bestimmte Fähigkeiten).
- **Runenseite:** Im Tab Runen unter „Runenseite (Build-Rechner)“ Primär- und Sekundärbaum, Runen und Shards wählen; eine
  Seite aus der Champion-Statistik lässt sich mit „In Build-Rechner“ übernehmen. Verrechnet werden Shards und Runen mit
  Werten (z. B. Transcendence, Cosmic Insight, Celerity); Stapel (Legend, Ultimate Hunter, Gathering Storm) und
  Bedingungen (Conditioning nach 12 Minuten, Absolute Focus) stellt man ein. Schaden von Keystones und Item Haste nicht.
- **Nicht berücksichtigt:** bedingte Item-Passiven und Stacks, Champion-Passiven und gegnerische Resistenzen.
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
