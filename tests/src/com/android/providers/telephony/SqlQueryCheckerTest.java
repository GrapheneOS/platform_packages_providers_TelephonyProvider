/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SqlQueryCheckerTest {

    @Test
    public void testCheckQueryParametersForSubqueries_valid() {
        String[] projection = {"_id", "body"};
        String selection = "thread_id=1";
        String sortOrder = "date DESC";
        SqlQueryChecker.checkQueryParametersForSubqueries(projection, selection, sortOrder);
    }

    @Test
    public void testCheckQueryParametersForSubqueries_invalidSelect() {
        String[] projection = {"(SELECT _id FROM sms) AS id"};
        assertThrows(IllegalArgumentException.class, () ->
                SqlQueryChecker.checkQueryParametersForSubqueries(projection, null, null));
    }

    @Test
    public void testCheckQueryParametersForSubqueries_invalidFunction() {
        String[] projection = {"hex(body)"};
        assertThrows(IllegalArgumentException.class, () ->
                SqlQueryChecker.checkQueryParametersForSubqueries(projection, null, null));
    }

    @Test
    public void testCheckQueryParametersForSubqueries_invalidUnion() {
        String selection = "1=1 UNION SELECT _id FROM sms";
        assertThrows(IllegalArgumentException.class, () ->
                SqlQueryChecker.checkQueryParametersForSubqueries(null, selection, null));
    }

    @Test
    public void testCheckQueryParametersForSubqueries_invalidJoin() {
        String selection = "1=1 JOIN pdu ON 1=1";
        assertThrows(IllegalArgumentException.class, () ->
                SqlQueryChecker.checkQueryParametersForSubqueries(null, selection, null));
    }

    @Test
    public void testCheckQueryParametersForSubqueries_validAggregateFunctions() {
        String[] projection = {"COUNT(*)", "MAX(date)", "MIN(date)", "SUM(_id)", "AVG(_id)"};
        SqlQueryChecker.checkQueryParametersForSubqueries(projection, null, null);
    }
}
