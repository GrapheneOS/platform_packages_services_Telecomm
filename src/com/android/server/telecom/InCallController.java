/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.telecom;

import static android.app.AppOpsManager.OPSTR_RECORD_AUDIO;
import static android.os.Process.myUid;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.SensorPrivacyManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.Connection;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.InCallService;
import android.telecom.Log;
import android.telecom.Logging.Runnable;
import android.telecom.ParcelableCall;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telecom.IInCallService;
import com.android.server.telecom.SystemStateHelper.SystemStateListener;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.ui.NotificationChannelManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Binds to {@link IInCallService} and provides the service to {@link CallsManager} through which it
 * can send updates to the in-call app. This class is created and owned by CallsManager and retains
 * a binding to the {@link IInCallService} (implemented by the in-call app).
 */
public class InCallController extends CallsManagerListenerBase implements
        AppOpsManager.OnOpActiveChangedListener {
    public static final String NOTIFICATION_TAG = InCallController.class.getSimpleName();
    public static final int IN_CALL_SERVICE_NOTIFICATION_ID = 3;
    private AnomalyReporterAdapter mAnomalyReporter = new AnomalyReporterAdapterImpl();

    /**
     * Anomaly Report UUIDs and corresponding error descriptions specific to InCallController.
     */
    public static final UUID SET_IN_CALL_ADAPTER_ERROR_UUID =
            UUID.fromString("0c2adf96-353a-433c-afe9-1e5564f304f9");
    public static final String SET_IN_CALL_ADAPTER_ERROR_MSG =
            "Exception thrown while setting the in-call adapter.";
    public static final UUID NULL_IN_CALL_SERVICE_BINDING_UUID =
            UUID.fromString("7d58dedf-b71d-4c18-9d23-47b434bde58b");
    public static final String NULL_IN_CALL_SERVICE_BINDING_ERROR_MSG =
            "InCallController#sendCallToInCallService with null InCallService binding";
    @VisibleForTesting
    public void setAnomalyReporterAdapter(AnomalyReporterAdapter mAnomalyReporterAdapter){
        mAnomalyReporter = mAnomalyReporterAdapter;
    }

    public class InCallServiceConnection {
        /**
         * Indicates that a call to {@link #connect(Call)} has succeeded and resulted in a
         * connection to an InCallService.
         */
        public static final int CONNECTION_SUCCEEDED = 1;
        /**
         * Indicates that a call to {@link #connect(Call)} has failed because of a binding issue.
         */
        public static final int CONNECTION_FAILED = 2;
        /**
         * Indicates that a call to {@link #connect(Call)} has been skipped because the
         * IncallService does not support the type of call..
         */
        public static final int CONNECTION_NOT_SUPPORTED = 3;

        public class Listener {
            public void onDisconnect(InCallServiceConnection conn, Call call) {}
        }

        protected Listener mListener;

        public int connect(Call call) { return CONNECTION_FAILED; }
        public void disconnect() {}
        public boolean isConnected() { return false; }
        public void setHasEmergency(boolean hasEmergency) {}
        public void setListener(Listener l) {
            mListener = l;
        }
        public InCallServiceInfo getInfo() { return null; }
        public void dump(IndentingPrintWriter pw) {}
        public Call mCall;
        private Call mPendingCall;
        public Call getPendingCall() { return mPendingCall; }
        public void setPendingCall(Call pendingCall) {
            mPendingCall = pendingCall;
        }
    }

    public static class InCallServiceInfo {
        private final ComponentName mComponentName;
        private boolean mIsExternalCallsSupported;
        private boolean mIsSelfManagedCallsSupported;
        private final int mType;
        private long mBindingStartTime;
        private long mDisconnectTime;

        private boolean mHasCrossUserOrProfilePerm;

        public InCallServiceInfo(ComponentName componentName,
                boolean isExternalCallsSupported,
                boolean isSelfManageCallsSupported,
                int type, boolean hasCrossUserOrProfilePerm) {
            mComponentName = componentName;
            mIsExternalCallsSupported = isExternalCallsSupported;
            mIsSelfManagedCallsSupported = isSelfManageCallsSupported;
            mType = type;
            mHasCrossUserOrProfilePerm = hasCrossUserOrProfilePerm;
        }

        public boolean hasCrossUserOrProfilePermission() { return mHasCrossUserOrProfilePerm; }
        public ComponentName getComponentName() {
            return mComponentName;
        }

        public boolean isExternalCallsSupported() {
            return mIsExternalCallsSupported;
        }

        public boolean isSelfManagedCallsSupported() {
            return mIsSelfManagedCallsSupported;
        }

        public int getType() {
            return mType;
        }

        public long getBindingStartTime() {
            return mBindingStartTime;
        }

        public long getDisconnectTime() {
            return mDisconnectTime;
        }

        public void setBindingStartTime(long bindingStartTime) {
            mBindingStartTime = bindingStartTime;
        }

        public void setDisconnectTime(long disconnectTime) {
            mDisconnectTime = disconnectTime;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            InCallServiceInfo that = (InCallServiceInfo) o;

            if (mIsExternalCallsSupported != that.mIsExternalCallsSupported) {
                return false;
            }
            if (mIsSelfManagedCallsSupported != that.mIsSelfManagedCallsSupported) {
                return false;
            }
            return mComponentName.equals(that.mComponentName);

        }

        @Override
        public int hashCode() {
            return Objects.hash(mComponentName, mIsExternalCallsSupported,
                    mIsSelfManagedCallsSupported);
        }

        @Override
        public String toString() {
            return "[" + mComponentName + " supportsExternal? " + mIsExternalCallsSupported +
                    " supportsSelfMg?" + mIsSelfManagedCallsSupported + "]";
        }
    }

    private class InCallServiceBindingConnection extends InCallServiceConnection {

        private final ServiceConnection mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.startSession("ICSBC.oSC", Log.getPackageAbbreviation(name));
                synchronized (mLock) {
                    try {
                        Log.d(this, "onServiceConnected: %s %b %b", name, mIsBound, mIsConnected);
                        mIsBound = true;
                        if (mIsConnected) {
                            // Only proceed if we are supposed to be connected.
                            onConnected(service);
                        }
                    } finally {
                        Log.endSession();
                    }
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.startSession("ICSBC.oSD", Log.getPackageAbbreviation(name));
                synchronized (mLock) {
                    try {
                        Log.d(this, "onServiceDisconnected: %s", name);
                        mIsBound = false;
                        onDisconnected();
                    } finally {
                        Log.endSession();
                    }
                }
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Log.startSession("ICSBC.oNB", Log.getPackageAbbreviation(name));
                synchronized (mLock) {
                    try {
                        Log.d(this, "onNullBinding: %s", name);
                        mIsNullBinding = true;
                        mIsBound = false;
                        onDisconnected();
                    } finally {
                        Log.endSession();
                    }
                }
            }

            @Override
            public void onBindingDied(ComponentName name) {
                Log.startSession("ICSBC.oBD", Log.getPackageAbbreviation(name));
                synchronized (mLock) {
                    try {
                        Log.d(this, "onBindingDied: %s", name);
                        mIsBound = false;
                        onDisconnected();
                    } finally {
                        Log.endSession();
                    }
                }
            }
        };

        private final InCallServiceInfo mInCallServiceInfo;
        private boolean mIsConnected = false;
        private boolean mIsBound = false;
        private boolean mIsNullBinding = false;

        //this is really used for cases where the userhandle for a call
        //does not match what we want to use for bindAsUser
        private UserHandle mUserHandleToUseForBinding;

        public InCallServiceBindingConnection(InCallServiceInfo info) {
            mInCallServiceInfo = info;
            mUserHandleToUseForBinding = null;
        }

        public InCallServiceBindingConnection(InCallServiceInfo info,
                UserHandle userHandleToUseForBinding) {
            mInCallServiceInfo = info;
            mUserHandleToUseForBinding = userHandleToUseForBinding;
        }

        @Override
        public int connect(Call call) {
            UserHandle userFromCall = getUserFromCall(call);

            if (mIsConnected) {
                Log.addEvent(call, LogUtils.Events.INFO, "Already connected, ignoring request: "
                        + mInCallServiceInfo);
                if (call != null) {
                    // Track the call if we don't already know about it.
                    addCall(call);

                    // Notify this new added call
                    if (mInCallServiceInfo.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH) {
                        synchronized (mLock) {
                            Pair<InCallServiceInfo, IInCallService> btService =
                                    mBTInCallServices.get(userFromCall);
                            if (btService != null) {
                                sendCallToService(call, mInCallServiceInfo, btService.second);
                            }
                        }
                    } else {
                        sendCallToService(call, mInCallServiceInfo,
                                mInCallServices.get(userFromCall).get(mInCallServiceInfo));
                    }
                }
                return CONNECTION_SUCCEEDED;
            }

            if (call != null && call.isSelfManaged() &&
                    (!mInCallServiceInfo.isSelfManagedCallsSupported()
                            || !call.visibleToInCallService())) {
                Log.i(this, "Skipping binding to %s - doesn't support self-mgd calls",
                        mInCallServiceInfo);
                mIsConnected = false;
                return CONNECTION_NOT_SUPPORTED;
            }

            Intent intent = new Intent(InCallService.SERVICE_INTERFACE);
            intent.setComponent(mInCallServiceInfo.getComponentName());
            if (call != null && !call.isIncoming() && !call.isExternalCall()) {
                intent.putExtra(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,
                        call.getIntentExtras());
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                        call.getTargetPhoneAccount());
            }

            Log.i(this, "Attempting to bind to InCall %s, with %s", mInCallServiceInfo, intent);
            if (mInCallServiceInfo.mType == IN_CALL_SERVICE_TYPE_DEFAULT_DIALER_UI ||
                mInCallServiceInfo.mType == IN_CALL_SERVICE_TYPE_SYSTEM_UI) {
                // Cache this call for dialer ICS as binding is taking time
                setPendingCall(call);
            }

            mIsConnected = true;
            mInCallServiceInfo.setBindingStartTime(mClockProxy.elapsedRealtime());
            boolean isManagedProfile = UserUtil.isManagedProfile(mContext,
                    userFromCall);
            // Note that UserHandle.CURRENT fails to capture the work profile, so we need to handle
            // it separately to ensure that the ICS is bound to the appropriate user. If ECBM is
            // active, we know that a work sim was previously used to place a MO emergency call. We
            // need to ensure that we bind to the CURRENT_USER in this case, as the work user would
            // not be running (handled in getUserFromCall).
            UserHandle userToBind = isManagedProfile ? userFromCall : UserHandle.CURRENT;
            if ((mInCallServiceInfo.mType == IN_CALL_SERVICE_TYPE_NON_UI
                    || mInCallServiceInfo.mType == IN_CALL_SERVICE_TYPE_CAR_MODE_UI
                    || mInCallServiceInfo.mType == IN_CALL_SERVICE_TYPE_BLUETOOTH) && (
                    mUserHandleToUseForBinding != null)) {
                //guarding change for non-UI/carmode-UI services which may not be present for
                // work profile.
                //In this case, we use the parent user handle. (This also seems to be more
                // accurate that USER_CURRENT since we queried/discovered the packages using the
                // parent handle)
                if (mInCallServiceInfo.hasCrossUserOrProfilePermission()) {
                    userToBind = mUserHandleToUseForBinding;
                } else {
                    Log.i(this,
                            "service does not have INTERACT_ACROSS_PROFILES or "
                                    + "INTERACT_ACROSS_USERS permission");
                }
            }
            // Used for referencing what user we used to bind to the given ICS.
            mUserHandleToUseForBinding = userToBind;
            Log.i(this, "using user id: %s for binding. User from Call is: %s", userToBind,
                    userFromCall);
            if (!mContext.bindServiceAsUser(intent, mServiceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE
                        | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS
                        | Constants.BIND_SCHEDULE_LIKE_TOP_APP, userToBind)) {
                Log.w(this, "Failed to connect.");
                mIsConnected = false;
            }

            if (mIsConnected && call != null) {
                mCall = call;
            }
            Log.i(this, "mCall: %s, mIsConnected: %s", mCall, mIsConnected);

            return mIsConnected ? CONNECTION_SUCCEEDED : CONNECTION_FAILED;
        }

        @Override
        public InCallServiceInfo getInfo() {
            return mInCallServiceInfo;
        }

        @Override
        public void disconnect() {
            if (mIsConnected) {
                UserHandle userFromCall = getUserFromCall(mCall);
                mInCallServiceInfo.setDisconnectTime(mClockProxy.elapsedRealtime());
                Log.i(InCallController.this, "ICSBC#disconnect: unbinding after %s ms;"
                                + "%s. isCrashed: %s", mInCallServiceInfo.mDisconnectTime
                                - mInCallServiceInfo.mBindingStartTime,
                        mInCallServiceInfo, mIsNullBinding);
                String packageName = mInCallServiceInfo.getComponentName().getPackageName();
                mContext.unbindService(mServiceConnection);
                mIsConnected = false;
                if (mIsNullBinding && mInCallServiceInfo.getType() != IN_CALL_SERVICE_TYPE_NON_UI) {
                    // Non-UI InCallServices are allowed to return null from onBind if they don't
                    // want to handle calls at the moment, so don't report them to the user as
                    // crashed.
                    sendCrashedInCallServiceNotification(packageName, userFromCall);
                }
                if (mCall != null) {
                    updateCallTracking(mCall, mInCallServiceInfo, false /* isAdd */);
                }

                InCallController.this.onDisconnected(mInCallServiceInfo, userFromCall);
            } else {
                Log.i(InCallController.this, "ICSBC#disconnect: already disconnected; %s",
                        mInCallServiceInfo);
                Log.addEvent(null, LogUtils.Events.INFO, "Already disconnected, ignoring request.");
            }
        }

        @Override
        public boolean isConnected() {
            return mIsConnected;
        }

        @Override
        public void dump(IndentingPrintWriter pw) {
            pw.print("BindingConnection [");
            pw.print(mIsConnected ? "" : "not ");
            pw.print("connected, ");
            pw.print(mIsBound ? "" : "not ");
            pw.print("bound, ");
            pw.print(mInCallServiceInfo);
            pw.println("\n");
        }

        protected void onConnected(IBinder service) {
            boolean shouldRemainConnected =
                    InCallController.this.onConnected(mInCallServiceInfo, service,
                            getUserFromCall(mCall));
            if (!shouldRemainConnected) {
                // Sometimes we can opt to disconnect for certain reasons, like if the
                // InCallService rejected our initialization step, or the calls went away
                // in the time it took us to bind to the InCallService. In such cases, we go
                // ahead and disconnect ourselves.
                disconnect();
            }
        }

        protected void onDisconnected() {
            boolean shouldReconnect = mIsConnected;
            InCallController.this.onDisconnected(mInCallServiceInfo, getUserFromCall(mCall));
            disconnect();  // Unbind explicitly if we get disconnected.
            if (mListener != null) {
                mListener.onDisconnect(InCallServiceBindingConnection.this, mCall);
            }
            // Check if we are expected to reconnect
            if (shouldReconnect && shouldHandleReconnect()) {
                connect(mCall);  // reconnect
            }
        }

        private boolean shouldHandleReconnect() {
            int serviceType = mInCallServiceInfo.getType();
            boolean nonUI = (serviceType == IN_CALL_SERVICE_TYPE_NON_UI)
                    || (serviceType == IN_CALL_SERVICE_TYPE_COMPANION);
            boolean carModeUI = (serviceType == IN_CALL_SERVICE_TYPE_CAR_MODE_UI);

            return carModeUI || (nonUI && !mIsNullBinding);
        }
    }

    /**
     * A version of the InCallServiceBindingConnection that proxies all calls to a secondary
     * connection until it finds an emergency call, or the other connection dies. When one of those
     * two things happen, this class instance will take over the connection.
     */
    private class EmergencyInCallServiceConnection extends InCallServiceBindingConnection {
        private boolean mIsProxying = true;
        private boolean mIsConnected = false;
        private final InCallServiceConnection mSubConnection;

        private Listener mSubListener = new Listener() {
            @Override
            public void onDisconnect(InCallServiceConnection subConnection, Call call) {
                if (subConnection == mSubConnection) {
                    if (mIsConnected && mIsProxying) {
                        // At this point we know that we need to be connected to the InCallService
                        // and we are proxying to the sub connection.  However, the sub-connection
                        // just died so we need to stop proxying and connect to the system in-call
                        // service instead.
                        mIsProxying = false;
                        connect(call);
                    }
                }
            }
        };

        public EmergencyInCallServiceConnection(
                InCallServiceInfo info, InCallServiceConnection subConnection) {

            super(info);
            mSubConnection = subConnection;
            if (mSubConnection != null) {
                mSubConnection.setListener(mSubListener);
            }
            mIsProxying = (mSubConnection != null);
        }

        @Override
        public int connect(Call call) {
            mIsConnected = true;
            if (mIsProxying) {
                int result = mSubConnection.connect(call);
                mIsConnected = result == CONNECTION_SUCCEEDED;
                if (result != CONNECTION_FAILED) {
                    return result;
                }
                // Could not connect to child, stop proxying.
                mIsProxying = false;
            }
            UserHandle userFromCall = getUserFromCall(call);
            mEmergencyCallHelper.maybeGrantTemporaryLocationPermission(call,
                    userFromCall);

            if (call != null && call.isIncoming()
                    && mEmergencyCallHelper.getLastEmergencyCallTimeMillis() > 0) {
                // Add the last emergency call time to the call
                Bundle extras = new Bundle();
                extras.putLong(android.telecom.Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS,
                        mEmergencyCallHelper.getLastEmergencyCallTimeMillis());
                call.putConnectionServiceExtras(extras);
            }

            // If we are here, we didn't or could not connect to child. So lets connect ourselves.
            return super.connect(call);
        }

        @Override
        public void disconnect() {
            Log.i(this, "Disconnecting from InCallService");
            if (mIsProxying) {
                mSubConnection.disconnect();
            } else {
                super.disconnect();
                mEmergencyCallHelper.maybeRevokeTemporaryLocationPermission();
            }
            mIsConnected = false;
        }

        @Override
        public void setHasEmergency(boolean hasEmergency) {
            if (hasEmergency) {
                takeControl();
            }
        }

        @Override
        public InCallServiceInfo getInfo() {
            if (mIsProxying) {
                return mSubConnection.getInfo();
            } else {
                return super.getInfo();
            }
        }

        @Override
        protected void onDisconnected() {
            // Save this here because super.onDisconnected() could force us to explicitly
            // disconnect() as a cleanup step and that sets mIsConnected to false.
            boolean shouldReconnect = mIsConnected;
            super.onDisconnected();
            // We just disconnected.  Check if we are expected to be connected, and reconnect.
            if (shouldReconnect && !mIsProxying) {
                connect(mCall);  // reconnect
            }
        }

        @Override
        public Call getPendingCall() {
            if (mIsProxying) {
                return mSubConnection.getPendingCall();
            } else {
                return super.getPendingCall();
            }
        }

        @Override
        public void setPendingCall(Call pendingCall) {
            if (mIsProxying) {
                mSubConnection.setPendingCall(pendingCall);
            } else {
                super.setPendingCall(pendingCall);
            }
        }

        @Override
        public void dump(IndentingPrintWriter pw) {
            pw.print("Emergency ICS Connection [");
            pw.append(mIsProxying ? "" : "not ").append("proxying, ");
            pw.append(mIsConnected ? "" : "not ").append("connected]\n");
            pw.increaseIndent();
            pw.print("Emergency: ");
            super.dump(pw);
            if (mSubConnection != null) {
                pw.print("Default-Dialer: ");
                mSubConnection.dump(pw);
            }
            pw.decreaseIndent();
        }

        /**
         * Forces the connection to take control from it's subConnection.
         */
        private void takeControl() {
            if (mIsProxying) {
                mIsProxying = false;
                if (mIsConnected) {
                    mSubConnection.disconnect();
                    super.connect(null);
                }
            }
        }
    }

    /**
     * A version of InCallServiceConnection which switches UI between two separate sub-instances of
     * InCallServicesConnections.
     */
    private class CarSwappingInCallServiceConnection extends InCallServiceConnection {
        private final InCallServiceConnection mDialerConnection;
        private InCallServiceConnection mCarModeConnection;
        private InCallServiceConnection mCurrentConnection;
        private boolean mIsCarMode = false;
        private boolean mIsConnected = false;

        public CarSwappingInCallServiceConnection(
                InCallServiceConnection dialerConnection,
                InCallServiceConnection carModeConnection) {
            mDialerConnection = dialerConnection;
            mCarModeConnection = carModeConnection;
            mCurrentConnection = getCurrentConnection();
        }

        /**
         * Called when we move to a state where calls are present on the device.  Chooses the
         * {@link InCallService} to which we should connect.
         *
         * @param isCarMode {@code true} if device is in car mode, {@code false} otherwise.
         */
        public synchronized void chooseInitialInCallService(boolean isCarMode) {
            Log.i(this, "chooseInitialInCallService: " + mIsCarMode + " => " + isCarMode);
            if (isCarMode != mIsCarMode) {
                mIsCarMode = isCarMode;
                InCallServiceConnection newConnection = getCurrentConnection();
                if (newConnection != mCurrentConnection) {
                    if (mIsConnected) {
                        mCurrentConnection.disconnect();
                    }
                    int result = newConnection.connect(null);
                    mIsConnected = result == CONNECTION_SUCCEEDED;
                    mCurrentConnection = newConnection;
                }
            }
        }

        /**
         * Invoked when {@link CarModeTracker} has determined that the device is no longer in car
         * mode (i.e. has no car mode {@link InCallService}).
         *
         * Switches back to the default dialer app.
         */
        public synchronized void disableCarMode() {
            mIsCarMode = false;
            if (mIsConnected) {
                mCurrentConnection.disconnect();
            }

            mCurrentConnection = mDialerConnection;
            int result = mDialerConnection.connect(null);
            mIsConnected = result == CONNECTION_SUCCEEDED;
        }

        /**
         * Changes the active {@link InCallService} to a car mode app.  Called whenever the device
         * changes to car mode or the currently active car mode app changes.
         *
         * @param packageName The package name of the car mode app.
         */
        public synchronized void changeCarModeApp(String packageName, UserHandle userHandle) {
            Log.i(this, "changeCarModeApp: isCarModeNow=" + mIsCarMode);

            InCallServiceInfo currentConnectionInfo = mCurrentConnection == null ? null
                    : mCurrentConnection.getInfo();
            InCallServiceInfo carModeConnectionInfo =
                    getInCallServiceComponent(userHandle, packageName,
                            IN_CALL_SERVICE_TYPE_CAR_MODE_UI, true /* ignoreDisabed */);

            if (!Objects.equals(currentConnectionInfo, carModeConnectionInfo)
                    && carModeConnectionInfo != null) {
                Log.i(this, "changeCarModeApp: " + currentConnectionInfo + " => "
                        + carModeConnectionInfo);
                if (mIsConnected) {
                    mCurrentConnection.disconnect();
                }

                mCarModeConnection = mCurrentConnection =
                        new InCallServiceBindingConnection(carModeConnectionInfo, userHandle);
                mIsCarMode = true;

                int result = mCurrentConnection.connect(null);
                mIsConnected = result == CONNECTION_SUCCEEDED;
            } else {
                Log.i(this, "changeCarModeApp: unchanged; " + currentConnectionInfo + " => "
                        + carModeConnectionInfo);
            }
        }

        public boolean isCarMode() {
            return mIsCarMode;
        }

        @Override
        public int connect(Call call) {
            if (mIsConnected) {
                Log.i(this, "already connected");
                return CONNECTION_SUCCEEDED;
            } else {
                int result = mCurrentConnection.connect(call);
                if (result != CONNECTION_FAILED) {
                    mIsConnected = result == CONNECTION_SUCCEEDED;
                    return result;
                }
            }

            return CONNECTION_FAILED;
        }

        @Override
        public void disconnect() {
            if (mIsConnected) {
                Log.i(InCallController.this, "CSICSC: disconnect %s", mCurrentConnection);
                mCurrentConnection.disconnect();
                mIsConnected = false;
            } else {
                Log.i(this, "already disconnected");
            }
        }

        @Override
        public boolean isConnected() {
            return mIsConnected;
        }

        @Override
        public void setHasEmergency(boolean hasEmergency) {
            if (mDialerConnection != null) {
                mDialerConnection.setHasEmergency(hasEmergency);
            }
            if (mCarModeConnection != null) {
                mCarModeConnection.setHasEmergency(hasEmergency);
            }
        }

        @Override
        public InCallServiceInfo getInfo() {
            return mCurrentConnection.getInfo();
        }

        @Override
        public void dump(IndentingPrintWriter pw) {
            pw.print("Car Swapping ICS [");
            pw.append(mIsConnected ? "" : "not ").append("connected]\n");
            pw.increaseIndent();
            if (mDialerConnection != null) {
                pw.print("Dialer: ");
                mDialerConnection.dump(pw);
            }
            if (mCarModeConnection != null) {
                pw.print("Car Mode: ");
                mCarModeConnection.dump(pw);
            }
        }

        @Override
        public void setPendingCall(Call pendingCall) {
            mDialerConnection.setPendingCall(pendingCall);
        }

        @Override
        public Call getPendingCall() {
            return mDialerConnection.getPendingCall();
        }

        private InCallServiceConnection getCurrentConnection() {
            if (mIsCarMode && mCarModeConnection != null) {
                return mCarModeConnection;
            } else {
                return mDialerConnection;
            }
        }
    }

    private class NonUIInCallServiceConnectionCollection extends InCallServiceConnection {
        private final List<InCallServiceBindingConnection> mSubConnections;

        public NonUIInCallServiceConnectionCollection(
                List<InCallServiceBindingConnection> subConnections) {
            mSubConnections = subConnections;
        }

        @Override
        public int connect(Call call) {
            for (InCallServiceBindingConnection subConnection : mSubConnections) {
                subConnection.connect(call);
            }
            return CONNECTION_SUCCEEDED;
        }

        @Override
        public void disconnect() {
            for (InCallServiceBindingConnection subConnection : mSubConnections) {
                if (subConnection.isConnected()) {
                    subConnection.disconnect();
                }
            }
        }

        @Override
        public boolean isConnected() {
            boolean connected = false;
            for (InCallServiceBindingConnection subConnection : mSubConnections) {
                connected = connected || subConnection.isConnected();
            }
            return connected;
        }

        @Override
        public void dump(IndentingPrintWriter pw) {
            pw.println("Non-UI Connections:");
            pw.increaseIndent();
            for (InCallServiceBindingConnection subConnection : mSubConnections) {
                subConnection.dump(pw);
            }
            pw.decreaseIndent();
        }

        public void addConnections(List<InCallServiceBindingConnection> newConnections) {
            // connect() needs to be called with a Call object. Since we're in the middle of any
            // possible number of calls right now, choose an arbitrary one from the ones that
            // InCallController is tracking.
            if (mCallIdMapper.getCalls().isEmpty()) {
                Log.w(InCallController.this, "No calls tracked while adding new NonUi incall");
                return;
            }
            Call callToConnectWith = mCallIdMapper.getCalls().iterator().next();
            for (InCallServiceBindingConnection newConnection : newConnections) {
                // Ensure we track the new sub-connection so that when we later disconnect we will
                // be able to disconnect it.
                mSubConnections.add(newConnection);
                newConnection.connect(callToConnectWith);
            }
        }

        public List<InCallServiceBindingConnection> getSubConnections() {
            return mSubConnections;
        }
    }

    private final Call.Listener mCallListener = new Call.ListenerBase() {
        @Override
        public void onConnectionCapabilitiesChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onConnectionPropertiesChanged(Call call, boolean didRttChange) {
            updateCall(call, false /* includeVideoProvider */, didRttChange, null);
        }

        @Override
        public void onCannedSmsResponsesLoaded(Call call) {
            updateCall(call);
        }

        @Override
        public void onVideoCallProviderChanged(Call call) {
            updateCall(call, true /* videoProviderChanged */, false, null);
        }

        @Override
        public void onStatusHintsChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onCallerInfoChanged(Call call) {
            updateCall(call);
        }

        /**
         * Listens for changes to extras reported by a Telecom {@link Call}.
         *
         * Extras changes can originate from a {@link ConnectionService} or an {@link InCallService}
         * so we will only trigger an update of the call information if the source of the
         * extras change was a {@link ConnectionService}.
         *
         * @param call   The call.
         * @param source The source of the extras change
         *               ({@link Call#SOURCE_CONNECTION_SERVICE} or
         *               {@link Call#SOURCE_INCALL_SERVICE}).
         * @param extras The extras.
         */
        @Override
        public void onExtrasChanged(Call call, int source, Bundle extras,
                String requestingPackageName) {
            if (source == Call.SOURCE_CONNECTION_SERVICE) {
                updateCall(call);
            } else if (source == Call.SOURCE_INCALL_SERVICE && requestingPackageName != null) {
                // If the change originated from another InCallService, we'll propagate the change
                // to all other InCallServices running, EXCEPT the one who made the original change.
                updateCall(call, false /* videoProviderChanged */, false /* rttInfoChanged */,
                        requestingPackageName);
            }
        }

        /**
         * Listens for changes to extras reported by a Telecom {@link Call}.
         *
         * Extras changes can originate from a {@link ConnectionService} or an {@link InCallService}
         * so we will only trigger an update of the call information if the source of the extras
         * change was a {@link ConnectionService}.
         *  @param call The call.
         * @param source The source of the extras change ({@link Call#SOURCE_CONNECTION_SERVICE} or
         *               {@link Call#SOURCE_INCALL_SERVICE}).
         * @param keys The extra key removed
         */
        @Override
        public void onExtrasRemoved(Call call, int source, List<String> keys) {
            // Do not inform InCallServices of changes which originated there.
            if (source == Call.SOURCE_INCALL_SERVICE) {
                return;
            }
            updateCall(call);
        }

        @Override
        public void onHandleChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onCallerDisplayNameChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onCallDirectionChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onVideoStateChanged(Call call, int previousVideoState, int newVideoState) {
            updateCall(call);
        }

        @Override
        public void onTargetPhoneAccountChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onConferenceableCallsChanged(Call call) {
            updateCall(call);
        }

        @Override
        public void onConnectionEvent(Call call, String event, Bundle extras) {
            notifyConnectionEvent(call, event, extras);
        }

        @Override
        public void onHandoverFailed(Call call, int error) {
            notifyHandoverFailed(call, error);
        }

        @Override
        public void onHandoverComplete(Call call) {
            notifyHandoverComplete(call);
        }

        @Override
        public void onRttInitiationFailure(Call call, int reason) {
            notifyRttInitiationFailure(call, reason);
            updateCall(call, false, true, null);
        }

        @Override
        public void onRemoteRttRequest(Call call, int requestId) {
            notifyRemoteRttRequest(call, requestId);
        }

        @Override
        public void onCallerNumberVerificationStatusChanged(Call call,
                int callerNumberVerificationStatus) {
            updateCall(call);
        }
    };

    private UserHandle findChildManagedProfileUser(UserHandle parent, UserManager um) {
        UserHandle childManagedProfileUser = null;

        //find child managed profile user (if any)
        List<UserHandle> allUsers = um.getAllProfiles();
        for (UserHandle u : allUsers) {
            if ((um.getProfileParent(u) != null) && (um.getProfileParent(u).equals(parent))
                    && um.isManagedProfile(u.getIdentifier())) {
                //found managed profile child
                Log.i(this,
                        "Child managed profile user found: " + u.getIdentifier());
                childManagedProfileUser = u;
                break;
            }
        }
        return childManagedProfileUser;
    }
    private AtomicBoolean mPackageChangedReceiverRegistered = new AtomicBoolean(false);
    private BroadcastReceiver mPackageChangedReceiver = new BroadcastReceiver() {
        private List<InCallController.InCallServiceInfo> getNonUiInCallServiceInfoList(
                Intent intent, UserHandle userHandle) {
            String changedPackage = intent.getData().getSchemeSpecificPart();
            List<InCallController.InCallServiceInfo> inCallServiceInfoList =
                    Arrays.stream(intent.getStringArrayExtra(
                                    Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST))
                            .map((className) ->
                                    ComponentName.createRelative(changedPackage,
                                            className))
                            .filter(mKnownNonUiInCallServices::contains)
                            .flatMap(componentName -> getInCallServiceComponents(
                                    userHandle, componentName,
                                    IN_CALL_SERVICE_TYPE_NON_UI).stream())
                            .collect(Collectors.toList());
            return ((inCallServiceInfoList != null) ? inCallServiceInfoList : new ArrayList<>());
        }

        //Here we query components using the userHandle. We then also query components using the
        //parent userHandle (if any) while removing duplicates. For non-dup components found using
        //parent userHandle, we use the overloaded InCallServiceBindingConnection constructor.
        @SuppressWarnings("ReturnValueIgnored")
        private List<InCallServiceBindingConnection> getNonUiInCallServiceBindingConnectionList(
                Intent intent, @NonNull UserHandle userHandle, UserHandle parentUserHandle) {
            List<InCallServiceBindingConnection> result = new ArrayList<>();
            List<InCallController.InCallServiceInfo> serviceInfoListForParent = new ArrayList<>();

            //query and add components for the child
            List<InCallController.InCallServiceInfo> serviceInfoListForUser =
                    getNonUiInCallServiceInfoList(intent, userHandle);

            //if user has a parent, get components for parents
            if (parentUserHandle != null) {
                serviceInfoListForParent = getNonUiInCallServiceInfoList(intent, parentUserHandle);
            }

            serviceInfoListForUser
                    .stream()
                    .map(InCallServiceBindingConnection::new)
                    .collect(Collectors.toCollection(() -> result));

            serviceInfoListForParent
                    .stream()
                    .filter((e) -> !(serviceInfoListForUser.contains(e)))
                    .map((serviceinfo) -> new InCallServiceBindingConnection(serviceinfo,
                            parentUserHandle))
                    .collect(Collectors.toCollection(() -> result));

            return result;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.startSession("ICC.pCR");
            UserManager um = mContext.getSystemService(UserManager.class);
            try {
                if (Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())) {
                    synchronized (mLock) {
                        int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
                        String changedPackage = intent.getData().getSchemeSpecificPart();
                        UserHandle userHandle = UserHandle.getUserHandleForUid(uid);
                        boolean isManagedProfile = um.isManagedProfile(userHandle.getIdentifier());

                        /*
                        There are two possibilities here:
                         1) We get a work-profile/managed userHandle. In this case we need to check
                         if there are any ongoing calls for that user. If yes, then process further
                         by querying component using this user handle (also bindAsUser using this
                          handle). Else safely ignore it.
                                OR
                         2) We get the primary/non-managed userHandle. In this case, we have two
                          sub-cases to handle:
                                   a) If there are ongoing calls for this user, query components
                                   using this user and addConnections
                                   b) If there are ongoing calls for the child of this user, we
                                   also addConnections to that child (but invoke bindAsUser later
                                    with the parent handle).

                         */

                        UserHandle childManagedProfileUser = findChildManagedProfileUser(
                                userHandle, um);
                        NonUIInCallServiceConnectionCollection connections =
                                mNonUIInCallServiceConnections.get(userHandle);
                        NonUIInCallServiceConnectionCollection childConnections =
                                (childManagedProfileUser == null) ? null
                                        : mNonUIInCallServiceConnections.get(
                                                childManagedProfileUser);
                        List<InCallServiceBindingConnection> componentsToBindForUser = null;
                        List<InCallServiceBindingConnection> componentsToBindForChild = null;
                        // Separate binding for BT logic.
                        boolean isBluetoothPkg = isBluetoothPackage(changedPackage);
                        Call callToConnectWith = mCallIdMapper.getCalls().isEmpty()
                                ? null
                                : mCallIdMapper.getCalls().iterator().next();

                        // Bind to BT service if there's an available call. When the flag isn't
                        // enabled, the service will be included as part of
                        // getNonUiInCallServiceBindingConnectionList.
                        if (isBluetoothPkg && callToConnectWith != null) {
                            // mNonUIInCallServiceConnections will always contain a key for
                            // userHandle and/or the child user if there is an ongoing call with
                            // that user, regardless if there aren't any non-UI ICS bound.
                            if (connections != null) {
                                bindToBTService(callToConnectWith, userHandle);
                            }
                            if (childConnections != null) {
                                // This will try to use the ICS found in the parent if one isn't
                                // available for the child.
                                bindToBTService(callToConnectWith, childManagedProfileUser);
                            }
                        }

                        if (connections != null) {
                            componentsToBindForUser =
                                    getNonUiInCallServiceBindingConnectionList(intent,
                                            userHandle, null);
                        }

                        if (childConnections != null) {
                            componentsToBindForChild =
                                    getNonUiInCallServiceBindingConnectionList(intent,
                                            childManagedProfileUser, userHandle);
                        }

                        Log.i(this,
                                "isUserKeyPresent:%b isChildKeyPresent:%b isManagedProfile:%b "
                                        + "user:%d",
                                connections != null, childConnections != null, isManagedProfile,
                                userHandle.getIdentifier());

                        if (connections != null && !componentsToBindForUser.isEmpty()) {
                            connections.addConnections(componentsToBindForUser);
                        }

                        if (childConnections != null && !componentsToBindForChild.isEmpty()) {
                            childConnections.addConnections(componentsToBindForChild);
                        }
                        // If the current car mode app become enabled from disabled, update
                        // the connection to binding
                        updateCarModeForConnections();
                    }
                }
            } finally {
                Log.endSession();
            }
        }
    };

    private final SystemStateListener mSystemStateListener = new SystemStateListener() {
        @Override
        public void onCarModeChanged(int priority, String packageName, boolean isCarMode) {
            InCallController.this.handleCarModeChange(priority, packageName, isCarMode);
        }

        @Override
        public void onAutomotiveProjectionStateSet(String automotiveProjectionPackage) {
            InCallController.this.handleSetAutomotiveProjection(automotiveProjectionPackage);
        }

        @Override
        public void onAutomotiveProjectionStateReleased() {
            InCallController.this.handleReleaseAutomotiveProjection();
        }

        @Override
        public void onPackageUninstalled(String packageName) {
            mCarModeTracker.forceRemove(packageName);
            updateCarModeForConnections();
        }
    };

    private static final int IN_CALL_SERVICE_TYPE_INVALID = 0;
    @VisibleForTesting
    public static final int IN_CALL_SERVICE_TYPE_DEFAULT_DIALER_UI = 1;
    @VisibleForTesting
    public static final int IN_CALL_SERVICE_TYPE_SYSTEM_UI = 2;
    private static final int IN_CALL_SERVICE_TYPE_CAR_MODE_UI = 3;
    private static final int IN_CALL_SERVICE_TYPE_NON_UI = 4;
    private static final int IN_CALL_SERVICE_TYPE_COMPANION = 5;
    private static final int IN_CALL_SERVICE_TYPE_BLUETOOTH = 6;

    // Timeout value to be used to ensure future completion for mDisconnectedToneBtFutures. This is
    // set to 4 seconds to account for the exceptional case (TONE_CONGESTION).
    private static final int DISCONNECTED_TONE_TIMEOUT = 4000;

    private static final int[] LIVE_CALL_STATES = { CallState.ACTIVE, CallState.PULLING,
            CallState.DISCONNECTING };

    /** The in-call app implementations, see {@link IInCallService}. */
    private final Map<UserHandle, Map<InCallServiceInfo, IInCallService>>
            mInCallServices = new ConcurrentHashMap<>();
    private final Map<UserHandle, Pair<InCallServiceInfo, IInCallService>> mBTInCallServices =
            new ConcurrentHashMap<>();
    private final Map<UserHandle, Map<InCallServiceInfo, IInCallService>>
            mCombinedInCallServiceMap = new ConcurrentHashMap<>();

    private final CallIdMapper mCallIdMapper = new CallIdMapper(Call::getId);
    private final Collection<Call> mBtIcsCallTracker = new ArraySet<>();

    private final Context mContext;
    private Context mAllUsersContext;
    private final AppOpsManager mAppOpsManager;
    private final PermissionManager mPermissionManager;
    private final SensorPrivacyManager mSensorPrivacyManager;
    private final TelecomSystem.SyncRoot mLock;
    private final CallsManager mCallsManager;
    private final SystemStateHelper mSystemStateHelper;
    private final Timeouts.Adapter mTimeoutsAdapter;
    private final DefaultDialerCache mDefaultDialerCache;
    private final EmergencyCallHelper mEmergencyCallHelper;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<UserHandle, CarSwappingInCallServiceConnection>
            mInCallServiceConnections = new ConcurrentHashMap<>();
    private final Map<UserHandle, NonUIInCallServiceConnectionCollection>
            mNonUIInCallServiceConnections = new ConcurrentHashMap<>();
    private final Map<UserHandle, InCallServiceBindingConnection> mBTInCallServiceConnections =
            new ConcurrentHashMap<>();
    private final ClockProxy mClockProxy;
    private final String mTelecomUiPackageName;
    private final FeatureFlags mFeatureFlags;

    // A set of known non-UI in call services on the device, including those that are disabled.
    // We track this so that we can efficiently bind to them when we're notified that a new
    // component has been enabled.
    private Set<ComponentName> mKnownNonUiInCallServices = new ArraySet<>();

    // Future that's in a completed state unless we're in the middle of binding to a service.
    // The future will complete with true if binding succeeds, false if it timed out.
    private CompletableFuture<Boolean> mBindingFuture = CompletableFuture.completedFuture(true);

    // Future that's in a completed state unless we're in the middle of a binding to a bluetooth
    // in-call service.
    // The future will complete with true if bluetooth in-call service succeeds, false if it timed
    // out.
    private Map<UserHandle, CompletableFuture<Boolean>> mBtBindingFuture =
            new ConcurrentHashMap<>();
    // Future used to delay terminating the BT InCallService before the call disconnect tone
    // finishes playing.
    private Map<String, CompletableFuture<Void>> mDisconnectedToneBtFutures =
            new ConcurrentHashMap<>();

    private final CarModeTracker mCarModeTracker;

    /**
     * The package name of the app which is showing the calling UX.
     */
    private String mCurrentUserInterfacePackageName = null;

    /**
     * {@code true} if InCallController is tracking a managed, not external call which is using the
     * microphone, and is not muted {@code false} otherwise.
     */
    private boolean mIsCallUsingMicrophone = false;

    /**
     * {@code true} if InCallController is tracking a managed, not external call which is using the
     * microphone, {@code false} otherwise.
     */
    private boolean mIsTrackingManagedAliveCall = false;

    private boolean mIsStartCallDelayScheduled = false;

    private boolean mDisconnectedToneStartedPlaying = false;

    /**
     * A list of call IDs which are currently using the camera.
     */
    private ArraySet<String> mCallsUsingCamera = new ArraySet<>();

    private ArraySet<String> mAllCarrierPrivilegedApps = new ArraySet<>();
    private ArraySet<String> mActiveCarrierPrivilegedApps = new ArraySet<>();

    private java.lang.Runnable mCallRemovedRunnable;

    public InCallController(Context context, TelecomSystem.SyncRoot lock, CallsManager callsManager,
            SystemStateHelper systemStateHelper, DefaultDialerCache defaultDialerCache,
            Timeouts.Adapter timeoutsAdapter, EmergencyCallHelper emergencyCallHelper,
            CarModeTracker carModeTracker, ClockProxy clockProxy, String telecomUiPackageName,
            FeatureFlags featureFlags) {
      this(context, lock, callsManager, systemStateHelper, defaultDialerCache, timeoutsAdapter,
              emergencyCallHelper, carModeTracker, clockProxy, telecomUiPackageName, featureFlags,
              null);
    }

    @VisibleForTesting
    public InCallController(Context context, TelecomSystem.SyncRoot lock, CallsManager callsManager,
            SystemStateHelper systemStateHelper, DefaultDialerCache defaultDialerCache,
            Timeouts.Adapter timeoutsAdapter, EmergencyCallHelper emergencyCallHelper,
            CarModeTracker carModeTracker, ClockProxy clockProxy, String telecomUiPackageName,
            FeatureFlags featureFlags,
            com.android.internal.telephony.flags.FeatureFlags telephonyFeatureFlags) {
        mContext = context;
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mPermissionManager = context.getSystemService(PermissionManager.class);
        mSensorPrivacyManager = context.getSystemService(SensorPrivacyManager.class);
        mLock = lock;
        mCallsManager = callsManager;
        mSystemStateHelper = systemStateHelper;
        mTimeoutsAdapter = timeoutsAdapter;
        mDefaultDialerCache = defaultDialerCache;
        mEmergencyCallHelper = emergencyCallHelper;
        mCarModeTracker = carModeTracker;
        mSystemStateHelper.addListener(mSystemStateListener);
        mClockProxy = clockProxy;
        // Important: Context must be retained or the receivers won't fire when the context is
        // garbage collected.
        mAllUsersContext = mContext.createContextAsUser(UserHandle.ALL, 0);
        mTelecomUiPackageName = telecomUiPackageName;
        mFeatureFlags = featureFlags;
    }

    @Override
    public void onOpActiveChanged(@androidx.annotation.NonNull String op, int uid,
            @androidx.annotation.NonNull String packageName, boolean active) {
        synchronized (mLock) {
            if (!mAllCarrierPrivilegedApps.contains(packageName)) {
                return;
            }

            if (active) {
                mActiveCarrierPrivilegedApps.add(packageName);
            } else {
                mActiveCarrierPrivilegedApps.remove(packageName);
            }
            maybeTrackMicrophoneUse(isMuted());
        }
    }

    private void updateAllCarrierPrivilegedUsingMic() {
        mActiveCarrierPrivilegedApps.clear();
        UserManager userManager = mContext.getSystemService(UserManager.class);
        PackageManager pkgManager = mContext.getPackageManager();
        for (String pkg : mAllCarrierPrivilegedApps) {
            boolean isActive = mActiveCarrierPrivilegedApps.contains(pkg);
            List<UserHandle> users = userManager.getUserHandles(true);
            for (UserHandle user : users) {
                if (isActive) {
                    break;
                }

                int uid;
                try {
                    uid = UserUtil.getPackageManagerFromUserHandler(mContext,
                            user).getPackageUid(pkg, 0/*flags*/);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                List<AppOpsManager.PackageOps> pkgOps = mAppOpsManager.getOpsForPackage(
                        uid, pkg, OPSTR_RECORD_AUDIO);
                for (int j = 0; j < pkgOps.size(); j++) {
                    List<AppOpsManager.OpEntry> opEntries = pkgOps.get(j).getOps();
                    for (int k = 0; k < opEntries.size(); k++) {
                        AppOpsManager.OpEntry entry = opEntries.get(k);
                        if (entry.isRunning()) {
                            mActiveCarrierPrivilegedApps.add(pkg);
                            break;
                        }
                    }
                }
            }
        }
    }

    private void updateAllCarrierPrivileged() {
        mAllCarrierPrivilegedApps.clear();
        for (Call call : mCallIdMapper.getCalls()) {
            mAllCarrierPrivilegedApps.add(call.getConnectionManagerPhoneAccount()
                    .getComponentName().getPackageName());
        }
    }

    @Override
    public void onCallAdded(Call call) {
        UserHandle userFromCall = getUserFromCall(call);

        Log.i(this, "onCallAdded: %s", call);
        // Track the call if we don't already know about it.
        addCall(call);

        boolean bindingToBtRequired = false;
        boolean bindingToOtherServicesRequired = false;
        if (!isBoundAndConnectedToBTService(userFromCall)) {
            Log.i(this, "onCallAdded: %s; not bound or connected to BT ICS.", call);
            bindingToBtRequired = true;
            bindToBTService(call, null);
        }

        if (!isBoundAndConnectedToServices(userFromCall)) {
            Log.i(this, "onCallAdded: %s; not bound or connected to other ICS.", call);
            // We are not bound, or we're not connected.
            bindingToOtherServicesRequired = true;
            bindToServices(call);
        }
        // If either BT service are already bound or other services are already bound, attempt
        // to add the new call to the connected incall services.
        if (!bindingToBtRequired || !bindingToOtherServicesRequired) {
            addCallToConnectedServices(call, userFromCall);
        }
    }

    private void addCallToConnectedServices(Call call, UserHandle userFromCall) {
        InCallServiceConnection inCallServiceConnection =
                mInCallServiceConnections.get(userFromCall);

        // We are bound, and we are connected.
        adjustServiceBindingsForEmergency(userFromCall);

        // This is in case an emergency call is added while there is an existing call.
        mEmergencyCallHelper.maybeGrantTemporaryLocationPermission(call, userFromCall);

        if (inCallServiceConnection != null) {
            Log.i(this, "mInCallServiceConnection isConnected=%b",
                    inCallServiceConnection.isConnected());
        }

        List<ComponentName> componentsUpdated = new ArrayList<>();
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        Map<InCallServiceInfo, IInCallService> userServices =
                serviceMap.getOrDefault(userFromCall, Collections.emptyMap());
        if (!userServices.isEmpty()) {
            for (Map.Entry<InCallServiceInfo, IInCallService> entry :
                    userServices.entrySet()) {
                InCallServiceInfo info = entry.getKey();

                if (call.isExternalCall() && !info.isExternalCallsSupported()) {
                    continue;
                }

                if (call.isSelfManaged() && (!call.visibleToInCallService()
                        || !info.isSelfManagedCallsSupported())) {
                    continue;
                }

                // Only send the RTT call if it's a UI in-call service
                boolean includeRttCall = false;
                if (inCallServiceConnection != null) {
                    includeRttCall = info.equals(inCallServiceConnection.getInfo());
                }

                componentsUpdated.add(info.getComponentName());
                IInCallService inCallService = entry.getValue();

                ParcelableCall parcelableCall = ParcelableCallUtils.toParcelableCall(call,
                        true /* includeVideoProvider */,
                        mCallsManager.getPhoneAccountRegistrar(),
                        info.isExternalCallsSupported(), includeRttCall,
                        info.getType() == IN_CALL_SERVICE_TYPE_SYSTEM_UI
                                || info.getType() == IN_CALL_SERVICE_TYPE_NON_UI
                                || info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH,
                        info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH);
                try {
                    inCallService.addCall(
                            sanitizeParcelableCallForService(info, parcelableCall));
                    updateCallTracking(call, info, true /* isAdd */);
                } catch (RemoteException ignored) {
                }
            }
            Log.i(this, "Call added to ICS: %s", componentsUpdated);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        Log.i(this, "onCallRemoved: %s", call);
        // Instead of checking if there are no active calls, we should check if there any calls with
        // the same associated user returned from getUserFromCall. For instance, it's possible to
        // have calls coexist on the personal profile and work profile, in which case, we would only
        // remove the ICS connection for the user associated with the call to be disconnected.
        UserHandle userFromCall = getUserFromCall(call);
        Stream<Call> callsAssociatedWithUserFromCall = mCallsManager.getCalls().stream()
                .filter((c) -> getUserFromCall(c).equals(userFromCall));
        boolean isCallCountZero = callsAssociatedWithUserFromCall.count() == 0;
        if (isCallCountZero) {
            /* Let's add a 2 second delay before we send unbind to the services to hopefully
             * give them enough time to process all the pending messages.
             */
            if (mCallRemovedRunnable != null) {
                mHandler.removeCallbacks(mCallRemovedRunnable);
            }
            mCallRemovedRunnable = new Runnable("ICC.oCR", mLock) {
                @Override
                public void loggedRun() {
                    // Check again to make sure there are no active calls for the associated user.
                    Stream<Call> callsAssociatedWithUserFromCall = mCallsManager.getCalls().stream()
                            .filter((c) -> getUserFromCall(c).equals(userFromCall));
                    boolean isCallCountZero = callsAssociatedWithUserFromCall.count() == 0;
                    if (isCallCountZero) {
                        unbindFromServices(userFromCall);
                        mEmergencyCallHelper.maybeRevokeTemporaryLocationPermission();
                    }
                }
            }.prepare();
            mHandler.postDelayed(mCallRemovedRunnable,
                    mTimeoutsAdapter.getCallRemoveUnbindInCallServicesDelay(
                            mContext, mFeatureFlags));
        }
        call.removeListener(mCallListener);
        mCallIdMapper.removeCall(call);
        if (mCallIdMapper.getCalls().isEmpty()) {
            mActiveCarrierPrivilegedApps.clear();
            mAppOpsManager.stopWatchingActive(this);
        }
        maybeTrackMicrophoneUse(isMuted());
        onSetCamera(call, null);
    }

    @Override
    public void onDisconnectedTonePlaying(Call call, boolean isTonePlaying) {
        Log.i(this, "onDisconnectedTonePlaying: %s -> %b", call, isTonePlaying);
        synchronized (mLock) {
            if (isTonePlaying) {
                mDisconnectedToneStartedPlaying = true;
            } else if (mDisconnectedToneStartedPlaying) {
                mDisconnectedToneStartedPlaying = false;
                CompletableFuture<Void> future = mDisconnectedToneBtFutures.remove(call.getId());
                if (future != null) {
                    Log.i(this, "onDisconnectedTonePlaying: completing BT "
                            + "disconnected tone future");
                    future.complete(null);
                }
                // Schedule unbinding of BT ICS.
                maybeScheduleBtUnbind(call);
            }
        }
    }

    public void maybeScheduleBtUnbind(Call call) {
        synchronized (mLock) {
            mBtIcsCallTracker.remove(call);
            UserHandle userHandle = getUserFromCall(call);
            // If we should stay bound because of another call, don't unbind.
            if (isBtIcsAlreadyBoundForAnotherCall(userHandle)) {
                return;
            }

            final InCallServiceBindingConnection connection =
                    mBTInCallServiceConnections.get(userHandle);
            if (connection != null) {
                Log.i(this, "scheduleBtUnbind: Schedule unbind BT service");
                // Similar to in onCallRemoved when we unbind from the other ICS, we need to
                // delay unbinding from the BT ICS because we need to give the ICS a
                // moment to finish the onCallRemoved signal it got just prior.
                mHandler.postDelayed(new Runnable("ICC.sBU", mLock) {
                    @Override
                    public void loggedRun() {
                        Log.i(this, "onDisconnectedTonePlaying: unbinding from BT ICS.");
                        // Prevent unbinding in the case that this is run while another call
                        // has been placed/received. Otherwise, we will early unbind from
                        // the BT ICS and not be able to properly relay call state updates.
                        if (!isBtIcsAlreadyBoundForAnotherCall(userHandle)) {
                            // The disconnect call below will ensure we clean up the
                            // corresponding mBtInCallServices reference as well if we're
                            // connected.
                            connection.disconnect();
                            // Clean up the ICSBC reference.
                            mBTInCallServiceConnections.remove(userHandle, connection);
                        } else {
                            Log.i(this, "onDisconnectedTonePlaying: Refraining from "
                                    + "unbinding BT ICS. Another call is ongoing.");
                        }
                    }
                }.prepare(), mTimeoutsAdapter.getCallRemoveUnbindInCallServicesDelay(
                        mContext, mFeatureFlags));
            }
        }
    }

    private boolean isBtIcsAlreadyBoundForAnotherCall(UserHandle userToCheck) {
        // Track the current calls that are being tracked by the BT ICS and determine the
        // associated users of those calls as well as the users which have been used to bind to
        // the ICS.
        Set<UserHandle> usersFromOngoingCalls = new ArraySet<>();
        Set<UserHandle> usersCurrentlyBound = new ArraySet<>();
        for (Call pendingCall : mBtIcsCallTracker) {
            UserHandle userFromPendingCall = getUserFromCall(pendingCall);
            final InCallServiceBindingConnection pendingCallConnection =
                    mBTInCallServiceConnections.get(userFromPendingCall);
            usersFromOngoingCalls.add(userFromPendingCall);
            if (pendingCallConnection != null) {
                usersCurrentlyBound.add(pendingCallConnection.mUserHandleToUseForBinding);
            }
        }

        // Refrain from unbinding ICS and clearing the ICS mapping if there's an ongoing call
        // under the same associated user. Make sure we keep the internal mappings so that they
        // aren't cleared until that call is disconnected. Note here that if the associated
        // users are the same, the user used for the binding will also be the same.
        if (usersFromOngoingCalls.contains(userToCheck)) {
            Log.i(this,
                    "scheduleBtUnbind: Refraining from unbinding BT service due to an ongoing "
                            + "call detected under the same user (%s).", userToCheck);
            return true;
        }

        // The user that was used for binding may be different than the user from call
        // (associated user), which is what we use to reference the BT ICS bindings. For
        // example, consider the work profile scenario where the BT ICS is only available
        // under User 0: in this case, the user to bind to will be User 0 whereas we store
        // the references to this connection and BT ICS under the work user. This logic
        // ensures that we prevent unbinding the BT ICS if there is a personal
        // (associatedUser: 0) call + work call (associatedUser: 10) and one of them gets
        // disconnected.
        InCallServiceBindingConnection connection = mBTInCallServiceConnections.get(userToCheck);
        if (connection != null && usersCurrentlyBound
                .contains(connection.mUserHandleToUseForBinding)) {
            Log.i(this, "scheduleBtUnbind: Refraining from unbinding BT service to an "
                            + "ongoing call detected which is bound to the same user (%s).",
                    connection.mUserHandleToUseForBinding);
            return true;
        }
        return false;
    }

    /**
     * Add a call to the incall services if it is not already tracked.
     * Note: This is the same logic that used to be in onExternalCallStateChanged.
     * @param call The call
     * @param userFromCall The user applicable to the call.
     * @return A list of components updated to include the call.
     */
    private List<ComponentName> maybeAddCallToInCallServices(Call call, UserHandle userFromCall) {
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        List<ComponentName> componentsUpdated = new ArrayList<>();
        Map<InCallServiceInfo, IInCallService> userServices =
                serviceMap.getOrDefault(userFromCall, Collections.emptyMap());
        if (userServices.isEmpty()) {
            return componentsUpdated;
        }

        for (Map.Entry<InCallServiceInfo, IInCallService> entry : userServices.entrySet()) {
            InCallServiceInfo info = entry.getKey();

            if (info.isExternalCallsSupported()) {
                // For InCallServices which support external calls, the call will have already
                // been added to the connection service, so we do not need to add it again.
                continue;
            }

            if (call.isSelfManaged() && !call.visibleToInCallService()
                    && !info.isSelfManagedCallsSupported()) {
                continue;
            }

            componentsUpdated.add(info.getComponentName());
            IInCallService inCallService = entry.getValue();

            // Only send the RTT call if it's a UI in-call service
            boolean includeRttCall = info.equals(mInCallServiceConnections.
                    get(userFromCall).getInfo());

            ParcelableCall parcelableCall = ParcelableCallUtils.toParcelableCall(call,
                    true /* includeVideoProvider */, mCallsManager.getPhoneAccountRegistrar(),
                    info.isExternalCallsSupported(), includeRttCall,
                    info.getType() == IN_CALL_SERVICE_TYPE_SYSTEM_UI
                            || info.getType() == IN_CALL_SERVICE_TYPE_NON_UI
                            || info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH,
                    info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH);
            try {
                inCallService.addCall(sanitizeParcelableCallForService(info, parcelableCall));
                updateCallTracking(call, info, true /* isAdd */);
            } catch (RemoteException ignored) {
            }
        }
        return componentsUpdated;
    }

    /**
     * If a call is tracked by InCallServices, removes it from each.
     * Note: This is formerly the logic which was in the onExternalCallChanged method.
     * @param call the call to remove.
     * @param userFromCall the user associated with the call.
     * @param overrideDisconnectCause if {@code null}, the actual disconnect cause for the call is
     *                                used, if {code non-null} this disconnect cause is communicated
     *                                to the InCallServices.  The override is used in the case of
     *                                local voicemail where we want the dialer to think the call was
     *                                missed.
     * @return list of component names where the call was removed.
     */
    private List<ComponentName> maybeRemoveCallFromInCallServices(Call call,
            UserHandle userFromCall, DisconnectCause overrideDisconnectCause) {
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        List<ComponentName> componentsUpdated = new ArrayList<>();

        Map<InCallServiceInfo, IInCallService> userServices =
                serviceMap.getOrDefault(userFromCall, Collections.emptyMap());
        if (userServices.isEmpty()) {
            return componentsUpdated;
        }

        for (Map.Entry<InCallServiceInfo, IInCallService> entry :
                userServices.entrySet()) {
            InCallServiceInfo info = entry.getKey();
            if (info.isExternalCallsSupported()) {
                // For InCallServices which support external calls, we do not need to remove
                // the call.
                continue;
            }

            componentsUpdated.add(info.getComponentName());
            IInCallService inCallService = entry.getValue();

            ParcelableCall parcelableCall = ParcelableCallUtils.toParcelableCall(
                    call,
                    false /* includeVideoProvider */,
                    mCallsManager.getPhoneAccountRegistrar(),
                    false /* supportsExternalCalls */,
                    android.telecom.Call.STATE_DISCONNECTED /* overrideState */,
                    overrideDisconnectCause /* overrideDisconnectCause */,
                    false /* includeRttCall */,
                    info.getType() == IN_CALL_SERVICE_TYPE_SYSTEM_UI
                            || info.getType() == IN_CALL_SERVICE_TYPE_NON_UI
                            || info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH,
                    info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH
            );

            try {
                inCallService.updateCall(
                        copyIfLocal(sanitizeParcelableCallForService(info, parcelableCall),
                                inCallService));
            } catch (RemoteException ignored) {
            }
        }
        // Note: We do NOT call onCallRemoved; there is a possibility the user picks up a local
        // voicemail call, so remaining bound lets us avoid the overhead of rebinding.  The call
        // is InCallService#onCallRemoved at this point though.
        return componentsUpdated;
    }

    @Override
    public void onExternalCallChanged(Call call, boolean isExternalCall) {
        Log.i(this, "onExternalCallChanged: %s -> %b", call, isExternalCall);

        UserHandle userFromCall = getUserFromCall(call);

        if (!isExternalCall) {
            // The call was external but it is no longer external.  We must now add it to any
            // InCallServices which do not support external calls.
            List<ComponentName> components = maybeAddCallToInCallServices(call, userFromCall);
            Log.i(this, "Previously external call added to components: %s", components);
        } else {
            // The call was regular but it is now external.  We must now remove it from any
            // InCallServices which do not support external calls.
            // Remove the call by sending a call update indicating the call was disconnected.
            Log.i(this, "Removing external call %s", call);
            List<ComponentName> components = maybeRemoveCallFromInCallServices(call, userFromCall,
                    null /* overrideDisconnectCause */);
            Log.i(this, "External call removed from components: %s", components);
        }
        maybeTrackMicrophoneUse(isMuted());
    }

    @Override
    public void onCallStateChanged(Call call, int oldState, int newState) {
        Log.i(this, "onCallStateChanged: Call state changed for %s: %s -> %s", call.getId(),
                CallState.toString(oldState), CallState.toString(newState));
        maybeTrackMicrophoneUse(isMuted());

        // Handle transition to and from local voicemail.  If we start local voicemail for a call,
        // remove it from InCallService tracking.  If we stop local voicemail and go active again,
        // add it back tp the InCallService (ie this is the "pickup voicemail call" usecase).
        if (android.telecom.flags.Flags.localVoicemail()) {
            if (oldState == CallState.ANSWERED_FOR_LOCAL_VOICEMAIL
                    && newState == CallState.LOCAL_VOICEMAIL) {
                UserHandle userFromCall = getUserFromCall(call);
                // The call is being handled by a local VM service, so remove it from the ICS.
                List<ComponentName> removedFrom = maybeRemoveCallFromInCallServices(call,
                        userFromCall,
                        // Override the disconnect cause to missed since this is effectively
                        // going to VM; this will cause dialer to show it as missed.
                        new DisconnectCause(DisconnectCause.MISSED));
                Log.i(this, "onCallStateChanged: removing call %s answered for local voicemail: %s",
                        call.getId(), removedFrom);
                return;
            } else if (oldState == CallState.LOCAL_VOICEMAIL
                    && newState == CallState.ACTIVE) {
                Log.i(this, "onCallStateChanged: %s moved from local VM to active, adding",
                        call.getId());
                UserHandle userFromCall = getUserFromCall(call);
                // The call went active once more, so add it to the ICS.
                maybeAddCallToInCallServices(call, userFromCall);
                return;
            } else if (oldState == CallState.LOCAL_VOICEMAIL) {
                // Ignore state transitions FROM local voicemail to anything else; it is likely just
                // moving to disconnected and we don't want to send that update to the ICSes.
                Log.i(this, "onCallStateChanged: skip call %s which is in local voicemail",
                        call.getId());
                return;
            }
        }
        updateCall(call);
    }

    @Override
    public void onConnectionServiceChanged(
            Call call,
            ConnectionServiceWrapper oldService,
            ConnectionServiceWrapper newService) {
        updateCall(call);
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState oldCallAudioState,
            CallAudioState newCallAudioState) {
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();

        if (!serviceMap.isEmpty() && !shouldSkipUpdateIcs()) {
            Log.i(this, "Calling onAudioStateChanged, audioState: %s -> %s", oldCallAudioState,
                    newCallAudioState);
            maybeTrackMicrophoneUse(newCallAudioState.isMuted());
            serviceMap.values().forEach(inCallServices -> {
                for (IInCallService inCallService : inCallServices.values()) {
                    try {
                        inCallService.onCallAudioStateChanged(newCallAudioState);
                    } catch (RemoteException ignored) {
                    }
                }
            });
        }
    }

    @Override
    public void onCallEndpointChanged(CallEndpoint callEndpoint) {
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        if (!serviceMap.isEmpty() && !shouldSkipUpdateIcs()) {
            Log.i(this, "Calling onCallEndpointChanged");
            serviceMap.values().forEach(inCallServices -> {
                for (IInCallService inCallService : inCallServices.values()) {
                    try {
                        inCallService.onCallEndpointChanged(callEndpoint);
                    } catch (RemoteException ignored) {
                        Log.d(this, "Remote exception calling onCallEndpointChanged");
                    }
                }
            });
        }
    }

    @Override
    public void onAvailableCallEndpointsChanged(Set<CallEndpoint> availableCallEndpoints) {
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        if (!serviceMap.isEmpty() && !shouldSkipUpdateIcs()) {
            Log.i(this, "Calling onAvailableCallEndpointsChanged");
            List<CallEndpoint> availableEndpoints = new ArrayList<>(availableCallEndpoints);
            serviceMap.values().forEach(inCallServices -> {
                for (IInCallService inCallService : inCallServices.values()) {
                    try {
                        inCallService.onAvailableCallEndpointsChanged(availableEndpoints);
                    } catch (RemoteException ignored) {
                        Log.d(this, "Remote exception calling onAvailableCallEndpointsChanged");
                    }
                }
            });
        }
    }

    @Override
    public void onMuteStateChanged(boolean isMuted) {
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        if (!serviceMap.isEmpty()) {
            Log.i(this, "Calling onMuteStateChanged");
            serviceMap.values().forEach(inCallServices -> {
                for (IInCallService inCallService : inCallServices.values()) {
                    try {
                        inCallService.onMuteStateChanged(isMuted);
                    } catch (RemoteException ignored) {
                        Log.d(this, "Remote exception calling onMuteStateChanged");
                    }
                }
            });
        }
    }

    @Override
    public void onCanAddCallChanged(boolean canAddCall) {
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        if (!serviceMap.isEmpty()) {
            Log.i(this, "onCanAddCallChanged : %b", canAddCall);
            serviceMap.values().forEach(inCallServices -> {
                for (IInCallService inCallService : inCallServices.values()) {
                    try {
                        inCallService.onCanAddCallChanged(canAddCall);
                    } catch (RemoteException ignored) {
                    }
                }
            });
        }
    }

    void onPostDialWait(Call call, String remaining) {
        UserHandle userFromCall = getUserFromCall(call);
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        Map<InCallServiceInfo, IInCallService> userServices =
                serviceMap.getOrDefault(userFromCall, Collections.emptyMap());
        if (!userServices.isEmpty()) {
            Log.i(this, "Calling onPostDialWait, remaining = %s", remaining);
            for (IInCallService inCallService : userServices.values()) {
                try {
                    inCallService.setPostDialWait(mCallIdMapper.getCallId(call), remaining);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    @Override
    public void onIsConferencedChanged(Call call) {
        Log.d(this, "onIsConferencedChanged %s", call);
        updateCall(call);
    }

    @Override
    public void onConnectionTimeChanged(Call call) {
        Log.d(this, "onConnectionTimeChanged %s", call);
        updateCall(call);
    }

    @Override
    public void onIsVoipAudioModeChanged(Call call) {
        Log.d(this, "onIsVoipAudioModeChanged %s", call);
        updateCall(call);
        maybeTrackMicrophoneUse(isMuted());
    }

    @Override
    public void onConferenceStateChanged(Call call, boolean isConference) {
        Log.d(this, "onConferenceStateChanged %s ,isConf=%b", call, isConference);
        updateCall(call);
    }

    @Override
    public void onCdmaConferenceSwap(Call call) {
        Log.d(this, "onCdmaConferenceSwap %s", call);
        updateCall(call);
    }

    /**
     * Track changes to camera usage for a call.
     *
     * @param call     The call.
     * @param cameraId The id of the camera to use, or {@code null} if camera is off.
     */
    @Override
    public void onSetCamera(Call call, String cameraId) {
        if (call == null) {
            return;
        }

        if (cameraId != null && !mCallIdMapper.containsCall(call)) {
            //camera was set, but the call has already been removed. Do nothing
            return;
        }

        Log.i(this, "onSetCamera callId=%s, cameraId=%s", call.getId(), cameraId);
        if (cameraId != null) {
            boolean shouldStart = mCallsUsingCamera.isEmpty();
            mCallsUsingCamera.add(call.getId());

            if (shouldStart) {
                // Note, not checking return value, as this op call is merely for tracing use
                mAppOpsManager.startOp(AppOpsManager.OPSTR_PHONE_CALL_CAMERA, myUid(),
                        mContext.getOpPackageName(), null, null);
            }
        } else {
            boolean hadCall = !mCallsUsingCamera.isEmpty();
            mCallsUsingCamera.remove(call.getId());
            if (hadCall && mCallsUsingCamera.isEmpty()) {
                mAppOpsManager.finishOp(AppOpsManager.OPSTR_PHONE_CALL_CAMERA, myUid(),
                        mContext.getOpPackageName(), null);
            }
        }
    }

    public void bringToForeground(boolean showDialpad, UserHandle callingUser) {
        KeyguardManager keyguardManager = mContext.getSystemService(KeyguardManager.class);
        boolean isLockscreenRestricted = keyguardManager != null
                && keyguardManager.isKeyguardLocked();
        UserHandle currentUser = mCallsManager.getCurrentUserHandle();
        Map<InCallServiceInfo, IInCallService> userServices =
                getCombinedInCallServiceMap().getOrDefault(callingUser, Collections.emptyMap());
        // Handle cases when calls are placed from the keyguard UI screen, which operates under
        // the admin user. This needs to account for emergency calls placed from secondary/guest
        // users as well as the work profile. Once the screen is locked, the user should be able to
        // return to the call (from the keyguard UI).
        if (mCallsManager.isInEmergencyCall() && isLockscreenRestricted
                && userServices.isEmpty()) {
            // If screen is locked and the current user is the system, query calls for the work
            // profile user, if available. Otherwise, the user is in the secondary/guest profile,
            // so we can default to the system user.
            if (currentUser.isSystem()) {
                UserManager um = mContext.getSystemService(UserManager.class);
                UserHandle workProfileUser = findChildManagedProfileUser(currentUser, um);
                boolean hasWorkCalls = mCallsManager.getCalls().stream()
                        .filter((c) -> getUserFromCall(c).equals(workProfileUser)).count() > 0;
                callingUser = hasWorkCalls ? workProfileUser : currentUser;
            } else {
                callingUser = currentUser;
            }
            userServices = getCombinedInCallServiceMap().getOrDefault(callingUser,
                    Collections.emptyMap());
        }
        if (!userServices.isEmpty()) {
            for (IInCallService inCallService : userServices.values()) {
                try {
                    inCallService.bringToForeground(showDialpad);
                } catch (RemoteException ignored) {
                }
            }
        } else {
            Log.w(this, "Asking to bring unbound in-call UI to foreground.");
        }
    }

    @VisibleForTesting
    public Map<UserHandle, Map<InCallServiceInfo, IInCallService>> getInCallServices() {
        return getCombinedInCallServiceMap();
    }

    @VisibleForTesting
    public Map<UserHandle, CarSwappingInCallServiceConnection> getInCallServiceConnections() {
        return mInCallServiceConnections;
    }

    public void silenceRinger(Set<UserHandle> userHandles) {
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        userHandles.forEach(userHandle -> {
            Map<InCallServiceInfo, IInCallService> userServices =
                    serviceMap.getOrDefault(userHandle, Collections.emptyMap());
            if (!userServices.isEmpty()) {
                for (IInCallService inCallService : userServices.values()) {
                    try {
                        inCallService.silenceRinger();
                    } catch (RemoteException ignored) {
                    }
                }
            }
        });
    }

    private void notifyConnectionEvent(Call call, String event, Bundle extras) {
        UserHandle userFromCall = getUserFromCall(call);
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        // In the case that we receive a disconnect failed event, ensure that the call state is
        // reverted if it's in disconnecting and ensure we send the update to the ICS as well.
        if (Connection.EVENT_DISCONNECT_FAILED.equals(event)) {
            handleCallDisconnectFailed(call);
        }
        Map<InCallServiceInfo, IInCallService> userServices =
                serviceMap.getOrDefault(userFromCall, Collections.emptyMap());
        if (!userServices.isEmpty()) {
            for (IInCallService inCallService : userServices.values()) {
                try {
                    Log.i(this, "notifyConnectionEvent {Call: %s, Event: %s, Extras:[%s]}",
                            (call != null ? call.toString() : "null"),
                            (event != null ? event : "null"),
                            (extras != null ? extras.toString() : "null"));
                    inCallService.onConnectionEvent(mCallIdMapper.getCallId(call), event, extras);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    /**
     * When we receive a disconnect failure connection event, the old call state will be in
     * disconnecting given that the locally disconnecting state is set. Notify the ICS with the new
     * call state to ensure they get the new update if they had been notified that the call was
     * disconnecting.
     * @param call The call that received the disconnect failure event.
     */
    private void handleCallDisconnectFailed(Call call) {
        Log.i(this, "handleCallDisconnectFailed: call: %s", call);
        call.setLocallyDisconnecting(false);
        updateCall(call);
        // Show an error dialog to the user mentioning why the disconnect failed.
        UserUtil.showErrorDialogForRestrictedOutgoingCall(mContext, mTelecomUiPackageName,
                TelecomResourceId.getText(mContext, "call_hangup_fail_during_merge"),
                NOTIFICATION_TAG, "Call cannot be disconnected during a call merge.");
    }

    private void notifyRttInitiationFailure(Call call, int reason) {
        UserHandle userFromCall = getUserFromCall(call);
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        Map<InCallServiceInfo, IInCallService> userServices = serviceMap.get(userFromCall);
        if (userServices != null) {
            CarSwappingInCallServiceConnection conn = mInCallServiceConnections.get(userFromCall);
            if (conn != null) {
                userServices.entrySet().stream()
                        .filter((entry) -> entry.getKey().equals(conn.getInfo()))
                        .forEach((entry) -> {
                            try {
                                Log.i(this, "notifyRttFailure, call %s, incall %s",
                                        call, entry.getKey());
                                entry.getValue().onRttInitiationFailure(
                                        mCallIdMapper.getCallId(call), reason);
                            } catch (RemoteException ignored) {
                            }
                        });
            }
        }
    }

    private void notifyRemoteRttRequest(Call call, int requestId) {
        UserHandle userFromCall = getUserFromCall(call);
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        Map<InCallServiceInfo, IInCallService> userServices = serviceMap.get(userFromCall);
        if (userServices != null) {
            CarSwappingInCallServiceConnection conn = mInCallServiceConnections.get(userFromCall);
            if (conn != null) {
                userServices.entrySet().stream()
                        .filter((entry) -> entry.getKey().equals(conn.getInfo()))
                        .forEach((entry) -> {
                            try {
                                Log.i(this, "notifyRemoteRttRequest, call %s, incall %s",
                                        call, entry.getKey());
                                entry.getValue().onRttUpgradeRequest(
                                        mCallIdMapper.getCallId(call), requestId);
                            } catch (RemoteException ignored) {
                            }
                        });
            }
        }
    }

    private void notifyHandoverFailed(Call call, int error) {
        UserHandle userFromCall = getUserFromCall(call);
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        Map<InCallServiceInfo, IInCallService> userServices =
                serviceMap.getOrDefault(userFromCall, Collections.emptyMap());
        if (!userServices.isEmpty()) {
            for (IInCallService inCallService : userServices.values()) {
                try {
                    inCallService.onHandoverFailed(mCallIdMapper.getCallId(call), error);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    private void notifyHandoverComplete(Call call) {
        UserHandle userFromCall = getUserFromCall(call);
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        Map<InCallServiceInfo, IInCallService> userServices =
                serviceMap.getOrDefault(userFromCall, Collections.emptyMap());
        if (!userServices.isEmpty()) {
            for (IInCallService inCallService : userServices.values()) {
                try {
                    inCallService.onHandoverComplete(mCallIdMapper.getCallId(call));
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    /**
     * Unbinds an existing bound connection to the in-call app.
     */
    public void unbindFromServices(UserHandle userHandle) {
        Log.i(this, "Unbinding from services for user %s", userHandle);

        // ⚠️ Receiver is registered on the mAllUsersContext; need to unregister from that
        // Context.
        // Only unregister if there is already a receiver registered.
        if (mPackageChangedReceiverRegistered.compareAndExchange(true, false)) {
            Log.i(this, "unbindFromServices: unregister pkg changed receiver.");
            mAllUsersContext.unregisterReceiver(mPackageChangedReceiver);
        }
        CarSwappingInCallServiceConnection connection =
                mInCallServiceConnections.remove(userHandle);
        if (connection != null) {
            connection.disconnect();
        }
        NonUIInCallServiceConnectionCollection nonUIConnections =
                mNonUIInCallServiceConnections.remove(userHandle);
        if (nonUIConnections != null) {
            nonUIConnections.disconnect();
        }
        getCombinedInCallServiceMap().remove(userHandle);
        // Note that the BT ICS will be repopulated as part of the combined map if the
        // BT ICS is still bound (disconnected tone hasn't finished playing).
        updateCombinedInCallServiceMap(userHandle);
    }

    /**
     * Binds to Bluetooth InCallServices. Method-invoker must check
     * {@link #isBoundAndConnectedToBTService(UserHandle)} before invoking.
     *
     * @param call The newly added call that triggered the binding to the in-call services.
     */
    public void bindToBTService(Call call, UserHandle userHandle) {
        synchronized (mLock) {
            UserHandle userToBind = userHandle == null
                    ? getUserFromCall(call)
                    : userHandle;
            // If we're already connected, then refrain from binding again.
            if (isBoundAndConnectedToBTService(userToBind)) {
                if (call != null) {
                    call.setBtIcsFuture(mBtBindingFuture.get(userToBind));
                }
                return;
            }

            UserManager um = mContext.getSystemService(UserManager.class);
            UserHandle parentUser = um.getProfileParent(userToBind);
            Log.i(this, "bindToBtService, userToBind:%s  parent:%s", userToBind, parentUser);

            // Track the call if we don't already know about it.
            addCall(call);
            List<InCallServiceInfo> infos = getInCallServiceComponents(userToBind,
                    IN_CALL_SERVICE_TYPE_BLUETOOTH);
            boolean serviceUnavailableForUser = false;
            if (infos.size() == 0 || infos.get(0) == null) {
                Log.i(this, "No available BT ICS for user (%s). Trying with parent instead.",
                        userToBind);
                serviceUnavailableForUser = true;
                // Check if the service is available under the parent user instead.
                if (parentUser != null) {
                    infos = getInCallServiceComponents(parentUser, IN_CALL_SERVICE_TYPE_BLUETOOTH);
                }
                if (infos.size() == 0 || infos.get(0) == null) {
                    Log.w(this, "No available BT ICS to bind to for user %s or its parent %s.",
                            userToBind, parentUser);
                    mBtBindingFuture.put(userToBind, CompletableFuture.completedFuture(false));
                    if (call != null) {
                        call.setBtIcsFuture(mBtBindingFuture.get(userToBind));
                    }
                    return;
                }
            }

            mBtBindingFuture.put(userToBind, new CompletableFuture<Boolean>().completeOnTimeout(
                    false, mTimeoutsAdapter.getCallBindBluetoothInCallServicesDelay(
                            mContext, mFeatureFlags), TimeUnit.MILLISECONDS));
            if (call != null) {
                call.setBtIcsFuture(mBtBindingFuture.get(userToBind));
            }
            InCallServiceBindingConnection btIcsBindingConnection =
                    new InCallServiceBindingConnection(infos.get(0),
                            serviceUnavailableForUser ? parentUser : userToBind);
            mBTInCallServiceConnections.put(userToBind, btIcsBindingConnection);
            btIcsBindingConnection.connect(call);
        }
    }

    /**
     * Binds to all the UI-providing InCallService as well as system-implemented non-UI
     * InCallServices. Method-invoker must check {@link #isBoundAndConnectedToServices(UserHandle)}
     * before invoking.
     *
     * @param call           The newly added call that triggered the binding to the in-call
     *                      services.
     */
    @VisibleForTesting
    public void bindToServices(Call call) {
        UserHandle userFromCall = getUserFromCall(call);
        UserManager um = mContext.getSystemService(UserManager.class);
        UserHandle parentUser = um.getProfileParent(userFromCall);
        Log.i(this, "child:%s  parent:%s", userFromCall, parentUser);

        CarSwappingInCallServiceConnection inCallServiceConnection =
                mInCallServiceConnections.get(userFromCall);
        if (inCallServiceConnection == null) {
            InCallServiceConnection dialerInCall = null;
            InCallServiceInfo defaultDialerComponentInfo = getDefaultDialerComponent(userFromCall);
            Log.i(this, "defaultDialer: " + defaultDialerComponentInfo);
            if (defaultDialerComponentInfo != null &&
                    !defaultDialerComponentInfo.getComponentName().equals(
                            mDefaultDialerCache.getSystemDialerComponent())) {
                dialerInCall = new InCallServiceBindingConnection(defaultDialerComponentInfo);
            }
            Log.i(this, "defaultDialer: " + dialerInCall);

            InCallServiceInfo systemInCallInfo = getInCallServiceComponent(userFromCall,
                    mDefaultDialerCache.getSystemDialerComponent(), IN_CALL_SERVICE_TYPE_SYSTEM_UI);
            EmergencyInCallServiceConnection systemInCall =
                    new EmergencyInCallServiceConnection(systemInCallInfo, dialerInCall);
            systemInCall.setHasEmergency(mCallsManager.isInEmergencyCall());

            InCallServiceConnection carModeInCall = null;
            InCallServiceInfo carModeComponentInfo = getCurrentCarModeComponent(userFromCall);
            InCallServiceInfo carModeComponentInfoForParentUser = null;
            if(parentUser != null) {
                //query using parent user too
                carModeComponentInfoForParentUser = getCurrentCarModeComponent(
                        parentUser);
            }

            if (carModeComponentInfo != null &&
                    !carModeComponentInfo.getComponentName().equals(
                            mDefaultDialerCache.getSystemDialerComponent())) {
                carModeInCall = new InCallServiceBindingConnection(carModeComponentInfo);
            } else if (carModeComponentInfo == null &&
                    carModeComponentInfoForParentUser != null &&
                    !carModeComponentInfoForParentUser.getComponentName().equals(
                            mDefaultDialerCache.getSystemDialerComponent())) {
                carModeInCall = new InCallServiceBindingConnection(
                        carModeComponentInfoForParentUser, parentUser);
                Log.i(this, "Using car mode component queried using parent handle");
            }

            inCallServiceConnection = new CarSwappingInCallServiceConnection(systemInCall,
                    carModeInCall);
            CarSwappingInCallServiceConnection existing =
                    mInCallServiceConnections.putIfAbsent(userFromCall, inCallServiceConnection);
            if (existing != null) {
                inCallServiceConnection = existing;
            }
        }

        inCallServiceConnection.chooseInitialInCallService(shouldUseCarModeUI());

        final boolean isHeadlessDevice = TelecomResourceId.getBoolean(mContext, "headless_dialer");
        final boolean isSelfManagedCall = call != null && call.isSelfManaged();
        final boolean uiServiceConnected =
                !isHeadlessDevice
                        && inCallServiceConnection.connect(call)
                                == InCallServiceConnection.CONNECTION_SUCCEEDED;
        final boolean allowNonUiBinding =
                uiServiceConnected || isSelfManagedCall || isHeadlessDevice;

        if (allowNonUiBinding) {
            // Only connect to the non-ui InCallServices if we actually connected to the main UI
            // one, or if the call is self-managed or headless(in which case we'd still want to keep
            // Wear, BT, etc. informed.
            connectToNonUiInCallServices(call);
            mBindingFuture = new CompletableFuture<Boolean>().completeOnTimeout(false,
                    mTimeoutsAdapter.getCallRemoveUnbindInCallServicesDelay(
                            mContext, mFeatureFlags),
                    TimeUnit.MILLISECONDS);
        } else {
            Log.i(this, "bindToServices: current UI doesn't support call; not binding.");
        }

        IntentFilter packageChangedFilter = new IntentFilter(Intent.ACTION_PACKAGE_CHANGED);
        packageChangedFilter.addDataScheme("package");

        // ⚠️ Receiver is registered on the mAllUsersContext; need to unregister from that Context.
        // Only register if we're not already registered to be safe.
        if (!mPackageChangedReceiverRegistered.compareAndExchange(false, true)) {
            Log.i(this, "bindToServices: Register Package changed receiver.");
            mAllUsersContext.registerReceiver(mPackageChangedReceiver, packageChangedFilter,
                    null, null);
        }
    }

    private void updateNonUiInCallServices(Call call) {
        UserHandle userFromCall = getUserFromCall(call);

        UserManager um = mContext.getSystemService(UserManager.class);
        UserHandle parentUser = um.getProfileParent(userFromCall);

        List<InCallServiceInfo> nonUIInCallComponents =
                getInCallServiceComponents(userFromCall, IN_CALL_SERVICE_TYPE_NON_UI);
        List<InCallServiceInfo> nonUIInCallComponentsForParent = new ArrayList<>();
        if(parentUser != null) {
            //also get Non-UI services using parent handle.
            nonUIInCallComponentsForParent =
                    getInCallServiceComponents(parentUser, IN_CALL_SERVICE_TYPE_NON_UI);

        }
        List<InCallServiceBindingConnection> nonUIInCalls = new LinkedList<>();
        for (InCallServiceInfo serviceInfo : nonUIInCallComponents) {
            nonUIInCalls.add(new InCallServiceBindingConnection(serviceInfo));
        }

        //add nonUI InCall services queried using parent user (if any)
        for (InCallServiceInfo serviceInfo : nonUIInCallComponentsForParent) {
            if (nonUIInCallComponents.contains(serviceInfo)) {
                //skip dups
                Log.i(this, "skipped duplicate component found using parent user: "
                        + serviceInfo.getComponentName());
            } else {
                nonUIInCalls.add(new InCallServiceBindingConnection(serviceInfo, parentUser));
                Log.i(this,
                        "added component queried using parent user: "
                                + serviceInfo.getComponentName());
            }
        }

        List<String> callCompanionApps = mCallsManager
                .getRoleManagerAdapter().getCallCompanionApps();
        if (callCompanionApps != null && !callCompanionApps.isEmpty()) {
            for (String pkg : callCompanionApps) {
                InCallServiceInfo info = getInCallServiceComponent(userFromCall, pkg,
                        IN_CALL_SERVICE_TYPE_COMPANION, true /* ignoreDisabled */);
                if (info != null) {
                    nonUIInCalls.add(new InCallServiceBindingConnection(info));
                }
            }
        }
        mNonUIInCallServiceConnections.put(userFromCall, new NonUIInCallServiceConnectionCollection(
                nonUIInCalls));
    }

    private void connectToNonUiInCallServices(Call call) {
        UserHandle userFromCall = getUserFromCall(call);
        NonUIInCallServiceConnectionCollection connections =
                mNonUIInCallServiceConnections.get(userFromCall);
        if (connections == null) {
            updateNonUiInCallServices(call);
            connections = mNonUIInCallServiceConnections.get(userFromCall);
        }
        if (connections != null) {
            connections.connect(call);
        }
    }

    private @Nullable InCallServiceInfo getDefaultDialerComponent(UserHandle userHandle) {
        String defaultPhoneAppName = mDefaultDialerCache.getDefaultDialerApplication(userHandle);
        String systemPhoneAppName = mDefaultDialerCache.getSystemDialerApplication();

        Log.d(this, "getDefaultDialerComponent: defaultPhoneAppName=[%s]", defaultPhoneAppName);
        Log.d(this, "getDefaultDialerComponent: systemPhoneAppName=[%s]", systemPhoneAppName);

        // Get the defaultPhoneApp InCallService component...
        InCallServiceInfo defaultPhoneAppComponent =
                (systemPhoneAppName != null && systemPhoneAppName.equals(defaultPhoneAppName)) ?
                        /* The defaultPhoneApp is also the systemPhoneApp. Get systemPhoneApp info*/
                        getInCallServiceComponent(userHandle, defaultPhoneAppName,
                                IN_CALL_SERVICE_TYPE_SYSTEM_UI, true /* ignoreDisabled */)
                        /* The defaultPhoneApp is NOT the systemPhoneApp. Get defaultPhoneApp info*/
                        : getInCallServiceComponent(userHandle, defaultPhoneAppName,
                                IN_CALL_SERVICE_TYPE_DEFAULT_DIALER_UI, true /* ignoreDisabled */);

        Log.d(this, "getDefaultDialerComponent: defaultPhoneAppComponent=[%s]",
                defaultPhoneAppComponent);

        // defaultPhoneAppComponent is null in the case when the defaultPhoneApp does not implement
        // the InCallService && is the package is different from the systemPhoneApp

        return defaultPhoneAppComponent;
    }

    private InCallServiceInfo getCurrentCarModeComponent(UserHandle userHandle) {
        return getInCallServiceComponent(userHandle,
                mCarModeTracker.getCurrentCarModePackage(),
                IN_CALL_SERVICE_TYPE_CAR_MODE_UI, true /* ignoreDisabled */);
    }

    private InCallServiceInfo getInCallServiceComponent(UserHandle userHandle,
            ComponentName componentName, int type) {
        List<InCallServiceInfo> list = getInCallServiceComponents(userHandle,
                componentName, type);
        if (list != null && !list.isEmpty()) {
            return list.get(0);
        } else {
            return handleInCallServiceNotFound(componentName, type);
        }
    }

    @VisibleForTesting
    public InCallServiceInfo handleInCallServiceNotFound(ComponentName componentName, int type) {
        // Last Resort: Try to bind to the ComponentName given directly.
        Log.e(this, new Exception(), "Package Manager could not find ComponentName: "
                + componentName + ". Trying to bind anyway.");
        return new InCallServiceInfo(componentName, false, false, type, false);
    }

    private InCallServiceInfo getInCallServiceComponent(UserHandle userHandle,
            String packageName, int type, boolean ignoreDisabled) {
        List<InCallServiceInfo> list = getInCallServiceComponents(userHandle,
                packageName, type, ignoreDisabled);
        if (list != null && !list.isEmpty()) {
            return list.get(0);
        }
        return null;
    }

    private List<InCallServiceInfo> getInCallServiceComponents(
            UserHandle userHandle, int type) {
        return getInCallServiceComponents(userHandle, null, null, type);
    }

    private List<InCallServiceInfo> getInCallServiceComponents(UserHandle userHandle,
            String packageName, int type, boolean ignoreDisabled) {
        return getInCallServiceComponents(userHandle, packageName, null,
                type, ignoreDisabled);
    }

    private List<InCallServiceInfo> getInCallServiceComponents(UserHandle userHandle,
            ComponentName componentName, int type) {
        return getInCallServiceComponents(userHandle, null, componentName, type);
    }

    private List<InCallServiceInfo> getInCallServiceComponents(UserHandle userHandle,
            String packageName, ComponentName componentName, int requestedType) {
        return getInCallServiceComponents(userHandle, packageName,
                componentName, requestedType, true /* ignoreDisabled */);
    }
    private boolean canInteractAcrossUsersOrProfiles(ServiceInfo serviceInfo,
            PackageManager packageManager) {
        String op = AppOpsManager.permissionToOp("android.permission.INTERACT_ACROSS_PROFILES");
        String[] uidPackages = packageManager.getPackagesForUid(serviceInfo.applicationInfo.uid);

        boolean hasInteractAcrossProfiles = Arrays.stream(uidPackages).anyMatch(
                p -> ((packageManager.checkPermission(
                        Manifest.permission.INTERACT_ACROSS_PROFILES,
                        p) == PackageManager.PERMISSION_GRANTED)
                ));
        boolean hasInteractAcrossUsers = Arrays.stream(uidPackages).anyMatch(
                p -> ((packageManager.checkPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS,
                        p) == PackageManager.PERMISSION_GRANTED)
                ));
        boolean hasInteractAcrossProfilesAppOp = Arrays.stream(uidPackages).anyMatch(
                p -> (AppOpsManager.MODE_ALLOWED == mAppOpsManager.checkOpNoThrow(
                        op, serviceInfo.applicationInfo.uid, p))
        );
        Log.i(this,
                "packageName:%s INTERACT_ACROSS_USERS:%b INTERACT_ACROSS_PROFILES:%b "
                        + "INTERACT_ACROSS_PROFILES_APPOP:%b",
                uidPackages[0], hasInteractAcrossUsers, hasInteractAcrossProfiles,
                hasInteractAcrossProfilesAppOp);

        return (hasInteractAcrossUsers || hasInteractAcrossProfiles
                || hasInteractAcrossProfilesAppOp);
    }

    /**
     * Verifies that the class for a given ServiceInfo exists within its package.
     * This prevents a system crash if a service is declared in the manifest but its
     * class was not included in the compiled code.
     * @param serviceInfo The ServiceInfo of the service to check.
     * @param userHandle The user under which to check for the service.
     * @return {@code true} if the class exists, {@code false} otherwise.
     */
    private boolean serviceClassExists(ServiceInfo serviceInfo, UserHandle userHandle) {
        Log.i(this, "serviceClassExists check");
        try {
            Context packageContext = mContext.createPackageContextAsUser(
                    serviceInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, userHandle);
            ClassLoader classLoader = packageContext.getClassLoader();
            Class.forName(serviceInfo.name, false, classLoader);
            return true;
        } catch (NameNotFoundException | ClassNotFoundException e) {
            Log.w(this, "Skipping InCallService: class not found for " + serviceInfo.name);
            return false;
        } catch (Exception e) {
            Log.e(this, e, "Error checking for existence of " + serviceInfo.name);
            return false;
        }
    }

    private List<InCallServiceInfo> getInCallServiceComponents(UserHandle userHandle,
            String packageName, ComponentName componentName,
            int requestedType, boolean ignoreDisabled) {
        List<InCallServiceInfo> retval = new LinkedList<>();

        Intent serviceIntent = new Intent(InCallService.SERVICE_INTERFACE);
        if (packageName != null) {
            serviceIntent.setPackage(packageName);
        }
        if (componentName != null) {
            serviceIntent.setComponent(componentName);
        }
        Log.i(this,
                "getComponents, pkgname: " + packageName + " comp: " + componentName + " userid: "
                        + userHandle.getIdentifier() + " requestedType: " + requestedType);
        PackageManager packageManager = mContext.getPackageManager();
        Context userContext = mContext.createContextAsUser(userHandle,
                0 /* flags */);
        PackageManager userPackageManager = userContext != null ?
                userContext.getPackageManager() : packageManager;

        List<ResolveInfo> entries;
        entries = userPackageManager.queryIntentServices(
                serviceIntent,
                PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS);
        for (ResolveInfo entry : entries) {
            ServiceInfo serviceInfo = entry.serviceInfo;

            if (serviceInfo != null) {
                boolean isMetaFlag = serviceInfo.metaData != null &&
                        serviceInfo.metaData.getBoolean(
                                "android.telecom.CLASS_EXISTENCE_CHECK", false);
                if (isMetaFlag && !serviceClassExists(serviceInfo, userHandle)) {
                    continue;
                }
                boolean isExternalCallsSupported = serviceInfo.metaData != null &&
                        serviceInfo.metaData.getBoolean(
                                TelecomManager.METADATA_INCLUDE_EXTERNAL_CALLS, false);
                boolean isSelfManageCallsSupported = serviceInfo.metaData != null &&
                        serviceInfo.metaData.getBoolean(
                                TelecomManager.METADATA_INCLUDE_SELF_MANAGED_CALLS, false);

                int currentType = getInCallServiceType(userHandle,
                        entry.serviceInfo, userPackageManager, packageName);

                boolean hasInteractAcrossUserOrProfilePerm = canInteractAcrossUsersOrProfiles(
                        entry.serviceInfo, userPackageManager);

                ComponentName foundComponentName =
                        new ComponentName(serviceInfo.packageName, serviceInfo.name);
                if (currentType == IN_CALL_SERVICE_TYPE_NON_UI) {
                    mKnownNonUiInCallServices.add(foundComponentName);
                }

                boolean isEnabled = isServiceEnabled(foundComponentName,
                        serviceInfo, userPackageManager);
                boolean isRequestedType;
                if (requestedType == IN_CALL_SERVICE_TYPE_INVALID) {
                    isRequestedType = true;
                } else {
                    isRequestedType = requestedType == currentType;
                }

                Log.i(this,
                        "found:%s isRequestedtype:%b isEnabled:%b ignoreDisabled:%b "
                                + "hasCrossProfilePerm:%b",
                        foundComponentName, isRequestedType, isEnabled, ignoreDisabled,
                        hasInteractAcrossUserOrProfilePerm);

                if ((!ignoreDisabled || isEnabled) && isRequestedType) {
                    retval.add(new InCallServiceInfo(foundComponentName, isExternalCallsSupported,
                            isSelfManageCallsSupported, requestedType,
                            hasInteractAcrossUserOrProfilePerm));
                }
            }
        }
        return retval;
    }

    private boolean isServiceEnabled(ComponentName componentName,
            ServiceInfo serviceInfo, PackageManager packageManager) {
        if (packageManager == null) {
            return serviceInfo.isEnabled();
        }

        int componentEnabledState = packageManager.getComponentEnabledSetting(componentName);

        if (componentEnabledState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return true;
        }

        if (componentEnabledState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            return serviceInfo.isEnabled();
        }

        return false;
    }

    private boolean shouldUseCarModeUI() {
        return mCarModeTracker.isInCarMode();
    }

    /**
     * Returns the type of InCallService described by the specified serviceInfo.
     */
    private int getInCallServiceType(UserHandle userHandle, ServiceInfo serviceInfo,
            PackageManager packageManager, String packageName) {
        // Verify that the InCallService requires the BIND_INCALL_SERVICE permission which
        // enforces that only Telecom can bind to it.
        boolean hasServiceBindPermission = serviceInfo.permission != null &&
                serviceInfo.permission.equals(
                        Manifest.permission.BIND_INCALL_SERVICE);
        if (!hasServiceBindPermission) {
            Log.w(this, "InCallService does not require BIND_INCALL_SERVICE permission: " +
                    serviceInfo.packageName);
            return IN_CALL_SERVICE_TYPE_INVALID;
        }

        if (mDefaultDialerCache.getSystemDialerApplication().equals(serviceInfo.packageName) &&
                mDefaultDialerCache.getSystemDialerComponent().getClassName()
                        .equals(serviceInfo.name)) {
            return IN_CALL_SERVICE_TYPE_SYSTEM_UI;
        }

        // Check to see if the service holds permissions or metadata for third party apps.
        boolean isUIService = serviceInfo.metaData != null &&
                serviceInfo.metaData.getBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI);

        // Check to see if the service is a car-mode UI type by checking that it has the
        // CONTROL_INCALL_EXPERIENCE (to verify it is a system app) and that it has the
        // car-mode UI metadata.
        // We check the permission grant on all of the packages contained in the InCallService's
        // same UID to see if any of them have been granted the permission.  This accomodates the
        // CTS tests, which have some shared UID stuff going on in order to work.  It also still
        // obeys the permission model since a single APK typically normally only has a single UID.
        String[] uidPackages = packageManager.getPackagesForUid(serviceInfo.applicationInfo.uid);
        boolean hasControlInCallPermission = Arrays.stream(uidPackages).anyMatch(
                p -> packageManager.checkPermission(
                        Manifest.permission.CONTROL_INCALL_EXPERIENCE,
                        p) == PackageManager.PERMISSION_GRANTED);

        boolean hasAppOpsPermittedManageOngoingCalls = false;
        if (isAppOpsPermittedManageOngoingCalls(serviceInfo.applicationInfo.uid,
                serviceInfo.packageName)) {
            hasAppOpsPermittedManageOngoingCalls = true;
        }

        boolean isCarModeUIService = serviceInfo.metaData != null &&
                serviceInfo.metaData.getBoolean(
                        TelecomManager.METADATA_IN_CALL_SERVICE_CAR_MODE_UI, false);

        if (isCarModeUIService && hasControlInCallPermission) {
            return IN_CALL_SERVICE_TYPE_CAR_MODE_UI;
        }

        // Check to see that it is the default dialer package
        String defaultDialer = mDefaultDialerCache.getDefaultDialerApplication(userHandle);
        boolean isDefaultDialerPackage = Objects.equals(serviceInfo.packageName, defaultDialer);
        if (isDefaultDialerPackage && isUIService) {
            return IN_CALL_SERVICE_TYPE_DEFAULT_DIALER_UI;
        }

        boolean processingBluetoothPackage = isBluetoothPackage(serviceInfo.packageName);
        if (processingBluetoothPackage && (hasControlInCallPermission
                || hasAppOpsPermittedManageOngoingCalls)) {
            return IN_CALL_SERVICE_TYPE_BLUETOOTH;
        }

        // Also allow any in-call service that has the control-experience permission (to ensure
        // that it is a system app) and doesn't claim to show any UI.
        if (!isUIService && !isCarModeUIService && (hasControlInCallPermission ||
                hasAppOpsPermittedManageOngoingCalls)) {
            return IN_CALL_SERVICE_TYPE_NON_UI;
        }

        // Anything else that remains, we will not bind to.
        Log.i(this, "Skipping binding to %s:%s, control: %b, car-mode: %b, ui: %b",
                serviceInfo.packageName, serviceInfo.name, hasControlInCallPermission,
                isCarModeUIService, isUIService);
        return IN_CALL_SERVICE_TYPE_INVALID;
    }

    private void adjustServiceBindingsForEmergency(UserHandle userHandle) {
        // The connected UI is not the system UI, so lets check if we should switch them
        // if there exists an emergency number.
        if (mCallsManager.isInEmergencyCall()) {
            mInCallServiceConnections.get(userHandle).setHasEmergency(true);
        }
    }

    /**
     * Persists the {@link IInCallService} instance and starts the communication between
     * this class and in-call app by sending the first update to in-call app. This method is
     * called after a successful binding connection is established.
     *
     * @param info Info about the service, including its {@link ComponentName}.
     * @param service The {@link IInCallService} implementation.
     * @return True if we successfully connected.
     */
    private boolean onConnected(InCallServiceInfo info, IBinder service, UserHandle userHandle) {
        Log.i(this, "onConnected to %s", info.getComponentName());

        if (info.getType() == IN_CALL_SERVICE_TYPE_CAR_MODE_UI
                || info.getType() == IN_CALL_SERVICE_TYPE_SYSTEM_UI
                || info.getType() == IN_CALL_SERVICE_TYPE_DEFAULT_DIALER_UI) {
            trackCallingUserInterfaceStarted(info);
        }
        IInCallService inCallService = IInCallService.Stub.asInterface(service);
        synchronized (mLock) {
            if (info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH) {
                // Binding completed after the timeout but let this through instead of
                // disconnecting the BT ICS.
                CompletableFuture<Boolean> future = mBtBindingFuture.get(userHandle);
                if (future == null || (future.isDone() && !future.getNow(false))) {
                    Log.i(this, "onConnected: BT binding future timed out but allowing bind "
                            + "to complete.");
                } else {
                    future.complete(true);
                }
                mBTInCallServices.put(userHandle, new Pair<>(info, inCallService));
            } else {
                mInCallServices.putIfAbsent(userHandle, new ConcurrentHashMap<>());
                mInCallServices.get(userHandle).put(info, inCallService);
            }
        }

        updateCombinedInCallServiceMap(userHandle);

        try {
            inCallService.setInCallAdapter(
                    new InCallAdapter(
                            mCallsManager,
                            mCallIdMapper,
                            mLock,
                            info.getComponentName().getPackageName()));
        } catch (RemoteException e) {
            Log.e(this, e, "Failed to set the in-call adapter.");
            mAnomalyReporter.reportAnomaly(SET_IN_CALL_ADAPTER_ERROR_UUID,
                    SET_IN_CALL_ADAPTER_ERROR_MSG);
            return false;
        }

        // Upon successful connection, send the state of the world to the service.
        List<Call> calls = orderCallsWithChildrenFirst(mCallsManager.getCalls().stream().filter(
                call -> getUserFromCall(call).equals(userHandle))
                .collect(Collectors.toUnmodifiableList()));
        if (calls.isEmpty() &&
            (info.mType == IN_CALL_SERVICE_TYPE_DEFAULT_DIALER_UI ||
            info.mType == IN_CALL_SERVICE_TYPE_SYSTEM_UI)) {
            // If the call list is empty
            // and yet onServiceConnected callback
            // is received, this implies that the relevant ICS has not received the call updates
            // Hence we are adding the cached call, to be sent to ICS.
            if (mInCallServiceConnections.containsKey(userHandle)) {
                Call pendingCall = mInCallServiceConnections.get(userHandle).getPendingCall();
                if (pendingCall != null) {
                    calls.add(pendingCall);
                }
            }

        }
        Log.i(this, "Adding %s calls to InCallService after onConnected: %s, including external " +
                "calls", calls.size(), info.getComponentName());
        int numCallsSent = 0;
        for (Call call : calls) {
            numCallsSent += sendCallToService(call, info, inCallService);
        }
        try {
            inCallService.onCallAudioStateChanged(mCallsManager.getAudioState());
            inCallService.onCanAddCallChanged(mCallsManager.canAddCall());
            if (mFeatureFlags.notifyAvailableEndpointsOnIcsConnected()) {
                inCallService.onAvailableCallEndpointsChanged(new ArrayList<>(
                        mCallsManager.getCallEndpointController().getAvailableEndpoints()));
            }
            inCallService.onCallEndpointChanged(mCallsManager.getCallEndpointController()
                    .getCurrentCallEndpoint());
        } catch (RemoteException ignored) {
        }
        // Don't complete the binding future for non-ui incalls
        if (info.getType() != IN_CALL_SERVICE_TYPE_NON_UI && !mBindingFuture.isDone()) {
            mBindingFuture.complete(true);
        }

        Log.i(this, "%s calls sent to InCallService.", numCallsSent);
        return true;
    }

    @VisibleForTesting
    public int sendCallToService(Call call, InCallServiceInfo info,
            IInCallService inCallService) {
        try {
            if ((call.isSelfManaged() && (!info.isSelfManagedCallsSupported()
                    || !call.visibleToInCallService())) ||
                    (call.isExternalCall() && !info.isExternalCallsSupported())) {
                return 0;
            }

            UserHandle userFromCall = getUserFromCall(call);
            // Only send the RTT call if it's a UI in-call service
            boolean includeRttCall = false;
            CarSwappingInCallServiceConnection connection =
                    mInCallServiceConnections.get(userFromCall);
            if (connection != null) {
                if (connection != null && connection.getInfo() != null) {
                    includeRttCall = info.equals(connection.getInfo())
                            && call.areRttStreamsInitialized();
                }
            }

            // Track the call if we don't already know about it.
            if (call.getState() != android.telecom.Call.STATE_DISCONNECTED) {
                // In the case of late binding for dialer ICS, when we call SendTOICS with
                // a call in disconnected state, we should not add the call back in CallIdMapper
                // to avoid a call already removed.
                addCall(call);
            }
            ParcelableCall parcelableCall = ParcelableCallUtils.toParcelableCall(
                    call,
                    true /* includeVideoProvider */,
                    mCallsManager.getPhoneAccountRegistrar(),
                    info.isExternalCallsSupported(),
                    includeRttCall,
                    info.getType() == IN_CALL_SERVICE_TYPE_SYSTEM_UI
                            || info.getType() == IN_CALL_SERVICE_TYPE_NON_UI
                            || info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH,
                    info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH);
            if (mFeatureFlags.doNotSendCallToNullIcs()) {
                if (inCallService != null) {
                    inCallService.addCall(sanitizeParcelableCallForService(info, parcelableCall));
                } else {
                    Log.w(this, "call=[%s], was not sent to InCallService"
                                    + " with info=[%s] due to a null InCallService binding",
                            call, info);
                    mAnomalyReporter.reportAnomaly(NULL_IN_CALL_SERVICE_BINDING_UUID,
                            NULL_IN_CALL_SERVICE_BINDING_ERROR_MSG);
                    return 0;
                }
            } else {
                inCallService.addCall(sanitizeParcelableCallForService(info, parcelableCall));
            }
            if (info.mType == IN_CALL_SERVICE_TYPE_DEFAULT_DIALER_UI ||
                info.mType == IN_CALL_SERVICE_TYPE_SYSTEM_UI) {
                // Reset cached call once we are done sending to dialer ICS
                if (mInCallServiceConnections.containsKey(userFromCall)) {
                    InCallServiceConnection icsConnection = mInCallServiceConnections.get(
                        userFromCall);
                    icsConnection.setPendingCall(null);
                }
            }
            updateCallTracking(call, info, true /* isAdd */);
            return 1;
        } catch (RemoteException ignored) {
        }
        return 0;
    }

    /**
     * Cleans up an instance of in-call app after the service has been unbound.
     *
     * @param disconnectedInfo The {@link InCallServiceInfo} of the service which disconnected.
     */
    private void onDisconnected(InCallServiceInfo disconnectedInfo, UserHandle userHandle) {
        synchronized (mLock) {
            Log.i(this, "onDisconnected from %s", disconnectedInfo.getComponentName());
            if (disconnectedInfo.getType() == IN_CALL_SERVICE_TYPE_CAR_MODE_UI
                    || disconnectedInfo.getType() == IN_CALL_SERVICE_TYPE_SYSTEM_UI
                    || disconnectedInfo.getType() == IN_CALL_SERVICE_TYPE_DEFAULT_DIALER_UI) {
                trackCallingUserInterfaceStopped(disconnectedInfo);
            }
            boolean removed = false;
            Map<InCallServiceInfo, IInCallService> userServices =
                    mInCallServices.get(userHandle);
            if (userServices != null) {
                removed = userServices.remove(disconnectedInfo) != null;
            }
            if (disconnectedInfo.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH) {
                mBTInCallServices.remove(userHandle);
                removed = true;
            }
            if (removed) {
                updateCombinedInCallServiceMap(userHandle);
            }
        }
    }

    public void onCallEndpointRequested(String requestingPackageName, CallEndpoint callEndpoint,
            Call call) {
        UserHandle userFromCall = getUserFromCall(call);
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        Map<InCallServiceInfo, IInCallService> userServices =
                serviceMap.getOrDefault(userFromCall, Collections.emptyMap());
        if (!userServices.isEmpty()) {
            for (Map.Entry<InCallServiceInfo, IInCallService> entry : userServices.entrySet()) {
                InCallServiceInfo info = entry.getKey();
                ComponentName componentName = info.getComponentName();

                // If specified, skip ICS if it matches the package name.  Used for cases where on
                // ICS requests the audio route and we want to skip notifying the same ICS that
                // requested the audio route.
                if (requestingPackageName != null
                        && componentName.getPackageName().equals(requestingPackageName)) {
                    Log.i(this, "skipping requestingPackageName: %s",
                            requestingPackageName);
                    continue;
                }

                IInCallService inCallService = entry.getValue();
                onCallEndpointRequestedToIcs(inCallService, callEndpoint, componentName);
            }
        } else {
            Log.i(this, "Unable to propagate onCallEndpointRequested. "
                    + "InCallService not found for user: %s", userFromCall);
        }
    }

    private void onCallEndpointRequestedToIcs(IInCallService inCallService,
            CallEndpoint callEndpoint, ComponentName componentName) {
        try {
            inCallService.onCallEndpointRequested(callEndpoint);
        } catch (RemoteException exception) {
            Log.w(this, "Call status update did not send to: "
                    + componentName + " successfully with error " + exception);
        }
    }

    /**
     * Informs all {@link InCallService} instances of the updated call information.
     *
     * @param call The {@link Call}.
     */
    private void updateCall(Call call) {
        // If a bulk state update is in progress,
        // defer sending the call update to the InCallUi.
        if (mFeatureFlags.bulkStateUpdateCall() &&
                call.isBulkStateUpdateInProgress()) {
            Log.d(this, "Call is still setting its initial state. " +
                    "Do not send update to InCallUi yet - " + call.getId());
            return;
        }
        updateCall(call, false /* videoProviderChanged */, false, null);
    }

    /**
     * Informs all {@link InCallService} instances of the updated call information.
     *
     * @param call                 The {@link Call}.
     * @param videoProviderChanged {@code true} if the video provider changed, {@code false}
     *                             otherwise.
     * @param rttInfoChanged       {@code true} if any information about the RTT session changed,
     *                             {@code false} otherwise.
     * @param exceptPackageName    When specified, this package name will not get a call update.
     *                             Used ONLY from {@link Call#putConnectionServiceExtras(Bundle)} to
     *                             ensure we can propagate extras changes between InCallServices but
     *                             not inform the requestor of their own change.
     */
    private void updateCall(Call call, boolean videoProviderChanged, boolean rttInfoChanged,
            String exceptPackageName) {
        if (call.getState() == CallState.LOCAL_VOICEMAIL) {
            // Skipping local VM call
            Log.i(this, "updateCall: skip call=%s as it is local VM", call.getId());
            return;
        }
        UserHandle userFromCall = getUserFromCall(call);
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        Map<InCallServiceInfo, IInCallService> userServices =
                serviceMap.getOrDefault(userFromCall, Collections.emptyMap());
        if (!userServices.isEmpty()) {
            Log.i(this, "Sending updateCall %s", call);
            List<ComponentName> componentsUpdated = new ArrayList<>();
            for (Map.Entry<InCallServiceInfo, IInCallService> entry : userServices.entrySet()) {
                InCallServiceInfo info = entry.getKey();
                ComponentName componentName = info.getComponentName();

                // If specified, skip ICS if it matches the package name.  Used for cases where on
                // ICS makes an update to extras and we want to skip updating the same ICS with the
                // change that it implemented.
                if (exceptPackageName != null
                        && componentName.getPackageName().equals(exceptPackageName)) {
                    continue;
                }

                if (call.isExternalCall() && !info.isExternalCallsSupported()) {
                    continue;
                }

                if (call.isSelfManaged() && (!call.visibleToInCallService()
                        || !info.isSelfManagedCallsSupported())) {
                    continue;
                }

                InCallServiceConnection conn = mInCallServiceConnections.get(userFromCall);
                boolean isRttService = conn != null && info.equals(conn.getInfo());
                ParcelableCall parcelableCall = ParcelableCallUtils.toParcelableCall(
                        call,
                        videoProviderChanged /* includeVideoProvider */,
                        mCallsManager.getPhoneAccountRegistrar(),
                        info.isExternalCallsSupported(),
                        rttInfoChanged && isRttService,
                        info.getType() == IN_CALL_SERVICE_TYPE_SYSTEM_UI
                                || info.getType() == IN_CALL_SERVICE_TYPE_NON_UI
                                || info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH,
                        info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH);
                IInCallService inCallService = entry.getValue();
                boolean isDisconnectingBtIcs = info.getType() == IN_CALL_SERVICE_TYPE_BLUETOOTH
                        && call.getState() == CallState.DISCONNECTED;

                if (isDisconnectingBtIcs) {
                    // If this is the first we heard about the disconnect for the BT ICS, then we
                    // will setup a future to notify the disconnet later.
                    CompletableFuture<Void> disconnectedToneFuture =
                            new CompletableFuture<Void>()
                                    .completeOnTimeout(null, DISCONNECTED_TONE_TIMEOUT,
                                            TimeUnit.MILLISECONDS);
                    // Note: DO NOT chain async work onto this future; using thenRun ensures
                    // when disconnectedToneFuture is completed that the chained work is run
                    // synchronously.
                    disconnectedToneFuture.thenRun(() -> {
                        Log.i(this,
                                "updateCall: (deferred) Sending call disconnected update "
                                        + "to BT ICS.");
                        updateCallToIcs(inCallService, info, parcelableCall, componentName);
                        mDisconnectedToneBtFutures.remove(call.getId());
                    });
                    if (mDisconnectedToneBtFutures.putIfAbsent(call.getId(),
                            disconnectedToneFuture) != null) {
                        // If we have already cached a disconnect signal for the BT ICS, don't sent
                        // any other updates (ie due to extras or whatnot) to the BT ICS.  If we do
                        // then it will hear about the disconnect in advance and not play the call
                        // end tone.
                        Log.i(this, "updateCall: skip update for disconnected call to BT ICS");
                    }
                } else {
                    componentsUpdated.add(componentName);
                    updateCallToIcs(inCallService, info, parcelableCall, componentName);
                }
            }
            Log.i(this, "Components updated: %s", componentsUpdated);
        } else {
            Log.i(this,
                    "Unable to update call. InCallService not found for user: %s", userFromCall);
        }
    }

    private void updateCallToIcs(IInCallService inCallService, InCallServiceInfo info,
            ParcelableCall parcelableCall, ComponentName componentName) {
        try {
            inCallService.updateCall(
                    copyIfLocal(sanitizeParcelableCallForService(info, parcelableCall),
                            inCallService));
        } catch (RemoteException exception) {
            Log.w(this, "Call status update did not send to: "
                    + componentName + " successfully with error " + exception);
        }
    }

    /**
     * Adds the call to the list of calls tracked by the {@link InCallController}.
     * @param call The call to add.
     */
    @VisibleForTesting
    public void addCall(Call call) {
        if (call == null) {
            return;
        }

        if (mCallIdMapper.getCalls().size() == 0) {
            mAppOpsManager.startWatchingActive(new String[] { OPSTR_RECORD_AUDIO },
                    java.lang.Runnable::run, this);
            updateAllCarrierPrivileged();
            updateAllCarrierPrivilegedUsingMic();
        }

        if (mCallIdMapper.getCallId(call) == null) {
            mCallIdMapper.addCall(call);
            call.addListener(mCallListener);
            mBtIcsCallTracker.add(call);
        }

        maybeTrackMicrophoneUse(isMuted());
    }

    /**
     * @return true if we are bound to the UI InCallService and it is connected.
     */
    private boolean isBoundAndConnectedToServices(UserHandle userHandle) {
        CarSwappingInCallServiceConnection conn = mInCallServiceConnections.get(userHandle);
        return conn != null && conn.isConnected();
    }

    @VisibleForTesting
    public boolean isBoundAndConnectedToBTService(UserHandle userHandle) {
        synchronized (mLock) {
            InCallServiceBindingConnection conn = mBTInCallServiceConnections.get(userHandle);
            return conn != null && conn.isConnected();
        }
    }

    /**
     * @return A future that is pending whenever we are in the middle of binding to an
     *         incall service.
     */
    public CompletableFuture<Boolean> getBindingFuture() {
        return mBindingFuture;
    }

    /**
     * @return A future that is pending whenever we are in the process of sending the call
     *         disconnected state to the BT ICS so that the disconnect tone can finish playing.
     */
    public Map<String, CompletableFuture<Void>> getDisconnectedToneBtFutures() {
        return mDisconnectedToneBtFutures;
    }

    /**
     * Dumps the state of the {@link InCallController}.
     *
     * @param pw The {@code IndentingPrintWriter} to write the state to.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.println("combinedInCallServiceMap (InCalls registered):");
        pw.increaseIndent();
        Map<UserHandle, Map<InCallController.InCallServiceInfo, IInCallService>> serviceMap =
                getCombinedInCallServiceMap();
        for (Map.Entry<UserHandle, Map<InCallServiceInfo, IInCallService>> entry :
                serviceMap.entrySet()) {
            pw.println("User: " + entry.getKey());
            pw.increaseIndent();
            for (InCallServiceInfo info : entry.getValue().keySet()) {
                pw.println(info);
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();

        pw.println("ServiceConnections (InCalls bound):");
        pw.increaseIndent();
        for (InCallServiceConnection inCallServiceConnection : mInCallServiceConnections.values()) {
            inCallServiceConnection.dump(pw);
        }
        pw.decreaseIndent();

        mCarModeTracker.dump(pw);
    }

    /**
     * @return The package name of the UI which is currently bound, or null if none.
     */
    private ComponentName getConnectedUi(UserHandle userHandle) {
        Map<InCallServiceInfo, IInCallService> userServices = mInCallServices.get(userHandle);
        if (userServices != null) {
            InCallServiceInfo connectedUi = userServices.keySet().stream().filter(
                            i -> i.getType() == IN_CALL_SERVICE_TYPE_DEFAULT_DIALER_UI
                                    || i.getType() == IN_CALL_SERVICE_TYPE_SYSTEM_UI)
                    .findAny()
                    .orElse(null);
            if (connectedUi != null) {
                return connectedUi.mComponentName;
            }
        }
        return null;
    }

    public boolean doesConnectedDialerSupportRinging(UserHandle userHandle) {
        String ringingPackage =  null;

        ComponentName connectedPackage = getConnectedUi(userHandle);
        if (connectedPackage != null) {
            ringingPackage = connectedPackage.getPackageName().trim();
            Log.d(this, "doesConnectedDialerSupportRinging: alreadyConnectedPackage=%s",
                    ringingPackage);
        }

        if (TextUtils.isEmpty(ringingPackage)) {
            // The current in-call UI returned nothing, so lets use the default dialer.
            ringingPackage = mDefaultDialerCache.getRoleManagerAdapter().getDefaultDialerApp(
                    userHandle.getIdentifier());
            if (ringingPackage != null) {
                Log.d(this, "doesConnectedDialerSupportRinging: notCurentlyConnectedPackage=%s",
                        ringingPackage);
            }
        }
        if (TextUtils.isEmpty(ringingPackage)) {
            Log.w(this, "doesConnectedDialerSupportRinging: no default dialer found; oh no!");
            return false;
        }

        Intent intent = new Intent(InCallService.SERVICE_INTERFACE)
                .setPackage(ringingPackage);
        List<ResolveInfo> entries;
        entries = UserUtil.getPackageManagerFromUserHandler(mContext,
                userHandle).queryIntentServices(
                intent, PackageManager.GET_META_DATA);
        if (entries.isEmpty()) {
            Log.w(this, "doesConnectedDialerSupportRinging: couldn't find dialer's package info"
                    + " <sad trombone>");
            return false;
        }

        ResolveInfo info = entries.get(0);
        if (info.serviceInfo == null || info.serviceInfo.metaData == null) {
            Log.w(this, "doesConnectedDialerSupportRinging: couldn't find dialer's metadata"
                    + " <even sadder trombone>");
            return false;
        }

        return info.serviceInfo.metaData
                .getBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_RINGING, false);
    }

    private List<Call> orderCallsWithChildrenFirst(Collection<Call> calls) {
        LinkedList<Call> parentCalls = new LinkedList<>();
        LinkedList<Call> childCalls = new LinkedList<>();
        for (Call call : calls) {
            if (call.getChildCalls().size() > 0) {
                parentCalls.add(call);
            } else {
                childCalls.add(call);
            }
        }
        childCalls.addAll(parentCalls);
        return childCalls;
    }

    @VisibleForTesting
    public ParcelableCall sanitizeParcelableCallForService(
            InCallServiceInfo info, ParcelableCall parcelableCall) {
        ParcelableCall.ParcelableCallBuilder builder =
                ParcelableCall.ParcelableCallBuilder.fromParcelableCall(parcelableCall);
        PackageManager pm = mContext.getPackageManager();

        // Check for contacts permission.
        if (pm.checkPermission(Manifest.permission.READ_CONTACTS,
                info.getComponentName().getPackageName()) != PackageManager.PERMISSION_GRANTED) {
            // contacts permission is not present...

            // removing the contactsDisplayName
            builder.setContactDisplayName(null);
            builder.setContactPhotoUri(null);

            // removing the Call.EXTRA_IS_SUPPRESSED_BY_DO_NOT_DISTURB extra
            if (parcelableCall.getExtras() != null) {
                Bundle callBundle = parcelableCall.getExtras();
                if (callBundle.containsKey(
                        android.telecom.Call.EXTRA_IS_SUPPRESSED_BY_DO_NOT_DISTURB)) {
                    Bundle newBundle = callBundle.deepCopy();
                    newBundle.remove(android.telecom.Call.EXTRA_IS_SUPPRESSED_BY_DO_NOT_DISTURB);
                    builder.setExtras(newBundle);
                }
            }
        }

        // TODO: move all the other service-specific sanitizations in here
        return builder.createParcelableCall();
    }

    @VisibleForTesting
    public Handler getHandler() {
        return mHandler;
    }

    /**
     * Determines if the specified package is a valid car mode {@link InCallService}.
     * @param packageName The package name to check.
     * @return {@code true} if the package has a valid car mode {@link InCallService} defined,
     * {@code false} otherwise.
     */
    private boolean isCarModeInCallService(@NonNull String packageName) {
        // Disabled InCallService should also be considered as a valid InCallService here so that
        // it can be added to the CarModeTracker, in case it will be enabled in future.
        InCallServiceInfo info =
                getInCallServiceComponent(mCallsManager.getCurrentUserHandle(),
                        packageName, IN_CALL_SERVICE_TYPE_CAR_MODE_UI, false /* ignoreDisabled */);
        return info != null && info.getType() == IN_CALL_SERVICE_TYPE_CAR_MODE_UI;
    }

    public void handleCarModeChange(int priority, String packageName, boolean isCarMode) {
        Log.i(this, "handleCarModeChange: packageName=%s, priority=%d, isCarMode=%b",
                packageName, priority, isCarMode);
        if (packageName == null) {
            Log.i(this, "handleCarModeChange: Got null packageName, ignoring");
            return;
        }
        // Don't ignore the signal if we are disabling car mode; package may be uninstalled.
        if (isCarMode && !isCarModeInCallService(packageName)) {
            Log.i(this, "handleCarModeChange: not a valid InCallService; packageName=%s",
                    packageName);
            return;
        }

        if (isCarMode) {
            mCarModeTracker.handleEnterCarMode(priority, packageName);
        } else {
            mCarModeTracker.handleExitCarMode(priority, packageName);
        }

        updateCarModeForConnections();
    }

    public void handleSetAutomotiveProjection(@NonNull String packageName) {
        Log.i(this, "handleSetAutomotiveProjection: packageName=%s", packageName);
        if (!isCarModeInCallService(packageName)) {
            Log.i(this, "handleSetAutomotiveProjection: not a valid InCallService: packageName=%s",
                    packageName);
            return;
        }
        mCarModeTracker.handleSetAutomotiveProjection(packageName);

        updateCarModeForConnections();
    }

    public void handleReleaseAutomotiveProjection() {
        Log.i(this, "handleReleaseAutomotiveProjection");
        mCarModeTracker.handleReleaseAutomotiveProjection();

        updateCarModeForConnections();
    }

    public void updateCarModeForConnections() {
        Log.i(this, "updateCarModeForConnections: car mode apps: %s",
                mCarModeTracker.getCarModeApps().stream().collect(Collectors.joining(", ")));

        UserManager um = mContext.getSystemService(UserManager.class);
        UserHandle currentUser = mCallsManager.getCurrentUserHandle();
        UserHandle childUser = findChildManagedProfileUser(currentUser, um);

        CarSwappingInCallServiceConnection inCallServiceConnectionForCurrentUser = null;
        CarSwappingInCallServiceConnection inCallServiceConnectionForChildUser = null;

        Log.i(this, "update carmode current:%s parent:%s", currentUser, childUser);
        inCallServiceConnectionForCurrentUser = mInCallServiceConnections.get(currentUser);
        if (childUser != null) {
            inCallServiceConnectionForChildUser = mInCallServiceConnections.get(childUser);
        }

        if (shouldUseCarModeUI()) {
            Log.i(this, "updateCarModeForConnections: potentially update car mode app.");
            //always pass current user to changeCarMode. That will ultimately be used for bindAsUser
            if (inCallServiceConnectionForCurrentUser != null) {
                inCallServiceConnectionForCurrentUser.changeCarModeApp(
                        mCarModeTracker.getCurrentCarModePackage(),
                        currentUser);
            }
            if (inCallServiceConnectionForChildUser != null) {
                inCallServiceConnectionForChildUser.changeCarModeApp(
                        mCarModeTracker.getCurrentCarModePackage(),
                        currentUser);
            }
        } else {
            if (inCallServiceConnectionForCurrentUser != null
                    && inCallServiceConnectionForCurrentUser.isCarMode()) {
                Log.i(this, "updateCarModeForConnections: car mode no longer "
                        + "applicable for current user; disabling");
                inCallServiceConnectionForCurrentUser.disableCarMode();
            }
            if (inCallServiceConnectionForChildUser != null
                    && inCallServiceConnectionForChildUser.isCarMode()) {
                Log.i(this, "updateCarModeForConnections: car mode no longer "
                        + "applicable for child user; disabling");
                inCallServiceConnectionForChildUser.disableCarMode();
            }
        }
    }

    /**
     * Tracks start of microphone use on binding to the current calling UX.
     * @param info
     */
    private void trackCallingUserInterfaceStarted(InCallServiceInfo info) {
        String packageName = info.getComponentName().getPackageName();
        if (!Objects.equals(mCurrentUserInterfacePackageName, packageName)) {
            Log.i(this, "trackCallingUserInterfaceStarted: %s is now calling UX.", packageName);
            mCurrentUserInterfacePackageName = packageName;
        }
        maybeTrackMicrophoneUse(isMuted());
    }

    /**
     * Tracks stop of microphone use on unbind from the current calling UX.
     * @param info
     */
    private void trackCallingUserInterfaceStopped(InCallServiceInfo info) {
        maybeTrackMicrophoneUse(isMuted());
        mCurrentUserInterfacePackageName = null;
        String packageName = info.getComponentName().getPackageName();
        Log.i(this, "trackCallingUserInterfaceStopped: %s is no longer calling UX", packageName);
    }

    private void maybeTrackMicrophoneUse(boolean isMuted) {
        maybeTrackMicrophoneUse(isMuted, false);
    }

    /**
     * As calls are added, removed and change between external and non-external status, track
     * whether the current active calling UX is using the microphone.  We assume if there is a
     * managed call present and the mic is not muted that the microphone is in use.
     */
    private void maybeTrackMicrophoneUse(boolean isMuted, boolean isScheduledDelay) {
        if (mIsStartCallDelayScheduled && !isScheduledDelay) {
            return;
        }

        mIsStartCallDelayScheduled = false;
        boolean wasUsingMicrophone = mIsCallUsingMicrophone;
        boolean wasTrackingCall = mIsTrackingManagedAliveCall;
        mIsTrackingManagedAliveCall = isTrackingManagedAliveCall();
        if (!wasTrackingCall && mIsTrackingManagedAliveCall) {
            mIsStartCallDelayScheduled = true;
            mHandler.postDelayed(new Runnable("ICC.mTMU", mLock) {
                @Override
                public void loggedRun() {
                    maybeTrackMicrophoneUse(isMuted(), true);
                }
            }.prepare(), mTimeoutsAdapter.getCallStartAppOpDebounceIntervalMillis());
            return;
        }

        mIsCallUsingMicrophone = mIsTrackingManagedAliveCall && !isMuted
                && !isCarrierPrivilegedUsingMicDuringVoipCall();
        if (wasUsingMicrophone != mIsCallUsingMicrophone) {
            int opPackageUid = getOpPackageUid();
            if (mIsCallUsingMicrophone) {
                // Note, not checking return value, as this op call is merely for tracing use
                mAppOpsManager.startOp(AppOpsManager.OPSTR_PHONE_CALL_MICROPHONE, opPackageUid,
                        mContext.getOpPackageName(), null /* attribution */, null /* msg */);
            } else {
                mAppOpsManager.finishOp(AppOpsManager.OPSTR_PHONE_CALL_MICROPHONE, opPackageUid,
                        mContext.getOpPackageName(), null /* attribution */);
            }
        }
    }

    /**
     * Returns the uid of the package in the current user to be used for app ops attribution.
     */
    private int getOpPackageUid() {
        UserHandle user = mCallsManager.getCurrentUserHandle();

        try {
            int uid;
            uid = UserUtil.getPackageManagerFromUserHandler(mContext,
                    user).getPackageUid(mContext.getOpPackageName(), 0/*flags*/);
            return uid;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(this, e, "getPackageForAssociatedUser: could not find package %s"
                    + " for user %s", mContext.getOpPackageName(), user);
            // fallback to current process id - this should not happen
            return myUid();
        }
    }

    /**
     * @return {@code true} if InCallController is tracking a managed call (i.e. not self managed
     * and not external) that is active.
     */
    private boolean isTrackingManagedAliveCall() {
        return mCallIdMapper.getCalls().stream().anyMatch(c -> !c.isExternalCall()
                && !c.isSelfManaged() && c.isAlive() && isCallInLiveCallState(c));
    }

    private boolean isCallInLiveCallState(Call call) {
        return IntStream.of(LIVE_CALL_STATES).anyMatch(element -> element == call.getState());
    }

    private boolean isCarrierPrivilegedUsingMicDuringVoipCall() {
        return !mActiveCarrierPrivilegedApps.isEmpty() &&
                mCallIdMapper.getCalls().stream().anyMatch(Call::getIsVoipAudioMode);
    }

    /**
     * @return {@code true} if the audio is currently muted, {@code false} otherwise.
     */
    private boolean isMuted() {
        if (mCallsManager.getAudioState() == null) {
            return false;
        }
        return mCallsManager.getAudioState().isMuted();
    }

    private boolean isAppOpsPermittedManageOngoingCalls(int uid, String callingPackage) {
        // checkPermissionForDataDelivery is intended for use when we are checking the
        // permission for the purpose of actually sending data to the recipient; this is in
        // contrast to in TelecomServiceImpl#hasManageOngoingCallsPermission which calls
        // checkPermissionForPreflight since that is not actually going to result in data
        // delivery to the app.
        int result = mPermissionManager.checkPermissionForDataDelivery(
                Manifest.permission.MANAGE_ONGOING_CALLS, new AttributionSource.Builder(uid)
                        .setPackageName(callingPackage)
                        .build(),
                "Reporting ongoing calls to app.");
        Log.d(this, "isAppOpsPermittedManageOngoingCalls: uid=%d, pkg=%s, res=%d", uid,
                callingPackage, result);
        return result == PermissionManager.PERMISSION_GRANTED;
    }

    private void sendCrashedInCallServiceNotification(String packageName, UserHandle userHandle) {
        PackageManager packageManager = mContext.getPackageManager();
        CharSequence appName;
        String systemDialer = mDefaultDialerCache.getSystemDialerApplication();
        if ((systemDialer != null) && systemDialer.equals(packageName)) {
            return;
        }
        try {
            appName = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0));
            if (TextUtils.isEmpty(appName)) {
                appName = packageName;
            }
        } catch (PackageManager.NameNotFoundException e) {
            appName = packageName;
        }
        Notification.Builder builder = new Notification.Builder(mContext,
                NotificationChannelManager.CHANNEL_ID_IN_CALL_SERVICE_CRASH);
        builder.setSmallIcon(TelecomResourceId.getIdentifier(mContext, "ic_phone", "drawable"))
                .setColor(TelecomResourceId.getResources(mContext).getColor(
                        TelecomResourceId.getIdentifier(mContext, "theme_color", "color")))
                .setContentTitle(TelecomResourceId.getString(mContext,
                        "notification_incallservice_not_responding_title", appName))
                .setStyle(new Notification.BigTextStyle()
                .bigText(TelecomResourceId.getText(mContext,
                        "notification_incallservice_not_responding_body")));
        UserUtil.processNotification(mContext, userHandle, NOTIFICATION_TAG,
                IN_CALL_SERVICE_NOTIFICATION_ID, builder.build());
    }

    private void updateCallTracking(Call call, InCallServiceInfo info, boolean isAdd) {
        int type = info.getType();
        boolean hasUi = type == IN_CALL_SERVICE_TYPE_CAR_MODE_UI
                || type == IN_CALL_SERVICE_TYPE_DEFAULT_DIALER_UI;
        call.maybeOnInCallServiceTrackingChanged(isAdd, hasUi);
    }

    private UserHandle getUserFromCall(Call call) {
        // Call may never be specified, so we can fall back to using the CallManager current user.
        if (call == null) {
            return mCallsManager.getCurrentUserHandle();
        } else {
            UserHandle userFromCall = call.getAssociatedUser();

            UserHandle currentUser = mCallsManager.getCurrentUserHandle() != null
                ? mCallsManager.getCurrentUserHandle() : UserHandle.CURRENT;

            UserManager userManager = mContext.createContextAsUser(currentUser, 0)
                            .getSystemService(UserManager.class);
            boolean isCurrentUserAdmin = userManager.isAdminUser();

            // Emergency call should never be blocked, so if the user associated with the target
            // phone account handle user is in quiet mode, use the current user for the ecall.
            // Note, that this only applies to incoming calls that are received on assigned
            // sims (i.e. work sim), where the associated user would be the target phone account
            // handle user.
            if ((call.isEmergencyCall() || call.isInECBM())
                    && (userManager.isQuietModeEnabled(userFromCall)
                    // We should also account for secondary/guest users where the profile may not
                    // necessarily be paused.
                    || !isCurrentUserAdmin)) {
                return mCallsManager.getCurrentUserHandle();
            }
            return userFromCall;
        }
    }

    /**
     * Useful for debugging purposes and called on the command line via
     * an "adb shell telecom command".
     *
     * @return true if a particular non-ui InCallService package is bound in a call.
     */
    public boolean isNonUiInCallServiceBound(String packageName) {
        for (NonUIInCallServiceConnectionCollection ics : mNonUIInCallServiceConnections.values()) {
            for (InCallServiceBindingConnection connection : ics.getSubConnections()) {
                InCallServiceInfo serviceInfo = connection.mInCallServiceInfo;
                Log.i(this, "isNonUiInCallServiceBound: found serviceInfo=[%s]", serviceInfo);
                if (serviceInfo != null &&
                        serviceInfo.mComponentName.getPackageName().contains(packageName)) {
                    Log.i(this, "isNonUiInCallServiceBound: found target package");
                    return true;
                }
            }
        }
        // If early binding for BT ICS is enabled, ensure that it is included into consideration as
        // a bound non-UI ICS.
        synchronized (mLock) {
            return !mBTInCallServices.isEmpty() && isBluetoothPackage(packageName);
        }
    }

    private void updateCombinedInCallServiceMap(UserHandle user) {
        synchronized (mLock) {
            Map<InCallServiceInfo, IInCallService> serviceMap = new ArrayMap<>();
            Map<InCallServiceInfo, IInCallService> mainServices = mInCallServices.get(user);
            if (mainServices != null) {
                serviceMap.putAll(mainServices);
            }
            Pair<InCallServiceInfo, IInCallService> btServicePair = mBTInCallServices.get(user);
            if (btServicePair != null) {
                serviceMap.put(btServicePair.first, btServicePair.second);
            }
            if (!serviceMap.isEmpty()) {
                mCombinedInCallServiceMap.put(user, Collections.unmodifiableMap(serviceMap));
            } else {
                mCombinedInCallServiceMap.remove(user);
            }
        }
    }

    private Map<UserHandle,
            Map<InCallController.InCallServiceInfo, IInCallService>> getCombinedInCallServiceMap() {
        synchronized (mLock) {
            return mCombinedInCallServiceMap;
        }
    }

    private boolean isBluetoothPackage(String packageName) {
        for (String pkgName : mDefaultDialerCache.getBTInCallServicePackages()) {
            if (pkgName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Given a {@link ParcelableCall} and a {@link IInCallService}, determines if the ICS binder is
     * local or remote.  If the binder is remote, we just return the parcelable call instance
     * already constructed.
     * If the binder if local, as will be the case for
     * {@code EnhancedConfirmationCallTrackerService} (or any other ICS in the system server, the
     * underlying Binder implementation is NOT going to parcel and unparcel the
     * {@link ParcelableCall} instance automatically.  This means that the parcelable call instance
     * is passed by reference and that the ICS in the system server could potentially try to access
     * internals in the {@link ParcelableCall} in an unsafe manner.  As a workaround, we will
     * manually parcel and unparcel the {@link ParcelableCall} instance so that they get a fresh
     * copy that they can use safely.
     *
     * @param parcelableCall The ParcelableCall instance we want to maybe copy.
     * @param remote the binder the call is going out over.
     * @return either the original {@link ParcelableCall} or a deep copy of it if the destination
     * binder is local.
     */
    private ParcelableCall copyIfLocal(ParcelableCall parcelableCall, IInCallService remote) {
        // We care more about parceling than local (though they should be the same); so, use
        // queryLocalInterface since that's what Binder uses to decide if it needs to parcel.
        if (remote.asBinder().queryLocalInterface(IInCallService.Stub.DESCRIPTOR) == null) {
            // No local interface, so binder itself will parcel and thus we don't need to.
            return parcelableCall;
        }
        // Binder won't be parceling; however, the remotes assume they have their own native
        // objects (and don't know if caller is local or not), so we need to make a COPY here so
        // that the remote can clean it up without clearing the original transaction.
        // Since there's no direct `copy` for Transaction, we have to parcel/unparcel instead.
        final Parcel p = Parcel.obtain();
        try {
            parcelableCall.writeToParcel(p, 0);
            p.setDataPosition(0);
            return ParcelableCall.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    /**
     * It's possible to race between when Telecom sends the request to abandon audio focus and
     * when the AF processes the message with when the ICS are unbound once a call is disconnected.
     * Once audio focus is relinquished, the ICS shouldn't be updated with audio states that could
     * potentially be changed by other apps who have gained focus. Instead, we can check if the
     * focus state is unfocused and that all calls are disconnected in order to prevent additional
     * audio style updates from being sent at the end of the call.
     */
    private boolean shouldSkipUpdateIcs() {
        if (!com.android.internal.telecom.flags.Flags.skipAudioUpdateToIcs()) {
            return false;
        }
        Collection<Call> calls = mCallsManager.getCalls();
        boolean allCallsAreDisconnected = true;
        for (Call call : calls) {
            if (call.getState() != CallState.DISCONNECTED) {
                allCallsAreDisconnected = false;
                break;
            }
        }
        boolean shouldSkipUpdate = mCallsManager.getCallAudioManager().isFocusStateUnfocused()
                && allCallsAreDisconnected;
        if (shouldSkipUpdate) {
            Log.w(this, "Skipping audio update for ICS because all calls are disconnected and"
                    + " the audio focus is abandoned.");
        }
        return shouldSkipUpdate;
    }
}
