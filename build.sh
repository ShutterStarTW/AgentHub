#!/usr/bin/env bash

echo ">>> Build version $(cat VERSION.md)"
./gradlew -q build || exit $?
ls -rtl build/distributions