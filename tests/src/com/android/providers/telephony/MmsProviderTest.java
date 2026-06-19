/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;

import androidx.test.core.app.ApplicationProvider;

import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import com.android.internal.telephony.metrics.ReadRestrictionStatsLogger;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.mockito.Mock;

import junit.framework.TestCase;

import org.junit.Test;

public class MmsProviderTest extends TestCase {
    private static final String TAG = "MmsProviderTest";
    private static final String PARTS_DIR_NAME = "parts";
    private File testDir;
    private File partsDir;

    private AppOpsManager mAppOpsManager;
    private MockContentResolver mContentResolver;
    private MmsProviderTestable mMmsProviderTestable;

    private int notifyChangeCount;

    private Context context;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mMmsProviderTestable = new MmsProviderTestable();

        // setup mocks
        context = mock(Context.class);
        mAppOpsManager = mock(AppOpsManager.class);
        when(mAppOpsManager.noteOpNoThrow(eq(AppOpsManager.OP_READ_RESTRICTED_MESSAGES),
                anyInt(), anyString(), anyString(), anyString())).thenReturn(
                    AppOpsManager.MODE_IGNORED);
        when(mAppOpsManager.noteOpNoThrow(eq(AppOpsManager.OP_WRITE_RESTRICTED_MESSAGES),
                anyInt(), anyString(), anyString(), anyString())).thenReturn(
                    AppOpsManager.MODE_IGNORED);
        PackageManager packageManager = mock(PackageManager.class);
        Resources resources = mock(Resources.class);
        when(context.getSystemService(eq(Context.APP_OPS_SERVICE)))
                .thenReturn(mock(AppOpsManager.class));
        when(context.getSystemService(eq(Context.TELEPHONY_SERVICE)))
                .thenReturn(mock(TelephonyManager.class));
        when(context.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(context.getUserId()).thenReturn(0);
        when(context.getPackageManager()).thenReturn(packageManager);
        when(context.getResources()).thenReturn(resources);
        when(resources.getString(anyInt())).thenReturn("");
        ReadRestrictionStatsLogger.setInstance(mock(ReadRestrictionStatsLogger.class));

        /**
         * This is used to give the MmsProviderTest a mocked context which takes a
         * MmsProvider and attaches it to the ContentResolver with telephony authority.
         * The mocked context also gives WRITE_APN_SETTINGS permissions
         */
        mContentResolver = new MockContentResolver() {
            @Override
            public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork,
                    int userHandle) {
                notifyChangeCount++;
            }
        };
        when(context.getContentResolver()).thenReturn(mContentResolver);

        // Add authority="mms" to given mmsProvider
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = "mms";

        // Add context to given mmsProvider
        mMmsProviderTestable.attachInfoForTesting(context, providerInfo);
        Log.d(TAG, "MockContextWithProvider: mmsProvider.getContext(): "
                + mMmsProviderTestable.getContext());

        // Add given MmsProvider to mResolver with authority="mms" so that
        // mResolver can send queries to mMmsProvider
        mContentResolver.addProvider("mms", mMmsProviderTestable);
        Log.d(TAG, "MockContextWithProvider: Add MmsProvider to mResolver");
        notifyChangeCount = 0;

        //Setup test directory
        testDir = Files.createTempDirectory("testDir").toFile();
        partsDir = new File(testDir, PARTS_DIR_NAME);
        partsDir.mkdirs(); // Create the parts directory
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mMmsProviderTestable.closeDatabase();

        if (testDir != null && testDir.exists()) {
            File[] files = testDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            testDir.delete();
        }
    }

    @Test
    public void testInsertMms() {
        final ContentValues values = getTestContentValues();

        Uri expected = Uri.parse("content://mms/1");
        Uri actual = mContentResolver.insert(Telephony.Mms.CONTENT_URI, values);

        assertEquals(expected, actual);
        assertEquals(1, notifyChangeCount);
    }

    @Test
    public void testDeleteDataRows_pathStartsWithPartsDirectory_canDeleteFile() throws Exception {
        // Create a file in the parts directory.
        when(context.getDir(eq(PARTS_DIR_NAME), anyInt())).thenReturn(partsDir);
        File validFile = new File(partsDir, "mms_part.txt");
        validFile.createNewFile();

        boolean canDeleteFile = MmsProviderTestable.isFilePathCanonical(
                context, validFile);

        assertTrue(canDeleteFile);
    }

    @Test
    public void testDeleteDataRows_invalidPath_cannotDeleteFile() throws Exception {
        // Create a file in the test directory, which is not under the parts directory.
        when(context.getDir(eq(PARTS_DIR_NAME), anyInt())).thenReturn(partsDir);
        File invalidFile = new File(testDir, "not_mms.txt");
        invalidFile.createNewFile();

        boolean canDeleteFile = MmsProviderTestable.isFilePathCanonical(
                context, invalidFile);

        assertFalse(canDeleteFile);
    }

    @Test
    public void testInsertMmsWithoutNotify() {

        MmsProvider.ProviderUtilWrapper providerUtilWrapper =
                mock(MmsProvider.ProviderUtilWrapper.class);
        when(providerUtilWrapper.isAccessRestricted(
                any(Context.class), anyString(), anyInt())).thenReturn(false);
        mMmsProviderTestable.setProviderUtilWrapper(providerUtilWrapper);

        final ContentValues values = getTestContentValues();
        values.put(TelephonyBackupAgent.NOTIFY, false);

        Uri expected = Uri.parse("content://mms/1");
        Uri actual = mContentResolver.insert(Telephony.Mms.CONTENT_URI, values);

        assertEquals(expected, actual);
        assertEquals(0, notifyChangeCount);
    }

    private ContentValues getTestContentValues() {
        final ContentValues values = new ContentValues();
        values.put(Telephony.Mms.READ, 1);
        values.put(Telephony.Mms.SEEN, 1);
        values.put(Telephony.Mms.SUBSCRIPTION_ID, 1);
        values.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_ALL);
        values.put(Telephony.Mms.TEXT_ONLY, 1);
        values.put(Telephony.Mms.THREAD_ID, 1);
        return values;
    }

    @Test
    public void testQuery_withUnbalancedParentheses_throwsIllegalArgumentException() {
        Uri testUri = Telephony.Mms.CONTENT_URI;
        String[] projection = new String[]{"_id"};
        // Verify to check for unbalanced parentheses
        String maliciousSelection = "1=1) OR (1=1";

        Cursor cursor = mMmsProviderTestable.query(testUri, projection, maliciousSelection, null,
                null);
        assertNull("Cursor should be null due to caught exception for unbalanced parentheses",
                cursor);
    }

    @Test
    public void testQuery_withMaliciousClosingParenthesis_throwsIllegalArgumentException() {
        Uri testUri = Telephony.Mms.CONTENT_URI;
        String[] projection = new String[]{"_id"};
        // Verify to check for a closing parenthesis at the beginning
        String maliciousSelection = ") OR (1=1";

        Cursor cursor = mMmsProviderTestable.query(testUri, projection, maliciousSelection, null,
                null);
        assertNull("Cursor should be null due to caught exception for unbalanced parentheses",
                cursor);
    }

    @Test
    public void testQuery_withProperlyBalancedParentheses_doesNotReturnNull() {
        Uri testUri = Telephony.Mms.CONTENT_URI;
        String[] projection = new String[]{"_id"};
        String[] normalSelections = {
                "(thread_id=1)", // Original test
                "",              // New: Empty selection
                "thread_id=1"    // New: Selection without parentheses
        };

        for (String selection : normalSelections) {
            Cursor cursor = null;
            try {
                cursor = mMmsProviderTestable.query(testUri, projection, selection, null, null);
                assertNotNull("Cursor should not be null for selection: \"" + selection + "\"",
                        cursor);
                Log.i(TAG,
                        "Query with selection: \"" + selection + "\" returned cursor: " + cursor);
            } catch (IllegalArgumentException e) {
                if (e.getMessage() != null && e.getMessage().contains("Unbalanced brackets")) {
                    fail("Should not have thrown IllegalArgumentException for selection '"
                            + selection + "': " + e.getMessage());
                }
                Log.e(TAG, "Unexpected IllegalArgumentException for selection '" + selection + "'",
                        e);
                fail("Unexpected IllegalArgumentException for selection '" + selection + "': "
                        + e.getMessage());
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    }

    @Test
    public void testDelete_withSelectionArgs_doesNotCheckSelectionArgsForForbiddenColumns() {
        Uri testUri = Telephony.Mms.CONTENT_URI;
        String selection = "thread_id=?";
        // "restricted" is a forbidden column.
        // If selectionArgs is checked, it would throw an exception.
        String[] selectionArgs = new String[]{"restricted"};

        try {
            mMmsProviderTestable.delete(testUri, selection, selectionArgs);
        } catch (IllegalArgumentException e) {
            fail("delete should not throw IllegalArgumentException for selectionArgs: "
                    + e.getMessage());
        }
    }

    @Test
    public void testUpdate_withSelectionArgs_doesNotCheckSelectionArgsForForbiddenColumns() {
        Uri testUri = Telephony.Mms.CONTENT_URI;
        ContentValues values = new ContentValues();
        values.put(Telephony.Mms.READ, 1);
        String selection = "thread_id=?";
        // "restricted" is a forbidden column.
        // If selectionArgs is checked, it would throw an exception.
        String[] selectionArgs = new String[]{"restricted"};

        try {
            mMmsProviderTestable.update(testUri, values, selection, selectionArgs);
        } catch (IllegalArgumentException e) {
            fail("update should not throw IllegalArgumentException for selectionArgs: "
                    + e.getMessage());
        }
    }

    @Test
    public void testInsertMms_withAddressAndThreadId_removesAddressColumn() {
        final ContentValues values = getTestContentValues();
        values.put(Telephony.Mms.THREAD_ID, 1);
        values.put(Telephony.CanonicalAddressesColumns.ADDRESS, "12345");

        Uri expected = Uri.parse("content://mms/1");
        Uri actual = mContentResolver.insert(Telephony.Mms.CONTENT_URI, values);

        assertEquals(expected, actual);
    }

    @Test
    public void testUpdateMms_withAddress_removesAddressColumn() {
        final ContentValues values = getTestContentValues();
        Uri uri = mContentResolver.insert(Telephony.Mms.CONTENT_URI, values);
        assertNotNull(uri);

        final ContentValues updateValues = new ContentValues();
        updateValues.put(Telephony.Mms.SUBJECT, "new subject");
        updateValues.put(Telephony.CanonicalAddressesColumns.ADDRESS, "12345");

        int count = mContentResolver.update(uri, updateValues, null, null);
        assertEquals(1, count);
    }

    @Test
    public void testInsertMms_withoutSubId_defaultsToInvalidSubIdWithoutCrash() {
        final ContentValues values = getTestContentValues();
        values.remove(Telephony.Mms.SUBSCRIPTION_ID);

        Uri expected = Uri.parse("content://mms/1");
        Uri actual = mContentResolver.insert(Telephony.Mms.CONTENT_URI, values);

        assertEquals(expected, actual);
    }

    @Test
    public void testQuery_withSubquery_throwsIllegalArgumentException() {
        MmsProvider.ProviderUtilWrapper providerUtilWrapper =
                mock(MmsProvider.ProviderUtilWrapper.class);
        when(providerUtilWrapper.isAccessRestricted(
                any(Context.class), anyString(), anyInt())).thenReturn(true);
        mMmsProviderTestable.setProviderUtilWrapper(providerUtilWrapper);

        Uri testUri = Telephony.Mms.CONTENT_URI;
        String[] projection = new String[]{"(SELECT _id FROM sms) AS id"};

        org.junit.Assert.assertThrows(IllegalArgumentException.class, () ->
                mMmsProviderTestable.query(testUri, projection, null, null, null));
    }
}
