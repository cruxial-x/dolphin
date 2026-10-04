// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.settings.ui.viewholder

import android.view.View
import org.dolphinemu.dolphinemu.databinding.ListItemSettingBinding
import org.dolphinemu.dolphinemu.features.settings.model.view.InfoSetting
import org.dolphinemu.dolphinemu.features.settings.model.view.SettingsItem
import org.dolphinemu.dolphinemu.features.settings.ui.SettingsAdapter

class InfoViewHolder(
    private val binding: ListItemSettingBinding,
    adapter: SettingsAdapter
) : SettingViewHolder(binding.getRoot(), adapter) {
    private lateinit var setting: InfoSetting

    override val item: SettingsItem
        get() = setting

    override fun bind(item: SettingsItem) {
        setting = item as InfoSetting

        binding.textSettingName.text = setting.name
        binding.textSettingDescription.text = setting.description

        setStyle(binding.textSettingName, setting)
    }

    override fun onClick(clicked: View) {
        val picker = setting.picker ?: return

        if (!setting.isEditable) {
            showNotRuntimeEditableError()
            return
        }

        if (picker.type == SettingsItem.TYPE_DIRECTORY_PICKER) {
            adapter.onFilePickerDirectoryClick(picker, bindingAdapterPosition)
        } else {
            adapter.onFilePickerFileClick(picker, bindingAdapterPosition)
        }
    }
}
