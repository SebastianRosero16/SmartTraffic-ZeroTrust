#!/bin/sh
set -eu

: "${MAIN_CLASS:?MAIN_CLASS must be configured}"
exec java \
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+ExitOnOutOfMemoryError \
    -cp "/app/classes:/app/lib/*" \
    "$MAIN_CLASS"
