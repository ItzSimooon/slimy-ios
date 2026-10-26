# Slimy design language

One rule holds everything together: **a native iOS app around a pixel-art slime.** Everything you operate looks and behaves like iOS. Everything that belongs to the slime's world is pixel art. The two never swap places.

This file covers the app, the Dynamic Island and widgets, the website and the admin area, voice and tone, and the store material. Tokens in code win over this file if they ever disagree: `App/UI/Kit/Theme.swift`, `Shared/Art/Brand.swift`, `site/assets/site.css`.

## 1. Principles

1. **Native chrome, pixel heart.** Navigation, lists, forms, buttons, sheets and alerts are standard iOS. The slime, its food, items, homes and games are pixels.
2. **Calm by default.** The slime is a companion, not a slot machine. No red badges for things that can wait, no countdowns that pressure, no guilt.
3. **Readable first.** System text styles, Dynamic Type, contrast in light and dark. Pixel fonts only where the slime speaks or where a number is part of the pixel world.
4. **Honest.** Nothing to buy, nothing hidden. The interface says what is sent when you go online and what the chat does.
5. **One slime, many places.** The same character in the island, on the Lock Screen, in widgets, in the app, on the website and in screenshots. Same sprites, same palette, same voice.

## 2. The app

### Structure

- Every screen root is a `NavigationStack`. Sheets get a title with `.sheetTitle("…")` from the Kit and a native background; there are no custom sheet backgrounds.
- Content is `List` with `.insetGrouped` style or `Form`, with `Section` headers and footers. A footer explains a setting in one or two sentences.
- Full-screen covers (Arcade, Bath) bring their own close and pause controls in the top corners.
- Toolbars hold actions: Done or Close on the leading or trailing side as iOS expects, secondary actions in a menu.
- Confirmation for anything destructive with `confirmationDialog` (release the slime, delete server data, remove a friend, block).
- Rows use `.swipeActions` and `.contextMenu` where iOS users expect them (friends, requests, chat replies).

### Colour

System colours and materials, so Light Mode, Dark Mode and Increase Contrast work without special cases. Tokens from `Theme`:

| Token | Source | Use |
|---|---|---|
| `backdrop` | `systemGroupedBackground` | behind grouped content |
| `card` | `secondarySystemGroupedBackground` | cards and rows |
| `well` | `tertiarySystemFill` | inputs, quiet tiles, icon backgrounds |
| `separator` | `separator` | hairlines |
| `ink` / `inkSoft` | `primary` / `secondary` | text |
| `accent` | AccentColor asset: `#1E7F37` light / `#4CC764` dark, the same values as the website | primary buttons, selection, progress |
| `onAccent` | `#FFFFFF` light / `#0B2912` dark | text and symbols on accent fills (4.5:1 in both modes) |
| `gold` | `#B7791F` light / `#FFD166` dark | coins |
| `love` | `systemPink` | hearts, love meter |
| `sky` | `systemBlue` | information |
| `danger` | `systemRed` | destructive actions, errors |

Needs have fixed colours and pixel icons (`Theme.color(for:)`, `Theme.icon(for:)`): food orange with the apple, fun blue with the ball, energy indigo with the star jelly, clean teal with the bubbles, love pink with the heart.

Light and dark are both first-class. Nothing forces dark mode. The launch screen uses the LaunchBackground colour (`#F2F2F7` light, `#000000` dark) so the app opens without a flash.

### Type

- SF Rounded for the whole app, set once at the root with `.fontDesign(.rounded)`.
- Semantic text styles only: `.largeTitle`, `.title2`, `.headline`, `.body`, `.subheadline`, `.footnote`, `.caption`. They scale with Dynamic Type. `Theme.title/label/text(size)` exist for older code and map to the nearest text style.
- **Pixelify Sans** appears in exactly two places: the slime's own voice (`Theme.voice` in speech bubbles, `Brand.voice` on the Lock Screen card) and the wordmark (About, the photo booth card). Never for headings, buttons, labels or body text. The speech line inside the Dynamic Island itself is SF Rounded.

### Shape and spacing

- Corner radius 16 for cards (`Theme.radius`), 12 for small tiles (`Theme.smallRadius`), continuous corners.
- Standard iOS margins and list insets; let `List` and `Form` do the spacing.
- Hit targets at least 44 × 44 pt, including icon-only buttons in game corners.

### Controls

Use the Kit (`App/UI/Kit/Kit.swift`), which wraps standard controls:

- `AppButton` with kinds `.primary` (bordered prominent, accent), `.secondary` (bordered, accent), `.danger` (prominent, red), `.quiet` (bordered, neutral). One primary button per screen area.
- `Card` for content that isn't a list, `TileStyle` for tappable tiles, `HUDButton` for the round buttons on the home screen, `Meter` and `NeedRing` for needs, `CoinCounter`, `SpeechBox`, `ChipTabs`, `ToastBanner`, `Tag`, `ItemIcon`.
- System `Toggle`, `Picker`, `Stepper`, `DatePicker` for settings.
- SF Symbols for every UI icon (navigation, actions, settings). Pixel items (`PixelItem`, `ItemIcon`) only for things that exist in the slime's world: food, items, coins, hearts, games.

### Pixel art

- Sprites come from `Shared/Sprites/sprites.json`, generated by `tools/make_slime.py`. A slime frame is 24 × 20 pixels with the layers body, shade, ink, shine, warm and cool. Skin colours live in `Shared/Art/Skin.swift`.
- Always scale by whole numbers with smoothing off. Never blur, rotate by odd angles, or stretch a sprite.
- Inside games, pixel tiles may use `NotchedRect` (one-pixel cut corners) and `Theme.border`. Outside games, never.
- Animation plays at about 2.5 frames per second. Reduce Motion shows a still idle frame and swaps poses without movement.

### Motion, sound and haptics

- Short, physical animations: a squash when the slime lands, a bounce when tapped. No parallax, no looping attention grabbers on menus.
- Respect Reduce Motion everywhere, including games' screen shake and confetti.
- Sounds, music and haptics go through `Feedback` and can each be switched off in Settings. Sounds use the ambient audio category, so the silent switch mutes them and music from other apps keeps playing.

### Accessibility

- Every icon-only control has a VoiceOver label ("Friends", "Settings", "Close").
- The slime is a single VoiceOver element ("Mochi, your slime") with a Pet action; the needs next to it carry their own labels and values.
- Never rely on colour alone: needs have icons and values, love has a percentage.
- Contrast passes 4.5:1 for text in light and dark, including text on the accent green.

## 3. The Dynamic Island, Lock Screen and widgets

The island is always dark, so it keeps its own palette (`Shared/Art/Brand.swift`):

| Token | Hex | Use |
|---|---|---|
| bg | `#101411` | app icon background, dark text on bright island badges |
| panel / raised / line | `#171D19` / `#212A24` / `#2E3A32` | inner surfaces and dividers |
| text / muted | `#E9F1E4` / `#8D9D91` | text |
| slime | `#7DE67A` | the default lime slime |
| warm / cool / gold | `#F07A9A` / `#7FD6F0` / `#FFD166` | hearts, sleep and bubbles, coins |
| hypr | `#9B7BFF` | the "by hyprnode" wordmark only |

- Compact: the slime on the leading side, a pixel emote for mood or need on the trailing side. Nothing else.
- Expanded: the slime, up to two friends' slimes, the speech line in SF Rounded, and the five needs as ring buttons that act without opening the app (Feed, Play, Sleep or Wake, Bath, Pet).
- Text inside the island is white on the black island (`IslandInk`).
- The Lock Screen and StandBy card uses the system's Live Activity background, so it follows light and dark mode. There is room for the slime's voice in Pixelify Sans.
- Island and Lock Screen text follows Dynamic Type up to Large (`IslandType.largest`), because the system gives both a fixed height.
- Widgets draw the slime still (widgets don't animate), with the needs as small bars or gauges, on the system widget background.

## 4. Voice and tone

- The **slime** speaks English in short lowercase fragments: "hungry. very hungry.", "squeaky clean", "power. yum.". It is warm, a little silly, never sarcastic.
- The slime **never guilt-trips**. "there you are" is right; "you forgot me" is wrong. Needs never drop to zero, and no line suggests the slime suffers because you were away.
- The **interface** speaks plain English. Buttons, menu items and navigation titles use title-style capitalization like iOS itself ("Get My Code", "Go Online", "Delete Server Data"); section headers, toggles, footers and explanations use sentence case ("Always bring it back", "Reacting to your iPhone"). Buttons say what happens. Explanations are one or two sentences.
- Numbers are exact (10 minutes, 24 characters, 100 %).
- No marketing phrases in the product ("Unleash", "Elevate", "Seamless", "Revolutionize"), no emoji. Exclamation marks only for real moments of joy (hatching, a new record, preset greetings like "Thank you!"), never in buttons, settings or explanations.

## 5. The website

The website feels like the app: native-iOS-like interface around the slime's pixel world. It looks like an Apple product page that happens to have a slime in it.

### Tokens

Defined as CSS custom properties, switched by `prefers-color-scheme` and by `:root[data-theme]`:

| Token | Light | Dark |
|---|---|---|
| bg | `#F2F2F7` | `#000000` |
| card | `#FFFFFF` | `#1C1C1E` |
| card-2 | `#F2F2F7` | `#2C2C2E` |
| label | `#000000` | `#FFFFFF` |
| label-2 | `rgba(60,60,67,.6)` | `rgba(235,235,245,.6)` |
| separator | `rgba(60,60,67,.29)` | `rgba(84,84,88,.65)` |
| accent | `#1E7F37` | `#4CC764` |
| on-accent | `#FFFFFF` | `#0B2912` |
| love | `#FF2D55` | `#FF2D55` |
| gold | `#B7791F` | `#FFD166` |
| hypr | `#9B7BFF` | `#9B7BFF` (wordmark only) |
| island | `#000000` | `#000000` |

Radius: cards 22 px, controls 12 px, pills 999 px.

### Type

- Font stack: `ui-rounded, "SF Pro Rounded", -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif`.
- Large, bold headlines with tight leading, like an Apple product page; body text at a comfortable 17 px or larger.
- Pixelify Sans, self-hosted from `/fonts/`, only for the wordmark, the slime's speech bubbles and pixel numbers (for example the invite code).

### Building blocks

- Grouped cards on the page background, generous whitespace, one idea per section.
- The pixel world: the animated canvas slime drawn from `sprites.json` (whole-pixel scaling, `image-rendering: pixelated`), pixel item icons, a pixel room scene, and a **Dynamic Island mock**: a black pill with the slime in it.
- Buttons: a filled accent pill for the main action ("Install the beta now"), a tinted or bordered pill for secondary actions. The App Store state is stated plainly ("Coming to the App Store").
- The invite page `/f/<CODE>` shows the inviter's slime in their colour, the code large in Pixelify Sans and copyable, and the next step.

### Rules

- No external fonts, no CDNs, no analytics, no trackers, no cookies (the admin session cookie is the only one, and only on the LAN).
- Responsive from 320 px; no horizontal scrolling at 375 px.
- Light and dark, reduced motion (the canvas slime holds still), visible keyboard focus.
- User-provided text (names on the invite page) is inserted as text, never as HTML.

### Admin area

Same tokens and type as the public site, but denser: navigation with Overview, Reports, Devices, Chat usage and About, tables with clear row actions, numbers in tabular figures. Destructive actions (ban, delete) ask for confirmation and say what will happen. The slime appears only where it helps: the sign-in screen, empty states and a device's page (in that device's colour). Tables, charts and the report queue stay plain.

## 6. Store and marketing material

- App Store screenshots: a flat background from the website palette, a short caption in a bold rounded sans at the top, the real screen below. No gradients, no fake reviews, no awards, no prices. Details in `appstore/screenshots.md`.
- App icon: the slime on its own (`Resources/Assets.xcassets/AppIcon.appiconset/icon.png`), no text.
- Videos: real footage of the app and the island. Close-ups of the island are the signature shot.
- Wordmark: "slimy" in Pixelify Sans Bold, followed by "by hyprnode" in `#9B7BFF`. On dark backgrounds the "slimy" part is white, on light backgrounds black.

## 7. Do and don't

| Do | Don't |
|---|---|
| Use `List`, `Form`, `Section`, `NavigationStack` and system controls | Build custom windows, beveled keys or paper textures around menus |
| Use system colours and the Theme tokens | Hard-code greys, or force dark mode |
| Use SF Symbols for UI icons | Use pixel items as UI icons for settings or navigation |
| Scale pixel art by whole numbers with smoothing off | Smooth, stretch or blur sprites |
| Use Pixelify Sans for the slime's voice and the wordmark | Use Pixelify Sans for headings, buttons or body text |
| Write the slime in short lowercase fragments | Let the slime guilt-trip, beg or threaten |
| Write UI copy in plain English with iOS capitalization | Use stock phrases ("Unleash", "Elevate", "Seamless", "Revolutionize"), emoji, or exclamation marks in buttons and settings |
| Keep surfaces flat: cards, separators, solid fills | Use gradients, glows, neon, glassmorphism or purple blob art |
| Respect Dynamic Type, VoiceOver, Reduce Motion and contrast | Fix text sizes outside the island and widgets, or rely on colour alone |
| Say plainly what goes online and why | Hide data use behind vague wording |
| Show "Coming to the App Store" until it is true | Show an App Store badge before the app is live |
| Self-host fonts and assets on the website | Load fonts, scripts or images from other domains |
| Keep debug tools in `#if DEBUG` | Ship diagnostics screens in release builds |
