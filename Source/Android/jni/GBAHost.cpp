// Copyright 2026 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <jni.h>

#include <android/bitmap.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <memory>
#include <mutex>
#include <span>
#include <vector>

#include "Common/CommonTypes.h"
#include "Core/Host.h"

#ifdef HAS_LIBMGBA
#include "Core/HW/GBACore.h"
#endif

#include "jni/AndroidCommon/IDCache.h"

// The Android frontend hands GBA frames to Kotlin with a pull model: FrameEnded (on the GBA's
// emulation thread) stores the newest frame and notifies Kotlin, which copies it into a Bitmap
// when it next draws. Frames that are never drawn are simply overwritten.

namespace
{
constexpr std::size_t MAX_GBAS = 4;

struct FrameSlot
{
  std::mutex mutex;
  u32 width = 0;
  u32 height = 0;
  std::vector<u32> pixels;
};

// Indexed by SI device number. Kept outside of GBAHost so that Kotlin can safely request a frame
// while a host is being destroyed.
std::array<FrameSlot, MAX_GBAS> s_frame_slots;

#ifdef HAS_LIBMGBA
class GBAHost final : public GBAHostInterface
{
public:
  explicit GBAHost(std::weak_ptr<HW::GBA::Core> core);
  ~GBAHost() override;

  GBAHost(const GBAHost&) = delete;
  GBAHost& operator=(const GBAHost&) = delete;

  void GameChanged() override;
  void FrameEnded(std::span<const u32> video_buffer) override;

private:
  void UpdateDimensions(const HW::GBA::CoreInfo& info);

  std::weak_ptr<HW::GBA::Core> m_core;
  int m_device_number;
};

GBAHost::GBAHost(std::weak_ptr<HW::GBA::Core> core) : m_core(std::move(core))
{
  const HW::GBA::CoreInfo info = m_core.lock()->GetCoreInfo();
  m_device_number = info.device_number;
  UpdateDimensions(info);

  JNIEnv* env = IDCache::GetEnvForThread();
  env->CallStaticVoidMethod(IDCache::GetGBAHostClass(), IDCache::GetGBAHostOnHostCreated(),
                            m_device_number, static_cast<jint>(info.width),
                            static_cast<jint>(info.height));
}

GBAHost::~GBAHost()
{
  {
    FrameSlot& slot = s_frame_slots[m_device_number];
    std::lock_guard lock(slot.mutex);
    slot.width = 0;
    slot.height = 0;
    slot.pixels.clear();
  }

  JNIEnv* env = IDCache::GetEnvForThread();
  env->CallStaticVoidMethod(IDCache::GetGBAHostClass(), IDCache::GetGBAHostOnHostDestroyed(),
                            m_device_number);
}

void GBAHost::UpdateDimensions(const HW::GBA::CoreInfo& info)
{
  FrameSlot& slot = s_frame_slots[m_device_number];
  std::lock_guard lock(slot.mutex);
  if (slot.width == info.width && slot.height == info.height)
    return;

  slot.width = info.width;
  slot.height = info.height;
  slot.pixels.assign(std::size_t{info.width} * info.height, 0);
}

void GBAHost::GameChanged()
{
  const auto core = m_core.lock();
  if (!core || !core->IsStarted())
    return;

  const HW::GBA::CoreInfo info = core->GetCoreInfo();
  UpdateDimensions(info);

  JNIEnv* env = IDCache::GetEnvForThread();
  env->CallStaticVoidMethod(IDCache::GetGBAHostClass(), IDCache::GetGBAHostOnGameChanged(),
                            m_device_number, static_cast<jint>(info.width),
                            static_cast<jint>(info.height));
}

void GBAHost::FrameEnded(std::span<const u32> video_buffer)
{
  {
    FrameSlot& slot = s_frame_slots[m_device_number];
    std::lock_guard lock(slot.mutex);
    // The buffer is resized before GameChanged reports new dimensions, so drop mismatched frames.
    if (video_buffer.size() != slot.pixels.size())
      return;
    std::ranges::copy(video_buffer, slot.pixels.begin());
  }

  JNIEnv* env = IDCache::GetEnvForThread();
  env->CallStaticVoidMethod(IDCache::GetGBAHostClass(), IDCache::GetGBAHostOnFrameEnded(),
                            m_device_number);
}
#endif  // HAS_LIBMGBA
}  // namespace

std::unique_ptr<GBAHostInterface> Host_CreateGBAHost(std::weak_ptr<HW::GBA::Core> core)
{
#ifdef HAS_LIBMGBA
  return std::make_unique<GBAHost>(std::move(core));
#else
  return nullptr;
#endif
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_org_dolphinemu_dolphinemu_features_gba_GbaHost_getFrame(
    JNIEnv* env, jclass, jint device_number, jobject bitmap)
{
  if (device_number < 0 || static_cast<std::size_t>(device_number) >= MAX_GBAS)
    return JNI_FALSE;

  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
  {
    return JNI_FALSE;
  }

  FrameSlot& slot = s_frame_slots[device_number];
  std::lock_guard lock(slot.mutex);
  if (slot.width != info.width || slot.height != info.height || slot.pixels.empty())
    return JNI_FALSE;

  void* dest;
  if (AndroidBitmap_lockPixels(env, bitmap, &dest) != ANDROID_BITMAP_RESULT_SUCCESS)
    return JNI_FALSE;

  // mGBA outputs 0x00BBGGRR, which in memory matches RGBA_8888 apart from the unset alpha byte.
  const u32* src = slot.pixels.data();
  for (u32 y = 0; y < info.height; ++y)
  {
    u32* dest_row = reinterpret_cast<u32*>(static_cast<u8*>(dest) + std::size_t{y} * info.stride);
    for (u32 x = 0; x < info.width; ++x)
      dest_row[x] = *src++ | 0xFF000000;
  }

  AndroidBitmap_unlockPixels(env, bitmap);
  return JNI_TRUE;
}
}
