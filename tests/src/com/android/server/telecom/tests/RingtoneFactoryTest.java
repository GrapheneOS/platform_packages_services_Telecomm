/*
 * Copyright (C) 2025 The Android Open Source Project
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
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.media.Ringtone;
import android.net.Uri;
import android.os.UserHandle;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.CallsManager;
import com.android.server.telecom.Call;
import com.android.server.telecom.RingtoneFactory;
import com.android.server.telecom.flags.FeatureFlags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;

@RunWith(JUnit4.class)
public class RingtoneFactoryTest extends TelecomTestCase {
    @Mock private Uri mockCustomRingtoneUri;
    @Mock private CallsManager mockCallsManager;
    @Mock private FeatureFlags mockFeatureFlags;
    @Mock Call mockCall;
    private RingtoneFactory ringtoneFactory;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mContext = spy(mComponentContextFixture.getTestDouble().getApplicationContext());
        ringtoneFactory = new RingtoneFactory(mockCallsManager, mContext, mockFeatureFlags);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testCustomRingtoneAccessibleWhenUserOwnsCustomRingtone() throws Exception {
        // Current User is User 10:
        when(mockCall.getAssociatedUser()).thenReturn(new UserHandle(10));

        // Custom ringtone is owned by User 10:
        when(mockCall.getRingtone()).thenReturn(mockCustomRingtoneUri);
        when(mockCustomRingtoneUri.getUserInfo()).thenReturn("10");

        // Ensure access to the custom ringtone is allowed:
        Pair<Uri, Ringtone> ringtonePair = ringtoneFactory.getRingtone(mockCall, null, false);
        assertEquals(mockCustomRingtoneUri, ringtonePair.first);
    }

    @SmallTest
    @Test
    public void testCustomRingtoneNotAccessibleByOtherUser() throws Exception {
        // Current User is User 10:
        when(mockCall.getAssociatedUser()).thenReturn(new UserHandle(0));

        // Custom ringtone is owned by User 10:
        when(mockCall.getRingtone()).thenReturn(mockCustomRingtoneUri);
        when(mockCustomRingtoneUri.getUserInfo()).thenReturn("10");

        // Ensure access to the custom ringtone is NOT allowed:
        Pair<Uri, Ringtone> ringtonePair = ringtoneFactory.getRingtone(mockCall, null, false);
        assertNotEquals(mockCustomRingtoneUri, ringtonePair.first);
    }
}

