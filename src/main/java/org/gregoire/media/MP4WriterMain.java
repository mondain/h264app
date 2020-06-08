package org.gregoire.media;

import java.io.IOException;

import org.red5.io.ITag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.red5pro.override.io.MP4Writer;
import com.red5pro.override.io.RawTagReader;

/**
 * Reads a specially crafted data file containing FLV "tag" content and writes an MP4 file using
 * JCodec.
 * 
 * @author Paul Gregoire (mondain@gmail.com)
 *
 */
public class MP4WriterMain {

    private static Logger log = LoggerFactory.getLogger(MP4WriterMain.class);

    public static void main(String[] args) throws IOException {
        String filePath = args.length > 0 ? args[0] : "mediatags.dat";
        // construct our reader
        RawTagReader reader = new RawTagReader(filePath);
        // construct our writer
        MP4Writer writer = new MP4Writer("output.mp4");
        // loop through read and write of the tags
        do {
            ITag tag = reader.readTag();
            if (tag != null) {
                writer.writeTag(tag);
            }
        } while (reader.hasMoreTags());
        // close down, we're done
        reader.close();
        writer.close();
        log.info("Finished read/write loop");
    }

}
