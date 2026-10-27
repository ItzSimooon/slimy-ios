package de.hyprnode.slimy.ui.screens.photo

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hyprnode.slimy.art.PixelSlime
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.saveFileUri
import de.hyprnode.slimy.model.Habitat
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSprites
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.prefixChars
import de.hyprnode.slimy.model.trimmedSwift
import de.hyprnode.slimy.ui.kit.AppButton
import de.hyprnode.slimy.ui.kit.AppButtonKind
import de.hyprnode.slimy.ui.kit.ChipTabs
import de.hyprnode.slimy.ui.kit.GroupedDivider
import de.hyprnode.slimy.ui.kit.GroupedRow
import de.hyprnode.slimy.ui.kit.GroupedSection
import de.hyprnode.slimy.ui.kit.SheetScaffold
import de.hyprnode.slimy.ui.kit.Theme
import de.hyprnode.slimy.ui.screens.common.rememberAnnouncer
import de.hyprnode.slimy.ui.screens.common.startSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

private const val captionLimit = 40

private enum class SaveState { idle, saving, saved }

/**
 * The photo booth: a picture of the slime in one of its places, with its
 * name and where to get the app, ready to share or save. The card is drawn
 * at three times its size, so every pixel of the slime stays sharp
 * (App/UI/Screens/PhotoBoothView.swift).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoBoothScreen(onClose: () -> Unit) {
    val store = LocalGameStore.current
    val context = LocalContext.current
    val s by store.state.collectAsStateWithLifecycle()
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()

    var pose by rememberSaveable { mutableStateOf(BoothPose.wave) }
    var place by rememberSaveable { mutableStateOf<Habitat?>(null) }
    var light by rememberSaveable { mutableStateOf(BoothLight.day) }
    var format by rememberSaveable { mutableStateOf(BoothFormat.portrait) }
    var caption by rememberSaveable { mutableStateOf("") }
    var showDetails by rememberSaveable { mutableStateOf(true) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(SaveState.idle) }
    var saveFailed by remember { mutableStateOf(false) }

    val habitat = place?.takeIf { s.inventory.habitats.contains(it) } ?: s.inventory.habitat
    val options = BoothOptions(pose, habitat, light, format, caption.trimmedSwift(), showDetails)

    LaunchedEffect(options) {
        if (photo != null) {
            // Typing in the caption changes the options with every key; wait for a short pause.
            delay(150)
        }
        val state = store.state.value
        val made = withContext(Dispatchers.Default) {
            runCatching {
                BoothCard.render(context, state.slime, state.inventory.furniture, options, options.light.date(Instant.now()))
            }.getOrNull()
        }
        if (made == null) {
            failed = photo == null
        } else {
            photo = made
            failed = false
        }
    }

    fun saved() {
        saving = SaveState.saved
        Feedback.shared.play(Sound.success)
        announce("Saved to Pictures")
        scope.launch {
            delay(2_000)
            if (saving == SaveState.saved) saving = SaveState.idle
        }
    }

    fun problem() {
        saving = SaveState.idle
        Feedback.shared.play(Sound.nope)
        saveFailed = true
    }

    // Before Android 10, the picture goes wherever the player picks: no storage permission needed.
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = photo
        if (uri == null || bitmap == null) {
            saving = SaveState.idle
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { BoothFiles.write(context, uri, bitmap) }
            if (ok) saved() else problem()
        }
    }

    fun save() {
        val bitmap = photo ?: return
        Feedback.shared.play(Sound.tap)
        saving = SaveState.saving
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            createDocument.launch(BoothFiles.fileName(s.slime.name))
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { BoothFiles.saveToPictures(context, bitmap, s.slime.name) }
            if (ok) saved() else problem()
        }
    }

    fun share() {
        val bitmap = photo ?: return
        scope.launch {
            val uri = withContext(Dispatchers.IO) { BoothFiles.shareable(context, bitmap) } ?: return@launch
            val name = s.slime.name
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$name in Slimy")
                putExtra(Intent.EXTRA_TEXT, "Meet $name, my slime in Slimy. https://slime.hyprnode.de")
                clipData = ClipData.newRawUri("$name in Slimy", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startSafely(context, Intent.createChooser(send, "$name in Slimy"))
        }
    }

    SheetScaffold(title = "Photo Booth", onDone = onClose, inline = true) { padding ->
        // The share bar brings its own bottom inset.
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Preview(photo, failed, s.slime, options)

                GroupedSection(header = "Format") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                        BoothFormat.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = format == option,
                                onClick = { format = option },
                                shape = SegmentedButtonDefaults.itemShape(index, BoothFormat.entries.size),
                                // Unselected segments show the card, not the black or grey page behind it.
                                colors = SegmentedButtonDefaults.colors(inactiveContainerColor = Color.Transparent),
                            ) { Text(option.title, maxLines = 1) }
                        }
                    }
                }

                GroupedSection(header = "Pose") {
                    PosePicker(s.slime, pose) { pose = it }
                }

                GroupedSection(
                    header = "Place",
                    footer = if (s.inventory.habitats.size < Habitat.allCases.size) "More places are waiting in the shop." else null,
                ) {
                    val owned = Habitat.allCases.filter { s.inventory.habitats.contains(it) }
                    ChipTabs(
                        options = owned.map { it to it.title },
                        selection = habitat,
                        onSelect = { place = it },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        bleed = 12.dp,
                    )
                }

                GroupedSection(header = "Light") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                        BoothLight.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = light == option,
                                onClick = { light = option },
                                shape = SegmentedButtonDefaults.itemShape(index, BoothLight.entries.size),
                                // Unselected segments show the card, not the black or grey page behind it.
                                colors = SegmentedButtonDefaults.colors(inactiveContainerColor = Color.Transparent),
                            ) { Text(option.title, maxLines = 1) }
                        }
                    }
                }

                GroupedSection(
                    header = "Card",
                    footer = "The speech bubble holds up to $captionLimit characters. Leave it empty for no bubble.",
                ) {
                    TextField(
                        value = caption,
                        onValueChange = { caption = it.prefixChars(captionLimit) },
                        placeholder = { Text("What does ${s.slime.name} say?") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GroupedDivider()
                    GroupedRow(
                        title = "Show level and age",
                        trailing = { Switch(checked = showDetails, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = showDetails, role = Role.Switch) { showDetails = it },
                    )
                }
            }
            ShareBar(
                ready = photo != null,
                saving = saving,
                onSave = ::save,
                onShare = ::share,
            )
        }
    }

    if (saveFailed) {
        AlertDialog(
            onDismissRequest = { saveFailed = false },
            title = { Text("The photo wasn't saved") },
            text = { Text("Something went wrong. Try again, or use Share and choose where to save it.") },
            confirmButton = { TextButton(onClick = { saveFailed = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun Preview(photo: Bitmap?, failed: Boolean, slime: Slime, options: BoothOptions) {
    // A fixed height keeps the controls below still when the format changes.
    Box(
        Modifier
            .fillMaxWidth()
            .height(400.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            val image = remember(photo) { photo.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = PhotoText.description(slime, options),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .aspectRatio(photo.width.toFloat() / photo.height)
                    .clip(RoundedCornerShape(Theme.radius)),
            )
        } else {
            Box(
                Modifier
                    .aspectRatio(options.format.aspect)
                    .clip(RoundedCornerShape(Theme.radius))
                    .background(Theme.well)
                    .clearAndSetSemantics {
                        contentDescription = if (failed) "The photo couldn't be made" else "Making the photo"
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (failed) {
                    Text(
                        "The photo couldn't be made. Try another format.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Theme.inkSoft,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    CircularProgressIndicator(color = Theme.accent)
                }
            }
        }
    }
}

@Composable
private fun PosePicker(slime: Slime, selected: BoothPose, onSelect: (BoothPose) -> Unit) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (p in BoothPose.entries) {
            val isSelected = selected == p
            val shape = RoundedCornerShape(Theme.smallRadius)
            Column(
                Modifier
                    .widthIn(min = 76.dp)
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(if (isSelected) Theme.accent.copy(alpha = 0.15f) else Theme.well)
                    .then(if (isSelected) Modifier.border(2.dp, Theme.accent, shape) else Modifier)
                    .selectable(selected = isSelected, role = Role.RadioButton) {
                        Feedback.shared.play(Sound.tap)
                        onSelect(p)
                    }
                    .semantics { contentDescription = p.title }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BoothSlime(slime, p, Modifier.size(width = 60.dp, height = 50.dp).clearAndSetSemantics { })
                Text(
                    p.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Theme.ink,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

/** One frame of the slime in a pose, wearing what it wears. */
@Composable
private fun BoothSlime(slime: Slime, pose: BoothPose, modifier: Modifier = Modifier) {
    val frames = SlimeSprites.frames(slime.stage, pose.anim)
    val anchors = SlimeSprites.anchors(slime.stage, pose.anim)
    val i = minOf(pose.frame, maxOf(0, frames.size - 1))
    PixelSlime(
        frame = frames.getOrNull(i) ?: SlimeSprites.blank,
        skin = slime.skin,
        accessories = slime.equipped,
        stage = slime.stage,
        anchor = anchors.getOrNull(i),
        modifier = modifier,
    )
}

@Composable
private fun ShareBar(ready: Boolean, saving: SaveState, onSave: () -> Unit, onShare: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = Theme.separator)
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val saved = saving == SaveState.saved
                AppButton(
                    title = if (saved) "Saved" else "Save Image",
                    systemIcon = if (saved) Icons.Filled.CheckCircle else Icons.Filled.Download,
                    kind = AppButtonKind.secondary,
                    enabled = ready && saving != SaveState.saving,
                    modifier = Modifier.weight(1f),
                    action = onSave,
                )
                AppButton(
                    title = "Share",
                    systemIcon = Icons.Filled.Share,
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                    action = onShare,
                )
            }
        }
    }
}

/** The booth's words, kept apart from the views so they can be tested. */
object PhotoText {
    fun whereAt(habitat: Habitat): String = when (habitat) {
        Habitat.room -> "in its room"
        Habitat.garden -> "in the garden"
        Habitat.beach -> "on the beach"
        Habitat.space -> "in space"
        Habitat.crystal -> "in the crystal cave"
    }

    fun description(slime: Slime, options: BoothOptions): String {
        var text = "Photo of ${slime.name} ${options.pose.doing} ${whereAt(options.habitat)}"
        text += ", ${options.light.title.lowercase()}"
        if (options.caption.isNotEmpty()) text += ", saying ${options.caption}"
        return text
    }
}

/** Writing the picture: to share, to Pictures/Slimy, or to a file the player picked. */
object BoothFiles {
    private const val prefix = "slimy-photo-"

    fun fileName(name: String): String {
        val cleaned = name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().ifEmpty { "Slimy" }
        return "$cleaned in Slimy.png"
    }

    /** A content uri for the picture in the cache's exports folder (shared through the FileProvider). */
    fun shareable(context: Context, bitmap: Bitmap): Uri? = runCatching {
        val folder = File(context.cacheDir, GameStore.exportFolder)
        if (!folder.isDirectory) folder.mkdirs()
        folder.listFiles()?.forEach { if (it.name.startsWith(prefix) && it.name.endsWith(".png")) it.delete() }
        val file = File(folder, "$prefix${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        context.saveFileUri(file)
    }.getOrNull()

    /** Android 10 and later: into Pictures/Slimy, without any permission. */
    fun saveToPictures(context: Context, bitmap: Bitmap, name: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(name))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Slimy")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return false
        val ok = write(context, uri, bitmap)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        return runCatching { resolver.update(uri, done, null, null) }.isSuccess
    }

    fun write(context: Context, uri: Uri, bitmap: Bitmap): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
    }.getOrDefault(false)
}
