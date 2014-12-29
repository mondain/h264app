package org.gregoire.media;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.jcodec.common.Assert;
import org.jcodec.common.NIOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents an avcC box.
 * 
 * @author Paul Gregoire (mondain@gmail.com)
 */
public class AvcConfigBox {

	private static Logger log = LoggerFactory.getLogger(AvcConfigBox.class);

	private int profile;

	private int profileCompat;

	private int level;

	private int nalLengthSize;

	private byte[] sps;

	private byte[] pps;

	public AvcConfigBox() {
	}
	
	public AvcConfigBox(ByteBuffer input) {
		NIOUtils.skip(input, 1); // skip version
		profile = input.get() & 0xff;
		profileCompat = input.get() & 0xff;
		level = input.get() & 0xff;
		int flags = input.get() & 0xff;
		nalLengthSize = (flags & 0x03) + 1;
		int nSPS = input.get() & 0x1f; // 3 bits reserved + 5 bits number of sps
		log.trace("SPS count: {}", nSPS);
		if (nSPS == 1) {
			int spsSize = input.getShort();
			Assert.assertEquals(0x27, input.get() & 0x3f);
			setSps(new byte[spsSize]);
			input.get(getSps());
		} else {
			log.warn("Multiple SPS ignored");
		}
		int nPPS = input.get() & 0xff;
		log.trace("PPS count: {}", nPPS);
		if (nPPS == 1) {
			int ppsSize = input.getShort();
			Assert.assertEquals(0x28, input.get() & 0x3f);
			pps = new byte[ppsSize];
			input.get(pps);
		} else {
			log.warn("Multiple PPS ignored");
		}
	}

	public AvcConfigBox(ByteBuffer input, boolean flvTag) {
		input.position(input.position() + 5);
		NIOUtils.skip(input, 1); // skip version
		profile = input.get() & 0xff;
		profileCompat = input.get() & 0xff;
		level = input.get() & 0xff;
		int flags = input.get() & 0xff;
		nalLengthSize = (flags & 0x03) + 1;
		int nSPS = input.get() & 0x1f; // 3 bits reserved + 5 bits number of sps
		log.trace("SPS count: {}", nSPS);
		if (nSPS == 1) {
			int spsSize = input.getShort();
			log.trace("SPS size: {}" , spsSize);		
			byte spsMarker = (byte) (input.get() & 0x3f); 
			if (spsMarker != 0x27) {
				log.warn("SPS marker not found where expected");
			}
			sps = new byte[spsSize];
			input.get(sps);
		} else {
			log.warn("Multiple SPS ignored");
		}
		int nPPS = input.get() & 0xff;
		log.trace("PPS count: {}", nPPS);
		if (nPPS == 1) {
			int ppsSize = input.getShort();
			log.trace("PPS size: {}" , ppsSize);
			byte ppsMarker = (byte) (input.get() & 0x3f); 
			if (ppsMarker != 0x28) {
				log.warn("PPS marker not found where expected");
			}
			pps = new byte[ppsSize];
			input.get(pps);
		} else {
			log.warn("Multiple PPS ignored");
		}
    }

	public byte[] getSps() {
	    return sps;
    }

	public void setSps(byte[] sps) {
	    this.sps = sps;
    }

	public int getProfile() {
		return profile;
	}

	public void setProfile(int profile) {
		this.profile = profile;
	}

	public int getProfileCompat() {
		return profileCompat;
	}

	public void setProfileCompat(int profileCompat) {
		this.profileCompat = profileCompat;
	}

	public int getLevel() {
		return level;
	}

	public void setLevel(int level) {
		this.level = level;
	}

	public int getNalLengthSize() {
		return nalLengthSize;
	}

	public void setNalLengthSize(int nalLengthSize) {
		this.nalLengthSize = nalLengthSize;
	}

	public byte[] getPps() {
		return pps;
	}

	public void setPps(byte[] pps) {
		this.pps = pps;
	}

	@Override
	public String toString() {
		return "AvcConfigBox [profile=" + profile + ", profileCompat=" + profileCompat + ", level=" + level + ", nalLengthSize=" + nalLengthSize + ", sps=" + Arrays.toString(sps) + ", pps=" + Arrays.toString(pps) + "]";
	}

}
