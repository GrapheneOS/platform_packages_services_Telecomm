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

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.android.server.telecom.TelephonyUtil;

import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TelephonyUtilTest extends TelecomTestCase {
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
     * Verifies that the helper method shouldProcessAsEmergency does not crash when telephony is not
     * present and returns "false" instead.
     */
    @Test
    public void testShouldProcessAsEmergencyWithNoTelephonyCalling() {
        when(mComponentContextFixture.getTelephonyManager().isEmergencyNumber(anyString()))
                .thenThrow(new UnsupportedOperationException("Bee boop"));
        assertFalse(TelephonyUtil.shouldProcessAsEmergency(mContext, Uri.parse("tel:911")));
    }
}
