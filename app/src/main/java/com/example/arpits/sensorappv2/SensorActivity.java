package com.example.arpits.sensorappv2;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Handler;
import android.os.Message;
import android.os.Looper;
import android.os.Parcelable;
import android.os.Vibrator;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Date;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.io.*;
/**
 * Created by arpits on 7/19/16.
 */
public class SensorActivity extends Activity implements SensorEventListener {


    public static SensorManager mSensorManager;
    public static Sensor accelerometer;
    public static Sensor gravitySensor;
    public static BluetoothAdapter mBluetoothAdapter;
    public static double MISS_LOW_THRESH = -2.0;
    public static double MISS_HIGH_THRESH = 3.0;

    public static float[] mAccelerometer = null;
    public enum State {
        READY, CHARGING, JUST_CHARGED, CHARGED, DONE, MISSED_TOO_LOW, MISSED_TOO_HIGH, PREMATURE,
        WAITING_NFC, GOT_SOCKET,
        ERROR
    }
    public enum MessageType {
        READY, NOT_READY, MISSED, PREMATURE, SHOT, GOCHARGE
    }

    static MessageType[] mt = MessageType.values();
    private String UUIDString = "b57405ce-4f7f-11e6-beb8-9e71128cae77";
    private Timer timer;
    private TimerTask currentTask;
    private State state;
    private long startTime;
    private long minTimeDiff;
    private boolean screenTapped;
    private double x, y, z;
    private TextView tvazi, tvpitch, tvroll, tvTime;
    public static int BLUETOOTH_REQUEST=1;
    private BluetoothDevice pairedDevice;
    private boolean amMaster;
    private BluetoothServerSocket mServerSocket;
    private BluetoothSocket socket;
    private Handler mHandler;
    private ConnectedThread cThread;
    private Thread thread;
    // variables for comm/gameplay?
    private boolean isOtherReady = false;

    private NfcAdapter mNfcAdapter;
    State otherState;

    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        if (requestCode == BLUETOOTH_REQUEST) {
            if (resultCode == RESULT_CANCELED)
                this.finishAffinity();
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gravitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mBluetoothAdapter == null) {
            // Device does not support Bluetooth
        }
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, BLUETOOTH_REQUEST);
        }

        tvTime = (TextView)findViewById(R.id.tvTime);
        String myBTID = android.provider.Settings.Secure.getString(
                this.getApplicationContext().getContentResolver(), "bluetooth_address");
        tvTime.setText(myBTID);

        state = State.WAITING_NFC;
        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(getIntent().getAction())) {
            // not started by nfc
            mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
            NdefRecord uriRecord = new NdefRecord(
                    NdefRecord.TNF_EXTERNAL_TYPE ,
                    ("com.example:duelgame/" + myBTID).getBytes(Charset.forName("US-ASCII")),
                    new byte[0], new byte[0]);
            NdefMessage message = new NdefMessage(uriRecord);
            mNfcAdapter.setNdefPushMessage(message, this);
            // we act as server
            BluetoothServerSocket tmp = null;
            try {
                // MY_UUID is the app's UUID string, also used by the client code
                tmp = mBluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                        "Duel", UUID.fromString(UUIDString));
            } catch (IOException e) { }
            mServerSocket = tmp;
            // should start a thread where accept() will be called.
            thread = new Thread() {
                @Override
                public void run() {
                    try {
                        socket = mServerSocket.accept();
                    } catch (IOException e) {
                        state = SensorActivity.State.ERROR;
                    }
                    if (socket != null) {
                        // yay
                        try {
                            mServerSocket.close();
                        } catch(IOException e) {}
                        state = SensorActivity.State.GOT_SOCKET;
                    }
                }
            };
            thread.start();
        } else {
            // we got started by nfc
//            String btID = "";
//            Parcelable[] rawMsgs = getIntent().getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
//            if (rawMsgs != null) {
//                NdefMessage msgs[] = new NdefMessage[rawMsgs.length];
//                for (int i = 0; i < rawMsgs.length; i++) {
//                    msgs[i] = (NdefMessage) rawMsgs[i];
//                    for (NdefRecord rec: msgs[i].getRecords()) {
//                        btID += rec.toString();
//                    }
//                }
//            }
            String btID = getIntent().getDataString();
            String[] strs = btID.split("/");
            btID = strs[strs.length - 1];
            pairedDevice = mBluetoothAdapter.getRemoteDevice(btID);
            BluetoothSocket tmp = null;
            try {
                // MY_UUID is the app's UUID string, also used by the server code
                tmp = pairedDevice.createInsecureRfcommSocketToServiceRecord(
                        UUID.fromString(UUIDString));
            } catch (IOException e) {
                state = SensorActivity.State.ERROR;
            }
            socket = tmp;
            mBluetoothAdapter.cancelDiscovery();
            // should start a thread where connect() will be called.
            thread = new Thread() {
                @Override
                public void run() {
                    try {
                        // Connect the device through the socket. This will block
                        // until it succeeds or throws an exception
                        socket.connect();
                        state = SensorActivity.State.GOT_SOCKET;
                    } catch (IOException connectException) {
                        // Unable to connect; close the socket and get out
                        try {
                            socket.close();
                        } catch (IOException closeException) { }
                        state = SensorActivity.State.ERROR;
                        socket = null;

                    }
                }
            };
            thread.start();
        }

        mHandler = new Handler() {
            /*
             * handleMessage() defines the operations to perform when
             * the Handler receives a new Message to process.
             */
            @Override
            public void handleMessage(Message inputMessage) {
                long val = (Long)inputMessage.obj;
                switch(mt[inputMessage.what]) {
                    case GOCHARGE:
                        // move to charging state (we must be slave)
                        state = State.CHARGING;
                        getWindow().getDecorView().setBackgroundColor(Color.RED);
                        currentTask = new TimerTask() {
                            synchronized public void run() {
                                state = State.JUST_CHARGED;
                            }
                        };
                        // shootout will begin at the time master sent
                        long shootoutTime = val;
                        timer.schedule(currentTask, new Date(shootoutTime) );
                        break;
                    case READY:
                        isOtherReady = true;
                        break;
                    case NOT_READY:
                        isOtherReady = false;
                        break;
                    case MISSED:
                        break;
                    case PREMATURE:
                        break;
                    case SHOT:
                        break;
                }
            }
        };
        isOtherReady = false;
        otherState = State.DONE;


        tvazi = (TextView)findViewById(R.id.tvazimuth);
        tvpitch = (TextView)findViewById(R.id.tvpitch);
        tvroll = (TextView)findViewById(R.id.tvroll);




        timer = new Timer();

        minTimeDiff = 99999;
        screenTapped = false;


    }

    public SensorActivity() {
        mSensorManager = null;
        mAccelerometer = null;
    }

    @Override
    protected void onResume() {
        super.onResume();

        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        mSensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this, accelerometer);
        mSensorManager.unregisterListener(this, gravitySensor);
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            screenTapped = true;
            loop();
        }
        return true;
    }

    public void loop() {
        switch (state) {
            case ERROR:
                break;

            case GOT_SOCKET:
                cThread = new ConnectedThread(socket);
                cThread.start();
                state = State.DONE;

            case WAITING_NFC:
                // if we're here, we're definitely master, right?

                break;


            case PREMATURE:
            case MISSED_TOO_LOW:
            case MISSED_TOO_HIGH:
            case DONE:
                // handle transition first
                if (y < -9.0) {
                    state = State.READY;
                    // send the message too! (only need to actually do this if we not master)
                    cThread.write(MessageType.READY, 0);
                }
                // other stuff (nothing really?)

                break;
            case READY:
                // handle transition first
                // if angle changes, move back to done.
                if (y > -7.0) {
                    state = State.DONE;
                    getWindow().getDecorView().setBackgroundColor(Color.BLUE);
                    // send the message too
                    cThread.write(MessageType.NOT_READY, 0);
                }

                // if we get signal that other is ready, and we are master, move to charging
                if (isOtherReady && amMaster) {
                    state = State.CHARGING;
                    getWindow().getDecorView().setBackgroundColor(Color.RED);
                    currentTask = new TimerTask() {
                        synchronized public void run() {
                            state = State.JUST_CHARGED;
                        }
                    };
                    // shootout will begin in 1-3 seconds. keep guessing
                    long shootoutTime = (System.currentTimeMillis()
                        + new Random().nextInt(3000-1000)+1000);
                    // send the message to other phone
                    cThread.write(MessageType.GOCHARGE, shootoutTime);
                    timer.schedule(currentTask, new Date(shootoutTime) );
                }

                break;

            case CHARGING:
                // handle transition first
                // if shaking happens, or not vertical enough, invalidate trial.
                float[] a = mAccelerometer;
                float accMagSq = 9;
                if (a != null) {
                    accMagSq = a[0]*a[0] + a[1]*a[1] + a[2]*a[2];
                }
                // removing acceleration condition, seems pointless
                if (y > -7.0 /*|| accMagSq > 15*15 || accMagSq < 4*4*/) {
                    // go to premature state, with color yellow.
                    currentTask.cancel();
                    state = State.PREMATURE;
                    getWindow().getDecorView().setBackgroundColor(Color.YELLOW);
                }
                // other stuff (nothing really)
                break;
            case JUST_CHARGED:
                // do cleanup and move to charged
                getWindow().getDecorView().setBackgroundColor(Color.GREEN);
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                v.vibrate(100);
                startTime = System.currentTimeMillis();
                state = State.CHARGED;
                break;
            case CHARGED:
                // handle transition first
                // as horizontal is crossed, transition over to Done
                // and print the shot time! (no tapping for now.)
                // changing this to an angle threshold around 0
                if (y > MISS_LOW_THRESH && y < MISS_HIGH_THRESH && screenTapped) {
                    // move to done
                    long nowTime = System.currentTimeMillis();
                    state = State.DONE;
                    getWindow().getDecorView().setBackgroundColor(Color.BLUE);
                    tvTime.setText((nowTime - startTime) + "");
                } else if (screenTapped) {
                    // move to missed
                    getWindow().getDecorView().setBackgroundColor(Color.YELLOW);
                    state = y <= MISS_LOW_THRESH? State.MISSED_TOO_LOW: State.MISSED_TOO_HIGH;
                }
                // other stuff (nothing really)
                break;

        }
        // make screenTapped false, so that same event doesn't run twice in switch
        screenTapped = false;
    }

    public void onSensorChanged(SensorEvent event) {
        //Right in here is where you put code to read the current sensor values and
        //update any views you might have that are displaying the sensor information
        //You'd get accelerometer values like this:
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            mAccelerometer = event.values;
        }

        TextView tvState = (TextView)findViewById(R.id.tvState);
        tvState.setText(state.toString());

        if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
            x = event.values[0];
            y = event.values[1];
            z = event.values[2];

            tvazi.setText(x + "");
            tvpitch.setText(y + "");
            tvroll.setText(z + "");
            loop();

        }

    }


    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) { }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];  // buffer store for the stream
            int bytes; // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);
                    // Send the obtained bytes to the UI activity
                    // deserialize the message: just split on ' ' and convert to int.
                    // set first param to 'what', second to 'arg1'
                    String str = new String(Arrays.copyOfRange(buffer, 0, bytes), "ascii");
                    Log.d("stream string", str + ", " + str.length());
                    // DEBUG: don't do anything here, just print out whatever the ufck
                    // ur receiving.
                    String[] strs = str.split(" ");
                    assert(strs.length == 2);
                    mHandler.obtainMessage(Integer.parseInt(strs[0]), Long.parseLong(strs[1]))
                            .sendToTarget();
                } catch (IOException e) {
                    break;
                }
            }
        }

        /* Call this from the main activity to send data to the remote device */
        public void write(MessageType type, long val) {
            try {
                // serialize the message: make string with two ints
                // first is MessageType ordinal, second is arg1
                String str = type.ordinal() + " " + val;
                byte[] byteArr = str.getBytes("ascii");
                Log.d("write stream", new String(byteArr, "ascii") + ", " +
                        byteArr.length);
                mmOutStream.write(byteArr, 0, byteArr.length);
            } catch (IOException e) { }
        }

        /* Call this from the main activity to shutdown the connection */
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }
}

