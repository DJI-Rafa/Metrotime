package com.example.metrotime.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

class PinWidgetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mgr = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, MetroWidgetProvider::class.java)

        if (mgr.isRequestPinAppWidgetSupported) {
            val ok = mgr.requestPinAppWidget(provider, null, null)
            Toast.makeText(this, if (ok) "Pedido enviado" else "Launcher recusou o pedido", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Launcher sem suporte a pin", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
