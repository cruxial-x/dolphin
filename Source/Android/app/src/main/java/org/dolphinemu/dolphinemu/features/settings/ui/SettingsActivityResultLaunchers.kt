// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.settings.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.features.gba.GbaLinkedSaves
import org.dolphinemu.dolphinemu.features.settings.model.StringSetting
import org.dolphinemu.dolphinemu.utils.FileBrowserHelper

class SettingsActivityResultLaunchers(
    private val fragment: Fragment, private val getAdapter: () -> SettingsAdapter?
) {
    val requestDirectory = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val intent = result.data
        if (result.resultCode == Activity.RESULT_OK && intent != null) {
            val path = FileBrowserHelper.getSelectedPath(intent)
            if (path != null) {
                getAdapter()?.onFilePickerConfirmation(path)
            }
        }
    }

    val requestGameFile = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        onFileResult(
            result,
            FileBrowserHelper.GAME_EXTENSIONS,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    val requestGbaRomFile = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        onFileResult(
            result,
            FileBrowserHelper.GBA_ROM_EXTENSIONS,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    val requestBinFile = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        onFileResult(
            result,
            FileBrowserHelper.BIN_EXTENSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    val requestRawFile = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        onFileResult(
            result,
            FileBrowserHelper.RAW_EXTENSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    // The save is copied back to the file it came from, so it needs write access.
    val requestGbaSaveFile = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        // A file of any other size would never be used, so it isn't accepted in the first place.
        val uri = result.data?.data
        val save = uri?.let { GbaLinkedSaves.getChosenSave(it.toString()) }
        if (result.resultCode == Activity.RESULT_OK && save != null && !save.hasSaveSize) {
            val context = fragment.requireContext()
            MaterialAlertDialogBuilder(context)
                .setMessage(context.getString(R.string.gba_save_wrong_size_not_chosen, save.name))
                .setPositiveButton(R.string.ok, null)
                .show()
        } else {
            onFileResult(
                result,
                FileBrowserHelper.GBA_SAVE_EXTENSIONS,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    val requestGbaSavesFolder =fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val intent = result.data
        val uri = intent?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val contentResolver = fragment.requireContext().contentResolver
            val canonicalizedUri = contentResolver.canonicalize(uri) ?: uri
            // Saves are copied back into the folder, so unlike a game folder it needs write access.
            val takeFlags = intent.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(canonicalizedUri, takeFlags)

            // Stop being able to write to the previous folder. Read access is left alone, as the
            // folder might also be a game folder.
            val previous = StringSetting.MAIN_GBA_SAVES_FOLDER.string
            if (previous.isNotEmpty() && previous != canonicalizedUri.toString()) {
                try {
                    contentResolver.releasePersistableUriPermission(
                        previous.toUri(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // We no longer had access to it.
                }
            }

            getAdapter()?.onFilePickerConfirmation(canonicalizedUri.toString())
        }
    }

    private fun onFileResult(result: ActivityResult, validExtensions: Set<String>, flags: Int) {
        val intent = result.data
        val uri = intent?.data
        val context = fragment.requireContext()
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val canonicalizedUri = context.contentResolver.canonicalize(uri) ?: uri
            val takeFlags = flags and intent.flags
            FileBrowserHelper.runAfterExtensionCheck(context, canonicalizedUri, validExtensions) {
                context.contentResolver.takePersistableUriPermission(canonicalizedUri, takeFlags)
                getAdapter()?.onFilePickerConfirmation(canonicalizedUri.toString())
            }
        }
    }
}
