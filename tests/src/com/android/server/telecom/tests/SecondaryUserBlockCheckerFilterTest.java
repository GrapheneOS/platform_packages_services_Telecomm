/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static com.android.server.telecom.callfiltering.BlockCheckerFilter.STATUS_BLOCKED_IN_LIST;
import static com.android.server.telecom.callfiltering.BlockCheckerFilter.STATUS_NOT_BLOCKED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;

import androidx.test.filters.SmallTest;

import com.android.internal.telecom.flags.Flags;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallerInfoLookupHelper;
import com.android.server.telecom.callfiltering.BlockCheckerAdapter;
import com.android.server.telecom.callfiltering.BlockCheckerFilter;
import com.android.server.telecom.callfiltering.CallFilteringResult;
import com.android.server.telecom.util.CallerInfo;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Tests for selecting a full secondary user's own block list and contacts. */
@RunWith(JUnit4.class)
@SmallTest
public class SecondaryUserBlockCheckerFilterTest extends TelecomTestCase {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final UserHandle SECONDARY_USER = UserHandle.of(10);
    private static final Uri HANDLE = Uri.parse("tel:+12025550123");

    @Mock private Call mCall;
    @Mock private Context mMainContext;
    @Mock private Context mSecondaryContext;
    @Mock private UserManager mSecondaryUserManager;
    @Mock private CarrierConfigManager mCarrierConfigManager;
    @Mock private CallerInfoLookupHelper mCallerInfoLookupHelper;
    @Mock private BlockCheckerAdapter mBlockCheckerAdapter;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mContext.getSystemService(UserManager.class).getMainUser())
                .thenReturn(UserHandle.SYSTEM);
        when(mContext.createContextAsUser(UserHandle.SYSTEM, 0)).thenReturn(mMainContext);
        when(mContext.createContextAsUser(SECONDARY_USER, 0)).thenReturn(mSecondaryContext);
        when(mSecondaryContext.getSystemService(UserManager.class))
                .thenReturn(mSecondaryUserManager);
        when(mSecondaryUserManager.isUserOfType(UserManager.USER_TYPE_FULL_SECONDARY))
                .thenReturn(true);
        when(mCall.getAssociatedUser()).thenReturn(SECONDARY_USER);
        when(mCall.getHandle()).thenReturn(HANDLE);
        PersistableBundle config = new PersistableBundle();
        config.putBoolean(CarrierConfigManager.KEY_SUPPORT_ENHANCED_CALL_BLOCKING_BOOL, false);
        when(mCarrierConfigManager.getConfig()).thenReturn(config);
        when(mMainContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
                .thenReturn(mCarrierConfigManager);
        when(mSecondaryContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
                .thenReturn(mCarrierConfigManager);
    }

    @Test
    @EnableFlags(Flags.FLAG_GET_MAIN_USER_FOR_BLOCK_CHECKER)
    public void testSecondaryListIsUsedWithMainUserFlagEnabled() throws Exception {
        assertSecondaryListIsUsed();
    }

    @Test
    @DisableFlags(Flags.FLAG_GET_MAIN_USER_FOR_BLOCK_CHECKER)
    public void testSecondaryListIsUsedWithMainUserFlagDisabled() throws Exception {
        assertSecondaryListIsUsed();
    }

    @Test
    @EnableFlags(Flags.FLAG_GET_MAIN_USER_FOR_BLOCK_CHECKER)
    public void testEmptySecondaryListDoesNotInheritMainUserBlock() throws Exception {
        when(mBlockCheckerAdapter.getBlockStatus(eq(mMainContext),
                eq(HANDLE.getSchemeSpecificPart()), anyInt(), anyBoolean()))
                .thenReturn(STATUS_BLOCKED_IN_LIST);
        when(mBlockCheckerAdapter.getBlockStatus(eq(mSecondaryContext),
                eq(HANDLE.getSchemeSpecificPart()), anyInt(), anyBoolean()))
                .thenReturn(STATUS_NOT_BLOCKED);

        assertTrue(filter().shouldAllowCall);
        verifySecondaryListQueried();
    }

    @Test
    @EnableFlags(Flags.FLAG_GET_MAIN_USER_FOR_BLOCK_CHECKER)
    public void testMainUserRetainsExistingSelection() throws Exception {
        when(mSecondaryUserManager.isMainUser()).thenReturn(true);
        when(mBlockCheckerAdapter.getBlockStatus(eq(mMainContext),
                eq(HANDLE.getSchemeSpecificPart()), anyInt(), anyBoolean()))
                .thenReturn(STATUS_BLOCKED_IN_LIST);
        assertFalse(filter().shouldAllowCall);
        verify(mBlockCheckerAdapter, never()).getBlockStatus(eq(mSecondaryContext),
                eq(HANDLE.getSchemeSpecificPart()), anyInt(), anyBoolean());
    }

    @Test
    public void testUnavailableUserDoesNotFallBackToAnotherUsersList() throws Exception {
        when(mSecondaryUserManager.isUserOfType(UserManager.USER_TYPE_FULL_SECONDARY))
                .thenThrow(new IllegalStateException("User is no longer available"));
        assertTrue(filter().shouldAllowCall);
        verify(mBlockCheckerAdapter, never()).getBlockStatus(
                org.mockito.ArgumentMatchers.any(Context.class),
                org.mockito.ArgumentMatchers.anyString(), anyInt(), anyBoolean());
    }

    @Test
    @EnableFlags(Flags.FLAG_GET_MAIN_USER_FOR_BLOCK_CHECKER)
    public void testEnhancedBlockingUsesSecondaryContactsForKnownNumber() throws Exception {
        assertEnhancedLookupIsUserScoped(true);
    }

    @Test
    @EnableFlags(Flags.FLAG_GET_MAIN_USER_FOR_BLOCK_CHECKER)
    public void testEnhancedBlockingUsesSecondaryContactsForUnknownNumber() throws Exception {
        assertEnhancedLookupIsUserScoped(false);
    }

    private void assertEnhancedLookupIsUserScoped(boolean contactExists) throws Exception {
        PersistableBundle config = new PersistableBundle();
        config.putBoolean(CarrierConfigManager.KEY_SUPPORT_ENHANCED_CALL_BLOCKING_BOOL, true);
        when(mCarrierConfigManager.getConfig()).thenReturn(config);
        when(mCall.getHandlePresentation()).thenReturn(TelecomManager.PRESENTATION_ALLOWED);
        when(mBlockCheckerAdapter.getBlockStatus(eq(mSecondaryContext),
                eq(HANDLE.getSchemeSpecificPart()), eq(TelecomManager.PRESENTATION_ALLOWED),
                eq(contactExists)))
                .thenReturn(contactExists ? STATUS_NOT_BLOCKED : STATUS_BLOCKED_IN_LIST);

        BlockCheckerFilter filter = new BlockCheckerFilter(mContext, mCall,
                mCallerInfoLookupHelper, mBlockCheckerAdapter, mFeatureFlags);
        CompletionStage<CallFilteringResult> result = filter.startFilterLookup(
                new CallFilteringResult.Builder().setShouldAllowCall(true).build());
        ArgumentCaptor<CallerInfoLookupHelper.OnQueryCompleteListener> callback =
                ArgumentCaptor.forClass(CallerInfoLookupHelper.OnQueryCompleteListener.class);
        verify(mCallerInfoLookupHelper).startLookupForUser(eq(mSecondaryContext), eq(HANDLE),
                callback.capture());
        verify(mCallerInfoLookupHelper, never()).startLookup(eq(HANDLE),
                org.mockito.ArgumentMatchers.any());
        CallerInfo info = new CallerInfo();
        info.contactExists = contactExists;
        callback.getValue().onCallerInfoQueryComplete(HANDLE, info);
        assertEquals(contactExists, result.toCompletableFuture()
                .get(BlockCheckerFilter.CALLER_INFO_QUERY_TIMEOUT, TimeUnit.MILLISECONDS)
                .shouldAllowCall);
        verifySecondaryListQueried();
    }

    private void assertSecondaryListIsUsed() throws Exception {
        when(mBlockCheckerAdapter.getBlockStatus(eq(mMainContext),
                eq(HANDLE.getSchemeSpecificPart()), anyInt(), anyBoolean()))
                .thenReturn(STATUS_NOT_BLOCKED);
        when(mBlockCheckerAdapter.getBlockStatus(eq(mSecondaryContext),
                eq(HANDLE.getSchemeSpecificPart()), anyInt(), anyBoolean()))
                .thenReturn(STATUS_BLOCKED_IN_LIST);

        CallFilteringResult result = filter();
        assertFalse(result.shouldAllowCall);
        assertTrue(result.shouldReject);
        verifySecondaryListQueried();
    }

    private CallFilteringResult filter() throws Exception {
        // These tests cover explicit numbers only, not contact-based enhanced blocking.
        BlockCheckerFilter filter = new BlockCheckerFilter(mContext, mCall,
                mCallerInfoLookupHelper, mBlockCheckerAdapter, mFeatureFlags);
        return filter.startFilterLookup(new CallFilteringResult.Builder()
                        .setShouldAllowCall(true).build())
                .toCompletableFuture()
                .get(BlockCheckerFilter.CALLER_INFO_QUERY_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    private void verifySecondaryListQueried() {
        verify(mBlockCheckerAdapter).getBlockStatus(eq(mSecondaryContext),
                eq(HANDLE.getSchemeSpecificPart()), anyInt(), anyBoolean());
        verify(mBlockCheckerAdapter, never()).getBlockStatus(eq(mMainContext),
                eq(HANDLE.getSchemeSpecificPart()), anyInt(), anyBoolean());
    }
}
