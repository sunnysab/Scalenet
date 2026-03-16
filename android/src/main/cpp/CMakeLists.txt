cmake_minimum_required(VERSION 3.22.1)

project(footprintjni LANGUAGES C CXX)

add_library(footprintjni SHARED
  footprint_jni.cpp
)

target_compile_features(footprintjni PRIVATE cxx_std_17)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(footprintjni
  ${log-lib}
  ${dl-lib}
)

