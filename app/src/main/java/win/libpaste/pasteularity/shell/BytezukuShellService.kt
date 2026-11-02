package win.libpaste.pasteularity.shell

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Runs inside ByteZuku's app_process as uid 2000. Instantiated by ByteZuku, not by the app. */
class BytezukuShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): ParcelFileDescriptor {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        thread(isDaemon = true) {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { process.inputStream.copyTo(it) }
            }
            process.destroy()
        }
        thread(isDaemon = true) { killWhenReaderCloses(process, writeEnd) }
        return readEnd
    }

    private fun killWhenReaderCloses(process: Process, writeEnd: ParcelFileDescriptor) {
        val poll = StructPollfd().apply {
            fd = writeEnd.fileDescriptor
            events = 0
        }
        while (process.isAlive) {
            val closed = runCatching {
                Os.poll(arrayOf(poll), POLL_INTERVAL_MS)
                poll.revents.toInt() and OsConstants.POLLERR != 0
            }.getOrDefault(true)
            if (closed) {
                process.destroy()
                return
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000
    }
}
