package com.example.arpits.sensorappv2;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Vibrator;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

/**
 * Created by arpits on 7/19/16.
 */
public class SensorActivity extends Activity implements SensorEventListener {
    public static float swRoll;
    public static float swPitch;
    public static float swAzimuth;


    public static SensorManager mSensorManager;
    public static Sensor accelerometer;
    public static Sensor magnetometer;
    public static Sensor gravitySensor;

    public static float[] mAccelerometer = null;
    public static float[] mGeomagnetic = null;
    public enum State {
        CHARGING, JUST_CHARGED, CHARGED, DONE, MISSED
    }
    private Timer timer;
    private TimerTask currentTask;
    private State state;
    private long startTime;
    private long minTimeDiff;
    private boolean screenTapped;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        gravitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        timer = new Timer();
        state = State.DONE;
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
        mSensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
        mSensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this, accelerometer);
        mSensorManager.unregisterListener(this, magnetometer);
        mSensorManager.unregisterListener(this, gravitySensor);
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
    public void screenTapped(View view) {
        screenTapped = true;
    }
    public void onSensorChanged(SensorEvent event) {
        //Right in here is where you put code to read the current sensor values and
        //update any views you might have that are displaying the sensor information
        //You'd get accelerometer values like this:
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            mAccelerometer = event.values;
        }

        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            mGeomagnetic = event.values;
        }

        TextView tvState = (TextView)findViewById(R.id.tvState);
        tvState.setText(state.toString());

        if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
            double x = event.values[0];
            double y = event.values[1];
            double z = event.values[2];
            TextView tvazi = (TextView)findViewById(R.id.tvazimuth);
            TextView tvpitch = (TextView)findViewById(R.id.tvpitch);
            TextView tvroll = (TextView)findViewById(R.id.tvroll);
            TextView tvTime = (TextView)findViewById(R.id.tvTime);

            tvazi.setText(x + "");
            tvpitch.setText(y + "");
            tvroll.setText(z + "");

            switch (state) {
                case MISSED:
                case DONE:
                    // handle transition first
                    if (y < -9.0) {
                        // move to charging
                        state = State.CHARGING;
                        getWindow().getDecorView().setBackgroundColor(Color.RED);
                        currentTask = new TimerTask() {
                            synchronized public void run() {
                                state = State.JUST_CHARGED;
                            }
                        };
                        timer.schedule(currentTask, 1000);
                    }
                    // other stuff (nothing really?)

                    break;
                case CHARGING:
                    // handle transition first
                    // if shaking happens, or not vertical enough, invalidate trial.
                    float[] a = mAccelerometer;
                    float accMagSq = 9;
                    if (a != null) {
                        accMagSq = a[0]*a[0] + a[1]*a[1] + a[2]*a[2];
                    }
                    if (y > -7.0 || accMagSq > 15*15 || accMagSq < 4*4) {
                        // go to done state, with color yellow.
                        currentTask.cancel();
                        state = State.DONE;
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
                    if (y > 0.0 && screenTapped) {
                        // move to done
                        long nowTime = System.currentTimeMillis();
                        state = State.DONE;
                        getWindow().getDecorView().setBackgroundColor(Color.BLUE);
                        tvTime.setText((nowTime - startTime) + "");
                    } else if (screenTapped) {
                        // move to missed
                        getWindow().getDecorView().setBackgroundColor(Color.YELLOW);
                        state = State.MISSED;
                    }
                    // other stuff (nothing really)
                    break;

            }
        }
        // make screenTapped false, so that same event doesn't run twice in switch
        screenTapped = false;
//        if (mAccelerometer != null && mGeomagnetic != null) {
//            float RR[] = new float[9];
//            float I[] = new float[9];
//            boolean success = SensorManager.getRotationMatrix(RR, I, mAccelerometer, mGeomagnetic);
//
//            if (success) {
//                float orientation[] = new float[3];
//                SensorManager.getOrientation(RR, orientation);
//                // at this point, orientation contains the azimuth(direction), pitch and roll values.
//                double azimuth = 180 * orientation[0] / Math.PI;
//                double pitch = 180 * orientation[1] / Math.PI;
//                double roll = 180 * orientation[2] / Math.PI;
//
//                TextView tvazi = (TextView)findViewById(R.id.tvazimuth);
//                TextView tvpitch = (TextView)findViewById(R.id.tvpitch);
//                TextView tvroll = (TextView)findViewById(R.id.tvroll);
//                TextView tvTime = (TextView)findViewById(R.id.tvTime);
//
//                tvazi.setText(azimuth + "");
//                tvpitch.setText(pitch + "");
//                tvroll.setText(roll + "");
//
//                int newState = state;
//                if (pitch > 75)
//                    newState = 0;
//                else if (pitch < 10)
//                    newState = 1;
//
//                if (state != newState) {
//                    state = newState;
//                    if (state == 0)
//                        startTime = System.currentTimeMillis();
//                    else if (state == 1) {
//                        long nowTime = System.currentTimeMillis();
//                        long diff = nowTime - startTime;
//                        tvTime.setText(diff + "");
//                        minTimeDiff = Math.min(minTimeDiff, diff);
//                    }
//                    getWindow().getDecorView().setBackgroundColor(colors[state]);
//
//                }
//
//            }
//        }
//        float mSensorX, mSensorY;
//        mSensorX = event.values[0];
//        mSensorY = event.values[1];
//        int newState = state;
//        if (mSensorY < -8.5)
//            newState = 0;
//        else if (mSensorY > 0)
//            newState = 1;
//        if (newState != state) {
//            state = newState;
//            getWindow().getDecorView().setBackgroundColor(colors[state]);
//        }
//
//        TextView tvx = (TextView)findViewById(R.id.xAccTextView);
//        TextView tvy = (TextView)findViewById(R.id.yAccTextView);
//        tvx.setText(mSensorX+"");
//        tvy.setText(mSensorY+"");
//        switch (mDisplay.getRotation()) {
//            case Surface.ROTATION_0:
//                mSensorX = event.values[0];
//                mSensorY = event.values[1];
//                break;
//            case Surface.ROTATION_90:
//                mSensorX = -event.values[1];
//                mSensorY = event.values[0];
//                break;
//            case Surface.ROTATION_180:
//                mSensorX = -event.values[0];
//                mSensorY = -event.values[1];
//                break;
//            case Surface.ROTATION_270:
//                mSensorX = event.values[1];
//                mSensorY = -event.values[0];
//        }
    }
}

