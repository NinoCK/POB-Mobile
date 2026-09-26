# libpoblua.so: LuaJIT (the version Path of Building ships) + the JNI bridge.
# The target-specific LuaJIT sources in gen/ come from tools/build_luajit.py.
cmake_minimum_required(VERSION 3.22.1)
project(poblua C ASM)

set(LUAJIT_SRC ${CMAKE_CURRENT_SOURCE_DIR}/luajit/src)
set(LUAJIT_GEN ${CMAKE_CURRENT_SOURCE_DIR}/gen/${ANDROID_ABI})
if(NOT EXISTS ${LUAJIT_GEN}/lj_vm.S)
    message(FATAL_ERROR "No generated LuaJIT sources for ${ANDROID_ABI}: run tools/build_luajit.py")
endif()
include(${LUAJIT_GEN}/config.cmake)

add_library(poblua SHARED
    ${LUAJIT_SRC}/ljamalg.c
    ${LUAJIT_GEN}/lj_vm.S
    pob_jni.c
)
# Generated headers (and luajit.h) come before the LuaJIT sources
target_include_directories(poblua PRIVATE ${LUAJIT_GEN} ${CMAKE_CURRENT_SOURCE_DIR}/gen ${LUAJIT_SRC})
target_compile_definitions(poblua PRIVATE ${LUAJIT_TARGET_DEFINES})
# As in LuaJIT's Makefile
target_compile_options(poblua PRIVATE -O2 -fomit-frame-pointer -U_FORTIFY_SOURCE -fno-stack-protector)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    # The interpreter is ARM code; keep the C code in the same instruction set
    target_compile_options(poblua PRIVATE -marm)
endif()
target_link_libraries(poblua PRIVATE m dl)
# 16 KB page size support (Android 15+)
target_link_options(poblua PRIVATE -Wl,-z,max-page-size=16384)
