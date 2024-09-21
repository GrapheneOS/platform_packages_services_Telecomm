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

import static com.android.server.telecom.TelecomStatsLog.TELECOM_ERROR_STATS;

import android.annotation.NonNull;
import android.app.StatsManager;
import android.content.Context;
import android.os.Looper;
import android.util.StatsEvent;

import androidx.annotation.VisibleForTesting;

import com.android.server.telecom.TelecomStatsLog;
import com.android.server.telecom.nano.PulledAtomsClass;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ErrorStats extends TelecomPulledAtom {

    private static final String FILE_NAME = "error_stats";
    private Map<ErrorStatsKey, Integer> mErrorStatsMap;

    public ErrorStats(@NonNull Context context, @NonNull Looper looper) {
        super(context, looper);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public int getTag() {
        return TELECOM_ERROR_STATS;
    }

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized int onPull(final List<StatsEvent> data) {
        if (mPulledAtoms.telecomErrorStats.length != 0) {
            Arrays.stream(mPulledAtoms.telecomErrorStats).forEach(v -> data.add(
                    TelecomStatsLog.buildStatsEvent(getTag(),
                            v.getSubmoduleName(), v.getErrorName(), v.getCount())));
            return StatsManager.PULL_SUCCESS;
        } else {
            return StatsManager.PULL_SKIP;
        }
    }

    @Override
    protected synchronized void onLoad() {
        if (mPulledAtoms.telecomErrorStats != null) {
            mErrorStatsMap = new HashMap<>();
            for (PulledAtomsClass.TelecomErrorStats v : mPulledAtoms.telecomErrorStats) {
                mErrorStatsMap.put(new ErrorStatsKey(v.getSubmoduleName(), v.getErrorName()),
                        v.getCount());
            }
            mLastPulledTimestamps = mPulledAtoms.getTelecomErrorStatsPullTimestampMillis();
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized void onAggregate() {
        clearAtoms();
        if (mErrorStatsMap.isEmpty()) {
            return;
        }
        mPulledAtoms.setTelecomErrorStatsPullTimestampMillis(mLastPulledTimestamps);
        mPulledAtoms.telecomErrorStats =
                new PulledAtomsClass.TelecomErrorStats[mErrorStatsMap.size()];
        int[] index = new int[1];
        mErrorStatsMap.forEach((k, v) -> {
            mPulledAtoms.telecomErrorStats[index[0]] = new PulledAtomsClass.TelecomErrorStats();
            mPulledAtoms.telecomErrorStats[index[0]].setSubmoduleName(k.mModuleId);
            mPulledAtoms.telecomErrorStats[index[0]].setErrorName(k.mErrorId);
            mPulledAtoms.telecomErrorStats[index[0]].setCount(v);
            index[0]++;
        });
        save(DELAY_FOR_PERSISTENT_MILLIS);
    }

    public void log(int moduleId, int errorId) {
        post(() -> {
            ErrorStatsKey key = new ErrorStatsKey(moduleId, errorId);
            mErrorStatsMap.put(key, mErrorStatsMap.getOrDefault(key, 0) + 1);
            onAggregate();
        });
    }

    static class ErrorStatsKey {

        final int mModuleId;
        final int mErrorId;

        ErrorStatsKey(int moduleId, int errorId) {
            mModuleId = moduleId;
            mErrorId = errorId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ErrorStatsKey obj)) {
                return false;
            }
            return this.mModuleId == obj.mModuleId && this.mErrorId == obj.mErrorId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mModuleId, mErrorId);
        }

        @Override
        public String toString() {
            return "[ErrorStatsKey: mModuleId=" + mModuleId + ", mErrorId=" + mErrorId + "]";
        }
    }
}
