package com.leapmotor.gavin.myrtspclient;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by gavin on 11/4/16.
 */
public class VideoReceiver {

    private StringBuffer buf;

    private Socket socket;
    private BufferedReader br;
    private InputStream is;
    PrintWriter pw;

    private FileOutputStream fos;
    private String RTSPUrl;
    private String ServerIP;
    private int ServerPort;

    private boolean SaveToFile;
    private boolean DEBUG;
    //private StringBuilder LogBuffer;
    private Handler handler;
    private boolean ExitCmdFromUI;

    private String trackInfo;
    private String sessionid;
    private int seq;

    private static final String VERSION = " RTSP/1.0";
    private static final String RTSP_OK = "RTSP/1.0 200 OK";
    private static final byte[] NaluStartCode = new byte[]{0, 0, 0, 1};
    private EnumRTSPStatus RTSPStatus;
    private EnumClientStatus ClientStatus;


    private static final int STRING_BUFFER_SIZE = 8192;
    private static final int RTP_SIZE = 5000;

    private int count;
    private H264Player h264Player;
    private ByteBuffer VideoStartBuffer;
    private int Status;

    private enum EnumRTSPStatus {
        init, options, describe, setup, play, pause, teardown
    }

    private enum EnumClientStatus {
        ok, disconnected, error, RTCPFlag, badPackage
    }

    /* done
    RTSPUrl is like rtsp://ip:8888/record/$carId/1/123.sdp
    */
    public VideoReceiver(H264Player h264Player) {

        this.h264Player = h264Player;
        this.SaveToFile = false;


        buf = new StringBuffer(STRING_BUFFER_SIZE);
        handler = new Handler(Looper.getMainLooper()){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what){
                    case 1:
                        ExitCmdFromUI = true;
                        break;
                    default:
                }
                super.handleMessage(msg);
            }
        };
        Status = 0;
    }

    public Boolean Initialize(String RTSPUrl) {
        try {
            ExitCmdFromUI = false;
            this.RTSPUrl = RTSPUrl.trim();
            int ColonIndexAfterIp = -1;
            int SlashIndexAfterPort = -1;
            if (!this.RTSPUrl.startsWith("rtsp://"))
                return false;

            if (-1 == (ColonIndexAfterIp = this.RTSPUrl.indexOf(':', 7)))
                return false;
            ServerIP = this.RTSPUrl.substring(7, ColonIndexAfterIp);

            if (-1 == (SlashIndexAfterPort = this.RTSPUrl.indexOf('/', ColonIndexAfterIp)))
                return false;

            ServerPort = Integer.parseInt(this.RTSPUrl.substring(ColonIndexAfterIp + 1, SlashIndexAfterPort));
            seq = 1;

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public void StartOrStopRecord() {
        try {
            if (SaveToFile){
                fos.close();
            }
            else {
                String fileName = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".h264";
                File file = new File(Environment.getExternalStorageDirectory() + "/leapmotor/video", fileName);
                file.createNewFile();
                fos = new FileOutputStream(file, true);
                fos.write(VideoStartBuffer.array());
                fos.flush();

            }

            SaveToFile = !SaveToFile;


        }
        catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    public void connect() {
        try {
            MainActivity.SendMsg("Connecting to server...");
            socket = new Socket(ServerIP, ServerPort);
            socket.setReceiveBufferSize(500 * 1024);
            //socket.setSoTimeout(8*1000);
            is = socket.getInputStream();
            br = new BufferedReader(new InputStreamReader(is));
            pw = new PrintWriter(socket.getOutputStream());
            VideoStartBuffer = ByteBuffer.allocate(1024);

//            if (true) {
//                file = new File(Environment.getExternalStorageDirectory(), fileName);
//                if (file.exists()) {
//                    file.delete();
//                }
//                file.createNewFile();

//                fos = new FileOutputStream(file, true);
//            }
            //RTSPStatus = EnumRTSPStatus.init;

            do {
                doOption();
                if (!sendAndRead(EnumRTSPStatus.options))
                    break;

                doDescribe();
                if (!sendAndRead(EnumRTSPStatus.describe))
                    break;

                doSetup();
                if (!sendAndRead(EnumRTSPStatus.setup))
                    break;

                doPlay();
                if (!sendAndRead(EnumRTSPStatus.play))
                    break;

                if (!isConnected()) {
                    System.out.println("[WARN] connection lost.");
                    break;
                }
                Status = 1;
                if (!ReadRTP())
                    System.out.println("[WARN] ReadRTP not ok.");

                Status = 4;
                doTeardown();
                sendAndRead(EnumRTSPStatus.teardown);
            } while (false);
            close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void doOption() {
        buf.delete(0, buf.length());
        buf.append("OPTIONS ");
        buf.append(this.RTSPUrl);
        buf.append(' ');
        buf.append(VERSION);
        AppendEnd(1);
        buf.append("Cseq: ");
        buf.append(seq++);
        AppendEnd(1);
        buf.append("User-Agent: Gavin RTSP Client Test");
        AppendEnd(2);
    }

    private void doDescribe() {
        buf.delete(0, buf.length());
        buf.append("DESCRIBE ");
        buf.append(this.RTSPUrl);
        buf.append(' ');
        buf.append(VERSION);
        AppendEnd(1);
        buf.append("Cseq: ");
        buf.append(seq++);
        AppendEnd(1);
        buf.append("User-Agent: Gavin RTSP Client Test");
        AppendEnd(1);
        buf.append("Accept: application/sdp");
        AppendEnd(2);
    }

    private void doSetup() {
        buf.delete(0, buf.length());
        buf.append("SETUP ");
        buf.append(this.RTSPUrl);
        buf.append("/");
        buf.append("trackID=0 ");
        buf.append(VERSION);
        AppendEnd(1);
        buf.append("Cseq: ");
        buf.append(seq++);
        AppendEnd(1);
        buf.append("User-Agent: Gavin RTSP Client Test");
        AppendEnd(1);
        buf.append("Transport: RTP/AVP/TCP;unicast;interleaved=0-1");
        AppendEnd(2);
    }

    private void doPlay() {
        buf.delete(0, buf.length());
        buf.append("PLAY ");
        buf.append(this.RTSPUrl);
        buf.append("/ ");
        buf.append(VERSION);
        AppendEnd(1);
        buf.append("Cseq: ");
        buf.append(seq++);
        AppendEnd(1);
        buf.append("User-Agent: Gavin RTSP Client Test");
        AppendEnd(1);
        buf.append("Session: ");
        buf.append(sessionid);
        AppendEnd(1);
        buf.append("Range: npt=0.000-");
        AppendEnd(2);
    }

    private void doPause() {
        buf.delete(0, buf.length());
        buf.append("PAUSE ");
        buf.append(this.RTSPUrl);
        buf.append("/ ");
        buf.append(VERSION);
        AppendEnd(1);
        buf.append("Cseq: ");
        buf.append(seq++);
        AppendEnd(1);
        buf.append("Session: ");
        buf.append(sessionid);
        AppendEnd(2);
    }

    private void doTeardown() {
        buf.delete(0, buf.length());
        buf.append("TEARDOWN ");
        buf.append(this.RTSPUrl);
        buf.append("/ ");
        buf.append(VERSION);
        AppendEnd(1);
        buf.append("Cseq: ");
        buf.append(seq++);
        AppendEnd(1);
        buf.append("User-Agent: Gavin RTSP Client Test");
        AppendEnd(1);
        buf.append("Session: ");
        buf.append(sessionid);
        AppendEnd(2);
    }

    private void send() {
        if (!isConnected()) {
            ClientStatus = EnumClientStatus.disconnected;
            return;
        }

        pw.write(buf.toString());
        pw.flush();
        ClientStatus = EnumClientStatus.ok;
    }

    private void receive() {
        if (!isConnected()) {
            ClientStatus = EnumClientStatus.disconnected;
            return;
        }
        buf.delete(0, buf.length());
        try {
            String STmp;
            do {
                STmp = br.readLine();
                buf.append(STmp);
                buf.append('\n');
            } while (null != STmp && !STmp.equals(""));

            ClientStatus = EnumClientStatus.ok;
        } catch (Exception e) {
            e.printStackTrace();
            ClientStatus = EnumClientStatus.error;
        }

    }

    private boolean sendAndRead(EnumRTSPStatus Status) {
        boolean PrintRTSP = true;
        if (PrintRTSP) {
            System.out.println("#C->S:");
            System.out.println(buf.toString().replaceAll("\r\n", "\n") + "\n");
        }
        send();

        if (EnumClientStatus.ok != ClientStatus) {
            System.out.println("[WARN] sendAndRead send " + Status.name() + " fail: " + ClientStatus);
            return false;
        }
        if (Status == EnumRTSPStatus.teardown)
            return true;
        receive();
        if (EnumClientStatus.ok != ClientStatus) {
            System.out.println("[WARN] sendAndRead receive " + Status.name() + " fail: " + ClientStatus);
            return false;
        }

        String tmp = buf.toString();
        if (PrintRTSP) {
            System.out.println("#S->C:");
            System.out.println(buf);
            System.out.println("");
        }

        if (!(tmp.startsWith(RTSP_OK) || tmp.contains(RTSP_OK))) {
            System.out.println("[WARN] Server return not ok.");
            MainActivity.SendMsg("Set up RTSP session fail.");
            return false;
        }

        if (EnumRTSPStatus.setup == Status) {

            sessionid = tmp.substring(tmp.indexOf("Session:") + 8, tmp.indexOf("Date:") - 1).trim();

            if (sessionid == null && sessionid.length() < 0) {
                System.out.println("[WARN] setup error");
            }
            String SPSParameter = "sprop-parameter-sets=";
            int IndexOfSPS = tmp.indexOf(SPSParameter) + SPSParameter.length();
            int CommaIndex = tmp.indexOf(',', IndexOfSPS);
            String Base64SPS = tmp.substring(IndexOfSPS, CommaIndex).trim();
            String Base64PPS = tmp.substring(CommaIndex + 1, tmp.indexOf(';', CommaIndex)).trim();

            byte[] SPSasBytes = Base64.decode(Base64SPS, Base64.DEFAULT);
            byte[] PPSasBytes = Base64.decode(Base64PPS, Base64.DEFAULT);

            VideoStartBuffer.put(NaluStartCode);
            VideoStartBuffer.put(SPSasBytes);
            VideoStartBuffer.put(NaluStartCode);
            VideoStartBuffer.put(PPSasBytes);
        }

        return true;
    }


    private boolean isConnected() {
        return socket != null && socket.isConnected() && br != null && pw != null && is != null;
    }

    private void AppendEnd(int num) {
        int i = 0;
        for (; i < num; i++)
            buf.append("\r\n");
    }

    private void close() {
        try {
            br.close();
            pw.close();
            is.close();
            socket.close();
            fos.close();
            Status = 5;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean ReadRTP() {
        MainActivity.SendMsg("Video loading...");
        try {
            socket.setSoTimeout(5*1000);
        } catch (Exception e){
            e.printStackTrace();
        }

        SingleRTPPacket.InitializeForClass(is, DEBUG);
        ByteBuffer RTPByteBuf;
        ByteBuffer NaluPLByteBuf;
        RTPByteBuf = ByteBuffer.allocate(RTP_SIZE);
        NaluPLByteBuf = ByteBuffer.allocate(RTP_SIZE);
        SingleRTPPacket singleRTPPacket = new SingleRTPPacket(RTPByteBuf);

        int TmpNumOfRTPRead = 0;
        int CurrSeq = 0;
        int TmpSeq = 0;

        int rc;
        int frameSize;

        h264Player.pushData(VideoStartBuffer.array(), VideoStartBuffer.position());


        System.out.println("[Client] Loop started.");

        for (; !ExitCmdFromUI; TmpNumOfRTPRead++/*, NumOfRTPRead++*/) {

            RTPByteBuf.clear();
            NaluPLByteBuf.clear();

            Status = 2;
            rc = singleRTPPacket.ReadNextRTPPacket();
            Status = 3;
            if (0 > rc) {
                System.out.printf("[WARN] RTPReceiver.ReadRTP.singleRTPPacket.ReadNextRTPPacket fail, rc = %d\n\n", rc);
                return false;
            }
            else if (1 == rc) {
                MainActivity.SendMsg("The server is not responding.");
                System.out.println("[INFO] The server is not responding.");
                continue;
            }
            else if (2 == rc){
                MainActivity.SendMsg("Read to end.");
                System.out.println("[INFO] Read to end.");
                return true;
            }

            CurrSeq = singleRTPPacket.getSeq();

            if (1000 == TmpNumOfRTPRead) {
                TmpNumOfRTPRead = 0;
                System.out.println("[Client] Packet loss probability: " + (CurrSeq - TmpSeq + 1 - 1000) * 100 / (CurrSeq - TmpSeq + 1) + "%");
                TmpSeq = CurrSeq;
            }

            rc = singleRTPPacket.processSpecialHeader();
            if (-1 == rc) {
                System.out.println("[WARN] ReadRTP: singleRTPPacket.processSpecialHeader() fail.");
                break;
            }

            for (; ; ) {
                frameSize = singleRTPPacket.nextEnclosedFrameSize();
                if (0 == frameSize) {
                    //System.out.println("parse a RTP completed.");
                    break;
                }

                if (NaluPLByteBuf.remaining() < frameSize) {
                    System.out.printf("[ERROR] ReadRTP: NaluPLByteBuf is too small. " +
                                    "NaluPLByteBuf.remaining = %d, frameSize = %d\n",
                            NaluPLByteBuf.remaining(), frameSize);
                    return false;
                }

                // if processSpecialHeader return 0, it means this rtp is a nal start part, we should add start code.
                if (0 == rc) {
                    NaluPLByteBuf.put(NaluStartCode);
                }

                try {
                    RTPByteBuf.get(NaluPLByteBuf.array(), NaluPLByteBuf.position(), frameSize);
                } catch (Exception e) {
                    System.out.println("NaluPLByteBuf.remaining: " + NaluPLByteBuf.remaining()
                            + "\nframeSize: " + frameSize
                            + "\nRTPByteBuf.remaining: " + RTPByteBuf.remaining()
                            + "\nRTPByteBuf.capacity: " + RTPByteBuf.capacity()
                    );

                    e.printStackTrace();
                    continue;
                }
                //System.out.printf("[Client] NaluPLByteBuf After StartCode: %x %x\n", NaluPLByteBuf.get(NaluPLByteBuf.position()), NaluPLByteBuf.get(NaluPLByteBuf.position()+1));
                NaluPLByteBuf.position(NaluPLByteBuf.position() + frameSize);
            }

            NaluPLByteBuf.flip();
            h264Player.pushData(NaluPLByteBuf.array(), NaluPLByteBuf.limit());

            if (SaveToFile) {
                try {
                    fos.write(NaluPLByteBuf.array(), NaluPLByteBuf.position(), NaluPLByteBuf.remaining());
                    fos.flush();

                } catch (Exception e) {
                    try {
                        fos.close();
                    }
                    catch (Exception ee){
                        ee.printStackTrace();
                    }

                    e.printStackTrace();
                    MainActivity.SendMsg("Write to file fail.");
                    SaveToFile = false;
                }
            }
        }


        singleRTPPacket.PrintTypeCount();


        return true;
    }

    public void SendStopMsg(){
        if(null == handler)
            return;
        Message message = Message.obtain();
        message.what = 1;
        handler.sendMessage(message);
    }

    public String GetStatus(){
        switch (Status){
            case 0: return "Init.";
            case 1: return "RTSP Complete.";
            case 2: return "Reading Next RTP Packet.";
            case 3: return "Read RTP Packet complete.";
            case 4: return "Play video done.";
            case 5: return "Closed.";
            default: return "";
        }
    }

}
