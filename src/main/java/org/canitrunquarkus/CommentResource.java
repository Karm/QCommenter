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

import org.canitrunquarkus.CommentApplication.Comment;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestResponse;

import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import static org.canitrunquarkus.Validation.validateToken;
import static org.jboss.resteasy.reactive.RestResponse.Status.BAD_REQUEST;
import static org.jboss.resteasy.reactive.RestResponse.Status.FORBIDDEN;
import static org.jboss.resteasy.reactive.RestResponse.Status.TOO_MANY_REQUESTS;

@Path("/comment")
public class CommentResource {

    @ConfigProperty(name = "comments.append.signature")
    private String signature;

    @ConfigProperty(name = "comments.text.max.length")
    private Integer maxCommentLength;

    @Inject
    CommentApplication app;

    /**
     * curl -X POST http://localhost:8080/comment -H "NOT_GITHUB_TOKEN: Changeit" -H "Content-Type: application/json" -d
     * '{"body":"example body","org":"exampleOrg","repo":"exampleRepo","id":"123"}'
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public RestResponse<String> postComment(@HeaderParam("NOT_GITHUB_TOKEN") String token,
            Comment comment) throws NoSuchAlgorithmException {
        String error = Validation.validateOrg(comment.org());
        if (error != null) {
            return RestResponse.ResponseBuilder.<String> create(BAD_REQUEST).entity(error).build();
        }
        error = Validation.validateRepo(comment.repo());
        if (error != null) {
            return RestResponse.ResponseBuilder.<String> create(BAD_REQUEST).entity(error).build();
        }
        if (!validateToken(token, comment.org() + "/" + comment.repo())) {
            return RestResponse.ResponseBuilder.<String> create(FORBIDDEN).entity("Invalid NOT_GITHUB_TOKEN value.").build();
        }
        error = Validation.validateBody(comment.body(), maxCommentLength);
        if (error != null) {
            return RestResponse.ResponseBuilder.<String> create(BAD_REQUEST).entity(error).build();
        }
        error = Validation.validateId(comment.id());
        if (error != null) {
            return RestResponse.ResponseBuilder.<String> create(BAD_REQUEST).entity(error).build();
        }
        if (app.queueComment(
                new Comment(comment.body() + signature, comment.org(), comment.repo(), comment.id(), Instant.now()))) {
            return RestResponse.accepted("Comment queued.");
        }
        return RestResponse.status(TOO_MANY_REQUESTS, "Comment queue is full. Talk to Karm.");
    }

    /**
     * curl -X GET http://localhost:8080/comment/status
     */
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public RestResponse<String> getQueueStatus() {
        return RestResponse.ok("{\"size\":\"" + app.getQueueSize() + "\"," +
                "\"commentsSinceStart\":\"" + app.commentCounter.get() + "\"}");
    }
}
