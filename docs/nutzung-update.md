# RiftLens – Automatisches Update

Ergänzt [`nutzung.md`](nutzung.md).

## Anzeige
- Unten rechts in der Statusleiste steht die installierte Version, z. B. „RiftLens 2.0.1“. Ein Klick darauf sucht
  sofort nach einem Update.
- Nach der Suche steht daneben „aktuell“ oder ein hervorgehobener Knopf „Update auf X.Y.Z“. Während eines Updates
  zeigt die Statusleiste den Fortschritt (Download in Prozent, Prüfen, Neustart).
- Beim Start nach einem Update meldet RiftLens kurz, ob es geklappt hat.

## Ablauf
1. Klick auf „Update auf X.Y.Z“ (oder „Aktualisieren“ in den Einstellungen) und Rückfrage bestätigen.
2. RiftLens lädt `RiftLens-X.Y.Z-win64.zip` und die Signatur `RiftLens-X.Y.Z-win64.zip.sig` des neuesten
   GitHub-Releases. Es prüft die digitale Signatur (Ed25519, mit dem in RiftLens eingebauten Schlüssel des
   Entwicklers), Größe und Prüfsumme, den Aufbau des Pakets und die enthaltene Programmversion und entpackt es erst
   dann. Ein Paket ohne gültige Signatur wird nie installiert, auch wenn es auf der GitHub-Seite steht.
3. RiftLens schließt sein Fenster und beendet sich. Ein kleiner Updater (Windows PowerShell, unsichtbar) wartet darauf,
   prüft noch einmal, dass im Programmordner nur RiftLens liegt, benennt ihn in `<ordner>.old` um, setzt die neue
   Version an dieselbe Stelle und startet sie.
4. Sobald die neue Version hochgefahren ist, wird `<ordner>.old` gelöscht. Beendet sie sich gleich wieder, holt der
   Updater die alte Version zurück und startet diese. Läuft sie, meldet sich aber nicht (z. B. Fehlermeldung beim
   Start), bleibt `<ordner>.old` als Sicherung liegen.
5. Klappt der Austausch nicht (z. B. weil ein Explorer- oder Konsolenfenster im Programmordner offen ist), bleibt die
   alte Version und wird neu gestartet. Details stehen in `%USERPROFILE%\.leaguetool\update\update.log`.

Einstellungen, Cache, Draft-Archiv und Fearless-Serien liegen in `%USERPROFILE%\.leaguetool` und bleiben unberührt.
Ein vorhandener Ordner `<ordner>.old`, der nicht von RiftLens stammt, wird nicht angefasst.

## Einstellungen
- „Beim Start nach Updates suchen (GitHub-Releases)“: Standard an. Aus = keine Anfrage beim Start.
- „Jetzt nach Updates suchen“: Suche von Hand, auch wenn die Suche beim Start aus ist.
- Im Offline-Modus wird nicht gesucht und nichts geladen.

## Wann nur ein Link erscheint
Statt des Knopfs erscheint „X.Y.Z auf GitHub“ (öffnet die Release-Seite, der Grund steht im Hinweis), wenn
- RiftLens nicht aus der entpackten `RiftLens.exe` läuft (z. B. aus dem Quellcode mit `start-modern.cmd`),
- im Programmordner noch andere Dateien liegen, z. B. weil der Inhalt des ZIPs direkt auf den Desktop entpackt wurde.
  Das Update ersetzt den ganzen Ordner und würde sie löschen. Abhilfe: RiftLens in einen eigenen Ordner legen,
- im Ordner über dem Programmordner keine Schreibrechte bestehen (z. B. unter `C:\Program Files`),
- das Release kein passendes Windows-Paket oder keine Signatur enthält.

Dann wie gewohnt von Hand aktualisieren: ZIP laden und den alten Ordner ersetzen.
