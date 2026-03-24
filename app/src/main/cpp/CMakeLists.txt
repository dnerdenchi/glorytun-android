cmake_minimum_required(VERSION 3.22.1)
project("glorytun_android")

# libsodiumの設定 (ユーザー配置パス)
include_directories(include)
add_library(sodium STATIC IMPORTED)
set_target_properties(sodium PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libsodium.a)

# glorytunのソースコード一覧
file(GLOB GLORYTUN_SOURCES "glorytun/src/*.c")

# サブモジュールのソースコード一覧を追加
list(APPEND GLORYTUN_SOURCES
        "glorytun/argz/argz.c"
        "glorytun/mud/mud.c"
        "glorytun/mud/aegis256/aegis256.c"
)

# ARM NEON の AES拡張（Crypto）を有効にするコンパイルオプションを付与
set_source_files_properties("glorytun/mud/aegis256/aegis256.c" PROPERTIES COMPILE_FLAGS "-march=armv8-a+crypto")

add_library(
        glorytun_jni
        SHARED
        glorytun_jni.c
        ${GLORYTUN_SOURCES}
)

# ヘッダ検索パスにglorytunルートを追加しておく
target_include_directories(glorytun_jni PRIVATE glorytun)

find_library(
        log-lib
        log
)

target_link_libraries(
        glorytun_jni
        sodium
        ${log-lib}
)
