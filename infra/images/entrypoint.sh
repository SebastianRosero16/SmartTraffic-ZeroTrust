#!/bin/sh
set -eu

: "${MAIN_CLASS:?MAIN_CLASS must be configured}"
exec java -cp "/app/classes:/app/lib/*" "$MAIN_CLASS"
