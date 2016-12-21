package com.leapmotor.gavin.myrtspclient;


import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

/**
 * Created by gavin on 11/4/16.
 */
public class SingleRTPPacket {

    private static final short MinEmbeddedDataLength = 12;
    private static final byte ChannelIDOfRTCP = 1;
    private static final short H264TypeCode = 96;
    private static final byte STAPATypeCode = 24;
    private static final byte FUATypeCode = 28;
    private static final byte StartOrderCode = 2;
    private static final byte EndOrderCode = 1;
    private static final byte MidOrderCode = 0;
    private static final byte[] NaluStartCode = new byte[]{0,0,0,1};

    private static InputStream is;
    private static boolean DEBUG;
    private static boolean TEST;

    public static void PrintTypeCount() {
        byte i = 0;
        for (; i < TypeArray.length; i++) {
            if (TypeArray[i] > 0)
                System.out.println(i + " " + TypeArray[i]);
        }
    }

    private static int[]TypeArray;


    public byte getNaluTypeCode() {
        return NaluTypeCode;
    }

    private byte NaluTypeCode;


    public int embeddedDataLength;

    public byte getOrderCode() {
        return OrderCode;
    }

    /**
     * if Nalu Type is FU-A
     * 2 for Start
     * 1 for End
     * 0 for Middle
     * others are invalid
     * */
    private byte OrderCode;

    private boolean M;  // for video, it is end flag of a frame
    private short PT;           // payload type

    public int getSeq() {
        return seq;
    }

    private int seq;
    private long timestamp;
    private long SSRC;

    private int NumOfOtherRTP;
    private long NumOfByteWrittenToBuf;

    private ByteBuffer RTPByteBuf;

    private byte LastOrderCode;

    public int SameOrderCount;

    public SingleRTPPacket(ByteBuffer RTPByteBuf) {

        this.RTPByteBuf = RTPByteBuf;
        this.OrderCode = -1;
        this.LastOrderCode = EndOrderCode;
        NumOfOtherRTP = 0;
        SameOrderCount = 1;
        TEST = false;
        NumOfByteWrittenToBuf = 0;
    }

    public static void InitializeForClass(InputStream is, boolean DEBUG){
        //is = _is;
        SingleRTPPacket.is = is;
        SingleRTPPacket.DEBUG = DEBUG;
        TypeArray = new int[32];
    }

    public String HdrToString() {
        return "SingleRTPPacket{" +
                "M=" + M +
                ", PT=" + PT +
                ", seq=" + seq +
                ", timestamp=" + timestamp +
                ", SSRC=" + SSRC +
                //", RTPByteBuf=" + RTPByteBuf +
                '}';
    }

    /**
     * this method will be called cyclically
     *  for(;;)
     *      ReadNextRTPPacket();
     *
     * RTP format: | magic number(1 byte: '$') | channel number(1 byte, 0 for RTP, 1 for RTCP)
     * | embedded data length(2 bytes) | data |
     *
     * after this, RTPByteBuf is fill with RTP data without header, pos is at start of RTP Payload.
     *
     * return 1 when read time out.
     * return 2 when read to end.
     */
    public int ReadNextRTPPacket() {

        if (null == is || null == RTPByteBuf || 0 == RTPByteBuf.limit())
            return -1;

        try {
            int count = 0;
            int c;
            short d;
            short channelNumber;
            int curBytesRead;
            //int embeddedDataLength;
            for(;;) {
                curBytesRead = 0;

                c = is.read();
                if (((int) '$') != c) {
                    if (-1 == c)
                        return 2;
                    //System.out.printf("[WARN] first byte is not '$', it is %d\n", c);
                    count++;
                    continue;
                }
//            if(DEBUG)
//                System.out.printf("[DEBUG] first byte： %d\n", c);

                channelNumber = (short) is.read();
                if (DEBUG)
                    System.out.printf("[DEBUG] channelNumber: %d\n", channelNumber);

                c = (short) is.read();
                if (DEBUG)
                    System.out.printf("[DEBUG] SizeH: %d\n", c);
                d = (short) is.read();
                if (DEBUG)
                    System.out.printf("[DEBUG] SizeL: %d\n", d);
                embeddedDataLength = (c << 8) | d;
                if (DEBUG)
                    System.out.printf("[DEBUG] embeddedDataLength = %d\n", embeddedDataLength);

                if (RTPByteBuf.limit() <= embeddedDataLength || MinEmbeddedDataLength >= embeddedDataLength)
                    continue;

                while (curBytesRead < embeddedDataLength) {
                    curBytesRead += is.read(RTPByteBuf.array(), curBytesRead, embeddedDataLength - curBytesRead);
//                if(DEBUG)
//                    System.out.printf("[DEBUG] curBytesRead:  %d\n", curBytesRead);
                }

                if (ChannelIDOfRTCP == channelNumber) //RTCP packet, ignore
                    continue;

                if (DEBUG) {
                    System.out.println("[DEBUG] RTP Header DATA: ");
                    //if(true) {
                    int i = 0;
                    byte tmp = 0;
                    System.out.printf("[DEBUG] ");
                    for (; i < 18; i++) {
                        tmp = RTPByteBuf.get(i);
                        System.out.printf("%d %x, ", i, tmp);
                    }
                    System.out.println();
                }

                RTPByteBuf.position(embeddedDataLength);
                RTPByteBuf.flip();
                int rc = ParseRTPAndGetRTPPayload();

                if (0 != rc) {
                    if (DEBUG)
                        System.out.printf("[WARN] ReadNextRTPPacket.ParseRTPAndGetRTPPayload fail, rc = %d\n\n", rc);
                    continue;   // we will ingore it, just get next packet.
                }

                if (H264TypeCode != PT) {
                    System.out.println("[WARN] ReadNextRTPPacket: not a H264 bit stream, RTP payload type code: " + PT);
                    continue;   // we will ignore it, just get next packet.
                }

                break;
            }
            if (count > 0)
                System.out.printf("[WARN] ReadNextRTPPacket: %d bytes ignored for waiting '$'.\n", count);
        } catch (SocketTimeoutException e) {
            return 1;

        } catch (Exception e) {
            e.printStackTrace();
            return -2;
        }

        return 0;
    }

    /**
     * |V(2)|P(1)|X(1)|cc(4)|M(1)|PT(7)|seq(16)|timestamp(32)|SSRC(32)|
     * |<-- header 12 bytes                                        -->|
     * | CSRC ( cc * 4 * bytes) ...
     * |(2bytes)|NumOfRemExtHdr(2bytes)| remain extension header ...
     * |<-- ExtHdr, length = (1+NumOfRemExtHdr)*4
     */
    private int ParseRTPAndGetRTPPayload(){
        if (null == RTPByteBuf || !RTPByteBuf.hasRemaining())
            return -1;

        byte ByteTmp = RTPByteBuf.get();

        byte version = (byte)((ByteTmp >> 6) & 0x03);
        if (2 != version) {   //RTP version should be 2
            System.out.printf("[WARN] RTP version check fail: %d\n", version);
            return -2;
        }

        boolean P = (ByteTmp & 0x20) > 0;   // flag of padding
        boolean X = (ByteTmp & 0x10) > 0;   // flag of extension header
        short cc = (short)(ByteTmp & 0xf);                 // is number of CSRC

        ByteTmp = RTPByteBuf.get();
        M = (ByteTmp & 0x80) > 0;     // for video, it is end flag of a frame
        PT = (short)(ByteTmp & 0x7f);                  // payload type
        seq = RTPByteBuf.getShort() & 0xffff;
        timestamp = RTPByteBuf.getInt() & 0xffffffffL;
        SSRC = RTPByteBuf.getInt() & 0xffffffffL;

        //if(true)
        if(DEBUG)
            System.out.println("[DEBUG] P: "+P+" X: "+X+" cc: "+cc+ HdrToString());

        if (RTPByteBuf.remaining() < cc * 4)
            return -3;
        if (cc > 0)
            RTPByteBuf.position(RTPByteBuf.position()+cc*4);    //skip CSRC

        if (X){
            RTPByteBuf.get();
            RTPByteBuf.get();
            int NumOfRemExtHdr = RTPByteBuf.getShort();
            if (RTPByteBuf.remaining() < NumOfRemExtHdr * 4)
                return -4;
            RTPByteBuf.position(RTPByteBuf.position()+NumOfRemExtHdr*4);    //skip RemExtHdr
        }

        if (P){
            short LenOfPadding = RTPByteBuf.get(RTPByteBuf.limit()-1);
            if (RTPByteBuf.remaining() < LenOfPadding)
                return -5;
            RTPByteBuf.limit(RTPByteBuf.limit() - LenOfPadding);
        }

        if (RTPByteBuf.capacity() - 12 <= RTPByteBuf.remaining()) {
            System.out.println("[ParseRTPAndGetRTPPayload] RTPByteBuf.remaining is not correct: " + RTPByteBuf.remaining());
            return -6;
        }

        return 0;
    }


    /**
     * analyze RTP PL data get Nalu type, OrderCode, and write data to NaluPLByteBuf
     *
     * EasyDarwin server's most NALU types are FU-A(code = 28) and STAP-A(24), so we only analyze these 2 types.
     * at this time, RTPByteBuf should be like:
     * for FU-A
     * | Indicator(1byte) | FU-A header(1byte) | Nalu data |
     * Indicator:   | F(1bit) | NRI(2bits) | Nal Packet Type(5bits) |
     * FU-A header: | S(1) | E(1) | not care(1) | Nal PL Type(5) |
     *
     * for STAP-A
     * | Indicator(1byte) | length(2bytes) | header(1byte) | NALU data | ... | length(2bytes) | header(1byte) | NALU data | ... |
     * Indicator is same as FU-A.
     *
     *  return value:
     *  1, Nalu get end.
     *  0, not end and ok.
     *  <0, error
     *
     * since ReadNextRTPPacket will flip RTPByteBuf after read, so this func will follow
     * this rule, flip NaluPLByteBuf after copy data from RTPByteBuf. so after call this
     * func, you can read data from NaluPLByteBuf without other operation.
     * */


    /**
     * 去掉RTP包头部之后，在去掉特殊头部。即指针指向特殊头部之后。若Type为28且为start，则将组装后的Nalu的头部写入RTPByteBuf，并把指针指向这个头部。
     * return val:
     *  0: it is start of a nalu.
     *  1: not a start nal.
     *  -1: format error.
     */
    public int processSpecialHeader() {
        int packetSize = RTPByteBuf.remaining();
        int RTPPLPos = RTPByteBuf.position();
        if (packetSize < 1) return -1;
        NaluTypeCode = (byte)(RTPByteBuf.get(RTPPLPos)&0x1F);

        // System.out.println("get type: " + NaluTypeCode);

        if (NaluTypeCode < 32)
            TypeArray[NaluTypeCode]++;
        else
            TypeArray[32]++;

//        if (NaluTypeCode != 28)
            //System.out.println("[processSpecialHeader] Type: " + NaluTypeCode);
        switch (NaluTypeCode) {
            case 24:
            { // STAP-A
                // numBytesToSkip = 1; // discard the type byte
                RTPByteBuf.position(RTPPLPos+1);
                break;
            }
            case 25: case 26: case 27:
            { // STAP-B, MTAP16, or MTAP24
                // numBytesToSkip = 3; // discard the type byte, and the initial DON
                RTPByteBuf.position(RTPPLPos+3);

                break;
            }
            case 28: case 29:
            { // // FU-A or FU-B
                // For these NALUs, the first two bytes are the FU indicator and the FU header.
                // If the start bit is set, we reconstruct the original NAL header into byte 1:
                if (packetSize < 2) return -1;
                byte OrderCode = (byte) (RTPByteBuf.get(RTPPLPos+1) >> 6 & 3);

                if (StartOrderCode == OrderCode) {
                    RTPByteBuf.array()[RTPPLPos+1] = (byte) ((RTPByteBuf.get(RTPPLPos) & 0xe0) | (RTPByteBuf.get(RTPPLPos+1) & 0x1f));

                    // numBytesToSkip = 1;
                    RTPByteBuf.position(RTPPLPos+1);
                } else {
                    // The start bit is not set, so we skip both the FU indicator and header:
                    // fCurrentPacketBeginsFrame = False;
                    // numBytesToSkip = 2;
                    RTPByteBuf.position(RTPPLPos+2);
                    return 1;
                }
                break;
            }
            default:
            {
                // This packet contains one complete NAL unit:
                // numBytesToSkip = 0;
                break;
            }
        }

        return 0;

    }

    /**
     * 指针略过需要忽略的部分，比如type为24的类型，需要略过2字节的长度。并返回需要读取的长度。
     */
    public int nextEnclosedFrameSize(/*unsigned char*& framePtr*/) {
        int resultNALUSize = 0; // if an error occurs

        switch (NaluTypeCode) {
            case 24: case 25:
            { // STAP-A or STAP-B
                // The first two bytes are NALU size:
                if (RTPByteBuf.remaining() < 2) break;

                // resultNALUSize = (framePtr[0] << 8) | framePtr[1];
                // framePtr += 2;
                resultNALUSize = RTPByteBuf.getShort() & 0xffff;

                break;
            }
            case 26:
            { // MTAP16
                // The first two bytes are NALU size.  The next three are the DOND and TS offset:
                if (RTPByteBuf.remaining() < 5) break;
                // resultNALUSize = (framePtr[0] << 8) | framePtr[1];
                // framePtr += 5;
                resultNALUSize = RTPByteBuf.getShort() & 0xffff;
                RTPByteBuf.position(RTPByteBuf.position()+3);

                break;
            }
            case 27:
            { // MTAP24
                // The first two bytes are NALU size.  The next four are the DOND and TS offset:
                if (RTPByteBuf.remaining() < 6) break;
//                resultNALUSize = (framePtr[0] << 8) | framePtr[1];
//                framePtr += 6;
                resultNALUSize = RTPByteBuf.getShort() & 0xffff;
                RTPByteBuf.position(RTPByteBuf.position()+4);

                break;
            }
            default:
            {
                // Common case: We use the entire packet data:
                return RTPByteBuf.remaining();
            }
        }

        if(resultNALUSize > RTPByteBuf.remaining()) {
            System.out.println("[WARN] Read frameSize error. remaining is less then frameSize. Remaining will be copied to buf."
                    + "\nframeSize: " + resultNALUSize
                    + " Remaining: " + RTPByteBuf.remaining()
                    + " Type: " + NaluTypeCode
            );
            return RTPByteBuf.remaining();
        }
        else if (RTPByteBuf.capacity() - 12 < RTPByteBuf.remaining()){
            System.out.println("[WARN] RTPByteBuf remaining error. Nothing to copy."
                    + "\nframeSize: " + resultNALUSize
                    + " Remaining: " + RTPByteBuf.remaining()
                    + " Type: " + NaluTypeCode
            );
            return 0;

        }


        return resultNALUSize;
    }




}
