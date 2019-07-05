package org.gregoire.media;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.mp4parser.Container;
import org.mp4parser.muxer.FileDataSourceImpl;
import org.mp4parser.muxer.Movie;
import org.mp4parser.muxer.builder.DefaultMp4Builder;
import org.mp4parser.muxer.tracks.h264.H264TrackImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class H264Main {

    private static Logger log = LoggerFactory.getLogger(H264Main.class);

    // JMF/FMJ video format
    //private final static VideoFormat VIDEO_FORMAT = new ParameterizedVideoFormat(Constants.H264, null, Format.NOT_SPECIFIED, Format.byteArray, Format.NOT_SPECIFIED, ParameterizedVideoFormat.toMap(VideoMediaFormatImpl.H264_PACKETIZATION_MODE_FMTP, "0")); // 0=one nal per packet, 1=multi-nal

    /**
     * Creates an MP4 file out of encoded h.264 bytes.
     * 
     * @throws IOException
     */
    public static void makeMP4() throws IOException {
        H264TrackImpl h264Track = new H264TrackImpl(new FileDataSourceImpl("dump.h264"));
        //AACTrackImpl aacTrack = new AACTrackImpl(new FileInputStream("/home/sannies2/Downloads/lv.aac").getChannel());
        Movie m = new Movie();
        m.addTrack(h264Track);
        //m.addTrack(aacTrack);
        Container out = new DefaultMp4Builder().build(m);
        FileOutputStream fos = new FileOutputStream(new File("h264_output.mp4"));
        FileChannel fc = fos.getChannel();
        out.writeContainer(fc);
        fos.close();
    }

    public static void main(String[] args) throws IOException {
        String filePath = args.length > 0 ? args[0] : "dump.h264";
        Path path = Paths.get(filePath);
        long length = path.toFile().length();
        List<Long> posList = new ArrayList<>();
        SeekableByteChannel channel = null;
        try {
            channel = Files.newByteChannel(path);
            int read = -1;
            int nalScore = 0;
            ByteBuffer dst = ByteBuffer.allocateDirect(1);
            while ((read = channel.read(dst)) > 0) {
                // flip for reading
                dst.flip();
                log.trace("Read: {} positions - channel: {} buffer: {}", read, channel.position(), dst.position());
                switch (nalScore) {
                    case 0: // we're starting a new detection
                    case 1: // we've already found the first byte
                    case 2: // we're already found the second byte
                        if (dst.get() == 0) {
                            // found a byte of the nal start code
                            nalScore += 1;
                        } else {
                            // reset the score
                            nalScore = 0;
                        }
                        break;
                    case 3: // we're ready for the last byte of the start code
                        if (dst.get() == 1) {
                            // record the index of the 00 00 00 01 delimiter
                            posList.add(channel.position() - 4);
                        }
                        // reset the score
                        nalScore = 0;
                        break;
                }
                dst.clear();
            }
            log.debug("Position - channel: {} count: {}", channel.position(), posList.size());
            // go back to the start
            channel.position(0);
        } catch (IOException e) {
            log.error("", e);
        }
        // positions of the NALU sequence marker
        Long[] positions = posList.toArray(new Long[0]);
        log.info("NALU count: {} positions: {}", positions.length, Arrays.toString(positions));
        // total nals processed
        int count = 0;
        // timestamp
        long timestamp = 0L;
        try {
            // start the renderer
            RTMPVideoRenderer.start();
            for (int i = 0; i < positions.length - 1; i++) {
                log.info("NAL index: {} start: {}", i, positions[i]);
                int size = (int) (positions[i + 1] - positions[i]);
                log.info("NAL size: {}", size);
                // chunk holder
                ByteBuffer chunk = ByteBuffer.allocate(size);
                // get the chunk
                channel.read(chunk);
                // process the chunk
                count += RTMPVideoRenderer.processNals(chunk.array(), (timestamp += 60L));
            }
        } catch (Exception e) {
            log.error("", e);
        }
        log.info("Total NAL count: {}", count);
        try {
            channel.close();
            RTMPVideoRenderer.stop();
        } catch (IOException e) {
            log.error("", e);
        } finally {
            // create the mp4 for verification that our dump file is ok
            makeMP4();
        }
    }

}
