package com.leapmotor.gavin.myrtspclient;

import java.nio.ByteBuffer;
//import car.loger.Loger;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.SurfaceHolder.Callback;

public class H264Player implements Callback
{
    private SurfaceView view;
    public H264Player(SurfaceView view)
    {
        this.view    = view;
        this.view.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder)
    {
        //Loger.DEBUG("surfaceCreated");
        synchronized (this)
        {
            initCodec();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
        //Loger.DEBUG("surfaceChanged width:"+width+" height:"+height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder)
    {
        //Loger.DEBUG("surfaceDestroyed");

        synchronized (this)
        {
            if (isInit)
            {
                isInit = false;
                mCodec.stop();
                mCodec.release();
                mCodec = null;

                isStart = false;
                frameLength = 0;
                framebuffer = null;
            }
        }
    }

    private void initCodec()
    {
        try {
            mCodec = MediaCodec.createDecoderByType(MIME_TYPE);
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT);
            mCodec.configure(mediaFormat, view.getHolder().getSurface(), null, 0);
            mCodec.start();

            framebuffer = new byte[BUFFER_LENGTH];
            isInit = true;
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    // Video Constants
    private final static String MIME_TYPE = "video/avc"; // H.264 Advanced Video
    private final static int VIDEO_WIDTH   = 1280;
    private final static int VIDEO_HEIGHT  = 720;
    private final static int TIME_INTERNAL = 40;
    private final static int HEAD_OFFSET   = 2;
    private final static int BUFFER_LENGTH = 1024*1024;

    private MediaCodec mCodec;
    private boolean isInit = false;
    private boolean isStart = false;
    private int frameLength = 0;
    private byte[] framebuffer;
    public void pushData(byte[] data, int length)
    {
        if (!isInit) return;

        synchronized (this)
        {
            // 数据拷贝进buffer，超出长度时前面的数据丢掉
            if (frameLength + length > BUFFER_LENGTH)
            {
                //Loger.ERROR("lost:" + frameLength);
                frameLength = 0;
                if (length > BUFFER_LENGTH) length = BUFFER_LENGTH;
            }

            System.arraycopy(data, 0, framebuffer, frameLength, length);
            frameLength += length;

            // 找帧头
            int offset = 0;
            int len = findHead(framebuffer, offset, frameLength-length-4, frameLength);
            while (len > 0)
            {
                ////Loger.DEBUG("offset:"+offset+" len:"+len);
                if (checkHead(framebuffer, offset) && isInit)
                {
                    // 开始解码的第一帧数据带sps的I帧
                    if (isStart || (isStart = checkIFrame(framebuffer, offset)))
                    {
                        onFrame(framebuffer, offset, len);
                    }
                }

                // 继续找下一帧头
                offset += len;
                len = findHead(framebuffer, offset, 0, frameLength-offset);
            }

            // 帧数据被使用后，剩余的数据前移
            if (offset > 0)
            {
                for (len = 0; offset < frameLength; ++offset)
                {
                    framebuffer[len] = framebuffer[offset];
                    ++len;
                }
                frameLength = len;
            }
        }
    }

    int count = 0;
    private boolean onFrame(byte[] buf, int offset, int length)
    {
        ////Loger.DEBUG("onFrame start");
        // Get input buffer index
        ByteBuffer[] inputBuffers = mCodec.getInputBuffers();
        int inputBufferIndex = mCodec.dequeueInputBuffer(1000);

        ////Loger.DEBUG("Frame index:" + inputBufferIndex);
        //System.out.println("[H264Player] Frame index:" + inputBufferIndex);
        if (inputBufferIndex >= 0)
        {
            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
            inputBuffer.clear();
            inputBuffer.put(buf, offset, length);
            mCodec.queueInputBuffer(inputBufferIndex, 0, length, count*TIME_INTERNAL, 0);
            ++count;
        }
        else
        {
            return false;
        }

        // Get output buffer index
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex = mCodec.dequeueOutputBuffer(bufferInfo, 1000);
        while (outputBufferIndex >= 0)
        {
            mCodec.releaseOutputBuffer(outputBufferIndex, true);
            outputBufferIndex = mCodec.dequeueOutputBuffer(bufferInfo, 0);
        }
        ////Loger.DEBUG("onFrame end");
        return true;
    }

    /**
     * Find H264 frame head
     *
     * @param buffer
     * @param len
     * @return the offset of frame head, return 0 if can not find one
     */
    static int findHead(byte[] buffer, int offset, int invalidLen, int len)
    {
        int start = HEAD_OFFSET > (len-invalidLen) ? HEAD_OFFSET : (len-invalidLen);
        for (start = HEAD_OFFSET; start < (len-4); start++)
        {
            if (checkHead(buffer, start+offset)) return start;
        }

        return 0;
    }

    /**
     * Check if is H264 frame head
     *
     * @param buffer
     * @param offset
     * @return whether the src buffer is frame head
     */
    static boolean checkHead(byte[] buffer, int offset)
    {
        // 00 00 00 01
        if (buffer[offset + 0] == 0 && buffer[offset + 1] == 0 &&
                buffer[offset + 2] == 0 && buffer[offset + 3] == 1)
        {
            return true;
        }

        // 00 00 01
        if (buffer[offset + 0] == 0 && buffer[offset + 1] == 0	&&
                buffer[offset + 2] == 1)
        {
            return true;
        }

        return false;
    }

    static boolean checkIFrame(byte[] buffer, int offset)
    {
        // 00 00 00 01
        if (buffer[offset + 0] == 0 && buffer[offset + 1] == 0 &&
                buffer[offset + 2] == 0 && buffer[offset + 3] == 1 &&
                (buffer[offset + 4]&0x1F) != 1)
        {
            return true;
        }

        // 00 00 01
        if (buffer[offset + 0] == 0 && buffer[offset + 1] == 0	&&
                buffer[offset + 2] == 1 && (buffer[offset + 3]&0x1F) != 1)
        {
            return true;
        }

        return false;
    }
}
