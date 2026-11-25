package com.speedgod.module

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(TextView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1120"))
            setTextColor(Color.WHITE)
            setPadding(48, 96, 48, 48)
            text = "SpeedGod Ready"
        })
    }
}
