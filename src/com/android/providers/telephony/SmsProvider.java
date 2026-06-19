/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.providers.telephony;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Contacts;
import android.provider.Telephony;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.ReadRestriction;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Threads;
import android.telephony.MessageUpgradeController;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.SmsApplication;
import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.telephony.metrics.ReadRestrictionStatsLogger;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.util.TelephonyUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SmsProvider extends ContentProvider {
    /* No response constant from SmsResponse */
    static final int NO_ERROR_CODE = -1;

    private static final Uri NOTIFICATION_URI = Uri.parse("content://sms");
    private static final Uri ICC_URI = Uri.parse("content://sms/icc");
    private static final Uri ICC_SUBID_URI = Uri.parse("content://sms/icc_subId");
    static final String TABLE_SMS = "sms";
    static final String TABLE_RAW = "raw";
    static final String TABLE_ATTACHMENTS = "attachments";
    static final String TABLE_CANONICAL_ADDRESSES = "canonical_addresses";
    static final String TABLE_SR_PENDING = "sr_pending";
    private static final String TABLE_WORDS = "words";
    /**
     * This view is a proxy for reading from the {@link #TABLE_SMS} table. It contains all the rows
     * in the {@link #TABLE_SMS} table.
     *
     * View is used here to enforce a uniform projection of columns across all queries:
     *  - {@link Sms#READ_RESTRICTION} is hidden from the selection.
     *  - {@link ReadRestriction#RESTRICTED} bit is extracted from the {@link Sms#READ_RESTRICTION}
     * column and exposed as a boolean (integer) field.
     */
    static final String VIEW_SMS_ALL = "sms_all";

    /**
     * This view is a proxy for reading from the {@link #TABLE_SMS} table.
     *
     * In comparison to the {@link #VIEW_SMS_ALL}, it is a restricted view which only contains sent
     * or received messages, without drafts.
     */
    static final String VIEW_SMS_RESTRICTED = "sms_restricted";
    /**
     * This is the list of columns in the {@link #TABLE_SMS} that are exposed in the queries via
     * {@link #VIEW_SMS_ALL} or {@link #VIEW_SMS_RESTRICTED}. {@link #TABLE_SMS} should not
     * be queried directly.
     */
    static final String[] SMS_SELECTION_COLUMNS = new String[] {
        Sms._ID,
        Sms.THREAD_ID,
        Sms.ADDRESS,
        Sms.PERSON,
        Sms.DATE,
        Sms.DATE_SENT,
        Sms.PROTOCOL,
        Sms.READ,
        Sms.STATUS,
        Sms.TYPE,
        Sms.REPLY_PATH_PRESENT,
        Sms.SUBJECT,
        Sms.BODY,
        Sms.SERVICE_CENTER,
        Sms.LOCKED,
        Sms.SUBSCRIPTION_ID,
        Sms.ERROR_CODE,
        Sms.CREATOR,
        Sms.SEEN,
        Sms.CONTAINS_OTP,
        Sms.TRANSACTION_ID,
        "CAST(CASE WHEN (" + Sms.READ_RESTRICTION + " & " +
                 ReadRestriction.ReadRestrictionValues.READ_RESTRICTION_RESTRICTED +
                 ") <> 0 THEN 1 ELSE 0 END AS INTEGER) AS restricted",
    };

    private static final Integer ONE = Integer.valueOf(1);

    private static final String[] CONTACT_QUERY_PROJECTION =
            new String[] { Contacts.Phones.PERSON_ID };
    private static final int PERSON_ID_COLUMN = 0;

    /** Delete any raw messages or message segments marked deleted that are older than an hour. */
    private static final long RAW_MESSAGE_EXPIRE_AGE_MS = TimeUnit.HOURS.toMillis(1);

    /**
     * These are the columns that are available when reading SMS
     * messages from the ICC.  Columns whose names begin with "is_"
     * have either "true" or "false" as their values.
     */
    private static final String[] ICC_COLUMNS = new String[] {
        // N.B.: These columns must appear in the same order as the
        // calls to add appear in convertIccToSms.
        "service_center_address",       // getServiceCenterAddress
        "address",                      // getDisplayOriginatingAddress or getRecipientAddress
        "message_class",                // getMessageClass
        "body",                         // getDisplayMessageBody
        "date",                         // getTimestampMillis
        "status",                       // getStatusOnIcc
        "index_on_icc",                 // getIndexOnIcc (1-based index)
        "is_status_report",             // isStatusReportMessage
        "transport_type",               // Always "sms".
        "type",                         // depend on getStatusOnIcc
        "locked",                       // Always 0 (false).
        "error_code",                   // Always -1 (NO_ERROR_CODE), previously it was 0 always.
        "_id"
    };
    private static final TextClassifier.EntityConfig TC_REQUEST_CONFIG =
            new TextClassifier.EntityConfig.Builder()
                    .setIncludedTypes(getIncludedTextClassifierTypes())
                    .includeTypesFromTextClassifier(false)
                    .build();

    @VisibleForTesting
    protected static final int MAX_DB_UPDATE_ATTEMPTS = 3;
    private static final long DB_UPDATE_RETRY_DELAY_MS = 100;

    private static List<String> getIncludedTextClassifierTypes() {
      ArrayList<String> includedTypes = new ArrayList();
      includedTypes.add(TextClassifier.TYPE_SMS_RETRIEVER_OTP);
      if (android.view.flags.Flags.redactWebOtpSmsApi()) {
          includedTypes.add(TextClassifier.TYPE_SMS_WEB_OTP);
      }
      if (android.view.flags.Flags.redactOtpAppCompatApi()) {
          includedTypes.add(TextClassifier.TYPE_OTP);
      }
      return includedTypes;
    }

    private final List<UserHandle> mUsersRemovedBeforeUnlockList = new ArrayList<>();

    private final Executor mBackgroundExecutor = Executors.newSingleThreadExecutor();

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    @VisibleForTesting
    protected TextClassifier mTextClassifier;

    @Override
    public boolean onCreate() {
        setAppOps(AppOpsManager.OP_READ_SMS, AppOpsManager.OP_WRITE_SMS);
        // So we have two database files. One in de, one in ce. Here only "raw" table is in
        // mDeOpenHelper, other tables are all in mCeOpenHelper.
        mDeOpenHelper = MmsSmsDatabaseHelper.getInstanceForDe(getContext());
        mCeOpenHelper = MmsSmsDatabaseHelper.getInstanceForCe(getContext());
        TelephonyBackupAgent.DeferredSmsMmsRestoreService.startIfFilesExist(getContext());

        // Creating intent broadcast receiver for user actions like Intent.ACTION_USER_REMOVED,
        // where we would need to remove SMS related to removed user.
        IntentFilter userIntentFilter = new IntentFilter();
        userIntentFilter.addAction(Intent.ACTION_USER_REMOVED);
        userIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        getContext().registerReceiver(mUserIntentReceiver, userIntentFilter,
                Context.RECEIVER_NOT_EXPORTED);
        mTextClassifier = getContext().getSystemService(TextClassificationManager.class)
                .getTextClassifier(TextClassifier.CLASSIFIER_TYPE_ANDROID_DEFAULT);

        return true;
    }

    private boolean hasCalling() {
        return getContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_CALLING);
    }

    /**
     * Return the proper view of "sms" table for the current access status.
     *
     * @param accessRestricted If the access is restricted
     * @return the table/view name of the "sms" data
     */
    public static String getSmsTable(boolean accessRestricted) {
        if (Flags.secureAccessToRestrictedRcsMessages()) {
            return accessRestricted ? VIEW_SMS_RESTRICTED : VIEW_SMS_ALL;
        }
        return accessRestricted ? VIEW_SMS_RESTRICTED : TABLE_SMS;
    }

    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        long startTime = SystemClock.elapsedRealtime();
        Cursor cursor = null;
        try {
            cursor = queryInternal(url, projectionIn, selection, selectionArgs, sort);
            int count = 0;
            if (cursor != null) {
                count = cursor.getCount(); // Force evaluation
            }
            ProviderMetricsLogger.logOperationLatency(
                    getContext(),
                    ProviderMetricsLogger.OPERATION_QUERY,
                    ProviderMetricsLogger.TARGET_URI_SMS,
                    startTime,
                    count);
        } catch (SQLiteDatabaseLockedException e) { // Lock
            ProviderMetricsLogger.logDbLockContention(getContext(),
                    ProviderMetricsLogger.OPERATION_QUERY, ProviderMetricsLogger.TARGET_URI_SMS);
            throw e;
        } catch (Exception e) {
            Log.e("ProviderMetrics", "Database operation failed", e);
            ProviderUtil.logRunningTelephonyProviderProcesses(getContext());
            throw e;
        }
        return cursor;
    }

    /** Internal implementation of the database operation. */
    public Cursor queryInternal(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        String callingPackage = getCallingPackage();
        final int callingUid = Binder.getCallingUid();
        final UserHandle callerUserHandle = Binder.getCallingUserHandle();

        // First check if a restricted view of the "sms" table should be used based on the
        // caller's identity. Only system, phone or the default sms app can have full access
        // of sms data. For other apps, we present a restricted view which only contains sent
        // or received messages.
        final boolean accessRestricted =  ProviderUtil.isAccessRestricted(
                getContext(), getCallingPackage(), callingUid);
        final String smsTable = getSmsTable(accessRestricted);
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        if (accessRestricted) {
            // Enable strict mode to validate columns against projection map and prevent SQL
            // injection in WHERE clauses.
            qb.setStrict(true);
            // Enable strict grammar check to validate SQL syntax and prevent syntax-based
            // injections (e.g. mismatched parentheses).
            qb.setStrictGrammar(true);
        }

        // If access is restricted, we don't allow subqueries in the query.
        if (accessRestricted) {
            try {
                SqlQueryChecker.checkQueryParametersForSubqueries(projectionIn, selection, sort);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Query rejected: " + e.getMessage());
                return null;
            }
        }

        if (Flags.secureAccessToRestrictedRcsMessages()) {
            SqlQueryChecker.checkQueryForForbiddenColumns(projectionIn, selection, sort, TAG);
        }

        final boolean canReadRestrictedMessages = ProviderUtil.canReadRestrictedMessages(
                getContext(), getCallingPackage(), callingUid);
        Log.v(TAG, "canReadRestrictedMessages=" + canReadRestrictedMessages);

        try {
            SqlQueryChecker.checkSelection(selection);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Query rejected: " + e.getMessage());
            return null;
        }

        Cursor emptyCursor = new MatrixCursor((projectionIn == null) ?
                (new String[] {}) : projectionIn);

        // Generate the body of the query.
        int match = sURLMatcher.match(url);

        if (Flags.secureAccessToRestrictedRcsMessages()
                && isAccessingPotentiallyRestrictedMessages(match)) {
            ReadRestrictionStatsLogger.getInstance().onRestrictedMessagesQueried(
                    ReadRestrictionStatsLogger.ContentProvider.SMS, callingUid,
                    canReadRestrictedMessages);
        }

        SQLiteDatabase db = getReadableDatabase(match);
        SQLiteOpenHelper sqLiteOpenHelper = getDBOpenHelper(match);
        if (sqLiteOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) sqLiteOpenHelper).addDatabaseOpeningDebugLog(
                    callingPackage + ";SmsProvider.query;" + url, true);
        }
        switch (match) {
            case SMS_ALL:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_ALL, smsTable, canReadRestrictedMessages);
                break;

            case SMS_UNDELIVERED:
                constructQueryForUndelivered(qb, smsTable, canReadRestrictedMessages);
                break;

            case SMS_FAILED:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_FAILED, smsTable,
                        canReadRestrictedMessages);
                break;

            case SMS_QUEUED:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_QUEUED, smsTable,
                        canReadRestrictedMessages);
                break;

            case SMS_INBOX:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_INBOX, smsTable,
                        canReadRestrictedMessages);
                break;

            case SMS_SENT:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_SENT, smsTable,
                        canReadRestrictedMessages);
                break;

            case SMS_DRAFT:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_DRAFT, smsTable,
                        canReadRestrictedMessages);
                break;

            case SMS_OUTBOX:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_OUTBOX, smsTable,
                        canReadRestrictedMessages);
                break;

            case SMS_ALL_ID:
                constructQueryForAllSms(qb, smsTable, canReadRestrictedMessages);
                appendWhere(qb, "(_id = " + url.getPathSegments().get(0) + ")");
                break;

            case SMS_INBOX_ID:
            case SMS_FAILED_ID:
            case SMS_SENT_ID:
            case SMS_DRAFT_ID:
            case SMS_OUTBOX_ID:
                constructQueryForAllSms(qb, smsTable, canReadRestrictedMessages);;
                appendWhere(qb, "(_id = " + url.getPathSegments().get(1) + ")");
                break;

            case SMS_CONVERSATIONS_ID:
                int threadID;

                try {
                    threadID = Integer.parseInt(url.getPathSegments().get(1));
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.d(TAG, "query conversations: threadID=" + threadID);
                    }
                }
                catch (Exception ex) {
                    Log.e(TAG,
                          "Bad conversation thread id: "
                          + url.getPathSegments().get(1));
                    return null;
                }

                qb.setTables(smsTable);
                appendWhere(qb, "thread_id = " + threadID);
                break;

            case SMS_CONVERSATIONS:
                qb.setTables(smsTable + ", "
                        + "(SELECT thread_id AS group_thread_id, "
                        + "MAX(date) AS group_date, "
                        + "COUNT(*) AS msg_count "
                        + "FROM " + smsTable + " "
                        + "GROUP BY thread_id) AS groups");
                appendWhere(qb, smsTable + ".thread_id=groups.group_thread_id"
                        + " AND " + smsTable + ".date=groups.group_date");
                final HashMap<String, String> projectionMap = new HashMap<>();
                projectionMap.put(Sms.Conversations.SNIPPET,
                        smsTable + ".body AS snippet");
                projectionMap.put(Sms.Conversations.THREAD_ID,
                        smsTable + ".thread_id AS thread_id");
                projectionMap.put(Sms.Conversations.MESSAGE_COUNT,
                        "groups.msg_count AS msg_count");
                projectionMap.put("delta", null);
                qb.setProjectionMap(projectionMap);
                ReadRestriction.appendRestrictedToQuery(qb, smsTable, canReadRestrictedMessages);
                break;

            case SMS_RAW_MESSAGE:
                if (!canReadRawTable(callingUid, callingPackage)) {
                    // Only the phone, system server, and default SMS app can query the raw table
                    return null;
                }
                // before querying purge old entries with deleted = 1
                purgeDeletedMessagesInRawTable(db);
                qb.setTables("raw");
                break;

            case SMS_STATUS_PENDING:
                qb.setTables("sr_pending");
                break;

            case SMS_ATTACHMENT:
                constructQueryForAttachments(qb, smsTable, canReadRestrictedMessages);
                break;

            case SMS_ATTACHMENT_ID:
                constructQueryForAttachments(qb, smsTable, canReadRestrictedMessages);
                appendWhere(qb, "(sms_id = " + url.getPathSegments().get(1) + ")");
                break;

            case SMS_QUERY_THREAD_ID:
                constructQueryForCanonicalAddresses(qb, canReadRestrictedMessages);
                if (projectionIn == null) {
                    projectionIn = sIDProjection;
                }
                break;

            case SMS_STATUS_ID:
                constructQueryForAllSms(qb, smsTable, canReadRestrictedMessages);
                appendWhere(qb, "(_id = " + url.getPathSegments().get(1) + ")");
                break;

            case SMS_ALL_ICC:
            case SMS_ALL_ICC_SUBID: {
                int subId;
                if (match == SMS_ALL_ICC) {
                    subId = SmsManager.getDefaultSmsSubscriptionId();
                } else {
                    try {
                        subId = Integer.parseInt(url.getPathSegments().get(1));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Wrong path segements, uri= " + url);
                    }
                }

                if (!ProviderUtil.allowInteractingWithEntryOfSubscription(getContext(),
                        subId, callerUserHandle)) {
                    // If subId is not associated with user, return empty cursor.
                    return emptyCursor;
                }

                Cursor ret = getAllMessagesFromIcc(subId);
                ret.setNotificationUri(getContext().getContentResolver(),
                        match == SMS_ALL_ICC ? ICC_URI : ICC_SUBID_URI);
                return ret;
            }

            case SMS_ICC:
            case SMS_ICC_SUBID: {
                int subId;
                int messageIndex;
                try {
                    if (match == SMS_ICC) {
                        subId = SmsManager.getDefaultSmsSubscriptionId();
                        messageIndex = Integer.parseInt(url.getPathSegments().get(1));
                    } else {
                        subId = Integer.parseInt(url.getPathSegments().get(1));
                        messageIndex = Integer.parseInt(url.getPathSegments().get(2));
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Wrong path segements, uri= " + url);
                }

                if (!ProviderUtil.allowInteractingWithEntryOfSubscription(getContext(),
                        subId, callerUserHandle)) {
                    // If subId is not associated with user, return empty cursor.
                    return emptyCursor;
                }

                Cursor ret = getSingleMessageFromIcc(subId, messageIndex);
                ret.setNotificationUri(getContext().getContentResolver(),
                        match == SMS_ICC ? ICC_URI : ICC_SUBID_URI);
                return ret;
            }

            default:
                Log.e(TAG, "Invalid request: " + url);
                return null;
        }

        final long token = Binder.clearCallingIdentity();
        String selectionBySubIds = null;
        String selectionByEmergencyNumbers = null;
        try {
            // Filter SMS based on subId and emergency numbers.
            selectionBySubIds = ProviderUtil.getSelectionBySubIds(getContext(),
                    callerUserHandle, getFirstTableName(qb));
            if (hasCalling() && qb.getTables().equals(smsTable)) {
                selectionByEmergencyNumbers = ProviderUtil
                        .getSelectionByEmergencyNumbers(getContext());
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        if (qb.getTables().equals(smsTable)) {
            if (selectionBySubIds == null && selectionByEmergencyNumbers == null) {
                // No subscriptions associated with user
                // and no emergency numbers return empty cursor.
                return emptyCursor;
            }
        } else {
            if (selectionBySubIds == null) {
                // No subscriptions associated with user return empty cursor.
                return emptyCursor;
            }
        }

        String filter = "";
        if (selectionBySubIds != null && selectionByEmergencyNumbers != null) {
            filter = (selectionBySubIds + " OR " + selectionByEmergencyNumbers);
        } else {
            filter = selectionBySubIds == null ?
                    selectionByEmergencyNumbers : selectionBySubIds;
        }
        if (!TextUtils.isEmpty(filter)) {
            // Use appendWhereStandalone to safely append the subId/emergency filter.
            // This automatically wraps the filter in parentheses, preventing the caller's
            // selection from escaping its scope and bypassing the filter.
            qb.appendWhereStandalone(filter);
        }

        String orderBy = null;

        if (!TextUtils.isEmpty(sort)) {
            orderBy = sort;
        } else if (qb.getTables().equals(smsTable)) {
            orderBy = Sms.DEFAULT_SORT_ORDER;
        }

        try {
            Trace.beginSection("SmsProvider_query_otpSection");
            if (Telephony.Sms.isOtpRedactionEnabled(getContext())
                    && qb.getTables().startsWith(smsTable)
                    && !canReadOtpSms(callingUid, callingPackage)) {
                qb.appendWhereStandalone(ProviderUtil.getOtpWhereFilter(getContext(),
                        callingPackage, callerUserHandle));
            }

            Cursor ret = qb.query(db, projectionIn, selection, selectionArgs,
                    null, null, orderBy);
            // TODO: Since the URLs are a mess, always use content://sms
            ret.setNotificationUri(getContext().getContentResolver(),
                    NOTIFICATION_URI);
            return ret;
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Returns the first table name in the query. This is used to disambiguate the sub_id column
     * name when two tables are joined.
     */
    private static String getFirstTableName(SQLiteQueryBuilder qb) {
        return qb.getTables().split("[, ]+")[0];
    }

    protected boolean canReadRawTable(int uid, String packageName) {
        return TelephonyPermissions.isSystemOrPhone(uid)
                || SmsApplication.isDefaultSmsApplication(getContext(), packageName);
    }

    private void purgeDeletedMessagesInRawTable(SQLiteDatabase db) {
        long oldTimestamp = System.currentTimeMillis() - RAW_MESSAGE_EXPIRE_AGE_MS;
        int num = db.delete(TABLE_RAW, "deleted = 1 AND date < " + oldTimestamp, null);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "purgeDeletedMessagesInRawTable: num rows older than " + oldTimestamp +
                    " purged: " + num);
        }
    }

    private static void appendWhere(SQLiteQueryBuilder qb, String where) {
        if (Flags.secureAccessToRestrictedRcsMessages()) {
            qb.appendWhereStandalone(where);
        } else {
            qb.appendWhere(where);
        }
    }

    private SQLiteOpenHelper getDBOpenHelper(int match) {
        // Raw table is stored on de database. Other tables are stored in ce database.
        if (match == SMS_RAW_MESSAGE || match == SMS_RAW_MESSAGE_PERMANENT_DELETE) {
            return mDeOpenHelper;
        }
        return mCeOpenHelper;
    }

    private Object[] convertIccToSms(SmsMessage message, int id) {
        int statusOnIcc = message.getStatusOnIcc();
        int type = Sms.MESSAGE_TYPE_ALL;
        switch (statusOnIcc) {
            case SmsManager.STATUS_ON_ICC_READ:
            case SmsManager.STATUS_ON_ICC_UNREAD:
                type = Sms.MESSAGE_TYPE_INBOX;
                break;
            case SmsManager.STATUS_ON_ICC_SENT:
                type = Sms.MESSAGE_TYPE_SENT;
                break;
            case SmsManager.STATUS_ON_ICC_UNSENT:
                type = Sms.MESSAGE_TYPE_OUTBOX;
                break;
        }

        String address = (type == Sms.MESSAGE_TYPE_INBOX)
                ? message.getDisplayOriginatingAddress()
                : message.getRecipientAddress();

        int index = message.getIndexOnIcc();
        if (address == null) {
            // The status byte of an EF_SMS record may not be correct. try to read other address
            // type again.
            Log.e(TAG, "convertIccToSms: EF_SMS(" + index + ")=> address=null, type=" + type
                    + ", status=" + statusOnIcc + "(may not be correct). fallback to other type.");
            address = (type == Sms.MESSAGE_TYPE_INBOX)
                    ? message.getRecipientAddress()
                    : message.getDisplayOriginatingAddress();

            if (address != null) {
                // Rely on actual PDU(address) to set type again.
                type = (type == Sms.MESSAGE_TYPE_INBOX)
                        ? Sms.MESSAGE_TYPE_SENT
                        : Sms.MESSAGE_TYPE_INBOX;
                Log.d(TAG, "convertIccToSms: new type=" + type + ", address=xxxxxx");
            } else {
                Log.e(TAG, "convertIccToSms: no change");
            }
        }

        // N.B.: These calls must appear in the same order as the
        // columns appear in ICC_COLUMNS.
        Object[] row = new Object[13];
        row[0] = message.getServiceCenterAddress();
        row[1] = address;
        row[2] = String.valueOf(message.getMessageClass());
        row[3] = message.getDisplayMessageBody();
        row[4] = message.getTimestampMillis();
        row[5] = statusOnIcc;
        row[6] = index;
        row[7] = message.isStatusReportMessage();
        row[8] = "sms";
        row[9] = type;
        row[10] = 0;      // locked
        row[11] = NO_ERROR_CODE;
        row[12] = id;
        return row;
    }

    /**
     * Gets single message from the ICC for a subscription ID.
     *
     * @param subId the subscription ID.
     * @param messageIndex the message index of the messaage in the ICC (1-based index).
     * @return a cursor containing just one message from the ICC for the subscription ID.
     */
    private Cursor getSingleMessageFromIcc(int subId, int messageIndex) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid Subscription ID " + subId);
        }
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
        List<SmsMessage> messages;

        // Use phone app permissions to avoid UID mismatch in AppOpsManager.noteOp() call.
        long token = Binder.clearCallingIdentity();
        try {
            // getMessagesFromIcc() returns a zero-based list of valid messages in the ICC.
            messages = smsManager.getMessagesFromIcc();
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        final int count = messages.size();
        for (int i = 0; i < count; i++) {
            SmsMessage message = messages.get(i);
            if (message != null && message.getIndexOnIcc() == messageIndex) {
                MatrixCursor cursor = new MatrixCursor(ICC_COLUMNS, 1);
                cursor.addRow(convertIccToSms(message, 0));
                return cursor;
            }
        }

        throw new IllegalArgumentException(
                "No message in index " + messageIndex + " for subId " + subId);
    }

    /**
     * Gets all the messages in the ICC for a subscription ID.
     *
     * @param subId the subscription ID.
     * @return a cursor listing all the message in the ICC for the subscription ID.
     */
    private Cursor getAllMessagesFromIcc(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid Subscription ID " + subId);
        }
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
        List<SmsMessage> messages;

        // Use phone app permissions to avoid UID mismatch in AppOpsManager.noteOp() call
        long token = Binder.clearCallingIdentity();
        try {
            // getMessagesFromIcc() returns a zero-based list of valid messages in the ICC.
            messages = smsManager.getMessagesFromIcc();
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        final int count = messages.size();
        MatrixCursor cursor = new MatrixCursor(ICC_COLUMNS, count);
        for (int i = 0; i < count; i++) {
            SmsMessage message = messages.get(i);
            if (message != null) {
                cursor.addRow(convertIccToSms(message, i));
            }
        }
        return cursor;
    }

    private void constructQueryForBox(SQLiteQueryBuilder qb, int type, String smsTable,
        boolean canReadRestrictedMessages) {
        constructQueryForAllSms(qb, smsTable, canReadRestrictedMessages);

        if (type != Sms.MESSAGE_TYPE_ALL) {
            appendWhere(qb, "type=" + type);
        }
    }

    private void constructQueryForUndelivered(SQLiteQueryBuilder qb, String smsTable,
        boolean canReadRestrictedMessages) {
        constructQueryForAllSms(qb, smsTable, canReadRestrictedMessages);

        appendWhere(qb, "(type=" + Sms.MESSAGE_TYPE_OUTBOX +
                       " OR type=" + Sms.MESSAGE_TYPE_FAILED +
                       " OR type=" + Sms.MESSAGE_TYPE_QUEUED + ")");
    }

    /**
     * Constructs a query for all rows in the sms table. Join with the pdu table to get the read
     * restriction column value.
     *
     * @param qb The SQLiteQueryBuilder to construct the query with.
     * @param canReadRestrictedMessages Whether the caller can read restricted messages.
     */
    private void constructQueryForAllSms(SQLiteQueryBuilder qb, String smsTable,
        boolean canReadRestrictedMessages) {
        qb.setTables(smsTable);
        ReadRestriction.appendRestrictedToQuery(qb, null, canReadRestrictedMessages);
    }

    /**
     * Constructs a query for the attachments table. Join with the sms table to get the read
     * restriction column value.
     *
     * @param qb The SQLiteQueryBuilder to construct the query with.
     * @param smsTable The sms table to join with.
     * @param canReadRestrictedMessages Whether the caller can read restricted messages.
     */
    private void constructQueryForAttachments(SQLiteQueryBuilder qb, String smsTable,
        boolean canReadRestrictedMessages) {
        qb.setTables(TABLE_ATTACHMENTS);
        String joinAssignmentClause = smsTable + "._id=" + TABLE_ATTACHMENTS + ".sms_id";
        ReadRestriction.appendRestrictedToQuery(qb, joinAssignmentClause, smsTable,
            canReadRestrictedMessages);
    }

    /**
     * Constructs a query for the canonical addresses table.
     *
     * @param qb The SQLiteQueryBuilder to construct the query with.
     * @param canReadRestrictedMessages Whether the caller can read restricted messages.
     */
    private void constructQueryForCanonicalAddresses(SQLiteQueryBuilder qb,
        boolean canReadRestrictedMessages) {
        qb.setTables(TABLE_CANONICAL_ADDRESSES);
        ReadRestriction.appendReadRestrictionToQuery(qb, TABLE_CANONICAL_ADDRESSES,
            canReadRestrictedMessages);
    }

    @Override
    public String getType(Uri url) {
        switch (url.getPathSegments().size()) {
        case 0:
            return VND_ANDROID_DIR_SMS;
            case 1:
                try {
                    Integer.parseInt(url.getPathSegments().get(0));
                    return VND_ANDROID_SMS;
                } catch (NumberFormatException ex) {
                    return VND_ANDROID_DIR_SMS;
                }
            case 2:
                // TODO: What about "threadID"?
                if (url.getPathSegments().get(0).equals("conversations")) {
                    return VND_ANDROID_SMSCHAT;
                } else {
                    return VND_ANDROID_SMS;
                }
        }
        return null;
    }

    @Override
    public int bulkInsert(@NonNull Uri url, @NonNull ContentValues[] values) {
        final int callerUid = Binder.getCallingUid();
        final UserHandle callerUserHandle = Binder.getCallingUserHandle();
        final String callerPkg = getCallingPackage();
        long token = Binder.clearCallingIdentity();
        try {
            int messagesInserted = 0;
            for (ContentValues initialValues : values) {
                Uri insertUri = insertInner(url, initialValues, callerUid, callerPkg,
                        callerUserHandle);
                if (insertUri != null) {
                    messagesInserted++;
                }
            }

            // The raw table is used by the telephony layer for storing an sms before
            // sending out a notification that an sms has arrived. We don't want to notify
            // the default sms app of changes to this table.
            final boolean notifyIfNotDefault = sURLMatcher.match(url) != SMS_RAW_MESSAGE;
            notifyChange(notifyIfNotDefault, url, callerPkg);
            return messagesInserted;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        long startTime = SystemClock.elapsedRealtime();
        Uri result = null;
        try {
            result = insertInternal(url, initialValues);
            int count = (result != null) ? 1 : 0;
            ProviderMetricsLogger.logOperationLatency(
                    getContext(),
                    ProviderMetricsLogger.OPERATION_INSERT,
                    ProviderMetricsLogger.TARGET_URI_SMS,
                    startTime,
                    count);
        } catch (SQLiteDatabaseLockedException e) { // Lock
            ProviderMetricsLogger.logDbLockContention(getContext(),
                    ProviderMetricsLogger.OPERATION_INSERT, ProviderMetricsLogger.TARGET_URI_SMS);
            throw e;
        } catch (Exception e) {
            Log.e("ProviderMetrics", "Database operation failed", e);
            ProviderUtil.logRunningTelephonyProviderProcesses(getContext());
            throw e;
        }
        return result;
    }

    /** Internal implementation of the database operation. */
    public Uri insertInternal(Uri url, ContentValues initialValues) {
        final int callerUid = Binder.getCallingUid();
        final UserHandle callerUserHandle = Binder.getCallingUserHandle();
        final String callerPkg = getCallingPackage();
        long token = Binder.clearCallingIdentity();
        try {
            Uri insertUri = insertInner(url, initialValues, callerUid, callerPkg, callerUserHandle);

            // Skip notifyChange() if insertUri is null
            if (insertUri != null) {
                int match = sURLMatcher.match(url);
                // The raw table is used by the telephony layer for storing an sms before sending
                // out a notification that an sms has arrived. We don't want to notify the default
                // sms app of changes to this table.
                final boolean notifyIfNotDefault = match != SMS_RAW_MESSAGE;
                notifyChange(notifyIfNotDefault, insertUri, callerPkg);
            }
            return insertUri;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private Uri insertInner(Uri url, ContentValues initialValues, int callerUid, String callerPkg,
            UserHandle callerUserHandle) {
        ContentValues values;
        long rowID;
        int type = Sms.MESSAGE_TYPE_ALL;

        int match = sURLMatcher.match(url);
        String table = TABLE_SMS;

        switch (match) {
            case SMS_ALL:
                Integer typeObj = initialValues.getAsInteger(Sms.TYPE);
                if (typeObj != null) {
                    type = typeObj.intValue();
                } else {
                    // default to inbox
                    type = Sms.MESSAGE_TYPE_INBOX;
                }
                break;

            case SMS_INBOX:
                type = Sms.MESSAGE_TYPE_INBOX;
                break;

            case SMS_FAILED:
                type = Sms.MESSAGE_TYPE_FAILED;
                break;

            case SMS_QUEUED:
                type = Sms.MESSAGE_TYPE_QUEUED;
                break;

            case SMS_SENT:
                type = Sms.MESSAGE_TYPE_SENT;
                break;

            case SMS_DRAFT:
                type = Sms.MESSAGE_TYPE_DRAFT;
                break;

            case SMS_OUTBOX:
                type = Sms.MESSAGE_TYPE_OUTBOX;
                break;

            case SMS_RAW_MESSAGE:
                table = "raw";
                break;

            case SMS_STATUS_PENDING:
                table = "sr_pending";
                break;

            case SMS_ATTACHMENT:
                table = "attachments";
                break;

            case SMS_NEW_THREAD_ID:
                table = "canonical_addresses";
                break;

            case SMS_ALL_ICC:
            case SMS_ALL_ICC_SUBID:
                int subId;
                if (match == SMS_ALL_ICC) {
                    subId = SmsManager.getDefaultSmsSubscriptionId();
                } else {
                    try {
                        subId = Integer.parseInt(url.getPathSegments().get(1));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "Wrong path segements for SMS_ALL_ICC_SUBID, uri= " + url);
                    }
                }

                if (!ProviderUtil.allowInteractingWithEntryOfSubscription(getContext(), subId,
                    callerUserHandle)) {
                    TelephonyUtils.showSwitchToManagedProfileDialogIfAppropriate(getContext(),
                        subId, callerUid, callerPkg);
                    return null;
                }

                if (initialValues == null) {
                    throw new IllegalArgumentException("ContentValues is null");
                }

                String scAddress = initialValues.getAsString(Sms.SERVICE_CENTER);
                String address = initialValues.getAsString(Sms.ADDRESS);
                String message = initialValues.getAsString(Sms.BODY);
                boolean isRead = true;
                Integer obj = initialValues.getAsInteger(Sms.TYPE);

                if (obj == null || address == null || message == null) {
                    throw new IllegalArgumentException("Missing SMS data");
                }

                type = obj.intValue();
                if (!isSupportedType(type)) {
                    throw new IllegalArgumentException("Unsupported message type= " + type);
                }
                obj = initialValues.getAsInteger(Sms.READ); // 0: Unread, 1: Read
                if (obj != null && obj.intValue() == 0) {
                    isRead = false;
                }

                Long date = initialValues.getAsLong(Sms.DATE);
                return insertMessageToIcc(subId, scAddress, address, message, type, isRead,
                        date != null ? date : 0) ? url : null;

            default:
                Log.e(TAG, "Invalid request: " + url);
                return null;
        }

        SQLiteDatabase db = getWritableDatabase(match);
        SQLiteOpenHelper sqLiteOpenHelper = getDBOpenHelper(match);
        if (sqLiteOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) sqLiteOpenHelper).addDatabaseOpeningDebugLog(
                    callerPkg + ";SmsProvider.insert;" + url, false);
        }

        String possibleOtpMessage = null;
        if (table.equals(TABLE_SMS)) {
            boolean addDate = false;
            boolean addType = false;

            // Make sure that the date and type are set
            if (initialValues == null) {
                values = new ContentValues(1);
                addDate = true;
                addType = true;
            } else {
                values = new ContentValues(initialValues);

                if (!initialValues.containsKey(Sms.DATE)) {
                    addDate = true;
                }

                if (!initialValues.containsKey(Sms.TYPE)) {
                    addType = true;
                }
            }

            if (addDate) {
                values.put(Sms.DATE, new Long(System.currentTimeMillis()));
            }

            if (addType && (type != Sms.MESSAGE_TYPE_ALL)) {
                values.put(Sms.TYPE, Integer.valueOf(type));
            }

            if (Flags.secureAccessToRestrictedRcsMessages()) {
                final boolean canWriteRestrictedMessages = ProviderUtil.canWriteRestrictedMessages(
                        getContext(), callerPkg, callerUid);
                ReadRestriction.setReadRestrictionValueOnInsert(getContext(), values, callerPkg,
                        canWriteRestrictedMessages);
            }

            // thread_id
            Long threadId = values.getAsLong(Sms.THREAD_ID);
            String address = values.getAsString(Sms.ADDRESS);

            if (((threadId == null) || (threadId == 0)) && (!TextUtils.isEmpty(address))) {
                values.put(Sms.THREAD_ID, Threads.getOrCreateThreadId(
                                   getContext(), address));
            }

            // If this message is going in as a draft, it should replace any
            // other draft messages in the thread.  Just delete all draft
            // messages with this thread ID.  We could add an OR REPLACE to
            // the insert below, but we'd have to query to find the old _id
            // to produce a conflict anyway.
            if (values.getAsInteger(Sms.TYPE) == Sms.MESSAGE_TYPE_DRAFT) {
                db.delete(TABLE_SMS, "thread_id=? AND type=?",
                        new String[] { values.getAsString(Sms.THREAD_ID),
                                       Integer.toString(Sms.MESSAGE_TYPE_DRAFT) });
            }

            if (type == Sms.MESSAGE_TYPE_INBOX) {

                // Determine if incoming messages contain an OTP code
                String message = values.getAsString(Sms.BODY);
                int otpType;
                Long date = values.getAsLong(Sms.DATE);
                if (date != null
                        && date > System.currentTimeMillis() - ProviderUtil.OTP_HIDING_TIME_MS
                        && Telephony.Sms.shouldCheckForOtp(getContext(), message)) {
                    otpType = Telephony.Sms.OTP_TYPE_PENDING;
                    possibleOtpMessage = message;
                } else {
                    otpType = Telephony.Sms.OTP_TYPE_NONE;
                }
                values.put(Telephony.Sms.CONTAINS_OTP, otpType);

                // Look up the person if not already filled in.
                if ((values.getAsLong(Sms.PERSON) == null) && (!TextUtils.isEmpty(address))) {
                    Cursor cursor = null;
                    Uri uri = Uri.withAppendedPath(Contacts.Phones.CONTENT_FILTER_URL,
                            Uri.encode(address));
                    try {
                        cursor = getContext().getContentResolver().query(
                                uri,
                                CONTACT_QUERY_PROJECTION,
                                null, null, null);

                        if (cursor != null && cursor.moveToFirst()) {
                            Long id = Long.valueOf(cursor.getLong(PERSON_ID_COLUMN));
                            values.put(Sms.PERSON, id);
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "insert: query contact uri " + uri + " caught ", ex);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else {
                // Mark all non-inbox messages read.
                values.put(Sms.READ, ONE);
            }
            if (ProviderUtil.shouldSetCreator(values, callerUid)) {
                // Only SYSTEM or PHONE can set CREATOR
                // If caller is not SYSTEM or PHONE, or SYSTEM or PHONE does not set CREATOR
                // set CREATOR using the truth on caller.
                // Note: Inferring package name from UID may include unrelated package names
                values.put(Sms.CREATOR, callerPkg);
            }
        } else {
            if (initialValues == null) {
                values = new ContentValues(1);
            } else {
                values = initialValues;
            }
        }

        // Insert subId value
        int subId;
        if (values.containsKey(Telephony.Sms.SUBSCRIPTION_ID)) {
            subId = values.getAsInteger(Telephony.Sms.SUBSCRIPTION_ID);
        } else {
            // TODO (b/256992531): Currently, one sim card is set as default sms subId in work
            //  profile. Default sms subId should be updated based on user pref.
            subId = SmsManager.getDefaultSmsSubscriptionId();
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                values.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
            }
        }


        if (table.equals(TABLE_SMS)) {
            // Get destination address from values
            String address = "";
            if (values.containsKey(Sms.ADDRESS)) {
                address = values.getAsString(Sms.ADDRESS);
            }

            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    && !TelephonyPermissions.checkSubscriptionAssociatedWithUser(getContext(),
                    subId, callerUserHandle, address)) {
                TelephonyUtils.showSwitchToManagedProfileDialogIfAppropriate(getContext(),
                        subId, callerUid, callerPkg);
                return null;
            }
        }

        rowID = db.insert(table, "body", values);

        // Don't use a trigger for updating the words table because of a bug
        // in FTS3.  The bug is such that the call to get the last inserted
        // row is incorrect.
        if (table == TABLE_SMS) {
            // Update the words table with a corresponding row.  The words table
            // allows us to search for words quickly, without scanning the whole
            // table;
            ContentValues cv = new ContentValues();
            cv.put(Telephony.MmsSms.WordsTable.ID, rowID);
            cv.put(Telephony.MmsSms.WordsTable.INDEXED_TEXT, values.getAsString("body"));
            cv.put(Telephony.MmsSms.WordsTable.SOURCE_ROW_ID, rowID);
            cv.put(Telephony.MmsSms.WordsTable.TABLE_ID, 1);
            cv.put(MmsSms.WordsTable.SUBSCRIPTION_ID, subId);
            db.insert(TABLE_WORDS, Telephony.MmsSms.WordsTable.INDEXED_TEXT, cv);
        }
        if (rowID > 0) {
            if (Flags.secureAccessToRestrictedRcsMessages() && table == TABLE_SMS) {
                ReadRestrictionStatsLogger.getInstance().onMessageInserted(
                    ReadRestrictionStatsLogger.ContentProvider.SMS,
                    callerUid, ProviderUtil.isMessageReadRestricted(values));
            }
            Uri uri = null;
            if (table == TABLE_SMS) {
                uri = Uri.withAppendedPath(Sms.CONTENT_URI, String.valueOf(rowID));
            } else {
                uri = Uri.withAppendedPath(url, String.valueOf(rowID));
            }
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "insert " + uri + " succeeded");
            }
            // If we might have an OTP, schedule the full otp check, which will update the inserted
            // value when complete.
            if (possibleOtpMessage != null) {
                scheduleOtpCheck(uri, possibleOtpMessage);
            }
            return uri;
        } else {
            Log.e(TAG, "insert: failed!");
            if (sqLiteOpenHelper instanceof MmsSmsDatabaseHelper) {
                ((MmsSmsDatabaseHelper) sqLiteOpenHelper).printDatabaseOpeningDebugLog();
            }
        }

        return null;
    }

    private boolean isSupportedType(int messageType) {
        return (messageType == Sms.MESSAGE_TYPE_INBOX)
                || (messageType == Sms.MESSAGE_TYPE_OUTBOX)
                || (messageType == Sms.MESSAGE_TYPE_SENT);
    }

    private int getMessageStatusForIcc(int messageType, boolean isRead) {
        if (messageType == Sms.MESSAGE_TYPE_SENT) {
            return SmsManager.STATUS_ON_ICC_SENT;
        } else if (messageType == Sms.MESSAGE_TYPE_OUTBOX) {
            return SmsManager.STATUS_ON_ICC_UNSENT;
        } else { // Sms.MESSAGE_BOX_INBOX
            if (isRead) {
                return SmsManager.STATUS_ON_ICC_READ;
            } else {
                return SmsManager.STATUS_ON_ICC_UNREAD;
            }
        }
    }

    @VisibleForTesting
    protected void scheduleOtpCheck(Uri uri, String text) {
        mBackgroundExecutor.execute(() -> {
            try {
                Trace.beginSection("scheduleOtpCheck");
                TextLinks.Request request =
                        new TextLinks.Request.Builder(text).setEntityConfig(
                                TC_REQUEST_CONFIG).build();

                TextLinks links = mTextClassifier.generateLinks(request);

                final boolean appCompatApiEnabled =
                        android.view.flags.Flags.redactOtpAppCompatApi();
                final boolean webOtpApiEnabled =
                        android.view.flags.Flags.redactWebOtpSmsApi();
                int otpType = Sms.OTP_TYPE_NONE;
                for (TextLinks.TextLink link : links.getLinks()) {
                    for (int i = 0; i < link.getEntityCount(); i++) {
                        final String entity = link.getEntity(i);

                        boolean isOtp = false;
                        // The sub-type distinguishes different type of OTP message.
                        // E.g. SMS Retriever OTP, Web OTP, or a generic OTP.
                        // Note that this represents bitfields of `otpType`.
                        // Please refer to Sms.OTP_SUBTYPE_* definitions for more details.
                        int subType = 0;
                        if (entity.equals(TextClassifier.TYPE_SMS_RETRIEVER_OTP)) {
                            isOtp = true;
                            subType = Sms.OTP_SUBTYPE_SMS_RETRIEVER_OTP;
                        } else if (webOtpApiEnabled
                                      && entity.equals(TextClassifier.TYPE_SMS_WEB_OTP)) {
                            isOtp = true;
                            subType = Sms.OTP_SUBTYPE_WEB_OTP;
                        } else if (appCompatApiEnabled
                                      && entity.equals(TextClassifier.TYPE_OTP)) {
                            isOtp = true;
                            subType = Sms.OTP_SUBTYPE_NONE;
                        }

                        if (isOtp) {
                            otpType |= Sms.OTP_TYPE_CONTAINS_OTP;
                            if (appCompatApiEnabled) {
                                // If Redact OTP App Compat API is enabled, add the subtype.
                                otpType |= subType;
                            }
                        }
                    }
                }
                ContentValues values = new ContentValues();
                values.put(Sms.CONTAINS_OTP, otpType);
                updateWithRetry(uri, values, "scheduleOtpCheck", 1);

                if ((otpType & Telephony.Sms.OTP_TYPE_MASK) == Sms.OTP_TYPE_CONTAINS_OTP) {
                    // Schedule an update for when the OTP hiding time expires, to remove the "has
                    // otp" value. This is best-effort, not guaranteed.
                    mMainThreadHandler.postDelayed(() -> {
                        ContentValues unredacted = new ContentValues();
                        unredacted.put(Sms.CONTAINS_OTP, Sms.OTP_TYPE_NONE);
                        updateWithRetry(uri, unredacted, "scheduleOtpCheck: unredact", 1);
                    }, ProviderUtil.OTP_HIDING_TIME_MS);
                }
            } finally {
                Trace.endSection();
            }
        });
    }

    private void updateWithRetry(Uri uri, ContentValues values, String operationName,
            int currentAttempt) {
        try {
            update(uri, values, null);
        } catch (SQLiteDatabaseLockedException e) {
            if (currentAttempt < MAX_DB_UPDATE_ATTEMPTS) {
                Log.w(TAG, operationName + ": Database locked. Attempt " + currentAttempt
                        + " of " + MAX_DB_UPDATE_ATTEMPTS + " failed.");
                mMainThreadHandler.postDelayed(()
                                -> updateWithRetry(uri, values, operationName, currentAttempt + 1),
                        DB_UPDATE_RETRY_DELAY_MS);
            } else {
                Log.e(TAG, operationName + ": Update failed. Database locked. Attempts: "
                        + currentAttempt, e);
            }
        }
    }

    // This method is to allow override in subclass used for testing on an in-memory database
    protected boolean canReadOtpSms(int uid, String packageName) {
        return ProviderUtil.canReadOtpSms(getContext(), uid, packageName);
    }

    /**
     * Inserts new message to the ICC for a subscription ID.
     *
     * @param subId the subscription ID.
     * @param scAddress the SMSC for this message.
     * @param address destination or originating address.
     * @param message the message text.
     * @param messageType type of the message.
     * @param isRead ture if the message has been read. Otherwise false.
     * @param date the date the message was received.
     * @return true for succeess. Otherwise false.
     */
    private boolean insertMessageToIcc(int subId, String scAddress, String address, String message,
            int messageType, boolean isRead, long date) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid Subscription ID " + subId);
        }
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);

        int status = getMessageStatusForIcc(messageType, isRead);
        SmsMessage.SubmitPdu smsPdu =
                SmsMessage.getSmsPdu(subId, status, scAddress, address, message, date);

        if (smsPdu == null) {
            throw new IllegalArgumentException("Failed to create SMS PDU");
        }

        // Use phone app permissions to avoid UID mismatch in AppOpsManager.noteOp() call.
        long token = Binder.clearCallingIdentity();
        try {
            return smsManager.copyMessageToIcc(
                    smsPdu.encodedScAddress, smsPdu.encodedMessage, status);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        long startTime = SystemClock.elapsedRealtime();
        int result = 0;
        try {
            result = deleteInternal(url, where, whereArgs);
            int count = result;
            ProviderMetricsLogger.logOperationLatency(
                    getContext(),
                    ProviderMetricsLogger.OPERATION_DELETE,
                    ProviderMetricsLogger.TARGET_URI_SMS,
                    startTime,
                    count);
        } catch (SQLiteDatabaseLockedException e) { // Lock
            ProviderMetricsLogger.logDbLockContention(getContext(),
                    ProviderMetricsLogger.OPERATION_DELETE, ProviderMetricsLogger.TARGET_URI_SMS);
            throw e;
        } catch (Exception e) {
            Log.e("ProviderMetrics", "Database operation failed", e);
            ProviderUtil.logRunningTelephonyProviderProcesses(getContext());
            throw e;
        }
        return result;
    }

    /** Internal implementation of the database operation. */
    public int deleteInternal(Uri url, String where, String[] whereArgs) {
        final UserHandle callerUserHandle = Binder.getCallingUserHandle();
        final int callerUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();

        String selectionBySubIds = null;
        String selectionByEmergencyNumbers = null;
        try {
            // Filter SMS based on subId and emergency numbers.
            selectionBySubIds = ProviderUtil.getSelectionBySubIds(getContext(),
                    callerUserHandle, TABLE_SMS);
            if (hasCalling()) {
                selectionByEmergencyNumbers = ProviderUtil
                        .getSelectionByEmergencyNumbers(getContext());
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // The delete operation is already restricted to WRITE_SMS permission, so we don't need
        // further restriction for deleting restricted messages.
        if (Flags.secureAccessToRestrictedRcsMessages()) {
            SqlQueryChecker.checkQueryForForbiddenColumns(/* projection= */ null, where,
                    /* sortOrder= */ null, TAG);
        }

        String filter = "";
        if (selectionBySubIds == null && selectionByEmergencyNumbers == null) {
            // No subscriptions associated with user and no emergency numbers
            filter = null;
        } else if (selectionBySubIds != null && selectionByEmergencyNumbers != null) {
            filter = (selectionBySubIds + " OR " + selectionByEmergencyNumbers);
        } else {
            filter = selectionBySubIds == null ?
                    selectionByEmergencyNumbers : selectionBySubIds;
        }

        int count;
        int match = sURLMatcher.match(url);
        SQLiteDatabase db = getWritableDatabase(match);
        String debugMessage = getCallingPackage() + ";SmsProvider.delete;" + url;
        // Always log delete for debug purpose, as delete is a critical but non-frequent operation.
        Log.d(TAG, debugMessage);
        SQLiteOpenHelper sqLiteOpenHelper = getDBOpenHelper(match);
        if (sqLiteOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) sqLiteOpenHelper).addDatabaseOpeningDebugLog(
                    debugMessage, false);
        }
        boolean notifyIfNotDefault = true;
        switch (match) {
            case SMS_ALL:
                if (filter == null) {
                    // No subscriptions associated with user and no emergency numbers, return 0.
                    return 0;
                }
                where = DatabaseUtils.concatenateWhere(where, filter);
                count = db.delete(TABLE_SMS, where, whereArgs);
                if (count != 0) {
                    // Don't update threads unless something changed.
                    MmsSmsDatabaseHelper.updateThreads(db, where, whereArgs);
                }
                break;

            case SMS_ALL_ID:
                try {
                    int message_id = Integer.parseInt(url.getPathSegments().get(0));
                    count = MmsSmsDatabaseHelper.deleteOneSms(db, message_id);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                        "Bad message id: " + url.getPathSegments().get(0));
                }
                break;

            case SMS_CONVERSATIONS_ID:
                int threadID;

                try {
                    threadID = Integer.parseInt(url.getPathSegments().get(1));
                } catch (Exception ex) {
                    throw new IllegalArgumentException(
                            "Bad conversation thread id: "
                            + url.getPathSegments().get(1));
                }

                // delete the messages from the sms table
                where = DatabaseUtils.concatenateWhere("thread_id=" + threadID, where);
                if (filter == null) {
                    // No subscriptions associated with user and no emergency numbers, return 0.
                    return 0;
                }
                where = DatabaseUtils.concatenateWhere(where, filter);
                count = db.delete(TABLE_SMS, where, whereArgs);
                MmsSmsDatabaseHelper.updateThread(db, threadID);
                break;

            case SMS_RAW_MESSAGE:
                ContentValues cv = new ContentValues();
                cv.put("deleted", 1);
                count = db.update(TABLE_RAW, cv, where, whereArgs);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.d(TAG, "delete: num rows marked deleted in raw table: " + count);
                }
                notifyIfNotDefault = false;
                break;

            case SMS_RAW_MESSAGE_PERMANENT_DELETE:
                count = db.delete(TABLE_RAW, where, whereArgs);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.d(TAG, "delete: num rows permanently deleted in raw table: " + count);
                }
                notifyIfNotDefault = false;
                break;

            case SMS_STATUS_PENDING:
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return 0.
                    return 0;
                }
                where = DatabaseUtils.concatenateWhere(where, selectionBySubIds);
                count = db.delete("sr_pending", where, whereArgs);
                break;

            case SMS_ALL_ICC:
            case SMS_ALL_ICC_SUBID: {
                int subId;
                int deletedCnt;
                if (match == SMS_ALL_ICC) {
                    subId = SmsManager.getDefaultSmsSubscriptionId();
                } else {
                    try {
                        subId = Integer.parseInt(url.getPathSegments().get(1));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Wrong path segements, uri= " + url);
                    }
                }

                if (!ProviderUtil.allowInteractingWithEntryOfSubscription(getContext(),
                        subId, callerUserHandle)) {
                    // If subId is not associated with user, return 0.
                    return 0;
                }

                deletedCnt = deleteAllMessagesFromIcc(subId);
                // Notify changes even failure case since there might be some changes should be
                // known.
                getContext()
                        .getContentResolver()
                        .notifyChange(
                                match == SMS_ALL_ICC ? ICC_URI : ICC_SUBID_URI,
                                null,
                                true,
                                UserHandle.USER_ALL);
                return deletedCnt;
            }

            case SMS_ICC:
            case SMS_ICC_SUBID: {
                int subId;
                int messageIndex;
                boolean success;
                try {
                    if (match == SMS_ICC) {
                        subId = SmsManager.getDefaultSmsSubscriptionId();
                        messageIndex = Integer.parseInt(url.getPathSegments().get(1));
                    } else {
                        subId = Integer.parseInt(url.getPathSegments().get(1));
                        messageIndex = Integer.parseInt(url.getPathSegments().get(2));
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Wrong path segements, uri= " + url);
                }

                if (!ProviderUtil.allowInteractingWithEntryOfSubscription(getContext(),
                        subId, callerUserHandle)) {
                    // If subId is not associated with user, return 0.
                    return 0;
                }

                success = deleteMessageFromIcc(subId, messageIndex);
                // Notify changes even failure case since there might be some changes should be
                // known.
                getContext()
                        .getContentResolver()
                        .notifyChange(
                                match == SMS_ICC ? ICC_URI : ICC_SUBID_URI,
                                null,
                                true,
                                UserHandle.USER_ALL);
                return success ? 1 : 0; // return deleted count
            }

            default:
                throw new IllegalArgumentException("Unknown URL");
        }

        if (count > 0) {
            notifyChange(notifyIfNotDefault, url, getCallingPackage());
        }
        return count;
    }

    /**
     * Deletes the message at index from the ICC for a subscription ID.
     *
     * @param subId the subscription ID.
     * @param messageIndex the message index of the message in the ICC (1-based index).
     * @return true for succeess. Otherwise false.
     */
    private boolean deleteMessageFromIcc(int subId, int messageIndex) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid Subscription ID " + subId);
        }
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);

        // Use phone app permissions to avoid UID mismatch in AppOpsManager.noteOp() call.
        long token = Binder.clearCallingIdentity();
        try {
            return smsManager.deleteMessageFromIcc(messageIndex);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Deletes all the messages from the ICC for a subscription ID.
     *
     * @param subId the subscription ID.
     * @return return deleted messaegs count.
     */
    private int deleteAllMessagesFromIcc(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid Subscription ID " + subId);
        }
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);

        // Use phone app permissions to avoid UID mismatch in AppOpsManager.noteOp() call.
        long token = Binder.clearCallingIdentity();
        try {
            int deletedCnt = 0;
            int maxIndex = smsManager.getSmsCapacityOnIcc();
            // messageIndex is 1-based index of the message in the ICC.
            for (int messageIndex = 1; messageIndex <= maxIndex; messageIndex++) {
                if (smsManager.deleteMessageFromIcc(messageIndex)) {
                    deletedCnt++;
                } else {
                    Log.e(TAG, "Fail to delete SMS at index " + messageIndex
                            + " for subId " + subId);
                }
            }
            return deletedCnt;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        long startTime = SystemClock.elapsedRealtime();
        int result = 0;
        try {
            result = updateInternal(url, values, where, whereArgs);
            int count = result;
            ProviderMetricsLogger.logOperationLatency(
                    getContext(),
                    ProviderMetricsLogger.OPERATION_UPDATE,
                    ProviderMetricsLogger.TARGET_URI_SMS,
                    startTime,
                    count);
        } catch (SQLiteDatabaseLockedException e) { // Lock
            ProviderMetricsLogger.logDbLockContention(getContext(),
                    ProviderMetricsLogger.OPERATION_UPDATE, ProviderMetricsLogger.TARGET_URI_SMS);
            throw e;
        } catch (Exception e) {
            Log.e("ProviderMetrics", "Database operation failed", e);
            ProviderUtil.logRunningTelephonyProviderProcesses(getContext());
            throw e;
        }
        return result;
    }

    /** Internal implementation of the database operation. */
    public int updateInternal(Uri url, ContentValues values, String where, String[] whereArgs) {
        final int callerUid = Binder.getCallingUid();
        final UserHandle callerUserHandle = Binder.getCallingUserHandle();
        final String callerPkg = getCallingPackage();
        int count = 0;
        String table = TABLE_SMS;
        String extraWhere = null;
        boolean notifyIfNotDefault = true;
        int match = sURLMatcher.match(url);
        SQLiteDatabase db = getWritableDatabase(match);
        SQLiteOpenHelper sqLiteOpenHelper = getDBOpenHelper(match);
        if (sqLiteOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) sqLiteOpenHelper).addDatabaseOpeningDebugLog(
                    callerPkg + ";SmsProvider.update;" + url, false);
        }
        if (Flags.secureAccessToRestrictedRcsMessages()) {
            SqlQueryChecker.checkQueryForForbiddenColumns(/* projection= */ null, where,
                    /* sortOrder= */ null, TAG);
        }
        if (callerUid != Process.myUid() && values.containsKey(Telephony.Sms.CONTAINS_OTP)) {
            // Apps are not allowed to update the CONTAINS_OTP column directly
            values.remove(Telephony.Sms.CONTAINS_OTP);
        }

        switch (match) {
            case SMS_RAW_MESSAGE:
                table = TABLE_RAW;
                notifyIfNotDefault = false;
                break;

            case SMS_STATUS_PENDING:
                table = TABLE_SR_PENDING;
                break;

            case SMS_ALL:
            case SMS_FAILED:
            case SMS_QUEUED:
            case SMS_INBOX:
            case SMS_SENT:
            case SMS_DRAFT:
            case SMS_OUTBOX:
            case SMS_CONVERSATIONS:
                break;

            case SMS_ALL_ID:
                extraWhere = "_id=" + url.getPathSegments().get(0);
                break;

            case SMS_INBOX_ID:
            case SMS_FAILED_ID:
            case SMS_SENT_ID:
            case SMS_DRAFT_ID:
            case SMS_OUTBOX_ID:
                extraWhere = "_id=" + url.getPathSegments().get(1);
                break;

            case SMS_CONVERSATIONS_ID: {
                String threadId = url.getPathSegments().get(1);

                try {
                    Integer.parseInt(threadId);
                } catch (Exception ex) {
                    Log.e(TAG, "Bad conversation thread id: " + threadId);
                    break;
                }

                extraWhere = "thread_id=" + threadId;
                break;
            }

            case SMS_STATUS_ID:
                extraWhere = "_id=" + url.getPathSegments().get(1);
                break;

            default:
                throw new UnsupportedOperationException(
                        "URI " + url + " not supported");
        }

        if (table.equals(TABLE_SMS) && ProviderUtil.shouldRemoveCreator(values, callerUid)) {
            // CREATOR should not be changed by non-SYSTEM/PHONE apps
            Log.w(TAG, callerPkg + " tries to update CREATOR");
            values.remove(Sms.CREATOR);
        }

        if (Flags.secureAccessToRestrictedRcsMessages()) {
            final boolean canWriteRestrictedMessages = ProviderUtil.canWriteRestrictedMessages(
                        getContext(), callerPkg, callerUid);
            ReadRestriction.setReadRestrictionValueOnUpdate(values, canWriteRestrictedMessages);
        }

        final long token = Binder.clearCallingIdentity();
        String selectionBySubIds = null;
        String selectionByEmergencyNumbers = null;
        try {
            // Filter SMS based on subId and emergency numbers.
            selectionBySubIds = ProviderUtil.getSelectionBySubIds(getContext(),
                    callerUserHandle, table);
            if (table.equals(TABLE_SMS)) {
                selectionByEmergencyNumbers = ProviderUtil
                        .getSelectionByEmergencyNumbers(getContext());
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        if (table.equals(TABLE_SMS)) {
            if (selectionBySubIds == null && selectionByEmergencyNumbers == null) {
                // No subscriptions associated with user and no emergency numbers, return 0.
                return 0;
            }
        } else {
            if (selectionBySubIds == null) {
                // No subscriptions associated with user, return 0.
                return 0;
            }
        }


        String filter = "";
        if (selectionBySubIds != null && selectionByEmergencyNumbers != null) {
            filter = (selectionBySubIds + " OR " + selectionByEmergencyNumbers);
        } else {
            filter = selectionBySubIds == null ?
                    selectionByEmergencyNumbers : selectionBySubIds;
        }
        where = DatabaseUtils.concatenateWhere(where, filter);

        where = DatabaseUtils.concatenateWhere(where, extraWhere);

        if (Flags.secureAccessToRestrictedRcsMessages()
            && values.containsKey(ReadRestriction.READ_RESTRICTION_COLUMN_NAME)) {
            count = ReadRestriction.performReadRestrictionDatabaseUpdate(
                db, table, values, where, whereArgs);
            if (count > 0 && !ProviderUtil.isMessageReadRestricted(values)) {
                ReadRestrictionStatsLogger.getInstance().onMessageUnrestricted(
                        ReadRestrictionStatsLogger.ContentProvider.SMS, callerUid);
            }
        } else {
            count = db.update(table, values, where, whereArgs);
        }

        if (count > 0) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "update " + url + " succeeded");
            }

            // If this message was upgraded, evaluate its new status and dispatch
            // any associated PendingIntents to notify the sender.
            // TODO(b/487924740) Optimize to avoid controller overhead during bulk writes
            final Context context = getContext();
            MessageUpgradeController.dispatchSmsPendingIntentsIfUpgraded(
                    context, context.getUserId(), url, values);
            notifyChange(notifyIfNotDefault, url, callerPkg);
        }
        return count;
    }

    private void notifyChange(boolean notifyIfNotDefault, Uri uri, final String callingPackage) {
        final Context context = getContext();
        ContentResolver cr = context.getContentResolver();
        cr.notifyChange(uri, null, true, UserHandle.USER_ALL);
        cr.notifyChange(MmsSms.CONTENT_URI, null, true, UserHandle.USER_ALL);
        cr.notifyChange(Uri.parse("content://mms-sms/conversations/"), null, true,
                UserHandle.USER_ALL);
        if (notifyIfNotDefault) {
            ProviderUtil.notifyIfNotDefaultSmsApp(uri, callingPackage, context);
        }
    }

    // Db open helper for tables stored in CE(Credential Encrypted) storage.
    @VisibleForTesting
    public SQLiteOpenHelper mCeOpenHelper;
    // Db open helper for tables stored in DE(Device Encrypted) storage. It's currently only used
    // to store raw table.
    @VisibleForTesting
    public SQLiteOpenHelper mDeOpenHelper;

    private final static String TAG = "SmsProvider";
    private final static String VND_ANDROID_SMS = "vnd.android.cursor.item/sms";
    private final static String VND_ANDROID_SMSCHAT =
            "vnd.android.cursor.item/sms-chat";
    private final static String VND_ANDROID_DIR_SMS =
            "vnd.android.cursor.dir/sms";

    private static final String[] sIDProjection = new String[] { "_id" };

    private static final int SMS_ALL = 0;
    private static final int SMS_ALL_ID = 1;
    private static final int SMS_INBOX = 2;
    private static final int SMS_INBOX_ID = 3;
    private static final int SMS_SENT = 4;
    private static final int SMS_SENT_ID = 5;
    private static final int SMS_DRAFT = 6;
    private static final int SMS_DRAFT_ID = 7;
    private static final int SMS_OUTBOX = 8;
    private static final int SMS_OUTBOX_ID = 9;
    private static final int SMS_CONVERSATIONS = 10;
    private static final int SMS_CONVERSATIONS_ID = 11;
    private static final int SMS_RAW_MESSAGE = 15;
    private static final int SMS_ATTACHMENT = 16;
    private static final int SMS_ATTACHMENT_ID = 17;
    private static final int SMS_NEW_THREAD_ID = 18;
    private static final int SMS_QUERY_THREAD_ID = 19;
    private static final int SMS_STATUS_ID = 20;
    private static final int SMS_STATUS_PENDING = 21;
    private static final int SMS_ALL_ICC = 22;
    private static final int SMS_ICC = 23;
    private static final int SMS_FAILED = 24;
    private static final int SMS_FAILED_ID = 25;
    private static final int SMS_QUEUED = 26;
    private static final int SMS_UNDELIVERED = 27;
    private static final int SMS_RAW_MESSAGE_PERMANENT_DELETE = 28;
    private static final int SMS_ALL_ICC_SUBID = 29;
    private static final int SMS_ICC_SUBID = 30;

    private static final UriMatcher sURLMatcher =
            new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sURLMatcher.addURI("sms", null, SMS_ALL);
        sURLMatcher.addURI("sms", "#", SMS_ALL_ID);
        sURLMatcher.addURI("sms", "inbox", SMS_INBOX);
        sURLMatcher.addURI("sms", "inbox/#", SMS_INBOX_ID);
        sURLMatcher.addURI("sms", "sent", SMS_SENT);
        sURLMatcher.addURI("sms", "sent/#", SMS_SENT_ID);
        sURLMatcher.addURI("sms", "draft", SMS_DRAFT);
        sURLMatcher.addURI("sms", "draft/#", SMS_DRAFT_ID);
        sURLMatcher.addURI("sms", "outbox", SMS_OUTBOX);
        sURLMatcher.addURI("sms", "outbox/#", SMS_OUTBOX_ID);
        sURLMatcher.addURI("sms", "undelivered", SMS_UNDELIVERED);
        sURLMatcher.addURI("sms", "failed", SMS_FAILED);
        sURLMatcher.addURI("sms", "failed/#", SMS_FAILED_ID);
        sURLMatcher.addURI("sms", "queued", SMS_QUEUED);
        sURLMatcher.addURI("sms", "conversations", SMS_CONVERSATIONS);
        sURLMatcher.addURI("sms", "conversations/#", SMS_CONVERSATIONS_ID);
        sURLMatcher.addURI("sms", "raw", SMS_RAW_MESSAGE);
        sURLMatcher.addURI("sms", "raw/permanentDelete", SMS_RAW_MESSAGE_PERMANENT_DELETE);
        sURLMatcher.addURI("sms", "attachments", SMS_ATTACHMENT);
        sURLMatcher.addURI("sms", "attachments/#", SMS_ATTACHMENT_ID);
        sURLMatcher.addURI("sms", "threadID", SMS_NEW_THREAD_ID);
        sURLMatcher.addURI("sms", "threadID/#", SMS_QUERY_THREAD_ID);
        sURLMatcher.addURI("sms", "status/#", SMS_STATUS_ID);
        sURLMatcher.addURI("sms", "sr_pending", SMS_STATUS_PENDING);
        sURLMatcher.addURI("sms", "icc", SMS_ALL_ICC);
        sURLMatcher.addURI("sms", "icc/#", SMS_ICC);
        sURLMatcher.addURI("sms", "icc_subId/#", SMS_ALL_ICC_SUBID);
        sURLMatcher.addURI("sms", "icc_subId/#/#", SMS_ICC_SUBID);
        //we keep these for not breaking old applications
        sURLMatcher.addURI("sms", "sim", SMS_ALL_ICC);
        sURLMatcher.addURI("sms", "sim/#", SMS_ICC);
    }

    /**
     * Returns true if the match is a potentially accessing restricted messages by reading a broad
     * range of messages, e.g. all messages, inbox, sent, draft, outbox, etc.
     */
    private static boolean isAccessingPotentiallyRestrictedMessages(int match) {
        return match == SMS_ALL
                || match == SMS_INBOX
                || match == SMS_SENT
                || match == SMS_DRAFT
                || match == SMS_OUTBOX
                || match == SMS_FAILED
                || match == SMS_QUEUED
                || match == SMS_UNDELIVERED
                || match == SMS_CONVERSATIONS
                || match == SMS_ATTACHMENT
                || match == SMS_STATUS_PENDING
                || match == SMS_ALL_ICC
                || match == SMS_ALL_ICC_SUBID;
    }

    /**
     * These methods can be overridden in a subclass for testing SmsProvider using an
     * in-memory database.
     */
    SQLiteDatabase getReadableDatabase(int match) {
        return getDBOpenHelper(match).getReadableDatabase();
    }

    SQLiteDatabase getWritableDatabase(int match) {
        return  getDBOpenHelper(match).getWritableDatabase();
    }

    private BroadcastReceiver mUserIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_USER_REMOVED:
                    UserHandle userToBeRemoved  = intent.getParcelableExtra(Intent.EXTRA_USER,
                            UserHandle.class);
                    UserManager userManager = context.getSystemService(UserManager.class);
                    if ((userToBeRemoved == null) || (userManager == null) ||
                            (!userManager.isManagedProfile(userToBeRemoved.getIdentifier()))) {
                        // Do not delete SMS if removed profile is not managed profile.
                        return;
                    }

                    if (!userManager.isUserUnlocked()) {
                        Log.d(TAG, "Received ACTION_USER_REMOVED for managed profile: "
                                + "Cannot delete SMS now as user is locked.");
                        mUsersRemovedBeforeUnlockList.add(userToBeRemoved);
                        return;
                    }

                    Log.d(TAG, "Received ACTION_USER_REMOVED for managed profile: Deleting SMS.");
                    deleteManagedProfileMessages(userToBeRemoved);
                    break;
                case Intent.ACTION_USER_UNLOCKED: {
                    for (UserHandle user : mUsersRemovedBeforeUnlockList) {
                        deleteManagedProfileMessages(user);
                    }
                    mUsersRemovedBeforeUnlockList.clear();
                    break;
                }
            }
        }
    };

    private void deleteManagedProfileMessages(UserHandle userToBeRemoved) {
        Log.d(TAG, "deleteManagedProfileMessages: userToBeRemoved="
                + userToBeRemoved.getIdentifier());
        // Deleting SMS related to managed profile.
        Uri uri = Sms.CONTENT_URI;
        int match = sURLMatcher.match(uri);
        SQLiteDatabase db = getWritableDatabase(match);

        final long token = Binder.clearCallingIdentity();
        String selectionBySubIds;
        try {
            // Filter SMS based on subId.
            selectionBySubIds = ProviderUtil.getSelectionBySubIds(getContext(),
                    userToBeRemoved, TABLE_SMS);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (selectionBySubIds == null) {
            // No subscriptions associated with user, return.
            Log.d(TAG, "deleteManagedProfileMessages: "
                    + "no subscriptions associated with user.");
            return;
        }

        int count = db.delete(TABLE_SMS, selectionBySubIds, null);
        if (count != 0) {
            // Don't update threads unless something changed.
            MmsSmsDatabaseHelper.updateThreads(db, selectionBySubIds, null);
            notifyChange(true, uri, getCallingPackage());
        }
    }
}
