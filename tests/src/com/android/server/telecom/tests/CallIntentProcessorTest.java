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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.app.IntentForwarderActivity;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallIntentProcessor;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.DefaultDialerCache;
import com.android.server.telecom.PhoneNumberUtilsAdapter;
import com.android.server.telecom.TelephonyUtil;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoSession;

import java.util.concurrent.CompletableFuture;

/** Unit tests for CollIntentProcessor class. */
@RunWith(JUnit4.class)
public class CallIntentProcessorTest extends TelecomTestCase {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Mock
    private CallsManager mCallsManager;
    @Mock
    private DefaultDialerCache mDefaultDialerCache;
    @Mock
    private Context mMockCreateContextAsUser;
    @Mock
    private UserManager mMockCurrentUserManager;
    @Mock
    private PhoneNumberUtilsAdapter mPhoneNumberUtilsAdapter;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ResolveInfo mResolveInfo;
    @Mock
    private ComponentName mComponentName;
    @Mock
    private ComponentInfo mComponentInfo;
    @Mock
    private CompletableFuture<Call> mCall;
    private CallIntentProcessor mCallIntentProcessor;
    private static final UserHandle PRIVATE_SPACE_USERHANDLE = new UserHandle(12);
    private static final String TEST_PACKAGE_NAME = "testPackageName";
    private static final Uri TEST_PHONE_NUMBER = Uri.parse("tel:1234567890");
    private static final Uri TEST_EMERGENCY_PHONE_NUMBER = Uri.parse("tel:911");

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        when(mContext.createContextAsUser(any(UserHandle.class), eq(0))).thenReturn(
                mMockCreateContextAsUser);
        when(mMockCreateContextAsUser.getSystemService(UserManager.class)).thenReturn(
                mMockCurrentUserManager);
        mCallIntentProcessor = new CallIntentProcessor(mContext, mCallsManager, mDefaultDialerCache,
                mFeatureFlags);
        when(mFeatureFlags.telecomResolveHiddenDependencies()).thenReturn(false);
        when(mCallsManager.getPhoneNumberUtilsAdapter()).thenReturn(mPhoneNumberUtilsAdapter);
        when(mPhoneNumberUtilsAdapter.isUriNumber(anyString())).thenReturn(true);
        when(mCallsManager.startOutgoingCall(any(Uri.class), any(), any(Bundle.class),
                any(UserHandle.class), any(Intent.class), anyString())).thenReturn(mCall);
        when(mCall.thenAccept(any())).thenReturn(null);
    }

    @Test
    public void testNonPrivateSpaceCall_noConsentDialogShown() {
        setPrivateSpaceFlagsEnabled();

        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(TEST_PHONE_NUMBER);
        intent.putExtra(CallIntentProcessor.KEY_INITIATING_USER, UserHandle.CURRENT);
        when(mCallsManager.isSelfManaged(any(), eq(UserHandle.CURRENT))).thenReturn(false);

        mCallIntentProcessor.processIntent(intent, TEST_PACKAGE_NAME);

        verify(mContext, never()).startActivityAsUser(any(Intent.class), any(UserHandle.class));

        // Verify that the call proceeds as normal since the dialog was not shown
        verify(mCallsManager).startOutgoingCall(any(Uri.class), any(), any(Bundle.class),
                eq(UserHandle.CURRENT), eq(intent), eq(TEST_PACKAGE_NAME));
    }

    @Test
    public void testPrivateSpaceCall_isSelfManaged_noDialogShown() {
        setPrivateSpaceFlagsEnabled();
        markInitiatingUserAsPrivateProfile();
        resolveAsIntentForwarderActivity();

        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(TEST_PHONE_NUMBER);
        intent.putExtra(CallIntentProcessor.KEY_INITIATING_USER, PRIVATE_SPACE_USERHANDLE);
        when(mCallsManager.isSelfManaged(any(), eq(PRIVATE_SPACE_USERHANDLE))).thenReturn(true);

        mCallIntentProcessor.processIntent(intent, TEST_PACKAGE_NAME);

        verify(mContext, never()).startActivityAsUser(any(Intent.class),
                eq(PRIVATE_SPACE_USERHANDLE));

        // Verify that the call proceeds as normal since the dialog was not shown
        verify(mCallsManager).startOutgoingCall(any(Uri.class), any(), any(Bundle.class),
                eq(PRIVATE_SPACE_USERHANDLE), eq(intent), eq(TEST_PACKAGE_NAME));
    }

    @Test
    public void testPrivateSpaceCall_isEmergency_noDialogShown() {
        MockitoSession session = ExtendedMockito.mockitoSession().mockStatic(
                TelephonyUtil.class).startMocking();
        ExtendedMockito.doReturn(true).when(
                () -> TelephonyUtil.shouldProcessAsEmergency(any(), any()));

        setPrivateSpaceFlagsEnabled();
        markInitiatingUserAsPrivateProfile();
        resolveAsIntentForwarderActivity();

        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(TEST_EMERGENCY_PHONE_NUMBER);
        intent.putExtra(CallIntentProcessor.KEY_INITIATING_USER, PRIVATE_SPACE_USERHANDLE);
        when(mCallsManager.isSelfManaged(any(), eq(PRIVATE_SPACE_USERHANDLE))).thenReturn(false);

        mCallIntentProcessor.processIntent(intent, TEST_PACKAGE_NAME);

        verify(mContext, never()).startActivityAsUser(any(Intent.class),
                eq(PRIVATE_SPACE_USERHANDLE));
        session.finishMocking();
    }

    @Test
    public void testPrivateSpaceCall_showConsentDialog() {
        setPrivateSpaceFlagsEnabled();
        markInitiatingUserAsPrivateProfile();
        resolveAsIntentForwarderActivity();

        Intent intent = new Intent(Intent.ACTION_CALL);
        intent.setData(TEST_PHONE_NUMBER);
        intent.putExtra(CallIntentProcessor.KEY_INITIATING_USER, PRIVATE_SPACE_USERHANDLE);
        when(mCallsManager.isSelfManaged(any(), eq(PRIVATE_SPACE_USERHANDLE))).thenReturn(false);

        mCallIntentProcessor.processIntent(intent, TEST_PACKAGE_NAME);

        // Consent dialog should be shown
        verify(mContext).startActivityAsUser(any(Intent.class), eq(PRIVATE_SPACE_USERHANDLE));

        /// Verify that the call does not proceeds as normal since the dialog was shown
        verify(mCallsManager, never()).startOutgoingCall(any(), any(), any(), any(), any(),
                anyString());
    }

    private void setPrivateSpaceFlagsEnabled() {
        mSetFlagsRule.enableFlags(android.multiuser.Flags.FLAG_ENABLE_PRIVATE_SPACE_FEATURES,
                android.multiuser.Flags.FLAG_ENABLE_PRIVATE_SPACE_INTENT_REDIRECTION);
    }

    private void markInitiatingUserAsPrivateProfile() {
        when(mMockCurrentUserManager.isPrivateProfile()).thenReturn(true);
    }

    private void resolveAsIntentForwarderActivity() {
        when(mComponentName.getShortClassName()).thenReturn(
                IntentForwarderActivity.FORWARD_INTENT_TO_PARENT);
        when(mComponentInfo.getComponentName()).thenReturn(mComponentName);
        when(mResolveInfo.getComponentInfo()).thenReturn(mComponentInfo);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        when(mPackageManager.resolveActivityAsUser(any(Intent.class),
                any(PackageManager.ResolveInfoFlags.class),
                eq(PRIVATE_SPACE_USERHANDLE.getIdentifier()))).thenReturn(mResolveInfo);
    }
}