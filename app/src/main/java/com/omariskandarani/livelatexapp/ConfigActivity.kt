package com.omariskandarani.livelatexapp

import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ConfigActivity : AppCompatActivity() {

    private lateinit var seekFontSize: SeekBar
    private lateinit var labelFontSize: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        seekFontSize = findViewById(R.id.seekEditorFontSize)
        labelFontSize = findViewById(R.id.labelEditorFontSize)

        val currentSp = EditorPrefs.getEditorFontSizeSp(this)
        val progress = (currentSp - EditorPrefs.MIN_FONT_SIZE_SP).toInt().coerceIn(0, seekFontSize.max)
        seekFontSize.progress = progress
        updateLabel(progress)

        seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateLabel(progress)
                    val sizeSp = EditorPrefs.MIN_FONT_SIZE_SP + progress
                    EditorPrefs.setEditorFontSizeSp(this@ConfigActivity, sizeSp)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateLabel(progress: Int) {
        val sizeSp = EditorPrefs.MIN_FONT_SIZE_SP + progress
        labelFontSize.text = "${sizeSp.toInt()} sp"
    }
}
