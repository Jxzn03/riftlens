# RiftLens – Summoner-Tab (Riot-API)

Ergänzt [`nutzung.md`](nutzung.md). Alle Daten kommen über die offizielle Riot-API; der League-Client wird dafür nicht
abgefragt. Aufbau und Kennzahlen orientieren sich an op.gg, gerechnet wird aber selbst aus den abgerufenen Matches.

**Eingabe:** Riot-ID (`Name#TAG`), Region und Anzahl der Matches (20, 50, 100, 200, 300, 500 oder 1000; Remakes werden durch ältere Spiele ersetzt, damit so viele gewertete Spiele zusammenkommen). Ein API-Key in den
Optionen ist nötig, und die Riot-Abfragen müssen in den Einstellungen aktiviert sein (Standard: aus). Die Region ist
standardmäßig `EUW1`. Schlägt eine Abfrage fehl, steht unter dem Fehler eine Anleitung für einen neuen Dev-Key.
Der zuletzt gesuchte Summoner wird mit Matchanzahl und Auswertungsfenster gespeichert (`ui-state.json`) und nach
einem Neustart beim ersten Öffnen des Tabs einmal neu abgefragt; bereits geladene Matches kommen dabei aus dem Cache.

**Eigene Konten** (Haupt- und Zweitkonten, „Alle meine Konten“, deine Bilanz im Draft):
[`nutzung-summoner-konten.md`](nutzung-summoner-konten.md).

## Bereiche

Alle Tabellen lassen sich per Klick auf einen Spaltenkopf sortieren (zweiter Klick dreht die Richtung, dritter stellt die ursprüngliche Reihenfolge wieder her); die Champion-Tabellen zeigen vorne die Platznummer. Bei „Meine Champions“ sind die Matchups des Champions auf Platz 1 aufgeklappt, bis du selbst auf- oder zuklappst.

Ein Klick auf einen Spielmodus filtert Übersicht, Champions und Spielstil; mehrere Modi lassen sich zusammen wählen und
werden addiert, „Alle“ hebt die Auswahl auf. „Summoner's Rift“ fasst alle Spiele mit direktem Lane-Gegner zusammen,
egal in welcher Queue (Ranked, Normal, Swiftplay, Custom Games …). Danach folgen Modi ohne Lane (ARURF, Arena,
ARAM …) und dann die einzelnen Summoner's-Rift-Modi. „Summoner's Rift“ schaltet diese Rift-Modi mit an und beim
erneuten Klick wieder aus; wird danach ein einzelner Rift-Modus abgewählt, verliert „Summoner's Rift“ seine Markierung. Rechts daneben begrenzt „Auswertung“ die
Rechnung auf die letzten 10, 20, 30, 50 … Spiele dieser Auswahl (z. B. 50 abgerufen, nur die letzten 20 ausgewertet).
Angeboten werden nur Stufen unter der vorhandenen Spielzahl, Remakes zählen nicht mit. Die Einstellung bleibt für weitere
Abfragen erhalten; abgerufen und gespeichert werden weiter alle Matches, die Karte „Spielmodi“ zählt alle.

- **Übersicht:** Ränge, meistgespielte Champions mit Anteil an den Spielen, Winrate, KDA und CS/Min, häufige Mitspieler, Spielmodi, Rollen, die
  sieben höchsten Mastery Points (aufklappen: erst 20 weitere, dann alle) und der Match-Verlauf (zuerst 50 Spiele, „weitere anzeigen“ und „Alle anzeigen“ laden den
  Rest).
- **Champions:** „Meine Champions“ und „Gegnerische Champions“ wie bei op.gg mit Gespielt (Siege/Niederlagen, Winrate),
  KDA mit Kill-Beteiligung, Schaden
  pro Minute und Schadensanteil, Wards (Vision Score, Control Wards, platziert/zerstört), CS, Gold und Mehrfachkills. Der
  Pfeil vor einem Champion klappt die Matchups auf: deine Bilanz gegen jeden Lane-Gegner (z. B. Talon gegen Taliyah 0:2)
  bzw. bei einem Gegner-Champion deine Champions gegen ihn. Lane-Gegner ist der Spieler des anderen Teams auf derselben
  Position; ARAM, Arena, URF und ähnliche Modi haben keinen, deshalb steht bei „Gegnerische Champions“, in wie vielen
  Spielen ein Lane-Gegner bekannt ist; aufgeklappt steht je Champion, wie viele seiner Spiele ohne Lane-Gegner waren
  (z. B. „Arena 7, ARURF 4“). Die OP-Wertung ist ein eigenes Verfahren von op.gg und fehlt.
- **Empfohlene Champions** (Bereich Champions, dritter Knopf neben „Meine“ und „Gegnerische Champions“): kaum gespielte Champions deiner Haupt- und Nebenposition, gewichtet nach
  Stärke laut Lolalytics, Nähe zu deinen Champions (ähnliche Klassen wie die, die du viel und erfolgreich spielst), Meisterschaft und Bann-Druck; höchstens drei derselben Klasse, mit
  den Kennzeichen „Haupt-/Nebenposition passt“, „Spielstil passt“, „Neu für dich“ bzw. „Schon vertraut“, „Starker Pick #n“ und „Geringer
  Bann-Druck“. Genutzt werden nur Lolalytics-Tierlisten, die schon im Cache liegen; fehlt die Liste deiner Hauptposition,
  steht dort ein Hinweis (einmal im Champions-Tab den Rollenfilter wählen). Bei einem eigenen Konto zählen die gespeicherten Spiele deiner
  übrigen Konten mit (halb gewichtet, ohne neue Abfrage): Was du auf dem Zweitkonto spielst, gilt nicht als neu. Bei
  fremden Spielern zählen nur deren eigene Spiele.
  Dazu kommen drei **empfohlene Bans** (rot, „B“): starke Lane-Gegner deiner Hauptposition laut Lolalytics, verstärkt
  durch deine Bilanz gegen sie (z. B. „Deine Bilanz 0:4“) und ihre Bannrate; mehr Spiele mit klar negativer Bilanz wiegen schwerer (3:8 vor 0:3).
  Gegner mit negativer Bilanz erscheinen nie als Pick, Bans nie unter den Picks, eigene
  Champions nie als Bann. Neun Picks und drei Bans füllen zwei Reihen.
- **Spielstil:** Spiele, Winrate, KDA, Kill-Beteiligung, Gesamtspielzeit und Ø Spiellänge; Vergleich der letzten 20 Spiele
  mit der ganzen Auswahl; blaue und rote Seite; Spiele je Wochentag (Balkenhöhe, Farbe = Winrate); Spiele je Stunde (drei Stunden um die aktuelle
  Uhrzeit und alle 24 Stunden als Farbstreifen); Champion-Pool und -Fokus (Anteil der drei meistgespielten) und
  Leistungsmetriken (Schaden, Gold, CS und Sicht pro Minute, Control Wards, Solo-Kills, Mehrfachkills).
- **Mastery Points:** die sieben höchsten Champions (aufklappen zeigt alle; bei Suche alle Treffer) des Kontos mit Stufe, Punkten, Fortschritt zur nächsten Stufe, Noten der laufenden
  Saison-Meilensteine und zuletzt gespielt; sortierbar nach Punkten (Standard, wie op.gg), Stufe, zuletzt gespielt oder
  Name, mit Suche.
- **Live-Spiel:** Beim Wechsel auf diesen Bereich wird das laufende Spiel des gerade angezeigten Summoners einmal
  abgefragt, danach nur per Knopf („… neu abfragen“, je eigenem Konto oder eine andere Riot-ID). Es gibt kein
  regelmäßiges Nachladen.
  Angezeigt werden je Team Name, Champion, Beschwörerzauber, Rang (Solo/Duo mit LP und Saison-Winrate, Flex) und die
  Banns. Die Champion-Winrate steht beim eigenen Konto sofort da (aus den gespeicherten Matches), bei anderen Spielern
  lädt „Champion-WR laden“ deren letzte 20 Ranked-Spiele. Spiele gegen KI liefert die Riot-API nicht.

**Häufige Mitspieler (Duo):** Riot meldet keine Premades. Als Mitspieler gilt, wer in der Auswahl mindestens dreimal im
eigenen Team war (Arena: im selben Zweierteam). Angezeigt werden Spiele und Winrate zusammen; die eigene Winrate in den
übrigen Spielen und die Differenz stehen im Hinweis über der Winrate. Ein Klick auf den Namen fragt diesen Spieler ab.

## Hinweise

- Winrates beziehen sich auf die abgerufenen Matches, Remakes zählen nicht mit.
- Riot liefert höchstens 100 Match-IDs je Aufruf; größere Mengen werden seitenweise geholt.
- Je Spieler liegt eine gzip-Datei `cache/_global/riot/history/<puuid>.json.gz` mit der eigenen Zeile je Match, dem
  Lane-Gegner und den Teamkollegen (PUUID, Riot-ID), höchstens 1200 Matches je Spieler. Weitere Abfragen laden nur neue
  Spiele. Matches aus älteren Versionen ohne Lane-Gegner werden bei der nächsten Abfrage einmal neu geladen.
- Mit einem Dev- oder Personal-Key gelten 100 Anfragen in 2 Minuten. Der erste Abruf dauert dadurch bei 500 Matches
  etwa 10, bei 1000 etwa 20 Minuten; für 1000 erscheint ein eigener Warnhinweis. Die Anzeige schätzt die Restzeit.
  Zwischendurch wird gespeichert, ein Abbruch verliert nichts. Riot hält Matches nur etwa zwei Jahre vor.
- Live-Spiel: etwa 12 Anfragen (Spiel und Rang je Spieler), die Champion-Winrate eines anderen Spielers bis zu 21.
