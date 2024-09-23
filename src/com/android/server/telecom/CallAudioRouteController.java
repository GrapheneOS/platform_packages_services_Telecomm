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

import static com.android.server.telecom.AudioRoute.BT_AUDIO_ROUTE_TYPES;
import static com.android.server.telecom.AudioRoute.TYPE_INVALID;
import static com.android.server.telecom.AudioRoute.TYPE_SPEAKER;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.audiopolicy.AudioProductStrategy;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.RemoteException;
import android.telecom.CallAudioState;
import android.telecom.Log;
import android.telecom.Logging.Session;
import android.telecom.VideoProfile;
import android.util.ArrayMap;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.telecom.bluetooth.BluetoothRouteManager;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.metrics.TelecomMetricsController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class CallAudioRouteController implements CallAudioRouteAdapter {
    private static final AudioRoute DUMMY_ROUTE = new AudioRoute(TYPE_INVALID, null, null);
    private static final Map<Integer, Integer> ROUTE_MAP;
    static {
        ROUTE_MAP = new ArrayMap<>();
        ROUTE_MAP.put(TYPE_INVALID, 0);
        ROUTE_MAP.put(AudioRoute.TYPE_EARPIECE, CallAudioState.ROUTE_EARPIECE);
        ROUTE_MAP.put(AudioRoute.TYPE_WIRED, CallAudioState.ROUTE_WIRED_HEADSET);
        ROUTE_MAP.put(AudioRoute.TYPE_SPEAKER, CallAudioState.ROUTE_SPEAKER);
        ROUTE_MAP.put(AudioRoute.TYPE_DOCK, CallAudioState.ROUTE_SPEAKER);
        ROUTE_MAP.put(AudioRoute.TYPE_BUS, CallAudioState.ROUTE_SPEAKER);
        ROUTE_MAP.put(AudioRoute.TYPE_BLUETOOTH_SCO, CallAudioState.ROUTE_BLUETOOTH);
        ROUTE_MAP.put(AudioRoute.TYPE_BLUETOOTH_HA, CallAudioState.ROUTE_BLUETOOTH);
        ROUTE_MAP.put(AudioRoute.TYPE_BLUETOOTH_LE, CallAudioState.ROUTE_BLUETOOTH);
        ROUTE_MAP.put(AudioRoute.TYPE_STREAMING, CallAudioState.ROUTE_STREAMING);
    }

    /** Valid values for the first argument for SWITCH_BASELINE_ROUTE */
    public static final int INCLUDE_BLUETOOTH_IN_BASELINE = 1;

    private final CallsManager mCallsManager;
    private final Context mContext;
    private AudioManager mAudioManager;
    private CallAudioManager mCallAudioManager;
    private final BluetoothRouteManager mBluetoothRouteManager;
    private final CallAudioManager.AudioServiceFactory mAudioServiceFactory;
    private final Handler mHandler;
    private final WiredHeadsetManager mWiredHeadsetManager;
    private Set<AudioRoute> mAvailableRoutes;
    private Set<AudioRoute> mCallSupportedRoutes;
    private AudioRoute mCurrentRoute;
    private AudioRoute mEarpieceWiredRoute;
    private AudioRoute mSpeakerDockRoute;
    private AudioRoute mStreamingRoute;
    private Set<AudioRoute> mStreamingRoutes;
    private Map<AudioRoute, BluetoothDevice> mBluetoothRoutes;
    private Pair<Integer, String> mActiveBluetoothDevice;
    private Map<Integer, String> mActiveDeviceCache;
    private String mBluetoothAddressForRinging;
    private Map<Integer, AudioRoute> mTypeRoutes;
    private PendingAudioRoute mPendingAudioRoute;
    private AudioRoute.Factory mAudioRouteFactory;
    private StatusBarNotifier mStatusBarNotifier;
    private FeatureFlags mFeatureFlags;
    private int mFocusType;
    private int mCallSupportedRouteMask = -1;
    private boolean mIsScoAudioConnected;
    private boolean mAvailableRoutesUpdated;
    private final Object mLock = new Object();
    private final TelecomSystem.SyncRoot mTelecomLock;
    private final BroadcastReceiver mSpeakerPhoneChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.startSession("CARC.mSPCR");
            try {
                if (AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED.equals(intent.getAction())) {
                    if (mAudioManager != null) {
                        AudioDeviceInfo info = mAudioManager.getCommunicationDevice();
                        if ((info != null) &&
                                (info.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)) {
                            if (mCurrentRoute.getType() != AudioRoute.TYPE_SPEAKER) {
                                sendMessageWithSessionInfo(SPEAKER_ON);
                            }
                        } else {
                            sendMessageWithSessionInfo(SPEAKER_OFF);
                        }
                    }
                } else {
                    Log.w(this, "Received non-speakerphone-change intent");
                }
            } finally {
                Log.endSession();
            }
        }
    };
    private final BroadcastReceiver mMuteChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.startSession("CARC.mCR");
            try {
                if (AudioManager.ACTION_MICROPHONE_MUTE_CHANGED.equals(intent.getAction())) {
                    if (mCallsManager.isInEmergencyCall()) {
                        Log.i(this, "Mute was externally changed when there's an emergency call. "
                                + "Forcing mute back off.");
                        sendMessageWithSessionInfo(MUTE_OFF);
                    } else {
                        sendMessageWithSessionInfo(MUTE_EXTERNALLY_CHANGED);
                    }
                } else if (AudioManager.STREAM_MUTE_CHANGED_ACTION.equals(intent.getAction())) {
                    int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                    boolean isStreamMuted = intent.getBooleanExtra(
                            AudioManager.EXTRA_STREAM_VOLUME_MUTED, false);

                    if (streamType == AudioManager.STREAM_RING && !isStreamMuted
                            && mCallAudioManager != null) {
                        Log.i(this, "Ring stream was un-muted.");
                        mCallAudioManager.onRingerModeChange();
                    }
                } else {
                    Log.w(this, "Received non-mute-change intent");
                }
            } finally {
                Log.endSession();
            }
        }
    };
    private CallAudioState mCallAudioState;
    private boolean mIsMute;
    private boolean mIsPending;
    private boolean mIsActive;
    private final TelecomMetricsController mMetricsController;

    public CallAudioRouteController(
            Context context, CallsManager callsManager,
            CallAudioManager.AudioServiceFactory audioServiceFactory,
            AudioRoute.Factory audioRouteFactory, WiredHeadsetManager wiredHeadsetManager,
            BluetoothRouteManager bluetoothRouteManager, StatusBarNotifier statusBarNotifier,
            FeatureFlags featureFlags, TelecomMetricsController metricsController) {
        mContext = context;
        mCallsManager = callsManager;
        mAudioManager = context.getSystemService(AudioManager.class);
        mAudioServiceFactory = audioServiceFactory;
        mAudioRouteFactory = audioRouteFactory;
        mWiredHeadsetManager = wiredHeadsetManager;
        mIsMute = false;
        mBluetoothRouteManager = bluetoothRouteManager;
        mStatusBarNotifier = statusBarNotifier;
        mFeatureFlags = featureFlags;
        mMetricsController = metricsController;
        mFocusType = NO_FOCUS;
        mIsScoAudioConnected = false;
        mTelecomLock = callsManager.getLock();
        HandlerThread handlerThread = new HandlerThread(this.getClass().getSimpleName());
        handlerThread.start();

        // Register broadcast receivers
        IntentFilter speakerChangedFilter = new IntentFilter(
                AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED);
        speakerChangedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(mSpeakerPhoneChangeReceiver, speakerChangedFilter);

        IntentFilter micMuteChangedFilter = new IntentFilter(
                AudioManager.ACTION_MICROPHONE_MUTE_CHANGED);
        micMuteChangedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(mMuteChangeReceiver, micMuteChangedFilter);

        IntentFilter muteChangedFilter = new IntentFilter(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        muteChangedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(mMuteChangeReceiver, muteChangedFilter);

        // Create handler
        mHandler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                synchronized (this) {
                    preHandleMessage(msg);
                    String address;
                    BluetoothDevice bluetoothDevice;
                    int focus;
                    int handleEndTone;
                    @AudioRoute.AudioRouteType int type;
                    switch (msg.what) {
                        case CONNECT_WIRED_HEADSET:
                            handleWiredHeadsetConnected();
                            break;
                        case DISCONNECT_WIRED_HEADSET:
                            handleWiredHeadsetDisconnected();
                            break;
                        case CONNECT_DOCK:
                            handleDockConnected();
                            break;
                        case DISCONNECT_DOCK:
                            handleDockDisconnected();
                            break;
                        case BLUETOOTH_DEVICE_LIST_CHANGED:
                            break;
                        case BT_ACTIVE_DEVICE_PRESENT:
                            type = msg.arg1;
                            address = (String) ((SomeArgs) msg.obj).arg2;
                            handleBtActiveDevicePresent(type, address);
                            break;
                        case BT_ACTIVE_DEVICE_GONE:
                            type = msg.arg1;
                            handleBtActiveDeviceGone(type);
                            break;
                        case BT_DEVICE_ADDED:
                            type = msg.arg1;
                            bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                            handleBtConnected(type, bluetoothDevice);
                            break;
                        case BT_DEVICE_REMOVED:
                            type = msg.arg1;
                            bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                            handleBtDisconnected(type, bluetoothDevice);
                            break;
                        case SWITCH_EARPIECE:
                        case USER_SWITCH_EARPIECE:
                            handleSwitchEarpiece();
                            break;
                        case SWITCH_BLUETOOTH:
                        case USER_SWITCH_BLUETOOTH:
                            address = (String) ((SomeArgs) msg.obj).arg2;
                            handleSwitchBluetooth(address);
                            break;
                        case SWITCH_HEADSET:
                        case USER_SWITCH_HEADSET:
                            handleSwitchHeadset();
                            break;
                        case SWITCH_SPEAKER:
                        case USER_SWITCH_SPEAKER:
                            handleSwitchSpeaker();
                            break;
                        case SWITCH_BASELINE_ROUTE:
                            address = (String) ((SomeArgs) msg.obj).arg2;
                            handleSwitchBaselineRoute(msg.arg1 == INCLUDE_BLUETOOTH_IN_BASELINE,
                                    address);
                            break;
                        case USER_SWITCH_BASELINE_ROUTE:
                            handleSwitchBaselineRoute(msg.arg1 == INCLUDE_BLUETOOTH_IN_BASELINE,
                                    null);
                            break;
                        case SPEAKER_ON:
                            handleSpeakerOn();
                            break;
                        case SPEAKER_OFF:
                            handleSpeakerOff();
                            break;
                        case STREAMING_FORCE_ENABLED:
                            handleStreamingEnabled();
                            break;
                        case STREAMING_FORCE_DISABLED:
                            handleStreamingDisabled();
                            break;
                        case BT_AUDIO_CONNECTED:
                            bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                            handleBtAudioActive(bluetoothDevice);
                            break;
                        case BT_AUDIO_DISCONNECTED:
                            bluetoothDevice = (BluetoothDevice) ((SomeArgs) msg.obj).arg2;
                            handleBtAudioInactive(bluetoothDevice);
                            break;
                        case MUTE_ON:
                            handleMuteChanged(true);
                            break;
                        case MUTE_OFF:
                            handleMuteChanged(false);
                            break;
                        case MUTE_EXTERNALLY_CHANGED:
                            handleMuteChanged(mAudioManager.isMicrophoneMute());
                            break;
                        case SWITCH_FOCUS:
                            focus = msg.arg1;
                            handleEndTone = (int) ((SomeArgs) msg.obj).arg2;
                            handleSwitchFocus(focus, handleEndTone);
                            break;
                        case EXIT_PENDING_ROUTE:
                            handleExitPendingRoute();
                            break;
                        case UPDATE_SYSTEM_AUDIO_ROUTE:
                            // Based on the available routes for foreground call, adjust routing.
                            updateRouteForForeground();
                            // Force update to notify all ICS/CS.
                            updateCallAudioState(new CallAudioState(mIsMute,
                                    mCallAudioState.getRoute(),
                                    mCallAudioState.getSupportedRouteMask(),
                                    mCallAudioState.getActiveBluetoothDevice(),
                                    mCallAudioState.getSupportedBluetoothDevices()));
                        default:
                            break;
                    }
                    postHandleMessage(msg);
                }
            }
        };
    }
    @Override
    public void initialize() {
        mAvailableRoutes = new HashSet<>();
        mCallSupportedRoutes = new HashSet<>();
        mBluetoothRoutes = new LinkedHashMap<>();
        mActiveDeviceCache = new HashMap<>();
        mActiveDeviceCache.put(AudioRoute.TYPE_BLUETOOTH_SCO, null);
        mActiveDeviceCache.put(AudioRoute.TYPE_BLUETOOTH_HA, null);
        mActiveDeviceCache.put(AudioRoute.TYPE_BLUETOOTH_LE, null);
        mActiveBluetoothDevice = null;
        mTypeRoutes = new ArrayMap<>();
        mStreamingRoutes = new HashSet<>();
        mPendingAudioRoute = new PendingAudioRoute(this, mAudioManager, mBluetoothRouteManager,
                mFeatureFlags);
        mStreamingRoute = new AudioRoute(AudioRoute.TYPE_STREAMING, null, null);
        mStreamingRoutes.add(mStreamingRoute);

        int supportMask = calculateSupportedRouteMaskInit();
        if ((supportMask & CallAudioState.ROUTE_SPEAKER) != 0) {
            int audioRouteType = AudioRoute.TYPE_SPEAKER;
            // Create speaker routes
            mSpeakerDockRoute = mAudioRouteFactory.create(AudioRoute.TYPE_SPEAKER, null,
                    mAudioManager);
            if (mSpeakerDockRoute == null){
                Log.i(this, "Can't find available audio device info for route TYPE_SPEAKER, trying"
                        + " for TYPE_BUS");
                mSpeakerDockRoute = mAudioRouteFactory.create(AudioRoute.TYPE_BUS, null,
                        mAudioManager);
                audioRouteType = AudioRoute.TYPE_BUS;
            }
            if (mSpeakerDockRoute != null) {
                mTypeRoutes.put(audioRouteType, mSpeakerDockRoute);
                updateAvailableRoutes(mSpeakerDockRoute, true);
            } else {
                Log.w(this, "Can't find available audio device info for route TYPE_SPEAKER "
                        + "or TYPE_BUS.");
            }
        }

        if ((supportMask & CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
            // Create wired headset routes
            mEarpieceWiredRoute = mAudioRouteFactory.create(AudioRoute.TYPE_WIRED, null,
                    mAudioManager);
            if (mEarpieceWiredRoute == null) {
                Log.w(this, "Can't find available audio device info for route TYPE_WIRED_HEADSET");
            } else {
                mTypeRoutes.put(AudioRoute.TYPE_WIRED, mEarpieceWiredRoute);
                updateAvailableRoutes(mEarpieceWiredRoute, true);
            }
        } else if ((supportMask & CallAudioState.ROUTE_EARPIECE) != 0) {
            // Create earpiece routes
            mEarpieceWiredRoute = mAudioRouteFactory.create(AudioRoute.TYPE_EARPIECE, null,
                    mAudioManager);
            if (mEarpieceWiredRoute == null) {
                Log.w(this, "Can't find available audio device info for route TYPE_EARPIECE");
            } else {
                mTypeRoutes.put(AudioRoute.TYPE_EARPIECE, mEarpieceWiredRoute);
                updateAvailableRoutes(mEarpieceWiredRoute, true);
            }
        }

        // set current route
        if (mEarpieceWiredRoute != null) {
            mCurrentRoute = mEarpieceWiredRoute;
        } else if (mSpeakerDockRoute != null) {
            mCurrentRoute = mSpeakerDockRoute;
        } else {
            mCurrentRoute = DUMMY_ROUTE;
        }
        mIsActive = false;
        mCallAudioState = new CallAudioState(mIsMute, ROUTE_MAP.get(mCurrentRoute.getType()),
                supportMask, null, new HashSet<>());
    }

    @Override
    public void sendMessageWithSessionInfo(int message) {
        sendMessageWithSessionInfo(message, 0, (String) null);
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg) {
        sendMessageWithSessionInfo(message, arg, (String) null);
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg, String data) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = Log.createSubsession();
        args.arg2 = data;
        sendMessage(message, arg, 0, args);
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg, int data) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = Log.createSubsession();
        args.arg2 = data;
        sendMessage(message, arg, 0, args);
    }

    @Override
    public void sendMessageWithSessionInfo(int message, int arg, BluetoothDevice bluetoothDevice) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = Log.createSubsession();
        args.arg2 = bluetoothDevice;
        sendMessage(message, arg, 0, args);
    }

    @Override
    public void sendMessage(int message, Runnable r) {
        r.run();
    }

    private void sendMessage(int what, int arg1, int arg2, Object obj) {
        mHandler.sendMessage(Message.obtain(mHandler, what, arg1, arg2, obj));
    }

    @Override
    public void setCallAudioManager(CallAudioManager callAudioManager) {
        mCallAudioManager = callAudioManager;
    }

    @Override
    public CallAudioState getCurrentCallAudioState() {
        return mCallAudioState;
    }

    @Override
    public boolean isHfpDeviceAvailable() {
        return !mBluetoothRoutes.isEmpty();
    }

    @Override
    public Handler getAdapterHandler() {
        return mHandler;
    }

    @Override
    public PendingAudioRoute getPendingAudioRoute() {
        return mPendingAudioRoute;
    }

    @Override
    public void dump(IndentingPrintWriter pw) {
    }

    private void preHandleMessage(Message msg) {
        if (msg.obj instanceof SomeArgs) {
            Session session = (Session) ((SomeArgs) msg.obj).arg1;
            String messageCodeName = MESSAGE_CODE_TO_NAME.get(msg.what, "unknown");
            Log.continueSession(session, "CARC.pM_" + messageCodeName);
            Log.i(this, "Message received: %s=%d, arg1=%d", messageCodeName, msg.what, msg.arg1);
        }
    }

    private void postHandleMessage(Message msg) {
        Log.endSession();
        if (msg.obj instanceof SomeArgs) {
            ((SomeArgs) msg.obj).recycle();
        }
    }

    public boolean isActive() {
        return mIsActive;
    }

    public boolean isPending() {
        return mIsPending;
    }

    private void routeTo(boolean active, AudioRoute destRoute) {
        if (destRoute == null || (!destRoute.equals(mStreamingRoute)
                && !getCallSupportedRoutes().contains(destRoute))) {
            Log.i(this, "Ignore routing to unavailable route: %s", destRoute);
            return;
        }
        if (mIsPending) {
            if (destRoute.equals(mPendingAudioRoute.getDestRoute()) && (mIsActive == active)) {
                return;
            }
            Log.i(this, "Override current pending route destination from %s(active=%b) to "
                            + "%s(active=%b)",
                    mPendingAudioRoute.getDestRoute(), mIsActive, destRoute, active);
            // Ensure we don't keep waiting for SPEAKER_ON if dest route gets overridden.
            if (active && mPendingAudioRoute.getDestRoute().getType() == TYPE_SPEAKER) {
                mPendingAudioRoute.clearPendingMessage(new Pair<>(SPEAKER_ON, null));
            }
            // override pending route while keep waiting for still pending messages for the
            // previous pending route
            mPendingAudioRoute.setOrigRoute(mIsActive, mPendingAudioRoute.getDestRoute());
        } else {
            if (mCurrentRoute.equals(destRoute) && (mIsActive == active)) {
                return;
            }
            Log.i(this, "Enter pending route, orig%s(active=%b), dest%s(active=%b)", mCurrentRoute,
                    mIsActive, destRoute, active);
            // route to pending route
            if (getCallSupportedRoutes().contains(mCurrentRoute)) {
                mPendingAudioRoute.setOrigRoute(mIsActive, mCurrentRoute);
            } else {
                // Avoid waiting for pending messages for an unavailable route
                mPendingAudioRoute.setOrigRoute(mIsActive, DUMMY_ROUTE);
            }
            mIsPending = true;
        }
        mPendingAudioRoute.setDestRoute(active, destRoute, mBluetoothRoutes.get(destRoute),
                mIsScoAudioConnected);
        mIsActive = active;
        mPendingAudioRoute.evaluatePendingState();
        if (mFeatureFlags.telecomMetricsSupport()) {
            mMetricsController.getAudioRouteStats().onRouteEnter(mPendingAudioRoute);
        }
    }

    private void handleWiredHeadsetConnected() {
        AudioRoute wiredHeadsetRoute = null;
        try {
            wiredHeadsetRoute = mAudioRouteFactory.create(AudioRoute.TYPE_WIRED, null,
                    mAudioManager);
        } catch (IllegalArgumentException e) {
            Log.e(this, e, "Can't find available audio device info for route type:"
                    + AudioRoute.DEVICE_TYPE_STRINGS.get(AudioRoute.TYPE_WIRED));
        }

        if (wiredHeadsetRoute != null) {
            updateAvailableRoutes(wiredHeadsetRoute, true);
            updateAvailableRoutes(mEarpieceWiredRoute, false);
            mTypeRoutes.put(AudioRoute.TYPE_WIRED, wiredHeadsetRoute);
            mEarpieceWiredRoute = wiredHeadsetRoute;
            routeTo(mIsActive, wiredHeadsetRoute);
            onAvailableRoutesChanged();
        }
    }

    public void handleWiredHeadsetDisconnected() {
        // Update audio route states
        AudioRoute wiredHeadsetRoute = mTypeRoutes.remove(AudioRoute.TYPE_WIRED);
        if (wiredHeadsetRoute != null) {
            updateAvailableRoutes(wiredHeadsetRoute, false);
            mEarpieceWiredRoute = null;
        }
        AudioRoute earpieceRoute = mTypeRoutes.get(AudioRoute.TYPE_EARPIECE);
        if (earpieceRoute != null) {
            updateAvailableRoutes(earpieceRoute, true);
            mEarpieceWiredRoute = earpieceRoute;
        }
        onAvailableRoutesChanged();

        // Route to expected state
        if (mCurrentRoute.equals(wiredHeadsetRoute)) {
            routeTo(mIsActive, getBaseRoute(true, null));
        }
    }

    private void handleDockConnected() {
        AudioRoute dockRoute = null;
        try {
            dockRoute = mAudioRouteFactory.create(AudioRoute.TYPE_DOCK, null, mAudioManager);
        } catch (IllegalArgumentException e) {
            Log.e(this, e, "Can't find available audio device info for route type:"
                    + AudioRoute.DEVICE_TYPE_STRINGS.get(AudioRoute.TYPE_WIRED));
        }

        if (dockRoute != null) {
            updateAvailableRoutes(dockRoute, true);
            updateAvailableRoutes(mSpeakerDockRoute, false);
            mTypeRoutes.put(AudioRoute.TYPE_DOCK, dockRoute);
            mSpeakerDockRoute = dockRoute;
            routeTo(mIsActive, dockRoute);
            onAvailableRoutesChanged();
        }
    }

    public void handleDockDisconnected() {
        // Update audio route states
        AudioRoute dockRoute = mTypeRoutes.get(AudioRoute.TYPE_DOCK);
        if (dockRoute != null) {
            updateAvailableRoutes(dockRoute, false);
            mSpeakerDockRoute = null;
        }
        AudioRoute speakerRoute = mTypeRoutes.get(AudioRoute.TYPE_SPEAKER);
        if (speakerRoute != null) {
            updateAvailableRoutes(speakerRoute, true);
            mSpeakerDockRoute = speakerRoute;
        }
        onAvailableRoutesChanged();

        // Route to expected state
        if (mCurrentRoute.equals(dockRoute)) {
            routeTo(mIsActive, getBaseRoute(true, null));
        }
    }

    private void handleStreamingEnabled() {
        if (!mCurrentRoute.equals(mStreamingRoute)) {
            routeTo(mIsActive, mStreamingRoute);
        } else {
            Log.i(this, "ignore enable streaming, already in streaming");
        }
    }

    private void handleStreamingDisabled() {
        if (mCurrentRoute.equals(mStreamingRoute)) {
            mCurrentRoute = DUMMY_ROUTE;
            onAvailableRoutesChanged();
            routeTo(mIsActive, getBaseRoute(true, null));
        } else {
            Log.i(this, "ignore disable streaming, not in streaming");
        }
    }

    /**
     * Handles the case when SCO audio is connected for the BT headset. This follows shortly after
     * the BT device has been established as an active device (BT_ACTIVE_DEVICE_PRESENT) and doesn't
     * apply to other BT device types. In this case, the pending audio route will process the
     * BT_AUDIO_CONNECTED message that will trigger routing to the pending destination audio route;
     * otherwise, routing will be ignored if there aren't pending routes to be processed.
     *
     * Message being handled: BT_AUDIO_CONNECTED
     */
    private void handleBtAudioActive(BluetoothDevice bluetoothDevice) {
        if (mIsPending) {
            Log.i(this, "handleBtAudioActive: is pending path");
            if (Objects.equals(mPendingAudioRoute.getDestRoute().getBluetoothAddress(),
                    bluetoothDevice.getAddress())) {
                mPendingAudioRoute.onMessageReceived(new Pair<>(BT_AUDIO_CONNECTED,
                        bluetoothDevice.getAddress()), null);
            }
        }
    }

    /**
     * Handles the case when SCO audio is disconnected for the BT headset. In this case, the pending
     * audio route will process the BT_AUDIO_DISCONNECTED message which will trigger routing to the
     * pending destination audio route; otherwise, routing will be ignored if there aren't any
     * pending routes to be processed.
     *
     * Message being handled: BT_AUDIO_DISCONNECTED
     */
    private void handleBtAudioInactive(BluetoothDevice bluetoothDevice) {
        if (mIsPending) {
            Log.i(this, "handleBtAudioInactive: is pending path");
            if (Objects.equals(mPendingAudioRoute.getOrigRoute().getBluetoothAddress(),
                    bluetoothDevice.getAddress())) {
                mPendingAudioRoute.onMessageReceived(new Pair<>(BT_AUDIO_DISCONNECTED,
                        bluetoothDevice.getAddress()), null);
            }
        }
    }

    /**
     * This particular routing occurs when the BT device is trying to establish itself as a
     * connected device (refer to BluetoothStateReceiver#handleConnectionStateChanged). The device
     * is included as an available route and cached into the current BT routes.
     *
     * Message being handled: BT_DEVICE_ADDED
     */
    private void handleBtConnected(@AudioRoute.AudioRouteType int type,
            BluetoothDevice bluetoothDevice) {
        if (containsHearingAidPair(type, bluetoothDevice)) {
            return;
        }

        AudioRoute bluetoothRoute = mAudioRouteFactory.create(type, bluetoothDevice.getAddress(),
                mAudioManager);
        if (bluetoothRoute == null) {
            Log.w(this, "Can't find available audio device info for route type:"
                    + AudioRoute.DEVICE_TYPE_STRINGS.get(type));
        } else {
            Log.i(this, "bluetooth route added: " + bluetoothRoute);
            updateAvailableRoutes(bluetoothRoute, true);
            mBluetoothRoutes.put(bluetoothRoute, bluetoothDevice);
            onAvailableRoutesChanged();
        }
    }

    /**
     * Handles the case when the BT device is in a disconnecting/disconnected state. In this case,
     * the audio route for the specified device is removed from the available BT routes and the
     * audio is routed to an available route if the current route is pointing to the device which
     * got disconnected.
     *
     * Message being handled: BT_DEVICE_REMOVED
     */
    private void handleBtDisconnected(@AudioRoute.AudioRouteType int type,
            BluetoothDevice bluetoothDevice) {
        // Clean up unavailable routes
        AudioRoute bluetoothRoute = getBluetoothRoute(type, bluetoothDevice.getAddress());
        if (bluetoothRoute != null) {
            Log.i(this, "bluetooth route removed: " + bluetoothRoute);
            mBluetoothRoutes.remove(bluetoothRoute);
            updateAvailableRoutes(bluetoothRoute, false);
            onAvailableRoutesChanged();
        }

        // Fallback to an available route
        if (Objects.equals(mCurrentRoute, bluetoothRoute)) {
            routeTo(mIsActive, getBaseRoute(true, null));
        }
    }

    /**
     * This particular routing occurs when the specified bluetooth device is marked as the active
     * device (refer to BluetoothStateReceiver#handleActiveDeviceChanged). This takes care of
     * moving the call audio route to the bluetooth route.
     *
     * Message being handled: BT_ACTIVE_DEVICE_PRESENT
     */
    private void handleBtActiveDevicePresent(@AudioRoute.AudioRouteType int type,
            String deviceAddress) {
        AudioRoute bluetoothRoute = getBluetoothRoute(type, deviceAddress);
        if (bluetoothRoute != null) {
            Log.i(this, "request to route to bluetooth route: %s (active=%b)", bluetoothRoute,
                    mIsActive);
            routeTo(mIsActive, bluetoothRoute);
        } else {
            Log.i(this, "request to route to unavailable bluetooth route - type (%s), address (%s)",
                    type, deviceAddress);
        }
    }

    /**
     * Handles routing for when the active BT device is removed for a given audio route type. In
     * this case, the audio is routed to another available route if the current route hasn't been
     * adjusted yet or there is a pending destination route associated with the device type that
     * went inactive. Note that BT_DEVICE_REMOVED will be processed first in this case, which will
     * handle removing the BT route for the device that went inactive as well as falling back to
     * an available route.
     *
     * Message being handled: BT_ACTIVE_DEVICE_GONE
     */
    private void handleBtActiveDeviceGone(@AudioRoute.AudioRouteType int type) {
        if ((mIsPending && mPendingAudioRoute.getDestRoute().getType() == type)
                || (!mIsPending && mCurrentRoute.getType() == type)) {
            // Fallback to an available route
            routeTo(mIsActive, getBaseRoute(true, null));
        }
    }

    private void handleMuteChanged(boolean mute) {
        mIsMute = mute;
        if (mIsMute != mAudioManager.isMicrophoneMute() && mIsActive) {
            IAudioService audioService = mAudioServiceFactory.getAudioService();
            Log.i(this, "changing microphone mute state to: %b [serviceIsNull=%b]", mute,
                    audioService == null);
            if (audioService != null) {
                try {
                    audioService.setMicrophoneMute(mute, mContext.getOpPackageName(),
                            mCallsManager.getCurrentUserHandle().getIdentifier(),
                            mContext.getAttributionTag());
                } catch (RemoteException e) {
                    Log.e(this, e, "Remote exception while toggling mute.");
                    return;
                }
            }
        }
        onMuteStateChanged(mIsMute);
    }

    private void handleSwitchFocus(int focus, int handleEndTone) {
        Log.i(this, "handleSwitchFocus: focus (%s)", focus);
        mFocusType = focus;
        switch (focus) {
            case NO_FOCUS -> {
                if (mIsActive) {
                    // Notify the CallAudioModeStateMachine that audio operations are complete so
                    // that we can relinquish audio focus.
                    mCallAudioManager.notifyAudioOperationsComplete();

                    // Reset mute state after call ends.
                    handleMuteChanged(false);
                    // Route back to inactive route.
                    routeTo(false, mCurrentRoute);
                    // Clear pending messages
                    mPendingAudioRoute.clearPendingMessages();
                    clearRingingBluetoothAddress();
                }
            }
            case ACTIVE_FOCUS -> {
                // Route to active baseline route (we may need to change audio route in the case
                // when a video call is put on hold). Ignore route changes if we're handling playing
                // the end tone. Otherwise, it's possible that we'll override the route a client has
                // previously requested.
                if (handleEndTone == 0) {
                    // Cache BT device switch in the case that inband ringing is disabled and audio
                    // was routed to a watch. When active focus is received, this selection will be
                    // honored provided that the current route is associated.
                    Log.i(this, "handleSwitchFocus (ACTIVE_FOCUS): mBluetoothAddressForRinging = "
                            + "%s, mCurrentRoute = %s", mBluetoothAddressForRinging, mCurrentRoute);
                    AudioRoute audioRoute = mBluetoothAddressForRinging != null
                            && mBluetoothAddressForRinging.equals(
                                    mCurrentRoute.getBluetoothAddress())
                            ? mCurrentRoute
                            : getBaseRoute(true, null);
                    routeTo(true, audioRoute);
                    clearRingingBluetoothAddress();
                }
            }
            case RINGING_FOCUS -> {
                if (!mIsActive) {
                    AudioRoute route = getBaseRoute(true, null);
                    BluetoothDevice device = mBluetoothRoutes.get(route);
                    // Check if in-band ringtone is enabled for the device; if it isn't, move to
                    // inactive route.
                    if (device != null && !mBluetoothRouteManager.isInbandRingEnabled(device)) {
                        routeTo(false, route);
                    } else {
                        routeTo(true, route);
                    }
                } else {
                    // Route is already active.
                    BluetoothDevice device = mBluetoothRoutes.get(mCurrentRoute);
                    if (device != null && !mBluetoothRouteManager.isInbandRingEnabled(device)) {
                        routeTo(false, mCurrentRoute);
                    }
                }
            }
        }
    }

    public void handleSwitchEarpiece() {
        AudioRoute earpieceRoute = mTypeRoutes.get(AudioRoute.TYPE_EARPIECE);
        if (earpieceRoute != null && getCallSupportedRoutes().contains(earpieceRoute)) {
            routeTo(mIsActive, earpieceRoute);
        } else {
            Log.i(this, "ignore switch earpiece request");
        }
    }

    private void handleSwitchBluetooth(String address) {
        Log.i(this, "handle switch to bluetooth with address %s", address);
        AudioRoute bluetoothRoute = null;
        BluetoothDevice bluetoothDevice = null;
        if (address == null) {
            bluetoothRoute = getArbitraryBluetoothDevice();
            bluetoothDevice = mBluetoothRoutes.get(bluetoothRoute);
        } else {
            for (AudioRoute route : getCallSupportedRoutes()) {
                if (Objects.equals(address, route.getBluetoothAddress())) {
                    bluetoothRoute = route;
                    bluetoothDevice = mBluetoothRoutes.get(route);
                    break;
                }
            }
        }

        if (bluetoothRoute != null && bluetoothDevice != null) {
            if (mFocusType == RINGING_FOCUS) {
                routeTo(mBluetoothRouteManager.isInbandRingEnabled(bluetoothDevice) && mIsActive,
                        bluetoothRoute);
                mBluetoothAddressForRinging = bluetoothDevice.getAddress();
            } else {
                routeTo(mIsActive, bluetoothRoute);
            }
        } else {
            Log.i(this, "ignore switch bluetooth request to unavailable address");
        }
    }

    /**
     * Retrieve the active BT device, if available, otherwise return the most recently tracked
     * active device, or null if none are available.
     * @return {@link AudioRoute} of the BT device.
     */
    private AudioRoute getArbitraryBluetoothDevice() {
        if (mActiveBluetoothDevice != null) {
            return getBluetoothRoute(mActiveBluetoothDevice.first, mActiveBluetoothDevice.second);
        } else if (!mBluetoothRoutes.isEmpty()) {
            return mBluetoothRoutes.keySet().stream().toList().get(mBluetoothRoutes.size() - 1);
        }
        return null;
    }

    private void handleSwitchHeadset() {
        AudioRoute headsetRoute = mTypeRoutes.get(AudioRoute.TYPE_WIRED);
        if (headsetRoute != null && getCallSupportedRoutes().contains(headsetRoute)) {
            routeTo(mIsActive, headsetRoute);
        } else {
            Log.i(this, "ignore switch headset request");
        }
    }

    private void handleSwitchSpeaker() {
        if (mSpeakerDockRoute != null && getCallSupportedRoutes().contains(mSpeakerDockRoute)
                && mSpeakerDockRoute.getType() == AudioRoute.TYPE_SPEAKER) {
            routeTo(mIsActive, mSpeakerDockRoute);
        } else {
            Log.i(this, "ignore switch speaker request");
        }
    }

    private void handleSwitchBaselineRoute(boolean includeBluetooth, String btAddressToExclude) {
        routeTo(mIsActive, calculateBaselineRoute(includeBluetooth, btAddressToExclude));
    }

    private void handleSpeakerOn() {
        if (isPending()) {
            Log.i(this, "handleSpeakerOn: sending SPEAKER_ON to pending audio route");
            mPendingAudioRoute.onMessageReceived(new Pair<>(SPEAKER_ON, null), null);
            // Update status bar notification if we are in a call.
            mStatusBarNotifier.notifySpeakerphone(mCallsManager.hasAnyCalls());
        } else {
            if (mSpeakerDockRoute != null && getCallSupportedRoutes().contains(mSpeakerDockRoute)
                    && mSpeakerDockRoute.getType() == AudioRoute.TYPE_SPEAKER) {
                routeTo(mIsActive, mSpeakerDockRoute);
                // Since the route switching triggered by this message, we need to manually send it
                // again so that we won't stuck in the pending route
                if (mIsActive) {
                    sendMessageWithSessionInfo(SPEAKER_ON);
                }
            }
        }
    }

    private void handleSpeakerOff() {
        if (isPending()) {
            Log.i(this, "handleSpeakerOff - sending SPEAKER_OFF to pending audio route");
            mPendingAudioRoute.onMessageReceived(new Pair<>(SPEAKER_OFF, null), null);
            // Update status bar notification
            mStatusBarNotifier.notifySpeakerphone(false);
        } else if (mCurrentRoute.getType() == AudioRoute.TYPE_SPEAKER) {
            routeTo(mIsActive, getBaseRoute(true, null));
            // Since the route switching triggered by this message, we need to manually send it
            // again so that we won't stuck in the pending route
            if (mIsActive) {
                sendMessageWithSessionInfo(SPEAKER_OFF);
            }
            onAvailableRoutesChanged();
        }
    }

    /**
     * This is invoked when there are no more pending audio routes to be processed, which signals
     * a change for the current audio route and the call audio state to be updated accordingly.
     */
    public void handleExitPendingRoute() {
        if (mIsPending) {
            mCurrentRoute = mPendingAudioRoute.getDestRoute();
            Log.addEvent(mCallsManager.getForegroundCall(), LogUtils.Events.AUDIO_ROUTE,
                    "Entering audio route: " + mCurrentRoute + " (active=" + mIsActive + ")");
            mIsPending = false;
            mPendingAudioRoute.clearPendingMessages();
            onCurrentRouteChanged();
            if (mFeatureFlags.telecomMetricsSupport()) {
                mMetricsController.getAudioRouteStats().onRouteExit(mPendingAudioRoute, true);
            }
        }
    }

    private void onCurrentRouteChanged() {
        synchronized (mLock) {
            BluetoothDevice activeBluetoothDevice = null;
            int route = ROUTE_MAP.get(mCurrentRoute.getType());
            if (route == CallAudioState.ROUTE_STREAMING) {
                updateCallAudioState(new CallAudioState(mIsMute, route, route));
                return;
            }
            if (route == CallAudioState.ROUTE_BLUETOOTH) {
                activeBluetoothDevice = mBluetoothRoutes.get(mCurrentRoute);
            }
            updateCallAudioState(new CallAudioState(mIsMute, route,
                    mCallAudioState.getRawSupportedRouteMask(), activeBluetoothDevice,
                    mCallAudioState.getSupportedBluetoothDevices()));
        }
    }

    private void onAvailableRoutesChanged() {
        synchronized (mLock) {
            int routeMask = 0;
            Set<BluetoothDevice> availableBluetoothDevices = new HashSet<>();
            for (AudioRoute route : getCallSupportedRoutes()) {
                routeMask |= ROUTE_MAP.get(route.getType());
                if (BT_AUDIO_ROUTE_TYPES.contains(route.getType())) {
                    BluetoothDevice deviceToAdd = mBluetoothRoutes.get(route);
                    // Only include the lead device for LE audio (otherwise, the routes will show
                    // two separate devices in the UI).
                    if (route.getType() == AudioRoute.TYPE_BLUETOOTH_LE
                            && getLeAudioService() != null) {
                        int groupId = getLeAudioService().getGroupId(deviceToAdd);
                        if (groupId != BluetoothLeAudio.GROUP_ID_INVALID) {
                            deviceToAdd = getLeAudioService().getConnectedGroupLeadDevice(groupId);
                        }
                    }
                    // This will only ever be null when the lead device (LE) is disconnected and
                    // try to obtain the lead device for the 2nd bud.
                    if (deviceToAdd != null) {
                        availableBluetoothDevices.add(deviceToAdd);
                    }
                }
            }

            updateCallAudioState(new CallAudioState(mIsMute, mCallAudioState.getRoute(), routeMask,
                    mCallAudioState.getActiveBluetoothDevice(), availableBluetoothDevices));
        }
    }

    private void onMuteStateChanged(boolean mute) {
        updateCallAudioState(new CallAudioState(mute, mCallAudioState.getRoute(),
                mCallAudioState.getSupportedRouteMask(), mCallAudioState.getActiveBluetoothDevice(),
                mCallAudioState.getSupportedBluetoothDevices()));
    }

    /**
     * Retrieves the current call's supported audio route and adjusts the audio routing if the
     * current route isn't supported.
     */
    private void updateRouteForForeground() {
        boolean updatedRouteForCall = updateCallSupportedAudioRoutes();
        // Ensure that current call audio state has updated routes for current call.
        if (updatedRouteForCall) {
            mCallAudioState = new CallAudioState(mIsMute, mCallAudioState.getRoute(),
                    mCallSupportedRouteMask, mCallAudioState.getActiveBluetoothDevice(),
                    mCallAudioState.getSupportedBluetoothDevices());
            // Update audio route if foreground call doesn't support the current route.
            if ((mCallSupportedRouteMask & mCallAudioState.getRoute()) == 0) {
                routeTo(mIsActive, getBaseRoute(true, null));
            }
        }
    }

    /**
     * Update supported audio routes for the foreground call if present.
     */
    private boolean updateCallSupportedAudioRoutes() {
        int availableRouteMask = 0;
        Call foregroundCall = mCallsManager.getForegroundCall();
        mCallSupportedRoutes.clear();
        if (foregroundCall != null) {
            int foregroundCallSupportedRouteMask = foregroundCall.getSupportedAudioRoutes();
            for (AudioRoute route : getAvailableRoutes()) {
                int routeType = ROUTE_MAP.get(route.getType());
                availableRouteMask |= routeType;
                if ((routeType & foregroundCallSupportedRouteMask) == routeType) {
                    mCallSupportedRoutes.add(route);
                }
            }
            mCallSupportedRouteMask = availableRouteMask & foregroundCallSupportedRouteMask;
            return true;
        } else {
            mCallSupportedRouteMask = -1;
            return false;
        }
    }

    private void updateCallAudioState(CallAudioState newCallAudioState) {
        synchronized (mTelecomLock) {
            Log.i(this, "updateCallAudioState: updating call audio state to %s", newCallAudioState);
            CallAudioState oldState = mCallAudioState;
            mCallAudioState = newCallAudioState;
            // Update status bar notification
            mStatusBarNotifier.notifyMute(newCallAudioState.isMuted());
            mCallsManager.onCallAudioStateChanged(oldState, mCallAudioState);
            updateAudioStateForTrackedCalls(mCallAudioState);
        }
    }

    private void updateAudioStateForTrackedCalls(CallAudioState newCallAudioState) {
        List<Call> calls = new ArrayList<>(mCallsManager.getTrackedCalls());
        for (Call call : calls) {
            if (call != null && call.getConnectionService() != null) {
                call.getConnectionService().onCallAudioStateChanged(call, newCallAudioState);
            }
        }
    }

    private AudioRoute getPreferredAudioRouteFromStrategy() {
        // Get preferred device
        AudioDeviceAttributes deviceAttr = getPreferredDeviceForStrategy();
        Log.i(this, "getPreferredAudioRouteFromStrategy: preferred device is %s", deviceAttr);
        if (deviceAttr == null) {
            return null;
        }

        // Get corresponding audio route
        @AudioRoute.AudioRouteType int type = AudioRoute.DEVICE_INFO_TYPE_TO_AUDIO_ROUTE_TYPE.get(
                deviceAttr.getType());
        if (BT_AUDIO_ROUTE_TYPES.contains(type)) {
            return getBluetoothRoute(type, deviceAttr.getAddress());
        } else {
            return mTypeRoutes.get(type);
        }
    }

    private AudioDeviceAttributes getPreferredDeviceForStrategy() {
        // Get audio produce strategy
        AudioProductStrategy strategy = null;
        final AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build();
        List<AudioProductStrategy> strategies = AudioManager.getAudioProductStrategies();
        for (AudioProductStrategy s : strategies) {
            if (s.supportsAudioAttributes(attr)) {
                strategy = s;
            }
        }
        if (strategy == null) {
            return null;
        }

        return mAudioManager.getPreferredDeviceForStrategy(strategy);
    }

    private AudioRoute getPreferredAudioRouteFromDefault(boolean includeBluetooth,
            String btAddressToExclude) {
        boolean skipEarpiece;
        Call foregroundCall = mCallAudioManager.getForegroundCall();
        synchronized (mTelecomLock) {
            skipEarpiece = foregroundCall != null
                    && VideoProfile.isVideo(foregroundCall.getVideoState());
        }
        // Route to earpiece, wired, or speaker route if there are not bluetooth routes or if there
        // are only wearables available.
        AudioRoute activeWatchOrNonWatchDeviceRoute =
                getActiveWatchOrNonWatchDeviceRoute(btAddressToExclude);
        if ((!mCallSupportedRoutes.isEmpty() && (mCallSupportedRouteMask
                & CallAudioState.ROUTE_BLUETOOTH) == 0) || mBluetoothRoutes.isEmpty()
                || !includeBluetooth || activeWatchOrNonWatchDeviceRoute == null) {
            Log.i(this, "getPreferredAudioRouteFromDefault: Audio routing defaulting to "
                    + "available non-BT route.");
            boolean callSupportsEarpieceWiredRoute = mCallSupportedRoutes.isEmpty()
                    || mCallSupportedRoutes.contains(mEarpieceWiredRoute);
            // If call supported route doesn't contain earpiece/wired/BT, it should have speaker
            // enabled. Otherwise, no routes would be supported for the call which should never be
            // the case.
            AudioRoute defaultRoute = mEarpieceWiredRoute != null && callSupportsEarpieceWiredRoute
                    ? mEarpieceWiredRoute
                    : mSpeakerDockRoute;
            // Ensure that we default to speaker route if we're in a video call, but disregard it if
            // a wired headset is plugged in.
            if (skipEarpiece && defaultRoute != null
                    && defaultRoute.getType() == AudioRoute.TYPE_EARPIECE) {
                Log.i(this, "getPreferredAudioRouteFromDefault: Audio routing defaulting to "
                        + "speaker route for video call.");
                defaultRoute = mSpeakerDockRoute;
            }
            return defaultRoute;
        } else {
            // Most recent active route will always be the last in the array (ensure that we don't
            // auto route to a wearable device unless it's already active).
            String autoRoutingToWatchExcerpt = mFeatureFlags.ignoreAutoRouteToWatchDevice()
                    ? " (except watch)"
                    : "";
            Log.i(this, "getPreferredAudioRouteFromDefault: Audio routing defaulting to "
                    + "most recently active BT route" + autoRoutingToWatchExcerpt + ".");
            return activeWatchOrNonWatchDeviceRoute;
        }
    }

    private int calculateSupportedRouteMaskInit() {
        Log.i(this, "calculateSupportedRouteMaskInit: is wired headset plugged in - %s",
                mWiredHeadsetManager.isPluggedIn());
        int routeMask = CallAudioState.ROUTE_SPEAKER;

        if (mWiredHeadsetManager.isPluggedIn()) {
            routeMask |= CallAudioState.ROUTE_WIRED_HEADSET;
        } else {
            AudioDeviceInfo[] deviceList = mAudioManager.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device: deviceList) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    routeMask |= CallAudioState.ROUTE_EARPIECE;
                    break;
                }
            }
        }
        return routeMask;
    }

    @VisibleForTesting
    public Set<AudioRoute> getAvailableRoutes() {
        if (mCurrentRoute.equals(mStreamingRoute)) {
            return mStreamingRoutes;
        } else {
            return mAvailableRoutes;
        }
    }

    public Set<AudioRoute> getCallSupportedRoutes() {
        if (mCurrentRoute.equals(mStreamingRoute)) {
            return mStreamingRoutes;
        } else {
            if (mAvailableRoutesUpdated) {
                updateCallSupportedAudioRoutes();
                mAvailableRoutesUpdated = false;
            }
            return mCallSupportedRoutes.isEmpty() ? mAvailableRoutes : mCallSupportedRoutes;
        }
    }

    public AudioRoute getCurrentRoute() {
        return mCurrentRoute;
    }

    public AudioRoute getBluetoothRoute(@AudioRoute.AudioRouteType int audioRouteType,
            String address) {
        for (AudioRoute route : mBluetoothRoutes.keySet()) {
            if (route.getType() == audioRouteType && route.getBluetoothAddress().equals(address)) {
                return route;
            }
        }
        return null;
    }

    public AudioRoute getBaseRoute(boolean includeBluetooth, String btAddressToExclude) {
        AudioRoute destRoute = getPreferredAudioRouteFromStrategy();
        Log.i(this, "getBaseRoute: preferred audio route is %s", destRoute);
        if (destRoute == null || (destRoute.getBluetoothAddress() != null && (!includeBluetooth
                || destRoute.getBluetoothAddress().equals(btAddressToExclude)))) {
            destRoute = getPreferredAudioRouteFromDefault(includeBluetooth, btAddressToExclude);
        }
        if (destRoute != null && !getCallSupportedRoutes().contains(destRoute)) {
            destRoute = null;
        }
        Log.i(this, "getBaseRoute - audio routing to %s", destRoute);
        return destRoute;
    }

    private AudioRoute calculateBaselineRoute(boolean includeBluetooth, String btAddressToExclude) {
        AudioRoute destRoute = getPreferredAudioRouteFromDefault(
                includeBluetooth, btAddressToExclude);
        if (destRoute != null && !getCallSupportedRoutes().contains(destRoute)) {
            destRoute = null;
        }
        Log.i(this, "getBaseRoute - audio routing to %s", destRoute);
        return destRoute;
    }

    /**
     * Don't add additional AudioRoute when a hearing aid pair is detected. The devices have
     * separate addresses, so we need to perform explicit handling to ensure we don't
     * treat them as two separate devices.
     */
    private boolean containsHearingAidPair(@AudioRoute.AudioRouteType int type,
            BluetoothDevice bluetoothDevice) {
        // Check if it is a hearing aid pair and skip connecting to the other device in this case.
        // Traverse mBluetoothRoutes backwards as the most recently active device will be inserted
        // last.
        String existingHearingAidAddress = null;
        List<AudioRoute> bluetoothRoutes = mBluetoothRoutes.keySet().stream().toList();
        for (int i = bluetoothRoutes.size() - 1; i >= 0; i--) {
            AudioRoute audioRoute = bluetoothRoutes.get(i);
            if (audioRoute.getType() == AudioRoute.TYPE_BLUETOOTH_HA) {
                existingHearingAidAddress = audioRoute.getBluetoothAddress();
                break;
            }
        }

        // Check that route is for hearing aid and that there exists another hearing aid route
        // created for the first device (of the pair) that was connected.
        if (type == AudioRoute.TYPE_BLUETOOTH_HA && existingHearingAidAddress != null) {
            BluetoothAdapter bluetoothAdapter = mBluetoothRouteManager.getDeviceManager()
                    .getBluetoothAdapter();
            if (bluetoothAdapter != null) {
                List<BluetoothDevice> activeHearingAids =
                        bluetoothAdapter.getActiveDevices(BluetoothProfile.HEARING_AID);
                for (BluetoothDevice hearingAid : activeHearingAids) {
                    if (hearingAid != null && hearingAid.getAddress() != null) {
                        String address = hearingAid.getAddress();
                        if (address.equals(bluetoothDevice.getAddress())
                                || address.equals(existingHearingAidAddress)) {
                            Log.i(this, "containsHearingAidPair: Detected a hearing aid "
                                    + "pair, ignoring creating a new AudioRoute");
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Prevent auto routing to a wearable device when calculating the default bluetooth audio route
     * to move to. This function ensures that the most recently active non-wearable device is
     * selected for routing unless a wearable device has already been identified as an active
     * device.
     */
    private AudioRoute getActiveWatchOrNonWatchDeviceRoute(String btAddressToExclude) {
        if (!mFeatureFlags.ignoreAutoRouteToWatchDevice()) {
            Log.i(this, "getActiveWatchOrNonWatchDeviceRoute: ignore_auto_route_to_watch_device "
                    + "flag is disabled. Routing to most recently reported active device.");
            return getMostRecentlyActiveBtRoute(btAddressToExclude);
        }

        List<AudioRoute> bluetoothRoutes = mBluetoothRoutes.keySet().stream().toList();
        // Traverse the routes from the most recently active recorded devices first.
        AudioRoute nonWatchDeviceRoute = null;
        for (int i = bluetoothRoutes.size() - 1; i >= 0; i--) {
            AudioRoute route = bluetoothRoutes.get(i);
            BluetoothDevice device = mBluetoothRoutes.get(route);
            // Skip excluded BT address and LE audio if it's not the lead device.
            if (route.getBluetoothAddress().equals(btAddressToExclude)
                    || isLeAudioNonLeadDeviceOrServiceUnavailable(route.getType(), device)) {
                continue;
            }
            // Check if the most recently active device is a watch device.
            if (i == (bluetoothRoutes.size() - 1) && device.equals(mCallAudioState
                    .getActiveBluetoothDevice()) && mBluetoothRouteManager.isWatch(device)) {
                Log.i(this, "getActiveWatchOrNonWatchDeviceRoute: Routing to active watch - %s",
                        bluetoothRoutes.get(0));
                return bluetoothRoutes.get(0);
            }
            // Record the first occurrence of a non-watch device route if found.
            if (!mBluetoothRouteManager.isWatch(device) && nonWatchDeviceRoute == null) {
                nonWatchDeviceRoute = route;
                break;
            }
        }

        Log.i(this, "Routing to a non-watch device - %s", nonWatchDeviceRoute);
        return nonWatchDeviceRoute;
    }

    /**
     * Returns the most actively reported bluetooth route excluding the passed in route.
     */
    private AudioRoute getMostRecentlyActiveBtRoute(String btAddressToExclude) {
        List<AudioRoute> bluetoothRoutes = mBluetoothRoutes.keySet().stream().toList();
        for (int i = bluetoothRoutes.size() - 1; i >= 0; i--) {
            AudioRoute route = bluetoothRoutes.get(i);
            // Skip LE route if it's not the lead device.
            if (isLeAudioNonLeadDeviceOrServiceUnavailable(
                    route.getType(), mBluetoothRoutes.get(route))) {
                continue;
            }
            if (!route.getBluetoothAddress().equals(btAddressToExclude)) {
                return route;
            }
        }
        return null;
    }

    private boolean isLeAudioNonLeadDeviceOrServiceUnavailable(@AudioRoute.AudioRouteType int type,
            BluetoothDevice device) {
        if (type != AudioRoute.TYPE_BLUETOOTH_LE) {
            return false;
        } else if (getLeAudioService() == null) {
            return true;
        }

        int groupId = getLeAudioService().getGroupId(device);
        if (groupId != BluetoothLeAudio.GROUP_ID_INVALID) {
            BluetoothDevice leadDevice = getLeAudioService().getConnectedGroupLeadDevice(groupId);
            Log.i(this, "Lead device for device (%s) is %s.", device, leadDevice);
            return leadDevice == null || !device.getAddress().equals(leadDevice.getAddress());
        }
        return false;
    }

    private BluetoothLeAudio getLeAudioService() {
        return mBluetoothRouteManager.getDeviceManager().getLeAudioService();
    }

    @VisibleForTesting
    public void setAudioManager(AudioManager audioManager) {
        mAudioManager = audioManager;
    }

    @VisibleForTesting
    public void setAudioRouteFactory(AudioRoute.Factory audioRouteFactory) {
        mAudioRouteFactory = audioRouteFactory;
    }

    public Map<AudioRoute, BluetoothDevice> getBluetoothRoutes() {
        return mBluetoothRoutes;
    }

    public void overrideIsPending(boolean isPending) {
        mIsPending = isPending;
    }

    public void setIsScoAudioConnected(boolean value) {
        mIsScoAudioConnected = value;
    }

    private void clearRingingBluetoothAddress() {
        mBluetoothAddressForRinging = null;
    }

    /**
     * Update the active bluetooth device being tracked (as well as for individual profiles).
     * We need to keep track of active devices for individual profiles because of potential
     * inconsistencies found in BluetoothStateReceiver#handleActiveDeviceChanged. When multiple
     * profiles are paired, we could have a scenario where an active device A is replaced
     * with an active device B (from a different profile), which is then removed as an active
     * device shortly after, causing device A to be reactive. It's possible that the active device
     * changed intent is never received again for device A so an active device cache is necessary
     * to track these devices at a profile level.
     * @param device {@link Pair} containing the BT audio route type (i.e. SCO/HA/LE) and the
     *                           address of the device.
     */
    public void updateActiveBluetoothDevice(Pair<Integer, String> device) {
        mActiveDeviceCache.put(device.first, device.second);
        // Update most recently active device if address isn't null (meaning some device is active).
        if (device.second != null) {
            mActiveBluetoothDevice = device;
        } else {
            // If a device was removed, check to ensure that no other device is still considered
            // active.
            boolean hasActiveDevice = false;
            for (String address : mActiveDeviceCache.values()) {
                if (address != null) {
                    hasActiveDevice = true;
                    break;
                }
            }
            if (!hasActiveDevice) {
                mActiveBluetoothDevice = null;
            }
        }
    }

    private void updateAvailableRoutes(AudioRoute route, boolean includeRoute) {
        if (includeRoute) {
            mAvailableRoutes.add(route);
        } else {
            mAvailableRoutes.remove(route);
        }
        mAvailableRoutesUpdated = true;
    }

    @VisibleForTesting
    public void setActive(boolean active) {
        if (active) {
            mFocusType = ACTIVE_FOCUS;
        } else {
            mFocusType = NO_FOCUS;
        }
        mIsActive = active;
    }

    void fallBack(String btAddressToExclude) {
        mMetricsController.getAudioRouteStats().onRouteExit(mPendingAudioRoute, false);
        sendMessageWithSessionInfo(SWITCH_BASELINE_ROUTE, INCLUDE_BLUETOOTH_IN_BASELINE,
                btAddressToExclude);
    }
}
