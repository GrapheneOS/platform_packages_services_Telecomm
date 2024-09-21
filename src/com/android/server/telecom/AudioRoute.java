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

package com.android.server.telecom;

import static com.android.server.telecom.CallAudioRouteAdapter.BT_AUDIO_CONNECTED;
import static com.android.server.telecom.CallAudioRouteAdapter.BT_AUDIO_DISCONNECTED;
import static com.android.server.telecom.CallAudioRouteAdapter.PENDING_ROUTE_FAILED;
import static com.android.server.telecom.CallAudioRouteAdapter.SPEAKER_OFF;
import static com.android.server.telecom.CallAudioRouteAdapter.SPEAKER_ON;

import android.annotation.IntDef;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothStatusCodes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.telecom.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AudioRoute {
    public static class Factory {
        private final ScheduledExecutorService mScheduledExecutorService =
                new ScheduledThreadPoolExecutor(1);
        private CompletableFuture<AudioRoute> mAudioRouteFuture;
        public AudioRoute create(@AudioRouteType int type, String bluetoothAddress,
                                 AudioManager audioManager) throws RuntimeException {
            mAudioRouteFuture = new CompletableFuture();
            createRetry(type, bluetoothAddress, audioManager, MAX_CONNECTION_RETRIES);
            try {
                return mAudioRouteFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("Error when creating requested audio route");
            }
        }
        private void createRetry(@AudioRouteType int type, String bluetoothAddress,
                                       AudioManager audioManager, int retryCount) {
            // Early exit if exceeded max number of retries (and complete the future).
            if (retryCount == 0) {
                mAudioRouteFuture.complete(null);
                return;
            }

            Log.i(this, "createRetry; type=%s, address=%s, retryCount=%d",
                    DEVICE_TYPE_STRINGS.get(type), bluetoothAddress, retryCount);
            AudioDeviceInfo routeInfo = null;
            List<AudioDeviceInfo> infos = audioManager.getAvailableCommunicationDevices();
            List<Integer> possibleInfoTypes = AUDIO_ROUTE_TYPE_TO_DEVICE_INFO_TYPE.get(type);
            for (AudioDeviceInfo info : infos) {
                Log.i(this, "type: " + info.getType());
                if (possibleInfoTypes != null && possibleInfoTypes.contains(info.getType())) {
                    if (BT_AUDIO_ROUTE_TYPES.contains(type)) {
                        if (bluetoothAddress.equals(info.getAddress())) {
                            routeInfo = info;
                            break;
                        }
                    } else {
                        routeInfo = info;
                        break;
                    }
                }
            }
            // Try connecting BT device anyway (to handle wearables not showing as available
            // communication device or LE device not showing up since it may not be the lead
            // device).
            if (routeInfo == null && bluetoothAddress == null) {
                try {
                    mScheduledExecutorService.schedule(
                            () -> createRetry(type, bluetoothAddress, audioManager, retryCount - 1),
                            RETRY_TIME_DELAY, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    Log.e(this, e, "Could not schedule retry for audio routing.");
                }
            } else {
                mAudioRouteFuture.complete(new AudioRoute(type, bluetoothAddress, routeInfo));
            }
        }
    }

    private static final long RETRY_TIME_DELAY = 500L;
    private static final int MAX_CONNECTION_RETRIES = 2;
    public static final int TYPE_INVALID = 0;
    public static final int TYPE_EARPIECE = 1;
    public static final int TYPE_WIRED = 2;
    public static final int TYPE_SPEAKER = 3;
    public static final int TYPE_DOCK = 4;
    public static final int TYPE_BLUETOOTH_SCO = 5;
    public static final int TYPE_BLUETOOTH_HA = 6;
    public static final int TYPE_BLUETOOTH_LE = 7;
    public static final int TYPE_STREAMING = 8;
    // Used by auto
    public static final int TYPE_BUS = 9;
    @IntDef(prefix = "TYPE", value = {
            TYPE_INVALID,
            TYPE_EARPIECE,
            TYPE_WIRED,
            TYPE_SPEAKER,
            TYPE_DOCK,
            TYPE_BLUETOOTH_SCO,
            TYPE_BLUETOOTH_HA,
            TYPE_BLUETOOTH_LE,
            TYPE_STREAMING,
            TYPE_BUS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioRouteType {}

    private @AudioRouteType int mAudioRouteType;
    private String mBluetoothAddress;
    private AudioDeviceInfo mInfo;
    private boolean mIsDestRouteForWatch;
    public static final Set<Integer> BT_AUDIO_DEVICE_INFO_TYPES = Set.of(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    );

    public static final Set<Integer> BT_AUDIO_ROUTE_TYPES = Set.of(
            AudioRoute.TYPE_BLUETOOTH_SCO,
            AudioRoute.TYPE_BLUETOOTH_HA,
            AudioRoute.TYPE_BLUETOOTH_LE
    );

    public static final HashMap<Integer, String> DEVICE_TYPE_STRINGS;
    static {
        DEVICE_TYPE_STRINGS = new HashMap<>();
        DEVICE_TYPE_STRINGS.put(TYPE_EARPIECE, "TYPE_EARPIECE");
        DEVICE_TYPE_STRINGS.put(TYPE_WIRED, "TYPE_WIRED_HEADSET");
        DEVICE_TYPE_STRINGS.put(TYPE_SPEAKER, "TYPE_SPEAKER");
        DEVICE_TYPE_STRINGS.put(TYPE_DOCK, "TYPE_DOCK");
        DEVICE_TYPE_STRINGS.put(TYPE_BUS, "TYPE_BUS");
        DEVICE_TYPE_STRINGS.put(TYPE_BLUETOOTH_SCO, "TYPE_BLUETOOTH_SCO");
        DEVICE_TYPE_STRINGS.put(TYPE_BLUETOOTH_HA, "TYPE_BLUETOOTH_HA");
        DEVICE_TYPE_STRINGS.put(TYPE_BLUETOOTH_LE, "TYPE_BLUETOOTH_LE");
        DEVICE_TYPE_STRINGS.put(TYPE_STREAMING, "TYPE_STREAMING");
    }

    public static final HashMap<Integer, Integer> DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE;
    static {
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE = new HashMap<>();
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                TYPE_EARPIECE);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                TYPE_SPEAKER);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_WIRED_HEADSET, TYPE_WIRED);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, TYPE_WIRED);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                TYPE_BLUETOOTH_SCO);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_USB_DEVICE, TYPE_WIRED);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_USB_ACCESSORY, TYPE_WIRED);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_DOCK, TYPE_DOCK);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_USB_HEADSET, TYPE_WIRED);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_HEARING_AID,
                TYPE_BLUETOOTH_HA);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_BLE_HEADSET,
                TYPE_BLUETOOTH_LE);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_BLE_SPEAKER,
                TYPE_BLUETOOTH_LE);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_BLE_BROADCAST,
                TYPE_BLUETOOTH_LE);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_DOCK_ANALOG, TYPE_DOCK);
        DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.put(AudioDeviceInfo.TYPE_BUS, TYPE_BUS);
    }

    private static final HashMap<Integer, List<Integer>> AUDIO_ROUTE_TYPE_TO_DEVICE_INFO_TYPE;
    static {
        AUDIO_ROUTE_TYPE_TO_DEVICE_INFO_TYPE = new HashMap<>();
        List<Integer> earpieceDeviceInfoTypes = new ArrayList<>();
        earpieceDeviceInfoTypes.add(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
        AUDIO_ROUTE_TYPE_TO_DEVICE_INFO_TYPE.put(TYPE_EARPIECE, earpieceDeviceInfoTypes);

        List<Integer> wiredDeviceInfoTypes = new ArrayList<>();
        wiredDeviceInfoTypes.add(AudioDeviceInfo.TYPE_WIRED_HEADSET);
        wiredDeviceInfoTypes.add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES);
        wiredDeviceInfoTypes.add(AudioDeviceInfo.TYPE_USB_DEVICE);
        wiredDeviceInfoTypes.add(AudioDeviceInfo.TYPE_USB_ACCESSORY);
        wiredDeviceInfoTypes.add(AudioDeviceInfo.TYPE_USB_HEADSET);
        AUDIO_ROUTE_TYPE_TO_DEVICE_INFO_TYPE.put(TYPE_WIRED, wiredDeviceInfoTypes);

        List<Integer> speakerDeviceInfoTypes = new ArrayList<>();
        speakerDeviceInfoTypes.add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        AUDIO_ROUTE_TYPE_TO_DEVICE_INFO_TYPE.put(TYPE_SPEAKER, speakerDeviceInfoTypes);

        List<Integer> dockDeviceInfoTypes = new ArrayList<>();
        dockDeviceInfoTypes.add(AudioDeviceInfo.TYPE_DOCK);
        dockDeviceInfoTypes.add(AudioDeviceInfo.TYPE_DOCK_ANALOG);
        AUDIO_ROUTE_TYPE_TO_DEVICE_INFO_TYPE.put(TYPE_DOCK, dockDeviceInfoTypes);

        List<Integer> busDeviceInfoTypes = new ArrayList<>();
        busDeviceInfoTypes.add(AudioDeviceInfo.TYPE_BUS);
        AUDIO_ROUTE_TYPE_TO_DEVICE_INFO_TYPE.put(TYPE_BUS, busDeviceInfoTypes);

        List<Integer> bluetoothScoDeviceInfoTypes = new ArrayList<>();
        bluetoothScoDeviceInfoTypes.add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        bluetoothScoDeviceInfoTypes.add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        AUDIO_ROUTE_TYPE_TO_DEVICE_INFO_TYPE.put(TYPE_BLUETOOTH_SCO, bluetoothScoDeviceInfoTypes);

        List<Integer> bluetoothHearingAidDeviceInfoTypes = new ArrayList<>();
        bluetoothHearingAidDeviceInfoTypes.add(AudioDeviceInfo.TYPE_HEARING_AID);
        AUDIO_ROUTE_TYPE_TO_DEVICE_INFO_TYPE.put(TYPE_BLUETOOTH_HA,
                bluetoothHearingAidDeviceInfoTypes);

        List<Integer> bluetoothLeDeviceInfoTypes = new ArrayList<>();
        bluetoothLeDeviceInfoTypes.add(AudioDeviceInfo.TYPE_BLE_HEADSET);
        bluetoothLeDeviceInfoTypes.add(AudioDeviceInfo.TYPE_BLE_SPEAKER);
        bluetoothLeDeviceInfoTypes.add(AudioDeviceInfo.TYPE_BLE_BROADCAST);
        AUDIO_ROUTE_TYPE_TO_DEVICE_INFO_TYPE.put(TYPE_BLUETOOTH_LE, bluetoothLeDeviceInfoTypes);
    }

    public int getType() {
        return mAudioRouteType;
    }

    public boolean isWatch() {
        return mIsDestRouteForWatch;
    }

    String getBluetoothAddress() {
        return mBluetoothAddress;
    }

    // Invoked when entered pending route whose dest route is this route
    void onDestRouteAsPendingRoute(boolean active, PendingAudioRoute pendingAudioRoute,
            BluetoothDevice device, AudioManager audioManager,
            BluetoothRouteManager bluetoothRouteManager, boolean isScoAudioConnected) {
        Log.i(this, "onDestRouteAsPendingRoute: active (%b), type (%s)", active,
                DEVICE_TYPE_STRINGS.get(mAudioRouteType));
        if (pendingAudioRoute.isActive() && !active) {
            clearCommunicationDevice(pendingAudioRoute, bluetoothRouteManager, audioManager);
        } else if (active) {
            // Handle BT routing case.
            if (BT_AUDIO_ROUTE_TYPES.contains(mAudioRouteType)) {
                boolean connectedBtAudio = connectBtAudio(pendingAudioRoute, device,
                        audioManager, bluetoothRouteManager);
                // Special handling for SCO case.
                if (mAudioRouteType == TYPE_BLUETOOTH_SCO) {
                    // Set whether the dest route is for the watch
                    mIsDestRouteForWatch = bluetoothRouteManager.isWatch(device);
                    // Check if the communication device was set for the device, even if
                    // BluetoothHeadset#connectAudio reports that the SCO connection wasn't
                    // successfully established.
                    if (connectedBtAudio || isScoAudioConnected) {
                        pendingAudioRoute.setCommunicationDeviceType(mAudioRouteType);
                        if (!isScoAudioConnected) {
                            pendingAudioRoute.addMessage(BT_AUDIO_CONNECTED, mBluetoothAddress);
                        }
                    } else {
                        pendingAudioRoute.onMessageReceived(new Pair<>(PENDING_ROUTE_FAILED,
                                mBluetoothAddress), mBluetoothAddress);
                    }
                    return;
                }
            } else if (mAudioRouteType == TYPE_SPEAKER) {
                pendingAudioRoute.addMessage(SPEAKER_ON, null);
            }

            boolean result = false;
            List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
            for (AudioDeviceInfo deviceInfo : devices) {
                // It's possible for the AudioDeviceInfo to be updated for the BT device so adjust
                // mInfo accordingly.
                if (BT_AUDIO_ROUTE_TYPES.contains(mAudioRouteType) && mBluetoothAddress
                        .equals(deviceInfo.getAddress())) {
                    mInfo = deviceInfo;
                }
                if (deviceInfo.equals(mInfo)) {
                    result = audioManager.setCommunicationDevice(mInfo);
                    if (result) {
                        pendingAudioRoute.setCommunicationDeviceType(mAudioRouteType);
                    }
                    Log.i(this, "onDestRouteAsPendingRoute: route=%s, "
                            + "AudioManager#setCommunicationDevice()=%b", this, result);
                    break;
                }
            }

            // It's possible that BluetoothStateReceiver needs to report that the device is active
            // before being able to successfully set the communication device. Refrain from sending
            // pending route failed message for BT route until the second attempt fails.
            if (!result && !BT_AUDIO_ROUTE_TYPES.contains(mAudioRouteType)) {
                pendingAudioRoute.onMessageReceived(new Pair<>(PENDING_ROUTE_FAILED, null), null);
            }
        }
    }

    // Takes care of cleaning up original audio route (i.e. clearCommunicationDevice,
    // sending SPEAKER_OFF, or disconnecting SCO).
    void onOrigRouteAsPendingRoute(boolean active, PendingAudioRoute pendingAudioRoute,
            AudioManager audioManager, BluetoothRouteManager bluetoothRouteManager) {
        Log.i(this, "onOrigRouteAsPendingRoute: active (%b), type (%d)", active, mAudioRouteType);
        if (active) {
            if (mAudioRouteType == TYPE_SPEAKER) {
                pendingAudioRoute.addMessage(SPEAKER_OFF, null);
            }
            int result = clearCommunicationDevice(pendingAudioRoute, bluetoothRouteManager,
                    audioManager);
            // Only send BT_AUDIO_DISCONNECTED for SCO if disconnect was successful.
            if (mAudioRouteType == TYPE_BLUETOOTH_SCO && result == BluetoothStatusCodes.SUCCESS) {
                pendingAudioRoute.addMessage(BT_AUDIO_DISCONNECTED, mBluetoothAddress);
            }
        }
    }

    @VisibleForTesting
    public AudioRoute(@AudioRouteType int type, String bluetoothAddress, AudioDeviceInfo info) {
        mAudioRouteType = type;
        mBluetoothAddress = bluetoothAddress;
        mInfo = info;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof AudioRoute otherRoute)) {
            return false;
        }
        if (mAudioRouteType != otherRoute.getType()) {
            return false;
        }
        return !BT_AUDIO_ROUTE_TYPES.contains(mAudioRouteType) || mBluetoothAddress.equals(
                otherRoute.getBluetoothAddress());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAudioRouteType, mBluetoothAddress);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[Type=" + DEVICE_TYPE_STRINGS.get(mAudioRouteType)
                + ", Address=" + ((mBluetoothAddress != null) ? mBluetoothAddress : "invalid")
                + "]";
    }

    private boolean connectBtAudio(PendingAudioRoute pendingAudioRoute, BluetoothDevice device,
            AudioManager audioManager, BluetoothRouteManager bluetoothRouteManager) {
        // Ensure that if another BT device was set, it is disconnected before connecting
        // the new one.
        AudioRoute currentRoute = pendingAudioRoute.getOrigRoute();
        if (currentRoute.getBluetoothAddress() != null &&
                !currentRoute.getBluetoothAddress().equals(device.getAddress())) {
            clearCommunicationDevice(pendingAudioRoute, bluetoothRouteManager, audioManager);
        }

        // Connect to the device (explicit handling for HFP devices).
        boolean success = false;
        if (device != null) {
            success = bluetoothRouteManager.getDeviceManager()
                    .connectAudio(device, mAudioRouteType);
        }

        Log.i(this, "connectBtAudio: routeToConnectTo = %s, successful = %b",
                this, success);
        return success;
    }

    int clearCommunicationDevice(PendingAudioRoute pendingAudioRoute,
            BluetoothRouteManager bluetoothRouteManager, AudioManager audioManager) {
        // Try to see if there's a previously set device for communication that should be cleared.
        // This only serves to help in the SCO case to ensure that we disconnect the headset.
        if (pendingAudioRoute.getCommunicationDeviceType() == AudioRoute.TYPE_INVALID) {
            return -1;
        }

        int result = BluetoothStatusCodes.SUCCESS;
        if (pendingAudioRoute.getCommunicationDeviceType() == TYPE_BLUETOOTH_SCO) {
            Log.i(this, "clearCommunicationDevice: Disconnecting SCO device.");
            result = bluetoothRouteManager.getDeviceManager().disconnectSco();
        } else {
            Log.i(this, "clearCommunicationDevice: AudioManager#clearCommunicationDevice, type=%s",
                    DEVICE_TYPE_STRINGS.get(pendingAudioRoute.getCommunicationDeviceType()));
            audioManager.clearCommunicationDevice();
        }

        if (result == BluetoothStatusCodes.SUCCESS) {
            pendingAudioRoute.setCommunicationDeviceType(AudioRoute.TYPE_INVALID);
        }
        return result;
    }
}
