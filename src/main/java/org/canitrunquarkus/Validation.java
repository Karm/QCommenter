/*
 * Copyright (c) 2024 Contributors to the QCommenter project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.canitrunquarkus;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import static org.canitrunquarkus.CommentApplication.LOG;
import static org.canitrunquarkus.CommentApplication.accessTokens;

public class Validation {

    public static final HexFormat HEX_FORMAT = HexFormat.of().withLowerCase();
    public static final Pattern ORG_REPO_PATTERN = Pattern.compile("^[a-zA-Z0-9-_]+$");

    public static String validateBody(String body, int maxSize) {
        if (body == null || body.isBlank()) {
            return "Comment body MUST NOT be empty.";
        }
        if (body.length() > maxSize) {
            return "Comment body MUST NOT exceed " + maxSize + " characters.";
        }
        return null;
    }

    public static String validateOrg(String org) {
        if (org == null || org.isBlank()) {
            return "Organization MUST NOT be empty.";
        }
        if (org.length() > 30) {
            return "Organization MUST NOT exceed 30 characters.";
        }
        if (!ORG_REPO_PATTERN.matcher(org).matches()) {
            return "Organization MUST match pattern " + ORG_REPO_PATTERN.pattern();
        }
        return null;
    }

    public static String validateRepo(String repo) {
        if (repo == null || repo.isBlank()) {
            return "Repository MUST NOT be empty.";
        }
        if (repo.length() > 30) {
            return "Repository MUST NOT exceed 30 characters.";
        }
        if (!ORG_REPO_PATTERN.matcher(repo).matches()) {
            return "Repository MUST match pattern " + ORG_REPO_PATTERN.pattern();
        }
        return null;
    }

    public static String validateId(Integer id) {
        if (id == null || id <= 0) {
            return "ID MUST NOT be empty.";
        }
        return null;
    }

    public static boolean validateToken(String token, String orgRepo) throws NoSuchAlgorithmException {
        if (token == null || token.isBlank()) {
            return false;
        }
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(token.getBytes());
        final String expected = accessTokens.get(HEX_FORMAT.formatHex(digest.digest()));
        // Token's hash must be both present in the map and match the expected org/repo.
        if (expected == null || !expected.equals(orgRepo)) {
            LOG.error("Unauthorized access attempt with token: " + token + " for " + orgRepo);
            return false;
        }
        return true;
    }

}
