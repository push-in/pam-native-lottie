package dev.pam.lottie

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.RenderMode
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.views.NativeViewFactory

/** Registers `lottie.view`. */
class LottieViewFactory(context: Context) : NativeViewFactory {
    init {
        registerMemoryTrim(context.applicationContext)
    }

    override fun create(context: Context, emit: (ByteArray) -> Unit): View = PamLottieView(context, emit)
    override fun update(view: View, properties: Map<String, WireValue>) = (view as PamLottieView).update(LottieProps.parse(properties))
    override fun release(view: View) = (view as PamLottieView).release()

    private companion object {
        @Volatile var trimRegistered = false

        fun registerMemoryTrim(context: Context) {
            if (trimRegistered) return
            synchronized(this) {
                if (trimRegistered) return
                trimRegistered = true
                context.registerComponentCallbacks(object : ComponentCallbacks2 {
                    @Suppress("DEPRECATION")
                    override fun onTrimMemory(level: Int) {
                        when {
                            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> LottieLoader.trim(0f)
                            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> LottieLoader.trim(0.5f)
                        }
                    }
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                    @Deprecated("Deprecated in Java") override fun onLowMemory() = LottieLoader.trim(0f)
                })
            }
        }
    }
}

/**
 * One Lottie animation (`lottie-react-native` LottieView equivalent).
 *
 * Frames run only while the view is attached, aggregated-visible and
 * `playing`; anything else pauses on the current frame (resume continues
 * from there, like RN `ref.resume()`). With animator scale 0 (reduced
 * motion) the first frame is shown. A fallback label covers the view until
 * the composition is ready and when it fails.
 */
@SuppressLint("ViewConstructor") // built only by LottieViewFactory, never inflated
internal class PamLottieView(context: Context, private val emit: (ByteArray) -> Unit) : FrameLayout(context) {
    private val animation = LottieAnimationView(context).apply {
        setRenderMode(RenderMode.HARDWARE)
        // The view decides reduced motion itself (first frame, never the last);
        // the replacement (LottieConfig.setReducedMotionOption) is process-wide.
        @Suppress("DEPRECATION")
        setIgnoreDisabledSystemAnimations(true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }
    private val fallback = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    private var props: LottieProps? = null
    private var composition: LottieComposition? = null
    private var loadedKey: String? = null
    private var generation = 0
    private var started = false
    private var finished = false
    private var failed = false
    private var visibleToUser = true

    init {
        addView(animation, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fallback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        animation.addAnimatorListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationStart(animator: Animator) { cancelled = false }
            override fun onAnimationCancel(animator: Animator) { cancelled = true }
            override fun onAnimationEnd(animator: Animator) {
                val current = props ?: return
                if (cancelled || current.loop || composition == null) return
                finished = true
                if (current.events) send(LottieIdl.EVENT_FINISHED)
            }
        })
    }

    fun update(next: LottieProps) {
        val previous = props
        props = next
        if (previous != null && !previous.playing && next.playing && finished) {
            // play again after an explicit pause/play toggle on a finished one-shot
            finished = false
            started = false
        }
        animation.repeatCount = if (next.loop) LottieDrawable.INFINITE else 0
        animation.speed = next.speed
        animation.scaleType = when (next.resize) {
            LottieIdl.RESIZE_COVER -> ImageView.ScaleType.CENTER_CROP
            LottieIdl.RESIZE_CENTER -> ImageView.ScaleType.CENTER_INSIDE
            else -> ImageView.ScaleType.FIT_CENTER
        }
        fallback.text = next.fallback
        next.fallbackColor?.let(fallback::setTextColor)
        next.fallbackSize?.let { fallback.setTextSize(TypedValue.COMPLEX_UNIT_DIP, it) } ?: run {
            fallback.setTextSize(TypedValue.COMPLEX_UNIT_PX, (minOf(width, height).takeIf { it > 0 } ?: 48) * 0.6f)
        }
        if (next.key != loadedKey) load(next) else applyPlayback()
        updateFallback()
    }

    private fun load(next: LottieProps) {
        val token = ++generation
        loadedKey = next.key
        composition = null
        started = false
        finished = false
        failed = false
        animation.cancelAnimation()
        animation.visibility = INVISIBLE
        if (!next.hasSource) {
            failed = true
            return
        }
        LottieLoader.load(context, next) { result ->
            if (token != generation) return@load
            when (result) {
                is LottieLoad.Ready -> {
                    composition = result.composition
                    animation.setComposition(result.composition)
                    animation.visibility = VISIBLE
                    if (props?.events == true) send(LottieIdl.EVENT_LOADED, "durationMs" to WireValue.Decimal(result.composition.duration.toDouble()))
                    applyPlayback()
                }
                is LottieLoad.Failed -> {
                    failed = true
                    if (props?.events == true) send(LottieIdl.EVENT_FAILED, "message" to WireValue.Text(result.message))
                }
            }
            updateFallback()
        }
    }

    private fun applyPlayback() {
        val current = props ?: return
        if (composition == null) return
        when (lottiePlayback(current.playing, current.progress, isAttachedToWindow, visibleToUser, ValueAnimator.areAnimatorsEnabled(), finished)) {
            LottiePlayback.PIN -> {
                if (animation.isAnimating) animation.pauseAnimation()
                animation.progress = current.progress ?: 0f
            }
            LottiePlayback.FIRST_FRAME -> {
                if (animation.isAnimating) animation.pauseAnimation()
                if (!started) animation.progress = if (current.speed < 0f) 1f else 0f
            }
            LottiePlayback.RUN -> if (!animation.isAnimating) {
                if (started) animation.resumeAnimation() else animation.playAnimation()
                started = true
            }
            LottiePlayback.PAUSE -> if (animation.isAnimating) animation.pauseAnimation()
        }
    }

    private fun updateFallback() {
        val show = (composition == null || failed) && fallback.text.isNotEmpty()
        fallback.visibility = if (show) VISIBLE else GONE
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (visibleToUser == isVisible) return
        visibleToUser = isVisible
        applyPlayback()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyPlayback()
    }

    override fun onDetachedFromWindow() {
        if (animation.isAnimating) animation.pauseAnimation()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (props?.fallbackSize == null && w > 0 && h > 0) fallback.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(w, h) * 0.6f)
    }

    fun release() {
        generation++
        animation.cancelAnimation()
        (animation.drawable as? LottieDrawable)?.clearComposition()
        composition = null
        loadedKey = null
        props = null
        started = false
        finished = false
    }

    private fun send(event: Int, vararg extra: Pair<String, WireValue>) {
        runCatching { emit(WireMap.encode(mapOf("event" to WireValue.Integer(event.toLong()), *extra))) }
    }
}
