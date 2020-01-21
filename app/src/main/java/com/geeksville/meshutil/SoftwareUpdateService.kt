package com.geeksville.meshutil

import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.JobIntentService
import java.util.*


/**
 * typical flow
 *
 * startScan
 * startUpdate
 * sendNextBlock
 * finishUpdate
 *
 * stopScan
 *
 * FIXME - if we don't find a device stop our scan
 * FIXME - broadcast when we found devices, made progress sending blocks or when the update is complete
 * FIXME - make the user decide to start an update on a particular device
 */
class SoftwareUpdateService : JobIntentService() {

    private val bluetoothAdapter: BluetoothAdapter by lazy(LazyThreadSafetyMode.NONE) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter!!
    }

    lateinit var updateGatt: BluetoothGatt // the gatt api used to talk to our device
    lateinit var updateService: BluetoothGattService // The service we are currently talking to to do the update
    lateinit var totalSizeDesc: BluetoothGattCharacteristic

    fun startUpdate() {
        if (updateService != null) {
            totalSizeDesc = updateService.getCharacteristic(SW_UPDATE_TOTALSIZE_CHARACTER)!!

            // Start the update by writing the # of bytes in the image
            val numBytes = 45
            assert(totalSizeDesc.setValue(numBytes, BluetoothGattCharacteristic.FORMAT_UINT32, 0))
            assert(updateGatt.writeCharacteristic(totalSizeDesc))
            assert(updateGatt.readCharacteristic(totalSizeDesc))
        }
    }

    // Send the next block of our file to the device
    fun sendNextBlock() {

    }

    // For each device that appears in our scan, ask for its GATT, when the gatt arrives,
    // check if it is an eligable device and store it in our list of candidates
    // if that device later disconnects remove it as a candidate
    private val leScanCallback = BluetoothAdapter.LeScanCallback { device, _, _ ->
        lateinit var bluetoothGatt: BluetoothGatt // late init so we can declare our callback and use this there

        //var connectionState = STATE_DISCONNECTED

        // Various callback methods defined by the BLE API.
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                //val intentAction: String
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        //intentAction = ACTION_GATT_CONNECTED
                        //connectionState = STATE_CONNECTED
                        // broadcastUpdate(intentAction)
                        Log.i(AppCompatActivity.TAG, "Connected to GATT server.")
                        Log.i(
                            AppCompatActivity.TAG, "Attempting to start service discovery: "
                        )
                        assert(bluetoothGatt.discoverServices())
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        //intentAction = ACTION_GATT_DISCONNECTED
                        //connectionState = STATE_DISCONNECTED
                        Log.i(AppCompatActivity.TAG, "Disconnected from GATT server.")
                        // broadcastUpdate(intentAction)
                    }
                }
            }

            // New services discovered
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                assert(status == BluetoothGatt.GATT_SUCCESS)

                // broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)

                val service = gatt.services.find { it.uuid == SW_UPDATE_UUID }
                if (service != null) {
                    // FIXME instead of slamming in the target device here, instead make it a param for startUpdate
                    updateService = service
                    // FIXME instead of keeping the connection open, make start update just reconnect (needed once user can choose devices)
                    updateGatt = bluetoothGatt
                    enqueueWork(this@SoftwareUpdateService, startUpdateIntent)
                } else {
                    // drop our connection - we don't care about this device
                    bluetoothGatt.disconnect()
                }
            }

            // Result of a characteristic read operation
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                assert(status == BluetoothGatt.GATT_SUCCESS)

                if (characteristic == totalSizeDesc) {
                    // Our read of this has completed, either fail or continue updating
                    val readvalue =
                        characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT32, 0)
                    assert(readvalue != 0) // FIXME - handle this case
                    enqueueWork(this@SoftwareUpdateService, sendNextBlockIntent)
                }

                // broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
            }
        }
        bluetoothGatt = device.connectGatt(this, false, gattCallback)!!
    }

    private fun scanLeDevice(enable: Boolean) {
        when (enable) {
            true -> {
                // Stops scanning after a pre-defined scan period.
                /* handler.postDelayed({
                    mScanning = false
                    bluetoothAdapter.stopLeScan(leScanCallback)
                }, SCAN_PERIOD)
                mScanning = true */
                assert(bluetoothAdapter.startLeScan(leScanCallback))
            }
            else -> {
                // mScanning = false
                bluetoothAdapter.stopLeScan(leScanCallback)
            }
        }
    }

    override fun onHandleWork(intent: Intent) { // We have received work to do.  The system or framework is already
// holding a wake lock for us at this point, so we can just go.
        Log.i("SimpleJobIntentService", "Executing work: $intent")
        var label = intent.getStringExtra("label")
        if (label == null) {
            label = intent.toString()
        }
        toast("Executing: $label")

        Log.i(
            "SimpleJobIntentService",
            "Completed service @ " + SystemClock.elapsedRealtime()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        toast("All work complete")
    }

    val mHandler = Handler()
    // Helper for showing tests
    fun toast(text: CharSequence?) {
        mHandler.post {
            Toast.makeText(this@SoftwareUpdateService, text, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /**
         * Unique job ID for this service.  Must be the same for all work.
         */
        const val JOB_ID = 1000

        val scanDevicesIntent = Intent("com.geeksville.meshutil.SCAN_DEVICES")
        val startUpdateIntent = Intent("com.geeksville.meshutil.START_UPDATE")
        private val sendNextBlockIntent = Intent("com.geeksville.meshutil.SEND_NEXT_BLOCK")
        private val finishUpdateIntent = Intent("com.geeksville.meshutil.FINISH_UPDATE")

        private const val SCAN_PERIOD: Long = 10000

        //const val ACTION_GATT_CONNECTED = "com.example.bluetooth.le.ACTION_GATT_CONNECTED"
        //const val ACTION_GATT_DISCONNECTED = "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED"

        private val TAG =
            MainActivity::class.java.simpleName // FIXME - use my logging class instead

        private val SW_UPDATE_UUID = UUID.fromString("cb0b9a0b-a84c-4c0d-bdbb-442e3144ee30")
        private val SW_UPDATE_TOTALSIZE_CHARACTER =
            UUID.fromString("e74dd9c0-a301-4a6f-95a1-f0e1dbea8e1e") // write|read          total image size, 32 bit, write this first, then read read back to see if it was acceptable (0 mean not accepted)
        private val SW_UPDATE_DATA_CHARACTER =
            UUID.fromString("e272ebac-d463-4b98-bc84-5cc1a39ee517") //  write               data, variable sized, recommended 512 bytes, write one for each block of file
        private val SW_UPDATE_CRC32_CHARACTER =
            UUID.fromString("4826129c-c22a-43a3-b066-ce8f0d5bacc6") //  write               crc32, write last - writing this will complete the OTA operation, now you can read result
        private val SW_UPDATE_RESULT_CHARACTER =
            UUID.fromString("5e134862-7411-4424-ac4a-210937432c77") // read|notify         result code, readable but will notify when the OTA operation completes

        /**
         * Convenience method for enqueuing work in to this service.
         */
        fun enqueueWork(context: Context, work: Intent) {
            enqueueWork(
                context,
                SoftwareUpdateService::class.java, JOB_ID, work
            )
        }
    }
}