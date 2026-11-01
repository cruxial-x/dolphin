// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.gba

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import org.dolphinemu.dolphinemu.DolphinApplication
import org.dolphinemu.dolphinemu.features.settings.model.StringSetting
import org.dolphinemu.dolphinemu.utils.ContentHandler

/**
 * Finds the save that belongs to a GBA ROM in the saves folder, a folder of saves that is shared
 * with other emulators. A save belongs to a ROM if it has the ROM's file name with the extension
 * .srm or .sav. A ROM that has no save there gets one, with the extension that Dolphin's own
 * saves have.
 */
object GbaLinkedSaves {
    private val EXTENSIONS = arrayOf("srm", "sav")
    private const val NEW_SAVE_EXTENSION = "sav"

    // A type that has no extension of its own, so that none is added to the file's name.
    private const val NEW_SAVE_MIME_TYPE = "application/octet-stream"

    private val CHOSEN_SAVES = arrayOf(
        StringSetting.MAIN_GBA_SAVE_1,
        StringSetting.MAIN_GBA_SAVE_2,
        StringSetting.MAIN_GBA_SAVE_3,
        StringSetting.MAIN_GBA_SAVE_4
    )
    private val SAVE_SIZES = longArrayOf(0x200, 0x2000, 0x8000, 0x10000, 0x20000)

    // mGBA appends this to the saves of games that have a real-time clock.
    private const val RTC_TRAILER_SIZE = 16L

    class Save(val uri: Uri, val name: String, val size: Long, val lastModified: Long) {
        /**
         * Whether the file has the size of a GBA save. A file that doesn't is some other file that
         * happens to have the right name, and must not be used as a save.
         */
        val hasSaveSize: Boolean
            get() = isSaveSize(size)
    }

    val isFolderSet: Boolean
        get() = StringSetting.MAIN_GBA_SAVES_FOLDER.string.isNotEmpty()

    fun isSaveSize(size: Long): Boolean {
        return SAVE_SIZES.any { size == it || size == it + RTC_TRAILER_SIZE }
    }

    /**
     * Returns the name a ROM's saves are based on: its file name without the extension.
     */
    fun getSaveBaseName(romPath: String): String? {
        val fileName = if (ContentHandler.isContentUri(romPath)) {
            ContentHandler.getDisplayName(romPath)
        } else {
            romPath.substringAfterLast('/')
        }
        if (fileName.isNullOrEmpty()) return null
        return fileName.substringBeforeLast('.')
    }

    /**
     * Returns the save the user chose for a port, or null if it can no longer be read.
     */
    fun getChosenSave(uri: String): Save? {
        try {
            val projection = arrayOf(
                Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
            )
            DolphinApplication.getAppContext().contentResolver
                .query(uri.toUri(), projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return Save(
                            uri.toUri(),
                            cursor.getString(0) ?: return null,
                            if (cursor.isNull(1)) 0 else cursor.getLong(1),
                            if (cursor.isNull(2)) 0 else cursor.getLong(2)
                        )
                    }
                }
        } catch (_: Exception) {
            // The file is gone or we are no longer allowed to read it.
        }
        return null
    }

    /**
     * Returns the save in the saves folder that belongs to the ROM, or null if there is none. If
     * there is more than one, the one of a valid size that was changed last wins.
     */
    fun findSave(romPath: String): Save? {
        return findSaves(romPath)?.firstOrNull()
    }

    /**
     * Returns the files in the saves folder that are named like a save of the ROM, the one that
     * wins first, or null if there is no saves folder or it can't be read.
     */
    fun findSaves(romPath: String): List<Save>? {
        val folder = StringSetting.MAIN_GBA_SAVES_FOLDER.string
        if (folder.isEmpty()) return null
        val baseName = getSaveBaseName(romPath) ?: return null

        val candidates = ArrayList<Save>()
        try {
            val tree = folder.toUri()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val projection = arrayOf(
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_DOCUMENT_ID
            )
            DolphinApplication.getAppContext().contentResolver
                .query(childrenUri, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: continue
                        if (Document.MIME_TYPE_DIR == cursor.getString(1)) continue
                        if (!isSaveOf(name, baseName)) continue
                        candidates.add(
                            Save(
                                DocumentsContract.buildDocumentUriUsingTree(
                                    tree, cursor.getString(4)
                                ),
                                name,
                                if (cursor.isNull(2)) 0 else cursor.getLong(2),
                                if (cursor.isNull(3)) 0 else cursor.getLong(3)
                            )
                        )
                    }
                } ?: return null
        } catch (_: Exception) {
            // The folder is gone or we are no longer allowed to read it.
            return null
        }

        return candidates.sortedWith(
            compareByDescending<Save> { it.hasSaveSize }.thenByDescending { it.lastModified }
        )
    }

    /**
     * Returns the save that the integrated GBA on a port should use in place of Dolphin's own:
     * the one chosen for the port or else the one found by name. Returns null if there is none
     * or if it isn't the size of a GBA save.
     */
    fun resolve(port: Int, romPath: String): Save? {
        val chosen = CHOSEN_SAVES[port].string
        val save = if (chosen.isNotEmpty()) getChosenSave(chosen) else findSave(romPath)
        return save?.takeIf { it.hasSaveSize }
    }

    /**
     * Returns the name of the save to create in the saves folder for the integrated GBA on a
     * port, or null if it doesn't get one: a save was chosen for the port, or the folder is not
     * set, can't be read or already has a file named like a save of the ROM.
     */
    fun getNameOfSaveToCreate(port: Int, romPath: String): String? {
        if (CHOSEN_SAVES[port].string.isNotEmpty()) return null
        if (findSaves(romPath)?.isEmpty() != true) return null
        val baseName = getSaveBaseName(romPath) ?: return null
        return "$baseName.$NEW_SAVE_EXTENSION"
    }

    /**
     * Creates a save in the saves folder. Returns null if it could not be created.
     */
    fun createSave(name: String, bytes: ByteArray): Uri? {
        val contentResolver = DolphinApplication.getAppContext().contentResolver
        var save: Uri? = null
        try {
            val tree = StringSetting.MAIN_GBA_SAVES_FOLDER.string.toUri()
            val folder = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            save = DocumentsContract.createDocument(
                contentResolver, folder, NEW_SAVE_MIME_TYPE, name
            )

            // A file whose name is taken is created under another name, which nothing would
            // ever look for.
            if (save != null && ContentHandler.getDisplayName(save) == name) {
                contentResolver.openOutputStream(save, "wt")?.use {
                    it.write(bytes)
                    return save
                }
            }
        } catch (_: Exception) {
            // The folder is gone or we are no longer allowed to write to it.
        }

        // A file that isn't a save would stop a save from being created the next time.
        try {
            save?.let { DocumentsContract.deleteDocument(contentResolver, it) }
        } catch (_: Exception) {
        }
        return null
    }

    private fun isSaveOf(fileName: String, baseName: String): Boolean {
        return fileName.substringBeforeLast('.') == baseName &&
                EXTENSIONS.any { it.equals(fileName.substringAfterLast('.', ""), ignoreCase = true) }
    }
}
