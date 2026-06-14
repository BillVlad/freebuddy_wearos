package com.billvlad.freebuddy_wearos.presentation

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.io.BufferedInputStream
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import java.io.IOException
import java.util.*

class MainActivity : ComponentActivity() {

    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private var bluetoothSocket: BluetoothSocket? = null

    // Реактивные состояния для UI
    private var connectionStatus = mutableStateOf("Отключено")
    private var leftBattery = mutableStateOf<Int?>(null)
    private var rightBattery = mutableStateOf<Int?>(null)
    private var caseBattery = mutableStateOf<Int?>(null)
    private var activeAncMode = mutableStateOf<Byte?>(null)

    private var targetDevice: BluetoothDevice? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            initBluetooth()
            connectToBuds()
        } else {
            connectionStatus.value = "Нет прав Bluetooth"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WearAppUI(
                status = connectionStatus.value,
                leftBat = leftBattery.value,
                rightBat = rightBattery.value,
                caseBat = caseBattery.value,
                activeMode = activeAncMode.value,
                onConnectClick = { connectToBuds() },
                onAncOn = { sendAncCommand(1) },
                onAncOff = { sendAncCommand(0) },
                onAwareness = { sendAncCommand(2) }
            )
        }

        checkPermissionsAndInit()
    }

    private fun checkPermissionsAndInit() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                initBluetooth()
                connectToBuds()
            }
        } else {
            initBluetooth()
            connectToBuds()
        }
    }

    @SuppressLint("MissingPermission")
    private fun initBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return
        val pairedDevices = adapter.bondedDevices
        val device = pairedDevices.firstOrNull { it.name.contains("FreeBuds 5i", ignoreCase = true) }

        if (device != null) {
            targetDevice = device
            connectionStatus.value = "Найдено: ${device.name}"
        } else {
            connectionStatus.value = "FreeBuds 5i не сопряжены"
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToBuds() {
        val device = targetDevice
        if (device == null) {
            Toast.makeText(this, "Сопрягите наушники в системе!", Toast.LENGTH_SHORT).show()
            return
        }

        connectionStatus.value = "Подключение..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                bluetoothSocket?.close()
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                bluetoothSocket = socket

                withContext(Dispatchers.Main) {
                    connectionStatus.value = "Подключено"
                }

                // 1. Запускаем бесконечный фоновый поток чтения данных от наушников
                startReaderLoop(socket)

                // 2. Отправляем наушникам запросы на получение начального статуса
                // Запрос текущего режима ANC (команда 2B 2A)
                socket.outputStream.write(buildQueryPackage(0x2B.toByte(), 0x2A.toByte()))
                // Запрос текущего заряда батарей (команда 01 08)
                socket.outputStream.write(buildQueryPackage(0x01.toByte(), 0x08.toByte()))
                socket.outputStream.flush()

            } catch (e: IOException) {
                Log.e("Bluetooth", "Сбой подключения", e)
                withContext(Dispatchers.Main) {
                    connectionStatus.value = "Ошибка подключения"
                    resetStates()
                }
            }
        }
    }

    // Оптимизированный фоновый цикл чтения данных с буферизацией
    private fun startReaderLoop(socket: BluetoothSocket) {
        CoroutineScope(Dispatchers.IO).launch {
            val inputStream = BufferedInputStream(socket.inputStream)
            try {
                while (socket.isConnected) {
                    val firstByte = inputStream.read()
                    if (firstByte == -1) break
                    if (firstByte == 0x5A) {
                        val lenHigh = inputStream.read()
                        val lenLow = inputStream.read()
                        if (lenHigh == -1 || lenLow == -1) break
                        val dataLength = (lenHigh shl 8) or lenLow

                        // ЗАЩИТА: Если длина пакета аномальная (>128 байт), игнорируем её.
                        // Без этого при потере байта поток зависнет в вечном ожидании данных.
                        if (dataLength <= 0 || dataLength > 128) {
                            continue
                        }

                        val payload = ByteArray(dataLength)
                        var totalRead = 0
                        while (totalRead < dataLength) {
                            val read = inputStream.read(payload, totalRead, dataLength - totalRead)
                            if (read == -1) break
                            totalRead += read
                        }
                        if (totalRead < dataLength) break

                        val crcHigh = inputStream.read()
                        val crcLow = inputStream.read()
                        if (crcHigh == -1 || crcLow == -1) break

                        parseIncomingPacket(payload)
                    }
                }
            } catch (e: Exception) {
                Log.e("Bluetooth", "Ошибка чтения потока", e)
            } finally {
                withContext(Dispatchers.Main) {
                    connectionStatus.value = "Отключено"
                    resetStates()
                }
            }
        }
    }

    // Оптимизированный парсер: собирает изменения в локальные переменные
    // и обновляет UI-поток один раз за пакет (без создания лишних CoroutineScope)
    private suspend fun parseIncomingPacket(payload: ByteArray) {
        if (payload.size < 3) return
        val serviceId = payload[1]
        val commandId = payload[2]

        var tempLeft: Int? = null
        var tempRight: Int? = null
        var tempCase: Int? = null
        var tempAnc: Byte? = null
        var hasBatteryUpdate = false
        var hasAncUpdate = false

        var i = 3
        while (i < payload.size - 1) {
            val pType = payload[i].toInt() and 0xFF
            val pLength = payload[i + 1].toInt() and 0xFF
            if (i + 2 + pLength > payload.size) break

            val pValue = ByteArray(pLength)
            System.arraycopy(payload, i + 2, pValue, 0, pLength)

            if (serviceId == 0x01.toByte() && (commandId == 0x08.toByte() || commandId == 0x27.toByte() || commandId == 0x22.toByte())) {
                if (pType == 2 && pLength >= 3) {
                    val left = pValue[0].toInt() and 0xFF
                    val right = pValue[1].toInt() and 0xFF
                    val case = pValue[2].toInt() and 0xFF

                    tempLeft = if (left != 0xFF && left <= 100) left else null
                    tempRight = if (right != 0xFF && right <= 100) right else null
                    tempCase = if (case != 0xFF && case <= 100) case else null
                    hasBatteryUpdate = true
                }
            }

            if (serviceId == 0x2B.toByte() && (commandId == 0x2A.toByte() || commandId == 0x04.toByte())) {
                if (pType == 1 && pLength == 1) {
                    tempAnc = pValue[0]
                    hasAncUpdate = true
                }
            }

            i += 2 + pLength
        }

        // Переключаемся на главный поток ОДИН раз для атомарного обновления Compose-состояний
        withContext(Dispatchers.Main) {
            if (hasBatteryUpdate) {
                leftBattery.value = tempLeft
                rightBattery.value = tempRight
                caseBattery.value = tempCase
            }
            if (hasAncUpdate) {
                activeAncMode.value = tempAnc
            }
        }
    }

    private fun sendAncCommand(mode: Byte) {
        val socket = bluetoothSocket
        if (socket == null || !socket.isConnected) {
            Toast.makeText(this, "Наушники не подключены!", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val data = buildAncPackage(mode)
                socket.outputStream.write(data)
                socket.outputStream.flush()
                Log.d("Bluetooth", "Отправлена команда ANC: $mode")
                // Мы не меняем UI вручную! Наушники сами пришлют в сокет пакет подтверждения 2B2A,
                // наш фоновый поток его поймает и красиво подсветит нужную кнопку.
            } catch (e: IOException) {
                Log.e("Bluetooth", "Ошибка отправки команды", e)
                withContext(Dispatchers.Main) {
                    connectionStatus.value = "Связь потеряна"
                    resetStates()
                }
            }
        }
    }

    private fun buildQueryPackage(serviceId: Byte, commandId: Byte): ByteArray {
        val dataLength = 3 // 1 байт нуля + 2 байта ID команды
        val payload = ByteArray(6)
        payload[0] = 0x5A
        payload[1] = 0x00
        payload[2] = dataLength.toByte()
        payload[3] = 0x00
        payload[4] = serviceId
        payload[5] = commandId

        val crc = calculateCRC16XModem(payload)
        val fullPackage = ByteArray(payload.size + 2)
        System.arraycopy(payload, 0, fullPackage, 0, payload.size)
        fullPackage[payload.size] = (crc ushr 8).toByte()
        fullPackage[payload.size + 1] = (crc and 0xFF).toByte()
        return fullPackage
    }

    private fun buildAncPackage(mode: Byte): ByteArray {
        val serviceId: Byte = 0x2B
        val commandId: Byte = 0x04
        val parameters = byteArrayOf(0x01, 0x01, mode)
        val paramLength = parameters.size
        val dataLength = paramLength + 3

        val payload = ByteArray(6 + paramLength)
        payload[0] = 0x5A
        payload[1] = (dataLength ushr 8).toByte()
        payload[2] = (dataLength and 0xFF).toByte()
        payload[3] = 0x00
        payload[4] = serviceId
        payload[5] = commandId
        System.arraycopy(parameters, 0, payload, 6, paramLength)

        val crc = calculateCRC16XModem(payload)

        val fullPackage = ByteArray(payload.size + 2)
        System.arraycopy(payload, 0, fullPackage, 0, payload.size)
        fullPackage[payload.size] = (crc ushr 8).toByte()
        fullPackage[payload.size + 1] = (crc and 0xFF).toByte()

        return fullPackage
    }

    private fun calculateCRC16XModem(data: ByteArray): Int {
        var crc = 0x0000
        val polynomial = 0x1021
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF shl 8)
            for (i in 0 until 8) {
                crc = if (crc and 0x8000 != 0) {
                    (crc shl 1) xor polynomial
                } else {
                    crc shl 1
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun resetStates() {
        leftBattery.value = null
        rightBattery.value = null
        caseBattery.value = null
        activeAncMode.value = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            bluetoothSocket?.close()
        } catch (ignored: Exception) {}
    }
}

@Composable
fun WearAppUI(
    status: String,
    leftBat: Int?,
    rightBat: Int?,
    caseBat: Int?,
    activeMode: Byte?,
    onConnectClick: () -> Unit,
    onAncOn: () -> Unit,
    onAncOff: () -> Unit,
    onAwareness: () -> Unit
) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val scrollState = rememberScrollState()

            // 1. Создаем инструменты фокуса для безеля и область для корутины
            val focusRequester = remember { FocusRequester() }
            val coroutineScope = rememberCoroutineScope()

            // 2. Принудительно отдаем фокус колонке при открытии экрана
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onRotaryScrollEvent { event ->
                        // dispatchRawDelta работает синхронно и мгновенно, убирая микро-фризы
                        scrollState.dispatchRawDelta(event.verticalScrollPixels)
                        true
                    }
                    .padding(top = 28.dp, bottom = 28.dp, start = 14.dp, end = 14.dp)
            ) {
                // ... Содержимое колонки остается неизменным ...
                Text(
                    text = status,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )

                BatteryRow(left = leftBat, right = rightBat, case = caseBat)

                Button(
                    onClick = onConnectClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                ) {
                    Text("Подключить")
                }

                Button(
                    onClick = onAncOn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeMode == 1.toByte()) MaterialTheme.colorScheme.primary else Color(0xFF202124),
                        contentColor = if (activeMode == 1.toByte()) Color.Black else Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("Шумоподавление", style = MaterialTheme.typography.labelSmall)
                }

                Button(
                    onClick = onAncOff,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeMode == 0.toByte()) MaterialTheme.colorScheme.primary else Color(0xFF202124),
                        contentColor = if (activeMode == 0.toByte()) Color.Black else Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("Выключено", style = MaterialTheme.typography.labelSmall)
                }

                Button(
                    onClick = onAwareness,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeMode == 2.toByte()) MaterialTheme.colorScheme.primary else Color(0xFF202124),
                        contentColor = if (activeMode == 2.toByte()) Color.Black else Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("Прозрачность", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun BatteryRow(left: Int?, right: Int?, case: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        val lText = if (left != null) "L: $left%" else "L: --"
        val rText = if (right != null) "R: $right%" else "R: --"
        val cText = if (case != null) "C: $case%" else "C: --"

        Text(text = lText, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        Text(text = rText, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        Text(text = cText, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
    }
}