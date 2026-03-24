#include <jni.h>
#include <string.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>

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
        LOGI("protect_socket_from_c(fd=%d) called, result: %d", fd, ret);
    } else {
        LOGE("protect_socket_from_c(fd=%d): Method 'protect' not found", fd);
    }
    
    if (isAttached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
    return ret;
}

#include <sys/socket.h>
int gt_socket(int domain, int type, int protocol) {
    int fd = socket(domain, type, protocol);
    if (fd >= 0) {
        if (domain == AF_INET || domain == AF_INET6) {
            protect_socket_from_c(fd);
        }
    }
    return fd;
}

// 擬似的なGlorytunの起動スレッド
void* glorytun_thread(void* arg) {
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

#include <signal.h>
extern volatile sig_atomic_t gt_quit;

JNIEXPORT void JNICALL
Java_com_example_glorytun_GlorytunVpnService_stopGlorytunNative(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    is_running = 0;
    gt_quit = 1;
}
