/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.telecom.CallException;

import com.android.server.telecom.LoggedHandlerExecutor;
import com.android.server.telecom.TelecomSystem;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A VoipCallTransaction implementation that its sub transactions will be executed in parallel
 */
public class ParallelTransaction extends VoipCallTransaction {
    public ParallelTransaction(List<VoipCallTransaction> subTransactions,
            TelecomSystem.SyncRoot lock) {
        super(subTransactions, lock);
    }

    @Override
    public void processTransactions() {
        if (mSubTransactions == null || mSubTransactions.isEmpty()) {
            scheduleTransaction();
            return;
        }
        TransactionManager.TransactionCompleteListener subTransactionListener =
                new TransactionManager.TransactionCompleteListener() {
                    private final AtomicInteger mCount = new AtomicInteger(mSubTransactions.size());

                    @Override
                    public void onTransactionCompleted(VoipCallTransactionResult result,
                            String transactionName) {
                        if (result.getResult() != VoipCallTransactionResult.RESULT_SUCCEED) {
                            CompletableFuture.completedFuture(null).thenApplyAsync(
                                    (x) -> {
                                        finish(result);
                                        mCompleteListener.onTransactionCompleted(result,
                                                mTransactionName);
                                        return null;
                                    }, new LoggedHandlerExecutor(mHandler,
                                            mTransactionName + "@" + hashCode()
                                                    + ".oTC", mLock));
                        } else {
                            if (mCount.decrementAndGet() == 0) {
                                scheduleTransaction();
                            }
                        }
                    }

                    @Override
                    public void onTransactionTimeout(String transactionName) {
                        CompletableFuture.completedFuture(null).thenApplyAsync(
                                (x) -> {
                                    VoipCallTransactionResult mainResult =
                                            new VoipCallTransactionResult(
                                                    CallException.CODE_OPERATION_TIMED_OUT,
                                            String.format("sub transaction %s timed out",
                                                    transactionName));
                                    finish(mainResult);
                                    mCompleteListener.onTransactionCompleted(mainResult,
                                            mTransactionName);
                                    return null;
                                }, new LoggedHandlerExecutor(mHandler,
                                        mTransactionName + "@" + hashCode()
                                                + ".oTT", mLock));
                    }
                };
        for (VoipCallTransaction transaction : mSubTransactions) {
            transaction.setCompleteListener(subTransactionListener);
            transaction.start();
        }
    }
}
