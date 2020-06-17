package org.gregoire.media.rtmp;

public class RTMPPrefix {

    public final static byte[] H264_CONFIG_PREFIX = { 0x17, 0, 0, 0, 0 };

    public final static byte[] H264_KEYFRAME_PREFIX = { 0x17, 0x01, 0, 0, 0 };

    public final static byte[] H264_INTRAFRAME_PREFIX = { 0x27, 0x01, 0, 0, 0 };

}
