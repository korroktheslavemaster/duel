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
import android.os.Vibrator;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

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
        READY, CHARGING, JUST_CHARGED, CHARGED, DONE, MISSED_TOO_LOW, MISSED_TOO_HIGH, PREMATURE, NO_PAIRED_DEVICE,
        WAITING_SERVER, WAITING_CLIENT,
        ERROR
    }
    private String UUIDString = "b57405ce-4f7f-11e6-beb8-9e71128cae77";
    private Timer timer;
    private TimerTask currentTask;
    private State state;
    private long startTime;
    private long minTimeDiff;
    private boolean screenTapped;
    private boolean otherReady;
    private double x, y, z;
    private TextView tvazi, tvpitch, tvroll, tvTime;
    public static int BLUETOOTH_REQUEST=1;
    private BluetoothDevice pairedDevice;
    private boolean amMaster;
    private BluetoothServerSocket mServerSocket;
    private BluetoothSocket socket;
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
        state = State.DONE;
        setContentView(R.layout.activity_main);
        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gravitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        tvazi = (TextView)findViewById(R.id.tvazimuth);
        tvpitch = (TextView)findViewById(R.id.tvpitch);
        tvroll = (TextView)findViewById(R.id.tvroll);
        tvTime = (TextView)findViewById(R.id.tvTime);

        if (mBluetoothAdapter == null) {
            // Device does not support Bluetooth
        }
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, BLUETOOTH_REQUEST);
        }
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        amMaster = false;
        // If there are paired devices
        if (pairedDevices.size() == 0) {
            state = State.NO_PAIRED_DEVICE;
        } else {
            // get first item from set
            for( BluetoothDevice device: pairedDevices) {
                pairedDevice = device;
                ((TextView)findViewById((R.id.tvDevice))).setText(pairedDevice.getName());
                break;
            }
            amMaster = false;
            String myBTID = android.provider.Settings.Secure.getString(this.getApplicationContext().getContentResolver(), "bluetooth_address");
            tvTime.setText(myBTID);
            if (myBTID.compareTo(pairedDevice.getAddress()) > 0) {
                amMaster = true;
                // do master init stuff
                BluetoothServerSocket tmp = null;
                try {
                    // MY_UUID is the app's UUID string, also used by the client code
                    tmp = mBluetoothAdapter.listenUsingRfcommWithServiceRecord("Duel", UUID.fromString(UUIDString));
                } catch (IOException e) { }
                mServerSocket = tmp;
                state = State.WAITING_SERVER;
            } else {
                // do slave init stuff
                BluetoothSocket tmp = null;
                try {
                    // MY_UUID is the app's UUID string, also used by the server code
                    tmp = pairedDevice.createRfcommSocketToServiceRecord(UUID.fromString(UUIDString));
                } catch (IOException e) { }
                socket = tmp;
                mBluetoothAdapter.cancelDiscovery();
                state = State.WAITING_CLIENT;
            }

        }
        otherReady = false;
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
            case WAITING_SERVER:
                // if we're here, we're definitely master, right?
                try {
                    socket = mServerSocket.accept();
                } catch (IOException e) {
                    break;
                }
                if (socket != null) {
                    // yay
                    try {
                        mServerSocket.close();
                    } catch(IOException e) {}
                    state = State.DONE;
                }
                break;
            case WAITING_CLIENT:
                try {
                    // Connect the device through the socket. This will block
                    // until it succeeds or throws an exception
                    socket.connect();
                    state = State.DONE;
                } catch (IOException connectException) {
                    // Unable to connect; close the socket and get out
                    try {
                        socket.close();
                    } catch (IOException closeException) { }

                    socket = null;
                    BluetoothSocket tmp = null;
                    try {
                        // MY_UUID is the app's UUID string, also used by the server code
                        tmp = pairedDevice.createRfcommSocketToServiceRecord(UUID.fromString(UUIDString));
                    } catch (IOException e) { }
                    socket = tmp;
                    return;
                }

                break;
            case NO_PAIRED_DEVICE:
                // maybe should look for pairing?
                break;
            case PREMATURE:
            case MISSED_TOO_LOW:
            case MISSED_TOO_HIGH:
            case DONE:
                // handle transition first
                if (y < -9.0) {
                    state = State.READY;
                }
                // other stuff (nothing really?)

                break;
            case READY:
                // handle transition first
                // if angle changes, move back to done.
                if (y > -7.0) {
                    state = State.DONE;
                    getWindow().getDecorView().setBackgroundColor(Color.BLUE);
                }
                // if we get signal, move to charging
                state = State.CHARGING;
                getWindow().getDecorView().setBackgroundColor(Color.RED);
                currentTask = new TimerTask() {
                    synchronized public void run() {
                        state = State.JUST_CHARGED;
                    }
                };
                // shootout will begin in 1-3 seconds. keep guessing
                timer.schedule(currentTask, new Random().nextInt(3000-1000)+1000);
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
}

