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

#include "mud/mud.h"

/* bind.c が mud 作成後にセットするグローバル mud ポインタ */
extern struct mud *g_mud;

#define LOG_TAG "GlorytunNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int is_running = 0;
int android_tun_fd = -1;

#include <signal.h>
extern volatile sig_atomic_t gt_quit;

JavaVM *g_jvm = NULL;
jobject g_vpn_service = NULL;

extern int glorytun_main(int argc, char **argv);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;  // 未使用の警告対策
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

__attribute__((used)) int protect_socket_from_c(int fd) {
    if (!g_jvm) {
        LOGE("protect_socket_from_c(fd=%d): JavaVM is NULL", fd);
        return 0;
    }
    if (!g_vpn_service) {
        LOGE("protect_socket_from_c(fd=%d): vpn_service is NULL", fd);
        return 0;
    }
    
    JNIEnv *env;
    int isAttached = 0;
    
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (status < 0) {
        status = (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        if (status < 0) {
            LOGE("protect_socket_from_c(fd=%d): Failed to attach thread", fd);
            return 0;
        }
        isAttached = 1;
    }
    
    jclass cls = (*env)->GetObjectClass(env, g_vpn_service);
    jmethodID protect_method = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
    int ret = 0;
    if (protect_method) {
        jboolean b_ret = (*env)->CallBooleanMethod(env, g_vpn_service, protect_method, fd);
        ret = (int)b_ret;
        if (ret) {
            LOGI("protect_socket_from_c(fd=%d): SUCCESS", fd);
        } else {
            LOGE("protect_socket_from_c(fd=%d): FAILED (returned false) - routing loop risk!", fd);
        }
    } else {
        LOGE("protect_socket_from_c(fd=%d): Method 'protect' not found", fd);
    }
    
    if (isAttached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
    return ret;
}

#include <sys/socket.h>
#undef socket
int gt_socket(int domain, int type, int protocol) {
    LOGI("gt_socket called (domain=%d, type=%d, protocol=%d)", domain, type, protocol);
    int fd = socket(domain, type, protocol);
    if (fd >= 0) {
        if (domain == AF_INET || domain == AF_INET6) {
            protect_socket_from_c(fd);
        }
    }
    return fd;
}
#define socket gt_socket

// 擬似的なGlorytunの起動スレッド
void* glorytun_thread(void* arg) {
    // Androidのシグナル（SIGQUIT=ANRチェック等）がglorytunの
    // シグナルハンドラに届いてgt_quit=1にならないよう、
    // このスレッドで全シグナルをブロックする。
    // 停止はstopGlorytunNative()からgt_quit=1で行う。
    sigset_t blocked;
    sigfillset(&blocked);
    pthread_sigmask(SIG_BLOCK, &blocked, NULL);

    LOGI("Glorytun thread started with FD: %d", android_tun_fd);

    
    // argには char** （argvの配列）が渡されている想定
    char **argv = (char **)arg;
    int argc = 0;
    while (argv[argc] != NULL) {
        argc++;
    }

    LOGI("Calling glorytun_main");
    glorytun_main(argc, argv);
    LOGI("glorytun_main exited");
    
    // Cleanup
    for(int i=0; i<argc; i++) {
        free(argv[i]);
    }
    free(argv);

    if (g_vpn_service && g_jvm) {
        JNIEnv *env;
        int isAttached = 0;
        if ((*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6) < 0) {
            (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
            isAttached = 1;
        }
        (*env)->DeleteGlobalRef(env, g_vpn_service);
        g_vpn_service = NULL;
        if (isAttached) (*g_jvm)->DetachCurrentThread(g_jvm);
    }

    is_running = 0;
    LOGI("Glorytun thread exiting");
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_example_glorytun_GlorytunVpnService_startGlorytunNative(JNIEnv *env, jobject thiz, jint fd, jstring ip, jstring port, jstring secret) {
    if (is_running) return -1;
    
    android_tun_fd = fd;
    is_running = 1;
    
    if (g_vpn_service == NULL) {
        g_vpn_service = (*env)->NewGlobalRef(env, thiz);
    }

    const char *ip_c = (*env)->GetStringUTFChars(env, ip, 0);
    const char *port_c = (*env)->GetStringUTFChars(env, port, 0);
    const char *secret_c = (*env)->GetStringUTFChars(env, secret, 0);

    LOGI("Received config -> IP: %s, Port: %s", ip_c, port_c);

    // keyfileを書き出す
    const char *keyfile_path = "/data/data/com.example.glorytun/cache/keyfile.txt";
    
    // secret_c のトリムと長さチェック
    char clean_secret[65] = {0};
    int j = 0;
    for (int i = 0; secret_c[i] != '\0'; i++) {
        if (secret_c[i] != ' ' && secret_c[i] != '\n' && secret_c[i] != '\r' && secret_c[i] != '\t') {
            if (j < 64) {
                clean_secret[j++] = secret_c[i];
            } else {
                j++; // 64文字を超えていることを記録
            }
        }
    }
    
    if (j != 64) {
        LOGE("FATAL: Secret key must be exactly 64 hex characters! Provided length after trim: %d", j);
        (*env)->ReleaseStringUTFChars(env, ip, ip_c);
        (*env)->ReleaseStringUTFChars(env, port, port_c);
        (*env)->ReleaseStringUTFChars(env, secret, secret_c);
        is_running = 0;
        return -1;
    }

    FILE *f = fopen(keyfile_path, "w");
    if (f) {
        fputs(clean_secret, f);
        fclose(f);
        sodium_memzero(clean_secret, sizeof(clean_secret));
    } else {
        LOGE("Failed to write keyfile to %s", keyfile_path);
        (*env)->ReleaseStringUTFChars(env, ip, ip_c);
        (*env)->ReleaseStringUTFChars(env, port, port_c);
        (*env)->ReleaseStringUTFChars(env, secret, secret_c);
        is_running = 0;
        return -1;
    }

    // argv を作成する (glorytun bind to addr IP port PORT keyfile /path/to/key)
    char **argv = malloc(sizeof(char*) * 10);
    argv[0] = strdup("glorytun");
    argv[1] = strdup("bind");
    argv[2] = strdup("to");
    argv[3] = strdup("addr");
    argv[4] = strdup(ip_c);
    argv[5] = strdup("port");
    argv[6] = strdup(port_c);
    argv[7] = strdup("keyfile");
    argv[8] = strdup(keyfile_path);
    argv[9] = NULL;

    // strdupが完了したのでJNI参照を解放
    (*env)->ReleaseStringUTFChars(env, ip, ip_c);
    (*env)->ReleaseStringUTFChars(env, port, port_c);
    (*env)->ReleaseStringUTFChars(env, secret, secret_c);

    gt_quit = 0; // 次の接続時にスレッドが即座に終了しないようにリセット

    pthread_t thread;
    if (pthread_create(&thread, NULL, glorytun_thread, (void*)argv) != 0) {
        LOGE("Failed to create thread");
        is_running = 0;
        for(int i=0; i<9; i++) free(argv[i]);
        free(argv);
        return -1;
    }
    pthread_detach(thread);

    return 0;
}

JNIEXPORT void JNICALL
Java_com_example_glorytun_GlorytunVpnService_stopGlorytunNative(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    is_running = 0;
    gt_quit = 1;
}

JNIEXPORT jboolean JNICALL
Java_com_example_glorytun_GlorytunVpnService_isGlorytunReady(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jboolean)(g_mud != NULL);
}

/* ----------------------------------------------------------------
 * マルチパス用 JNI メソッド
 * ---------------------------------------------------------------- */

/* Kotlin 側の Network.bindSocket(fd) を JNI 経由で呼ぶ。
 * networkHandle は Network.getNetworkHandle() の値。 */
static int bind_socket_to_network(int fd, jlong network_handle) {
    if (!g_jvm || !g_vpn_service) return 0;

    JNIEnv *env;
    int isAttached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6) < 0) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
        isAttached = 1;
    }
    jclass cls = (*env)->GetObjectClass(env, g_vpn_service);
    jmethodID m = (*env)->GetMethodID(env, cls, "bindSocketToNetwork", "(IJ)Z");
    int ret = 0;
    if (m) {
        jboolean r = (*env)->CallBooleanMethod(env, g_vpn_service, m,
                                               (jint)fd, network_handle);
        ret = (int)r;
        LOGI("bindSocketToNetwork(fd=%d, handle=%lld): %s", fd,
             (long long)network_handle, ret ? "OK" : "FAIL");
    }
    if (isAttached) (*g_jvm)->DetachCurrentThread(g_jvm);
    return ret;
}

/* addPathForNetwork(localIp: String, networkHandle: Long): Int
 * 新しいネットワーク (WiFi or SIM) のパスを mud に追加する。
 * 1. socket() を作成
 * 2. VpnService.protect() でルーティングループ防止
 * 3. Network.bindSocket() で特定ネットワークに紐付け
 * 4. mud_set_path_socket() でパスに割り当て */
JNIEXPORT jint JNICALL
Java_com_example_glorytun_GlorytunVpnService_addPathForNetwork(
        JNIEnv *env, jobject thiz,
        jstring local_ip, jlong network_handle) {
    (void)thiz;

    if (!g_mud) {
        LOGE("addPathForNetwork: glorytun not running");
        return -1;
    }

    const char *lip = (*env)->GetStringUTFChars(env, local_ip, 0);
    LOGI("addPathForNetwork: local_ip=%s handle=%lld", lip, (long long)network_handle);

    /* ローカルアドレスを解析 */
    union mud_sockaddr local_addr;
    memset(&local_addr, 0, sizeof(local_addr));
    if (inet_pton(AF_INET, lip, &local_addr.sin.sin_addr) == 1) {
        local_addr.sin.sin_family = AF_INET;
        local_addr.sin.sin_port = 0;
    } else if (inet_pton(AF_INET6, lip, &local_addr.sin6.sin6_addr) == 1) {
        local_addr.sin6.sin6_family = AF_INET6;
        local_addr.sin6.sin6_port = 0;
    } else {
        LOGE("addPathForNetwork: invalid IP address: %s", lip);
        (*env)->ReleaseStringUTFChars(env, local_ip, lip);
        return -1;
    }
    (*env)->ReleaseStringUTFChars(env, local_ip, lip);

    /* 新しいソケットを作成 */
    int fd = socket(local_addr.sa.sa_family, SOCK_DGRAM, IPPROTO_UDP);
    if (fd < 0) {
        LOGE("addPathForNetwork: socket() failed: %s", strerror(errno));
        return -1;
    }

    /* VPN ルーティングループを防止 */
    protect_socket_from_c(fd);

    /* 特定ネットワークにバインド */
    if (!bind_socket_to_network(fd, network_handle)) {
        LOGE("addPathForNetwork: bindSocketToNetwork failed");
        close(fd);
        return -1;
    }

    /* mud にパス専用ソケットを登録 */
    if (mud_set_path_socket(g_mud, &local_addr, fd) != 0) {
        LOGE("addPathForNetwork: mud_set_path_socket failed (path may not exist yet)");
        close(fd);
        return -1;
    }

    LOGI("addPathForNetwork: SUCCESS fd=%d", fd);
    return 0;
}

/* removePathForNetwork(localIp: String): Int
 * ネットワーク消滅時にパスの専用ソケットを解除する */
JNIEXPORT jint JNICALL
Java_com_example_glorytun_GlorytunVpnService_removePathForNetwork(
        JNIEnv *env, jobject thiz, jstring local_ip) {
    (void)thiz;

    if (!g_mud) return -1;

    const char *lip = (*env)->GetStringUTFChars(env, local_ip, 0);

    union mud_sockaddr local_addr;
    memset(&local_addr, 0, sizeof(local_addr));
    if (inet_pton(AF_INET, lip, &local_addr.sin.sin_addr) == 1) {
        local_addr.sin.sin_family = AF_INET;
    } else if (inet_pton(AF_INET6, lip, &local_addr.sin6.sin6_addr) == 1) {
        local_addr.sin6.sin6_family = AF_INET6;
    }
    (*env)->ReleaseStringUTFChars(env, local_ip, lip);

    int ret = mud_clear_path_socket(g_mud, &local_addr);
    LOGI("removePathForNetwork: %s", ret == 0 ? "OK" : "path not found");
    return ret;
}
