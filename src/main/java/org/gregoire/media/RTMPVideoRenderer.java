package org.gregoire.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.media.Buffer;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.server.net.rtmp.event.VideoData;
import org.red5.server.net.rtmp.message.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders incoming video image data into a flash usable format and dispatches it to an attached broadcast stream.
 * 
 * @author Paul Gregoire (mondain@gmail.com)
 * @author Andy Shaules (bowljoman@gmail.com)
 */
public class RTMPVideoRenderer {

	private static Logger log = LoggerFactory.getLogger(RTMPVideoRenderer.class);

	public final static int BUFFER_PROCESSED_FAILED = -1;

	public final static int BUFFER_PROCESSED_OK = 0;
	
	private static String[] names = {"Undefined", "Coded Slice", "Partition A", "Partition B", "Partition C", "IDR", "SEI", "SPS", "PPS", "AUD","","","","","","","","","","","","","","","","","","","FUA"};
		
	private static VideoData videoConfig;

	private static boolean configSent;
	
	private static long startTs;
	
	private static byte[] sps, pps;
	
	private static FLVRecorder recorder = new FLVRecorder();
	
	public static void start() {
		log.debug("start");
		startTs = System.currentTimeMillis();		
		if (recorder != null) {
			try {
	            recorder.start();
	        } catch (IOException e) {
	        	log.warn("Exception starting recorder", e);
	        }
		}
	}
	
	public static void stop() {
		log.debug("stop");		
		if (recorder != null) {
			recorder.stop();
		}
	}

	private static int process(Buffer outBuffer) {
		int ret = BUFFER_PROCESSED_OK;
		try {
    		if (log.isDebugEnabled()) {
    			log.debug("process - buffer - ts: {} format: {} length: {}", outBuffer.getTimeStamp(), outBuffer.getFormat(), outBuffer.getLength());		
    		}
    		// pull out the bytes in to a properly sized array
    		int encLength = outBuffer.getLength();
    		log.debug("Encoded video - offset: {} length: {}", outBuffer.getOffset(), encLength);
    		byte[] data = new byte[encLength];
    		System.arraycopy((byte[]) outBuffer.getData(), 0, data, 0, encLength);
    		// check for decoder OK
    		if (ret == BUFFER_PROCESSED_OK) {
    			// http://www.cs.columbia.edu/~hgs/rtp/faq.html#timestamp-computed
    			long timestamp = System.currentTimeMillis() - startTs;
    			log.debug("Video timestamp: {} ms", timestamp);
        		// get the NALU
        		int nalsProcessed = processNals(data, timestamp);
        		log.debug("NALU processed: {}", nalsProcessed);
    		}
    	} catch (Throwable t) {
    		log.error("Exception in video render", t);
    	}
		return ret;
	}
	
	/**
	 * Returns nal header type.
	 * 
	 * @param data
	 * @param position
	 * @return
	 */
	private static int readNalHeader(byte bite) {
		int nalType = bite & 0x1f;
		log.debug(names[nalType]);
		return nalType;
	}
	
	/**
	 * Returns count of NALU's processed for given byte array.
	 * 
	 * @param frame
	 * @param timestamp
	 * @return nal process count
	 */
	public static int processNals(byte[] frame, long timestamp) {
		int count = 0;
		
		for (int i = 0; i < frame.length - 4; i++) {
			if (frame[i] == 0) {
				if (frame[i + 1] == 0) {
					if (frame[i + 2] == 0) {
						if (frame[i + 3] == 1) {
							log.debug("Found marker ");
							i += 4; // cursor past 0001
							// look for next 8_bit_zero marker
							int size = findFrameEnd(frame, i+4);
							if (size == -1) {
								// from i to end of segment
								size = frame.length - i;
							} else {
								// size is from start of segment to next 8_bit_zero marker
								size = size - i;
							}
							// process an individual nal
							processNal(frame, i, size, (int) timestamp);
							timestamp+=33;
							count++;
							// cue next '0'001 point 
							i += size - 1;
						} else {
							log.debug("Expected marker {}, {}" , frame[i+3], frame[i+4]);
						}
					} else {
						if (frame[i + 2] == 1) {
							log.debug("Marker at 3");
							i += 3; // cursor past 0001
							int size = findFrameEnd(frame, i);
							if (size == -1) {
								// from i to end of segment
								size = frame.length - i;
							} else {
								// size is from start of segment to next 8_bit_zero marker
								size = size - i;
							}
							// process an individual nal
							processNal(frame, i, size, (int) timestamp);
							timestamp+=33;
							count++;
							// cue next '0'001 point 
							i += size - 1;
							
						}
					}
				}
			}
		}
		return count;
	}
	
	/**
	 * Returns point of '0'001' marker or -1.
	 * 
	 * @param frame The NALU stream
	 * @param offset The point to search from
	 * @return The point before the next 0001 marker
	 */
	public static int findFrameEnd(byte[] frame, int offset) {		
		for (int i = offset; i < frame.length - 3; i++) {
			if (frame[i] == 0) {
				if (frame[i + 1] == 0) {
					if (frame[i + 2] == 0) {
						if (frame[i + 3] == 1) {
							return i;
						}
					}else if (frame[i + 2] == 1){
						return i;
					}
				}
			}
		}
		log.debug("Frame end not found");
		return -1;
	}	
	
	/**
	 * Processes an individual NALU.
	 * 
	 * @param data
	 * @param offset
	 * @param size
	 * @param timestamp
	 */
	private static void processNal(byte[] data, int offset, int size, int timestamp) {
		log.debug("processNal - offset: {} size: {} ts: {}", offset, size, timestamp);
		VideoData video;
		final int type = readNalHeader((byte) data[offset]);
		log.debug("nal type : {}", type);
		switch(type) {
			case 5: // IDR
				// check that AVC config information has been set up
				if (videoConfig == null) {
					// if we have sps and pps, build the config
					if (sps != null && pps != null) {
						// try to build the config
						buildVideoConfigFrame(timestamp);
						// if we have a config send it
						if (videoConfig != null) {
							log.debug("Sending avc config");
							if (recorder != null) {
								try {
	                                recorder.process(videoConfig.duplicate());
                                } catch (Exception e) {
                                	log.warn("Exception duplicating data", e);
                                }
							}
							configSent = true;
						}
					} else {
						log.debug("Configuration data not yet available to build config");
						return;
					} 
				}
				if (!configSent) {
					return;
				}
	    		log.debug("Sending IDR frame");
	    		video = buildVideoFrame(data, offset, size, timestamp);
				if (recorder != null) {
					try {
                        recorder.process(video.duplicate());
                    } catch (Exception e) {
                    	log.warn("Exception duplicating data", e);
                    }
				}	
				break;
			case 1: // Coded Slice
				// check that AVC config information has been set up
				if (videoConfig == null) {
					// if we have sps and pps, build the config
					if (sps != null && pps != null) {
						// try to build the config
						buildVideoConfigFrame(timestamp);
						// if we have a config send it
						if (videoConfig != null) {
							log.debug("Sending avc config");
							if (recorder != null) {
								try {
	                                recorder.process(videoConfig.duplicate());
                                } catch (Exception e) {
                                	log.warn("Exception duplicating data", e);
                                }
							}
							configSent = true;
						}
					} else {
						log.debug("Configuration data not yet available to build config");
						return;
					} 
				}
				if (!configSent) {
					return;
				}
	    		log.debug("Sending slice frame");
	    		video = buildVideoFrame(data, offset, size, timestamp);
				if (recorder != null) {
					try {
                        recorder.process(video.duplicate());
                    } catch (Exception e) {
                    	log.warn("Exception duplicating data", e);
                    }
				}
				break;
			case 7: // SPS - 67
				log.debug("SPS data");
				sps = new byte[size];
				System.arraycopy(data, offset, sps, 0, sps.length);
				break;
			case 8: // PPS - 68
				log.debug("PPS data");
				pps = new byte[size];
				System.arraycopy(data, offset, pps, 0, pps.length);
				break;
			default:
				log.debug("Non-picture data");
		}	
    }

	/**
	 * Builds a configuration video frame.
	 * 
	 * @param timestamp
	 * @return
	 */
	private static void buildVideoConfigFrame(int timestamp) {
		log.debug("buildConfig");
		int cursor = 0;
		// avc header 5, sps header 8, sps len, pps header 3, pps len
		byte[] avcConfig = new byte[13 + sps.length + 3 + pps.length];
		// write prefix bytes
		avcConfig[cursor++] = (byte) 0x17; // 0x10 - key frame; 0x07 - H264_CODEC_ID
		avcConfig[cursor++] = (byte) 0; // 0: AVC sequence header; 1: AVC NALU; 2: AVC end of sequence
		avcConfig[cursor++] = (byte) 0; // composition time
		avcConfig[cursor++] = (byte) 0; // composition time
		avcConfig[cursor++] = (byte) 0; // composition time
		// sps
		avcConfig[cursor++] = (byte) 1; // version
		avcConfig[cursor++] = (byte) sps[1]; // profile
		avcConfig[cursor++] = (byte) sps[2]; // profile compat
		avcConfig[cursor++] = (byte) sps[3]; // level
		// reserved bytes - adobe doesn't write these
		avcConfig[cursor++] = (byte) 0x3; // 6 bits reserved (111111) + 2 bits nal size length - 1 (11)
		avcConfig[cursor++] = (byte) 0x1; // 3 bits reserved (111) + 5 bits number of sps (00001)
		// sps length
		avcConfig[cursor++] = (byte) ((sps.length >> 8) & 0xff);
		avcConfig[cursor++] = (byte) (sps.length & 0xff);
		// sps data
		for (int k = 0; k < sps.length; k++) {
			avcConfig[cursor++] = sps[k];
		}
		// pps
		avcConfig[cursor++] = 1; //version
		// pps length - short to big endian
		avcConfig[cursor++] = (byte) ((pps.length >> 8) & 0x000000ff);
		avcConfig[cursor++] = (byte) (pps.length & 0x000000ff);
		// pps data
		for (int k = 0; k < pps.length; k++) {
			avcConfig[cursor++] = pps[k];
		}
		// testing
		if (log.isDebugEnabled()) {
			log.debug("AVC config: {}", Arrays.toString(avcConfig));
		}
		videoConfig = new VideoData(IoBuffer.wrap(avcConfig));
		videoConfig.setHeader(new Header());
		videoConfig.getHeader().setTimer((int) timestamp);	
		videoConfig.setTimestamp((int) timestamp);
		// testing
		if (log.isDebugEnabled()) {
    		AvcConfigBox avcC = new AvcConfigBox(ByteBuffer.wrap(avcConfig), true);	
    		log.debug("Box: {}", avcC);
		}
	}
	
	/**
	 * Builds a video frame (non-config).
	 * 
	 * <pre>
	 * flv tagged h264 encoded bytes resemble this sequence: 23 01 00 00 00 00 00 00 02 09 16 00 00 00 15 06 00...
	 * |--Header--|--Presentation offset--|--Size of packet + 1 --|--Encoded data 
	 *  0x17 0x01  0x0 0x0 0x0             0x00 0x00 0x00 0x02      0x0916 bytes of data
	 * </pre>
	 * 
	 * @param nal
	 * @param offset
	 * @param size
	 * @param timestamp
	 * @return
	 */
	private static VideoData buildVideoFrame(byte[] nal, int offset, int size, int timestamp) {
		log.debug("buildVideoFrame");
		byte[] framedData = new byte[size + 9];
		// write prefix bytes
		framedData[0] = (byte) ((nal[offset] & 0x1f) == 5 ? 0x17 : 0x27); // 0x10 - key frame; 0x07 - H264_CODEC_ID
		framedData[1] = (byte) 0x01; // 0: AVC sequence header; 1: AVC NALU; 2: AVC end of sequence
		// presentation off set
		framedData[2] = (byte) 0;
		framedData[3] = (byte) 0;
		framedData[4] = (byte) 0;
		// nal size
		framedData[5] = (byte) (size >> 24);
		framedData[6] = (byte) (size >> 16);
		framedData[7] = (byte) (size >> 8);
		framedData[8] = (byte) size;
		// copy in encoded bytes
		System.arraycopy(nal, offset, framedData, 9, size - 1);
		// write end byte
		framedData[framedData.length - 1] = (byte) 0;
		VideoData video = new VideoData(IoBuffer.wrap(framedData));
		video.setHeader(new Header());
		video.getHeader().setTimer((int) timestamp);	
		video.setTimestamp((int) timestamp);
		return video;
	}

}