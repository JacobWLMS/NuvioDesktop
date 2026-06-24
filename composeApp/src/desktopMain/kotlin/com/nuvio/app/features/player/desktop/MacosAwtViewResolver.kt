package com.nuvio.app.features.player.desktop

import java.awt.Component
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object AwtNativeViewResolver {
    fun resolveNativeViewPointer(component: Component): Long =
        when (DesktopHostOs.current) {
            DesktopHostOs.MACOS -> MacosAwtViewResolver.resolveNativeViewPointer(component)
            DesktopHostOs.WINDOWS -> WindowsAwtViewResolver.resolveNativeViewPointer(component)
            DesktopHostOs.LINUX -> LinuxAwtViewResolver.resolveNativeViewPointer(component)
            else -> error("Native desktop playback is not implemented for ${DesktopHostOs.current}.")
        }
}

private object MacosAwtViewResolver {
    private val componentPeerField: Field by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativeViewPointer(component: Component): Long {
        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        val platformWindow = invokeObject(peer, "getPlatformWindow")
        val contentView = invokeObject(platformWindow, "getContentView")
        val pointer = invokeLong(contentView, "getAWTView")
        if (pointer == 0L) {
            error("macOS AWT view pointer was zero.")
        }
        return pointer
    }

    private fun findMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Method $name was not found on ${type.name}.")
    }

    private fun invokeObject(target: Any, methodName: String): Any =
        findMethod(target.javaClass, methodName).invoke(target)
            ?: error("$methodName returned null.")

    private fun invokeLong(target: Any, methodName: String): Long =
        (findMethod(target.javaClass, methodName).invoke(target) as Number).toLong()
}

private object WindowsAwtViewResolver {
    private val componentPeerField: Field by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativeViewPointer(component: Component): Long {
        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        val pointer = invokeLong(peer, "getHWnd")
        if (pointer == 0L) {
            error("Windows AWT HWND pointer was zero.")
        }
        return pointer
    }

    private fun findMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Method $name was not found on ${type.name}.")
    }

    private fun invokeLong(target: Any, methodName: String): Long =
        (findMethod(target.javaClass, methodName).invoke(target) as Number).toLong()
}

/**
 * Linux X11: resolves the native X11 Window ID from AWT peer.
 * This allows mpv to render directly into the X11 window with vo=gpu-next,
 * bypassing the expensive CPU frame copy pipeline.
 *
 * Primary method: JAWT (Java AWT Native Interface) — stable, official API.
 * Fallback: reflection on internal XBaseWindow.window field.
 */
private object LinuxAwtViewResolver {
    private val componentPeerField: Field by lazy {
        Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
    }

    fun resolveNativeViewPointer(component: Component): Long {
        if (DesktopHostOs.isWayland) {
            error("GPU-direct wid mode is not supported on Wayland; use SW rendering fallback.")
        }

        // Primary: JAWT — works on all JDK versions without --add-opens
        val jawtResult = runCatching { NativePlayerBridge.getX11WindowId(component) }.getOrElse { e ->
            System.err.println("[NUVIO_X11] JAWT failed: ${e.message}")
            null
        }
        if (jawtResult != null && jawtResult != 0L) {
            System.err.println("[NUVIO_X11] resolved via JAWT: 0x${jawtResult.toString(16)}")
            return jawtResult
        }

        // Fallback: reflection on peer's internal 'window' field (XBaseWindow stores XID there)
        val peer = componentPeerField.get(component)
            ?: error("AWT component peer is not ready for native playback.")

        System.err.println("[NUVIO_X11] JAWT returned $jawtResult, trying field reflection on ${peer.javaClass.name}")

        val fieldResult = runCatching { getWindowField(peer) }.getOrNull()
        if (fieldResult != null && fieldResult != 0L) {
            System.err.println("[NUVIO_X11] resolved via 'window' field: 0x${fieldResult.toString(16)}")
            return fieldResult
        }

        // Last resort: try method-based approach
        val methodNames = listOf("getWindow", "getContentWindow", "getWidget")
        for (name in methodNames) {
            val pointer = runCatching { invokeLong(peer, name) }.getOrNull()
            if (pointer != null && pointer != 0L) {
                System.err.println("[NUVIO_X11] resolved via method '$name': 0x${pointer.toString(16)}")
                return pointer
            }
        }
        error("Linux AWT X11 window pointer could not be resolved. " +
            "JAWT returned ${jawtResult ?: "exception"}, " +
            "Peer=${peer.javaClass.name}, methods tried: $methodNames")
    }

    /** Read the 'window' field from XBaseWindow hierarchy — holds the X11 Window (XID). */
    private fun getWindowField(peer: Any): Long {
        var clazz: Class<*>? = peer.javaClass
        while (clazz != null) {
            runCatching {
                val field = clazz!!.getDeclaredField("window")
                field.isAccessible = true
                return (field.get(peer) as Number).toLong()
            }
            clazz = clazz.superclass
        }
        return 0L
    }

    private fun findMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredMethod(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Method $name was not found on ${type.name}.")
    }

    private fun invokeLong(target: Any, methodName: String): Long =
        (findMethod(target.javaClass, methodName).invoke(target) as Number).toLong()
}
