#!/bin/bash
# exit immediately if error
set -e

java $JAVA_OPTS -cp "classes:app:app/lib/*" main.core
