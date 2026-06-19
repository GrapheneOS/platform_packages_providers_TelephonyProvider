/*
 * Copyright 2019 The Android Open Source Project
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

import android.util.Log;
import android.provider.Telephony.ReadRestriction;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public class SqlQueryChecker {
    private static final Set<String> DISALLOWED_TOKENS = Set.of(
            "SELECT",
            "UNION",
            "EXCEPT",
            "INTERSECT",
            "JOIN",
            "FROM",
            "GROUP",
            "HAVING",
            "WINDOW",
            "VALUES"
    );

    private static final Set<String> DISALLOWED_FUNCTIONS = Set.of(
            "HEX", "SUBSTR", "SQLITE_VERSION", "UNICODE", "PRINTF", "INSTR",
            "RANDOM", "RANDOMBLOB", "ZEROBLOB", "TYPEOF"
    );

    private static final Set<String> FORBIDDEN_TOKENS =
        Set.of(ReadRestriction.READ_RESTRICTION_COLUMN_NAME);

    static void checkTokenForDisallowed(String token) {
        String tokenUpper = token.toUpperCase(Locale.US);
        if (DISALLOWED_TOKENS.contains(tokenUpper)) {
            throw new IllegalArgumentException(tokenUpper + " token not allowed in query");
        }
        if (DISALLOWED_FUNCTIONS.contains(tokenUpper)) {
            throw new IllegalArgumentException("Function " + tokenUpper + " not allowed in query");
        }
    }

    private static void checkTokenForForbiddenColumns(String token) {
        if (FORBIDDEN_TOKENS.contains(token)) {
            throw new IllegalArgumentException(
                String.format("%s token not allowed in query", token));
        }
    }

    /**
     * Check the query parameters to see if they contain disallowed tokens. Throws an
     * {@link IllegalArgumentException} if they do. See
     * {@link android.content.ContentProvider#query} for the definitions of the arguments.
     */
    static void checkQueryParametersForSubqueries(String[] projection,
            String selection, String sortOrder) {
        checkQueryForToken(projection, selection, sortOrder, "MmsProvider",
                "checkQueryParametersForSubqueries", SqlQueryChecker::checkTokenForDisallowed);
    }

    /**
     * Check the query parameters to see if they contain reference to columns that shouldn't be
     * queried or modified directly by apps. Throws an {@link IllegalArgumentException} if they do.
     */
    static void checkQueryForForbiddenColumns(String[] projection,
            String selection, String sortOrder, String logTag) {
        checkQueryForToken(projection, selection, sortOrder, logTag,
                "checkQueryForForbiddenColumns", SqlQueryChecker::checkTokenForForbiddenColumns);
    }

    /**
     * Check the selection's bracketing, throwing an {@link IllegalArgumentException} if
     * it is invalid. An invalid selection string could have unbalanced parentheses
     * or attempt to break out of the intended boolean structure, potentially leading to
     * SQL injection vulnerabilities.
     */
    static void checkSelection(String selection) {
        Log.v("MmsProvider", "inside checkSelection checking sel: " + selection);
        SQLiteTokenizer.tokenize(selection, SQLiteTokenizer.OPTION_CHECK_BRACKETS, null);
    }


    private static void checkQueryForToken(String[] projection, String selection,
            String sortOrder, String logTag, String methodName, Consumer<String> checker) {
        Log.v(logTag, "inside " + methodName);
        if (projection != null) {
            for (String proj : projection) {
                Log.v(logTag, methodName + " checking proj: " + proj);
                SQLiteTokenizer.tokenize(proj, SQLiteTokenizer.OPTION_NONE, checker);
            }
        }
        Log.v(logTag, methodName + " checking sel: " + selection);
        SQLiteTokenizer.tokenize(selection, SQLiteTokenizer.OPTION_NONE, checker);
        Log.v(logTag, methodName + " checking sort: " + sortOrder);
        SQLiteTokenizer.tokenize(sortOrder, SQLiteTokenizer.OPTION_NONE, checker);
    }
}
