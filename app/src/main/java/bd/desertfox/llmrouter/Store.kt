package bd.desertfox.llmrouter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class LogEntry(val ts: Long, val provider: String, val model: String,
               val status: Int, val latencyMs: Long, val error: String, val tokens: Int = 0) {
    fun toJson(): JSONObject = JSONObject()
        .put("ts", ts).put("provider", provider).put("model", model)
        .put("status", status).put("latencyMs", latencyMs).put("error", error).put("tokens", tokens)
    companion object {
        fun from(j: JSONObject) = LogEntry(j.optLong("ts"), j.optString("provider"),
            j.optString("model"), j.optInt("status"), j.optLong("latencyMs"), j.optString("error"),
            j.optInt("tokens", 0))
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
        invalidateIndexes()
        // One auto-managed combo only: rebuildAutoBest() removes the
        // old v0.5.2 alias combos and the preinstalled "auto" combo.
        rebuildAutoBest()
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
        if (!cfg.has("autoTestNew")) cfg.put("autoTestNew", false)
        if (!cfg.has("port")) cfg.put("port", 8790)
    }

    // Auto-managed combo check: alias combos (v0.5.2, removed by the
    // rebuild below) and the auto-best combo (v0.5.5).
    fun isAutoManaged(n: String): Boolean {
        val c = comboByName(n) ?: return false
        return c.optBoolean("autoAlias", false) || c.optBoolean("autoBest", false)
    }

    fun save() {
        // Debounced persistence (perf fix #1): save() used to rewrite the
        // whole config.json on every log/bump — i.e. twice per chat request,
        // ~200 times for a 100-model test run. Now repeated calls coalesce
        // into a single write, at most once per SAVE_DEBOUNCE_MS.
        synchronized(this) {
            if (saveScheduled) return
            val delay = (SAVE_DEBOUNCE_MS - (System.currentTimeMillis() - lastSaveMs))
                .coerceAtLeast(0L)
            saveScheduled = true
            saveExecutor.schedule({ saveNow() }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /** Write config.json now, on the calling thread. Call from onPause so
     *  the last debounced write is not lost if the process is killed. */
    fun saveNow() {
        val ctx: Context
        val text: String
        synchronized(this) {
            saveScheduled = false
            ctx = appCtx ?: return
            try {
                val arr = JSONArray()
                synchronized(log) { for (e in log.takeLast(LOG_CAP)) arr.put(e.toJson()) }
                cfg.put("log", arr)
                text = cfg.toString()
            } catch (e: Exception) {
                return
            }
            lastSaveMs = System.currentTimeMillis()
        }
        // Atomic-ish replace: write to a temp file, then rename, so a
        // crash mid-write can never leave a half-written config.json.
        try {
            val tmp = File(ctx.filesDir, "config.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(ctx.filesDir, "config.json"))) {
                File(ctx.filesDir, "config.json").writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
        }
    }

    // Debounced-save state. The executor's thread is created lazily on the
    // first scheduled write, is a daemon, and is the only extra thread.
    private const val SAVE_DEBOUNCE_MS = 2000L
    private val saveExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "llmrouter-save").apply { isDaemon = true }
        }
    @Volatile private var saveScheduled = false
    @Volatile private var lastSaveMs = 0L

    fun providers(): JSONArray = cfg.optJSONArray("providers") ?: JSONArray()

    // Lazily-built O(1) indexes. Each is a single O(n) scan rebuilt on
    // next use after any mutation that can change its keys (see
    // invalidateIndexes()); lookups between mutations are O(1).
    private var providerIndex: HashMap<String, JSONObject>? = null
    private var modelIndex: HashMap<String, JSONObject>? = null
    private var comboIndex: HashMap<String, JSONObject>? = null

    private fun invalidateIndexes() {
        providerIndex = null
        modelIndex = null
        comboIndex = null
    }

    // In-memory 429 cooldowns: model key ("provider|id") -> cooldownUntilMs.
    // Never written to config.json, no timer thread — expiry is checked
    // lazily against System.currentTimeMillis() on lookup, so the battery
    // cost is zero when the server is idle.
    private const val COOLDOWN_MS = 60_000L
    private val cooldowns = HashMap<String, Long>()

    fun markCooldown(key: String) {
        synchronized(cooldowns) { cooldowns[key] = System.currentTimeMillis() + COOLDOWN_MS }
    }

    fun isCooledDown(key: String): Boolean {
        synchronized(cooldowns) {
            val until = cooldowns[key] ?: return false
            if (System.currentTimeMillis() >= until) {
                cooldowns.remove(key)
                return false
            }
            return true
        }
    }

    // Same-model rotation for alias combos: combo name -> next start
    // index. In-memory only (like cooldowns) — rotation fairness
    // across restarts is not important, and this avoids a disk write
    // on every request.
    private val rrIndex = HashMap<String, Int>()

    // Rotate an alias combo's keys so each request starts at a
    // different provider of the SAME model (safe-form round-robin:
    // the model never changes mid-conversation, only the provider).
    private fun rotateAlias(name: String, keys: List<String>): List<String> {
        if (keys.size <= 1) return keys
        val idx = synchronized(rrIndex) {
            val i = (rrIndex[name] ?: 0) % keys.size
            rrIndex[name] = (i + 1) % keys.size
            i
        }
        return keys.subList(idx, keys.size) + keys.subList(0, idx)
    }

    // Keys of a named combo, rotated when it is an auto-managed
    // alias combo. Custom combos are never rotated.
    private fun comboKeysRotated(name: String): List<String> {
        val c = comboByName(name) ?: return emptyList()
        val ids = c.optJSONArray("models") ?: return emptyList()
        val keys = ArrayList<String>()
        for (i in 0 until ids.length()) keys.add(ids.optString(i))
        return if (c.optBoolean("autoAlias", false)) rotateAlias(name, keys) else keys
    }

    private fun providersByName(): HashMap<String, JSONObject> {
        var idx = providerIndex
        if (idx == null) {
            idx = HashMap()
            val ps = providers()
            for (i in 0 until ps.length()) {
                val p = ps.getJSONObject(i)
                idx[p.optString("name")] = p
            }
            providerIndex = idx
        }
        return idx
    }

    private fun modelsByKey(): HashMap<String, JSONObject> {
        var idx = modelIndex
        if (idx == null) {
            idx = HashMap()
            val ms = models()
            for (i in 0 until ms.length()) {
                val m = ms.getJSONObject(i)
                idx[m.optString("provider") + "|" + m.optString("id")] = m
            }
            modelIndex = idx
        }
        return idx
    }

    private fun combosByName(): HashMap<String, JSONObject> {
        var idx = comboIndex
        if (idx == null) {
            idx = HashMap()
            val cs = combos()
            for (i in 0 until cs.length()) {
                val c = cs.getJSONObject(i)
                idx[c.optString("name")] = c
            }
            comboIndex = idx
        }
        return idx
    }

    fun providerByName(n: String): JSONObject? = providersByName()[n]

    fun addProvider(name: String, baseUrl: String, apiKey: String): Boolean {
        if (name.isBlank() || baseUrl.isBlank() || providerByName(name) != null) return false
        providers().put(JSONObject().put("name", name).put("baseUrl", baseUrl.trimEnd('/'))
            .put("apiKey", apiKey).put("enabled", true))
        invalidateIndexes()
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
        invalidateIndexes()
        save()
    }

    fun models(): JSONArray = cfg.optJSONArray("models") ?: JSONArray()

    fun findModel(key: String): JSONObject? = modelsByKey()[key]

    /** First enabled model with this id, any provider — for /v1/chat/completions
     *  when a client (e.g. RikkaHub) asks for a single model by id. */
    fun findEnabledModelById(id: String): Pair<JSONObject, String>? {
        for ((p, m) in enabledChain()) if (m == id) return Pair(p, m)
        return null
    }

    fun enabledChain(): List<Pair<JSONObject, String>> {
        val out = ArrayList<Pair<JSONObject, String>>()
        val ms = models()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val key = m.optString("provider") + "|" + m.optString("id")
            if (m.optBoolean("hidden", false)) continue
            if (!m.optBoolean("enabled", true)) continue
            val p = providerByName(m.optString("provider")) ?: continue
            if (!p.optBoolean("enabled", true)) continue
            out.add(Pair(p, m.optString("id")))
        }
        return out
    }

    private fun resolveKey(key: String, out: MutableList<Pair<JSONObject, String>>, skipCooled: Boolean) {
        val m = findModel(key) ?: return
        if (m.optBoolean("hidden", false)) return
        if (!m.optBoolean("enabled", true)) return
        val p = providerByName(m.optString("provider")) ?: return
        if (!p.optBoolean("enabled", true)) return
        if (skipCooled && isCooledDown(key)) return
        out.add(Pair(p, m.optString("id")))
    }

    // Resolve a list of model keys to a chain, skipping 429-cooled-down
    // models — unless every model in the chain is cooled down, in which
    // case the cooled models are used anyway (a slow answer beats none).
    private fun resolveKeys(keys: List<String>): List<Pair<JSONObject, String>> {
        val out = ArrayList<Pair<JSONObject, String>>()
        for (k in keys) resolveKey(k, out, skipCooled = true)
        if (out.isEmpty()) for (k in keys) resolveKey(k, out, skipCooled = false)
        return out
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
        return resolveKeys(comboKeysRotated(name))
    }

    fun activeChain(): List<Pair<JSONObject, String>> {
        val a = cfg.optJSONObject("active") ?: return emptyList()
        val keys = ArrayList<String>()
        when (a.optString("type")) {
            "single", "combo" -> {
                val name = a.optString("name", "")
                if (a.optString("type") == "combo" && name.isNotBlank()) {
                    // Alias combos rotate too, so the active combo also
                    // spreads requests across the model's providers.
                    keys.addAll(comboKeysRotated(name))
                } else {
                    val ids = a.optJSONArray("models")
                    if (ids != null) for (i in 0 until ids.length()) keys.add(ids.optString(i))
                }
            }
        }
        return resolveKeys(keys)
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

    fun comboByName(n: String): JSONObject? = combosByName()[n]

    fun addCombo(n: String): Boolean {
        if (n.isBlank() || comboByName(n) != null) return false
        combos().put(JSONObject().put("name", n).put("models", JSONArray()))
        invalidateIndexes()
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
        invalidateIndexes()
        save()
    }

    fun renameCombo(old: String, new: String): Boolean {
        if (new.isBlank() || (comboByName(new) != null && old != new)) return false
        val c = comboByName(old) ?: return false
        // Renaming an alias or auto-best combo would break the rebuild
        // (it would recreate the auto one and leave the renamed one
        // behind as a stale custom combo).
        if (c.optBoolean("autoAlias", false) || c.optBoolean("autoBest", false)) return false
        c.put("name", new)
        if (activeComboName() == old) cfg.getJSONObject("active").put("name", new)
        invalidateIndexes()
        save()
        return true
    }

    fun comboKeys(n: String): List<String> {
        val ids = comboByName(n)?.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until ids.length()) out.add(ids.optString(i))
        return out
    }

    // ---- auto-best combo (v0.5.5) ----
    // Replaces the v0.5.2 alias combos (one combo per model id) and the
    // preinstalled "auto" combo. There is exactly one auto-managed
    // combo: "auto-best". It holds every model that actually replied
    // successfully (usable + lastOk), ordered fastest first — so a
    // model only joins after it has really answered, and a model that
    // starts failing leaves on the next rebuild. No rotation: the
    // order is the fallback order (fastest first). Custom combos are
    // never touched; a custom combo that happens to be named
    // "auto-best" is left alone, and the old "auto" combo is removed
    // (it was the preinstalled auto combo, not a custom one — the
    // auto-best combo takes its place). If the active combo was one
    // of the removed auto combos, auto-best becomes active.
    // Diff-guarded: nothing is written when the desired state
    // already holds, so calling this on every refresh() is cheap.
    fun rebuildAutoBest() {
        val wasActiveAuto = activeComboName()?.let { n ->
            n == "auto" || isAutoManaged(n)
        } ?: false
        // 1. Remove all alias combos and the old "auto" combo.
        val cs = combos()
        val names = ArrayList<String>()
        for (i in 0 until cs.length()) names.add(cs.getJSONObject(i).optString("name"))
        for (n in names) {
            val c = comboByName(n) ?: continue
            if (c.optBoolean("autoAlias", false) || n == "auto") removeCombo(n)
        }
        // 2. Desired members: usable models that actually replied.
        val rows = ArrayList<Pair<String, Long>>()
        val ms = models()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val key = m.optString("provider") + "|" + m.optString("id")
            if (!isModelUsable(key)) continue
            if (!m.optBoolean("lastOk", false)) continue
            rows.add(Pair(key, m.optLong("lastLatencyMs", Long.MAX_VALUE)))
        }
        val desired = rows.sortedBy { it.second }.map { it.first }
        // 3. Create or update auto-best.
        val c = comboByName("auto-best")
        if (c == null) {
            if (addCombo("auto-best")) {
                comboByName("auto-best")?.put("autoBest", true)
                comboSet("auto-best", desired)
                save()
            }
        } else if (c.optBoolean("autoBest", false)) {
            if (comboKeys("auto-best") != desired) comboSet("auto-best", desired)
        }
        // else: a custom combo owns this name — leave it alone.
        // 4. Keep something active if the active combo was removed.
        if (wasActiveAuto && comboByName("auto-best")?.optBoolean("autoBest", false) == true) {
            activateCombo("auto-best")
        }
    }

    /** Usable = the model can actually be used at runtime: it exists,
     *  is enabled, its provider exists and is enabled, and it did not
     *  fail its last test. Same rule as the Models tab's Hide failed
     *  (MainActivity.isUnusable). Used to keep hidden models out of
     *  combos on every add path, not just the Add-models dialog. */
    fun isModelUsable(key: String): Boolean {
        val m = findModel(key) ?: return false
        if (m.optBoolean("hidden", false)) return false
        if (!m.optBoolean("enabled", true)) return false
        if (m.optBoolean("paid", false)) return false
        val p = providerByName(m.optString("provider")) ?: return false
        if (!p.optBoolean("enabled", true)) return false
        return !(m.has("lastOk") && !m.optBoolean("lastOk"))
    }

    fun comboAddModel(n: String, key: String): Boolean {
        val c = comboByName(n) ?: return false
        // Alias and auto-best combos are auto-managed — hand-adding a
        // model would be overwritten by the next rebuild, so refuse
        // it here (covers the dialog and the long-press "Add to combo"
        // path).
        if (c.optBoolean("autoAlias", false) || c.optBoolean("autoBest", false)) return false
        if (!isModelUsable(key)) return false
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
    // Returns (added, removed, addedKeys) where addedKeys are the
    // "provider|id" keys of the newly added models, for Test-new-models.
    // The paid flag comes from the provider's pricing (see
    // ProviderClient.ModelInfo); it is only updated when this response
    // actually carries pricing — absence of pricing must not clear a
    // paid flag set by an earlier response.
    fun refreshProviderModels(providerName: String, fresh: List<ProviderClient.ModelInfo>): Triple<Int, Int, List<String>> {
        // Empty list = the provider answered but listed nothing (outage,
        // key problem, half-broken relay). Keep the stored models instead
        // of wiping them: a refresh must never delete everything on a
        // bad day. The user can still remove models by hand.
        if (fresh.isEmpty()) return Triple(0, 0, emptyList())
        val ms = models()
        val byId = fresh.associateBy { it.id }
        val keepIds = byId.keys
        var added = 0
        var removed = 0
        val addedKeys = ArrayList<String>()
        val km = JSONArray()
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            if (m.optString("provider") == providerName) {
                if (keepIds.contains(m.optString("id"))) {
                    val info = byId[m.optString("id")]
                    if (info != null && info.hasPricing) m.put("paid", info.paid)
                    km.put(m)
                } else removed++
            } else km.put(m)
        }
        for (info in fresh) {
            var have = false
            for (i in 0 until km.length()) {
                val m = km.getJSONObject(i)
                if (m.optString("provider") == providerName && m.optString("id") == info.id) { have = true; break }
            }
            if (!have) {
                km.put(JSONObject().put("provider", providerName).put("id", info.id)
                    .put("enabled", true).put("paid", info.paid))
                added++
                addedKeys.add(providerName + "|" + info.id)
            }
        }
        cfg.put("models", km)
        invalidateIndexes()
        save()
        return Triple(added, removed, addedKeys)
    }

    // ---- log + results ----
    fun addLog(e: LogEntry) {
        synchronized(log) { log.add(e); while (log.size > LOG_CAP) log.removeAt(0) }
        save()
    }

    // Total tokens used by one model, summed over every served request
    // (see HttpServer usage parsing). Stored on the model itself, so it
    // survives restarts; the debounced save() keeps the write cheap.
    fun addTokens(key: String, n: Int) {
        if (n <= 0) return
        val m = findModel(key) ?: return
        m.put("usedTokens", m.optLong("usedTokens", 0L) + n)
        save()
    }

    // permanentFail = this failure will not be fixed by retrying
    // (account/model-gone errors: 401/402/403/404, or a provider
    // rejection on a 200). Stored as lastFailPermanent so Test All can
    // skip these models next time. lastTestTs records when the model
    // was last tested, so fresh passes can be skipped for 24h.
    fun bumpModel(key: String, ok: Boolean, latency: Long, err: String, permanentFail: Boolean = false) {
        val m = findModel(key) ?: return
        m.put("lastOk", ok).put("lastLatencyMs", latency)
        m.put("lastTestTs", System.currentTimeMillis())
        if (ok) {
            m.put("failStreak", 0)
            m.put("lastFailPermanent", false)
            if (!m.optBoolean("enabled", true)) m.put("enabled", true)  // re-test pass brings it back
        } else {
            m.put("failStreak", m.optInt("failStreak", 0) + 1)
            m.put("lastFailPermanent", permanentFail)
            if (cfg.optBoolean("autoDisable", true) && m.optInt("failStreak", 0) >= 3) m.put("enabled", false)
        }
        if (err.isNotBlank()) m.put("lastError", err)
        save()
    }

    // ---- v0.3: provider enable/disable + rename (remaps model keys) ----
    fun setProviderEnabled(name: String, enabled: Boolean) {
        val p = providerByName(name) ?: return
        p.put("enabled", enabled)
        invalidateIndexes()
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
        invalidateIndexes()
        save()
        return true
    }
}
