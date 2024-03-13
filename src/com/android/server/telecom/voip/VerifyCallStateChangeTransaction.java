/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.telecom.voip;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.Call;
import com.android.server.telecom.TelecomSystem;

import android.telecom.Log;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * VerifyCallStateChangeTransaction is a transaction that verifies a CallState change and has
 * the ability to disconnect if the CallState is not changed within the timeout window.
 * <p>
 * Note: This transaction has a timeout of 2 seconds.
 */
public class VerifyCallStateChangeTransaction extends VoipCallTransaction {
    private static final String TAG = VerifyCallStateChangeTransaction.class.getSimpleName();
    private static final long CALL_STATE_TIMEOUT_MILLISECONDS = 2000L;
    private final Call mCall;
    private final int mTargetCallState;
    private final CompletableFuture<VoipCallTransactionResult> mTransactionResult =
            new CompletableFuture<>();

    private final Call.CallStateListener mCallStateListenerImpl = new Call.CallStateListener() {
        @Override
        public void onCallStateChanged(int newCallState) {
            Log.d(TAG, "newState=[%d], expectedState=[%d]", newCallState, mTargetCallState);
            if (newCallState == mTargetCallState) {
                mTransactionResult.complete(new VoipCallTransactionResult(
                        VoipCallTransactionResult.RESULT_SUCCEED, TAG));
            }
            // NOTE:: keep listening to the call state until the timeout is reached. It's possible
            // another call state is reached in between...
        }
    };

    public VerifyCallStateChangeTransaction(TelecomSystem.SyncRoot lock,  Call call,
            int targetCallState) {
        super(lock, CALL_STATE_TIMEOUT_MILLISECONDS);
        mCall = call;
        mTargetCallState = targetCallState;
    }

    @Override
    public CompletionStage<VoipCallTransactionResult> processTransaction(Void v) {
        Log.d(TAG, "processTransaction:");
        // It's possible the Call is already in the expected call state
        if (isNewCallStateTargetCallState()) {
            mTransactionResult.complete(new VoipCallTransactionResult(
                    VoipCallTransactionResult.RESULT_SUCCEED, TAG));
            return mTransactionResult;
        }
        mCall.addCallStateListener(mCallStateListenerImpl);
        return mTransactionResult;
    }

    @Override
    public void finishTransaction() {
        mCall.removeCallStateListener(mCallStateListenerImpl);
    }

    private boolean isNewCallStateTargetCallState() {
        return mCall.getState() == mTargetCallState;
    }

    @VisibleForTesting
    public CompletableFuture<VoipCallTransactionResult> getTransactionResult() {
        return mTransactionResult;
    }

    @VisibleForTesting
    public Call.CallStateListener getCallStateListenerImpl() {
        return mCallStateListenerImpl;
    }
}
