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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.net.Uri;
import android.telecom.PhoneAccountHandle;
import android.telephony.SubscriptionManager;
import android.telephony.emergency.EmergencyNumber;
import android.util.ArrayMap;

import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.PhoneStateBroadcaster;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class PhoneStateBroadcasterTest extends TelecomTestCase {
    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Tests behavior where FEATURE_TELEPHONY_CALLING is not available, but
     * FEATURE_TELEPHONY_SUBSCRIPTION is; in this case we can't detect that the number is emergency
     * so we will not bother sending out anything.
     */
    @Test
    public void testNotifyOutgoingEmergencyCallWithNoTelephonyCalling() {
        CallsManager cm = mock(CallsManager.class);
        when(cm.getContext()).thenReturn(mContext);
        when(mComponentContextFixture.getTelephonyManager().isEmergencyNumber(anyString()))
                .thenThrow(new UnsupportedOperationException("Bee boop"));
        PhoneStateBroadcaster psb = new PhoneStateBroadcaster(cm);

        Call call = mock(Call.class);
        when(call.isExternalCall()).thenReturn(false);
        when(call.isEmergencyCall()).thenReturn(true);
        when(call.isIncoming()).thenReturn(false);
        when(call.getHandle()).thenReturn(Uri.parse("tel:911"));

        psb.onCallAdded(call);
        verify(mComponentContextFixture.getTelephonyRegistryManager(), never())
                .notifyOutgoingEmergencyCall(anyInt(), anyInt(), any(EmergencyNumber.class));
    }

    /**
     * Tests behavior where FEATURE_TELEPHONY_CALLING is available, but
     * FEATURE_TELEPHONY_SUBSCRIPTION is; in this case we can detect that this is an emergency
     * call, but we can't figure out any of the subscription parameters.  It is doubtful we'd ever
     * see this in practice since technically FEATURE_TELEPHONY_CALLING needs
     * FEATURE_TELEPHONY_SUBSCRIPTION.
     */
    @Test
    public void testNotifyOutgoingEmergencyCallWithNoTelephonySubscription() {
        CallsManager cm = mock(CallsManager.class);
        when(cm.getContext()).thenReturn(mContext);
        Map<Integer, List<EmergencyNumber>> nums = new ArrayMap<Integer, List<EmergencyNumber>>();
        nums.put(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                Arrays.asList(new EmergencyNumber("911", "US", null, 0, Collections.EMPTY_LIST,
                        0, 0)));
        when(mComponentContextFixture.getTelephonyManager().getEmergencyNumberList())
                .thenReturn(nums);
        when(mComponentContextFixture.getTelephonyManager().getSubscriptionId(any(
                        PhoneAccountHandle.class)))
                .thenThrow(new UnsupportedOperationException("Bee boop"));
        PhoneStateBroadcaster psb = new PhoneStateBroadcaster(cm);

        Call call = mock(Call.class);
        when(call.isExternalCall()).thenReturn(false);
        when(call.isEmergencyCall()).thenReturn(true);
        when(call.isIncoming()).thenReturn(false);
        when(call.getHandle()).thenReturn(Uri.parse("tel:911"));
        when(call.getTargetPhoneAccount()).thenReturn(new PhoneAccountHandle(
                ComponentName.unflattenFromString("foo/bar"), "90210"));

        psb.onCallAdded(call);
        verify(mComponentContextFixture.getTelephonyRegistryManager())
                .notifyOutgoingEmergencyCall(eq(SubscriptionManager.INVALID_SIM_SLOT_INDEX),
                        eq(SubscriptionManager.INVALID_SUBSCRIPTION_ID),
                        any(EmergencyNumber.class));
    }
}
