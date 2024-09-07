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

import static com.android.server.telecom.voip.VideoStateTranslation.TransactionalVideoStateToString;

import android.telecom.Log;

public class CachedVideoStateChange implements CachedCallback {
    public static final String ID = CachedVideoStateChange.class.getSimpleName();
    int mCurrentVideoState;

    public int getCurrentCallEndpoint() {
        return mCurrentVideoState;
    }

    public CachedVideoStateChange(int videoState) {
        mCurrentVideoState = videoState;
    }

    @Override
    public int getCacheType() {
        return TYPE_STATE;
    }

    @Override
    public void executeCallback(CallSourceService service, Call call) {
        service.onVideoStateChanged(call, mCurrentVideoState);
        Log.addEvent(call, LogUtils.Events.VIDEO_STATE_CHANGED,
                TransactionalVideoStateToString(mCurrentVideoState));
    }

    @Override
    public String getCallbackId() {
        return ID;
    }

    @Override
    public int hashCode() {
        return mCurrentVideoState;
    }

    @Override
    public boolean equals(Object obj){
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof CachedVideoStateChange other)) {
            return false;
        }
        return mCurrentVideoState == other.mCurrentVideoState;
    }
}

