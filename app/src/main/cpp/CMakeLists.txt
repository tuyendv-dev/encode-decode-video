cmake_minimum_required(VERSION 3.18.1)
project("demoplayvideo")

# Set C++ standard
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Opus directories
set(OPUS_DIR ${CMAKE_SOURCE_DIR}/opus)
set(OPUS_INCLUDE_DIR ${OPUS_DIR}/include)
set(OPUS_LIB_DIR ${OPUS_DIR}/lib/${ANDROID_ABI})

# Debug info
message(STATUS "========================================")
message(STATUS "Building Opus JNI for ${ANDROID_ABI}")
message(STATUS "CMAKE_SOURCE_DIR: ${CMAKE_SOURCE_DIR}")
message(STATUS "OPUS_INCLUDE_DIR: ${OPUS_INCLUDE_DIR}")
message(STATUS "OPUS_LIB_DIR: ${OPUS_LIB_DIR}")
message(STATUS "========================================")

# Include directories
include_directories(${OPUS_INCLUDE_DIR})

# Check if opus headers exist
if(EXISTS "${OPUS_INCLUDE_DIR}/opus/opus.h")
    message(STATUS "✓ opus.h found")
else()
    message(FATAL_ERROR "✗ opus.h NOT found at ${OPUS_INCLUDE_DIR}/opus/opus.h")
endif()

# Import Opus static library
add_library(opus STATIC IMPORTED)
set_target_properties(opus PROPERTIES
        IMPORTED_LOCATION ${OPUS_LIB_DIR}/libopus.a
)

# Check if opus library exists
if(EXISTS "${OPUS_LIB_DIR}/libopus.a")
    message(STATUS "✓ libopus.a found for ${ANDROID_ABI}")
else()
    message(FATAL_ERROR "✗ libopus.a NOT found at ${OPUS_LIB_DIR}/libopus.a")
endif()

# Build JNI wrapper library
add_library(opusdemo SHARED opus_jni.cpp)

# Link libraries - QUAN TRỌNG: Thứ tự linking
target_link_libraries(opusdemo
        -Wl,--whole-archive
        opus      # Opus codec
        -Wl,--no-whole-archive
        android   # Android APIs
        log       # Android logging
        m         # Math library (required by Opus)
)

# Compiler flags
target_compile_options(opusdemo PRIVATE
        -Wall
        -Wextra
        -O3
)

message(STATUS "✓ CMake configuration completed")