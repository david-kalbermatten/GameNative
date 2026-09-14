package app.gamenative.display

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.Window
import java.lang.ref.WeakReference
import kotlin.math.abs
import timber.log.Timber

object DisplayCadenceManager {
    private const val TAG = "DisplayCadenceManager"

    data class CadenceMatch(
        val modeId: Int,
        val refreshRateHz: Float,
        val multiplier: Int, // 1 for 1:1, 2 for 2:1, 3 for 3:1, etc.
    )

    private var activeWindowRef: WeakReference<Window>? = null
    private var lastTargetFps: Int = 0
    private var externalSwapControllerRef: WeakReference<app.gamenative.externaldisplay.ExternalDisplaySwapController>? = null

    fun registerExternalDisplayController(controller: app.gamenative.externaldisplay.ExternalDisplaySwapController?) {
        externalSwapControllerRef = controller?.let { WeakReference(it) }
    }

    fun unregisterExternalDisplayController() {
        externalSwapControllerRef = null
    }

    fun reapplyCurrentCadence(activity: Activity?, surfaceHolder: SurfaceHolder? = null) {
        applyCadence(activity = activity, surfaceHolder = surfaceHolder, targetFps = lastTargetFps)
    }

    private var lockedModeId: Int = 0
    var currentRefreshRateHz: Float = 0f
        private set

    /**
     * Finds the optimal display mode for a given target FPS.
     *
     * Priority:
     * 1. Exact 1:1 match (e.g. 40Hz for 40 FPS, 60Hz for 60 FPS, 120Hz for 120 FPS) -> Lowest Power & Perfect Pacing
     * 2. Lowest integer harmonic multiple (e.g. 120Hz [2:1] for 60 FPS, 90Hz [2:1] for 45 FPS, 120Hz [3:1] for 40 FPS)
     * 3. Round UP to smallest supported refresh rate >= target FPS (e.g. 88 FPS -> 90Hz, 91 FPS -> 120Hz)
     * 4. Clamp to highest available refresh rate if target FPS exceeds maximum panel capability (e.g. 180 FPS on 165Hz)
     */
    fun findBestDisplayMode(display: Display?, targetFps: Int): CadenceMatch? {
        if (display == null || targetFps <= 0) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

        val currentMode = display.mode ?: return null
        val supportedModes = display.supportedModes ?: return null

        // Preserve current display resolution while searching for refresh rates, sorted ascending
        val modes = supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight
        }.sortedBy { it.refreshRate }
        if (modes.isEmpty()) return null
        val target = targetFps.toFloat()

        // 1. Check for exact 1:1 match (e.g. 40Hz for 40 FPS, 60Hz for 60 FPS, 120Hz for 120 FPS)
        val exactMode = modes.firstOrNull { abs(it.refreshRate - target) < 1.0f }
        if (exactMode != null) {
            return CadenceMatch(exactMode.modeId, exactMode.refreshRate, 1)
        }

        // 2. Check for lowest integer harmonic multiple (e.g. 120Hz [2:1] for 60 FPS, 90Hz [2:1] for 45 FPS, 120Hz [3:1] for 40 FPS)
        for (mult in 2..4) {
            val desiredHz = target * mult
            val match = modes.firstOrNull { abs(it.refreshRate - desiredHz) < 1.0f }
            if (match != null) {
                return CadenceMatch(match.modeId, match.refreshRate, mult)
            }
        }

        // 3. Round UP: Smallest supported refresh rate that is >= target FPS (e.g. 88 FPS -> 90Hz, 91 FPS -> 120Hz)
        val roundUpMode = modes.firstOrNull { it.refreshRate >= target - 0.5f }
        if (roundUpMode != null) {
            return CadenceMatch(roundUpMode.modeId, roundUpMode.refreshRate, 0)
        }

        // 4. Target FPS exceeds highest panel refresh rate: clamp to maximum supported refresh rate
        val maxMode = modes.lastOrNull()
        if (maxMode != null) {
            return CadenceMatch(maxMode.modeId, maxMode.refreshRate, 0)
        }

        return null
    }

    /**
     * Apply cadence lock to the target window and surface.
     * Handles internal display as well as external presentation displays.
     */
    fun applyCadence(
        activity: Activity?,
        targetWindow: Window? = null,
        targetDisplay: Display? = null,
        surfaceHolder: SurfaceHolder? = null,
        targetFps: Int = 0,
    ) {
        lastTargetFps = targetFps
        val swapController = externalSwapControllerRef?.get()
        val isExternal = swapController?.isGameOnExternal == true
        val window = targetWindow ?: (if (isExternal) swapController?.presentationWindow else null) ?: activity?.window
        val display = targetDisplay
            ?: (if (isExternal) swapController?.presentationDisplay else null)
            ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity?.display else null)
            ?: window?.decorView?.display
            ?: @Suppress("DEPRECATION") window?.windowManager?.defaultDisplay
            ?: @Suppress("DEPRECATION") (activity?.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay

        val previousWindow = activeWindowRef?.get()
        if (previousWindow != null && previousWindow != window) {
            // Target window changed (e.g. transitioned between internal and external display)
            restoreWindowMode(previousWindow)
        }
        activeWindowRef = window?.let { WeakReference(it) }

        if (targetFps <= 0) {
            // Lock to maximum supported refresh rate instead of restoring to system default
            // (which Android typically resolves to 60Hz rather than panel maximum).
            val maxMatch: CadenceMatch? = if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val currentMode = display.mode
                val supportedModes = display.supportedModes
                if (currentMode != null && supportedModes != null) {
                    val sameSizeModes = supportedModes.filter {
                        it.physicalWidth == currentMode.physicalWidth &&
                            it.physicalHeight == currentMode.physicalHeight
                    }
                    val maxMode = sameSizeModes.maxByOrNull { it.refreshRate }
                    if (maxMode != null) CadenceMatch(maxMode.modeId, maxMode.refreshRate, 0) else null
                } else null
            } else null

            if (window != null && maxMatch != null) {
                val lp = window.attributes
                if (lp.preferredDisplayModeId != maxMatch.modeId) {
                    lp.preferredDisplayModeId = maxMatch.modeId
                    window.attributes = lp
                    Timber.tag(TAG).i(
                        "FPS limit disabled: locked to max refresh rate modeId=%d (%.1f Hz)",
                        maxMatch.modeId, maxMatch.refreshRateHz
                    )
                }
                lockedModeId = maxMatch.modeId
                currentRefreshRateHz = maxMatch.refreshRateHz
            } else if (window != null) {
                restoreWindowMode(window)
                lockedModeId = 0
                currentRefreshRateHz = display?.mode?.refreshRate ?: 0f
            } else {
                lockedModeId = 0
                currentRefreshRateHz = 0f
            }
            applySurfaceRate(surfaceHolder?.surface, 0f)
            val notifyHz = if (maxMatch != null) maxMatch.refreshRateHz else (display?.mode?.refreshRate ?: 60f)
            (app.gamenative.PluviaApp.xServerView?.renderer as? com.winlator.renderer.VulkanRenderer)
                ?.setFrameGenerationRefreshRate(notifyHz)
            return
        }

        if (window != null && display != null) {
            val match = findBestDisplayMode(display, targetFps)
            if (match != null) {
                val lp = window.attributes
                if (lp.preferredDisplayModeId != match.modeId) {
                    lp.preferredDisplayModeId = match.modeId
                    window.attributes = lp
                    lockedModeId = match.modeId
                    Timber.tag(TAG).i(
                        "Locked display '%s' (id=%d) to modeId=%d (%.1f Hz for %d FPS, ratio=%d)",
                        display.name, display.displayId, match.modeId, match.refreshRateHz, targetFps, match.multiplier
                    )
                }
                currentRefreshRateHz = match.refreshRateHz
                (app.gamenative.PluviaApp.xServerView?.renderer as? com.winlator.renderer.VulkanRenderer)
                    ?.setFrameGenerationRefreshRate(match.refreshRateHz)
            } else {
                restoreWindowMode(window)
                lockedModeId = 0
                val defaultHz = display.mode?.refreshRate ?: 60f
                currentRefreshRateHz = defaultHz
                (app.gamenative.PluviaApp.xServerView?.renderer as? com.winlator.renderer.VulkanRenderer)
                    ?.setFrameGenerationRefreshRate(defaultHz)
            }
        }

        applySurfaceRate(surfaceHolder?.surface, targetFps.toFloat())
    }

    /**
     * Restore original display modes and reset surface frame rate hint.
     */
    fun restoreCadence(activity: Activity?, surfaceHolder: SurfaceHolder? = null) {
        val window = activeWindowRef?.get() ?: activity?.window
        if (window != null) {
            restoreWindowMode(window)
        }
        activeWindowRef = null
        applySurfaceRate(surfaceHolder?.surface, 0f)
        lastTargetFps = 0
        lockedModeId = 0
        currentRefreshRateHz = 0f
    }

    private fun restoreWindowMode(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val lp = window.attributes
                if (lp.preferredDisplayModeId != 0) {
                    lp.preferredDisplayModeId = 0
                    window.attributes = lp
                    Timber.tag(TAG).i("Restored default display mode on window")
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Failed to restore window mode")
            }
        }
    }

    private fun applySurfaceRate(surface: Surface?, rate: Float) {
        if (surface == null || !surface.isValid) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    rate,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    if (rate > 0f) Surface.CHANGE_FRAME_RATE_ALWAYS else Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed to set surface frame rate: %.1f", rate)
        }
    }
}
