/*
 * This file is part of Sui.
 *
 * Sui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2021-2026 Sui Contributors
 */

#include <cstdlib>
#include <cstring>
#include <logging.h>
#include <unistd.h>
#include <sched.h>
#include <app_process.h>
#include <misc.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <selinux.h>

/*
 * argv[1]: path of the module, such as /data/adb/modules/zygisk-sui
 */
static int sui_main(int argc, char** argv) {
    LOGI("Sui starter begin: %s", argv[1]);

    if (daemon(false, false) != 0) {
        PLOGE("daemon");
        return EXIT_FAILURE;
    }

    {
        int fd = open("/proc/self/oom_score_adj", O_WRONLY | O_CLOEXEC);
        if (fd >= 0) {
            const char value[] = "-1000";
            if (write_full(fd, value, sizeof(value) - 1) != 0) {
                LOGW("write /proc/self/oom_score_adj failed with %d: %s", errno, strerror(errno));
            }
            close(fd);
        } else {
            LOGW("open /proc/self/oom_score_adj failed with %d: %s", errno, strerror(errno));
        }
    }

    wait_for_zygote();

    if (access("/data/adb/sui", F_OK) != 0) {
        mkdir("/data/adb/sui", 0700);
    }
    chmod("/data/adb/sui", 0700);
    chown("/data/adb/sui", 0, 0);

    auto root_path = argv[1];

    char dex_path[PATH_MAX]{0};
    strcpy(dex_path, root_path);
    strcat(dex_path, "/sui.dex");

    pid_t pid = fork();
    if (pid < 0) {
        PLOGE("fork");
        return EXIT_FAILURE;
    }

    if (pid == 0) {
        // Child process -> Shell Server
        // uid 2000 cannot read /data/adb/modules/zygisk-sui/sui.dex or .so libraries
        const char* shell_dir = "/data/local/tmp/sui_shell";
        mkdir(shell_dir, 0755);
        chown(shell_dir, 2000, 2000);

        char shell_dex_path[] = "/data/local/tmp/sui_shell/sui.dex";
        if (copyfile(dex_path, shell_dex_path) == 0) {
            chmod(shell_dex_path, 0644);
            chown(shell_dex_path, 2000, 2000);
        }

        char lib_path[PATH_MAX];
        snprintf(lib_path, PATH_MAX, "%s/librish.so", root_path);
        char shell_lib_path[] = "/data/local/tmp/sui_shell/librish.so";
        if (copyfile(lib_path, shell_lib_path) == 0) {
            chmod(shell_lib_path, 0644);
            chown(shell_lib_path, 2000, 2000);
        }

        char libsui_path[PATH_MAX];
        snprintf(libsui_path, PATH_MAX, "%s/libsui.so", root_path);
        char shell_libsui_path[] = "/data/local/tmp/sui_shell/libsui.so";
        if (copyfile(libsui_path, shell_libsui_path) == 0) {
            chmod(shell_libsui_path, 0644);
            chown(shell_libsui_path, 2000, 2000);
        }

        // Set SELinux context to shell BEFORE dropping UID/GID (requires root privileges)
        if (setcon("u:r:shell:s0") != 0) {
            PLOGE("setcon u:r:shell:s0");
            exit(EXIT_FAILURE);
        }

        // Set GID to shell (2000)
        if (setresgid(2000, 2000, 2000) != 0) {
            PLOGE("setresgid 2000");
            exit(EXIT_FAILURE);
        }

        // Set UID to shell (2000)
        if (setresuid(2000, 2000, 2000) != 0) {
            PLOGE("setresuid 2000");
            exit(EXIT_FAILURE);
        }

        app_process(shell_dex_path, shell_dir, "rikka.sui.server.Starter", "sui_shell", "--shell");
        exit(EXIT_FAILURE);
    } else {
        // Parent process -> Root Server
        app_process(dex_path, root_path, "rikka.sui.server.Starter", "sui");
        exit(EXIT_FAILURE);
    }

    return EXIT_SUCCESS;
}
