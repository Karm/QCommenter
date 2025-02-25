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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class CommentApplication {

    public static final Logger LOG = Logger.getLogger(CommentApplication.class);

    @Inject
    Vertx vertx;

    private WebClient webClient;

    public static final ConcurrentHashMap<String, String> accessTokens = new ConcurrentHashMap<>();

    @ConfigProperty(name = "token.hashes.file")
    private String tokenHashesFilePath;

    private Path tokenHashesFile;
    private Path dir;

    @ConfigProperty(name = "my.github.api.url")
    private String githubApiUrl;

    @ConfigProperty(name = "my.github.api.token")
    private String githubToken;

    @ConfigProperty(name = "comments.batch.intervals.ms")
    private Long batchIntervalMs;

    @ConfigProperty(name = "comments.batch.size")
    private Integer batchSize;

    @ConfigProperty(name = "comments.max.queue.size")
    private Integer maxCommentQueueSize;

    @ConfigProperty(name = "comments.max.age.seconds")
    private Integer maxCommentAgeSeconds;

    public final AtomicInteger commentCounter = new AtomicInteger(0);
    private Queue<Comment> commentQueue;
    private WatchService watchService;

    @JsonIgnoreProperties("age")
    public record Comment(String body, String org, String repo, Integer id, Instant age) {
    }

    @PostConstruct
    public void init() throws IOException {
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
        vertx.setPeriodic(batchIntervalMs, id -> processCommentsBatch());
    }

    void onStop(@Observes ShutdownEvent ev) throws IOException {
        watchService.close();
        webClient.close();
        vertx.close();
    }

    private void startFileWatcher() {
        final Callable<Void> watcherTask = () -> {
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
            while (true) {
                // we wait for file changes
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

    /**
     * Loads all lines from the file to memory and updates our map, token hash:org/repo.
     */
    private void processFile() {
        try (Stream<String> lines = Files.lines(tokenHashesFile)) {
            final Map<String, String> newTokens = lines
                    .map(line -> line.split(":", 2))
                    .filter(parts -> parts.length == 2)
                    .collect(Collectors.toMap(
                            parts -> parts[0].trim(),
                            parts -> parts[1].trim(),
                            (v1, v2) -> v2
                    ));
            LOG.info("Loaded " + newTokens.size() + " tokens hashes from " + tokenHashesFile.toAbsolutePath());
            accessTokens.keySet().retainAll(newTokens.keySet());
            accessTokens.putAll(newTokens);
        } catch (Exception e) {
            LOG.error("Failed to process file: " + e.getMessage() + ", it might not exist at this time.");
        }
    }

    public int getQueueSize() {
        return commentQueue.size();
    }

    public boolean queueComment(Comment comment) {
        return commentQueue.offer(comment);
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
        final String jsonBody = "{\"body\": \"" + comment.body() + "\"}";
        final String target = githubApiUrl + "/repos/" + comment.org() + "/" + comment.repo() + "/issues/" + comment.id() + "/comments";
        webClient.postAbs(target)
                .putHeader("Authorization", "token " + githubToken)
                .putHeader("Accept", "application/vnd.github+json")
                .putHeader("Content-Type", "application/json")
                .sendBuffer(io.vertx.core.buffer.Buffer.buffer(jsonBody))
                .onSuccess(response -> {
                    commentCounter.incrementAndGet();
                    LOG.info("Comment posted to " + target + ": " + response.bodyAsString());
                })
                .onFailure(err -> {
                    LOG.error("Failed to post comment to " + target + " and will try again: " + err.getMessage());
                    commentQueue.offer(comment);
                });
    }

}
