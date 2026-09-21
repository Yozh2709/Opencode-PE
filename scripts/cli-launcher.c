// Installed APK executable: Android forbids exec of scripts in writable app data.
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef LAUNCH_MODE
#define LAUNCH_MODE 0
#endif

int main(int argc, char **argv) {
    char binary[PATH_MAX], script[PATH_MAX];
    ssize_t size = readlink("/proc/self/exe", binary, sizeof(binary) - 1);
    if (size < 0 || (size_t)size >= sizeof(binary) - 1) { perror("launcher path"); return 126; }
    binary[size] = 0;
    char *slash = strrchr(binary, '/');
    if (!slash) return 126;
    *slash = 0;
    const char *runtime = LAUNCH_MODE == 0 ? "/libbun.so" : LAUNCH_MODE == 3 ? "/libtool_bin_python3_14.so" : "/libtool_bin_node.so";
    if (strlen(binary) + strlen(runtime) >= sizeof(binary)) return 126;
    strcat(binary, runtime);
    const char *entry = LAUNCH_MODE == 3 ? "pip" : getenv(LAUNCH_MODE == 0 ? "POCKET_OPENCODE_ENTRY" : "NPM_CLI");
    if (!entry || !*entry || strlen(entry) >= sizeof(script)) {
        fputs("Pocket CLI environment is missing. Run from Pocket OpenCode's environment.\n", stderr);
        return 126;
    }
    strcpy(script, entry);
    if (LAUNCH_MODE == 2) {
        char *name = strrchr(script, '/');
        if (!name || (size_t)(name - script) + sizeof("/npx-cli.js") > sizeof(script)) return 126;
        strcpy(name, "/npx-cli.js");
    }
    char **args = calloc((size_t)argc + 4, sizeof(char *));
    if (!args) return 126;
    int next = 0;
    args[next++] = binary;
    if (LAUNCH_MODE == 0) args[next++] = "--no-install";
    if (LAUNCH_MODE == 3) args[next++] = "-m";
    args[next++] = script;
    for (int i = 1; i < argc; i++) args[next++] = argv[i];
    execv(binary, args);
    int failure = errno;
    perror("Pocket CLI exec");
    free(args);
    return failure == ENOENT ? 127 : 126;
}
