# Slimy

Ein Pixel-Slime, der in der Dynamic Island deines iPhones wohnt. Von hyprnode, [slime.hyprnode.de](https://slime.hyprnode.de).

<p align="center"><img src="Resources/Assets.xcassets/AppIcon.appiconset/icon.png" width="140" alt="Slimy App-Icon"></p>

## Was er kann

**In der Dynamic Island**
- Der Slime sitzt links in der Insel und bewegt sich. Rechts zeigt ein kleines Pixel-Emote, wie es ihm geht oder was er braucht.
- Freunde stehen daneben: in der großen Ansicht und auf dem Sperrbildschirm bis zu zwei, in der kleinen Insel der Freund, mit dem ihr gerade etwas teilt (Wiedersehen, Umarmung, Geschenk, Spielen, Verliebt, Gute Nacht).
- Gedrückt halten öffnet Sprechblase, Bedürfnisse und die Tasten Essen, Spielen, Baden und Kraulen. Sie funktionieren, ohne die App zu öffnen.
- Sperrbildschirm, StandBy und Apple Watch zeigen eine Karte. Im Kontrollzentrum gibt es „Slime holen“ und „Slime füttern“, beides auch für den Action Button.

**Der Slime**
- Fünf Bedürfnisse: Essen, Spaß, Energie, Sauber, Zuneigung. Er sinkt nie unter eine Ruhegrenze, auch wenn du wochenlang weg bist, und macht dir kein schlechtes Gewissen.
- Eigene Persönlichkeit (verspielt, verschmust, Feinschmecker, Schlafmütze, neugierig), die sich mit der Zeit verändert. Lieblingsessen und Lieblingsspiel findest du selbst heraus.
- Er wächst vom Baby über jung bis ausgewachsen, mit eigenen Sprites. 17 Animationen, rund 150 Sätze, die er nicht ständig wiederholt, und ein Tagebuch mit Erinnerungen.
- Feste Schlafenszeit (einstellbar). Umstellen der Uhr bringt ihn nicht durcheinander.

**Spielhalle:** zehn Spiele (Snack-Fänger, Slime-Sprung, Blasen-Platzer, Gedächtnisgarten, Slime-Rennen, Rhythmus-Hüpfer, Garten-Rätsel, Schatzsuche, Slime-Basketball, Farbenspiel), jedes mit Anleitung, Pause (auch automatisch beim Verlassen der App) und Rekord. Eine Runde zahlt genau einmal.

**Sammeln:** Münzen nur im Spiel verdient, kein Echtgeld. Hüte, Brillen, Schals, Möbel, Lebensräume (Zimmer, Garten, Strand, Weltraum, Kristallhöhle), Tages- und Wochenaufgaben, 24 Erfolge.

**Reaktionen aufs iPhone:** Akku, Ladekabel, Stromsparmodus, WLAN, mobile Daten, kein Internet, Flugmodus, Morgen, Wecker, volle Stunde. Solange die App offen ist, merkt Slimy das selbst; sonst über Kurzbefehle-Automationen mit „Slime reagieren“ (Anleitung in Einstellungen).

**Widgets:** Home-Bildschirm (klein, mittel) und Sperrbildschirm (rund, rechteckig, Zeile). Sie brauchen die App Group, siehe Installieren.

**Freunde** (braucht den Server in `server/`, siehe [HANDOVER.md](HANDOVER.md)): Code teilen, Anfrage annehmen, umarmen, besuchen (sein Slime kommt zu dir nach Hause), Essen schenken, Grüße schicken, ab 50 % küssen, bei 100 % verliebt. Herausforderungen auf demselben Spielfeld, ein gemeinsamer Wochengarten, Blockieren, Privatsphäre, Daten löschen. Ohne Netz wartet alles und wird später genau einmal gesendet. Bis der Server läuft, gibt es den Test-Freund Mochi nur auf dem eigenen iPhone.

**Reden** (braucht den Server): Der Slime antwortet kurz und passend zu seiner Laune. Der API-Schlüssel liegt nur auf dem Server.

## Installieren

1. Unter [Releases](https://github.com/ItzSimooon/slimy-ios/releases) die neueste `Slimy.ipa` laden. Sie enthält die App Group, die App und Widgets für den gemeinsamen Spielstand brauchen. Lehnt dein Werkzeug sie ab, nimm `Slimy-unsigned.ipa`: dann geht alles außer den Home- und Sperrbildschirm-Widgets.
2. Mit Sideloadly oder AltStore und deiner Apple-ID aufs iPhone bringen.
3. Auf dem iPhone unter Einstellungen → Allgemein → VPN & Geräteverwaltung der Apple-ID vertrauen. Falls gefragt, unter Datenschutz & Sicherheit den Entwicklermodus einschalten.
4. Slimy öffnen, das Ei ausbrüten, „In die Insel holen“ tippen.

Mit einer kostenlosen Apple-ID muss die App alle 7 Tage neu signiert werden.

## Den ganzen Tag in der Insel

iOS beendet jede Live-Aktivität nach etwa 8 Stunden. Slimy holt den Slime zurück:
- jedes Mal, wenn du die App öffnest,
- mit „Slime holen“ im Kontrollzentrum oder auf dem Action Button,
- mit Kurzbefehle-Automationen nach Tageszeit (zum Beispiel 08:00, 15:00, 22:00),
- per Hintergrundaktualisierung, wenn iOS sie zulässt.

## Wie die Animation funktioniert

Eine Live-Aktivität kann keinen Code ausführen, aber iOS lässt `Text(timerInterval:)` von selbst jede Sekunde weiterzählen. `tools/make_slime.py` baut Schriften, in denen jede Ziffer ein ganzes Bild des Slimes ist und jedes Zeichen genau ein Bild breit. Die Insel setzt die Uhr rechtsbündig in einen breiten Rahmen und zeigt nur das letzte Zeichen, also die Sekunden. So wechselt das Bild jede Sekunde. Sechs Uhren übereinander, eine pro Farbebene, ergeben den farbigen Slime.

Die Animation ist standardmäßig an und lässt sich unter Einstellungen → Dynamic Island ausschalten. Unter Diagnose gibt es einen Test-Streifen für den Sperrbildschirm.

`tools/verify_fonts.py` prüft mit HarfBuzz, dass jedes Zeichen ein Bild breit ist und das letzte Zeichen jeder Uhrzeit das richtige Bild ist. Die CI führt das bei jedem Push aus.

## Aufbau

```
App/            die iOS-App (SwiftUI)
  Core/         GameStore (Zustand), IslandController (Live-Aktivität), PhoneMonitor, Notifier, Feedback (Ton, Vibration)
  Arcade/       Spielhalle: GameHost, Engines (reine Spiellogik, getestet), Games (Ansichten)
  Online/       HyprnodeAPI, FriendsStore (mit Offline-Warteschlange), ChatStore
  UI/           Kit (Fenster, Tasten), World (Zuhause), Screens
Shared/         App und Widgets: Model (Spielstand, Regeln, Dialoge, Ziele), Art, Activity (Insel, Intents), Sprites, Schrift
Widget/         Live-Aktivität, Home- und Sperrbildschirm-Widgets, Kontrollzentrum, Animationsschriften
Tests/          XCTest: Bedürfnisse, Zeit, Level, Belohnungen, Aufgaben, Dialoge, Speichern, Migration, Spiele, API
server/         Node-Server für slime.hyprnode.de mit Tests (siehe server/README.md)
tools/          make_slime.py (Pixel-Art, Schriften), verify_fonts.py, check_puzzles.py, make_sounds.py, make_icon.py
HANDOVER.md     Server betreiben und Website bauen (für die nächste KI, auf Englisch)
```

## Bauen

- **Ohne Mac:** Jeder Push auf `main` startet `.github/workflows/build.yml`. Die Schriften werden unter Linux geprüft, die Garten-Rätsel werden auf Lösbarkeit geprüft, der Server wird getestet, ein Mac in der Cloud führt die Unit-Tests im Simulator aus, baut die IPA und veröffentlicht sie als Release.
- **Mit Mac:** `brew install xcodegen && xcodegen generate`, `Slimy.xcodeproj` öffnen, unter Signing & Capabilities für beide Targets dein Team wählen, starten.
- **Pixel-Art ändern:** `tools/make_slime.py` bearbeiten, dann `pip install fonttools uharfbuzz pillow`, `python3 tools/make_slime.py` und `python3 tools/verify_fonts.py` ausführen und die erzeugten Dateien committen.

Braucht iOS 18 und ein iPhone mit Dynamic Island (ab iPhone 14 Pro, alle iPhone 15 und neuer).

Pixel-Schrift: [Pixelify Sans](https://github.com/eifetx/Pixelify-Sans), SIL Open Font License, Lizenz in `Shared/Fonts/PixelifySans-OFL.txt`.
