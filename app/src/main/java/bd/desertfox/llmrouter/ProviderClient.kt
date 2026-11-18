package bd.desertfox.llmrouter

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object ProviderClient {
    fun listModels(p: JSONObject): List<String> {
        val c = URL(p.optString("baseUrl") + "/models").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer " + p.optString("apiKey"))
        try {
            val code = c.responseCode
            if (code != 200) throw RuntimeException("HTTP " + code)
            val body = c.inputStream.bufferedReader().readText()
            val j = JSONObject(body)
            val data = j.optJSONArray("data") ?: throw RuntimeException("no data[] in response")
            val out = ArrayList<String>()
            for (i in 0 until data.length()) {
                val m = data.optJSONObject(i) ?: continue
                val id = m.optString("id")
                if (id.isNotBlank()) out.add(id)
            }
            return out
        } finally { c.disconnect() }
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
        val body = JSONObject(reqBody.toString())
        body.put("model", model)
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.write(bytes)
        c.outputStream.close()
        val code = c.responseCode
        val ct = c.contentType ?: "application/json"
        if (code in 200..299) {
            return if (stream) ChatResult(code, ct, null, c.inputStream)
            else ChatResult(code, ct, c.inputStream.readBytes(), null)
        }
        val err = try { c.errorStream?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
        c.disconnect()
        return ChatResult(code, ct, err.toByteArray(Charsets.UTF_8), null)
    }
}
