package com.example.timer

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var preview: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences("timer", Context.MODE_PRIVATE)

        bindSwitch(R.id.swSound, "sound", true)
        bindSwitch(R.id.swMedia, "media_volume", true)
        bindSwitch(R.id.swVibrate, "vibrate", true)
        bindSwitch(R.id.swKeepOn, "keep_on", false)
        bindInt(R.id.etSec, "sound_sec", 10)
        bindInt(R.id.etCount, "repeat_count", 1)
        bindFloat(R.id.etGap, "repeat_gap_min", 1f)
        bindFloat(R.id.etStrike, "strike_gap", 3f)

        val rg = findViewById<RadioGroup>(R.id.rgSound)
        Synth.NAMES.forEachIndexed { i, name ->
            val rb = RadioButton(this)
            rb.id = 1000 + i
            rb.text = name
            rb.textSize = 18f
            rg.addView(rb)
        }
        rg.check(1000 + prefs.getInt("sound_idx", 0).coerceIn(0, Synth.NAMES.size - 1))
        rg.setOnCheckedChangeListener { _, id -> prefs.edit().putInt("sound_idx", id - 1000).apply() }

        val tvVol = findViewById<TextView>(R.id.tvVolume)
        val sb = findViewById<SeekBar>(R.id.sbVolume)
        sb.max = 90
        val v0 = prefs.getInt("volume", 70).coerceIn(10, 100)
        sb.progress = v0 - 10
        tvVol.text = "Громкость: $v0%"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt("volume", p + 10).apply()
                tvVol.text = "Громкость: ${p + 10}%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            preview?.interrupt()
            val idx = prefs.getInt("sound_idx", 0)
            val sec = minOf(prefs.getInt("sound_sec", 10).coerceIn(1, 60), 8)
            val vol = prefs.getInt("volume", 70).coerceIn(10, 100) / 100f
            val strike = prefs.getFloat("strike_gap", 3f).coerceIn(0.5f, 30f).toDouble()
            val media = prefs.getBoolean("media_volume", true)
            preview = Thread {
                try { Synth.play(idx, sec, vol, strike, media) } catch (_: InterruptedException) {}
            }.also { it.start() }
        }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onPause() {
        super.onPause()
        preview?.interrupt()
    }

    private fun bindSwitch(id: Int, key: String, def: Boolean) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply() }
    }

    private fun bindFloat(id: Int, key: String, def: Float) {
        val et = findViewById<EditText>(id)
        val v0 = prefs.getFloat(key, def)
        et.setText(if (v0 % 1f == 0f) v0.toInt().toString() else v0.toString())
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.replace(',', '.')?.toFloatOrNull()
                if (v != null) prefs.edit().putFloat(key, v).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun bindInt(id: Int, key: String, def: Int) {
        val et = findViewById<EditText>(id)
        et.setText(prefs.getInt(key, def).toString())
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull()
                if (v != null) prefs.edit().putInt(key, v).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }
}
