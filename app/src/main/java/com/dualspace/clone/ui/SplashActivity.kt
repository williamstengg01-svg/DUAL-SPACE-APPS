package com.dualspace.clone.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.dualspace.clone.util.Prefs

/** Routes to the setup wizard on first run, otherwise straight to the clone grid. */
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (Prefs.setupCompleted) MainActivity::class.java else SetupWizardActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}
