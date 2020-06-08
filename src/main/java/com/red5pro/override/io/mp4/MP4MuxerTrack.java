package com.red5pro.override.io.mp4;

import static org.jcodec.common.Ints.checkedCast;
import static org.jcodec.common.Preconditions.checkState;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.bouncycastle.util.encoders.Hex;
import org.jcodec.codecs.aac.ADTSParser;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.mp4.AvcCBox;
import org.jcodec.codecs.mpeg4.mp4.EsdsBox;
import org.jcodec.common.AudioFormat;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrackMeta.Orientation;
import org.jcodec.common.IntArrayList;
import org.jcodec.common.LongArrayList;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Packet.FrameType;
import org.jcodec.common.model.Rational;
import org.jcodec.common.model.Size;
import org.jcodec.common.model.Unit;
import org.jcodec.containers.mp4.MP4TrackType;
import org.jcodec.containers.mp4.boxes.AudioSampleEntry;
import org.jcodec.containers.mp4.boxes.Box;
import org.jcodec.containers.mp4.boxes.ChunkOffsets64Box;
import org.jcodec.containers.mp4.boxes.CompositionOffsetsBox;
import org.jcodec.containers.mp4.boxes.CompositionOffsetsBox.Entry;
import org.jcodec.containers.mp4.boxes.CompositionOffsetsBox.LongEntry;
import org.jcodec.containers.mp4.boxes.Edit;
import org.jcodec.containers.mp4.boxes.HandlerBox;
import org.jcodec.containers.mp4.boxes.Header;
import org.jcodec.containers.mp4.boxes.MediaBox;
import org.jcodec.containers.mp4.boxes.MediaHeaderBox;
import org.jcodec.containers.mp4.boxes.MediaInfoBox;
import org.jcodec.containers.mp4.boxes.MovieHeaderBox;
import org.jcodec.containers.mp4.boxes.NodeBox;
import org.jcodec.containers.mp4.boxes.PixelAspectExt;
import org.jcodec.containers.mp4.boxes.SampleDescriptionBox;
import org.jcodec.containers.mp4.boxes.SampleEntry;
import org.jcodec.containers.mp4.boxes.SampleSizesBox;
import org.jcodec.containers.mp4.boxes.SampleToChunkBox;
import org.jcodec.containers.mp4.boxes.SampleToChunkBox.SampleToChunkEntry;
import org.jcodec.containers.mp4.boxes.SyncSamplesBox;
import org.jcodec.containers.mp4.boxes.TimeToSampleBox;
import org.jcodec.containers.mp4.boxes.TimeToSampleBox.TimeToSampleEntry;
import org.jcodec.containers.mp4.boxes.TrackHeaderBox;
import org.jcodec.containers.mp4.boxes.TrakBox;
import org.jcodec.containers.mp4.boxes.VideoSampleEntry;
import org.jcodec.containers.mp4.muxer.AbstractMP4MuxerTrack;
import org.jcodec.containers.mp4.muxer.TimecodeMP4MuxerTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initially based on the JCodec org.jcodec.containers.mp4.muxer.MP4MuxerTrack and org.jcodec.containers.mp4.muxer.CodecMP4MuxerTrack sources.
 * 
 * @author Paul Gregoire
 */
public class MP4MuxerTrack extends AbstractMP4MuxerTrack {

    private static Logger logger = LoggerFactory.getLogger(MP4MuxerTrack.class);

    private final static int[] MATRIX_0 = new int[] { 65536, 0, 0, 0, 65536, 0, 0, 0, 0x40000000 };

    private final static int[] MATRIX_90CW = new int[] { 0, 65536, 0, -65536, 0, 0, 0, 0, 0x40000000 };

    private final static int[] MATRIX_180 = new int[] { -65536, 0, 0, 0, -65536, 0, 0, 0, 0x40000000 };

    private final static int[] MATRIX_90CCW = new int[] { 0, -65536, 0, 65536, 0, 0, 0, 0, 0x40000000 };

    private List<TimeToSampleEntry> sampleDurations;

    private long sameDurCount = 0;

    private long curDuration = -1;

    private LongArrayList chunkOffsets;

    private IntArrayList sampleSizes;

    private IntArrayList iframes;

    private List<LongEntry> compositionOffsets;

    private long lastCompositionOffset = 0;

    private long lastCompositionSamples = 0;

    private long ptsEstimate = 0;

    private int lastEntry = -1;

    private long trackTotalDuration;

    private int curFrame;

    private boolean allIframes = true;

    private TimecodeMP4MuxerTrack timecodeTrack;

    private static Map<Codec, String> codec2fourcc = new HashMap<>();

    static {
        codec2fourcc.put(Codec.MP1, ".mp1");
        codec2fourcc.put(Codec.MP2, ".mp2");
        codec2fourcc.put(Codec.MP3, ".mp3");
        codec2fourcc.put(Codec.H264, "avc1");
        codec2fourcc.put(Codec.AAC, "mp4a");
        codec2fourcc.put(Codec.PRORES, "apch");
        codec2fourcc.put(Codec.JPEG, "mjpg");
        codec2fourcc.put(Codec.PNG, "png ");
        codec2fourcc.put(Codec.V210, "v210");
    }

    private Codec codec;

    // SPS/PPS lists when h.264 is stored, otherwise these lists are not used.
    private LinkedHashSet<ByteBuffer> spsList = new LinkedHashSet<>();

    private LinkedHashSet<ByteBuffer> ppsList = new LinkedHashSet<>();

    // ADTS header used to construct audio sample entry for AAC
    private ADTSParser.Header adtsHeader;

    // 0-degree orientation matrix
    private int[] matrix = MATRIX_0;

    // track instantiation time
    private long created = System.currentTimeMillis();

    public MP4MuxerTrack(int trackId, MP4TrackType type, Codec codec) {
        super(trackId, type);
        this.sampleDurations = new ArrayList<>();
        this.chunkOffsets = LongArrayList.createLongArrayList();
        this.sampleSizes = IntArrayList.createIntArrayList();
        this.iframes = IntArrayList.createIntArrayList();
        this.compositionOffsets = new ArrayList<>();
        setTgtChunkDuration(new Rational(1, 1), Unit.FRAME);
        this.codec = codec;
    }

    @Override
    public void addFrame(Packet pkt) throws IOException {
        if (codec == Codec.H264) {
            ByteBuffer result = pkt.getData();
            //logger.debug("addFrame: {}", pkt.frameType);
            if (pkt.frameType == FrameType.UNKNOWN) {
                pkt.setFrameType(H264Utils.isByteBufferIDRSlice(result) ? FrameType.KEY : FrameType.INTER);
            }
            // this is where the sps and pps lists get filled-in
            //            if (logger.isDebugEnabled()) {
            //                logger.debug("SPS: {} PPS: {}", spsList, ppsList);
            //                spsList.forEach(bb -> {
            //                    byte[] arr = Arrays.copyOfRange(bb.array(), 0, bb.limit());
            //                    logger.debug("SPS entry: {}", Hex.toHexString(arr));
            //                });
            //                ppsList.forEach(bb -> {
            //                    byte[] arr = Arrays.copyOfRange(bb.array(), 0, bb.limit());
            //                    logger.debug("PPS entry: {}", Hex.toHexString(arr));
            //                });
            //            }
            // this strips the sps and pps and puts them in the list
            H264Utils.wipePSinplace(result, spsList, ppsList);
            // encodes 1..n nalu
            result = H264Utils.encodeMOVPacket(result);
            pkt = Packet.createPacketWithData(pkt, result);
        } else if (codec == Codec.AAC) {
            ByteBuffer result = pkt.getData();
            adtsHeader = ADTSParser.read(result);
            //logger.trace(String.format("crc_absent: %d, num_aac_frames: %d, size: %d, remaining: %d, %d, %d, %d", adtsHeader.getCrcAbsent(), adtsHeader.getNumAACFrames(), adtsHeader.getSize(), result.remaining(), adtsHeader.getObjectType(), adtsHeader.getSamplingIndex(), adtsHeader.getChanConfig()));
            pkt = Packet.createPacketWithData(pkt, result);
        }
        addFrameInternal(pkt, 1);
        processTimecode(pkt);
    }

    public void addFrameInternal(Packet pkt, int entryNo) throws IOException {
        checkState(!finished, "The muxer track has finished muxing");
        if (_timescale == NO_TIMESCALE_SET) {
            if (adtsHeader != null) {
                _timescale = adtsHeader.getSampleRate();
            } else {
                _timescale = pkt.getTimescale();
            }
        }
        if (adtsHeader != null) {
            pkt.setDuration(1024);
        }
        if (_timescale != pkt.getTimescale()) {
            pkt.setPts((pkt.getPts() * _timescale) / pkt.getTimescale());
            pkt.setDuration((pkt.getPts() * _timescale) / pkt.getDuration());
        }
        if (type == MP4TrackType.VIDEO) {
            long compositionOffset = pkt.getPts() - ptsEstimate;
            if (compositionOffset != lastCompositionOffset) {
                if (lastCompositionSamples > 0) {
                    compositionOffsets.add(new LongEntry(lastCompositionSamples, lastCompositionOffset));
                }
                lastCompositionOffset = compositionOffset;
                lastCompositionSamples = 0;
            }
            lastCompositionSamples++;
            ptsEstimate += pkt.getDuration();
        }
        if (lastEntry != -1 && lastEntry != entryNo) {
            outChunk(lastEntry);
            samplesInLastChunk = -1;
        }
        curChunk.add(pkt.getData());
        if (pkt.isKeyFrame()) {
            iframes.add(curFrame + 1);
        } else {
            allIframes = false;
        }
        curFrame++;
        chunkDuration += pkt.getDuration();
        if (curDuration != -1 && pkt.getDuration() != curDuration) {
            sampleDurations.add(new TimeToSampleEntry((int) sameDurCount, (int) curDuration));
            sameDurCount = 0;
        }
        curDuration = pkt.getDuration();
        sameDurCount++;
        trackTotalDuration += pkt.getDuration();
        outChunkIfNeeded(entryNo);
        lastEntry = entryNo;
    }

    private void processTimecode(Packet pkt) throws IOException {
        if (timecodeTrack != null) {
            timecodeTrack.addTimecode(pkt);
        }
    }

    private void outChunkIfNeeded(int entryNo) throws IOException {
        checkState(tgtChunkDurationUnit == Unit.FRAME || tgtChunkDurationUnit == Unit.SEC);
        if (tgtChunkDurationUnit == Unit.FRAME && curChunk.size() * tgtChunkDuration.getDen() == tgtChunkDuration.getNum()) {
            outChunk(entryNo);
        } else if (tgtChunkDurationUnit == Unit.SEC && chunkDuration > 0 && chunkDuration * tgtChunkDuration.getDen() >= tgtChunkDuration.getNum() * _timescale) {
            outChunk(entryNo);
        }
    }

    void outChunk(int entryNo) throws IOException {
        logger.debug("{} outChunk: {}", (isAudio() ? "Audio" : isVideo() ? "Video" : "Other"), chunkNo);
        if (curChunk.size() > 0) {
            chunkOffsets.add(out.position());
            for (ByteBuffer bs : curChunk) {
                sampleSizes.add(bs.remaining());
                out.write(bs);
            }
            if (samplesInLastChunk == -1 || samplesInLastChunk != curChunk.size()) {
                samplesInChunks.add(new SampleToChunkEntry(chunkNo + 1, curChunk.size(), entryNo));
            }
            samplesInLastChunk = curChunk.size();
            chunkNo++;
            chunkDuration = 0;
            curChunk.clear();
        } else {
            logger.debug("No current chunk to write");
        }
    }

    @Override
    protected Box finish(MovieHeaderBox mvhd) throws IOException {
        logger.info("finish - S: {} I: {}", sampleSizes.size(), iframes.size());
        // VLC fails to playback without samples / frames
        if (sampleSizes.size() == 0) {
            logger.info("Empty track {} skipped", trackId);
            return null;
        }
        checkState(!finished, "The muxer track has finished muxing");
        if (logger.isDebugEnabled()) {
            if (isAudio()) {
                logger.info("Finishing audio track id: {}", trackId);
            } else if (isVideo()) {
                logger.info("Finishing video track id: {}", trackId);
            } else {
                logger.info("Finishing track id: {}", trackId);
            }
        }
        setCodecPrivateIfNeeded();
        final long modified = System.currentTimeMillis();
        outChunk(lastEntry);
        if (sameDurCount > 0) {
            sampleDurations.add(new TimeToSampleEntry((int) sameDurCount, (int) curDuration));
        }
        finished = true;
        TrakBox trak = TrakBox.createTrakBox();
        Size dd = getDisplayDimensions();
        // set the correct matrix for the orientation here
        TrackHeaderBox tkhd = TrackHeaderBox.createTrackHeaderBox(trackId, (mvhd.getTimescale() * trackTotalDuration) / _timescale, dd.getWidth(), dd.getHeight(), created, modified, 1.0f, (short) 0, 0, matrix);
        tkhd.setFlags(0xf);
        if (logger.isDebugEnabled()) {
            if (isVideo()) {
                logger.info("Track {} header - Orientation 0={} 90={} 180={} 270={} {}x{}", tkhd.getTrackId(), tkhd.isOrientation0(), tkhd.isOrientation90(), tkhd.isOrientation180(), tkhd.isOrientation270(), tkhd.getWidth(), tkhd.getHeight());
            } else if (isAudio()) {
                logger.info("Track {} header - volume: {}", tkhd.getTrackId(), tkhd.getVolume());
            }
        }
        trak.add(tkhd);
        // VLC doesn't like the tapt box
        //tapt(trak);
        MediaBox media = MediaBox.createMediaBox();
        trak.add(media);
        media.add(MediaHeaderBox.createMediaHeaderBox(_timescale, trackTotalDuration, 0, created, modified, 0));
        HandlerBox hdlr = HandlerBox.createHandlerBox("mhlr", type.getHandler(), "appl", 0, 0);
        media.add(hdlr);
        MediaInfoBox minf = MediaInfoBox.createMediaInfoBox();
        media.add(minf);
        mediaHeader(minf, type);
        minf.add(HandlerBox.createHandlerBox("dhlr", "url ", "appl", 0, 0));
        addDref(minf);
        NodeBox stbl = new NodeBox(new Header("stbl"));
        minf.add(stbl);
        putCompositionOffsets(stbl);
        putEdits(trak);
        putName(trak);
        stbl.add(SampleDescriptionBox.createSampleDescriptionBox(sampleEntries.toArray(new SampleEntry[0])));
        stbl.add(SampleToChunkBox.createSampleToChunkBox(samplesInChunks.toArray(new SampleToChunkEntry[0])));
        stbl.add(SampleSizesBox.createSampleSizesBox2(sampleSizes.toArray()));
        stbl.add(TimeToSampleBox.createTimeToSampleBox(sampleDurations.toArray(new TimeToSampleEntry[] {})));
        stbl.add(ChunkOffsets64Box.createChunkOffsets64Box(chunkOffsets.toArray()));
        if (!allIframes && iframes.size() > 0) {
            stbl.add(SyncSamplesBox.createSyncSamplesBox(iframes.toArray()));
        }
        return trak;
    }

    private void putCompositionOffsets(NodeBox stbl) {
        if (compositionOffsets.size() > 0) {
            compositionOffsets.add(new LongEntry(lastCompositionSamples, lastCompositionOffset));
            long min = minLongOffset(compositionOffsets);
            if (min > 0) {
                for (LongEntry entry : compositionOffsets) {
                    entry.offset -= min;
                }
            }
            LongEntry first = compositionOffsets.get(0);
            if (first.getOffset() > 0) {
                if (edits == null) {
                    edits = new ArrayList<Edit>();
                    edits.add(new Edit(trackTotalDuration, first.getOffset(), 1.0f));
                } else {
                    for (Edit edit : edits) {
                        edit.setMediaTime(edit.getMediaTime() + first.getOffset());
                    }
                }
            }
            Entry[] intEntries = new Entry[compositionOffsets.size()];
            for (int i = 0; i < compositionOffsets.size(); i++) {
                LongEntry longEntry = compositionOffsets.get(i);
                intEntries[i] = new Entry(checkedCast(longEntry.count), checkedCast(longEntry.offset));
            }
            stbl.add(CompositionOffsetsBox.createCompositionOffsetsBox(intEntries));
        }
    }

    public static long minLongOffset(List<LongEntry> offs) {
        long min = Long.MAX_VALUE;
        for (LongEntry entry : offs) {
            min = Math.min(min, entry.getOffset());
        }
        return min;
    }

    public static int minOffset(List<Entry> offs) {
        int min = Integer.MAX_VALUE;
        for (Entry entry : offs) {
            min = Math.min(min, entry.getOffset());
        }
        return min;
    }

    public int getSampleCount() {
        return sampleSizes.size();
    }

    @Override
    public long getTrackTotalDuration() {
        return trackTotalDuration;
    }

    public TimecodeMP4MuxerTrack getTimecodeTrack() {
        return timecodeTrack;
    }

    public void setTimecode(TimecodeMP4MuxerTrack timecodeTrack) {
        this.timecodeTrack = timecodeTrack;
    }

    public void setCodecPrivateIfNeeded() {
        logger.debug("setCodecPrivateIfNeeded: {} samples: {}", codec, getEntries().size());
        if (codec == Codec.H264) {
            if (getEntries().size() == 0) {
                logger.debug("Configs sps: {} pps: {}", spsList, ppsList);
                final List<ByteBuffer> sps;
                final List<ByteBuffer> pps;
                // use the first SPS/PPS and skip the rest that may exist
                sps = new ArrayList<>(1);
                spsList.forEach(s -> {
                    sps.add(s);
                    return;
                });
                pps = new ArrayList<>(1);
                ppsList.forEach(p -> {
                    pps.add(p);
                    return;
                });
                // previous way - get unique entries
                //sps = selectUnique(spsList);
                //pps = selectUnique(ppsList);
                if (!sps.isEmpty() && !pps.isEmpty()) {
                    getEntries().get(0).add(H264Utils.createAvcCFromPS(sps, pps, 4));
                } else {
                    logger.warn("Not adding a sample entry for h.264 track, missing any SPS/PPS NAL units");
                }
            }
        } else if (codec == Codec.AAC) {
            if (adtsHeader != null) {
                getEntries().get(0).add(EsdsBox.fromADTS(adtsHeader));
            } else {
                logger.warn("Not adding a sample entry for AAC track, missing any ADTS headers");
            }
        }
    }

    // XXX: not needed when using Set collection
    //    private static List<ByteBuffer> selectUnique(List<ByteBuffer> bblist) {
    //        Set<ByteArrayWrapper> all = new HashSet<>();
    //        for (ByteBuffer byteBuffer : bblist) {
    //            all.add(new ByteArrayWrapper(byteBuffer));
    //        }
    //        List<ByteBuffer> result = new ArrayList<>();
    //        for (ByteArrayWrapper bs : all) {
    //            result.add(bs.get());
    //        }
    //        return result;
    //    }

    /**
     * Adds an SPS entry.
     * 
     * @param sps SPS entry
     */
    public void addSPS(ByteBuffer sps) {
        if (logger.isDebugEnabled()) {
            byte[] arr = Arrays.copyOfRange(sps.array(), 0, sps.limit());
            logger.debug("Track {} addSPS: {}", trackId, Hex.toHexString(arr));
        }
        spsList.add(sps);
    }

    /**
     * Returns the first SPS entry or null if none exist.
     * 
     * @return first SPS entry
     */
    public ByteBuffer getSPS() {
        return spsList.isEmpty() ? null : spsList.iterator().next();
    }

    /**
     * Adds an PPS entry.
     * 
     * @param pps PPS entry
     */
    public void addPPS(ByteBuffer pps) {
        if (logger.isDebugEnabled()) {
            byte[] arr = Arrays.copyOfRange(pps.array(), 0, pps.limit());
            logger.debug("Track {} addPPS: {}", trackId, Hex.toHexString(arr));
        }
        ppsList.add(pps);
    }

    /**
     * Returns the first PPS entry or null if none exist.
     * 
     * @return first PPS entry
     */
    public ByteBuffer getPPS() {
        return ppsList.isEmpty() ? null : ppsList.iterator().next();
    }

    public void addAudioSampleEntry(AudioFormat format) {
        AudioSampleEntry ase = AudioSampleEntry.compressedAudioSampleEntry(codec2fourcc.get(codec), (short) 1, (short) 16, format.getChannels(), format.getSampleRate(), 0, 0, 0);
        addSampleEntry(ase);
    }

    public void addVideoSampleEntry(VideoCodecMeta meta) {
        //logger.info("addVideoSampleEntry: {}x{}", meta.getSize().getWidth(), meta.getSize().getHeight());
        SampleEntry se = VideoSampleEntry.videoSampleEntry(codec2fourcc.get(codec), meta.getSize(), "Red5Pro");
        if (meta.getPixelAspectRatio() != null) {
            se.add(PixelAspectExt.createPixelAspectExt(meta.getPixelAspectRatio()));
        }
        if (logger.isTraceEnabled()) {
            int sampleCount = sampleEntries.size();
            logger.trace("Sample entries at meta addition: {}", sampleCount);
            if (sampleCount > 0) {
                logger.trace("Sample entries: {}", sampleEntries);
            }
        }
        addSampleEntry(se);
    }

    public int[] getMatrix() {
        return matrix;
    }

    public void setMatrix(int[] matrix) {
        this.matrix = matrix;
    }

    public void setOrientation(Orientation orientation) {
        switch (orientation) {
            case D_270:
                setMatrix(MATRIX_90CCW);
                break;
            case D_180:
                setMatrix(MATRIX_180);
                break;
            case D_90:
                setMatrix(MATRIX_90CW);
                break;
            default:
                setMatrix(MATRIX_0);
                break;
        }
    }

    /**
     * Sets the avcC box in the first video sample entry.
     * 
     * @param avcCBox
     */
    public void setAvcC(AvcCBox avcCBox) {
        logger.debug("setAvcC: {}", avcCBox);
        VideoSampleEntry vse = (VideoSampleEntry) getEntries().get(0);
        vse.add(avcCBox);
        logger.debug("Sample entries: {}", sampleEntries);
        AvcCBox avcC = H264Utils.parseAVCC(vse);
        logger.debug("AvcC box: {} {} {} {} {}", avcC.getFourcc(), avcC.getProfile(), avcC.getLevel(), Hex.toHexString(avcC.getSpsList().get(0).array()), Hex.toHexString(avcC.getPpsList().get(0).array()));
        ByteBuffer sps = avcC.getSpsList().get(0);
        ByteBuffer pps = avcC.getPpsList().get(0);
        logger.debug("AvcC box - sps: {} {} pps: {} {}", sps.position(), sps.limit(), pps.position(), pps.limit());
    }

    /**
     * Replaces the video sample entry with updated codec metadata.
     * 
     * @param meta
     */
    public void replaceVideoSampleEntry(VideoCodecMeta meta) {
        sampleEntries.clear();
        addVideoSampleEntry(meta);
    }

}