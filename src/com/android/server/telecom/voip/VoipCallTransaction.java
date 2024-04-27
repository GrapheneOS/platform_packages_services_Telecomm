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

import android.os.Handler;
import android.os.HandlerThread;
import android.telecom.CallException;
import android.telecom.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.LoggedHandlerExecutor;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.flags.Flags;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class VoipCallTransaction {
    //TODO: add log events
    private static final long DEFAULT_TRANSACTION_TIMEOUT_MS = 5000L;

    /**
     * Tracks stats about a transaction for logging purposes.
     */
    public static class Stats {
        // the logging visible timestamp for ease of debugging
        public final LocalDateTime addedTimeStamp;
        // the time in nS that the transaction was first created
        private final long mCreatedTimeNs;
        // the time that the transaction was started.
        private long mStartedTimeNs = -1L;
        // the time that the transaction was finished.
        private long mFinishedTimeNs = -1L;
        // If finished, did this transaction finish because it timed out?
        private boolean mIsTimedOut = false;
        private VoipCallTransactionResult  mTransactionResult = null;

        public Stats() {
            addedTimeStamp = LocalDateTime.now();
            mCreatedTimeNs = System.nanoTime();
        }

        /**
         * Mark the transaction as started and record the time.
         */
        public void markStarted() {
            if (mStartedTimeNs > -1) return;
            mStartedTimeNs = System.nanoTime();
        }

        /**
         * Mark the transaction as completed and record the time.
         */
        public void markComplete(boolean isTimedOut, VoipCallTransactionResult result) {
            if (mFinishedTimeNs > -1) return;
            mFinishedTimeNs = System.nanoTime();
            mIsTimedOut = isTimedOut;
            mTransactionResult = result;
        }

        /**
         * @return Time in mS since the transaction was created.
         */
        public long measureTimeSinceCreatedMs() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - mCreatedTimeNs);
        }

        /**
         * @return Time in mS between when transaction was created and when it was marked as
         * started. Returns -1 if the transaction was not started yet.
         */
        public long measureCreatedToStartedMs() {
            return mStartedTimeNs > 0 ?
                    TimeUnit.NANOSECONDS.toMillis(mStartedTimeNs - mCreatedTimeNs) : -1;
        }

        /**
         * @return Time in mS since the transaction was marked started to the TransactionManager.
         * Returns -1 if the transaction hasn't been started yet.
         */
        public long measureTimeSinceStartedMs() {
            return mStartedTimeNs > 0 ?
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - mStartedTimeNs) : -1;
        }

        /**
         * @return Time in mS between when the transaction was marked as started and when it was
         * marked as completed. Returns -1 if the transaction hasn't started or finished yet.
         */
        public long measureStartedToCompletedMs() {
            return (mStartedTimeNs > 0 && mFinishedTimeNs > 0) ?
                    TimeUnit.NANOSECONDS.toMillis(mFinishedTimeNs - mStartedTimeNs) : -1;

        }

        /**
         * @return true if this transaction completed due to timing out, false if the transaction
         * hasn't completed yet or it completed and did not time out.
         */
        public boolean isTimedOut() {
            return mIsTimedOut;
        }

        /**
         * @return the result if the transaction completed, null if it timed out or hasn't completed
         * yet.
         */
        public VoipCallTransactionResult getTransactionResult() {
            return mTransactionResult;
        }
    }

    protected final AtomicBoolean mCompleted = new AtomicBoolean(false);
    protected final String mTransactionName = this.getClass().getSimpleName();
    private final HandlerThread mHandlerThread;
    protected final Handler mHandler;
    protected TransactionManager.TransactionCompleteListener mCompleteListener;
    protected final List<VoipCallTransaction> mSubTransactions;
    protected final TelecomSystem.SyncRoot mLock;
    protected final long mTransactionTimeoutMs;
    protected final Stats mStats;

    public VoipCallTransaction(
            List<VoipCallTransaction> subTransactions, TelecomSystem.SyncRoot lock,
            long timeoutMs) {
        mSubTransactions = subTransactions;
        mHandlerThread = new HandlerThread(this.toString());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mLock = lock;
        mTransactionTimeoutMs = timeoutMs;
        mStats = Flags.enableCallSequencing() ? new Stats() : null;
    }

    public VoipCallTransaction(List<VoipCallTransaction> subTransactions,
            TelecomSystem.SyncRoot lock) {
        this(subTransactions, lock, DEFAULT_TRANSACTION_TIMEOUT_MS);
    }
    public VoipCallTransaction(TelecomSystem.SyncRoot lock, long timeoutMs) {
        this(null /* mSubTransactions */, lock, timeoutMs);
    }

    public VoipCallTransaction(TelecomSystem.SyncRoot lock) {
        this(null /* mSubTransactions */, lock);
    }

    public final void start() {
        if (mStats != null) mStats.markStarted();
        // post timeout work
        CompletableFuture<Void> future = new CompletableFuture<>();
        mHandler.postDelayed(() -> future.complete(null), mTransactionTimeoutMs);
        future.thenApplyAsync((x) -> {
            timeout();
            return null;
        }, new LoggedHandlerExecutor(mHandler, mTransactionName + "@" + hashCode()
                + ".s", mLock));

        processTransactions();
    }

    /**
     * By default, this processes this transaction. For VoipCallTransactions with sub-transactions,
     * this implementation should be overwritten to handle also processing sub-transactions.
     */
    protected void processTransactions() {
        scheduleTransaction();
    }

    /**
     * This method is called when the transaction has finished either successfully or exceptionally.
     * VoipCallTransactions that are extending this class should override this method to clean up
     * any leftover state.
     */
    protected void finishTransaction() {

    }

    protected final void scheduleTransaction() {
        LoggedHandlerExecutor executor = new LoggedHandlerExecutor(mHandler,
                mTransactionName + "@" + hashCode() + ".sT", mLock);
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        future.thenComposeAsync(this::processTransaction, executor)
                .thenApplyAsync((Function<VoipCallTransactionResult, Void>) result -> {
                    notifyListenersOfResult(result);
                    return null;
                }, executor)
                .exceptionally((throwable -> {
                    // Do NOT wait for the timeout in order to finish this failed transaction.
                    // Instead, propagate the failure to the other transactions immediately!
                    String errorMessage = throwable != null ? throwable.getMessage() :
                            "encountered an exception while processing " + mTransactionName;
                    notifyListenersOfResult(new VoipCallTransactionResult(
                            CallException.CODE_ERROR_UNKNOWN, errorMessage));
                    Log.e(this, throwable, "Error while executing transaction.");
                    return null;
                }));
    }

    protected void notifyListenersOfResult(VoipCallTransactionResult result){
        mCompleted.set(true);
        finish(result);
        if (mCompleteListener != null) {
            mCompleteListener.onTransactionCompleted(result, mTransactionName);
        }
    }

    protected CompletionStage<VoipCallTransactionResult> processTransaction(Void v) {
        return CompletableFuture.completedFuture(
                new VoipCallTransactionResult(VoipCallTransactionResult.RESULT_SUCCEED, null));
    }

    public final void setCompleteListener(TransactionManager.TransactionCompleteListener listener) {
        mCompleteListener = listener;
    }

    @VisibleForTesting
    public final void timeout() {
        if (mCompleted.getAndSet(true)) {
            return;
        }
        finish(true, null);
        if (mCompleteListener != null) {
            mCompleteListener.onTransactionTimeout(mTransactionName);
        }
    }

    @VisibleForTesting
    public final Handler getHandler() {
        return mHandler;
    }

    public final void finish(VoipCallTransactionResult result) {
        finish(false, result);
    }

    private void finish(boolean isTimedOut, VoipCallTransactionResult result) {
        if (mStats != null) mStats.markComplete(isTimedOut, result);
        finishTransaction();
        // finish all sub transactions
        if (mSubTransactions != null && !mSubTransactions.isEmpty()) {
            mSubTransactions.forEach( t -> t.finish(isTimedOut, result));
        }
        mHandlerThread.quitSafely();
    }

    /**
     * @return Stats related to this transaction if stats are enabled, null otherwise.
     */
    public final Stats getStats() {
        return mStats;
    }
}
