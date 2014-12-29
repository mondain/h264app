package org.gregoire.media;

import java.awt.Dimension;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

import javax.media.Format;
import javax.media.format.VideoFormat;

import org.jitsi.impl.neomedia.format.ParameterizedVideoFormat;
import org.jitsi.impl.neomedia.format.VideoMediaFormatImpl;
import org.jitsi.service.neomedia.codec.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.coremedia.iso.boxes.Container;
import com.googlecode.mp4parser.FileDataSourceImpl;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.tracks.H264TrackImpl;

public class H264Main {

	private static Logger log = LoggerFactory.getLogger(H264Main.class);
	
	// JMF/FMJ video format for our pre-recorded dump file (for reference)
	private final static VideoFormat VIDEO_FORMAT = new ParameterizedVideoFormat(
            Constants.H264,
            new Dimension(640, 480),
            Format.NOT_SPECIFIED,
            Format.byteArray,
            15,
            ParameterizedVideoFormat.toMap(VideoMediaFormatImpl.H264_PACKETIZATION_MODE_FMTP, "0")); // 0=one nal per packet, 1=multi-nal
	
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
		// get the prerecorded h264 encoded bytes
		RandomAccessFile raf = new RandomAccessFile("dump.h264", "rw");
		// read entire file into array
		byte[] chunk= new byte[(int) raf.length()];
		raf.readFully(chunk);
		// start the renderer
		RTMPVideoRenderer.start();
		// process our bytes
		RTMPVideoRenderer.processNals(chunk, 0);
		try {
            raf.close();
            RTMPVideoRenderer.stop();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
        	// create the mp4 for verification that our dump file is ok
            makeMP4();        	
        }
	}

}

