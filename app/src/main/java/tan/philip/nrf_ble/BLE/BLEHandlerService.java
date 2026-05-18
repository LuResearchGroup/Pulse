package tan.philip.nrf_ble.BLE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tan.philip.nrf_ble.BLE.BLEDevices.BLEDevice;
import tan.philip.nrf_ble.BLE.BLEDevices.BLETattooDevice;
import tan.philip.nrf_ble.BLE.BLEDevices.DebugBLEDevice;
import tan.philip.nrf_ble.BLE.Gatt.GattManager;
import tan.philip.nrf_ble.BLE.PacketParsing.BLEPacketParser;
import tan.philip.nrf_ble.Events.ScanListUpdatedEvent;
import tan.philip.nrf_ble.Events.Sickbay.SickbayReinitializeEvent;
import tan.philip.nrf_ble.Events.UIRequests.RequestBLEClearScanListEvent;
import tan.philip.nrf_ble.Events.UIRequests.RequestBLEConnectEvent;
import tan.philip.nrf_ble.Events.UIRequests.RequestBLEDisconnectEvent;
import tan.philip.nrf_ble.Events.UIRequests.RequestBLEStartScanEvent;
import tan.philip.nrf_ble.Events.UIRequests.RequestBLEStopScanEvent;
import tan.philip.nrf_ble.Events.UIRequests.RequestEndBLEForegroundEvent;
import tan.philip.nrf_ble.R;
import tan.philip.nrf_ble.SickbayPush.SickbayPushService;

import static tan.philip.nrf_ble.BLE.BLEDevices.DebugBLEDevice.DEBUG_MODE_ADDRESS;
import static tan.philip.nrf_ble.Constants.NUS_UUID;
import static tan.philip.nrf_ble.NotificationHandler.CHANNEL_ID;
import static tan.philip.nrf_ble.NotificationHandler.FOREGROUND_SERVICE_NOTIFICATION_ID;
import static tan.philip.nrf_ble.NotificationHandler.makeNotification;


//This service is a higher level package to handle scanning, connection events, receiving and sending data, etc
public class BLEHandlerService extends Service {

    public static final String TAG = "BLEHandlerService";
    private Map<String, BluetoothDevice> mScanResults = new HashMap();
    private final Map<String, Integer> mScanRSSIs = new HashMap();
    private static boolean isRunning = false;

    //Messenger to communicate with client threads
    private final IBinder binder = new LocalBinder();

    //Tattoo Connection Management
    private GattManager mGattManager;
    private TattooConnectionManager mConnectionManager;

    //Scanning
    private boolean mScanning = false;
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private ScanCallback mScanCallback;
    private Handler scanHandler;

    //Communication to SickbayPush
    SickbayPushService sickbayPushService;
    boolean mIsBound = false;
    boolean pushToSickbay = true;

    //Saving to file
    private final boolean mRecording = false;

    // ADD fields
    private static final String CH_ALERT = "ble_disconnect_alert";
    private static final int    NOTIF_ID_ALERT = AlertStopReceiver.NOTIF_ID_ALERT;
    private static final String ACTION_SILENCE = "tan.philip.nrf_ble.action.SILENCE_ALERT";

    // If you want to suppress the alarm on *user*-requested disconnects:
    private boolean mUserRequestedDisconnect = false;

    NotificationCompat.Builder notificationBuilder;

    private void ensureAlertChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ALERT, "BLE Disconnect Alert", NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private PendingIntent makeStopPI() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(this, 0,
                new Intent(this, AlertStopReceiver.class).setAction(ACTION_SILENCE), flags);
    }

    private void startDisconnectAlert() {
        ensureAlertChannel();

        // Tap body -> open your GraphActivity (optional)
        int cFlags = PendingIntent.FLAG_UPDATE_CURRENT |
                (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentPI = PendingIntent.getActivity(
                this, 0, new Intent(this, tan.philip.nrf_ble.GraphScreen.GraphActivity.class), cFlags);

        PendingIntent stopPI = makeStopPI();

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.heartrate)
                .setContentTitle("Sensor disconnected")
                .setContentText("Tap to open. Swipe away or press Silence to stop vibration.")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .setContentIntent(contentPI)
                .addAction(0, "Silence", stopPI)
                .setDeleteIntent(stopPI);

        NotificationManagerCompat.from(this).notify(NOTIF_ID_ALERT, nb.build());

        // Vibrate in a loop until user stops it
        Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vib != null) {
            long[] pattern = new long[]{0, 700, 500}; // start immediately, vibrate 700ms, pause 500ms
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, 0)); // repeat from index 0
            } else {
                // Deprecated pre-26 but still works
                vib.vibrate(pattern, 0);
            }
        }
    }

    private void stopDisconnectAlert() {
        Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vib != null) vib.cancel();
        NotificationManagerCompat.from(this).cancel(NOTIF_ID_ALERT);
    }



    /////////////////////////Lifecycle Methods//////////////////////////////////////////////
    //@Nullable
    @Override
    public IBinder onBind(Intent intent) {
        //activityToServiceMessenger = new Messenger(new ServiceHandler());
        //return activityToServiceMessenger.getBinder();
        return binder;
    }

    //@Override
    public void onStartCommand() {

    }

    @Override
    public void onCreate() {
        mGattManager = new GattManager(this);
        mConnectionManager = new TattooConnectionManager(this, mGattManager);

        //Register on EventBus
        EventBus.getDefault().register(this);

        //Scanning
        setupBLEScanner();

        isRunning = true;

        //Foreground Service - keep it running. Just set up here, need to call startForeground to actually make it run in foreground
        Intent notificationIntent = new Intent(this, BLEHandlerService.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Attempting BLE connection...")
                .setContentText("")
                .setSmallIcon(R.drawable.heartrate)
                .setContentIntent(pendingIntent);
    }

    @Override
    public void onDestroy() {
        //Unregister from EventBus
        EventBus.getDefault().unregister(this);
        mConnectionManager.unregister();

        //Unbind from and explicitly stop the SickbayPushService so it tears down its socket and
        //releases the WiFi + partial wake locks (prevents permanent battery drain).
        if (mIsBound) {
            unbindService(sickbayPushConnection);
            mIsBound = false;
        }
        stopService(new Intent(this, SickbayPushService.class));

        //Connecting
        closeAllConnections();

        isRunning = false;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void endForeground(RequestEndBLEForegroundEvent event) {
        stopForeground(true);
    }

    public class LocalBinder extends Binder {
        public BLEHandlerService getService() {
            // Return this instance of BLEHandlerService so clients can call public methods
            return BLEHandlerService.this;
        }
    }

    ////////////////////////////Connection to SickbayPush Service/////////////////////////////////

    private final ServiceConnection sickbayPushConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            SickbayPushService.LocalBinder binder = (SickbayPushService.LocalBinder) service;
            sickbayPushService = binder.getService();
            mIsBound = true;

            //sickbayPushService.initializeQueues(mConnectionManager.getBLEDevices());
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mIsBound = false;
        }
    };

    //////////////////////////Scanning//////////////////////////////////////////////////
    public boolean hasBLEPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled());
    }

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    private boolean setupBLEScanner() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    @SuppressLint("MissingPermission")
    @Subscribe(threadMode =  ThreadMode.MAIN)
    public void startScan(RequestBLEStartScanEvent event) {
        //BLE code
        //Return all broadcasting Bluetooth devices
        List<ScanFilter> filters = new ArrayList<>();

        ScanFilter scanFilter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(NUS_UUID))
                .build();

        filters.add(scanFilter);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        //Store scan results in HashMap
        mScanResults = new HashMap<>();
        mScanCallback = new BtleScanCallback(mScanResults, mScanRSSIs);

        //Now grab hold of the BluetoothLeScanner to start the scan, and set our scanning boolean to true.
        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (mBluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null. Check if Bluetooth is enabled/supported.");
            return;
        }
        mBluetoothLeScanner.startScan(filters, settings, mScanCallback);

        mScanning = true;

        //The scan results saved in the map includes:
        //BluetoothDevice: Name and address
        //RSSI: Received signal strength indication
        //Timestamp
        //ScanRecord
        //  - Advertisement Flags: Discoverable mode and cababilities of the device
        //  - Manufacturer Specific Data: Info useful when filtering
        //  - GATT Service UUIDs


        //Limit the scan duration to a specified time
        scanHandler = new Handler();
        //mHandler.postDelayed(this::stopScan, SCAN_PERIOD);
        scanHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Map<String, Boolean> isInitialized = new HashMap<>();
                for(String address : mScanResults.keySet()) //If the init file returns null, put in the not initialized map.
                    isInitialized.put(address, BLEPacketParser.lookupInitFile(mScanResults.get(address).getName(),BLEHandlerService.this) != null);

                EventBus.getDefault().post(new ScanListUpdatedEvent(mScanResults, mScanRSSIs, isInitialized));

                //Clear
                updateScanList();
                scanHandler.postDelayed(this, 1500);
            }
        }, 00);
    }

    @SuppressLint("MissingPermission")
    @Subscribe(threadMode =  ThreadMode.MAIN)
    public void stopScan(RequestBLEStopScanEvent event) {
        //valueAnimator.cancel();
        mScanning = false;

        if(scanHandler != null)
            scanHandler.removeCallbacksAndMessages(null);

        if (mScanning && mBluetoothAdapter != null && mBluetoothAdapter.isEnabled() && mBluetoothLeScanner != null) {
            mBluetoothLeScanner.stopScan(mScanCallback);

            if (mScanResults.isEmpty()) {
                Toast toast = Toast.makeText(getApplicationContext(), "No devices found", Toast.LENGTH_SHORT);
                toast.show();
                return;
            }
        }

        mScanCallback = null;
        scanHandler = null;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void clearScan(RequestBLEClearScanListEvent event) {
        mScanResults.clear();
    }

    private void updateScanList() {
        mScanResults.clear();
        mScanRSSIs.clear();
    }

    private class BtleScanCallback extends ScanCallback {

        List<UUID> serviceUUIDsList        = new ArrayList<>();
        List<UUID> characteristicUUIDsList = new ArrayList<>();
        List<UUID> descriptorUUIDsList     = new ArrayList<>();

        private final Map<String, BluetoothDevice> mScanResults;
        private final Map<String, Integer> mRSSIs;

        BtleScanCallback(Map<String, BluetoothDevice> scanResults, Map<String, Integer> rssis) {
            mScanResults = scanResults;
            mRSSIs = rssis;
        }
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            addScanResult(result);
        }
        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                addScanResult(result);
            }
        }
        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE Scan Failed with code " + errorCode);
        }
        private void addScanResult(ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String deviceAddress = device.getAddress();

            serviceUUIDsList = getServiceUUIDsList(result, deviceAddress);

            mScanResults.put(deviceAddress, device);
            mRSSIs.put(deviceAddress, result.getRssi());
        }

        private List<UUID> getServiceUUIDsList(ScanResult scanResult, String deviceAddress)
        {
            List<ParcelUuid> parcelUuids = scanResult.getScanRecord().getServiceUuids();

            List<UUID> serviceList = new ArrayList<>();

            if(parcelUuids != null) {
                for (int i = 0; i < parcelUuids.size(); i++) {
                    UUID serviceUUID = parcelUuids.get(i).getUuid();
                    String uuidString = serviceUUID.toString();
                    //Log.d(TAG, deviceAddress + " " + uuidString);

                    if (!serviceList.contains(serviceUUID))
                        serviceList.add(serviceUUID);
                }
            }

            return serviceList;
        }
    }


    /////////////////////////Connecting////////////////////////////////////////////////
    public ArrayList<BLEDevice> getBLEDevices() {
        return mConnectionManager.getBLEDevices();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void connectDevices(RequestBLEConnectEvent event) {
        ArrayList<String> addresses = event.getAddresses();
        for (String address : addresses)
            connectDevice(address);

        if(pushToSickbay) {
            //Explicitly START the SickbayPushService (as a foreground service) BEFORE binding so
            //its lifetime is independent of the binding and it owns its own foreground/wake-lock
            //lifecycle. BIND_AUTO_CREATE alone would tear it down with the last unbind.
            Intent intent = new Intent(this, SickbayPushService.class);
            ContextCompat.startForegroundService(this, intent);
            bindService(intent, sickbayPushConnection, Context.BIND_AUTO_CREATE);
            EventBus.getDefault().post(new SickbayReinitializeEvent());
        }
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    @SuppressLint("MissingPermission")
    private void connectDevice(final String address) {
        notificationBuilder.setContentTitle("Attempting BLE connection...");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FOREGROUND_SERVICE_NOTIFICATION_ID, notificationBuilder.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(FOREGROUND_SERVICE_NOTIFICATION_ID, notificationBuilder.build());
        }
        makeNotification(FOREGROUND_SERVICE_NOTIFICATION_ID, notificationBuilder.build());

        //deviceAddress = address;
        //deviceNameToConnect = getBluetoothIdentifier(deviceAddress);

        stopDisconnectAlert(); // NEW---

        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return;
        }

        try {
            BLETattooDevice newTattooDevice;
            if(!address.equals(DEBUG_MODE_ADDRESS)) {
                final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
                String deviceName = null;
                if (mScanResults.containsKey(address)) {
                    deviceName = mScanResults.get(address).getName();
                }
                if (deviceName == null) deviceName = device.getName();
                Log.w(TAG, deviceName != null ? deviceName : "Unknown Device");
                newTattooDevice = new BLETattooDevice(this, device, deviceName);

                if (device == null) {
                    Log.w(TAG, "Device not found.  Unable to connect.");
                    return;
                }
            } else {
                newTattooDevice = new DebugBLEDevice(this, null);
            }

            mConnectionManager.checkAndConnectToTattoo(newTattooDevice);
            notificationBuilder.setContentTitle("Paired with " + newTattooDevice.getBluetoothIdentifier());
            makeNotification(FOREGROUND_SERVICE_NOTIFICATION_ID, notificationBuilder.build());

        } catch (FileNotFoundException e) { //The init file does not exist for this tattoo.
            Log.e("", "Tattoo not recognized");
            disconnect(address);
        }
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect(String address) {
        Log.w(TAG, "disconnect() called for " + address +
                "  userRequested=" + mUserRequestedDisconnect +
                "  remaining=" + mConnectionManager.getBLEDevices().size());

        if (mBluetoothAdapter == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }

        mConnectionManager.disconnectTattoo(address);

        if (mConnectionManager.getBLEDevices().size() == 0) {
            stopForeground(true);

            //No BLE devices left -> nothing to stream. Stop Sickbay so it releases its
            //WiFi + partial wake locks instead of holding the CPU awake indefinitely.
            if (mIsBound) {
                unbindService(sickbayPushConnection);
                mIsBound = false;
            }
            stopService(new Intent(this, SickbayPushService.class));

            // If user *explicitly* requested, don't alarm. Otherwise, alarm.
            if (mUserRequestedDisconnect) {
                mUserRequestedDisconnect = false; // consume the flag
                stopDisconnectAlert();            // just in case
            } else {
                startDisconnectAlert();
            }
        }
    }

    public void closeAllConnections() {
        for(BLEDevice d : mConnectionManager.getBLEDevices())
            disconnect(d.getAddress());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void disconnect(RequestBLEDisconnectEvent event) {
        mUserRequestedDisconnect = true;  // mark as intentional
        closeAllConnections();
    }

    public static boolean isRunning() {
        return isRunning;
    }

    /////////////////////Transcieving/////////////////////////////////////
}