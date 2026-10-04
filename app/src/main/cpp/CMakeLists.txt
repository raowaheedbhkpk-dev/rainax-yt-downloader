# Builds the MP3 encoder (LAME 3.99.5, LGPL) as librainaxlame.so for each phone type.
cmake_minimum_required(VERSION 3.22.1)
project(rainaxlame C)

file(GLOB LAME_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/lame/*.c)
add_library(rainaxlame SHARED rainax_lame.c ${LAME_SOURCES})
target_include_directories(rainaxlame PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/lame)
target_compile_definitions(rainaxlame PRIVATE HAVE_CONFIG_H NDEBUG STDC_HEADERS)
target_compile_options(rainaxlame PRIVATE -O3 -ffast-math -fno-strict-aliasing -w)
target_link_libraries(rainaxlame m)
# 16 KB page alignment (needed by newer Android versions and Google Play)
target_link_options(rainaxlame PRIVATE "-Wl,-z,max-page-size=16384")
