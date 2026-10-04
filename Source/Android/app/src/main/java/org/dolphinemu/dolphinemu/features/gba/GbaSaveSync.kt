// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.gba

import android.net.Uri
import org.dolphinemu.dolphinemu.DolphinApplication
import org.dolphinemu.dolphinemu.utils.Log
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the save of an integrated GBA in step with a save outside of Dolphin: the one chosen for
 * the port, or the one in the linked saves folder that is named after the ROM (see
 * [GbaLinkedSaves]).
 *
 * mGBA only ever opens Dolphin's own save file. The outside save is copied over it just before
 * mGBA opens it, and Dolphin's file is copied back out shortly after the game has written to it,
 * when the app goes to the background and when the GBA stops. As in other emulators, the game
 * that is being played wins: copying out doesn't ask, even if the outside save has changed.
 *
 * The app can be killed before it has copied a save out. To notice that the next time, a record
 * of what was last copied in either direction is kept next to Dolphin's save file.
 */
object GbaSaveSync {
    private const val WATCH_INTERVAL_SECONDS = 3L
    private const val BACKUPS_TO_KEEP = 5

    private class Session(
        val internal: File,
        val external: Uri,
        val externalName: String,
        // The hash of what Dolphin's save and the outside save both held when last copied.
        var syncedHash: String
    ) {
        var lastSeenHash = syncedHash
        var changedSinceLastLook = false
    }

    private class Record(val hash: String, val external: String)

    private val lock = Any()
    private val sessions = HashMap<Int, Session>()
    private val watchExecutor = Executors.newSingleThreadScheduledExecutor()
    private var watch: ScheduledFuture<*>? = null

    /**
     * Called on an emulation thread when the integrated GBA on a port is about to open its save.
     */
    fun onSaveOpening(deviceNumber: Int, romPath: String, savePath: String) {
        if (deviceNumber !in 0 until GbaHost.MAX_GBAS || savePath.isEmpty()) return

        synchronized(lock) {
            try {
                // A GBA that is restarted without having been stopped first.
                sessions.remove(deviceNumber)?.let { copyOut(it) }

                open(deviceNumber, romPath, File(savePath))
            } catch (e: Exception) {
                Log.error("[GbaSaveSync] Failed to bring in the save for port ${deviceNumber + 1}: $e")
            }
            updateWatch()
        }
    }

    /**
     * Called on an emulation thread when the integrated GBA on a port has stopped and closed its
     * save.
     */
    fun onGbaStopped(deviceNumber: Int) {
        synchronized(lock) {
            sessions.remove(deviceNumber)?.let { copyOut(it) }
            updateWatch()
        }
    }

    /**
     * Copies out every save that has changed. Called when the app goes to the background, after
     * which it can be killed without notice.
     */
    fun copyOutAll() {
        synchronized(lock) {
            sessions.values.forEach { copyOut(it) }
        }
    }

    private fun open(deviceNumber: Int, romPath: String, internal: File) {
        val save = GbaLinkedSaves.resolve(deviceNumber, romPath) ?: return

        // Two GBAs would overwrite each other's progress in one save, so only the first gets it.
        if (sessions.values.any { it.external == save.uri }) return

        val externalBytes = read(save.uri) ?: return
        val externalHash = hash(externalBytes)
        val internalBytes = if (internal.isFile) internal.readBytes() else null
        val internalHash = internalBytes?.let { hash(it) }
        val record = readRecord(internal)?.takeIf { it.external == save.uri.toString() }

        val syncedHash = when {
            internalHash == externalHash -> externalHash

            // The app was killed before it copied Dolphin's save out, and nothing else has
            // touched the outside save since. Finish that copy rather than undo the progress.
            internalBytes != null && record != null && internalHash != record.hash &&
                    externalHash == record.hash -> {
                if (!write(save.uri, internalBytes)) return
                internalHash!!
            }

            else -> {
                // Dolphin's save is about to be replaced. Keep it if it holds anything that was
                // never copied out: a save from before the link, or progress from a session that
                // was killed while the outside save changed too.
                if (internalBytes != null && internalHash != record?.hash) {
                    backUp(internal, internal.name, internalBytes)
                }
                val temporary = File(internal.path + ".tmp")
                internal.parentFile?.mkdirs()
                temporary.writeBytes(externalBytes)
                if (!temporary.renameTo(internal)) {
                    temporary.delete()
                    return
                }
                externalHash
            }
        }

        writeRecord(internal, Record(syncedHash, save.uri.toString()))
        sessions[deviceNumber] = Session(internal, save.uri, save.name, syncedHash)
    }

    private fun copyOut(session: Session) {
        try {
            if (!session.internal.isFile) return
            val internalBytes = session.internal.readBytes()
            val internalHash = hash(internalBytes)
            session.lastSeenHash = internalHash
            session.changedSinceLastLook = false
            if (internalHash == session.syncedHash) return

            // Something else changed the outside save while the game was running. The game being
            // played wins, but what it replaces is kept.
            val externalBytes = read(session.external)
            if (externalBytes != null && hash(externalBytes) != session.syncedHash) {
                backUp(session.internal, session.externalName, externalBytes)
            }

            if (!write(session.external, internalBytes)) return
            session.syncedHash = internalHash
            writeRecord(session.internal, Record(internalHash, session.external.toString()))
        } catch (e: Exception) {
            Log.error("[GbaSaveSync] Failed to copy out ${session.externalName}: $e")
        }
    }

    /**
     * Copies a save out once the game has stopped writing to it, so that a half-written save is
     * never copied: a save that differs from the last look is left until it has stayed the same
     * for one more look.
     */
    private fun watchSaves() {
        synchronized(lock) {
            for (session in sessions.values) {
                try {
                    if (!session.internal.isFile) continue
                    val currentHash = hash(session.internal.readBytes())
                    if (currentHash != session.lastSeenHash) {
                        session.lastSeenHash = currentHash
                        session.changedSinceLastLook = true
                    } else if (session.changedSinceLastLook) {
                        copyOut(session)
                    }
                } catch (e: Exception) {
                    Log.error("[GbaSaveSync] Failed to check ${session.internal.name}: $e")
                }
            }
        }
    }

    private fun updateWatch() {
        if (sessions.isEmpty()) {
            watch?.cancel(false)
            watch = null
        } else if (watch == null) {
            watch = watchExecutor.scheduleWithFixedDelay(
                ::watchSaves, WATCH_INTERVAL_SECONDS, WATCH_INTERVAL_SECONDS, TimeUnit.SECONDS
            )
        }
    }

    private fun read(uri: Uri): ByteArray? {
        return try {
            DolphinApplication.getAppContext().contentResolver.openInputStream(uri)
                ?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.error("[GbaSaveSync] Failed to read $uri: $e")
            null
        }
    }

    private fun write(uri: Uri, bytes: ByteArray): Boolean {
        return try {
            val stream = DolphinApplication.getAppContext().contentResolver
                .openOutputStream(uri, "wt") ?: return false
            stream.use { it.write(bytes) }
            true
        } catch (e: Exception) {
            Log.error("[GbaSaveSync] Failed to write $uri: $e")
            false
        }
    }

    /**
     * Keeps a copy of a save that is about to be replaced, in a folder next to Dolphin's saves.
     */
    private fun backUp(internal: File, name: String, bytes: ByteArray) {
        val folder = File(internal.parentFile, "Backups")
        folder.mkdirs()
        val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        File(folder, "$name.$time.bak").writeBytes(bytes)

        folder.listFiles { file -> file.name.startsWith("$name.") && file.name.endsWith(".bak") }
            ?.sortedByDescending { it.name }
            ?.drop(BACKUPS_TO_KEEP)
            ?.forEach { it.delete() }
    }

    private fun recordFile(internal: File) = File(internal.path + ".link")

    private fun readRecord(internal: File): Record? {
        val lines = try {
            recordFile(internal).readLines()
        } catch (_: Exception) {
            return null
        }
        return if (lines.size >= 2) Record(lines[0], lines[1]) else null
    }

    private fun writeRecord(internal: File, record: Record) {
        recordFile(internal).writeText("${record.hash}\n${record.external}\n")
    }

    private fun hash(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
