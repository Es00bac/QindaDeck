package org.qinda.deck.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Wi-Fi provisioning over a short BLE link.
 *
 * The deck advertises a tiny GATT service while its provisioning window is
 * open (armed from the app via the `bt_prov` op, or always when it has no
 * Wi-Fi credentials). We connect, write SSID, password, and the apply flag,
 * and drop the link — the deck stores them and reboots onto the network.
 * Nothing about this touches the deck's day job as a BLE HID host; the radio
 * is only a provisioning peripheral for these few seconds.
 */
@SuppressLint("MissingPermission") // gated by the caller's permission check
class WifiSync(context: Context) {

    enum class Stage { Idle, Scanning, Connecting, Sending, Done, Failed }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _stage = MutableStateFlow(Stage.Idle)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    val bluetoothAvailable: Boolean get() = adapter != null

    fun sync(ssid: String, pass: String, scope: CoroutineScope) {
        if (_stage.value == Stage.Scanning || _stage.value == Stage.Connecting ||
            _stage.value == Stage.Sending) return
        scope.launch(Dispatchers.Default) {
            _stage.value = Stage.Scanning
            _detail.value = "Looking for the deck…"
            try {
                withTimeout(45_000) { run(ssid, pass) }
                _stage.value = Stage.Done
                _detail.value = "Sent. The deck is rebooting onto \"$ssid\"."
            } catch (e: Exception) {
                _stage.value = Stage.Failed
                _detail.value = e.message ?: "sync failed"
            }
        }
    }

    fun reset() {
        _stage.value = Stage.Idle
        _detail.value = ""
    }

    // ------------------------------------------------------------ the dance

    private suspend fun run(ssid: String, pass: String) {
        val adapter = this.adapter ?: error("This phone has no Bluetooth")
        if (!adapter.isEnabled) error("Bluetooth is off")

        // Scan for the service UUID; a name prefix is the fallback for phones
        // that filter 128-bit UUIDs badly.
        val found = CompletableDeferred<ScanResult>()
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build(),
            ScanFilter.Builder().setDeviceName("QindaDeck").build(),
        )
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: ""
                val uuids = result.scanRecord?.serviceUuids
                if (uuids?.contains(ParcelUuid(SVC)) == true ||
                    name.startsWith("QindaDeck")) {
                    found.complete(result)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(DeckSyncException("BLE scan failed ($errorCode)"))
            }
        }
        val scanner = adapter.bluetoothLeScanner
            ?: error("BLE scanner unavailable (is Bluetooth fully on?)")
        scanner.startScan(filters, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        val result = try {
            withTimeout(15_000) { found.await() }
        } catch (e: Exception) {
            throw DeckSyncException("No deck found advertising Wi-Fi sync. " +
                "Open the window from Settings on a connected deck, or leave a " +
                "fresh deck in its setup mode.")
        } finally {
            scanner.stopScan(callback)
        }

        // Connect and write the three characteristics in order.
        _stage.value = Stage.Connecting
        _detail.value = "Connecting to ${result.device.name ?: "the deck"}…"
        BleLink(appContext, result.device.address).use { link ->
            link.connect()
            _stage.value = Stage.Sending
            _detail.value = "Sending network…"
            link.write(CHAR_SSID, ssid.toByteArray(Charsets.UTF_8))
            link.write(CHAR_PASS, pass.toByteArray(Charsets.UTF_8))
            link.write(CHAR_APPLY, byteArrayOf(1))
        }
    }

    private class DeckSyncException(message: String) : Exception(message)

    /** One short GATT connection, used and thrown away. */
    private inner class BleLink(
        private val context: Context,
        private val address: String,
    ) : AutoCloseable {
        private var gatt: BluetoothGatt? = null
        private var step = CompletableDeferred<Unit>()

        suspend fun connect() {
            step = CompletableDeferred()
            gatt = adapter?.getRemoteDevice(address)
                ?.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
                ?: error("could not open GATT")
            withTimeout(12_000) { step.await() }
            step = CompletableDeferred()
            gatt?.discoverServices()
            withTimeout(12_000) { step.await() }
        }

        suspend fun write(uuid: UUID, value: ByteArray) {
            val g = gatt ?: error("not connected")
            val ch = g.getService(SVC)?.getCharacteristic(uuid)
                ?: error("the deck has no Wi-Fi sync service")
            step = CompletableDeferred()
            @Suppress("DEPRECATION")
            ch.value = value
            @Suppress("DEPRECATION")
            if (!g.writeCharacteristic(ch)) error("write rejected for $uuid")
            withTimeout(8_000) { step.await() }
        }

        override fun close() {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
        }

        private val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    step.complete(Unit)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !step.isCompleted) {
                    step.completeExceptionally(DeckSyncException("the deck dropped the link ($status)"))
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) step.complete(Unit)
                else step.completeExceptionally(DeckSyncException("service discovery failed ($status)"))
            }
            @Deprecated("legacy path")
            override fun onCharacteristicWrite(g: BluetoothGatt,
                                               characteristic: BluetoothGattCharacteristic,
                                               status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) step.complete(Unit)
                else step.completeExceptionally(DeckSyncException("write failed ($status)"))
            }
        }
    }

    companion object {
        /** Must match firmware/main/bt_prov.c. */
        val SVC: UUID = UUID.fromString("51DA1000-0DEC-4000-A000-0000000DECA0")
        val CHAR_SSID: UUID = UUID.fromString("51DA1001-0DEC-4000-A000-0000000DECA0")
        val CHAR_PASS: UUID = UUID.fromString("51DA1002-0DEC-4000-A000-0000000DECA0")
        val CHAR_APPLY: UUID = UUID.fromString("51DA1003-0DEC-4000-A000-0000000DECA0")
    }
}
