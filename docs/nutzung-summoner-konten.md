# RiftLens – Summoner-Tab: eigene Konten

Ergänzt [`nutzung-summoner.md`](nutzung-summoner.md). Eigene Konten werden nur lokal gespeichert (`settings.properties`).

## Meine Konten

„Als mein Konto festlegen“ merkt sich das abgefragte Konto lokal (`settings.properties`). Mehrere
Konten sind möglich (bis zu 10, z. B. Haupt- und Zweitkonto): einfach jedes Konto abfragen und festlegen. Sie stehen dann
als erste Schnellzugriffe unter der Suche, das Live-Spiel hat je Konto einen Knopf, und der Draft-Tab zeigt in der Karte
„Deine Bilanz“ die Spiele aller eigenen Konten zusammen. „Nicht mehr mein Konto“ entfernt das angezeigte Konto wieder.

## Alle meine Konten

Ab zwei eigenen Konten steht unter der Suche der Knopf „Alle meine Konten“. Er zeigt Übersicht,
Champions, Spielstil und Mastery Points über alle eigenen Konten zusammen: Matches und Mitspieler aus den gespeicherten
Spielen (auch ohne API-Key), jedes Match mit seinem Konto. Mit aktivierter Riot-API kommen die Ränge je Konto und die
Meisterschaft dazu (Punkte zusammengezählt, Stufe vom stärksten Konto). Neue Spiele erscheinen erst, wenn ein Konto
einzeln abgefragt wurde; die Kontonamen oben führen direkt dorthin. Die Auswahl bleibt wie eine normale Suche nach einem
Neustart erhalten. Das Live-Spiel gibt es nur je Konto.

## Deine Bilanz im Draft

Mit festgelegtem eigenen Konto zeigt der Draft-Tab deine Bilanz gegen deinen direkten Lane-Gegner an zwei Stellen:

- **Am Platz des Gegners** auf deiner Rolle steht unter dem Namen „Deine Bilanz 5:7“; der Tooltip nennt deine drei
  häufigsten Champions gegen ihn. Deine Rolle kommt aus dem Client-Abgleich (dein Platz) oder, ohne ihn, aus der unter
  „Beste Picks“ gewählten Rolle fürs eigene Team.
- **In „Beste Picks“** (nur für dein Team) zeigt jeder Vorschlag „Du 4:1“, deine Bilanz mit diesem Champion gegen den
  Gegner auf seiner Rolle; die Tabelle „Weitere Picks“ hat dafür die sortierbare Spalte „Du“. Ist deine Rolle bekannt,
  gilt das nur für sie.

Die Werte stammen nur aus den gespeicherten
Matches deines Kontos (neue Spiele kommen mit der nächsten Abfrage im Summoner-Tab dazu); der Draft fragt dafür weder
Riot noch den League-Client ab und zeigt keine Daten anderer Spieler.
