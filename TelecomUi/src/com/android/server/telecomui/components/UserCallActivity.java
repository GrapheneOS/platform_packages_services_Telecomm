/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.telecomui.components;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * Activity in TelecomUi responsible for handling call initiation requests.
 *
 * <p>This activity serves as the entry point for outgoing calls within the TelecomUi module.
 * It is designed to handle the {@code com.android.internal.telecom.action.CALL_TRAMPOLINE} intent,
 * which is used by the system (via {@code TelecomShim}) to bridge {@link Intent#ACTION_CALL}
 * requests from applications into the Mainline module context.
 *
 * <p>Upon receiving the request, this activity initiates the call via
 * {@link TelecomManager#placeCall(android.net.Uri, Bundle)}.
 */
public class UserCallActivity extends Activity {
    private static final String TAG = "UserCallActivity TelecomUi";

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        Log.i(TAG, "Handling call in TelecomUi");
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "UserCallActivity");
        wakelock.acquire();

        try {
            Intent intent = getIntent();

            TelecomManager tm = getSystemService(TelecomManager.class);
            if (tm != null && intent != null) {
                tm.placeCall(intent.getData(), intent.getExtras());
            }
        } finally {
            wakelock.release();
        }
        finish();
    }
}
