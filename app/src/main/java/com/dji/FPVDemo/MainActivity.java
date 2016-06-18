package com.dji.FPVDemo;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.TextureView.SurfaceTextureListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.google.android.gms.appindexing.Action;
import com.google.android.gms.appindexing.AppIndex;
import com.google.android.gms.common.api.GoogleApiClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;

import dji.sdk.Battery.DJIBattery;
import dji.sdk.Camera.DJICamera;
import dji.sdk.Camera.DJICamera.CameraReceivedVideoDataCallback;
import dji.sdk.Camera.DJIMedia;
import dji.sdk.Codec.DJICodecManager;
import dji.sdk.FlightController.DJIFlightControllerDataType;
import dji.sdk.FlightController.DJIFlightControllerDelegate;
import dji.sdk.Gimbal.DJIGimbal;
import dji.sdk.Products.DJIAircraft;
import dji.sdk.base.DJIBaseComponent.DJICompletionCallback;
import dji.sdk.base.DJIBaseProduct;
import dji.sdk.base.DJIBaseProduct.Model;
import dji.sdk.base.DJIError;
import dji.sdk.Camera.DJICameraSettingsDef.CameraMode;
import dji.sdk.Camera.DJICameraSettingsDef.CameraShootPhotoMode;
import dji.sdk.RemoteController.*;

import static android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK;


public class MainActivity extends Activity implements SurfaceTextureListener, OnClickListener {


    private static final String TAG = MainActivity.class.getName();

    protected CameraReceivedVideoDataCallback mReceivedVideoDataCallBack = null;

    // Codec for video live view
    protected DJICodecManager mCodecManager = null;
    protected TextView mConnectStatusTextView;

    protected TextureView mVideoSurface = null;
    private Button mCaptureBtn, mShootPhotoModeBtn, mRecordVideoModeBtn;
    private ToggleButton mRecordBtn;
    private TextView recordingTime;
    protected PowerManager mPowerManager;
    protected PowerManager.WakeLock mWakeLock;
    protected TextView mTextViewPower;
    protected TextView mTextViewExp;


    //added for pose display and storage;
    protected static final int CHANGE_TEXT_VIEW = 0;
    protected static final int RECORD_BUTTON_DOWN=1;
    protected static final int BATTERY_STATUS_CHANGED=2;
    protected static final int EXPOSURE_STATUS_CHANGED=3;
    protected StringBuffer mStringBuffer;
    protected StringBuffer mStringBufferPower;
    protected StringBuffer mStringBufferExp;
    protected TextView mTextViewOSD;
    protected Handler mHandler = new Handler(new Handler.Callback() {

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case CHANGE_TEXT_VIEW:
                    mTextViewOSD.setText(mStringBuffer.toString());
                    break;
                case RECORD_BUTTON_DOWN:
                    mRecordBtn.performClick();
                    break;
                case BATTERY_STATUS_CHANGED:
                    mTextViewPower.setText(mStringBufferPower.toString());
                    break;
                case EXPOSURE_STATUS_CHANGED:
                    String evString=mStringBufferExp.toString();
                    evString=evString.replaceAll("ShutterSpeed1_","ShutterSpeed1/");
                    mTextViewExp.setText(evString);
                    break;
                default:
                    break;
            }
            return false;
        }
    });

    protected File mPoseOutputFile;
    protected FileOutputStream mPoseOutputStream;
    protected DJIMedia newMovMedia=null;
    protected boolean isRecording=false;
    long startTime = 0;
    protected File mFlyLogDir;
    protected StringBuffer outMessage;

    /**
     * ATTENTION: This was auto-generated to implement the App Indexing API.
     * See https://g.co/AppIndexing/AndroidStudio for more information.
     */
    private GoogleApiClient client;


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        mFlyLogDir=getExternalFilesDir("fly_log");
        //showToast(mFlyLogDir.getAbsolutePath());
        if (!mFlyLogDir.exists())
        {

            mFlyLogDir.mkdirs();
        }

        mPoseOutputFile=null;

        // When the compile and target version is higher than 22, please request the
        // following permissions at runtime to ensure the
        // SDK work well.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.VIBRATE,
                            Manifest.permission.INTERNET, Manifest.permission.ACCESS_WIFI_STATE,
                            Manifest.permission.WAKE_LOCK, Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
                            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.SYSTEM_ALERT_WINDOW,
                            Manifest.permission.READ_PHONE_STATE,
                    }
                    , 1);
        }

        setContentView(R.layout.activity_main);

        initUI();

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataCallBack = new CameraReceivedVideoDataCallback() {

            @Override
            public void onResult(byte[] videoBuffer, int size) {
                if (mCodecManager != null) {
                    // Send the raw H264 video data to codec manager for decoding
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                } else {
                    Log.e(TAG, "mCodecManager is null");
                }
            }
        };

        DJICamera camera = FPVDemoApplication.getCameraInstance();

        if (camera != null) {
            camera.setDJICameraUpdatedSystemStateCallback(new DJICamera.CameraUpdatedSystemStateCallback() {
                @Override
                public void onResult(DJICamera.CameraSystemState cameraSystemState) {
                    if (null != cameraSystemState) {

                        int recordTime = cameraSystemState.getCurrentVideoRecordingTimeInSeconds();
                        int minutes = (recordTime % 3600) / 60;
                        int seconds = recordTime % 60;

                        final String timeString = String.format("%02d:%02d", minutes, seconds);
                        final boolean isVideoRecording = cameraSystemState.isRecording();

                        MainActivity.this.runOnUiThread(new Runnable() {

                            @Override
                            public void run() {

                                recordingTime.setText(timeString);

                                /*
                                 * Update recordingTime TextView visibility and mRecordBtn's check state
                                 */
                                if (isVideoRecording) {
                                    recordingTime.setVisibility(View.VISIBLE);
                                } else {
                                    recordingTime.setVisibility(View.INVISIBLE);
                                }
                            }
                        });
                    }
                }
            });



        }


        mPowerManager=(PowerManager)getSystemService(Context.POWER_SERVICE);
        mWakeLock=mPowerManager.newWakeLock(SCREEN_BRIGHT_WAKE_LOCK,"Geranimo!");

        mWakeLock.acquire();


        // Register the broadcast receiver for receiving the device connection's changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(FPVDemoApplication.FLAG_CONNECTION_CHANGE);
        registerReceiver(mReceiver, filter);


        mStringBuffer = new StringBuffer();
        mStringBufferPower = new StringBuffer();
        mStringBufferExp = new StringBuffer();
        outMessage=new StringBuffer();

        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        client = new GoogleApiClient.Builder(this).addApi(AppIndex.API).build();
    }

    protected BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            updateTitleBar();
            onProductChange();
        }

    };

    private void updateTitleBar() {
        if (mConnectStatusTextView == null) return;
        boolean ret = false;
        DJIBaseProduct product = FPVDemoApplication.getProductInstance();
        if (product != null) {
            if (product.isConnected()) {
                //The product is connected
                mConnectStatusTextView.setText(FPVDemoApplication.getProductInstance().getModel() + " Connected");
                ret = true;
            } else {
                if (product instanceof DJIAircraft) {
                    DJIAircraft aircraft = (DJIAircraft) product;
                    if (aircraft.getRemoteController() != null && aircraft.getRemoteController().isConnected()) {
                        // The product is not connected, but the remote controller is connected
                        mConnectStatusTextView.setText("only RC Connected");
                        ret = true;
                    }
                }
            }
        }

        if (!ret) {
            // The product or the remote controller are not connected.
            mConnectStatusTextView.setText("Disconnected");
        }
    }

    protected void onProductChange() {
        initPreviewer();
        initStatusPreviewer();
        initRecord();

    }

    @Override
    public void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();
        initPreviewer();
        updateTitleBar();
        mWakeLock.acquire();
        if (mVideoSurface == null) {
            Log.e(TAG, "mVideoSurface is null");
        }
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");
        uninitPreviewer();
        mWakeLock.release();
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.e(TAG, "onStop");
        super.onStop();
        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        Action viewAction = Action.newAction(
                Action.TYPE_VIEW, // TODO: choose an action type.
                "Main Page", // TODO: Define a title for the content shown.
                // TODO: If you have web page content that matches this app activity's content,
                // make sure this auto-generated web page URL is correct.
                // Otherwise, set the URL to null.
                Uri.parse("http://host/path"),
                // TODO: Make sure this auto-generated app URL is correct.
                Uri.parse("android-app://com.dji.FPVDemo/http/host/path")
        );
        AppIndex.AppIndexApi.end(client, viewAction);
        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        client.disconnect();
    }

    public void onReturn(View view) {
        Log.e(TAG, "onReturn");
        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        uninitPreviewer();
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    private void initUI() {
        mConnectStatusTextView = (TextView) findViewById(R.id.ConnectStatusTextView);
        // init mVideoSurface
        mVideoSurface = (TextureView) findViewById(R.id.video_previewer_surface);

        recordingTime = (TextView) findViewById(R.id.timer);
        mCaptureBtn = (Button) findViewById(R.id.btn_capture);
        mRecordBtn = (ToggleButton) findViewById(R.id.btn_record);
        mShootPhotoModeBtn = (Button) findViewById(R.id.btn_shoot_photo_mode);
        mRecordVideoModeBtn = (Button) findViewById(R.id.btn_record_video_mode);

        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);
        }

        mCaptureBtn.setOnClickListener(this);
        mRecordBtn.setOnClickListener(this);
        mShootPhotoModeBtn.setOnClickListener(this);
        mRecordVideoModeBtn.setOnClickListener(this);

//        recordingTime.setVisibility(View.INVISIBLE);

        mRecordBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startRecord();
                } else {
                    stopRecord();
                }
            }
        });

        mTextViewOSD = (TextView) findViewById(R.id.textView_osd);

        mTextViewPower=(TextView) findViewById(R.id.textView_power);
        mTextViewExp=(TextView) findViewById(R.id.textView_exp);

    }

    private void initPreviewer() {

        DJIBaseProduct product = FPVDemoApplication.getProductInstance();

        if (product == null || !product.isConnected()) {
            showToast(getString(R.string.disconnected));
        } else {
            if (null != mVideoSurface) {
                mVideoSurface.setSurfaceTextureListener(this);
            }
            if (!product.getModel().equals(Model.UnknownAircraft)) {
                DJICamera camera = product.getCamera();
                if (camera != null) {
                    // Set the callback
                    camera.setDJICameraReceivedVideoDataCallback(mReceivedVideoDataCallBack);
                    camera.setCameraUpdatedCurrentExposureValuesCallback(
                            new DJICamera.CameraUpdatedCurrentExposureValuesCallback() {
                                @Override
                                public void onResult(DJICamera.DJICameraExposureParameters djiCameraExposureParameters) {
                                    mStringBufferExp.delete(0,mStringBufferExp.length());
                                    mStringBufferExp
                                            .append(djiCameraExposureParameters.getExposureCompensation()).append("EV, ")
                                            .append(djiCameraExposureParameters.getAperture()).append(", ")
                                            .append(djiCameraExposureParameters.getShutterSpeed()).append("s, ")
                                            .append(djiCameraExposureParameters.getISO());
                                    Message msg=new Message();
                                    msg.what=EXPOSURE_STATUS_CHANGED;
                                    mHandler.sendMessage(msg);
                                }
                            }
                    );



                }
                if(null!=product.getBattery()){
                    product.getBattery().setBatteryStateUpdateCallback(
                            new DJIBattery.DJIBatteryStateUpdateCallback() {
                                @Override
                                public void onResult(DJIBattery.DJIBatteryState djiBatteryState) {
                                    mStringBufferPower.delete(0,mStringBufferPower.length());
                                    mStringBufferPower.append(djiBatteryState.getBatteryEnergyRemainingPercent())
                                            .append("%  ");
                                    Message msg=new Message();
                                    msg.what=BATTERY_STATUS_CHANGED;
                                    mHandler.sendMessage(msg);

                                }
                            }
                    );
                }


            }


        }



    }

    private void initStatusPreviewer(){

        if (FPVDemoApplication.isFlightControllerAvailable()) {
            FPVDemoApplication.getAircraftInstance().getFlightController().setUpdateSystemStateCallback(
                    new DJIFlightControllerDelegate.FlightControllerUpdateSystemStateCallback() {


                        @Override
                        public void onResult(DJIFlightControllerDataType.DJIFlightControllerCurrentState djiFlightControllerCurrentState) {
                            DJIGimbal.DJIGimbalAttitude gimbalAtitude
                                    =FPVDemoApplication.getProductInstance().getGimbal().getAttitudeInDegrees();

                            mStringBuffer.delete(0, mStringBuffer.length());
                            mStringBuffer.append("Body Roll: ").
                                    append(djiFlightControllerCurrentState.getAttitude().roll).append(",  ");
                            mStringBuffer.append("Body Pitch: ").
                                    append(djiFlightControllerCurrentState.getAttitude().pitch).append(",  ");
                            mStringBuffer.append("Body Yaw: ").
                                    append(djiFlightControllerCurrentState.getAttitude().yaw).append(",  ");
                            mStringBuffer.append("Homeheight: ").
                                    append(djiFlightControllerCurrentState.getHomePointAltitude()).append("\n");


                            mStringBuffer.append("Camera Roll: ").
                                    append(gimbalAtitude.roll).append(",  ");
                            mStringBuffer.append("Camera Pitch: ").
                                    append(gimbalAtitude.pitch).append(",  ");
                            mStringBuffer.append("Camera Yaw: ").
                                    append(gimbalAtitude.yaw).append(",  ");
                            mStringBuffer.append("Altitude: ").
                                    append(djiFlightControllerCurrentState.getAircraftLocation().getAltitude()).append("\n");




                            mStringBuffer.append("Longitude: ").
                                    append(djiFlightControllerCurrentState.getAircraftLocation().getLongitude()).append(",  ");

                            mStringBuffer.append("Latitude: ").
                                    append(djiFlightControllerCurrentState.getAircraftLocation().getLatitude());


                            mHandler.sendEmptyMessage(CHANGE_TEXT_VIEW);

                            if(isRecording){


                                try{
                                    outMessage.delete(0,outMessage.length());
                                    long elapsedTime=System.currentTimeMillis()-startTime;
                                    //time (ms)
                                    outMessage.append(elapsedTime).append(",");
                                    //Longitude
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAircraftLocation().getLongitude())
                                            .append(",");

                                    //latitude
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAircraftLocation().getLatitude())
                                            .append(",");

                                    //altitude
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAircraftLocation().getAltitude())
                                            .append(",");

                                    //homeheight
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getHomePointAltitude())
                                            .append(",");

                                    //body roll,pitch,yaw
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAttitude().roll)
                                            .append(",");
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAttitude().pitch)
                                            .append(",");
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAttitude().yaw)
                                            .append(",");

                                    //camera roll,pitch,yaw

                                    outMessage.
                                            append(gimbalAtitude.roll).append(",");
                                    outMessage.
                                            append(gimbalAtitude.pitch).append(",");
                                    outMessage.
                                            append(gimbalAtitude.yaw).append("\n");

                                    mPoseOutputStream.write(outMessage.toString().getBytes());






                                }
                                catch(Exception e){
                                    showToast(e.getMessage());
                                    e.printStackTrace();
                                }
                            }

                        }

                    }

            );

        }

    }


    //all the init for video record and pose record;


    private void initRecord() {
        //register Record button to C1
        if(FPVDemoApplication.isRemoteControllerAvailable()) {
            FPVDemoApplication.getAircraftInstance().getRemoteController().setHardwareStateUpdateCallback(
                    new DJIRemoteController.RCHardwareStateUpdateCallback() {
                        @Override
                        public void onHardwareStateUpdate(DJIRemoteController djiRemoteController, DJIRemoteController.DJIRCHardwareState djircHardwareState) {
                            if(true==djircHardwareState.recordButton.buttonDown)
                            {


                                Message btnPressed=new Message();
                                btnPressed.what=RECORD_BUTTON_DOWN;
                                mHandler.sendMessage(btnPressed);




                            }
                        }
                    }

            );

        }
        if(FPVDemoApplication.isCameraModuleAvailable()){
            FPVDemoApplication.getAircraftInstance().getCamera().setDJICameraGeneratedNewMediaFileCallback(
                    new DJICamera.CameraGeneratedNewMediaFileCallback() {
                        @Override
                        public void onResult(DJIMedia djiMedia) {

                            //newMovMedia=djiMedia;
                            File newName=new File(mFlyLogDir.getAbsolutePath()+"/"+djiMedia.getFileNameWithoutExtension()+".txt");
                            mPoseOutputFile.renameTo(newName);



                            showToast(newName.getPath());
                            //mPoseOutputFile.renameTo(new File(mPoseOutputFile.getAbsolutePath()+djiMedia.getFileNameWithoutExtension()+".txt"));
                            //new AlertDialog.Builder(MainActivity.this).setTitle("File Path").setMessage(mPoseOutputFile.getAbsolutePath()).show();
                        }
                    }
            );
        }





    }




    private void uninitPreviewer() {
        DJICamera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            // Reset the callback
            FPVDemoApplication.getCameraInstance().setDJICameraReceivedVideoDataCallback(null);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureAvailable");
        if (mCodecManager == null) {
            mCodecManager = new DJICodecManager(this, surface, width, height);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureSizeChanged");
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.e(TAG, "onSurfaceTextureDestroyed");
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = null;
        }

        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onClick(View v) {

        switch (v.getId()) {
            case R.id.btn_capture: {
                captureAction();
                break;
            }
            case R.id.btn_shoot_photo_mode: {
                switchCameraMode(CameraMode.ShootPhoto);
                break;
            }
            case R.id.btn_record_video_mode: {
                switchCameraMode(CameraMode.RecordVideo);
                break;
            }
            default:
                break;
        }
    }

    private void switchCameraMode(CameraMode cameraMode) {

        DJICamera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            camera.setCameraMode(cameraMode, new DJICompletionCallback() {
                @Override
                public void onResult(DJIError error) {

                    if (error == null) {
                        showToast("Switch Camera Mode Succeeded");
                    } else {
                        showToast(error.getDescription());
                    }
                }
            });
        }

    }

    // Method for taking photo
    private void captureAction() {

        CameraMode cameraMode = CameraMode.ShootPhoto;

        final DJICamera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {

            CameraShootPhotoMode photoMode = CameraShootPhotoMode.Single; // Set the camera capture mode as Single mode
            camera.startShootPhoto(photoMode, new DJICompletionCallback() {

                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        showToast("take photo: success");
                    } else {
                        showToast(error.getDescription());
                    }
                }

            }); // Execute the startShootPhoto API
        }
    }

    // Method for starting recording
    private void startRecord() {

        SimpleDateFormat    sDateFormat    =   new SimpleDateFormat("yyyyMMddhhmmss");
        String  date  =sDateFormat.format(new java.util.Date());
        String fileName=date+".txt";


        mPoseOutputFile=new File(mFlyLogDir.getAbsolutePath()+"/"+fileName);
        try {
            mPoseOutputStream = new FileOutputStream(mPoseOutputFile.getPath());
            String title="time(ms),Longitude,latitude,altitude,homeheight,body_roll,body_pitch,body_yaw,camera_roll,camera_pitch,camera_yaw\n";
            mPoseOutputStream.write(title.getBytes());

        }
        catch(Exception e){
            showToast(e.getMessage());
            e.printStackTrace();

        }


        CameraMode cameraMode = CameraMode.RecordVideo;
        final DJICamera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            camera.startRecordVideo(new DJICompletionCallback() {
                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        showToast("Record video: success");
                        startTime=System.currentTimeMillis();
                    } else {
                        showToast(error.getDescription());
                    }
                }
            }); // Execute the startRecordVideo API
        }
        isRecording=true;

    }

    // Method for stopping recording
    private void stopRecord() {

        try{
            mPoseOutputStream.close();

        }
        catch(Exception e){
            e.printStackTrace();
        }

        DJICamera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            camera.stopRecordVideo(new DJICompletionCallback() {

                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        showToast("Stop recording: success");
                    } else {
                        showToast(error.getDescription());
                    }
                }
            }); // Execute the stopRecordVideo API
        }
        isRecording=false;










    }

    @Override
    public void onStart() {
        super.onStart();

        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        client.connect();
        Action viewAction = Action.newAction(
                Action.TYPE_VIEW, // TODO: choose an action type.
                "Main Page", // TODO: Define a title for the content shown.
                // TODO: If you have web page content that matches this app activity's content,
                // make sure this auto-generated web page URL is correct.
                // Otherwise, set the URL to null.
                Uri.parse("http://host/path"),
                // TODO: Make sure this auto-generated app URL is correct.
                Uri.parse("android-app://com.dji.FPVDemo/http/host/path")
        );
        AppIndex.AppIndexApi.start(client, viewAction);
    }
}
