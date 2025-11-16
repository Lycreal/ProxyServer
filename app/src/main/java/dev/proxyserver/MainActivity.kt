package dev.proxyserver

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity


class MainActivity : ComponentActivity() {

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val logTextView = findViewById<TextView>(R.id.logTextView)
        val service = Socks5Service(
            Config(
                listenType = "port",
                listenPort = 9000,
                listenUnixSocket = "ProxyServer",
                allowIPv6 = true
            )
        ) {
            runOnUiThread {
                logTextView.text = "$it\n${logTextView.text}".take(65535)
            }
        }
        service.start()
    }
}