# Builds the MP3 encoder (LAME 3.99.5, LGPL) as librainaxlame.so for each phone type.
cmake_minimum_required(VERSION 3.22.1)
project(rainaxlame C)

file(GLOB LAME_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/lame/*.c)
add_library(rainaxlame SHARED rainax_lame.c ${LAME_SOURCES})
target_include_directories(rainaxlame PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/lame)
target_compile_definitions(rainaxlame PRIVATE HAVE_CONFIG_H NDEBUG STDC_HEADERS)
target_compile_options(rainaxlame PRIVATE -O3 -ffast-math -fno-strict-aliasing -w)
# Opus decoder (libopus 1.5.2, BSD licence) for fast MP3 converting: decodes inside the app
file(STRINGS ${CMAKE_CURRENT_SOURCE_DIR}/opus/sources.txt OPUS_LIST)
set(OPUS_SOURCES "")
foreach(f ${OPUS_LIST})
    list(APPEND OPUS_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/opus/${f})
endforeach()
add_library(rxopus STATIC ${OPUS_SOURCES})
target_include_directories(rxopus PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/opus/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/opus/celt ${CMAKE_CURRENT_SOURCE_DIR}/opus/silk ${CMAKE_CURRENT_SOURCE_DIR}/opus/silk/float)
target_compile_definitions(rxopus PRIVATE OPUS_BUILD USE_ALLOCA HAVE_LRINTF HAVE_LRINT NDEBUG)
target_compile_options(rxopus PRIVATE -O2 -fPIC -w)
target_sources(rainaxlame PRIVATE rainax_opus.c)
target_link_libraries(rainaxlame rxopus m)
# 16 KB page alignment (needed by newer Android versions and Google Play)
target_link_options(rainaxlame PRIVATE "-Wl,-z,max-page-size=16384")
