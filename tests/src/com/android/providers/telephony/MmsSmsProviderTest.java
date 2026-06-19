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

package com.android.providers.telephony;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentResolver;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.telephony.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class MmsSmsProviderTest {
    private static final String TAG = "MmsSmsProviderTest";

    private Context mContext;
    private MockContentResolver mContentResolver;
    private MmsSmsProvider mMmsSmsProvider;
    private SmsProviderTestable mSmsProviderTestable;
    @Rule
    public final MockitoRule mocks = MockitoJUnit.rule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Resources mMockResources;
    @Mock
    private SubscriptionManager mSubscriptionManager;

    @Before
    public void setUp() throws Exception {
        logd("Setup!");
        mContext = spy(ApplicationProvider.getApplicationContext());
        doNothing().when(mContext).sendBroadcast(any());
        PackageManager pm = mContext.getPackageManager();

        // Check for telephony messaging feature
        boolean hasTelephonyMessaging = pm.hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_MESSAGING);
        assumeTrue("Device does not support FEATURE_TELEPHONY_MESSAGING, skipping test",
                hasTelephonyMessaging);

        mMmsSmsProvider = new MmsSmsProvider() {
            @Override
            protected String getOtpFilter(int callerUid, String callingPackage,
                    UserHandle callerUserHandle) {
                return "";
            }
        };
        mSmsProviderTestable = new SmsProviderTestable();

        // Common mock setup
        when(mContext.getSystemService(eq(Context.APP_OPS_SERVICE)))
                .thenReturn(mock(AppOpsManager.class));
        doNothing().when(mContext).sendBroadcast(any(Intent.class));
        when(mContext.getSystemService(eq(Context.TELEPHONY_SERVICE)))
                .thenReturn(mock(TelephonyManager.class));
        when(mContext.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.getResources()).thenReturn(mMockResources);
        when(mContext.getUserId()).thenReturn(0);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getSystemService(SubscriptionManager.class)).thenReturn(mSubscriptionManager);

        // --- Start: Added SubscriptionManager mocks ---
        int subid = SmsManager.getDefaultSmsSubscriptionId();
        List<SubscriptionInfo> subscriptionInfoList = new ArrayList<>();
        SubscriptionInfo subscriptionInfo1 = mock(SubscriptionInfo.class);
        when(subscriptionInfo1.getSubscriptionId()).thenReturn(subid);
        when(subscriptionInfo1.getSimSlotIndex()).thenReturn(0);
        subscriptionInfoList.add(subscriptionInfo1);

        doReturn(subscriptionInfoList).when(mSubscriptionManager)
                .getSubscriptionInfoListAssociatedWithUser(any(UserHandle.class));
        doReturn(true).when(mSubscriptionManager).isSubscriptionAssociatedWithUser(anyInt(),
                any(UserHandle.class));
        // --- End: Added SubscriptionManager mocks ---

        mContentResolver = new MockContentResolver();
        when(mContext.getContentResolver()).thenReturn(mContentResolver);

        // Register MmsSmsProvider
        ProviderInfo mmsSmsProviderInfo = new ProviderInfo();
        mmsSmsProviderInfo.authority = "mms-sms";
        mMmsSmsProvider.attachInfo(mContext, mmsSmsProviderInfo);
        mContentResolver.addProvider("mms-sms", mMmsSmsProvider);

        // Register SmsProviderTestable for "sms" authority
        ProviderInfo smsProviderInfo = new ProviderInfo();
        smsProviderInfo.authority = "sms";
        mSmsProviderTestable.attachInfoForTesting(mContext, smsProviderInfo);
        mContentResolver.addProvider("sms", mSmsProviderTestable);

        // Insert preset data to make sure DB is not empty.
        insertPresetData();
    }

    @After
    public void tearDown() throws Exception {
        logd("TearDown!");
        if (mSmsProviderTestable != null) {
            mSmsProviderTestable.closeDatabase();
        }
    }

    @Test
    public void testQuery_withUnbalancedParentheses_returnsNull() {
        Uri testUri = Uri.parse("content://mms-sms/conversations");
        String[] projection = new String[]{"_id"};
        // Verify to check for unbalanced parentheses
        String maliciousSelection = "1=1) OR (1=1";

        // This should return null because the IllegalArgumentException from checkSelection
        // is caught and handled in MmsSmsProvider.
        Cursor cursor = mMmsSmsProvider.query(testUri, projection, maliciousSelection, null, null);
        assertNull("Cursor should be null due to caught exception for unbalanced parentheses",
                cursor);
    }

    @Test
    public void testQuery_withMaliciousClosingParenthesis_returnsNull() {
        Uri testUri = Uri.parse("content://mms-sms/conversations");
        String[] projection = new String[]{"_id"};
        // Verify to check for a closing parenthesis at the beginning
        String maliciousSelection = ") OR (1=1";

        // This should return null because the IllegalArgumentException from checkSelection
        // is caught and handled in MmsSmsProvider.
        Cursor cursor = mMmsSmsProvider.query(testUri, projection, maliciousSelection, null, null);
        assertNull(
                "Cursor should be null due to caught exception for malicious closing parenthesis",
                cursor);
    }

    @Test
    public void testQuery_withProperlyBalancedParentheses_doesNotReturnNullFromSelectionCheck() {
        Uri queryUri = Uri.parse("content://mms-sms/conversations");
        String[] projection = new String[]{"_id"};
        String[] normalSelections = {
                "(" + Telephony.Sms.READ + "=1)",  // Original test
                "",                                // New: Empty selection
                Telephony.Sms.READ + "=1"          // New: Selection without parentheses
        };

        for (String selection : normalSelections) {
            Cursor cursor = null;
            try {
                cursor = mMmsSmsProvider.query(queryUri, projection, selection, null, null);
                assertNotNull("Cursor should not be null for selection: \"" + selection + "\"",
                        cursor);

            } catch (IllegalArgumentException e) {
                if (e.getMessage() != null && e.getMessage().contains("Unbalanced brackets")) {
                    fail("Should NOT have thrown IllegalArgumentException from checkSelection for"
                            + " selection '"
                            + selection + "': " + e.getMessage());
                }
                Log.w(TAG,
                        "MmsSmsProvider.query threw an expected IllegalArgumentException for "
                                + "selection '"
                                + selection + "': " + e.getMessage());
            } catch (Exception e) {
                Log.w(TAG, "MmsSmsProvider.query threw an exception for selection '" + selection
                        + "': " + e.getMessage());
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_REDACT_OTP_SMS)
    @DisableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void testQuery_searchSuggest_withOtpFilter_returnsEmptyCursor() {
        // Prepare a provider that returns false for canReadOtpSms
        MmsSmsProvider providerWithOtpFilter = new MmsSmsProvider() {
            @Override
            protected String getOtpFilter(int callerUid, String callingPackage,
                    UserHandle callerUserHandle) {
                return "1=1";
            }
        };
        ProviderInfo mmsSmsProviderInfo = new ProviderInfo();
        mmsSmsProviderInfo.authority = "mms-sms";
        providerWithOtpFilter.attachInfo(mContext, mmsSmsProviderInfo);

        // searchSuggest URI
        Uri testUri = Uri.parse("content://mms-sms/searchSuggest")
                .buildUpon()
                .appendQueryParameter("pattern", "test")
                .build();

        // Query the provider
        Cursor cursor = providerWithOtpFilter.query(testUri, null, null, null, null);

        assertNotNull("Cursor should not be null", cursor);
        assertEquals("Cursor should be empty when otpFilter is active", 0, cursor.getCount());
    }

    @Test
    public void testQuery_withNullProjection_doesNotThrowNPE() {
        Cursor cursor = null;
        try {
            Uri testUri = Uri.parse("content://mms-sms/complete-conversations");
            String[] projection = null;
            // This should default to UNION_COLUMNS and not throw an NPE
            cursor = mMmsSmsProvider.query(testUri, projection, null, null, null);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void testQuery_subIdEmpty_returnsEmptyCursor() {
        // Setup mock to return empty subscriptions
        doReturn(new ArrayList<SubscriptionInfo>()).when(mSubscriptionManager)
                .getSubscriptionInfoListAssociatedWithUser(any(UserHandle.class));

        String[] urisToTest = {
                "content://mms-sms/conversations?simple=true",
                "content://mms-sms/conversations/1/recipients",
                "content://mms-sms/conversations/1/subject",
                "content://mms-sms/search?pattern=test",
                "content://mms-sms/searchSuggest?pattern=test"
        };

        for (String uriString : urisToTest) {
            Uri testUri = Uri.parse(uriString);
            Cursor cursor = null;
            try {
                cursor = mMmsSmsProvider.query(testUri, null, null, null, null);
                assertNotNull("Cursor should not be null for URI: " + uriString, cursor);
                assertEquals("Cursor should be empty for URI: " + uriString + " when no subIds", 0,
                        cursor.getCount());
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_SECURE_ACCESS_TO_RESTRICTED_RCS_MESSAGES)
    public void testQuery_subIdValid_doesNotReturnEmptyCursor() {
        // Mocks for sub_id are already setup in setUp()

        String[] urisToTest = {
                "content://mms-sms/conversations?simple=true",
                "content://mms-sms/conversations/1/recipients",
                "content://mms-sms/conversations/1/subject",
                "content://mms-sms/search?pattern=test",
                "content://mms-sms/searchSuggest?pattern=test"
        };

        for (String uriString : urisToTest) {
            Uri testUri = Uri.parse(uriString);
            Cursor cursor = null;
            try {
                cursor = mMmsSmsProvider.query(testUri, null, null, null, null);
                assertNotNull("Cursor should not be null for URI: " + uriString, cursor);
                // We just verify it does not crash and returns a cursor.
                // The actual count might be 0 if the preset data does not match the specific URI
                // (like search), but we are primarily testing that it doesn't short-circuit to
                // an empty cursor due to sub_id checks.
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    }

    private void insertPresetData() {
        // Insert common data for all tests
        try {
            ContentValues values = new ContentValues();
            values.put(Telephony.Sms.ADDRESS, "12345");
            values.put(Telephony.Sms.BODY, "common test body");
            values.put(Telephony.Sms.READ, 1);
            Uri insertedUri = mContentResolver.insert(Telephony.Sms.CONTENT_URI, values);
            if (insertedUri != null) {
                Log.i(TAG, "Common data inserted successfully: " + insertedUri);
            } else {
                Log.w(TAG, "Common data insertion returned null URI.");
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Known issue: Common data insertion failed in setUp: " + e.getMessage());
        }
    }

    @Test
    public void testQuery_withSubquery_returnsNull() {
        Uri testUri = Uri.parse("content://mms-sms/conversations");
        String[] projection = new String[]{"(SELECT _id FROM sms) AS id"};

        Cursor cursor = mMmsSmsProvider.query(testUri, projection, null, null, null);
        assertNull("Cursor should be null due to caught exception for subquery in projection",
                cursor);
    }

    static void logd(String msg) {
        Log.d(TAG, msg);
    }
}
