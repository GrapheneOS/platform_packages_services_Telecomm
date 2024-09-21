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

package com.android.server.telecom.metrics;

import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS;
import static com.android.server.telecom.TelecomStatsLog.TELECOM_API_STATS;
import static com.android.server.telecom.TelecomStatsLog.TELECOM_ERROR_STATS;

import android.annotation.NonNull;
import android.app.StatsManager;
import android.content.Context;
import android.os.HandlerThread;
import android.telecom.Log;
import android.util.StatsEvent;

import androidx.annotation.VisibleForTesting;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class TelecomMetricsController implements StatsManager.StatsPullAtomCallback {

    private static final String TAG = TelecomMetricsController.class.getSimpleName();

    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final ConcurrentHashMap<Integer, TelecomPulledAtom> mStats = new ConcurrentHashMap<>();

    private TelecomMetricsController(@NonNull Context context,
                                     @NonNull HandlerThread handlerThread) {
        mContext = context;
        mHandlerThread = handlerThread;
    }

    @NonNull
    public static TelecomMetricsController make(@NonNull Context context) {
        Log.i(TAG, "TMC.iN1");
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        return make(context, handlerThread);
    }

    @VisibleForTesting
    @NonNull
    public static TelecomMetricsController make(@NonNull Context context,
                                                @NonNull HandlerThread handlerThread) {
        Log.i(TAG, "TMC.iN2");
        Objects.requireNonNull(context);
        Objects.requireNonNull(handlerThread);
        return new TelecomMetricsController(context, handlerThread);
    }

    @NonNull
    public ApiStats getApiStats() {
        ApiStats stats = (ApiStats) mStats.get(TELECOM_API_STATS);
        if (stats == null) {
            stats = new ApiStats(mContext, mHandlerThread.getLooper());
            registerAtom(stats.getTag(), stats);
        }
        return stats;
    }

    @NonNull
    public AudioRouteStats getAudioRouteStats() {
        AudioRouteStats stats = (AudioRouteStats) mStats.get(CALL_AUDIO_ROUTE_STATS);
        if (stats == null) {
            stats = new AudioRouteStats(mContext, mHandlerThread.getLooper());
            registerAtom(stats.getTag(), stats);
        }
        return stats;
    }

    @NonNull
    public CallStats getCallStats() {
        CallStats stats = (CallStats) mStats.get(CALL_STATS);
        if (stats == null) {
            stats = new CallStats(mContext, mHandlerThread.getLooper());
            registerAtom(stats.getTag(), stats);
        }
        return stats;
    }

    @NonNull
    public ErrorStats getErrorStats() {
        ErrorStats stats = (ErrorStats) mStats.get(TELECOM_ERROR_STATS);
        if (stats == null) {
            stats = new ErrorStats(mContext, mHandlerThread.getLooper());
            registerAtom(stats.getTag(), stats);
        }
        return stats;
    }

    @Override
    public int onPullAtom(final int atomTag, final List<StatsEvent> data) {
        if (mStats.containsKey(atomTag)) {
            return Objects.requireNonNull(mStats.get(atomTag)).pull(data);
        }
        return StatsManager.PULL_SKIP;
    }

    @VisibleForTesting
    public Map<Integer, TelecomPulledAtom> getStats() {
        return mStats;
    }

    @VisibleForTesting
    public void registerAtom(int tag, TelecomPulledAtom atom) {
        mStats.put(tag, atom);
    }

    public void destroy() {
        mStats.clear();
        mHandlerThread.quitSafely();
    }
}
