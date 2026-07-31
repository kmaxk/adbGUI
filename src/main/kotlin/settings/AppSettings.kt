package settings

import java.util.prefs.Preferences

object AppSettings {
    private val prefs = Preferences.userNodeForPackage(AppSettings::class.java)
    private const val MAX_DEEPLINK_HISTORY = 50
    private const val LIST_SEP = ""

    var adbPath: String
        get() = prefs.get("adbPath", "")
        set(value) = prefs.put("adbPath", value)

    private var deeplinkHistoryRaw: String
        get() = prefs.get("deeplinkHistory", "")
        set(value) = prefs.put("deeplinkHistory", value)

    fun deeplinkHistory(): List<String> =
        deeplinkHistoryRaw.split("\n").filter { it.isNotBlank() }

    fun addDeeplink(url: String) {
        val updated = deeplinkHistory().toMutableList()
        updated.remove(url)
        updated.add(0, url)
        deeplinkHistoryRaw = updated.take(MAX_DEEPLINK_HISTORY).joinToString("\n")
    }

    fun batchNames(): List<String> =
        prefs.get("batchNames", "").split(LIST_SEP).filter { it.isNotBlank() }

    fun batchScript(name: String): String = prefs.get("batch_$name", "")

    fun saveBatch(name: String, script: String) {
        val names = batchNames().toMutableList()
        if (name !in names) {
            names.add(name)
            prefs.put("batchNames", names.joinToString(LIST_SEP))
        }
        prefs.put("batch_$name", script)
    }

    fun deleteBatch(name: String) {
        prefs.put("batchNames", batchNames().minus(name).joinToString(LIST_SEP))
        prefs.remove("batch_$name")
    }
}
