package org.gregoire.media;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.codec.VideoCodec;
import org.red5.io.ITag;
import org.red5.io.flv.impl.FLVWriter;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.stream.IStreamData;
import org.red5.server.stream.consumer.ImmutableTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FLVRecorder {

	private Logger log = LoggerFactory.getLogger(FLVRecorder.class);

	private AtomicBoolean started = new AtomicBoolean(false);

	private File file;

	private FLVWriter writer;
	
	private Semaphore lock = new Semaphore(1, true);

	private String fileName = "output.flv";

	private volatile int previousTagSize;

	public void start() throws IOException {
		log.debug("start");
		if (started.compareAndSet(false, true)) {
			try {
				lock.acquire();
    			file = new File(fileName);
    			// remove previous flv if it exists
				if (file.exists()) {
					file.delete();
					file.createNewFile();
				}    			
    			writer = new FLVWriter(file, false);
    			//writer.setAudioCodecId(AudioCodec.SPEEX.getId());
    			writer.setVideoCodecId(VideoCodec.AVC.getId());
			} catch (InterruptedException e) {
				log.warn("Exception during start", e);
            } finally {
				lock.release();
			}
		} else {
			log.debug("Already started");
		}
	}

	public void stop() {
		log.debug("stop");
		if (started.compareAndSet(true, false)) {
			lock.drainPermits();
			writer.close();
			log.debug("Bytes written at stop: {}", writer.getBytesWritten());
			writer = null;
			file = null;
		} else {
			log.debug("Not started or already stopped");
		}
	}

	@SuppressWarnings("rawtypes")
	public boolean process(IRTMPEvent event) {
		log.debug("process");
		boolean result = false;
		if (started.get()) {
			try {
				lock.acquire();
				IoBuffer buf = ((IStreamData) event).getData();
				int bufLength = buf.limit();
				result = writer.writeTag(ImmutableTag.build(event.getDataType(), event.getTimestamp(), buf, previousTagSize));
				this.previousTagSize = bufLength + 11; // add 11 for the tag header length (fixed for flv at 11)
			} catch (Exception e) {
				log.warn("Exception processing audio", e);
			} finally {
				lock.release();
			}
		} else {
			log.debug("Not started");
		}
		return result;
	}

	public boolean processAudio(byte[] buf, int timestamp) {
		boolean result = false;
		if (started.get()) {
			try {
				lock.acquire();
				result = writer.writeTag(ImmutableTag.build(ITag.TYPE_AUDIO, timestamp, buf, previousTagSize));
				this.previousTagSize = buf.length + 11; // add 11 for the tag header length (fixed for flv at 11)
			} catch (Exception e) {
				log.warn("Exception processing audio", e);
			} finally {
				lock.release();
			}
		} else {
			log.debug("Not started");
		}
		return result;
	}

	public boolean processVideo(byte[] buf, int timestamp) {
		boolean result = false;
		if (started.get()) {
			try {
				lock.acquire();
				result = writer.writeTag(ImmutableTag.build(ITag.TYPE_VIDEO, timestamp, buf, previousTagSize));
				this.previousTagSize = buf.length + 11; // add 11 for the tag header length (fixed for flv at 11)
			} catch (Exception e) {
				log.warn("Exception processing audio", e);
			} finally {
				lock.release();
			}
		} else {
			log.debug("Not started");
		}
		return result;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName.endsWith(".flv") ? fileName : fileName + ".flv";
	}

}
