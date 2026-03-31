#include <jni.h>
#include <string.h>
#include <android/log.h>
#include "sodium.h"
#include <pthread.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <signal.h>
#include <ctype.h>

#include "mud/mud.h"

/* bind.c が mud 作成後にセットするグローバル mud ポインタ */
extern struct mud *g_mud;

#define LOG_TAG "GlorytunNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define DEFAULT_MAX_RATE 10000000ULL
#define DEFAULT_BEAT_MS 1000

static int is_running = 0;
int android_tun_fd = -1;

/* パス作成に使うサーバー共通アドレス */
static union mud_sockaddr g_server_addr;
static int g_server_addr_ready = 0;

extern volatile sig_atomic_t gt_quit;
JavaVM *g_jvm = NULL;
jobject g_vpn_service = NULL;

extern int glorytun_main(int argc, char **argv);

/* ----------------------------------------------------------------
 * JNI ユーティリティ
 * ---------------------------------------------------------------- */

static JNIEnv* get_jni_env(int* is_attached) {
    JNIEnv *env;
    *is_attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6) < 0) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) == 0) {
            *is_attached = 1;
        } else {
            LOGE("Failed to attach current thread");
            return NULL;
        }
    }
    return env;
}

static void release_jni_env(int is_attached) {
    if (is_attached) (*g_jvm)->DetachCurrentThread(g_jvm);
}

/* ----------------------------------------------------------------
 * 補助関数
 * ---------------------------------------------------------------- */

int protect_socket_from_c(int fd) {
    if (!g_jvm || !g_vpn_service) return 0;
    
    int is_attached;
    JNIEnv *env = get_jni_env(&is_attached);
    if (!env) return 0;
    
    jclass cls = (*env)->GetObjectClass(env, g_vpn_service);
    jmethodID method = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
    int ret = 0;
    if (method) {
        ret = (int)(*env)->CallBooleanMethod(env, g_vpn_service, method, fd);
    }
    
    release_jni_env(is_attached);
    return ret;
}

/** Socket() ラッパー: 作成したソケットを自動的に VpnService.protect() する */
#undef socket
int gt_socket(int domain, int type, int protocol) {
    int fd = socket(domain, type, protocol);
    if (fd >= 0 && (domain == AF_INET || domain == AF_INET6)) {
        protect_socket_from_c(fd);
    }
    return fd;
}
#define socket gt_socket

/** IP 文字列とポートを mud_sockaddr に変換する */
static int parse_address(const char *ip, const char *port, union mud_sockaddr *addr) {
    memset(addr, 0, sizeof(*addr));
    int port_num = port ? atoi(port) : 0;
    
    if (inet_pton(AF_INET, ip, &addr->sin.sin_addr) == 1) {
        addr->sin.sin_family = AF_INET;
        addr->sin.sin_port = htons((uint16_t)port_num);
        return 0;
    } else if (inet_pton(AF_INET6, ip, &addr->sin6.sin6_addr) == 1) {
        addr->sin6.sin6_family = AF_INET6;
        addr->sin6.sin6_port = htons((uint16_t)port_num);
        return 0;
    }
    return -1;
}

/** 秘密鍵をトリム・バリデーションしてファイルに書き出す */
static int write_key_to_file(const char *path, const char *secret) {
    char clean_secret[65] = {0};
    int j = 0;
    for (int i = 0; secret[i] != '\0'; i++) {
        if (!isspace((unsigned char)secret[i])) {
            if (j < 64) clean_secret[j++] = secret[i];
            else j++;
        }
    }
    
    if (j != 64) {
        LOGE("Secret key must be exactly 64 hex characters (got %d)", j);
        return -1;
    }
    
    FILE *f = fopen(path, "w");
    if (!f) {
        LOGE("Failed to open keyfile for writing: %s", path);
        return -1;
    }
    
    fputs(clean_secret, f);
    fclose(f);
    sodium_memzero(clean_secret, sizeof(clean_secret));
    return 0;
}

/** glorytun 起動用の引数配列を構築する */
static char **build_glorytun_args(const char *ip, const char *port, const char *keyfile) {
    char **argv = malloc(sizeof(char*) * 10);
    if (!argv) return NULL;
    argv[0] = strdup("glorytun");
    argv[1] = strdup("bind");
    argv[2] = strdup("to");
    argv[3] = strdup("addr");
    argv[4] = strdup(ip);
    argv[5] = strdup("port");
    argv[6] = strdup(port);
    argv[7] = strdup("keyfile");
    argv[8] = strdup(keyfile);
    argv[9] = NULL;
    return argv;
}

static void free_glorytun_args(char **argv) {
    if (!argv) return;
    for (int i = 0; argv[i] != NULL; i++) free(argv[i]);
    free(argv);
}

/** Kotlin の Network.bindSocket(fd) を呼び出す */
static int bind_socket_to_network(int fd, jlong network_handle) {
    int is_attached;
    JNIEnv *env = get_jni_env(&is_attached);
    if (!env) return 0;

    jclass cls = (*env)->GetObjectClass(env, g_vpn_service);
    jmethodID method = (*env)->GetMethodID(env, cls, "bindSocketToNetwork", "(IJ)Z");
    int ret = 0;
    if (method) {
        ret = (int)(*env)->CallBooleanMethod(env, g_vpn_service, method, (jint)fd, network_handle);
        LOGI("bindSocketToNetwork(fd=%d, handle=%lld): %s", fd, (long long)network_handle, ret ? "OK" : "FAIL");
    }
    
    release_jni_env(is_attached);
    return ret;
}

/* ----------------------------------------------------------------
 * メインスレッド・ライフサイクル
 * ---------------------------------------------------------------- */

void* glorytun_thread(void* arg) {
    sigset_t blocked;
    sigfillset(&blocked);
    pthread_sigmask(SIG_BLOCK, &blocked, NULL);

    char **argv = (char **)arg;
    int argc = 0;
    while (argv[argc]) argc++;

    LOGI("Glorytun main thread started");
    glorytun_main(argc, argv);
    LOGI("Glorytun main thread finished");
    
    free_glorytun_args(argv);

    int is_attached;
    JNIEnv *env = get_jni_env(&is_attached);
    if (env && g_vpn_service) {
        (*env)->DeleteGlobalRef(env, g_vpn_service);
        g_vpn_service = NULL;
    }
    release_jni_env(is_attached);

    is_running = 0;
    return NULL;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

/* ----------------------------------------------------------------
 * JNI Export 関数
 * ---------------------------------------------------------------- */

JNIEXPORT jint JNICALL
Java_com_example_glorytun_GlorytunVpnService_startGlorytunNative(
    JNIEnv *env, jobject thiz, jint fd, jstring ip, jstring port, jstring secret, jstring keyfile_path) {
    
    if (is_running) return -1;
    
    const char *ip_c     = (*env)->GetStringUTFChars(env, ip, NULL);
    const char *port_c   = (*env)->GetStringUTFChars(env, port, NULL);
    const char *secret_c = (*env)->GetStringUTFChars(env, secret, NULL);
    const char *path_c   = (*env)->GetStringUTFChars(env, keyfile_path, NULL);

    jint result = -1;

    if (parse_address(ip_c, port_c, &g_server_addr) == 0) {
        g_server_addr_ready = 1;
    } else {
        LOGE("Invalid server address: %s", ip_c);
        goto cleanup;
    }

    if (write_key_to_file(path_c, secret_c) != 0) goto cleanup;

    char **argv = build_glorytun_args(ip_c, port_c, path_c);
    if (!argv) goto cleanup;

    android_tun_fd = fd;
    is_running = 1;
    gt_quit = 0;
    g_vpn_service = (*env)->NewGlobalRef(env, thiz);

    pthread_t thread;
    if (pthread_create(&thread, NULL, glorytun_thread, (void*)argv) != 0) {
        LOGE("Failed to create glorytun thread");
        is_running = 0;
        (*env)->DeleteGlobalRef(env, g_vpn_service);
        g_vpn_service = NULL;
        free_glorytun_args(argv);
        goto cleanup;
    }
    pthread_detach(thread);
    result = 0;

cleanup:
    (*env)->ReleaseStringUTFChars(env, ip, ip_c);
    (*env)->ReleaseStringUTFChars(env, port, port_c);
    (*env)->ReleaseStringUTFChars(env, secret, secret_c);
    (*env)->ReleaseStringUTFChars(env, keyfile_path, path_c);
    return result;
}

JNIEXPORT void JNICALL
Java_com_example_glorytun_GlorytunVpnService_stopGlorytunNative(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    is_running = 0;
    gt_quit = 1;
    g_server_addr_ready = 0;
}

JNIEXPORT jboolean JNICALL
Java_com_example_glorytun_GlorytunVpnService_isGlorytunReady(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jboolean)(g_mud != NULL);
}

JNIEXPORT jint JNICALL
Java_com_example_glorytun_GlorytunVpnService_addPathForNetwork(
    JNIEnv *env, jobject thiz, jstring local_ip, jlong network_handle) {
    
    if (!g_mud) return -1;

    const char *lip = (*env)->GetStringUTFChars(env, local_ip, NULL);
    union mud_sockaddr local_addr;
    int ret = -1;

    if (parse_address(lip, "0", &local_addr) != 0) {
        LOGE("Invalid local IP: %s", lip);
        goto cleanup;
    }

    int fd = socket(local_addr.sa.sa_family, SOCK_DGRAM, IPPROTO_UDP);
    if (fd < 0) {
        LOGE("Socket creation failed: %s", strerror(errno));
        goto cleanup;
    }

    if (!bind_socket_to_network(fd, network_handle)) {
        close(fd);
        goto cleanup;
    }

    if (g_server_addr_ready) {
        struct mud_path_conf conf;
        memset(&conf, 0, sizeof(conf));
        conf.state       = MUD_UP;
        conf.local       = local_addr;
        conf.remote      = g_server_addr;
        conf.tx_max_rate = DEFAULT_MAX_RATE;
        conf.rx_max_rate = DEFAULT_MAX_RATE;
        conf.beat        = DEFAULT_BEAT_MS;
        mud_set_path(g_mud, &conf);
    }

    if (mud_set_path_socket(g_mud, &local_addr, fd) != 0) {
        LOGE("mud_set_path_socket failed");
        close(fd);
        goto cleanup;
    }

    ret = 0;

cleanup:
    (*env)->ReleaseStringUTFChars(env, local_ip, lip);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_example_glorytun_GlorytunVpnService_removePathForNetwork(
    JNIEnv *env, jobject thiz, jstring local_ip) {
    
    if (!g_mud) return -1;
    const char *lip = (*env)->GetStringUTFChars(env, local_ip, NULL);
    union mud_sockaddr addr;
    parse_address(lip, "0", &addr);
    (*env)->ReleaseStringUTFChars(env, local_ip, lip);

    return mud_clear_path_socket(g_mud, &addr);
}

JNIEXPORT jint JNICALL
Java_com_example_glorytun_GlorytunVpnService_setPathMaxRate(
    JNIEnv *env, jobject thiz, jstring local_ip, jlong tx_rate, jlong rx_rate) {
    
    if (!g_mud || !g_server_addr_ready) return -1;

    const char *lip = (*env)->GetStringUTFChars(env, local_ip, NULL);
    union mud_sockaddr local_addr;
    int ret = -1;

    if (parse_address(lip, "0", &local_addr) == 0) {
        struct mud_path_conf conf;
        memset(&conf, 0, sizeof(conf));
        conf.state       = MUD_UP;
        conf.local       = local_addr;
        conf.remote      = g_server_addr;
        conf.tx_max_rate = (tx_rate > 0) ? (uint64_t)tx_rate : DEFAULT_MAX_RATE;
        conf.rx_max_rate = (rx_rate > 0) ? (uint64_t)rx_rate : DEFAULT_MAX_RATE;
        conf.beat        = DEFAULT_BEAT_MS;
        ret = mud_set_path(g_mud, &conf);
    }

    (*env)->ReleaseStringUTFChars(env, local_ip, lip);
    return ret;
}

/* setPathThrottleRate: 帯域幅スロットル専用。fixed_rate を制御することで
 * 輻輳制御による tx.rate の上書きを防ぎ、設定速度を正確に維持する。
 *
 * 問題の背景:
 *   setPathMaxRate のみでは mud_update_rl() の輻輳制御が tx.rate を動的に
 *   再計算してしまう。ダウンロード中はACKのみ送信するため tx.rate が急落し、
 *   window_max (= tx.rate / 10) も縮小してスループットが設定値の約1/10になる。
 *   fixed_rate=1 にするとこの動的更新をスキップし、設定レートを維持できる。
 *
 * fixed_rate エンコーディング (mud_set_path の仕様):
 *   conf.fixed_rate = 0 → 変更なし
 *   conf.fixed_rate = 1 → 0 に設定（無効化）
 *   conf.fixed_rate = 2 → 1 に設定（有効化）
 *
 * enable=JNI_TRUE  → fixed_rate 有効化（スロットルON）
 * enable=JNI_FALSE → fixed_rate 無効化（スロットルOFF・デフォルト復帰）
 */
JNIEXPORT jint JNICALL
Java_com_example_glorytun_GlorytunVpnService_setPathThrottleRate(
    JNIEnv *env, jobject thiz, jstring local_ip, jlong tx_rate, jlong rx_rate, jboolean enable) {

    if (!g_mud || !g_server_addr_ready) return -1;

    const char *lip = (*env)->GetStringUTFChars(env, local_ip, NULL);
    union mud_sockaddr local_addr;
    int ret = -1;

    if (parse_address(lip, "0", &local_addr) == 0) {
        struct mud_path_conf conf;
        memset(&conf, 0, sizeof(conf));
        conf.state       = MUD_UP;
        conf.local       = local_addr;
        conf.remote      = g_server_addr;
        conf.tx_max_rate = (tx_rate > 0) ? (uint64_t)tx_rate : DEFAULT_MAX_RATE;
        conf.rx_max_rate = (rx_rate > 0) ? (uint64_t)rx_rate : DEFAULT_MAX_RATE;
        conf.beat        = DEFAULT_BEAT_MS;
        /* enable=true → 2 (有効化), enable=false → 1 (無効化) */
        conf.fixed_rate  = enable ? 2 : 1;
        ret = mud_set_path(g_mud, &conf);
        LOGI("setPathThrottleRate(%s, tx=%lld, rx=%lld, enable=%d): %d",
             lip, (long long)conf.tx_max_rate, (long long)conf.rx_max_rate, (int)enable, ret);
    }

    (*env)->ReleaseStringUTFChars(env, local_ip, lip);
    return ret;
}

JNIEXPORT jlongArray JNICALL
Java_com_example_glorytun_GlorytunVpnService_getPathStatsForIp(
    JNIEnv *env, jobject thiz, jstring local_ip_str) {
    
    if (!g_mud) return NULL;

    const char *lip = (*env)->GetStringUTFChars(env, local_ip_str, NULL);
    union mud_sockaddr local_addr;
    jlongArray result = NULL;

    if (parse_address(lip, "0", &local_addr) == 0) {
        struct mud_paths paths;
        if (mud_get_paths(g_mud, &paths, &local_addr, NULL) == 0 && paths.count > 0) {
            struct mud_path *p = &paths.path[0];
            jlong data[4] = { (jlong)p->tx.bytes, (jlong)p->rx.bytes, (jlong)p->tx.rate, (jlong)p->rx.rate };
            result = (*env)->NewLongArray(env, 4);
            if (result) (*env)->SetLongArrayRegion(env, result, 0, 4, data);
        }
    }

    (*env)->ReleaseStringUTFChars(env, local_ip_str, lip);
    return result;
}
