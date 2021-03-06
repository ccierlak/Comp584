package ss12.com.lightsout;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.support.wearable.view.WatchViewStub;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataSource;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class SinglePlayerGame extends Activity implements MessageApi.MessageListener,
        GoogleApiClient.ConnectionCallbacks, SensorEventListener {

    private GoogleApiClient mGoogleApiClient;
    private final String TAG = "Single Player Wear"; //tag for logging
    private Vibrator vibrator;
    // Create a data source
    DataSource dataSource = new DataSource.Builder()
            //.setAppPackageName(getApplicationContext())
            .setDataType(DataType.TYPE_HEART_RATE_BPM)
            .setName(TAG + " - BPM")
            .setType(DataSource.TYPE_RAW)
            .build();

    TextView mTextView;
    Button button;
    TextView compare;

    //timer variables
    //begin time limit at 4 seconds
    private int timeLimit = 4000;
    private Random rand;

    //node for mobile device
    private String nodeId = "";

    //sensor variables
    private SensorManager sensorManager;
    private Sensor accel;
    private Sensor bpmS;
    private float[] dataArray = new float[3];

    //max readings for the accelerometer per round
    private double xMaxAccel=0,yMaxAccel=0,zMaxAccel=0;

    boolean viewDone=false;

    //Time limit implemented through Handler
    static private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
                //this triggers after a certain amount of time has passed defined by timeLimit
            Log.d("wear message handler","delay message received");
                ((SinglePlayerGame) msg.obj).endRound(msg.what);
        }
    };





    //OVERRIDE METHODS
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_single_player_game);
        final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                mTextView = (TextView) stub.findViewById(R.id.text);

                compare = (TextView) stub.findViewById(R.id.compare);
                viewDone=true;
            }
        });
        //keep screen active on wearable
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        //register sensors
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        bpmS = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        createGoogleApiClient();

        //create random number generator
        rand = new Random();

        vibrator =  (Vibrator) this.getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE);



    }

    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
    }



    @Override
    public void onConnected(Bundle bundle) {
        Log.d(TAG, "onConnected: " + bundle);
        Wearable.MessageApi.addListener(mGoogleApiClient,this);
        // now we can use the Message API
        //assigns nodeId
        retrieveDeviceNode();
        registerBPMButton();



    }

    @Override
    public void onConnectionSuspended(int cause) {
        Log.d(TAG, "onConnectionSuspended: " + cause);

    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        final String message = messageEvent.getPath();
         final int expectedAction = Integer.parseInt(message);
        respond(expectedAction);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.d("Wearable", "phone message received: "+expectedAction);
                actionGiven(expectedAction);
                startRound(expectedAction);
            }
        });
    }

    @Override
    protected void onStop() {
        Wearable.MessageApi.removeListener(mGoogleApiClient, this);
        super.onStop();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Sensor mySensor = event.sensor;
            //taking the absolute value accounts for the magnitude of negative acceleration
            dataArray[0] = Math.abs(event.values[0]);
            dataArray[1] = Math.abs(event.values[1]);
            dataArray[2] = Math.abs(event.values[2]);


        if (mySensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {

            if (dataArray[0] > xMaxAccel)
                xMaxAccel = dataArray[0];
            if (dataArray[1] > yMaxAccel)
                yMaxAccel = dataArray[1];
            if (dataArray[2] > zMaxAccel)
                zMaxAccel = dataArray[2];
        }

        /*for debug
        if(viewDone) {

            //TextView display of accelerometer data
                mTextView.setText("accel" + "\nx: " + xMaxAccel + "\ny: " + yMaxAccel + "\nz: "+zMaxAccel);
        }*/



    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    private void createGoogleApiClient(){
        //Basic Google Api Client build
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult result) {
                        Log.d(TAG, "onConnectionFailed: " + result);
                    }
                })
                        // adding only the wearable API
                .addApi(Wearable.API)
                .addApi(Fitness.API)
                .build();
    }

    //this method sets the global variable nodeId to the id of the phone for communication
    private void retrieveDeviceNode() {
        //we are using a worker thread to get the nodeId since we do not want to
        // clog up the main thread
        Log.d(TAG,"nodes retrieved");

        new Thread(new Runnable() {
            @Override
            public void run() {

                NodeApi.GetConnectedNodesResult nodesResult =
                        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();
                final List<Node> nodes = nodesResult.getNodes();
                //we are assuming here that there is only one wearable attached to the phone
                //currently Android only supports having one wearable connected at a time
                if (nodes.size() > 0) {
                    nodeId=nodes.get(0).getId();
                    Log.d(TAG,nodeId);
                }
                Log.d(TAG,nodes.size()+"");
            }
        }).start();
    }

    //game logic for each round
    private void startRound(int action){
        sensorManager.registerListener(this,accel,SensorManager.SENSOR_DELAY_NORMAL);

        //increase time limit randomly up to half a second only if it will result in less than
        //five seconds for the user to react
        if(timeLimit<5000) {
            timeLimit += rand.nextInt(750);
        }
        //decrease time limit up to one second only if it will result in over .8 seconds for the
        //user to react
        if(timeLimit>1800) {
            timeLimit -= rand.nextInt(1000);
        }
        Message msg = mHandler.obtainMessage(action,this);
        mHandler.sendMessageDelayed(msg, timeLimit);
        Log.d(TAG,"delay message sent "+msg.what);


    }

    private void endRound(int expectedAction){

        int actualAction=compareAxes();
        //reset maxes
        xMaxAccel=0;
        yMaxAccel=0;
        zMaxAccel=0;

        /*
        each expected action corresponds to a movement. These movements correspond to each axis
        in that a punch will have the most activity on the x axis, a block will have the most
        activity on the y axis, and a push will have the most activity on the z axis.
         */
        Log.d(TAG,"actual :"+actualAction+"\texpected: "+expectedAction);
        if(actualAction==expectedAction){
            //win detected
            //send message back to phone, 9 signifies win
            Wearable.MessageApi.sendMessage(mGoogleApiClient,nodeId,"9",null);
        }else{
            //loss detected
            //send message back to phone with possible error code
            Wearable.MessageApi.sendMessage(mGoogleApiClient,nodeId,actualAction+"",null);
        }

    }

    //returns an int representing which of the 3 axes recorded the most activity
    //this will be compared to what axis activity is expected on to determine if the round is won
    //or lost
    private int compareAxes(){
        //unregister Listener at the end of every round
        sensorManager.unregisterListener(this,accel);
        Log.d(TAG,"axes update: \n"+xMaxAccel+"\n"+yMaxAccel+"\n"+zMaxAccel);
        /*for debug
        compare.setText("\nxy: "+((xMaxAccel+1)/(yMaxAccel+1))+"\nxz:"+
                ((xMaxAccel+1)/(zMaxAccel+1))+"\nyx: "+((yMaxAccel+1)/(xMaxAccel+1))+"\nyz: "
                +((yMaxAccel+1)/(zMaxAccel+1))+"\nzx: "+((zMaxAccel+1)/(xMaxAccel+1))+
                "\nzy: "+((zMaxAccel+1)/(yMaxAccel+1)));
                */
        double axisMax = Math.max(Math.max(xMaxAccel,yMaxAccel),zMaxAccel);
        Log.d(TAG,"max axis: "+axisMax);
        if(axisMax>1.5) {
            //threshold for valid movement reading
            if (axisMax == xMaxAccel) {
                //0 means x axis had the strongest activity
                return 0;
            } else if (axisMax == yMaxAccel) {
                //1 means y axis had the strongest activity
                return 1;
            } else {
                //2 means z axis had the strongest activity
                return 2;
            }
        }
        else{
            //3 means no valid movement was registered, round is counted as loss
            return 3;
        }
    }


    //auditory and haptic feedback on success or fail
    private void respond(int action){
        //general playing of sounds should occur here
        //probably will want to pass in sound name or path
        switch (action)
        {
            case 0://punch
                vibrator.vibrate(new long[]{0, 400,0, 0, 0, 0}, -1);
                break;
            case 1: //counter
                vibrator.vibrate(new long[]{0, 400, 0, 0, 0, 0, 400, 0 , 0, 0, 0, 500}, -1);
                break;
            case 2: //push
                vibrator.vibrate(new long[]{0, 400, 0, 0, 0, 0, 400,0 , 0, 0, 0, 400, 0 , 0 , 0, 500}, -1);
                break;
            default:
                break;
        }

    }

    public  void actionGiven(int action)
    {
        switch (action)
        {
            case 0://punch
                Toast.makeText(getApplicationContext(),"Punch",Toast.LENGTH_SHORT).show();
                break;
            case 1: //block
                Toast.makeText(getApplicationContext(),"Block",Toast.LENGTH_SHORT).show();
                break;
            case 2: //push
                Toast.makeText(getApplicationContext(),"Rush",Toast.LENGTH_SHORT).show();
                break;
            default:
                break;
        }
    }

    public void registerBPMButton(){
        Button bpmB=(Button) findViewById(R.id.bpm);
        bpmB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sensorManager.registerListener(new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent event) {
                        int bpm = (int)event.values[0];
                        Calendar cal = Calendar.getInstance();
                        Date now = new Date();
                        cal.setTime(now);
                        long startTime = cal.getTimeInMillis();
                        long endTime=startTime;
                        DataSet dataSet = DataSet.create(dataSource);
                        DataPoint dataPoint = dataSet.createDataPoint()
                                .setTimeInterval(startTime, endTime, TimeUnit.MILLISECONDS);
                        dataPoint.getValue(Field.FIELD_BPM).setInt(bpm);
                        dataSet.add(dataPoint);
                        Log.i(TAG, "Inserting the dataset in the History API");
                        Fitness.HistoryApi.insertData(mGoogleApiClient, dataSet);
                    }

                    @Override
                    public void onAccuracyChanged(Sensor sensor, int accuracy) {

                    }
                },bpmS,SensorManager.SENSOR_DELAY_NORMAL);
                // Set a start and end time for our data, using a start time of 1 hour before this moment.
                Calendar cal = Calendar.getInstance();
                Date now = new Date();
                cal.setTime(now);
                long endTime = cal.getTimeInMillis();
                cal.add(Calendar.HOUR_OF_DAY, -1);
                long startTime = cal.getTimeInMillis();



// Create a data set

            }
        });
    }

}