# Linux NVIDIA EGL Handoff

## Goal

Find a real fix for Linux NVIDIA playback under a Wayland desktop where the JVM/AWT/Compose app is running through XWayland. "Real fix" means the app can keep Skiko UI rendering hardware accelerated and still run the Linux mpv player in `gpuMode=2` instead of falling back to `gpuMode=0`.

The existing software-Skiko workaround is useful for proving the player can use NVIDIA GL when Skiko does not own a GL context, but it makes the desktop UI laggy and should be treated as a workaround, not the final fix.

## Contribution Constraints

Read `CONTRIBUTING.md` before continuing. The relevant rules are:

- Keep this narrowly scoped to a reproducible Linux playback bug.
- Do not bundle unrelated UI changes, dependency changes, refactors, auth fixes, or cleanup.
- Document how behavior was tested.
- Any large architecture change or dependency addition needs maintainer discussion first.

## Branch State

Fork: `JacobWLMS/NuvioDesktop`

Upstream PR being investigated: `NuvioMedia/NuvioDesktop#142`, branch `linux-gpu-player`.

Current branch:

- `jacob/linux-nvidia-egl-context-fix`
- Tracks `origin/jacob/linux-nvidia-egl-context-fix`
- Built from the workaround state at commit `ba3dad17`
- Includes temporary diagnostic changes in:
  - `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/PlayerEngine.desktop.kt`
  - `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerBridge.kt`
  - `composeApp/src/desktopMain/native/linux/player_bridge.c`

Workaround branch:

- `jacob/linux-nvidia-skiko-software-workaround`
- Also pushed to the fork
- Contains the two workaround commits:
  - `fd9f1d9b fix(linux/nvidia): force Skiko software rendering so the player's EGL context works`
  - `ba3dad17 fix(linux/nvidia): try headless EGL_PLATFORM_DEVICE_EXT first on the NVIDIA branch`

Important local stash:

- `stash@{0}: On claude/nvidia-egl-driver-issues-d7zlih: local auth/build test support before nvidia branch split`
- This contains unrelated local auth/build test changes. Do not drop it unless the user asks.

## Current Test Machine

- Fedora GNOME Wayland session with the app running as an X11/AWT app through XWayland
- NVIDIA RTX 4060 Ti 16GB
- NVIDIA driver observed in logs: `595.80`

## Linux Player Architecture

Linux video uses `mpv` through the libmpv render API in:

- `composeApp/src/desktopMain/native/linux/player_bridge.c`

The Linux GPU path currently does this:

1. mpv renders offscreen on its own render thread.
2. The player bridge creates its own EGL display/context/surface.
3. mpv renders into an OpenGL FBO.
4. The bridge calls `glReadPixels`.
5. Kotlin wraps the bytes with `Image.makeRaster`.
6. Compose draws that raster image on a `Canvas`.

When Skiko is using OpenGL, the process has two independent EGL/GL contexts:

- Skiko UI context
- mpv/player offscreen context

The working theory is that NVIDIA proprietary EGL under XWayland refuses the player context while Skiko's context is live in the same process.

## What Was Changed

### `fd9f1d9b`

- On Linux, `Main.kt` sets `skiko.renderApi=SOFTWARE` unless the user explicitly set `SKIKO_RENDER_API` or `-Dskiko.renderApi`.
- This uses the correct Skiko property. The earlier PR test used `skia.renderApi`, which Skiko does not read.
- Restored GBM/DRM as the first EGL path so Intel/AMD keep the original VAAPI-friendly route.

### `ba3dad17`

- In the NVIDIA fallback chain after GBM `eglMakeCurrent` fails, tries `EGL_PLATFORM_DEVICE_EXT` first.
- Device platform is headless and should avoid X11/Wayland/XWayland.
- On this NVIDIA machine it still fails when Skiko OpenGL is active, but it is still a sensible first fallback.

### Current Uncommitted Diagnostic

Added a temporary JNI probe:

- `NativePlayerBridge.debugCurrentEglContext(handle, tag)`
- Called once from the Linux Compose `Canvas` draw block
- Logs `eglGetCurrentDisplay`, `eglGetCurrentContext`, draw/read surfaces, GL version, and renderer

This is diagnostic only. Do not ship it unchanged unless it is turned into intentional debug logging.

## What Works

### Workaround Path: Skiko Software + Player GPU

Default run on the workaround branch, with Skiko forced to software by `Main.kt`, successfully uses NVIDIA GPU rendering in the player:

```text
[player_bridge] create: mpv initialized (gpuMode=2)
[player_bridge] EGL: initialized 1.5 via GBM
[player_bridge] render thread: eglMakeCurrent=1 (err=0x0)
[player_bridge] render thread: GL=3.3.0 NVIDIA 595.80 renderer=NVIDIA GeForce RTX 4060 Ti/PCIe/SSE2
[player_bridge] create: returning handle (gpuMode=2)
```

The user confirmed video playback in this mode.

### Codec Install

The user installed Fedora codec packages. A fresh process showed native and NVIDIA decoders available, including H.264, HEVC, AV1 CUDA/CUVID paths, `ffmpeg`, `ffmpeg-libs`, `libavcodec-freeworld`, `x264-libs`, `x265-libs`, `mpv-libs`, and `mpv-devel`.

This likely resolves many "audio but black video" cases, but codec coverage should still be tested against several real streams.

### Zink From PR Comments

PR tester `FeelThePoveR` reported that Mesa Zink worked with `gpuMode=2` on NVIDIA:

```bash
__GLX_VENDOR_LIBRARY_NAME=mesa \
MESA_LOADER_DRIVER_OVERRIDE=zink \
./gradlew run
```

Their log showed:

```text
render thread: eglMakeCurrent=1 (err=0x0)
render thread: GL render context created successfully
create: returning handle (gpuMode=2)
```

If adopted, Zink must be selected before the process starts. GLVND/Mesa vendor selection cannot be reliably changed from inside the render thread after Skiko or any EGL user has already initialized GL.

## What Does Not Work

### Skiko OpenGL + NVIDIA Proprietary EGL

Forcing Skiko back to OpenGL reproduces the original issue. Command used:

```bash
timeout 90s env \
  JAVA_TOOL_OPTIONS='-Dskiko.renderApi=OPENGL' \
  SKIKO_RENDER_API=OPENGL \
  NUVIO_DESKTOP_SMOKE_PLAYER_URL=/tmp/nuvio-smoke.mp4 \
  ./gradlew --no-daemon --no-configuration-cache :composeApp:run \
  > /tmp/nuvio-smoke-opengl-context-probe.log 2>&1
```

Relevant result:

```text
[player_bridge] create: mpv initialized (gpuMode=2)
[player_bridge] EGL: GBM eglMakeCurrent pre-check failed (err=0x3000) - almost always NVIDIA
[player_bridge] EGL: trying EGL_PLATFORM_DEVICE_EXT fallback
[player_bridge] EGL: Device[0] eglMakeCurrent failed (surface=pbuffer, err=0x3000)
[player_bridge] EGL: Device[2] eglMakeCurrent failed (surface=pbuffer, err=0x3000)
[player_bridge] EGL: all Device Platform attempts failed
[player_bridge] EGL: NVIDIA override eglMakeCurrent failed (err=0x3000)
[player_bridge] EGL: Wayland eglMakeCurrent failed (err=0x3000)
[player_bridge] EGL: shared context ready (display=..., surface=pbuffer)
[player_bridge] render thread: eglMakeCurrent=0 (err=0x3000)
[player_bridge] render thread: eglMakeCurrent FAILED, falling back to SW
[player_bridge] create: returning handle (gpuMode=0)
```

The user saw video playing in this run, but the log proves that playback was the software fallback (`gpuMode=0`), not the real GPU path.

### Same-Context Canvas Hypothesis

The diagnostic checked whether the Compose `Canvas` draw callback has Skiko's EGL context current. It does not:

```text
[player_bridge] current EGL probe (compose-canvas): display=(nil) context=(nil) draw=(nil) read=(nil) GL=null renderer=null
```

That means a simple "render mpv from inside Canvas using Skiko's current GL context" path is not directly viable. Compose/Skia appears not to expose a current EGL context to this callback.

### `skia.renderApi=SOFTWARE`

The PR thread tried:

```bash
JAVA_TOOL_OPTIONS=-Dskia.renderApi=SOFTWARE
```

That property name is wrong for Skiko. It is a no-op for this app. The correct key is:

```bash
JAVA_TOOL_OPTIONS=-Dskiko.renderApi=SOFTWARE
```

or:

```bash
SKIKO_RENDER_API=SOFTWARE
```

### `EGL_PLATFORM=wayland`

From PR comments, `EGL_PLATFORM=wayland` removed some DRI2 warnings but did not fix `eglMakeCurrent` failure or prevent fallback to `gpuMode=0`.

### X11 Window ID Path

The older `gpuMode=1` / native window ID path did not initialize reliably under XWayland or a full X11 session in the PR tester's environment. It also conflicts with the current Linux design goal of drawing video into Compose so controls overlay correctly.

## Important Error-Code Note

Several earlier notes called `0x3000` `EGL_NOT_INITIALIZED`. That is incorrect. Khronos defines:

- `0x3000` = `EGL_SUCCESS`
- `0x3001` = `EGL_NOT_INITIALIZED`

The observed problem is still real: `eglMakeCurrent` returns false. But the reported `eglGetError()` value is `EGL_SUCCESS`, so treat the numeric error as unhelpful/stale rather than as `EGL_NOT_INITIALIZED`.

## Useful Commands

Build only the Linux bridge:

```bash
./gradlew --no-daemon --no-configuration-cache :composeApp:buildLinuxPlayerBridge
```

Run the full app normally:

```bash
./gradlew --no-daemon --no-configuration-cache :composeApp:run
```

Run the OpenGL smoke test that reproduces the real issue:

```bash
timeout 90s env \
  JAVA_TOOL_OPTIONS='-Dskiko.renderApi=OPENGL' \
  SKIKO_RENDER_API=OPENGL \
  NUVIO_DESKTOP_SMOKE_PLAYER_URL=/tmp/nuvio-smoke.mp4 \
  ./gradlew --no-daemon --no-configuration-cache :composeApp:run \
  > /tmp/nuvio-smoke-opengl-context-probe.log 2>&1
```

Extract important native log lines:

```bash
rg -n "current EGL probe|create:|EGL:|eglMakeCurrent|render thread|gpuMode|GL=|renderer=|falling back|mpv_render_context" /tmp/nuvio-smoke-opengl-context-probe.log
```

Run with Zink, the currently known GPU-rendering workaround that keeps Skiko off NVIDIA proprietary EGL:

```bash
__GLX_VENDOR_LIBRARY_NAME=mesa \
__EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json \
MESA_LOADER_DRIVER_OVERRIDE=zink \
./gradlew --no-daemon --no-configuration-cache :composeApp:run
```

## Likely Next Directions

1. Decide whether a launcher-level NVIDIA/Zink path is acceptable.
   - It keeps UI hardware accelerated and can preserve `gpuMode=2`.
   - It must be conditional and process-start-time only.
   - It depends on working Mesa Zink and Vulkan support.

2. If avoiding Zink, look for an official Skiko/Compose way to render custom OpenGL work inside Skiko's own context.
   - The current `Canvas` callback does not expose a current EGL context.
   - A same-context mpv render path would require deeper Skiko interop than the current Compose draw callback.

3. Treat the forced `skiko.renderApi=SOFTWARE` branch as a fallback/workaround.
   - It proves the mpv player GPU path itself works on this hardware.
   - It causes UI lag and does not solve the original "Skiko OpenGL plus mpv GPU" conflict.

4. Keep decode testing separate from EGL testing.
   - Decode options such as `hwdec=auto-copy`, `nvdec-copy`, `hwdec=no`, and `hwdec-codecs=all` help diagnose black frames/codecs.
   - They do not fix `eglMakeCurrent` failing before the mpv GL render path is available.
