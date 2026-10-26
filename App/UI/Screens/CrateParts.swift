import SwiftUI

// Pieces shared by the crate shelf, the opening screen, the drop rates, the
// shop, the gift calendar and the journal.

// MARK: - Words

/// How crates are talked about, the same everywhere.
enum CrateCopy {
    /// "Wooden crate", whether the kind's own title already says "crate" or not.
    static func name(_ kind: CrateKind) -> String {
        kind.title.localizedCaseInsensitiveContains("crate") ? kind.title : "\(kind.title) crate"
    }

    static func coins(_ n: Int) -> String { n == 1 ? "1 coin" : "\(n) coins" }

    static func needMore(_ missing: Int) -> String {
        missing == 1 ? "You need 1 more coin." : "You need \(missing) more coins."
    }

    static let contents = "Every crate holds one surprise: coins, food, something to wear, furniture, a colour or another crate. Anything you already have turns into coins."
    static let noRealMoney = "Crates are earned in the game or bought with coins you earn. They're never sold for real money."
    static let mysteryHint = "Mystery crates can't be bought. They come with the seventh daily gift, every fifth level, now and then after a great arcade round, and with some achievements."
    /// Shown instead of a working Buy button once `Crates.maxStack` are waiting.
    static let shelfFull = "You have the most crates you can keep. Open some first."
}

// MARK: - Rarity

extension Rarity {
    /// The rarity's own colour, for sparks, badges and the glow-free disc
    /// behind a reward.
    var displayColor: Color { Color(hex: tint) }
}

/// "EPIC" in a capsule of the rarity's colour. The text stays ink, so it
/// reads on any rarity colour in light and dark mode.
struct RarityBadge: View {
    let rarity: Rarity
    var compact = false

    var body: some View {
        Text(rarity.title.uppercased())
            .font(compact ? Font.caption2.weight(.bold) : Font.caption.weight(.heavy))
            .tracking(compact ? 0.2 : 0.8)
            .foregroundStyle(Theme.ink)
            .lineLimit(1)
            .padding(.horizontal, compact ? 6 : 10)
            .padding(.vertical, compact ? 2 : 4)
            .background(rarity.displayColor.opacity(0.24), in: Capsule())
            .overlay(Capsule().strokeBorder(rarity.displayColor, lineWidth: compact ? 1 : 1.5))
            .accessibilityLabel(rarity.title)
    }
}

// MARK: - Crate art

/// The crate sprites from the sprite data: `crate.wooden` and its open twin
/// `crate.wooden.open`. Both share their bottom rows: closed, the lid sits on
/// the box and the rows above it stay empty; open, the lid stands up behind
/// the box.
enum CrateArt {
    static func closed(_ kind: CrateKind) -> ItemSprite? {
        SlimeSprites.items[kind.sprite]
    }

    static func opened(_ kind: CrateKind) -> ItemSprite? {
        SlimeSprites.items["\(kind.sprite).open"]
    }

    /// The first row with any pixels in it.
    static func firstFilledRow(_ sprite: ItemSprite) -> Int {
        sprite.rows.firstIndex { row in row.contains { $0 != "." } } ?? 0
    }

    /// The rows of a closed crate that make its lid: the top of what's drawn.
    static func lidRows(_ sprite: ItemSprite) -> Range<Int> {
        let first = firstFilledRow(sprite)
        let lid = max(1, Int((Double(sprite.rows.count - first) * 0.4).rounded()))
        return first..<min(sprite.rows.count, first + lid)
    }

    /// The sprite without the empty rows above it, for icons.
    static func cropped(_ sprite: ItemSprite) -> ItemSprite {
        let first = firstFilledRow(sprite)
        guard first > 0 else { return sprite }
        return ItemSprite(palette: sprite.palette, rows: Array(sprite.rows.dropFirst(first)))
    }

    /// How far down the closed crate starts, as a share of its sprite's height.
    static func topShare(_ kind: CrateKind) -> CGFloat {
        guard let sprite = closed(kind), !sprite.rows.isEmpty else { return 0 }
        return CGFloat(firstFilledRow(sprite)) / CGFloat(sprite.rows.count)
    }

    /// The crate's own colours, for the splinters a tap knocks off.
    static func colors(_ kind: CrateKind) -> [Color] {
        guard let sprite = closed(kind) else { return [] }
        return sprite.palette.keys.sorted().compactMap { key in
            sprite.palette[key].map { hex in Color(hex: hex) }
        }
    }
}

/// Some rows of a sprite, drawn crisp. Rows outside `keep` stay empty but
/// keep their place, so a lid and a box drawn apart line up exactly.
struct CratePixels: View {
    let sprite: ItemSprite
    var keep: Range<Int>? = nil

    var body: some View {
        let cols = max(1, sprite.rows.first?.count ?? 1)
        let rows: [String] = sprite.rows.enumerated().map { index, row in
            guard let keep, !keep.contains(index) else { return row }
            return String(repeating: ".", count: row.count)
        }
        ZStack {
            ForEach(sprite.palette.keys.sorted(), id: \.self) { key in
                if let ch = key.first {
                    PixelGridShape(rows: rows, code: ch, cols: cols, count: max(1, rows.count), bottom: false)
                        .fill(Color(hex: sprite.palette[key] ?? 0))
                }
            }
        }
        .aspectRatio(CGFloat(cols) / CGFloat(max(1, rows.count)), contentMode: .fit)
        .accessibilityHidden(true)
    }
}

/// A crate as pixels. `lift` (0...1) loosens the lid a little with every
/// tap and `tilt` leans it; `open` shows the open crate.
struct CrateSprite: View {
    let kind: CrateKind
    /// Leaves out the empty rows above a closed crate, for icons. The
    /// opening screen keeps them, so the open crate lines up with the closed one.
    var cropped = true
    var lift: Double = 0
    /// Degrees the loose lid leans, left or right.
    var tilt: Double = 0
    var open = false

    var body: some View {
        if open, let opened = CrateArt.opened(kind) {
            CratePixels(sprite: cropped ? CrateArt.cropped(opened) : opened)
        } else if let closed = CrateArt.closed(kind) {
            let sprite = cropped ? CrateArt.cropped(closed) : closed
            let lid = CrateArt.lidRows(sprite)
            // The lid turns about its own bottom edge.
            let hinge = UnitPoint(x: 0.5, y: CGFloat(lid.upperBound) / CGFloat(max(1, sprite.rows.count)))
            GeometryReader { geo in
                let side: CGFloat = min(geo.size.width, geo.size.height)
                let loose = CGFloat(min(1, max(0, lift)))
                let dx: CGFloat = open ? side * 0.34 : 0
                let dy: CGFloat = open ? -side * 0.55 : -side * 0.05 * loose
                ZStack {
                    CratePixels(sprite: sprite, keep: lid.upperBound..<sprite.rows.count)
                    // Without an open sprite, opening throws the lid off.
                    CratePixels(sprite: sprite, keep: lid)
                        .rotationEffect(.degrees(open ? -34 : tilt), anchor: hinge)
                        .offset(x: dx, y: dy)
                        .opacity(open ? 0 : 1)
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .accessibilityHidden(true)
        }
    }
}

// MARK: - Rewards

/// What came out of a crate, drawn big. Colours show the slime wearing
/// them; the legendary reward is always the axolotl.
struct CrateRewardArt: View {
    let reward: CrateReward
    let slime: Slime
    var size: CGFloat = 112

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let skin = shownSkin {
            slimeArt(skin)
        } else if let kind = crateKind {
            CrateSprite(kind: kind)
                .frame(width: size, height: size)
        } else {
            CrateRewardIcon(reward: reward)
                .frame(width: size, height: size)
        }
    }

    private var shownSkin: Skin? {
        if reward.rarity == .legendary { return .axolotl }
        if case .skin(let skin) = reward.kind { return skin }
        return nil
    }

    private var crateKind: CrateKind? {
        if case .crate(let kind) = reward.kind { return kind }
        return nil
    }

    private func slimeArt(_ skin: Skin) -> some View {
        let step = 0.22
        return TimelineView(.periodic(from: .now, by: reduceMotion ? 3600 : step)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            // A cheer now and then, otherwise just being there.
            let anim: SpriteAnim = reduceMotion || Int(t / 2.4) % 3 != 0 ? .idle : .celebrate
            let frames = SlimeSprites.frames(slime.stage, anim)
            let anchors = SlimeSprites.anchors(slime.stage, anim)
            let count = max(1, frames.count)
            let i = reduceMotion ? anim.posterIndex(at: context.date) % count : Int(t / step) % count
            PixelSlime(frame: frames.isEmpty ? SlimeSprites.blank : frames[i], skin: skin,
                       accessories: slime.equipped, stage: slime.stage,
                       anchor: i < anchors.count ? anchors[i] : nil)
        }
        .frame(width: size * 1.4, height: size * 1.4 / SlimeFont.aspect)
        .accessibilityHidden(true)
    }
}

/// The pixel icon of a reward: its own sprite if there is one, otherwise
/// the sprite of the food or item it stands for. Effects show what they
/// make float around the slime.
struct CrateRewardIcon: View {
    let reward: CrateReward

    var body: some View {
        if SlimeSprites.items[reward.icon] == nil, let effect = effectID {
            ZStack {
                switch effect {
                case "effect.hearts": PixelItem(name: "heart")
                case "effect.bubbles": PixelItem(name: "bubbleItem")
                default: IconShape(rows: SlimeSprites.emoteIcons["sparkle"] ?? []).fill(Theme.gold)
                }
            }
            .padding(8)
            .accessibilityHidden(true)
        } else {
            PixelItem(name: spriteName)
        }
    }

    private var effectID: String? {
        if case .item(let id) = reward.kind, Catalog.item(id)?.kind == .effect { return id }
        return nil
    }

    private var spriteName: String {
        var names = [reward.icon]
        switch reward.kind {
        case .coins: names.append("coin")
        case .food(let food, _): names.append(food.sprite)
        case .item(let id): if let item = Catalog.item(id) { names.append(item.sprite) }
        case .skin, .crate: break
        }
        return names.first { SlimeSprites.items[$0] != nil } ?? "coin"
    }
}

// MARK: - Sparks

/// Pixel sparks thrown out of a point, falling back with a little gravity.
struct SparkBurst: Identifiable {
    let id = UUID()
    var start = Date.now
    let colors: [Color]
    var count = 24
    /// Points per second at the start.
    var speed: CGFloat = 420
    var size: CGFloat = 7
    var life: Double = 1.1
    var gravity: CGFloat = 760
    /// Where they start, from the centre of the layer.
    var origin: CGSize = .zero
    /// 0: straight up, 1: all around.
    var spread: Double = 0.6
    let seed = UInt64.random(in: 1...UInt64.max)
}

/// Draws spark bursts while any is in the air.
struct SparkLayer: View {
    let bursts: [SparkBurst]

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 60, paused: bursts.isEmpty)) { context in
            Canvas { gc, size in
                let now = context.date
                for burst in bursts where !burst.colors.isEmpty {
                    let t = now.timeIntervalSince(burst.start)
                    guard t >= 0, t < burst.life else { continue }
                    let fade = 1 - t / burst.life
                    for i in 0..<burst.count {
                        let turn: Double = (Self.unit(burst.seed, i, 1) - 0.5) * 2 * Double.pi * burst.spread
                        let angle: Double = -Double.pi / 2 + turn
                        let v: Double = Double(burst.speed) * (0.45 + 0.75 * Self.unit(burst.seed, i, 2))
                        let dx = CGFloat(cos(angle) * v * t)
                        let fall: CGFloat = 0.5 * burst.gravity * CGFloat(t * t)
                        let dy = CGFloat(sin(angle) * v * t) + fall
                        let x = size.width / 2 + burst.origin.width + dx
                        let y = size.height / 2 + burst.origin.height + dy
                        let scale = CGFloat(0.6 + 0.8 * Self.unit(burst.seed, i, 3))
                        let side = (burst.size * scale).rounded()
                        var spark = gc
                        spark.opacity = fade
                        spark.fill(Path(CGRect(x: (x - side / 2).rounded(), y: (y - side / 2).rounded(),
                                               width: side, height: side)),
                                   with: .color(burst.colors[i % burst.colors.count]))
                    }
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// A steady random number in 0..<1 for one spark, so a burst looks the
    /// same in every frame.
    private static func unit(_ seed: UInt64, _ index: Int, _ salt: UInt64) -> Double {
        var z = seed &+ UInt64(index) &* 0x9E3779B97F4A7C15 &+ salt &* 0xD1B54A32D192ED03
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        z ^= z >> 31
        return Double(z >> 11) / Double(UInt64(1) << 53)
    }
}
