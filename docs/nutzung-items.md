# RiftLens – Items-Tab und Build-Rechner

Ergänzt [`nutzung.md`](nutzung.md).

## Item-Liste

Die Suche findet Items nach Name und Kategorie (z. B. „boots“), ohne auf Groß- und Kleinschreibung zu achten.
„Nur fertige Items“ blendet Boots, Support-Items und Jungle-Pets aus. Die Spalten „Goldwert“ und „Effizienz“ zeigen den
Goldwert der Stats und die Goldeffizienz (siehe unten; grün ab 100 %). Eine eigene Kategorien-Spalte gibt es nicht,
sortieren lässt sich nach Kategorie über den Knopf „Kategorie“ (erneut klicken kehrt die Richtung um). Die Spalte
„Stats“ zeigt die Werte eines Items
getrennt von den Kategorien. Sie lässt sich nach einem Stat filtern und sortieren, mit Filter nach dessen Wert, sonst
nach der Anzahl der Stats. Die Stat-Namen folgen der eingestellten Data-Dragon-Sprache.

## Build-Rechner

Items kommen auf drei Wegen in den Build: über „+“ auf einem leeren Platz (Auswahlfenster mit Suche nach Name oder
Kategorie, Enter nimmt das erste passende Item), per Ziehen einer Zeile aus der Item-Liste auf einen Platz oder im
Detail über „Zum Build hinzufügen“. Ein Klick auf ein Item im Build zeigt dessen Details. Die Items lassen sich per
Ziehen umordnen, und wer eines auf die Item-Liste zieht, entfernt es aus dem Build.

Boots, Support-Item, Jungle-Pet und jedes fertige Item passen je nur einmal in den Build (Komponenten auch mehrfach),
Trinkets und Verbrauchsgegenstände gar nicht; solche Items sind im Auswahlfenster und bei „Zum Build hinzufügen“
ausgegraut und lassen sich nicht hineinziehen. Die Rolle (Feld neben der Champion-Auswahl, siehe unten) stellt den
Build-Rechner ein; Top ändert nichts:

- **ADC:** 6 normale Items, Boots liegen auf einem festen 7. Platz und zählen in allen Berechnungen mit. „+“ auf dem
  leeren Boots-Platz öffnet die Auswahl mit den Boots.
- **Mid:** Boots werden zur aufgewerteten Variante, bei einer anderen Rolle wieder zur normalen. Aufgewertete Boots
  lassen sich nur mit Mid hinzufügen.
- **Support:** World Atlas und Runic Compass werden zu Bounty of Worlds, bei einer anderen Rolle wieder zu World Atlas;
  eine gewählte Endstufe bleibt. Ohne Champion stellt ein Support-Item die Rolle von selbst auf Support.
- **Jungle:** Nur dann lässt sich ein Jungle-Pet (Scorchclaw Pup, Gustwalker Hatchling, Mosstomper Seedling) in den
  Build legen, höchstens eins. Es kommt nie von selbst hinein, weil es sich im Spielverlauf auflöst und den Platz
  freigibt. Bei einer anderen Rolle wird das Pet entfernt.

## Champion und Rolle

Über dem Build lässt sich der Champion wählen, auf den der Build angewendet wird, daneben die Rolle. Mit Champion gilt
„Auto“ (die häufigste Rolle aus bereits geladener, notfalls älterer Statistik; es wird dafür nichts abgerufen) oder
eine von Hand gewählte Rolle, die auch im Champions-Tab gilt. Bleibt die Rolle unbekannt, wirkt sie wie Top. Ohne
Champion wählt das Feld die Rolle direkt („Top / keine Rolle“, Jungle, Mid, ADC, Support). „In Build-Rechner“ bei
einer Runenseite im Champions-Tab übernimmt auch den Champion, „Champion entfernen“ neben „Leeren“ setzt ihn zurück;
beide Knöpfe und die Auswahl „Kein Champion“ stellen die Rolle auf „Top / keine Rolle“. Mit Champion lassen sich
Support-Items nur in der Rolle Support hinzufügen, bei einer anderen Rolle werden sie entfernt.

## Baupfad, Goldwert und Goldeffizienz

Im Detail eines Items zeigt „Baut aus“ den Baupfad wie im Spiel als Baum von oben nach unten bis zu den Basis-Items, je Item mit Gesamtpreis (Name und
Kombinationskosten im Tooltip, Klick auf eine Komponente öffnet sie). Darunter stehen der Goldwert der Stats und die Goldeffizienz
(Goldwert ÷ Preis, Passiven nicht eingerechnet). Der Goldwert je Stat-Einheit wird wie im League-Wiki aus den
Komponenten abgeleitet: aus dem billigsten Basis-Item mit nur diesem Stat (z. B. Long Sword für Angriffsschaden),
sonst per Abzug aus dem billigsten gebauten Item, bei dem nur dieser Stat fehlt; mit „Show Advanced Tooltips“ nennt ein Tooltip die Quelle. Stats
ohne solche Komponente (z. B. kritischer Schaden) bleiben unbewertet. Die Werte folgen so jedem Patch ohne Abruf.

Immer aktive Passiven, die sich in Stats umrechnen lassen, zählen zusätzlich als „Goldwert inkl. Passiven“, unter der
Annahme, dass man nur dieses Item besitzt, wie das Wiki bei Rabadon's Deathcap (130 AP × 30 % = +39 AP, 96,57 %).
Bezug sind die Stats des Items selbst (z. B. Warmog's: Leben des Items, Awe: Mana des Items, Endless Hunger: Wert für
Nahkämpfer). Die Spalten „Goldwert“ und „Effizienz“ der Liste enthalten diese Passiven. Passiven mit Bedingungen,
Stapeln, Champion-Bezug (Sterak's, Swiftmarch) oder Haste nur für einzelne Fähigkeiten bleiben unbewertet.

Unter „Werte und Beschreibung“ steht bei jeder Passive und Aktive die Abklingzeit laut League-Wiki (Feld `cd` von
`Module:ItemData` oder „… second cooldown“ im Wiki-Text), z. B. Spellblade 1,5 s. Ohne Wiki-Zugriff fehlt sie.
Leere Platzhalter aus Data Dragon wie „Lifeline (0s)“, „ACTIVE (0s)“ oder „Item Health (0)“ werden ausgeblendet und
die Abklingzeit aus dem Wiki eingesetzt (bei Heartsteel „30 s je Ziel“).
