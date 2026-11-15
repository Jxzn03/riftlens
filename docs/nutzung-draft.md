# RiftLens – Draft-Tab im Detail

Ergänzt die [README](../README.md) und [`nutzung.md`](nutzung.md).


**Bedienung:** Picks beider Teams und Bans eintragen. Ohne feste Rolle sagt das Tool die wahrscheinlichste Verteilung
voraus. Für jede Situation stehen die **fünf besten Picks** hervorgehoben oben, wahlweise für dein Team oder den Gegner
und je Rolle gefiltert. Ein Klick übernimmt den Pick.

**Draft-Analyse:** erwartete Winrate, Gesamteinschätzung, alle Matchups und Duos, Schadensverteilung und Vorsprung
nach Spieldauer.

**Archiv:** Vollständige Drafts (alle zehn Champions, 10 Sekunden unverändert) landen automatisch im Bereich „Archiv“,
standardmäßig die letzten 10, in den Einstellungen auch 25, 50 oder 100. Gespeichert wird nur lokal in
`draft-archive.json` und nur Champions, Rollen und Bans, keine Spielernamen. „Laden“ holt einen Draft zurück aufs
Brett; ein Klick auf einen Champion lädt ihn ohne diesen Pick und zeigt, was an seiner Stelle am besten gewesen wäre.
Weicht ein vollständiger Draft in weniger als 3 Champions von einem gespeicherten ab, gilt er als Variante und
verdrängt keine älteren Einträge.

**Abgleich mit dem League-Client** (Schalter im Draft-Tab, Standard aus, sein Stand bleibt über Neustarts erhalten;
ausgeschaltet wird der Client nie abgefragt): Während der Champion-Auswahl übernimmt das
Tool gesperrte Picks, Bans und eure zugewiesenen Rollen automatisch, wie DraftGap. Ansagen von Mitspielern erscheinen
als Hinweis. „Nur spielbare Champions“ zeigt nur Vorschläge, die dein Account in dieser Auswahl spielen darf.
Der Abgleich liest nur, und zwar ausschließlich die Champion-Auswahl. Er klickt nichts im Client, übernimmt keine
Spielernamen und liest nichts im laufenden Spiel. Den League-Ordner findet das Tool über Riots eigene Dateien, auch auf
anderen Laufwerken; liegt League ungewöhnlich, lässt er sich in den Optionen eintragen. Riot unterstützt die
Schnittstelle des League-Clients nicht offiziell für Drittanbieter-Apps; die Nutzung erfolgt auf eigenes Risiko.

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

