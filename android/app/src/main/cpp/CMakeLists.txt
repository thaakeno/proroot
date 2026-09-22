cmake_minimum_required(VERSION 3.22)
project(proroot_native C CXX)

include(FetchContent)

set(ANLAND_GIT_TAG "a70879f059a2e8439fdb132195c3dd10d4810106" CACHE STRING "Pinned Anland commit")

FetchContent_Declare(
    anland
    GIT_REPOSITORY https://github.com/lfdevs/anland-termux.git
    GIT_TAG ${ANLAND_GIT_TAG}
    GIT_SHALLOW FALSE
    PATCH_COMMAND
        git reset --hard ${ANLAND_GIT_TAG}
        COMMAND git apply --whitespace=nowarn ${CMAKE_CURRENT_SOURCE_DIR}/anland-lifecycle.patch
    SOURCE_SUBDIR app/src/main/jni
)
FetchContent_MakeAvailable(anland)

add_executable(anland_daemon
    ${anland_SOURCE_DIR}/termux/anland/anland.c
    ${anland_SOURCE_DIR}/termux/anland/common/socket_utils.c
)
target_include_directories(anland_daemon PRIVATE
    ${anland_SOURCE_DIR}/termux/anland
    ${anland_SOURCE_DIR}/termux/anland/common
)
target_compile_options(anland_daemon PRIVATE -O3 -Wall -Wextra)
set_target_properties(anland_daemon PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "anland_daemon"
    SUFFIX ".so"
)
target_link_libraries(anland_daemon log)
