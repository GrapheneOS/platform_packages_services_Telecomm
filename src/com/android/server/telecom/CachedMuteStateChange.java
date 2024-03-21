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

public class CachedMuteStateChange implements CachedCallback {
    public static final String ID = CachedMuteStateChange.class.getSimpleName();
    boolean mIsMuted;

    public boolean isMuted() {
        return mIsMuted;
    }

    public CachedMuteStateChange(boolean isMuted) {
        mIsMuted = isMuted;
    }

    @Override
    public void executeCallback(CallSourceService service, Call call) {
        service.onMuteStateChanged(call, mIsMuted);
    }

    @Override
    public String getCallbackId() {
        return ID;
    }

    @Override
    public int hashCode() {
        return Boolean.hashCode(mIsMuted);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof CachedMuteStateChange other)) {
            return false;
        }
        return mIsMuted == other.mIsMuted;
    }
}

