package win.libpaste.pasteularity.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class UiMessage(val text: String, val isError: Boolean)

/** App-wide snackbar channel, so every feature reports results the same way. */
class UiMessenger {
    private val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val events = messages.asSharedFlow()

    fun success(text: String) {
        messages.tryEmit(UiMessage(text, isError = false))
    }

    fun error(text: String) {
        messages.tryEmit(UiMessage(text, isError = true))
    }
}
