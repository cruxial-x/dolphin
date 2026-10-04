// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.settings.model.view

import org.dolphinemu.dolphinemu.features.settings.model.AbstractSetting

/**
 * A row whose description is worked out by the caller rather than being a fixed text or the
 * value of a setting. If it is given a [picker], clicking the row opens that picker and clearing
 * the row clears the picker's setting, for a description that says what a file or folder setting
 * leads to.
 */
class InfoSetting(
    name: CharSequence,
    description: CharSequence,
    val picker: FilePicker? = null
) : SettingsItem(name, description) {
    override val type: Int = TYPE_INFO

    override val setting: AbstractSetting?
        get() = picker?.setting

    override val isEditable: Boolean
        get() = picker == null || super.isEditable
}
