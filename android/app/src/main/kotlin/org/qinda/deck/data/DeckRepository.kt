package org.qinda.deck.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.qinda.deck.net.DeckClient
import org.qinda.deck.net.DeckException
import org.qinda.deck.net.Link
import org.qinda.deck.net.HidUsage
import org.qinda.deck.ble.WifiSync
import org.qinda.deck.voice.Ascii
import org.qinda.deck.voice.VoiceTyper

/**
 * Single source of truth for the app.
 *
 * The device owns the data, so this is a cache rather than a store: every edit is
 * written through to the deck and the reply decides whether the local copy
 * updates. That keeps the two from drifting, and means firmware validation
 * errors surface as the user's error rather than appearing later as a silently
 * broken button.
 */
class DeckRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("qindadeck", Context.MODE_PRIVATE)

    val client = DeckClient(scope)
    val link: StateFlow<Link> get() = client.link
    val events: SharedFlow<JSONObject> get() = client.events

    private val _status = MutableStateFlow(DeckStatus())
    val status: StateFlow<DeckStatus> = _status.asStateFlow()

    private val _profiles = MutableStateFlow<List<ProfileSummary>>(emptyList())
    val profiles: StateFlow<List<ProfileSummary>> = _profiles.asStateFlow()

    private val _displays = MutableStateFlow(DisplayConfig())
    val displays: StateFlow<DisplayConfig> = _displays.asStateFlow()

    private val _btDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    val btDevices: StateFlow<List<BtDevice>> = _btDevices.asStateFlow()

    /** Full profiles, fetched lazily and kept for the deck and editor screens. */
    private val _loaded = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val loaded: StateFlow<Map<String, Profile>> = _loaded.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // ------------------------------------------------------------ voice typing

    /** The speech engine. Lives here so dictation survives tab switches. */
    val voice = VoiceTyper(context)

    /** Send Enter after each spoken phrase — chat and terminal style. */
    private val _voiceEnterAfter = MutableStateFlow(prefs.getBoolean("voice_enter", false))
    val voiceEnterAfter: StateFlow<Boolean> = _voiceEnterAfter.asStateFlow()

    /** Separate spoken phrases with a space when typing them. */
    private val _voiceSpaceBetween = MutableStateFlow(prefs.getBoolean("voice_space", true))
    val voiceSpaceBetween: StateFlow<Boolean> = _voiceSpaceBetween.asStateFlow()

    fun setVoiceEnterAfter(on: Boolean) {
        _voiceEnterAfter.value = on
        prefs.edit().putBoolean("voice_enter", on).apply()
    }

    fun setVoiceSpaceBetween(on: Boolean) {
        _voiceSpaceBetween.value = on
        prefs.edit().putBoolean("voice_space", on).apply()
    }

    var lastHost: String
        get() = prefs.getString("host", "") ?: ""
        private set(v) = prefs.edit().putString("host", v).apply()

    init {
        // Device-pushed events keep the cache honest without polling.
        scope.launch {
            client.events.collect { ev ->
                when (ev.optString("ev")) {
                    "hello" -> _status.value = DeckStatus.read(ev)
                    "usb" -> _status.value = _status.value.copy(usb = ev.optBoolean("mounted"))
                    "macro" -> {
                        val state = ev.optString("state")
                        _status.value = _status.value.copy(
                            running = if (state == "start") ev.optString("name") else null,
                        )
                        if (state == "abort") {
                            ev.optString("reason").takeIf { it.isNotEmpty() && it != "stopped" }
                                ?.let { say("Macro stopped: $it") }
                        }
                    }
                    "bt" -> handleBtEvent(ev)
                }
            }
        }

        // Voice finals become keystrokes as they arrive. Typing is serialized:
        // one utterance finishes before the next starts, or words would
        // interleave on the host.
        scope.launch {
            voice.finals.collect { segment ->
                runCatching {
                    var text = segment
                    if (_voiceSpaceBetween.value) text += " "
                    typeText(text)
                    if (_voiceEnterAfter.value) client.tapKey(HidUsage.ENTER)
                }.onFailure { say("Voice typing failed: ${it.message}") }
            }
        }
    }

    private fun handleBtEvent(ev: JSONObject) {
        val addr = ev.optString("addr")
        when (ev.optString("state")) {
            "connected" -> {
                say("${ev.optString("name").ifBlank { addr }} connected")
                scope.launch { runCatching { refreshBt() } }
            }
            "disconnected" -> {
                say("$addr disconnected")
                _btDevices.value = _btDevices.value.map {
                    if (it.addr == addr) it.copy(connected = false) else it
                }
            }
            "failed" -> say(ev.optString("reason").ifBlank { "Could not connect" })
        }
    }

    fun say(message: String) {
        _toast.value = message
    }

    fun clearToast() {
        _toast.value = null
    }

    /** BLE Wi-Fi provisioning, driven manually from Settings → Wi-Fi. */
    val wifiSync = WifiSync(context)

    // ------------------------------------------------------------ connection

    suspend fun connect(host: String) {
        client.connect(host)
        lastHost = host
        refreshAll()
    }

    suspend fun disconnect() {
        client.disconnect()
    }

    suspend fun refreshAll() {
        refreshStatus()
        refreshProfiles()
        refreshDisplays()
        refreshBt()
    }

    suspend fun refreshStatus() {
        _status.value = DeckStatus.read(client.call("get_status"))
    }

    suspend fun refreshProfiles() {
        val arr = client.call("list_profiles").optJSONArray("profiles")
        _profiles.value = buildList {
            for (i in 0 until (arr?.length() ?: 0)) add(ProfileSummary.read(arr!!.getJSONObject(i)))
        }
    }

    suspend fun refreshDisplays() {
        val obj = client.call("get_displays").optJSONObject("displays") ?: return
        _displays.value = DisplayConfig.read(obj)
    }

    suspend fun refreshBt() {
        val arr = client.call("bt_list").optJSONArray("devices")
        _btDevices.value = buildList {
            for (i in 0 until (arr?.length() ?: 0)) add(BtDevice.read(arr!!.getJSONObject(i)))
        }
    }

    // ------------------------------------------------------------ profiles

    suspend fun profile(slug: String, force: Boolean = false): Profile {
        if (!force) _loaded.value[slug]?.let { return it }
        val obj = client.call("get_profile") { put("slug", slug) }
            .optJSONObject("profile") ?: throw DeckException("no profile $slug")
        val p = Profile.read(obj)
        _loaded.value = _loaded.value + (slug to p)
        return p
    }

    suspend fun saveProfile(p: Profile) {
        client.call("put_profile") { put("profile", p.toJson()) }
        _loaded.value = _loaded.value + (p.slug to p)
        refreshProfiles()
    }

    suspend fun deleteProfile(slug: String) {
        client.call("del_profile") { put("slug", slug) }
        _loaded.value = _loaded.value - slug
        refreshProfiles()
    }

    suspend fun setActive(slug: String, page: Int) {
        _status.value = DeckStatus.read(client.call("set_active") {
            put("slug", slug); put("page", page)
        })
    }

    suspend fun press(slug: String, page: Int, index: Int, state: String) {
        client.call("press") {
            put("slug", slug); put("page", page); put("index", index); put("state", state)
        }
    }

    suspend fun runMacro(macro: Macro) {
        client.call("run_macro") { put("macro", macro.toJson()) }
    }

    /**
     * Type arbitrary text on the host as an ad-hoc macro of text steps. The
     * firmware caps one text step at 512 bytes and types ASCII only, so the
     * string is folded and chunked here (see voice/Ascii.kt).
     */
    suspend fun typeText(raw: String) {
        val folded = Ascii.fold(raw)
        if (folded.isBlank()) return
        val steps = Ascii.chunks(folded).map { Step(kind = StepKind.TEXT, text = it) }
        runMacro(Macro(name = "voice", steps = steps))
    }

    /** One key edge from the voice screen's quick keys. */
    fun tapKey(usage: Int, mods: Int = 0) = client.tapKey(usage, mods)

    suspend fun stopMacro() {
        client.call("stop_macro")
        _status.value = _status.value.copy(running = null)
    }

    // ------------------------------------------------------------ displays

    suspend fun saveDisplays(cfg: DisplayConfig) {
        client.call("put_displays") { put("displays", cfg.toJson()) }
        _displays.value = cfg
    }

    // ------------------------------------------------------------ bluetooth

    suspend fun btScan(seconds: Int = 6): List<BtDevice> {
        val arr = client.call("bt_scan") { put("seconds", seconds) }.optJSONArray("devices")
        return buildList {
            for (i in 0 until (arr?.length() ?: 0)) add(BtDevice.read(arr!!.getJSONObject(i)))
        }
    }

    suspend fun btPair(dev: BtDevice) {
        client.call("bt_pair") {
            put("addr", dev.addr); put("addr_type", dev.addrType); put("name", dev.name)
        }
        refreshBt()
    }

    suspend fun btForget(addr: String) {
        client.call("bt_forget") { put("addr", addr) }
        refreshBt()
    }

    suspend fun remap(addr: String): RemapDoc {
        val reply = client.call("get_remap") { put("addr", addr) }
        val obj = reply.optJSONObject("remap")
        return if (obj == null) RemapDoc(addr = addr) else RemapDoc.read(obj)
    }

    suspend fun saveRemap(doc: RemapDoc) {
        client.call("put_remap") { put("remap", doc.toJson()) }
    }

    suspend fun setLearn(on: Boolean, mute: Boolean = true) {
        client.call("learn") { put("on", on); put("mute", mute) }
        _status.value = _status.value.copy(learn = on)
    }

    // ------------------------------------------------------------ sd card files

    data class SdEntry(
        val name: String,
        val dir: Boolean,
        val size: Long,
        val mtime: Long,
    )

    data class SdStatus(
        val present: Boolean,
        val capacityMb: Long,
        val owner: String,   // "usb" | "app" | "none"
    )

    data class SdChunk(
        val size: Long,
        val offset: Long,
        val data: ByteArray,
        val eof: Boolean,
    )

    suspend fun sdStatus(): SdStatus {
        val r = client.call("sd_status")
        return SdStatus(
            present = r.optBoolean("present", false),
            capacityMb = r.optLong("capacity_mb", 0),
            owner = r.optString("owner", "none"),
        )
    }

    /** Take the card from the USB host. Returns true when a filesystem mounted. */
    suspend fun sdTake(): Boolean {
        return client.call("sd_take").optBoolean("filesystem", false)
    }

    suspend fun sdRelease() {
        client.call("sd_release")
    }

    /** Fire-and-forget release that outlives the calling screen's composition. */
    fun sdReleaseAsync() {
        scope.launch { runCatching { sdRelease() } }
    }

    suspend fun sdList(path: String): Pair<List<SdEntry>, Boolean> {
        val r = client.call("sd_list") { put("path", path) }
        val arr = r.optJSONArray("entries")
        val entries = buildList {
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr!!.getJSONObject(i)
                add(SdEntry(
                    name = o.optString("name"),
                    dir = o.optBoolean("dir", false),
                    size = o.optLong("size", 0),
                    mtime = o.optLong("mtime", 0),
                ))
            }
        }
        return entries to r.optBoolean("truncated", false)
    }

    suspend fun sdRead(path: String, offset: Long, max: Int = 24 * 1024): SdChunk {
        val r = client.call("sd_read") {
            put("path", path); put("offset", offset); put("max", max)
        }
        return SdChunk(
            size = r.optLong("size", 0),
            offset = r.optLong("offset", 0),
            data = android.util.Base64.decode(r.optString("data", ""), android.util.Base64.DEFAULT),
            eof = r.optBoolean("eof", true),
        )
    }

    suspend fun sdWrite(path: String, offset: Long, data: ByteArray) {
        client.call("sd_write") {
            put("path", path)
            put("offset", offset)
            put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
        }
    }

    suspend fun sdDelete(path: String) {
        client.call("sd_delete") { put("path", path) }
    }

    suspend fun sdMkdir(path: String) {
        client.call("sd_mkdir") { put("path", path) }
    }

    // ------------------------------------------------------------ maintenance

    suspend fun setName(name: String) {
        client.call("set_name") { put("name", name) }
        refreshStatus()
    }

    suspend fun reboot() = client.call("reboot")

    suspend fun factoryReset() = client.call("factory_reset")

    suspend fun wifiScan(): List<Pair<String, Int>> {
        val arr = client.call("wifi_scan").optJSONArray("nets")
        return buildList {
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr!!.getJSONObject(i)
                add(o.optString("ssid") to o.optInt("rssi"))
            }
        }
    }

    suspend fun wifiSet(ssid: String, pass: String) {
        client.call("wifi_set") { put("ssid", ssid); put("pass", pass) }
    }

    /**
     * Ask the deck to open its BLE provisioning window. Best-effort: a fresh
     * deck with no credentials keeps the window open on its own, and a deck
     * on another network is exactly the case this covers, so callers swallow
     * the failure and just try BLE next.
     */
    suspend fun btProv(seconds: Int = 120) {
        client.call("bt_prov") { put("seconds", seconds) }
    }
}
