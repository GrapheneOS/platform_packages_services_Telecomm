/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import static com.android.server.telecom.CallAudioRouteAdapter.ACTIVE_FOCUS;
import static com.android.server.telecom.CallAudioRouteAdapter.BT_ACTIVE_DEVICE_GONE;
import static com.android.server.telecom.CallAudioRouteAdapter.BT_ACTIVE_DEVICE_PRESENT;
import static com.android.server.telecom.CallAudioRouteAdapter.BT_AUDIO_CONNECTED;
import static com.android.server.telecom.CallAudioRouteAdapter.BT_DEVICE_ADDED;
import static com.android.server.telecom.CallAudioRouteAdapter.BT_DEVICE_REMOVED;
import static com.android.server.telecom.CallAudioRouteAdapter.CONNECT_DOCK;
import static com.android.server.telecom.CallAudioRouteAdapter.CONNECT_WIRED_HEADSET;
import static com.android.server.telecom.CallAudioRouteAdapter.DISCONNECT_DOCK;
import static com.android.server.telecom.CallAudioRouteAdapter.DISCONNECT_WIRED_HEADSET;
import static com.android.server.telecom.CallAudioRouteAdapter.MUTE_OFF;
import static com.android.server.telecom.CallAudioRouteAdapter.MUTE_ON;
import static com.android.server.telecom.CallAudioRouteAdapter.NO_FOCUS;
import static com.android.server.telecom.CallAudioRouteAdapter.RINGING_FOCUS;
import static com.android.server.telecom.CallAudioRouteAdapter.SPEAKER_OFF;
import static com.android.server.telecom.CallAudioRouteAdapter.SPEAKER_ON;
import static com.android.server.telecom.CallAudioRouteAdapter.STREAMING_FORCE_DISABLED;
import static com.android.server.telecom.CallAudioRouteAdapter.STREAMING_FORCE_ENABLED;
import static com.android.server.telecom.CallAudioRouteAdapter.SWITCH_BASELINE_ROUTE;
import static com.android.server.telecom.CallAudioRouteAdapter.SWITCH_FOCUS;
import static com.android.server.telecom.CallAudioRouteAdapter.USER_SWITCH_BLUETOOTH;
import static com.android.server.telecom.CallAudioRouteAdapter.USER_SWITCH_EARPIECE;
import static com.android.server.telecom.CallAudioRouteAdapter.USER_SWITCH_HEADSET;
import static com.android.server.telecom.CallAudioRouteAdapter.USER_SWITCH_SPEAKER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.audiopolicy.AudioProductStrategy;
import android.os.UserHandle;
import android.telecom.CallAudioState;
import android.telecom.VideoProfile;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.AudioRoute;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallAudioManager;
import com.android.server.telecom.CallAudioRouteController;
import com.android.server.telecom.CallAudioRouteStateMachine;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.PendingAudioRoute;
import com.android.server.telecom.StatusBarNotifier;
import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.WiredHeadsetManager;
import com.android.server.telecom.bluetooth.BluetoothDeviceManager;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;
import com.android.server.telecom.metrics.TelecomMetricsController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(JUnit4.class)
public class CallAudioRouteControllerTest extends TelecomTestCase {
    private CallAudioRouteController mController;
    @Mock WiredHeadsetManager mWiredHeadsetManager;
    @Mock AudioManager mAudioManager;
    @Mock AudioDeviceInfo mEarpieceDeviceInfo;
    @Mock CallsManager mCallsManager;
    @Mock CallAudioManager.AudioServiceFactory mAudioServiceFactory;
    @Mock IAudioService mAudioService;
    @Mock BluetoothRouteManager mBluetoothRouteManager;
    @Mock BluetoothDeviceManager mBluetoothDeviceManager;
    @Mock BluetoothAdapter mBluetoothAdapter;
    @Mock StatusBarNotifier mockStatusBarNotifier;
    @Mock AudioDeviceInfo mAudioDeviceInfo;
    @Mock BluetoothLeAudio mBluetoothLeAudio;
    @Mock CallAudioManager mCallAudioManager;
    @Mock Call mCall;
    @Mock private TelecomSystem.SyncRoot mLock;
    @Mock private TelecomMetricsController mMockTelecomMetricsController;
    private AudioRoute mEarpieceRoute;
    private AudioRoute mSpeakerRoute;
    private boolean mOverrideSpeakerToBus;
    private static final String BT_ADDRESS_1 = "00:00:00:00:00:01";
    private static final BluetoothDevice BLUETOOTH_DEVICE_1 =
            BluetoothRouteManagerTest.makeBluetoothDevice("00:00:00:00:00:01");
    private static final Set<BluetoothDevice> BLUETOOTH_DEVICES;
    static {
        BLUETOOTH_DEVICES = new HashSet<>();
        BLUETOOTH_DEVICES.add(BLUETOOTH_DEVICE_1);
    }
    private static final int TEST_TIMEOUT = 500;
    AudioRoute.Factory mAudioRouteFactory = new AudioRoute.Factory() {
        @Override
        public AudioRoute create(@AudioRoute.AudioRouteType int type, String bluetoothAddress,
                                 AudioManager audioManager) {
            if (mOverrideSpeakerToBus && type == AudioRoute.TYPE_SPEAKER) {
                type = AudioRoute.TYPE_BUS;
            }
            return new AudioRoute(type, bluetoothAddress, mAudioDeviceInfo);
        }
    };

    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(mWiredHeadsetManager.isPluggedIn()).thenReturn(false);
        when(mEarpieceDeviceInfo.getType()).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
        when(mAudioManager.getDevices(eq(AudioManager.GET_DEVICES_OUTPUTS))).thenReturn(
                new AudioDeviceInfo[] {
                        mEarpieceDeviceInfo
                });
        when(mAudioManager.getPreferredDeviceForStrategy(nullable(AudioProductStrategy.class)))
                .thenReturn(null);
        when(mAudioManager.getAvailableCommunicationDevices())
                .thenReturn(List.of(mAudioDeviceInfo));
        when(mAudioManager.getCommunicationDevice()).thenReturn(mAudioDeviceInfo);
        when(mAudioManager.setCommunicationDevice(any(AudioDeviceInfo.class)))
                .thenReturn(true);
        when(mAudioServiceFactory.getAudioService()).thenReturn(mAudioService);
        when(mContext.getAttributionTag()).thenReturn("");
        doNothing().when(mCallsManager).onCallAudioStateChanged(any(CallAudioState.class),
                any(CallAudioState.class));
        when(mCallsManager.getCurrentUserHandle()).thenReturn(
                new UserHandle(UserHandle.USER_SYSTEM));
        when(mCallsManager.getLock()).thenReturn(mLock);
        when(mCallsManager.getForegroundCall()).thenReturn(mCall);
        when(mBluetoothRouteManager.getDeviceManager()).thenReturn(mBluetoothDeviceManager);
        when(mBluetoothDeviceManager.connectAudio(any(BluetoothDevice.class), anyInt()))
                .thenReturn(true);
        when(mBluetoothDeviceManager.getBluetoothAdapter()).thenReturn(mBluetoothAdapter);
        when(mBluetoothAdapter.getActiveDevices(anyInt())).thenReturn(List.of(BLUETOOTH_DEVICE_1));
        when(mBluetoothDeviceManager.getLeAudioService()).thenReturn(mBluetoothLeAudio);
        when(mBluetoothLeAudio.getGroupId(any(BluetoothDevice.class))).thenReturn(1);
        when(mBluetoothLeAudio.getConnectedGroupLeadDevice(anyInt()))
                .thenReturn(BLUETOOTH_DEVICE_1);
        when(mAudioDeviceInfo.getAddress()).thenReturn(BT_ADDRESS_1);
        mController = new CallAudioRouteController(mContext, mCallsManager, mAudioServiceFactory,
                mAudioRouteFactory, mWiredHeadsetManager, mBluetoothRouteManager,
                mockStatusBarNotifier, mFeatureFlags, mMockTelecomMetricsController);
        mController.setAudioRouteFactory(mAudioRouteFactory);
        mController.setAudioManager(mAudioManager);
        mEarpieceRoute = new AudioRoute(AudioRoute.TYPE_EARPIECE, null, null);
        mSpeakerRoute = new AudioRoute(AudioRoute.TYPE_SPEAKER, null, null);
        mOverrideSpeakerToBus = false;
        mController.setCallAudioManager(mCallAudioManager);
        when(mCallAudioManager.getForegroundCall()).thenReturn(mCall);
        when(mCall.getVideoState()).thenReturn(VideoProfile.STATE_AUDIO_ONLY);
        when(mCall.getSupportedAudioRoutes()).thenReturn(CallAudioState.ROUTE_ALL);
        when(mFeatureFlags.ignoreAutoRouteToWatchDevice()).thenReturn(false);
        when(mFeatureFlags.useRefactoredAudioRouteSwitching()).thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        mController.getAdapterHandler().getLooper().quit();
        mController.getAdapterHandler().getLooper().getThread().join();
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testInitializeWithEarpiece() {
        mController.initialize();
        assertEquals(mEarpieceRoute, mController.getCurrentRoute());
        assertEquals(2, mController.getAvailableRoutes().size());
        assertTrue(mController.getAvailableRoutes().contains(mSpeakerRoute));
    }

    @SmallTest
    @Test
    public void testInitializeWithoutEarpiece() {
        when(mAudioManager.getDevices(eq(AudioManager.GET_DEVICES_OUTPUTS))).thenReturn(
                new AudioDeviceInfo[] {});

        mController.initialize();
        assertEquals(mSpeakerRoute, mController.getCurrentRoute());
    }

    @SmallTest
    @Test
    public void testInitializeWithWiredHeadset() {
        AudioRoute wiredHeadsetRoute = new AudioRoute(AudioRoute.TYPE_WIRED, null, null);
        when(mWiredHeadsetManager.isPluggedIn()).thenReturn(true);
        mController.initialize();
        assertEquals(wiredHeadsetRoute, mController.getCurrentRoute());
        assertEquals(2, mController.getAvailableRoutes().size());
        assertTrue(mController.getAvailableRoutes().contains(mSpeakerRoute));
    }

    @SmallTest
    @Test
    public void testNormalCallRouteToEarpiece() {
        mController.initialize();
        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, ACTIVE_FOCUS, 0);
        // Verify that pending audio destination route is set to speaker. This will trigger pending
        // message to wait for SPEAKER_ON message once communication device is set before routing.
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);
        PendingAudioRoute pendingRoute = mController.getPendingAudioRoute();
        assertEquals(AudioRoute.TYPE_EARPIECE, pendingRoute.getDestRoute().getType());

        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testActiveFocusAudioRouting() {
        mController.initialize();
        // Connect wired headset
        mController.sendMessageWithSessionInfo(CONNECT_WIRED_HEADSET);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Explicitly switch to speaker
        mController.sendMessageWithSessionInfo(USER_SWITCH_SPEAKER);
        mController.sendMessageWithSessionInfo(SPEAKER_ON);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
        // Expect that active focus received from a new active call will force route to baseline
        // (in this case, this should be the wired headset).
        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, ACTIVE_FOCUS, 0);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Switch back to speaker and send active focus for end tone to confirm that audio routing
        // doesn't fall back onto the baseline.
        mController.sendMessageWithSessionInfo(USER_SWITCH_SPEAKER);
        mController.sendMessageWithSessionInfo(SPEAKER_ON);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, ACTIVE_FOCUS, 1);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testVideoCallHoldRouteToEarpiece() {
        mController.initialize();
        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, ACTIVE_FOCUS, 0);
        // Verify that pending audio destination route is not defaulted to speaker when a video call
        // is not the foreground call.
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);
        PendingAudioRoute pendingRoute = mController.getPendingAudioRoute();
        assertEquals(AudioRoute.TYPE_EARPIECE, pendingRoute.getDestRoute().getType());
    }

    @SmallTest
    @Test
    public void testVideoCallRouteToSpeaker() {
        when(mCall.getVideoState()).thenReturn(VideoProfile.STATE_BIDIRECTIONAL);
        mController.initialize();
        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, ACTIVE_FOCUS, 0);
        // Verify that pending audio destination route is set to speaker. This will trigger pending
        // message to wait for SPEAKER_ON message once communication device is set before routing.
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);
        PendingAudioRoute pendingRoute = mController.getPendingAudioRoute();
        assertEquals(AudioRoute.TYPE_SPEAKER, pendingRoute.getDestRoute().getType());

        // Mock SPEAKER_ON message received by controller.
        mController.sendMessageWithSessionInfo(SPEAKER_ON);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Verify that audio is routed to wired headset if it's present.
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        mController.sendMessageWithSessionInfo(CONNECT_WIRED_HEADSET);
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);
        mController.sendMessageWithSessionInfo(SPEAKER_OFF);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testActiveDeactivateBluetoothDevice() {
        mController.initialize();
        mController.sendMessageWithSessionInfo(BT_DEVICE_ADDED, AudioRoute.TYPE_BLUETOOTH_SCO,
                BLUETOOTH_DEVICE_1);

        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, BLUETOOTH_DEVICE_1, BLUETOOTH_DEVICES);
        mController.sendMessageWithSessionInfo(BT_ACTIVE_DEVICE_PRESENT,
                AudioRoute.TYPE_BLUETOOTH_SCO, BT_ADDRESS_1);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, null, BLUETOOTH_DEVICES);
        mController.sendMessageWithSessionInfo(BT_ACTIVE_DEVICE_GONE,
                AudioRoute.TYPE_BLUETOOTH_SCO);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testSwitchFocusForBluetoothDeviceSupportInbandRinging() {
        when(mBluetoothRouteManager.isInbandRingEnabled(eq(BLUETOOTH_DEVICE_1))).thenReturn(true);

        mController.initialize();
        mController.sendMessageWithSessionInfo(BT_DEVICE_ADDED, AudioRoute.TYPE_BLUETOOTH_SCO,
                BLUETOOTH_DEVICE_1);

        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, BLUETOOTH_DEVICE_1, BLUETOOTH_DEVICES);
        mController.sendMessageWithSessionInfo(BT_ACTIVE_DEVICE_PRESENT,
                AudioRoute.TYPE_BLUETOOTH_SCO, BT_ADDRESS_1);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
        assertFalse(mController.isActive());

        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, RINGING_FOCUS, 0);
        verify(mBluetoothDeviceManager, timeout(TEST_TIMEOUT))
                .connectAudio(BLUETOOTH_DEVICE_1, AudioRoute.TYPE_BLUETOOTH_SCO);
        assertTrue(mController.isActive());

        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, ACTIVE_FOCUS, 0);
        assertTrue(mController.isActive());

        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, NO_FOCUS, 0);
        // Ensure we tell the CallAudioManager that audio operations are done so that we can ensure
        // audio focus is relinquished.
        verify(mCallAudioManager, timeout(TEST_TIMEOUT)).notifyAudioOperationsComplete();

        // Ensure the BT device is disconnected.
        verify(mBluetoothDeviceManager, timeout(TEST_TIMEOUT).atLeastOnce()).disconnectSco();
        assertFalse(mController.isActive());
    }

    @SmallTest
    @Test
    public void testConnectAndDisconnectWiredHeadset() {
        mController.initialize();
        mController.sendMessageWithSessionInfo(CONNECT_WIRED_HEADSET);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(DISCONNECT_WIRED_HEADSET);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testConnectAndDisconnectDock() {
        mController.initialize();
        mController.sendMessageWithSessionInfo(CONNECT_DOCK);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(DISCONNECT_DOCK);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testSpeakerToggle() {
        mController.initialize();
        mController.setActive(true);
        mController.sendMessageWithSessionInfo(SPEAKER_ON);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(SPEAKER_OFF);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testSpeakerToggleWhenDockConnected() {
        mController.initialize();
        mController.setActive(true);
        mController.sendMessageWithSessionInfo(CONNECT_DOCK);
        mController.sendMessageWithSessionInfo(SPEAKER_ON);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(SPEAKER_ON);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(SPEAKER_OFF);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testSwitchEarpiece() {
        mController.initialize();
        mController.sendMessageWithSessionInfo(SPEAKER_ON);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(USER_SWITCH_EARPIECE);
        mController.sendMessageWithSessionInfo(SPEAKER_OFF);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testSwitchBluetooth() {
        doAnswer(invocation -> {
            mController.sendMessageWithSessionInfo(BT_AUDIO_CONNECTED, 0, BLUETOOTH_DEVICE_1);
            return true;
        }).when(mAudioManager).setCommunicationDevice(nullable(AudioDeviceInfo.class));

        mController.initialize();
        mController.setActive(true);
        mController.sendMessageWithSessionInfo(BT_DEVICE_ADDED, AudioRoute.TYPE_BLUETOOTH_SCO,
                BLUETOOTH_DEVICE_1);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, null, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(USER_SWITCH_BLUETOOTH, 0,
                BLUETOOTH_DEVICE_1.getAddress());
        mController.sendMessageWithSessionInfo(BT_AUDIO_CONNECTED, 0, BLUETOOTH_DEVICE_1);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, BLUETOOTH_DEVICE_1, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testSwitchSpeakerAndHeadset() {
        mController.initialize();
        mController.sendMessageWithSessionInfo(CONNECT_WIRED_HEADSET);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(USER_SWITCH_SPEAKER);
        mController.sendMessageWithSessionInfo(SPEAKER_ON);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(USER_SWITCH_HEADSET);
        mController.sendMessageWithSessionInfo(SPEAKER_OFF);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testEnableAndDisableStreaming() {
        mController.initialize();
        mController.sendMessageWithSessionInfo(STREAMING_FORCE_ENABLED);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_STREAMING,
                CallAudioState.ROUTE_STREAMING, null, new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(SPEAKER_ON);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(CONNECT_WIRED_HEADSET);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(STREAMING_FORCE_DISABLED);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testStreamRingMuteChange() {
        mController.initialize();

        // Make sure we register a receiver for the STREAM_MUTE_CHANGED_ACTION so we can see if the
        // ring stream unmutes.
        ArgumentCaptor<BroadcastReceiver> brCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        ArgumentCaptor<IntentFilter> filterCaptor = ArgumentCaptor.forClass(IntentFilter.class);
        verify(mContext, times(3)).registerReceiver(brCaptor.capture(), filterCaptor.capture());
        boolean foundValid = false;
        for (int ix = 0; ix < brCaptor.getAllValues().size(); ix++) {
            BroadcastReceiver receiver = brCaptor.getAllValues().get(ix);
            IntentFilter filter = filterCaptor.getAllValues().get(ix);
            if (!filter.hasAction(AudioManager.STREAM_MUTE_CHANGED_ACTION)) {
                continue;
            }

            // Fake out a call to the broadcast receiver and make sure we call into audio manager
            // to trigger re-evaluation of ringing.
            Intent intent = new Intent(AudioManager.STREAM_MUTE_CHANGED_ACTION);
            intent.putExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, false);
            intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_RING);
            receiver.onReceive(mContext, intent);
            verify(mCallAudioManager).onRingerModeChange();
            foundValid = true;
        }
        assertTrue(foundValid);
    }


    @SmallTest
    @Test
    public void testToggleMute() throws Exception {
        when(mAudioManager.isMicrophoneMute()).thenReturn(false);
        mController.initialize();
        mController.setActive(true);

        mController.sendMessageWithSessionInfo(MUTE_ON);
        CallAudioState expectedState = new CallAudioState(true, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mAudioService, timeout(TEST_TIMEOUT)).setMicrophoneMute(eq(true), anyString(),
                anyInt(), anyString());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        when(mAudioManager.isMicrophoneMute()).thenReturn(true);
        mController.sendMessageWithSessionInfo(MUTE_OFF);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mAudioService, timeout(TEST_TIMEOUT)).setMicrophoneMute(eq(false), anyString(),
                anyInt(), anyString());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testMuteOffAfterCallEnds() throws Exception {
        when(mAudioManager.isMicrophoneMute()).thenReturn(false);
        mController.initialize();
        mController.setActive(true);

        mController.sendMessageWithSessionInfo(MUTE_ON);
        CallAudioState expectedState = new CallAudioState(true, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mAudioService, timeout(TEST_TIMEOUT)).setMicrophoneMute(eq(true), anyString(),
                anyInt(), anyString());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Switch to NO_FOCUS to indicate call termination and verify mute is reset.
        when(mAudioManager.isMicrophoneMute()).thenReturn(true);
        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, NO_FOCUS, 0);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        verify(mAudioService, timeout(TEST_TIMEOUT)).setMicrophoneMute(eq(false), anyString(),
                anyInt(), anyString());
        verify(mCallsManager, timeout(TEST_TIMEOUT).atLeastOnce()).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
        // Ensure we tell the CallAudioManager that audio operations are done so that we can ensure
        // audio focus is relinquished.
        verify(mCallAudioManager, timeout(TEST_TIMEOUT)).notifyAudioOperationsComplete();
    }

    @SmallTest
    @Test
    public void testIgnoreAutoRouteToWatch() {
        when(mFeatureFlags.ignoreAutoRouteToWatchDevice()).thenReturn(true);
        when(mBluetoothRouteManager.isWatch(any(BluetoothDevice.class))).thenReturn(true);

        mController.initialize();
        mController.sendMessageWithSessionInfo(BT_DEVICE_ADDED, AudioRoute.TYPE_BLUETOOTH_SCO,
                BLUETOOTH_DEVICE_1);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, null, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Connect wired headset.
        mController.sendMessageWithSessionInfo(CONNECT_WIRED_HEADSET);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_WIRED_HEADSET,
                CallAudioState.ROUTE_WIRED_HEADSET | CallAudioState.ROUTE_SPEAKER
                        | CallAudioState.ROUTE_BLUETOOTH, null, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Disconnect wired headset and ensure Telecom routes to earpiece instead of the BT route.
        mController.sendMessageWithSessionInfo(DISCONNECT_WIRED_HEADSET);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER
                        | CallAudioState.ROUTE_BLUETOOTH, null , BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testConnectDisconnectScoDuringCall() {
        verifyConnectBluetoothDevice(AudioRoute.TYPE_BLUETOOTH_SCO);
        verifyDisconnectBluetoothDevice(AudioRoute.TYPE_BLUETOOTH_SCO);
    }

    @SmallTest
    @Test
    public void testConnectAndDisconnectLeDeviceDuringCall() {
        when(mBluetoothLeAudio.getConnectedGroupLeadDevice(anyInt()))
                .thenReturn(BLUETOOTH_DEVICE_1);
        verifyConnectBluetoothDevice(AudioRoute.TYPE_BLUETOOTH_LE);
        verifyDisconnectBluetoothDevice(AudioRoute.TYPE_BLUETOOTH_LE);
    }

    @SmallTest
    @Test
    public void testConnectAndDisconnectHearingAidDuringCall() {
        verifyConnectBluetoothDevice(AudioRoute.TYPE_BLUETOOTH_HA);
        verifyDisconnectBluetoothDevice(AudioRoute.TYPE_BLUETOOTH_HA);
    }

    @SmallTest
    @Test
    public void testSwitchBetweenLeAndScoDevices() {
        when(mBluetoothLeAudio.getConnectedGroupLeadDevice(anyInt()))
                .thenReturn(BLUETOOTH_DEVICE_1);
        verifyConnectBluetoothDevice(AudioRoute.TYPE_BLUETOOTH_LE);
        BluetoothDevice scoDevice =
                BluetoothRouteManagerTest.makeBluetoothDevice("00:00:00:00:00:03");
        BLUETOOTH_DEVICES.add(scoDevice);

        // Add SCO device.
        mController.sendMessageWithSessionInfo(BT_DEVICE_ADDED, AudioRoute.TYPE_BLUETOOTH_SCO,
                scoDevice);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, BLUETOOTH_DEVICE_1, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Switch to SCO and verify active device is updated.
        mController.sendMessageWithSessionInfo(USER_SWITCH_BLUETOOTH, 0, scoDevice.getAddress());
        mController.sendMessageWithSessionInfo(BT_AUDIO_CONNECTED, 0, scoDevice);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, scoDevice, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Disconnect SCO and verify audio routed back to LE audio.
        BLUETOOTH_DEVICES.remove(scoDevice);
        mController.sendMessageWithSessionInfo(BT_DEVICE_REMOVED, AudioRoute.TYPE_BLUETOOTH_SCO,
                scoDevice);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, BLUETOOTH_DEVICE_1, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testFallbackWhenBluetoothConnectionFails() {
        when(mBluetoothDeviceManager.connectAudio(any(BluetoothDevice.class), anyInt()))
                .thenReturn(false);

        AudioDeviceInfo mockAudioDeviceInfo = mock(AudioDeviceInfo.class);
        when(mAudioManager.getCommunicationDevice()).thenReturn(mockAudioDeviceInfo);
        verifyConnectBluetoothDevice(AudioRoute.TYPE_BLUETOOTH_LE);
        BluetoothDevice scoDevice =
                BluetoothRouteManagerTest.makeBluetoothDevice("00:00:00:00:00:03");
        BLUETOOTH_DEVICES.add(scoDevice);

        // Add SCO device.
        mController.sendMessageWithSessionInfo(BT_DEVICE_ADDED, AudioRoute.TYPE_BLUETOOTH_SCO,
                scoDevice);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, BLUETOOTH_DEVICE_1, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Switch to SCO but reject connection and make sure audio is routed back to LE device.
        mController.sendMessageWithSessionInfo(BT_ACTIVE_DEVICE_PRESENT,
                AudioRoute.TYPE_BLUETOOTH_SCO, scoDevice.getAddress());
        verify(mBluetoothDeviceManager, timeout(TEST_TIMEOUT))
                .connectAudio(scoDevice, AudioRoute.TYPE_BLUETOOTH_SCO);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, BLUETOOTH_DEVICE_1, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT).atLeastOnce()).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Cleanup supported devices for next test
        BLUETOOTH_DEVICES.remove(scoDevice);
    }

    @SmallTest
    @Test
    public void testIgnoreLeRouteWhenServiceUnavailable() {
        when(mBluetoothLeAudio.getConnectedGroupLeadDevice(anyInt()))
                .thenReturn(BLUETOOTH_DEVICE_1);
        verifyConnectBluetoothDevice(AudioRoute.TYPE_BLUETOOTH_LE);

        when(mBluetoothDeviceManager.getLeAudioService()).thenReturn(null);
        // Switch baseline to verify that we don't route back to LE audio this time.
        mController.sendMessageWithSessionInfo(SWITCH_BASELINE_ROUTE, 0, (String) null);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, null, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT).atLeastOnce()).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testRouteFromBtSwitchInRingingSelected() {
        when(mFeatureFlags.ignoreAutoRouteToWatchDevice()).thenReturn(true);
        when(mBluetoothRouteManager.isWatch(any(BluetoothDevice.class))).thenReturn(true);
        when(mBluetoothRouteManager.isInbandRingEnabled(eq(BLUETOOTH_DEVICE_1))).thenReturn(false);

        mController.initialize();
        mController.sendMessageWithSessionInfo(BT_DEVICE_ADDED, AudioRoute.TYPE_BLUETOOTH_SCO,
            BLUETOOTH_DEVICE_1);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
            CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                | CallAudioState.ROUTE_SPEAKER, null, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
            any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, RINGING_FOCUS, 0);
        assertFalse(mController.isActive());

        // BT device should be cached. Verify routing into BT device once focus becomes active.
        mController.sendMessageWithSessionInfo(USER_SWITCH_BLUETOOTH, 0,
            BLUETOOTH_DEVICE_1.getAddress());
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
            CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                | CallAudioState.ROUTE_SPEAKER, BLUETOOTH_DEVICE_1, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
            any(CallAudioState.class), eq(expectedState));
        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, ACTIVE_FOCUS, 0);
        mController.sendMessageWithSessionInfo(BT_AUDIO_CONNECTED, 0, BLUETOOTH_DEVICE_1);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
            CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                | CallAudioState.ROUTE_SPEAKER, BLUETOOTH_DEVICE_1, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
            any(CallAudioState.class), eq(expectedState));
    }

    @SmallTest
    @Test
    public void testUpdateRouteForForeground() {
        mController.initialize();
        mController.sendMessageWithSessionInfo(BT_DEVICE_ADDED, AudioRoute.TYPE_BLUETOOTH_SCO,
                BLUETOOTH_DEVICE_1);

        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, BLUETOOTH_DEVICE_1, BLUETOOTH_DEVICES);
        mController.sendMessageWithSessionInfo(BT_ACTIVE_DEVICE_PRESENT,
                AudioRoute.TYPE_BLUETOOTH_SCO, BT_ADDRESS_1);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Ensure that supported routes is updated along with the current route to reflect the
        // foreground call's supported audio routes.
        when(mCall.getSupportedAudioRoutes()).thenReturn(CallAudioState.ROUTE_SPEAKER);
        mController.sendMessageWithSessionInfo(
                CallAudioRouteStateMachine.UPDATE_SYSTEM_AUDIO_ROUTE);
        mController.sendMessageWithSessionInfo(SPEAKER_ON);
        expectedState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_SPEAKER, null, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
        assertEquals(3, mController.getAvailableRoutes().size());
        assertEquals(1, mController.getCallSupportedRoutes().size());
    }

    @SmallTest
    @Test
    public void testRouteToBusForAuto() {
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                .thenReturn(new AudioDeviceInfo[0]);
        mOverrideSpeakerToBus = true;
        mController.initialize();

        mController.sendMessageWithSessionInfo(SWITCH_FOCUS, ACTIVE_FOCUS, 0);
        waitForHandlerAction(mController.getAdapterHandler(), TEST_TIMEOUT);
        PendingAudioRoute pendingRoute = mController.getPendingAudioRoute();
        assertEquals(AudioRoute.TYPE_BUS, pendingRoute.getDestRoute().getType());

        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_SPEAKER,
                CallAudioState.ROUTE_SPEAKER, null, new HashSet<>());
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Ensure that turning speaker phone on doesn't get triggered when speaker isn't available.
        mController.sendMessageWithSessionInfo(USER_SWITCH_SPEAKER);
        mController.sendMessageWithSessionInfo(SPEAKER_ON);
        verify(mockStatusBarNotifier, times(0)).notifySpeakerphone(anyBoolean());

    }

    private void verifyConnectBluetoothDevice(int audioType) {
        mController.initialize();
        mController.setActive(true);

        mController.sendMessageWithSessionInfo(BT_DEVICE_ADDED, audioType, BLUETOOTH_DEVICE_1);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, null, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        mController.sendMessageWithSessionInfo(BT_ACTIVE_DEVICE_PRESENT, audioType, BT_ADDRESS_1);
        if (audioType == AudioRoute.TYPE_BLUETOOTH_SCO) {
            verify(mBluetoothDeviceManager, timeout(TEST_TIMEOUT))
                    .connectAudio(BLUETOOTH_DEVICE_1, AudioRoute.TYPE_BLUETOOTH_SCO);
            mController.sendMessageWithSessionInfo(BT_AUDIO_CONNECTED,
                    0, BLUETOOTH_DEVICE_1);
        } else {
            verify(mAudioManager, timeout(TEST_TIMEOUT))
                    .setCommunicationDevice(nullable(AudioDeviceInfo.class));
        }

        expectedState = new CallAudioState(false, CallAudioState.ROUTE_BLUETOOTH,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                        | CallAudioState.ROUTE_SPEAKER, BLUETOOTH_DEVICE_1, BLUETOOTH_DEVICES);
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));

        // Test hearing aid pair and ensure second device isn't added as a route
        if (audioType == AudioRoute.TYPE_BLUETOOTH_HA) {
            BluetoothDevice hearingAidDevice2 =
                    BluetoothRouteManagerTest.makeBluetoothDevice("00:00:00:00:00:02");
            mController.sendMessageWithSessionInfo(BT_DEVICE_ADDED, audioType, hearingAidDevice2);
            expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                    CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_BLUETOOTH
                            | CallAudioState.ROUTE_SPEAKER, null, BLUETOOTH_DEVICES);
            // Verify that supported BT devices only shows the first connected hearing aid device.
            verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                    any(CallAudioState.class), eq(expectedState));
        }
    }

    private void verifyDisconnectBluetoothDevice(int audioType) {
        mController.sendMessageWithSessionInfo(BT_DEVICE_REMOVED, audioType, BLUETOOTH_DEVICE_1);
        CallAudioState expectedState = new CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
                CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER, null,
                new HashSet<>());
        if (audioType == AudioRoute.TYPE_BLUETOOTH_SCO) {
            verify(mBluetoothDeviceManager, timeout(TEST_TIMEOUT)).disconnectSco();
        } else {
            verify(mAudioManager, timeout(TEST_TIMEOUT)).clearCommunicationDevice();
        }
        verify(mCallsManager, timeout(TEST_TIMEOUT)).onCallAudioStateChanged(
                any(CallAudioState.class), eq(expectedState));
    }
}
