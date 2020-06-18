h264app
=======

Example and debugging app

The testing source files included in the root of the project: 
 * `mediatags.dat` video only, I and P frames seem ok
 * `mediatags2.dat` audio and video, I and P frames grouped in blocks or missing

Consist of semi-custom packaged h.264 bytes. The objective of this project example is to produce an MP4 file which can be played back in VLC etc. A script `run.sh` is included to make testing easy, it will read the `mediatags.dat` file and produce the `output.mp4`.

To switch between dat file source for ingest change the last parameter:

```sh
java -cp target/classes:lib/bcprov-jdk15on-1.62.jar:lib/slf4j-api-1.7.25.jar:lib/logback-core-1.2.3.jar:lib/logback-classic-1.2.3.jar:lib/red5-server-common-1.1.1.jar:lib/red5-io-1.1.1.jar:lib/ehcache-core-2.5.0.jar:lib/mina-core-2.0.21.jar:lib/jcodec-0.2.5.jar org.gregoire.media.MP4WriterMain mediatags2.dat
```

Target JVM version is 1.8.