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
 */
package org.canitrunquarkus;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.impl.ConcurrentHashSet;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

@ApplicationScoped
public class CommentApplication {

    public static final Logger LOG = Logger.getLogger(CommentApplication.class);

    @Inject
    Vertx vertx;

    private WebClient webClient;

    public static final ConcurrentHashSet<String> githubTokenOrgRepo = new ConcurrentHashSet<>();

    @ConfigProperty(name = "token.hashes.file")
    private String tokenHashesFilePath;

    private Path tokenHashesFile;
    private Path dir;

    @ConfigProperty(name = "comments.append.signature")
    private String signature;

    @ConfigProperty(name = "my.github.api.url")
    private String githubApiUrl;

    @ConfigProperty(name = "comments.batch.intervals.seconds")
    private Long batchIntervalSeconds;

    @ConfigProperty(name = "comments.batch.size")
    private Integer batchSize;

    @ConfigProperty(name = "comments.max.queue.size")
    private Integer maxCommentQueueSize;

    @ConfigProperty(name = "comments.max.age.seconds")
    private Integer maxCommentAgeSeconds;

    @ConfigProperty(name = "polling.interval.seconds")
    private Integer pollingIntervalSeconds;

    @ConfigProperty(name = "polling.workflow.runs.pages")
    private Integer pollingWorkflowRunsPages;

    @ConfigProperty(name = "yaml.workflow.artifact.name.prefix")
    private String artifactNamePrefix;

    @ConfigProperty(name = "yaml.workflow.name")
    private String workflowName;

    public final AtomicInteger commentCounter = new AtomicInteger(0);
    private Queue<Comment> commentQueue;
    private WatchService watchService;

    private final ConcurrentMap<Integer, Long> lastProcessedRunIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, String> prHeadShas = new ConcurrentHashMap<>();

    public record Comment(String body, String org, String repo, String token, Integer id, Instant age) {
    }

    public record WorkflowRunData(String prNumber, String org, String repo, String runId, String commentBody) {
    }

    @PostConstruct
    public void init() throws IOException {
        // keep an eye on the file with tokens
        watchService = FileSystems.getDefault().newWatchService();
        webClient = WebClient.create(vertx, new WebClientOptions()
                .setSsl(githubApiUrl.startsWith("https:")));
        commentQueue = new LinkedBlockingQueue<>(maxCommentQueueSize);
        tokenHashesFile = Paths.get(tokenHashesFilePath);
        dir = tokenHashesFile.getParent();
        if (!Files.exists(tokenHashesFile)) {
            LOG.warn("Token hashes file does not exist (yet): " + tokenHashesFile.toAbsolutePath());
        }
    }

    void onStart(@Observes StartupEvent event) {
        startFileWatcher();
        processFile();
        vertx.setPeriodic(batchIntervalSeconds * 1000L, id -> processCommentsBatch());
        vertx.setPeriodic(pollingIntervalSeconds * 1000L, id -> pollWorkflowRuns());
    }

    void onStop(@Observes ShutdownEvent ev) throws IOException {
        vertx.close();
        webClient.close();
        watchService.close();
    }

    private void startFileWatcher() {
        final Callable<Void> watcherTask = () -> {
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
            while (true) {
                final WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context().equals(tokenHashesFile.getFileName())) {
                        vertx.runOnContext(v -> processFile());
                    }
                }
                if (!key.reset()) {
                    LOG.error("Watch key no longer valid. Stopping watcher.");
                    break;
                }
            }
            return null;
        };
        vertx.executeBlocking(watcherTask)
                .onFailure(throwable -> LOG.error("File watcher failed: " + throwable));
    }

    private void processFile() {
        try (Stream<String> lines = Files.lines(tokenHashesFile)) {
            final Set<String> newTokens = lines.collect(Collectors.toSet());
            LOG.info("Loaded " + newTokens.size() + " tokens from " + tokenHashesFile.toAbsolutePath());
            githubTokenOrgRepo.removeIf(tokenEntry -> !newTokens.contains(tokenEntry));
            githubTokenOrgRepo.addAll(newTokens);
            LOG.info("Updated githubTokenOrgRepo, size now: " + githubTokenOrgRepo.size());
        } catch (Exception e) {
            LOG.error("Failed to process file: " + e.getMessage() + ", it might not exist at this time.");
        }
    }

    public int getQueueSize() {
        return commentQueue.size();
    }

    private void processCommentsBatch() {
        if (commentQueue.isEmpty()) {
            return;
        }
        for (int i = 0; i < batchSize && !commentQueue.isEmpty(); i++) {
            final Comment comment = commentQueue.poll();
            if (comment != null && comment.age().isAfter(Instant.now().minusSeconds(maxCommentAgeSeconds))) {
                sendCommentToGitHub(comment);
            } else if (comment != null) {
                LOG.error("Comment is too old and will NOT be posted: " + comment.body());
            }
        }
    }

    private void sendCommentToGitHub(Comment comment) {
        final String jsonBody = "{\"body\": " + comment.body() + "}";
        LOG.info("Posting comment to GitHub: " + jsonBody);
        final String target = githubApiUrl + "/repos/" + comment.org() + "/" + comment.repo() + "/issues/" + comment.id() + "/comments";
        webClient.postAbs(target)
                .putHeader("Authorization", "token " + comment.token)
                .putHeader("Accept", "application/vnd.github+json")
                .putHeader("Content-Type", "application/json")
                .sendBuffer(Buffer.buffer(jsonBody))
                .onSuccess(response -> {
                    commentCounter.incrementAndGet();
                    LOG.info("Comment posted to " + target + ": " + response.bodyAsString());
                })
                .onFailure(err -> {
                    LOG.error("Failed to post comment to " + target + " and will try again: " + err.getMessage());
                    commentQueue.offer(comment);
                });
    }

    private void pollWorkflowRuns() {
        // We go serially, shouldn't matter for the few repos we have and
        // at least we don't hammer the GitHub API
        for (String tokenOrgRepo : githubTokenOrgRepo) {
            final String[] parts = tokenOrgRepo.split(":");
            if (parts.length != 3) {
                LOG.error("Invalid tokenOrgRepo, expected 3 parts, but got: " + parts.length);
                continue;
            }
            final String token = parts[0];
            final String org = parts[1];
            final String repo = parts[2];
            webClient.getAbs(githubApiUrl + "/repos/" + org + "/" + repo + "/pulls?state=open")
                    .putHeader("Authorization", "token " + token)
                    .putHeader("Accept", "application/vnd.github+json")
                    .send()
                    .onSuccess(response -> {
                        final JsonArray prs = response.bodyAsJsonArray();
                        for (int i = 0; i < prs.size(); i++) {
                            final JsonObject pr = prs.getJsonObject(i);
                            final Integer prNumber = pr.getInteger("number");
                            final String headSha = pr.getJsonObject("head").getString("sha");
                            prHeadShas.put(prNumber, headSha);
                            checkWorkflowRunsForPR(prNumber, org, repo, token);
                        }
                    })
                    .onFailure(err -> LOG.error("Failed to fetch PRs for " + org + "/" + repo + ": " + err.getMessage()));
        }
    }

    private void checkWorkflowRunsForPR(Integer prNumber, String org, String repo, String token) {
        webClient.getAbs(
                        githubApiUrl + "/repos/" + org + "/" + repo + "/actions/runs?event=pull_request&per_page=" + pollingWorkflowRunsPages + "&sort=created&direction=desc")
                .putHeader("Authorization", "token " + token)
                .putHeader("Accept", "application/vnd.github+json")
                .send()
                .onSuccess(response -> {
                    final JsonArray runs = response.bodyAsJsonObject().getJsonArray("workflow_runs");
                    LOG.debug("Fetched " + runs.size() + " runs for PR #" + prNumber);
                    for (int i = 0; i < runs.size(); i++) {
                        final JsonObject run = runs.getJsonObject(i);
                        final Long runId = run.getLong("id");
                        final String conclusion = run.getString("conclusion");
                        final String createdAt = run.getString("created_at");
                        final JsonArray prsInRun = run.getJsonArray("pull_requests");
                        final String headSha = run.getString("head_sha");
                        LOG.debug("Run " + runId + " for PR #" + prNumber + " created at " + createdAt +
                                " has conclusion: " + conclusion + ", PRs: " + prsInRun.encode() +
                                ", head_sha: " + headSha);
                        // failed workflows have results too...
                        if (workflowName.equals(run.getString("name")) &&
                                ("success".equals(conclusion) || "failure".equals(conclusion))) {
                            // check PRs array: MAy be empty if ran from fork
                            boolean prMatch = false;
                            for (int j = 0; j < prsInRun.size(); j++) {
                                if (prNumber.equals(prsInRun.getJsonObject(j).getInteger("number"))) {
                                    prMatch = true;
                                    break;
                                }
                            }
                            // for fork PRs, use head_sha to match with PR data from pollWorkflowRuns
                            if (!prMatch) {
                                // try fetch PR's head SHA from pollWorkflowRuns context
                                prMatch = headSha.equals(prHeadShas.get(prNumber));
                            }
                            if (prMatch) {
                                if (!runId.equals(lastProcessedRunIds.get(prNumber))) {
                                    LOG.debug("Processing workflow run " + runId + " for PR #" + prNumber);
                                    processWorkflowRun(runId, prNumber, org, repo, token);
                                } else {
                                    LOG.debug("Skipping run " + runId + " for PR #" + prNumber + " (already processed)");
                                }
                            } else {
                                LOG.debug("Skipping run " + runId + " for PR #" + prNumber + " (PR not matched)");
                            }
                        } else {
                            LOG.debug("Skipping run " + runId + " for PR #" + prNumber + " with conclusion " + conclusion);
                        }
                    }
                })
                .onFailure(err -> LOG.error(
                        "Failed to fetch runs for PR #" + prNumber + " repo " + org + "/" + repo + ": " + err.getMessage()));
    }

    private void processWorkflowRun(Long runId, Integer prNumber, String org, String repo, String token) {
        LOG.debug("Processing workflow run " + runId + " for PR #" + prNumber);
        webClient.getAbs(githubApiUrl + "/repos/" + org + "/" + repo + "/actions/runs/" + runId + "/artifacts")
                .putHeader("Authorization", "token " + token)
                .putHeader("Accept", "application/vnd.github+json")
                .send()
                .onSuccess(response -> {
                    final JsonObject artifactsData = response.bodyAsJsonObject();
                    final JsonArray artifacts = artifactsData.getJsonArray("artifacts");
                    // e.g. comment-data-8.zip
                    final String artifactName = artifactNamePrefix + prNumber;
                    LOG.debug("Fetched " + artifacts.size() + " artifacts for run " + runId + "and PR #" + prNumber);
                    for (int i = 0; i < artifacts.size(); i++) {
                        final JsonObject artifact = artifacts.getJsonObject(i);
                        if (artifactName.equals(artifact.getString("name"))) {
                            LOG.debug("Processing artifact " + artifact.getString("name") + " from run " + runId);
                            downloadAndProcessArtifact(artifact.getLong("id"), runId, org, repo, token);
                            break;
                        } else {
                            LOG.debug("Skipping artifact " + artifact.getString("name") + " from run " + runId);
                        }
                    }
                })
                .onFailure(err -> LOG.error(
                        "Failed to fetch artifacts for run " + runId + " and PR #" + prNumber + " repo " + org + "/" + repo + ": " + err.getMessage()));
    }

    private void downloadAndProcessArtifact(Long artifactId, Long runId, String org, String repo, String token) {
        final String url = githubApiUrl + "/repos/" + org + "/" + repo + "/actions/artifacts/" + artifactId + "/zip";
        LOG.debug("Requesting artifact from: " + url);
        webClient.getAbs(url)
                .putHeader("Authorization", "token " + token)
                // this was a tough one; vert.x would pass on the Authorization header to the Azure blob storage
                // and the request would fail :)  So we have to do the redirect manually. Not sure if I could tell
                // vert.x not to do this.
                .followRedirects(false)
                .send()
                .onSuccess(response -> {
                    LOG.debug("Initial response status code: " + response.statusCode());
                    LOG.debug("Initial response headers: " + response.headers().entries().toString());
                    if (response.statusCode() == 302) {
                        final String redirectUrl = response.getHeader("Location");
                        LOG.debug("Redirecting to: " + redirectUrl);
                        webClient.getAbs(redirectUrl)
                                .send()
                                .onSuccess(redirectResponse -> {
                                    LOG.debug("Redirect response status code: " + redirectResponse.statusCode());
                                    LOG.debug("Redirect response headers: " + redirectResponse.headers().entries().toString());
                                    processArtifactResponse(redirectResponse, artifactId, runId, org, repo, token);
                                })
                                .onFailure(err -> LOG.error(
                                        "Failed to follow redirect for artifact " + artifactId + " from run " + runId + " repo " + org + "/" + repo + ": " + err.getMessage()));
                    } else if (response.statusCode() == 200) {
                        // no redirect, e.g. local thing
                        processArtifactResponse(response, artifactId, runId, org, repo, token);
                    } else {
                        LOG.error(
                                "Unexpected initial response: " + response.statusCode() + ", body: " + response.bodyAsString());
                    }
                })
                .onFailure(err -> LOG.error(
                        "Failed to download artifact " + artifactId + " for run " + runId + " repo " + org + "/" + repo + ": " + err.getMessage()));
    }

    private void processArtifactResponse(HttpResponse<Buffer> response, Long artifactId, Long runId, String org, String repo,
            String token) {
        final Buffer buffer = response.bodyAsBuffer();
        if (buffer == null || buffer.length() == 0) {
            LOG.error(
                    "Response buffer is null or empty for artifact " + artifactId + " from run " + runId + " repo " + org + "/" + repo);
            return;
        }
        final byte[] zipBytes = buffer.getBytes();
        LOG.debug(
                "Downloaded artifact " + artifactId + " from run " + runId + ", size: " + zipBytes.length + " bytes, repo " + org + "/" + repo);
        if (zipBytes.length < 4 || zipBytes[0] != 0x50 || zipBytes[1] != 0x4B) {
            LOG.error("Response is not a ZIP file. Here is at most first 100 bytes as UTF-8: " + new String(zipBytes, 0,
                    Math.min(100, zipBytes.length), UTF_8));
            return;
        }
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            final ZipEntry entry = zis.getNextEntry();
            if (entry == null) {
                LOG.error("No entries found in artifact " + artifactId + " from run " + runId + " repo " + org + "/" + repo);
                return;
            }
            LOG.debug("Found ZIP entry: " + entry.getName() + ", size: " + entry.getSize());
            final String json = new String(zis.readAllBytes(), UTF_8);
            LOG.debug("Content of " + entry.getName() + ": " + json);
            if (json.isBlank()) {
                LOG.error("Artifact " + artifactId + " entry " + entry.getName() + " from run " + runId + " is empty");
                return;
            }
            /*
             * Expected JSON data in the artifact:
             * {
             *   "pr_number": "8",
             *   "org": "Karm",
             *   "repo": "quarkus-images",
             *   "comment_body": "Comment..."
             * }
             */
            final JsonObject data = new JsonObject(json);
            LOG.debug("Gonna process this data: " + data.encode());
            // Do I have to do this to have it escaped?
            final String escapedCommentBody = Json.encode(data.getString("comment_body"));
            LOG.debug("Escaped comment_body: " + escapedCommentBody);
            final WorkflowRunData runData = new WorkflowRunData(
                    data.getString("pr_number"),
                    data.getString("org"),
                    data.getString("repo"),
                    runId.toString(),
                    escapedCommentBody
            );
            if (!runData.org().equals(org) || !runData.repo().equals(repo)) {
                LOG.error(
                        "Mismatch in org/repo for run " + runId + ": " + org + "/" + repo + " vs " + runData.org() + "/" + runData.repo());
                return;
            }
            LOG.debug("Gonna queue this comment: " + escapedCommentBody);
            if (commentQueue.offer(
                    new Comment(escapedCommentBody, runData.org(), runData.repo(), token, Integer.parseInt(runData.prNumber()),
                            Instant.now()))) {
                LOG.info("Queued comment for PR #" + runData.prNumber() + " from run " + runId + " repo " + org + "/" + repo);
                lastProcessedRunIds.put(Integer.parseInt(runData.prNumber()), Long.parseLong(runData.runId()));
            } else {
                LOG.error("Failed to queue comment for PR #" + runData.prNumber() + " from run " + runId + " (queue full)");
            }
        } catch (IOException e) {
            LOG.error("Failed to unzip artifact " + artifactId + " for run " + runId + ": " + e.getMessage(), e);
        }
    }
}
