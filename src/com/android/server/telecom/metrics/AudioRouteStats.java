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

import static com.android.server.telecom.AudioRoute.TYPE_BLUETOOTH_HA;
import static com.android.server.telecom.AudioRoute.TYPE_BLUETOOTH_LE;
import static com.android.server.telecom.AudioRoute.TYPE_BLUETOOTH_SCO;
import static com.android.server.telecom.AudioRoute.TYPE_DOCK;
import static com.android.server.telecom.AudioRoute.TYPE_EARPIECE;
import static com.android.server.telecom.AudioRoute.TYPE_SPEAKER;
import static com.android.server.telecom.AudioRoute.TYPE_STREAMING;
import static com.android.server.telecom.AudioRoute.TYPE_WIRED;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_BLUETOOTH;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_BLUETOOTH_LE;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_EARPIECE;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_HEARING_AID;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_PHONE_SPEAKER;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_UNSPECIFIED;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_WATCH_SPEAKER;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_WIRED_HEADSET;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_HEARING_AID;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_PHONE_SPEAKER;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_UNSPECIFIED;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_WATCH_SPEAKER;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_WIRED_HEADSET;

import android.annotation.NonNull;
import android.app.StatsManager;
import android.content.Context;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.telecom.Log;
import android.util.Pair;
import android.util.StatsEvent;

import androidx.annotation.VisibleForTesting;

import com.android.server.telecom.AudioRoute;
import com.android.server.telecom.PendingAudioRoute;
import com.android.server.telecom.TelecomStatsLog;
import com.android.server.telecom.nano.PulledAtomsClass;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AudioRouteStats extends TelecomPulledAtom {
    @VisibleForTesting
    public static final long THRESHOLD_REVERT_MS = 5000;
    @VisibleForTesting
    public static final int EVENT_REVERT_THRESHOLD_EXPIRED = EVENT_SUB_BASE + 1;
    private static final String TAG = AudioRouteStats.class.getSimpleName();
    private static final String FILE_NAME = "audio_route_stats";
    private Map<AudioRouteStatsKey, AudioRouteStatsData> mAudioRouteStatsMap;
    private Pair<AudioRouteStatsKey, long[]> mCur;
    private boolean mIsOngoing;

    public AudioRouteStats(@NonNull Context context, @NonNull Looper looper) {
        super(context, looper);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public int getTag() {
        return CALL_AUDIO_ROUTE_STATS;
    }

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized int onPull(final List<StatsEvent> data) {
        if (mPulledAtoms.callAudioRouteStats.length != 0) {
            Arrays.stream(mPulledAtoms.callAudioRouteStats).forEach(v -> data.add(
                    TelecomStatsLog.buildStatsEvent(getTag(),
                            v.getCallAudioRouteSource(), v.getCallAudioRouteDest(),
                            v.getSuccess(), v.getRevert(), v.getCount(), v.getAverageLatencyMs())));
            return StatsManager.PULL_SUCCESS;
        } else {
            return StatsManager.PULL_SKIP;
        }
    }

    @Override
    protected synchronized void onLoad() {
        if (mPulledAtoms.callAudioRouteStats != null) {
            mAudioRouteStatsMap = new HashMap<>();
            for (PulledAtomsClass.CallAudioRouteStats v : mPulledAtoms.callAudioRouteStats) {
                mAudioRouteStatsMap.put(new AudioRouteStatsKey(v.getCallAudioRouteSource(),
                                v.getCallAudioRouteDest(), v.getSuccess(), v.getRevert()),
                        new AudioRouteStatsData(v.getCount(), v.getAverageLatencyMs()));
            }
            mLastPulledTimestamps = mPulledAtoms.getCallAudioRouteStatsPullTimestampMillis();
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized void onAggregate() {
        Log.d(TAG, "onAggregate: %s", mAudioRouteStatsMap);
        clearAtoms();
        if (mAudioRouteStatsMap.isEmpty()) {
            return;
        }
        mPulledAtoms.setCallAudioRouteStatsPullTimestampMillis(mLastPulledTimestamps);
        mPulledAtoms.callAudioRouteStats =
                new PulledAtomsClass.CallAudioRouteStats[mAudioRouteStatsMap.size()];
        int[] index = new int[1];
        mAudioRouteStatsMap.forEach((k, v) -> {
            mPulledAtoms.callAudioRouteStats[index[0]] = new PulledAtomsClass.CallAudioRouteStats();
            mPulledAtoms.callAudioRouteStats[index[0]].setCallAudioRouteSource(k.mSource);
            mPulledAtoms.callAudioRouteStats[index[0]].setCallAudioRouteDest(k.mDest);
            mPulledAtoms.callAudioRouteStats[index[0]].setSuccess(k.mIsSuccess);
            mPulledAtoms.callAudioRouteStats[index[0]].setRevert(k.mIsRevert);
            mPulledAtoms.callAudioRouteStats[index[0]].setCount(v.mCount);
            mPulledAtoms.callAudioRouteStats[index[0]].setAverageLatencyMs(v.mAverageLatency);
            index[0]++;
        });
        save(DELAY_FOR_PERSISTENT_MILLIS);
    }

    @VisibleForTesting
    public void log(int source, int target, boolean isSuccess, boolean isRevert, int latency) {
        post(() -> onLog(new AudioRouteStatsKey(source, target, isSuccess, isRevert), latency));
    }

    public void onRouteEnter(PendingAudioRoute pendingRoute) {
        int sourceType = convertAudioType(pendingRoute.getOrigRoute(), true);
        int destType = convertAudioType(pendingRoute.getDestRoute(), false);
        long curTime = SystemClock.elapsedRealtime();

        post(() -> {
            // Ignore the transition route
            if (!mIsOngoing) {
                mIsOngoing = true;
                // Check if the previous route is reverted as the revert time has not been expired.
                if (mCur != null) {
                    if (destType == mCur.first.getSource() && curTime - mCur.second[0]
                            < THRESHOLD_REVERT_MS) {
                        mCur.first.setRevert(true);
                    }
                    if (mCur.second[1] < 0) {
                        mCur.second[1] = curTime;
                    }
                    onLog();
                }
                mCur = new Pair<>(new AudioRouteStatsKey(sourceType, destType), new long[]{curTime,
                        -1});
                if (hasMessages(EVENT_REVERT_THRESHOLD_EXPIRED)) {
                    // Only keep the latest event
                    removeMessages(EVENT_REVERT_THRESHOLD_EXPIRED);
                }
                sendMessageDelayed(
                        obtainMessage(EVENT_REVERT_THRESHOLD_EXPIRED), THRESHOLD_REVERT_MS);
            }
        });
    }

    public void onRouteExit(PendingAudioRoute pendingRoute, boolean isSuccess) {
        // Check the dest type on the route exiting as it may be different as the enter
        int destType = convertAudioType(pendingRoute.getDestRoute(), false);
        long curTime = SystemClock.elapsedRealtime();
        post(() -> {
            if (mIsOngoing) {
                mIsOngoing = false;
                // Should not be null unless the route is not done before the revert timer expired.
                if (mCur != null) {
                    mCur.first.setDestType(destType);
                    mCur.first.setSuccess(isSuccess);
                    mCur.second[1] = curTime;
                }
            }
        });
    }

    private void onLog() {
        if (mCur != null) {
            // Ignore the case if the source and dest types are same
            if (mCur.first.mSource != mCur.first.mDest) {
                // The route should have been done before the revert timer expires. Otherwise, it
                // would be logged as the failed case
                if (mCur.second[1] < 0) {
                    mCur.second[1] = SystemClock.elapsedRealtime();
                }
                onLog(mCur.first, (int) (mCur.second[1] - mCur.second[0]));
            }
            mCur = null;
        }
    }

    private void onLog(AudioRouteStatsKey key, int latency) {
        AudioRouteStatsData data = mAudioRouteStatsMap.computeIfAbsent(key,
                k -> new AudioRouteStatsData(0, 0));
        data.add(latency);
        onAggregate();
    }

    private int convertAudioType(AudioRoute route, boolean isSource) {
        if (route != null) {
            switch (route.getType()) {
                case TYPE_EARPIECE:
                    return isSource ? CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE
                            : CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_EARPIECE;
                case TYPE_WIRED:
                    return isSource ? CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_WIRED_HEADSET
                            : CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_WIRED_HEADSET;
                case TYPE_SPEAKER:
                    return isSource ? CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_PHONE_SPEAKER
                            : CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_PHONE_SPEAKER;
                case TYPE_BLUETOOTH_LE:
                    return isSource ? CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE
                            : CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_BLUETOOTH_LE;
                case TYPE_BLUETOOTH_SCO:
                    if (isSource) {
                        return route.isWatch()
                                ? CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_WATCH_SPEAKER
                                : CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH;
                    } else {
                        return route.isWatch()
                                ? CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_WATCH_SPEAKER
                                : CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_BLUETOOTH;
                    }
                case TYPE_BLUETOOTH_HA:
                    return isSource ? CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_HEARING_AID
                            : CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_HEARING_AID;
                case TYPE_DOCK:
                    // Reserved for the future
                case TYPE_STREAMING:
                    // Reserved for the future
                default:
                    break;
            }
        }

        return isSource ? CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_UNSPECIFIED
                : CALL_AUDIO_ROUTE_STATS__ROUTE_DEST__CALL_AUDIO_UNSPECIFIED;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_REVERT_THRESHOLD_EXPIRED:
                onLog();
                break;
            default:
                super.handleMessage(msg);
        }
    }

    static class AudioRouteStatsKey {

        final int mSource;
        int mDest;
        boolean mIsSuccess;
        boolean mIsRevert;

        AudioRouteStatsKey(int source, int dest) {
            mSource = source;
            mDest = dest;
        }

        AudioRouteStatsKey(int source, int dest, boolean isSuccess, boolean isRevert) {
            mSource = source;
            mDest = dest;
            mIsSuccess = isSuccess;
            mIsRevert = isRevert;
        }

        void setDestType(int dest) {
            mDest = dest;
        }

        void setSuccess(boolean isSuccess) {
            mIsSuccess = isSuccess;
        }

        void setRevert(boolean isRevert) {
            mIsRevert = isRevert;
        }

        int getSource() {
            return mSource;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof AudioRouteStatsKey obj)) {
                return false;
            }
            return this.mSource == obj.mSource && this.mDest == obj.mDest
                    && this.mIsSuccess == obj.mIsSuccess && this.mIsRevert == obj.mIsRevert;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mSource, mDest, mIsSuccess, mIsRevert);
        }

        @Override
        public String toString() {
            return "[AudioRouteStatsKey: mSource=" + mSource + ", mDest=" + mDest
                    + ", mIsSuccess=" + mIsSuccess + ", mIsRevert=" + mIsRevert + "]";
        }
    }

    static class AudioRouteStatsData {

        int mCount;
        int mAverageLatency;

        AudioRouteStatsData(int count, int averageLatency) {
            mCount = count;
            mAverageLatency = averageLatency;
        }

        void add(int latency) {
            mCount++;
            mAverageLatency += (latency - mAverageLatency) / mCount;
        }

        @Override
        public String toString() {
            return "[AudioRouteStatsData: mCount=" + mCount + ", mAverageLatency:"
                    + mAverageLatency + "]";
        }
    }
}
