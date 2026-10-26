cmake_minimum_required(VERSION 3.18)
project(mlpoddsp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mlpoddsp SHARED mlpod_dsp_jni.cpp)
target_link_libraries(mlpoddsp log)
target_compile_options(mlpoddsp PRIVATE -O3 -fno-math-errno)
# Android 15: pagine da 16 KB
target_link_options(mlpoddsp PRIVATE -Wl,-z,max-page-size=16384)
