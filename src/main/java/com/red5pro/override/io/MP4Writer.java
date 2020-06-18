package com.red5pro.override.io;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.mina.core.buffer.IoBuffer;
import org.bouncycastle.util.encoders.Hex;
import org.gregoire.media.aac.AACCodec;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.decode.SliceHeaderReader;
import org.jcodec.codecs.h264.io.model.NALUnit;
import org.jcodec.codecs.h264.io.model.NALUnitType;
import org.jcodec.codecs.h264.io.model.PictureParameterSet;
import org.jcodec.codecs.h264.io.model.RefPicMarking;
import org.jcodec.codecs.h264.io.model.RefPicMarking.InstrType;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.SliceHeader;
import org.jcodec.codecs.h264.io.write.SliceHeaderWriter;
import org.jcodec.codecs.h264.mp4.AvcCBox;
import org.jcodec.common.AudioCodecMeta;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.DemuxerTrackMeta.Orientation;
import org.jcodec.common.IntObjectMap;
import org.jcodec.common.MuxerTrack;
import org.jcodec.common.TrackType;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.io.BitReader;
import org.jcodec.common.io.BitWriter;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Packet.FrameType;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mp4.MP4Packet;
import org.jcodec.containers.mp4.MP4TrackType;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.containers.mp4.muxer.MP4Muxer;
import org.red5.codec.AudioCodec;
import org.red5.codec.VideoCodec;
import org.red5.io.IStreamableFile;
import org.red5.io.ITag;
import org.red5.io.ITagWriter;
import org.red5.io.mp4.IMP4;
import org.red5.media.processor.IPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.red5pro.override.io.mp4.MP4MuxerTrack;
import com.red5pro.util.ParsableBitArray;

/**
 * A Writer is used to write the contents of an MP4 file, using the jcodec lib.
 * 
 * References for merging mp4 files:
 * https://github.com/jcodec/jcodec/blob/master/samples/main/java/org/jcodec/samples/mashup/MovStitch2.java
 * https://github.com/jcodec/jcodec/blob/82cdc2e683cdfe54acc12e92fdfb315156e3708a/samples/main/java/org/jcodec/samples/mashup/H264Mashup.java
 *
 * @author Paul Gregoire
 */
public class MP4Writer implements ITagWriter {

    private static Logger log = LoggerFactory.getLogger(MP4Writer.class);

    private final static boolean isTrace = log.isTraceEnabled();

    private final static boolean isDebug = log.isDebugEnabled();

    /**
     * MP4 object
     */
    private static IMP4 mp4;

    /**
     * Number of bytes written
     */
    private volatile long bytesWritten;

    /**
     * Position in file
     */
    private int offset;

    /**
     * Id of the audio codec used
     */
    private volatile int audioCodecId = -1;

    /**
     * Id of the video codec used
     */
    private volatile int videoCodecId = -1;

    /**
     * If audio configuration data has been written
     */
    private AtomicBoolean audioConfigWritten = new AtomicBoolean(false);

    /**
     * If video configuration data has been written
     */
    private AtomicBoolean videoConfigWritten = new AtomicBoolean(false);

    // Path to the output file
    private Path filePath;

    private int audioSampleRate = 44100;

    /*
        1: 1 channel: front-center
        2: 2 channels: front-left, front-right
     */
    private int audioChannels = 1; // mono

    /*
        1: AAC Main
        2: AAC LC (Low Complexity)
        3: AAC SSR (Scalable Sample Rate)
        4: AAC LTP (Long Term Prediction)
        5: SBR (Spectral Band Replication)
        6: AAC Scalable
     */
    private int aacProfile = 2; // LC

    private int aacFrequencyIndex = -1;

    private MP4Muxer muxer;

    private SeekableByteChannel dataChannel;

    private MuxerTrack aacTrack, h264Track;

    private IntObjectMap<PictureParameterSet> ppsMap = new IntObjectMap<>();

    private IntObjectMap<SeqParameterSet> spsMap = new IntObjectMap<>();

    private NALUnit prevNalu;

    private SliceHeader prevSliceHeader;

    private H264State h264State = new H264State();

    // holds nalu until a frame is complete
    private IoBuffer nalQueue = IoBuffer.allocate(8192).setAutoExpand(true);

    private int audioFrameNo;

    // current orientation
    private Orientation orientation = Orientation.D_0;

    private int width, height;

    // sequence / picture parameter set id
    private int spParameterSetId = 0;

    // codec private etc for the h.264 video
    private AvcCBox avcCBox;

    // used for packet fps
    private int videoTimeScale = 25, videoDuration = 1;

    // centrally located size guess-timates
    private final static int SPS_SIZE = 420, PPS_SIZE = 8;

    // flag to indicate the avcc box needs to be updated
    private AtomicBoolean updateAvcC = new AtomicBoolean(false);

    /**
     * Creates writer implementation with for a given file
     * 
     * @param filePath
     *            path to existing file
     */
    public MP4Writer(String filePath) {
        this(Paths.get(filePath), false);
    }

    /**
     * Creates writer implementation with given file and flag indicating whether or not to append.
     *
     * MP4.java uses this constructor so we have access to the file object
     *
     * @param file
     *            File output stream
     * @param append
     *            true if append to existing file
     */
    public MP4Writer(File file, boolean append) {
        this(file.toPath(), append);
    }

    /**
     * Creates writer implementation with given file and flag indicating whether or not to append.
     *
     * MP4.java uses this constructor so we have access to the file object
     *
     * @param path
     *            File output path
     * @param append
     *            true if append to existing file
     */
    @SuppressWarnings("unused")
    public MP4Writer(Path path, boolean append) {
        log.trace("Recording path: {}", path);
        filePath = path;
        // create a path for renaming if needed
        Path oldFilePath = path.resolveSibling(path.toFile().getName().replace(".mp4", ".old"));
        if (append) {
            log.debug("Setting up for appending {} {}", path, oldFilePath);
            try {
                // move/rename
                Files.move(path, oldFilePath, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) {
                log.warn("Rename/move failed", t);
                append = false;
            }
        }
        log.debug("Recording to: {}", filePath);
        try {
            // create file output and its channel for the muxer
            dataChannel = NIOUtils.writableChannel(filePath.toFile());
            muxer = MP4Muxer.createMP4MuxerToChannel(dataChannel);
        } catch (Exception e) {
            log.error("Failed to create MP4 writer", e);
        }
        // if append is good-to-go
        if (append) {
            log.debug("Appending to: {}", filePath);
            // create a demuxer for the previous file
            MP4Demuxer demuxer = null;
            // metadata via the demuxer
            DemuxerTrackMeta videoMeta, audioMeta;
            try {
                log.debug("Opening source file demuxing {}", oldFilePath);
                // instance demuxer
                demuxer = MP4Demuxer.createMP4Demuxer(NIOUtils.readableChannel(oldFilePath.toFile()));
                // only handling single a/v tracks att
                try {
                    DemuxerTrack at = demuxer.getAudioTracks().get(0);
                    DemuxerTrackMeta ameta = at.getMeta();
                    AudioCodecMeta audioCodecMeta = ameta.getAudioCodecMeta();
                    aacTrack = muxer.addAudioTrack(Codec.AAC, audioCodecMeta);
                    audioMeta = new DemuxerTrackMeta(TrackType.AUDIO, Codec.AAC, 0, null, 0, null, null, audioCodecMeta);
                    for (int i = 0; i < ameta.getTotalFrames(); i++) {
                        aacTrack.addFrame((MP4Packet) at.nextFrame());
                    }
                    // set the config written flag
                    audioConfigWritten.set(true);
                } catch (IndexOutOfBoundsException iobe) {
                    log.warn("No source audio track found", iobe);
                }
                // video
                try {
                    DemuxerTrack vt = demuxer.getVideoTracks().get(0);
                    DemuxerTrackMeta vmeta = vt.getMeta();
                    VideoCodecMeta videoCodecMeta = vmeta.getVideoCodecMeta();
                    h264Track = muxer.addVideoTrack(Codec.H264, videoCodecMeta);
                    videoMeta = new DemuxerTrackMeta(TrackType.VIDEO, Codec.H264, 0d, null, 0, null, videoCodecMeta, null);
                    for (int i = 0; i < vmeta.getTotalFrames(); i++) {
                        h264Track.addFrame((MP4Packet) vt.nextFrame());
                    }
                    // get previous orientation; default is 0
                    orientation = vmeta.getOrientation();
                    // get prev wxh
                    Size size = vmeta.getVideoCodecMeta().getSize();
                    if (size != null) {
                        width = size.getWidth();
                        height = size.getHeight();
                    }
                    // set the config written flag
                    videoConfigWritten.set(true);
                } catch (IndexOutOfBoundsException iobe) {
                    log.warn("No source video track found", iobe);
                }
            } catch (IOException e) {
                log.warn("Exception handling existing file for append", e);
            } finally {
                if (demuxer != null) {
                    try {
                        demuxer.close();
                        Files.delete(oldFilePath);
                    } catch (IOException e) {
                        log.warn("Exception removing previous source file", e);
                    }
                }
            }
        }
    }

    @Override
    public void writeHeader() throws IOException {
        // no-op
    }

    @Override
    public boolean writeStream(byte[] b) {
        // not supported
        return false;
    }

    @Override
    public boolean writeTag(byte type, IoBuffer data) throws IOException {
        // not supported
        return false;
    }

    @Override
    public boolean writeTag(ITag tag) throws IOException {
        //log.trace("writeTag: {}", tag);
        // skip tags with no data
        int bodySize = tag.getBodySize();
        //if (isTrace) {
        //    log.trace("Tag body size: {}", bodySize);
        //}
        // ensure that the channel is still open
        if (dataChannel != null && dataChannel.isOpen()) {
            // get the data type
            final byte dataType = tag.getDataType();
            // when tag is ImmutableTag which is in red5-server-common.jar, tag.getBody().reset() will throw InvalidMarkException because 
            // ImmutableTag.getBody() returns a new IoBuffer instance every time.
            IoBuffer tagBody = tag.getBody();
            //if (isTrace) {
            //    log.trace("Tag body: {}", Hex.toHexString(tagBody.array()));
            //}
            if (bodySize > 0) {
                byte[] data;
                // codec id, format etc..
                int id = tagBody.get() & 0xff; // must be unsigned
                // process based on the type identifier
                switch (dataType) {
                    case ITag.TYPE_AUDIO:
                        audioCodecId = (id & ITag.MASK_SOUND_FORMAT) >> 4;
                        //log.trace("Audio codec id: {}", audioCodecId);
                        // if aac use defaults
                        if (audioCodecId == AudioCodec.AAC.getId()) {
                            //log.trace("AAC audio type");
                            // this is aac data, so a config chunk should be written before any media data
                            if (tagBody.get() == 0) { // position 1
                                // pull-out in-line config data
                                byte objAndFreq = tagBody.get();
                                byte freqAndChannel = tagBody.get();
                                aacProfile = ((objAndFreq & 0xFF) >> 3) & 0x1F;
                                aacFrequencyIndex = (objAndFreq & 0x7) << 1 | (freqAndChannel >> 7) & 0x1;
                                audioSampleRate = AACCodec.samplingFrequencyIndexMap.get(aacFrequencyIndex);
                                audioChannels = (freqAndChannel & 0x78) >> 3;
                                log.debug("AAC config - profile: {} freq: {} rate: {} channels: {}", new Object[] { aacProfile, aacFrequencyIndex, audioSampleRate, audioChannels });
                                // when this config is written set the flag
                                if (audioConfigWritten.compareAndSet(false, true)) {
                                    // create the metadata for audio
                                    // fourcc, sampleSize, channelCount, sampleRate, endian, pcm, Label[] labels, codecPrivate
                                    AudioCodecMeta meta = AudioCodecMeta.createAudioCodecMeta("mp4a", 16, audioChannels, audioSampleRate, ByteOrder.LITTLE_ENDIAN, false, null, null);
                                    // add the audio track
                                    MP4MuxerTrack track = new MP4MuxerTrack(muxer.getNextTrackId(), MP4TrackType.SOUND, Codec.AAC);
                                    track.addAudioSampleEntry(meta.getFormat());
                                    aacTrack = muxer.addTrack(track);
                                }
                                // return true, the aac streaming track impl doesnt like our af 00 configs
                                return true;
                            } else if (!audioConfigWritten.get()) {
                                // reject packet since config hasnt been written yet
                                log.debug("Rejecting AAC data since config has not yet been written");
                                return false;
                            }
                        } else {
                            log.debug("Rejecting non-AAC data, codec id: {}", audioCodecId);
                            return false;
                        }
                        // add ADTS header
                        // ref https://wiki.multimedia.cx/index.php/ADTS
                        data = new byte[bodySize + 5]; // (bodySize - 2) + 7 (no protection)
                        if (aacFrequencyIndex == -1) {
                            aacFrequencyIndex = AACCodec.samplingFrequencyIndexMap.get(audioSampleRate);
                        }
                        int finallength = data.length;
                        data[0] = (byte) 0xff; // syncword 0xFFF, all bits must be 1
                        data[1] = (byte) 0b11110001; // mpeg v0, layer 0, protection absent
                        data[2] = (byte) (((aacProfile - 1) << 6) + (aacFrequencyIndex << 2) + (audioChannels >> 2));
                        data[3] = (byte) (((audioChannels & 0x3) << 6) + (finallength >> 11));
                        data[4] = (byte) ((finallength & 0x7ff) >> 3);
                        data[5] = (byte) (((finallength & 7) << 5) + 0x1f);
                        data[6] = (byte) 0xfc;
                        // slice out what we want, skip af 01; offset to 7
                        tagBody.get(data, 7, bodySize - 2);
                        //if (isTrace) {
                        //    log.trace("ADTS body: {}", Hex.toHexString(data));
                        //}
                        // write to audio out
                        Packet packet = Packet.createPacket(ByteBuffer.wrap(data), audioFrameNo * 1024, audioSampleRate, 1024, audioFrameNo, FrameType.KEY, null);
                        // increment audio frame number
                        ++audioFrameNo;
                        // add audio data to the track
                        aacTrack.addFrame(packet);
                        // increment bytes written
                        bytesWritten += data.length;
                        break;
                    case ITag.TYPE_VIDEO:
                        videoCodecId = id & ITag.MASK_VIDEO_CODEC;
                        //log.trace("Video codec id: {}", videoCodecId);
                        if (videoCodecId == VideoCodec.AVC.getId()) {
                            // this is avc/h264 data, so a config chunk should be written before any media data
                            if (tagBody.get() == 0) { // position 1
                                //log.trace("Config body: {}", tagBody);
                                // sps/pps
                                SeqParameterSet sps = null;
                                PictureParameterSet pps = null;
                                // store codec private bytes by way of the AvcCBox
                                ByteBuffer buf = tagBody.buf();
                                // move past bytes we dont care about
                                buf.position(5);
                                log.debug("AvcCBox data: {}", Hex.toHexString(buf.array(), 5, buf.limit() - 5));
                                avcCBox = AvcCBox.parseAvcCBox(buf);
                                // make a copy of the bb for reading
                                sps = SeqParameterSet.read(avcCBox.getSpsList().get(0).duplicate());
                                spsMap.put(sps.seqParameterSetId, sps);
                                // make a copy of the bb for reading
                                pps = PictureParameterSet.read(avcCBox.getPpsList().get(0).duplicate());
                                ppsMap.put(pps.picParameterSetId, pps);
                                log.info("SPS id: {} PPS id: {} profile idc: {}", sps.seqParameterSetId, pps.picParameterSetId, sps.profileIdc);
                                // get size
                                Size size = H264Utils.getPicSize(sps);
                                if (size != null) {
                                    width = size.getWidth();
                                    height = size.getHeight();
                                }
                                log.info("Video config: {} {}x{}", orientation, size.getWidth(), size.getHeight());
                                // when this config is written set the flag
                                if (videoConfigWritten.compareAndSet(false, true)) {
                                    // get our track id
                                    int trackId = muxer.getNextTrackId();
                                    // create the metadata and add the video track
                                    MP4MuxerTrack track = new MP4MuxerTrack(trackId, MP4TrackType.VIDEO, Codec.H264);
                                    track.addVideoSampleEntry(VideoCodecMeta.createSimpleVideoCodecMeta(size, ColorSpace.YUV420));
                                    track.setOrientation(orientation);
                                    // set the timing - 25 fps as a default
                                    //track.setTgtChunkDuration(new Rational(25, 1), Unit.FRAME);
                                    // add the track to the muxer
                                    h264Track = muxer.addTrack(track);
                                    // store the avcC track content on the track
                                    ((MP4MuxerTrack) h264Track).setAvcC(avcCBox);
                                    // get the sps/pps data into bb for nal writes
                                    ByteBuffer spsBuf = ByteBuffer.allocate(SPS_SIZE); // annexb + nalu type + data
                                    spsBuf.putInt(1);
                                    spsBuf.put((byte) 0x67); // SPS
                                    sps.write(spsBuf);
                                    spsBuf.flip();
                                    ByteBuffer ppsBuf = ByteBuffer.allocate(PPS_SIZE); // annexb + nalu type + 3b
                                    ppsBuf.putInt(1);
                                    ppsBuf.put((byte) 0x68); // PPS
                                    pps.write(ppsBuf);
                                    ppsBuf.flip();
                                    // put sps into the nal queue
                                    nalQueue.put(spsBuf);
                                    if (isTrace) {
                                        log.trace("SPS - length: {} {}", spsBuf.array().length, Hex.toHexString(spsBuf.array(), 0, spsBuf.limit()));
                                    }
                                    // put pps into the nal queue
                                    nalQueue.put(ppsBuf);
                                    if (isTrace) {
                                        log.trace("PPS - length: {} {}", ppsBuf.array().length, Hex.toHexString(ppsBuf.array(), 0, ppsBuf.limit()));
                                    }
                                }
                            } else if (!videoConfigWritten.get()) {
                                // reject packet since config hasnt been written yet
                                log.debug("Rejecting AVC data since config has not yet been written");
                                return false;
                            } else {
                                // create a byte buffer from the tagbody iobuffer (assumed to contain 1..n nalu)
                                ByteBuffer nalus = ByteBuffer.wrap(Arrays.copyOfRange(tagBody.array(), 5, tagBody.remaining() - 5));
                                if (isTrace) {
                                    // annex-b nalu
                                    log.trace("NALU body snip: {}", Hex.toHexString(nalus.array(), 0, Math.max(32, nalus.array().length / 3)));
                                    nalus.mark();
                                    log.warn("Check for Annex-b aaa: {}", nalus.getInt());
                                    // reset for read
                                    nalus.reset();
                                }
                                // need at least the size of the frame, so 4 bytes minimum
                                while (nalus.remaining() >= 4) {
                                    // put the data into a bb
                                    ByteBuffer nalu;
                                    // mark so we can reset back to the expected position
                                    nalus.mark();
                                    // before advancing past annex-b prefix, ensure its not a size
                                    int frameSize = nalus.getInt();
                                    if (isDebug) {
                                        log.debug("Frame size: {}", frameSize);
                                    }
                                    // no nalu lengths are less than 2
                                    if (frameSize > 1) {
                                        log.warn("Not Annex-b, read nalu size. Body position: {}", nalus.position());
                                        // reset for read of data length
                                        //nalus.reset();
                                        // read the size
                                        //frameSize = (nalus.get() & 0xFF);
                                        //frameSize = frameSize << 8 | (nalus.get() & 0xFF);
                                        //frameSize = frameSize << 8 | (nalus.get() & 0xFF);
                                        //frameSize = frameSize << 8 | (nalus.get() & 0xFF);
                                        //log.debug("Frame size (parsed): {}", frameSize);
                                        // XXX an error seemingly exists or existed that caused the frame length to be off by 7 bytes
                                        int diff = frameSize - nalus.remaining();
                                        if (diff > 7) {
                                            log.warn("Bad h264 frame...frameSize {} available: {}", frameSize, nalus.remaining());
                                            return false;
                                        } else {
                                            // resize based on whats available
                                            data = new byte[diff <= 0 ? frameSize : frameSize - diff];
                                        }
                                        // pull data out
                                        nalus.get(data);
                                        if (isDebug) {
                                            log.debug("Grabbed nalu out {}, body position: {}", data.length, nalus.position());
                                        }
                                        // size up single nalu
                                        nalu = ByteBuffer.allocate(frameSize + 4);
                                        nalu.putInt(1); // annex-b 00,00,00,01
                                        nalu.put(data);
                                        nalu.flip();
                                    } else {
                                        // reset back
                                        nalus.reset();
                                        // slice out the nalu
                                        nalu = nalus.slice();
                                        // advance the nalus beyond our sliced out nalu
                                        nalus.position(nalu.limit());
                                    }
                                    // jump past prefix
                                    nalu.position(4);
                                    // read the nalu
                                    NALUnit nu = NALUnit.read(nalu);
                                    if (nu.type == NALUnitType.IDR_SLICE || nu.type == NALUnitType.NON_IDR_SLICE) {
                                        log.info("Slice type: {}", nu.type);
                                        // regular frame handling
                                        SliceHeader sh = h264State.readSliceHeader(nu, nalu);
                                        if (prevNalu != null && prevSliceHeader != null && !h264State.sameFrame(prevNalu, nu, prevSliceHeader, sh)) {
                                            // start of a new frame, so its time to process the nals in the queue
                                            nalQueue.flip();
                                            // increment bytes written
                                            bytesWritten += nalQueue.remaining();
                                            // construct a packet and send it to the track
                                            Packet outPacket = h264State.detectPoc(prevNalu, prevSliceHeader, nalQueue.buf());
                                            if (isDebug) {
                                                log.debug("Adding frame {} (pts: {}) to track: {} write total: {}", outPacket.getFrameNo(), outPacket.getPts(), ((MP4MuxerTrack) h264Track).getTrackId(), bytesWritten);
                                                //log.trace("Packet data size: {}", outPacket.getData().remaining());
                                            }
                                            h264Track.addFrame(outPacket);
                                            // clear the queue and prepare for new nalu
                                            nalQueue.clear();
                                            // return back to normal flow
                                        }
                                        // keep track incoming nalu/slice
                                        prevSliceHeader = sh;
                                        prevNalu = nu;
                                        // rewind and queue the nalu
                                        nalu.rewind();
                                        log.debug("Adding nal sized: {} to queue: {}", nalu.remaining(), nalQueue.position());
                                        nalQueue.put(nalu);
                                        log.debug("Added nal to queue: {}", nalQueue.position());
                                    } else {
                                        log.info("Non-slice type: {}", nu.type);
                                        // nalu is annexb here 0001 prefix
                                        if (nu.type == NALUnitType.SPS) {
                                            //log.debug("SPS: {}", Hex.toHexString(nalu.array()));
                                            SeqParameterSet sps = SeqParameterSet.read(nalu);
                                            // reset the dimensions
                                            Size size = H264Utils.getPicSize(sps);
                                            if (size != null) {
                                                // ensure that dimensions have changed before saving a new sps
                                                int w = size.getWidth();
                                                int h = size.getHeight();
                                                if (w != width && h != height) {
                                                    log.debug("Updated dimensions: {}x{}", w, h);
                                                    width = w;
                                                    height = h;
                                                    log.debug("Adding SPS {} to map", sps.seqParameterSetId);
                                                    spsMap.put(sps.seqParameterSetId, sps);
                                                    // set the position just past the nal type
                                                    nalu.position(5);
                                                    avcCBox.getSpsList().clear();
                                                    avcCBox.getSpsList().add(spParameterSetId, nalu);
                                                    // replace the meta since we've decided on the correct size for orientation at 0 degrees
                                                    ((MP4MuxerTrack) h264Track).replaceVideoSampleEntry(VideoCodecMeta.createSimpleVideoCodecMeta(size, ColorSpace.YUV420));
                                                    // update to ensure 0 degrees orientation
                                                    ((MP4MuxerTrack) h264Track).setOrientation(Orientation.D_0);
                                                    // set flag so next pps incoming will get stored
                                                    updateAvcC.set(true);
                                                    // add to the nal queue so when an I or P come in it'll get written
                                                    nalu.rewind();
                                                    // add sps to the track
                                                    nalQueue.put(nalu);
                                                }
                                            }
                                        } else if (nu.type == NALUnitType.PPS) {
                                            if (updateAvcC.compareAndSet(true, false)) {
                                                PictureParameterSet pps = PictureParameterSet.read(nalu);
                                                log.debug("Adding PPS {} {} to map", pps.picParameterSetId, pps.seqParameterSetId);
                                                ppsMap.put(pps.picParameterSetId, pps);
                                                // set the position just past the nal type
                                                nalu.position(5);
                                                avcCBox.getPpsList().clear();
                                                avcCBox.getPpsList().add(spParameterSetId, nalu);
                                                // set the update avcC box
                                                ((MP4MuxerTrack) h264Track).setAvcC(avcCBox);
                                                // add to the nal queue so when an I or P come in it'll get written
                                                nalu.rewind();
                                                // add pps to the track
                                                nalQueue.put(nalu);
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            log.debug("Rejecting non-AVC data");
                            return false;
                        }
                        break;
                    case ITag.TYPE_METADATA:
                        log.trace("Metadata! current state: {}", orientation, width, height);
                        // TODO we'll need a special track to store metadata, skipping it for now
                        break;
                    default:
                        log.info("Unhandled tag type: {}", dataType);
                }
            }
            return true;
        } else {
            // throw an exception and let them know the cause
            throw new IOException("MP4 write channel has been closed", new ClosedChannelException());
        }
    }


    @Override
    public void close() {
        log.debug("close");
        // finish up our mp4 writer work
        try {
            // no tracks, nothing to finish
            if (muxer.getTracks().size() > 0) {
                // wrap-up writing to the mp4
                muxer.finish();
                log.debug("MP4 muxer finished");
            }
        } catch (Exception e) {
            log.warn("Exception at close", e);
        } finally {
            if (dataChannel != null) {
                // close dataChannel
                try {
                    dataChannel.close();
                } catch (IOException e) {
                }
            }
            // free the iobuffer
            nalQueue.free();
        }
    }

    @Override
    public void addPostProcessor(IPostProcessor postProcessor) {
        throw new UnsupportedOperationException("Post-processing not supported for MP4");
    }

    @Override
    public long getBytesWritten() {
        return bytesWritten;
    }

    @Override
    public IStreamableFile getFile() {
        return mp4;
    }

    @Override
    public int getOffset() {
        return offset;
    }

    public int getVideoTimeScale() {
        return videoTimeScale;
    }

    public void setVideoTimeScale(int videoTimeScale) {
        this.videoTimeScale = videoTimeScale;
    }

    public int getVideoDuration() {
        return videoDuration;
    }

    public void setVideoDuration(int videoDuration) {
        this.videoDuration = videoDuration;
    }

    public static int readValueBits(ParsableBitArray pab) {
        int val = 0;
        int b = 255;
        while (b == 255) {
            b = pab.readBits(8);
            //log.trace("Value bit: {}", b);
            val += b;
        }
        return val;
    }

    // read value in rbsp format
    public static int readValue(ByteBuffer in) {
        int value = 0;
        int last = in.get();
        while (last == 255) { // 0xFF
            value += 255;
            last = in.get();
        }
        return value + last;
    }

    // write value in rbsp format
    public static void writeValue(ByteBuffer out, int value) {
        while (value >= 255) {
            out.put((byte) 255);
            value -= 255;
        }
        out.put((byte) value);
    }

    ByteBuffer updateCodecPrivate(ByteBuffer codecPrivate, List<ByteBuffer> sps, List<ByteBuffer> pps) {
        H264Utils.wipePS(codecPrivate, null, sps, pps);
        for (int i = 0; i < pps.size(); i++) {
            pps.set(i, updatePps(pps.get(i)));
        }
        for (int i = 0; i < sps.size(); i++) {
            sps.set(i, updateSps(sps.get(i)));
        }
        return H264Utils.saveCodecPrivate(sps, pps);
    }

    ByteBuffer updateSps(ByteBuffer bb) {
        SeqParameterSet sps = SeqParameterSet.read(bb);
        sps.seqParameterSetId = spParameterSetId;
        ByteBuffer out = ByteBuffer.allocate(bb.capacity() + 10);
        sps.write(out);
        out.flip();
        return out;
    }

    ByteBuffer updatePps(ByteBuffer bb) {
        PictureParameterSet pps = PictureParameterSet.read(bb);
        pps.seqParameterSetId = spParameterSetId;
        pps.picParameterSetId = spParameterSetId;
        ByteBuffer out = ByteBuffer.allocate(bb.capacity() + 10);
        pps.write(out);
        out.flip();
        return out;
    }

    /**
     * h.264 / nalu helper utility methods. Contains logic from BufferH264ES class.
     */
    class H264State {

        int frameNo;

        // pts calculation
        int absoluteFrameNum;

        // used to calculate pts on subsequent tracks
        int aggregateFrameNumber;

        // POC and framenum detection
        int prevFrameNumOffset;

        int prevFrameNum;

        int prevPicOrderCntMsb;

        int prevPicOrderCntLsb;

        SliceHeader readSliceHeader(NALUnit nu, ByteBuffer buf) {
            BitReader br = BitReader.createBitReader(buf);
            SliceHeader sh = SliceHeaderReader.readPart1(br);
            if (isTrace) {
                log.trace("Slice picParameterSetId: {}", sh.picParameterSetId);
                //log.trace("SPS entry: {} PPS entry: {}", spsMap.get(ppsMap.get(sh.picParameterSetId).seqParameterSetId), ppsMap.get(sh.picParameterSetId));
            }
            PictureParameterSet pps = ppsMap.get(sh.picParameterSetId);
            SeqParameterSet sps = spsMap.get(pps.seqParameterSetId);
            SliceHeaderReader.readPart2(sh, nu, sps, pps, br);
            return sh;
        }

        boolean sameFrame(NALUnit nu1, NALUnit nu2, SliceHeader sh1, SliceHeader sh2) {
            log.trace("sameFrame - idc: {}={} psId: {}={} frameNo: {}={}", nu1.nal_ref_idc, nu2.nal_ref_idc, sh1.picParameterSetId, sh2.picParameterSetId, sh1.frameNum, sh2.frameNum);
            if (sh1.picParameterSetId != sh2.picParameterSetId) {
                return false;
            }
            if (sh1.frameNum != sh2.frameNum) {
                return false;
            }
            SeqParameterSet sps = sh1.sps;
            if (sps.picOrderCntType == 0 && sh1.picOrderCntLsb != sh2.picOrderCntLsb) {
                return false;
            }
            if (sps.picOrderCntType == 1 && (sh1.deltaPicOrderCnt[0] != sh2.deltaPicOrderCnt[0] || sh1.deltaPicOrderCnt[1] != sh2.deltaPicOrderCnt[1])) {
                return false;
            }
            //if ((nu1.nal_ref_idc == 0 || nu2.nal_ref_idc == 0) && nu1.nal_ref_idc != nu2.nal_ref_idc) {
            //    return false;
            //}
            if ((nu1.type == NALUnitType.IDR_SLICE) != (nu2.type == NALUnitType.IDR_SLICE)) {
                return false;
            }
            if (sh1.idrPicId != sh2.idrPicId) {
                return false;
            }
            return true;
        }

        Packet detectPoc(NALUnit nu, SliceHeader sh, ByteBuffer data) {
            FrameType frameType = nu.type == NALUnitType.IDR_SLICE ? FrameType.KEY : FrameType.INTER;
            int maxFrameNum = 1 << (sh.sps.log2MaxFrameNumMinus4 + 4);
            if (detectGap(sh, maxFrameNum)) {
                issueNonExistingPic(sh, maxFrameNum);
            }
            absoluteFrameNum = updateFrameNumber(sh.frameNum, maxFrameNum, detectMMCO5(sh.refPicMarkingNonIDR));
            int poc = 0;
            if (frameType == FrameType.INTER) {
                poc = calcPoc(absoluteFrameNum, nu, sh);
            }
            log.debug("PTS: {} maxFrameNum: {} frameNo: {}", absoluteFrameNum, maxFrameNum, frameNo);
            // TODO figure out how to increase fps to match the actual stream (timescale param seems to work)
            return new Packet(data, (absoluteFrameNum + aggregateFrameNumber), videoTimeScale, videoDuration, frameNo++, frameType, null, poc);
        }

        int updateFrameNumber(int frameNo, int maxFrameNum, boolean mmco5) {
            int frameNumOffset;
            if (prevFrameNum > frameNo) {
                frameNumOffset = prevFrameNumOffset + maxFrameNum;
            } else {
                frameNumOffset = prevFrameNumOffset;
            }
            int absFrameNum = frameNumOffset + frameNo;
            prevFrameNum = mmco5 ? 0 : frameNo;
            prevFrameNumOffset = frameNumOffset;
            return absFrameNum;
        }

        void issueNonExistingPic(SliceHeader sh, int maxFrameNum) {
            int nextFrameNum = (prevFrameNum + 1) % maxFrameNum;
            // refPictureManager.addNonExisting(nextFrameNum);
            prevFrameNum = nextFrameNum;
        }

        boolean detectGap(SliceHeader sh, int maxFrameNum) {
            return sh.frameNum != prevFrameNum && sh.frameNum != ((prevFrameNum + 1) % maxFrameNum);
        }

        int calcPoc(int absFrameNum, NALUnit nu, SliceHeader sh) {
            if (sh.sps.picOrderCntType == 0) {
                return calcPOC0(nu, sh);
            } else if (sh.sps.picOrderCntType == 1) {
                return calcPOC1(absFrameNum, nu, sh);
            } else {
                return calcPOC2(absFrameNum, nu, sh);
            }
        }

        int calcPOC2(int absFrameNum, NALUnit nu, SliceHeader sh) {
            if (nu.nal_ref_idc == 0) {
                return 2 * absFrameNum - 1;
            } else {
                return 2 * absFrameNum;
            }
        }

        int calcPOC1(int absFrameNum, NALUnit nu, SliceHeader sh) {
            if (sh.sps.numRefFramesInPicOrderCntCycle == 0) {
                absFrameNum = 0;
            }
            if (nu.nal_ref_idc == 0 && absFrameNum > 0) {
                absFrameNum = absFrameNum - 1;
            }
            int expectedDeltaPerPicOrderCntCycle = 0;
            for (int i = 0; i < sh.sps.numRefFramesInPicOrderCntCycle; i++) {
                expectedDeltaPerPicOrderCntCycle += sh.sps.offsetForRefFrame[i];
            }
            int expectedPicOrderCnt;
            if (absFrameNum > 0) {
                int picOrderCntCycleCnt = (absFrameNum - 1) / sh.sps.numRefFramesInPicOrderCntCycle;
                int frameNumInPicOrderCntCycle = (absFrameNum - 1) % sh.sps.numRefFramesInPicOrderCntCycle;
                expectedPicOrderCnt = picOrderCntCycleCnt * expectedDeltaPerPicOrderCntCycle;
                for (int i = 0; i <= frameNumInPicOrderCntCycle; i++) {
                    expectedPicOrderCnt = expectedPicOrderCnt + sh.sps.offsetForRefFrame[i];
                }
            } else {
                expectedPicOrderCnt = 0;
            }
            if (nu.nal_ref_idc == 0) {
                expectedPicOrderCnt = expectedPicOrderCnt + sh.sps.offsetForNonRefPic;
            }
            return expectedPicOrderCnt + sh.deltaPicOrderCnt[0];
        }

        int calcPOC0(NALUnit nu, SliceHeader sh) {
            int pocCntLsb = sh.picOrderCntLsb;
            int maxPicOrderCntLsb = 1 << (sh.sps.log2MaxPicOrderCntLsbMinus4 + 4);
            // TODO prevPicOrderCntMsb should be wrapped!!
            int picOrderCntMsb;
            if ((pocCntLsb < prevPicOrderCntLsb) && ((prevPicOrderCntLsb - pocCntLsb) >= (maxPicOrderCntLsb / 2))) {
                picOrderCntMsb = prevPicOrderCntMsb + maxPicOrderCntLsb;
            } else if ((pocCntLsb > prevPicOrderCntLsb) && ((pocCntLsb - prevPicOrderCntLsb) > (maxPicOrderCntLsb / 2))) {
                picOrderCntMsb = prevPicOrderCntMsb - maxPicOrderCntLsb;
            } else {
                picOrderCntMsb = prevPicOrderCntMsb;
            }
            if (nu.nal_ref_idc != 0) {
                prevPicOrderCntMsb = picOrderCntMsb;
                prevPicOrderCntLsb = pocCntLsb;
            }
            return picOrderCntMsb + pocCntLsb;
        }

        boolean detectMMCO5(RefPicMarking refPicMarkingNonIDR) {
            if (refPicMarkingNonIDR == null) {
                return false;
            }
            RefPicMarking.Instruction[] instructions = refPicMarkingNonIDR.getInstructions();
            for (int i = 0; i < instructions.length; i++) {
                RefPicMarking.Instruction instr = instructions[i];
                if (instr.getType() == InstrType.CLEAR) {
                    return true;
                }
            }
            return false;
        }

        SliceHeader copyAndUpdateNU(NALUnit nu, ByteBuffer is, ByteBuffer os, SeqParameterSet sps, PictureParameterSet pps) {
            BitReader reader = BitReader.createBitReader(is);
            BitWriter writer = new BitWriter(os);
            SliceHeader sh = SliceHeaderReader.readPart1(reader);
            SliceHeaderReader.readPart2(sh, nu, sps, pps, reader);
            sh.picParameterSetId = spParameterSetId;
            nu.write(os);
            SliceHeaderWriter.write(sh, nu.type == NALUnitType.IDR_SLICE, nu.nal_ref_idc, writer);
            if (pps.entropyCodingModeFlag) {
                copyCABAC(writer, reader);
            } else {
                copyCAVLC(writer, reader);
            }
            os.flip();
            return sh;
        }

        void copyCAVLC(BitWriter w, BitReader r) {
            int rem = 8 - r.curBit();
            int l = r.readNBit(rem);
            w.writeNBit(l, rem);
            if (r.moreData()) {
                int b = r.readNBit(8), next;
                while (r.moreData() && (next = r.readNBit(8)) != -1) {
                    w.writeNBit(b, 8);
                    b = next;
                }
                int len = 7;
                while ((b & 0x1) == 0) {
                    b >>= 1;
                    len--;
                }
                w.writeNBit(b, len);
                w.write1Bit(1);
            } else {
                log.warn("No more CAVLC bits available to read");
            }
            w.flush();
        }

        void copyCABAC(BitWriter w, BitReader r) {
            long bp = r.curBit();
            long rem = r.readNBit(8 - (int) bp);
            if ((long) ((1 << (8 - bp)) - 1) == rem) {
                log.warn("No more CABAC bit assertion failed");
            }
            if (w.curBit() != 0) {
                w.writeNBit(0xff, 8 - w.curBit());
            }
            int b;
            while (r.moreData() && (b = r.readNBit(8)) != -1) {
                //                if (isTrace) {
                //                    log.trace("Write buffer position: {} limit: {} read more: {}", w.getBuffer().position(), w.getBuffer().limit(), r.moreData());
                //                }
                w.writeNBit(b, 8);
            }
        }

        // used when switching to new track
        void reset() {
            frameNo = 0;
            prevFrameNumOffset = 0;
            prevFrameNum = 0;
            prevPicOrderCntMsb = 0;
            prevPicOrderCntLsb = 0;
            aggregateFrameNumber += absoluteFrameNum + 1;
        }

    }

}
