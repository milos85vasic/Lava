# CMakeLists.txt — Android NDK build of the JNI bridge for the lava-api-go embed.
#
# This builds liblavaapi_jni.so, a thin native library that links against the
# prebuilt liblavaapi.so (produced by `go build -buildmode=c-shared` via
# scripts/build-cshared.sh) and exposes the JNI entry points the Kotlin
# `digital.vasic.lava.apigo.LavaNative` object binds.
#
# Phase C/D wiring (the Android :app or a dedicated :lava-api-embed module):
#   externalNativeBuild {
#       cmake {
#           path = file("../../lava-api-go/cmd/lavaapi-cshared/jni/CMakeLists.txt")
#       }
#   }
# AND the prebuilt liblavaapi.so + liblavaapi.h must be made available per ABI.
# Set LAVAAPI_PREBUILT_DIR to the directory containing
#   <abi>/liblavaapi.so  and  <abi>/liblavaapi.h
# (i.e. lava-api-go/build/jniLibs); ANDROID_ABI selects the per-ABI subdir.
#
# Alternatively, since liblavaapi.so already contains the cgo runtime and the
# exported C functions, an integrator MAY skip CMake entirely and package BOTH
# liblavaapi.so (from the build script) and a separately-compiled
# liblavaapi_jni.so. This CMake file is the canonical recipe for producing the
# JNI .so so Gradle's externalNativeBuild owns it.

cmake_minimum_required(VERSION 3.18.1)

project(lavaapi_jni C)

# Directory holding the prebuilt Go c-shared artifacts, laid out as
# <LAVAAPI_PREBUILT_DIR>/<ABI>/liblavaapi.{so,h}. Defaults to the build script's
# output dir resolved relative to this file.
# This file lives at lava-api-go/cmd/lavaapi-cshared/jni/CMakeLists.txt, so the
# build script's output dir (lava-api-go/build/jniLibs) is three levels up.
if(NOT DEFINED LAVAAPI_PREBUILT_DIR)
    set(LAVAAPI_PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/jniLibs")
endif()

set(LAVAAPI_ABI_DIR "${LAVAAPI_PREBUILT_DIR}/${ANDROID_ABI}")

# Import the prebuilt Go c-shared library for this ABI.
add_library(lavaapi SHARED IMPORTED)
set_target_properties(lavaapi PROPERTIES
    IMPORTED_LOCATION "${LAVAAPI_ABI_DIR}/liblavaapi.so")

# The cgo-generated header (liblavaapi.h) lives alongside the .so.
include_directories("${LAVAAPI_ABI_DIR}")

# Build the JNI bridge.
add_library(lavaapi_jni SHARED
    jni_bridge.c)

# Link the JNI bridge against the prebuilt Go library and the Android log lib.
find_library(log-lib log)
target_link_libraries(lavaapi_jni
    lavaapi
    ${log-lib})
