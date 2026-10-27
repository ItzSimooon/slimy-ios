package de.hyprnode.slimy.arcade

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import de.hyprnode.slimy.art.PixelIcon
import de.hyprnode.slimy.art.PixelItem
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.arcade.BubblePopEngine
import de.hyprnode.slimy.model.arcade.ColorMatchEngine
import de.hyprnode.slimy.model.arcade.GardenPuzzleEngine
import de.hyprnode.slimy.model.arcade.MemoryGardenEngine
import de.hyprnode.slimy.model.arcade.TreasureDigEngine
import de.hyprnode.slimy.model.arcade.clock
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.Card
import de.hyprnode.slimy.ui.kit.NotchedRectShape
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.kit.TileButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

// The calm games. They are built from real buttons, so TalkBack can play
// them: every cell says what it is.

/** Small white marks that tell the colours apart without colour. */
private val colorMarks: List<List<String>> = listOf(
    listOf(".#.#.", "#####", "#####", ".###.", "..#.."),
    listOf("..#..", ".###.", "#####", "#####", ".###."),
    listOf("..#..", "#####", ".###.", ".#.#.", "#...#"),
    listOf("..#..", "..#..", "#####", "..#..", "..#.."),
)
private val bubbleColors = listOf(0xE0577FL, 0x4FA3D9L, 0xE8A92AL, 0x4FB84EL)
private val bubbleNames = listOf("Pink", "Blue", "Yellow", "Green")

/** A grid cell with the window chrome: a notched pixel rectangle with a border (`Tile`). */
@Composable
private fun Tile(fill: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = NotchedRectShape(2.dp)
    Box(
        modifier
            .background(fill, shape)
            .border(2.dp, Theme.border.copy(alpha = 0.6f), shape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// MARK: - Bubble Pop

@Composable
fun BubblePopView(session: SessionState) {
    val engine = remember { BubblePopEngine(session.seed) }
    var version by remember { mutableIntStateOf(0) }

    fun report() {
        session.score = engine.score
        val left = engine.grid.sumOf { column -> column.count { it != null } }
        session.status = "$left left"
    }

    fun pop(column: Int, row: Int) {
        if (!session.running) return
        val popped = engine.pop(column, row)
        Feedback.shared.play(if (popped >= 2) (if (popped >= 6) Sound.perfect else Sound.pop) else Sound.nope)
        report()
        version += 1
        if (engine.isOver) {
            if (engine.isCleared) Feedback.shared.play(Sound.success)
            session.end()
        }
    }

    LaunchedEffect(Unit) { report() }
    @Suppress("UNUSED_VARIABLE") val shown = version
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .drawBehind { drawBackdrop(0xBFE3F0, 0xE8F6F8) },
        contentAlignment = Alignment.Center,
    ) {
        @Suppress("UNUSED_VARIABLE") val current = version
        val cell = min(maxWidth / BubblePopEngine.columns, maxHeight / BubblePopEngine.rows)
        Column {
            for (row in BubblePopEngine.rows - 1 downTo 0) {
                Row {
                    for (column in 0 until BubblePopEngine.columns) {
                        val color = engine.color(column, row)
                        val group = if (color == null) 0 else engine.group(column, row).size
                        Bubble(color, group, cell) { pop(column, row) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(color: Int?, size: Int, cell: Dp, pop: () -> Unit) {
    if (color == null) {
        Spacer(Modifier.size(cell))
        return
    }
    val border = Theme.border
    Box(
        Modifier
            .size(cell)
            .padding(2.dp)
            .clip(CircleShape)
            .background(hex(bubbleColors[color]))
            .border(2.dp, border.copy(alpha = 0.5f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = if (size >= 2) "Pop" else "Too small", onClick = pop)
            .semantics { contentDescription = "${bubbleNames[color]}, group of $size" },
        contentAlignment = Alignment.Center,
    ) {
        PixelIcon(colorMarks[color], Color.White.copy(alpha = 0.8f), Modifier.fillMaxSize().padding(cell * 0.3f))
        Box(
            Modifier
                .offset(x = -cell * 0.2f, y = -cell * 0.2f)
                .size(cell * 0.12f)
                .background(Color.White.copy(alpha = 0.7f)),
        )
    }
}

// MARK: - Memory Garden

@Composable
fun MemoryGardenView(session: SessionState) {
    val engine = remember { MemoryGardenEngine(session.seed) }
    val ticker = rememberTicker()
    var flash by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var lastGlow by remember { mutableStateOf<Int?>(null) }
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { session.status = "Round 1" }
    GameLoop(session.running) { dt ->
        engine.step(dt)
        val glow = engine.glowing
        if (glow != lastGlow && glow != null) {
            Feedback.shared.play(Sound.tone(glow))
            announce("Flower ${glow + 1}")
        }
        lastGlow = glow
        ticker.tick()
    }

    fun tap(i: Int) {
        if (!session.running || !engine.isListening) return
        val mark = Pair(i, SystemClock.uptimeMillis())
        flash = mark
        if (engine.tap(i)) {
            Feedback.shared.play(Sound.tone(i))
            session.score = engine.score
            session.status = "Round ${engine.score + 1}"
        } else {
            Feedback.shared.play(Sound.nope)
            session.end()
        }
        scope.launch {
            delay(260)
            if (flash == mark) flash = null
        }
        ticker.tick()
    }

    @Suppress("UNUSED_VARIABLE") val frame = ticker.value
    Column(
        Modifier
            .fillMaxSize()
            .drawBehind { drawBackdrop(0xCFEBC0, 0x9BD08A) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The garden is always light green, so the text stays dark in dark mode too.
        Text(
            if (engine.isListening) "Your turn" else "Watch closely",
            style = Theme.title(20.sp),
            color = hex(0x1F3D1A),
            modifier = Modifier
                .padding(top = 16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (r in 0 until MemoryGardenEngine.flowers / 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in 0 until 3) {
                        val i = r * 3 + c
                        val f = flash
                        val flashing = f != null && f.first == i && SystemClock.uptimeMillis() - f.second < 250
                        val lit = engine.glowing == i || flashing
                        Flower(i, lit, Modifier.weight(1f)) { tap(i) }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Flower(i: Int, lit: Boolean, modifier: Modifier, tap: () -> Unit) {
    Tile(
        fill = if (lit) hex(0xFFF3B0) else hex(0x6FAF5A),
        modifier = modifier
            .aspectRatio(1f)
            .clickable(role = Role.Button, onClick = tap)
            .semantics {
                contentDescription = "Flower ${i + 1}"
                selected = lit
            },
    ) {
        PixelItem(
            "flower",
            Modifier
                .padding(14.dp)
                .scale(if (lit) 1.15f else 1f)
                .alpha(if (lit) 1f else 0.75f),
        )
    }
}

// MARK: - Garden Puzzle

@Composable
fun GardenPuzzleView(session: SessionState) {
    val engine = remember { GardenPuzzleEngine() }
    val ticker = rememberTicker()
    var time by remember { mutableDoubleStateOf(0.0) }
    val announce = rememberAnnouncer()

    LaunchedEffect(Unit) { session.status = "Level 1/${GardenPuzzleEngine.levels.size}" }
    GameLoop(session.running) { dt ->
        time += dt
        ticker.tick()
    }

    fun boardDescription(): String {
        val s = engine.slime
        fun describe(d: GardenPuzzleEngine.Direction): String {
            val p = s.moved(d)
            if (engine.walls.contains(p)) return "hedge"
            if (engine.stones.contains(p)) return "stone"
            if (engine.seeds.contains(p)) return "seed"
            if (p == engine.bed) return "flower bed"
            return "free"
        }
        return "Level ${engine.level + 1}. ${engine.seeds.size} seeds left. " +
            "Up: ${describe(GardenPuzzleEngine.Direction.up)}, down: ${describe(GardenPuzzleEngine.Direction.down)}, " +
            "left: ${describe(GardenPuzzleEngine.Direction.left)}, right: ${describe(GardenPuzzleEngine.Direction.right)}."
    }

    fun move(d: GardenPuzzleEngine.Direction) {
        if (!session.running) return
        when (engine.move(d)) {
            GardenPuzzleEngine.MoveResult.blocked -> Feedback.shared.play(Sound.nope)
            GardenPuzzleEngine.MoveResult.moved -> Feedback.shared.play(Sound.hop)
            GardenPuzzleEngine.MoveResult.pushed -> Feedback.shared.play(Sound.land)
            GardenPuzzleEngine.MoveResult.seed -> Feedback.shared.play(Sound.coin)
            GardenPuzzleEngine.MoveResult.solved -> {
                Feedback.shared.play(Sound.success)
                session.status = "Level ${engine.level + 1}/${GardenPuzzleEngine.levels.size}"
            }
            GardenPuzzleEngine.MoveResult.finished -> {
                Feedback.shared.play(Sound.success)
                session.score = engine.score
                session.end()
            }
        }
        session.score = engine.score
        announce(boardDescription())
        ticker.tick()
    }

    @Suppress("UNUSED_VARIABLE") val frame = ticker.value
    val description = boardDescription()
    Column(
        Modifier
            .fillMaxSize()
            .background(hex(0x8FCB76)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(10.dp)
                .dragGesture(minimumDistance = 16.dp, onEnded = { v ->
                    val t = v.translation
                    if (abs(t.x) > abs(t.y)) {
                        move(if (t.x > 0) GardenPuzzleEngine.Direction.right else GardenPuzzleEngine.Direction.left)
                    } else {
                        move(if (t.y > 0) GardenPuzzleEngine.Direction.down else GardenPuzzleEngine.Direction.up)
                    }
                })
                .clearAndSetSemantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            @Suppress("UNUSED_VARIABLE") val current = ticker.value
            val cell = min(maxWidth / engine.width, maxHeight / engine.height)
            Column {
                for (y in 0 until engine.height) {
                    Row {
                        for (x in 0 until engine.width) {
                            val look = PuzzleCell.of(engine, GardenPuzzleEngine.Point(x, y))
                            PuzzleTile(look, cell, if (look.slime) time else 0.0)
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TileButton(
                onClick = {
                    if (session.running) {
                        Feedback.shared.play(Sound.tap)
                        engine.restartLevel()
                        ticker.tick()
                    }
                },
                modifier = Modifier.semantics { contentDescription = "Restart level" },
                fill = Theme.card,
                radius = 14.dp,
            ) {
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, tint = Theme.ink, modifier = Modifier.size(20.dp))
                    Text("Restart", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Theme.ink)
                }
            }
            Spacer(Modifier.weight(1f))
            ArrowButton(Icons.AutoMirrored.Filled.ArrowBack, "Left") { move(GardenPuzzleEngine.Direction.left) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ArrowButton(Icons.Filled.ArrowUpward, "Up") { move(GardenPuzzleEngine.Direction.up) }
                ArrowButton(Icons.Filled.ArrowDownward, "Down") { move(GardenPuzzleEngine.Direction.down) }
            }
            ArrowButton(Icons.AutoMirrored.Filled.ArrowForward, "Right") { move(GardenPuzzleEngine.Direction.right) }
        }
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, label: String, action: () -> Unit) {
    TileButton(
        onClick = action,
        modifier = Modifier
            .size(width = 56.dp, height = 48.dp)
            .semantics { contentDescription = label },
        fill = Theme.card,
        radius = 14.dp,
    ) {
        Icon(icon, contentDescription = null, tint = Theme.ink, modifier = Modifier.align(Alignment.Center).size(22.dp))
    }
}

/** What one square of the garden shows. */
private data class PuzzleCell(
    val wall: Boolean,
    val light: Boolean,
    val bed: Boolean,
    val bloom: Boolean,
    val seed: Boolean,
    val stone: Boolean,
    val slime: Boolean,
) {
    companion object {
        fun of(engine: GardenPuzzleEngine, p: GardenPuzzleEngine.Point): PuzzleCell = PuzzleCell(
            wall = engine.walls.contains(p),
            light = (p.x + p.y) % 2 == 0,
            bed = p == engine.bed,
            bloom = p == engine.bed && engine.seeds.isEmpty(),
            seed = engine.seeds.contains(p),
            stone = engine.stones.contains(p),
            slime = engine.slime == p,
        )
    }
}

@Composable
private fun PuzzleTile(look: PuzzleCell, cell: Dp, time: Double) {
    if (look.wall) {
        Box(
            Modifier
                .size(cell)
                .background(hex(0x2F6B3A)),
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(3.dp)
                    .offset(y = (-2).dp)
                    .background(hex(0x3F8F4A)),
            )
        }
        return
    }
    Box(
        Modifier
            .size(cell)
            .background(if (look.light) hex(0xA8DB8C) else hex(0x9DD382)),
        contentAlignment = Alignment.Center,
    ) {
        if (look.bed) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(3.dp)
                    .background(hex(0x8A5A3C)),
            )
            if (look.bloom) PixelItem("flower", Modifier.matchParentSize().padding(4.dp))
        }
        if (look.seed) PixelItem("seed", Modifier.matchParentSize().padding(6.dp))
        if (look.stone) PixelItem("rock", Modifier.matchParentSize().padding(2.dp))
        if (look.slime) GameSlime(height = cell * 0.8f, time = time)
    }
}

// MARK: - Treasure Dig

@Composable
fun TreasureDigView(session: SessionState) {
    val engine = remember { TreasureDigEngine(session.seed) }
    var version by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun report() {
        session.score = engine.score
        session.status = "${engine.shovels} shovels"
    }

    fun dig(i: Int) {
        if (!session.running) return
        val cell = engine.dig(i) ?: return
        Feedback.shared.play(if (cell == TreasureDigEngine.Cell.treasure) Sound.treasure else Sound.dig)
        report()
        version += 1
        if (engine.isOver) {
            scope.launch {
                delay(700)
                session.end()
            }
        }
    }

    LaunchedEffect(Unit) { report() }
    @Suppress("UNUSED_VARIABLE") val shown = version
    val found = "${engine.found} of ${TreasureDigEngine.treasureCount} treasures found"
    Column(
        Modifier
            .fillMaxSize()
            .background(hex(0x7CC46A)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .padding(top = 12.dp)
                .clearAndSetSemantics { contentDescription = found },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 0 until TreasureDigEngine.treasureCount) {
                PixelItem("chest", Modifier.size(22.dp), alpha = if (i < engine.found) 1f else 0.25f)
            }
        }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            @Suppress("UNUSED_VARIABLE") val current = version
            val cell = min(maxWidth, maxHeight) / TreasureDigEngine.size
            Column {
                for (y in 0 until TreasureDigEngine.size) {
                    Row {
                        for (x in 0 until TreasureDigEngine.size) {
                            val i = y * TreasureDigEngine.size + x
                            val state = engine.cells[i]
                            val hint = if (state is TreasureDigEngine.Cell.empty) engine.hint(i) else 0
                            DigCell(state, hint, i, cell) { dig(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DigCell(state: TreasureDigEngine.Cell, hint: Int, i: Int, cell: Dp, dig: (Int) -> Unit) {
    val size = TreasureDigEngine.size
    when (state) {
        TreasureDigEngine.Cell.hidden -> Tile(
            fill = if (i % 2 == 0) hex(0x9DD382) else hex(0x92CB77),
            modifier = Modifier
                .size(cell)
                .clickable(role = Role.Button, onClickLabel = "Dig") { dig(i) }
                .semantics { contentDescription = "Grass, row ${i / size + 1}, column ${i % size + 1}" },
        ) {
            Box(
                Modifier
                    .offset(x = (-5).dp, y = 3.dp)
                    .size(4.dp)
                    .background(hex(0x6FAF5A)),
            )
        }
        is TreasureDigEngine.Cell.empty -> {
            Tile(
                fill = hex(0x8A5A3C),
                modifier = Modifier
                    .size(cell)
                    .clearAndSetSemantics { contentDescription = "Hole. Nearest treasure $hint squares away" },
            ) {
                Text(
                    "$hint",
                    style = Theme.label(16.sp),
                    color = if (hint <= 1) hex(0xFFE07A) else hex(0xF1E2C4),
                )
            }
        }
        TreasureDigEngine.Cell.treasure -> Tile(
            fill = hex(0x8A5A3C),
            modifier = Modifier
                .size(cell)
                .clearAndSetSemantics { contentDescription = "Treasure" },
        ) {
            PixelItem("chest", Modifier.fillMaxSize().padding(5.dp))
        }
    }
}

// MARK: - Color Match

private val matchColors = listOf(0xD9483BL, 0x4FA3D9L, 0xE8C02AL, 0x4FB84EL)
private val matchShapes: List<List<String>> = listOf(
    listOf("..###..", ".#####.", "#######", "#######", "#######", ".#####.", "..###.."),
    listOf("#######", "#######", "#######", "#######", "#######", "#######", "#######"),
    listOf("...#...", "..###..", "..###..", ".#####.", ".#####.", "#######", "#######"),
    listOf("...#...", "...#...", "#######", ".#####.", "..###..", ".##.##.", "##...##"),
)

@Composable
fun ColorMatchView(session: SessionState) {
    val engine = remember { ColorMatchEngine(session.seed) }
    val ticker = rememberTicker()
    var feedback by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { session.status = clock(engine.timeLeft) }
    GameLoop(session.running) { dt ->
        engine.step(dt)
        session.status = clock(engine.timeLeft)
        if (engine.isOver) session.end()
        ticker.tick()
    }

    fun answer(yes: Boolean) {
        if (!session.running) return
        val right = engine.answer(yes)
        val mark = Pair(right, SystemClock.uptimeMillis())
        feedback = mark
        Feedback.shared.play(if (right) Sound.pop else Sound.nope)
        session.score = engine.score
        scope.launch {
            delay(320)
            if (feedback == mark) feedback = null
        }
        ticker.tick()
    }

    @Suppress("UNUSED_VARIABLE") val frame = ticker.value
    val accent = Theme.accent
    val danger = Theme.danger
    Column(
        Modifier
            .fillMaxSize()
            .background(Theme.well),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            if (engine.rule == ColorMatchEngine.Rule.color) "Same COLOR?" else "Same SHAPE?",
            style = Theme.title(26.sp),
            color = Theme.ink,
            modifier = Modifier
                .padding(top = 16.dp)
                .semantics { heading() },
        )
        Box {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MatchCard(engine.target, "Sample", Modifier.weight(1f))
                MatchCard(engine.piece, "Piece", Modifier.weight(1f))
            }
            val f = feedback
            if (f != null && SystemClock.uptimeMillis() - f.second < 300) {
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(horizontal = 8.dp)
                        .border(6.dp, if (f.first) accent else danger, NotchedRectShape()),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppButton("No", Modifier.weight(1f), kind = AppButtonKind.danger) { answer(false) }
            AppButton("Yes", Modifier.weight(1f)) { answer(true) }
        }
    }
}

@Composable
private fun MatchCard(piece: ColorMatchEngine.Piece, title: String, modifier: Modifier) {
    val colorName = ColorMatchEngine.colorNames[piece.color]
    val shapeName = ColorMatchEngine.shapeNames[piece.shape]
    Card(modifier.clearAndSetSemantics { contentDescription = "$title: $colorName, $shapeName" }) {
        Text(title, style = Theme.label(13.sp), color = Theme.inkSoft)
        PixelIcon(
            matchShapes[piece.shape],
            hex(matchColors[piece.color]),
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(10.dp),
        )
        Text(
            "$colorName · $shapeName",
            style = Theme.text(13.sp),
            color = Theme.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
