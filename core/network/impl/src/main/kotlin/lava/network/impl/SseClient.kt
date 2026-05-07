package lava.network.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

sealed interface SseEvent {
    data class Event(val type: String, val data: String) : SseEvent
    data class Error(val message: String) : SseEvent
    data object StreamEnd : SseEvent
}

class SseClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build(),
) {
    fun connect(url: String, headers: Map<String, String> = emptyMap()): Flow<SseEvent> = callbackFlow {
        val requestBuilder = Request.Builder().url(url).header("Accept", "text/event-stream")
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        val call = client.newCall(requestBuilder.build())
        val response: Response = try {
            call.execute()
        } catch (e: Exception) {
            trySend(SseEvent.Error("Connection failed: ${e.message}"))
            close()
            return@callbackFlow
        }

        if (!response.isSuccessful) {
            trySend(SseEvent.Error("HTTP ${response.code}: ${response.message}"))
            response.close()
            close()
            return@callbackFlow
        }

        val body = response.body ?: run {
            trySend(SseEvent.Error("Empty response body"))
            response.close()
            close()
            return@callbackFlow
        }

        val reader = BufferedReader(InputStreamReader(body.byteStream()))
        var eventType = ""
        val dataBuilder = StringBuilder()

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: break
                when {
                    currentLine.startsWith("event: ") -> {
                        eventType = currentLine.removePrefix("event: ").trim()
                    }
                    currentLine.startsWith("data: ") -> {
                        dataBuilder.append(currentLine.removePrefix("data: "))
                    }
                    currentLine.isEmpty() -> {
                        if (dataBuilder.isNotEmpty()) {
                            val event = if (eventType == "stream_end") {
                                SseEvent.StreamEnd
                            } else {
                                SseEvent.Event(eventType, dataBuilder.toString())
                            }
                            trySend(event)
                            dataBuilder.clear()
                            eventType = ""
                        }
                    }
                }
            }
        } catch (e: Exception) {
            trySend(SseEvent.Error("Stream read error: ${e.message}"))
        } finally {
            try {
                reader.close()
                response.close()
            } catch (_: Exception) {}
        }

        close()
    }
}
