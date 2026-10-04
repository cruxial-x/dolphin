// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.settings.model.view

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import org.dolphinemu.dolphinemu.features.settings.model.AbstractStringSetting

/**
 * A folder chosen with the system's folder picker. Unlike with [DirectoryPicker], the setting holds
 * a tree URI that only the app's own code uses, so it also works under scoped storage.
 */
class DocumentTreePicker(
    context: Context,
    setting: AbstractStringSetting,
    titleId: Int,
    descriptionId: Int,
    launcher: ActivityResultLauncher<Intent>
) : FilePicker(context, setting, titleId, descriptionId, launcher, null) {
    override val type: Int = TYPE_DIRECTORY_PICKER
}
