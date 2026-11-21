package com.tprlab.teletube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi.*
import org.drinkless.tdlib.TdApi.Function
import org.drinkless.tdlib.TdApi.Error

internal class TelegramError(val code: Int, message: String) : Exception(message)

internal class TelegramClient(
    private val scope: CoroutineScope,
    private val onUpdate: suspend (Object) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    // TDLib delivers updates and responses in order. Apply each before handling the next.
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var client: Client? = null

    init {
        scope.launch {
            for (event in events) {
                try {
                    event()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    onFailure(failure)
                }
            }
        }
    }

    fun start() {
        Client.execute(SetLogVerbosityLevel(0))
        client = Client.create(
            { update -> events.trySend { onUpdate(update) } },
            { failure -> events.trySend { onFailure(failure) } },
            { failure -> events.trySend { onFailure(failure) } },
        )
    }

    suspend fun <T : Object> call(request: Function<T>): T = apply(request) { it }

    suspend fun <T : Object, R> apply(request: Function<T>, consume: suspend (T) -> R): R {
        val active = client ?: throw IllegalStateException("Telegram is disconnected.")
        // Only the network response is timed. Once it arrives, finish its ordered database writes.
        val arrived = CompletableDeferred<Unit>()
        val result = CompletableDeferred<R>()
        active.send(request) { response ->
            arrived.complete(Unit)
            events.trySend {
                if (!result.isActive) return@trySend
                if (response is Error) {
                    result.completeExceptionally(TelegramError(response.code, response.message))
                } else {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        result.complete(consume(response as T))
                    } catch (failure: Exception) {
                        result.completeExceptionally(failure)
                    }
                }
            }
        }
        try {
            if (withTimeoutOrNull(45_000) { arrived.await() } == null) {
                throw TelegramError(408, "Telegram took too long to respond. Try again.")
            }
            return result.await()
        } finally {
            result.cancel()
        }
    }
}
