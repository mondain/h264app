package com.red5pro.override.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import org.apache.mina.core.buffer.IoBuffer;
import org.bouncycastle.util.encoders.Hex;
import org.gregoire.media.rtmp.RTMPPrefix;
import org.red5.io.IStreamableFile;
import org.red5.io.ITag;
import org.red5.io.ITagReader;
import org.red5.server.stream.consumer.ImmutableTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads ITags from a raw dat file.
 * 
 * @author Paul Gregoire
 * 
 */
public class RawTagReader implements ITagReader {

    private static Logger log = LoggerFactory.getLogger(RawTagReader.class);

    // path to the input content
    @SuppressWarnings("unused")
    private Path path;

    // string for absolute path
    private String filePath;

    private SeekableByteChannel fileChannel;

    private long totalRead;

    private boolean hasVideo;

    public RawTagReader(String filePath) {
        this(Paths.get(filePath));
    }

    public RawTagReader(Path path) {
        this.path = path;
        filePath = path.toFile().getAbsolutePath();
        try {
            this.fileChannel = Files.newByteChannel(path, StandardOpenOption.READ);
        } catch (IOException e) {
            log.error("Failed to create tag reader", e);
        }
    }

    @Override
    public void close() {
        if (fileChannel != null && fileChannel.isOpen()) {
            log.info("Total bytes read: {} from: {}", totalRead, filePath);
            try {
                fileChannel.close();
            } catch (IOException e) {
                log.warn("Exception closing input file", e);
            }
        }
    }

    @Override
    public void decodeHeader() {
    }

    @Override
    public long getBytesRead() {
        return totalRead;
    }

    @Override
    public long getDuration() {
        return 0;
    }

    @Override
    public IStreamableFile getFile() {
        return null;
    }

    @Override
    public int getOffset() {
        int offset = 0;
        if (fileChannel != null) {
            try {
                offset = (int) fileChannel.position();
            } catch (IOException e) {
            }
        }
        return offset;
    }

    @Override
    public long getTotalBytes() {
        try {
            return fileChannel.size();
        } catch (IOException e) {
        }
        return 0;
    }

    @Override
    public boolean hasMoreTags() {
        if (fileChannel != null && fileChannel.isOpen()) {
            return totalRead < getTotalBytes();
        }
        return false;
    }

    @Override
    public boolean hasVideo() {
        return hasVideo;
    }

    @Override
    public void position(long pos) {
        try {
            fileChannel.position(pos);
        } catch (IOException e) {
            log.warn("Exception setting new position: {}", pos, e);
        }
    }

    @Override
    public ITag readTag() {
        ITag tag = null;
        // structure: | 4b tag body size | 1b tag type | 4b tag timestamp | nb tag data |
        ByteBuffer buf = ByteBuffer.allocate(9);
        try {
            // read the "header"
            int read = fileChannel.read(buf);
            // detect eof
            if (read > 0) {
                // update total
                totalRead += read;
                // flip so we can decode
                buf.flip();
                // decode the header
                int bodyLength = buf.getInt();
                byte dataType = buf.get();
                int timestamp = buf.getInt();
                log.debug("Tag - type: {} timestamp: {} length: {}", dataType, timestamp, bodyLength);
                // size a tag body buffer
                ByteBuffer body = ByteBuffer.allocate(bodyLength);
                read = fileChannel.read(body);
                log.debug("Body read: {} file position: {}", read, fileChannel.position());
                if (read > 0) {
                    // update total again
                    totalRead += read;
                    // flip so it can be read
                    body.flip();
                    if (dataType == 9) { // Constants.TYPE_VIDEO_DATA:
                        // update hasVideo flag
                        hasVideo = true;
                        log.debug("Body: {}", Hex.toHexString(body.array(), 0, body.array().length));
                        // 17 01 should be IDR/CS but we'll need to pull any SPS/PPS off the front
                        byte first = body.get();
                        if ((first == 0x17 || first == 0x27) && body.get() == 1) {
                            // skip next 3 bytes 0,0,0
                            body.get();
                            body.get();
                            body.get();
                            do {
                                // H264 data, size prepended
                                int frameSize = (body.get() & 0xFF);
                                frameSize = frameSize << 8 | (body.get() & 0xFF);
                                frameSize = frameSize << 8 | (body.get() & 0xFF);
                                frameSize = frameSize << 8 | (body.get() & 0xFF);
                                log.debug("Frame size: {}", frameSize);
                                if (frameSize > body.remaining()) {
                                    log.warn("Bad h264 frame...frameSize {} available: {}", frameSize, body.remaining());
                                    return null;
                                }
                                // get the nalu data
                                byte[] data = new byte[frameSize];
                                body.get(data);
                                // get nalu type
                                int nalType = data[0] & 0x1f;
                                // put the data into an iobuffer with the annexb prefix
                                IoBuffer nalu = IoBuffer.allocate(frameSize + 4 + 5);
                                switch (nalType) {
                                    case 1: // CodedSlice
                                        nalu.put(RTMPPrefix.H264_INTRAFRAME_PREFIX);
                                        nalu.putInt(1);
                                        nalu.put(data);
                                        nalu.flip();
                                        tag = ImmutableTag.build(dataType, timestamp, nalu);
                                        break;
                                    case 5: // IDR
                                        nalu.put(RTMPPrefix.H264_KEYFRAME_PREFIX);
                                        nalu.putInt(1);
                                        nalu.put(data);
                                        nalu.flip();
                                        tag = ImmutableTag.build(dataType, timestamp, nalu);
                                        break;
                                    default:
                                        log.debug("Ignoring nalu type: {}", nalType);
                                        break;
                                }
                            } while (body.remaining() > 4);
                        } else {
                            // rewind back from reading flash tag prefixs (2 byte)
                            body.rewind();
                            // config / private data
                            tag = ImmutableTag.build(dataType, timestamp, IoBuffer.wrap(body));
                        }
                    } else {
                        // audio, metadata, etc...
                        tag = ImmutableTag.build(dataType, timestamp, IoBuffer.wrap(body));
                    }
                }
                body.clear();
            }
            buf.clear();
        } catch (IOException e) {
            log.warn("Exception reading tag", e);
        }
        return tag;
    }

}
