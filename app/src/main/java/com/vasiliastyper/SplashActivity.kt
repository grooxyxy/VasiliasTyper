package com.vasiliastyper

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import kotlin.math.cos
import kotlin.math.sin

class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val runningAnimators = mutableListOf<Animator>()
    private var homeLaunched = false

    private val launcherRunnable = Runnable { launchHome() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        findViewById<View>(R.id.splashRoot).setOnClickListener { launchHome() }
        startSplashAnimation()
    }

    private fun launchHome() {
        if (homeLaunched || isFinishing || isDestroyed) return
        homeLaunched = true
        handler.removeCallbacksAndMessages(null)
        val animation = ActivityOptionsCompat.makeCustomAnimation(
            this,
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
        startActivity(Intent(this, HomeActivity::class.java), animation.toBundle())
        finish()
    }

    private fun startSplashAnimation() {
        runningAnimators.forEach(Animator::cancel)
        runningAnimators.clear()

        val icon = findViewById<ImageView>(R.id.splashIcon)
        val ring = findViewById<View>(R.id.splashRing)
        val halo = findViewById<View>(R.id.splashHalo)
        val eyebrow = findViewById<TextView>(R.id.splashEyebrow)
        val title = findViewById<TextView>(R.id.splashTitle)
        val subtitle = findViewById<TextView>(R.id.splashSubtitle)
        val progress = findViewById<View>(R.id.splashProgress)
        val status = findViewById<TextView>(R.id.splashStatus)
        val skipHint = findViewById<TextView>(R.id.splashSkipHint)
        val orbitDots = listOf<View>(
            findViewById(R.id.orbitDot1),
            findViewById(R.id.orbitDot2),
            findViewById(R.id.orbitDot3)
        )

        if (!ValueAnimator.areAnimatorsEnabled()) {
            listOf(icon, ring, halo, eyebrow, title, subtitle, status, skipHint).forEach { it.alpha = 1f }
            progress.scaleX = 1f
            handler.postDelayed(launcherRunnable, 500L)
            return
        }

        val iconEntrance = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.58f, 1f),
                ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.58f, 1f),
                ObjectAnimator.ofFloat(icon, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(icon, View.ROTATION, -12f, 0f),
                ObjectAnimator.ofFloat(icon, View.TRANSLATION_Y, 28f, 0f)
            )
            duration = 760L
            interpolator = OvershootInterpolator(1.05f)
        }
        val frameEntrance = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.48f, 1f),
                ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.48f, 1f),
                ObjectAnimator.ofFloat(ring, View.ALPHA, 0f, 0.92f),
                ObjectAnimator.ofFloat(ring, View.ROTATION, -55f, 0f),
                ObjectAnimator.ofFloat(halo, View.ALPHA, 0f, 0.78f),
                ObjectAnimator.ofFloat(halo, View.SCALE_X, 0.72f, 1f),
                ObjectAnimator.ofFloat(halo, View.SCALE_Y, 0.72f, 1f)
            )
            duration = 900L
            interpolator = DecelerateInterpolator(1.8f)
        }
        val copyEntrance = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(eyebrow, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(title, View.TRANSLATION_Y, title.translationY, 0f),
                ObjectAnimator.ofFloat(subtitle, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(subtitle, View.TRANSLATION_Y, subtitle.translationY, 0f),
                ObjectAnimator.ofFloat(status, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(skipHint, View.ALPHA, 0f, 1f)
            )
            startDelay = 230L
            duration = 650L
            interpolator = DecelerateInterpolator()
        }
        val progressAnimator = ObjectAnimator.ofFloat(progress, View.SCALE_X, 0f, 1f).apply {
            startDelay = 260L
            duration = 1550L
            interpolator = AccelerateDecelerateInterpolator()
        }
        val ringRotation = ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
            startDelay = 760L
            duration = 6800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        val iconFloat = ObjectAnimator.ofFloat(icon, View.TRANSLATION_Y, 0f, -8f, 0f).apply {
            startDelay = 850L
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val haloPulse = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(halo, View.SCALE_X, 0.96f, 1.1f),
                ObjectAnimator.ofFloat(halo, View.SCALE_Y, 0.96f, 1.1f),
                ObjectAnimator.ofFloat(halo, View.ALPHA, 0.42f, 0.76f)
            )
            startDelay = 700L
            duration = 1100L
            interpolator = AccelerateDecelerateInterpolator()
            childAnimations.forEach {
                (it as? ValueAnimator)?.apply {
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                }
            }
        }

        val density = resources.displayMetrics.density
        val orbitRadius = 91f * density
        orbitDots.forEachIndexed { index, dot ->
            val orbit = ValueAnimator.ofFloat(0f, 360f).apply {
                startDelay = 460L + index * 100L
                duration = 3000L + index * 650L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    val angle = Math.toRadians(
                        (animator.animatedValue as Float + index * 120f).toDouble()
                    )
                    dot.translationX = (cos(angle) * orbitRadius).toFloat()
                    dot.translationY = (sin(angle) * orbitRadius).toFloat()
                    dot.alpha = 0.45f + 0.5f * ((sin(angle) + 1.0) / 2.0).toFloat()
                    val scale = 0.75f + 0.35f * ((cos(angle) + 1.0) / 2.0).toFloat()
                    dot.scaleX = scale
                    dot.scaleY = scale
                }
            }
            runningAnimators += orbit
        }

        runningAnimators += listOf(
            iconEntrance,
            frameEntrance,
            copyEntrance,
            progressAnimator,
            ringRotation,
            iconFloat,
            haloPulse
        )
        runningAnimators.forEach(Animator::start)

        postStatus(status, 520L, "Memuat mesin tipografi…")
        postStatus(status, 1120L, "Menyusun layer kreatif…")
        postStatus(status, 1640L, "Studio siap")
    }

    private fun postStatus(view: TextView, delay: Long, text: String) {
        handler.postDelayed({
            if (homeLaunched || isFinishing || isDestroyed) return@postDelayed
            view.animate()
                .alpha(0f)
                .translationY(5f)
                .setDuration(110L)
                .withEndAction {
                    view.text = text
                    view.translationY = -5f
                    view.animate().alpha(1f).translationY(0f).setDuration(180L).start()
                }
                .start()
        }, delay)
    }

    override fun onResume() {
        super.onResume()
        if (!homeLaunched) handler.postDelayed(launcherRunnable, 2150L)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runningAnimators.forEach(Animator::cancel)
        runningAnimators.clear()
        super.onDestroy()
    }
}
