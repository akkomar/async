#!/bin/bash
scalac -version
mkdir -p classes
scalac -P:continuations:enable -d classes -deprecation -feature src/main/scala/scala/async/*.scala
