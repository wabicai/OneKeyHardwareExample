package com.onekey.hardware.hardwareexample

import android.Manifest
import android.annotation.SuppressLint

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smallbuer.jsbridge.core.BridgeHandler
import com.smallbuer.jsbridge.core.BridgeWebView
import com.smallbuer.jsbridge.core.CallBackFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nordicsemi.android.common.core.DataByteArray
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattCharacteristic
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanFilter
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerSettings
import no.nordicsemi.android.kotlin.ble.core.scanner.FilteredServiceUuid
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner
import no.nordicsemi.android.kotlin.ble.scanner.aggregator.BleScanResultAggregator
import java.util.UUID

data class OneKeyDeviceInfo(
    val id: String, val name: String
)

class MainActivity : AppCompatActivity() {
    companion object {
        const val REQUEST_PERMISSION_BLUETOOTH = 1
    }

    lateinit var webview: BridgeWebView

    private val aggregator = BleScanResultAggregator()
    private val bleScanner by lazy { BleScanner(this) }

    private var connection: ClientBleGatt? = null
    private var writeCharacteristic: ClientBleGattCharacteristic? = null
    private var notifyCharacteristic: ClientBleGattCharacteristic? = null

    // Demo
    @RequiresApi(Build.VERSION_CODES.S)
    fun searchBleDevice() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
                ), REQUEST_PERMISSION_BLUETOOTH
            )
            return
        }
        bleScanner.scan(
            filters = listOf(
                BleScanFilter(
                    serviceUuid = FilteredServiceUuid(
                        ParcelUuid.fromString(
                            "00000001-0000-1000-8000-00805f9b34fb"
                        )
                    )
                )
            ), settings = BleScannerSettings(
                scanMode = BleScanMode.SCAN_MODE_LOW_LATENCY
            )
        ).map { aggregator.aggregateDevices(it) } //Add new device and return an aggregated list
            .launchIn(lifecycleScope)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webview = findViewById(R.id.webview)
        configureWebView()
        loadHtmlFile()
        registerHandlers()
    }

    fun registerHandlers() {
        webview.addHandlerLocal("enumerate", object : BridgeHandler() {
            @RequiresApi(Build.VERSION_CODES.S)
            override fun handler(context: Context?, data: String?, function: CallBackFunction?) {
                searchBleDevice()
                lifecycleScope.launch(Dispatchers.Default) {
                    delay(3 * 1000)
                    val deviceList = aggregator.results.map {
                        Log.d("===== service Devices: ", Gson().toJson(it))
                        OneKeyDeviceInfo(
                            id = it.device.address,
                            name = it.device.name ?: "",
                        )
                    }
                    withContext(Dispatchers.Main) {
                        function?.onCallBack(Gson().toJson(deviceList))
                    }
                }
            }
        })

        webview.addHandlerLocal("send", object : BridgeHandler() {
            @SuppressLint("MissingPermission")
            override fun handler(context: Context?, data: String?, function: CallBackFunction?) {
                val jsonObject = JsonParser.parseString(data).asJsonObject

                val uuid = jsonObject.get("uuid").asString
                val data = jsonObject.get("data").asString

                lifecycleScope.launch(Dispatchers.Default) {
                    Log.d("addHandlerLocal send", data)
                    writeCharacteristic?.write(DataByteArray(HexUtil.fromHex(data)))
                    function?.onCallBack("")
                }
            }
        })

        webview.addHandlerLocal("connect", object : BridgeHandler() {
            override fun handler(context: Context?, data: String?, function: CallBackFunction?) {
                lifecycleScope.launch(Dispatchers.Main) {
                    val macAddress = JsonParser.parseString(data).asJsonObject.get("uuid").asString
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                            REQUEST_PERMISSION_BLUETOOTH
                        )
                        function?.onCallBack(JsonObject().apply {
                            addProperty("error", 800)
                        }.toString())
                        return@launch
                    } else {
                        if (connection?.isConnected == true) connection?.discoverServices()
                        connection = ClientBleGatt.connect(this@MainActivity, macAddress, this)
                        val services = connection?.discoverServices()
                        val service =
                            services?.findService(UUID.fromString("00000001-0000-1000-8000-00805f9b34fb"))

                        writeCharacteristic =
                            service?.findCharacteristic(UUID.fromString("00000002-0000-1000-8000-00805f9b34fb"))
                        notifyCharacteristic =
                            service?.findCharacteristic(UUID.fromString("00000003-0000-1000-8000-00805f9b34fb"))
                        notifyCharacteristic?.getNotifications()?.onEach {
                            Log.d("read notifyCharacteristic", HexUtil.toHex(it.value))
                            withContext(Dispatchers.Main) {
                                webview.callHandler(
                                    "monitorCharacteristic",
                                    HexUtil.toHex(it.value)
                                ) { value ->
                                    Log.d("monitorCharacteristic result", value)
                                }
                            }
                        }?.launchIn(lifecycleScope)
                        function?.onCallBack("")
                    }
                }
                Toast.makeText(this@MainActivity, "connect:$data", Toast.LENGTH_SHORT).show()
            }
        })

        webview.addHandlerLocal("disconnect", object : BridgeHandler() {
            override fun handler(context: Context?, data: String?, function: CallBackFunction?) {
                connection?.disconnect()
                Toast.makeText(this@MainActivity, "disconnect:$data", Toast.LENGTH_SHORT).show()
            }
        })
    }


    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webview.settings.javaScriptEnabled = true  // 启用 JavaScript
        webview.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebView", consoleMessage?.message() ?: "null")
                return true
            }
        }
    }

    private fun loadHtmlFile() {
        webview.loadUrl("file:///android_asset/index.html")
    }

    val connectId = "EA:21:88:12:75:86"
    val deviceId = ""
    fun searchDevices(view: View) {
        webview.callHandler("searchDevice", "") { value ->
            Log.d("searchDevices result", value)
        }
    }

    fun getFeatures(view: View) {
        val json = JsonObject().apply {
            addProperty("connectId", connectId)
        }
        webview.callHandler("getFeatures", json.toString()) { value ->
            Log.d("getFeatures result", value)
        }
    }

    fun btcGetAddress(view: View) {
        val json = JsonObject().apply {
            addProperty("connectId", connectId)
            addProperty("deviceId", deviceId)
            addProperty("path", "m/44'/0'/0'/0/0")
            addProperty("coin", "btc")
            addProperty("showOnOneKey", false)
        }
        webview.callHandler("btcGetAddress", json.toString()) { value ->
            Log.d("btcGetAddress result", value)
        }
    }
}