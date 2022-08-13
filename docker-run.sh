#!/bin/bash
CLASSPATH=$(find /root/ -name '*.jar' -printf '%p:' | sed 's/:$//')
java -cp $CLASSPATH mbta.actor.MBTAMain