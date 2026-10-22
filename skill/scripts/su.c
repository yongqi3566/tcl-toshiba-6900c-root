/*
 * Minimal setuid-root shell for the TCL 75U6900C (Android 6, arm64).
 * Build static:
 *   aarch64-linux-android23-clang -O2 -static -o su su.c
 * Install as /system/xbin/su, owner root:shell, mode 4750.
 * Only for your own device: any process in the shell group can use it.
 */
#include <unistd.h>
#include <string.h>

int main(int argc, char **argv) {
    setgid(0);
    setuid(0);
    setresgid(0, 0, 0);
    setresuid(0, 0, 0);
    if (argc >= 3 && strcmp(argv[1], "-c") == 0) {
        execl("/system/bin/sh", "sh", "-c", argv[2], (char *)0);
    } else {
        execl("/system/bin/sh", "sh", (char *)0);
    }
    return 1;
}
