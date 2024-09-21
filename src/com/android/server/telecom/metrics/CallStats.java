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

import static com.android.server.telecom.TelecomStatsLog.CALL_STATS;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__ACCOUNT_TYPE__ACCOUNT_MANAGED;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__ACCOUNT_TYPE__ACCOUNT_SELFMANAGED;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__ACCOUNT_TYPE__ACCOUNT_SIM;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__ACCOUNT_TYPE__ACCOUNT_UNKNOWN;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__ACCOUNT_TYPE__ACCOUNT_VOIP_API;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__CALL_DIRECTION__DIR_INCOMING;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__CALL_DIRECTION__DIR_OUTGOING;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__CALL_DIRECTION__DIR_UNKNOWN;

import android.annotation.NonNull;
import android.app.StatsManager;
import android.content.Context;
import android.os.Looper;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.util.StatsEvent;

import androidx.annotation.VisibleForTesting;

import com.android.server.telecom.Call;
import com.android.server.telecom.TelecomStatsLog;
import com.android.server.telecom.nano.PulledAtomsClass;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class CallStats extends TelecomPulledAtom {
    private static final String TAG = CallStats.class.getSimpleName();

    private static final String FILE_NAME = "call_stats";
    private final Set<String> mOngoingCallsWithoutMultipleAudioDevices = new HashSet<>();
    private final Set<String> mOngoingCallsWithMultipleAudioDevices = new HashSet<>();
    private Map<CallStatsKey, CallStatsData> mCallStatsMap;
    private boolean mHasMultipleAudioDevices;

    public CallStats(@NonNull Context context, @NonNull Looper looper) {
        super(context, looper);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public int getTag() {
        return CALL_STATS;
    }

    @Override
    protected String getFileName() {
        return FILE_NAME;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized int onPull(final List<StatsEvent> data) {
        if (mPulledAtoms.callStats.length != 0) {
            Arrays.stream(mPulledAtoms.callStats).forEach(v -> data.add(
                    TelecomStatsLog.buildStatsEvent(getTag(),
                            v.getCallDirection(), v.getExternalCall(), v.getEmergencyCall(),
                            v.getMultipleAudioAvailable(), v.getAccountType(), v.getUid(),
                            v.getCount(), v.getAverageDurationMs())));
            return StatsManager.PULL_SUCCESS;
        } else {
            return StatsManager.PULL_SKIP;
        }
    }

    @Override
    protected synchronized void onLoad() {
        if (mPulledAtoms.callStats != null) {
            mCallStatsMap = new HashMap<>();
            for (PulledAtomsClass.CallStats v : mPulledAtoms.callStats) {
                mCallStatsMap.put(new CallStatsKey(v.getCallDirection(),
                                v.getExternalCall(), v.getEmergencyCall(),
                                v.getMultipleAudioAvailable(),
                                v.getAccountType(), v.getUid()),
                        new CallStatsData(v.getCount(), v.getAverageDurationMs()));
            }
            mLastPulledTimestamps = mPulledAtoms.getCallStatsPullTimestampMillis();
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @Override
    public synchronized void onAggregate() {
        Log.d(TAG, "onAggregate: %s", mCallStatsMap);
        clearAtoms();
        if (mCallStatsMap.isEmpty()) {
            return;
        }
        mPulledAtoms.setCallStatsPullTimestampMillis(mLastPulledTimestamps);
        mPulledAtoms.callStats = new PulledAtomsClass.CallStats[mCallStatsMap.size()];
        int[] index = new int[1];
        mCallStatsMap.forEach((k, v) -> {
            mPulledAtoms.callStats[index[0]] = new PulledAtomsClass.CallStats();
            mPulledAtoms.callStats[index[0]].setCallDirection(k.mDirection);
            mPulledAtoms.callStats[index[0]].setExternalCall(k.mIsExternal);
            mPulledAtoms.callStats[index[0]].setEmergencyCall(k.mIsEmergency);
            mPulledAtoms.callStats[index[0]].setMultipleAudioAvailable(k.mIsMultipleAudioAvailable);
            mPulledAtoms.callStats[index[0]].setAccountType(k.mAccountType);
            mPulledAtoms.callStats[index[0]].setUid(k.mUid);
            mPulledAtoms.callStats[index[0]].setCount(v.mCount);
            mPulledAtoms.callStats[index[0]].setAverageDurationMs(v.mAverageDuration);
            index[0]++;
        });
        save(DELAY_FOR_PERSISTENT_MILLIS);
    }

    public void log(int direction, boolean isExternal, boolean isEmergency,
                    boolean isMultipleAudioAvailable, int accountType, int uid, int duration) {
        post(() -> {
            CallStatsKey key = new CallStatsKey(direction, isExternal, isEmergency,
                    isMultipleAudioAvailable, accountType, uid);
            CallStatsData data = mCallStatsMap.computeIfAbsent(key, k -> new CallStatsData(0, 0));
            data.add(duration);
            onAggregate();
        });
    }

    public void onCallStart(Call call) {
        post(() -> {
            if (mHasMultipleAudioDevices) {
                mOngoingCallsWithMultipleAudioDevices.add(call.getId());
            } else {
                mOngoingCallsWithoutMultipleAudioDevices.add(call.getId());
            }
        });
    }

    public void onCallEnd(Call call) {
        final int duration = (int) (call.getAgeMillis());
        post(() -> {
            final boolean hasMultipleAudioDevices = mOngoingCallsWithMultipleAudioDevices.remove(
                    call.getId());
            final int direction = call.isIncoming() ? CALL_STATS__CALL_DIRECTION__DIR_INCOMING
                    : (call.isOutgoing() ? CALL_STATS__CALL_DIRECTION__DIR_OUTGOING
                    : CALL_STATS__CALL_DIRECTION__DIR_UNKNOWN);
            final int accountType = getAccountType(call.getPhoneAccountFromHandle());
            final int uid = call.getAssociatedUser().getIdentifier();
            log(direction, call.isExternalCall(), call.isEmergencyCall(), hasMultipleAudioDevices,
                    accountType, uid, duration);
        });
    }

    private int getAccountType(PhoneAccount account) {
        if (account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)) {
            return account.hasCapabilities(
                    PhoneAccount.CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS)
                    ? CALL_STATS__ACCOUNT_TYPE__ACCOUNT_VOIP_API
                    : CALL_STATS__ACCOUNT_TYPE__ACCOUNT_SELFMANAGED;
        }
        if (account.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)) {
            return account.hasCapabilities(
                    PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                    ? CALL_STATS__ACCOUNT_TYPE__ACCOUNT_SIM
                    : CALL_STATS__ACCOUNT_TYPE__ACCOUNT_MANAGED;
        }
        return CALL_STATS__ACCOUNT_TYPE__ACCOUNT_UNKNOWN;
    }

    public void onAudioDevicesChange(boolean hasMultipleAudioDevices) {
        post(() -> {
            if (mHasMultipleAudioDevices != hasMultipleAudioDevices) {
                mHasMultipleAudioDevices = hasMultipleAudioDevices;
                if (mHasMultipleAudioDevices) {
                    mOngoingCallsWithMultipleAudioDevices.addAll(
                            mOngoingCallsWithoutMultipleAudioDevices);
                    mOngoingCallsWithoutMultipleAudioDevices.clear();
                }
            }
        });
    }

    static class CallStatsKey {
        final int mDirection;
        final boolean mIsExternal;
        final boolean mIsEmergency;
        final boolean mIsMultipleAudioAvailable;
        final int mAccountType;
        final int mUid;

        CallStatsKey(int direction, boolean isExternal, boolean isEmergency,
                     boolean isMultipleAudioAvailable, int accountType, int uid) {
            mDirection = direction;
            mIsExternal = isExternal;
            mIsEmergency = isEmergency;
            mIsMultipleAudioAvailable = isMultipleAudioAvailable;
            mAccountType = accountType;
            mUid = uid;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CallStatsKey obj)) {
                return false;
            }
            return this.mDirection == obj.mDirection && this.mIsExternal == obj.mIsExternal
                    && this.mIsEmergency == obj.mIsEmergency
                    && this.mIsMultipleAudioAvailable == obj.mIsMultipleAudioAvailable
                    && this.mAccountType == obj.mAccountType && this.mUid == obj.mUid;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDirection, mIsExternal, mIsEmergency, mIsMultipleAudioAvailable,
                    mAccountType, mUid);
        }

        @Override
        public String toString() {
            return "[CallStatsKey: mDirection=" + mDirection + ", mIsExternal=" + mIsExternal
                    + ", mIsEmergency=" + mIsEmergency + ", mIsMultipleAudioAvailable="
                    + mIsMultipleAudioAvailable + ", mAccountType=" + mAccountType + ", mUid="
                    + mUid + "]";
        }
    }

    static class CallStatsData {

        int mCount;
        int mAverageDuration;

        CallStatsData(int count, int averageDuration) {
            mCount = count;
            mAverageDuration = averageDuration;
        }

        void add(int duration) {
            mCount++;
            mAverageDuration += (duration - mAverageDuration) / mCount;
        }

        @Override
        public String toString() {
            return "[CallStatsData: mCount=" + mCount + ", mAverageDuration:" + mAverageDuration
                    + "]";
        }
    }
}
