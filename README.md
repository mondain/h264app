h264app
=======

Example and debugging app

The testing source file is included in the root of the project `mediatags.dat`. It consists of semi-custom packaged h.264 bytes; there is no included audio. The objective of this project example is to produce an MP4 file which can be played back in VLC etc. A script `run.sh` is included to make testing easy, it will read the `mediatags.dat` file and produce the `output.mp4`.

Target JVM version is 1.8.