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

package com.android.server.telecom;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.sysprop.TelephonyProperties;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.telecom.ITelecomService;
import com.android.modules.utils.BasicShellCommandHandler;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Implements shell commands sent to telecom using the "adb shell cmd telecom..." command from shell
 * or CTS.
 */
public class TelecomShellCommand extends BasicShellCommandHandler {
    private static final String CALLING_PACKAGE = TelecomShellCommand.class.getPackageName();
    private static final String COMMAND_SET_PHONE_ACCOUNT_ENABLED = "set-phone-account-enabled";
    private static final String COMMAND_SET_PHONE_ACCOUNT_DISABLED = "set-phone-account-disabled";
    private static final String COMMAND_REGISTER_PHONE_ACCOUNT = "register-phone-account";
    private static final String COMMAND_SET_USER_SELECTED_OUTGOING_PHONE_ACCOUNT =
            "set-user-selected-outgoing-phone-account";
    private static final String COMMAND_REGISTER_SIM_PHONE_ACCOUNT = "register-sim-phone-account";
    private static final String COMMAND_SET_TEST_CALL_REDIRECTION_APP =
            "set-test-call-redirection-app";
    private static final String COMMAND_SET_TEST_CALL_SCREENING_APP = "set-test-call-screening-app";
    private static final String COMMAND_ADD_OR_REMOVE_CALL_COMPANION_APP =
            "add-or-remove-call-companion-app";
    private static final String COMMAND_SET_PHONE_ACCOUNT_SUGGESTION_COMPONENT =
            "set-phone-acct-suggestion-component";
    private static final String COMMAND_UNREGISTER_PHONE_ACCOUNT = "unregister-phone-account";
    private static final String COMMAND_SET_CALL_DIAGNOSTIC_SERVICE = "set-call-diagnostic-service";
    private static final String COMMAND_SET_DEFAULT_DIALER = "set-default-dialer";
    private static final String COMMAND_GET_DEFAULT_DIALER = "get-default-dialer";
    private static final String COMMAND_STOP_BLOCK_SUPPRESSION = "stop-block-suppression";
    private static final String COMMAND_CLEANUP_STUCK_CALLS = "cleanup-stuck-calls";
    private static final String COMMAND_CLEANUP_ORPHAN_PHONE_ACCOUNTS =
            "cleanup-orphan-phone-accounts";
    private static final String COMMAND_RESET_CAR_MODE = "reset-car-mode";
    private static final String COMMAND_IS_NON_IN_CALL_SERVICE_BOUND =
            "is-non-ui-in-call-service-bound";

    /**
     * Change the system dialer package name if a package name was specified,
     * Example: adb shell telecom set-system-dialer <PACKAGE>
     *
     * Restore it to the default if if argument is "default" or no argument is passed.
     * Example: adb shell telecom set-system-dialer default
     */
    private static final String COMMAND_SET_SYSTEM_DIALER = "set-system-dialer";
    private static final String COMMAND_GET_SYSTEM_DIALER = "get-system-dialer";
    private static final String COMMAND_WAIT_ON_HANDLERS = "wait-on-handlers";
    private static final String COMMAND_SET_SIM_COUNT = "set-sim-count";
    private static final String COMMAND_GET_SIM_CONFIG = "get-sim-config";
    private static final String COMMAND_GET_MAX_PHONES = "get-max-phones";
    private static final String COMMAND_SET_TEST_EMERGENCY_PHONE_ACCOUNT_PACKAGE_FILTER =
            "set-test-emergency-phone-account-package-filter";
    /**
     * Command used to emit a distinct "mark" in the logs.
     */
    private static final String COMMAND_LOG_MARK = "log-mark";

    private final Context mContext;
    private final ITelecomService mTelecomService;
    private TelephonyManager mTelephonyManager;
    private UserManager mUserManager;

    public TelecomShellCommand(ITelecomService binder, Context context) {
        mTelecomService = binder;
        mContext = context;
    }

    @Override
    public int onCommand(String command) {
        if (command == null || command.isEmpty()) {
            onHelp();
            return 0;
        }
        try {
            switch (command) {
                case COMMAND_SET_PHONE_ACCOUNT_ENABLED:
                    runSetPhoneAccountEnabled(true);
                    break;
                case COMMAND_SET_PHONE_ACCOUNT_DISABLED:
                    runSetPhoneAccountEnabled(false);
                    break;
                case COMMAND_REGISTER_PHONE_ACCOUNT:
                    runRegisterPhoneAccount();
                    break;
                case COMMAND_SET_TEST_CALL_REDIRECTION_APP:
                    runSetTestCallRedirectionApp();
                    break;
                case COMMAND_SET_TEST_CALL_SCREENING_APP:
                    runSetTestCallScreeningApp();
                    break;
                case COMMAND_ADD_OR_REMOVE_CALL_COMPANION_APP:
                    runAddOrRemoveCallCompanionApp();
                    break;
                case COMMAND_SET_PHONE_ACCOUNT_SUGGESTION_COMPONENT:
                    runSetTestPhoneAcctSuggestionComponent();
                    break;
                case COMMAND_SET_CALL_DIAGNOSTIC_SERVICE:
                    runSetCallDiagnosticService();
                    break;
                case COMMAND_REGISTER_SIM_PHONE_ACCOUNT:
                    runRegisterSimPhoneAccount();
                    break;
                case COMMAND_SET_USER_SELECTED_OUTGOING_PHONE_ACCOUNT:
                    runSetUserSelectedOutgoingPhoneAccount();
                    break;
                case COMMAND_UNREGISTER_PHONE_ACCOUNT:
                    runUnregisterPhoneAccount();
                    break;
                case COMMAND_STOP_BLOCK_SUPPRESSION:
                    runStopBlockSuppression();
                    break;
                case COMMAND_CLEANUP_STUCK_CALLS:
                    runCleanupStuckCalls();
                    break;
                case COMMAND_CLEANUP_ORPHAN_PHONE_ACCOUNTS:
                    runCleanupOrphanPhoneAccounts();
                    break;
                case COMMAND_RESET_CAR_MODE:
                    runResetCarMode();
                    break;
                case COMMAND_SET_DEFAULT_DIALER:
                    runSetDefaultDialer();
                    break;
                case COMMAND_GET_DEFAULT_DIALER:
                    runGetDefaultDialer();
                    break;
                case COMMAND_SET_SYSTEM_DIALER:
                    runSetSystemDialer();
                    break;
                case COMMAND_GET_SYSTEM_DIALER:
                    runGetSystemDialer();
                    break;
                case COMMAND_WAIT_ON_HANDLERS:
                    runWaitOnHandler();
                    break;
                case COMMAND_SET_SIM_COUNT:
                    runSetSimCount();
                    break;
                case COMMAND_GET_SIM_CONFIG:
                    runGetSimConfig();
                    break;
                case COMMAND_GET_MAX_PHONES:
                    runGetMaxPhones();
                    break;
                case COMMAND_IS_NON_IN_CALL_SERVICE_BOUND:
                    runIsNonUiInCallServiceBound();
                    break;
                case COMMAND_SET_TEST_EMERGENCY_PHONE_ACCOUNT_PACKAGE_FILTER:
                    runSetEmergencyPhoneAccountPackageFilter();
                    break;
                case COMMAND_LOG_MARK:
                    runLogMark();
                    break;
                default:
                    return handleDefaultCommands(command);
            }
        } catch (Exception e) {
            getErrPrintWriter().println("Command["+ command + "]: Error: " + e);
            return -1;
        }
        return 0;
    }

    @Override
    public void onHelp() {
        getOutPrintWriter().println("usage: telecom [subcommand] [options]\n"
                + "usage: telecom set-phone-account-enabled <COMPONENT> <ID> <USER_SN>\n"
                + "usage: telecom set-phone-account-disabled <COMPONENT> <ID> <USER_SN>\n"
                + "usage: telecom register-phone-account <COMPONENT> <ID> <USER_SN> <LABEL>\n"
                + "usage: telecom register-sim-phone-account [-e] <COMPONENT> <ID> <USER_SN>"
                + " <LABEL>: registers a PhoneAccount with CAPABILITY_SIM_SUBSCRIPTION"
                + " and optionally CAPABILITY_PLACE_EMERGENCY_CALLS if \"-e\" is provided\n"
                + "usage: telecom set-user-selected-outgoing-phone-account [-e] <COMPONENT> <ID> "
                + "<USER_SN>\n"
                + "usage: telecom set-test-call-redirection-app <PACKAGE>\n"
                + "usage: telecom set-test-call-screening-app <PACKAGE>\n"
                + "usage: telecom set-phone-acct-suggestion-component <COMPONENT>\n"
                + "usage: telecom add-or-remove-call-companion-app <PACKAGE> <1/0>\n"
                + "usage: telecom register-sim-phone-account <COMPONENT> <ID> <USER_SN>"
                + " <LABEL> <ADDRESS>\n"
                + "usage: telecom unregister-phone-account <COMPONENT> <ID> <USER_SN>\n"
                + "usage: telecom set-call-diagnostic-service <PACKAGE>\n"
                + "usage: telecom set-default-dialer <PACKAGE>\n"
                + "usage: telecom get-default-dialer\n"
                + "usage: telecom get-system-dialer\n"
                + "usage: telecom wait-on-handlers\n"
                + "usage: telecom set-sim-count <COUNT>\n"
                + "usage: telecom get-sim-config\n"
                + "usage: telecom get-max-phones\n"
                + "usage: telecom stop-block-suppression: Stop suppressing the blocked number"
                + " provider after a call to emergency services.\n"
                + "usage: telecom cleanup-stuck-calls: Clear any disconnected calls that have"
                + " gotten wedged in Telecom.\n"
                + "usage: telecom cleanup-orphan-phone-accounts: remove any phone accounts that"
                + " no longer have a valid UserHandle or accounts that no longer belongs to an"
                + " installed package.\n"
                + "usage: telecom set-emer-phone-account-filter <PACKAGE>\n"
                + "\n"
                + "telecom set-phone-account-enabled: Enables the given phone account, if it has"
                + " already been registered with Telecom.\n"
                + "\n"
                + "telecom set-phone-account-disabled: Disables the given phone account, if it"
                + " has already been registered with telecom.\n"
                + "\n"
                + "telecom set-call-diagnostic-service: overrides call diagnostic service.\n"
                + "telecom set-default-dialer: Sets the override default dialer to the given"
                + " component; this will override whatever the dialer role is set to.\n"
                + "\n"
                + "telecom get-default-dialer: Displays the current default dialer.\n"
                + "\n"
                + "telecom get-system-dialer: Displays the current system dialer.\n"
                + "telecom set-system-dialer: Set the override system dialer to the given"
                + " component. To remove the override, send \"default\"\n"
                + "\n"
                + "telecom wait-on-handlers: Wait until all handlers finish their work.\n"
                + "\n"
                + "telecom set-sim-count: Set num SIMs (2 for DSDS, 1 for single SIM."
                + " This may restart the device.\n"
                + "\n"
                + "telecom get-sim-config: Get the mSIM config string. \"DSDS\" for DSDS mode,"
                + " or \"\" for single SIM\n"
                + "\n"
                + "telecom get-max-phones: Get the max supported phones from the modem.\n"
                + "telecom set-test-emergency-phone-account-package-filter <PACKAGE>: sets a"
                + " package name that will be used for test emergency calls. To clear,"
                + " send an empty package name. Real emergency calls will still be placed"
                + " over Telephony.\n"
                + "telecom log-mark <MESSAGE>: emits a message into the telecom logs.  Useful for "
                + "testers to indicate where in the logs various test steps take place.\n"
                + "telecom is-non-ui-in-call-service-bound <PACKAGE>: queries a particular "
                + "non-ui-InCallService in InCallController to determine if it is bound \n"
        );
    }
    private void runSetPhoneAccountEnabled(boolean enabled) throws RemoteException {
        final PhoneAccountHandle handle = getPhoneAccountHandleFromArgs();
        final boolean success =  mTelecomService.enablePhoneAccount(handle, enabled);
        if (success) {
            getOutPrintWriter().println("Success - " + handle
                    + (enabled ? " enabled." : " disabled."));
        } else {
            getOutPrintWriter().println("Error - is " + handle + " a valid PhoneAccount?");
        }
    }

    private void runRegisterPhoneAccount() throws RemoteException {
        final PhoneAccountHandle handle = getPhoneAccountHandleFromArgs();
        final String label = getNextArgRequired();
        PhoneAccount account = PhoneAccount.builder(handle, label)
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER).build();
        mTelecomService.registerPhoneAccount(account, CALLING_PACKAGE);
        getOutPrintWriter().println("Success - " + handle + " registered.");
    }

    private void runRegisterSimPhoneAccount() throws RemoteException {
        boolean isEmergencyAccount = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-e": {
                    isEmergencyAccount = true;
                    break;
                }
            }
        }
        final PhoneAccountHandle handle = getPhoneAccountHandleFromArgs();
        final String label = getNextArgRequired();
        final String address = getNextArgRequired();
        int capabilities = PhoneAccount.CAPABILITY_CALL_PROVIDER
                | PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION
                | (isEmergencyAccount ? PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS : 0);
        PhoneAccount account = PhoneAccount.builder(
                        handle, label)
                .setAddress(Uri.parse(address))
                .setSubscriptionAddress(Uri.parse(address))
                .setCapabilities(capabilities)
                .setShortDescription(label)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .addSupportedUriScheme(PhoneAccount.SCHEME_VOICEMAIL)
                .build();
        mTelecomService.registerPhoneAccount(account, CALLING_PACKAGE);
        getOutPrintWriter().println("Success - " + handle + " registered.");
    }

    private void runSetTestCallRedirectionApp() throws RemoteException {
        final String packageName = getNextArg();
        mTelecomService.setTestDefaultCallRedirectionApp(packageName);
    }

    private void runSetTestCallScreeningApp() throws RemoteException {
        final String packageName = getNextArg();
        mTelecomService.setTestDefaultCallScreeningApp(packageName);
    }

    private void runAddOrRemoveCallCompanionApp() throws RemoteException {
        final String packageName = getNextArgRequired();
        String isAdded = getNextArgRequired();
        boolean isAddedBool = "1".equals(isAdded);
        mTelecomService.addOrRemoveTestCallCompanionApp(packageName, isAddedBool);
    }

    private void runSetCallDiagnosticService() throws RemoteException {
        String packageName = getNextArg();
        if ("default".equals(packageName)) packageName = null;
        mTelecomService.setTestCallDiagnosticService(packageName);
        getOutPrintWriter().println("Success - " + packageName
                + " set as call diagnostic service.");
    }

    private void runSetTestPhoneAcctSuggestionComponent() throws RemoteException {
        final String componentName = getNextArg();
        final UserHandle userHandle = getUserHandleFromArgs();
        mTelecomService.setTestPhoneAcctSuggestionComponent(componentName, userHandle);
    }

    private void runSetUserSelectedOutgoingPhoneAccount() throws RemoteException {
        Log.i(this, "runSetUserSelectedOutgoingPhoneAccount");
        final PhoneAccountHandle handle = getPhoneAccountHandleFromArgs();
        mTelecomService.setUserSelectedOutgoingPhoneAccount(handle);
        getOutPrintWriter().println("Success - " + handle + " set as default outgoing account.");
    }

    private void runUnregisterPhoneAccount() throws RemoteException {
        final PhoneAccountHandle handle = getPhoneAccountHandleFromArgs();
        mTelecomService.unregisterPhoneAccount(handle, CALLING_PACKAGE);
        getOutPrintWriter().println("Success - " + handle + " unregistered.");
    }

    private void runStopBlockSuppression() throws RemoteException {
        mTelecomService.stopBlockSuppression();
    }

    private void runCleanupStuckCalls() throws RemoteException {
        mTelecomService.cleanupStuckCalls();
    }

    private void runCleanupOrphanPhoneAccounts() throws RemoteException {
        getOutPrintWriter().println("Success - cleaned up "
                + mTelecomService.cleanupOrphanPhoneAccounts()
                + "  phone accounts.");
    }

    private void runResetCarMode() throws RemoteException {
        mTelecomService.resetCarMode();
    }

    private void runSetDefaultDialer() throws RemoteException {
        String packageName = getNextArg();
        if ("default".equals(packageName)) packageName = null;
        mTelecomService.setTestDefaultDialer(packageName);
        getOutPrintWriter().println("Success - " + packageName
                + " set as override default dialer.");
    }

    private void runSetSystemDialer() throws RemoteException {
        final String flatComponentName = getNextArg();
        final ComponentName componentName = (flatComponentName.equals("default")
                ? null : parseComponentName(flatComponentName));
        mTelecomService.setSystemDialer(componentName);
        getOutPrintWriter().println("Success - " + componentName + " set as override system dialer.");
    }

    private void runGetDefaultDialer() throws RemoteException {
        getOutPrintWriter().println(mTelecomService.getDefaultDialerPackage(CALLING_PACKAGE));
    }

    private void runGetSystemDialer() throws RemoteException {
        getOutPrintWriter().println(mTelecomService.getSystemDialerPackage(CALLING_PACKAGE));
    }

    private void runWaitOnHandler() throws RemoteException {

    }

    private void runSetSimCount() throws RemoteException {
        if (!callerIsRoot()) {
            getOutPrintWriter().println("set-sim-count requires adb root");
            return;
        }
        int numSims = Integer.parseInt(getNextArgRequired());
        getOutPrintWriter().println("Setting sim count to " + numSims + ". Device may reboot");
        getTelephonyManager().switchMultiSimConfig(numSims);
    }

    /**
     * prints out whether a particular non-ui InCallServices is bound in a call
     */
    public void runIsNonUiInCallServiceBound() throws RemoteException {
        if (TextUtils.isEmpty(peekNextArg())) {
            getOutPrintWriter().println("No Argument passed. Please pass a <PACKAGE_NAME> to "
                    + "lookup.");
        } else {
            getOutPrintWriter().println(
                    String.valueOf(mTelecomService.isNonUiInCallServiceBound(getNextArg())));
        }
    }

    /**
     * Prints the mSIM config to the console.
     * "DSDS" for a phone in DSDS mode
     * "" (empty string) for a phone in SS mode
     */
    private void runGetSimConfig() throws RemoteException {
        getOutPrintWriter().println(TelephonyProperties.multi_sim_config().orElse(""));
    }

    private void runGetMaxPhones() throws RemoteException {
        // how many logical modems can be potentially active simultaneously
        getOutPrintWriter().println(getTelephonyManager().getSupportedModemCount());
    }

    private void runSetEmergencyPhoneAccountPackageFilter() throws RemoteException {
        String packageName = getNextArg();
        if (TextUtils.isEmpty(packageName)) {
            mTelecomService.setTestEmergencyPhoneAccountPackageNameFilter(null);
            getOutPrintWriter().println("Success - filter cleared");
        } else {
            mTelecomService.setTestEmergencyPhoneAccountPackageNameFilter(packageName);
            getOutPrintWriter().println("Success = filter set to " + packageName);
        }

    }

    private void runLogMark() throws RemoteException {
        String message = Arrays.stream(peekRemainingArgs()).collect(Collectors.joining(" "));
        mTelecomService.requestLogMark(message);
    }

    private UserHandle getUserHandleFromArgs() throws RemoteException {
        if (TextUtils.isEmpty(peekNextArg())) {
            return null;
        }
        final String userSnInStr = getNextArgRequired();
        UserHandle userHandle;
        try {
            final int userSn = Integer.parseInt(userSnInStr);
            userHandle = UserHandle.of(getUserManager().getUserHandle(userSn));
        } catch (NumberFormatException ex) {
            Log.w(this, "getPhoneAccountHandleFromArgs - invalid user %s", userSnInStr);
            throw new IllegalArgumentException ("Invalid user serial number " + userSnInStr);
        }
        return userHandle;
    }

    private PhoneAccountHandle getPhoneAccountHandleFromArgs() throws RemoteException {
        if (TextUtils.isEmpty(peekNextArg())) {
            return null;
        }
        final ComponentName component = parseComponentName(getNextArgRequired());
        final String accountId = getNextArgRequired();
        final String userSnInStr = getNextArgRequired();
        UserHandle userHandle;
        try {
            final int userSn = Integer.parseInt(userSnInStr);
            userHandle = UserHandle.of(getUserManager().getUserHandle(userSn));
        } catch (NumberFormatException ex) {
            Log.w(this, "getPhoneAccountHandleFromArgs - invalid user %s", userSnInStr);
            throw new IllegalArgumentException ("Invalid user serial number " + userSnInStr);
        }
        return new PhoneAccountHandle(component, accountId, userHandle);
    }

    private boolean callerIsRoot() {
        return Process.ROOT_UID == Process.myUid();
    }

    private ComponentName parseComponentName(String component) {
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) {
            throw new IllegalArgumentException ("Invalid component " + component);
        }
        return cn;
    }

    private TelephonyManager getTelephonyManager() throws IllegalStateException {
        if (mTelephonyManager == null) {
            mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        }
        if (mTelephonyManager == null) {
            Log.w(this, "getTelephonyManager: Can't access telephony service.");
            throw new IllegalStateException("Could not access the Telephony Service. Is the system "
                    + "running?");
        }
        return mTelephonyManager;
    }

    private UserManager getUserManager() throws IllegalStateException {
        if (mUserManager == null) {
            mUserManager = mContext.getSystemService(UserManager.class);
        }
        if (mUserManager == null) {
            Log.w(this, "getUserManager: Can't access UserManager service.");
            throw new IllegalStateException("Could not access the UserManager Service. Is the "
                    + "system running?");
        }
        return mUserManager;
    }
}
