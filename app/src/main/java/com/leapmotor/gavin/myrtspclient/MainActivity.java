/*
* To do:
* 1. ByteBuffer.allocateDirect()
* 2. Byte.get change to ByteBuffer.put(ByteBuffer)
*
*
*
* */

package com.leapmotor.gavin.myrtspclient;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import android.view.SurfaceView;
import android.view.View;

import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {

    private static Handler handler;

    private final static String localFileName = "MyRTSPClient.h264";

    // video rate is 16k/40ms
    private final static int VideoBufSize = 1024 * 16;
    private final static int MilliSecondToSleep = 40;


    private final static String url1 = "rtsp://120.26.86.124:8888/realtime/leapmotorNo1/1/realtime.sdp";
    private final static String url2 = "rtsp://120.26.86.124:8888/record/leapmotorNo1/1/123.sdp";
    private final static String url3 = "rtsp://120.26.86.124:8888/realtime/1234/1/realtime.sdp";
    //private final static String url4 = "rtsp://120.27.188.84:8888/record/leapmotorNo1/1/123.sdp";

    private final static String url4 = "rtsp://120.27.188.84:8888/record/leapmotorNo1/1/123.sdp";

    private final static String url5 = "rtsp://120.27.188.84:8888/realtime/1234/1/realtime.sdp";
    //private final static String url4 = "rtsp://218.204.223.237:554/live/1/66251FC11353191F/e7ooqwcfbqjoo80j.sdp";
    private final static String Urls[] = new String[]{url1, url2, url3, url4, url5};
    //Thread RTSPTask;
    static final boolean DEBUG = false;
    //static boolean WriteToFile = false;

    private RadioOnClick radioOnClick = new RadioOnClick(0);
    private ListView areaRadioListView;
    private CheckBox CBSaveToFile;

    private H264Player h264Player;
    private VideoReceiver videoReceiver;

    private boolean VideoPlayerNeedExit;

    private Thread RTPReceiverThread;
    private Thread VideoPlayerThread;

    //private MyDBHelper myDBHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        h264Player = new H264Player((SurfaceView) findViewById(R.id.surfaceView1));;
        findViewById(R.id.BtPlay).setOnClickListener(new RadioClickListener());

        findViewById(R.id.play_local).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (null != VideoPlayerThread && VideoPlayerThread.isAlive())
                    return;

                StartVideoPlayer(true);
            }
        });

        findViewById(R.id.buttonStop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VideoPlayerNeedExit = true;
                videoReceiver.SendStopMsg();
                Toast.makeText(MainActivity.this, videoReceiver.GetStatus(), Toast.LENGTH_SHORT).show();
            }
        });

        CBSaveToFile = ((CheckBox) findViewById(R.id.CBSaveToFile));
        CBSaveToFile.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (null != videoReceiver)
                    videoReceiver.StartOrStopRecord();
            }
        });

        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                Toast.makeText(MainActivity.this, msg.obj.toString(), Toast.LENGTH_SHORT).show();
                super.handleMessage(msg);
            }
        };
        VideoPlayerNeedExit = false;


        videoReceiver = new VideoReceiver(h264Player);

        //myDBHelper = new MyDBHelper(this, "gavin.db", 1);

    }

    @Override
    public void onBackPressed() {
        VideoPlayerNeedExit = true;
        videoReceiver.SendStopMsg();

        super.onBackPressed();
    }

    class RadioClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {

            if (null != RTPReceiverThread && RTPReceiverThread.isAlive())  {
                return;
            }

            AlertDialog ad = new AlertDialog.Builder(MainActivity.this).setTitle("Choose A URL:")
                    .setSingleChoiceItems(Urls, radioOnClick.getIndex(), radioOnClick).create();
            areaRadioListView = ad.getListView();
            ad.show();
        }
    }

    class RadioOnClick implements DialogInterface.OnClickListener {
        public RadioOnClick(int index) {
            this.index = index;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public void onClick(DialogInterface dialogInterface, int whichButton) {

            setIndex(whichButton);

            Toast.makeText(MainActivity.this, "Use URL: " + Urls[index], Toast.LENGTH_LONG).show();
            StartRTSPClient(Urls[index]);

            //Toast.makeText(MainActivity.this, "Playing h264 stream...", Toast.LENGTH_SHORT).show();
            //StartVideoPlayer(false);

            dialogInterface.dismiss();
        }

        private int index;

    }


    private void StartRTSPClient(final String url) {
        RTPReceiverThread = new Thread() {
            public void run() {
                Thread.currentThread().setName("videoReceiver");
                System.out.println("[DEBUG] Thread " + Thread.currentThread().getName() + " created.");

                if (!videoReceiver.Initialize(url)) {
                    System.out.println("Initializer error. url: " + url);
                    SendMsg("Initializer error. url: " + url);
                    return;
                }

                videoReceiver.connect();
                System.out.println("RTSP Client existed.");
                SendMsg("RTSP Client existed.");
            }
        };
        RTPReceiverThread.start();
    }

    private void StartVideoPlayer(final boolean PlayLocalVideo) {
        VideoPlayerThread = new Thread() {
            public void run() {

                Thread.currentThread().setName("Player");
                System.out.println("[DEBUG] Thread " + Thread.currentThread().getName() + " created.");
                VideoPlayer videoPlayer = new VideoPlayer();
                videoPlayer.PlayVideo(PlayLocalVideo);
                System.out.println("Video Player existed.");
                SendMsg("Video Player existed.");
            }
        };
        VideoPlayerThread.start();
    }

    public static void SendMsg(String s) {
        Message message = Message.obtain();
        message.obj = s;
        handler.sendMessage(message);
    }

    public class VideoPlayer {

        private void PlayVideo(final boolean playLocal) {
            try {
                if (playLocal) {
                    ByteBuffer VideoBuffer = ByteBuffer.allocate(VideoBufSize);
                    File FileLocalH264 = new File(Environment.getExternalStorageDirectory(), localFileName);
                    if (!FileLocalH264.exists()) {
                        System.out.println("[Player] Local file " + localFileName + " is not existing.");
                        SendMsg("Local file " + localFileName + " is not existing.");
                    } else {
                        FileInputStream fisLocalH264 = new FileInputStream(FileLocalH264);

                        for (int i = 0; !VideoPlayerNeedExit; i++) {
                            if (-1 == fisLocalH264.read(VideoBuffer.array(), 0, VideoBuffer.capacity()))
                                break;
                            h264Player.pushData(VideoBuffer.array(), VideoBuffer.capacity());
                            Thread.sleep(40);
                            //System.out.println(i);
                        }
                        VideoPlayerNeedExit = false;
                    }
                } else {
//                    LockForBuf2.lock();
//                    Thread.sleep(100);
//                    ByteBuffer ReadBufRef = Buffer2;
//                    ByteBuffer TmpBuf = ByteBuffer.allocate(VideoBufSize);
//                    int SizeToCopy;
//                    //System.out.println("[Player] First time to get read buf, Lock2 got, waiting for Lock1.");
//                    ReadBufRef = VideoReceiver.SwapBuf(Buffer1, Buffer2, ReadBufRef, LockForBuf1, LockForBuf2);
//                    for (; !VideoPlayerNeedExit; ) {
//
//                        if (VideoBufSize > ReadBufRef.remaining()) {
//                            TmpBuf.put(ReadBufRef);
//                            //h264Player.pushData(TmpBuf, ReadBufRef.remaining());
//                            ReadBufRef.clear();
//                            ReadBufRef = VideoReceiver.SwapBuf(Buffer1, Buffer2, ReadBufRef, LockForBuf1, LockForBuf2);
//                        }
//                        SizeToCopy = TmpBuf.remaining();
//                        ReadBufRef.get(TmpBuf.array(), TmpBuf.position(), SizeToCopy);
//                        TmpBuf.clear();
//
//                        h264Player.pushData(TmpBuf.array(), VideoBufSize);
//                        Thread.sleep(MilliSecondToSleep);
//                        //System.out.println(i);
//
//
//                        //System.out.println("[Player] Release lock, play ok.");
//                        //break;
//                    }
//                    VideoReceiver.UnLock(Buffer1, Buffer2, ReadBufRef, LockForBuf1, LockForBuf2);
//                    VideoPlayerNeedExit = false;
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
            //h264Player.pushData(src, src.length);
        }

    }
}

