# Screenshots

The App Store product page for Slimy 1.0: which screens, in which order, with which captions, at which sizes.

## Sizes

| Display class | Upload size (portrait) |
|---|---|
| **6.9"** (required) | 1320 × 2868 px |
| **6.5"** | 1284 × 2778 px |

App Store Connect scales the 6.9" set down for smaller iPhones. The 6.5" set is optional once 6.9" exists; upload it anyway so older large iPhones get pixel-exact images. Slimy is iPhone-only, so no iPad screenshots are needed.

PNG or JPEG, RGB, no transparency, portrait only. Up to 10 per locale; Slimy uses 8. The first three appear in search results, so they carry the story.

The uploads are composed images (caption plus the screen on a flat background, see "Layout"), so the raw screens only need to be at least as large as the screen area on the canvas. Any current iPhone or iPhone simulator capture is big enough.

## Where the raw screens come from

Debug builds take launch arguments that open the app straight on a screen with a demo slime (`App/Core/ScreenshotMode.swift`):

```
-SlimyScreenshot                       home screen with the demo slime
-SlimyScreenshot -SlimySheet <name>    a sheet: kitchen, shop, journal, friends, settings, chat, gift
-SlimyScreenshot -SlimyCover <name>    a full-screen cover: arcade, bath
```

CI (`tools/ci_screenshots.sh`, job "Screenshots (simulator)") runs all of them in light and dark on the newest iPhone Pro simulator (1206 × 2622 px) and, on every push, replaces the contents of the **`ci-screenshots`** branch. The files are named `light-<screen>.png` and `dark-<screen>.png` with `<screen>` one of `home gift kitchen shop journal friends settings chat arcade bath`; the branch's README shows them side by side. Every run also keeps them as the `screenshots` artifact. Release builds ignore the arguments.

Three shots come from a real iPhone with a Dynamic Island instead:

- **Shot 1 (the island).** The simulator capture does not show a living slime in the island. On the phone: the home screen with the compact island, then touch and hold for the expanded island. Use the expanded state for the image.
- **Shot 4 (friends) and shot 5 (talk).** In screenshot mode the app is offline, so these screens show the "Go Online" step. Use two phones you own, with made-up names that belong to no real person (for example "Robin" with the slime "Mochi"), and keep only chat replies that are friendly and short.

Name the device captures `device-island.png`, `device-friends.png` and `device-chat.png` next to the CI files so the set stays in one folder.

## The set

Order matters. Captions are short (two lines at most on the image), sentence case, no full stop when it's a fragment.

| # | Screen | Source | Caption (en-US) | Caption (de-DE) |
|---|---|---|---|---|
| 1 | Expanded Dynamic Island with the slime, its needs and the buttons, over the home screen | `device-island.png` | A tiny slime that lives in your Dynamic Island | Ein kleiner Slime, der in deiner Dynamic Island wohnt |
| 2 | Home: the slime in its room with the needs | `light-home.png` | Feed it, pet it, tuck it in | Füttern, streicheln, ins Bett bringen |
| 3 | Arcade with the game list and Today's game | `light-arcade.png` | 12 arcade games and a new one every day | 12 Arcade-Spiele und jeden Tag ein neues |
| 4 | Friends with two or three friends, love meters, one in love | `device-friends.png` | Friends visit, hug and fall in love | Freunde kommen vorbei und verlieben sich |
| 5 | Talk with a short, friendly exchange | `device-chat.png` | It talks back, in a few words | Er antwortet, in ein paar Worten |
| 6 | Shop with hats, glasses and furniture | `light-shop.png` | Hats, homes and furniture, earned by playing | Hüte, Zuhause und Möbel, erspielt statt gekauft |
| 7 | Journal with growth stage, memories and achievements | `light-journal.png` | It grows up and remembers | Er wächst und erinnert sich |
| 8 | Bath | `dark-bath.png` | Bubble bath included | Schaumbad inklusive |

Spare shots if one of the above doesn't look good: Kitchen (`light-kitchen.png`, caption "Ten foods and a secret favourite" / "Zehn Gerichte und ein geheimes Lieblingsessen"), the daily gift calendar (`light-gift.png`, "A small gift every day" / "Jeden Tag ein kleines Geschenk") and Settings in dark mode (`dark-settings.png`, "Light and dark, your call" / "Hell oder dunkel, wie du magst").

Shots 1 to 7 are light mode; shot 8 is dark to show that both exist. Never mix modes within the first three.

## Layout of each image

Build the frames once in a free design tool that runs on Windows (Figma in the browser has Nunito built in; Photopea works too), one frame per shot and size, then swap the screens and captions.

| | 6.9" canvas 1320 × 2868 | 6.5" canvas 1284 × 2778 |
|---|---|---|
| Background | flat `#F2F2F7` (or slime green `#1E7F37` for shot 1) | same |
| Caption | centred in the band from y 150 to 520, 120 px, line height 1.15, at most two lines | band y 145 to 505, 116 px |
| Caption font and colour | Nunito ExtraBold (SIL Open Font License) or SF Pro Rounded Heavy on a Mac; `#000000` on light, `#FFFFFF` on green | same |
| Screen | 1000 px wide, centred (x 160), top at y 600, height follows (about 2174 px) | 970 px wide (x 157), top at y 580 |
| Screen corners | radius 130 px, no border, no shadow | radius 126 px |

- No device frames, hands, fake notifications, prices, rankings or awards. No gradients or glows.
- Shot 1 may add the island enlarged: crop the island area from the capture, scale it to about 1100 px wide and place it as a rounded black card overlapping the top of the screen, so the slime reads at a glance in search results.
- Captions are localized; the screens stay English because the app is English.
- Export PNG at 1x. Folder layout that matches App Store Connect: `en-US/6.9/01.png … 08.png`, `en-US/6.5/…`, `de-DE/…`.

## Checklist before upload

1. Sizes exactly as in the table; open one file and check its pixel size.
2. Status bar: CI already sets 9:41, full battery and full signal. On the phone, charge it, turn on Do Not Disturb, and take the shots with good signal.
3. No personal data: no real names, no real codes that are still valid.
4. Every claim on the images is true in the build under review (12 games, Today's game, friends, talking).
5. Same order and captions in both locales.
