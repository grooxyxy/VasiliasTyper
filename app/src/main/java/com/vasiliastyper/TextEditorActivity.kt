package com.vasiliastyper

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Reserved for full standalone text editor screen.
 */
class TextEditorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.R.layout.simple_list_item_1)
        findViewById<TextView>(android.R.id.text1).text = "Text editor belum diaktifkan."
    }
}
