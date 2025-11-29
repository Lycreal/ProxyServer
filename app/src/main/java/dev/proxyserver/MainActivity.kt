package dev.proxyserver

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import java.time.ZonedDateTime


@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {
    val config: Config = Config(
        listenType = "port",
        listenPort = 9000,
        listenUnixSocket = "ProxyServer",
    )
    lateinit var service: Socks5Server

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolBar))
        initListenTypeSpinner()
        initPortNumberPicker()
        initSocketNameEditText()
        initStartButton()
        initStopButton()
    }

    private fun initService() {
        service = Socks5Server(config).apply {
            onStarted = {
                runOnUiThread {
                    findViewById<android.widget.Spinner>(R.id.listenTypeSpinner).isEnabled = false
                    findViewById<android.widget.NumberPicker>(R.id.portNumberPicker).isEnabled = false
                    findViewById<android.widget.EditText>(R.id.socketNameEditText).isEnabled = false
                    findViewById<android.widget.Button>(R.id.startButton).isEnabled = false
                    findViewById<android.widget.Button>(R.id.stopButton).isEnabled = true
                }
            }
            onStopped = {
                runOnUiThread {
                    findViewById<android.widget.Spinner>(R.id.listenTypeSpinner).isEnabled = true
                    findViewById<android.widget.NumberPicker>(R.id.portNumberPicker).isEnabled = true
                    findViewById<android.widget.EditText>(R.id.socketNameEditText).isEnabled = true
                    findViewById<android.widget.Button>(R.id.startButton).isEnabled = true
                    findViewById<android.widget.Button>(R.id.stopButton).isEnabled = false
                }
            }
            logger = { text ->
                runOnUiThread {
                    val timeString = ZonedDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    )
                    findViewById<TextView>(R.id.logTextView).let {
                        it.text = "[${timeString}] $text\n${it.text}".take(65535)
                    }
                }
            }
        }
    }

    private fun initPortNumberPicker() {
        val portNumberPicker = findViewById<android.widget.NumberPicker>(R.id.portNumberPicker)
        portNumberPicker.minValue = 1
        portNumberPicker.maxValue = 65535
        portNumberPicker.value = config.listenPort

        portNumberPicker.setOnValueChangedListener { _, _, newVal ->
            config.listenPort = newVal
        }
    }

    private fun initSocketNameEditText() {
        val socketNameEditText = findViewById<android.widget.EditText>(R.id.socketNameEditText)
        socketNameEditText.setText(config.listenUnixSocket)

        socketNameEditText.doOnTextChanged { s, _, _, _ ->
            config.listenUnixSocket = s.toString()
        }
    }

    private fun initStartButton() {
        val startButton = findViewById<android.widget.Button>(R.id.startButton)
        val logTextView = findViewById<TextView>(R.id.logTextView)

        startButton.setOnClickListener {
            try {
                if (!::service.isInitialized) {
                    initService()
                }
                service.start()
            } catch (e: Exception) {
                logTextView.text = "[ERROR] ${e.message}\n${logTextView.text}".take(65535)
                return@setOnClickListener
            }
        }
    }

    private fun initStopButton() {
        val stopButton = findViewById<android.widget.Button>(R.id.stopButton)

        stopButton.setOnClickListener {
            service.stop()
        }
    }

    private fun initListenTypeSpinner() {
        val listenTypeSpinner = findViewById<android.widget.Spinner>(R.id.listenTypeSpinner)
        val portNumberPicker = findViewById<android.widget.NumberPicker>(R.id.portNumberPicker)
        val socketNameEditText = findViewById<android.widget.EditText>(R.id.socketNameEditText)
        val choices = arrayOf("port", "unix socket")
        listenTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, choices).apply {
            this.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        listenTypeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                config.listenType = choices[position]
                if (choices[position] == "port") {
                    portNumberPicker.visibility = android.view.View.VISIBLE
                    socketNameEditText.visibility = android.view.View.GONE
                } else {
                    portNumberPicker.visibility = android.view.View.GONE
                    socketNameEditText.visibility = android.view.View.VISIBLE
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

    }
}