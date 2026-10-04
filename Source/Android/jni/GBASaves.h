// Copyright 2026 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <string_view>

namespace Android
{
// Tells the app that the integrated GBA with the given device number is about to open its save at
// save_path, so that the app can first replace that file with a save it shares with other
// emulators.
void PrepareGBASave(int device_number, std::string_view rom_path, std::string_view save_path);
}  // namespace Android
