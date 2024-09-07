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
import java.util.Set;

public class CachedAvailableEndpointsChange implements CachedCallback {
    public static final String ID = CachedAvailableEndpointsChange.class.getSimpleName();
    Set<CallEndpoint> mAvailableEndpoints;

    public Set<CallEndpoint> getAvailableEndpoints() {
        return mAvailableEndpoints;
    }

    public CachedAvailableEndpointsChange(Set<CallEndpoint> endpoints) {
        mAvailableEndpoints = endpoints;
    }

    @Override
    public int getCacheType() {
        return TYPE_STATE;
    }

    @Override
    public void executeCallback(CallSourceService service, Call call) {
        service.onAvailableCallEndpointsChanged(call, mAvailableEndpoints);
    }

    @Override
    public String getCallbackId() {
        return ID;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mAvailableEndpoints);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof CachedAvailableEndpointsChange other)) {
            return false;
        }
        if (mAvailableEndpoints.size() != other.mAvailableEndpoints.size()) {
            return false;
        }
        for (CallEndpoint e : mAvailableEndpoints) {
            if (!other.getAvailableEndpoints().contains(e)) {
                return false;
            }
        }
        return true;
    }
}

