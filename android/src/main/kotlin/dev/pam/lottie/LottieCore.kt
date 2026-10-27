package dev.pam.lottie

import dev.pam.nativeapp.protocol.WireValue

/** `LottieSourceKind` / `LottieResizeMode` / `LottieEventKind` of the IDL (integers from 1). */
internal object LottieIdl {
    const val SOURCE_URL = 1
    const val SOURCE_JSON = 2

    const val RESIZE_CONTAIN = 1
    const val RESIZE_COVER = 2
    const val RESIZE_CENTER = 3

    const val EVENT_LOADED = 1
    const val EVENT_FAILED = 2
    const val EVENT_FINISHED = 3
}

/** Host properties of one `lottie.view` (see `Lottie::hostProperties()`). */
internal data class LottieProps(
    val kind: Int,
    val source: String,
    val key: String,
    val loop: Boolean,
    val playing: Boolean,
    val speed: Float,
    val resize: Int,
    /** Pinned progress (0..1), or null when the animation runs. */
    val progress: Float?,
    val fallback: String,
    val fallbackColor: Int?,
    val fallbackSize: Float?,
    val events: Boolean,
) {
    val hasSource: Boolean get() = source.isNotEmpty() && key.isNotEmpty()

    companion object {
        const val MIN_SPEED = 0.05f
        const val MAX_SPEED = 8f

        fun parse(values: Map<String, WireValue>): LottieProps {
            val kind = values.int("kind")?.takeIf { it == LottieIdl.SOURCE_URL || it == LottieIdl.SOURCE_JSON }
                ?: LottieIdl.SOURCE_URL
            val source = values.text("source").orEmpty()
            val speed = values.number("speed")?.takeIf { it.isFinite() && it != 0f } ?: 1f
            return LottieProps(
                kind = kind,
                source = source,
                key = values.text("key") ?: source,
                loop = values.flag("loop") ?: true,
                playing = values.flag("playing") ?: true,
                speed = if (speed < 0f) speed.coerceIn(-MAX_SPEED, -MIN_SPEED) else speed.coerceIn(MIN_SPEED, MAX_SPEED),
                resize = values.int("resize")?.takeIf { it in LottieIdl.RESIZE_CONTAIN..LottieIdl.RESIZE_CENTER }
                    ?: LottieIdl.RESIZE_CONTAIN,
                progress = values.number("progress")?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
                fallback = values.text("fallback").orEmpty(),
                fallbackColor = values.text("fallbackColor")?.let(LottieColor::argb),
                fallbackSize = values.number("fallbackSize")?.takeIf { it.isFinite() && it > 0f },
                events = values.flag("events") ?: false,
            )
        }

        private fun Map<String, WireValue>.text(name: String): String? = (this[name] as? WireValue.Text)?.value

        private fun Map<String, WireValue>.flag(name: String): Boolean? = (this[name] as? WireValue.Flag)?.value

        private fun Map<String, WireValue>.int(name: String): Int? = when (val value = this[name]) {
            is WireValue.Integer -> value.value.toInt()
            is WireValue.Decimal -> value.value.toInt()
            else -> null
        }

        private fun Map<String, WireValue>.number(name: String): Float? = when (val value = this[name]) {
            is WireValue.Integer -> value.value.toFloat()
            is WireValue.Decimal -> value.value.toFloat()
            else -> null
        }
    }
}

/** `#rgb`, `#rrggbb` and CSS `#rrggbbaa` to ARGB. */
internal object LottieColor {
    fun argb(value: String): Int? {
        val hex = value.trim().removePrefix("#")
        if (hex.isEmpty() || hex.any { Character.digit(it, 16) < 0 }) return null
        return when (hex.length) {
            3 -> (0xFF shl 24) or hex.map { Character.digit(it, 16) * 17 }.let { (r, g, b) -> (r shl 16) or (g shl 8) or b }
            6 -> (0xFF000000.toInt()) or hex.toLong(16).toInt()
            8 -> hex.toLong(16).let { rgba -> (((rgba and 0xFF) shl 24) or (rgba ushr 8)).toInt() }
            else -> null
        }
    }
}

/**
 * Least-recently-used map bounded by entry count and by an estimated byte
 * size; [trim] keeps a fraction of the byte budget (memory pressure).
 * Not thread-safe: the loader touches it from the main thread only.
 */
internal class LottieLru<V : Any>(
    private val maxEntries: Int,
    private val maxBytes: Long,
) {
    private class Entry<V>(val value: V, val bytes: Long)

    private val entries = LinkedHashMap<String, Entry<V>>(16, 0.75f, true)
    var bytes: Long = 0
        private set

    val size: Int get() = entries.size

    operator fun get(key: String): V? = entries[key]?.value

    fun put(key: String, value: V, bytes: Long) {
        val cost = bytes.coerceAtLeast(1)
        entries.remove(key)?.let { this.bytes -= it.bytes }
        if (cost > maxBytes) return
        entries[key] = Entry(value, cost)
        this.bytes += cost
        evict(maxEntries, maxBytes)
    }

    fun trim(keepFraction: Float) {
        val fraction = keepFraction.coerceIn(0f, 1f)
        evict((maxEntries * fraction).toInt(), (maxBytes * fraction).toLong())
    }

    fun keys(): List<String> = entries.keys.toList()

    private fun evict(entryLimit: Int, byteLimit: Long) {
        val iterator = entries.entries.iterator()
        while ((entries.size > entryLimit || bytes > byteLimit) && iterator.hasNext()) {
            bytes -= iterator.next().value.bytes
            iterator.remove()
        }
    }
}

/** Callbacks waiting on one in-flight load: a second request for the same key joins the first. */
internal class LottieInFlight<R> {
    private val waiting = HashMap<String, MutableList<(R) -> Unit>>()

    /** True when [key] was not loading yet and the caller must start the load. */
    fun join(key: String, callback: (R) -> Unit): Boolean {
        val list = waiting[key]
        if (list != null) {
            list += callback
            return false
        }
        waiting[key] = mutableListOf(callback)
        return true
    }

    fun complete(key: String, result: R) {
        waiting.remove(key)?.forEach { it(result) }
    }

    fun pending(key: String): Int = waiting[key]?.size ?: 0
}

/** What the view does with its animation for a given state (pure, unit tested). */
internal enum class LottiePlayback { RUN, PAUSE, PIN, FIRST_FRAME }

internal fun lottiePlayback(
    playing: Boolean,
    progress: Float?,
    attached: Boolean,
    visible: Boolean,
    animatorsEnabled: Boolean,
    finished: Boolean,
): LottiePlayback = when {
    progress != null -> LottiePlayback.PIN
    !animatorsEnabled -> LottiePlayback.FIRST_FRAME
    playing && attached && visible && !finished -> LottiePlayback.RUN
    else -> LottiePlayback.PAUSE
}

/** Estimated retained size of a parsed composition (the JSON text dominates). */
internal fun lottieCompositionBytes(jsonLength: Int): Long = jsonLength.toLong() * 2 + 4_096
