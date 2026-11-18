package bd.desertfox.llmrouter

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object ProviderClient {
    private const val MAX_CHAT_RESPONSE_BYTES = 8 * 1024 * 1024
    private const val MAX_MODELS_RESPONSE_BYTES = 1024 * 1024

    /** Read at most [cap] bytes; throws RuntimeException("response too large") if exceeded. */
    private fun readBounded(ins: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) throw RuntimeException("response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun listModels(p: JSONObject): List<String> {
        val c = URL(p.optString("baseUrl") + "/models").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer " + p.optString("apiKey"))
        // No disconnect() here on purpose: letting HttpURLConnection keep
        // the connection alive lets the next request reuse it.
        val code = c.responseCode
        if (code != 200) throw RuntimeException("HTTP " + code)
        // Close the body stream: on Android HttpURLConnection is backed
        // by OkHttp, and a connection only returns to the pool once its
        // response body is closed. Leaving it open leaked one connection
        // per call — with OkHttp's 5-per-host limit, Test All stalled
        // after the first 4–5 models of the same host and the rest never
        // even started. No disconnect() here: closing the body is what
        // lets the connection be reused.
        val body = c.inputStream.use { readBounded(it, MAX_MODELS_RESPONSE_BYTES) }
        val j = JSONObject(String(body, Charsets.UTF_8))
        val data = j.optJSONArray("data") ?: throw RuntimeException("no data[] in response")
        val out = ArrayList<String>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id")
            if (id.isNotBlank()) out.add(id)
        }
        return out
    }

    class ChatResult(val status: Int, val contentType: String,
                     val body: ByteArray?, val stream: InputStream?) {
        fun close() { try { stream?.close() } catch (e: Exception) {} }
    }

    fun chat(p: JSONObject, model: String, reqBody: JSONObject, stream: Boolean, readTimeoutMs: Int): ChatResult {
        val c = URL(p.optString("baseUrl") + "/chat/completions").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = readTimeoutMs
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer " + p.optString("apiKey"))
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
        // Cheap shallow copy (no re-parse of the whole body): the same
        // reqBody is passed for every provider in the fallback chain, so
        // it must never be mutated — only the copy gets "model" set.
        // Cheap shallow copy (no toString() re-parse, no mutation of the
        // caller's object — it is shared across the fallback chain).
        // keys() exists in both Android's org.json and org.json-java;
        // the static getNames() does not exist in Android's fork.
        val body = JSONObject()
        val it = reqBody.keys()
        while (it.hasNext()) {
            val k = it.next()
            body.put(k, reqBody.opt(k))
        }
        body.put("model", model)
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.write(bytes)
        c.outputStream.close()
        val code = c.responseCode
        val ct = c.contentType ?: "application/json"
        if (code in 200..299) {
            return if (stream) ChatResult(code, ct, null, c.inputStream)
            else try {
                // use{} closes the body stream, releasing the connection
                // back to the pool (see listModels: without the close,
                // OkHttp's 5-per-host limit stalls all later requests).
                ChatResult(code, ct, c.inputStream.use { readBounded(it, MAX_CHAT_RESPONSE_BYTES) }, null)
            } catch (e: Exception) {
                c.disconnect()
                throw e
            }
        }
        val err = try {
            c.errorStream?.use { readBounded(it, MAX_CHAT_RESPONSE_BYTES) }?.toString(Charsets.UTF_8) ?: ""
        } catch (e: Exception) { "" }
        c.disconnect()
        return ChatResult(code, ct, err.toByteArray(Charsets.UTF_8), null)
    }
}
