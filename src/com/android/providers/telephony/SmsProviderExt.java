package com.android.providers.telephony;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.ext.AppInfoExtFlag;
import android.ext.PackageId;
import android.os.UserHandle;

import com.android.internal.telephony.PackageBasedTokenUtil;

import java.util.ArrayList;

class SmsProviderExt {

    static void maybeGetGmsCoreDependantPackageHashes(Context ctx, String callingPackage,
                                                      UserHandle callerUser, ArrayList<String> dst) {
        if (!PackageId.GMS_CORE_NAME.equals(callingPackage)) {
            return;
        }

        ApplicationInfo callerAppInfo;
        PackageManager pm = ctx.getPackageManager();
        int callerUserId = callerUser.getIdentifier();
        try {
            callerAppInfo = pm.getApplicationInfoAsUser(PackageId.GMS_CORE_NAME, 0, callerUserId);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        if (callerAppInfo.ext().getPackageId() != PackageId.GMS_CORE) {
            return;
        }

        for (ApplicationInfo app : pm.getInstalledApplicationsAsUser(0, callerUserId)) {
            if (app.ext().hasFlag(AppInfoExtFlag.HAS_GMSCORE_CLIENT_LIBRARY)) {
                // GmsCompat: allow GmsCore to read SMS OTP that are addressed to
                // its clients if GmsCore has the SMS permission
                dst.add(PackageBasedTokenUtil.generatePackageBasedToken(pm, app.packageName));
            }
        }
    }
}
