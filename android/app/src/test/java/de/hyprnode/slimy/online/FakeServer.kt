package de.hyprnode.slimy.online

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/** A request the fake server saw. */
data class SeenRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, String>,
    val body: String?,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * slime.hyprnode.de for tests: an OkHttp interceptor that answers every
 * request from [answers] (or throws what it is told to), so the real client
 * code runs without a network.
 */
class FakeServer {
    sealed class Answer {
        data class json(val status: Int, val body: String = "") : Answer()
        data class fail(val error: IOException) : Answer()
    }

    val seen = mutableListOf<SeenRequest>()
    private val answers = ArrayDeque<Answer>()

    /** From now on, answers the next requests with these, in order; the last one repeats. */
    fun answer(vararg next: Answer) {
        synchronized(answers) {
            answers.clear()
            answers.addAll(next)
        }
    }

    fun answer(status: Int, body: String = "") = answer(Answer.json(status, body))

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> handle(chain.request()) }).build()

    fun api(token: String? = "test-token"): HyprnodeAPI = HyprnodeAPI(token, client, base)

    private fun handle(request: Request): Response {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val body = if (request.body == null) null else buffer.readUtf8()
        val headers = request.headers.names().associate { it.lowercase() to request.header(it).orEmpty() }
        val prefix = "/api/v1/"
        val path = request.url.encodedPath.removePrefix(prefix)
        synchronized(seen) { seen.add(SeenRequest(request.method, path, request.url.encodedQuery, headers, body)) }
        val answer = synchronized(answers) { if (answers.size > 1) answers.removeFirst() else answers.firstOrNull() }
            ?: Answer.json(204)
        return when (answer) {
            is Answer.fail -> throw answer.error
            is Answer.json -> Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(answer.status)
                .message("Test")
                .body(answer.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    companion object {
        const val base = "https://slime.test/api/v1/"
    }
}
