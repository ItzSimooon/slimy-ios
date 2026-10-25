# Slimy

Ein Pixel-Slime, der in der Dynamic Island deines iPhones wohnt. Von hyprnode, [slime.hyprnode.de](https://slime.hyprnode.de).

<p align="center"><img src="Resources/Assets.xcassets/AppIcon.appiconset/icon.png" width="140" alt="Slimy App-Icon"></p>

## Was er kann

**In der Dynamic Island**
- Der Slime sitzt links in der Insel. Rechts steht, was er gerade braucht, oder sein Level.
- Gedrückt halten öffnet die große Ansicht mit Sprechblase, Bedürfnissen und den Tasten Essen, Spielen, Baden und Kraulen. Die Tasten funktionieren, ohne die App zu öffnen.
- Auf dem Sperrbildschirm, im StandBy und auf der Apple Watch erscheint eine Karte.
- Im Kontrollzentrum gibt es „Slime holen“ und „Slime füttern“, beides auch für den Action Button.

**In der App**
- Fünf Bedürfnisse, die mit der Zeit sinken: Essen, Spaß, Energie, Sauber und Liebe. Er sagt, was ihm fehlt.
- Füttern mit fünf Sorten Essen (eins davon ist sein Lieblingsessen), Snack-Fangen spielen für Münzen, Matsch abschrubben beim Baden, kraulen durch Reiben, anstupsen durch Tippen.
- Mit den Levels kommen neue Farben.
- Nachts schläft er und wacht ausgeschlafen von selbst auf.
- Mitteilungen, kurz bevor er etwas braucht.

**Reaktionen aufs iPhone**

| Ereignis | Reaktion |
|---|---|
| Akku niedrig oder fast leer | schmilzt |
| Ladekabel dran, Akku voll | wird aufgeladen |
| WLAN verbunden | hüpft |
| WLAN weg, mobile Daten, kein Internet, Flugmodus | ist verwirrt |
| Morgen, Wecker, volle Stunde | winkt |
| Gute Nacht | Herzchen |

Solange die App offen ist, merkt Slimy das selbst. Ist sie zu, schicken Kurzbefehle-Automationen die Ereignisse über die Aktion „Slime reagiert“. Die Anleitung steht in der App unter Einstellungen → „Reagieren, wenn die App zu ist“.

**Freunde und Liebe** (braucht den Server, siehe [HANDOVER.md](HANDOVER.md))
- Mit einem 6-stelligen Code verbinden, dann umarmen, besuchen, Geschenke schicken und ab 50 % küssen.
- Bei 100 % sind die Slimes verliebt und sitzen zusammen in der Insel.
- Bis der Server läuft, gibt es einen Test-Freund (Mochi) nur auf dem eigenen iPhone.

**Reden** (braucht den Server): Der Slime antwortet passend zu seiner Laune. Ohne Server sagt er nur „blub“.

## Installieren

1. Unter [Releases](https://github.com/ItzSimooon/slimy-ios/releases) die neueste `Slimy.ipa` laden.
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

Standardmäßig zeichnet die Insel den Slime direkt aus seinen Pixeln (sicher sichtbar, wechselt das Bild bei jeder Änderung). Die sekündliche Animation ist unter Einstellungen → Diagnose einschaltbar. Dort gibt es auch einen Test-Streifen für den Sperrbildschirm.

`tools/verify_fonts.py` prüft mit HarfBuzz, dass jedes Zeichen ein Bild breit ist und das letzte Zeichen jeder Uhrzeit das richtige Bild ist. Die CI führt das bei jedem Push aus.

## Aufbau

```
App/            die iOS-App (SwiftUI)
  Core/         SlimeStore (Zustand), IslandController (Live-Aktivität), PhoneMonitor, Notifier
  Online/       HyprnodeAPI, FriendsStore, ChatStore
  UI/           Bildschirme
Shared/         App und Widget: Spielregeln, Marke, Farben, Intents, Sprites, Pixel-Schrift
Widget/         Live-Aktivität, Kontrollzentrum, Animationsschriften
Resources/      Asset-Katalog und App-Icon
tools/          make_slime.py (Pixel-Art, Schriften), verify_fonts.py, make_icon.py
HANDOVER.md     Spezifikation für Server und Website (für die nächste KI, auf Englisch)
```

## Bauen

- **Ohne Mac:** Jeder Push auf `main` startet `.github/workflows/build.yml`. Die Schriften werden unter Linux geprüft, ein Mac in der Cloud baut die unsignierte IPA und veröffentlicht sie als Release.
- **Mit Mac:** `brew install xcodegen && xcodegen generate`, `Slimy.xcodeproj` öffnen, unter Signing & Capabilities für beide Targets dein Team wählen, starten.
- **Pixel-Art ändern:** `tools/make_slime.py` bearbeiten, dann `pip install fonttools uharfbuzz pillow`, `python3 tools/make_slime.py` und `python3 tools/verify_fonts.py` ausführen und die erzeugten Dateien committen.

Braucht iOS 18 und ein iPhone mit Dynamic Island (ab iPhone 14 Pro, alle iPhone 15 und neuer).

Pixel-Schrift: [Pixelify Sans](https://github.com/eifetx/Pixelify-Sans), SIL Open Font License, Lizenz in `Shared/Fonts/PixelifySans-OFL.txt`.
