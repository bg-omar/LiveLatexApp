cmake_minimum_required(VERSION 3.22.1)
project(livelatex_engine)

add_library(livelatex_engine SHARED livelatex_jni.cpp)

find_library(log-lib log)
target_link_libraries(livelatex_engine ${log-lib})
