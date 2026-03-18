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
 * Copyright (c) 2026 Sui Contributors
 */

package rikka.sui.server;

import static rikka.sui.server.ServerConstants.LOGGER;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoHidden;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.ArrayMap;
import dev.rikka.tools.refine.Refine;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.UserManagerApis;
import rikka.parcelablelist.ParcelableListSlice;
import rikka.sui.model.AppInfo;
import rikka.sui.util.MapUtil;
import rikka.sui.util.UserHandleCompat;

public class AppListBuilder {

    private static int getFlagsForUidInternal(SuiConfigManager configManager, int uid, int mask) {
        SuiConfig.PackageEntry entry = configManager.findExplicit(uid);
        if (entry != null) {
            return entry.flags & mask;
        }
        return 0;
    }

    public static ParcelableListSlice<AppInfo> build(
            SuiConfigManager configManager, int systemUiUid, int userId, boolean onlyShizuku) {

        int defaultPermissionFlags = configManager.getDefaultPermissionFlags();
        List<Integer> users = new ArrayList<>();
        if (userId == -1) {
            users.addAll(UserManagerApis.getUserIdsNoThrow());
        } else {
            users.add(userId);
        }

        Map<String, Boolean> existenceCache = new ArrayMap<>();
        List<AppInfo> list = new ArrayList<>();
        int installedBaseFlags = 0x00002000 /*MATCH_UNINSTALLED_PACKAGES*/ | PackageManager.GET_PERMISSIONS;

        for (int user : users) {
            for (PackageInfo pi : PackageManagerApis.getInstalledPackagesNoThrow(installedBaseFlags, user)) {
                try {
                    if (pi.applicationInfo == null
                            || Refine.<PackageInfoHidden>unsafeCast(pi).overlayTarget != null
                            || (pi.applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) == 0) continue;

                    int uid = pi.applicationInfo.uid;

                    if (onlyShizuku) {
                        boolean explicitlyAllowed = false;
                        SuiConfig.PackageEntry explicitEntry = configManager.findExplicit(uid);
                        if (explicitEntry != null && (explicitEntry.isAllowed() || explicitEntry.isAllowedShell())) {
                            explicitlyAllowed = true;
                        }

                        if (!explicitlyAllowed) {
                            if (pi.requestedPermissions == null) continue;
                            boolean requested = false;
                            for (String p : pi.requestedPermissions) {
                                if ("moe.shizuku.manager.permission.API_V23".equals(p)) {
                                    requested = true;
                                    break;
                                }
                            }
                            if (!requested) continue;
                        }
                    }

                    int appId = UserHandleCompat.getAppId(uid);
                    if (uid == systemUiUid) continue;

                    int flags = getFlagsForUidInternal(configManager, uid, SuiConfig.MASK_PERMISSION);
                    if (flags == 0 && uid != 2000 && appId < 10000) continue;

                    if (flags == 0) {
                        String dataDir;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            dataDir = pi.applicationInfo.deviceProtectedDataDir;
                        } else {
                            dataDir = pi.applicationInfo.dataDir;
                        }

                        final String sourceDir = pi.applicationInfo.sourceDir;
                        boolean hasApk = sourceDir != null
                                && MapUtil.getOrPut(existenceCache, sourceDir, () -> new File(sourceDir).exists());
                        boolean hasData = dataDir != null
                                && MapUtil.getOrPut(existenceCache, dataDir, () -> new File(dataDir).exists());

                        // Installed (or hidden): hasApk && hasData
                        // Uninstalled but keep data: !hasApk && hasData
                        // Installed in other users only: hasApk && !hasData
                        if (!(hasApk && hasData)) {
                            LOGGER.v(
                                    "skip %d:%s: hasApk=%s, hasData=%s",
                                    user, pi.packageName, Boolean.toString(hasApk), Boolean.toString(hasData));
                            continue;
                        }
                    }

                    pi.activities = null;
                    pi.receivers = null;
                    pi.services = null;
                    pi.providers = null;

                    AppInfo item = new AppInfo();
                    item.packageInfo = pi;
                    item.flags = flags;
                    item.defaultFlags = defaultPermissionFlags;
                    list.add(item);
                } catch (Throwable e) {
                    LOGGER.w(e, "Error processing package %d %s", user, pi.packageName);
                }
            }
        }
        return new ParcelableListSlice<>(list);
    }
}
