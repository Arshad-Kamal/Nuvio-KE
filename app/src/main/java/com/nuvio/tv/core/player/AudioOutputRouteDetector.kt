package com.nuvio.tv.core.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.C
import java.util.Locale

internal data class AudioOutputRoute(
    val key: String,
    val label: String,
    val isBluetooth: Boolean
)

/**
 * Detects the active (or preferred) media audio output route.
 *
 * Bluetooth handling follows Media3 1.8.0 [AudioCapabilities] policy:
 * - When the media route is Bluetooth, only PCM is safe (A2DP/LE Audio cannot carry
 *   TrueHD / Atmos / DTS-HD bitstream passthrough).
 * - Device type set matches Media3 `Api23.getAllBluetoothDeviceTypes()`.
 *
 * On API 33+, prefers [AudioManager.getAudioDevicesForAttributes] so a BT remote that
 * appears in [AudioManager.getDevices] does not steal the route from HDMI while media
 * is still going to the TV.
 */
internal object AudioOutputRouteDetector {
    @SuppressLint("NewApi")
    fun detect(context: Context): AudioOutputRoute? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return null

        val mediaRouted = detectMediaRoutedDevice(audioManager)
        if (mediaRouted != null) {
            return mediaRouted.toRoute()
        }

        // Fallback: rank connected output devices. Bluetooth media sinks win when present
        // so headphone connect is detected even if attribute routing is unavailable.
        val device = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .minByOrNull { routeRank(it.type) }
            ?.takeIf { routeRank(it.type) < ROUTE_RANK_UNSUPPORTED }

        return device?.toRoute()
    }

    /**
     * Whether media audio is currently (or will be) routed to a Bluetooth sink.
     * Same semantic Media3 uses before returning [AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES].
     */
    fun isBluetoothMediaOutput(context: Context): Boolean {
        return detect(context)?.isBluetooth == true
    }

    /** Whether an [AudioOutputRoute.key] names an HDMI output (HDMI, ARC or eARC). */
    fun isHdmiRouteKey(key: String): Boolean = key.startsWith("type:hdmi")

    @SuppressLint("NewApi")
    private fun detectMediaRoutedDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            val devices = audioManager.getAudioDevicesForAttributes(mediaMovieAudioAttributes())
            devices.firstOrNull()
        }.getOrNull()
    }

    @SuppressLint("NewApi")
    private fun AudioDeviceInfo.toRoute(): AudioOutputRoute {
        val typeLabel = typeName(type)
        val product = productName?.toString()?.trim().orEmpty()
        val label = product.ifBlank { typeLabel }
        val normalizedLabel = label
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^a-z0-9._:-]"), "")
            .ifBlank { typeLabel.lowercase(Locale.US) }

        return AudioOutputRoute(
            key = "type:${typeName(type).lowercase(Locale.US)}|name:$normalizedLabel",
            label = label,
            isBluetooth = isBluetoothType(type)
        )
    }

    /**
     * Lower rank = preferred when scanning all outputs (Bluetooth media first).
     * Aligns with Media3 Bluetooth types for ranking, not with HDMI-first detection
     * (API 33+ attribute routing handles the "remote vs HDMI" case).
     */
    @SuppressLint("NewApi")
    private fun routeRank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 1
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> 2
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> 3
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 4
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> 10
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 20
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 30
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 40
        else -> ROUTE_RANK_UNSUPPORTED
    }

    /**
     * Media3 1.8.0 `AudioCapabilities.Api23.getAllBluetoothDeviceTypes()` parity.
     *
     * Type constants are matched regardless of the process SDK level so unit tests and
     * older devices that still surface a newer type value behave consistently.
     */
    @SuppressLint("NewApi")
    fun isBluetoothType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> true
        else -> false
    }

    @SuppressLint("NewApi")
    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "ble_broadcast"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi_arc"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi_earc"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb_accessory"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built_in_speaker"
        else -> "type_$type"
    }

    private const val ROUTE_RANK_UNSUPPORTED = 100

    /**
     * Bluetooth-sink capabilities: PCM 16-bit only like Media3
     * [AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES], with 8-channel headroom — no encoded passthrough.
     */
    // why: the only public constructor; Media3 offers no non-deprecated way to build a fixed
    // capability set, and DEFAULT_AUDIO_CAPABILITIES allows 10 channels, not the 8 chosen here.
    @Suppress("DEPRECATION")
    fun bluetoothPcmOnlyCapabilities(): androidx.media3.exoplayer.audio.AudioCapabilities {
        // Stereo is what A2DP/LE Audio effectively delivers; keep a small headroom for
        // multi-channel PCM that the platform will downmix before the BT stack.
        return androidx.media3.exoplayer.audio.AudioCapabilities(
            intArrayOf(C.ENCODING_PCM_16BIT),
            /* maxChannelCount= */ 8
        )
    }
}
