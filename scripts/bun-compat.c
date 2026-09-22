// Android app seccomp policies can TRAP close_range rather than return ENOSYS.
// Interpose libc syscall before Bun initialization; keep fd cleanup semantics.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

__attribute__((visibility("hidden"))) long pocket_syscall_result(long result) {
    if ((unsigned long)result >= (unsigned long)-4095) { errno = (int)-result; return -1; }
    return result;
}

__attribute__((visibility("hidden"))) long pocket_close_range(unsigned int first, unsigned int last, unsigned int flags) {
    const unsigned int unshare = 2, cloexec = 4;
    if (first > last || (flags & ~(unshare | cloexec))) { errno = EINVAL; return -1; }
    // Do not pretend to implement fd-table unsharing. Callers may use a fallback.
    if (flags & unshare) { errno = ENOSYS; return -1; }
    int directory = open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (directory < 0) return -1;
    struct entry { uint64_t ino; int64_t off; unsigned short size; unsigned char type; char name[]; };
    union { uint64_t alignment; char bytes[4096]; } buffer;
    int failure = 0;
    for (;;) {
        long count = syscall(SYS_getdents64, directory, buffer.bytes, sizeof(buffer.bytes));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) { failure = errno; break; }
        if (!count) break;
        for (long position = 0; position < count;) {
            struct entry *item = (struct entry *)(buffer.bytes + position);
            const size_t header = offsetof(struct entry, name);
            if ((size_t)(count - position) <= header || item->size <= header || item->size > count - position) { failure = EIO; break; }
            unsigned int fd = 0;
            size_t i = 0, length = item->size - header;
            for (; i < length && item->name[i] >= '0' && item->name[i] <= '9'; ++i) {
                unsigned int digit = (unsigned int)(item->name[i] - '0');
                if (fd > ((unsigned int)INT_MAX - digit) / 10) break;
                fd = fd * 10 + digit;
            }
            if (i && i < length && !item->name[i] && fd >= first && fd <= last && fd != (unsigned int)directory) {
                if (flags & cloexec) {
                    int old, result;
                    do { old = fcntl((int)fd, F_GETFD); } while (old < 0 && errno == EINTR);
                    if (old >= 0) {
                        do { result = fcntl((int)fd, F_SETFD, old | FD_CLOEXEC); } while (result < 0 && errno == EINTR);
                        if (result < 0 && errno != EBADF) { failure = errno; break; }
                    } else if (errno != EBADF) { failure = errno; break; }
                } else {
                    // Linux close releases the fd even on EINTR; never retry it.
                    (void)close((int)fd);
                }
            }
            position += item->size;
        }
        if (failure) break;
    }
    close(directory);
    if (failure) { errno = failure; return -1; }
    return 0;
}
