#if defined __APPLE__
#define __APPLE_USE_RFC_3542
#endif

#if defined __linux__ && !defined _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "mud.h"

#include <errno.h>
#ifdef __ANDROID__
#include <sys/epoll.h>
#include <android/log.h>
#define MUD_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "GlorytunMud", __VA_ARGS__)
#define MUD_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GlorytunMud", __VA_ARGS__)
#endif
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <sys/ioctl.h>
#include <sys/time.h>

#include <arpa/inet.h>
#include <net/if.h>

#include <sodium.h>
#include "aegis256/aegis256.h"

#ifdef __ANDROID__
#include <android/log.h>
#define gt_log(...) __android_log_print(ANDROID_LOG_ERROR, "GlorytunNative", __VA_ARGS__)
#else
#define gt_log(...)
#endif

#if !defined MSG_CONFIRM
#define MSG_CONFIRM 0
#endif

#if defined __linux__
#define MUD_V4V6 1
#else
#define MUD_V4V6 0
#endif

#if defined __APPLE__
#include <mach/mach_time.h>
#endif

#if defined IP_PKTINFO
#define MUD_PKTINFO IP_PKTINFO
#define MUD_PKTINFO_SRC(X) &((struct in_pktinfo *)(X))->ipi_addr
#define MUD_PKTINFO_DST(X) &((struct in_pktinfo *)(X))->ipi_spec_dst
#define MUD_PKTINFO_SIZE sizeof(struct in_pktinfo)
#elif defined IP_RECVDSTADDR
#define MUD_PKTINFO IP_RECVDSTADDR
#define MUD_PKTINFO_SRC(X) (X)
#define MUD_PKTINFO_DST(X) (X)
#define MUD_PKTINFO_SIZE sizeof(struct in_addr)
#endif

#if defined IP_MTU_DISCOVER
#define MUD_DFRAG IP_MTU_DISCOVER
#define MUD_DFRAG_OPT IP_PMTUDISC_PROBE
#elif defined IP_DONTFRAG
#define MUD_DFRAG IP_DONTFRAG
#define MUD_DFRAG_OPT 1
#endif

#define MUD_ONE_MSEC (UINT64_C(1000))
#define MUD_ONE_SEC  (1000 * MUD_ONE_MSEC)
#define MUD_ONE_MIN  (60 * MUD_ONE_SEC)

#define MUD_TIME_SIZE    (6U)
#define MUD_TIME_BITS    (MUD_TIME_SIZE * 8U)
#define MUD_TIME_MASK(X) ((X) & ((UINT64_C(1) << MUD_TIME_BITS) - 2))

#define MUD_KEY_SIZE (32U)
#define MUD_MAC_SIZE (16U)

#define MUD_MSG(X)       ((X) & UINT64_C(1))
#define MUD_MSG_MARK(X)  ((X) | UINT64_C(1))
#define MUD_MSG_SENT_MAX (5)

#define MUD_PKT_MIN_SIZE (MUD_TIME_SIZE + MUD_MAC_SIZE)
#define MUD_PKT_MAX_SIZE (1500U)

#define MUD_MTU_MIN ( 576U + MUD_PKT_MIN_SIZE)
#define MUD_MTU_MAX ( 576U + MUD_PKT_MIN_SIZE)

#define MUD_CTRL_SIZE (CMSG_SPACE(MUD_PKTINFO_SIZE) + \
                       CMSG_SPACE(sizeof(struct in6_pktinfo)))

#define MUD_STORE_MSG(D,S) mud_store((D),(S),sizeof(D))
#define MUD_LOAD_MSG(S)    mud_load((S),sizeof(S))

struct mud_crypto_opt {
    unsigned char *dst;
    const unsigned char *src;
    size_t size;
};

struct mud_crypto_key {
    struct {
        unsigned char key[MUD_KEY_SIZE];
    } encrypt, decrypt;
    int aes;
};

struct mud_addr {
    union {
        unsigned char v6[16];
        struct {
            unsigned char zero[10];
            unsigned char ff[2];
            unsigned char v4[4];
        };
    };
    unsigned char port[2];
};

struct mud_msg {
    unsigned char sent_time[MUD_TIME_SIZE];
    unsigned char aes;
    unsigned char pkey[MUD_PUBKEY_SIZE];
    struct {
        unsigned char bytes[sizeof(uint64_t)];
        unsigned char total[sizeof(uint64_t)];
    } tx, rx, fw;
    unsigned char max_rate[sizeof(uint64_t)];
    unsigned char beat[MUD_TIME_SIZE];
    unsigned char mtu[2];
    unsigned char pref;
    unsigned char loss;
    unsigned char fixed_rate;
    unsigned char loss_limit;
    struct mud_addr addr;
};

struct mud_keyx {
    uint64_t time;
    unsigned char secret[crypto_scalarmult_SCALARBYTES];
    unsigned char remote[MUD_PUBKEY_SIZE];
    unsigned char local[MUD_PUBKEY_SIZE];
    struct mud_crypto_key private, last, next, current;
    int use_next;
    int aes;
};

struct mud {
    int fd;
#ifdef __ANDROID__
    int primary_fd;  /* 初期バインドUDPソケット。mud->fd は epoll fd になる */
#endif
    struct mud_conf conf;
    struct mud_path *paths;
    unsigned pref;
    unsigned capacity;
    struct mud_keyx keyx;
    uint64_t last_recv_time;
    size_t mtu;
    struct mud_errors err;
    uint64_t rate;
    uint64_t window;
    uint64_t window_time;
    uint64_t base_time;
#if defined __APPLE__
    mach_timebase_info_data_t mtid;
#endif
};

static inline int
mud_encrypt_opt(const struct mud_crypto_key *k,
                const struct mud_crypto_opt *c)
{
    if (k->aes) {
        unsigned char npub[AEGIS256_NPUBBYTES] = {0};
        memcpy(npub, c->dst, MUD_TIME_SIZE);
        return aegis256_encrypt(
            c->dst + MUD_TIME_SIZE,
            NULL,
            c->src,
            c->size,
            c->dst,
            MUD_TIME_SIZE,
            npub,
            k->encrypt.key
        );
    } else {
        unsigned char npub[crypto_aead_chacha20poly1305_NPUBBYTES] = {0};
        memcpy(npub, c->dst, MUD_TIME_SIZE);
        return crypto_aead_chacha20poly1305_encrypt(
            c->dst + MUD_TIME_SIZE,
            NULL,
            c->src,
            c->size,
            c->dst,
            MUD_TIME_SIZE,
            NULL,
            npub,
            k->encrypt.key
        );
    }
}

static inline int
mud_decrypt_opt(const struct mud_crypto_key *k,
                const struct mud_crypto_opt *c)
{
    if (k->aes) {
        unsigned char npub[AEGIS256_NPUBBYTES] = {0};
        memcpy(npub, c->src, MUD_TIME_SIZE);
        return aegis256_decrypt(
            c->dst,
            NULL,
            c->src + MUD_TIME_SIZE,
            c->size - MUD_TIME_SIZE,
            c->src, MUD_TIME_SIZE,
            npub,
            k->decrypt.key
        );
    } else {
        unsigned char npub[crypto_aead_chacha20poly1305_NPUBBYTES] = {0};
        memcpy(npub, c->src, MUD_TIME_SIZE);
        return crypto_aead_chacha20poly1305_decrypt(
            c->dst,
            NULL,
            NULL,
            c->src + MUD_TIME_SIZE,
            c->size - MUD_TIME_SIZE,
            c->src, MUD_TIME_SIZE,
            npub,
            k->decrypt.key
        );
    }
}

static inline void
mud_store(unsigned char *dst, uint64_t src, size_t size)
{
    dst[0] = (unsigned char)(src);
    dst[1] = (unsigned char)(src >> 8);
    if (size <= 2) return;
    dst[2] = (unsigned char)(src >> 16);
    dst[3] = (unsigned char)(src >> 24);
    dst[4] = (unsigned char)(src >> 32);
    dst[5] = (unsigned char)(src >> 40);
    if (size <= 6) return;
    dst[6] = (unsigned char)(src >> 48);
    dst[7] = (unsigned char)(src >> 56);
}

static inline uint64_t
mud_load(const unsigned char *src, size_t size)
{
    uint64_t ret = 0;
    ret = src[0];
    ret |= ((uint64_t)src[1]) << 8;
    if (size <= 2) return ret;
    ret |= ((uint64_t)src[2]) << 16;
    ret |= ((uint64_t)src[3]) << 24;
    ret |= ((uint64_t)src[4]) << 32;
    ret |= ((uint64_t)src[5]) << 40;
    if (size <= 6) return ret;
    ret |= ((uint64_t)src[6]) << 48;
    ret |= ((uint64_t)src[7]) << 56;
    return ret;
}

static inline uint64_t
mud_time(void)
{
#if defined CLOCK_REALTIME
    struct timespec tv;
    clock_gettime(CLOCK_REALTIME, &tv);
    return MUD_TIME_MASK(0
            + (uint64_t)tv.tv_sec * MUD_ONE_SEC
            + (uint64_t)tv.tv_nsec / MUD_ONE_MSEC);
#else
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return MUD_TIME_MASK(0
            + (uint64_t)tv.tv_sec * MUD_ONE_SEC
            + (uint64_t)tv.tv_usec);
#endif
}

static inline uint64_t
mud_now(struct mud *mud)
{
#if defined __APPLE__
    return MUD_TIME_MASK(mud->base_time
            + (mach_absolute_time() * mud->mtid.numer / mud->mtid.denom)
            / 1000ULL);
#elif defined CLOCK_MONOTONIC
    struct timespec tv;
    clock_gettime(CLOCK_MONOTONIC, &tv);
    return MUD_TIME_MASK(mud->base_time
            + (uint64_t)tv.tv_sec * MUD_ONE_SEC
            + (uint64_t)tv.tv_nsec / MUD_ONE_MSEC);
#else
    return mud_time();
#endif
}

static inline uint64_t
mud_abs_diff(uint64_t a, uint64_t b)
{
    return (a >= b) ? a - b : b - a;
}

static inline int
mud_timeout(uint64_t now, uint64_t last, uint64_t timeout)
{
    return (!last) || (MUD_TIME_MASK(now - last) >= timeout);
}

static inline void
mud_unmapv4(union mud_sockaddr *addr)
{
    if (addr->sa.sa_family != AF_INET6)
        return;

    if (!IN6_IS_ADDR_V4MAPPED(&addr->sin6.sin6_addr))
        return;

    struct sockaddr_in sin = {
        .sin_family = AF_INET,
        .sin_port = addr->sin6.sin6_port,
    };
    memcpy(&sin.sin_addr.s_addr,
           &addr->sin6.sin6_addr.s6_addr[12],
           sizeof(sin.sin_addr.s_addr));

    addr->sin = sin;
}

static struct mud_path *
mud_select_path(struct mud *mud, uint16_t cursor)
{
    uint64_t k = (cursor * mud->rate) >> 16;

    for (unsigned i = 0; i < mud->capacity; i++) {
        struct mud_path *path = &mud->paths[i];

        if (path->status != MUD_RUNNING)
            continue;

        if (k < path->tx.rate)
            return path;

        k -= path->tx.rate;
    }
    return NULL;
}

static int
mud_send_path(struct mud *mud, struct mud_path *path, uint64_t now,
              void *data, size_t size, int flags)
{
    if (!size || !path)
        return 0;

    unsigned char ctrl[MUD_CTRL_SIZE];
    memset(ctrl, 0, sizeof(ctrl));

    struct msghdr msg = {
        .msg_iov = &(struct iovec) {
            .iov_base = data,
            .iov_len = size,
        },
        .msg_iovlen = 1,
        .msg_control = ctrl,
    };
    if (path->conf.remote.sa.sa_family == AF_INET) {
        msg.msg_name = &path->conf.remote.sin;
        msg.msg_namelen = sizeof(struct sockaddr_in);
        msg.msg_controllen = CMSG_SPACE(MUD_PKTINFO_SIZE);

        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = IPPROTO_IP;
        cmsg->cmsg_type = MUD_PKTINFO;
        cmsg->cmsg_len = CMSG_LEN(MUD_PKTINFO_SIZE);
        memcpy(MUD_PKTINFO_DST(CMSG_DATA(cmsg)),
               &path->conf.local.sin.sin_addr,
               sizeof(struct in_addr));
    } else if (path->conf.remote.sa.sa_family == AF_INET6) {
        msg.msg_name = &path->conf.remote.sin6;
        msg.msg_namelen = sizeof(struct sockaddr_in6);
        msg.msg_controllen = CMSG_SPACE(sizeof(struct in6_pktinfo));

        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = IPPROTO_IPV6;
        cmsg->cmsg_type = IPV6_PKTINFO;
        cmsg->cmsg_len = CMSG_LEN(sizeof(struct in6_pktinfo));
        memcpy(&((struct in6_pktinfo *)CMSG_DATA(cmsg))->ipi6_addr,
               &path->conf.local.sin6.sin6_addr,
               sizeof(struct in6_addr));
    } else {
        errno = EAFNOSUPPORT;
        return -1;
    }
#ifdef __ANDROID__
    /* パス専用ソケットがある場合はそちらを使う。
     * 専用ソケットは Network.bindSocket() で特定インターフェースにバインド済みなので
     * IP_PKTINFO cmsg は不要 (msg_controllen を 0 にして cmsg を無効化)。 */
    int send_fd;
    if (path->fd >= 0) {
        send_fd = path->fd;
        msg.msg_controllen = 0;
        msg.msg_control    = NULL;
    } else {
        send_fd = mud->primary_fd;
    }
    ssize_t ret = sendmsg(send_fd, &msg, flags);
#else
    ssize_t ret = sendmsg(mud->fd, &msg, flags);
#endif

    path->tx.total++;
    path->tx.bytes += size;
    path->tx.time = now;

    if (mud->window > size) {
        mud->window -= size;
    } else {
        mud->window = 0;
    }
    return (int)ret;
}

static int
mud_sso_int(int fd, int level, int optname, int opt)
{
    return setsockopt(fd, level, optname, &opt, sizeof(opt));
}

static inline int
mud_cmp_addr(union mud_sockaddr *a, union mud_sockaddr *b)
{
    if (a->sa.sa_family != b->sa.sa_family)
        return 1;

    if (a->sa.sa_family == AF_INET)
        return memcmp(&a->sin.sin_addr, &b->sin.sin_addr,
                      sizeof(a->sin.sin_addr));

    if (a->sa.sa_family == AF_INET6)
        return memcmp(&a->sin6.sin6_addr, &b->sin6.sin6_addr,
                      sizeof(a->sin6.sin6_addr));
    return 1;
}

static inline int
mud_cmp_port(union mud_sockaddr *a, union mud_sockaddr *b)
{
    if (a->sa.sa_family != b->sa.sa_family)
        return 1;

    if (a->sa.sa_family == AF_INET)
        return memcmp(&a->sin.sin_port, &b->sin.sin_port,
                      sizeof(a->sin.sin_port));

    if (a->sa.sa_family == AF_INET6)
        return memcmp(&a->sin6.sin6_port, &b->sin6.sin6_port,
                      sizeof(a->sin6.sin6_port));
    return 1;
}

int
mud_get_paths(struct mud *mud, struct mud_paths *paths,
              union mud_sockaddr *local, union mud_sockaddr *remote)
{
    if (!paths) {
        errno = EINVAL;
        return -1;
    }
    unsigned count = 0;

    for (unsigned i = 0; i < mud->capacity; i++) {
        struct mud_path *path = &mud->paths[i];

        if (local && local->sa.sa_family &&
            mud_cmp_addr(local, &path->conf.local))
            continue;

        if (remote && remote->sa.sa_family &&
            (mud_cmp_addr(remote, &path->conf.remote) ||
             mud_cmp_port(remote, &path->conf.remote)))
            continue;

        if (path->conf.state != MUD_EMPTY)
            paths->path[count++] = *path;
    }
    paths->count = count;
    return 0;
}

static int
mud_is_inaddr_any(union mud_sockaddr *addr)
{
    if (addr->sa.sa_family == AF_INET)
        return addr->sin.sin_addr.s_addr == 0;
    if (addr->sa.sa_family == AF_INET6)
        return IN6_IS_ADDR_UNSPECIFIED(&addr->sin6.sin6_addr);
    return 0;
}

static struct mud_path *
mud_get_path(struct mud *mud,
             union mud_sockaddr *local,
             union mud_sockaddr *remote,
             enum mud_state state)
{
    if (local->sa.sa_family != 0 &&
        local->sa.sa_family != remote->sa.sa_family) {
        errno = EINVAL;
        return NULL;
    }
    for (unsigned i = 0; i < mud->capacity; i++) {
        struct mud_path *path = &mud->paths[i];

        if (path->conf.state == MUD_EMPTY)
            continue;

        /* conf.local が INADDR_ANY (0.0.0.0) の場合はワイルドカードとして扱う。
         * Android では 'from' 引数なしで起動すると local=0.0.0.0 になる。
         * サーバーからの応答は実際の IP で届くため、通常の addr 比較では
         * path[0] (MUD_UP) が見つからず、新しい PASSIVE path が量産されてしまう。 */
        int local_match;
        if (local->sa.sa_family == 0 ||
            mud_is_inaddr_any(&path->conf.local)) {
            local_match = 0;  /* ワイルドカード: ローカルは一致とみなす */
        } else {
            local_match = mud_cmp_addr(local, &path->conf.local);
        }

        if (local_match ||
            mud_cmp_addr(remote, &path->conf.remote) ||
            mud_cmp_port(remote, &path->conf.remote))
            continue;

        gt_log("mud_get_path: matched path[%u] state=%d (local_wildcard=%d)\n",
               i, (int)path->conf.state, mud_is_inaddr_any(&path->conf.local));
        return path;
    }
    if (state <= MUD_DOWN) {
        errno = 0;
        return NULL;
    }
    struct mud_path *path = NULL;

    for (unsigned i = 0; i < mud->capacity; i++) {
        if (mud->paths[i].conf.state == MUD_EMPTY) {
            path = &mud->paths[i];
            break;
        }
    }
    if (!path) {
        if (mud->capacity == MUD_PATH_MAX) {
            errno = ENOMEM;
            return NULL;
        }
        struct mud_path *paths = realloc(mud->paths,
                (mud->capacity + 1) * sizeof(struct mud_path));

        if (!paths)
            return NULL;

        path = &paths[mud->capacity];

        mud->capacity++;
        mud->paths = paths;
    }
    memset(path, 0, sizeof(struct mud_path));
#ifdef __ANDROID__
    path->fd = -1;  /* デフォルトは primary_fd を使用 */
#endif
    path->conf.local      = *local;
    path->conf.remote     = *remote;
    path->conf.state      = state;
    path->conf.beat       = 100 * MUD_ONE_MSEC;
    path->conf.fixed_rate = 1;
    path->conf.loss_limit = 255;
    path->status          = MUD_PROBING;
    path->idle            = mud_now(mud);

    return path;
}

int
mud_get_errors(struct mud *mud, struct mud_errors *err)
{
    if (!err) {
        errno = EINVAL;
        return -1;
    }
    memcpy(err, &mud->err, sizeof(struct mud_errors));
    return 0;
}

int
mud_set(struct mud *mud, struct mud_conf *conf)
{
    struct mud_conf c = mud->conf;

    if (conf->keepalive)     c.keepalive     = conf->keepalive;
    if (conf->timetolerance) c.timetolerance = conf->timetolerance;
    if (conf->kxtimeout)     c.kxtimeout     = conf->kxtimeout;

    *conf = mud->conf = c;
    return 0;
}

size_t
mud_get_mtu(struct mud *mud)
{
    if (!mud->mtu)
        return 0;

    return mud->mtu - MUD_PKT_MIN_SIZE;
}

static int
mud_setup_socket(int fd, int v4, int v6)
{
    if (mud_sso_int(fd, SOL_SOCKET, SO_REUSEADDR, 1))
        return -1;
#ifdef __ANDROID__
    /* Android では Network.bindSocket() で紐付けたソケットに対して
     * IP_PKTINFO / IPV6_RECVPKTINFO が EOPNOTSUPP を返すことがある。
     * mud_recv 側にフォールバック処理があるため失敗しても続行する。 */
    if (v4) {
        int r = mud_sso_int(fd, IPPROTO_IP, MUD_PKTINFO, 1);
        MUD_LOGI("mud_setup_socket: IP_PKTINFO fd=%d -> %s (errno=%d)", fd, r ? "FAIL(ignored)" : "OK", errno);
    }
    if (v6) {
        mud_sso_int(fd, IPPROTO_IPV6, IPV6_RECVPKTINFO, 1);
        mud_sso_int(fd, IPPROTO_IPV6, IPV6_V6ONLY, !v4);
    }
#else
    if ((v4 && mud_sso_int(fd, IPPROTO_IP, MUD_PKTINFO, 1)) ||
        (v6 && mud_sso_int(fd, IPPROTO_IPV6, IPV6_RECVPKTINFO, 1)) ||
        (v6 && mud_sso_int(fd, IPPROTO_IPV6, IPV6_V6ONLY, !v4)))
        return -1;
#endif

#if defined MUD_DFRAG
    if (v4)
        mud_sso_int(fd, IPPROTO_IP, MUD_DFRAG, MUD_DFRAG_OPT);
#endif
    return 0;
}

static void
mud_hash_key(unsigned char *dst, unsigned char *key, unsigned char *secret,
             unsigned char *pk0, unsigned char *pk1)
{
    crypto_generichash_state state;

    crypto_generichash_init(&state, key, MUD_KEY_SIZE, MUD_KEY_SIZE);
    crypto_generichash_update(&state, secret, crypto_scalarmult_BYTES);
    crypto_generichash_update(&state, pk0, MUD_PUBKEY_SIZE);
    crypto_generichash_update(&state, pk1, MUD_PUBKEY_SIZE);
    crypto_generichash_final(&state, dst, MUD_KEY_SIZE);

    sodium_memzero(&state, sizeof(state));
}

static int
mud_keyx(struct mud_keyx *kx, unsigned char *remote, int aes)
{
    unsigned char secret[crypto_scalarmult_BYTES];

    if (crypto_scalarmult(secret, kx->secret, remote))
        return 1;

    mud_hash_key(kx->next.encrypt.key,
                 kx->private.encrypt.key,
                 secret, remote, kx->local);

    mud_hash_key(kx->next.decrypt.key,
                 kx->private.encrypt.key,
                 secret, kx->local, remote);

    sodium_memzero(secret, sizeof(secret));

    memcpy(kx->remote, remote, MUD_PUBKEY_SIZE);
    kx->next.aes = kx->aes && aes;

    return 0;
}

static int
mud_keyx_init(struct mud *mud, uint64_t now)
{
    struct mud_keyx *kx = &mud->keyx;

    if (!mud_timeout(now, kx->time, mud->conf.kxtimeout))
        return 1;

    static const unsigned char test[crypto_scalarmult_BYTES] = {
        0x9b, 0xf4, 0x14, 0x90, 0x0f, 0xef, 0xf8, 0x2d, 0x11, 0x32, 0x6e,
        0x3d, 0x99, 0xce, 0x96, 0xb9, 0x4f, 0x79, 0x31, 0x01, 0xab, 0xaf,
        0xe3, 0x03, 0x59, 0x1a, 0xcd, 0xdd, 0xb0, 0xfb, 0xe3, 0x49
    };
    unsigned char tmp[crypto_scalarmult_BYTES];

    do {
        randombytes_buf(kx->secret, sizeof(kx->secret));
        crypto_scalarmult_base(kx->local, kx->secret);
    } while (crypto_scalarmult(tmp, test, kx->local));

    sodium_memzero(tmp, sizeof(tmp));
    kx->time = now;

    return 0;
}

struct mud *
mud_create(union mud_sockaddr *addr, unsigned char *key, int *aes)
{
    if (!addr || !key || !aes)
        return NULL;

    int v4, v6;
    socklen_t addrlen = 0;

    switch (addr->sa.sa_family) {
    case AF_INET:
        addrlen = sizeof(struct sockaddr_in);
        v4 = 1;
        v6 = 0;
        break;
    case AF_INET6:
        addrlen = sizeof(struct sockaddr_in6);
        v4 = MUD_V4V6;
        v6 = 1;
        break;
    default:
        return NULL;
    }
    if (sodium_init() == -1)
        return NULL;

    struct mud *mud = sodium_malloc(sizeof(struct mud));

    if (!mud)
        return NULL;

    memset(mud, 0, sizeof(struct mud));

#ifdef __ANDROID__
    mud->primary_fd = -1;
    mud->fd = -1;

    int primary = socket(addr->sa.sa_family, SOCK_DGRAM, IPPROTO_UDP);
    if ((primary == -1) ||
        (mud_setup_socket(primary, v4, v6)) ||
        (bind(primary, &addr->sa, addrlen)) ||
        (getsockname(primary, &addr->sa, &addrlen))) {
        if (primary >= 0) close(primary);
        mud_delete(mud);
        return NULL;
    }
    int epoll_fd = epoll_create1(0);
    if (epoll_fd == -1) {
        close(primary);
        mud_delete(mud);
        return NULL;
    }
    struct epoll_event _ev = {.events = EPOLLIN, .data.fd = primary};
    if (epoll_ctl(epoll_fd, EPOLL_CTL_ADD, primary, &_ev) == -1) {
        close(primary);
        close(epoll_fd);
        mud_delete(mud);
        return NULL;
    }
    mud->primary_fd = primary;
    mud->fd = epoll_fd;
#else
    mud->fd = socket(addr->sa.sa_family, SOCK_DGRAM, IPPROTO_UDP);

    if ((mud->fd == -1) ||
        (mud_setup_socket(mud->fd, v4, v6)) ||
        (bind(mud->fd, &addr->sa, addrlen)) ||
        (getsockname(mud->fd, &addr->sa, &addrlen))) {
        mud_delete(mud);
        return NULL;
    }
#endif
    mud->conf.keepalive     = 25 * MUD_ONE_SEC;
    mud->conf.timetolerance = 10 * MUD_ONE_MIN;
    mud->conf.kxtimeout     = 60 * MUD_ONE_MIN;

    /* Android: 初期windowを設定して接続直後から mud_send_wait() がブロックしないようにする */
    mud->window = (uint64_t)1500 * 100;

#if defined __APPLE__
    mach_timebase_info(&mud->mtid);
#endif

    uint64_t now = mud_now(mud);
    uint64_t base_time = mud_time();

    if (base_time > now)
        mud->base_time = base_time - now;

    memcpy(mud->keyx.private.encrypt.key, key, MUD_KEY_SIZE);
    memcpy(mud->keyx.private.decrypt.key, key, MUD_KEY_SIZE);
    sodium_memzero(key, MUD_KEY_SIZE);

    mud->keyx.current = mud->keyx.private;
    mud->keyx.next = mud->keyx.private;
    mud->keyx.last = mud->keyx.private;

    if (*aes && !aegis256_is_available())
        *aes = 0;

    mud->keyx.aes = *aes;
    return mud;
}

int
mud_get_fd(struct mud *mud)
{
    if (!mud)
        return -1;

    return mud->fd;
}

#ifdef __ANDROID__
int
mud_set_path_socket(struct mud *mud, union mud_sockaddr *local, int fd)
{
    if (!mud || !local || fd < 0)
        return -1;

    /* ソケットオプション設定 */
    int v4 = (local->sa.sa_family == AF_INET);
    int v6 = (local->sa.sa_family == AF_INET6);
    if (mud_setup_socket(fd, v4, v6)) {
        MUD_LOGE("mud_set_path_socket: mud_setup_socket failed fd=%d errno=%d", fd, errno);
        return -1;
    }

    /* ローカルアドレスにバインド */
    socklen_t addrlen = v4 ? sizeof(struct sockaddr_in)
                           : sizeof(struct sockaddr_in6);
    if (bind(fd, &local->sa, addrlen) == -1) {
        MUD_LOGE("mud_set_path_socket: bind failed fd=%d errno=%d", fd, errno);
        return -1;
    }
    MUD_LOGI("mud_set_path_socket: bind OK fd=%d", fd);

    /* local addr が一致するパスを検索して fd を紐付ける */
    unsigned found = 0;
    for (unsigned i = 0; i < mud->capacity; i++) {
        struct mud_path *path = &mud->paths[i];
        if (path->conf.state == MUD_EMPTY)
            continue;
        if (mud_cmp_addr(local, &path->conf.local) != 0)
            continue;

        found = 1;
        /* 既存の専用ソケットを解除 */
        if (path->fd >= 0) {
            epoll_ctl(mud->fd, EPOLL_CTL_DEL, path->fd, NULL);
            close(path->fd);
        }
        path->fd = fd;
        struct epoll_event ev = {.events = EPOLLIN, .data.fd = fd};
        if (epoll_ctl(mud->fd, EPOLL_CTL_ADD, fd, &ev) == -1) {
            MUD_LOGE("mud_set_path_socket: epoll_ctl failed fd=%d errno=%d", fd, errno);
            return -1;
        }
        MUD_LOGI("mud_set_path_socket: SUCCESS path[%u] fd=%d", i, fd);
        return 0;
    }
    MUD_LOGE("mud_set_path_socket: path not found (capacity=%u, found=%u)", mud->capacity, found);
    return -1;
}

int
mud_clear_path_socket(struct mud *mud, union mud_sockaddr *local)
{
    if (!mud || !local)
        return -1;

    for (unsigned i = 0; i < mud->capacity; i++) {
        struct mud_path *path = &mud->paths[i];
        if (path->conf.state == MUD_EMPTY)
            continue;
        if (mud_cmp_addr(local, &path->conf.local) != 0)
            continue;
        if (path->fd >= 0) {
            epoll_ctl(mud->fd, EPOLL_CTL_DEL, path->fd, NULL);
            close(path->fd);
            path->fd = -1;
        }
        return 0;
    }
    return -1;
}
#endif  /* __ANDROID__ */

void
mud_delete(struct mud *mud)
{
    if (!mud)
        return;

    if (mud->paths) {
#ifdef __ANDROID__
        for (unsigned i = 0; i < mud->capacity; i++) {
            if (mud->paths[i].fd >= 0) {
                epoll_ctl(mud->fd, EPOLL_CTL_DEL, mud->paths[i].fd, NULL);
                close(mud->paths[i].fd);
                mud->paths[i].fd = -1;
            }
        }
#endif
        free(mud->paths);
    }

#ifdef __ANDROID__
    if (mud->primary_fd >= 0) close(mud->primary_fd);
#endif
    if (mud->fd >= 0)
        close(mud->fd);

    sodium_free(mud);
}

static size_t
mud_encrypt(struct mud *mud, uint64_t now,
            unsigned char *dst, size_t dst_size,
            const unsigned char *src, size_t src_size)
{
    const size_t size = src_size + MUD_PKT_MIN_SIZE;

    if (size > dst_size)
        return 0;

    const struct mud_crypto_opt opt = {
        .dst = dst,
        .src = src,
        .size = src_size,
    };
    mud_store(dst, now, MUD_TIME_SIZE);

    if (mud->keyx.use_next) {
        mud_encrypt_opt(&mud->keyx.next, &opt);
    } else {
        mud_encrypt_opt(&mud->keyx.current, &opt);
    }
    return size;
}

static size_t
mud_decrypt(struct mud *mud,
            unsigned char *dst, size_t dst_size,
            const unsigned char *src, size_t src_size)
{
    const size_t size = src_size - MUD_PKT_MIN_SIZE;

    if (size > dst_size)
        return 0;

    const struct mud_crypto_opt opt = {
        .dst = dst,
        .src = src,
        .size = src_size,
    };
    if (mud_decrypt_opt(&mud->keyx.current, &opt)) {
        if (!mud_decrypt_opt(&mud->keyx.next, &opt)) {
            mud->keyx.last = mud->keyx.current;
            mud->keyx.current = mud->keyx.next;
            mud->keyx.use_next = 0;
        } else {
            if (mud_decrypt_opt(&mud->keyx.last, &opt) &&
                mud_decrypt_opt(&mud->keyx.private, &opt))
                return 0;
        }
    }
    return size;
}

static int
mud_localaddr(union mud_sockaddr *addr, struct msghdr *msg)
{
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(msg);

    for (; cmsg; cmsg = CMSG_NXTHDR(msg, cmsg)) {
        if ((cmsg->cmsg_level == IPPROTO_IP) &&
            (cmsg->cmsg_type == MUD_PKTINFO)) {
            addr->sa.sa_family = AF_INET;
            memcpy(&addr->sin.sin_addr,
                   MUD_PKTINFO_SRC(CMSG_DATA(cmsg)),
                   sizeof(struct in_addr));
            return 0;
        }
        if ((cmsg->cmsg_level == IPPROTO_IPV6) &&
            (cmsg->cmsg_type == IPV6_PKTINFO)) {
            addr->sa.sa_family = AF_INET6;
            memcpy(&addr->sin6.sin6_addr,
                   &((struct in6_pktinfo *)CMSG_DATA(cmsg))->ipi6_addr,
                   sizeof(struct in6_addr));
            mud_unmapv4(addr);
            return 0;
        }
    }
    return 1;
}

static int
mud_addr_is_v6(struct mud_addr *addr)
{
    static const unsigned char v4mapped[] = {
        [10] = 255,
        [11] = 255,
    };
    return memcmp(addr->v6, v4mapped, sizeof(v4mapped));
}

static int
mud_addr_from_sock(struct mud_addr *addr, union mud_sockaddr *sock)
{
    if (sock->sa.sa_family == AF_INET) {
        memset(addr->zero, 0, sizeof(addr->zero));
        memset(addr->ff, 0xFF, sizeof(addr->ff));
        memcpy(addr->v4, &sock->sin.sin_addr, 4);
        memcpy(addr->port, &sock->sin.sin_port, 2);
    } else if (sock->sa.sa_family == AF_INET6) {
        memcpy(addr->v6, &sock->sin6.sin6_addr, 16);
        memcpy(addr->port, &sock->sin6.sin6_port, 2);
    } else {
        errno = EAFNOSUPPORT;
        return -1;
    }
    return 0;
}

static void
mud_sock_from_addr(union mud_sockaddr *sock, struct mud_addr *addr)
{
    if (mud_addr_is_v6(addr)) {
        sock->sin6.sin6_family = AF_INET6;
        memcpy(&sock->sin6.sin6_addr, addr->v6, 16);
        memcpy(&sock->sin6.sin6_port, addr->port, 2);
    } else {
        sock->sin.sin_family = AF_INET;
        memcpy(&sock->sin.sin_addr, addr->v4, 4);
        memcpy(&sock->sin.sin_port, addr->port, 2);
    }
}

static int
mud_send_msg(struct mud *mud, struct mud_path *path, uint64_t now,
             uint64_t sent_time, uint64_t fw_bytes, uint64_t fw_total,
             size_t size)
{
    unsigned char dst[MUD_PKT_MAX_SIZE];
    unsigned char src[MUD_PKT_MAX_SIZE] = {0};
    struct mud_msg *msg = (struct mud_msg *)src;

    if (size < MUD_PKT_MIN_SIZE + sizeof(struct mud_msg))
        size = MUD_PKT_MIN_SIZE + sizeof(struct mud_msg);

    mud_store(dst, MUD_MSG_MARK(now), MUD_TIME_SIZE);
    MUD_STORE_MSG(msg->sent_time, sent_time);

    if (mud_addr_from_sock(&msg->addr, &path->conf.remote))
        return -1;

    memcpy(msg->pkey, mud->keyx.local, sizeof(mud->keyx.local));
    msg->aes = (unsigned char)mud->keyx.aes;

    if (!path->mtu.probe)
        MUD_STORE_MSG(msg->mtu, path->mtu.last);

    MUD_STORE_MSG(msg->tx.bytes, path->tx.bytes);
    MUD_STORE_MSG(msg->rx.bytes, path->rx.bytes);
    MUD_STORE_MSG(msg->tx.total, path->tx.total);
    MUD_STORE_MSG(msg->rx.total, path->rx.total);
    MUD_STORE_MSG(msg->fw.bytes, fw_bytes);
    MUD_STORE_MSG(msg->fw.total, fw_total);
    MUD_STORE_MSG(msg->max_rate, path->conf.rx_max_rate);
    MUD_STORE_MSG(msg->beat, path->conf.beat);

    msg->loss = (unsigned char)path->tx.loss;
    msg->pref = path->conf.pref;
    msg->fixed_rate = path->conf.fixed_rate;
    msg->loss_limit = path->conf.loss_limit;

    const struct mud_crypto_opt opt = {
        .dst = dst,
        .src = src,
        .size = size - MUD_PKT_MIN_SIZE,
    };
    mud_encrypt_opt(&mud->keyx.private, &opt);

    return mud_send_path(mud, path, now, dst, size,
                         sent_time ? MSG_CONFIRM : 0);
}

static size_t
mud_decrypt_msg(struct mud *mud,
                unsigned char *dst, size_t dst_size,
                const unsigned char *src, size_t src_size)
{
    const size_t size = src_size - MUD_PKT_MIN_SIZE;

    if (size < sizeof(struct mud_msg) || size > dst_size)
        return 0;

    const struct mud_crypto_opt opt = {
        .dst = dst,
        .src = src,
        .size = src_size,
    };
    if (mud_decrypt_opt(&mud->keyx.private, &opt))
        return 0;

    return size;
}

static void
mud_update_rl(struct mud *mud, struct mud_path *path, uint64_t now,
              uint64_t tx_dt, uint64_t tx_bytes, uint64_t tx_pkt,
              uint64_t rx_dt, uint64_t rx_bytes, uint64_t rx_pkt)
{
    if (rx_dt && rx_dt > tx_dt + (tx_dt >> 3)) {
        if (!path->conf.fixed_rate)
            path->tx.rate = (7 * rx_bytes * MUD_ONE_SEC) / (8 * rx_dt);
    } else {
        uint64_t tx_acc = path->msg.tx.acc + tx_pkt;
        uint64_t rx_acc = path->msg.rx.acc + rx_pkt;

        if (tx_acc > 1000) {
            if (tx_acc >= rx_acc)
                path->tx.loss = (tx_acc - rx_acc) * 255U / tx_acc;
            path->msg.tx.acc = tx_acc - (tx_acc >> 4);
            path->msg.rx.acc = rx_acc - (rx_acc >> 4);
        } else {
            path->msg.tx.acc = tx_acc;
            path->msg.rx.acc = rx_acc;
        }

        if (!path->conf.fixed_rate)
            path->tx.rate += path->tx.rate / 10;
    }
    if (path->tx.rate > path->conf.tx_max_rate)
        path->tx.rate = path->conf.tx_max_rate;
}

static void
mud_update_mtu(struct mud_path *path, size_t size)
{
    gt_log("mud_update_mtu: size=%zu, current probe=%zu, MAX=%d\n", size, path->mtu.probe, (int)MUD_MTU_MAX);
    if (!path->mtu.probe) {
        if (!path->mtu.last) {
            path->mtu.min = MUD_MTU_MIN;
            path->mtu.max = MUD_MTU_MAX;
            path->mtu.probe = MUD_MTU_MAX;
        }
        return;
    }
    if (size) {
        if (path->mtu.min > size || path->mtu.max < size)
            return;
        path->mtu.min = size + 1;
        path->mtu.last = size;
    } else {
        path->mtu.max = path->mtu.probe - 1;
    }

    size_t probe = (path->mtu.min + path->mtu.max) >> 1;

    if (path->mtu.min > path->mtu.max) {
        path->mtu.probe = 0;
    } else {
        path->mtu.probe = probe;
    }
}

static void
mud_update_stat(struct mud_stat *stat, const uint64_t val)
{
    if (stat->setup) {
        const uint64_t var = mud_abs_diff(stat->val, val);
        stat->var = ((stat->var << 1) + stat->var + var) >> 2;
        stat->val = ((stat->val << 3) - stat->val + val) >> 3;
    } else {
        stat->setup = 1;
        stat->var = val >> 1;
        stat->val = val;
    }
}

static void
mud_recv_msg(struct mud *mud, struct mud_path *path,
             uint64_t now, uint64_t sent_time,
             unsigned char *data, size_t size)
{
    struct mud_msg *msg = (struct mud_msg *)data;
    const uint64_t tx_time = MUD_LOAD_MSG(msg->sent_time);

    mud_sock_from_addr(&path->remote, &msg->addr);

    if (tx_time) {
        mud_update_stat(&path->rtt, MUD_TIME_MASK(now - tx_time));

        const uint64_t tx_bytes = MUD_LOAD_MSG(msg->fw.bytes);
        const uint64_t tx_total = MUD_LOAD_MSG(msg->fw.total);
        const uint64_t rx_bytes = MUD_LOAD_MSG(msg->rx.bytes);
        const uint64_t rx_total = MUD_LOAD_MSG(msg->rx.total);
        const uint64_t rx_time  = sent_time;

        if ((tx_time > path->msg.tx.time) && (tx_bytes > path->msg.tx.bytes) &&
            (rx_time > path->msg.rx.time) && (rx_bytes > path->msg.rx.bytes)) {
            if (path->msg.set && path->status > MUD_PROBING) {
                mud_update_rl(mud, path, now,
                        MUD_TIME_MASK(tx_time - path->msg.tx.time),
                        tx_bytes - path->msg.tx.bytes,
                        tx_total - path->msg.tx.total,
                        MUD_TIME_MASK(rx_time - path->msg.rx.time),
                        rx_bytes - path->msg.rx.bytes,
                        rx_total - path->msg.rx.total);
            }
            path->msg.tx.time = tx_time;
            path->msg.rx.time = rx_time;
            path->msg.tx.bytes = tx_bytes;
            path->msg.rx.bytes = rx_bytes;
            path->msg.tx.total = tx_total;
            path->msg.rx.total = rx_total;
            path->msg.set = 1;
        }
        path->rx.loss = (uint64_t)msg->loss;
        path->msg.sent = 0;

        if (path->conf.state == MUD_PASSIVE)
            return;

        mud_update_mtu(path, size);

        if (path->mtu.last) {
            const uint64_t reported_mtu = MUD_LOAD_MSG(msg->mtu);
            /* サーバーが PASSIVE の場合、response beat の msg->mtu = 0（サーバー側で未設定）。
             * reported_mtu == 0 ならサーバーは MTU ネゴシエーション中でないとみなし、
             * 自分の mtu.last をそのまま採用する。
             * これにより mtu.ok が設定され、path が MUD_RUNNING に遷移できる。 */
            if (reported_mtu == 0 || path->mtu.last == reported_mtu)
                path->mtu.ok = path->mtu.last;
        }
    } else {
        path->conf.beat = MUD_LOAD_MSG(msg->beat);

        const uint64_t max_rate = MUD_LOAD_MSG(msg->max_rate);

        /* max_rate == 0 はサーバーが rx_max_rate を未設定（デフォルト0）で起動している場合。
         * この場合 tx.rate を 0 にしてしまうと mud->window が増加せず、
         * Android→Server 方向の送信が永久にブロックされるため、0の場合は上書きしない。 */
        if (max_rate && (path->conf.tx_max_rate != max_rate || msg->fixed_rate))
            path->tx.rate = max_rate;

        if (max_rate)
            path->conf.tx_max_rate = max_rate;
        path->conf.pref = msg->pref;
        path->conf.fixed_rate = msg->fixed_rate;
        path->conf.loss_limit = msg->loss_limit;

        path->mtu.last = MUD_LOAD_MSG(msg->mtu);
        path->mtu.ok = path->mtu.last;

        path->msg.sent++;
        path->msg.time = now;
    }
    if (memcmp(msg->pkey, mud->keyx.remote, MUD_PUBKEY_SIZE)) {
        if (mud_keyx(&mud->keyx, msg->pkey, msg->aes)) {
            mud->err.keyx.addr = path->conf.remote;
            mud->err.keyx.time = now;
            mud->err.keyx.count++;
            return;
        }
    } else if (path->conf.state == MUD_UP) {
        mud->keyx.use_next = 1;
    }
    mud_send_msg(mud, path, now, sent_time,
                 MUD_LOAD_MSG(msg->tx.bytes),
                 MUD_LOAD_MSG(msg->tx.total),
                 size);
}

int
mud_recv(struct mud *mud, void *data, size_t size)
{
    union mud_sockaddr remote;
    unsigned char ctrl[MUD_CTRL_SIZE];
    unsigned char packet[MUD_PKT_MAX_SIZE];
    struct msghdr msg = {
        .msg_name = &remote,
        .msg_namelen = sizeof(remote),
        .msg_iov = &(struct iovec) {
            .iov_base = packet,
            .iov_len = sizeof(packet),
        },
        .msg_iovlen = 1,
        .msg_control = ctrl,
        .msg_controllen = sizeof(ctrl),
    };
#ifdef __ANDROID__
    /* epoll_wait でどの path fd にデータが来たか確認 */
    struct epoll_event _recv_ev;
    int _nev = epoll_wait(mud->fd, &_recv_ev, 1, 0);
    if (_nev <= 0) {
        /* イベントなし (select で epoll_fd が readable になったが処理済み) */
        return 0;
    }
    int recv_fd = _recv_ev.data.fd;
#else
    int recv_fd = mud->fd;
#endif
    const ssize_t packet_size = recvmsg(recv_fd, &msg, 0);

    if (packet_size == (ssize_t)-1) {
        gt_log("mud_recv drop: recvmsg failed. errno=%d (%s)\n", errno, strerror(errno));
        return -1;
    }

    if ((msg.msg_flags & MSG_TRUNC) ||
        (packet_size <= (ssize_t)MUD_PKT_MIN_SIZE)) {
        gt_log("mud_recv drop: MSG_TRUNC or too small. flags=%d, size=%d\n", msg.msg_flags, (int)packet_size);
        return 0;
    }
    /* MSG_CTRUNC: cmsgが切れているがデータは正常。Androidでは IP_PKTINFO が
     * 取得できないことがあるが、パケット自体は処理できるので破棄しない。 */
    if (msg.msg_flags & MSG_CTRUNC) {
        gt_log("mud_recv: MSG_CTRUNC (Android: cmsg truncated, data ok)\n");
    }

    const uint64_t now = mud_now(mud);
    const uint64_t sent_time = mud_load(packet, MUD_TIME_SIZE);

    mud_unmapv4(&remote);

    if ((MUD_TIME_MASK(now - sent_time) > mud->conf.timetolerance) &&
        (MUD_TIME_MASK(sent_time - now) > mud->conf.timetolerance)) {
        mud->err.clocksync.addr = remote;
        mud->err.clocksync.time = now;
        mud->err.clocksync.count++;
        gt_log("mud_recv drop: clocksync. now=%llu, sent_time=%llu\n", (unsigned long long)now, (unsigned long long)sent_time);
        return 0;
    }
    const size_t ret = MUD_MSG(sent_time)
                     ? mud_decrypt_msg(mud, data, size, packet, (size_t)packet_size)
                     : mud_decrypt(mud, data, size, packet, (size_t)packet_size);
    if (!ret) {
        mud->err.decrypt.addr = remote;
        mud->err.decrypt.time = now;
        mud->err.decrypt.count++;
        gt_log("mud_recv drop: decrypt failed.\n");
        return 0;
    }
    union mud_sockaddr local;
    memset(&local, 0, sizeof(local));

    if (mud_localaddr(&local, &msg)) {
        gt_log("mud_recv: localaddr failed (Android fallback: using zero local addr)\n");
        /* Android では IP_PKTINFO の cmsg が取得できない場合がある。
         * その場合もパケットを破棄せず、remote アドレスのみでパスを検索する。
         * mud_get_path は local が AF_UNSPEC (sa_family==0) の場合にも動作する。 */
    }

    struct mud_path *path = mud_get_path(mud, &local, &remote, MUD_PASSIVE);

    if (!path || path->conf.state <= MUD_DOWN) {
        /* localが0の場合はremoteだけでパスを再検索する（Androidフォールバック） */
        if (local.sa.sa_family == 0) {
            /* capacityを直接ループしてremoteが一致するパスを探す */
            for (unsigned _i = 0; _i < mud->capacity; _i++) {
                struct mud_path *_p = &mud->paths[_i];
                if (_p->conf.state <= MUD_DOWN) continue;
                if (!mud_cmp_addr(&remote, &_p->conf.remote) &&
                    !mud_cmp_port(&remote, &_p->conf.remote)) {
                    path = _p;
                    break;
                }
            }
        }
        if (!path || path->conf.state <= MUD_DOWN) {
            gt_log("mud_recv drop: path not found or down.\n");
            return 0;
        }
    }

    if (MUD_MSG(sent_time)) {
        mud_recv_msg(mud, path, now, sent_time, data, (size_t)packet_size);
    } else {
        path->idle = now;
    }
    path->rx.total++;
    path->rx.time = now;
    path->rx.bytes += (size_t)packet_size;

    mud->last_recv_time = now;

    return MUD_MSG(sent_time) ? 0 : (int)ret;
}

static int
mud_path_update(struct mud *mud, struct mud_path *path, uint64_t now)
{
    switch (path->conf.state) {
        case MUD_DOWN:
            path->status = MUD_DELETING;
        case MUD_PASSIVE:
            if (mud_timeout(now, path->rx.time, 5 * MUD_ONE_MIN)) {
                memset(path, 0, sizeof(struct mud_path));
                return 0;
            }
        case MUD_UP: break;
        default:     return 0;
    }
    if (path->conf.state == MUD_DOWN)
        return 0;

    if (path->msg.sent >= MUD_MSG_SENT_MAX) {
        if (path->mtu.probe) {
            mud_update_mtu(path, 0);
            path->msg.sent = 0;
        } else {
            path->msg.sent = MUD_MSG_SENT_MAX;
            path->status = MUD_DEGRADED;
            return 0;
        }
    }
    if (!path->mtu.ok) {
        /* Android: mtu.ok が未設定でも MUD_PROBING としたまま処理を続行する。
         * （return しないため、後続の MUD_RUNNING への遷移ロジックが実行される）
         * サーバー側は PASSIVE のため mtu.ok が設定されないことがある。 */
        path->status = MUD_PROBING;
        /* fall-through: MUD_RUNNING への遷移を許可 */
    }
    if (path->tx.loss > path->conf.loss_limit ||
        path->rx.loss > path->conf.loss_limit) {
        path->status = MUD_LOSSY;
        return 0;
    }
    if (path->conf.state == MUD_PASSIVE &&
        mud_timeout(mud->last_recv_time, path->rx.time,
                    MUD_MSG_SENT_MAX * path->conf.beat)) {
        path->status = MUD_WAITING;
        return 0;
    }
    if (path->conf.pref > mud->pref) {
        path->status = MUD_READY;
    } else if (path->status != MUD_RUNNING) {
        path->status = MUD_RUNNING;
        path->idle = now;
    }
    return 1;
}

static uint64_t
mud_path_track(struct mud *mud, struct mud_path *path, uint64_t now)
{
    if (path->conf.state != MUD_UP)
        return now;

    uint64_t timeout = path->conf.beat;

    switch (path->status) {
        case MUD_RUNNING:
            if (mud_timeout(now, path->idle, MUD_ONE_SEC))
                timeout = mud->conf.keepalive;
            break;
        case MUD_DEGRADED:
        case MUD_LOSSY:
        case MUD_PROBING:
            break;
        default:
            return now;
    }
    if (mud_timeout(now, path->msg.time, timeout)) {
        path->msg.sent++;
        path->msg.time = now;
        mud_send_msg(mud, path, now, 0, 0, 0, path->mtu.probe);
        now = mud_now(mud);
    }
    return now;
}

static void
mud_update_window(struct mud *mud, const uint64_t now)
{
    uint64_t elapsed = MUD_TIME_MASK(now - mud->window_time);

    if (elapsed > MUD_ONE_MSEC) {
        mud->window += mud->rate * elapsed / MUD_ONE_SEC;
        mud->window_time = now;
    }
    uint64_t window_max = mud->rate * 100 * MUD_ONE_MSEC / MUD_ONE_SEC;

    if (mud->window > window_max)
        mud->window = window_max;
}

int
mud_update(struct mud *mud)
{
    unsigned count = 0;
    unsigned pref = 255;
    unsigned next_pref = 255;
    uint64_t rate = 0;
    size_t   mtu = 0;
    uint64_t now = mud_now(mud);

    if (!mud_keyx_init(mud, now))
        now = mud_now(mud);

    for (unsigned i = 0; i < mud->capacity; i++) {
        struct mud_path *path = &mud->paths[i];

        if (mud_path_update(mud, path, now)) {
            if (next_pref > path->conf.pref && path->conf.pref > mud->pref)
                next_pref = path->conf.pref;
            if (pref > path->conf.pref)
                pref = path->conf.pref;
            if (path->status == MUD_RUNNING)
                rate += path->tx.rate;
        }
        if (path->mtu.ok) {
            if (!mtu || mtu > path->mtu.ok)
                mtu = path->mtu.ok;
        }
        now = mud_path_track(mud, path, now);
        count++;
    }
    if (rate) {
        mud->pref = pref;
    } else {
        mud->pref = next_pref;

        for (unsigned i = 0; i < mud->capacity; i++) {
            struct mud_path *path = &mud->paths[i];

            if (!mud_path_update(mud, path, now))
                continue;

            if (path->status == MUD_RUNNING)
                rate += path->tx.rate;
        }
    }
    mud->rate = rate;
    mud->mtu = mtu;

    mud_update_window(mud, now);

#ifdef __ANDROID__
    /* デバッグ: 1秒に1回程度、mud の状態をログ出力 */
    {
        static uint64_t last_log_time = 0;
        if (MUD_TIME_MASK(now - last_log_time) > MUD_ONE_SEC) {
            last_log_time = now;
            for (unsigned i = 0; i < mud->capacity; i++) {
                struct mud_path *p = &mud->paths[i];
                if (p->conf.state == MUD_EMPTY) continue;
                gt_log("mud_update[%u]: state=%d status=%d mtu.ok=%zu mtu.last=%zu mtu.probe=%zu tx.rate=%llu window=%llu\n",
                       i, (int)p->conf.state, (int)p->status,
                       p->mtu.ok, p->mtu.last, p->mtu.probe,
                       (unsigned long long)p->tx.rate,
                       (unsigned long long)mud->window);
            }
        }
    }
#endif

    if (!count)
        return -1;

    return mud->window < 1500;
}

int
mud_set_path(struct mud *mud, struct mud_path_conf *conf)
{
    if (conf->state < MUD_EMPTY || conf->state >= MUD_LAST) {
        errno = EINVAL;
        return -1;
    }
    struct mud_path *path = mud_get_path(mud, &conf->local,
                                              &conf->remote,
                                              conf->state);
    if (!path)
        return -1;

    struct mud_path_conf c = path->conf;

#ifdef __ANDROID__
    /* INADDR_ANY パスを具体的な IP で上書き（Android マルチパス用）。
     * glorytun 起動時は local=0.0.0.0 のパスが作られる。
     * addPathForNetwork で具体的な IP を渡した際、このパスをワイルドカードとして
     * 流用するのではなく conf.local を更新する。
     * これにより mud_set_path_socket が正確な local IP でパスを検索できる。 */
    if (mud_is_inaddr_any(&path->conf.local) && conf->local.sa.sa_family != 0) {
        MUD_LOGI("mud_set_path: claim INADDR_ANY path -> update local to specific IP");
        c.local = conf->local;
    }
#endif

    if (conf->state)       c.state       = conf->state;
    if (conf->pref)        c.pref        = conf->pref >> 1;
    if (conf->beat)        c.beat        = conf->beat * MUD_ONE_MSEC;
    if (conf->fixed_rate)  c.fixed_rate  = conf->fixed_rate >> 1;
    if (conf->loss_limit)  c.loss_limit  = conf->loss_limit;
    if (conf->tx_max_rate) c.tx_max_rate = path->tx.rate = conf->tx_max_rate;
    if (conf->rx_max_rate) c.rx_max_rate = path->rx.rate = conf->rx_max_rate;

    *conf = path->conf = c;
    return 0;
}

int
mud_send_wait(struct mud *mud)
{
    return mud->window < 1500;
}

int
mud_send(struct mud *mud, const void *data, size_t size)
{
    if (!size)
        return 0;

    if (mud->window < 1500) {
        errno = EAGAIN;
        return -1;
    }
    unsigned char packet[MUD_PKT_MAX_SIZE];
    const uint64_t now = mud_now(mud);
    const size_t packet_size = mud_encrypt(mud, now,
                                           packet, sizeof(packet),
                                           data, size);
    if (!packet_size) {
        errno = EMSGSIZE;
        return -1;
    }
    uint16_t k;
    memcpy(&k, &packet[packet_size - sizeof(k)], sizeof(k));

    struct mud_path *path = mud_select_path(mud, k);

    if (!path) {
        errno = EAGAIN;
        return -1;
    }
    path->idle = now;

    return mud_send_path(mud, path, now, packet, packet_size, 0);
}
