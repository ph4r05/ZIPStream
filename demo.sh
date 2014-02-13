#!/bin/bash

cat my.apk | CLASSPATH=target/ZIPStream-1.0-SNAPSHOT.jar java cz.muni.fi.xklinec.zipstream.Mallory -f 2 --padd-extra 8192  --cmd '/bin/cp <<INPUTAPK>> <<OUTPUTAPK>>' > haha.apk

