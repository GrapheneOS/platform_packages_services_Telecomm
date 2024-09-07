/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.telecom;

import android.os.Bundle;
import android.telecom.Log;

public class CachedCallEventQueue implements CachedCallback {
    public static final String ID = CachedCallEventQueue.class.getSimpleName();

    private final String mEvent;
    private final Bundle mExtras;

    public CachedCallEventQueue(String event, Bundle extras) {
        mEvent = event;
        mExtras = extras;
    }

    @Override
    public int getCacheType() {
        return TYPE_QUEUE;
    }

    @Override
    public void executeCallback(CallSourceService service, Call call) {
        Log.addEvent(call, LogUtils.Events.CALL_EVENT, mEvent);
        service.sendCallEvent(call, mEvent, mExtras);
    }

    @Override
    public String getCallbackId() {
        return ID;
    }
}
