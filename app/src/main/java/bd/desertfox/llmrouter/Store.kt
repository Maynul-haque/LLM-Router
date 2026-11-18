package bd.desertfox.llmrouter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LogEntry(val ts: Long, val provider: String, val model: String,
               val status: Int, val latencyMs: Long, val error: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("ts", ts).put("provider", provider).put("model", model)
        .put("status", status).put("latencyMs", latencyMs).put("error", error)
    companion object {
        fun from(j: JSONObject) = LogEntry(j.optLong("ts"), j.optString("provider"),
            j.optString("model"), j.optInt("status"), j.optLong("latencyMs"), j.optString("error"))
    }
}

object Store {
    lateinit var cfg: JSONObject
        private set
    private var appCtx: Context? = null
    val log = ArrayList<LogEntry>()
    const val LOG_CAP = 1000

    fun load(ctx: Context) {
        appCtx = ctx.applicationContext
        val f = File(ctx.filesDir, "config.json")
        cfg = try { if (f.exists()) JSONObject(f.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
        defaults()
        synchronized(log) {
            log.clear()
            val la = cfg.optJSONArray("log") ?: JSONArray()
            for (i in 0 until la.length()) log.add(LogEntry.from(la.getJSONObject(i)))
        }
        save()
    }

    private fun defaults() {
        if (!cfg.has("providers")) cfg.put("providers", JSONArray())
        if (!cfg.has("models")) cfg.put("models", JSONArray())
        if (!cfg.has("combos")) cfg.put("combos", JSONArray())
        if (!cfg.has("active")) cfg.put("active", JSONObject().put("type", "none"))
        if (!cfg.has("autoDisable")) cfg.put("autoDisable", true)
        if (!cfg.has("port")) cfg.put("port", 8790)
    }

    fun save() {
        try {
            val ctx = appCtx ?: return
            val arr = JSONArray()
            synchronized(log) { for (e in log.takeLast(LOG_CAP)) arr.put(e.toJson()) }
            cfg.put("log", arr)
            File(ctx.filesDir, "config.json").writeText(cfg.toString())
        } catch (e: Exception) { }
    }

    fun providers(): JSONArray = cfg.optJSONArray("providers") ?: JSONArray()

    fun providerByName(n: String): JSONObject? {
        val ps = providers()
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            if (p.optString("name") == n) return p
        }
        return null
    }

    fun addProvider(name: String, baseUrl: String, apiKey: String): Boolean {
        if (name.isBlank() || baseUrl.isBlank() || providerByName(name) != null) return false
        providers().put(JSONObject().put("name", name).put("baseUrl", baseUrl.trimEnd('/'))
            .put("apiKey", apiKey).put("enabled", true))
        save()
        return true
    }

    fun removeProvider(name: String) {
        val ps = providers()
        val keep = JSONArray()
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            if (p.optString("name") != name) keep.put(p)
        }
        cfg.put("providers", keep)
        val ms = models()
        val km = JSONArray()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            if (m.optString("provider") != name) km.put(m)
        }
        cfg.put("models", km)
        // drop from every combo
        val cs = combos()
        for (i in 0 until cs.length()) {
            val ids = cs.getJSONObject(i).optJSONArray("models") ?: JSONArray()
            val kid = JSONArray()
            for (j in 0 until ids.length()) {
                val k = ids.optString(j)
                if (!k.startsWith(name + "|")) kid.put(k)
            }
            cs.getJSONObject(i).put("models", kid)
        }
        if (activeType() == "combo" && activeComboName() != null) { /* re-resolved live, nothing to do */ }
        if (activeType() == "single") {
            val ids = cfg.optJSONObject("active")?.optJSONArray("models")
            if (ids != null && ids.length() > 0 && ids.optString(0).startsWith(name + "|")) clearActive()
        }
        save()
    }

    fun models(): JSONArray = cfg.optJSONArray("models") ?: JSONArray()

    fun findModel(key: String): JSONObject? {
        val ms = models()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            if (m.optString("provider") + "|" + m.optString("id") == key) return m
        }
        return null
    }

    fun enabledChain(): List<Pair<JSONObject, String>> {
        val out = ArrayList<Pair<JSONObject, String>>()
        val ms = models()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val key = m.optString("provider") + "|" + m.optString("id")
            if (!m.optBoolean("enabled", true)) continue
            val p = providerByName(m.optString("provider")) ?: continue
            if (!p.optBoolean("enabled", true)) continue
            out.add(Pair(p, m.optString("id")))
        }
        return out
    }

    private fun resolveKey(key: String, out: MutableList<Pair<JSONObject, String>>) {
        val m = findModel(key) ?: return
        if (!m.optBoolean("enabled", true)) return
        val p = providerByName(m.optString("provider")) ?: return
        if (!p.optBoolean("enabled", true)) return
        out.add(Pair(p, m.optString("id")))
    }

    fun activeType(): String = cfg.optJSONObject("active")?.optString("type") ?: "none"
    fun activeComboName(): String? {
        val a = cfg.optJSONObject("active") ?: return null
        val n = a.optString("name", "")
        return if (a.optString("type") == "combo" && n.isNotBlank()) n else null
    }

    // Chain for a named combo (used by /v1/chat/completions when the
    // requested model matches a combo name exposed on /v1/models).
    fun comboChain(name: String): List<Pair<JSONObject, String>> {
        val out = ArrayList<Pair<JSONObject, String>>()
        val ids = comboByName(name)?.optJSONArray("models") ?: return out
        for (i in 0 until ids.length()) resolveKey(ids.optString(i), out)
        return out
    }

    fun activeChain(): List<Pair<JSONObject, String>> {
        val out = ArrayList<Pair<JSONObject, String>>()
        val a = cfg.optJSONObject("active") ?: return out
        when (a.optString("type")) {
            "single", "combo" -> {
                var ids = a.optJSONArray("models")
                val name = a.optString("name", "")
                if (a.optString("type") == "combo" && name.isNotBlank()) {
                    ids = comboByName(name)?.optJSONArray("models")
                }
                if (ids != null) for (i in 0 until ids.length()) resolveKey(ids.optString(i), out)
            }
        }
        return out
    }

    fun setActiveSingle(providerName: String, modelId: String) {
        cfg.put("active", JSONObject().put("type", "single")
            .put("models", JSONArray().put(providerName + "|" + modelId)))
        save()
    }

    fun activateCombo(name: String) {
        cfg.put("active", JSONObject().put("type", "combo").put("name", name))
        save()
    }

    fun setActiveCombo(keys: List<String>) {
        val arr = JSONArray(); for (k in keys) arr.put(k)
        cfg.put("active", JSONObject().put("type", "combo").put("models", arr))
        save()
    }

    fun clearActive() {
        cfg.put("active", JSONObject().put("type", "none"))
        save()
    }

    fun activeKey(): String {
        val a = cfg.optJSONObject("active") ?: return "none"
        when (a.optString("type")) {
            "single" -> {
                val ids = a.optJSONArray("models") ?: return "none"
                return if (ids.length() == 0) "none" else ids.optString(0)
            }
            "combo" -> {
                val name = a.optString("name", "")
                if (name.isNotBlank()) {
                    val c = comboByName(name) ?: return "none"
                    val ids = c.optJSONArray("models") ?: JSONArray()
                    return name + " (" + ids.length() + " models)"
                }
                val ids = a.optJSONArray("models") ?: return "none"
                return if (ids.length() == 0) "none" else ids.join(" -> ")
            }
            else -> return "none"
        }
    }

    // ---- combos ----
    fun combos(): JSONArray {
        if (!cfg.has("combos")) cfg.put("combos", JSONArray())
        return cfg.getJSONArray("combos")
    }

    fun comboByName(n: String): JSONObject? {
        val cs = combos()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            if (c.optString("name") == n) return c
        }
        return null
    }

    fun addCombo(n: String): Boolean {
        if (n.isBlank() || comboByName(n) != null) return false
        combos().put(JSONObject().put("name", n).put("models", JSONArray()))
        save()
        return true
    }

    fun removeCombo(n: String) {
        val cs = combos()
        val keep = JSONArray()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            if (c.optString("name") != n) keep.put(c)
        }
        cfg.put("combos", keep)
        if (activeComboName() == n) clearActive()
        save()
    }

    fun renameCombo(old: String, new: String): Boolean {
        if (new.isBlank() || (comboByName(new) != null && old != new)) return false
        val c = comboByName(old) ?: return false
        c.put("name", new)
        if (activeComboName() == old) cfg.getJSONObject("active").put("name", new)
        save()
        return true
    }

    fun comboKeys(n: String): List<String> {
        val ids = comboByName(n)?.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until ids.length()) out.add(ids.optString(i))
        return out
    }

    fun comboAddModel(n: String, key: String): Boolean {
        val c = comboByName(n) ?: return false
        val ids = c.optJSONArray("models") ?: JSONArray().also { c.put("models", it) }
        for (i in 0 until ids.length()) if (ids.optString(i) == key) return false
        ids.put(key)
        save()
        return true
    }

    fun comboSet(n: String, keys: List<String>) {
        val c = comboByName(n) ?: return
        val ids = JSONArray(); for (k in keys) ids.put(k)
        c.put("models", ids)
        save()
    }

    fun comboRemoveAt(n: String, idx: Int) {
        val c = comboByName(n) ?: return
        val ids = c.optJSONArray("models") ?: return
        if (idx < 0 || idx >= ids.length()) return
        val kid = JSONArray()
        for (i in 0 until ids.length()) if (i != idx) kid.put(ids.optString(i))
        c.put("models", kid)
        save()
    }

    fun comboMove(n: String, idx: Int, delta: Int): Boolean {
        val c = comboByName(n) ?: return false
        val ids = c.optJSONArray("models") ?: return false
        val j = idx + delta
        if (idx < 0 || idx >= ids.length() || j < 0 || j >= ids.length()) return false
        val a = ids.optString(idx)
        val b = ids.optString(j)
        ids.put(idx, b)
        ids.put(j, a)
        save()
        return true
    }

    fun comboSortBySpeed(n: String) {
        val c = comboByName(n) ?: return
        val keys = comboKeys(n)
        val scored = ArrayList<Pair<String, Long>>()
        for (k in keys) {
            val m = findModel(k)
            val ok = m != null && m!!.optBoolean("lastOk", false) && m.optBoolean("enabled", true)
            scored.add(Pair(k, if (ok) m!!.optLong("lastLatencyMs", Long.MAX_VALUE) else Long.MAX_VALUE))
        }
        scored.sortBy { it.second }
        comboSet(n, scored.map { it.first })
    }

    // ---- import ----
    fun importKeys(text: String): Int {
        val keysArr = try {
            if (text.trim().startsWith("[")) JSONArray(text)
            else JSONObject(text).optJSONArray("keys") ?: JSONArray()
        } catch (e: Exception) { return 0 }
        val urlMap = HashMap<String, String>()
        urlMap["groq"] = "https://api.groq.com/openai/v1"
        urlMap["openrouter"] = "https://openrouter.ai/api/v1"
        urlMap["agnes"] = "https://apihub.agnes-ai.com/v1"
        urlMap["anyapi"] = "https://api.anyapi.ai/v1"
        urlMap["bazaarlink"] = "https://bazaarlink.ai/api/v1"
        urlMap["llm7"] = "https://api.llm7.io/v1"
        urlMap["xkiro"] = "https://api.xkiro.com/v1"
        urlMap["nara"] = "https://router.bynara.id/v1"
        urlMap["google"] = "https://generativelanguage.googleapis.com/v1beta/openai/"
        var n = 0
        for (i in 0 until keysArr.length()) {
            val e = keysArr.optJSONObject(i) ?: continue
            val key = e.optString("key", e.optString("api_key", e.optString("apiKey", "")))
            if (key.isBlank()) continue
            val platform = e.optString("platform", "custom").lowercase()
            var url = e.optString("baseUrl", e.optString("base_url", ""))
            if (url.isBlank()) url = urlMap[platform] ?: ""
            if (url.isBlank()) continue
            val label = e.optString("label", e.optString("name", "")).ifBlank { platform }
            // skip exact duplicates (same url + key already imported)
            var dup = false
            val ps = providers()
            for (j in 0 until ps.length()) {
                val p = ps.getJSONObject(j)
                if (p.optString("baseUrl") == url.trimEnd('/') && p.optString("apiKey") == key) { dup = true; break }
            }
            if (dup) continue
            var name = platform + (if (label != platform) " · " + label else "")
            var k = 2
            while (providerByName(name) != null) { name = platform + (if (label != platform) " · " + label else "") + " " + k; k++ }
            if (addProvider(name, url, key)) n++
        }
        return n
    }

    // ---- per-provider refresh: add new models, remove stale ones ----
    fun refreshProviderModels(providerName: String, fresh: List<String>): Pair<Int, Int> {
        val ms = models()
        val keepIds = HashSet(fresh)
        var added = 0
        var removed = 0
        val km = JSONArray()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            if (m.optString("provider") == providerName) {
                if (keepIds.contains(m.optString("id"))) km.put(m) else removed++
            } else km.put(m)
        }
        for (id in fresh) {
            var have = false
            for (i in 0 until km.length()) {
                val m = km.getJSONObject(i)
                if (m.optString("provider") == providerName && m.optString("id") == id) { have = true; break }
            }
            if (!have) { km.put(JSONObject().put("provider", providerName).put("id", id).put("enabled", true)); added++ }
        }
        cfg.put("models", km)
        save()
        return Pair(added, removed)
    }

    // ---- log + results ----
    fun addLog(e: LogEntry) {
        synchronized(log) { log.add(e); while (log.size > LOG_CAP) log.removeAt(0) }
        save()
    }

    fun bumpModel(key: String, ok: Boolean, latency: Long, err: String) {
        val m = findModel(key) ?: return
        m.put("lastOk", ok).put("lastLatencyMs", latency)
        if (ok) {
            m.put("failStreak", 0)
            if (!m.optBoolean("enabled", true)) m.put("enabled", true)  // re-test pass brings it back
        } else {
            m.put("failStreak", m.optInt("failStreak", 0) + 1)
            if (cfg.optBoolean("autoDisable", true) && m.optInt("failStreak", 0) >= 3) m.put("enabled", false)
        }
        if (err.isNotBlank()) m.put("lastError", err)
        save()
    }

    // ---- v0.3: provider enable/disable + rename (remaps model keys) ----
    fun setProviderEnabled(name: String, enabled: Boolean) {
        val p = providerByName(name) ?: return
        p.put("enabled", enabled)
        save()
    }

    fun renameProvider(old: String, new: String): Boolean {
        if (new.isBlank()) return false
        val p = providerByName(old) ?: return false
        if (old != new && providerByName(new) != null) return false
        p.put("name", new)
        val ms = models()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            if (m.optString("provider") == old) m.put("provider", new)
        }
        val cs = combos()
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            val ids = c.optJSONArray("models") ?: continue
            for (j in 0 until ids.length()) {
                val k = ids.optString(j)
                if (k.startsWith(old + "|")) ids.put(j, new + "|" + k.substringAfter("|"))
            }
        }
        val a = cfg.optJSONObject("active")
        if (a != null) {
            val ids = a.optJSONArray("models")
            if (ids != null) for (j in 0 until ids.length()) {
                val k = ids.optString(j)
                if (k.startsWith(old + "|")) ids.put(j, new + "|" + k.substringAfter("|"))
            }
        }
        save()
        return true
    }
}
