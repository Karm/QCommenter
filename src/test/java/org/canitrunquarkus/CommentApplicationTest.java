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
import io.vertx.core.json.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.jboss.resteasy.reactive.RestResponse.StatusCode.CREATED;
import static org.jboss.resteasy.reactive.RestResponse.StatusCode.FOUND;
import static org.jboss.resteasy.reactive.RestResponse.StatusCode.OK;

@QuarkusTest
class CommentApplicationTest {

    private static WireMockServer wireMockServer;
    private static final int WIREMOCK_PORT = 8917;
    private static final String GITHUB_API_URL = "http://localhost:" + WIREMOCK_PORT;
    private static final String TOKEN = "ghp_fake_token";
    private static final String ORG = "testOrg";
    private static final String REPO = "testRepo";
    private static final String PR_NUMBER = "1";
    private static final String RUN_ID = "123456789";
    private static final String ARTIFACT_ID = "987654321";
    private static final String SIGNATURE = "\n\nComment posted by a robot. Responsible human: Karm.";
    private static final Path TOKEN_HASHES_FILE_PATH = Paths.get("/tmp/test-token-hashes.txt");
    private static final String COMMENT_BODY = "Test comment from workflow\nand it has multiple lines and perhaps tabs \t too.";
    private static final String ESCAPED_COMMENT_BODY = Json.encode(COMMENT_BODY);
    private static final String ESCAPED_COMMENT_BODY_SIGNATURE = Json.encode(COMMENT_BODY + SIGNATURE);

    @BeforeAll
    public static void setup() throws IOException {
        wireMockServer = new WireMockServer(WIREMOCK_PORT);
        wireMockServer.start();
        // /pulls endpoint: one open PR
        wireMockServer.stubFor(get(urlEqualTo("/repos/" + ORG + "/" + REPO + "/pulls?state=open"))
                .withHeader("Authorization", equalTo("token " + TOKEN))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format("""
                                [
                                    {
                                        "number": %s,
                                        "head": {"sha": "abc123"},
                                        "org": "%s",
                                        "repo": "%s"
                                    }
                                ]
                                """, PR_NUMBER, ORG, REPO))));
        // /actions/runs endpoint: one completed workflow run
        // some time in the future to simulate a new run
        final String updatedAt = Instant.now().plusSeconds(10).toString();
        wireMockServer.stubFor(get(urlMatching(
                "/repos/" + ORG + "/" + REPO + "/actions/runs\\?event=pull_request&per_page=\\d+&sort=created&direction=desc"))
                .withHeader("Authorization", equalTo("token " + TOKEN))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format("""
                                {
                                    "workflow_runs": [
                                        {
                                            "id": %s,
                                            "name": "Build",
                                            "conclusion": "success",
                                            "created_at": "2025-02-27T09:00:00Z",
                                            "updated_at": "%s",
                                            "head_sha": "abc123",
                                            "pull_requests": [{"number": %s}]
                                        }
                                    ]
                                }
                                """, RUN_ID, updatedAt, PR_NUMBER))));
        // /actions/runs/{runId}/artifacts endpoint: one artifact
        wireMockServer.stubFor(get(urlEqualTo("/repos/" + ORG + "/" + REPO + "/actions/runs/" + RUN_ID + "/artifacts"))
                .withHeader("Authorization", equalTo("token " + TOKEN))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(String.format("""
                                {
                                    "artifacts": [
                                        {
                                            "id": %s,
                                            "name": "comment-data-%s",
                                            "archive_download_url": "/repos/%s/%s/actions/artifacts/%s/zip"
                                        }
                                    ]
                                }
                                """, ARTIFACT_ID, PR_NUMBER, ORG, REPO, ARTIFACT_ID))));
        // download: redirect to ZIP
        // this is what GitHub API does, it redirects you to some Azure endpoint
        wireMockServer.stubFor(get(urlEqualTo("/repos/" + ORG + "/" + REPO + "/actions/artifacts/" + ARTIFACT_ID + "/zip"))
                .withHeader("Authorization", equalTo("token " + TOKEN))
                .willReturn(aResponse()
                        .withStatus(FOUND)
                        .withHeader("Location", GITHUB_API_URL + "/artifact.zip")));
        wireMockServer.stubFor(get(urlEqualTo("/artifact.zip"))
                .willReturn(aResponse()
                        .withStatus(OK)
                        .withHeader("Content-Type", "application/zip")
                        // this is what's expected of the GHA yaml file...
                        .withBody(createZipWithJson(String.format("""
                                {
                                    "pr_number": "%s",
                                    "org": "%s",
                                    "repo": "%s",
                                    "comment_body": %s
                                }
                                """, PR_NUMBER, ORG, REPO, ESCAPED_COMMENT_BODY)))));
        // comment posting endpoint
        wireMockServer.stubFor(post(urlEqualTo("/repos/" + ORG + "/" + REPO + "/issues/" + PR_NUMBER + "/comments"))
                .withHeader("Authorization", equalTo("token " + TOKEN))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"body\": " + ESCAPED_COMMENT_BODY_SIGNATURE + "}"))
                .willReturn(aResponse()
                        .withStatus(CREATED)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                "{\"id\": \"456\", \"body\": " + ESCAPED_COMMENT_BODY_SIGNATURE + ", \"created_at\": \"" + Instant.now() + "\"}")));
        // fake token file
        Files.deleteIfExists(TOKEN_HASHES_FILE_PATH);
        Files.writeString(TOKEN_HASHES_FILE_PATH, TOKEN + ":" + ORG + ":" + REPO + "\n", StandardCharsets.US_ASCII);
    }

    @AfterAll
    public static void tearDown() {
        wireMockServer.stop();
    }

    @Test
    public void testProcessSinglePRWorkflowAndArtifact() {
        wireMockServer.resetRequests();
        // the polling is set to 1 second for testing profile...
        await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> !wireMockServer.findAll(postRequestedFor(urlEqualTo(
                                "/repos/" + ORG + "/" + REPO + "/issues/" + PR_NUMBER + "/comments")))
                        .isEmpty());
        wireMockServer.verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo(
                "/repos/" + ORG + "/" + REPO + "/pulls?state=open")));
        wireMockServer.verify(moreThanOrExactly(1), getRequestedFor(urlMatching(
                "/repos/" + ORG + "/" + REPO + "/actions/runs\\?event=pull_request&per_page=\\d+&sort=created&direction=desc")));
        wireMockServer.verify(moreThanOrExactly(1),
                getRequestedFor(urlEqualTo(
                        "/repos/" + ORG + "/" + REPO + "/actions/runs/" + RUN_ID + "/artifacts")));
        wireMockServer.verify(moreThanOrExactly(1),
                getRequestedFor(urlEqualTo(
                        "/repos/" + ORG + "/" + REPO + "/actions/artifacts/" + ARTIFACT_ID + "/zip")));
        wireMockServer.verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo("/artifact.zip")));
        wireMockServer.verify(moreThanOrExactly(1),
                postRequestedFor(urlEqualTo("/repos/" + ORG + "/" + REPO + "/issues/" + PR_NUMBER + "/comments"))
                        .withHeader("Authorization", equalTo("token " + TOKEN))
                        .withHeader("Accept", equalTo("application/vnd.github+json"))
                        .withHeader("Content-Type", equalTo("application/json"))
                        .withRequestBody(equalToJson("{\"body\": " + ESCAPED_COMMENT_BODY_SIGNATURE + "}")));
        await()
                // polling picks up fast in test profile
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    try {
                        given()
                                .when()
                                .get("/comment/status")
                                .then()
                                .log().all()
                                .statusCode(OK)
                                .contentType(ContentType.JSON)
                                .body("size", org.hamcrest.Matchers.equalTo("0"))
                                .body("commentsSinceStart", org.hamcrest.Matchers.equalTo("1"));
                        return true;
                    } catch (AssertionError e) {
                        return false;
                    }
                });
    }

    private static byte[] createZipWithJson(String json) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("comment-data.json"));
            zos.write(json.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test ZIP", e);
        }
        return baos.toByteArray();
    }
}
