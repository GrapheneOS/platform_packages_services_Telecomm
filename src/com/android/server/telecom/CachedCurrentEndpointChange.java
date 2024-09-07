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

import android.telecom.CallEndpoint;

import java.util.Objects;

public class CachedCurrentEndpointChange implements CachedCallback {
    public static final String ID = CachedCurrentEndpointChange.class.getSimpleName();
    CallEndpoint mCurrentCallEndpoint;

    public CallEndpoint getCurrentCallEndpoint() {
        return mCurrentCallEndpoint;
    }

    public CachedCurrentEndpointChange(CallEndpoint callEndpoint) {
        mCurrentCallEndpoint = callEndpoint;
    }

    @Override
    public int getCacheType() {
        return TYPE_STATE;
    }

    @Override
    public void executeCallback(CallSourceService service, Call call) {
        service.onCallEndpointChanged(call, mCurrentCallEndpoint);
    }

    @Override
    public String getCallbackId() {
        return ID;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mCurrentCallEndpoint);
    }

    @Override
    public boolean equals(Object obj){
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof CachedCurrentEndpointChange other)) {
            return false;
        }
        return mCurrentCallEndpoint.equals(other.mCurrentCallEndpoint);
    }
}

