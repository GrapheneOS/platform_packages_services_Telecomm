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

package com.android.server.telecom.tests;

import static com.android.server.telecom.voip.VideoStateTranslation.TransactionalVideoStateToVideoProfileState;
import static com.android.server.telecom.voip.VideoStateTranslation.VideoProfileStateToTransactionalVideoState;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.isA;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.UserHandle;
import android.telecom.CallAttributes;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.CallerInfoLookupHelper;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ClockProxy;
import com.android.server.telecom.ConnectionServiceWrapper;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.PhoneNumberUtilsAdapter;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.ui.ToastFactory;
import com.android.server.telecom.voip.EndCallTransaction;
import com.android.server.telecom.voip.HoldCallTransaction;
import com.android.server.telecom.voip.IncomingCallTransaction;
import com.android.server.telecom.voip.OutgoingCallTransaction;
import com.android.server.telecom.voip.MaybeHoldCallForNewCallTransaction;
import com.android.server.telecom.voip.RequestNewActiveCallTransaction;
import com.android.server.telecom.voip.TransactionManager;
import com.android.server.telecom.voip.VerifyCallStateChangeTransaction;
import com.android.server.telecom.voip.VideoStateTranslation;
import com.android.server.telecom.voip.VoipCallTransactionResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


public class TransactionTests extends TelecomTestCase {

    private static final String CALL_ID_1 = "1";

    private static final PhoneAccountHandle mHandle = new PhoneAccountHandle(
            new ComponentName("foo", "bar"), "1");
    private static final String TEST_NAME = "Sergey Brin";
    private static final Uri TEST_URI = Uri.fromParts("tel", "abc", "123");

    @Mock private Call mMockCall1;
    @Mock private Context mMockContext;
    @Mock private CallsManager mCallsManager;
    @Mock private ToastFactory mToastFactory;
    @Mock private ClockProxy mClockProxy;
    @Mock private PhoneNumberUtilsAdapter mPhoneNumberUtilsAdapter;
    @Mock private CallerInfoLookupHelper mCallerInfoLookupHelper;

    private final TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() {
    };
    private static final Uri TEST_ADDRESS = Uri.parse("tel:555-1212");

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        Mockito.when(mMockCall1.getId()).thenReturn(CALL_ID_1);
        Mockito.when(mMockContext.getResources()).thenReturn(Mockito.mock(Resources.class));
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testEndCallTransactionWithDisconnect() throws Exception {
        // GIVEN
        EndCallTransaction transaction =
                new EndCallTransaction(mCallsManager,  new DisconnectCause(0), mMockCall1);

        // WHEN
        transaction.processTransaction(null);

        // THEN
        verify(mCallsManager, times(1))
                .markCallAsDisconnected(mMockCall1, new DisconnectCause(0));
        verify(mCallsManager, never())
                .rejectCall(mMockCall1, 0);
        verify(mCallsManager, times(1))
                .markCallAsRemoved(mMockCall1);
    }

    @Test
    public void testHoldCallTransaction() throws Exception {
        // GIVEN
        Call spyCall = createSpyCall(null, CallState.ACTIVE, CALL_ID_1);

        HoldCallTransaction transaction =
                new HoldCallTransaction(mCallsManager, spyCall);

        // WHEN
        when(mCallsManager.canHold(spyCall)).thenReturn(true);
        doAnswer(invocation -> {
            Call call = invocation.getArgument(0);
            call.setState(CallState.ON_HOLD, "manual set");
            return null;
        }).when(mCallsManager).markCallAsOnHold(spyCall);

        transaction.processTransaction(null);

        // THEN
        verify(mCallsManager, times(1))
                .markCallAsOnHold(spyCall);

        assertEquals(CallState.ON_HOLD, spyCall.getState());
    }

    @Test
    public void testRequestNewCallFocusWithDialingCall() throws Exception {
        // GIVEN
        RequestNewActiveCallTransaction transaction =
                new RequestNewActiveCallTransaction(mCallsManager, mMockCall1);

        // WHEN
        when(mMockCall1.getState()).thenReturn(CallState.DIALING);
        transaction.processTransaction(null);

        // THEN
        verify(mCallsManager, times(1))
                .requestNewCallFocusAndVerify(eq(mMockCall1), isA(OutcomeReceiver.class));
    }

    @Test
    public void testRequestNewCallFocusWithRingingCall() throws Exception {
        // GIVEN
        RequestNewActiveCallTransaction transaction =
                new RequestNewActiveCallTransaction(mCallsManager, mMockCall1);

        // WHEN
        when(mMockCall1.getState()).thenReturn(CallState.RINGING);
        transaction.processTransaction(null);

        // THEN
        verify(mCallsManager, times(1))
                .requestNewCallFocusAndVerify(eq(mMockCall1), isA(OutcomeReceiver.class));
    }

    @Test
    public void testRequestNewCallFocusFailure() throws Exception {
        // GIVEN
        RequestNewActiveCallTransaction transaction =
                new RequestNewActiveCallTransaction(mCallsManager, mMockCall1);

        // WHEN
        when(mMockCall1.getState()).thenReturn(CallState.DISCONNECTING);
        when(mCallsManager.getActiveCall()).thenReturn(null);
        transaction.processTransaction(null);

        // THEN
        verify(mCallsManager, times(0))
                .requestNewCallFocusAndVerify( eq(mMockCall1), isA(OutcomeReceiver.class));
    }

    @Test
    public void testTransactionalHoldActiveCallForNewCall() throws Exception {
        // GIVEN
        MaybeHoldCallForNewCallTransaction transaction =
                new MaybeHoldCallForNewCallTransaction(mCallsManager, mMockCall1, false);

        // WHEN
        transaction.processTransaction(null);

        // THEN
        verify(mCallsManager, times(1))
                .transactionHoldPotentialActiveCallForNewCall(eq(mMockCall1), eq(false),
                        isA(OutcomeReceiver.class));
    }

    @Test
    public void testOutgoingCallTransaction() throws Exception {
        // GIVEN
        CallAttributes callAttributes = new CallAttributes.Builder(mHandle,
                CallAttributes.DIRECTION_OUTGOING, TEST_NAME, TEST_URI).build();

        OutgoingCallTransaction transaction =
                new OutgoingCallTransaction(CALL_ID_1, mMockContext, callAttributes, mCallsManager,
                        mFeatureFlags);

        // WHEN
        when(mMockContext.getOpPackageName()).thenReturn("testPackage");
        when(mMockContext.checkCallingPermission(android.Manifest.permission.CALL_PRIVILEGED))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mCallsManager.isOutgoingCallPermitted(callAttributes.getPhoneAccountHandle()))
                .thenReturn(true);
        transaction.processTransaction(null);

        // THEN
        verify(mCallsManager, times(1))
                .startOutgoingCall(isA(Uri.class),
                        isA(PhoneAccountHandle.class),
                        isA(Bundle.class),
                        isA(UserHandle.class),
                        isA(Intent.class),
                        nullable(String.class));
    }

    @Test
    public void testIncomingCallTransaction() throws Exception {
        // GIVEN
        CallAttributes callAttributes = new CallAttributes.Builder(mHandle,
                CallAttributes.DIRECTION_INCOMING, TEST_NAME, TEST_URI).build();

        IncomingCallTransaction transaction =
                new IncomingCallTransaction(CALL_ID_1, callAttributes, mCallsManager,
                        mFeatureFlags);

        // WHEN
        when(mCallsManager.isIncomingCallPermitted(callAttributes.getPhoneAccountHandle()))
                .thenReturn(true);
        transaction.processTransaction(null);

        // THEN
        verify(mCallsManager, times(1))
                .processIncomingCallIntent(isA(PhoneAccountHandle.class),
                        isA(Bundle.class),
                        isA(Boolean.class));
    }

    /**
     * Verify that transactional OUTGOING calls are re-mapping the CallAttributes video state to
     * VideoProfile states when starting the call via CallsManager#startOugoingCall.
     */
    @Test
    public void testOutgoingCallTransactionRemapsVideoState() {
        // GIVEN
        CallAttributes audioOnlyAttributes = new CallAttributes.Builder(mHandle,
                CallAttributes.DIRECTION_OUTGOING, TEST_NAME, TEST_URI)
                .setCallType(CallAttributes.AUDIO_CALL)
                .build();

        CallAttributes videoAttributes = new CallAttributes.Builder(mHandle,
                CallAttributes.DIRECTION_OUTGOING, TEST_NAME, TEST_URI)
                .setCallType(CallAttributes.VIDEO_CALL)
                .build();

        OutgoingCallTransaction t = new OutgoingCallTransaction(null,
                mContext, null, mCallsManager, new Bundle(), mFeatureFlags);

        // WHEN
        when(mFeatureFlags.transactionalVideoState()).thenReturn(true);
        t.setFeatureFlags(mFeatureFlags);

        // THEN
        assertEquals(VideoProfile.STATE_AUDIO_ONLY, t
                .generateExtras(audioOnlyAttributes)
                .getInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE));

        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, t
                .generateExtras(videoAttributes)
                .getInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE));
    }

    /**
     * Verify that transactional INCOMING calls are re-mapping the CallAttributes video state to
     * VideoProfile states when starting the call in CallsManager#processIncomingCallIntent.
     */
    @Test
    public void testIncomingCallTransactionRemapsVideoState() {
        // GIVEN
        CallAttributes audioOnlyAttributes = new CallAttributes.Builder(mHandle,
                CallAttributes.DIRECTION_INCOMING, TEST_NAME, TEST_URI)
                .setCallType(CallAttributes.AUDIO_CALL)
                .build();

        CallAttributes videoAttributes = new CallAttributes.Builder(mHandle,
                CallAttributes.DIRECTION_INCOMING, TEST_NAME, TEST_URI)
                .setCallType(CallAttributes.VIDEO_CALL)
                .build();

        IncomingCallTransaction t = new IncomingCallTransaction(null, null,
                mCallsManager, new Bundle(), mFeatureFlags);

        // WHEN
        when(mFeatureFlags.transactionalVideoState()).thenReturn(true);
        t.setFeatureFlags(mFeatureFlags);

        // THEN
        assertEquals(VideoProfile.STATE_AUDIO_ONLY, t
                .generateExtras(audioOnlyAttributes)
                .getInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE));

        assertEquals(VideoProfile.STATE_BIDIRECTIONAL, t
                .generateExtras(videoAttributes)
                .getInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE));
    }

    @Test
    public void testTransactionalVideoStateToVideoProfileState() {
        assertEquals(VideoProfile.STATE_AUDIO_ONLY,
                TransactionalVideoStateToVideoProfileState(CallAttributes.AUDIO_CALL));
        assertEquals(VideoProfile.STATE_BIDIRECTIONAL,
                TransactionalVideoStateToVideoProfileState(CallAttributes.VIDEO_CALL));
        // ensure non-defined values default to audio
        assertEquals(VideoProfile.STATE_AUDIO_ONLY,
                TransactionalVideoStateToVideoProfileState(-1));
    }

    @Test
    public void testVideoProfileStateToTransactionalVideoState() {
        assertEquals(CallAttributes.AUDIO_CALL,
                VideoProfileStateToTransactionalVideoState(VideoProfile.STATE_AUDIO_ONLY));
        assertEquals(CallAttributes.VIDEO_CALL,
                VideoProfileStateToTransactionalVideoState(VideoProfile.STATE_RX_ENABLED));
        assertEquals(CallAttributes.VIDEO_CALL,
                VideoProfileStateToTransactionalVideoState(VideoProfile.STATE_TX_ENABLED));
        assertEquals(CallAttributes.VIDEO_CALL,
                VideoProfileStateToTransactionalVideoState(VideoProfile.STATE_BIDIRECTIONAL));
        // ensure non-defined values default to audio
        assertEquals(CallAttributes.AUDIO_CALL,
                VideoProfileStateToTransactionalVideoState(-1));
    }

    /**
     * This test verifies if the ConnectionService call is NOT transitioned to the desired call
     * state (within timeout period), Telecom will disconnect the call.
     */
    @SmallTest
    @Test
    public void testCallStateChangeTimesOut() {
        when(mFeatureFlags.transactionalCsVerifier()).thenReturn(true);
        VerifyCallStateChangeTransaction t = new VerifyCallStateChangeTransaction(
                mLock, mMockCall1, CallState.ON_HOLD);
        TransactionManager.TransactionCompleteListener listener =
                mock(TransactionManager.TransactionCompleteListener.class);
        t.setCompleteListener(listener);
        // WHEN
        setupHoldableCall();

        // simulate the transaction being processed and the CompletableFuture timing out
        t.processTransaction(null);
        t.timeout();

        // THEN
        verify(mMockCall1, times(1)).addCallStateListener(t.getCallStateListenerImpl());
        verify(listener).onTransactionTimeout(anyString());
        verify(mMockCall1, atLeastOnce()).removeCallStateListener(any());
    }

    /**
     * This test verifies that when an application transitions a call to the requested state,
     * Telecom does not disconnect the call and transaction completes successfully.
     */
    @SmallTest
    @Test
    public void testCallStateIsSuccessfullyChanged()
            throws ExecutionException, InterruptedException, TimeoutException {
        when(mFeatureFlags.transactionalCsVerifier()).thenReturn(true);
        VerifyCallStateChangeTransaction t = new VerifyCallStateChangeTransaction(
                mLock, mMockCall1, CallState.ON_HOLD);
        // WHEN
        setupHoldableCall();

        // simulate the transaction being processed and the setOnHold() being called / state change
        t.processTransaction(null);
        doReturn(CallState.ON_HOLD).when(mMockCall1).getState();
        t.getCallStateListenerImpl().onCallStateChanged(CallState.ON_HOLD);
        t.finish(null);


        // THEN
        verify(mMockCall1, times(1)).addCallStateListener(t.getCallStateListenerImpl());
        assertEquals(VoipCallTransactionResult.RESULT_SUCCEED,
                t.getTransactionResult().get(2, TimeUnit.SECONDS).getResult());
        verify(mMockCall1, atLeastOnce()).removeCallStateListener(any());
    }

    private Call createSpyCall(PhoneAccountHandle targetPhoneAccount, int initialState, String id) {
        when(mCallsManager.getCallerInfoLookupHelper()).thenReturn(mCallerInfoLookupHelper);

        Call call = new Call(id,
                mMockContext,
                mCallsManager,
                mLock, /* ConnectionServiceRepository */
                null,
                mPhoneNumberUtilsAdapter,
                TEST_ADDRESS,
                null /* GatewayInfo */,
                null /* ConnectionManagerAccount */,
                targetPhoneAccount,
                Call.CALL_DIRECTION_INCOMING,
                false /* shouldAttachToExistingConnection*/,
                false /* isConference */,
                mClockProxy,
                mToastFactory,
                mFeatureFlags);

        Call callSpy = Mockito.spy(call);

        callSpy.setState(initialState, "manual set in test");

        // Mocks some methods to not call the real method.
        doNothing().when(callSpy).unhold();
        doNothing().when(callSpy).hold();
        doNothing().when(callSpy).disconnect();

        return callSpy;
    }

    private void setupHoldableCall(){
        when(mMockCall1.getState()).thenReturn(CallState.ACTIVE);
        when(mMockCall1.getConnectionServiceWrapper()).thenReturn(
                mock(ConnectionServiceWrapper.class));
        doNothing().when(mMockCall1).addCallStateListener(any());
        doReturn(true).when(mMockCall1).removeCallStateListener(any());
    }
}