/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.telecom.CallAttributes;
import android.telecom.CallEndpoint;
import android.telecom.CallerInfo;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.ParcelableConference;
import android.telecom.ParcelableConnection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.StatusHints;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.CallQuality;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.server.telecom.CachedAvailableEndpointsChange;
import com.android.server.telecom.CachedCallEventQueue;
import com.android.server.telecom.CachedCurrentEndpointChange;
import com.android.server.telecom.CachedMuteStateChange;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallIdMapper;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallerInfoLookupHelper;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ClockProxy;
import com.android.server.telecom.ConnectionServiceWrapper;
import com.android.server.telecom.EmergencyCallHelper;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.PhoneNumberUtilsAdapter;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.TransactionalServiceWrapper;
import com.android.server.telecom.ui.ToastFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class CallTest extends TelecomTestCase {
    private static final Uri TEST_ADDRESS = Uri.parse("tel:555-1212");
    private static final ComponentName COMPONENT_NAME_1 = ComponentName
            .unflattenFromString("com.foo/.Blah");
    private static final ComponentName COMPONENT_NAME_2 = ComponentName
            .unflattenFromString("com.bar/.Blah");
    private static final PhoneAccountHandle SIM_1_HANDLE = new PhoneAccountHandle(
            COMPONENT_NAME_1, "Sim1");
    private static final PhoneAccount SIM_1_ACCOUNT = new PhoneAccount.Builder(SIM_1_HANDLE, "Sim1")
            .setCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION
                    | PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .setIsEnabled(true)
            .build();
    private static final long TIMEOUT_MILLIS = 1000;

    @Mock private CallsManager mMockCallsManager;
    @Mock private CallerInfoLookupHelper mMockCallerInfoLookupHelper;
    @Mock private PhoneAccountRegistrar mMockPhoneAccountRegistrar;
    @Mock private ClockProxy mMockClockProxy;
    @Mock private ToastFactory mMockToastProxy;
    @Mock private PhoneNumberUtilsAdapter mMockPhoneNumberUtilsAdapter;
    @Mock private ConnectionServiceWrapper mMockConnectionService;
    @Mock private TransactionalServiceWrapper mMockTransactionalService;

    private final TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() { };

    @Before
    public void setUp() throws Exception {
        super.setUp();
        doReturn(mMockCallerInfoLookupHelper).when(mMockCallsManager).getCallerInfoLookupHelper();
        doReturn(mMockPhoneAccountRegistrar).when(mMockCallsManager).getPhoneAccountRegistrar();
        doReturn(0L).when(mMockClockProxy).elapsedRealtime();
        doReturn(SIM_1_ACCOUNT).when(mMockPhoneAccountRegistrar).getPhoneAccountUnchecked(
                eq(SIM_1_HANDLE));
        doReturn(new ComponentName(mContext, CallTest.class))
                .when(mMockConnectionService).getComponentName();
        doReturn(UserHandle.CURRENT).when(mMockCallsManager).getCurrentUserHandle();
        Resources mockResources = mContext.getResources();
        when(mockResources.getBoolean(R.bool.skip_loading_canned_text_response))
                .thenReturn(false);
        when(mockResources.getString(R.string.skip_incoming_caller_info_account_package))
                .thenReturn("");
        EmergencyCallHelper helper = mock(EmergencyCallHelper.class);
        doReturn(helper).when(mMockCallsManager).getEmergencyCallHelper();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    @SmallTest
    public void testSetHasGoneActive() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        assertFalse(call.hasGoneActiveBefore());
        call.setState(CallState.ACTIVE, "");
        assertTrue(call.hasGoneActiveBefore());
        call.setState(CallState.AUDIO_PROCESSING, "");
        assertTrue(call.hasGoneActiveBefore());
    }

    /**
     * Verify that transactional calls remap the [CallAttributes#CallCapability]s to
     * Connection capabilities.
     */
    @Test
    @SmallTest
    public void testTransactionalCallCapabilityRemapping() {
        // ensure when the flag is disabled, the old behavior is unchanged
        Bundle disabledFlagExtras = new Bundle();
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        disabledFlagExtras.putInt(CallAttributes.CALL_CAPABILITIES_KEY,
                Connection.CAPABILITY_MERGE_CONFERENCE);
        when(mFeatureFlags.remapTransactionalCapabilities()).thenReturn(false);
        call.setTransactionalCapabilities(disabledFlagExtras);
        assertTrue(call.can(Connection.CAPABILITY_MERGE_CONFERENCE));
        // enable the bug fix flag and ensure the transactional capabilities are remapped
        Bundle enabledFlagExtras = new Bundle();
        Call call2 = createCall("2", Call.CALL_DIRECTION_INCOMING);
        enabledFlagExtras.putInt(CallAttributes.CALL_CAPABILITIES_KEY,
                CallAttributes.SUPPORTS_SET_INACTIVE);
        when(mFeatureFlags.remapTransactionalCapabilities()).thenReturn(true);
        call2.setTransactionalCapabilities(enabledFlagExtras);
        assertTrue(call2.can(Connection.CAPABILITY_HOLD));
        assertTrue(call2.can(Connection.CAPABILITY_SUPPORT_HOLD));
    }

    /**
     * Verify Call#setVideoState will only upgrade to video if the PhoneAccount supports video
     * state capabilities
     */
    @Test
    @SmallTest
    public void testSetVideoStateForTransactionalCalls() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        call.setIsTransactionalCall(true);
        call.setTransactionServiceWrapper(tsw);
        assertTrue(call.isTransactionalCall());
        assertNotNull(call.getTransactionServiceWrapper());
        when(mFeatureFlags.transactionalVideoState()).thenReturn(true);

        // VoIP apps using transactional APIs must register a PhoneAccount that supports
        // video calling capabilities or the video state will be defaulted to audio
        assertFalse(call.isVideoCallingSupportedByPhoneAccount());
        call.setVideoState(VideoProfile.STATE_BIDIRECTIONAL);
        assertEquals(VideoProfile.STATE_AUDIO_ONLY, call.getVideoState());

        call.setVideoCallingSupportedByPhoneAccount(true);
        assertTrue(call.isVideoCallingSupportedByPhoneAccount());

        // After the PhoneAccount signals it supports video calling, video state changes can occur
        call.setVideoState(VideoProfile.STATE_BIDIRECTIONAL);
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, call.getVideoState());
        verify(tsw, times(1)).onVideoStateChanged(call, CallAttributes.VIDEO_CALL);
    }

    /**
     * Verify all video state changes are echoed out to the TransactionalServiceWrapper
     */
    @Test
    @SmallTest
    public void testToggleTransactionalVideoState() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        call.setIsTransactionalCall(true);
        call.setTransactionServiceWrapper(tsw);
        call.setVideoCallingSupportedByPhoneAccount(true);
        assertTrue(call.isTransactionalCall());
        assertNotNull(call.getTransactionServiceWrapper());
        assertTrue(call.isVideoCallingSupportedByPhoneAccount());
        when(mFeatureFlags.transactionalVideoState()).thenReturn(true);

        call.setVideoState(VideoProfile.STATE_BIDIRECTIONAL);
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, call.getVideoState());
        verify(tsw, times(1)).onVideoStateChanged(call, CallAttributes.VIDEO_CALL);

        call.setVideoState(VideoProfile.STATE_BIDIRECTIONAL);
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, call.getVideoState());
        verify(tsw, times(2)).onVideoStateChanged(call, CallAttributes.VIDEO_CALL);

        call.setVideoState(VideoProfile.STATE_AUDIO_ONLY);
        assertEquals(VideoProfile.STATE_AUDIO_ONLY, call.getVideoState());
        verify(tsw, times(1)).onVideoStateChanged(call, CallAttributes.AUDIO_CALL);

        call.setVideoState(VideoProfile.STATE_BIDIRECTIONAL);
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, call.getVideoState());
        verify(tsw, times(3)).onVideoStateChanged(call, CallAttributes.VIDEO_CALL);
    }

    @Test
    public void testMultipleCachedCallEvents() {
        when(mFeatureFlags.cacheCallAudioCallbacks()).thenReturn(true);
        when(mFeatureFlags.cacheCallEvents()).thenReturn(true);
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        assertNull(call.getTransactionServiceWrapper());

        String testEvent1 = "test1";
        Bundle testBundle1 = new Bundle();
        testBundle1.putInt("testKey", 1);
        call.sendCallEvent(testEvent1, testBundle1);
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedCallEventQueue.ID).size());

        String testEvent2 = "test2";
        Bundle testBundle2 = new Bundle();
        testBundle2.putInt("testKey", 2);
        call.sendCallEvent(testEvent2, testBundle2);
        assertEquals(2,
                call.getCachedServiceCallbacksCopy().get(CachedCallEventQueue.ID).size());

        String testEvent3 = "test3";
        Bundle testBundle3 = new Bundle();
        testBundle2.putInt("testKey", 3);
        call.sendCallEvent(testEvent3, testBundle3);
        assertEquals(3,
                call.getCachedServiceCallbacksCopy().get(CachedCallEventQueue.ID).size());

        verify(tsw, times(0)).sendCallEvent(any(), any(), any());
        call.setTransactionServiceWrapper(tsw);
        verify(tsw, times(1)).sendCallEvent(any(), eq(testEvent1), eq(testBundle1));
        verify(tsw, times(1)).sendCallEvent(any(), eq(testEvent2), eq(testBundle2));
        verify(tsw, times(1)).sendCallEvent(any(), eq(testEvent3), eq(testBundle3));
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    @Test
    public void testMultipleCachedMuteStateChanges() {
        when(mFeatureFlags.cacheCallAudioCallbacks()).thenReturn(true);
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        assertNull(call.getTransactionServiceWrapper());

        call.cacheServiceCallback(new CachedMuteStateChange(true));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedMuteStateChange.ID).size());

        call.cacheServiceCallback(new CachedMuteStateChange(false));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedMuteStateChange.ID).size());

        CachedMuteStateChange currentCacheMuteState = (CachedMuteStateChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedMuteStateChange.ID)
                .getLast();

        assertFalse(currentCacheMuteState.isMuted());

        call.setTransactionServiceWrapper(tsw);
        verify(tsw, times(1)).onMuteStateChanged(any(), eq(false));
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    @Test
    public void testCacheAfterServiceSet() {
        when(mFeatureFlags.cacheCallAudioCallbacks()).thenReturn(true);
        when(mFeatureFlags.cacheCallEvents()).thenReturn(true);
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        assertNull(call.getTransactionServiceWrapper());
        call.setTransactionServiceWrapper(tsw);
        call.cacheServiceCallback(new CachedMuteStateChange(true));
        // Ensure that we do not lose events if for some reason a CachedCallback is cached after
        // the service is set
        verify(tsw, times(1)).onMuteStateChanged(any(), eq(true));
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    @Test
    public void testMultipleCachedCurrentEndpointChanges() {
        when(mFeatureFlags.cacheCallAudioCallbacks()).thenReturn(true);
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        CallEndpoint earpiece = Mockito.mock(CallEndpoint.class);
        CallEndpoint speaker = Mockito.mock(CallEndpoint.class);
        when(earpiece.getEndpointType()).thenReturn(CallEndpoint.TYPE_EARPIECE);
        when(speaker.getEndpointType()).thenReturn(CallEndpoint.TYPE_SPEAKER);

        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        assertNull(call.getTransactionServiceWrapper());

        call.cacheServiceCallback(new CachedCurrentEndpointChange(earpiece));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedCurrentEndpointChange.ID).size());

        call.cacheServiceCallback(new CachedCurrentEndpointChange(speaker));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedCurrentEndpointChange.ID).size());

        CachedCurrentEndpointChange currentEndpointChange = (CachedCurrentEndpointChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedCurrentEndpointChange.ID)
                .getLast();

        assertEquals(CallEndpoint.TYPE_SPEAKER,
                currentEndpointChange.getCurrentCallEndpoint().getEndpointType());

        call.setTransactionServiceWrapper(tsw);
        verify(tsw, times(1)).onCallEndpointChanged(any(), any());
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    @Test
    public void testMultipleCachedAvailableEndpointChanges() {
        when(mFeatureFlags.cacheCallAudioCallbacks()).thenReturn(true);
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        CallEndpoint earpiece = Mockito.mock(CallEndpoint.class);
        CallEndpoint bluetooth = Mockito.mock(CallEndpoint.class);
        Set<CallEndpoint> initialSet = Set.of(earpiece);
        Set<CallEndpoint> finalSet = Set.of(earpiece, bluetooth);
        when(earpiece.getEndpointType()).thenReturn(CallEndpoint.TYPE_EARPIECE);
        when(bluetooth.getEndpointType()).thenReturn(CallEndpoint.TYPE_BLUETOOTH);

        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        assertNull(call.getTransactionServiceWrapper());

        call.cacheServiceCallback(new CachedAvailableEndpointsChange(initialSet));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedAvailableEndpointsChange.ID).size());

        call.cacheServiceCallback(new CachedAvailableEndpointsChange(finalSet));
        assertEquals(1,
                call.getCachedServiceCallbacksCopy().get(CachedAvailableEndpointsChange.ID).size());

        CachedAvailableEndpointsChange availableEndpoints = (CachedAvailableEndpointsChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedAvailableEndpointsChange.ID)
                .getLast();

        assertEquals(2, availableEndpoints.getAvailableEndpoints().size());

        call.setTransactionServiceWrapper(tsw);
        verify(tsw, times(1)).onAvailableCallEndpointsChanged(any(), any());
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    /**
     * verify that if multiple types of cached callbacks are added to the call, the call executes
     * all the callbacks once the service is set.
     */
    @Test
    public void testAllCachedCallbacks() {
        when(mFeatureFlags.cacheCallAudioCallbacks()).thenReturn(true);
        when(mFeatureFlags.cacheCallEvents()).thenReturn(true);
        TransactionalServiceWrapper tsw = Mockito.mock(TransactionalServiceWrapper.class);
        CallEndpoint earpiece = Mockito.mock(CallEndpoint.class);
        CallEndpoint bluetooth = Mockito.mock(CallEndpoint.class);
        Set<CallEndpoint> availableEndpointsSet = Set.of(earpiece, bluetooth);
        when(earpiece.getEndpointType()).thenReturn(CallEndpoint.TYPE_EARPIECE);
        when(bluetooth.getEndpointType()).thenReturn(CallEndpoint.TYPE_BLUETOOTH);
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);

        // The call should have a null service so that callbacks are cached
        assertNull(call.getTransactionServiceWrapper());

        // add cached callbacks
        call.cacheServiceCallback(new CachedMuteStateChange(false));
        assertEquals(1, call.getCachedServiceCallbacksCopy().size());
        call.cacheServiceCallback(new CachedCurrentEndpointChange(earpiece));
        assertEquals(2, call.getCachedServiceCallbacksCopy().size());
        call.cacheServiceCallback(new CachedAvailableEndpointsChange(availableEndpointsSet));
        assertEquals(3, call.getCachedServiceCallbacksCopy().size());
        String testEvent = "testEvent";
        Bundle testBundle = new Bundle();
        call.sendCallEvent("testEvent", testBundle);

        // verify the cached callbacks are stored properly within the cache map and the values
        // can be evaluated
        CachedMuteStateChange currentCacheMuteState = (CachedMuteStateChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedMuteStateChange.ID)
                .getLast();
        CachedCurrentEndpointChange currentEndpointChange = (CachedCurrentEndpointChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedCurrentEndpointChange.ID)
                .getLast();
        CachedAvailableEndpointsChange availableEndpoints = (CachedAvailableEndpointsChange) call
                .getCachedServiceCallbacksCopy()
                .get(CachedAvailableEndpointsChange.ID)
                .getLast();
        assertFalse(currentCacheMuteState.isMuted());
        assertEquals(CallEndpoint.TYPE_EARPIECE,
                currentEndpointChange.getCurrentCallEndpoint().getEndpointType());
        assertEquals(2, availableEndpoints.getAvailableEndpoints().size());

        // set the service to a non-null value
        call.setTransactionServiceWrapper(tsw);

        // ensure the cached callbacks were executed
        verify(tsw, times(1)).onMuteStateChanged(any(), anyBoolean());
        verify(tsw, times(1)).onCallEndpointChanged(any(), any());
        verify(tsw, times(1)).onAvailableCallEndpointsChanged(any(), any());
        verify(tsw, times(1)).sendCallEvent(any(), eq(testEvent), eq(testBundle));

        // the cache map should be cleared
        assertEquals(0, call.getCachedServiceCallbacksCopy().size());
    }

    /**
     * Basic tests to check which call states are considered transitory.
     */
    @Test
    @SmallTest
    public void testIsCallStateTransitory() {
        assertTrue(CallState.isTransitoryState(CallState.NEW));
        assertTrue(CallState.isTransitoryState(CallState.CONNECTING));
        assertTrue(CallState.isTransitoryState(CallState.DISCONNECTING));
        assertTrue(CallState.isTransitoryState(CallState.ANSWERED));

        assertFalse(CallState.isTransitoryState(CallState.SELECT_PHONE_ACCOUNT));
        assertFalse(CallState.isTransitoryState(CallState.DIALING));
        assertFalse(CallState.isTransitoryState(CallState.RINGING));
        assertFalse(CallState.isTransitoryState(CallState.ACTIVE));
        assertFalse(CallState.isTransitoryState(CallState.ON_HOLD));
        assertFalse(CallState.isTransitoryState(CallState.DISCONNECTED));
        assertFalse(CallState.isTransitoryState(CallState.ABORTED));
        assertFalse(CallState.isTransitoryState(CallState.PULLING));
        assertFalse(CallState.isTransitoryState(CallState.AUDIO_PROCESSING));
        assertFalse(CallState.isTransitoryState(CallState.SIMULATED_RINGING));
    }

    /**
     * Basic tests to check which call states are considered intermediate.
     */
    @Test
    @SmallTest
    public void testIsCallStateIntermediate() {
        assertTrue(CallState.isIntermediateState(CallState.DIALING));
        assertTrue(CallState.isIntermediateState(CallState.RINGING));

        assertFalse(CallState.isIntermediateState(CallState.NEW));
        assertFalse(CallState.isIntermediateState(CallState.CONNECTING));
        assertFalse(CallState.isIntermediateState(CallState.DISCONNECTING));
        assertFalse(CallState.isIntermediateState(CallState.ANSWERED));
        assertFalse(CallState.isIntermediateState(CallState.SELECT_PHONE_ACCOUNT));
        assertFalse(CallState.isIntermediateState(CallState.ACTIVE));
        assertFalse(CallState.isIntermediateState(CallState.ON_HOLD));
        assertFalse(CallState.isIntermediateState(CallState.DISCONNECTED));
        assertFalse(CallState.isIntermediateState(CallState.ABORTED));
        assertFalse(CallState.isIntermediateState(CallState.PULLING));
        assertFalse(CallState.isIntermediateState(CallState.AUDIO_PROCESSING));
        assertFalse(CallState.isIntermediateState(CallState.SIMULATED_RINGING));
    }

    @SmallTest
    @Test
    public void testIsCreateConnectionComplete() {
        // A new call with basic info.
        Call call = new Call(
                "1", /* callId */
                mContext,
                mMockCallsManager,
                mLock,
                null /* ConnectionServiceRepository */,
                mMockPhoneNumberUtilsAdapter,
                TEST_ADDRESS,
                null /* GatewayInfo */,
                null /* connectionManagerPhoneAccountHandle */,
                SIM_1_HANDLE,
                Call.CALL_DIRECTION_INCOMING,
                false /* shouldAttachToExistingConnection*/,
                false /* isConference */,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);

        // To start with connection creation isn't complete.
        assertFalse(call.isCreateConnectionComplete());

        // Need the bare minimum to get connection creation to complete.
        ParcelableConnection connection = new ParcelableConnection(null, 0, 0, 0, 0, null, 0, null,
                0, null, 0, false, false, 0L, 0L, null, null, Collections.emptyList(), null, null,
                0, 0);
        call.handleCreateConnectionSuccess(Mockito.mock(CallIdMapper.class), connection);
        assertTrue(call.isCreateConnectionComplete());
    }

    @Test
    @SmallTest
    public void testDisconnectCauseWhenAudioProcessing() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setState(CallState.AUDIO_PROCESSING, "");
        call.disconnect();
        call.setDisconnectCause(new DisconnectCause(DisconnectCause.LOCAL));
        assertEquals(DisconnectCause.REJECTED, call.getDisconnectCause().getCode());
    }

    @Test
    @SmallTest
    public void testDisconnectCauseWhenAudioProcessingAfterActive() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setState(CallState.AUDIO_PROCESSING, "");
        call.setState(CallState.ACTIVE, "");
        call.setState(CallState.AUDIO_PROCESSING, "");
        call.disconnect();
        call.setDisconnectCause(new DisconnectCause(DisconnectCause.LOCAL));
        assertEquals(DisconnectCause.LOCAL, call.getDisconnectCause().getCode());
    }

    @Test
    @SmallTest
    public void testDisconnectCauseWhenSimulatedRingingAndDisconnect() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setState(CallState.SIMULATED_RINGING, "");
        call.disconnect();
        call.setDisconnectCause(new DisconnectCause(DisconnectCause.LOCAL));
        assertEquals(DisconnectCause.MISSED, call.getDisconnectCause().getCode());
    }

    @Test
    @SmallTest
    public void testDisconnectCauseWhenSimulatedRingingAndReject() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setState(CallState.SIMULATED_RINGING, "");
        call.reject(false, "");
        call.setDisconnectCause(new DisconnectCause(DisconnectCause.LOCAL));
        assertEquals(DisconnectCause.REJECTED, call.getDisconnectCause().getCode());
    }

    @Test
    @SmallTest
    public void testCanPullCallRemovedDuringEmergencyCall() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        boolean[] hasCalledConnectionCapabilitiesChanged = new boolean[1];
        call.addListener(new Call.ListenerBase() {
            @Override
            public void onConnectionCapabilitiesChanged(Call call) {
                hasCalledConnectionCapabilitiesChanged[0] = true;
            }
        });
        call.setConnectionService(mMockConnectionService);
        call.setConnectionProperties(Connection.PROPERTY_IS_EXTERNAL_CALL);
        call.setConnectionCapabilities(Connection.CAPABILITY_CAN_PULL_CALL);
        call.setState(CallState.ACTIVE, "");
        assertTrue(hasCalledConnectionCapabilitiesChanged[0]);
        // Capability should be present
        assertTrue((call.getConnectionCapabilities() | Connection.CAPABILITY_CAN_PULL_CALL) > 0);
        hasCalledConnectionCapabilitiesChanged[0] = false;
        // Emergency call in progress
        call.setIsPullExternalCallSupported(false /*isPullCallSupported*/);
        assertTrue(hasCalledConnectionCapabilitiesChanged[0]);
        // Capability should not be present
        assertEquals(0, call.getConnectionCapabilities() & Connection.CAPABILITY_CAN_PULL_CALL);
        hasCalledConnectionCapabilitiesChanged[0] = false;
        // Emergency call complete
        call.setIsPullExternalCallSupported(true /*isPullCallSupported*/);
        assertTrue(hasCalledConnectionCapabilitiesChanged[0]);
        // Capability should be present
        assertEquals(Connection.CAPABILITY_CAN_PULL_CALL,
                call.getConnectionCapabilities() & Connection.CAPABILITY_CAN_PULL_CALL);
    }

    @Test
    @SmallTest
    public void testCanNotPullCallDuringEmergencyCall() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setConnectionService(mMockConnectionService);
        call.setConnectionProperties(Connection.PROPERTY_IS_EXTERNAL_CALL);
        call.setConnectionCapabilities(Connection.CAPABILITY_CAN_PULL_CALL);
        call.setState(CallState.ACTIVE, "");
        // Emergency call in progress, this should show a toast and never call pullExternalCall
        // on the ConnectionService.
        doReturn(true).when(mMockCallsManager).isInEmergencyCall();
        call.pullExternalCall();
        verify(mMockConnectionService, never()).pullExternalCall(any());
    }

    @Test
    @SmallTest
    public void testCallDirection() {
        Call call = createCall("1");
        boolean[] hasCallDirectionChanged = new boolean[1];
        call.addListener(new Call.ListenerBase() {
            @Override
            public void onCallDirectionChanged(Call call) {
                hasCallDirectionChanged[0] = true;
            }
        });
        assertFalse(call.isIncoming());
        call.setCallDirection(Call.CALL_DIRECTION_INCOMING);
        assertTrue(hasCallDirectionChanged[0]);
        assertTrue(call.isIncoming());
    }

    @Test
    public void testIsSuppressedByDoNotDisturbExtra() {
        Call call = new Call(
                "1", /* callId */
                mContext,
                mMockCallsManager,
                mLock,
                null /* ConnectionServiceRepository */,
                mMockPhoneNumberUtilsAdapter,
                TEST_ADDRESS,
                null /* GatewayInfo */,
                null /* connectionManagerPhoneAccountHandle */,
                SIM_1_HANDLE,
                Call.CALL_DIRECTION_UNDEFINED,
                false /* shouldAttachToExistingConnection*/,
                true /* isConference */,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);

        assertFalse(call.wasDndCheckComputedForCall());
        assertFalse(call.isCallSuppressedByDoNotDisturb());
        call.setCallIsSuppressedByDoNotDisturb(true);
        assertTrue(call.wasDndCheckComputedForCall());
        assertTrue(call.isCallSuppressedByDoNotDisturb());
    }

    @Test
    public void testGetConnectionServiceWrapper() {
        Call call = new Call(
                "1", /* callId */
                mContext,
                mMockCallsManager,
                mLock,
                null /* ConnectionServiceRepository */,
                mMockPhoneNumberUtilsAdapter,
                TEST_ADDRESS,
                null /* GatewayInfo */,
                null /* connectionManagerPhoneAccountHandle */,
                SIM_1_HANDLE,
                Call.CALL_DIRECTION_UNDEFINED,
                false /* shouldAttachToExistingConnection*/,
                true /* isConference */,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);

        assertNull(call.getConnectionServiceWrapper());
        assertFalse(call.isTransactionalCall());
        call.setConnectionService(mMockConnectionService);
        assertEquals(mMockConnectionService, call.getConnectionServiceWrapper());
        call.setIsTransactionalCall(true);
        assertTrue(call.isTransactionalCall());
        assertNull(call.getConnectionServiceWrapper());
        call.setTransactionServiceWrapper(mMockTransactionalService);
        assertEquals(mMockTransactionalService, call.getTransactionServiceWrapper());
    }

    @Test
    public void testCallEventCallbacksWereCalled() {
        Call call = new Call(
                "1", /* callId */
                mContext,
                mMockCallsManager,
                mLock,
                null /* ConnectionServiceRepository */,
                mMockPhoneNumberUtilsAdapter,
                TEST_ADDRESS,
                null /* GatewayInfo */,
                null /* connectionManagerPhoneAccountHandle */,
                SIM_1_HANDLE,
                Call.CALL_DIRECTION_UNDEFINED,
                false /* shouldAttachToExistingConnection*/,
                true /* isConference */,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);

        // setup
        call.setIsTransactionalCall(true);
        assertTrue(call.isTransactionalCall());
        assertNull(call.getConnectionServiceWrapper());
        call.setTransactionServiceWrapper(mMockTransactionalService);
        assertEquals(mMockTransactionalService, call.getTransactionServiceWrapper());

        // assert CallEventCallback#onSetInactive is called
        call.setState(CallState.ACTIVE, "test");
        call.hold();
        verify(mMockTransactionalService, times(1)).onSetInactive(call);

        // assert CallEventCallback#onSetActive is called
        call.setState(CallState.ON_HOLD, "test");
        call.unhold();
        verify(mMockTransactionalService, times(1)).onSetActive(call);

        // assert CallEventCallback#onAnswer is called
        call.setState(CallState.RINGING, "test");
        call.answer(0);
        verify(mMockTransactionalService, times(1)).onAnswer(call, 0);

        // assert CallEventCallback#onDisconnect is called
        call.setState(CallState.ACTIVE, "test");
        call.disconnect();
        verify(mMockTransactionalService, times(1)).onDisconnect(call,
                call.getDisconnectCause());
    }

    @Test
    @SmallTest
    public void testSetConnectionPropertiesRttOnOff() {
        Call call = createCall("1");
        call.setConnectionService(mMockConnectionService);

        call.setConnectionProperties(Connection.PROPERTY_IS_RTT);
        verify(mMockCallsManager).playRttUpgradeToneForCall(any());
        assertNotNull(null, call.getInCallToCsRttPipeForCs());
        assertNotNull(null, call.getCsToInCallRttPipeForInCall());

        call.setConnectionProperties(0);
        assertNull(null, call.getInCallToCsRttPipeForCs());
        assertNull(null, call.getCsToInCallRttPipeForInCall());
    }

    @Test
    @SmallTest
    public void testGetFromCallerInfo() {
        Call call = createCall("1");

        CallerInfo info = new CallerInfo();
        info.setName("name");
        info.setPhoneNumber("number");
        info.cachedPhoto = new ColorDrawable();
        info.cachedPhotoIcon = Bitmap.createBitmap(24, 24, Bitmap.Config.ALPHA_8);

        ArgumentCaptor<CallerInfoLookupHelper.OnQueryCompleteListener> listenerCaptor =
                ArgumentCaptor.forClass(CallerInfoLookupHelper.OnQueryCompleteListener.class);
        verify(mMockCallerInfoLookupHelper).startLookup(any(), listenerCaptor.capture());
        listenerCaptor.getValue().onCallerInfoQueryComplete(call.getHandle(), info);

        assertEquals(info, call.getCallerInfo());
        assertEquals(info.getName(), call.getName());
        assertEquals(info.getPhoneNumber(), call.getPhoneNumber());
        assertEquals(info.cachedPhoto, call.getPhoto());
        assertEquals(info.cachedPhotoIcon, call.getPhotoIcon());
        assertEquals(call.getHandle(), call.getContactUri());
    }

    @Test
    @SmallTest
    public void testGetFromCallerInfo_skipLookup() {
        Resources mockResources = mContext.getResources();
        when(mockResources.getString(R.string.skip_incoming_caller_info_account_package))
                .thenReturn("com.foo");

        createCall("1");

        verify(mMockCallerInfoLookupHelper, never()).startLookup(any(), any());
    }

    @Test
    @SmallTest
    public void testOriginalCallIntent() {
        Call call = createCall("1");

        Intent i = new Intent();
        call.setOriginalCallIntent(i);

        assertEquals(i, call.getOriginalCallIntent());
    }

    @Test
    @SmallTest
    public void testHandleCreateConferenceSuccessNotifiesListeners() {
        Call.Listener listener = mock(Call.Listener.class);

        Call incomingCall = createCall("1", Call.CALL_DIRECTION_INCOMING);
        incomingCall.setConnectionService(mMockConnectionService);
        incomingCall.addListener(listener);
        Call outgoingCall = createCall("2", Call.CALL_DIRECTION_OUTGOING);
        outgoingCall.setConnectionService(mMockConnectionService);
        outgoingCall.addListener(listener);

        StatusHints statusHints = mock(StatusHints.class);
        Bundle extra = new Bundle();
        ParcelableConference conference =
                new ParcelableConference.Builder(SIM_1_HANDLE, Connection.STATE_NEW)
                    .setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED)
                    .setConnectionCapabilities(123)
                    .setVideoAttributes(null, VideoProfile.STATE_AUDIO_ONLY)
                    .setRingbackRequested(true)
                    .setStatusHints(statusHints)
                    .setExtras(extra)
                    .build();

        incomingCall.handleCreateConferenceSuccess(null, conference);
        verify(listener).onSuccessfulIncomingCall(incomingCall);

        outgoingCall.handleCreateConferenceSuccess(null, conference);
        verify(listener).onSuccessfulOutgoingCall(outgoingCall, CallState.NEW);
    }

    @Test
    @SmallTest
    public void testHandleCreateConferenceSuccess() {
        Call call = createCall("1", Call.CALL_DIRECTION_INCOMING);
        call.setConnectionService(mMockConnectionService);

        StatusHints statusHints = mock(StatusHints.class);
        Bundle extra = new Bundle();
        ParcelableConference conference =
                new ParcelableConference.Builder(SIM_1_HANDLE, Connection.STATE_NEW)
                    .setAddress(TEST_ADDRESS, TelecomManager.PRESENTATION_ALLOWED)
                    .setConnectionCapabilities(123)
                    .setVideoAttributes(null, VideoProfile.STATE_AUDIO_ONLY)
                    .setRingbackRequested(true)
                    .setStatusHints(statusHints)
                    .setExtras(extra)
                    .build();

        call.handleCreateConferenceSuccess(null, conference);

        assertEquals(SIM_1_HANDLE, call.getTargetPhoneAccount());
        assertEquals(TEST_ADDRESS, call.getHandle());
        assertEquals(123, call.getConnectionCapabilities());
        assertNull(call.getVideoProviderProxy());
        assertEquals(VideoProfile.STATE_AUDIO_ONLY, call.getVideoState());
        assertTrue(call.isRingbackRequested());
        assertEquals(statusHints, call.getStatusHints());
    }

    @Test
    @SmallTest
    public void testHandleCreateConferenceFailure() {
        Call.Listener listener = mock(Call.Listener.class);

        Call incomingCall = createCall("1", Call.CALL_DIRECTION_INCOMING);
        incomingCall.setConnectionService(mMockConnectionService);
        incomingCall.addListener(listener);
        Call outgoingCall = createCall("2", Call.CALL_DIRECTION_OUTGOING);
        outgoingCall.setConnectionService(mMockConnectionService);
        outgoingCall.addListener(listener);

        final DisconnectCause cause = new DisconnectCause(DisconnectCause.REJECTED);

        incomingCall.handleCreateConferenceFailure(cause);
        assertEquals(cause, incomingCall.getDisconnectCause());
        verify(listener).onFailedIncomingCall(incomingCall);

        outgoingCall.handleCreateConferenceFailure(cause);
        assertEquals(cause, outgoingCall.getDisconnectCause());
        verify(listener).onFailedOutgoingCall(outgoingCall, cause);
    }

    @Test
    @SmallTest
    public void testWasConferencePreviouslyMerged() {
        Call call = createCall("1");
        call.setConnectionService(mMockConnectionService);
        call.setConnectionCapabilities(Connection.CAPABILITY_MERGE_CONFERENCE);

        assertFalse(call.wasConferencePreviouslyMerged());

        call.mergeConference();

        assertTrue(call.wasConferencePreviouslyMerged());
    }

    @Test
    @SmallTest
    public void testSwapConference() {
        Call.Listener listener = mock(Call.Listener.class);

        Call call = createCall("1");
        call.setConnectionService(mMockConnectionService);
        call.setConnectionCapabilities(Connection.CAPABILITY_SWAP_CONFERENCE);
        call.addListener(listener);

        call.swapConference();
        assertNull(call.getConferenceLevelActiveCall());

        Call childCall1 = createCall("child1");
        childCall1.setChildOf(call);
        call.swapConference();
        assertEquals(childCall1, call.getConferenceLevelActiveCall());

        Call childCall2 = createCall("child2");
        childCall2.setChildOf(call);
        call.swapConference();
        assertEquals(childCall1, call.getConferenceLevelActiveCall());
        call.swapConference();
        assertEquals(childCall2, call.getConferenceLevelActiveCall());

        verify(listener, times(4)).onCdmaConferenceSwap(call);
    }

    @Test
    @SmallTest
    public void testHandleCreateConnectionFailure() {
        Call.Listener listener = mock(Call.Listener.class);

        Call incomingCall = createCall("1", Call.CALL_DIRECTION_INCOMING);
        incomingCall.setConnectionService(mMockConnectionService);
        incomingCall.addListener(listener);
        Call outgoingCall = createCall("2", Call.CALL_DIRECTION_OUTGOING);
        outgoingCall.setConnectionService(mMockConnectionService);
        outgoingCall.addListener(listener);
        Call unknownCall = createCall("3", Call.CALL_DIRECTION_UNKNOWN);
        unknownCall.setConnectionService(mMockConnectionService);
        unknownCall.addListener(listener);

        final DisconnectCause cause = new DisconnectCause(DisconnectCause.REJECTED);

        incomingCall.handleCreateConnectionFailure(cause);
        assertEquals(cause, incomingCall.getDisconnectCause());
        verify(listener).onFailedIncomingCall(incomingCall);

        outgoingCall.handleCreateConnectionFailure(cause);
        assertEquals(cause, outgoingCall.getDisconnectCause());
        verify(listener).onFailedOutgoingCall(outgoingCall, cause);

        unknownCall.handleCreateConnectionFailure(cause);
        assertEquals(cause, unknownCall.getDisconnectCause());
        verify(listener).onFailedUnknownCall(unknownCall);
    }

    /**
     * ensure a Call object does not throw an NPE when the CallingPackageIdentity is not set and
     * the correct values are returned when set
     */
    @Test
    @SmallTest
    public void testCallingPackageIdentity() {
        final int packageUid = 123;
        final int packagePid = 1;

        Call call = createCall("1");

        // assert default values for a Calls CallingPackageIdentity are -1 unless set via the setter
        assertEquals(-1, call.getCallingPackageIdentity().mCallingPackageUid);
        assertEquals(-1, call.getCallingPackageIdentity().mCallingPackagePid);

        // set the Call objects CallingPackageIdentity via the setter and a bundle
        Bundle extras = new Bundle();
        extras.putInt(CallAttributes.CALLER_UID_KEY, packageUid);
        extras.putInt(CallAttributes.CALLER_PID_KEY, packagePid);
        // assert that the setter removed the extras
        assertEquals(packageUid, extras.getInt(CallAttributes.CALLER_UID_KEY));
        assertEquals(packagePid, extras.getInt(CallAttributes.CALLER_PID_KEY));
        call.setCallingPackageIdentity(extras);
        // assert that the setter removed the extras
        assertEquals(0, extras.getInt(CallAttributes.CALLER_UID_KEY));
        assertEquals(0, extras.getInt(CallAttributes.CALLER_PID_KEY));
        // assert the properties are fetched correctly
        assertEquals(packageUid, call.getCallingPackageIdentity().mCallingPackageUid);
        assertEquals(packagePid, call.getCallingPackageIdentity().mCallingPackagePid);
    }

        @Test
    @SmallTest
    public void testOnConnectionEventNotifiesListener() {
        Call.Listener listener = mock(Call.Listener.class);
        Call call = createCall("1");
        call.addListener(listener);

        call.onConnectionEvent(Connection.EVENT_ON_HOLD_TONE_START, null);
        verify(listener).onHoldToneRequested(call);
        assertTrue(call.isRemotelyHeld());

        call.onConnectionEvent(Connection.EVENT_ON_HOLD_TONE_END, null);
        verify(listener, times(2)).onHoldToneRequested(call);
        assertFalse(call.isRemotelyHeld());

        call.onConnectionEvent(Connection.EVENT_CALL_HOLD_FAILED, null);
        verify(listener).onCallHoldFailed(call);

        call.onConnectionEvent(Connection.EVENT_CALL_SWITCH_FAILED, null);
        verify(listener).onCallSwitchFailed(call);

        final int d2dType = 1;
        final int d2dValue = 2;
        final Bundle d2dExtras = new Bundle();
        d2dExtras.putInt(Connection.EXTRA_DEVICE_TO_DEVICE_MESSAGE_TYPE, d2dType);
        d2dExtras.putInt(Connection.EXTRA_DEVICE_TO_DEVICE_MESSAGE_VALUE, d2dValue);
        call.onConnectionEvent(Connection.EVENT_DEVICE_TO_DEVICE_MESSAGE, d2dExtras);
        verify(listener).onReceivedDeviceToDeviceMessage(call, d2dType, d2dValue);

        final CallQuality quality = new CallQuality();
        final Bundle callQualityExtras = new Bundle();
        callQualityExtras.putParcelable(Connection.EXTRA_CALL_QUALITY_REPORT, quality);
        call.onConnectionEvent(Connection.EVENT_CALL_QUALITY_REPORT, callQualityExtras);
        verify(listener).onReceivedCallQualityReport(call, quality);
    }

    @Test
    @SmallTest
    public void testDiagnosticMessage() {
        Call.Listener listener = mock(Call.Listener.class);
        Call call = createCall("1");
        call.addListener(listener);

        final int id = 1;
        final String message = "msg";

        call.displayDiagnosticMessage(id, message);
        verify(listener).onConnectionEvent(
                eq(call),
                eq(android.telecom.Call.EVENT_DISPLAY_DIAGNOSTIC_MESSAGE),
                argThat(extras -> {
                    return extras.getInt(android.telecom.Call.EXTRA_DIAGNOSTIC_MESSAGE_ID) == id &&
                            extras.getCharSequence(android.telecom.Call.EXTRA_DIAGNOSTIC_MESSAGE)
                                .toString().equals(message);
                }));

        call.clearDiagnosticMessage(id);
        verify(listener).onConnectionEvent(
                eq(call),
                eq(android.telecom.Call.EVENT_CLEAR_DIAGNOSTIC_MESSAGE),
                argThat(extras -> {
                    return extras.getInt(android.telecom.Call.EXTRA_DIAGNOSTIC_MESSAGE_ID) == id;
                }));
    }

    @Test
    @SmallTest
    public void testExcludesInCallServiceFromDoNotLogCallExtra() {
        Call call = createCall("any");
        Bundle extra = new Bundle();
        extra.putBoolean(TelecomManager.EXTRA_DO_NOT_LOG_CALL, true);

        call.putInCallServiceExtras(extra, "packageName");

        assertFalse(call.getExtras().containsKey(TelecomManager.EXTRA_DO_NOT_LOG_CALL));
    }

    /**
     * Verify that a Call can handle a case where no telephony stack is present to detect emergency
     * numbers.
     */
    @Test
    @SmallTest
    public void testNoTelephonyEmergencyBehavior() {
        when(mComponentContextFixture.getTelephonyManager().isEmergencyNumber(any()))
                .thenReturn(true);
        Call testCall = createCall("1", Call.CALL_DIRECTION_OUTGOING, Uri.parse("tel:911"));
        assertTrue(testCall.isEmergencyCall());

        when(mComponentContextFixture.getTelephonyManager().isEmergencyNumber(any()))
                .thenThrow(new UnsupportedOperationException("Bee-boop"));
        Call testCall2 = createCall("2", Call.CALL_DIRECTION_OUTGOING, Uri.parse("tel:911"));
        assertTrue(!testCall2.isEmergencyCall());
    }

    @Test
    @SmallTest
    public void testExcludesConnectionServiceWithoutModifyStatePermissionFromDoNotLogCallExtra() {
        PackageManager packageManager = mContext.getPackageManager();
        Bundle extra = new Bundle();
        extra.putBoolean(TelecomManager.EXTRA_DO_NOT_LOG_CALL, true);
        String packageName = SIM_1_HANDLE.getComponentName().getPackageName();
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(packageManager)
                .checkPermission(android.Manifest.permission.MODIFY_PHONE_STATE, packageName);
        Call call = createCall("any");

        call.putConnectionServiceExtras(extra);

        assertFalse(call.getExtras().containsKey(TelecomManager.EXTRA_DO_NOT_LOG_CALL));
    }

    @Test
    @SmallTest
    public void testDoesNotExcludeConnectionServiceWithModifyStatePermissionFromDoNotLogCallExtra() {
        String packageName = SIM_1_HANDLE.getComponentName().getPackageName();
        Bundle extra = new Bundle();
        extra.putBoolean(TelecomManager.EXTRA_DO_NOT_LOG_CALL, true);
        PackageManager packageManager = mContext.getPackageManager();
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(packageManager)
                .checkPermission(android.Manifest.permission.MODIFY_PHONE_STATE, packageName);
        Call call = createCall("any");

        call.putConnectionServiceExtras(extra);

        assertTrue(call.getExtras().containsKey(TelecomManager.EXTRA_DO_NOT_LOG_CALL));
    }

    @Test
    @SmallTest
    public void testSkipLoadingCannedTextResponse() {
        Call call = createCall("any");
        Resources mockResources = mContext.getResources();
        when(mockResources.getBoolean(R.bool.skip_loading_canned_text_response))
                .thenReturn(true);


        assertFalse(call.isRespondViaSmsCapable());
    }

    private Call createCall(String id) {
        return createCall(id, Call.CALL_DIRECTION_UNDEFINED);
    }

    private Call createCall(String id, int callDirection) {
        return createCall(id, callDirection, TEST_ADDRESS);
    }

    private Call createCall(String id, int callDirection, Uri address) {
        return new Call(
                id,
                mContext,
                mMockCallsManager,
                mLock,
                null,
                mMockPhoneNumberUtilsAdapter,
                address,
                null /* GatewayInfo */,
                null,
                SIM_1_HANDLE,
                callDirection,
                false,
                false,
                mMockClockProxy,
                mMockToastProxy,
                mFeatureFlags);
    }
}
