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
import android.telecom.CallEndpoint;

import java.util.Set;

/**
 * android.telecom.Call backed Services (i.e. ConnectionService, TransactionalService, etc.) that
 * have callbacks that can be executed before the service is set (within the Call object) should
 * implement this interface in order for clients to receive the callback.
 *
 * It has been shown that clients can miss important callback information (e.g. available audio
 * endpoints) if the service is null within the call at the time the callback is sent.  This is a
 * way to eliminate the timing issue and for clients to receive all callbacks.
 */
public interface CallSourceService {
    void onMuteStateChanged(Call activeCall, boolean isMuted);

    void onCallEndpointChanged(Call activeCall, CallEndpoint callEndpoint);

    void onAvailableCallEndpointsChanged(Call activeCall, Set<CallEndpoint> availableCallEndpoints);

    void onVideoStateChanged(Call activeCall, int videoState);

    void sendCallEvent(Call activeCall, String event, Bundle extras);
}
