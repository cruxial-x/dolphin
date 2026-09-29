// Copyright 2021 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include "Core/HW/GBAPad.h"

#include <array>
#include <atomic>

#include "Common/ScopeGuard.h"
#include "Core/HW/GBAPadEmu.h"
#include "InputCommon/ControlReference/ControlReference.h"
#include "InputCommon/ControllerEmu/ControlGroup/ControlGroup.h"
#include "InputCommon/GCPadStatus.h"
#include "InputCommon/InputConfig.h"

namespace Pad
{
static InputConfig s_config("GBA", _trans("Pad"), "GBA", "GBA");
static std::array<std::atomic<bool>, 4> s_input_enabled{true, true, true, true};

InputConfig* GetGBAConfig()
{
  return &s_config;
}

void ShutdownGBA()
{
  s_config.UnregisterHotplugCallback();

  s_config.ClearControllers();
}

void InitializeGBA()
{
  if (s_config.ControllersNeedToBeCreated())
  {
    for (unsigned int i = 0; i < 4; ++i)
      s_config.CreateController<GBAPad>(i);
  }

  s_config.RegisterHotplugCallback();

  // Load the saved controller config
  s_config.LoadConfig();
}

void LoadGBAConfig()
{
  s_config.LoadConfig();
}

bool IsGBAInitialized()
{
  return !s_config.ControllersNeedToBeCreated();
}

GCPadStatus GetGBAStatus(int pad_num)
{
  auto* const pad = static_cast<GBAPad*>(s_config.GetController(pad_num));
  if (s_input_enabled[pad_num])
    return pad->GetInput();

  // Read the pad with the input gate closed, so that it reports a neutral state.
  const bool input_gate = ControlReference::GetInputGate();
  Common::ScopeGuard gate_guard{[input_gate] { ControlReference::SetInputGate(input_gate); }};
  ControlReference::SetInputGate(false);
  return pad->GetInput();
}

void SetGBAInputEnabled(int pad_num, bool enabled)
{
  s_input_enabled[pad_num] = enabled;
}
void SetGBAReset(int pad_num, bool reset)
{
  static_cast<GBAPad*>(s_config.GetController(pad_num))->SetReset(reset);
}

ControllerEmu::ControlGroup* GetGBAGroup(int pad_num, GBAPadGroup group)
{
  return static_cast<GBAPad*>(s_config.GetController(pad_num))->GetGroup(group);
}
}  // namespace Pad
