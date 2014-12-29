h264app
=======

Example and debugging app

The testing source file is included in the root of the project as:
```
dump.h264
```
It consists of raw encoded h.264 bytes; there is no included audio and the video frame parameters are as such:
 * Width: 640 px
 * Height: 480 px
 * Frames per second: 15

The objective of this project example is to produce an FLV file which mirrors the visual consistency produced by tools such as ffmpeg.

FFMpeg
=======
Using ffmpeg to verify media (Tested with ffmpeg version 2.5)

Create an mp4 from the flv output file generated in the applicaton
```
ffmpeg -i output.flv -vcodec libx264 -an flv-to-h264-output.mp4
```

Create an mp4 from the h264 dump file
```
ffmpeg -i dump.h264 -vcodec copy -an rawh264-to-mp4-output.mp4
```

Create an flv from the h264 dump file
```
ffmpeg -i dump.h264 -vcodec copy -an -f flv rawh264-to-flv-output.flv
```
