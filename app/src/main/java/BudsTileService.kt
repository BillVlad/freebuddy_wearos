package com.billvlad.freebuddy_wearos.presentation

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import androidx.wear.protolayout.material.Chip
import androidx.wear.protolayout.material.ChipColors
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.MultiButtonLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class BudsTileService : TileService() {

    private val RESOURCES_VERSION = "1"
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        // 1. Проверяем, была ли нажата кнопка на плитке
        val lastClickId = requestParams.currentState.lastClickableId
        if (lastClickId.isNotEmpty()) {
            val mode: Byte? = when (lastClickId) {
                "action_anc_1" -> 1.toByte() // Шум
                "action_anc_0" -> 0.toByte() // Выкл
                "action_anc_2" -> 2.toByte() // Прозрачность
                else -> null
            }
            if (mode != null) {
                // Выполняем тихую отправку команды в фоновом потоке
                sendAncCommandInBackground(mode)
            }
        }

        val layout = LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setWidth(DimensionBuilders.expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        Text.Builder(this, "FreeBuds 5i")
                            .setTypography(Typography.TYPOGRAPHY_TITLE3)
                            .setColor(ColorBuilders.argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(4f)).build())

                    // Первая строка
                    .addContent(
                        createAncChip(this, "ANC On", "1", requestParams.deviceConfiguration)
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(2f)).build())

                    // Вторая строка
                    .addContent(
                        createAncChip(this, "ANC Off", "0", requestParams.deviceConfiguration)
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(2f)).build())

                    // Третья строка
                    .addContent(
                        createAncChip(this, "Awareness", "2", requestParams.deviceConfiguration)
                    )
                    .build()
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(layout).build())
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    private fun createAncChip(
        context: Context,
        label: String,
        modeValue: String,
        deviceConfiguration: androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
    ): LayoutElementBuilders.LayoutElement {
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId("action_anc_$modeValue")
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()

        return Chip.Builder(context, clickable, deviceConfiguration)
            .setPrimaryLabelContent(label) // Передаем кастомный элемент вместо обычной строки
            .setWidth(DimensionBuilders.dp(160f))
            .setChipColors(
                ChipColors(
                    ColorBuilders.argb(0xFF202124.toInt()),
                    ColorBuilders.argb(0xFFFFFFFF.toInt())
                )
            )
            .build()
    }

    // Фоновое подключение и отправка команды без участия Activity
    @SuppressLint("MissingPermission")
    private fun sendAncCommandInBackground(mode: Byte) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return
        val pairedDevices = adapter.bondedDevices
        val device = pairedDevices.firstOrNull { it.name.contains("FreeBuds 5i", ignoreCase = true) }

        if (device == null) {
            Toast.makeText(this, "5i не сопряжены!", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()

                val data = buildAncPackage(mode)
                socket.outputStream.write(data)
                socket.outputStream.flush()

                withContext(Dispatchers.Main) {
                    val modeName = when(mode) {
                        1.toByte() -> "Шумоподавление"
                        0.toByte() -> "Выкл"
                        2.toByte() -> "Прозрачность"
                        else -> "Режим изменен"
                    }
                    Toast.makeText(this@BudsTileService, "ANC: $modeName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("BudsTileService", "Background send failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BudsTileService, "Ошибка подключения", Toast.LENGTH_SHORT).show()
                }
            } finally {
                try {
                    socket?.close()
                } catch (ignored: Exception) {}
            }
        }
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
}