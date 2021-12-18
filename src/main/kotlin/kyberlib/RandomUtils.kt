package kyberlib

import java.io.File
import java.io.IOException

// random misc functions

/**
 * Executes string as command in terminal
 * @param workingDir the directory the terminal will be located
 */
fun String.runCommand(workingDir: File) {  // this doesn't work
    try {
        println("here")
        val parts = this.split("\\s".toRegex())
        println("parts: $parts")
        val proc = ProcessBuilder(*parts.toTypedArray()).apply {
            directory(workingDir)
            redirectOutput(ProcessBuilder.Redirect.PIPE)
            redirectError(ProcessBuilder.Redirect.PIPE)
        }
        proc.start()
        proc.redirectOutput(File("out.txt"))
    }
    catch(e: IOException) {
        e.printStackTrace()
    }
}

val Int.even: Boolean
    get() = this.rem(2) == 0

val Int.odd: Boolean
    get() = !even