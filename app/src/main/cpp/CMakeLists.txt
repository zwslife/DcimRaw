cmake_minimum_required(VERSION 3.22.1)
project(dcimraw CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# LibRaw 全量源码（src/ 下 78 个 .cpp；integration 两个胶水文件内部有
# USE_DNGSDK / USE_RAWSPEED 宏保护，未启用依赖时编译为空，安全包含）
file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/libraw/src/*.cpp")

add_library(libraw STATIC ${LIBRAW_SOURCES})
target_include_directories(libraw PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/libraw")
target_compile_options(libraw PRIVATE -w -O3)
target_compile_definitions(libraw PRIVATE LIBRAW_NODLL)

# JNI 桥
add_library(dcimraw SHARED raw_bridge.cpp)
target_compile_options(dcimraw PRIVATE -O3)
target_link_libraries(dcimraw PRIVATE libraw log jnigraphics)
