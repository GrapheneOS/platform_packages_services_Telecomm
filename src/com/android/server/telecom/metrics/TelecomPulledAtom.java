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

import android.annotation.NonNull;
import android.app.StatsManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telecom.Log;
import android.util.StatsEvent;

import androidx.annotation.VisibleForTesting;

import com.android.server.telecom.nano.PulledAtomsClass.PulledAtoms;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.List;

public abstract class TelecomPulledAtom extends Handler {
    /**
     * Min interval to persist the data.
     */
    protected static final int DELAY_FOR_PERSISTENT_MILLIS = 30000;
    protected static final int EVENT_SUB_BASE = 1000;
    private static final String TAG = TelecomPulledAtom.class.getSimpleName();
    private static final long MIN_PULL_INTERVAL_MILLIS = 23L * 60 * 60 * 1000;
    private static final int EVENT_SAVE = 1;
    private final Context mContext;
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public PulledAtoms mPulledAtoms;
    protected long mLastPulledTimestamps;

    protected TelecomPulledAtom(@NonNull Context context, @NonNull Looper looper) {
        super(looper);
        mContext = context;
        mPulledAtoms = loadAtomsFromFile();
        onLoad();
    }

    public synchronized int pull(final List<StatsEvent> data) {
        long cur = System.currentTimeMillis();
        if (cur - mLastPulledTimestamps < MIN_PULL_INTERVAL_MILLIS) {
            return StatsManager.PULL_SKIP;
        }
        mLastPulledTimestamps = cur;
        return onPull(data);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    abstract public int getTag();

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public abstract int onPull(List<StatsEvent> data);

    protected abstract void onLoad();

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public abstract void onAggregate();

    public void onFlush() {
        save(0);
    }

    protected abstract String getFileName();

    private synchronized PulledAtoms loadAtomsFromFile() {
        try {
            return
                    PulledAtoms.parseFrom(
                            Files.readAllBytes(mContext.getFileStreamPath(getFileName()).toPath()));
        } catch (NoSuchFileException e) {
            Log.e(TAG, e, "the atom file not found");
        } catch (IOException | NullPointerException e) {
            Log.e(TAG, e, "cannot load/parse the atom file");
        }
        return makeNewPulledAtoms();
    }

    protected synchronized void clearAtoms() {
        mPulledAtoms = makeNewPulledAtoms();
    }

    private synchronized void onSave() {
        try (FileOutputStream stream = mContext.openFileOutput(getFileName(),
                Context.MODE_PRIVATE)) {
            Log.d(TAG, "save " + getTag());
            stream.write(PulledAtoms.toByteArray(mPulledAtoms));
        } catch (IOException e) {
            Log.e(TAG, e, "cannot save the atom to file");
        } catch (UnsupportedOperationException e) {
            Log.e(TAG, e, "cannot open the file");
        }
    }

    private PulledAtoms makeNewPulledAtoms() {
        return new PulledAtoms();
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public void save(int delayMillis) {
        if (delayMillis > 0) {
            if (!hasMessages(EVENT_SAVE)) {
                sendMessageDelayed(obtainMessage(EVENT_SAVE), delayMillis);
            }
        } else {
            onSave();
        }
    }

    @Override
    public void handleMessage(Message msg) {
        if (msg.what == EVENT_SAVE) {
            onSave();
        }
    }
}
