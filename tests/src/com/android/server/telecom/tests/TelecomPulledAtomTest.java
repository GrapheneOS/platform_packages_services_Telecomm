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
package com.android.server.telecom.tests;

import static com.android.server.telecom.AudioRoute.TYPE_BLUETOOTH_LE;
import static com.android.server.telecom.AudioRoute.TYPE_EARPIECE;
import static com.android.server.telecom.AudioRoute.TYPE_SPEAKER;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE;
import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__ACCOUNT_TYPE__ACCOUNT_SIM;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS__CALL_DIRECTION__DIR_INCOMING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.StatsManager;
import android.content.Context;
import android.os.Looper;
import android.os.UserHandle;
import android.telecom.PhoneAccount;
import android.util.StatsEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.telecom.AudioRoute;
import com.android.server.telecom.Call;
import com.android.server.telecom.PendingAudioRoute;
import com.android.server.telecom.metrics.ApiStats;
import com.android.server.telecom.metrics.AudioRouteStats;
import com.android.server.telecom.metrics.CallStats;
import com.android.server.telecom.metrics.ErrorStats;
import com.android.server.telecom.nano.PulledAtomsClass;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TelecomPulledAtomTest extends TelecomTestCase {
    private static final long MIN_PULL_INTERVAL_MILLIS = 23L * 60 * 60 * 1000;
    private static final long DEFAULT_TIMESTAMPS_MILLIS = 3000;
    private static final int DELAY_FOR_PERSISTENT_MILLIS = 30000;
    private static final int DELAY_TOLERANCE = 50;
    private static final int TEST_TIMEOUT = (int) AudioRouteStats.THRESHOLD_REVERT_MS + 1000;
    private static final String FILE_NAME_TEST_ATOM = "test_atom.pb";

    private static final int VALUE_ATOM_COUNT = 1;

    private static final int VALUE_UID = 10000 + 1;
    private static final int VALUE_API_ID = 1;
    private static final int VALUE_API_RESULT = 1;
    private static final int VALUE_API_COUNT = 1;

    private static final int VALUE_AUDIO_ROUTE_TYPE1 = 1;
    private static final int VALUE_AUDIO_ROUTE_TYPE2 = 2;
    private static final int VALUE_AUDIO_ROUTE_COUNT = 1;
    private static final int VALUE_AUDIO_ROUTE_LATENCY = 300;

    private static final int VALUE_CALL_DIRECTION = 1;
    private static final int VALUE_CALL_ACCOUNT_TYPE = 1;
    private static final int VALUE_CALL_COUNT = 1;
    private static final int VALUE_CALL_DURATION = 3000;

    private static final int VALUE_MODULE_ID = 1;
    private static final int VALUE_ERROR_ID = 1;
    private static final int VALUE_ERROR_COUNT = 1;

    @Rule
    public TemporaryFolder mTempFolder = new TemporaryFolder();
    @Mock
    FileOutputStream mFileOutputStream;
    @Mock
    PendingAudioRoute mMockPendingAudioRoute;
    @Mock
    AudioRoute mMockSourceRoute;
    @Mock
    AudioRoute mMockDestRoute;
    private File mTempFile;
    private Looper mLooper;
    private Context mSpyContext;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mSpyContext = spy(mContext);
        mLooper = Looper.getMainLooper();
        mTempFile = mTempFolder.newFile(FILE_NAME_TEST_ATOM);
        doReturn(mTempFile).when(mSpyContext).getFileStreamPath(anyString());
        doReturn(mFileOutputStream).when(mSpyContext).openFileOutput(anyString(), anyInt());
        doReturn(mMockSourceRoute).when(mMockPendingAudioRoute).getOrigRoute();
        doReturn(mMockDestRoute).when(mMockPendingAudioRoute).getDestRoute();
        doReturn(TYPE_EARPIECE).when(mMockSourceRoute).getType();
        doReturn(TYPE_BLUETOOTH_LE).when(mMockDestRoute).getType();
    }

    @After
    @Override
    public void tearDown() throws Exception {
        mTempFile.delete();
        super.tearDown();
    }

    @Test
    public void testNewPulledAtomsFromFileInvalid() throws Exception {
        mTempFile.delete();

        ApiStats apiStats = new ApiStats(mSpyContext, mLooper);

        assertNotNull(apiStats.mPulledAtoms);
        assertEquals(apiStats.mPulledAtoms.telecomApiStats.length, 0);

        AudioRouteStats audioRouteStats = new AudioRouteStats(mSpyContext, mLooper);

        assertNotNull(audioRouteStats.mPulledAtoms);
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 0);

        CallStats callStats = new CallStats(mSpyContext, mLooper);

        assertNotNull(callStats.mPulledAtoms);
        assertEquals(callStats.mPulledAtoms.callStats.length, 0);

        ErrorStats errorStats = new ErrorStats(mSpyContext, mLooper);

        assertNotNull(errorStats.mPulledAtoms);
        assertEquals(errorStats.mPulledAtoms.telecomErrorStats.length, 0);
    }

    @Test
    public void testNewPulledAtomsFromFileValid() throws Exception {
        createTestFileForApiStats(DEFAULT_TIMESTAMPS_MILLIS);
        ApiStats apiStats = new ApiStats(mSpyContext, mLooper);

        verifyTestDataForApiStats(apiStats.mPulledAtoms, DEFAULT_TIMESTAMPS_MILLIS);

        createTestFileForAudioRouteStats(DEFAULT_TIMESTAMPS_MILLIS);
        AudioRouteStats audioRouteStats = new AudioRouteStats(mSpyContext, mLooper);

        verifyTestDataForAudioRouteStats(audioRouteStats.mPulledAtoms, DEFAULT_TIMESTAMPS_MILLIS);

        createTestFileForCallStats(DEFAULT_TIMESTAMPS_MILLIS);
        CallStats callStats = new CallStats(mSpyContext, mLooper);

        verifyTestDataForCallStats(callStats.mPulledAtoms, DEFAULT_TIMESTAMPS_MILLIS);

        createTestFileForErrorStats(DEFAULT_TIMESTAMPS_MILLIS);
        ErrorStats errorStats = new ErrorStats(mSpyContext, mLooper);

        verifyTestDataForErrorStats(errorStats.mPulledAtoms, DEFAULT_TIMESTAMPS_MILLIS);
    }

    @Test
    public void testPullApiStatsLessThanMinPullIntervalShouldSkip() throws Exception {
        createTestFileForApiStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS / 2);
        ApiStats apiStats = spy(new ApiStats(mSpyContext, mLooper));
        final List<StatsEvent> data = new ArrayList<>();

        int result = apiStats.pull(data);

        assertEquals(StatsManager.PULL_SKIP, result);
        verify(apiStats, never()).onPull(any());
        assertEquals(data.size(), 0);
    }

    @Test
    public void testPullApiStatsGreaterThanMinPullIntervalShouldNotSkip() throws Exception {
        createTestFileForApiStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS - 1);
        ApiStats apiStats = spy(new ApiStats(mSpyContext, mLooper));
        final List<StatsEvent> data = new ArrayList<>();

        int result = apiStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(apiStats).onPull(eq(data));
        assertEquals(data.size(), apiStats.mPulledAtoms.telecomApiStats.length);
    }

    @Test
    public void testPullAudioRouteStatsLessThanMinPullIntervalShouldSkip() throws Exception {
        createTestFileForAudioRouteStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS / 2);
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper));
        final List<StatsEvent> data = new ArrayList<>();

        int result = audioRouteStats.pull(data);

        assertEquals(StatsManager.PULL_SKIP, result);
        verify(audioRouteStats, never()).onPull(any());
        assertEquals(data.size(), 0);
    }

    @Test
    public void testPullAudioRouteStatsGreaterThanMinPullIntervalShouldNotSkip() throws Exception {
        createTestFileForAudioRouteStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS - 1);
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper));
        final List<StatsEvent> data = new ArrayList<>();

        int result = audioRouteStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(audioRouteStats).onPull(eq(data));
        assertEquals(data.size(), audioRouteStats.mPulledAtoms.callAudioRouteStats.length);
    }

    @Test
    public void testPullCallStatsLessThanMinPullIntervalShouldSkip() throws Exception {
        createTestFileForCallStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS / 2);
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper));
        final List<StatsEvent> data = new ArrayList<>();

        int result = callStats.pull(data);

        assertEquals(StatsManager.PULL_SKIP, result);
        verify(callStats, never()).onPull(any());
        assertEquals(data.size(), 0);
    }

    @Test
    public void testPullCallStatsGreaterThanMinPullIntervalShouldNotSkip() throws Exception {
        createTestFileForCallStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS - 1);
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper));
        final List<StatsEvent> data = new ArrayList<>();

        int result = callStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(callStats).onPull(eq(data));
        assertEquals(data.size(), callStats.mPulledAtoms.callStats.length);
    }

    @Test
    public void testPullErrorStatsLessThanMinPullIntervalShouldSkip() throws Exception {
        createTestFileForErrorStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS / 2);
        ErrorStats errorStats = spy(new ErrorStats(mSpyContext, mLooper));
        final List<StatsEvent> data = new ArrayList<>();

        int result = errorStats.pull(data);

        assertEquals(StatsManager.PULL_SKIP, result);
        verify(errorStats, never()).onPull(any());
        assertEquals(data.size(), 0);
    }

    @Test
    public void testPullErrorStatsGreaterThanMinPullIntervalShouldNotSkip() throws Exception {
        createTestFileForErrorStats(System.currentTimeMillis() - MIN_PULL_INTERVAL_MILLIS - 1);
        ErrorStats errorStats = spy(new ErrorStats(mSpyContext, mLooper));
        final List<StatsEvent> data = new ArrayList<>();

        int result = errorStats.pull(data);

        assertEquals(StatsManager.PULL_SUCCESS, result);
        verify(errorStats).onPull(eq(data));
        assertEquals(data.size(), errorStats.mPulledAtoms.telecomErrorStats.length);
    }

    @Test
    public void testApiStatsLog() throws Exception {
        ApiStats apiStats = spy(new ApiStats(mSpyContext, mLooper));

        apiStats.log(VALUE_API_ID, VALUE_UID, VALUE_API_RESULT);
        waitForHandlerAction(apiStats, TEST_TIMEOUT);

        verify(apiStats, times(1)).onAggregate();
        verify(apiStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(apiStats.mPulledAtoms.telecomApiStats.length, 1);
        verifyMessageForApiStats(apiStats.mPulledAtoms.telecomApiStats[0], VALUE_API_ID,
                VALUE_UID, VALUE_API_RESULT, 1);

        apiStats.log(VALUE_API_ID, VALUE_UID, VALUE_API_RESULT);
        waitForHandlerAction(apiStats, TEST_TIMEOUT);

        verify(apiStats, times(2)).onAggregate();
        verify(apiStats, times(2)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(apiStats.mPulledAtoms.telecomApiStats.length, 1);
        verifyMessageForApiStats(apiStats.mPulledAtoms.telecomApiStats[0], VALUE_API_ID,
                VALUE_UID, VALUE_API_RESULT, 2);
    }

    @Test
    public void testAudioRouteStatsLog() throws Exception {
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper));

        audioRouteStats.log(VALUE_AUDIO_ROUTE_TYPE1, VALUE_AUDIO_ROUTE_TYPE2, true, false,
                VALUE_AUDIO_ROUTE_LATENCY);
        waitForHandlerAction(audioRouteStats, TEST_TIMEOUT);

        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                VALUE_AUDIO_ROUTE_TYPE1, VALUE_AUDIO_ROUTE_TYPE2, true, false, 1,
                VALUE_AUDIO_ROUTE_LATENCY);

        audioRouteStats.log(VALUE_AUDIO_ROUTE_TYPE1, VALUE_AUDIO_ROUTE_TYPE2, true, false,
                VALUE_AUDIO_ROUTE_LATENCY);
        waitForHandlerAction(audioRouteStats, TEST_TIMEOUT);

        verify(audioRouteStats, times(2)).onAggregate();
        verify(audioRouteStats, times(2)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                VALUE_AUDIO_ROUTE_TYPE1, VALUE_AUDIO_ROUTE_TYPE2, true, false, 2,
                VALUE_AUDIO_ROUTE_LATENCY);
    }

    @Test
    public void testAudioRouteStatsOnEnterThenExit() throws Exception {
        int latency = 500;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);
        audioRouteStats.onRouteExit(mMockPendingAudioRoute, true);
        waitForHandlerAction(audioRouteStats, 100);

        // Verify that the stats should not be saved before the revert threshold is expired
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));

        // Verify that the stats should be saved when the revert threshold is expired
        waitForHandlerActionDelayed(
                audioRouteStats, TEST_TIMEOUT, AudioRouteStats.THRESHOLD_REVERT_MS);

        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE,
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE, true, false, 1,
                latency);
    }

    @Test
    public void testAudioRouteStatsOnRevertToSourceInThreshold() throws Exception {
        int delay = 100;
        int latency = 500;
        int duration = 1000;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);
        audioRouteStats.onRouteExit(mMockPendingAudioRoute, true);
        waitForHandlerAction(audioRouteStats, delay);

        // Verify that the stats should not be saved before the revert threshold is expired
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));

        // Verify that the event should be saved as revert when routing back to the source before
        // the revert threshold is expired
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, duration);

        // Reverse the audio types
        doReturn(TYPE_BLUETOOTH_LE).when(mMockSourceRoute).getType();
        doReturn(TYPE_EARPIECE).when(mMockDestRoute).getType();

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerAction(audioRouteStats, delay);

        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE,
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE, true, true, 1,
                latency);
    }

    @Test
    public void testAudioRouteStatsOnRevertToSourceBeyondThreshold() throws Exception {
        int delay = 100;
        int latency = 500;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);
        audioRouteStats.onRouteExit(mMockPendingAudioRoute, true);
        waitForHandlerAction(audioRouteStats, delay);

        // Verify that the stats should not be saved before the revert threshold is expired
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));

        // Verify that the event should not be saved as revert when routing back to the source
        // after the revert threshold is expired
        waitForHandlerActionDelayed(
                audioRouteStats, TEST_TIMEOUT, AudioRouteStats.THRESHOLD_REVERT_MS);

        // Reverse the audio types
        doReturn(TYPE_BLUETOOTH_LE).when(mMockSourceRoute).getType();
        doReturn(TYPE_EARPIECE).when(mMockDestRoute).getType();

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerAction(audioRouteStats, delay);

        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE,
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE, true, false, 1,
                latency);
    }

    @Test
    public void testAudioRouteStatsOnRouteToAnotherDestInThreshold() throws Exception {
        int delay = 100;
        int latency = 500;
        int duration = 1000;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);
        audioRouteStats.onRouteExit(mMockPendingAudioRoute, true);
        waitForHandlerAction(audioRouteStats, delay);

        // Verify that the stats should not be saved before the revert threshold is expired
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));

        // Verify that the event should not be saved as  revert when routing to a type different
        // as the source before the revert threshold is expired
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, duration);

        AudioRoute dest2 = mock(AudioRoute.class);
        doReturn(TYPE_SPEAKER).when(dest2).getType();
        doReturn(dest2).when(mMockPendingAudioRoute).getDestRoute();

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerAction(audioRouteStats, delay);

        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(audioRouteStats.mPulledAtoms.callAudioRouteStats.length, 1);
        verifyMessageForAudioRouteStats(audioRouteStats.mPulledAtoms.callAudioRouteStats[0],
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_EARPIECE,
                CALL_AUDIO_ROUTE_STATS__ROUTE_SOURCE__CALL_AUDIO_BLUETOOTH_LE, true, false, 1,
                latency);
    }

    @Test
    public void testAudioRouteStatsOnMultipleEnterWithoutExit() throws Exception {
        int latency = 500;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);

        doReturn(mMockDestRoute).when(mMockPendingAudioRoute).getOrigRoute();
        AudioRoute dest2 = mock(AudioRoute.class);
        doReturn(TYPE_SPEAKER).when(dest2).getType();
        doReturn(dest2).when(mMockPendingAudioRoute).getDestRoute();
        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);

        // Verify that the stats should not be saved without exit
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));
    }

    @Test
    public void testAudioRouteStatsOnMultipleEnterWithExit() throws Exception {
        int latency = 500;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper));

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);
        audioRouteStats.onRouteExit(mMockPendingAudioRoute, true);
        waitForHandlerAction(audioRouteStats, 100);

        doReturn(mMockDestRoute).when(mMockPendingAudioRoute).getOrigRoute();
        AudioRoute dest2 = mock(AudioRoute.class);
        doReturn(TYPE_SPEAKER).when(dest2).getType();
        doReturn(dest2).when(mMockPendingAudioRoute).getDestRoute();
        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);

        // Verify that the stats should be saved after exit
        verify(audioRouteStats, times(1)).onAggregate();
        verify(audioRouteStats, times(1)).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));
    }

    @Test
    public void testAudioRouteStatsOnRouteToSameDestWithExit() throws Exception {
        int latency = 500;
        AudioRouteStats audioRouteStats = spy(new AudioRouteStats(mSpyContext, mLooper));
        doReturn(mMockSourceRoute).when(mMockPendingAudioRoute).getDestRoute();

        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);

        // Enter again to trigger the log
        AudioRoute dest2 = mock(AudioRoute.class);
        doReturn(TYPE_SPEAKER).when(dest2).getType();
        doReturn(dest2).when(mMockPendingAudioRoute).getDestRoute();
        audioRouteStats.onRouteEnter(mMockPendingAudioRoute);
        waitForHandlerActionDelayed(audioRouteStats, TEST_TIMEOUT, latency);

        // Verify that the stats should not be saved without exit
        verify(audioRouteStats, never()).onAggregate();
        verify(audioRouteStats, never()).save(anyInt());
        assertTrue(audioRouteStats.hasMessages(AudioRouteStats.EVENT_REVERT_THRESHOLD_EXPIRED));
    }

    @Test
    public void testCallStatsLog() throws Exception {
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper));

        callStats.log(VALUE_CALL_DIRECTION, false, false, true, VALUE_CALL_ACCOUNT_TYPE,
                VALUE_UID, VALUE_CALL_DURATION);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        verify(callStats, times(1)).onAggregate();
        verify(callStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(callStats.mPulledAtoms.callStats.length, 1);
        verifyMessageForCallStats(callStats.mPulledAtoms.callStats[0], VALUE_CALL_DIRECTION,
                false, false, true, VALUE_CALL_ACCOUNT_TYPE, VALUE_UID, 1, VALUE_CALL_DURATION);

        callStats.log(VALUE_CALL_DIRECTION, false, false, true, VALUE_CALL_ACCOUNT_TYPE,
                VALUE_UID, VALUE_CALL_DURATION);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        verify(callStats, times(2)).onAggregate();
        verify(callStats, times(2)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(callStats.mPulledAtoms.callStats.length, 1);
        verifyMessageForCallStats(callStats.mPulledAtoms.callStats[0], VALUE_CALL_DIRECTION,
                false, false, true, VALUE_CALL_ACCOUNT_TYPE, VALUE_UID, 2, VALUE_CALL_DURATION);
    }

    @Test
    public void testCallStatsOnStartThenEnd() throws Exception {
        int duration = 1000;
        UserHandle uh = UserHandle.of(UserHandle.USER_SYSTEM);
        PhoneAccount account = mock(PhoneAccount.class);
        Call call = mock(Call.class);
        doReturn(true).when(call).isIncoming();
        doReturn(account).when(call).getPhoneAccountFromHandle();
        doReturn((long) duration).when(call).getAgeMillis();
        doReturn(false).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_SELF_MANAGED));
        doReturn(true).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_CALL_PROVIDER));
        doReturn(true).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION));
        doReturn(uh).when(call).getAssociatedUser();
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper));

        callStats.onCallStart(call);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        callStats.onCallEnd(call);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        verify(callStats, times(1)).log(eq(CALL_STATS__CALL_DIRECTION__DIR_INCOMING),
                eq(false), eq(false), eq(false), eq(CALL_STATS__ACCOUNT_TYPE__ACCOUNT_SIM),
                eq(UserHandle.USER_SYSTEM), eq(duration));
    }

    @Test
    public void testCallStatsOnMultipleAudioDevices() throws Exception {
        int duration = 1000;
        UserHandle uh = UserHandle.of(UserHandle.USER_SYSTEM);
        PhoneAccount account = mock(PhoneAccount.class);
        Call call = mock(Call.class);
        doReturn(true).when(call).isIncoming();
        doReturn(account).when(call).getPhoneAccountFromHandle();
        doReturn((long) duration).when(call).getAgeMillis();
        doReturn(false).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_SELF_MANAGED));
        doReturn(true).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_CALL_PROVIDER));
        doReturn(true).when(account).hasCapabilities(eq(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION));
        doReturn(uh).when(call).getAssociatedUser();
        CallStats callStats = spy(new CallStats(mSpyContext, mLooper));

        callStats.onCallStart(call);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        callStats.onAudioDevicesChange(true);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        callStats.onCallEnd(call);
        waitForHandlerAction(callStats, TEST_TIMEOUT);

        verify(callStats, times(1)).log(eq(CALL_STATS__CALL_DIRECTION__DIR_INCOMING),
                eq(false), eq(false), eq(true), eq(CALL_STATS__ACCOUNT_TYPE__ACCOUNT_SIM),
                eq(UserHandle.USER_SYSTEM), eq(duration));
    }

    @Test
    public void testErrorStatsLog() throws Exception {
        ErrorStats errorStats = spy(new ErrorStats(mSpyContext, mLooper));

        errorStats.log(VALUE_MODULE_ID, VALUE_ERROR_ID);
        waitForHandlerAction(errorStats, TEST_TIMEOUT);

        verify(errorStats, times(1)).onAggregate();
        verify(errorStats, times(1)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(errorStats.mPulledAtoms.telecomErrorStats.length, 1);
        verifyMessageForErrorStats(errorStats.mPulledAtoms.telecomErrorStats[0], VALUE_MODULE_ID,
                VALUE_ERROR_ID, 1);

        errorStats.log(VALUE_MODULE_ID, VALUE_ERROR_ID);
        waitForHandlerAction(errorStats, TEST_TIMEOUT);

        verify(errorStats, times(2)).onAggregate();
        verify(errorStats, times(2)).save(eq(DELAY_FOR_PERSISTENT_MILLIS));
        assertEquals(errorStats.mPulledAtoms.telecomErrorStats.length, 1);
        verifyMessageForErrorStats(errorStats.mPulledAtoms.telecomErrorStats[0], VALUE_MODULE_ID,
                VALUE_ERROR_ID, 2);
    }

    private void createTestFileForApiStats(long timestamps) throws IOException {
        PulledAtomsClass.PulledAtoms atom = new PulledAtomsClass.PulledAtoms();
        atom.telecomApiStats =
                new PulledAtomsClass.TelecomApiStats[VALUE_ATOM_COUNT];
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            atom.telecomApiStats[i] = new PulledAtomsClass.TelecomApiStats();
            atom.telecomApiStats[i].setApiName(VALUE_API_ID + i);
            atom.telecomApiStats[i].setUid(VALUE_UID);
            atom.telecomApiStats[i].setApiResult(VALUE_API_RESULT);
            atom.telecomApiStats[i].setCount(VALUE_API_COUNT);
        }
        atom.setTelecomApiStatsPullTimestampMillis(timestamps);

        FileOutputStream stream = new FileOutputStream(mTempFile);
        stream.write(PulledAtomsClass.PulledAtoms.toByteArray(atom));
        stream.close();
    }

    private void verifyTestDataForApiStats(final PulledAtomsClass.PulledAtoms atom,
                                           long timestamps) {
        assertNotNull(atom);
        assertEquals(atom.getTelecomApiStatsPullTimestampMillis(), timestamps);
        assertNotNull(atom.telecomApiStats);
        assertEquals(atom.telecomApiStats.length, VALUE_ATOM_COUNT);
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            assertNotNull(atom.telecomApiStats[i]);
            verifyMessageForApiStats(atom.telecomApiStats[i], VALUE_API_ID + i, VALUE_UID,
                    VALUE_API_RESULT, VALUE_API_COUNT);
        }
    }

    private void verifyMessageForApiStats(final PulledAtomsClass.TelecomApiStats msg, int apiId,
                                          int uid, int result, int count) {
        assertEquals(msg.getApiName(), apiId);
        assertEquals(msg.getUid(), uid);
        assertEquals(msg.getApiResult(), result);
        assertEquals(msg.getCount(), count);
    }

    private void createTestFileForAudioRouteStats(long timestamps) throws IOException {
        PulledAtomsClass.PulledAtoms atom = new PulledAtomsClass.PulledAtoms();
        atom.callAudioRouteStats =
                new PulledAtomsClass.CallAudioRouteStats[VALUE_ATOM_COUNT];
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            atom.callAudioRouteStats[i] = new PulledAtomsClass.CallAudioRouteStats();
            atom.callAudioRouteStats[i].setCallAudioRouteSource(VALUE_AUDIO_ROUTE_TYPE1);
            atom.callAudioRouteStats[i].setCallAudioRouteDest(VALUE_AUDIO_ROUTE_TYPE2);
            atom.callAudioRouteStats[i].setSuccess(true);
            atom.callAudioRouteStats[i].setRevert(false);
            atom.callAudioRouteStats[i].setCount(VALUE_AUDIO_ROUTE_COUNT);
            atom.callAudioRouteStats[i].setAverageLatencyMs(VALUE_AUDIO_ROUTE_LATENCY);
        }
        atom.setCallAudioRouteStatsPullTimestampMillis(timestamps);
        FileOutputStream stream = new FileOutputStream(mTempFile);
        stream.write(PulledAtomsClass.PulledAtoms.toByteArray(atom));
        stream.close();
    }

    private void verifyTestDataForAudioRouteStats(final PulledAtomsClass.PulledAtoms atom,
                                                  long timestamps) {
        assertNotNull(atom);
        assertEquals(atom.getCallAudioRouteStatsPullTimestampMillis(), timestamps);
        assertNotNull(atom.callAudioRouteStats);
        assertEquals(atom.callAudioRouteStats.length, VALUE_ATOM_COUNT);
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            assertNotNull(atom.callAudioRouteStats[i]);
            verifyMessageForAudioRouteStats(atom.callAudioRouteStats[i], VALUE_AUDIO_ROUTE_TYPE1,
                    VALUE_AUDIO_ROUTE_TYPE2, true, false, VALUE_AUDIO_ROUTE_COUNT,
                    VALUE_AUDIO_ROUTE_LATENCY);
        }
    }

    private void verifyMessageForAudioRouteStats(
            final PulledAtomsClass.CallAudioRouteStats msg, int source, int dest, boolean success,
            boolean revert, int count, int latency) {
        assertEquals(msg.getCallAudioRouteSource(), source);
        assertEquals(msg.getCallAudioRouteDest(), dest);
        assertEquals(msg.getSuccess(), success);
        assertEquals(msg.getRevert(), revert);
        assertEquals(msg.getCount(), count);
        assertTrue(Math.abs(latency - msg.getAverageLatencyMs()) < DELAY_TOLERANCE);
    }

    private void createTestFileForCallStats(long timestamps) throws IOException {
        PulledAtomsClass.PulledAtoms atom = new PulledAtomsClass.PulledAtoms();
        atom.callStats =
                new PulledAtomsClass.CallStats[VALUE_ATOM_COUNT];
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            atom.callStats[i] = new PulledAtomsClass.CallStats();
            atom.callStats[i].setCallDirection(VALUE_CALL_DIRECTION);
            atom.callStats[i].setExternalCall(false);
            atom.callStats[i].setEmergencyCall(false);
            atom.callStats[i].setMultipleAudioAvailable(false);
            atom.callStats[i].setAccountType(VALUE_CALL_ACCOUNT_TYPE);
            atom.callStats[i].setUid(VALUE_UID);
            atom.callStats[i].setCount(VALUE_CALL_COUNT);
            atom.callStats[i].setAverageDurationMs(VALUE_CALL_DURATION);
        }
        atom.setCallStatsPullTimestampMillis(timestamps);
        FileOutputStream stream = new FileOutputStream(mTempFile);
        stream.write(PulledAtomsClass.PulledAtoms.toByteArray(atom));
        stream.close();
    }

    private void verifyTestDataForCallStats(final PulledAtomsClass.PulledAtoms atom,
                                            long timestamps) {
        assertNotNull(atom);
        assertEquals(atom.getCallStatsPullTimestampMillis(), timestamps);
        assertNotNull(atom.callStats);
        assertEquals(atom.callStats.length, VALUE_ATOM_COUNT);
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            assertNotNull(atom.callStats[i]);
            verifyMessageForCallStats(atom.callStats[i], VALUE_CALL_DIRECTION, false, false,
                    false, VALUE_CALL_ACCOUNT_TYPE, VALUE_UID, VALUE_CALL_COUNT,
                    VALUE_CALL_DURATION);
        }
    }

    private void verifyMessageForCallStats(final PulledAtomsClass.CallStats msg,
            int direction, boolean external, boolean emergency, boolean multipleAudio,
            int accountType, int uid, int count, int duration) {
        assertEquals(msg.getCallDirection(), direction);
        assertEquals(msg.getExternalCall(), external);
        assertEquals(msg.getEmergencyCall(), emergency);
        assertEquals(msg.getMultipleAudioAvailable(), multipleAudio);
        assertEquals(msg.getAccountType(), accountType);
        assertEquals(msg.getUid(), uid);
        assertEquals(msg.getCount(), count);
        assertEquals(msg.getAverageDurationMs(), duration);
    }

    private void createTestFileForErrorStats(long timestamps) throws IOException {
        PulledAtomsClass.PulledAtoms atom = new PulledAtomsClass.PulledAtoms();
        atom.telecomErrorStats =
                new PulledAtomsClass.TelecomErrorStats[VALUE_ATOM_COUNT];
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            atom.telecomErrorStats[i] = new PulledAtomsClass.TelecomErrorStats();
            atom.telecomErrorStats[i].setSubmoduleName(VALUE_MODULE_ID);
            atom.telecomErrorStats[i].setErrorName(VALUE_ERROR_ID);
            atom.telecomErrorStats[i].setCount(VALUE_ERROR_COUNT);
        }
        atom.setTelecomErrorStatsPullTimestampMillis(timestamps);
        FileOutputStream stream = new FileOutputStream(mTempFile);
        stream.write(PulledAtomsClass.PulledAtoms.toByteArray(atom));
        stream.close();
    }

    private void verifyTestDataForErrorStats(
            final PulledAtomsClass.PulledAtoms atom, long timestamps) {
        assertNotNull(atom);
        assertEquals(atom.getTelecomErrorStatsPullTimestampMillis(), timestamps);
        assertNotNull(atom.telecomErrorStats);
        assertEquals(atom.telecomErrorStats.length, VALUE_ATOM_COUNT);
        for (int i = 0; i < VALUE_ATOM_COUNT; i++) {
            assertNotNull(atom.telecomErrorStats[i]);
            verifyMessageForErrorStats(atom.telecomErrorStats[i], VALUE_MODULE_ID, VALUE_ERROR_ID
                    , VALUE_ERROR_COUNT);
        }
    }

    private void verifyMessageForErrorStats(final PulledAtomsClass.TelecomErrorStats msg,
            int moduleId, int errorId, int count) {
        assertEquals(msg.getSubmoduleName(), moduleId);
        assertEquals(msg.getErrorName(), errorId);
        assertEquals(msg.getCount(), count);
    }
}
