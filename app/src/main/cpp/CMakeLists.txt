cmake_minimum_required(VERSION 3.22.1)
project("llama-android")

add_library(llama-android SHARED llama-bridge.c)

# Include directory with llama.h (copied during CI)
target_include_directories(llama-android PRIVATE ${CMAKE_SOURCE_DIR}/llama-include)

# Link against the pre-built llama library
set(LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libllama.so)

# ggml - only link if it exists
if(EXISTS ${LIB_DIR}/libggml.so)
    add_library(ggml SHARED IMPORTED)
    set_target_properties(ggml PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libggml.so)
    target_link_libraries(llama-android llama ggml log)
else()
    target_link_libraries(llama-android llama log)
endif()
