package com.leapmotor.gavin.myrtspclient;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.Lock;

/**
 * Created by gavin on 11/4/16.
 */
public class RTSPClient {

    private StringBuffer buf;

    private Socket socket;
    private BufferedReader br;
    private InputStream is;
    PrintWriter pw;

    File file;
    //FileWriter fw;
    FileOutputStream fos;
    String fileName;

    private String RTSPUrl;
    private String ServerIP;
    private int ServerPort;
    private boolean WriteToFile;
    private boolean DEBUG;
    //private StringBuilder LogBuffer;
    static private Handler handler;
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

    private enum EnumRTSPStatus {
        init, options, describe, setup, play, pause, teardown
    }

    private enum EnumClientStatus {
        ok, disconnected, error, RTCPFlag, badPackage
    }

    /* done
    RTSPUrl is like rtsp://ip:8888/record/$carId/1/123.sdp
    */
    public RTSPClient(String RTSPUrl, String fileName, boolean DEBUG, boolean WriteToFile, H264Player h264Player) {
        this.RTSPUrl = RTSPUrl.trim();
        this.fileName = fileName;
        //this.handler = handler;
        this.DEBUG = DEBUG;
        this.WriteToFile = WriteToFile;
        this.h264Player = h264Player;

        ExitCmdFromUI = false;
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
    }

    public Boolean Initializer() {
        try {
            int ColonIndexAfterIp = -1;
            int SlashIndexAfterPort = -1;
            if (!RTSPUrl.startsWith("rtsp://"))
                return false;

            if (-1 == (ColonIndexAfterIp = RTSPUrl.indexOf(':', 7)))
                return false;
            ServerIP = RTSPUrl.substring(7, ColonIndexAfterIp);

            if (-1 == (SlashIndexAfterPort = RTSPUrl.indexOf('/', ColonIndexAfterIp)))
                return false;

            ServerPort = Integer.parseInt(RTSPUrl.substring(ColonIndexAfterIp + 1, SlashIndexAfterPort));
            seq = 1;

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public void connect() {
        try {
            MainActivity.SendMsg("Connecting to server...");
            socket = new Socket(ServerIP, ServerPort);
            socket.setReceiveBufferSize(500 * 1024);
            is = socket.getInputStream();
            br = new BufferedReader(new InputStreamReader(is));
            pw = new PrintWriter(socket.getOutputStream());
            VideoStartBuffer = ByteBuffer.allocate(1024);

            if (WriteToFile) {
                file = new File(Environment.getExternalStorageDirectory(), fileName);
                if (file.exists()) {
                    file.delete();
                }
                file.createNewFile();
//            fw = new FileWriter(file, true);

                fos = new FileOutputStream(file, true);
            }
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
                if (!ReadRTP())
                    break;

                // read user input
//                char i = 'q';//(char) System.in.read();
//                if ('p' == i) {
//                    doPause();
//                    if (!sendAndRead(EnumRTSPStatus.pause)) {
//                        close();
//                        return;
//                    }
//                }

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
            if (WriteToFile) {
                try {
                    fos.write(NaluStartCode);
                    fos.write(SPSasBytes);
                    fos.write(NaluStartCode);
                    fos.write(PPSasBytes);
                    fos.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                    return false;
                }
            }

            VideoStartBuffer.put(NaluStartCode);
            VideoStartBuffer.put(SPSasBytes);
            VideoStartBuffer.put(NaluStartCode);
            VideoStartBuffer.put(PPSasBytes);
            h264Player.pushData(VideoStartBuffer.array(), VideoStartBuffer.position());

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

            if (WriteToFile)
                fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean ReadRTP() {
        MainActivity.SendMsg("Video loading...");
        SingleRTPPacket.InitializeForClass(is, DEBUG);
        ByteBuffer RTPByteBuf;
        ByteBuffer NaluPLByteBuf;
        RTPByteBuf = ByteBuffer.allocate(RTP_SIZE);
        NaluPLByteBuf = ByteBuffer.allocate(RTP_SIZE);

        ByteBuffer TmpBuffer = ByteBuffer.allocate(100*RTP_SIZE);

        SingleRTPPacket singleRTPPacket = new SingleRTPPacket(RTPByteBuf);

        int NumOfRTPRead = 1;
        int TmpNumOfRTPRead = 1;
        int NumOfNaluWriteToBuf = 0;
        int NumOfRTCP = 0;

        int FirstRTPSeq = -1;
        int CurrSeq = -1;
        int LastSeq = 0;
        //int NumOfRTPLost = 0;
        int NumOfRTPLost2 = 0;
        int i = 0;

        int rc;
        int frameSize;

        //int TotalSizeWriteToBuf = 0;


        System.out.println("[Client] Loop started.");


        // 1488 1499 ... 20000 1477 1478 ... 20011 1488 1490

        RTPByteBuf.clear();
        NaluPLByteBuf.clear();
        rc = singleRTPPacket.ReadNextRTPPacket();
        if (0 > rc) {
            System.out.printf("[WARN] RTPReceiver.ReadRTP.singleRTPPacket.ReadNextRTPPacket fail, rc = %d\n\n", rc);
            return false;
        }

        FirstRTPSeq = CurrSeq = singleRTPPacket.getSeq();
        LastSeq = CurrSeq - 1;
        //int NaluSize = 0;
        //while (NumOfRTPRead < NumOfRTPToRead || !singleRTPPacket.NaluIsComplete()) {
        for (; !ExitCmdFromUI; TmpNumOfRTPRead++/*, NumOfRTPRead++*/) {

            if (false) {
                if (LastSeq < CurrSeq && LastSeq + 1 != CurrSeq) {
                    NumOfRTPLost2 += CurrSeq - LastSeq - 1;
                    if (CurrSeq - LastSeq - 1 > 10)
                        System.out.println("[Client] More than 10 packets lost: " + LastSeq + ". Current: " + CurrSeq + ". Lost: " + (CurrSeq - LastSeq - 1));
                }
            }

            if (LastSeq > CurrSeq) {
                //NumOfRTPLost += LastSeq - FirstRTPSeq + 1 - TmpNumOfRTPRead;
                //NumOfRTPLost = CurrSeq  - FirstRTPSeq;
                System.out.println("[Client] Packet loss probability: " + (LastSeq - FirstRTPSeq + 1 - TmpNumOfRTPRead) * 100 / (LastSeq - FirstRTPSeq + 1) + "%");
                FirstRTPSeq = CurrSeq;
                TmpNumOfRTPRead = 1;
            }

//            if (NumOfRTPRead == 100){
//                NumOfRTPRead = 0;
//                TmpBuffer.flip();
//                h264Player.pushData(TmpBuffer.array(), TmpBuffer.limit());
//                System.out.println("[DEBUG] " + TmpBuffer.limit() + " bytes pushed.");
//                TmpBuffer.clear();
//            }




            rc = singleRTPPacket.processSpecialHeader();
            if (-1 == rc) {
                System.out.println("[WARN] ReadRTP: singleRTPPacket.processSpecialHeader() fail.");
                break;
            }

            for (; ; ) {
                frameSize = singleRTPPacket.nextEnclosedFrameSize();

                if (frameSize == 0) {
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
//                        if(NaluSize > 16000)
//                            System.out.println("[Client] Nalu size: " + NaluSize);
//                        NaluSize = frameSize;
                }
//                    else if (28 == singleRTPPacket.getNaluTypeCode())
//                        NaluSize += frameSize;

                //System.out.printf("[Client] RTPByteBuf After StartCode: %x %x\n", RTPByteBuf.get(RTPByteBuf.position()), RTPByteBuf.get(RTPByteBuf.position()+1));

                try {
                    RTPByteBuf.get(NaluPLByteBuf.array(), NaluPLByteBuf.position(), frameSize);
                } catch (Exception e) {
                    System.out.println("NaluPLByteBuf.remaining(): " + NaluPLByteBuf.remaining()
                            + "\nframeSize: " + frameSize
                            + "\nRTPByteBuf.remaining(): " + RTPByteBuf.remaining());

                    e.printStackTrace();
                }
                //System.out.printf("[Client] NaluPLByteBuf After StartCode: %x %x\n", NaluPLByteBuf.get(NaluPLByteBuf.position()), NaluPLByteBuf.get(NaluPLByteBuf.position()+1));
                NaluPLByteBuf.position(NaluPLByteBuf.position() + frameSize);
                NumOfNaluWriteToBuf++;
            }
            NaluPLByteBuf.flip();

            //TmpBuffer.put(NaluPLByteBuf);
            h264Player.pushData(NaluPLByteBuf.array(), NaluPLByteBuf.limit());
            //System.out.println("[DEBUG] " + NaluPLByteBuf.limit() + " bytes pushed.");


            if (WriteToFile) {
                try {
                    fos.write(NaluPLByteBuf.array(), NaluPLByteBuf.position(), NaluPLByteBuf.remaining());
                    fos.flush();

                } catch (Exception e) {
                    e.printStackTrace();
                    return false;
                }
            }


            RTPByteBuf.clear();
            NaluPLByteBuf.clear();

            rc = singleRTPPacket.ReadNextRTPPacket();
            if (0 > rc) {
                System.out.printf("[WARN] RTPReceiver.ReadRTP.singleRTPPacket.ReadNextRTPPacket fail, rc = %d\n\n", rc);
                return false;
            }

            LastSeq = CurrSeq;
            CurrSeq = singleRTPPacket.getSeq();


        }

//        NumOfRTPLost += CurrSeq - FirstRTPSeq + 1 - TmpNumOfRTPRead;
//
//        System.out.println("[Client] RTP report:\n    Total number of RTP received: " + NumOfRTPRead
//                        //+ "\nNumber of Nalu write to buf: " + NumOfNaluWriteToBuf
//                        + "\n    RTP packets lost: " + NumOfRTPLost
//                        + "\n    Packet loss probability: " + NumOfRTPLost * 100 / (NumOfRTPRead+NumOfRTPLost) + "%"
////                        + "\n    RTP packets lost2: " + NumOfRTPLost2
////                        + "\n    Packet loss probability2: " + NumOfRTPLost2 * 100 / NumOfRTPRead + "%"
//
////                + "\nNum Of FU-A RTP Write To Nalu Buf: %d\n"
////                + "\nNum Of STAP-A RTP: %d\n"
////                + "\nNum Of other RTP: %d\n" +
////                            + "\nNum of RTCP: " + NumOfRTCP
////                "Number of FU-A RTP which Order is Incorrect: %d\n"
////                singleRTPPacket.getNumOfFUARTPWriteToNaluBuf(),
////                singleRTPPacket.getNumOfSTAPARTP(),
////                singleRTPPacket.getNumOfOtherRTP(),
//
////                singleRTPPacket.getNumOfRTPOrderIncorrect()
//        );

        singleRTPPacket.PrintTypeCount();


        return true;
    }

    public static void SendStopMsg(){
        if(null == handler)
            return;
        Message message = Message.obtain();
        message.what = 1;
        handler.sendMessage(message);
    }

}
