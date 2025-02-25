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

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.stream.Collectors;

import static org.canitrunquarkus.Validation.HEX_FORMAT;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.jboss.resteasy.reactive.RestResponse.Status.TOO_MANY_REQUESTS;
import static org.jboss.resteasy.reactive.RestResponse.StatusCode.ACCEPTED;
import static org.jboss.resteasy.reactive.RestResponse.StatusCode.FORBIDDEN;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CommentResourceTest {

    private static WireMockServer wireMockServer;
    private static final String GITHUB_TOKEN = "ChangeitGitHub";
    private static final String NOT_GITHUB_TOKEN = "Changeit";
    private static final int WIREMOCK_PORT = 8917;
    private static final String MOCK_GH_URI = "/repos/exampleOrg/exampleRepo/issues/123/comments";
    private static final String SIGNATURE = "\\n\\n Comment posted by a robot. Responsible human: Karm.";
    private static final Path TOKEN_HASHES_FILE_PATH = Path.of("/tmp/test-token-hashes.txt");
    private static final String requestBody = """
            {
                "body": "example body",
                "org": "exampleOrg",
                "repo": "exampleRepo",
                "id": 123
            }
            """;

    @BeforeAll
    public static void setup() throws IOException, NoSuchAlgorithmException {
        // Github "API"
        wireMockServer = new WireMockServer(WIREMOCK_PORT);
        wireMockServer.start();
        wireMockServer.stubFor(post(urlEqualTo(MOCK_GH_URI))
                .withHeader("Authorization", equalTo("token " + GITHUB_TOKEN))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"body\": \"example body" + SIGNATURE + "\"}"))
                .willReturn(aResponse()
                        .withStatus(201) // GitHub returns 201 Created for successful comment
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                "{\"id\": 456, \"body\": \"example body" + SIGNATURE + "\", \"created_at\": \"2045-02-24T12:00:00Z\"}")));
        // Test token files
        Files.deleteIfExists(TOKEN_HASHES_FILE_PATH);
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(NOT_GITHUB_TOKEN.getBytes());
        Files.writeString(TOKEN_HASHES_FILE_PATH,
                HEX_FORMAT.formatHex(digest.digest()) + ":" + "exampleOrg/exampleRepo\nsomeOtherTokenHash:someOrg/someRepo\n",
                StandardCharsets.US_ASCII, CREATE, WRITE, TRUNCATE_EXISTING);
    }

    @AfterAll
    public static void tearDown() {
        wireMockServer.stop();
    }

    @Test
    public void testPostComment() {

        wireMockServer.resetRequests();

        given()
                .header("NOT_GITHUB_TOKEN", NOT_GITHUB_TOKEN)
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .log().all()
                .post("/comment")
                .then()
                .log().all()
                .statusCode(ACCEPTED);

        await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> !wireMockServer.findAll(postRequestedFor(
                        urlEqualTo(MOCK_GH_URI))).isEmpty());

        wireMockServer.verify(1, postRequestedFor(urlEqualTo(MOCK_GH_URI))
                .withHeader("Authorization", equalTo("token " + GITHUB_TOKEN))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"body\": \"example body" + SIGNATURE + "\"}")));

        // bad token
        given()
                .header("NOT_GITHUB_TOKEN", NOT_GITHUB_TOKEN + "gibberish")
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .log().all()
                .post("/comment")
                .then()
                .log().all()
                .statusCode(FORBIDDEN);

        // org doesn't match the token
        given()
                .header("NOT_GITHUB_TOKEN", NOT_GITHUB_TOKEN)
                .contentType(ContentType.JSON)
                .body(requestBody.replace("exampleOrg", "gibberishOrg"))
                .when()
                .log().all()
                .post("/comment")
                .then()
                .log().all()
                .statusCode(FORBIDDEN);
    }

    @Test
    public void testUpdateTokenHashes() throws NoSuchAlgorithmException, IOException {

        // This token is not registered in the file.
        final String NEW_NOT_GITHUB_TOKEN = "SomeNewToken";

        given()
                .header("NOT_GITHUB_TOKEN", NEW_NOT_GITHUB_TOKEN)
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .log().all()
                .post("/comment")
                .then()
                .log().all()
                .statusCode(FORBIDDEN);

        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(NEW_NOT_GITHUB_TOKEN.getBytes());
        final String tokenHash = HEX_FORMAT.formatHex(digest.digest());
        Files.writeString(TOKEN_HASHES_FILE_PATH,
                tokenHash + ":" + "exampleOrg/exampleRepo\n",
                StandardCharsets.US_ASCII, CREATE, WRITE, APPEND);

        // Server should notice the file changed, there is an io event watch registered.
        await()
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    try {
                        given()
                                .header("NOT_GITHUB_TOKEN", NEW_NOT_GITHUB_TOKEN)
                                .contentType(ContentType.JSON)
                                .body(requestBody)
                                .when()
                                .log().all()
                                .post("/comment")
                                .then()
                                .log().all()
                                .statusCode(ACCEPTED);
                        return true;
                    } catch (AssertionError e) {
                        return false;
                    }
                });

        // We revoke it now
        Files.writeString(TOKEN_HASHES_FILE_PATH,
                Files.readAllLines(TOKEN_HASHES_FILE_PATH).stream()
                        // Revoke the token by omitting its hash from the file
                        .filter(line -> !line.startsWith(tokenHash))
                        .collect(Collectors.joining("\n")),
                StandardCharsets.US_ASCII, CREATE, WRITE, TRUNCATE_EXISTING);

        // Server should notice the file changed again...
        await()
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    try {
                        given()
                                .header("NOT_GITHUB_TOKEN", NEW_NOT_GITHUB_TOKEN)
                                .contentType(ContentType.JSON)
                                .body(requestBody)
                                .when()
                                .log().all()
                                .post("/comment")
                                .then()
                                .log().all()
                                .statusCode(FORBIDDEN);
                        return true;
                    } catch (AssertionError e) {
                        return false;
                    }
                });
    }

    @Test
    public void testPostThrottling() {
        int tooManyRequestsCount = 0;
        // This is comments.max.queue.size=60 in application.properties
        final int queueSize = 70;
        for (int i = 0; i < queueSize; i++) {
            final int statusCode = given()
                    .header("NOT_GITHUB_TOKEN", NOT_GITHUB_TOKEN)
                    .contentType(ContentType.JSON)
                    .body(requestBody)
                    .when()
                    .log().ifValidationFails()
                    .post("/comment")
                    .then()
                    .log().ifValidationFails()
                    .statusCode(anyOf(Matchers.equalTo(ACCEPTED), Matchers.equalTo(TOO_MANY_REQUESTS.getStatusCode())))
                    .extract().statusCode();
            if (statusCode == TOO_MANY_REQUESTS.getStatusCode()) {
                tooManyRequestsCount++;
            }
        }

        assertTrue(tooManyRequestsCount > 0,
                "Expected at least one 429 Too Many Requests response after " + queueSize + " posts");
    }
}
