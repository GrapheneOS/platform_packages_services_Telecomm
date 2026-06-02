package com.android.server.telecom;

import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;

import android.Manifest;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.ui.UiConstants;

import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.telecom.DefaultDialerManager;
import android.telecom.Log;
import android.telecom.Logging.Session;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Single point of entry for all outgoing and incoming calls.
 * {@link com.android.server.telecom.components.UserCallIntentProcessor} serves as a trampoline that
 * captures call intents for individual users and forwards it to the {@link CallIntentProcessor}
 * which interacts with the rest of Telecom, both of which run only as the primary user.
 */
public class CallIntentProcessor {
    public interface Adapter {
        void processOutgoingCallIntent(Context context, CallsManager callsManager,
                Intent intent, String callingPackage, FeatureFlags featureFlags);
        void processIncomingCallIntent(CallsManager callsManager, Intent intent);
        void processUnknownCallIntent(CallsManager callsManager, Intent intent);
    }

    public static class AdapterImpl implements Adapter {
        private final DefaultDialerCache mDefaultDialerCache;
        private final String mTelecomUiPackageName;
        public AdapterImpl(DefaultDialerCache cache, String telecomUiPackageName) {
            mDefaultDialerCache = cache;
            mTelecomUiPackageName = telecomUiPackageName;
        }

        @Override
        public void processOutgoingCallIntent(Context context, CallsManager callsManager,
                Intent intent, String callingPackage, FeatureFlags featureFlags) {
            CallIntentProcessor.processOutgoingCallIntent(context, callsManager, intent,
                    callingPackage, mTelecomUiPackageName, mDefaultDialerCache, featureFlags);
        }

        @Override
        public void processIncomingCallIntent(CallsManager callsManager, Intent intent) {
            CallIntentProcessor.processIncomingCallIntent(callsManager, intent);
        }

        @Override
        public void processUnknownCallIntent(CallsManager callsManager, Intent intent) {
            CallIntentProcessor.processUnknownCallIntent(callsManager, intent);
        }
    }

    public static final String KEY_IS_UNKNOWN_CALL = "is_unknown_call";
    public static final String KEY_IS_INCOMING_CALL = "is_incoming_call";
    public static final String FORWARD_INTENT_TO_PARENT =
            "com.android.internal.app.ForwardIntentToParent";

    /**
     * The user initiating the outgoing call.
     */
    public static final String KEY_INITIATING_USER = "initiating_user";


    private final Context mContext;
    private final CallsManager mCallsManager;
    private final DefaultDialerCache mDefaultDialerCache;
    private final String mTelecomPackageName;
    private final FeatureFlags mFeatureFlags;

    public CallIntentProcessor(Context context, CallsManager callsManager,
            DefaultDialerCache defaultDialerCache, String telecomUiPackageName,
            FeatureFlags featureFlags) {
        this.mContext = context;
        this.mCallsManager = callsManager;
        this.mDefaultDialerCache = defaultDialerCache;
        this.mTelecomPackageName = telecomUiPackageName;
        this.mFeatureFlags = featureFlags;
    }

    public void processIntent(Intent intent, String callingPackage) {
        final boolean isUnknownCall = intent.getBooleanExtra(KEY_IS_UNKNOWN_CALL, false);
        Log.i(this, "onReceive - isUnknownCall: %s", isUnknownCall);

        if (isUnknownCall) {
            processUnknownCallIntent(mCallsManager, intent);
        } else {
            processOutgoingCallIntent(mContext, mCallsManager, intent, callingPackage,
                    mTelecomPackageName, mDefaultDialerCache, mFeatureFlags);
        }
    }


    /**
     * Processes CALL, CALL_PRIVILEGED, and CALL_EMERGENCY intents.
     *
     * @param intent Call intent containing data about the handle to call.
     * @param callingPackage The package which initiated the outgoing call (if known).
     */
    static void processOutgoingCallIntent(
            Context context,
            CallsManager callsManager,
            Intent intent,
            String callingPackage,
            String telecomUiPackage,
            DefaultDialerCache defaultDialerCache,
            FeatureFlags featureFlags) {

        Uri handle = intent.getData();
        String scheme = handle.getScheme();
        String uriString = handle.getSchemeSpecificPart();

        // Ensure sip URIs dialed using TEL scheme get converted to SIP scheme.
        if (PhoneAccount.SCHEME_TEL.equals(scheme) && PhoneNumberUtils.isUriNumber(uriString)) {
            handle = Uri.fromParts(PhoneAccount.SCHEME_SIP, uriString, null);
        }

        PhoneAccountHandle phoneAccountHandle = intent.getParcelableExtra(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);

        Bundle clientExtras = null;
        if (intent.hasExtra(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)) {
            clientExtras = intent.getBundleExtra(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS);
        }
        if (clientExtras == null) {
            clientExtras = new Bundle();
        }

        if (intent.hasExtra(TelecomManager.EXTRA_IS_USER_INTENT_EMERGENCY_CALL)) {
            clientExtras.putBoolean(TelecomManager.EXTRA_IS_USER_INTENT_EMERGENCY_CALL,
                    intent.getBooleanExtra(TelecomManager.EXTRA_IS_USER_INTENT_EMERGENCY_CALL,
                            false));
        }

        // Ensure call subject is passed on to the connection service.
        if (intent.hasExtra(TelecomManager.EXTRA_CALL_SUBJECT)) {
            String callsubject = intent.getStringExtra(TelecomManager.EXTRA_CALL_SUBJECT);
            clientExtras.putString(TelecomManager.EXTRA_CALL_SUBJECT, callsubject);
        }

        if (intent.hasExtra(android.telecom.TelecomManager.EXTRA_PRIORITY)) {
            clientExtras.putInt(android.telecom.TelecomManager.EXTRA_PRIORITY, intent.getIntExtra(
                    android.telecom.TelecomManager.EXTRA_PRIORITY,
                            android.telecom.TelecomManager.PRIORITY_NORMAL));
        }

        if (intent.hasExtra(android.telecom.TelecomManager.EXTRA_LOCATION)) {
            clientExtras.putParcelable(android.telecom.TelecomManager.EXTRA_LOCATION,
                    intent.getParcelableExtra(android.telecom.TelecomManager.EXTRA_LOCATION));
        }

        if (intent.hasExtra(android.telecom.TelecomManager.EXTRA_OUTGOING_PICTURE)) {
            clientExtras.putParcelable(android.telecom.TelecomManager.EXTRA_OUTGOING_PICTURE,
                    intent.getParcelableExtra(
                            android.telecom.TelecomManager.EXTRA_OUTGOING_PICTURE));
        }

        final int videoState = intent.getIntExtra( TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                VideoProfile.STATE_AUDIO_ONLY);
        clientExtras.putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, videoState);

        if (!callsManager.isSelfManaged(phoneAccountHandle,
                (UserHandle) intent.getParcelableExtra(KEY_INITIATING_USER))) {
            boolean fixedInitiatingUser = fixInitiatingUserIfNecessary(
                    context, intent, featureFlags);
            // Show the toast to warn user that it is a personal call though initiated in work
            // profile.
            if (fixedInitiatingUser) {
                context.getMainExecutor().execute(() ->
                        Toast.makeText(context, TelecomResourceId.getString(context,
                                "toast_personal_call_msg"), Toast.LENGTH_LONG).show());
            }
        } else {
            Log.i(CallIntentProcessor.class,
                    "processOutgoingCallIntent: skip initiating user check");
        }

        UserHandle initiatingUser = intent.getParcelableExtra(KEY_INITIATING_USER);

        boolean isPrivilegedDialer = defaultDialerCache.isDefaultOrSystemDialer(callingPackage,
                initiatingUser.getIdentifier())
                || (callingPackage != null
                        && UserUtil.getPackageManagerFromUserHandler(context, initiatingUser)
                                .checkPermission(Manifest.permission.CALL_PRIVILEGED,
                                                 callingPackage)
                                == PackageManager.PERMISSION_GRANTED);

        if (!callsManager.isSelfManaged(phoneAccountHandle, initiatingUser)
                && !TelephonyUtil.shouldProcessAsEmergency(context, handle)
                && UserUtil.isPrivateProfile(initiatingUser, context)) {
            boolean dialogShown = maybeRedirectToIntentForwarderForPrivate(context, intent,
                    initiatingUser);
            if (dialogShown) {
                return;
            }
        }

        NewOutgoingCallIntentBroadcaster broadcaster = new NewOutgoingCallIntentBroadcaster(
                context, callsManager, intent, callsManager.getPhoneNumberUtilsAdapter(),
                isPrivilegedDialer, defaultDialerCache, new MmiUtils(), featureFlags);

        // If the broadcaster comes back with an immediate error, disconnect and show a dialog.
        NewOutgoingCallIntentBroadcaster.CallDisposition disposition = broadcaster.evaluateCall();
        if (disposition.disconnectCause != DisconnectCause.NOT_DISCONNECTED) {
            showErrorDialog(context, telecomUiPackage, disposition.disconnectCause);
            return;
        }

        // Send to CallsManager to ensure the InCallUI gets kicked off before the broadcast returns
        CompletableFuture<Call> callFuture = callsManager
                .startOutgoingCall(handle, phoneAccountHandle, clientExtras, initiatingUser,
                        intent, callingPackage);

        final Session logSubsession = Log.createSubsession();
        callFuture.thenAccept((call) -> {
            if (call != null) {
                Log.i(CallIntentProcessor.class, "Call for outgoing call - %s", call);
                Log.continueSession(logSubsession, "CIP.sNOCI");
                try {
                    broadcaster.processCall(call, disposition);
                } finally {
                    Log.endSession();
                }
            }
        }).exceptionally(
                e -> {
                    Log.e(CallIntentProcessor.class, e, "Failed to start outgoing call");
                    return null;
                }
        );;
    }

    /**
     * Determines if the {@link RoleManager} for the specified user has a role holder for the dialer
     * role.
     * @param context the context.
     * @param user the user to check.
     * @return {@code true} if the dialer role is held by a valid apk that meets the requirements of
     * being a dialer app, {@code false} otherwise.
     */
    private static boolean doesUserHaveDialerRoleHolder(Context context, UserHandle user) {
        Context userContext = context.createContextAsUser(user, 0);
        RoleManager roleManager = userContext.getSystemService(RoleManager.class);
        List<String> roleHolders = roleManager.getRoleHolders(RoleManager.ROLE_DIALER);
        if (roleHolders == null || roleHolders.isEmpty()) {
            return false;
        }

        String dialerPackage = roleHolders.get(0);
        try {
            ApplicationInfo info = userContext.getPackageManager()
                    .getApplicationInfo(dialerPackage, 0);
            return info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * If the call is initiated from managed profile but there is no work dialer installed, treat
     * the call is initiated from its parent user.
     *
     * @return whether the initiating user is fixed.
     */
    static boolean fixInitiatingUserIfNecessary(Context context, Intent intent,
            FeatureFlags featureFlags) {
        final UserHandle initiatingUser = intent.getParcelableExtra(KEY_INITIATING_USER);
        if (UserUtil.isManagedProfile(context, initiatingUser)) {
            // Note: We used to just check DefaultDialerManager.getInstalledDialerApplications to
            // see if some activity handles the ACTION_DIAL intent.  An OS bug was introduced
            // where we were seeing that a work profile had something handling the ACTION_DIAL
            // intent in the android package, despite none existing. (╯°□°)╯︵ ┻━┻
            // That method was introduced in 2015 when there was no concept of a dialer role and
            // RoleManager.  It is much more reliable to check if the RoleManager reports that
            // there is a role holder for that user instead.  Just because you handle the dial
            // intent it does not mean you are actually a dialer.
            boolean noDialerInstalled = !doesUserHaveDialerRoleHolder(context, initiatingUser);
            if (noDialerInstalled) {
                final UserManager userManager = context.getSystemService(UserManager.class);
                UserHandle parentUserHandle = userManager.getProfileParent(initiatingUser);
                intent.putExtra(KEY_INITIATING_USER, parentUserHandle);

                Log.i(CallIntentProcessor.class, "fixInitiatingUserIfNecessary: no dialer installed"
                        + " for current user; setting initiator to parent %s" + parentUserHandle);
                return true;
            }
        }
        return false;
    }

    static void processIncomingCallIntent(CallsManager callsManager, Intent intent) {
        PhoneAccountHandle phoneAccountHandle = intent.getParcelableExtra(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);

        if (phoneAccountHandle == null) {
            Log.w(CallIntentProcessor.class,
                    "Rejecting incoming call due to null phone account");
            return;
        }
        if (phoneAccountHandle.getComponentName() == null) {
            Log.w(CallIntentProcessor.class,
                    "Rejecting incoming call due to null component name");
            return;
        }

        Bundle clientExtras = null;
        if (intent.hasExtra(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)) {
            clientExtras = intent.getBundleExtra(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS);
        }
        if (clientExtras == null) {
            clientExtras = new Bundle();
        }

        Log.d(CallIntentProcessor.class,
                "Processing incoming call from connection service [%s]",
                phoneAccountHandle.getComponentName());
        callsManager.processIncomingCallIntent(phoneAccountHandle, clientExtras);
    }

    static void processUnknownCallIntent(CallsManager callsManager, Intent intent) {
        PhoneAccountHandle phoneAccountHandle = intent.getParcelableExtra(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);

        if (phoneAccountHandle == null) {
            Log.w(CallIntentProcessor.class, "Rejecting unknown call due to null phone account");
            return;
        }
        if (phoneAccountHandle.getComponentName() == null) {
            Log.w(CallIntentProcessor.class, "Rejecting unknown call due to null component name");
            return;
        }

        callsManager.addNewUnknownCall(phoneAccountHandle, intent.getExtras());
    }

    private static void showErrorDialog(Context context, String telecomUiPackage, int errorCode) {
        final Intent errorIntent = new Intent();
        errorIntent.setClassName(telecomUiPackage,
              UiConstants.COMPONENT_ERROR_DIALOG);

        CharSequence errorMessage = null;
        switch (errorCode) {
            case DisconnectCause.INVALID_NUMBER:
            case DisconnectCause.NO_PHONE_NUMBER_SUPPLIED:
                errorMessage = TelecomResourceId.getString(context,
                        "outgoing_call_error_no_phone_number_supplied");
                break;
        }
        if (errorMessage != null) {
            errorIntent.putExtra(UiConstants.ERROR_MESSAGE_STRING_EXTRA, errorMessage);
            errorIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivityAsUser(errorIntent, UserHandle.CURRENT);
        }
    }

    private static boolean maybeRedirectToIntentForwarderForPrivate(
            Context context,
            Intent forwardCallIntent,
            UserHandle initiatingUser) {

        // If CALL intent filters are set to SKIP_CURRENT_PROFILE, PM will resolve this to an
        // intent forwarder activity.
        forwardCallIntent.setComponent(null);
        forwardCallIntent.setPackage(null);
        Context userContext = context.createContextAsUser(initiatingUser, 0 /* flags */);

        ResolveInfo resolveInfo = userContext.getPackageManager().resolveActivity(
                forwardCallIntent,
                PackageManager.ResolveInfoFlags.of(MATCH_DEFAULT_ONLY));

        if (resolveInfo == null || resolveInfo.activityInfo == null) {
          return false;
        }

        ComponentName componentName = new ComponentName(
            resolveInfo.activityInfo.packageName,
            resolveInfo.activityInfo.name);

        if (!componentName.getShortClassName().equals(FORWARD_INTENT_TO_PARENT)) {
          return false;
        }

        try {
            userContext.startActivity(forwardCallIntent);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.e(CallIntentProcessor.class, e, "Unable to start call intent in the main user");
            return false;
        }
    }
}
