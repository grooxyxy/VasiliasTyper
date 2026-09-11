package com.vasiliastyper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Reserved for future: full-screen canvas workspace activity.
 * Currently all workspace is handled inside MainActivity.
 */
class WorkspaceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
