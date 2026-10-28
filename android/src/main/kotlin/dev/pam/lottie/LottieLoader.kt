package dev.pam.lottie

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Result of one load: a composition or a failure message. */
internal sealed interface LottieLoad {
    data class Ready(val composition: LottieComposition) : LottieLoad
    data class Failed(val message: String) : LottieLoad
}

/**
 * Process-wide composition cache and loader.
 *
 * Compositions are kept in a [LottieLru] bounded by count and estimated
 * bytes (Lottie's own cache is bypassed with a null cache key), trimmed on
 * memory pressure. Downloads and parsing run on two low-priority threads;
 * concurrent requests for one key share a single load. Every public call and
 * every callback happens on the main thread.
 */
internal object LottieLoader {
    const val MAX_ENTRIES = 32
    const val MAX_BYTES = 12L * 1024 * 1024
    const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    private val cache = LottieLru<LottieComposition>(MAX_ENTRIES, MAX_BYTES)
    private val inFlight = LottieInFlight<LottieLoad>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "pam-lottie-load").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY + 1
        }
    }

    fun cached(key: String): LottieComposition? = cache[key]

    fun load(context: Context, props: LottieProps, callback: (LottieLoad) -> Unit) {
        cache[props.key]?.let {
            callback(LottieLoad.Ready(it))
            return
        }
        if (!inFlight.join(props.key, callback)) return
        val appContext = context.applicationContext
        val key = props.key
        val kind = props.kind
        val source = props.source
        worker.execute {
            val result = runCatching {
                val json = if (kind == LottieIdl.SOURCE_JSON) source else read(appContext, source)
                val parsed = LottieCompositionFactory.fromJsonStringSync(json, null)
                parsed.value?.let { it to json.length } ?: throw (parsed.exception ?: IllegalStateException("Invalid Lottie JSON."))
            }
            main.post {
                val load = result.fold(
                    onSuccess = { (composition, length) ->
                        cache.put(key, composition, lottieCompositionBytes(length))
                        LottieLoad.Ready(composition)
                    },
                    onFailure = { LottieLoad.Failed(it.message ?: it.javaClass.simpleName) },
                )
                inFlight.complete(key, load)
            }
        }
    }

    fun trim(keepFraction: Float) {
        if (Looper.myLooper() == Looper.getMainLooper()) cache.trim(keepFraction) else main.post { cache.trim(keepFraction) }
    }

    private fun read(context: Context, source: String): String {
        val uri = source.toUri()
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> download(source)
            "file" -> File(requireNotNull(uri.path) { "Invalid file URL." }).inputStream().use(::readBounded)
            "asset" -> context.assets.open((uri.host.orEmpty() + uri.path.orEmpty()).trimStart('/')).use(::readBounded)
            else -> throw IllegalArgumentException("Unsupported Lottie URL scheme.")
        }
    }

    private fun download(source: String): String {
        val connection = URL(source).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            if (connection.contentLengthLong > MAX_JSON_BYTES) throw IllegalStateException("Lottie JSON too large.")
            return connection.inputStream.use(::readBounded)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > MAX_JSON_BYTES) throw IllegalStateException("Lottie JSON too large.")
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
