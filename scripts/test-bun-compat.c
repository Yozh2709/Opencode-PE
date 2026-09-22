#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

int main(int argc, char **argv) {
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_close_range, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program = { .len = sizeof(filter)/sizeof(filter[0]), .filter = filter };
    assert(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
    assert(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0);
    if (argc > 1) { execvp(argv[1], argv + 1); perror("exec"); return 1; }
    int fd = open("/dev/null", O_RDONLY);
    assert(fd >= 0);
    int low = fcntl(fd, F_DUPFD, 100), middle = fcntl(fd, F_DUPFD, 101), high = fcntl(fd, F_DUPFD, 102);
    assert(low == 100 && middle == 101 && high == 102);
    assert(syscall(SYS_close_range, 101U, 101U, 4U) == 0);
    assert(fcntl(low, F_GETFD) == 0 && fcntl(middle, F_GETFD) == FD_CLOEXEC && fcntl(high, F_GETFD) == 0);
    assert(syscall(SYS_close_range, 101U, 101U, 0U) == 0);
    assert(fcntl(middle, F_GETFD) == -1 && errno == EBADF);
    assert(fcntl(low, F_GETFD) == 0 && fcntl(high, F_GETFD) == 0);
    assert(syscall(SYS_close_range, UINT_MAX, UINT_MAX, 0U) == 0);
    assert(syscall(SYS_close_range, 9U, 8U, 0U) == -1 && errno == EINVAL);
    assert(syscall(SYS_close_range, 4U, UINT_MAX, 8U) == -1 && errno == EINVAL);
    assert(syscall(SYS_close_range, 4U, UINT_MAX, 2U) == -1 && errno == ENOSYS);
    assert(syscall(SYS_getpid) == getpid());
    assert(syscall(SYS_close, -1) == -1 && errno == EBADF);
    // Exercise forwarding of all six syscall arguments and negative errno.
    void *memory = (void *)syscall(SYS_mmap, NULL, 4096, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
    assert(memory != MAP_FAILED); strcpy(memory, "forwarded"); assert(munmap(memory, 4096) == 0);
    pid_t child = fork(); assert(child >= 0);
    if (!child) { assert(syscall(SYS_close_range, 3U, UINT_MAX, 4U) == 0); execl("/bin/sh","sh","-c","test ! -e /proc/self/fd/100 && test ! -e /proc/self/fd/102",NULL); _exit(1); }
    int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(fcntl(low, F_GETFD) == 0); close(fd);close(low);close(high);
    puts("PASS: close_range TRAP fallback, fd bounds/flags, fork/exec and syscall forwarding");
}
