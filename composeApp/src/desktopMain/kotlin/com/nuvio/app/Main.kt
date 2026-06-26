package com.nuvio.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.desktop.DesktopAppFullscreenController
import com.nuvio.app.features.player.desktop.applyNativeDesktopWindowChrome
import com.nuvio.app.features.player.desktop.installDesktopAppFullscreenShortcuts
import com.nuvio.app.features.player.desktop.preloadNativePlayerBridgeAsync
import com.nuvio.app.features.player.desktop.registerDesktopAppFullscreenToggle
import java.awt.Color as AwtColor
import javax.swing.JComponent

private val NuvioDesktopNativeBackground = AwtColor(0x0D, 0x0D, 0x0D)
private const val NuvioDesktopIconPath = "icons/nuvio-app-icon.png"
private const val MacosDarkAquaAppearance = "NSAppearanceNameDarkAqua"

fun main() {
    configureDesktopChrome()
    preloadNativePlayerBridgeAsync()

    application {
        val smokePlayerUrl = (
            System.getProperty("nuvio.desktop.smokePlayerUrl")
                ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
            )
            ?.takeIf { it.isNotBlank() }
        val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
        val fullscreenController = remember { DesktopAppFullscreenController() }

        Window(
            onCloseRequest = ::exitApplication,
            title = if (smokePlayerUrl == null) "Nuvio" else "Nuvio Player Smoke",
            state = windowState,
            icon = painterResource(NuvioDesktopIconPath),
        ) {
            SideEffect {
                window.background = NuvioDesktopNativeBackground
                window.rootPane.background = NuvioDesktopNativeBackground
                window.contentPane.background = NuvioDesktopNativeBackground
                (window.contentPane as? JComponent)?.isOpaque = true
            }
            LaunchedEffect(window) {
                applyNativeDesktopWindowChrome(window)
            }
            DisposableEffect(window, windowState) {
                val unregisterFullscreenToggle = registerDesktopAppFullscreenToggle(
                    handler = { targetWindow ->
                        if (targetWindow == null || targetWindow === window) {
                            fullscreenController.toggle(window, windowState)
                        }
                    },
                    isFullscreen = { targetWindow ->
                        (targetWindow == null || targetWindow === window) &&
                            fullscreenController.isFullscreen(window, windowState)
                    },
                )
                val uninstallFullscreenShortcuts = installDesktopAppFullscreenShortcuts(window)
                onDispose {
                    fullscreenController.dispose(window)
                    uninstallFullscreenShortcuts()
                    unregisterFullscreenToggle()
                }
            }

            if (smokePlayerUrl == null) {
                App()
            } else {
                PlatformPlayerSurface(
                    sourceUrl = smokePlayerUrl,
                    modifier = Modifier.fillMaxSize(),
                    onControllerReady = {},
                    onSnapshot = {},
                    onError = {},
                )
            }
        }
    }
}

private fun configureDesktopChrome() {
    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
        System.setProperty("apple.awt.application.appearance", MacosDarkAquaAppearance)
    }
    if (System.getProperty("os.name").contains("linux", ignoreCase = true)) {
        // Force Skiko (the Compose UI renderer) to render in software on Linux.
        //
        // The desktop video player runs mpv in an offscreen EGL context on its own
        // render thread (see desktopMain/native/linux/player_bridge.c). NVIDIA's EGL
        // driver refuses to make-current a second, independent context in a process
        // that already holds Skiko's OpenGL context — every offscreen path (GBM,
        // EGL Device Platform, vendor lib, pbuffer) fails identically with
        // EGL_NOT_INITIALIZED (0x3000). With Skiko in software mode the player's
        // context is the only EGL context in the process, so eglMakeCurrent succeeds.
        //
        // This is effectively free for the video pipeline: Linux frames are already
        // read back to CPU (glReadPixels) and composited as raster Skia images, and
        // video decode stays GPU-accelerated via hwdec=auto-copy (nvdec / VAAPI).
        // Only the lightweight UI moves to CPU compositing.
        //
        // The property is "skiko.renderApi" (Skiko), NOT "skia.renderApi" — an earlier
        // attempt used the wrong key and silently had no effect. Skiko also honours the
        // SKIKO_RENDER_API env var, which takes precedence, so we don't clobber an
        // explicit override.
        if (System.getProperty("skiko.renderApi") == null) {
            System.setProperty("skiko.renderApi", "SOFTWARE")
        }
    }
}
