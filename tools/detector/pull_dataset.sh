#!/usr/bin/env bash
# Pulls captured dataset frames from the iQOO into dataset/images/ (git-ignored), prefixed by session.
set -euo pipefail
export MSYS_NO_PATHCONV=1
PKG=com.application.sensorforge
mkdir -p dataset/images
for s in $(adb shell run-as $PKG ls files/dataset | tr -d '\r'); do
  for f in $(adb shell run-as $PKG ls files/dataset/$s | tr -d '\r'); do
    out="dataset/images/${s}_${f}"
    [ -f "$out" ] || adb exec-out run-as $PKG cat "files/dataset/$s/$f" > "$out"
  done
done
echo "$(ls dataset/images | wc -l) frames in dataset/images"
