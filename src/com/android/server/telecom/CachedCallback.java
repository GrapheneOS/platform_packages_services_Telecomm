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
     * This callback is caching a state, meaning any new CachedCallbacks with the same
     * {@link #getCallbackId()} will REPLACE any existing CachedCallback.
     */
    int TYPE_STATE = 0;
    /**
     * This callback is caching a Queue, meaning that any new CachedCallbacks with the same
     * {@link #getCallbackId()} will enqueue as a FIFO queue and each instance of this
     * CachedCallback will run {@link #executeCallback(CallSourceService, Call)}.
     */
    int TYPE_QUEUE = 1;

    /**
     * This method allows the callback to determine whether it is caching a {@link #TYPE_STATE} or
     * a {@link #TYPE_QUEUE}.
     *
     * @return Either {@link #TYPE_STATE} or {@link #TYPE_QUEUE} based on the callback type.
     */
    int getCacheType();

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
     * The ID that this CachedCallback should use to identify itself as a distinct operation.
     * <p>
     * If {@link #TYPE_STATE} is set for {@link #getCacheType()}, and a CachedCallback with the
     * same ID is called multiple times while the service is not set, ONLY the last callback will be
     * sent to the client since the last callback is the most relevant.
     * <p>
     * If {@link #TYPE_QUEUE} is set for {@link #getCacheType()} and the CachedCallback with the
     * same ID is called multiple times while the service is not set, each CachedCallback will be
     * enqueued in FIFO order. Once the service is set, {@link #executeCallback} will be called
     * for each CachedCallback with the same ID.
     *
     * @return A unique callback id that will be used differentiate this CachedCallback type with
     * other CachedCallback types.
     */
    String getCallbackId();
}
