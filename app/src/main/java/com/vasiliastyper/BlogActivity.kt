package com.vasiliastyper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

/** In-app, offline changelog and tutorial reader. */
class BlogActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SLUG = "article_slug"

        fun intent(context: Context, slug: String? = null): Intent =
            Intent(context, BlogActivity::class.java).apply {
                slug?.let { putExtra(EXTRA_SLUG, it) }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blog)

        findViewById<ImageButton>(R.id.btnBlogBack).setOnClickListener { finish() }
        val container = findViewById<LinearLayout>(R.id.blogContentContainer)
        val article = BlogContent.find(intent.getStringExtra(EXTRA_SLUG))
        if (article == null) renderIndex(container) else renderArticle(container, article)
    }

    private fun renderIndex(container: LinearLayout) {
        findViewById<TextView>(R.id.tvBlogToolbarTitle).text = "Pusat Belajar"
        container.addView(textView("BELAJAR DI VASILIASTYPER", 10f, R.color.ink_violet, bold = true))
        container.addView(textView("Changelog & tutorial", 28f, R.color.ps_text_bright, bold = true).withTop(6))
        container.addView(
            textView(
                "Panduan ringkas yang tersimpan offline. Pilih artikel untuk membuka halaman lengkap.",
                13f,
                R.color.ink_muted
            ).withTop(8)
        )

        BlogContent.articles.forEachIndexed { index, article ->
            container.addView(articleCard(article).apply {
                if (index == 0) setTopMargin(22) else setTopMargin(12)
                setOnClickListener { startActivity(intent(this@BlogActivity, article.slug)) }
            })
        }
    }

    private fun renderArticle(container: LinearLayout, article: BlogContent.Article) {
        findViewById<TextView>(R.id.tvBlogToolbarTitle).text = "Artikel"
        container.addView(textView(article.category, 10f, R.color.ink_violet, bold = true))
        container.addView(textView(article.title, 27f, R.color.ps_text_bright, bold = true).withTop(8))
        container.addView(textView("${article.readTime} baca · tersedia offline", 11f, R.color.ink_muted).withTop(8))
        container.addView(textView(article.summary, 15f, R.color.ps_text).withTop(18))

        article.sections.forEachIndexed { index, section ->
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = getColor(R.color.ps_border)
                setCardBackgroundColor(getColor(R.color.vt_surface))
                setContentPadding(dp(16), dp(16), dp(16), dp(16))
                setTopMargin(if (index == 0) 24 else 12)
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(textView(section.heading, 16f, R.color.ps_text_bright, bold = true))
                addView(textView(section.body, 13f, R.color.ps_text).withTop(8).apply {
                    setLineSpacing(dp(3).toFloat(), 1f)
                })
            }
            card.addView(body)
            container.addView(card)
        }

        container.addView(
            TextView(this).apply {
                text = "Lihat semua artikel"
                textSize = 13f
                setTextColor(getColor(R.color.white))
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.action_card_primary)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    startActivity(intent(this@BlogActivity).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(50)
                ).apply { topMargin = dp(20) }
            }
        )
    }

    private fun articleCard(article: BlogContent.Article): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = getColor(R.color.ps_border)
            setCardBackgroundColor(getColor(R.color.vt_surface))
            setContentPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView(article.category, 9f, R.color.ink_violet, bold = true))
            addView(textView(article.title, 18f, R.color.ps_text_bright, bold = true).withTop(7))
            addView(textView(article.summary, 12f, R.color.ps_text).withTop(7))
            addView(textView("${article.readTime} baca  ›", 11f, R.color.vt_secondary, bold = true).withTop(12))
        }
        card.addView(body)
        return card
    }

    private fun textView(text: String, size: Float, colorRes: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(getColor(colorRes))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun <T : View> T.withTop(value: Int): T = apply { setTopMargin(value) }

    private fun View.setTopMargin(value: Int) {
        val current = layoutParams as? LinearLayout.LayoutParams
        layoutParams = (current ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )).apply { topMargin = dp(value) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
