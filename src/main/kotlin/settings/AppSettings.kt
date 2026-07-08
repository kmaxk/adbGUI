package settings

import java.util.prefs.Preferences

object AppSettings {
    private val prefs = Preferences.userNodeForPackage(AppSettings::class.java)

    var adbPath: String
        get() = prefs.get("adbPath", "")
        set(value) = prefs.put("adbPath", value)
}
