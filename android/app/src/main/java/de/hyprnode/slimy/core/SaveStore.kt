package de.hyprnode.slimy.core

import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.GameStorage
import de.hyprnode.slimy.model.SlimyJson
import de.hyprnode.slimy.model.WidgetSnapshot
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads and writes the save in the app's private files: the Android side of
 * the iPhone app's `GameStorage`. Same file names and the same JSON
 * (`slimy-game.json`, dates as seconds since 1970), the previous save kept as
 * `slimy-game.backup.json`, and `slimy-widget.json` for widgets and the slime
 * notification.
 *
 * Every write is atomic (a temporary file in the same folder, synced, then
 * renamed over the old one) and runs on one background thread, in order.
 * [saveLater] coalesces: only the newest state waiting is written.
 * Plain JVM, so it is unit-tested without a device.
 */
class SaveStore(
    val directory: File,
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "slimy-save").apply { isDaemon = true }
    },
) {
    /** How loading went: the game and where it came from. */
    data class Loaded(val state: GameState, val source: GameStorage.Source)

    val fileURL: File get() = File(directory, GameStorage.fileName)
    val backupURL: File get() = File(directory, GameStorage.backupName)
    val snapshotURL: File get() = File(directory, snapshotName)

    private val lock = Any()

    /** The newest state waiting to be written, and its widget snapshot. */
    private var pending: Pending? = null
    private var scheduled = false

    private class Pending(val state: GameState, val snapshot: WidgetSnapshot?, val done: ((Throwable?) -> Unit)?)

    // MARK: Reading

    /** The save, else the backup, else a new game. Loaded saves are brought up to date and repaired. */
    fun load(now: Instant = Instant.now()): Loaded {
        read(fileURL)?.let { return Loaded(GameStorage.prepared(it, now), GameStorage.Source.file) }
        read(backupURL)?.let { return Loaded(GameStorage.prepared(it, now), GameStorage.Source.backup) }
        return Loaded(GameState.fresh(), GameStorage.Source.fresh)
    }

    private fun read(file: File): GameState? {
        if (!file.isFile) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return null
        }
        return GameStorage.decode(text)
    }

    /** The widget snapshot last written, if any. */
    fun readSnapshot(): WidgetSnapshot? {
        val file = snapshotURL
        if (!file.isFile) return null
        return runCatching {
            SlimyJson.storage.decodeFromString(WidgetSnapshot.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    // MARK: Writing

    /**
     * Writes [state] (and [snapshot]) soon, on the save thread. If another
     * write is already waiting, only the newest is written. [done] runs on the
     * save thread with the error, or null when it worked. The state must not
     * be changed afterwards (GameStore only hands over published copies).
     */
    fun saveLater(state: GameState, snapshot: WidgetSnapshot? = null, done: ((Throwable?) -> Unit)? = null) {
        synchronized(lock) {
            pending = Pending(state, snapshot, done)
            if (scheduled) return
            scheduled = true
        }
        writer.execute { drain() }
    }

    /** Writes [state] now and waits for it; anything still waiting is dropped. Throws when it can't be written. */
    fun save(state: GameState, snapshot: WidgetSnapshot? = null) {
        synchronized(lock) { pending = null }
        val task = writer.submit<Unit> {
            write(state)
            snapshot?.let { writeSnapshotNow(it) }
        }
        try {
            task.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: "Couldn't save", e.cause)
        }
    }

    /** Waits until every write asked for so far is on disk (the app is going to the background). */
    fun flush(timeoutSeconds: Long = 5) {
        val task = writer.submit { drain() }
        runCatching { task.get(timeoutSeconds, TimeUnit.SECONDS) }
    }

    /** Writes the widget snapshot on the save thread. */
    fun writeSnapshot(snapshot: WidgetSnapshot) {
        writer.execute { runCatching { writeSnapshotNow(snapshot) } }
    }

    /** Deletes the save, the backup and the snapshot (starting over), after any write still waiting. */
    fun reset() {
        synchronized(lock) { pending = null }
        val task = writer.submit {
            fileURL.delete()
            backupURL.delete()
            snapshotURL.delete()
        }
        runCatching { task.get(5, TimeUnit.SECONDS) }
    }

    private fun drain() {
        val next = synchronized(lock) {
            val p = pending
            pending = null
            scheduled = false
            p
        } ?: return
        val error = runCatching {
            write(next.state)
            next.snapshot?.let { writeSnapshotNow(it) }
        }.exceptionOrNull()
        next.done?.invoke(error)
    }

    /** The previous save becomes the backup, then the new one replaces it. */
    private fun write(state: GameState) {
        val data = GameStorage.encode(state).toByteArray(Charsets.UTF_8)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Can't create ${directory.path}")
        }
        val current = fileURL
        if (current.isFile) {
            runCatching { writeAtomically(backupURL, current.readBytes()) }
        }
        writeAtomically(current, data)
    }

    private fun writeSnapshotNow(snapshot: WidgetSnapshot) {
        val text = SlimyJson.storage.encodeToString(WidgetSnapshot.serializer(), snapshot)
        writeAtomically(snapshotURL, text.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val snapshotName = "slimy-widget.json"

        /**
         * Writes [bytes] to a temporary file next to [target], syncs it to
         * disk and renames it over [target], so a crash leaves either the old
         * file or the new one, never half of one.
         */
        fun writeAtomically(target: File, bytes: ByteArray) {
            val dir = target.absoluteFile.parentFile ?: throw IOException("No folder for ${target.path}")
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Can't create ${dir.path}")
            val temp = File(dir, ".${target.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                try {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: IOException) {
                    // Last resort: copy over the old file, then drop the temporary one.
                    Files.copy(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }
}
