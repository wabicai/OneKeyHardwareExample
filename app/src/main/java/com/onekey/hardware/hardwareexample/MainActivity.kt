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
import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import android.widget.Button
import android.view.View.OnClickListener
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.text.InputType
import android.widget.LinearLayout

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

    private val PREF_NAME = "BlePreferences"
    private val LAST_DEVICE_ADDRESS = "last_device_address"
    
    // 设备类型常量
    private val DEVICE_TYPE_CLASSIC = "classic"
    private val DEVICE_TYPE_TOUCH = "touch"
    private val DEVICE_TYPE_PRO = "pro"
    private val DEVICE_TYPE_UNKNOWN = "unknown"
    
    // 当前设备类型
    private var currentDeviceType = DEVICE_TYPE_UNKNOWN

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
        
        // 恢复上次连接的设备地址
        val sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        selectedDeviceAddress = sharedPreferences.getString(LAST_DEVICE_ADDRESS, null)
        if (selectedDeviceAddress != null) {
            updateConnectionStatus()
        }
        
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

        // Add PIN input handler
        webview.addHandlerLocal("requestPinInput", object : BridgeHandler() {
            override fun handler(context: Context?, data: String?, function: CallBackFunction?) {
                Log.d("PIN Input", "Showing PIN input dialog, device type: $currentDeviceType")
                
                // 如果设备类型不是Classic1S，则直接返回空字符串，使用设备输入
                if (currentDeviceType != DEVICE_TYPE_CLASSIC){
                    Log.d("PIN Input", "Device is not Classic1S, using device PIN input")
                    function?.onCallBack("")
                    return
                }
                
                // Create a custom PIN input dialog
                val builder = AlertDialog.Builder(this@MainActivity)
                builder.setTitle("PIN Input")
                
                // Define keyboardMap as in the React code
                val keyboardMap = arrayOf("7", "8", "9", "4", "5", "6", "1", "2", "3")
                
                // Inflate the custom layout
                val inflater = LayoutInflater.from(this@MainActivity)
                val view = inflater.inflate(R.layout.dialog_pin_input, null)
                
                // Get button and display references
                val pinDisplay = view.findViewById<TextView>(R.id.pinDisplay)
                val pinButtons = arrayOf(
                    view.findViewById<Button>(R.id.pinButton1),
                    view.findViewById<Button>(R.id.pinButton2),
                    view.findViewById<Button>(R.id.pinButton3),
                    view.findViewById<Button>(R.id.pinButton4),
                    view.findViewById<Button>(R.id.pinButton5),
                    view.findViewById<Button>(R.id.pinButton6),
                    view.findViewById<Button>(R.id.pinButton7),
                    view.findViewById<Button>(R.id.pinButton8),
                    view.findViewById<Button>(R.id.pinButton9)
                )
                val confirmButton = view.findViewById<Button>(R.id.confirmButton)
                val switchToDeviceButton = view.findViewById<Button>(R.id.switchToDeviceButton)
                
                // Store the PIN sequence
                val pinSequence = StringBuilder()
                
                // Set the view to the dialog
                builder.setView(view)
                
                // No need for standard dialog buttons as we have custom ones
                builder.setCancelable(false)
                
                // Create the dialog
                val dialog = builder.create()
                
                // Set up button click listeners
                for (i in pinButtons.indices) {
                    pinButtons[i].setOnClickListener {
                        // Add the mapped number from keyboardMap array based on button index
                        val mappedNumber = keyboardMap[i]
                        pinSequence.append(mappedNumber)
                        
                        // Update the display with dots for each entered digit
                        pinDisplay.text = "•".repeat(pinSequence.length)
                        
                        // Visual feedback for button press
                        it.isPressed = true
                        Handler(Looper.getMainLooper()).postDelayed({ it.isPressed = false }, 200)
                    }
                }
                
                // Set up confirm button
                confirmButton.setOnClickListener {
                    if (pinSequence.isNotEmpty()) {
                        // Return the PIN sequence
                        function?.onCallBack(pinSequence.toString())
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this@MainActivity, "请输入PIN码", Toast.LENGTH_SHORT).show()
                    }
                }
                
                // Set up switch to device button
                switchToDeviceButton.setOnClickListener {
                    // Return empty string to use hardware PIN entry
                    function?.onCallBack("")
                    dialog.dismiss()
                }
                
                // Show dialog on UI thread
                lifecycleScope.launch(Dispatchers.Main) {
                    dialog.show()
                }
            }
        })

        // Add Passphrase input handler
        webview.addHandlerLocal("requestPassphrase", object : BridgeHandler() {
            override fun handler(context: Context?, data: String?, function: CallBackFunction?) {
                Log.d("Passphrase Input", "Showing passphrase input dialog")
                
                // Use AlertDialog to show passphrase input dialog
                val builder = AlertDialog.Builder(this@MainActivity)
                builder.setTitle("输入密码") // Enter passphrase
                
                // Create a custom view for passphrase input
                val inflater = LayoutInflater.from(this@MainActivity)
                val view = LinearLayout(this@MainActivity)
                view.orientation = LinearLayout.VERTICAL
                view.setPadding(50, 50, 50, 50)
                
                // Create passphrase input EditText
                val input = EditText(this@MainActivity)
                input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                input.hint = "请输入密码" // Enter passphrase
                view.addView(input)
                
                // Set the view to the dialog
                builder.setView(view)
                
                // Add option to use hardware passphrase instead
                builder.setNeutralButton("在设备上输入") { dialog, _ ->
                    dialog.dismiss()
                    function?.onCallBack("")  // Empty string means use hardware passphrase
                }
                
                // Add buttons for submit and cancel
                builder.setPositiveButton("确认") { dialog, _ ->
                    val passphrase = input.text.toString()
                    function?.onCallBack(passphrase)
                    dialog.dismiss()
                }
                
                builder.setNegativeButton("取消") { dialog, _ ->
                    dialog.cancel()
                    function?.onCallBack("")  // If canceled, use hardware passphrase
                }
                
                // Show dialog on UI thread
                lifecycleScope.launch(Dispatchers.Main) {
                    builder.show()
                }
            }
        })

        // Add button confirmation handler
        webview.addHandlerLocal("requestButtonConfirmation", object : BridgeHandler() {
            override fun handler(context: Context?, data: String?, function: CallBackFunction?) {
                Log.d("Button Confirmation", "Showing confirmation dialog")
                
                // Parse message from data if available
                var message = "请在硬件设备上确认此操作" // Please confirm this action on your device
                if (data != null) {
                    try {
                        val jsonObject = JsonParser.parseString(data).asJsonObject
                        if (jsonObject.has("message")) {
                            message = jsonObject.get("message").asString
                        }
                    } catch (e: Exception) {
                        Log.e("Button Confirmation", "Error parsing data", e)
                    }
                }
                
                // Show confirmation dialog
                val builder = AlertDialog.Builder(this@MainActivity)
                builder.setTitle("确认操作") // Confirm Operation
                builder.setMessage(message)
                builder.setPositiveButton("了解") { dialog, _ -> // OK
                    dialog.dismiss()
                    // No callback needed, just dismiss the dialog
                }
                
                // Show dialog on UI thread
                lifecycleScope.launch(Dispatchers.Main) {
                    builder.show()
                }
                
                // Return empty response if function is not null
                function?.onCallBack("")
            }
        })

        // Add close UI window handler
        webview.addHandlerLocal("closeUIWindow", object : BridgeHandler() {
            override fun handler(context: Context?, data: String?, function: CallBackFunction?) {
                Log.d("Close UI", "Received request to close UI windows")
                
                // Here we would dismiss any open dialogs
                // Since we're handling dialogs individually and each has its own dismissal logic,
                // there's not much to do here in this implementation.
                
                // Optionally show a toast message to indicate operation completed
                Toast.makeText(this@MainActivity, "操作已完成", Toast.LENGTH_SHORT).show()
                
                // Return empty response if function is not null
                function?.onCallBack("")
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
    var connectId = ""
    val deviceId = ""

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
            updateResultText("Features: $value")
        }
    }

    fun btcGetAddress(view: View) {
        val dataJson = JsonObject().apply {
            addProperty("connectId", connectId)
            addProperty("deviceId", deviceId)
            addProperty("path", "m/44'/0'/0'/0/0")
            addProperty("coin", "btc")
            addProperty("showOnOneKey", false)
            addProperty("useEmptyPassphrase", true)
        }

        val json = JsonObject().apply {
            addProperty("name", "btcGetAddress")
            add("data", dataJson)
        }

        webview.callHandler("bridgeCommonCall", json.toString()) { value ->
            Log.d("btcGetAddress result", value)
            updateResultText("BTC Address: $value")
        }
    }

    fun evmGetAddress(view: View) {
        val dataJson = JsonObject().apply {
            addProperty("connectId", connectId)
            addProperty("deviceId", deviceId)
            addProperty("path", "m/44'/60'/0'/0/0")
            addProperty("chainId", 1)
            addProperty("showOnOneKey", true)
            addProperty("useEmptyPassphrase", true)
        }
        val json = JsonObject().apply {
            addProperty("name", "evmGetAddress")
            add("data", dataJson)
        }
        webview.callHandler("bridgeCommonCall", json.toString()) { value ->
            Log.d("evmGetAddress result", value)
            updateResultText("EVM Address: $value")
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
            updateResultText("Firmware Release: $value")    
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
            updateResultText("BLE Firmware Release: $value")
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
        scanJob?.cancel()
        
        if (!checkBluetoothPermissions() || !checkBluetoothEnabled()) {
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ble_devices, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.deviceList)
        val scanStatus = dialogView.findViewById<TextView>(R.id.scanStatus)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        val scannedDevices = mutableListOf<ServerDevice>()
        
        deviceAdapter = BleDeviceAdapter { address ->
            selectedDeviceAddress = address
            connectId = address
            
            // 保存选择的设备地址
            getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(LAST_DEVICE_ADDRESS, address)
                .apply()
            
            // 初始设置为未知类型，稍后会从API响应中更新
            currentDeviceType = DEVICE_TYPE_UNKNOWN
            
            Toast.makeText(this, "Selected device: $address", Toast.LENGTH_SHORT).show()
            scanDialog?.dismiss()
            scanJob?.cancel()
            updateConnectionStatus()
            
            val dataJson = JsonObject().apply {
                addProperty("uuid", address)
            }
            val json = JsonObject().apply {
                addProperty("name", "searchDevices")
                add("data", dataJson)
            }   
            webview.callHandler("bridgeCommonCall", json.toString()) { value ->
                updateResultText("Connect Result: $value")
                
                // 从返回值中直接获取deviceType字段
                try {
                    val jsonResponse = JsonParser.parseString(value).asJsonObject
                    if (jsonResponse.has("success") && jsonResponse.get("success").asBoolean) {
                        val payload = jsonResponse.getAsJsonArray("payload")
                        if (payload != null && payload.size() > 0) {
                            val deviceInfo = payload.get(0).asJsonObject
                            
                            // 直接获取deviceType字段
                            if (deviceInfo.has("deviceType")) {
                                currentDeviceType = deviceInfo.get("deviceType").asString.lowercase()
                                Log.d("DeviceType", "Device type from API: $currentDeviceType")
                                Toast.makeText(this@MainActivity, "Device type: $currentDeviceType", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DeviceType", "Error parsing device type from response", e)
                }
            }
        }
        recyclerView.adapter = deviceAdapter
        recyclerView.visibility = View.GONE // 初始隐藏列表

        scanDialog = AlertDialog.Builder(this)
            .setTitle("Scan BLE Devices")
            .setView(dialogView)
            .setNegativeButton("Cancel") { dialog, _ -> 
                scanJob?.cancel()
                dialog.dismiss() 
            }
            .create()

        scanDialog?.show()

        lifecycleScope.launch {
            scanStatus.text = "Scanning..."
            
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
                    scannedDevices.clear()
                    scannedDevices.addAll(results)
                }
                .launchIn(lifecycleScope)

            delay(2000)
            scanJob?.cancel()
            
            withContext(Dispatchers.Main) {
                recyclerView.visibility = View.VISIBLE
                deviceAdapter?.updateDevices(scannedDevices)
                scanStatus.text = if (scannedDevices.isEmpty()) {
                    "No devices found"
                } else {
                    "Found ${scannedDevices.size} devices"
                }
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

    private fun updateResultText(result: String) {
        val resultText = findViewById<TextView>(R.id.resultText)
        resultText.text = result
    }
}