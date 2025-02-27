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

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

@Path("/comment")
public class CommentResource {

    @Inject
    CommentApplication app;

    /**
     * curl -X GET http://localhost:8080/comment/status
     */
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public RestResponse<String> getQueueStatus() {
        return RestResponse.ok("{\"size\":\"" + app.getQueueSize() + "\"," +
                "\"commentsSinceStart\":\"" + app.getCommentCounter().get() + "\"}");
    }
}
