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
import android.bluetooth.BluetoothManager
import android.app.AlertDialog
import android.view.LayoutInflater
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import kotlinx.coroutines.Job

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

    private var scanDialog: AlertDialog? = null
    private var deviceAdapter: BleDeviceAdapter? = null
    private var selectedDeviceAddress: String? = null

    private var scanJob: Job? = null

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

//                val uuid = jsonObject.get("uuid").asString
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
                    Log.d("connect", "macAddress: $macAddress")
                    // if (ActivityCompat.checkSelfPermission(
                    //         this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT
                    //     ) != PackageManager.PERMISSION_GRANTED
                    // ) {
                    //     Log.d("connect", "requestPermissions")
                    //     ActivityCompat.requestPermissions(
                    //         this@MainActivity,
                    //         arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    //         REQUEST_PERMISSION_BLUETOOTH
                    //     )
                    //     function?.onCallBack(JsonObject().apply {
                    //         addProperty("error", 800)
                    //     }.toString())
                    //     return@launch
                    // } else {
                        Log.d("connect", "connect")
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
                    // }
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

    // 设备mac地址
//    val connectId = "C5:CD:4A:8D:D9:2D"
    // var connectId = "DE:E9:AA:40:11:EE"
    var connectId = ""
    // deviceId为空
    val deviceId = ""
    fun searchDevices(view: View) {
        val dataJson = JsonObject().apply {
            addProperty("connectId", connectId)
        }
        val json = JsonObject().apply {
            addProperty("name", "searchDevices")
            add("data", dataJson)
        }
        webview.callHandler("bridgeCommonCall", json.toString()) { value ->
            Log.d("searchDevices result", value)
        }
    }

    fun getFeatures(view: View) {
        val dataJson = JsonObject().apply {
            addProperty("connectId", connectId)
        }
        val json = JsonObject().apply {
            addProperty("name", "getFeatures")
            add("data", dataJson)
        }
        webview.callHandler("bridgeCommonCall", json.toString()) { value ->
            Log.d("getFeatures result", value)
        }
    }

    fun btcGetAddress(view: View) {
        val dataJson = JsonObject().apply {
            addProperty("connectId", connectId)
            addProperty("deviceId", deviceId)
            addProperty("path", "m/44'/0'/0'/0/0")
            addProperty("coin", "btc")
            addProperty("showOnOneKey", false)
        }

        val json = JsonObject().apply {
            addProperty("name", "btcGetAddress")
            add("data", dataJson)
        }

        webview.callHandler("bridgeCommonCall", json.toString()) { value ->
            Log.d("btcGetAddress result", value)
        }
    }

    fun evmGetAddress(view: View) {
        val dataJson = JsonObject().apply {
            addProperty("connectId", connectId)
            addProperty("deviceId", deviceId)
            addProperty("path", "m/44'/60'/0'/0/0")
            addProperty("chainId", 1)
            addProperty("showOnOneKey", true)
        }
        val json = JsonObject().apply {
            addProperty("name", "evmGetAddress")
            add("data", dataJson)
        }
        webview.callHandler("bridgeCommonCall", json.toString()) { value ->
            Log.d("evmGetAddress result", value)
        }
    }

    @SuppressLint("MissingPermission")
    fun getConnectedDevices(view: View) {
        Log.d("getConnectedDevices", "getConnectedDevices")
        // if (ActivityCompat.checkSelfPermission(
        //         this,
        //         Manifest.permission.BLUETOOTH_CONNECT
        //     ) != PackageManager.PERMISSION_GRANTED
        // ) {
        //     ActivityCompat.requestPermissions(
        //         this,
        //         arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
        //         REQUEST_PERMISSION_BLUETOOTH
        //     )
        //     return
        // }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        // 检查蓝牙是否开启
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }

        // 获取已配对设备
        val pairedDevices = bluetoothAdapter.bondedDevices
        
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "没有已配对的设备", Toast.LENGTH_SHORT).show()
            return
        }

        // 遍历并显示已配对设备
        pairedDevices.forEach { device ->
            Log.d("Paired Device", "Name: ${device.name}, MAC: ${device.address}")
            Toast.makeText(
                this,
                "配对设备: ${device.name}, MAC: ${device.address}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun checkFirmwareRelease(view: View) {
        val dataJson = JsonObject().apply {
            addProperty("connectId", connectId)
            addProperty("deviceId", deviceId)
        }
        val json = JsonObject().apply {
            addProperty("name", "checkFirmwareRelease")
            add("data", dataJson)
        }
        webview.callHandler("bridgeCommonCall", json.toString()) { value ->
            Log.d("checkFirmwareRelease result", value)
        }
    }

    fun checkBleFirmwareRelease(view: View) {
        val dataJson = JsonObject().apply {
            addProperty("connectId", connectId)
            addProperty("deviceId", deviceId)
        }
        val json = JsonObject().apply {
            addProperty("name", "checkBLEFirmwareRelease")
            add("data", dataJson)
        }
        webview.callHandler("bridgeCommonCall", json.toString()) { value ->
            Log.d("checkBleFirmwareRelease result", value)
        }
    }

    private fun checkBluetoothEnabled(): Boolean {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun checkBluetoothPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSION_BLUETOOTH
            )
            return false
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun showScanDialog(view: View) {
        // 取消之前的扫描任务
        scanJob?.cancel()
        
        // 检查蓝牙权限和状态
        if (!checkBluetoothPermissions() || !checkBluetoothEnabled()) {
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ble_devices, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.deviceList)
        val scanStatus = dialogView.findViewById<TextView>(R.id.scanStatus)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        deviceAdapter = BleDeviceAdapter { address ->
            selectedDeviceAddress = address
            connectId = address
            Toast.makeText(this, "Selected device: $address", Toast.LENGTH_SHORT).show()
            scanDialog?.dismiss()
            scanJob?.cancel()
            updateConnectionStatus()
            
            val dataJson = JsonObject().apply {
                addProperty("uuid", address)
            }
            webview.callHandler("connect", dataJson.toString()) { value ->
                Log.d("connect result", value)
            }
        }
        recyclerView.adapter = deviceAdapter

        scanDialog = AlertDialog.Builder(this)
            .setTitle("Scan BLE Devices")
            .setView(dialogView)
            .setNegativeButton("Cancel") { dialog, _ -> 
                scanJob?.cancel()
                dialog.dismiss() 
            }
            .create()

        scanDialog?.show()

        // Start scanning with timeout
        lifecycleScope.launch {
            scanJob = bleScanner.scan(
                filters = listOf(
                    BleScanFilter(
                        serviceUuid = FilteredServiceUuid(
                            ParcelUuid.fromString("00000001-0000-1000-8000-00805f9b34fb")
                        )
                    )
                ),
                settings = BleScannerSettings(
                    scanMode = BleScanMode.SCAN_MODE_LOW_LATENCY
                )
            ).map { aggregator.aggregateDevices(it) }
                .onEach { results ->
                    withContext(Dispatchers.Main) {
                        deviceAdapter?.updateDevices(results)
                        scanStatus.text = if (results.isEmpty()) {
                            "Scanning... No devices found"
                        } else {
                            "Found ${results.size} devices"
                        }
                    }
                }
                .launchIn(lifecycleScope)

            // 10秒后自动停止扫描
            delay(10000)
            scanJob?.cancel()
            withContext(Dispatchers.Main) {
                scanStatus.text = "Scan completed"
            }
        }
    }

    private fun updateConnectionStatus() {
        val statusText = findViewById<TextView>(R.id.connectionStatus)
        statusText.text = if (selectedDeviceAddress != null) {
            "Selected device: $selectedDeviceAddress"
        } else {
            "No device selected"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanJob?.cancel()
        scanDialog?.dismiss()
    }

    override fun onPause() {
        super.onPause()
        scanJob?.cancel()
    }
}