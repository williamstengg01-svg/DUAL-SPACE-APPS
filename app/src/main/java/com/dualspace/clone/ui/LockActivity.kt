package com.dualspace.clone.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.dualspace.clone.R
import com.dualspace.clone.databinding.ActivityLockBinding
import com.dualspace.clone.util.Prefs

/**
 * PIN / biometric gate. Two modes:
 *  - verify (default): unlock the host app or a locked clone.
 *  - set (EXTRA_SET_PIN): create / change the PIN (asks twice to confirm).
 */
class LockActivity : AppCompatActivity() {

    private lateinit var b: ActivityLockBinding
    private val entered = StringBuilder()
    private var setMode = false
    private var firstEntry: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLockBinding.inflate(layoutInflater)
        setContentView(b.root)
        setMode = intent.getBooleanExtra(EXTRA_SET_PIN, false)

        b.title.setText(if (setMode) R.string.lock_set_pin else R.string.lock_enter_pin)

        val keys = listOf(b.k1, b.k2, b.k3, b.k4, b.k5, b.k6, b.k7, b.k8, b.k9, b.k0)
        keys.forEach { key -> key.setOnClickListener { onDigit((it as com.google.android.material.button.MaterialButton).text.toString()) } }
        b.kBack.setOnClickListener { if (entered.isNotEmpty()) { entered.setLength(entered.length - 1); render() } }
        b.kBio.setOnClickListener { showBiometric() }
        b.kOk.setOnClickListener { if (entered.length >= 4) submit() }

        val canBio = !setMode && Prefs.biometricEnabled && BiometricManager.from(this)
            .canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        b.kBio.visibility = if (canBio) View.VISIBLE else View.INVISIBLE
        if (canBio) showBiometric()
        render()
    }

    private fun onDigit(d: String) {
        if (entered.length >= 6) return
        entered.append(d)
        render()
        // Accept 4–6 digits; auto-submit at 6, otherwise the user taps OK.
        if (entered.length == 6) submit()
    }

    private fun submit() {
        val pin = entered.toString()
        if (setMode) {
            if (firstEntry == null) {
                firstEntry = pin
                b.title.setText(R.string.lock_confirm_pin)
                entered.setLength(0); render()
            } else if (firstEntry == pin) {
                Prefs.setPin(pin)
                Prefs.lockEnabled = true
                setResult(Activity.RESULT_OK); finish()
            } else {
                firstEntry = null
                b.title.setText(R.string.lock_set_pin)
                fail()
            }
        } else {
            if (Prefs.checkPin(pin)) {
                LockGate.markUnlocked()
                setResult(Activity.RESULT_OK); finish()
            } else fail()
        }
    }

    private fun fail() {
        entered.setLength(0)
        render()
        b.error.visibility = View.VISIBLE
        b.dots.animate().translationX(16f).setDuration(40).withEndAction {
            b.dots.animate().translationX(-16f).setDuration(40).withEndAction {
                b.dots.animate().translationX(0f).setDuration(40)
            }
        }
    }

    private fun render() {
        b.error.visibility = View.INVISIBLE
        val dots = listOf(b.d1, b.d2, b.d3, b.d4, b.d5, b.d6)
        dots.forEachIndexed { i, v -> v.isSelected = i < entered.length }
        b.kOk.isEnabled = entered.length >= 4
    }

    private fun showBiometric() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    LockGate.markUnlocked()
                    setResult(Activity.RESULT_OK); finish()
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_name))
            .setSubtitle(getString(R.string.lock_biometric_subtitle))
            .setNegativeButtonText(getString(R.string.lock_use_pin))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    override fun onBackPressed() {
        setResult(Activity.RESULT_CANCELED)
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_SET_PIN = "set_pin"
    }
}
