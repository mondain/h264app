#!/bin/bash

java -cp target/classes:lib/bcprov-jdk15on-1.62.jar:lib/slf4j-api-1.7.25.jar:lib/logback-core-1.2.3.jar:lib/logback-classic-1.2.3.jar:lib/red5-server-common-1.1.1.jar:lib/red5-io-1.1.1.jar:lib/ehcache-core-2.5.0.jar:lib/mina-core-2.0.21.jar:lib/jcodec-0.2.5.jar org.gregoire.media.MP4WriterMain 

