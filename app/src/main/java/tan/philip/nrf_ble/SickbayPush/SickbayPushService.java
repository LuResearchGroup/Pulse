package tan.philip.nrf_ble.SickbayPush;

import static android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY;
import static tan.philip.nrf_ble.NotificationHandler.CHANNEL_ID;
import static tan.philip.nrf_ble.NotificationHandler.FOREGROUND_SERVICE_NOTIFICATION_ID_SICKBAY;
import static tan.philip.nrf_ble.SickbayPush.SickbayMessage.convertPacketToJSONString;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import tan.philip.nrf_ble.BLE.BLEDevices.BLEDevice;
import tan.philip.nrf_ble.R;
import tan.philip.nrf_ble.GraphScreen.GraphActivity;
import tan.philip.nrf_ble.Events.Sickbay.SickbayQueueEvent;
import tan.philip.nrf_ble.Events.Sickbay.SickbayReinitializeEvent;
import tan.philip.nrf_ble.Events.Sickbay.SickbaySendFloatsEvent;

public class SickbayPushService extends Service {
    private static final String TAG = "SickbayPushService";
    private static final String WIFI_TAG = "SICKBAY_WIFI_LOCK";

    private static final String DEFAULT_IP_ADDRESS = "192.168.50.147";
    private static final String DEFAULT_WEB_SOCKET_URL = "http://" + DEFAULT_IP_ADDRESS + ":3001";
    private String webSocketURL = DEFAULT_WEB_SOCKET_URL;

    private final String DEFAULT_BED_NAME = "BED001";
    private String bedName = DEFAULT_BED_NAME;

    // Binder given to clients
    private final IBinder binder = new LocalBinder();

    //Might be better to be a hashmap. However, there are 2 keys (NS and instanceID), which is messy.
    //Key is the instanceID.
    //private final HashMap<Integer, SickbayQueue> dataQueues = new HashMap<>();
    private Handler mHandler;
    private boolean queuesInitialized = false;

    private long lastPushTime = 0;

    //Wifi Manager. Used for WiFi Lock, may be good to have in separate service dedicated for
    //handling WiFi.
    WifiManager mWifiManager;// = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
    WifiManager.WifiLock mWifiLock;// = mWifiManager.createWifiLock(WIFI_MODE_FULL_LOW_LATENCY, WIFI_TAG);

    //Partial wake lock. Keeps the CPU running while we are actively streaming to the socket so
    //the Socket.IO heartbeat/emit threads keep firing when the screen is off / device is dozing.
    //Acquired and released in lockstep with the WiFi lock so it can never leak.
    private static final String WAKE_TAG = "Pulse:SickbayPushWakeLock";
    private PowerManager.WakeLock mWakeLock;
    public class LocalBinder extends Binder {
        public SickbayPushService getService() {
            // Return this instance of SickbayPushService so clients can call public methods
            return SickbayPushService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //Promote to a real foreground service so the OS keeps it (and the network/wake locks)
        //alive when the screen is off. Must call startForeground() promptly after being started
        //with startForegroundService().
        startForegroundNotification();

        //If the system kills us under memory pressure while streaming, recreate the service.
        return START_STICKY;
    }

    private void startForegroundNotification() {
        PendingIntent contentPI = PendingIntent.getActivity(
                this, 0, new Intent(this, GraphActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT |
                        (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.heartrate)
                .setContentTitle("Streaming to Sickbay")
                .setContentText("Forwarding device data to " + webSocketURL)
                .setOngoing(true)
                .setContentIntent(contentPI);

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FOREGROUND_SERVICE_NOTIFICATION_ID_SICKBAY, nb.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FOREGROUND_SERVICE_NOTIFICATION_ID_SICKBAY, nb.build());
        }
    }

    @Override
    public void onCreate() {
        initializeSickbaySettings();

        //Register on EventBus
        EventBus.getDefault().register(this);
    }

    @Override
    public void onDestroy() {
        disconnectSocket();
        releaseStreamingLocks();

        //Drop foreground status / notification.
        stopForeground(true);

        //Unregister from EventBus
        EventBus.getDefault().unregister(this);
    }

    private void initializeSickbaySettings() {
        readSickbaySettings(); //TO DO: Check if the IP address and Bed names are valid

        Log.d(TAG, "Initializing Sockets.");
        initializeSocket();

        mHandler = new Handler();
        connectSocket();

        //Release any previously held locks before re-creating them (this method can be
        //re-entered via SickbayReinitializeEvent) so we never leak a held lock.
        releaseStreamingLocks();

        //No need to have WiFi lock on to start probably
        mWifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
        mWifiLock = mWifiManager.createWifiLock(WIFI_MODE_FULL_LOW_LATENCY, WIFI_TAG);

        //Create (but do not yet acquire) the partial wake lock.
        PowerManager pm = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG);
        mWakeLock.setReferenceCounted(false);

        releaseWifiLock();
    }

    //Reads the sickbay settings from local memory
    private static final String BASE_DIR_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "Pulse_Data";
    private void readSickbaySettings() {
        //Read the sickbay IP first
        String filePath = BASE_DIR_PATH + File.separator + "sickbayIP.txt";

        try {
            FileReader fileReader = new FileReader(filePath);

            String sickbayIP = "";
            int i;
            while ((i = fileReader.read()) != -1) {
                sickbayIP += (char)i;
            }
            Log.d(TAG, "Sickbay IP set to:" + sickbayIP);

            webSocketURL = "https://" + sickbayIP + ":3001";

            fileReader.close();
        }
        catch (Exception e) {
            File file = new File(filePath);
            try {
                file.createNewFile();
                FileWriter fileWriter = new FileWriter(file);
                fileWriter.write(DEFAULT_IP_ADDRESS);
                fileWriter.close();

                Log.e(TAG, "Made sickbay settings file.", e);

            } catch(Exception f) {
                Log.e(TAG, "Could not make sickbay settings files.", e);
            }
        }

        //Read the sickbay bed ID
        filePath = BASE_DIR_PATH + File.separator + "sickbayBedID.txt";

        try {
            FileReader fileReader = new FileReader(filePath);

            String bedID = "";
            int i;
            while ((i = fileReader.read()) != -1) {
                bedID += (char)i;
            }
            Log.d(TAG, "Sickbay Bed ID set to:" + bedID);

            bedName = bedID;

            fileReader.close();
        }
        catch (Exception e) {
            File file = new File(filePath);
            try {
                file.createNewFile();
                FileWriter fileWriter = new FileWriter(file);
                fileWriter.write(DEFAULT_BED_NAME);
                fileWriter.close();
                Log.e(TAG, "Made sickbay settings file.", e);

            } catch(Exception f) {
                Log.e(TAG, "Could not make sickbay settings files.", e);
            }
        }
    }

    // ////////////////////////////////Queue Functions////////////////////////////////////////////
    @Subscribe
    public void reinitalizeSickbaySettings(SickbayReinitializeEvent event) {
        initializeSickbaySettings();
    }

    @Subscribe
    public void sendSickbayFrameEvent(SickbaySendFloatsEvent event) {
        Long curTime = System.currentTimeMillis();

        JSONObject message = convertPacketToJSONString(curTime, event.getData(), event.getBLEDevice(), bedName);

        //Attempt to send the data
        attemptSend(message);
    }

    /*
    @Subscribe
    public void addToQueueEvent(SickbayQueueEvent event) {
        if(queuesInitialized && dataQueues != null)
            dataQueues.get(event.getInstanceId()).addToQueue(event.getData());
    }

    public void initializeQueues(ArrayList<BLEDevice> devices) {
        for (BLEDevice d : devices) {
            //To do: Unique namespace
            //WARNING. UNIQUE ID IS HARD CODED
            //dataQueues.put(d.getUniqueId(), new SickbayQueue(bedName, "TATTOOWAVE", d.getUniqueId(), d.getNotificationFrequency()));
            dataQueues.put(0, new SickbayQueue(bedName, "TATTOOWAVE", d.getUniqueId(), d.getNotificationFrequency()));
        }
        queuesInitialized = true;
    }

    private synchronized void pushQueue(long timestamp) {
        //Consolidate queue to a single frame and push the frame

        //For every instance ID and namespace, push the respective queue.
        for (int instanceID : dataQueues.keySet()) {
            SickbayQueue q = dataQueues.get(instanceID);
            //Reformat data in queue into string.
            JSONObject message = q.convertQueueToJSONString(timestamp);

            //Attempt to send the data
            attemptSend(message);
        }
    }

     */

    ///////////////////////////////////////////////////////////////////////////////////////////////
    /////////////////////Functions for sockets/////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////////////////////////
    private Socket mSocket;

    void initializeSocket() {
        try {
            IO.Options options = new IO.Options();
            SocketSSL.set(options);
            mSocket = IO.socket(webSocketURL, options);
            Log.d(TAG, "Socket object created.");
        } catch (URISyntaxException e) {
            Log.e("Error URI", String.valueOf(e));
            throw new RuntimeException(e);
        }
    }

    //Will need a recovery
    void connectSocket() {
        //Listen for events using onNewMessage (Emitter.Listener)
        mSocket.on("new message", onNewMessage);
        mSocket.on(Socket.EVENT_CONNECT_ERROR, onConnectError);
        mSocket.on(Socket.EVENT_CONNECT, onConnection);

        mSocket.connect();
        Log.d(TAG,"Attempted to connect socket.");
    }

    void disconnectSocket() {
        mSocket.disconnect();
        mSocket.off("new message", onNewMessage);
        mSocket.off(Socket.EVENT_CONNECT_ERROR, onConnectError);
        mSocket.off(Socket.EVENT_CONNECT, onConnection);
        releaseWifiLock();
    }

    private void attemptSend(JSONObject message) {
        //If the message is empty, don't send a packet.
        if (message == null) {
            return;
        }

        Long curTime = System.currentTimeMillis();
//        if(lastPushTime != 0)
//            Log.d(TAG, "Push (dt = " + (curTime - lastPushTime) + " ms)");
        lastPushTime = curTime;


        if (mSocket.connected()) {
            mSocket.emit("NewWebsocketData_serverside_timestamp", message);
        }
    }

    //For listening. Currently we do not expect to receive packets, so it doesn't do anything.
    private final Emitter.Listener onNewMessage = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            Runnable listenSocket = new Runnable() {
                @Override
                public void run() {
                    JSONObject data = (JSONObject) args[0];
                    String testMessage;
                    try {
                        // Replace with whatever we expect to recieve
                        testMessage = data.getString("test");
                    } catch (JSONException e) {
                        return;
                    }
                    // Do something with our testMessage
                }
            };
        }
    };

    //Handler for server connection error
    private final Emitter.Listener onConnectError = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            mSocket.connect();
            Log.e(TAG, "Socket connection had an error (" + args[0] +")");
        }
    };

    //Handler for connection event
    private final Emitter.Listener onConnection = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            Log.d(TAG, "Socket connected!");

            acquireWifiLock();
        }
    };

    //Acquire the WiFi + partial wake locks together. Called when the socket connects, i.e. when
    //the data stream actually starts. Idempotent (locks are not reference counted).
    private void acquireStreamingLocks() {
        if (mWifiLock != null && !mWifiLock.isHeld())
            mWifiLock.acquire();
        if (mWakeLock != null && !mWakeLock.isHeld())
            mWakeLock.acquire();
    }

    //Release the WiFi + partial wake locks together. Called on socket disconnect and onDestroy so
    //the CPU is never held awake once we stop streaming (prevents permanent battery drain).
    private void releaseStreamingLocks() {
        if (mWifiLock != null && mWifiLock.isHeld())
            mWifiLock.release();
        if (mWakeLock != null && mWakeLock.isHeld())
            mWakeLock.release();
    }

    private void releaseWifiLock() {
        releaseStreamingLocks();
    }

    private void acquireWifiLock() {
        acquireStreamingLocks();
    }

}