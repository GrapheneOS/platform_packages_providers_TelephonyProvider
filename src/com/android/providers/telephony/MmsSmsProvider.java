/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.AppOpsManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.BaseColumns;
import android.provider.Telephony;
import android.provider.Telephony.CanonicalAddressesColumns;
import android.provider.Telephony.Mms;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.MmsSms.PendingMessages;
import android.provider.Telephony.ReadRestriction;
import android.provider.Telephony.ReadRestriction.ReadRestrictionValues;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Sms.Conversations;
import android.provider.Telephony.Threads;
import android.provider.Telephony.ThreadsColumns;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.SmsApplication;
import com.android.internal.telephony.TelephonyStatsLog;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.util.TelephonyUtils;

import com.google.android.mms.pdu.PduHeaders;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * This class provides the ability to query the MMS and SMS databases
 * at the same time, mixing messages from both in a single thread
 * (A.K.A. conversation).
 *
 * A virtual column, MmsSms.TYPE_DISCRIMINATOR_COLUMN, may be
 * requested in the projection for a query.  Its value is either "mms"
 * or "sms", depending on whether the message represented by the row
 * is an MMS message or an SMS message, respectively.
 *
 * This class also provides the ability to find out what addresses
 * participated in a particular thread.  It doesn't support updates
 * for either of these.
 *
 * This class provides a way to allocate and retrieve thread IDs.
 * This is done atomically through a query.  There is no insert URI
 * for this.
 *
 * Finally, this class provides a way to delete or update all messages
 * in a thread.
 */
public class MmsSmsProvider extends ContentProvider {
    private static final UriMatcher URI_MATCHER =
            new UriMatcher(UriMatcher.NO_MATCH);
    private static final String LOG_TAG = "MmsSmsProvider";
    private static final boolean DEBUG = false;

    // ThreadLocal to store the access restriction state of the calling thread.
    // This allows static helper methods to access the state without propagating it through parameters.
    private static final ThreadLocal<Boolean> sAccessRestricted = ThreadLocal.withInitial(() -> false);

    private static void configureStrictQueryBuilder(SQLiteQueryBuilder qb) {
        if (sAccessRestricted.get()) {
            // Enable strict mode to validate columns against projection map and prevent SQL
            // injection in WHERE clauses.
            qb.setStrict(true);
            // Enable strict grammar check to validate SQL syntax and prevent syntax-based
            // injections (e.g. mismatched parentheses).
            qb.setStrictGrammar(true);
        }
    }
    private static final int MULTIPLE_THREAD_IDS_FOUND = TelephonyStatsLog
        .MMS_SMS_PROVIDER_GET_THREAD_ID_FAILED__FAILURE_CODE__FAILURE_MULTIPLE_THREAD_IDS_FOUND;
    private static final int FAILURE_FIND_OR_CREATE_THREAD_ID_SQL =
        TelephonyStatsLog
        .MMS_SMS_PROVIDER_GET_THREAD_ID_FAILED__FAILURE_CODE__FAILURE_FIND_OR_CREATE_THREAD_ID_SQL;

    private static final String NO_DELETES_INSERTS_OR_UPDATES =
            "MmsSmsProvider does not support deletes, inserts, or updates for this URI.";
    private static final int URI_CONVERSATIONS                     = 0;
    private static final int URI_CONVERSATIONS_MESSAGES            = 1;
    private static final int URI_CONVERSATIONS_RECIPIENTS          = 2;
    private static final int URI_MESSAGES_BY_PHONE                 = 3;
    private static final int URI_THREAD_ID                         = 4;
    private static final int URI_CANONICAL_ADDRESS                 = 5;
    private static final int URI_PENDING_MSG                       = 6;
    private static final int URI_COMPLETE_CONVERSATIONS            = 7;
    private static final int URI_UNDELIVERED_MSG                   = 8;
    private static final int URI_CONVERSATIONS_SUBJECT             = 9;
    private static final int URI_NOTIFICATIONS                     = 10;
    private static final int URI_OBSOLETE_THREADS                  = 11;
    private static final int URI_DRAFT                             = 12;
    private static final int URI_CANONICAL_ADDRESSES               = 13;
    private static final int URI_SEARCH                            = 14;
    private static final int URI_SEARCH_SUGGEST                    = 15;
    private static final int URI_FIRST_LOCKED_MESSAGE_ALL          = 16;
    private static final int URI_FIRST_LOCKED_MESSAGE_BY_THREAD_ID = 17;
    private static final int URI_MESSAGE_ID_TO_THREAD              = 18;

    /**
     * the name of the table that is used to store the queue of
     * messages(both MMS and SMS) to be sent/downloaded.
     */
    public static final String TABLE_PENDING_MSG = "pending_msgs";

    /**
     * the name of the table that is used to store the canonical addresses for both SMS and MMS.
     */
    static final String TABLE_CANONICAL_ADDRESSES = "canonical_addresses";

    /**
     * the name of the table that is used to store the conversation threads.
     */
    static final String TABLE_THREADS = "threads";

    // These constants are used to construct union queries across the
    // MMS and SMS base tables.

    // These are the columns that appear in both the MMS ("pdu") and
    // SMS ("sms") message tables.
    private static final String[] MMS_SMS_COLUMNS =
            { BaseColumns._ID, Mms.DATE, Mms.DATE_SENT, Mms.READ, Mms.THREAD_ID, Mms.LOCKED,
                    Mms.SUBSCRIPTION_ID, Mms.TRANSACTION_ID };

    // These are the columns that appear only in the MMS message
    // table.
    private static final String[] MMS_ONLY_COLUMNS = {
        Mms.CONTENT_CLASS, Mms.CONTENT_LOCATION, Mms.CONTENT_TYPE,
        Mms.DELIVERY_REPORT, Mms.EXPIRY, Mms.MESSAGE_CLASS, Mms.MESSAGE_ID,
        Mms.MESSAGE_SIZE, Mms.MESSAGE_TYPE, Mms.MESSAGE_BOX, Mms.PRIORITY,
        Mms.READ_STATUS, Mms.RESPONSE_STATUS, Mms.RESPONSE_TEXT,
        Mms.RETRIEVE_STATUS, Mms.RETRIEVE_TEXT_CHARSET, Mms.REPORT_ALLOWED,
        Mms.READ_REPORT, Mms.STATUS, Mms.SUBJECT, Mms.SUBJECT_CHARSET,
        Mms.MMS_VERSION, Mms.TEXT_ONLY };

    // These are the columns that appear only in the SMS message
    // table.
    private static final String[] SMS_ONLY_COLUMNS =
            { "address", "body", "person", "reply_path_present",
              "service_center", "status", "subject", "type", "error_code" };

    // These are all the columns that appear in the "threads" table.
    private static final String[] THREADS_COLUMNS = {
        BaseColumns._ID,
        ThreadsColumns.DATE,
        ThreadsColumns.RECIPIENT_IDS,
        ThreadsColumns.MESSAGE_COUNT
    };

    private static final String[] CANONICAL_ADDRESSES_COLUMNS_1 =
            new String[] { CanonicalAddressesColumns.ADDRESS };

    private static final String[] CANONICAL_ADDRESSES_COLUMNS_2 =
            new String[] { CanonicalAddressesColumns._ID,
                    CanonicalAddressesColumns.ADDRESS };

    // These are all the columns that appear in the MMS and SMS
    // message tables.
    private static final String[] UNION_COLUMNS =
            new String[MMS_SMS_COLUMNS.length
                       + MMS_ONLY_COLUMNS.length
                       + SMS_ONLY_COLUMNS.length];

    // These are all the columns that appear in the MMS table.
    private static final Set<String> MMS_COLUMNS = new HashSet<String>();

    // These are all the columns that appear in the SMS table.
    private static final Set<String> SMS_COLUMNS = new HashSet<String>();

    private static final String VND_ANDROID_DIR_MMS_SMS =
            "vnd.android-dir/mms-sms";

    private static final String[] ID_PROJECTION = { BaseColumns._ID };

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private static final String[] SEARCH_STRING = new String[1];
    private static final String SEARCH_QUERY = "SELECT snippet(words, '', ' ', '', 1, 1) as " +
            "snippet FROM words WHERE index_text MATCH ?";

    private static final String SMS_CONVERSATION_CONSTRAINT = "(" +
            Sms.TYPE + " != " + Sms.MESSAGE_TYPE_DRAFT + ")";

    private static final String MMS_CONVERSATION_CONSTRAINT = "(" +
            Mms.MESSAGE_BOX + " != " + Mms.MESSAGE_BOX_DRAFTS + " AND (" +
            Mms.MESSAGE_TYPE + " = " + PduHeaders.MESSAGE_TYPE_SEND_REQ + " OR " +
            Mms.MESSAGE_TYPE + " = " + PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF + " OR " +
            Mms.MESSAGE_TYPE + " = " + PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND + "))";

    private static String getTextSearchQuery(String smsTable, String pduTable,
            boolean canReadRestrictedMessages, String otpFilter, String selectionBySubIds) {

        // Append read restriction clause to the query if the caller can't read restricted messages.
        String smsQueryReadRestrictionClause =
                Flags.secureAccessToRestrictedRcsMessages() && !canReadRestrictedMessages
                        ? (" AND " + getRestrictedTextSearchQueryWhereClause(smsTable)) : "";

        String smsOtpClause = !TextUtils.isEmpty(otpFilter) ? " AND " + otpFilter + " " : "";
        String smsSubIdClause = !TextUtils.isEmpty(selectionBySubIds)
                ? " AND " + smsTable + "." + selectionBySubIds.replace("'", "") + " " : "";

        // Search on the words table but return the rows from the corresponding sms table
        final String smsQuery = "SELECT "
                + smsTable + "._id AS _id,"
                + "thread_id,"
                + "address,"
                + "body,"
                + "date,"
                + "date_sent,"
                + "index_text,"
                + "words._id "
                + "FROM " + smsTable + ",words "
                + "WHERE (index_text MATCH ? "
                + "AND " + smsTable + "._id=words.source_id "
                + smsQueryReadRestrictionClause
                + smsOtpClause
                + smsSubIdClause
                + "AND words.table_to_use=1)";

        // Append read restriction clause to the query if the caller can't read restricted messages.
        String mmsQueryReadRestrictionClause =
                Flags.secureAccessToRestrictedRcsMessages() && !canReadRestrictedMessages
                        ? (" AND " + getRestrictedTextSearchQueryWhereClause(pduTable)) : "";
        String mmsSubIdClause = !TextUtils.isEmpty(selectionBySubIds)
                ? " AND " + pduTable + "." + selectionBySubIds.replace("'", "") + " " : "";
        // Search on the words table but return the rows from the corresponding parts table
        final String mmsQuery = "SELECT "
                + pduTable + "._id,"
                + "thread_id,"
                + "addr.address,"
                + "part.text AS body,"
                + pduTable + ".date,"
                + pduTable + ".date_sent,"
                + "index_text,"
                + "words._id "
                + "FROM " + pduTable + ",part,addr,words "
                + "WHERE ((part.mid=" + pduTable + "._id) "
                + "AND (addr.msg_id=" + pduTable + "._id) "
                + "AND (addr.type=" + PduHeaders.TO + ") "
                + "AND (part.ct='text/plain') "
                + "AND (index_text MATCH ?) "
                + "AND (part._id = words.source_id) "
                + mmsQueryReadRestrictionClause
                + mmsSubIdClause
                + "AND (words.table_to_use=2))";

        // This code queries the sms and mms tables and returns a unified result set
        // of text matches.  We query the sms table which is pretty simple.  We also
        // query the pdu, part and addr table to get the mms result.  Note we're
        // using a UNION so we have to have the same number of result columns from
        // both queries.
        return smsQuery + " UNION " + mmsQuery + " "
                + "GROUP BY thread_id "
                + "ORDER BY thread_id ASC, date DESC";
    }

    /**
     *  Returns the WHERE clause with {@link ReadRestriction.RESTRICTED} column set to 0 to filter
     *  out restricted messages.
     *
     * @param table The name of the table to read the {@link ReadRestriction.RESTRICTED}
     *        column from.
     */
    private static String getRestrictedTextSearchQueryWhereClause(String table) {
        return " (" + table + "." + ReadRestriction.RESTRICTED + " = 0) ";
    }

    private String getOtpRedactedThreadsTable(String otpFilter) {
        long startOfCurrentMinuteInMs = (System.currentTimeMillis() / TimeUnit.MINUTES.toMillis(1))
                * TimeUnit.MINUTES.toMillis(1);
        long otpCutoff = startOfCurrentMinuteInMs - ProviderUtil.OTP_HIDING_TIME_MS;
        String needsFilteringColumn = "(date > " + otpCutoff + " AND EXISTS (SELECT 1 FROM sms"
                + " WHERE thread_id = threads._id AND body = threads.snippet"
                + " AND NOT " + otpFilter + ")) AS needs_filtering";

        String[] columns = {
                Threads._ID,
                Threads.DATE,
                Threads.MESSAGE_COUNT,
                Threads.RECIPIENT_IDS,
                "CASE WHEN needs_filtering THEN '' ELSE " + Threads.SNIPPET + " END AS "
                        + Threads.SNIPPET,
                "CASE WHEN needs_filtering THEN 0 ELSE " + Threads.SNIPPET_CHARSET + " END AS "
                        + Threads.SNIPPET_CHARSET,
                Threads.READ,
                Threads.ARCHIVED,
                Threads.TYPE,
                Threads.ERROR,
                Threads.HAS_ATTACHMENT,
                Threads.SUBSCRIPTION_ID,
                Threads.READ_RESTRICTION
        };

        return "(SELECT " + String.join(", ", columns) + " FROM (SELECT *, " + needsFilteringColumn
                + " FROM " + TABLE_THREADS + ")) AS " + TABLE_THREADS;
    }

    private static final String AUTHORITY = "mms-sms";

    static {
        URI_MATCHER.addURI(AUTHORITY, "conversations", URI_CONVERSATIONS);
        URI_MATCHER.addURI(AUTHORITY, "complete-conversations", URI_COMPLETE_CONVERSATIONS);

        // In these patterns, "#" is the thread ID.
        URI_MATCHER.addURI(
                AUTHORITY, "conversations/#", URI_CONVERSATIONS_MESSAGES);
        URI_MATCHER.addURI(
                AUTHORITY, "conversations/#/recipients",
                URI_CONVERSATIONS_RECIPIENTS);

        URI_MATCHER.addURI(
                AUTHORITY, "conversations/#/subject",
                URI_CONVERSATIONS_SUBJECT);

        // URI for deleting obsolete threads.
        URI_MATCHER.addURI(AUTHORITY, "conversations/obsolete", URI_OBSOLETE_THREADS);

        URI_MATCHER.addURI(
                AUTHORITY, "messages/byphone/*",
                URI_MESSAGES_BY_PHONE);

        // In this pattern, two query parameter names are expected:
        // "subject" and "recipient."  Multiple "recipient" parameters
        // may be present.
        URI_MATCHER.addURI(AUTHORITY, "threadID", URI_THREAD_ID);

        // Use this pattern to query the canonical address by given ID.
        URI_MATCHER.addURI(AUTHORITY, "canonical-address/#", URI_CANONICAL_ADDRESS);

        // Use this pattern to query all canonical addresses.
        URI_MATCHER.addURI(AUTHORITY, "canonical-addresses", URI_CANONICAL_ADDRESSES);

        URI_MATCHER.addURI(AUTHORITY, "search", URI_SEARCH);
        URI_MATCHER.addURI(AUTHORITY, "searchSuggest", URI_SEARCH_SUGGEST);

        // In this pattern, two query parameters may be supplied:
        // "protocol" and "message." For example:
        //   content://mms-sms/pending?
        //       -> Return all pending messages;
        //   content://mms-sms/pending?protocol=sms
        //       -> Only return pending SMs;
        //   content://mms-sms/pending?protocol=mms&message=1
        //       -> Return the the pending MM which ID equals '1'.
        //
        URI_MATCHER.addURI(AUTHORITY, "pending", URI_PENDING_MSG);

        // Use this pattern to get a list of undelivered messages.
        URI_MATCHER.addURI(AUTHORITY, "undelivered", URI_UNDELIVERED_MSG);

        // Use this pattern to see what delivery status reports (for
        // both MMS and SMS) have not been delivered to the user.
        URI_MATCHER.addURI(AUTHORITY, "notifications", URI_NOTIFICATIONS);

        URI_MATCHER.addURI(AUTHORITY, "draft", URI_DRAFT);

        URI_MATCHER.addURI(AUTHORITY, "locked", URI_FIRST_LOCKED_MESSAGE_ALL);

        URI_MATCHER.addURI(AUTHORITY, "locked/#", URI_FIRST_LOCKED_MESSAGE_BY_THREAD_ID);

        URI_MATCHER.addURI(AUTHORITY, "messageIdToThread", URI_MESSAGE_ID_TO_THREAD);
        initializeColumnSets();
    }

    private SQLiteOpenHelper mOpenHelper;

    private boolean mUseStrictPhoneNumberComparation;

    // Call() methods and parameters
    private static final String METHOD_IS_RESTORING = "is_restoring";
    private static final String IS_RESTORING_KEY = "restoring";
    private static final String METHOD_GARBAGE_COLLECT = "garbage_collect";
    private static final String DO_DELETE = "delete";

    @Override
    public boolean onCreate() {
        setAppOps(AppOpsManager.OP_READ_SMS, AppOpsManager.OP_WRITE_SMS);
        mOpenHelper = MmsSmsDatabaseHelper.getInstanceForCe(getContext());
        mUseStrictPhoneNumberComparation =
            getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_use_strict_phone_number_comparation);
        TelephonyBackupAgent.DeferredSmsMmsRestoreService.startIfFilesExist(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        long startTime = SystemClock.elapsedRealtime();
        Cursor cursor = null;

        int targetUri = ProviderMetricsLogger.TARGET_URI_CONVERSATIONS;
        int match = URI_MATCHER.match(uri);
        if (match == URI_THREAD_ID) {
            targetUri = ProviderMetricsLogger.TARGET_URI_THREAD_ID_RESOLUTION;
        }

        final int callerUid = Binder.getCallingUid();
        final boolean accessRestricted = ProviderUtil.isAccessRestricted(
                getContext(), getCallingPackage(), callerUid);
        // Store the caller's restriction state in ThreadLocal for use in static helpers.
        sAccessRestricted.set(accessRestricted);

        try {
            cursor = queryInternal(uri, projection, selection, selectionArgs, sortOrder);

            if (cursor != null) {
                cursor.getCount(); // Force evaluation
                ProviderMetricsLogger.logOperationLatency(
                        getContext(),
                        ProviderMetricsLogger.OPERATION_QUERY,
                        targetUri,
                        startTime,
                        cursor.getCount());
            }
        } catch (SQLiteDatabaseLockedException e) { // Lock
            ProviderMetricsLogger.logDbLockContention(getContext(),
                    ProviderMetricsLogger.OPERATION_QUERY, targetUri);
            throw e;
        } catch (Exception e) {
            Log.e("ProviderMetrics", "Database operation failed", e);
            ProviderUtil.logRunningTelephonyProviderProcesses(getContext());
            throw e;
        } finally {
            // Always remove the ThreadLocal value to prevent memory leaks and state pollution
            // when the binder thread is returned to the pool.
            sAccessRestricted.remove();
        }
        return cursor;
    }

    /** Internal implementation of the database operation. */
    public Cursor queryInternal(Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        final int callerUid = Binder.getCallingUid();
        final UserHandle callerUserHandle = Binder.getCallingUserHandle();
        String callingPackage = getCallingPackage();

        // First check if restricted views of the "sms" and "pdu" tables should be used based on the
        // caller's identity. Only system, phone or the default sms app can have full access
        // of sms/mms data. For other apps, we present a restricted view which only contains sent
        // or received messages, without wap pushes.
        final boolean accessRestricted = ProviderUtil.isAccessRestricted(
                getContext(), getCallingPackage(), callerUid);
        final String pduTable = MmsProvider.getPduTable(accessRestricted);
        final String smsTable = SmsProvider.getSmsTable(accessRestricted);
        final boolean canReadRestrictedMessages = ProviderUtil.canReadRestrictedMessages(
                getContext(), callingPackage, callerUid);

        String otpFilter = getOtpFilter(callerUid, callingPackage, callerUserHandle);

        Log.v(LOG_TAG, "#query: canReadRestrictedMessages=" + canReadRestrictedMessages);

        // If access is restricted, we don't allow subqueries in the query.
        if (accessRestricted) {
            try {
                SqlQueryChecker.checkQueryParametersForSubqueries(projection, selection, sortOrder);
            } catch (IllegalArgumentException e) {
                Log.w(LOG_TAG, "Query rejected: " + e.getMessage());
                return null;
            }
        }

        try {
            SqlQueryChecker.checkSelection(selection);
        } catch (IllegalArgumentException e) {
            Log.w(LOG_TAG, "Query rejected: " + e.getMessage());
            return null;
        }

        String selectionBySubIds;
        final long token = Binder.clearCallingIdentity();
        try {
            // Filter MMS/SMS based on subId
            selectionBySubIds = ProviderUtil.getSelectionBySubIds(getContext(), callerUserHandle, null);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        if (mOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) mOpenHelper).addDatabaseOpeningDebugLog(
                    callingPackage + ";MmsSmsProvider.query;" + uri, true);
        }
        Cursor cursor = null;
        Cursor emptyCursor = new MatrixCursor((projection == null) ?
                (new String[] {}) : projection);
        final int match = URI_MATCHER.match(uri);
        switch (match) {
            case URI_COMPLETE_CONVERSATIONS:
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return empty cursor.
                    return emptyCursor;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                cursor = getCompleteConversations(projection, selection, sortOrder, smsTable,
                        pduTable, canReadRestrictedMessages, otpFilter);
                break;
            case URI_CONVERSATIONS:
                String simple = uri.getQueryParameter("simple");
                if ((simple != null) && simple.equals("true")) {
                    String threadType = uri.getQueryParameter("thread_type");
                    if (!TextUtils.isEmpty(threadType)) {
                        try {
                            Integer.parseInt(threadType);
                            selection = concatSelections(
                                    selection, Threads.TYPE + "=" + threadType);
                        } catch (NumberFormatException ex) {
                            Log.e(LOG_TAG, "Thread type must be int");
                            // return empty cursor
                            break;
                        }
                    }
                    if (selectionBySubIds == null) {
                        // No subscriptions associated with user, return empty cursor.
                        Log.d(LOG_TAG, "URI_CONVERSATIONS - subId not associated with user.");
                        return emptyCursor;
                    }
                    selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                    cursor = getSimpleConversations(
                            projection, selection, selectionArgs,
                            canReadRestrictedMessages, otpFilter);
                } else {
                    if (selectionBySubIds == null) {
                        // No subscriptions associated with user, return empty cursor.
                        return emptyCursor;
                    }
                    selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                    cursor = getConversations(
                            projection, selection, sortOrder, smsTable, pduTable,
                            canReadRestrictedMessages, otpFilter);
                }
                break;
            case URI_CONVERSATIONS_MESSAGES:
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return empty cursor.
                    return emptyCursor;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                cursor = getConversationMessages(uri.getPathSegments().get(1), projection,
                        selection, sortOrder, smsTable, pduTable, canReadRestrictedMessages,
                        otpFilter);
                break;
            case URI_CONVERSATIONS_RECIPIENTS:
                if (selectionBySubIds == null) {
                    Log.d(LOG_TAG,
                            "URI_CONVERSATIONS_RECIPIENTS - subId not associated with user.");
                    return emptyCursor;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                cursor = getConversationById(
                        uri.getPathSegments().get(1), projection, selection,
                        selectionArgs, sortOrder, canReadRestrictedMessages, otpFilter);
                break;
            case URI_CONVERSATIONS_SUBJECT:
                if (selectionBySubIds == null) {
                    Log.d(LOG_TAG, "URI_CONVERSATIONS_SUBJECT - subId not associated with user.");
                    return emptyCursor;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                cursor = getConversationById(
                        uri.getPathSegments().get(1), projection, selection,
                        selectionArgs, sortOrder, canReadRestrictedMessages, otpFilter);
                break;
            case URI_MESSAGES_BY_PHONE:
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return emptyCursor.
                    return emptyCursor;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                cursor = getMessagesByPhoneNumber(
                        uri.getPathSegments().get(2), projection, selection, sortOrder, smsTable,
                        pduTable, canReadRestrictedMessages, otpFilter);
                break;
            case URI_THREAD_ID:
                List<String> recipients = uri.getQueryParameters("recipient");

                cursor = getThreadId(recipients, canReadRestrictedMessages);
                break;
            case URI_CANONICAL_ADDRESS: {
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return empty cursor.
                    return emptyCursor;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);
                if (Flags.secureAccessToRestrictedRcsMessages() && !canReadRestrictedMessages) {
                    selection = DatabaseUtils.concatenateWhere(selection,
                    CanonicalAddressesColumns.READ_RESTRICTION + " & "+
                    ReadRestrictionValues.READ_RESTRICTION_RESTRICTED + " = 0");
                }

                String extraSelection = "_id=" + uri.getPathSegments().get(1);
                String finalSelection = TextUtils.isEmpty(selection)
                        ? extraSelection : extraSelection + " AND " + selection;

                cursor = db.query(TABLE_CANONICAL_ADDRESSES,
                        CANONICAL_ADDRESSES_COLUMNS_1,
                        finalSelection,
                        selectionArgs,
                        null, null,
                        sortOrder);
                break;
            }
            case URI_CANONICAL_ADDRESSES:
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return empty cursor.
                    return emptyCursor;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);
                if (Flags.secureAccessToRestrictedRcsMessages() && !canReadRestrictedMessages) {
                    selection = DatabaseUtils.concatenateWhere(selection,
                    CanonicalAddressesColumns.READ_RESTRICTION + " & " +
                    ReadRestrictionValues.READ_RESTRICTION_RESTRICTED + " = 0");
                }

                cursor = db.query(TABLE_CANONICAL_ADDRESSES,
                        CANONICAL_ADDRESSES_COLUMNS_2,
                        selection,
                        selectionArgs,
                        null, null,
                        sortOrder);
                break;
            case URI_SEARCH_SUGGEST: {
                if(Flags.secureAccessToRestrictedRcsMessages()) {
                    if (!SmsApplication.isDefaultSmsApplication(getContext(), callingPackage)) {
                        throw new UnsupportedOperationException(
                                "URI_SEARCH_SUGGEST is not supported for non-default SMS app");
                    }
                }
                // Although the Flags.secureAccessToRestrictedRcsMessages() check above should
                // restrict untrusted apps, this OTP filter check is performed unconditionally as
                // a safety measure to ensure security even if the flag is disabled.
                if (!TextUtils.isEmpty(otpFilter)) {
                    return emptyCursor;
                }
                if (selectionBySubIds == null) {
                    Log.d(LOG_TAG, "URI_CONVERSATIONS_SUGGEST - subId not associated with user.");
                    return emptyCursor;
                }

                SEARCH_STRING[0] = uri.getQueryParameter("pattern") + '*' ;

                // find the words which match the pattern using the snippet function.  The
                // snippet function parameters mainly describe how to format the result.
                // See http://www.sqlite.org/fts3.html#section_4_2 for details.
                if (       sortOrder != null
                        || selection != null
                        || selectionArgs != null
                        || projection != null) {
                    throw new IllegalArgumentException(
                            "do not specify sortOrder, selection, selectionArgs, or projection" +
                            "with this query");
                }

                String searchQuery = SEARCH_QUERY + " AND " + selectionBySubIds.replace("'", "")
                        + " ORDER BY snippet LIMIT 50;";
                cursor = db.rawQuery(searchQuery, SEARCH_STRING);
                break;
            }
            case URI_MESSAGE_ID_TO_THREAD: {
                // Given a message ID and an indicator for SMS vs. MMS return
                // the thread id of the corresponding thread.
                try {
                    long id = Long.parseLong(uri.getQueryParameter("row_id"));
                    switch (Integer.parseInt(uri.getQueryParameter("table_to_use"))) {
                        case 1:  // sms
                            cursor = db.query(
                                smsTable,
                                new String[] { "thread_id" },
                                "_id=?",
                                new String[] { String.valueOf(id) },
                                null,
                                null,
                                null);
                            break;
                        case 2:  // mms
                            String mmsQuery = "SELECT thread_id "
                                    + "FROM " + pduTable + ",part "
                                    + "WHERE ((part.mid=" + pduTable + "._id) "
                                    + "AND " + "(part._id=?))";
                            cursor = db.rawQuery(mmsQuery, new String[] { String.valueOf(id) });
                            break;
                    }
                } catch (NumberFormatException ex) {
                    // ignore... return empty cursor
                }
                break;
            }
            case URI_SEARCH: {
                if (       sortOrder != null
                        || selection != null
                        || selectionArgs != null
                        || projection != null) {
                    throw new IllegalArgumentException(
                            "do not specify sortOrder, selection, selectionArgs, or projection" +
                            "with this query");
                }

                if (selectionBySubIds == null) {
                    Log.d(LOG_TAG, "URI_SEARCH - subId not associated with user.");
                    return emptyCursor;
                }

                String searchString = uri.getQueryParameter("pattern") + "*";

                try {
                    cursor = db.rawQuery(getTextSearchQuery(smsTable, pduTable,
                        canReadRestrictedMessages, otpFilter, selectionBySubIds),
                            new String[] { searchString, searchString });
                } catch (Exception ex) {
                    Log.e(LOG_TAG, "got exception: " + ex.toString());
                }
                break;
            }
            case URI_PENDING_MSG: {
                String protoName = uri.getQueryParameter("protocol");
                String msgId = uri.getQueryParameter("message");
                int proto = TextUtils.isEmpty(protoName) ? -1
                        : (protoName.equals("sms") ? MmsSms.SMS_PROTO : MmsSms.MMS_PROTO);

                String extraSelection = (proto != -1) ?
                        (PendingMessages.PROTO_TYPE + "=" + proto) : " 0=0 ";
                if (!TextUtils.isEmpty(msgId)) {
                    try {
                        Long.parseLong(msgId);
                        extraSelection += " AND " + PendingMessages.MSG_ID + "=" + msgId;
                    } catch(NumberFormatException ex) {
                        Log.e(LOG_TAG, "MSG ID must be a Long.");
                        // return empty cursor
                        break;
                    }
                }
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return empty cursor.
                    return emptyCursor;
                }
                // In PendingMessages table, SUBSCRIPTION_ID column name is pending_sub_id.
                selectionBySubIds = "pending_" + selectionBySubIds;
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                String finalSelection = TextUtils.isEmpty(selection)
                        ? extraSelection : ("(" + extraSelection + ") AND " + selection);
                String finalOrder = TextUtils.isEmpty(sortOrder)
                        ? PendingMessages.DUE_TIME : sortOrder;
                cursor = db.query(TABLE_PENDING_MSG, null,
                        finalSelection, selectionArgs, null, null, finalOrder);
                break;
            }
            case URI_UNDELIVERED_MSG: {
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return empty cursor.
                    return emptyCursor;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                cursor = getUndeliveredMessages(projection, selection,
                        selectionArgs, sortOrder, smsTable, pduTable, canReadRestrictedMessages);
                break;
            }
            case URI_DRAFT: {
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return empty cursor.
                    return emptyCursor;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                cursor = getDraftThread(projection, selection, sortOrder, smsTable, pduTable,
                    canReadRestrictedMessages);
                break;
            }
            case URI_FIRST_LOCKED_MESSAGE_BY_THREAD_ID: {
                long threadId;
                try {
                    threadId = Long.parseLong(uri.getLastPathSegment());
                } catch (NumberFormatException e) {
                    Log.e(LOG_TAG, "Thread ID must be a long.");
                    break;
                }
                selection = DatabaseUtils.concatenateWhere(selection, ("thread_id=" + threadId));

                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return empty cursor.
                    return emptyCursor;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                cursor = getFirstLockedMessage(projection, selection, sortOrder,
                        smsTable, pduTable, canReadRestrictedMessages);
                break;
            }
            case URI_FIRST_LOCKED_MESSAGE_ALL: {
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return empty cursor.
                    return emptyCursor;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                cursor = getFirstLockedMessage(
                        projection, selection, sortOrder, smsTable, pduTable,
                        canReadRestrictedMessages);
                break;
            }
            default:
                throw new IllegalStateException("Unrecognized URI:" + uri);
        }

        if (cursor != null) {
            cursor.setNotificationUri(getContext().getContentResolver(), MmsSms.CONTENT_URI);
        }
        return cursor;
    }

    /**
     * Return the canonical address ID for this address.
     */
    private long getSingleAddressId(String address) {
        boolean isEmail = Mms.isEmailAddress(address);
        boolean isPhoneNumber = Mms.isPhoneNumber(address);

        // We lowercase all email addresses, but not addresses that aren't numbers, because
        // that would incorrectly turn an address such as "My Vodafone" into "my vodafone"
        // and the thread title would be incorrect when displayed in the UI.
        String refinedAddress = isEmail ? address.toLowerCase(Locale.ROOT) : address;

        String selection = "address=?";
        String[] selectionArgs;
        long retVal = -1L;
        int minMatch =
            getContext().getResources().getInteger(
                    com.android.internal.R.integer.config_phonenumber_compare_min_match);

        if (!isPhoneNumber) {
            selectionArgs = new String[] { refinedAddress };
        } else {
            selection += " OR PHONE_NUMBERS_EQUAL(address, ?, " +
                        (mUseStrictPhoneNumberComparation ? "1)" : "0, " + minMatch + ")");
            selectionArgs = new String[] { refinedAddress, refinedAddress };
        }

        Cursor cursor = null;

        try {
            SQLiteDatabase db = mOpenHelper.getReadableDatabase();
            cursor = db.query(
                    "canonical_addresses", ID_PROJECTION,
                    selection, selectionArgs, null, null, null);

            if (cursor.getCount() == 0) {
                // TODO (b/256992531): Currently, one sim card is set as default sms subId in work
                //  profile. Default sms subId should be updated based on user pref.
                int subId = SmsManager.getDefaultSmsSubscriptionId();
                ContentValues contentValues = new ContentValues(1);
                contentValues.put(CanonicalAddressesColumns.ADDRESS, refinedAddress);
                contentValues.put(CanonicalAddressesColumns.SUBSCRIPTION_ID, subId);
                if (Flags.secureAccessToRestrictedRcsMessages()) {
                    // New canonical addresses should be restricted by default. They become
                    // unrestricted when any thread that they belong to becomes unrestricted.
                    contentValues.put(CanonicalAddressesColumns.READ_RESTRICTION,
                            ReadRestrictionValues.READ_RESTRICTION_RESTRICTED);
                }

                db = mOpenHelper.getWritableDatabase();
                retVal = db.insert("canonical_addresses",
                        CanonicalAddressesColumns.ADDRESS, contentValues);

                Log.d(LOG_TAG, "getSingleAddressId: insert new canonical_address for " +
                        /*address*/ "xxxxxx" + ", sub_id=" + subId + ", _id=" + retVal);

                return retVal;
            }

            if (cursor.moveToFirst()) {
                retVal = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return retVal;
    }

    /**
     * Return the canonical address IDs for these addresses.
     */
    private Set<Long> getAddressIds(List<String> addresses) {
        Set<Long> result = new HashSet<Long>(addresses.size());

        for (String address : addresses) {
            if (!address.equals(PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR)) {
                long id = getSingleAddressId(address);
                if (id != -1L) {
                    result.add(id);
                } else {
                    Log.e(LOG_TAG, "getAddressIds: address ID not found for " + address);
                }
            }
        }
        return result;
    }

    /**
     * Return a sorted array of the given Set of Longs.
     */
    private long[] getSortedSet(Set<Long> numbers) {
        int size = numbers.size();
        long[] result = new long[size];
        int i = 0;

        for (Long number : numbers) {
            result[i++] = number;
        }

        if (size > 1) {
            Arrays.sort(result);
        }

        return result;
    }

    /**
     * Return a String of the numbers in the given array, in order,
     * separated by spaces.
     */
    private String getSpaceSeparatedNumbers(long[] numbers) {
        int size = numbers.length;
        StringBuilder buffer = new StringBuilder();

        for (int i = 0; i < size; i++) {
            if (i != 0) {
                buffer.append(' ');
            }
            buffer.append(numbers[i]);
        }
        return buffer.toString();
    }

    /**
     * Insert a record for a new thread.
     */
    private void insertThread(String recipientIds, int numberOfRecipients) {
        ContentValues values = new ContentValues(5);

        long date = System.currentTimeMillis();
        values.put(ThreadsColumns.DATE, date - date % 1000);
        values.put(ThreadsColumns.RECIPIENT_IDS, recipientIds);
        // TODO (b/256992531): Currently, one sim card is set as default sms subId in work
        //  profile. Default sms subId should be updated based on user pref.
        values.put(ThreadsColumns.SUBSCRIPTION_ID, SmsManager.getDefaultSmsSubscriptionId());
        if (numberOfRecipients > 1) {
            values.put(Threads.TYPE, Threads.BROADCAST_THREAD);
        }
        values.put(ThreadsColumns.MESSAGE_COUNT, 0);
        if(Flags.secureAccessToRestrictedRcsMessages()) {
            // New threads are created as restricted by default. They can be downgraded to
            // unrestricted when an unrestricted message is inserted in the thread.
            values.put(ThreadsColumns.READ_RESTRICTION,
                    ReadRestrictionValues.READ_RESTRICTION_RESTRICTED);
        }

        long result = mOpenHelper.getWritableDatabase().insert(TABLE_THREADS, null, values);
        Log.d(LOG_TAG, "insertThread: created new thread_id " + result +
                " for recipientIds " + /*recipientIds*/ "xxxxxxx");

        getContext().getContentResolver().notifyChange(MmsSms.CONTENT_URI, null, true,
                UserHandle.USER_ALL);
    }

    private static final String THREAD_QUERY =
            "SELECT _id FROM threads " + "WHERE recipient_ids=?";

    /**
     * Return the threads with the given recipient IDs.
     *
     * @param db The database to query.
     * @param recipientIds The recipient IDs to query.
     * @param canReadRestrictedMessages Whether the caller can read restricted messages.
     * @return A cursor containing the threads with the given recipient IDs.
     */
    private static Cursor getThreads(SQLiteDatabase db, String recipientIds,
            boolean canReadRestrictedMessages) {
        if (Flags.secureAccessToRestrictedRcsMessages()) {
            final String[] projection = new String[] {ThreadsColumns._ID};
            final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            configureStrictQueryBuilder(qb);
            qb.setTables(TABLE_THREADS);
            qb.appendWhereStandalone(ThreadsColumns.RECIPIENT_IDS + " = ?");
            if (!canReadRestrictedMessages) {
                ReadRestriction.appendReadRestrictionToQuery(qb, TABLE_THREADS,
                    canReadRestrictedMessages);
            }
            return qb.query(db, projection, null, new String[] { recipientIds }, null, null, null);
        } else {
            return db.rawQuery(THREAD_QUERY, new String[] { recipientIds });
        }
    }

    /**
     * Return the thread ID for this list of
     * recipients IDs.  If no thread exists with this ID, create
     * one and return it.  Callers should always use
     * Threads.getThreadId to access this information.
     */

    private synchronized Cursor getThreadId(List<String> recipients,
            boolean canReadRestrictedMessages) {
        long startTime = SystemClock.elapsedRealtime();
        Cursor cursor = null;
        try {
            cursor = getThreadIdInternal(recipients, canReadRestrictedMessages);
            int count = 0;
            if (cursor != null) {
                count = cursor.getCount(); // Force evaluation
            }
            ProviderMetricsLogger.logOperationLatency(
                    getContext(),
                    ProviderMetricsLogger.OPERATION_QUERY,
                    ProviderMetricsLogger.TARGET_URI_THREAD_ID_RESOLUTION,
                    startTime,
                    count);
        } catch (SQLiteDatabaseLockedException e) { // Lock
            ProviderMetricsLogger.logDbLockContention(getContext(),
                    ProviderMetricsLogger.OPERATION_QUERY,
                    ProviderMetricsLogger.TARGET_URI_THREAD_ID_RESOLUTION);
            throw e;
        } catch (Exception e) {
            Log.e("ProviderMetrics", "Database operation failed", e);
            ProviderUtil.logRunningTelephonyProviderProcesses(getContext());
            throw e;
        }
        return cursor;
    }

    private synchronized Cursor getThreadIdInternal(List<String> recipients,
            boolean canReadRestrictedMessages) {
        // Read restriction does not need to be enforced when assembling addresses for a thread.
        // This method already verifies if the caller has the permission to create a new thread and
        // new canonical addresses will be created as restricted by default.
        Set<Long> addressIds = getAddressIds(recipients);
        String recipientIds = "";

        if (addressIds.size() == 0) {
            Log.e(LOG_TAG, "getThreadId: NO receipients specified -- NOT creating thread",
                    new Exception());
            TelephonyStatsLog.write(
                TelephonyStatsLog.MMS_SMS_PROVIDER_GET_THREAD_ID_FAILED,
                TelephonyStatsLog
                    .MMS_SMS_PROVIDER_GET_THREAD_ID_FAILED__FAILURE_CODE__FAILURE_NO_RECIPIENTS);
            return null;
        } else if (addressIds.size() == 1) {
            // optimize for size==1, which should be most of the cases
            for (Long addressId : addressIds) {
                recipientIds = Long.toString(addressId);
            }
        } else {
            recipientIds = getSpaceSeparatedNumbers(getSortedSet(addressIds));
        }

        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.d(LOG_TAG, "getThreadId: recipientIds (selectionArgs) =" +
                    /*recipientIds*/ "xxxxxxx");
        }

        String[] selectionArgs = new String[] { recipientIds };

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        db.beginTransaction();
        Cursor cursor = null;
        try {
            // Find the thread with the given recipients
            cursor = getThreads(db, recipientIds, canReadRestrictedMessages);
            if (cursor.getCount() == 0) {
                // If the caller doesn't have permission to access a restricted thread, or the
                // thread doesn't exist, don't allow them to create a new thread.
                if (Flags.secureAccessToRestrictedRcsMessages() && !canReadRestrictedMessages) {
                    return cursor;
                }

                // No thread with those recipients exists, so create the thread.
                cursor.close();

                Log.d(LOG_TAG, "getThreadId: create new thread_id for recipients " +
                        /*recipients*/ "xxxxxxxx");
                insertThread(recipientIds, recipients.size());

                // The thread was just created, now find it and return it.
                cursor = db.rawQuery(THREAD_QUERY, selectionArgs);
            }
            db.setTransactionSuccessful();
        } catch (Throwable ex) {
            Log.e(LOG_TAG, ex.getMessage(), ex);
            if (mOpenHelper instanceof MmsSmsDatabaseHelper) {
                ((MmsSmsDatabaseHelper) mOpenHelper).printDatabaseOpeningDebugLog();
            }
            TelephonyStatsLog.write(
                TelephonyStatsLog.MMS_SMS_PROVIDER_GET_THREAD_ID_FAILED,
                FAILURE_FIND_OR_CREATE_THREAD_ID_SQL);
        } finally {
            db.endTransaction();
        }

        if (cursor != null && cursor.getCount() > 1) {
            Log.w(LOG_TAG, "getThreadId: why is cursorCount=" + cursor.getCount());
            TelephonyStatsLog.write(
                TelephonyStatsLog.MMS_SMS_PROVIDER_GET_THREAD_ID_FAILED,
                MULTIPLE_THREAD_IDS_FOUND);
        }
        return cursor;
    }

    private static String concatSelections(String selection1, String selection2) {
        if (TextUtils.isEmpty(selection1)) {
            return selection2;
        } else if (TextUtils.isEmpty(selection2)) {
            return selection1;
        } else {
            return selection1 + " AND " + selection2;
        }
    }

    /**
     * If a null projection is given, return the union of all columns
     * in both the MMS and SMS messages tables.  Otherwise, return the
     * given projection.
     */
    private static String[] handleNullMessageProjection(
            String[] projection) {
        return projection == null ? UNION_COLUMNS : projection;
    }

    /**
     * If a null projection is given, return the set of all columns in
     * the threads table.  Otherwise, return the given projection.
     */
    private static String[] handleNullThreadsProjection(
            String[] projection) {
        return projection == null ? THREADS_COLUMNS : projection;
    }

    /**
     * If a null sort order is given, return "normalized_date ASC".
     * Otherwise, return the given sort order.
     */
    private static String handleNullSortOrder (String sortOrder) {
        return sortOrder == null ? "normalized_date ASC" : sortOrder;
    }

    /**
     * Return existing threads in the database.
     */
    private Cursor getSimpleConversations(String[] projection, String selection,
            String[] selectionArgs, boolean canReadRestrictedMessages, String otpFilter) {
        final String table = TextUtils.isEmpty(otpFilter)
                ? TABLE_THREADS
                : getOtpRedactedThreadsTable(otpFilter);
        if (Flags.secureAccessToRestrictedRcsMessages()) {
            final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            configureStrictQueryBuilder(qb);
            qb.setTables(table);
            if (!canReadRestrictedMessages) {
                ReadRestriction.appendReadRestrictionToQuery(qb, TABLE_THREADS,
                        canReadRestrictedMessages);
            }
            return qb.query(mOpenHelper.getReadableDatabase(), projection, selection, selectionArgs,
                    null, null, " date DESC");
        }
        return mOpenHelper.getReadableDatabase().query(table, projection,
                selection, selectionArgs, null, null, " date DESC");
    }

    /**
     * Return the thread which has draft in both MMS and SMS.
     *
     * Use this query:
     *
     *   SELECT ...
     *     FROM (SELECT _id, thread_id, ...
     *             FROM pdu
     *             WHERE msg_box = 3 AND ...
     *           UNION
     *           SELECT _id, thread_id, ...
     *             FROM sms
     *             WHERE type = 3 AND ...
     *          )
     *   ;
     */
    private Cursor getDraftThread(String[] projection, String selection,
            String sortOrder, String smsTable, String pduTable, boolean canReadRestrictedMessages) {
        String[] innerProjection = new String[] {BaseColumns._ID, Conversations.THREAD_ID};
        SQLiteQueryBuilder mmsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(mmsQueryBuilder);
        SQLiteQueryBuilder smsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(smsQueryBuilder);

        mmsQueryBuilder.setTables(pduTable);
        smsQueryBuilder.setTables(smsTable);

        ReadRestriction.appendRestrictedToQuery(mmsQueryBuilder, pduTable,
                canReadRestrictedMessages);
        ReadRestriction.appendRestrictedToQuery(smsQueryBuilder, smsTable,
                canReadRestrictedMessages);

        String mmsSubQuery = mmsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, innerProjection,
                MMS_COLUMNS, 1, "mms",
                concatSelections(selection, Mms.MESSAGE_BOX + "=" + Mms.MESSAGE_BOX_DRAFTS),
                null, null);
        String smsSubQuery = smsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, innerProjection,
                SMS_COLUMNS, 1, "sms",
                concatSelections(selection, Sms.TYPE + "=" + Sms.MESSAGE_TYPE_DRAFT),
                null, null);
        SQLiteQueryBuilder unionQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(unionQueryBuilder);

        unionQueryBuilder.setDistinct(true);

        String unionQuery = unionQueryBuilder.buildUnionQuery(
                new String[] { mmsSubQuery, smsSubQuery }, null, null);

        SQLiteQueryBuilder outerQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(outerQueryBuilder);

        outerQueryBuilder.setTables("(" + unionQuery + ")");

        String outerQuery = outerQueryBuilder.buildQuery(
                projection, null, null, null, sortOrder, null);

        return mOpenHelper.getReadableDatabase().rawQuery(outerQuery, EMPTY_STRING_ARRAY);
    }

    /**
     * Return the most recent message in each conversation in both MMS
     * and SMS.
     *
     * Use this query:
     *
     *   SELECT ...
     *     FROM (SELECT thread_id AS tid, date * 1000 AS normalized_date, ...
     *             FROM pdu
     *             WHERE msg_box != 3 AND ...
     *             GROUP BY thread_id
     *             HAVING date = MAX(date)
     *           UNION
     *           SELECT thread_id AS tid, date AS normalized_date, ...
     *             FROM sms
     *             WHERE ...
     *             GROUP BY thread_id
     *             HAVING date = MAX(date))
     *     GROUP BY tid
     *     HAVING normalized_date = MAX(normalized_date);
     *
     * The msg_box != 3 comparisons ensure that we don't include draft
     * messages.
     */
    private Cursor getConversations(String[] projection, String selection,
            String sortOrder, String smsTable, String pduTable, boolean canReadRestrictedMessages,
            String otpFilter) {
        SQLiteQueryBuilder mmsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(mmsQueryBuilder);
        SQLiteQueryBuilder smsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(smsQueryBuilder);

        mmsQueryBuilder.setTables(pduTable);
        smsQueryBuilder.setTables(smsTable);

        ReadRestriction.appendRestrictedToQuery(mmsQueryBuilder, pduTable,
                canReadRestrictedMessages);
        ReadRestriction.appendRestrictedToQuery(smsQueryBuilder, smsTable,
                canReadRestrictedMessages);

        String[] columns = handleNullMessageProjection(projection);
        String[] innerMmsProjection = makeProjectionWithDateAndThreadId(
                UNION_COLUMNS, 1000);
        String[] innerSmsProjection = makeProjectionWithDateAndThreadId(
                UNION_COLUMNS, 1);
        String mmsSubQuery = mmsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, innerMmsProjection,
                MMS_COLUMNS, 1, "mms",
                concatSelections(selection, MMS_CONVERSATION_CONSTRAINT),
                "thread_id", "date = MAX(date)");
        String smsSelection = concatSelections(selection, SMS_CONVERSATION_CONSTRAINT);
        if (!TextUtils.isEmpty(otpFilter)) {
            smsSelection = concatSelections(smsSelection, otpFilter);
        }

        String smsSubQuery = smsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, innerSmsProjection,
                SMS_COLUMNS, 1, "sms",
                smsSelection,
                "thread_id", "date = MAX(date)");
        SQLiteQueryBuilder unionQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(unionQueryBuilder);

        unionQueryBuilder.setDistinct(true);

        String unionQuery = unionQueryBuilder.buildUnionQuery(
                new String[] { mmsSubQuery, smsSubQuery }, null, null);

        SQLiteQueryBuilder outerQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(outerQueryBuilder);

        outerQueryBuilder.setTables("(" + unionQuery + ")");

        String outerQuery = outerQueryBuilder.buildQuery(
                columns, null, "tid",
                "normalized_date = MAX(normalized_date)", sortOrder, null);

        return mOpenHelper.getReadableDatabase().rawQuery(outerQuery, EMPTY_STRING_ARRAY);
    }

    /**
     * Return the first locked message found in the union of MMS
     * and SMS messages.
     *
     * Use this query:
     *
     *  SELECT _id FROM pdu GROUP BY _id HAVING locked=1 UNION SELECT _id FROM sms GROUP
     *      BY _id HAVING locked=1 LIMIT 1
     *
     * We limit by 1 because we're only interested in knowing if
     * there is *any* locked message, not the actual messages themselves.
     */
    private Cursor getFirstLockedMessage(String[] projection, String selection,
            String sortOrder, String smsTable, String pduTable, boolean canReadRestrictedMessages) {
        SQLiteQueryBuilder mmsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(mmsQueryBuilder);
        SQLiteQueryBuilder smsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(smsQueryBuilder);

        mmsQueryBuilder.setTables(pduTable);
        smsQueryBuilder.setTables(smsTable);

        ReadRestriction.appendRestrictedToQuery(mmsQueryBuilder, pduTable,
            canReadRestrictedMessages);
        ReadRestriction.appendRestrictedToQuery(smsQueryBuilder, smsTable,
            canReadRestrictedMessages);

        String[] idColumn = new String[] { BaseColumns._ID };

        // NOTE: buildUnionSubQuery *ignores* selectionArgs
        String mmsSubQuery = mmsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, idColumn,
                null, 1, "mms",
                selection,
                BaseColumns._ID, "locked=1");

        String smsSubQuery = smsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, idColumn,
                null, 1, "sms",
                selection,
                BaseColumns._ID, "locked=1");

        SQLiteQueryBuilder unionQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(unionQueryBuilder);

        unionQueryBuilder.setDistinct(true);

        String unionQuery = unionQueryBuilder.buildUnionQuery(
                new String[] { mmsSubQuery, smsSubQuery }, null, "1");

        Cursor cursor = mOpenHelper.getReadableDatabase().rawQuery(unionQuery, EMPTY_STRING_ARRAY);

        if (DEBUG) {
            Log.v("MmsSmsProvider", "getFirstLockedMessage query: " + unionQuery);
            Log.v("MmsSmsProvider", "cursor count: " + cursor.getCount());
        }
        return cursor;
    }

    /**
     * Return every message in each conversation in both MMS
     * and SMS.
     */
    private Cursor getCompleteConversations(String[] projection,
            String selection, String sortOrder, String smsTable, String pduTable,
            boolean canReadRestrictedMessages, String otpFilter) {
        String unionQuery = buildConversationQuery(projection, selection, sortOrder, smsTable,
                pduTable, canReadRestrictedMessages, otpFilter);

        return mOpenHelper.getReadableDatabase().rawQuery(unionQuery, EMPTY_STRING_ARRAY);
    }

    /**
     * Add normalized date and thread_id to the list of columns for an
     * inner projection.  This is necessary so that the outer query
     * can have access to these columns even if the caller hasn't
     * requested them in the result.
     */
    private String[] makeProjectionWithDateAndThreadId(
            String[] projection, int dateMultiple) {
        int projectionSize = projection.length;
        String[] result = new String[projectionSize + 2];

        result[0] = "thread_id AS tid";
        result[1] = "date * " + dateMultiple + " AS normalized_date";
        for (int i = 0; i < projectionSize; i++) {
            result[i + 2] = projection[i];
        }
        return result;
    }

    /**
     * Return the union of MMS and SMS messages for this thread ID.
     */
    private Cursor getConversationMessages(
            String threadIdString, String[] projection, String selection,
            String sortOrder, String smsTable, String pduTable, boolean canReadRestrictedMessages,
            String otpFilter) {
        try {
            Long.parseLong(threadIdString);
        } catch (NumberFormatException exception) {
            Log.e(LOG_TAG, "Thread ID must be a Long.");
            return null;
        }

        String finalSelection = concatSelections(
                selection, "thread_id = " + threadIdString);
        String unionQuery = buildConversationQuery(projection, finalSelection, sortOrder, smsTable,
                pduTable, canReadRestrictedMessages, otpFilter);


        return mOpenHelper.getReadableDatabase().rawQuery(unionQuery, EMPTY_STRING_ARRAY);
    }

    /**
     * Return the union of MMS and SMS messages whose recipients
     * included this phone number.
     *
     * Use this query:
     *
     * SELECT ...
     *   FROM pdu, (SELECT msg_id AS address_msg_id
     *              FROM addr
     *              WHERE (address='<phoneNumber>' OR
     *              PHONE_NUMBERS_EQUAL(addr.address, '<phoneNumber>', 1/0, none/minMatch)))
     *             AS matching_addresses
     *   WHERE pdu._id = matching_addresses.address_msg_id
     * UNION
     * SELECT ...
     *   FROM sms
     *   WHERE (address='<phoneNumber>' OR
     *          PHONE_NUMBERS_EQUAL(sms.address, '<phoneNumber>', 1/0, none/minMatch));
     */
    private Cursor getMessagesByPhoneNumber(
            String phoneNumber, String[] projection, String selection,
            String sortOrder, String smsTable, String pduTable, boolean canReadRestrictedMessages,
            String otpFilter) {
        int minMatch =
            getContext().getResources().getInteger(
                    com.android.internal.R.integer.config_phonenumber_compare_min_match);
        String finalMmsSelection =
                concatSelections(
                        selection,
                        pduTable + "._id = matching_addresses.address_msg_id");
        String finalSmsSelection =
                concatSelections(
                        selection,
                        "(address=? OR PHONE_NUMBERS_EQUAL(address, ?" +
                        (mUseStrictPhoneNumberComparation ? ", 1))" : ", 0, " + minMatch + "))"));

        String smsSelectionWithOtp = finalSmsSelection;
        if (!TextUtils.isEmpty(otpFilter)) {
            smsSelectionWithOtp = concatSelections(finalSmsSelection, otpFilter);
        }

        SQLiteQueryBuilder mmsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(mmsQueryBuilder);
        SQLiteQueryBuilder smsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(smsQueryBuilder);

        mmsQueryBuilder.setDistinct(true);
        smsQueryBuilder.setDistinct(true);
        mmsQueryBuilder.setTables(
                pduTable +
                ", (SELECT msg_id AS address_msg_id " +
                "FROM addr WHERE (address=?" +
                " OR PHONE_NUMBERS_EQUAL(addr.address, ?" +
                (mUseStrictPhoneNumberComparation ? ", 1))) " : ", 0, " + minMatch + "))) ") +
                "AS matching_addresses");
        smsQueryBuilder.setTables(smsTable);

        ReadRestriction.appendRestrictedToQuery(mmsQueryBuilder, pduTable,
                canReadRestrictedMessages);
        ReadRestriction.appendRestrictedToQuery(smsQueryBuilder, smsTable,
                canReadRestrictedMessages);

        String[] columns = handleNullMessageProjection(projection);
        String mmsSubQuery = mmsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, columns, MMS_COLUMNS,
                0, "mms", finalMmsSelection, null, null);
        String smsSubQuery = smsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, columns, SMS_COLUMNS,
                0, "sms", smsSelectionWithOtp, null, null);
        SQLiteQueryBuilder unionQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(unionQueryBuilder);

        unionQueryBuilder.setDistinct(true);

        String unionQuery = unionQueryBuilder.buildUnionQuery(
                new String[] { mmsSubQuery, smsSubQuery }, sortOrder, null);

        return mOpenHelper.getReadableDatabase().rawQuery(unionQuery,
                new String[] { phoneNumber, phoneNumber, phoneNumber, phoneNumber });
    }

    /**
     * Return the conversation of certain thread ID.
     */
    private Cursor getConversationById(
            String threadIdString, String[] projection, String selection,
            String[] selectionArgs, String sortOrder, boolean canReadRestrictedMessages,
            String otpFilter) {
        try {
            Long.parseLong(threadIdString);
        } catch (NumberFormatException exception) {
            Log.e(LOG_TAG, "Thread ID must be a Long.");
            return null;
        }

        String extraSelection = "_id=" + threadIdString;
        String finalSelection = concatSelections(selection, extraSelection);
        SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(queryBuilder);
        String[] columns = handleNullThreadsProjection(projection);

        queryBuilder.setDistinct(true);
        final String table = TextUtils.isEmpty(otpFilter)
                ? TABLE_THREADS
                : getOtpRedactedThreadsTable(otpFilter);
        queryBuilder.setTables(table);
        ReadRestriction.appendReadRestrictionToQuery(queryBuilder, TABLE_THREADS,
                canReadRestrictedMessages);
        return queryBuilder.query(
                mOpenHelper.getReadableDatabase(), columns, finalSelection,
                selectionArgs, sortOrder, null, null);
    }

    private static String joinPduAndPendingMsgTables(String pduTable) {
        return pduTable + " LEFT JOIN " + TABLE_PENDING_MSG
                + " ON " + pduTable + "._id = pending_msgs.msg_id";
    }

    private static String[] createMmsProjection(String[] old, String pduTable) {
        String[] newProjection = new String[old.length];
        for (int i = 0; i < old.length; i++) {
            if (old[i].equals(BaseColumns._ID)) {
                newProjection[i] = pduTable + "._id";
            } else {
                newProjection[i] = old[i];
            }
        }
        return newProjection;
    }

    private Cursor getUndeliveredMessages(
            String[] projection, String selection, String[] selectionArgs,
            String sortOrder, String smsTable, String pduTable, boolean canReadRestrictedMessages) {
        String[] columns = handleNullMessageProjection(projection);
        String[] mmsColumns = createMmsProjection(columns, pduTable);

        SQLiteQueryBuilder mmsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(mmsQueryBuilder);
        SQLiteQueryBuilder smsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(smsQueryBuilder);

        mmsQueryBuilder.setTables(joinPduAndPendingMsgTables(pduTable));
        smsQueryBuilder.setTables(smsTable);

        ReadRestriction.appendRestrictedToQuery(mmsQueryBuilder, pduTable,
                canReadRestrictedMessages);
        ReadRestriction.appendRestrictedToQuery(smsQueryBuilder, smsTable,
                canReadRestrictedMessages);

        String finalMmsSelection = concatSelections(
                selection, Mms.MESSAGE_BOX + " = " + Mms.MESSAGE_BOX_OUTBOX);
        String finalSmsSelection = concatSelections(
                selection, "(" + Sms.TYPE + " = " + Sms.MESSAGE_TYPE_OUTBOX
                + " OR " + Sms.TYPE + " = " + Sms.MESSAGE_TYPE_FAILED
                + " OR " + Sms.TYPE + " = " + Sms.MESSAGE_TYPE_QUEUED + ")");

        String[] innerMmsProjection = makeProjectionWithDateAndThreadId(
                mmsColumns, 1000);
        String[] innerSmsProjection = makeProjectionWithDateAndThreadId(
                columns, 1);

        Set<String> columnsPresentInTable = new HashSet<String>(MMS_COLUMNS);
        columnsPresentInTable.add(pduTable + "._id");
        columnsPresentInTable.add(PendingMessages.ERROR_TYPE);
        String mmsSubQuery = mmsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, innerMmsProjection,
                columnsPresentInTable, 1, "mms", finalMmsSelection,
                null, null);
        String smsSubQuery = smsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, innerSmsProjection,
                SMS_COLUMNS, 1, "sms", finalSmsSelection,
                null, null);
        SQLiteQueryBuilder unionQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(unionQueryBuilder);

        unionQueryBuilder.setDistinct(true);

        String unionQuery = unionQueryBuilder.buildUnionQuery(
                new String[] { smsSubQuery, mmsSubQuery }, null, null);

        SQLiteQueryBuilder outerQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(outerQueryBuilder);

        outerQueryBuilder.setTables("(" + unionQuery + ")");

        String outerQuery = outerQueryBuilder.buildQuery(
                columns, null, null, null, sortOrder, null);

        return mOpenHelper.getReadableDatabase().rawQuery(outerQuery, EMPTY_STRING_ARRAY);
    }

    /**
     * Add normalized date to the list of columns for an inner
     * projection.
     */
    private static String[] makeProjectionWithNormalizedDate(
            String[] projection, int dateMultiple) {
        int projectionSize = projection.length;
        String[] result = new String[projectionSize + 1];

        result[0] = "date * " + dateMultiple + " AS normalized_date";
        System.arraycopy(projection, 0, result, 1, projectionSize);
        return result;
    }

    private static String buildConversationQuery(String[] projection,
            String selection, String sortOrder, String smsTable, String pduTable,
            boolean canReadRestrictedMessages, String otpFilter) {
        String[] columns = handleNullMessageProjection(projection);
        String[] mmsColumns = createMmsProjection(columns, pduTable);

        SQLiteQueryBuilder mmsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(mmsQueryBuilder);
        SQLiteQueryBuilder smsQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(smsQueryBuilder);

        mmsQueryBuilder.setDistinct(true);
        smsQueryBuilder.setDistinct(true);
        mmsQueryBuilder.setTables(joinPduAndPendingMsgTables(pduTable));
        smsQueryBuilder.setTables(smsTable);

        ReadRestriction.appendRestrictedToQuery(mmsQueryBuilder, pduTable,
                canReadRestrictedMessages);
        ReadRestriction.appendRestrictedToQuery(smsQueryBuilder, smsTable,
                canReadRestrictedMessages);

        String[] innerMmsProjection = makeProjectionWithNormalizedDate(mmsColumns, 1000);
        String[] innerSmsProjection = makeProjectionWithNormalizedDate(columns, 1);

        Set<String> columnsPresentInTable = new HashSet<String>(MMS_COLUMNS);
        columnsPresentInTable.add(pduTable + "._id");
        columnsPresentInTable.add(PendingMessages.ERROR_TYPE);

        String mmsSelection = concatSelections(selection,
                                Mms.MESSAGE_BOX + " != " + Mms.MESSAGE_BOX_DRAFTS);
        String mmsSubQuery = mmsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, innerMmsProjection,
                columnsPresentInTable, 0, "mms",
                concatSelections(mmsSelection, MMS_CONVERSATION_CONSTRAINT),
                null, null);
        String smsSelection = concatSelections(selection, SMS_CONVERSATION_CONSTRAINT);
        if (!TextUtils.isEmpty(otpFilter)) {
            smsSelection = concatSelections(smsSelection, otpFilter);
        }

        String smsSubQuery = smsQueryBuilder.buildUnionSubQuery(
                MmsSms.TYPE_DISCRIMINATOR_COLUMN, innerSmsProjection, SMS_COLUMNS,
                0, "sms", smsSelection,
                null, null);
        SQLiteQueryBuilder unionQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(unionQueryBuilder);

        unionQueryBuilder.setDistinct(true);

        String unionQuery = unionQueryBuilder.buildUnionQuery(
                new String[] { smsSubQuery, mmsSubQuery },
                handleNullSortOrder(sortOrder), null);

        SQLiteQueryBuilder outerQueryBuilder = new SQLiteQueryBuilder();
        configureStrictQueryBuilder(outerQueryBuilder);

        outerQueryBuilder.setTables("(" + unionQuery + ")");

        return outerQueryBuilder.buildQuery(
                columns, null, null, null, sortOrder, null);
    }

    @Override
    public String getType(Uri uri) {
        return VND_ANDROID_DIR_MMS_SMS;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        long startTime = SystemClock.elapsedRealtime();
        int result = 0;
        try {
            result = deleteInternal(uri, selection, selectionArgs);
            ProviderMetricsLogger.logOperationLatency(
                    getContext(),
                    ProviderMetricsLogger.OPERATION_DELETE,
                    ProviderMetricsLogger.TARGET_URI_CONVERSATIONS,
                    startTime,
                    result);
        } catch (SQLiteDatabaseLockedException e) { // Lock
            ProviderMetricsLogger.logDbLockContention(getContext(),
                    ProviderMetricsLogger.OPERATION_DELETE,
                    ProviderMetricsLogger.TARGET_URI_CONVERSATIONS);
            throw e;
        } catch (Exception e) {
            Log.e("ProviderMetrics", "Database operation failed", e);
            ProviderUtil.logRunningTelephonyProviderProcesses(getContext());
            throw e;
        }
        return result;
    }

    /** Internal implementation of the database operation. */
    public int deleteInternal(Uri uri, String selection, String[] selectionArgs) {
        final UserHandle callerUserHandle = Binder.getCallingUserHandle();
        String selectionBySubIds;
        final long token = Binder.clearCallingIdentity();
        try {
            // Filter MMS/SMS based on subId
            selectionBySubIds = ProviderUtil.getSelectionBySubIds(getContext(), callerUserHandle,
                    /* tableName= */ null);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // The delete operation is already restricted to WRITE_SMS permission, so we don't need
        // further restriction for deleting restricted messages.
        if (Flags.secureAccessToRestrictedRcsMessages()) {
            SqlQueryChecker.checkQueryForForbiddenColumns(/* projection= */ null, selection,
                    /* sortOrder= */ null, LOG_TAG);
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        String debugMessage = getCallingPackage() + ";MmsSmsProvider.delete;" + uri;
        // Always log delete for debug purpose, as delete is a critical but non-frequent operation.
        Log.d(LOG_TAG, debugMessage);
        if (mOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) mOpenHelper).addDatabaseOpeningDebugLog(
                    debugMessage, false);
        }
        Context context = getContext();
        int affectedRows = 0;

        switch(URI_MATCHER.match(uri)) {
            case URI_CONVERSATIONS_MESSAGES:
                long threadId;
                try {
                    threadId = Long.parseLong(uri.getLastPathSegment());
                } catch (NumberFormatException e) {
                    Log.e(LOG_TAG, "Thread ID must be a long.");
                    break;
                }

                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return 0.
                    return 0;
                }
                selection = DatabaseUtils.concatenateWhere(selectionBySubIds, selection);

                affectedRows = deleteConversation(uri, selection, selectionArgs);
                MmsSmsDatabaseHelper.updateThread(db, threadId);
                break;
            case URI_CONVERSATIONS:
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return 0.
                    return 0;
                }
                selection = DatabaseUtils.concatenateWhere(selectionBySubIds, selection);

                affectedRows = MmsProvider.deleteMessages(context, db,
                                        selection, selectionArgs, uri)
                        + db.delete("sms", selection, selectionArgs);
                // Intentionally don't pass the selection variable to updateThreads.
                // When we pass in "locked=0" there, the thread will get excluded from
                // the selection and not get updated.
                MmsSmsDatabaseHelper.updateThreads(db, null, null);
                break;
            case URI_OBSOLETE_THREADS:
                affectedRows = db.delete(TABLE_THREADS,
                        "_id NOT IN (SELECT DISTINCT thread_id FROM sms where thread_id NOT NULL " +
                        "UNION SELECT DISTINCT thread_id FROM pdu where thread_id NOT NULL)", null);
                break;
            default:
                throw new UnsupportedOperationException(NO_DELETES_INSERTS_OR_UPDATES + uri);
        }

        if (affectedRows > 0) {
            context.getContentResolver().notifyChange(MmsSms.CONTENT_URI, null, true,
                    UserHandle.USER_ALL);
        }
        return affectedRows;
    }

    /**
     * Delete the conversation with the given thread ID.
     */
    private int deleteConversation(Uri uri, String selection, String[] selectionArgs) {
        String threadId = uri.getLastPathSegment();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        String finalSelection = concatSelections(selection, "thread_id = " + threadId);
        return MmsProvider.deleteMessages(getContext(), db, finalSelection,
                                          selectionArgs, uri)
                + db.delete("sms", finalSelection, selectionArgs);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        long startTime = SystemClock.elapsedRealtime();
        Uri result = null;
        try {
            result = insertInternal(uri, values);
            ProviderMetricsLogger.logOperationLatency(
                    getContext(),
                    ProviderMetricsLogger.OPERATION_INSERT,
                    ProviderMetricsLogger.TARGET_URI_CONVERSATIONS,
                    startTime,
                    1);
        } catch (SQLiteDatabaseLockedException e) { // Lock
            ProviderMetricsLogger.logDbLockContention(getContext(),
                    ProviderMetricsLogger.OPERATION_INSERT,
                    ProviderMetricsLogger.TARGET_URI_CONVERSATIONS);
            throw e;
        } catch (Exception e) {
            Log.e("ProviderMetrics", "Database operation failed", e);
            ProviderUtil.logRunningTelephonyProviderProcesses(getContext());
            throw e;
        }
        return result;
    }

    /** Internal implementation of the database operation. */
    public Uri insertInternal(Uri uri, ContentValues values) {
        final UserHandle callerUserHandle = Binder.getCallingUserHandle();
        final int callerUid = Binder.getCallingUid();
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        if (mOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) mOpenHelper).addDatabaseOpeningDebugLog(
                    getCallingPackage() + ";MmsSmsProvider.insert;" + uri, false);
        }

        int matchIndex = URI_MATCHER.match(uri);
        // TODO (b/256992531): Currently, one sim card is set as default sms subId in work
        //  profile. Default sms subId should be updated based on user pref.
        int defaultSmsSubId = SmsManager.getDefaultSmsSubscriptionId();
        if (matchIndex == URI_PENDING_MSG) {
            int subId;
            if (values.containsKey(PendingMessages.SUBSCRIPTION_ID)) {
                subId = values.getAsInteger(PendingMessages.SUBSCRIPTION_ID);
            } else {
                subId = defaultSmsSubId;
                if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    values.put(PendingMessages.SUBSCRIPTION_ID, subId);
                }
            }

            if (!ProviderUtil
                    .allowInteractingWithEntryOfSubscription(getContext(), subId,
                            callerUserHandle)) {
                TelephonyUtils.showSwitchToManagedProfileDialogIfAppropriate(getContext(), subId,
                        callerUid, getCallingPackage());
                return null;
            }

            long rowId = db.insert(TABLE_PENDING_MSG, null, values);
            return uri.buildUpon().appendPath(Long.toString(rowId)).build();
        } else if (matchIndex == URI_CANONICAL_ADDRESS) {
            if (!values.containsKey(CanonicalAddressesColumns.SUBSCRIPTION_ID)) {
                if (SubscriptionManager.isValidSubscriptionId(defaultSmsSubId)) {
                    values.put(CanonicalAddressesColumns.SUBSCRIPTION_ID, defaultSmsSubId);
                }
                if (Flags.secureAccessToRestrictedRcsMessages()) {
                    // New canonical addresses should be restricted by default. They become
                    // unrestricted when any thread that they belong to becomes unrestricted.
                    values.put(CanonicalAddressesColumns.READ_RESTRICTION,
                            ReadRestrictionValues.READ_RESTRICTION_RESTRICTED);
                }
            }

            long rowId = db.insert(TABLE_CANONICAL_ADDRESSES, null, values);
            return uri.buildUpon().appendPath(Long.toString(rowId)).build();
        }
        throw new UnsupportedOperationException(NO_DELETES_INSERTS_OR_UPDATES + uri);
    }

    @Override
    public int update(Uri uri, ContentValues values,
            String selection, String[] selectionArgs) {
        long startTime = SystemClock.elapsedRealtime();
        int result = 0;
        try {
            result = updateInternal(uri, values, selection, selectionArgs);
            ProviderMetricsLogger.logOperationLatency(
                    getContext(),
                    ProviderMetricsLogger.OPERATION_UPDATE,
                    ProviderMetricsLogger.TARGET_URI_CONVERSATIONS,
                    startTime,
                    result);
        } catch (SQLiteDatabaseLockedException e) { // Lock
            ProviderMetricsLogger.logDbLockContention(getContext(),
                    ProviderMetricsLogger.OPERATION_UPDATE,
                    ProviderMetricsLogger.TARGET_URI_CONVERSATIONS);
            throw e;
        } catch (Exception e) {
            Log.e("ProviderMetrics", "Database operation failed", e);
            ProviderUtil.logRunningTelephonyProviderProcesses(getContext());
            throw e;
        }
        return result;
    }

    /** Internal implementation of the database operation. */
    public int updateInternal(Uri uri, ContentValues values,
            String selection, String[] selectionArgs) {
        final int callerUid = Binder.getCallingUid();
        final UserHandle callerUserHandle = Binder.getCallingUserHandle();
        final String callerPkg = getCallingPackage();

        String selectionBySubIds;
        final long token = Binder.clearCallingIdentity();
        try {
            // Filter MMS/SMS based on subId.
            selectionBySubIds = ProviderUtil.getSelectionBySubIds(getContext(), callerUserHandle,
                    /* tableName= */ null);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        if (mOpenHelper instanceof MmsSmsDatabaseHelper) {
            ((MmsSmsDatabaseHelper) mOpenHelper).addDatabaseOpeningDebugLog(
                    callerPkg + ";MmsSmsProvider.update;" + uri, false);
        }

        int affectedRows = 0;
        switch(URI_MATCHER.match(uri)) {
            case URI_CONVERSATIONS_MESSAGES:
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return 0.
                    return 0;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                String threadIdString = uri.getPathSegments().get(1);
                affectedRows = updateConversation(threadIdString, values,
                        selection, selectionArgs, callerUid, callerPkg);
                break;

            case URI_PENDING_MSG:
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return 0.
                    return 0;
                }
                // In PendingMessages table, SUBSCRIPTION_ID column name is pending_sub_id.
                selectionBySubIds = "pending_" + selectionBySubIds;
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                affectedRows = db.update(TABLE_PENDING_MSG, values, selection, null);
                break;

            case URI_CANONICAL_ADDRESS: {
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return 0.
                    return 0;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                String extraSelection = "_id=" + uri.getPathSegments().get(1);
                String finalSelection = TextUtils.isEmpty(selection)
                        ? extraSelection : extraSelection + " AND " + selection;

                if (Flags.secureAccessToRestrictedRcsMessages()
                        && values.containsKey(CanonicalAddressesColumns.READ_RESTRICTION)) {
                    throw new UnsupportedOperationException(
                            "Updating read_restriction column is not supported.");
                }
                affectedRows = db.update(TABLE_CANONICAL_ADDRESSES, values, finalSelection, null);
                break;
            }

            case URI_CONVERSATIONS: {
                if (selectionBySubIds == null) {
                    // No subscriptions associated with user, return 0.
                    return 0;
                }
                selection = DatabaseUtils.concatenateWhere(selection, selectionBySubIds);

                final ContentValues finalValues = new ContentValues(1);
                if (values.containsKey(Threads.ARCHIVED)) {
                    // Only allow update archived
                    finalValues.put(Threads.ARCHIVED, values.getAsBoolean(Threads.ARCHIVED));
                }
                affectedRows = db.update(TABLE_THREADS, finalValues, selection, selectionArgs);
                break;
            }

            default:
                throw new UnsupportedOperationException(
                        NO_DELETES_INSERTS_OR_UPDATES + uri);
        }

        if (affectedRows > 0) {
            getContext().getContentResolver().notifyChange(
                    MmsSms.CONTENT_URI, null, true, UserHandle.USER_ALL);
        }
        return affectedRows;
    }

    // This method is to support unit test override
    protected String getOtpFilter(int callerUid, String callingPackage,
            UserHandle callerUserHandle) {
        boolean canReadOtpSms = ProviderUtil.canReadOtpSms(getContext(), callerUid, callingPackage);
        if (!canReadOtpSms && Telephony.Sms.isOtpRedactionEnabled(getContext())) {
            return ProviderUtil.getOtpWhereFilter(getContext(), callingPackage,
                    callerUserHandle);
        } else {
            return "";
        }
    }

    private int updateConversation(String threadIdString, ContentValues values, String selection,
            String[] selectionArgs, int callerUid, String callerPkg) {
        try {
            Long.parseLong(threadIdString);
        } catch (NumberFormatException exception) {
            Log.e(LOG_TAG, "Thread ID must be a Long.");
            return 0;

        }
        if (ProviderUtil.shouldRemoveCreator(values, callerUid)) {
            // CREATOR should not be changed by non-SYSTEM/PHONE apps
            Log.w(LOG_TAG, callerPkg + " tries to update CREATOR");
            // Sms.CREATOR and Mms.CREATOR are same. But let's do this
            // twice in case the names may differ in the future
            values.remove(Sms.CREATOR);
            values.remove(Mms.CREATOR);
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        String finalSelection = concatSelections(selection, "thread_id=" + threadIdString);
        return db.update(MmsProvider.TABLE_PDU, values, finalSelection, selectionArgs)
                + db.update("sms", values, finalSelection, selectionArgs);
    }

    /**
     * Construct Sets of Strings containing exactly the columns
     * present in each table.  We will use this when constructing
     * UNION queries across the MMS and SMS tables.
     */
    private static void initializeColumnSets() {
        int commonColumnCount = MMS_SMS_COLUMNS.length;
        int mmsOnlyColumnCount = MMS_ONLY_COLUMNS.length;
        int smsOnlyColumnCount = SMS_ONLY_COLUMNS.length;
        Set<String> unionColumns = new HashSet<String>();

        for (int i = 0; i < commonColumnCount; i++) {
            MMS_COLUMNS.add(MMS_SMS_COLUMNS[i]);
            SMS_COLUMNS.add(MMS_SMS_COLUMNS[i]);
            unionColumns.add(MMS_SMS_COLUMNS[i]);
        }
        for (int i = 0; i < mmsOnlyColumnCount; i++) {
            MMS_COLUMNS.add(MMS_ONLY_COLUMNS[i]);
            unionColumns.add(MMS_ONLY_COLUMNS[i]);
        }
        for (int i = 0; i < smsOnlyColumnCount; i++) {
            SMS_COLUMNS.add(SMS_ONLY_COLUMNS[i]);
            unionColumns.add(SMS_ONLY_COLUMNS[i]);
        }

        int i = 0;
        for (String columnName : unionColumns) {
            UNION_COLUMNS[i++] = columnName;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        // Dump default SMS app
        String defaultSmsApp = Telephony.Sms.getDefaultSmsPackage(getContext());
        if (TextUtils.isEmpty(defaultSmsApp)) {
            defaultSmsApp = "None";
        }
        writer.println("Default SMS app: " + defaultSmsApp);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (ProviderUtil.isAccessRestricted(
                getContext(), getCallingPackage(), Binder.getCallingUid())) {
            return null;
        }
        if (METHOD_IS_RESTORING.equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean(IS_RESTORING_KEY, TelephonyBackupAgent.getIsRestoring());
            return result;
        } else if (METHOD_GARBAGE_COLLECT.equals(method)) {
            Bundle result = new Bundle();
            boolean doDelete = TextUtils.equals(DO_DELETE, arg);
            MmsPartsCleanup.cleanupDanglingParts(getContext(), doDelete, result);
            return result;
        }
        Log.w(LOG_TAG, "Ignored unsupported " + method + " call");
        return null;
    }
}
