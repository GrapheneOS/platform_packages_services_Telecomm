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

/**
 * Any android.telecom.Call service (e.g. ConnectionService, TransactionalService) that declares
 * a {@link CallSourceService} should implement this interface in order to cache the callback.
 * The callback will be executed once the service is set.
 */
public interface CachedCallback {
    /**
     * This method executes the callback that was cached because the service was not available
     * at the time the callback was ready.
     *
     * @param service that was recently set (e.g. ConnectionService)
     * @param call    that had a null service at the time the callback was ready. The service is now
     *                non-null in the call and can be executed/
     */
    void executeCallback(CallSourceService service, Call call);

    /**
     * This method is helpful for caching the callbacks.  If the callback is called multiple times
     * while the service is not set, ONLY the last callback should be sent to the client since the
     * last callback is the most relevant
     *
     * @return the callback id that is used in a map to only store the last callback value
     */
    String getCallbackId();
}
