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

package rikka.sui.server;

import androidx.annotation.Nullable;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import rikka.shizuku.server.ConfigManager;

public class SuiConfigManager extends ConfigManager {

    public static final int DEFAULT_UID = -1;

    private static android.os.FileObserver shellConfigObserver;

    private void reloadShellConfigFromFile() {
        try {
            java.io.File file = new java.io.File("/data/local/tmp/sui_shell/sui_uids.txt");
            if (!file.exists()) return;
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file))) {
                String line;
                synchronized (this) {
                    config.packages.clear();
                    while ((line = br.readLine()) != null) {
                        String[] parts = line.split(":");
                        if (parts.length == 2) {
                            int uid = Integer.parseInt(parts[0]);
                            int flags = Integer.parseInt(parts[1]);
                            config.packages.add(new SuiConfig.PackageEntry(uid, flags));
                        }
                    }
                }
            }

            // Update client states in memory
            SuiService service = SuiService.getInstance();
            if (service != null && service.getClientManager() != null) {
                for (rikka.shizuku.server.ClientRecord record :
                        service.getClientManager().getClients()) {
                    SuiConfig.PackageEntry entry = find(record.uid);
                    boolean allowed = entry != null
                            && ((entry.flags & (SuiConfig.FLAG_ALLOWED | SuiConfig.FLAG_ALLOWED_SHELL)) != 0);
                    record.allowed = allowed;
                }
            }
            LOGGER.i("Shell server reloaded config, apps: " + config.packages.size());
        } catch (Throwable e) {
            LOGGER.e(e, "reload shell config");
        }
    }

    private void syncUidsToShellFile() {
        if (SuiService.isShellMode()) return;
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (this) {
                for (SuiConfig.PackageEntry entry : config.packages) {
                    sb.append(entry.uid).append(":").append(entry.flags).append("\n");
                }
            }
            java.io.File dir = new java.io.File("/data/local/tmp/sui_shell");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "sui_uids.txt");
            java.io.File tempFile = new java.io.File(dir, "sui_uids.txt.tmp");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                fos.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.getFD().sync();
                fos.close();
                if (tempFile.renameTo(file)) {
                    android.system.Os.chmod(file.getAbsolutePath(), 0644);
                }
            }
        } catch (Throwable e) {
            LOGGER.e(e, "sync uids to shell");
        }
    }

    public static SuiConfig load() {
        if (SuiService.isShellMode()) {
            LOGGER.i("SuiConfigManager: shell mode, starting with empty config and setting up FileObserver");
            return new SuiConfig();
        }
        SuiConfig config = SuiDatabase.readConfig();
        if (config == null) {
            LOGGER.e("SuiConfigManager: failed to read database, starting empty");
            return new SuiConfig();
        }
        LOGGER.i("SuiConfigManager: Loaded " + config.packages.size() + " packages from database.");
        return config;
    }

    private static SuiConfigManager instance;

    public static SuiConfigManager getInstance() {
        if (instance == null) {
            instance = new SuiConfigManager();
        }
        return instance;
    }

    private final SuiConfig config;

    public static final int UID_GLOBAL_SETTINGS = -2;

    public SuiConfigManager() {
        this.config = load();
        if (SuiService.isShellMode()) {
            reloadShellConfigFromFile();
            if (shellConfigObserver == null) {
                shellConfigObserver =
                        new android.os.FileObserver(
                                "/data/local/tmp/sui_shell/",
                                android.os.FileObserver.CLOSE_WRITE | android.os.FileObserver.MOVED_TO) {
                            @Override
                            public void onEvent(int event, String path) {
                                if ("sui_uids.txt".equals(path)) {
                                    reloadShellConfigFromFile();
                                }
                            }
                        };
                shellConfigObserver.startWatching();
            }
        } else {
            // Root server initial sync
            syncUidsToShellFile();
        }
    }

    public int getGlobalSettings() {
        synchronized (this) {
            SuiConfig.PackageEntry entry = findLocked(UID_GLOBAL_SETTINGS);
            return entry != null ? entry.flags : 0;
        }
    }

    public void setGlobalSettings(int flags) {
        update(UID_GLOBAL_SETTINGS, 0xFFFFFFFF, flags);
    }

    private SuiConfig.PackageEntry findLocked(int uid) {
        for (SuiConfig.PackageEntry entry : config.packages) {
            if (uid == entry.uid) {
                return entry;
            }
        }
        return null;
    }

    @Nullable public SuiConfig.PackageEntry findExplicit(int uid) {
        synchronized (this) {
            return findLocked(uid);
        }
    }

    @Nullable public SuiConfig.PackageEntry find(int uid) {
        synchronized (this) {
            if (uid == 0 || uid == 1000) {
                return new SuiConfig.PackageEntry(uid, SuiConfig.FLAG_ALLOWED);
            }
            SuiConfig.PackageEntry entry = findLocked(uid);
            if (uid == DEFAULT_UID) {
                return entry;
            }
            if (entry != null && entry.flags != 0) {
                LOGGER.i("SuiConfigManager: Found explicit flags for uid " + uid + ": " + entry.flags);
                return entry;
            }
            SuiConfig.PackageEntry defaultEntry = findLocked(DEFAULT_UID);
            if (defaultEntry == null || defaultEntry.flags == 0) {
                return null;
            }
            LOGGER.i("SuiConfigManager: Using DEFAULT flags for uid " + uid + ". Flags: " + defaultEntry.flags);
            return new SuiConfig.PackageEntry(uid, defaultEntry.flags);
        }
    }

    @Override
    public void update(int uid, List<String> packages, int mask, int values) {
        update(uid, mask, values);
    }

    public void update(int uid, int mask, int values) {
        LOGGER.i("SuiConfigManager: update uid=" + uid + " mask=" + mask + " val=" + values);
        boolean needRemove = false;
        boolean needUpdate = false;
        int finalFlags = 0;

        synchronized (this) {
            SuiConfig.PackageEntry entry = findLocked(uid);
            if (entry == null) {
                int newValue = mask & values;
                if (newValue == 0) {
                    return;
                }
                entry = new SuiConfig.PackageEntry(uid, newValue);
                config.packages.add(entry);
                needUpdate = true;
                finalFlags = newValue;
                LOGGER.i("SuiConfigManager: Added new entry for uid " + uid);
            } else {
                int newValue = (entry.flags & ~mask) | (mask & values);
                if (newValue == entry.flags) {
                    return;
                }
                if (newValue == 0) {
                    config.packages.remove(entry);
                    needRemove = true;
                    LOGGER.i("SuiConfigManager: Removed entry for uid " + uid);
                } else {
                    entry.flags = newValue;
                    needUpdate = true;
                    finalFlags = newValue;
                    LOGGER.i("SuiConfigManager: Updated entry for uid " + uid);
                }
            }
        }
        if (needRemove) {
            if (!SuiService.isShellMode()) SuiDatabase.removeUid(uid);
        } else if (needUpdate) {
            if (!SuiService.isShellMode()) SuiDatabase.updateUid(uid, finalFlags);
        }
        syncUidsToShellFile();
    }

    @Override
    public void remove(int uid) {
        boolean needRemove = false;
        synchronized (this) {
            SuiConfig.PackageEntry entry = findLocked(uid);
            if (entry != null) {
                config.packages.remove(entry);
                needRemove = true;
            }
        }
        if (needRemove) {
            if (!SuiService.isShellMode()) SuiDatabase.removeUid(uid);
        }
        syncUidsToShellFile();
    }

    public boolean isHidden(int uid) {
        SuiConfig.PackageEntry entry = find(uid);
        if (entry == null) {
            return false;
        }
        return (entry.flags & SuiConfig.FLAG_HIDDEN) != 0;
    }

    public int getDefaultPermissionFlags() {
        synchronized (this) {
            SuiConfig.PackageEntry entry = findLocked(DEFAULT_UID);
            if (entry == null) {
                return 0;
            }
            return entry.flags & SuiConfig.MASK_PERMISSION;
        }
    }

    public void setDefaultPermissionFlags(int flags) {
        LOGGER.i("SuiConfigManager: Setting default permission flags: " + flags);
        int value = flags & SuiConfig.MASK_PERMISSION;
        if (value == 0) {
            remove(DEFAULT_UID);
        } else {
            update(DEFAULT_UID, SuiConfig.MASK_PERMISSION, value);
        }
    }

    public int[] getHiddenUids() {
        synchronized (this) {
            List<Integer> uids = new ArrayList<>();
            for (SuiConfig.PackageEntry entry : config.packages) {
                if (entry.uid >= 10000 && (entry.flags & SuiConfig.FLAG_HIDDEN) != 0) {
                    uids.add(entry.uid);
                }
            }
            int[] res = new int[uids.size()];
            for (int i = 0; i < uids.size(); i++) {
                res[i] = uids.get(i);
            }
            return res;
        }
    }

    public int[] getRootUids() {
        synchronized (this) {
            List<Integer> uids = new ArrayList<>();
            for (SuiConfig.PackageEntry entry : config.packages) {
                if (entry.uid >= 10000 && (entry.flags & SuiConfig.FLAG_ALLOWED) != 0) {
                    uids.add(entry.uid);
                }
            }
            int[] res = new int[uids.size()];
            for (int i = 0; i < uids.size(); i++) {
                res[i] = uids.get(i);
            }
            return res;
        }
    }

    public int[] getShellUids() {
        synchronized (this) {
            List<Integer> uids = new ArrayList<>();
            for (SuiConfig.PackageEntry entry : config.packages) {
                if (entry.uid >= 10000 && (entry.flags & SuiConfig.FLAG_ALLOWED_SHELL) != 0) {
                    uids.add(entry.uid);
                }
            }
            int[] res = new int[uids.size()];
            for (int i = 0; i < uids.size(); i++) {
                res[i] = uids.get(i);
            }
            return res;
        }
    }

    private String shortcutToken;

    public synchronized String getShortcutToken() {
        if (shortcutToken != null) {
            return shortcutToken;
        }

        File tokenFile = new File("/data/adb/sui/shortcut_token");
        if (tokenFile.exists() && tokenFile.length() > 0) {
            try (BufferedReader reader = new BufferedReader(new FileReader(tokenFile))) {
                shortcutToken = reader.readLine();
                if (shortcutToken != null && !shortcutToken.isEmpty()) {
                    return shortcutToken;
                }
            } catch (IOException e) {
                LOGGER.e(e, "Failed to read shortcut token");
            }
        }

        shortcutToken = UUID.randomUUID().toString();
        File tempFile = new File(tokenFile.getPath() + ".tmp");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
            fos.write(shortcutToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.getFD().sync();
            fos.close();
            if (!tempFile.renameTo(tokenFile)) {
                throw new IOException("Rename failed");
            }
        } catch (IOException e) {
            LOGGER.e(e, "Failed to write shortcut token atomically");
        }
        return shortcutToken;
    }
}
