# RiftLens

Desktop-Tool für Windows rund um League of Legends: Draft-Analyse, Champion-Statistiken, Build-Rechner und
Summoner-Auswertung in einem Fenster.

## Installation

1. Unter **Releases** die Datei `RiftLens-<version>-win64.zip` herunterladen.
2. Die ZIP-Datei in einen beliebigen Ordner entpacken.
3. `RiftLens.exe` starten.

**Voraussetzungen:** Windows 10/11 (64 Bit), Microsoft Edge oder Google Chrome (die Oberfläche läuft als
App-Fenster) und eine Internetverbindung beim ersten Start. Java muss nicht installiert sein, eine Laufzeit ist
enthalten.

Windows SmartScreen kann beim ersten Start warnen, weil die Datei nicht signiert ist: *Weitere Informationen →
Trotzdem ausführen*.

**Update:** RiftLens sucht beim Start auf GitHub nach einer neuen Version (abschaltbar in den Einstellungen). Gibt es
eine, steht unten rechts in der Statusleiste „Update auf X.Y.Z“: Ein Klick lädt das Update, prüft die digitale
Signatur und den Inhalt, tauscht den Programmordner aus und startet RiftLens neu; startet die neue Version nicht, kommt
die alte zurück. Das klappt, wenn RiftLens in einem eigenen Ordner liegt (so, wie das ZIP ihn anlegt). Von Hand geht es
weiterhin: den alten Ordner durch den neuen ersetzen. Einstellungen und Cache bleiben in beiden Fällen erhalten.

## Funktionen

| Tab | Was er kann |
|---|---|
| **Draft** | Picks und Bans beider Teams eintragen, die fünf besten Picks je Situation, erwartete Winrate, Matchups, Synergien, Schadensverteilung und ein lokales Archiv vergangener Drafts. Optional liest RiftLens die Champion-Auswahl aus dem League-Client mit (standardmäßig aus), Fearless-Serien für Scrims und Bo3/Bo5. |
| **Champions** | Sortierbare Tabelle mit Winrate, Pickrate und Stärke je Rolle und Rang-Bereich, dazu Builds, Runen, Skill-Reihenfolge und Counters je Champion. |
| **Items, Runen, Abilities** | Build-Rechner: Werte nach Level mit Items und Runenseite, Cooldowns mit Ability Haste, Schadensvorschau der Fähigkeiten. |
| **Summoner** | Ränge, meistgespielte Champions, häufige Mitspieler, Rollen und Match-Verlauf über die Riot-API (bis 1000 Spiele). |

Schnellsuche über alles mit `Strg`+`K`. Helles und dunkles Design; Build, Filter und Layout bleiben gespeichert.

## Erste Schritte

- **Statistiken:** Oben Zeitraum (30 Tage oder letzter Patch) und Rang-Bereich wählen, Standard ist Emerald+.
- **Summoner-Tab:** Braucht einen eigenen Riot-API-Key. Den Key unter *Einstellungen* eintragen und die
  Riot-Abfragen dort aktivieren (standardmäßig aus). RiftLens enthält keinen eigenen Key.
- **Client-Abgleich:** Im Draft-Tab einschalten (standardmäßig aus). RiftLens liest nur die Champion-Auswahl, klickt
  nichts, liest keine Spielernamen und nichts im laufenden Spiel.

## Hinweise und Risiken

**Eigener Riot-API-Key**
- Einen Key gibt es kostenlos im [Riot Developer Portal](https://developer.riotgames.com/). Ein Development-Key läuft
  nach 24 Stunden ab; für die eigene, dauerhafte Nutzung lässt sich dort ein Personal-Key beantragen.
- Der Key gilt nur für dich: nicht weitergeben, nicht veröffentlichen. Er wird nur lokal gespeichert und nur an
  Riot gesendet.
- Es gelten Riots [Developer-Richtlinien](https://developer.riotgames.com/policies/general). Mit einem Dev- oder
  Personal-Key erlaubt Riot nur 100 Anfragen in 2 Minuten, große Abfragen dauern deshalb einige Minuten.

**Client-Abgleich**
- Die Schnittstelle des League-Clients wird von Riot für Drittanbieter-Apps nicht offiziell unterstützt und kann
  sich jederzeit ändern. Die Nutzung erfolgt auf eigenes Risiko.
- RiftLens nutzt sie nur lesend und nur für zwei Abfragen der Champion-Auswahl (Session und spielbare Champions).
  Es automatisiert nichts, deckt keine verborgenen Spieler auf, greift nicht auf Prozesse oder den Spielspeicher
  zu und liest nichts im laufenden Spiel. Das entspricht dem, was Riot bei Drittanbieter-Apps duldet; eine
  Garantie gegen Sperren kann trotzdem niemand geben.

**Statistiken**
- Die Statistiken stammen von Lolalytics und werden gedrosselt und zwischengespeichert abgerufen. RiftLens steht in
  keiner Verbindung zu Lolalytics; der Abruf lässt sich in den Einstellungen abschalten.

Die Software wird ohne Gewährleistung bereitgestellt (siehe [Lizenz](LICENSE)).

## Daten und Datenschutz

- Einstellungen, Cache und Draft-Archiv liegen unter `%USERPROFILE%\.leaguetool`. Ordner löschen = Zurücksetzen.
- Der Riot-API-Key bleibt lokal in `settings.properties`.
- Die Oberfläche läuft über einen Server nur auf `127.0.0.1`, von außen ist nichts erreichbar.
- Datenquellen: Riot Data Dragon, League of Legends Wiki, Lolalytics und die Riot-API. Abrufe werden gedrosselt
  und zwischengespeichert; ohne Netz arbeitet RiftLens mit dem Cache weiter.
- Update-Prüfung: beim Start eine Anfrage an die GitHub-API (`api.github.com`, nur das neueste Release dieses
  Repositorys), ohne Nutzerdaten. Abschaltbar in den Einstellungen, im Offline-Modus aus.

## Aus dem Quellcode bauen

Voraussetzung: JDK 21 oder neuer. Maven wird vom Wrapper selbst geladen.

```powershell
.\start-modern.cmd             # bauen und starten
.\start-modern.cmd --rebuild   # nach Änderungen neu bauen (App vorher schließen)
.\mvnw.cmd clean verify        # bauen und alle Tests ausführen
```

## Weitere Details

- [Bedienung im Detail](docs/nutzung.md): Statistiken, Stärke-Formel, Summoner-Tab, Startparameter
- [Items-Tab und Build-Rechner](docs/nutzung-items.md): Item-Liste, Hinzufügen per „+“ oder Ziehen, Schalter, Rolle
- [Draft-Tab im Detail](docs/nutzung-draft.md): Rechenmodell, Vorschläge, Client-Abgleich
- [Draft-Archiv und Fearless-Serien](docs/nutzung-archiv.md): Archiv, Papierkorb, Rollen korrigieren, Hard Fearless
- [Konfiguration und Grenzen](docs/konfiguration.md): Speicherorte, Sicherheit, Architektur, bekannte Einschränkungen

## Lizenz

[MIT](LICENSE). Das Draft-Rechenmodell folgt [DraftGap](https://github.com/vigovlugt/draftgap) (MIT).
Mitgelieferte Fremdsoftware (Jackson, htm, Preact, Java-Laufzeit) und ihre Lizenzen:
[THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt), Lizenztexte in [`licenses/`](licenses/). Alles liegt auch im
Release-ZIP bei. Die Wiki-Testdaten unter `src/test/resources/fixtures/wiki/` stehen unter CC BY-SA 3.0.

RiftLens isn't endorsed by Riot Games and doesn't reflect the views or opinions of Riot Games or anyone officially
involved in producing or managing Riot Games properties. Riot Games, and all associated properties are trademarks
or registered trademarks of Riot Games, Inc. RiftLens steht in keiner Verbindung zu Lolalytics.
