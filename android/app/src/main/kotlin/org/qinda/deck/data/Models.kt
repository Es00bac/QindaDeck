package org.qinda.deck.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * The schema in protocol/qindadeck.schema.json, as Kotlin.
 *
 * Mapping is written by hand against org.json rather than generated, for one
 * reason worth stating: the firmware validates and stores whatever it is sent, so
 * a field silently dropped on the way through the editor would silently delete
 * part of the user's profile. Explicit `toJson` makes that a visible omission.
 */

// ---------------------------------------------------------------- actions

enum class ActionKind(val wire: String, val label: String) {
    NOOP("noop", "Nothing"),
    TAP("tap", "Key chord"),
    HOLD("hold", "Hold key"),
    TEXT("text", "Type text"),
    CONSUMER("consumer", "Media key"),
    MACRO("macro", "Macro"),
    MACRO_REF("macro_ref", "Saved macro"),
    PAGE("page", "Switch page"),
    PROFILE("profile", "Switch profile");

    companion object {
        fun from(wire: String?) = entries.firstOrNull { it.wire == wire } ?: NOOP
    }
}

/**
 * What a press does. Deck buttons carry these fields inline; gamepad bindings
 * nest them under `action`. Both are produced by [writeInto].
 */
data class Action(
    val kind: ActionKind = ActionKind.NOOP,
    val keys: String = "",
    val text: String = "",
    val media: String = "",
    val macro: Macro? = null,
    val macroRef: String = "",
    val page: Int = 0,
    val profile: String = "",
    val repeatOnHold: Boolean = false,
) {
    fun writeInto(o: JSONObject): JSONObject {
        o.put("kind", kind.wire)
        when (kind) {
            ActionKind.TAP, ActionKind.HOLD -> o.put("keys", keys)
            ActionKind.TEXT -> o.put("text", text)
            ActionKind.CONSUMER -> o.put("media", media)
            ActionKind.MACRO -> o.put("macro", (macro ?: Macro()).toJson())
            ActionKind.MACRO_REF -> o.put("macro_ref", macroRef)
            ActionKind.PAGE -> o.put("page", page)
            ActionKind.PROFILE -> o.put("profile", profile)
            ActionKind.NOOP -> {}
        }
        if (repeatOnHold) o.put("repeat_on_hold", true)
        return o
    }

    fun toJson(): JSONObject = writeInto(JSONObject())

    /** A one-line description for list rows. */
    fun summary(): String = when (kind) {
        ActionKind.NOOP -> "—"
        ActionKind.TAP -> keys.ifEmpty { "(no chord)" }
        ActionKind.HOLD -> "hold ${keys.ifEmpty { "(no chord)" }}"
        ActionKind.TEXT -> "\"${text.take(24)}\""
        ActionKind.CONSUMER -> media.replace('_', ' ')
        ActionKind.MACRO -> "${macro?.steps?.size ?: 0} step macro"
        ActionKind.MACRO_REF -> "macro \"$macroRef\""
        ActionKind.PAGE -> "page ${page + 1}"
        ActionKind.PROFILE -> "profile $profile"
    }

    companion object {
        fun read(o: JSONObject): Action {
            val kind = ActionKind.from(o.optString("kind", "noop"))
            return Action(
                kind = kind,
                keys = o.optString("keys", ""),
                text = o.optString("text", ""),
                media = o.optString("media", ""),
                macro = o.optJSONObject("macro")?.let { Macro.read(it) },
                macroRef = o.optString("macro_ref", ""),
                page = o.optInt("page", 0),
                profile = o.optString("profile", ""),
                repeatOnHold = o.optBoolean("repeat_on_hold", false),
            )
        }
    }
}

// ---------------------------------------------------------------- macros

enum class Easing(val wire: String, val label: String) {
    LINEAR("linear", "Linear"),
    IN("in", "Ease in"),
    OUT("out", "Ease out"),
    IN_OUT("in_out", "Ease in-out");

    companion object {
        fun from(w: String?) = entries.firstOrNull { it.wire == w } ?: IN_OUT
    }
}

enum class StepKind(val wire: String, val label: String) {
    TAP("tap", "Tap chord"),
    DOWN("down", "Hold chord down"),
    UP("up", "Release chord"),
    TEXT("text", "Type text"),
    CONSUMER("consumer", "Media key"),
    MOVE_ABS("move_abs", "Move to point"),
    MOVE_REL("move_rel", "Move by offset"),
    MDOWN("mdown", "Mouse button down"),
    MUP("mup", "Mouse button up"),
    CLICK("click", "Click"),
    DRAG("drag", "Drag"),
    SCROLL("scroll", "Scroll"),
    DELAY("delay", "Wait"),
    REPEAT("repeat", "Repeat block");

    val movesPointer: Boolean
        get() = this == MOVE_ABS || this == MOVE_REL || this == DRAG

    companion object {
        fun from(w: String?) = entries.firstOrNull { it.wire == w } ?: DELAY
    }
}

data class Point(val x: Int = 0, val y: Int = 0) {
    fun toJson(): JSONObject = JSONObject().put("x", x).put("y", y)

    companion object {
        fun read(o: JSONObject?): Point? =
            o?.let { Point(it.optInt("x", 0), it.optInt("y", 0)) }
    }
}

data class Step(
    val kind: StepKind = StepKind.DELAY,
    val after: Int = 0,
    val keys: String = "",
    val text: String = "",
    val media: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val dx: Int = 0,
    val dy: Int = 0,
    val display: String = "",
    val dur: Int = 400,
    val ease: Easing = Easing.IN_OUT,
    val jitter: Int = 0,
    val btn: String = "left",
    val count: Int = 1,
    val gap: Int = 60,
    val from: Point? = null,
    val to: Point? = null,
    val amount: Int = 3,
    val hamount: Int = 0,
    val ms: Int = 200,
    val steps: List<Step> = emptyList(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("t", kind.wire)
        if (after > 0) o.put("after", after)
        when (kind) {
            StepKind.TAP, StepKind.DOWN, StepKind.UP -> o.put("keys", keys)
            StepKind.TEXT -> o.put("s", text)
            StepKind.CONSUMER -> o.put("media", media)
            StepKind.MOVE_ABS -> {
                o.put("x", x).put("y", y).put("dur", dur).put("ease", ease.wire)
                if (jitter > 0) o.put("jitter", jitter)
                if (display.isNotEmpty()) o.put("display", display)
            }
            StepKind.MOVE_REL -> {
                o.put("dx", dx).put("dy", dy).put("dur", dur).put("ease", ease.wire)
                if (jitter > 0) o.put("jitter", jitter)
            }
            StepKind.MDOWN, StepKind.MUP -> o.put("btn", btn)
            StepKind.CLICK -> o.put("btn", btn).put("count", count).put("gap", gap)
            StepKind.DRAG -> {
                o.put("btn", btn).put("dur", dur).put("ease", ease.wire)
                o.put("to", (to ?: Point()).toJson())
                from?.let { o.put("from", it.toJson()) }
                if (jitter > 0) o.put("jitter", jitter)
                if (display.isNotEmpty()) o.put("display", display)
            }
            StepKind.SCROLL -> {
                o.put("amount", amount)
                if (hamount != 0) o.put("hamount", hamount)
                if (dur > 0) o.put("dur", dur)
            }
            StepKind.DELAY -> o.put("ms", ms)
            StepKind.REPEAT -> {
                o.put("count", count)
                o.put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
            }
        }
        return o
    }

    /** Row subtitle in the macro editor. */
    fun summary(): String = when (kind) {
        StepKind.TAP -> keys
        StepKind.DOWN -> "$keys down"
        StepKind.UP -> "$keys up"
        StepKind.TEXT -> "\"${text.take(28)}\""
        StepKind.CONSUMER -> media.replace('_', ' ')
        StepKind.MOVE_ABS -> "to $x, $y over ${dur} ms"
        StepKind.MOVE_REL -> "by $dx, $dy over ${dur} ms"
        StepKind.MDOWN -> "$btn down"
        StepKind.MUP -> "$btn up"
        StepKind.CLICK -> if (count > 1) "$btn ×$count" else btn
        StepKind.DRAG -> {
            val f = from?.let { "${it.x}, ${it.y}" } ?: "here"
            "$f → ${to?.x ?: 0}, ${to?.y ?: 0} over ${dur} ms"
        }
        StepKind.SCROLL -> "${if (amount > 0) "up" else "down"} ${kotlin.math.abs(amount)}"
        StepKind.DELAY -> "$ms ms"
        StepKind.REPEAT -> "${steps.size} step(s) ×$count"
    }

    /** Wall-clock cost, used to show a macro's total duration before running it. */
    fun durationMs(): Int = when (kind) {
        StepKind.DELAY -> ms + after
        StepKind.MOVE_ABS, StepKind.MOVE_REL -> dur + after
        StepKind.DRAG -> dur + 240 + after
        StepKind.SCROLL -> dur + after
        StepKind.CLICK -> count * gap + after
        StepKind.TEXT -> text.length * 16 + after
        StepKind.REPEAT -> count * steps.sumOf { it.durationMs() } + after
        StepKind.TAP -> 12 + after
        else -> after
    }

    companion object {
        fun read(o: JSONObject): Step {
            val kind = StepKind.from(o.optString("t"))
            val inner = o.optJSONArray("steps")
            return Step(
                kind = kind,
                after = o.optInt("after", 0),
                keys = o.optString("keys", ""),
                text = o.optString("s", ""),
                media = o.optString("media", ""),
                x = o.optInt("x", 0),
                y = o.optInt("y", 0),
                dx = o.optInt("dx", 0),
                dy = o.optInt("dy", 0),
                display = o.optString("display", ""),
                dur = o.optInt("dur", if (kind.movesPointer) 400 else 0),
                ease = Easing.from(o.optString("ease")),
                jitter = o.optInt("jitter", 0),
                btn = o.optString("btn", "left"),
                count = o.optInt("count", 1),
                gap = o.optInt("gap", 60),
                from = Point.read(o.optJSONObject("from")),
                to = Point.read(o.optJSONObject("to")),
                amount = o.optInt("amount", 3),
                hamount = o.optInt("hamount", 0),
                ms = o.optInt("ms", 200),
                steps = buildList {
                    for (i in 0 until (inner?.length() ?: 0)) {
                        add(read(inner!!.getJSONObject(i)))
                    }
                },
            )
        }
    }
}

data class Macro(
    val name: String = "Macro",
    val loop: Int = 1,
    val steps: List<Step> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("loop", loop)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })

    fun durationMs(): Int = loop * steps.sumOf { it.durationMs() }

    companion object {
        fun read(o: JSONObject): Macro {
            val arr = o.optJSONArray("steps")
            return Macro(
                name = o.optString("name", "Macro"),
                loop = o.optInt("loop", 1),
                steps = buildList {
                    for (i in 0 until (arr?.length() ?: 0)) add(Step.read(arr!!.getJSONObject(i)))
                },
            )
        }
    }
}

// ---------------------------------------------------------------- profiles

data class DeckButton(
    val index: Int,
    val label: String = "",
    val sub: String = "",
    val color: String = "",
    val action: Action = Action(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("index", index)
        if (label.isNotEmpty()) o.put("label", label)
        if (sub.isNotEmpty()) o.put("sub", sub)
        if (color.isNotEmpty()) o.put("color", color)
        return action.writeInto(o)
    }

    companion object {
        fun read(o: JSONObject) = DeckButton(
            index = o.optInt("index", 0),
            label = o.optString("label", ""),
            sub = o.optString("sub", ""),
            color = o.optString("color", ""),
            action = Action.read(o),
        )
    }
}

data class DeckPage(
    val name: String = "Page",
    val cols: Int = 4,
    val rows: Int = 4,
    val buttons: List<DeckButton> = emptyList(),
) {
    fun buttonAt(index: Int): DeckButton? = buttons.firstOrNull { it.index == index }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("cols", cols)
        .put("rows", rows)
        .put("buttons", JSONArray().apply { buttons.forEach { put(it.toJson()) } })

    companion object {
        fun read(o: JSONObject): DeckPage {
            val arr = o.optJSONArray("buttons")
            return DeckPage(
                name = o.optString("name", "Page"),
                cols = o.optInt("cols", 4),
                rows = o.optInt("rows", 4),
                buttons = buildList {
                    for (i in 0 until (arr?.length() ?: 0)) {
                        add(DeckButton.read(arr!!.getJSONObject(i)))
                    }
                },
            )
        }
    }
}

data class Profile(
    val slug: String,
    val name: String,
    val accent: String = "",
    val icon: String = "",
    val builtin: Boolean = false,
    val pages: List<DeckPage> = emptyList(),
    val macros: Map<String, Macro> = emptyMap(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("schema", 1)
            .put("slug", slug)
            .put("name", name)
        if (accent.isNotEmpty()) o.put("accent", accent)
        if (icon.isNotEmpty()) o.put("icon", icon)
        if (builtin) o.put("builtin", true)
        o.put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
        if (macros.isNotEmpty()) {
            o.put("macros", JSONObject().apply {
                macros.forEach { (k, v) -> put(k, v.toJson()) }
            })
        }
        return o
    }

    companion object {
        fun read(o: JSONObject): Profile {
            val arr = o.optJSONArray("pages")
            val macroObj = o.optJSONObject("macros")
            return Profile(
                slug = o.optString("slug"),
                name = o.optString("name"),
                accent = o.optString("accent", ""),
                icon = o.optString("icon", ""),
                builtin = o.optBoolean("builtin", false),
                pages = buildList {
                    for (i in 0 until (arr?.length() ?: 0)) add(DeckPage.read(arr!!.getJSONObject(i)))
                },
                macros = buildMap {
                    macroObj?.keys()?.forEach { k ->
                        macroObj.optJSONObject(k)?.let { put(k, Macro.read(it)) }
                    }
                },
            )
        }
    }
}

/** List-screen summary; the full profile is fetched only when opened. */
data class ProfileSummary(
    val slug: String,
    val name: String,
    val accent: String,
    val pages: Int,
    val builtin: Boolean,
) {
    companion object {
        fun read(o: JSONObject) = ProfileSummary(
            slug = o.optString("slug"),
            name = o.optString("name"),
            accent = o.optString("accent", ""),
            pages = o.optInt("pages", 0),
            builtin = o.optBoolean("builtin", false),
        )
    }
}

// ---------------------------------------------------------------- displays

data class DisplayCfg(
    val id: String,
    val name: String = "",
    val w: Int = 1920,
    val h: Int = 1080,
    val x: Int = 0,
    val y: Int = 0,
    val scale: Double = 1.0,
    val primary: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("w", w).put("h", h).put("x", x).put("y", y)
        .put("scale", scale).put("primary", primary)

    companion object {
        fun read(o: JSONObject) = DisplayCfg(
            id = o.optString("id"),
            name = o.optString("name", ""),
            w = o.optInt("w", 1920),
            h = o.optInt("h", 1080),
            x = o.optInt("x", 0),
            y = o.optInt("y", 0),
            scale = o.optDouble("scale", 1.0),
            primary = o.optBoolean("primary", false),
        )
    }
}

data class DisplayConfig(
    val active: String = "",
    val displays: List<DisplayCfg> = emptyList(),
) {
    val virtualWidth: Int get() = (displays.maxOfOrNull { it.x + it.w } ?: 1920) -
        (displays.minOfOrNull { it.x } ?: 0)
    val virtualHeight: Int get() = (displays.maxOfOrNull { it.y + it.h } ?: 1080) -
        (displays.minOfOrNull { it.y } ?: 0)

    fun byId(id: String): DisplayCfg? = displays.firstOrNull { it.id == id }

    /** The display a step with this id targets, falling back the way the firmware does. */
    fun resolve(id: String): DisplayCfg? =
        byId(id) ?: byId(active) ?: displays.firstOrNull { it.primary } ?: displays.firstOrNull()

    fun toJson(): JSONObject = JSONObject()
        .put("active", active)
        .put("displays", JSONArray().apply { displays.forEach { put(it.toJson()) } })

    companion object {
        fun read(o: JSONObject): DisplayConfig {
            val arr = o.optJSONArray("displays")
            return DisplayConfig(
                active = o.optString("active", ""),
                displays = buildList {
                    for (i in 0 until (arr?.length() ?: 0)) add(DisplayCfg.read(arr!!.getJSONObject(i)))
                },
            )
        }
    }
}

// ---------------------------------------------------------------- bluetooth

data class BtDevice(
    val addr: String,
    val name: String = "",
    val rssi: Int = 0,
    val what: String = "device",
    val addrType: Int = 0,
    val connected: Boolean = false,
) {
    val display: String get() = name.ifBlank { addr }

    companion object {
        fun read(o: JSONObject) = BtDevice(
            addr = o.optString("addr"),
            name = o.optString("name", ""),
            rssi = o.optInt("rssi", 0),
            what = o.optString("what", "device"),
            addrType = o.optInt("addr_type", 0),
            connected = o.optBoolean("connected", false),
        )
    }
}

/** Which physical input a binding watches for. See remap.h. */
sealed interface SrcSpec {
    fun toJson(): JSONObject
    fun label(): String

    data class Btn(val byte: Int, val bit: Int) : SrcSpec {
        override fun toJson() = JSONObject().put("kind", "btn").put("byte", byte).put("bit", bit)
        override fun label() = "button · byte $byte bit $bit"
    }

    data class Axis(val byte: Int, val dir: String, val thr: Int) : SrcSpec {
        override fun toJson() =
            JSONObject().put("kind", "axis").put("byte", byte).put("dir", dir).put("thr", thr)
        override fun label() =
            "axis · byte $byte ${if (dir == "lo") "below" else "above"} $thr"
    }

    data class Key(val usage: Int) : SrcSpec {
        override fun toJson() = JSONObject().put("kind", "key").put("usage", usage)
        override fun label() = "key · usage 0x%02X".format(usage)
    }

    companion object {
        fun read(o: JSONObject?): SrcSpec? {
            o ?: return null
            return when (o.optString("kind")) {
                "btn" -> Btn(o.optInt("byte"), o.optInt("bit"))
                "axis" -> Axis(o.optInt("byte"), o.optString("dir", "hi"), o.optInt("thr", 128))
                "key" -> Key(o.optInt("usage"))
                else -> null
            }
        }
    }
}

data class Binding(
    val src: SrcSpec,
    val label: String = "",
    val action: Action = Action(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("src", src.toJson())
        .put("label", label)
        .put("action", action.toJson())

    companion object {
        fun read(o: JSONObject): Binding? {
            val src = SrcSpec.read(o.optJSONObject("src")) ?: return null
            return Binding(
                src = src,
                label = o.optString("label", ""),
                action = o.optJSONObject("action")?.let { Action.read(it) } ?: Action(),
            )
        }
    }
}

data class RemapDoc(
    val addr: String,
    val name: String = "",
    val passthrough: Boolean = false,
    val bindings: List<Binding> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("addr", addr)
        .put("name", name)
        .put("passthrough", passthrough)
        .put("bindings", JSONArray().apply { bindings.forEach { put(it.toJson()) } })

    companion object {
        fun read(o: JSONObject): RemapDoc {
            val arr = o.optJSONArray("bindings")
            return RemapDoc(
                addr = o.optString("addr"),
                name = o.optString("name", ""),
                passthrough = o.optBoolean("passthrough", false),
                bindings = buildList {
                    for (i in 0 until (arr?.length() ?: 0)) {
                        Binding.read(arr!!.getJSONObject(i))?.let { add(it) }
                    }
                },
            )
        }
    }
}

/** Device identity and live state, from `hello` / `get_status`. */
data class DeckStatus(
    val fw: String = "",
    val name: String = "",
    val ip: String = "",
    val mac: String = "",
    val usb: Boolean = false,
    val apMode: Boolean = false,
    val heap: Int = 0,
    val uptime: Int = 0,
    val active: String = "",
    val page: Int = 0,
    val profiles: Int = 0,
    val absMax: Int = 32767,
    val tick: Int = 8,
    val running: String? = null,
    val learn: Boolean = false,
    val btDevices: List<BtDevice> = emptyList(),
) {
    companion object {
        fun read(o: JSONObject): DeckStatus {
            val arr = o.optJSONArray("bt_devices")
            return DeckStatus(
                fw = o.optString("fw", ""),
                name = o.optString("name", ""),
                ip = o.optString("ip", ""),
                mac = o.optString("mac", ""),
                usb = o.optBoolean("usb", false),
                apMode = o.optBoolean("ap", false),
                heap = o.optInt("heap", 0),
                uptime = o.optInt("uptime", 0),
                active = o.optString("active", ""),
                page = o.optInt("page", 0),
                profiles = o.optInt("profiles", 0),
                absMax = o.optInt("abs_max", 32767),
                tick = o.optInt("tick", 8),
                running = if (o.isNull("running")) null else o.optString("running"),
                learn = o.optBoolean("learn", false),
                btDevices = buildList {
                    for (i in 0 until (arr?.length() ?: 0)) add(BtDevice.read(arr!!.getJSONObject(i)))
                },
            )
        }
    }
}
