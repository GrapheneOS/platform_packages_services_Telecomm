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

package com.android.server.telecom.util;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.ContactsContract.RawContacts;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Locale;

/**
 * Looks up caller information for the given phone number.
 */
public class CallerInfo {

    public static final long USER_TYPE_CURRENT = 0;
    public static final long USER_TYPE_WORK = 1;
    private static final String TAG = "CallerInfo";
    private static final boolean VDBG = android.util.Log.isLoggable(TAG, Log.VERBOSE);
    public String normalizedNumber;
    public String cnapName;
    public int numberPresentation;
    public int namePresentation;
    public boolean contactExists;
    public String phoneLabel;
    /**
     * Split up the phoneLabel into number type and label name.
     */
    public int numberType;
    public String numberLabel;
    public int photoResource;
    public boolean needUpdate;
    public Uri contactRefUri;
    public String lookupKey;
    public ComponentName preferredPhoneAccountComponent;
    public String preferredPhoneAccountId;
    public long userType;
    // fields to hold individual contact preference data,
    // including the send to voicemail flag and the ringtone
    // uri reference.
    public Uri contactRingtoneUri;
    public boolean shouldSendToVoicemail;
    /**
     * Drawable representing the caller image.  This is essentially a cache for the image data tied
     * into the connection / callerinfo object.
     *
     * This might be a high resolution picture which is more suitable for full-screen image view
     * than for smaller icons used in some kinds of notifications.
     *
     * The {@link #isCachedPhotoCurrent} flag indicates if the image data needs to be reloaded.
     */
    public Drawable cachedPhoto;
    /**
     * Bitmap representing the caller image which has possibly lower resolution than
     * {@link #cachedPhoto} and thus more suitable for icons (like notification icons).
     *
     * In usual cases this is just down-scaled image of {@link #cachedPhoto}. If the down-scaling
     * fails, this will just become null.
     *
     * The {@link #isCachedPhotoCurrent} flag indicates if the image data needs to be reloaded.
     */
    public Bitmap cachedPhotoIcon;
    /**
     * Boolean which indicates if {@link #cachedPhoto} and {@link #cachedPhotoIcon} is fresh enough.
     * If it is false, those images aren't pointing to valid objects.
     */
    public boolean isCachedPhotoCurrent;
    /**
     * Please note that, any one of these member variables can be null, and any accesses to them
     * should be prepared to handle such a case.
     *
     * Also, it is implied that phoneNumber is more often populated than name is, (think of calls
     * being dialed/received using numbers where names are not known to the device), so phoneNumber
     * should serve as a dependable fallback when name is unavailable.
     *
     * One other detail here is that this CallerInfo object reflects information found on a
     * connection, it is an OUTPUT that serves mainly to display information to the user.  In no way
     * is this object used as input to make a connection, so we can choose to display whatever
     * human-readable text makes sense to the user for a connection.  This is especially relevant
     * for the phone number field, since it is the one field that is most likely exposed to the
     * user.
     *
     * As an example: 1. User dials "911" 2. Device recognizes that this is an emergency number 3.
     * We use the "Emergency Number" string instead of "911" in the phoneNumber field.
     *
     * What we're really doing here is treating phoneNumber as an essential field here, NOT name.
     * We're NOT always guaranteed to have a name for a connection, but the number should be
     * displayable.
     */
    private String name;
    private String phoneNumber;
    // Contact ID, which will be 0 if a contact comes from the corp CP2.
    private long contactIdOrZero;
    /**
     * Contact display photo URI.  If a contact has no display photo but a thumbnail, it'll be the
     * thumbnail URI instead.
     */
    private Uri contactDisplayPhotoUri;
    private boolean mIsEmergency;
    private boolean mIsVoiceMail;

    public CallerInfo() {
        // TODO: Move all the basic initialization here?
        mIsEmergency = false;
        mIsVoiceMail = false;
        userType = USER_TYPE_CURRENT;
    }

    /**
     * getCallerInfo given a Cursor.
     *
     * @param context the context used to retrieve string constants
     * @param contactRef the URI to attach to this CallerInfo object
     * @param cursor the first object in the cursor is used to build the CallerInfo object.
     * @return the CallerInfo which contains the caller id for the given number. The returned
     *     CallerInfo is null if no number is supplied.
     */
    public static CallerInfo getCallerInfo(Context context, Uri contactRef, Cursor cursor) {
        CallerInfo info = new CallerInfo();
        info.photoResource = 0;
        info.phoneLabel = null;
        info.numberType = 0;
        info.numberLabel = null;
        info.cachedPhoto = null;
        info.isCachedPhotoCurrent = false;
        info.contactExists = false;
        info.userType = USER_TYPE_CURRENT;

        if (VDBG) {
            Log.v(TAG, "getCallerInfo() based on cursor...");
        }

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                // TODO: photo_id is always available but not taken
                // care of here. Maybe we should store it in the
                // CallerInfo object as well.

                int columnIndex;

                // Look for the name
                columnIndex = cursor.getColumnIndex(PhoneLookup.DISPLAY_NAME);
                if (columnIndex != -1) {
                    info.name = cursor.getString(columnIndex);
                }

                // Look for the number
                columnIndex = cursor.getColumnIndex(PhoneLookup.NUMBER);
                if (columnIndex != -1) {
                    info.phoneNumber = cursor.getString(columnIndex);
                }

                // Look for the normalized number
                columnIndex = cursor.getColumnIndex(PhoneLookup.NORMALIZED_NUMBER);
                if (columnIndex != -1) {
                    info.normalizedNumber = cursor.getString(columnIndex);
                }

                // Look for the label/type combo
                columnIndex = cursor.getColumnIndex(PhoneLookup.LABEL);
                if (columnIndex != -1) {
                    int typeColumnIndex = cursor.getColumnIndex(PhoneLookup.TYPE);
                    if (typeColumnIndex != -1) {
                        info.numberType = cursor.getInt(typeColumnIndex);
                        info.numberLabel = cursor.getString(columnIndex);
                        info.phoneLabel = Phone.getTypeLabel(context.getResources(),
                                info.numberType, info.numberLabel).toString();
                    }
                }

                // Look for the person_id.
                columnIndex = getColumnIndexForPersonId(contactRef, cursor);
                if (columnIndex != -1) {
                    final long contactId = cursor.getLong(columnIndex);
                    if (contactId != 0 && !Contacts.isEnterpriseContactId(contactId)) {
                        info.contactIdOrZero = contactId;
                        if (VDBG) {
                            Log.v(TAG, "==> got info.contactIdOrZero: " + info.contactIdOrZero);
                        }
                    }
                    if (Contacts.isEnterpriseContactId(contactId)) {
                        info.userType = USER_TYPE_WORK;
                    }
                } else {
                    // No valid columnIndex, so we can't look up person_id.
                    Log.w(TAG, "Couldn't find contact_id column for " + contactRef);
                    // Watch out: this means that anything that depends on
                    // person_id will be broken (like contact photo lookups in
                    // the in-call UI, for example.)
                }

                // Contact lookupKey
                columnIndex = cursor.getColumnIndex(PhoneLookup.LOOKUP_KEY);
                if (columnIndex != -1) {
                    info.lookupKey = cursor.getString(columnIndex);
                }

                // Display photo URI.
                columnIndex = cursor.getColumnIndex(PhoneLookup.PHOTO_URI);
                if ((columnIndex != -1) && (cursor.getString(columnIndex) != null)) {
                    info.contactDisplayPhotoUri = Uri.parse(cursor.getString(columnIndex));
                } else {
                    info.contactDisplayPhotoUri = null;
                }

                columnIndex = cursor.getColumnIndex(Data.PREFERRED_PHONE_ACCOUNT_COMPONENT_NAME);
                if ((columnIndex != -1) && (cursor.getString(columnIndex) != null)) {
                    info.preferredPhoneAccountComponent =
                        ComponentName.unflattenFromString(cursor.getString(columnIndex));
                }

                columnIndex = cursor.getColumnIndex(Data.PREFERRED_PHONE_ACCOUNT_ID);
                if ((columnIndex != -1) && (cursor.getString(columnIndex) != null)) {
                    info.preferredPhoneAccountId = cursor.getString(columnIndex);
                }

                // look for the custom ringtone, create from the string stored
                // in the database.
                // An empty string ("") in the database indicates a silent ringtone,
                // and we set contactRingtoneUri = Uri.EMPTY, so that no ringtone will be played.
                // {null} in the database indicates the default ringtone,
                // and we set contactRingtoneUri = null, so that default ringtone will be played.
                columnIndex = cursor.getColumnIndex(PhoneLookup.CUSTOM_RINGTONE);
                if ((columnIndex != -1) && (cursor.getString(columnIndex) != null)) {
                    if (TextUtils.isEmpty(cursor.getString(columnIndex))) {
                        info.contactRingtoneUri = Uri.EMPTY;
                    } else {
                        info.contactRingtoneUri = Uri.parse(cursor.getString(columnIndex));
                    }
                } else {
                    info.contactRingtoneUri = null;
                }

                // look for the send to voicemail flag, set it to true only
                // under certain circumstances.
                columnIndex = cursor.getColumnIndex(PhoneLookup.SEND_TO_VOICEMAIL);
                info.shouldSendToVoicemail = (columnIndex != -1) &&
                    ((cursor.getInt(columnIndex)) == 1);
                info.contactExists = true;
            }
            cursor.close();
            cursor = null;
        }

        info.needUpdate = false;
        info.name = normalize(info.name);
        info.contactRefUri = contactRef;

        return info;
    }

    /**
     * getCallerInfo given a URI, look up in the call-log database for the uri unique key.
     *
     * @param context the context used to get the ContentResolver
     * @param contactRef the URI used to lookup caller id
     * @return the CallerInfo which contains the caller id for the given number. The returned
     *     CallerInfo is null if no number is supplied.
     */
    public static CallerInfo getCallerInfo(Context context, Uri contactRef) {
        return getCallerInfo(context, contactRef, true);
    }

    private static CallerInfo getCallerInfo(Context context, Uri contactRef,
            boolean useForegroundUser) {
        CallerInfo info = null;
        ContentResolver cr = useForegroundUser
                ? CallerInfoAsyncQuery.getCurrentProfileContentResolver(context)
                : context.getContentResolver();
        if (cr != null) {
            try {
                info = getCallerInfo(context, contactRef,
                    cr.query(contactRef, null, null, null, null));
            } catch (RuntimeException re) {
                Log.e(TAG, "Error getting caller info." + re.toString());
            }
        }
        return info;
    }

    /**
     * getCallerInfo given a phone number, look up in the call-log database for the matching caller
     * id info.
     *
     * @param context the context used to get the ContentResolver
     * @param number the phone number used to lookup caller id
     * @return the CallerInfo which contains the caller id for the given number. The returned
     *     CallerInfo is null if no number is supplied. If a matching number is not found, then a
     *     generic caller info is returned, with all relevant fields empty or null.
     */
    public static CallerInfo getCallerInfo(Context context, String number) {
        if (VDBG) {
            Log.v(TAG, "getCallerInfo() based on number...");
        }

        int subId = SubscriptionManager.getDefaultSubscriptionId();
        return getCallerInfo(context, number, subId);
    }

    /**
     * getCallerInfo given a phone number and subscription, look up in the call-log database for the
     * matching caller id info.
     *
     * @param context the context used to get the ContentResolver
     * @param number the phone number used to lookup caller id
     * @param subId the subscription for checking for if voice mail number or not
     * @return the CallerInfo which contains the caller id for the given number. The returned
     *     CallerInfo is null if no number is supplied. If a matching number is not found, then a
     *     generic caller info is returned, with all relevant fields empty or null.
     */
    public static CallerInfo getCallerInfo(Context context, String number, int subId) {

        if (TextUtils.isEmpty(number)) {
            return null;
        }

        // Change the callerInfo number ONLY if it is an emergency number
        // or if it is the voicemail number.  If it is either, take a
        // shortcut and skip the query.
        try {
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            if (tm.isEmergencyNumber(number)) {
                return new CallerInfo().markAsEmergency(context);
            } else if (PhoneNumberUtils.isVoiceMailNumber(null, subId, number)) {
                return new CallerInfo().markAsVoiceMail(context, subId);
            }
        } catch (UnsupportedOperationException | IllegalStateException ex) {
            // Ignore; Telephony is not available or failed, so we cannot perform an emergency
            // number check.
        } catch (RuntimeException ex) {
            // Some other runtime exception occurred; don't crash but log.
            Log.e(TAG, "Error getting caller info.", ex);
        }

        Uri contactUri = Uri.withAppendedPath(PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI,
            Uri.encode(number));

        CallerInfo info = getCallerInfo(context, contactUri);
        info = doSecondaryLookupIfNecessary(context, number, info);

        // if no query results were returned with a viable number,
        // fill in the original number value we used to query with.
        if (TextUtils.isEmpty(info.phoneNumber)) {
            info.phoneNumber = number;
        }

        return info;
    }

    /**
     * Performs another lookup if previous lookup fails and it's a SIP call and the peer's username
     * is all numeric. Look up the username as it could be a PSTN number in the contact database.
     *
     * @param context the query context
     * @param number the original phone number, could be a SIP URI
     * @param previousResult the result of previous lookup
     * @return previousResult if it's not the case
     */
    static CallerInfo doSecondaryLookupIfNecessary(Context context,
         String number, CallerInfo previousResult) {
        return doSecondaryLookupIfNecessary(context, number, previousResult, true);
    }

    static CallerInfo doSecondaryLookupIfNecessary(Context context, String number,
            CallerInfo previousResult, boolean useForegroundUser) {
        if (!previousResult.contactExists
            && PhoneNumberUtils.isUriNumber(number)) {
            String username = PhoneNumberUtils.getUsernameFromUriNumber(number);
            if (PhoneNumberUtils.isGlobalPhoneNumber(username)) {
                previousResult = getCallerInfo(context,
                    Uri.withAppendedPath(PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI,
                        Uri.encode(username)), useForegroundUser);
            }
        }
        return previousResult;
    }

    private static String normalize(String s) {
        if (s == null || s.length() > 0) {
            return s;
        } else {
            return null;
        }
    }

    /**
     * Returns the column index to use to find the "person_id" field in the specified cursor, based
     * on the contact URI that was originally queried.
     *
     * This is a helper function for the getCallerInfo() method that takes a Cursor.  Looking up the
     * person_id is nontrivial (compared to all the other CallerInfo fields) since the column we
     * need to use depends on what query we originally ran.
     *
     * Watch out: be sure to not do any database access in this method, since it's run from the UI
     * thread (see comments below for more info.)
     *
     * @return the columnIndex to use (with cursor.getLong()) to get the person_id, or -1 if we
     *     couldn't figure out what column to use.
     *
     *         TODO: Add a unittest for this method.  (This is a little tricky to
     *         test, since we'll need a live contacts database to test against,
     *         preloaded with at least some phone numbers and SIP addresses.  And
     *         we'll probably have to hardcode the column indexes we expect, so
     *         the test might break whenever the contacts schema changes.  But we
     *         can at least make sure we handle all the URI patterns we claim to,
     *         and that the mime types match what we expect...)
     */
    private static int getColumnIndexForPersonId(Uri contactRef, Cursor cursor) {
        // TODO: This is pretty ugly now, see bug 2269240 for
        // more details. The column to use depends upon the type of URL:
        // - content://com.android.contacts/data/phones ==> use the "contact_id" column
        // - content://com.android.contacts/phone_lookup ==> use the "_ID" column
        // - content://com.android.contacts/data ==> use the "contact_id" column
        // If it's none of the above, we leave columnIndex=-1 which means
        // that the person_id field will be left unset.
        //
        // The logic here *used* to be based on the mime type of contactRef
        // (for example Phone.CONTENT_ITEM_TYPE would tell us to use the
        // RawContacts.CONTACT_ID column).  But looking up the mime type requires
        // a call to context.getContentResolver().getType(contactRef), which
        // isn't safe to do from the UI thread since it can cause an ANR if
        // the contacts provider is slow or blocked (like during a sync.)
        //
        // So instead, figure out the column to use for person_id by just
        // looking at the URI itself.

        if (VDBG) {
            Log.v(TAG, "- getColumnIndexForPersonId: contactRef URI = '"
                + contactRef + "'...");
        }
        // Warning: Do not enable the following logging (due to ANR risk.)
        // if (VDBG) Log.v(TAG, "- MIME type: "
        //                 + context.getContentResolver().getType(contactRef));

        String url = contactRef.toString();
        String columnName = null;
        if (url.startsWith("content://com.android.contacts/data/phones")) {
            // Direct lookup in the Phone table.
            // MIME type: Phone.CONTENT_ITEM_TYPE (= "vnd.android.cursor.item/phone_v2")
            if (VDBG) {
                Log.v(TAG, "'data/phones' URI; using RawContacts.CONTACT_ID");
            }
            columnName = RawContacts.CONTACT_ID;
        } else if (url.startsWith("content://com.android.contacts/data")) {
            // Direct lookup in the Data table.
            // MIME type: Data.CONTENT_TYPE (= "vnd.android.cursor.dir/data")
            if (VDBG) {
                Log.v(TAG, "'data' URI; using Data.CONTACT_ID");
            }
            // (Note Data.CONTACT_ID and RawContacts.CONTACT_ID are equivalent.)
            columnName = Data.CONTACT_ID;
        } else if (url.startsWith("content://com.android.contacts/phone_lookup")) {
            // Lookup in the PhoneLookup table, which provides "fuzzy matching"
            // for phone numbers.
            // MIME type: PhoneLookup.CONTENT_TYPE (= "vnd.android.cursor.dir/phone_lookup")
            if (VDBG) {
                Log.v(TAG, "'phone_lookup' URI; using PhoneLookup._ID");
            }
            columnName = PhoneLookup._ID;
        } else {
            Log.w(TAG, "Unexpected prefix for contactRef '" + url + "'");
        }
        int columnIndex = (columnName != null) ? cursor.getColumnIndex(columnName) : -1;
        if (VDBG) {
            Log.v(TAG, "==> Using column '" + columnName
                + "' (columnIndex = " + columnIndex + ") for person_id lookup...");
        }
        return columnIndex;
    }

    /**
     * @return The ISO 3166-1 two letters country code of the country the user is in.
     */
    private static String getCurrentCountryIso(Context context, Locale locale) {
        String countryIso = null;
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        if (tm != null) {
            countryIso = tm.getNetworkCountryIso().toUpperCase();
        }
        if (countryIso == null || countryIso.isEmpty()) {
            countryIso = locale.getCountry();
            Log.w(TAG, "No CountryDetector; falling back to countryIso based on locale: "
                + countryIso);
        }
        return countryIso;
    }

    protected static String getCurrentCountryIso(Context context) {
        return getCurrentCountryIso(context, Locale.getDefault());
    }

    /**
     * @return Name associated with this caller.
     */
    @Nullable
    public String getName() {
        return name;
    }

    /**
     * Set caller Info Name.
     *
     * @param name caller Info Name
     */
    public void setName(@Nullable String name) {
        this.name = name;
    }

    // Accessors

    /**
     * @return Phone number associated with this caller.
     */
    @Nullable
    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String number) {
        phoneNumber = number;
    }

    /**
     * @return Contact ID, which will be 0 if a contact comes from the corp Contacts Provider.
     */
    public long getContactId() {
        return contactIdOrZero;
    }

    /**
     * @return Contact display photo URI. If a contact has no display photo but a thumbnail, it'll
     *     the thumbnail URI instead.
     */
    @Nullable
    public Uri getContactDisplayPhotoUri() {
        return contactDisplayPhotoUri;
    }

    @VisibleForTesting
    public void SetContactDisplayPhotoUri(Uri photoUri) {
        contactDisplayPhotoUri = photoUri;
    }

    /**
     * @return true if the caller info is an emergency number.
     */
    public boolean isEmergencyNumber() {
        return mIsEmergency;
    }

    /**
     * @return true if the caller info is a voicemail number
     */
    public boolean isVoiceMailNumber() {
        return mIsVoiceMail;
    }

    /**
     * Mark this CallerInfo as an emergency call.
     *
     * @param context To lookup the localized 'Emergency Number' string.
     * @return this instance.
     */
    // TODO: Note we're setting the phone number here (refer to
    // javadoc comments at the top of CallerInfo class) to a localized
    // string 'Emergency Number'. This is pretty bad because we are
    // making UI work here instead of just packaging the data. We
    // should set the phone number to the dialed number and name to
    // 'Emergency Number' and let the UI make the decision about what
    // should be displayed.
    /* package */ CallerInfo markAsEmergency(Context context) {
        int resourceId = Resources.getSystem().getIdentifier(
                "emergency_call_dialog_number_for_display", "string", "android");
        if (resourceId != 0) {
            phoneNumber = context.getResources().getString(resourceId);
        }
        photoResource = Resources.getSystem().getIdentifier("picture_emergency", "drawable",
                "android");
        mIsEmergency = true;
        return this;
    }

    /* package */ CallerInfo markAsVoiceMail(Context context, int subId) {
        mIsVoiceMail = true;

        try {
            phoneNumber = context.getSystemService(TelephonyManager.class)
                .createForSubscriptionId(subId)
                .getVoiceMailAlphaTag();
        } catch (SecurityException se) {
            // Should never happen: if this process does not have
            // permission to retrieve VM tag, it should not have
            // permission to retrieve VM number and would not call
            // this method.
            // Leave phoneNumber untouched.
            Log.e(TAG, "Cannot access VoiceMail.");
        }
        // TODO: There is no voicemail picture?
        // FIXME: FIND ANOTHER ICON
        // photoResource = android.R.drawable.badge_voicemail;
        return this;
    }

    /**
     * @return a string debug representation of this instance.
     */
    @Override
    public String toString() {
        // Warning: never check in this file with VERBOSE_DEBUG = true
        // because that will result in PII in the system log.
        final boolean VERBOSE_DEBUG = false;

        if (VERBOSE_DEBUG) {
            return super.toString() + " { "
                + "\nname: " + name
                + "\nphoneNumber: " + phoneNumber
                + "\nnormalizedNumber: " + normalizedNumber
                + "\ncnapName: " + cnapName
                + "\nnumberPresentation: " + numberPresentation
                + "\nnamePresentation: " + namePresentation
                + "\ncontactExits: " + contactExists
                + "\nphoneLabel: " + phoneLabel
                + "\nnumberType: " + numberType
                + "\nnumberLabel: " + numberLabel
                + "\nphotoResource: " + photoResource
                + "\ncontactIdOrZero: " + contactIdOrZero
                + "\nneedUpdate: " + needUpdate
                + "\ncontactRingtoneUri: " + contactRingtoneUri
                + "\ncontactDisplayPhotoUri: " + contactDisplayPhotoUri
                + "\nshouldSendToVoicemail: " + shouldSendToVoicemail
                + "\ncachedPhoto: " + cachedPhoto
                + "\nisCachedPhotoCurrent: " + isCachedPhotoCurrent
                + "\nemergency: " + mIsEmergency
                + "\nvoicemail " + mIsVoiceMail
                + "\ncontactExists " + contactExists
                + "\nuserType " + userType
                + " }";
        } else {
            return super.toString() + " { "
                + "name " + ((name == null) ? "null" : "non-null")
                + ", phoneNumber " + ((phoneNumber == null) ? "null" : "non-null")
                + " }";
        }
    }
}
