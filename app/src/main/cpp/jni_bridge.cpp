// JNI bridge between Kotlin and the embedded mGBA core.
//
// Phase 0 surface: loadRom, runFrame, getFramebuffer, getFramebufferHash, destroy.
// Phase 1 will add: setKeyState, audio pull, save state.
// Phase 3a will add: busRead8/16/32 + frame callback hook.

#include <jni.h>
#include <fcntl.h>
#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

extern "C" {
#include <mgba/core/core.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/config.h>
#include <mgba/core/serialize.h>
#include <mgba/gba/core.h>
#include <mgba-util/vfs.h>
}

#include "movement_gate.h"

// Vendored xdelta3 (decode-only subset). Self-guards with extern "C".
#include "xdelta3.h"

#define LOG_TAG "poketrek-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int GBA_WIDTH = 240;
constexpr int GBA_HEIGHT = 160;
constexpr int FRAMEBUFFER_BYTES = GBA_WIDTH * GBA_HEIGHT * 4;

struct Emulator {
    mCore* core = nullptr;
    std::vector<uint8_t> framebuffer;  // RGBA8888, GBA_WIDTH * GBA_HEIGHT * 4
    std::vector<uint8_t> romCopy;      // mGBA expects ROM memory to outlive loadROM
    std::mutex mutex;
    MovementGate gate;

    Emulator() : framebuffer(FRAMEBUFFER_BYTES, 0) {}

    ~Emulator() {
        if (core) {
            core->deinit(core);
            core = nullptr;
        }
    }
};

std::unique_ptr<Emulator> g_emulator;

uint64_t fnv1a64(const uint8_t* data, size_t len) {
    uint64_t hash = 0xcbf29ce484222325ULL;
    for (size_t i = 0; i < len; ++i) {
        hash ^= data[i];
        hash *= 0x100000001b3ULL;
    }
    return hash;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_poketrek_emu_NativeEmulator_loadRom(JNIEnv* env, jobject /*thiz*/, jbyteArray romBytes,
                                             jstring savePath) {
    if (g_emulator) {
        LOGI("loadRom called while emulator alive — recreating");
        g_emulator.reset();
    }

    auto emu = std::make_unique<Emulator>();
    emu->core = GBACoreCreate();
    if (!emu->core) {
        LOGE("GBACoreCreate failed");
        return JNI_FALSE;
    }
    // mCoreInitConfig sets up internal hash tables and config state. Must run
    // before core->init() — without it, GBALoadROM crashes inside the game-DB
    // lookup with an uninitialized table.
    mCoreInitConfig(emu->core, nullptr);
    if (!emu->core->init(emu->core)) {
        LOGE("core->init failed");
        return JNI_FALSE;
    }

    emu->core->setVideoBuffer(emu->core,
                              reinterpret_cast<color_t*>(emu->framebuffer.data()),
                              GBA_WIDTH);

    // Copy the ROM bytes into a buffer we own. The libretro reference does this
    // (line 857–860 of platform/libretro/libretro.c) because mGBA may keep the
    // pointer alive past loadROM and the JNI array's lifetime is unreliable.
    jsize len = env->GetArrayLength(romBytes);
    emu->romCopy.resize(static_cast<size_t>(len));
    env->GetByteArrayRegion(romBytes, 0, len,
                            reinterpret_cast<jbyte*>(emu->romCopy.data()));
    VFile* vf = VFileFromMemory(emu->romCopy.data(), emu->romCopy.size());
    if (!vf) {
        LOGE("VFileFromMemory failed");
        return JNI_FALSE;
    }
    if (!emu->core->loadROM(emu->core, vf)) {
        LOGE("loadROM failed");
        return JNI_FALSE;
    }
    // Battery save (the game's own in-game save). mGBA memory-maps the file,
    // so every write the game makes to flash lands in it directly; without
    // this the save only lived in RAM and was lost when the app closed.
    if (savePath) {
        const char* path = env->GetStringUTFChars(savePath, nullptr);
        VFile* sv = VFileOpen(path, O_CREAT | O_RDWR);
        if (!sv || !emu->core->loadSave(emu->core, sv)) {
            LOGE("battery save not attached: %s", path);
            if (sv) sv->close(sv);
        } else {
            LOGI("battery save: %s", path);
        }
        env->ReleaseStringUTFChars(savePath, path);
    }
    emu->core->reset(emu->core);

    g_emulator = std::move(emu);
    LOGI("ROM loaded; emulator ready");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_poketrek_emu_NativeEmulator_initAudio(JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate) {
    if (!g_emulator || !g_emulator->core) return;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    mCore* core = g_emulator->core;
    // Match the libretro core's sizing (src/platform/libretro/libretro.c L893–L909):
    // expected samples per frame = sampleRate * frameCycles / frequency. Double
    // it for headroom; clamp to blip's hard 0x4000 limit.
    auto samplesPerFrame = static_cast<size_t>(
        static_cast<float>(sampleRate) * static_cast<float>(core->frameCycles(core))
            / static_cast<float>(core->frequency(core)));
    size_t bufSize = samplesPerFrame * 2;
    if (bufSize > 0x4000) bufSize = 0x4000;
    core->setAudioBufferSize(core, bufSize);
    blip_set_rates(core->getAudioChannel(core, 0),
                   static_cast<double>(core->frequency(core)),
                   static_cast<double>(sampleRate));
    blip_set_rates(core->getAudioChannel(core, 1),
                   static_cast<double>(core->frequency(core)),
                   static_cast<double>(sampleRate));
}

// Drains both blip channels into a direct ShortBuffer as interleaved L/R
// samples. Returns the number of stereo frames written (each frame is two shorts).
extern "C" JNIEXPORT jint JNICALL
Java_com_poketrek_emu_NativeEmulator_pollAudio(JNIEnv* env, jobject /*thiz*/, jobject directShortBuffer) {
    if (!g_emulator || !g_emulator->core) return 0;
    short* dst = static_cast<short*>(env->GetDirectBufferAddress(directShortBuffer));
    jlong capBytes = env->GetDirectBufferCapacity(directShortBuffer);
    if (!dst || capBytes <= 0) return 0;
    int maxStereoFrames = static_cast<int>((capBytes / sizeof(short)) / 2);
    if (maxStereoFrames <= 0) return 0;

    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    blip_t* left = g_emulator->core->getAudioChannel(g_emulator->core, 0);
    blip_t* right = g_emulator->core->getAudioChannel(g_emulator->core, 1);
    int avail = blip_samples_avail(left);
    int availR = blip_samples_avail(right);
    if (availR < avail) avail = availR;
    if (avail > maxStereoFrames) avail = maxStereoFrames;
    if (avail <= 0) return 0;
    // blip_read_samples with stereo=1 writes every other slot (stride 2).
    blip_read_samples(left, dst, avail, 1);
    blip_read_samples(right, dst + 1, avail, 1);
    return avail;
}

extern "C" JNIEXPORT void JNICALL
Java_com_poketrek_emu_NativeEmulator_runFrame(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_emulator || !g_emulator->core) return;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    g_emulator->core->runFrame(g_emulator->core);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_poketrek_emu_NativeEmulator_getFramebuffer(JNIEnv* env, jobject /*thiz*/) {
    if (!g_emulator) return nullptr;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    jbyteArray out = env->NewByteArray(FRAMEBUFFER_BYTES);
    env->SetByteArrayRegion(out, 0, FRAMEBUFFER_BYTES,
                            reinterpret_cast<const jbyte*>(g_emulator->framebuffer.data()));
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_poketrek_emu_NativeEmulator_getFramebufferHash(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_emulator) return 0;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    return static_cast<jlong>(fnv1a64(g_emulator->framebuffer.data(), FRAMEBUFFER_BYTES));
}

extern "C" JNIEXPORT void JNICALL
Java_com_poketrek_emu_NativeEmulator_destroy(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_emulator.reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_poketrek_emu_NativeEmulator_setKeys(JNIEnv* /*env*/, jobject /*thiz*/, jint keys) {
    if (!g_emulator || !g_emulator->core) return;
    g_emulator->core->setKeys(g_emulator->core, static_cast<uint32_t>(keys));
}

// Writes the current framebuffer into a direct ByteBuffer of length >=
// FRAMEBUFFER_BYTES. The byte layout is mGBA's native 32-bit color_t: R, G, B, A
// in memory order, which matches Android Bitmap.Config.ARGB_8888 byte layout
// (despite the name) and works with Bitmap.copyPixelsFromBuffer.
//
// mGBA leaves A at 0 for most pixels. Drawn over black that looks right, but
// anything that reads the Bitmap's alpha (PNG export for the ask bridge) sees
// a mostly transparent frame, so A is forced to 0xFF here.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_poketrek_emu_NativeEmulator_writeFramebuffer(JNIEnv* env, jobject /*thiz*/, jobject directBuffer) {
    if (!g_emulator) return JNI_FALSE;
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    jlong cap = env->GetDirectBufferCapacity(directBuffer);
    if (!dst || cap < FRAMEBUFFER_BYTES) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    std::memcpy(dst, g_emulator->framebuffer.data(), FRAMEBUFFER_BYTES);
    for (int i = 3; i < FRAMEBUFFER_BYTES; i += 4) dst[i] = 0xFF;
    return JNI_TRUE;
}

// RAM reads. The mutex is held to avoid racing with a runFrame on the emu thread.
// Returned values are zero-extended into Java's signed int.

extern "C" JNIEXPORT jint JNICALL
Java_com_poketrek_emu_NativeEmulator_busRead8(JNIEnv* /*env*/, jobject /*thiz*/, jint addr) {
    if (!g_emulator || !g_emulator->core) return 0;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    return static_cast<jint>(
        g_emulator->core->busRead8(g_emulator->core, static_cast<uint32_t>(addr)) & 0xff);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_poketrek_emu_NativeEmulator_busRead16(JNIEnv* /*env*/, jobject /*thiz*/, jint addr) {
    if (!g_emulator || !g_emulator->core) return 0;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    return static_cast<jint>(
        g_emulator->core->busRead16(g_emulator->core, static_cast<uint32_t>(addr)) & 0xffff);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_poketrek_emu_NativeEmulator_busRead32(JNIEnv* /*env*/, jobject /*thiz*/, jint addr) {
    if (!g_emulator || !g_emulator->core) return 0;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    return static_cast<jint>(
        g_emulator->core->busRead32(g_emulator->core, static_cast<uint32_t>(addr)));
}

// RAM writes. Mutex-locked like reads — mGBA's bus writes can poke MMIO and we
// don't want to race a concurrent runFrame on the emu thread.

extern "C" JNIEXPORT void JNICALL
Java_com_poketrek_emu_NativeEmulator_busWrite8(JNIEnv* /*env*/, jobject /*thiz*/, jint addr, jint value) {
    if (!g_emulator || !g_emulator->core) return;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    g_emulator->core->busWrite8(g_emulator->core,
                                static_cast<uint32_t>(addr),
                                static_cast<uint8_t>(value & 0xff));
}

extern "C" JNIEXPORT void JNICALL
Java_com_poketrek_emu_NativeEmulator_busWrite16(JNIEnv* /*env*/, jobject /*thiz*/, jint addr, jint value) {
    if (!g_emulator || !g_emulator->core) return;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    g_emulator->core->busWrite16(g_emulator->core,
                                 static_cast<uint32_t>(addr),
                                 static_cast<uint16_t>(value & 0xffff));
}

extern "C" JNIEXPORT void JNICALL
Java_com_poketrek_emu_NativeEmulator_busWrite32(JNIEnv* /*env*/, jobject /*thiz*/, jint addr, jint value) {
    if (!g_emulator || !g_emulator->core) return;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    g_emulator->core->busWrite32(g_emulator->core,
                                 static_cast<uint32_t>(addr),
                                 static_cast<uint32_t>(value));
}

// Bulk reads: drains [addr, addr+length) into a freshly-allocated jbyteArray.
// Used by the ROM calibrator to scan EWRAM (256KB) and IWRAM (32KB) without
// firing thousands of separate JNI calls. Reads aligned chunks via busRead32
// for speed; tail bytes via busRead8.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_poketrek_emu_NativeEmulator_busReadBytes(JNIEnv* env, jobject /*thiz*/, jint addr, jint length) {
    if (!g_emulator || !g_emulator->core || length <= 0) return nullptr;
    jbyteArray result = env->NewByteArray(length);
    if (!result) return nullptr;
    std::vector<uint8_t> buf(static_cast<size_t>(length));
    {
        std::lock_guard<std::mutex> lock(g_emulator->mutex);
        int i = 0;
        while (i + 4 <= length && ((addr + i) & 3) == 0) {
            uint32_t v = g_emulator->core->busRead32(g_emulator->core,
                                                    static_cast<uint32_t>(addr + i));
            buf[i + 0] = static_cast<uint8_t>(v & 0xff);
            buf[i + 1] = static_cast<uint8_t>((v >> 8) & 0xff);
            buf[i + 2] = static_cast<uint8_t>((v >> 16) & 0xff);
            buf[i + 3] = static_cast<uint8_t>((v >> 24) & 0xff);
            i += 4;
        }
        while (i < length) {
            buf[i] = static_cast<uint8_t>(
                g_emulator->core->busRead8(g_emulator->core,
                                           static_cast<uint32_t>(addr + i)) & 0xff);
            i++;
        }
    }
    env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte*>(buf.data()));
    return result;
}

// Save / load state. Pattern matches src/platform/libretro/libretro.c
// retro_serialize / retro_unserialize: serialize into a growable in-memory
// VFile, then copy out to a Java byte[].

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_poketrek_emu_NativeEmulator_saveState(JNIEnv* env, jobject /*thiz*/) {
    if (!g_emulator || !g_emulator->core) return nullptr;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    VFile* vfm = VFileMemChunk(nullptr, 0);
    if (!vfm) return nullptr;
    bool ok = mCoreSaveStateNamed(g_emulator->core, vfm,
                                  SAVESTATE_SAVEDATA | SAVESTATE_RTC);
    if (!ok) {
        vfm->close(vfm);
        return nullptr;
    }
    ssize_t size = vfm->size(vfm);
    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    if (result) {
        vfm->seek(vfm, 0, SEEK_SET);
        std::vector<uint8_t> buf(size);
        vfm->read(vfm, buf.data(), size);
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(size),
                                reinterpret_cast<const jbyte*>(buf.data()));
    }
    vfm->close(vfm);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_poketrek_emu_NativeEmulator_loadState(JNIEnv* env, jobject /*thiz*/, jbyteArray data,
                                               jboolean withSavedata) {
    if (!g_emulator || !g_emulator->core || !data) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_emulator->mutex);
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buf(len);
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    VFile* vfm = VFileFromConstMemory(buf.data(), len);
    if (!vfm) return JNI_FALSE;
    // States carry a copy of the battery save. Restoring it would overwrite
    // the save file with an older in-game save, so (like mGBA's own default)
    // it's only restored when the caller says the save file is still blank.
    int flags = SAVESTATE_RTC | (withSavedata ? SAVESTATE_SAVEDATA : 0);
    bool ok = mCoreLoadStateNamed(g_emulator->core, vfm, flags);
    vfm->close(vfm);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Applies an xdelta (VCDIFF) patch to a base ROM entirely in memory and
// returns the patched bytes, or null on failure. Standalone — does not
// touch g_emulator, so no mutex. Mirrors tools/moneo/rom_swap/apply_patch.py:
// try a strict decode first, then retry with XD3_ADLER32_NOVER so
// non-canonical base dumps (e.g. [f1]-fixed) still apply.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_poketrek_emu_NativeEmulator_applyXdelta(JNIEnv* env, jobject /*thiz*/,
                                                 jbyteArray base, jbyteArray patch) {
    if (!base || !patch) return nullptr;

    jsize baseLen = env->GetArrayLength(base);
    jsize patchLen = env->GetArrayLength(patch);
    std::vector<uint8_t> baseBuf(baseLen), patchBuf(patchLen);
    env->GetByteArrayRegion(base, 0, baseLen, reinterpret_cast<jbyte*>(baseBuf.data()));
    env->GetByteArrayRegion(patch, 0, patchLen, reinterpret_cast<jbyte*>(patchBuf.data()));

    // GBA cartridge ROMs top out at 32 MiB; give the decode buffer that ceiling.
    constexpr usize_t kCap = 32u * 1024u * 1024u;
    std::vector<uint8_t> out(kCap);
    usize_t outLen = 0;

    int ret = xd3_decode_memory(patchBuf.data(), patchLen,
                                baseBuf.data(), baseLen,
                                out.data(), &outLen, kCap, 0);
    if (ret != 0) {
        LOGI("applyXdelta: strict decode failed (%d: %s); retrying ADLER32_NOVER",
             ret, xd3_strerror(ret));
        outLen = 0;
        ret = xd3_decode_memory(patchBuf.data(), patchLen,
                                baseBuf.data(), baseLen,
                                out.data(), &outLen, kCap, XD3_ADLER32_NOVER);
    }
    if (ret != 0) {
        LOGE("applyXdelta: decode failed (%d: %s)", ret, xd3_strerror(ret));
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(outLen));
    if (result) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(outLen),
                                reinterpret_cast<const jbyte*>(out.data()));
    }
    LOGI("applyXdelta: %d-byte base + %d-byte patch -> %u bytes",
         baseLen, patchLen, static_cast<unsigned>(outLen));
    return result;
}
