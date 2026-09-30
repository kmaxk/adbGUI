package process

import java.io.File

/** Result of a finished process with stdout and stderr kept apart. */
data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val isSuccess get() = exitCode == 0
}

/** Blocking process helpers; callers wrap them in `withContext(Dispatchers.IO)`. */
object Exec {
    /** Runs [args] with stderr merged into stdout and returns the combined text. */
    fun run(args: List<String>): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    /** Runs [args] and returns raw stdout bytes (e.g. `adb exec-out screencap`). */
    fun runBytes(args: List<String>): ByteArray {
        val process = ProcessBuilder(args).start()
        val bytes = process.inputStream.readBytes()
        process.waitFor()
        return bytes
    }

    /**
     * Runs [args] keeping stdout clean, for tools that print JSON to stdout and
     * progress to stderr (`devicectl --json-output -`). Stderr goes through a temp
     * file so a chatty stderr can't block the process.
     */
    fun capture(args: List<String>, stdin: String? = null): ExecResult {
        val errFile = File.createTempFile("adbgui-exec", ".err")
        try {
            val process = ProcessBuilder(args).redirectError(errFile).start()
            process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
            val stdout = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            return ExecResult(exit, stdout, errFile.readText())
        } finally {
            errFile.delete()
        }
    }
}
