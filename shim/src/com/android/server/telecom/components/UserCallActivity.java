/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.components;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * Activity that handles system CALL actions and forwards them to TelecomUi.
 */
public class UserCallActivity extends Activity {
    private static final String TAG = "UserCallActivity[TS]";
    private static final String FALLBACK_TELECOM_UI_PACKAGE = "com.android.server.telecomui";
    private static final String ACTION_CALL_TRAMPOLINE =
            "com.android.internal.telecom.action.CALL_TRAMPOLINE";
    // Note: although the telecom ui package changes, the UserCallActivity namespace
    // stays the same. The prefix should not be overwritten based on UI package name.
    private static final String ACTION_ACTIVITY_NAME =
            "com.android.server.telecomui.components.UserCallActivity";

    private static final String EXTRA_TRAMPOLINE_CALLING_PACKAGE =
            "android.telecom.extra.TRAMPOLINE_CALLING_PACKAGE";
    private static final String EXTRA_TRAMPOLINE_CALLING_UID =
            "android.telecom.extra.TRAMPOLINE_CALLING_UID";

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        String pkgName = getTelecomUiPackageName();
        Log.i(TAG, "Trampolining to " + pkgName);
        Intent intent = getIntent();
        Intent newIntent = new Intent(intent);
        newIntent.setAction(ACTION_CALL_TRAMPOLINE);
        newIntent.setComponent(new ComponentName(pkgName, ACTION_ACTIVITY_NAME));
        newIntent.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);

        // SECURE IDENTIFICATION OF SENDER:
        // Only append trampoline extras if the target component is the unprivileged
        // UserCallActivity.
        // If the intent targeted EmergencyCallActivity or PrivilegedCallActivity, ActivityManager
        // already enforced CALL_PRIVILEGED on the creator/sender, so we preserve the privilege
        // delegation.
        ComponentName targetComponent = intent.getComponent();
        if (targetComponent != null && targetComponent.getClassName().equals(
                UserCallActivity.class.getName())) {
            String callingPackage = getLaunchedFromPackage();
            int callingUid = getLaunchedFromUid();
            Log.i(TAG, "Unprivileged trampoline: forwarding calling package " + callingPackage
                    + " uid " + callingUid);
            newIntent.putExtra(EXTRA_TRAMPOLINE_CALLING_PACKAGE, callingPackage);
            newIntent.putExtra(EXTRA_TRAMPOLINE_CALLING_UID, callingUid);
        } else {
            Log.i(TAG, "Privileged trampoline: skipping calling package extraction "
                    + (targetComponent != null ? targetComponent.getClassName() : "null"));
        }

        startActivity(newIntent);
        finish();
    }

    private String getTelecomUiPackageName() {
        TelecomManager manager = getSystemService(TelecomManager.class);
        if (manager == null) {
            Log.wtf(TAG, "getTelecomUiPackageName: couldn't get telecommanager");
            return FALLBACK_TELECOM_UI_PACKAGE;
        }

        // This is weird looking, but the idea is that creating this intent will cause
        // TelecomManager to query telecom for the name of TelecomUi in setPackage.
        // Don't use this intent, simply create to extract the correct package name.
        Intent blockedNumbersIntent = manager.createManageBlockedNumbersIntent();

        String pkgName = blockedNumbersIntent.getPackage();
        if (pkgName == null) {
            Log.wtf(TAG, "getTelecomUiPackageName: couldn't resolve");
            return FALLBACK_TELECOM_UI_PACKAGE;
        }

        return pkgName;
    }
}
