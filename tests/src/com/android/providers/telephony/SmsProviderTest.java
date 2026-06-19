/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.providers.telephony;

import static com.android.compatibility.common.util.SystemUtil.eventually;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.util.Log;
import android.view.textclassifier.TextClassificationManager;
import android.platform.test.annotations.EnableFlags;
import android.os.Process;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.ISms;
import com.android.internal.telephony.metrics.ReadRestrictionStatsLogger;

import junit.framework.TestCase;

import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tests for testing CRUD operations of SmsProvider.
 * Uses a MockContentResolver to test insert
 * Uses SmsProviderTestable to set up in-memory database
 *
 * Build, install and run the tests by running the commands below:
 *     runtest --path <dir or file>
 *     runtest --path <dir or file> --test-method <testMethodName>
 *     e.g.)
 *         runtest --path tests/src/com/android/providers/telephony/SmsProviderTest.java \
 *                 --test-method testInsertUri
 */
public class SmsProviderTest extends TestCase {
    private static final String TAG = "SmsProviderTest";

    private Context mContext;
    private MockContentResolver mContentResolver;
    private SmsProviderTestable mSmsProviderTestable;
    @Mock private PackageManager mPackageManager;
    @Mock private Resources mMockResources;
    @Mock private SubscriptionManager mSubscriptionManager;
    @Mock private ReadRestrictionStatsLogger mReadRestrictionStatsLogger;

    private int notifyChangeCount;

    private final UserHandle mMyUserHandle = UserHandle.of(UserHandle.myUserId());

    private final String mFakePdu = "123abc";
    private final String mFakeAddress = "FakeAddress";
    private final String mFakeOriginatingAddr = "FakeDisplayAddress";
    private final String mFakeMessageBody = "FakeMessageBody";
    private final int mFakeRefNumber = 123;
    private final int mFakeSequence = 1;
    private final int mFakeCount = 1;
    private final int mFakePort = 1 << 19;
    private final long mDate = 0;

    private final Uri mRawUri =
            Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, "raw");
    private final Uri mRawUriPermanentDelete =
            Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, "raw/permanentDelete");

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mSmsProviderTestable = new SmsProviderTestable();
        mContext = spy(ApplicationProvider.getApplicationContext());
        TelephonyManager.setupISmsForTest(Mockito.mock(ISms.class));

        when(mContext.getSystemService(eq(Context.APP_OPS_SERVICE)))
                .thenReturn(mock(AppOpsManager.class));
        when(mContext.getSystemService(eq(Context.TELEPHONY_SERVICE)))
                .thenReturn(mock(TelephonyManager.class));
        when(mContext.getSystemService(eq(Context.DEVICE_POLICY_SERVICE)))
                .thenReturn(mock(DevicePolicyManager.class));

        when(mContext.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        when(mMockResources
                        .getString(eq(com.android.internal.R.string.config_systemBluetoothStack)))
                .thenReturn("com.android.bluetooth");
        when(mContext.getResources()).thenReturn(mMockResources);
        when(mContext.getUserId()).thenReturn(0);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        /**
         * This is used to give the SmsProviderTest a mocked context which takes a
         * SmsProvider and attaches it to the ContentResolver with telephony authority.
         * The mocked context also gives WRITE_APN_SETTINGS permissions
         */
        mContentResolver = new MockContentResolver() {
            @Override
            public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork,
                    int userHandle) {
                notifyChangeCount++;
            }
        };
        when(mContext.getContentResolver()).thenReturn(mContentResolver);

        // Add authority="sms" to given smsProvider
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = "sms";

        // Add context to given smsProvider
        mSmsProviderTestable.attachInfoForTesting(mContext, providerInfo);
        Log.d(TAG, "MockContextWithProvider: smsProvider.getContext(): "
                + mSmsProviderTestable.getContext());

        mSmsProviderTestable.mTextClassifier = mContext.getSystemService(
                        TextClassificationManager.class).getTextClassifier();
        ReadRestrictionStatsLogger.setInstance(mReadRestrictionStatsLogger);

        // Add given SmsProvider to mResolver with authority="sms" so that
        // mResolver can send queries to mSmsProvider
        mContentResolver.addProvider("sms", mSmsProviderTestable);
        Log.d(TAG, "MockContextWithProvider: Add SmsProvider to mResolver");
        notifyChangeCount = 0;

        int subid = SmsManager.getDefaultSmsSubscriptionId();

        when(mContext.getSystemService(SubscriptionManager.class)).thenReturn(mSubscriptionManager);
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<>();
        SubscriptionInfo subscriptionInfo1 = new SubscriptionInfo.Builder()
                .setId(subid)
                .setSimSlotIndex(0)
                .build();
        subscriptionInfoList.add(subscriptionInfo1);
        // Return subscriptions associated with SYSTEM user.
        doReturn(subscriptionInfoList).when(mSubscriptionManager)
                .getSubscriptionInfoListAssociatedWithUser(mMyUserHandle);
        doReturn(true).when(mSubscriptionManager).isSubscriptionAssociatedWithUser(subid,
                mMyUserHandle);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mSmsProviderTestable.closeDatabase();
    }

    @Test
    @SmallTest
    public void testInsertUri() {
        // insert test contentValues
        final ContentValues values = new ContentValues();
        values.put(Telephony.Sms.ADDRESS, "12345");
        values.put(Telephony.Sms.BODY, "test");
        values.put(Telephony.Sms.DATE, System.currentTimeMillis()); // milliseconds
        values.put(Telephony.Sms.SEEN, 1);
        values.put(Telephony.Sms.READ, 1);
        values.put(Telephony.Sms.THREAD_ID, 1);

        // test for sms table
        Log.d(TAG, "testInsertSmsTable Inserting contentValues: " + values);
        assertEquals(Uri.parse("content://sms/1"),
                mContentResolver.insert(Uri.parse("content://sms"), values));
        assertEquals(Uri.parse("content://sms/2"),
                mContentResolver.insert(Uri.parse("content://sms"), values));

        // test for attachments table
        values.clear();
        values.put("sms_id", 1);
        values.put("content_url", "test");
        values.put("offset", 0);
        Log.d(TAG, "testInsertAttachmentTable Inserting contentValues: " + values);
        assertEquals(Uri.parse("content://sms/attachments/1"),
                mContentResolver.insert(Uri.parse("content://sms/attachments"), values));
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void testInsert_logsReadRestrictionStats() {
        // insert test contentValues
        final ContentValues values = new ContentValues();
        values.put(Telephony.Sms.ADDRESS, "12345");
        values.put(Telephony.Sms.BODY, "test");
        values.put(Telephony.ReadRestriction.RESTRICTED, true);
        values.put(Telephony.Sms.THREAD_ID, 1);

        mContentResolver.insert(Uri.parse("content://sms"), values);

        verify(mReadRestrictionStatsLogger)
                .onMessageInserted(
                ReadRestrictionStatsLogger.ContentProvider.SMS, Process.myUid(), true);
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void testUpdate_downgradeToUnrestricted_logsReadRestrictionStats() {
        // insert test contentValues
        final ContentValues values = new ContentValues();
        values.put(Telephony.Sms.ADDRESS, "12345");
        values.put(Telephony.Sms.BODY, "test");
        values.put(Telephony.ReadRestriction.RESTRICTED, true);
        values.put(Telephony.Sms.THREAD_ID, 1);

        Uri messageUri = mContentResolver.insert(Uri.parse("content://sms"), values);

        final ContentValues updateValues = new ContentValues();
        updateValues.put(Telephony.ReadRestriction.RESTRICTED, false);
        mContentResolver.update(messageUri, updateValues, null, null);

        verify(mReadRestrictionStatsLogger)
                .onMessageUnrestricted(
                ReadRestrictionStatsLogger.ContentProvider.SMS, Process.myUid());
    }

    @Test
    @SmallTest
    public void testRawTableInsert() {
        // insert test contentValues
        assertEquals(Uri.parse("content://sms/raw/1"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));

        // Query and confirm contents.
        Cursor cursor = mContentResolver.query(mRawUri, null, null, null, null);
        assertEquals(1, cursor.getCount());
        cursor.moveToNext();
        assertEquals(mFakePdu, cursor.getString(cursor.getColumnIndex("pdu")));
        assertEquals(mFakeAddress, cursor.getString(cursor.getColumnIndex("address")));
        assertEquals(mFakeOriginatingAddr,
                cursor.getString(cursor.getColumnIndex("display_originating_addr")));
        assertEquals(mFakeMessageBody, cursor.getString(cursor.getColumnIndex("message_body")));
        assertEquals(mDate, cursor.getInt(cursor.getColumnIndex("date")));
        assertEquals(mFakePort, cursor.getInt(cursor.getColumnIndex("destination_port")));
        assertEquals(mFakeRefNumber, cursor.getInt(cursor.getColumnIndex("reference_number")));
        assertEquals(mFakeSequence, cursor.getInt(cursor.getColumnIndex("sequence")));
        assertEquals(mFakeCount, cursor.getInt(cursor.getColumnIndex("count")));
        assertEquals(0, cursor.getInt(cursor.getColumnIndex("deleted")));

        // Insert another two.
        assertEquals(Uri.parse("content://sms/raw/2"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));
        assertEquals(Uri.parse("content://sms/raw/3"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));

        cursor.close();
    }

    @Test
    @SmallTest
    public void testRawTableDelete() throws Exception {
        assertEquals(Uri.parse("content://sms/raw/1"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));

        // Mark as deleted.
        String where = "reference_number=?";
        String[] whereArgs = {Integer.toString(mFakeRefNumber)};
        assertEquals(1, mContentResolver.delete(mRawUri, where, whereArgs));

        // The row should still be in table, with column "deleted" to be 1.
        Cursor cursor = mSmsProviderTestable.mDeOpenHelper.getReadableDatabase().query(
                "raw", null, null, null, null, null, null);
        assertEquals(1, cursor.getCount());
        cursor.moveToNext();
        assertEquals(1, cursor.getInt(cursor.getColumnIndex("deleted")));
        cursor.close();

        // The deleted row should be purged.
        cursor = mContentResolver.query(mRawUri, null, null, null, null);
        assertEquals(0, cursor.getCount());

        // Permanent delete all rows.
        assertEquals(Uri.parse("content://sms/raw/1"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));
        assertEquals(Uri.parse("content://sms/raw/2"),
                mContentResolver.insert(mRawUri, getFakeRawValue()));
        assertEquals(2, mContentResolver.delete(mRawUriPermanentDelete, null, null));
        cursor = mSmsProviderTestable.mDeOpenHelper.getReadableDatabase().query(
                "raw", null, null, null, null, null, null);
        assertEquals(0, cursor.getCount());
        cursor.close();
    }

    @Test
    @SmallTest
    public void testQuery_withUnbalancedParentheses_returnsNull() {
        Uri testUri = Telephony.Sms.CONTENT_URI;
        String[] projection = new String[]{Telephony.Sms._ID};
        // Verify to check for unbalanced parentheses
        String maliciousSelection = "1=1) OR (1=1";

        Cursor cursor = mSmsProviderTestable.query(testUri, projection, maliciousSelection, null,
                null);
        assertNull("Cursor should be null due to caught exception for unbalanced parentheses",
                cursor);
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void testQuery_triesToReadAllMessages_logsReadRestrictionStats() {
                final ContentValues values = new ContentValues();
        values.put(Telephony.Sms.ADDRESS, "12345");
        values.put(Telephony.Sms.BODY, "test");
        values.put(Telephony.ReadRestriction.RESTRICTED, true);
        values.put(Telephony.Sms.THREAD_ID, 1);

        Uri uri = mContentResolver.insert(Uri.parse("content://sms"), values);

        SQLiteDatabase db = mSmsProviderTestable.mCeOpenHelper.getWritableDatabase();
        try {
            db.execSQL(
                    "CREATE VIEW IF NOT EXISTS sms_restricted AS SELECT _id, thread_id, address, "
                            + "person, date, date_sent, protocol, read, status, type, "
                            + "reply_path_present, subject, body, service_center, locked, sub_id,"
                            + " error_code, creator, seen, read_restriction"
                            + " FROM sms WHERE (type=1 OR type=2)");

            // Query directly from the database to avoid restricted view issues in tests
            try (Cursor cursor = mSmsProviderTestable.query(Telephony.Sms.CONTENT_URI, null, null,
                    null, null)) {
                assertTrue(cursor.moveToFirst());
            }
            verify(mReadRestrictionStatsLogger)
                    .onRestrictedMessagesQueried(
                    ReadRestrictionStatsLogger.ContentProvider.SMS, Process.myUid(), true);
        } finally {
            try {
                db.execSQL("DROP VIEW IF EXISTS sms_restricted");
            } catch (Exception e) {
                Log.e(TAG, "Failed to drop sms_restricted view after test.", e);
            }
        }
    }

    @Test
    @SmallTest
    public void testQuery_withMaliciousClosingParenthesis_returnsNull() {
        Uri testUri = Telephony.Sms.CONTENT_URI;
        String[] projection = new String[]{Telephony.Sms._ID};
        // Verify to check for a closing parenthesis at the beginning
        String maliciousSelection = ") OR (1=1";

        Cursor cursor = mSmsProviderTestable.query(testUri, projection, maliciousSelection, null,
                null);
        assertNull(
                "Cursor should be null due to caught exception for malicious closing parenthesis",
                cursor);
    }

    @Test
    @SmallTest
    public void testQuery_withProperlyBalancedParentheses_doesNotReturnNull() {
        Uri testUri = Telephony.Sms.CONTENT_URI;
        String[] projection = new String[]{Telephony.Sms._ID};
        String[] normalSelections = {
                "(" + Telephony.Sms.READ + "=1)",  // Original test
                "",                                // New: Empty selection
                Telephony.Sms.READ + "=1"          // New: Selection without parentheses
        };

        SQLiteDatabase db = mSmsProviderTestable.mCeOpenHelper.getWritableDatabase();
        try {
            db.execSQL(
                    "CREATE VIEW IF NOT EXISTS sms_restricted AS SELECT _id, thread_id, address, "
                            + "person, date, date_sent, protocol, read, status, type, "
                            + "reply_path_present, subject, body, service_center, locked, sub_id,"
                            + " error_code, creator, seen, read_restriction"
                            + " FROM sms WHERE (type=1 OR type=2)");

            for (String selection : normalSelections) {
                Cursor cursor = null;
                try {
                    cursor = mSmsProviderTestable.query(testUri, projection, selection, null, null);
                    assertNotNull("Cursor should not be null for selection: \"" + selection + "\"",
                            cursor);
                } catch (IllegalArgumentException e) {
                    if (e.getMessage() != null && e.getMessage().contains("Unbalanced brackets")) {
                        fail("Should not have thrown IllegalArgumentException for selection '"
                                + selection + "': " + e.getMessage());
                    }
                    Log.e(TAG,
                            "Unexpected IllegalArgumentException for selection '" + selection + "'",
                            e);
                    fail("Unexpected IllegalArgumentException for selection '" + selection + "': "
                            + e.getMessage());
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        } finally {
            try {
                db.execSQL("DROP VIEW IF EXISTS sms_restricted");
            } catch (Exception e) {
                Log.e(TAG, "Failed to drop sms_restricted view after test.", e);
            }
        }
    }

    @Test
    @SmallTest
    public void testQuery_withMaliciousProjection_rejected() {
        Uri testUri = Telephony.Sms.CONTENT_URI;
        // Malicious projection attempting to execute a subquery or inject SQL
        String[] maliciousProjection = new String[]{"_id", "(SELECT * FROM sms)"};

        try {
            Cursor cursor = mSmsProviderTestable.query(testUri, maliciousProjection, null, null,
                    null);
            assertNull("Cursor should be null due to caught exception for malicious projection",
                    cursor);
        } catch (IllegalArgumentException e) {
            // Expected behavior if strict query validation throws
        }
    }

    @Test
    @SmallTest
    public void testQuery_withMaliciousSortOrder_rejected() {
        Uri testUri = Telephony.Sms.CONTENT_URI;
        String[] projection = new String[]{Telephony.Sms._ID};
        // Malicious sortOrder attempting to inject SQL
        String maliciousSortOrder = "date DESC; DROP TABLE sms;";

        try {
            Cursor cursor = mSmsProviderTestable.query(testUri, projection, null, null,
                    maliciousSortOrder);
            assertNull("Cursor should be null due to caught exception for malicious sortOrder",
                    cursor);
        } catch (IllegalArgumentException e) {
            // Expected behavior if strict query validation throws
        }
    }

    @Test
    @SmallTest
    public void testOtpUpdate_whenDbLocked_retries() {
        try {
            mSmsProviderTestable.mLockedExceptionCountToSimulate = 1;
            mSmsProviderTestable.mUpdateCallCount = 0;
            mSmsProviderTestable.scheduleOtpCheck(Uri.parse("content://sms/1"),
                    "Your OTP code is 123456");
            eventually(() ->
                    assertEquals(2, mSmsProviderTestable.mUpdateCallCount)
            );
        } finally {
            mSmsProviderTestable.mLockedExceptionCountToSimulate = 0;
        }
    }

    @Test
    @SmallTest
    public void testOtpUpdate_whenRetryLimitExceeded_stopsGracefully() {
        try {
            mSmsProviderTestable.mLockedExceptionCountToSimulate =
                    SmsProvider.MAX_DB_UPDATE_ATTEMPTS + 1;
            mSmsProviderTestable.mUpdateCallCount = 0;
            mSmsProviderTestable.scheduleOtpCheck(Uri.parse("content://sms/1"),
                    "Your OTP code is 123456");
            eventually(() ->
                    assertEquals(SmsProvider.MAX_DB_UPDATE_ATTEMPTS,
                            mSmsProviderTestable.mUpdateCallCount)
            );
        } finally {
            mSmsProviderTestable.mLockedExceptionCountToSimulate = 0;
        }
    }

    @Test
    @SmallTest
    public void testInsertOldOtp_skipsClassification() {
        final ContentValues values = new ContentValues();
        values.put(Telephony.Sms.ADDRESS, "12345");
        values.put(Telephony.Sms.BODY, "Your OTP code is 123456");
        // 4 hours ago
        long oldDate = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(4);
        values.put(Telephony.Sms.DATE, oldDate);
        values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
        values.put(Telephony.Sms.THREAD_ID, 1);

        Uri uri = mContentResolver.insert(Uri.parse("content://sms"), values);
        assertNotNull(uri);

        // Query directly from the database to avoid restricted view issues in tests
        try (Cursor cursor = mSmsProviderTestable.mCeOpenHelper.getReadableDatabase().query(
                "sms", new String[]{Telephony.Sms.CONTAINS_OTP},
                "_id=?", new String[]{uri.getLastPathSegment()}, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(Telephony.Sms.OTP_TYPE_NONE, cursor.getInt(0));
        }
    }

    @Test
    @SmallTest
    public void testInsertRecentOtp_triggersClassification() {
        final ContentValues values = new ContentValues();
        values.put(Telephony.Sms.ADDRESS, "12345");
        values.put(Telephony.Sms.BODY, "Your OTP code is 123456");
        // 1 hour ago
        long recentDate = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        values.put(Telephony.Sms.DATE, recentDate);
        values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
        values.put(Telephony.Sms.THREAD_ID, 1);

        Uri uri = mContentResolver.insert(Uri.parse("content://sms"), values);
        assertNotNull(uri);

        // Query directly from the database to avoid restricted view issues in tests
        try (Cursor cursor = mSmsProviderTestable.mCeOpenHelper.getReadableDatabase().query(
                "sms", new String[]{Telephony.Sms.CONTAINS_OTP},
                "_id=?", new String[]{uri.getLastPathSegment()}, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            // Should be PENDING initially
            assertEquals(Telephony.Sms.OTP_TYPE_PENDING, cursor.getInt(0));
        }

        // Wait for classification to finish to avoid IllegalStateException in tearDown
        eventually(() -> {
            try (Cursor c = mSmsProviderTestable.mCeOpenHelper.getReadableDatabase().query(
                    "sms", new String[]{Telephony.Sms.CONTAINS_OTP},
                    "_id=?", new String[]{uri.getLastPathSegment()}, null, null, null)) {
                assertTrue(c.moveToFirst());
                int containsOtp = c.getInt(0);
                assertTrue("OTP classification should no longer be PENDING. Current value: "
                                + containsOtp,
                        containsOtp != Telephony.Sms.OTP_TYPE_PENDING);
            }
        });
    }

    private ContentValues getFakeRawValue() {
        ContentValues values = new ContentValues();
        values.put("pdu", mFakePdu);
        values.put("date", mDate);
        values.put("destination_port", mFakePort);
        values.put("address", mFakeAddress);
        values.put("display_originating_addr", mFakeOriginatingAddr);
        values.put("reference_number", mFakeRefNumber);
        values.put("sequence", mFakeSequence);
        values.put("count", mFakeCount);
        values.put("message_body", mFakeMessageBody);

        return values;
    }
}
