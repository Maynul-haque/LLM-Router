package bd.desertfox.llmrouter

import android.util.Log
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
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
    private val pool = Executors.newCachedThreadPool()
    private var ss: ServerSocket? = null
    private val alive = AtomicBoolean(false)

    fun start() {
        alive.set(true)
        Thread {
            try {
                ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                while (alive.get()) {
                    val s = try { ss!!.accept() } catch (e: Exception) { break }
                    pool.submit {
                        try { handle(s) } catch (e: Exception) {
                            Log.w("llmrouter", "conn", e)
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

    private fun readReq(ins: InputStream): Req? {
        val hb = ByteArrayOutputStream()
        var state = 0
        while (state < 4) {
            val b = ins.read()
            if (b < 0) return null
            hb.write(b)
            state = when (b) {
                13 -> if (state == 0) 1 else if (state == 2) 3 else 0
                10 -> if (state == 1) 2 else if (state == 3) 4 else 0
                else -> 0
            }
        }
        val headText = String(hb.toByteArray(), Charsets.ISO_8859_1)
        val lines = headText.trim().split("\r\n")
        val first = lines[0].split(" ")
        if (first.size < 2) return null
        val method = first[0]
        val path = first[1]
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
        }
        val cl = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (cl > 0) {
            val b = ByteArray(cl)
            var r = 0
            while (r < cl) {
                val n = ins.read(b, r, cl - r)
                if (n < 0) break
                r += n
            }
            b
        } else ByteArray(0)
        return Req(method, path, headers, body)
    }

    private fun handle(s: Socket) {
        s.tcpNoDelay = true
        val req = readReq(s.getInputStream())
        if (req == null) { try { s.close() } catch (e: Exception) {}; return }
        val out = BufferedOutputStream(s.getOutputStream())
        val route = req.path.substringBefore('?')
        try {
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
        var chain = Store.activeChain()
        if (chain.isEmpty()) chain = Store.enabledChain()
        for ((p, m) in chain) {
            if (seen.add(m)) {
                data.put(JSONObject().put("id", m).put("object", "model").put("owned_by", p.optString("name")))
            }
        }
        val j = JSONObject().put("object", "list").put("data", data)
        respond(out, 200, "application/json", j.toString().toByteArray())
    }

    private fun handleChat(out: BufferedOutputStream, req: Req) {
        val j = try { JSONObject(String(req.body, Charsets.UTF_8)) } catch (e: Exception) {
            respond(out, 400, "application/json",
                "{\"error\":{\"message\":\"bad json\"}}".toByteArray())
            return
        }
        val stream = j.optBoolean("stream", false)
        // Requested model matching a combo name -> that combo's chain
        // (with per-model fallback). Anything else keeps the active chain.
        val want = j.optString("model", "").trim()
        var chain = if (want.isNotBlank()) Store.comboChain(want) else emptyList()
        if (chain.isEmpty()) chain = Store.activeChain()
        if (chain.isEmpty()) {
            respond(out, 503, "application/json",
                "{\"error\":{\"message\":\"no active model. open LLM Router and activate one\"}}".toByteArray())
            return
        }
        var lastErr = ""
        var lastCode = 502
        for ((p, m) in chain) {
            val pname = p.optString("name")
            val key = pname + "|" + m
            val t0 = System.currentTimeMillis()
            try {
                val r = ProviderClient.chat(p, m, j, stream, 600000)
                val dt = System.currentTimeMillis() - t0
                if (r.status in 200..299) {
                    Store.addLog(LogEntry(System.currentTimeMillis(), pname, m, r.status, dt, ""))
                    Store.bumpModel(key, true, dt, "")
                    if (stream) {
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.ISO_8859_1))
                        out.flush()
                        val ins = r.stream!!
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = try { ins.read(buf) } catch (e: Exception) { break }
                            if (n < 0) break
                            out.write(buf, 0, n)
                            out.flush()
                        }
                        r.close()
                    } else {
                        respond(out, 200, r.contentType, r.body ?: ByteArray(0))
                    }
                    return
                }
                lastErr = String(r.body ?: ByteArray(0), Charsets.UTF_8).take(300)
                lastCode = r.status
                Store.addLog(LogEntry(System.currentTimeMillis(), pname, m, r.status, dt, lastErr))
                Store.bumpModel(key, false, dt, lastErr)
                r.close()
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
