package bd.desertfox.llmrouter

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

object HttpServerHolder {
    @Volatile var server: HttpServer? = null
    @Volatile var running = false
    fun start(port: Int) {
        if (running) return
        server = HttpServer(port)
        server?.start()
        running = true
    }
    fun stop() { server?.stop(); server = null; running = false }
}

class HttpServer(private val port: Int) {
    // Bounded pool (perf fix #3): newCachedThreadPool() grew one thread
    // per connection with no limit, so a burst of clients could exhaust
    // memory on thread stacks. 8 core / 16 max threads + a 64-deep queue
    // is plenty for a loopback server; beyond that we answer 503
    // instead of spawning more threads.
    private val pool = ThreadPoolExecutor(
        8, 16, 60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(64),
        { r -> Thread(r, "llmrouter-conn").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private var ss: ServerSocket? = null
    private val alive = AtomicBoolean(false)

    fun start() {
        alive.set(true)
        Thread {
            try {
                ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                while (alive.get()) {
                    val s = try { ss!!.accept() } catch (e: Exception) { break }
                    try {
                        pool.execute {
                            try { handle(s) } catch (e: Exception) {
                                Log.w("llmrouter", "conn", e)
                                try { s.close() } catch (e2: Exception) {}
                            }
                        }
                    } catch (e: RejectedExecutionException) {
                        // Pool + queue are full: shed load with 503
                        // instead of queuing unboundedly or spawning threads.
                        try {
                            val out = BufferedOutputStream(s.getOutputStream())
                            respond(out, 503, "application/json",
                                "{\"error\":{\"message\":\"server busy\"}}".toByteArray())
                            out.flush()
                        } catch (e2: Exception) {
                        } finally {
                            try { s.close() } catch (e2: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) { Log.w("llmrouter", "server", e) }
        }.start()
    }

    fun stop() {
        alive.set(false)
        try { ss?.close() } catch (e: Exception) {}
        pool.shutdown()
    }

    class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    // Hard caps (perf fix #2): a request must never be able to force an
    // unbounded allocation. Headers are capped at 32 KB in total, a single
    // header line at 8 KB, and a body at 10 MB.
    private val MAX_HEADER_BYTES = 32768
    private val MAX_BODY_BYTES = 10 * 1024 * 1024

    private fun readReq(ins: InputStream): Req? {
        // BufferedInputStream fills an 8 KB buffer per syscall, so the
        // per-byte loop below runs on in-memory bytes instead of one
        // syscall per byte (the old code called ins.read() per byte).
        // The body is read from the same buffered stream, so no bytes
        // already buffered ahead of the header are lost.
        val bin = BufferedInputStream(ins, 8192)
        var totalHeaderBytes = 0
        fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = bin.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                totalHeaderBytes++
                if (totalHeaderBytes > MAX_HEADER_BYTES)
                    throw IllegalArgumentException("header too large")
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 8192)
                    throw IllegalArgumentException("header line too large")
            }
            return sb.toString()
        }
        val firstLine = readLine() ?: return null
        val first = firstLine.split(" ")
        if (first.size < 2) return null
        val method = first[0]
        val path = first[1]
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                line.substring(idx + 1).trim()
        }
        val cl = headers["content-length"]?.toIntOrNull() ?: 0
        if (cl < 0 || cl > MAX_BODY_BYTES)
            throw IllegalArgumentException("body too large")
        val body = if (cl > 0) {
            val b = ByteArray(cl)
            var r = 0
            while (r < cl) {
                val n = bin.read(b, r, cl - r)
                if (n < 0) break
                r += n
            }
            if (r < cl) b.copyOf(r) else b
        } else ByteArray(0)
        return Req(method, path, headers, body)
    }

    private fun handle(s: Socket) {
        s.tcpNoDelay = true
        val out = BufferedOutputStream(s.getOutputStream())
        try {
            val req = try {
                readReq(s.getInputStream())
            } catch (e: IllegalArgumentException) {
                respond(out, 413, "application/json",
                    "{\"error\":{\"message\":\"request too large\"}}".toByteArray())
                out.flush()
                return
            }
            if (req == null) return
            val route = req.path.substringBefore('?')
            when {
                route == "/health" -> respond(out, 200, "application/json", "{\"ok\":true}".toByteArray())
                route == "/v1/models" -> handleModels(out)
                route == "/v1/chat/completions" && req.method == "POST" -> handleChat(out, req)
                else -> respond(out, 404, "application/json",
                    "{\"error\":{\"message\":\"not found\"}}".toByteArray())
            }
            out.flush()
        } finally { try { s.close() } catch (e: Exception) {} }
    }

    private fun respond(out: BufferedOutputStream, code: Int, ct: String, body: ByteArray) {
        val head = "HTTP/1.1 " + code + " " + (if (code < 400) "OK" else "Error") +
            "\r\nContent-Type: " + ct + "\r\nContent-Length: " + body.size +
            "\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
    }

    private fun handleModels(out: BufferedOutputStream) {
        val data = JSONArray()
        val seen = HashSet<String>()
        // Expose every combo as a selectable model (id = combo name) so
        // clients like RikkaHub can pick a specific combo directly.
        val cs = Store.combos()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            val name = c.optString("name")
            if (name.isNotBlank() && seen.add(name)) {
                data.put(JSONObject().put("id", name).put("object", "model")
                    .put("owned_by", "LLM Router combo"))
            }
        }
        // Expose every enabled model, not just the active chain: clients
        // like RikkaHub can only pick models they see here, so models
        // outside the active combo used to be unusable even though they
        // were tested and fast. Combos still come first.
        val chain = Store.enabledChain()
        for ((p, m) in chain) {
            if (seen.add(m)) {
                data.put(JSONObject().put("id", m).put("object", "model").put("owned_by", p.optString("name")))
            }
        }
        val j = JSONObject().put("object", "list").put("data", data)
        respond(out, 200, "application/json", j.toString().toByteArray())
    }

    // Token usage, when the provider reports it: non-streaming
    // responses carry a top-level "usage" object; streaming ones send
    // it in a final SSE chunk (only when the client asked for
    // stream_options.include_usage). Absent usage means "unknown",
    // never an invented number — those requests simply show no token
    // count in the log.
    private fun parseUsageTokens(text: String): Int {
        if (!text.contains("\"usage\"")) return 0
        return try {
            val u = JSONObject(text).optJSONObject("usage") ?: return 0
            val total = u.optInt("total_tokens", 0)
            if (total > 0) total else u.optInt("prompt_tokens", 0) + u.optInt("completion_tokens", 0)
        } catch (e: Exception) { 0 }
    }

    private fun chunkHasUsage(buf: ByteArray, n: Int): Boolean {
        // Byte-level search for "usage" so the 8 KB chunk is only
        // turned into a String when it actually carries usage.
        val needle = "usage".toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..n - needle.size) {
            for (j in needle.indices) if (buf[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun parseStreamUsage(buf: ByteArray, n: Int): Int {
        val text = String(buf, 0, n, Charsets.UTF_8)
        var found = 0
        for (line in text.lines()) {
            val t = line.trim()
            if (!t.startsWith("data:")) continue
            val payload = t.removePrefix("data:").trim()
            if (!payload.startsWith("{")) continue
            val v = parseUsageTokens(payload)
            if (v > 0) found = v
        }
        return found
    }

    // A 200 is not a success when the body carries an error — the Test
    // probe already treats it that way, but /v1/chat/completions did
    // not: a relay can answer 200 with {"error": {...}} (e.g. "This
    // free model's quota is temporarily used up") and the router
    // forwarded it as a success, so RikkaHub showed the error and no
    // fallback ran. Returns the error message, or null when there is
    // no error object.
    private fun errorFromJsonBody(text: String): String? {
        return try {
            val j = JSONObject(text)
            val e = j.opt("error") ?: return null
            if (e === JSONObject.NULL) return null
            if (e is JSONObject) {
                val msg = e.optString("message", "")
                if (msg.isNotBlank()) msg else e.toString()
            } else e.toString()
        } catch (e: Exception) { null }
    }

    private fun isQuotaLikeError(msg: String): Boolean {
        val l = msg.lowercase()
        return l.contains("quota") || l.contains("rate limit") ||
            l.contains("rate-limited") || l.contains("temporarily") ||
            l.contains("too many requests")
    }

    // First chunk(s) of a streaming reply: returns the error message
    // when a data: line (or a bare JSON body) carries an error, else
    // null. Non-JSON data lines, "[DONE]" and comments are ignored.
    private fun errorFromSseText(text: String): String? {
        val t = text.trim()
        if (t.startsWith("{")) return errorFromJsonBody(t)
        for (line in text.lines()) {
            val s = line.trim()
            if (!s.startsWith("data:")) continue
            val payload = s.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            if (!payload.startsWith("{")) continue
            val err = errorFromJsonBody(payload)
            if (err != null) return err
        }
        return null
    }

    // Non-streaming body with no usable content: a choices array whose
    // first message has blank content and no tool calls (same rule as
    // the Test probe's "empty reply"). Bodies with no choices array
    // are only failures when they are blank.
    private fun isEmptyReply(text: String): Boolean {
        return try {
            val j = JSONObject(text)
            val choices = j.optJSONArray("choices") ?: return text.isBlank()
            if (choices.length() == 0) return true
            val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return true
            if (msg.optJSONArray("tool_calls") != null) return false
            msg.optString("content", "").isBlank()
        } catch (e: Exception) { false }
    }

    private fun handleChat(out: BufferedOutputStream, req: Req) {
        val j = try { JSONObject(String(req.body, Charsets.UTF_8)) } catch (e: Exception) {
            respond(out, 400, "application/json",
                "{\"error\":{\"message\":\"bad json\"}}".toByteArray())
            return
        }
        val stream = j.optBoolean("stream", false)
        // Requested model matching a combo name -> that combo's chain
        // (with per-model fallback). A single model id -> that model
        // first, then the active chain as fallback (it used to be
        // ignored entirely, so models outside the active combo could
        // never be used from RikkaHub even when listed on /v1/models).
        // Anything else keeps the active chain, and if that resolves
        // empty (e.g. its models were all auto-disabled) the whole
        // enabled set is tried before giving up with 503.
        val want = j.optString("model", "").trim()
        var chain = if (want.isNotBlank()) Store.comboChain(want) else emptyList()
        if (chain.isEmpty() && want.isNotBlank()) {
            val single = if (want.contains("|")) {
                val p = Store.providerByName(want.substringBefore("|"))
                val m = Store.findModel(want)
                if (p != null && m != null && m.optBoolean("enabled", true) &&
                    p.optBoolean("enabled", true)) Pair(p, want.substringAfter("|")) else null
            } else Store.findEnabledModelById(want)
            if (single != null) {
                chain = listOf(single) + Store.activeChain().filter { it != single }
            }
        }
        if (chain.isEmpty()) chain = Store.activeChain()
        if (chain.isEmpty()) chain = Store.enabledChain()
        if (chain.isEmpty()) {
            respond(out, 503, "application/json",
                "{\"error\":{\"message\":\"no active model. open LLM Router and activate one\"}}".toByteArray())
            return
        }
        var lastErr = ""
        var lastCode = 502
        // Providers whose account-level error (401/402/403) already
        // failed in this request: their other models use the same key
        // and account, so they would fail identically — skip them and
        // try other providers instead of burning battery and data.
        val deadProviders = HashSet<String>()
        for ((p, m) in chain) {
            val pname = p.optString("name")
            if (deadProviders.contains(pname)) continue
            val key = pname + "|" + m
            val t0 = System.currentTimeMillis()
            try {
                // Per-read timeout, not 600s: a hanging model used to
                // block the whole chain for 10 minutes per model, so
                // RikkaHub gave up before fallback reached a working
                // model. 30s for streaming (tokens arrive steadily
                // once a model starts, so a 30s gap means it is
                // stuck) and 60s for non-streaming (a real reply can
                // take ~40s to generate before headers arrive).
                val r = ProviderClient.chat(p, m, j, stream, if (stream) 30000 else 60000)
                val dt = System.currentTimeMillis() - t0
                if (r.status in 200..299) {
                    if (stream) {
                        // Peek at the first chunk(s) BEFORE sending
                        // 200 headers to RikkaHub: once headers are
                        // sent, fallback is impossible. A provider
                        // that answers 200 then streams an error
                        // (quota used up, model unavailable) must
                        // fail here so the next model in the combo
                        // is tried. Up to 3 reads are buffered to
                        // skip keep-alive comments (": ping") that
                        // carry no data: line.
                        val firstBuf = ByteArrayOutputStream()
                        var firstErr: String? = null
                        var firstReadFailed: String? = null
                        var gotData = false
                        var streamEnded = false
                        val ins = r.stream!!
                        var attempts = 0
                        while (attempts < 3 && !gotData && !streamEnded) {
                            attempts++
                            val buf = ByteArray(8192)
                            val n = try { ins.read(buf) } catch (e: Exception) {
                                firstReadFailed = (e.message ?: e.toString()).take(120)
                                break
                            }
                            if (n < 0) { streamEnded = true; break }
                            if (n == 0) continue
                            firstBuf.write(buf, 0, n)
                            val chunkText = String(firstBuf.toByteArray(), Charsets.ISO_8859_1)
                            if (chunkText.contains("data:") || chunkText.trim().startsWith("{")) gotData = true
                        }
                        if (firstReadFailed == null && firstBuf.size() > 0) {
                            firstErr = errorFromSseText(String(firstBuf.toByteArray(), Charsets.ISO_8859_1))
                        }
                        val dt2 = System.currentTimeMillis() - t0
                        if (firstReadFailed != null || firstErr != null ||
                            (streamEnded && firstBuf.size() == 0)) {
                            // Failure, not a reply: log it as failed
                            // (status 0, like the Test probe does for
                            // a 200-with-error) and try the next model.
                            lastErr = (firstErr ?: firstReadFailed ?: "empty reply").take(300)
                            lastCode = 200
                            Store.addLog(LogEntry(System.currentTimeMillis(), pname, m, 0, dt2, lastErr))
                            Store.bumpModel(key, false, dt2, lastErr, permanentFail = true)
                            if (firstErr != null && isQuotaLikeError(firstErr)) Store.markCooldown(key)
                            try { r.close() } catch (e: Exception) {}
                            continue
                        }
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.ISO_8859_1))
                        out.write(firstBuf.toByteArray())
                        out.flush()
                        // finally closes the provider stream even if the
                        // client disconnects mid-stream, so the pooled
                        // provider connection is released (same leak as
                        // the non-streaming path used to have).
                        var tokens = 0
                        if (chunkHasUsage(firstBuf.toByteArray(), firstBuf.size())) {
                            tokens = parseStreamUsage(firstBuf.toByteArray(), firstBuf.size())
                        }
                        try {
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = try { ins.read(buf) } catch (e: Exception) { break }
                                if (n < 0) break
                                if (tokens == 0 && chunkHasUsage(buf, n)) {
                                    tokens = parseStreamUsage(buf, n)
                                }
                                out.write(buf, 0, n)
                                out.flush()
                            }
                        } finally {
                            r.close()
                        }
                        if (tokens > 0) Store.addTokens(key, tokens)
                        Store.addLog(LogEntry(System.currentTimeMillis(), pname, m, r.status, dt2, "", tokens))
                        Store.bumpModel(key, true, dt2, "")
                    } else {
                        val body = r.body ?: ByteArray(0)
                        val bodyText = String(body, Charsets.UTF_8)
                        // 200 with an error object, or with no usable
                        // content, is a failure (same rule as the Test
                        // probe) — fall through to the next model.
                        val errText = errorFromJsonBody(bodyText)
                        val failReason = errText ?: if (isEmptyReply(bodyText)) "empty reply" else null
                        if (failReason != null) {
                            lastErr = failReason.take(300)
                            lastCode = r.status
                            Store.addLog(LogEntry(System.currentTimeMillis(), pname, m, 0, dt, lastErr))
                            Store.bumpModel(key, false, dt, lastErr, permanentFail = true)
                            if (errText != null && isQuotaLikeError(errText)) Store.markCooldown(key)
                            r.close()
                            continue
                        }
                        val tokens = parseUsageTokens(bodyText)
                        if (tokens > 0) Store.addTokens(key, tokens)
                        Store.addLog(LogEntry(System.currentTimeMillis(), pname, m, r.status, dt, "", tokens))
                        Store.bumpModel(key, true, dt, "")
                        respond(out, 200, r.contentType, body)
                    }
                    return
                }
                lastErr = String(r.body ?: ByteArray(0), Charsets.UTF_8).take(300)
                lastCode = r.status
                Store.addLog(LogEntry(System.currentTimeMillis(), pname, m, r.status, dt, lastErr))
                Store.bumpModel(key, false, dt, lastErr,
                    permanentFail = (r.status == 401 || r.status == 402 || r.status == 403))
                r.close()
                if (r.status == 429) Store.markCooldown(key)
                if (r.status == 401 || r.status == 402 || r.status == 403) deadProviders.add(pname)
                // Fallback tries the next model on ANY error now: the
                // old code returned immediately on 4xx, so a 402
                // (e.g. OpenRouter out of credits) never reached the
                // other providers in the combo. Only same-provider
                // account errors are skipped (deadProviders above).
            } catch (e: Exception) {
                val dt = System.currentTimeMillis() - t0
                lastErr = (e.message ?: e.toString()).take(300)
                Store.addLog(LogEntry(System.currentTimeMillis(), pname, m, 0, dt, lastErr))
                Store.bumpModel(key, false, dt, lastErr)
            }
        }
        val errJson = JSONObject().put("error", JSONObject().put("message", "all models failed: " + lastErr))
        respond(out, if (lastCode in 400..599) lastCode else 502, "application/json", errJson.toString().toByteArray())
    }
}
