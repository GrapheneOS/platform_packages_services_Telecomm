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

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.os.UserHandle;

import com.android.server.telecom.CallsManager;
import com.android.server.telecom.ConnectionServiceRepository;
import com.android.server.telecom.ConnectionServiceWrapper;
import com.android.server.telecom.PhoneAccountRegistrar;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.flags.FeatureFlags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ConnectionServiceWrapperTest extends TelecomTestCase {
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
     * Verify we don't crash when getting the last known cell id and there is no telephony.
     */
    @Test
    public void testGetLastKnownCellIdWhenNoTelephony() {
        ConnectionServiceWrapper wrapper = new ConnectionServiceWrapper(
                ComponentName.unflattenFromString("foo/baz"),
                mock(ConnectionServiceRepository.class),
                mock(PhoneAccountRegistrar.class),
                mock(CallsManager.class),
                mContext,
                new TelecomSystem.SyncRoot() {},
                UserHandle.CURRENT,
                mock(FeatureFlags.class));
        when(mComponentContextFixture.getTelephonyManager().getLastKnownCellIdentity())
                .thenThrow(new UnsupportedOperationException("Bee boop"));
        assertNull(wrapper.getLastKnownCellIdentity());
   }
}
