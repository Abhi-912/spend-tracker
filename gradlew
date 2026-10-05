#!/bin/sh
# Lightweight wrapper: uses system Gradle (needs Gradle 8.7, JDK 17/21, ANDROID_HOME set).
exec gradle "$@"
