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

import static com.android.server.telecom.TelecomStatsLog.TELECOM_API_STATS;

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

public class ApiStats extends TelecomPulledAtom {

    private static final String FILE_NAME = "api_stats";
    private Map<ApiStatsKey, Integer> mApiStatsMap;

    public ApiStats(@NonNull Context context, @NonNull Looper looper) {
        super(context, looper);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public int getTag() {
        return TELECOM_API_STATS;
    }

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized int onPull(final List<StatsEvent> data) {
        if (mPulledAtoms.telecomApiStats.length != 0) {
            Arrays.stream(mPulledAtoms.telecomApiStats).forEach(v -> data.add(
                    TelecomStatsLog.buildStatsEvent(getTag(),
                            v.getApiName(), v.getUid(), v.getApiResult(), v.getCount())));
            return StatsManager.PULL_SUCCESS;
        } else {
            return StatsManager.PULL_SKIP;
        }
    }

    @Override
    protected synchronized void onLoad() {
        if (mPulledAtoms.telecomApiStats != null) {
            mApiStatsMap = new HashMap<>();
            for (PulledAtomsClass.TelecomApiStats v : mPulledAtoms.telecomApiStats) {
                mApiStatsMap.put(new ApiStatsKey(v.getApiName(), v.getUid(), v.getApiResult()),
                        v.getCount());
            }
            mLastPulledTimestamps = mPulledAtoms.getTelecomApiStatsPullTimestampMillis();
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized void onAggregate() {
        clearAtoms();
        if (mApiStatsMap.isEmpty()) {
            return;
        }
        mPulledAtoms.setTelecomApiStatsPullTimestampMillis(mLastPulledTimestamps);
        mPulledAtoms.telecomApiStats =
                new PulledAtomsClass.TelecomApiStats[mApiStatsMap.size()];
        int[] index = new int[1];
        mApiStatsMap.forEach((k, v) -> {
            mPulledAtoms.telecomApiStats[index[0]] = new PulledAtomsClass.TelecomApiStats();
            mPulledAtoms.telecomApiStats[index[0]].setApiName(k.mApiId);
            mPulledAtoms.telecomApiStats[index[0]].setUid(k.mCallerUid);
            mPulledAtoms.telecomApiStats[index[0]].setApiResult(k.mResult);
            mPulledAtoms.telecomApiStats[index[0]].setCount(v);
            index[0]++;
        });
        save(DELAY_FOR_PERSISTENT_MILLIS);
    }

    public void log(int apiId, int callerUid, int result) {
        post(() -> {
            ApiStatsKey key = new ApiStatsKey(apiId, callerUid, result);
            mApiStatsMap.put(key, mApiStatsMap.getOrDefault(key, 0) + 1);
            onAggregate();
        });
    }

    static class ApiStatsKey {

        int mApiId;
        int mCallerUid;
        int mResult;

        ApiStatsKey(int apiId, int callerUid, int result) {
            mApiId = apiId;
            mCallerUid = callerUid;
            mResult = result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || !(other instanceof ApiStatsKey obj)) {
                return false;
            }
            return this.mApiId == obj.mApiId && this.mCallerUid == obj.mCallerUid
                    && this.mResult == obj.mResult;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mApiId, mCallerUid, mResult);
        }

        @Override
        public String toString() {
            return "[ApiStatsKey: mApiId=" + mApiId + ", mCallerUid=" + mCallerUid
                    + ", mResult=" + mResult + "]";
        }
    }
}
