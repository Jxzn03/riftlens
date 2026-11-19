# RiftLens – Draft-Archiv und Fearless-Serien

Ergänzt [`nutzung-draft.md`](nutzung-draft.md) (Draft-Tab im Detail).

**Archiv:** Vollständige Drafts (alle zehn Champions, 10 Sekunden unverändert) landen automatisch im Bereich „Archiv“,
standardmäßig die letzten 100, in den Einstellungen auch 10, 25, 50, 250, 500 oder 1000 (die Liste zeigt sie in Schritten von 50). Gespeichert wird nur lokal in
`draft-archive.json` und nur Champions, Rollen und Bans, keine Spielernamen. „Laden“ holt einen Draft zurück aufs
Brett; ein Klick auf einen Champion lädt ihn ohne diesen Pick und zeigt, was an seiner Stelle am besten gewesen wäre.
Weicht ein vollständiger Draft in weniger als 3 Champions von einem gespeicherten ab, gilt er als Variante und
verdrängt keine älteren Einträge. Hat jeder der fünf Champions eines Teams eine Rolle, zeigt die Liste sie von Top bis Support, sonst in der
gespeicherten Reihenfolge. Zählen tun feste Rollen und die Auto-Rollen, die das Brett beim Speichern zeigte (nur bei
offenem Draft-Tab, ohne neue Abfrage; ältere Einträge haben sie nicht). Gelöschte Drafts landen im **Papierkorb** unter der Liste und lassen sich
7 Tage lang wiederherstellen; danach werden sie automatisch endgültig gelöscht.
Rollen lassen sich im Nachhinein korrigieren: Ändert man an einem geladenen Draft (aus „Drafts“ oder ein Game unter
„Fearless“) die Rollen, wird „Rollen speichern“ neben „Draft leeren“ aktiv; ein Klick überschreibt den gespeicherten
Draft mit den Rollen und der Reihenfolge vom Brett. Der gleiche Draft auf der anderen Seite (Fearless-Game bzw. Eintrag
unter „Drafts“) bekommt die Rollen mit. Fehlt ein Fearless-Game unter „Drafts“ ganz (auch nicht im Papierkorb),
legt das Speichern es dort neu an. Das gilt nur, solange jede Seite genau dieselben Champions hat. Mit anderen
Champions bleibt der gespeicherte Draft unverändert, und es gilt die normale Speicherung.

**Fearless-Serie** (Kachel im Draft-Tab unter den Bans, Standard aus; für Scrims oder nachgestellte Profispiele): Es gilt Hard
Fearless. Champions, die ein Team in einem früheren Game der Serie gepickt hat, sind danach für **beide** Teams
gesperrt. Bans werden nicht übertragen. Beim Einschalten wählst du das Format (Bo3, Bo5 oder Offen, bei Offen
dazu die Anzahl der Games, 1 bis 10) und optional eine Bezeichnung wie „Scrim Dienstag“; der aktuelle Draft ist Game 1. Ein Game zählt erst nach „Game abschließen“ (alle
zehn Champions nötig, Ergebnis optional). Danach ist das Brett leer, die gesperrten Champions stehen in der Fearless-Zeile unter dem
Brett (je Game eine Zeile, dein Team, dann der Gegner, jeweils Top bis Support nach den Rollen auf dem Brett; die Games stehen
bündig untereinander, Top unter Top), sind in der Auswahl ausgegraut und fehlen in den Vorschlägen. Das letzte Game lässt sich zurücknehmen. Eine Bo3 oder
Bo5 endet von selbst, sobald sie entschieden ist, eine offene Serie nach der gewählten Zahl an Games; Ausschalten beendet die Serie. Während einer Serie nimmt das Archiv
keine Zwischenstände auf; jedes abgeschlossene Game steht zusätzlich unter „Drafts“ (als Game der Serie gekennzeichnet, Games älterer Serien werden einmal nachgetragen). Gespeichert wird nur lokal in `fearless-series.json`, wie im Archiv nur Champions, Rollen, Bans und
die Bezeichnung. Der League-Client wird dafür nicht zusätzlich abgefragt.
Mit dem Client-Abgleich fragt der Tab nach dem Ende einer Champion-Auswahl mit vollem Brett, ob der Draft als nächstes
Game zählen soll; automatisch übernommen wird nichts (in der Finalisierung kann noch jemand dodgen). Wird im Client ein
gesperrter Champion gepickt, etwa im Ranked bei vergessener Serie, erscheint ein Hinweis mit „Serie beenden“. Läuft eine
Serie seit über 12 Stunden ohne neues Game, fragt der Tab einmal je Sitzung, ob sie noch aktuell ist.
Unter „Archiv › Fearless“ stehen laufende und beendete Serien mit ihren Games (einklappbar; offen sind anfangs die
laufende und die neueste) (beendete: standardmäßig die letzten 50, in den
Einstellungen auch 10, 25 oder 100; ihre Games bleiben als Drafts im Archiv). Wurde ein Draft schon vor dem Start der Serie archiviert, trägt er unter
„Drafts“ das Etikett „Bezeichnung · Game n (Fearless)“; ein Klick darauf springt zu diesem Game unter „Fearless“. „Laden“ und der Klick auf einen Champion funktionieren wie im Archiv, dazu gelten die
Sperren, die in diesem Game galten, egal ob das Game unter „Fearless“ oder sein Draft unter „Drafts“ geladen wird. Ein
geladenes Serien-Game zählt nie als neues Game: Die Fearless-Zeile zeigt statt „Game n abschließen“ „Game 2 geladen“
mit **Änderungen in Game 2 speichern** (etwa einen falsch eingetragenen Pick austauschen; Champions, Rollen und Bans
werden überschrieben, Ergebnis und Datum bleiben, der gleiche Draft unter „Drafts“ ändert sich mit) und **Schließen**
(leert das Brett, danach geht die laufende Serie weiter). Champions aus anderen Games der Serie, auch späteren, sind
dabei nicht erlaubt. Das gibt es nur für Games einer Fearless-Serie, nicht für normale Drafts. Die zuletzt beendete Serie lässt sich wieder öffnen, etwa nach einem falsch
eingetragenen Ergebnis; Bezeichnungen lassen sich jederzeit über den Stift ändern, bei offenen Serien dort auch die Anzahl der Games (nicht unter der Zahl schon gespielter; erreicht eine laufende Serie damit ihre Spielzahl, endet sie; eine volle, beendete Serie läuft mit mehr Games wieder, sofern keine andere läuft, eine von Hand vorzeitig beendete bleibt beendet). Eine laufende offene Serie mit mindestens einem Game lässt sich dort mit **Jetzt beenden** vorzeitig beenden: Die Anzahl der Games wird auf die gespielten gesetzt, mehr Games öffnen sie später wieder. Bei Bo3 und Bo5 gibt es das nicht. Das Ergebnis jedes Games lässt sich über die Auswahl
am Game nachträglich ändern. Ist eine beendete Serie danach nicht mehr entschieden (z. B. 2:0 wird im Best-of-3 zu 1:1),
läuft sie wieder und ein weiteres Game kann folgen; läuft schon eine andere Serie, bleibt sie beendet. Wird eine laufende
Serie durch die Änderung entschieden, endet sie. Bei verwechselter Reihenfolge verschieben die Pfeile an
einem Game es um eine Stelle; Ergebnisse wandern mit, die Sperren je Game folgen der neuen Reihenfolge. Der Papierkorb im Kopf einer Serie löscht sie, auch die laufende
(etwa versehentlich gestartet): Sie endet dabei, ohne Game wird sie einfach verworfen. Gelöschte Serien mit Games landen wie Drafts 7 Tage im Papierkorb unter der
Liste und lassen sich bis dahin wiederherstellen. Gleiche Drafts unter „Drafts“ gehen dabei mit in den Papierkorb und
kommen mit der Serie zurück; Games einer Serie nimmt das Archiv nicht ein zweites Mal als neue Drafts auf. Ein falsch zugeordnetes
Game löst das **x** vor den Pfeilen aus der Serie: Die übrigen Games rücken nach, seine Sperren fallen weg. Einen
Papierkorb für einzelne Games gibt es nicht. Der Draft bleibt unter „Drafts“ (fehlt er dort ganz, wird er neu angelegt)
und lässt sich dort löschen. Das einzige Game einer beendeten Serie entfernt man, indem man die Serie löscht.

Das **+** im Kopf einer Serie (neben dem Papierkorb) trägt ein Game aus „Drafts“ nach, etwa ein versehentlich
entferntes. Das geht bei laufenden und beendeten Serien, solange sie weniger Games haben, als ihr Format erlaubt
(Best-of-3: 3, Best-of-5: 5, Offen: die gewählte Anzahl, sonst 10); danach ist das + ausgegraut. Zur Wahl stehen Drafts, die zu keiner Serie
gehören und keinen dort schon gespielten Champion enthalten. Das Game kommt ans Ende, mit dem Speicherdatum des Drafts
und ohne Ergebnis; das Ergebnis lässt sich am Game eintragen, die Pfeile schieben es an die richtige Stelle. Macht es
eine laufende Serie voll, endet sie.
