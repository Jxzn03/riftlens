# RiftLens – Draft-Archiv

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
**Ergebnis:** Neben dem Patch steht ein Knopf, grün „Win“ für Sieg, rot „Loss“ für Niederlage (aus Sicht von „Dein Team“),
ausgegraut „Offen“ ohne Ergebnis. Ein Klick wechselt Offen → Win → Loss → Offen. Das Ergebnis trägt man
von Hand ein, der League-Client liefert es nicht. Gehört der Draft zu einem Fearless-Game, zeigt der Knopf dessen
Ergebnis; geändert wird es dann am Game unter „Fearless“, wo es ebenfalls neben dem Patch steht.
Rollen lassen sich im Nachhinein korrigieren: Ändert man an einem geladenen Draft (aus „Drafts“ oder ein Game unter
„Fearless“) die Rollen, wird „Rollen speichern“ neben „Draft leeren“ aktiv; ein Klick überschreibt den gespeicherten
Draft mit den Rollen und der Reihenfolge vom Brett. Der gleiche Draft auf der anderen Seite (Fearless-Game bzw. Eintrag
unter „Drafts“) bekommt die Rollen mit. Fehlt ein Fearless-Game unter „Drafts“ ganz (auch nicht im Papierkorb),
legt das Speichern es dort neu an. Das gilt nur, solange jede Seite genau dieselben Champions hat. Mit anderen
Champions bleibt der gespeicherte Draft unverändert, und es gilt die normale Speicherung.

**Fearless-Serien:** siehe [`nutzung-fearless.md`](nutzung-fearless.md).
