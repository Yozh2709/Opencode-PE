// Preserve the variadic libc syscall ABI without reading nonexistent C varargs.
#include <asm/unistd.h>
.text
.global syscall
.hidden pocket_close_range
.hidden pocket_syscall_result
#if defined(__aarch64__)
.type syscall, %function
syscall:
    cmp x0, #__NR_close_range
    b.eq 1f
    mov x8, x0
    mov x0, x1
    mov x1, x2
    mov x2, x3
    mov x3, x4
    mov x4, x5
    mov x5, x6
    svc #0
    b pocket_syscall_result
1:
    mov x0, x1
    mov x1, x2
    mov x2, x3
    b pocket_close_range
#elif defined(__x86_64__)
// Host regression tests exercise the same C fallback under a seccomp TRAP.
.type syscall, @function
syscall:
    cmp $__NR_close_range, %rdi
    je 1f
    mov %rdi, %rax
    mov %rsi, %rdi
    mov %rdx, %rsi
    mov %rcx, %rdx
    mov %r8, %r10
    mov %r9, %r8
    mov 8(%rsp), %r9
    syscall
    mov %rax, %rdi
    jmp pocket_syscall_result
1:
    mov %rsi, %rdi
    mov %rdx, %rsi
    mov %rcx, %rdx
    jmp pocket_close_range
#else
#error Unsupported syscall ABI
#endif
.size syscall, .-syscall
.section .note.GNU-stack,"",%progbits
