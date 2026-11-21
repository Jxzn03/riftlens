# RiftLens – Draft-Tab im Detail

Ergänzt die [README](../README.md) und [`nutzung.md`](nutzung.md).


**Bedienung:** Picks beider Teams und Bans eintragen. Ohne feste Rolle sagt das Tool die wahrscheinlichste Verteilung
voraus. Für jede Situation stehen die **fünf besten Picks** hervorgehoben oben, wahlweise für dein Team oder den Gegner
und je Rolle gefiltert. Ein Klick übernimmt den Pick. Beim Start der App und bei jedem Wechsel auf „Beste Picks“ ist
„Für mein Team“ gewählt; zum Gegner wechselt die Ansicht nur von selbst, wenn du einen Gegner-Pick entfernst (am Brett
oder per Klick im Archiv).

**Aufbau:** Unter den Bans stehen zwei Kacheln für die Modi **Fearless-Serie** und **League-Client**; ein Klick schaltet
sie ein oder aus, darunter steht ihr aktueller Stand. Darunter rechts neben „Draft leeren“ stehen die **Mindest-Pickrate** (mit „Seltene
Picks zeigen“ stattdessen die Mindestspiele, „Games“) zum schnellen Nachstellen und **Optionen** mit den Schaltern
„Nur Synergien und Matchups“ und „Seltene Picks zeigen“. Ist einer davon an, zeigt der Knopf ihre Anzahl, und über der
Vorschlagsliste steht er als Tag; ✕ schaltet ihn wieder aus. Zeitraum und Rang-Bereich stellst du wie in den anderen Tabs oben in der Kopfleiste ein.

**Favoriten:** In den **Optionen** stehen alle Favoriten (Stern im Champions-Tab oder „+“ hier). Ein Klick auf ein Icon
öffnet darunter die Rollen (mehrere wählbar, z. B. Top und Mid), die Schalter **Üben** und **Off-Meta** (unkonventioneller
Pick, etwa Off-Role) und „Entfernen“. Ein Favorit mit
Rollen erscheint bei den Vorschlägen „Für mein Team“ nur auf diesen Rollen; ohne Rolle auf jeder. Die Buchstaben am Icon
zeigen die Rollen (T, J, M, A, S). Favoriten zum Üben haben einen kräftig gestrichelten goldenen Rahmen, einen hohlen
Stern und das Schild „Üben“; Off-Meta-Favoriten sind violett unterlegt mit dem Schild „Off-Meta“, beides zusammen
violett gestrichelt. **Nur Favoriten** zeigt bei „Für mein Team“ nur Favoriten (auch die zum Üben und Off-Meta) auf
ihren Rollen; die Champion-Auswahl beim Klick auf einen Platz bleibt vollständig. Favoriten unter der Pickrate-Grenze
fehlen weiterhin (bei Off-Meta-Picks häufig), dann hilft eine niedrigere Pickrate oder „Seltene Picks zeigen“. Rollen,
Üben und Off-Meta werden nur lokal mit den Favoriten gespeichert.

**Marvins-Liste (besser nicht spielen):** In den **Optionen** steht unter den Schaltern die Marvins-Liste. „+“ öffnet die
Champion-Auswahl (bleibt offen für mehrere), ein Klick auf ein Icon nimmt den Champion wieder heraus, „Marvins-Liste leeren“
entfernt alle. Diese Champions sind im Draft rot unterlegt und mit „Besser nicht spielen“ markiert: im eigenen Team, in
der Auswahl für einen eigenen Pick und bei den Vorschlägen „Für mein Team“. Beim Gegner, bei Bans und bei „Für den
Gegner“ bleibt die Markierung weg. Sie ändert nur die Anzeige, nicht die Bewertung oder Reihenfolge. Gespeichert wird
die Liste nur lokal im UI-Zustand, wie die Favoriten.

**Draft-Analyse:** erwartete Winrate, Gesamteinschätzung, alle Matchups und Synergien, Schadensverteilung und Vorsprung
nach Spieldauer.

**Archiv:** siehe [`nutzung-archiv.md`](nutzung-archiv.md) (Aufnahme, Papierkorb, Rollen nachträglich korrigieren,
Ergebnis). **Fearless-Serien:** siehe [`nutzung-fearless.md`](nutzung-fearless.md) (Hard Fearless).

**Abgleich mit dem League-Client** (Kachel „League-Client“ im Draft-Tab, Standard aus, sein Stand bleibt über Neustarts erhalten;
ausgeschaltet wird der Client nie abgefragt): Während der Champion-Auswahl übernimmt das
Tool gesperrte Picks, Bans und eure zugewiesenen Rollen automatisch, wie DraftGap. Ansagen von Mitspielern erscheinen
als Hinweis. „Nur spielbare Champions“ zeigt nur Vorschläge, die dein Account in dieser Auswahl spielen darf.
Der Abgleich liest nur, und zwar ausschließlich die Champion-Auswahl. Er klickt nichts im Client, übernimmt keine
Spielernamen und liest nichts im laufenden Spiel. Den League-Ordner findet das Tool über Riots eigene Dateien, auch auf
anderen Laufwerken; liegt League ungewöhnlich, lässt er sich in den Optionen eintragen. Riot unterstützt die
Schnittstelle des League-Clients nicht offiziell für Drittanbieter-Apps; die Nutzung erfolgt auf eigenes Risiko.

**Rechenmodell**

Es folgt [DraftGap](https://github.com/vigovlugt/draftgap) (MIT-Lizenz): Winrates werden zu Rating-Punkten, Champions,
Synergien (Duos) und Matchups addieren sich. Kleine Stichproben werden zur Erwartung hin geglättet. Abweichungen:

- Die Glättung richtet sich je Rollenpaar nach der gemessenen Streuung echter Effekte. Lane-Matchups und das
  Botlane-Duo zählen schon bei wenigen Spielen, Duos über die Map erst bei sehr vielen. Einen Regler „Risiko“
  gibt es nicht: Messungen zeigten, dass jede andere Stärke der Glättung die Schätzung verschlechtert.
- Die Champion-Winrate wird auch bei „30 Tage“ zum Durchschnitt hin geglättet. Bei „letzter Patch“ wird sie so stark
  zum 30-Tage-Wert gezogen, wie sich Champions zwischen Patches tatsächlich verschieben (aus den Daten geschätzt).

**Bewertung der Vorschläge**

- „Bester Pick“ heißt: höchste erwartete Winrate mit den bisher bekannten Picks. Spätere Antworten des Gegners und
  die Beherrschung des Champions fließen nicht ein.
- Champions auf Nebenrollen können wegen ihrer spezialisierten Spieler hoch stehen. Dagegen hilft standardmäßig die
  Mindest-Pickrate; mit „Seltene Picks zeigen“ gilt stattdessen der Filter **Mindestspiele** (beides neben „Optionen“). Sein Standard **Auto** richtet sich nach der Spielmenge des Rang-Bereichs und
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

