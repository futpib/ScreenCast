cmake_minimum_required(VERSION 3.22)
project(screencast_opus LANGUAGES C CXX)

set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
add_subdirectory(opus)

add_library(screencast_opus SHARED OpusEncoderJni.cpp)
target_link_libraries(screencast_opus PRIVATE opus)
