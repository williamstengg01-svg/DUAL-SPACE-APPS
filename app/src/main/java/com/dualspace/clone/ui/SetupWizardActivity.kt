package com.dualspace.clone.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dualspace.clone.R
import com.dualspace.clone.databinding.ActivitySetupBinding
import com.dualspace.clone.databinding.ItemSetupStepBinding
import com.dualspace.clone.util.BrandCompat
import com.dualspace.clone.util.Prefs

/**
 * One-time, brand-aware guided setup. Each row deep-links into the exact OEM settings
 * page (autostart, battery, background pop-up, notifications). The user ticks a step
 * after granting it; "battery" is verified automatically.
 */
class SetupWizardActivity : AppCompatActivity() {

    private lateinit var b: ActivitySetupBinding
    private lateinit var steps: List<BrandCompat.Step>
    private lateinit var adapter: StepAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        val brand = BrandCompat.detect()
        b.brand.text = getString(R.string.setup_detected, getString(brand.label), android.os.Build.MODEL)
        steps = BrandCompat.steps(this)
        adapter = StepAdapter(steps)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        val leave = {
            Prefs.setupCompleted = true
            if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        b.btnDone.setOnClickListener { leave() }
        b.btnSkip.setOnClickListener { leave() }
    }

    override fun onResume() {
        super.onResume()
        // Auto-verify the one step Android lets us query.
        if (BrandCompat.isIgnoringBatteryOptimizations(this)) Prefs.setStepDone("battery", true)
        if (BrandCompat.hasStorageAccess(this)) Prefs.setStepDone("storage", true)
        adapter.notifyDataSetChanged()
        updateDone()
    }

    private fun updateDone() {
        val requiredDone = steps.filter { it.required }.all { Prefs.isStepDone(it.key) }
        b.btnDone.isEnabled = requiredDone
    }

    private inner class StepAdapter(val items: List<BrandCompat.Step>) : RecyclerView.Adapter<StepAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemSetupStepBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position + 1)

        inner class VH(val b: ItemSetupStepBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(step: BrandCompat.Step, n: Int) {
                b.number.text = n.toString()
                b.title.setText(step.title)
                b.desc.setText(step.description)
                b.optional.visibility = if (step.required) View.GONE else View.VISIBLE
                b.check.setOnCheckedChangeListener(null)
                b.check.isChecked = Prefs.isStepDone(step.key)
                b.check.setOnCheckedChangeListener { _, on -> Prefs.setStepDone(step.key, on); updateDone() }
                b.btnOpen.setOnClickListener {
                    if (step.key == "storage" && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        ActivityCompat.requestPermissions(this@SetupWizardActivity,
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                    } else BrandCompat.open(b.root.context, step)
                }
            }
        }
    }
}
