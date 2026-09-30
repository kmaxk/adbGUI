package device

enum class Platform { Android, IOS }

/** A target the app can act on: an adb device, an iOS simulator or an iPhone. */
interface Device {
    /** adb serial or iOS UDID; stable across polls, used to keep the selection. */
    val id: String
    val name: String
    val platform: Platform
    /** False for shut-down simulators; screens that need a running device are replaced by a boot prompt. */
    val isReady: Boolean
}
