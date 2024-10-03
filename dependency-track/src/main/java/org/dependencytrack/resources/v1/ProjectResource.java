/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.dependencytrack.resources.v1;

import alpine.common.logging.Logger;
import alpine.event.framework.Event;
import alpine.model.ApiKey;
import alpine.model.Team;
import alpine.model.UserPrincipal;
import alpine.persistence.PaginatedResult;
import alpine.server.auth.PermissionRequired;
import alpine.server.resources.AlpineResource;
import io.jsonwebtoken.lang.Collections;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.apache.commons.lang3.StringUtils;
import org.dependencytrack.auth.Permissions;
import org.dependencytrack.event.CloneProjectEvent;
import org.dependencytrack.model.Classifier;
import org.dependencytrack.model.Project;
import org.dependencytrack.model.Tag;
import org.dependencytrack.model.validation.ValidUuid;
import org.dependencytrack.persistence.QueryManager;
import org.dependencytrack.resources.v1.openapi.PaginatedApi;
import org.dependencytrack.resources.v1.vo.BomUploadResponse;
import org.dependencytrack.resources.v1.vo.CloneProjectRequest;

import jakarta.validation.Validator;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import javax.jdo.FetchGroup;
import java.security.Principal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNullElseGet;
import static org.dependencytrack.util.PersistenceUtil.isPersistent;

/**
 * JAX-RS resources for processing projects.
 *
 * @author Steve Springett
 * @since 3.0.0
 */
@Path("/v1/project")
@io.swagger.v3.oas.annotations.tags.Tag(name = "project")
@SecurityRequirements({
        @SecurityRequirement(name = "ApiKeyAuth"),
        @SecurityRequirement(name = "BearerAuth")
})
public class ProjectResource extends AlpineResource {

    private static final Logger LOGGER = Logger.getLogger(ProjectResource.class);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Returns a list of all projects",
            description = "<p>Requires permission <strong>VIEW_PORTFOLIO</strong></p>"
    )
    @PaginatedApi
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "A list of all projects",
                    headers = @Header(name = TOTAL_COUNT_HEADER, description = "The total number of projects", schema = @Schema(format = "integer")),
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = Project.class)))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PermissionRequired(Permissions.Constants.VIEW_PORTFOLIO)
    public Response getProjects(@Parameter(description = "The optional name of the project to query on", required = false)
                                @QueryParam("name") String name,
                                @Parameter(description = "Optionally excludes inactive projects from being returned", required = false)
                                @QueryParam("excludeInactive") boolean excludeInactive,
                                @Parameter(description = "Optionally excludes children projects from being returned", required = false)
                                @QueryParam("onlyRoot") boolean onlyRoot,
                                @Parameter(description = "The UUID of the team which projects shall be excluded", schema = @Schema(type = "string", format = "uuid"), required = false)
                                @QueryParam("notAssignedToTeamWithUuid") @ValidUuid String notAssignedToTeamWithUuid) {
        try (QueryManager qm = new QueryManager(getAlpineRequest())) {
            Team notAssignedToTeam = null;
            if (StringUtils.isNotEmpty(notAssignedToTeamWithUuid)) {
                notAssignedToTeam = qm.getObjectByUuid(Team.class, notAssignedToTeamWithUuid);
                if (notAssignedToTeam == null) {
                    return Response.status(Response.Status.NOT_FOUND).entity("The UUID of the team could not be found.").build();
                }
            }

            final PaginatedResult result = (name != null) ? qm.getProjects(name, excludeInactive, onlyRoot, notAssignedToTeam) : qm.getProjects(true, excludeInactive, onlyRoot, notAssignedToTeam);
            return Response.ok(result.getObjects()).header(TOTAL_COUNT_HEADER, result.getTotal()).build();
        }
    }

    @GET
    @Path("/{uuid}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Returns a specific project",
            description = "<p>Requires permission <strong>VIEW_PORTFOLIO</strong></p>"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "A specific project",
                    content = @Content(schema = @Schema(implementation = Project.class))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Access to the specified project is forbidden"),
            @ApiResponse(responseCode = "404", description = "The project could not be found")
    })
    @PermissionRequired(Permissions.Constants.VIEW_PORTFOLIO)
    public Response getProject(
            @Parameter(description = "The UUID of the project to retrieve", schema = @Schema(type = "string", format = "uuid"), required = true)
            @PathParam("uuid") @ValidUuid String uuid) {
        try (QueryManager qm = new QueryManager()) {
            final Project project = qm.getProject(uuid);
            if (project != null) {
                if (qm.hasAccess(super.getPrincipal(), project)) {
                    return Response.ok(project).build();
                } else {
                    return Response.status(Response.Status.FORBIDDEN).entity("Access to the specified project is forbidden").build();
                }
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("The project could not be found.").build();
            }
        }
    }

    @GET
    @Path("/lookup")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Returns a specific project by its name and version",
            operationId = "getProjectByNameAndVersion",
            description = "<p>Requires permission <strong>VIEW_PORTFOLIO</strong></p>"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "A specific project by its name and version",
                    content = @Content(schema = @Schema(implementation = Project.class))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Access to the specified project is forbidden"),
            @ApiResponse(responseCode = "404", description = "The project could not be found")
    })
    @PermissionRequired(Permissions.Constants.VIEW_PORTFOLIO)
    public Response getProject(
            @Parameter(description = "The name of the project to query on", required = true)
            @QueryParam("name") String name,
            @Parameter(description = "The version of the project to query on", required = true)
            @QueryParam("version") String version) {
        try (QueryManager qm = new QueryManager()) {
            final Project project = qm.getProject(name, version);
            if (project != null) {
                if (qm.hasAccess(super.getPrincipal(), project)) {
                    return Response.ok(project).build();
                } else {
                    return Response.status(Response.Status.FORBIDDEN).entity("Access to the specified project is forbidden").build();
                }
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("The project could not be found.").build();
            }
        }
    }


    @GET
    @Path("/latest/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Returns the latest version of a project by its name",
            description = "<p>Requires permission <strong>VIEW_PORTFOLIO</strong></p>"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "The latest version of the specified project",
                    content = @Content(schema = @Schema(implementation = Project.class))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Access to the specified project is forbidden"),
            @ApiResponse(responseCode = "404", description = "The project could not be found")
    })
    @PermissionRequired(Permissions.Constants.VIEW_PORTFOLIO)
    public Response getLatestProjectByName(
            @Parameter(description = "The name of the project to retrieve the latest version of", required = true)
            @PathParam("name") String name) {
        try (QueryManager qm = new QueryManager()) {
            final Project project = qm.getLatestProjectVersion(name);
            if (project != null) {
                if (qm.hasAccess(super.getPrincipal(), project)) {
                    return Response.ok(project).build();
                } else {
                    return Response.status(Response.Status.FORBIDDEN).entity("Access to the specified project is forbidden").build();
                }
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("The project could not be found.").build();
            }
        }
    }

    @GET
    @Path("/tag/{tag}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Returns a list of all projects by tag",
            description = "<p>Requires permission <strong>VIEW_PORTFOLIO</strong></p>"
    )
    @PaginatedApi
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "A list of all projects by tag",
                    headers = @Header(name = TOTAL_COUNT_HEADER, description = "The total number of projects", schema = @Schema(format = "integer")),
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = Project.class)))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PermissionRequired(Permissions.Constants.VIEW_PORTFOLIO)
    public Response getProjectsByTag(
            @Parameter(description = "The tag to query on", required = true)
            @PathParam("tag") String tagString,
            @Parameter(description = "Optionally excludes inactive projects from being returned", required = false)
            @QueryParam("excludeInactive") boolean excludeInactive,
            @Parameter(description = "Optionally excludes children projects from being returned", required = false)
            @QueryParam("onlyRoot") boolean onlyRoot) {
        try (QueryManager qm = new QueryManager(getAlpineRequest())) {
            final Tag tag = qm.getTagByName(tagString);
            final PaginatedResult result = qm.getProjects(tag, true, excludeInactive, onlyRoot);
            return Response.ok(result.getObjects()).header(TOTAL_COUNT_HEADER, result.getTotal()).build();
        }
    }

    @GET
    @Path("/classifier/{classifier}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Returns a list of all projects by classifier",
            description = "<p>Requires permission <strong>VIEW_PORTFOLIO</strong></p>"
    )
    @PaginatedApi
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "A list of all projects by classifier",
                    headers = @Header(name = TOTAL_COUNT_HEADER, description = "The total number of projects", schema = @Schema(format = "integer")),
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = Project.class)))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PermissionRequired(Permissions.Constants.VIEW_PORTFOLIO)
    public Response getProjectsByClassifier(
            @Parameter(description = "The classifier to query on", required = true)
            @PathParam("classifier") String classifierString,
            @Parameter(description = "Optionally excludes inactive projects from being returned", required = false)
            @QueryParam("excludeInactive") boolean excludeInactive,
            @Parameter(description = "Optionally excludes children projects from being returned", required = false)
            @QueryParam("onlyRoot") boolean onlyRoot) {
        try (QueryManager qm = new QueryManager(getAlpineRequest())) {
            final Classifier classifier = Classifier.valueOf(classifierString);
            final PaginatedResult result = qm.getProjects(classifier, true, excludeInactive, onlyRoot);
            return Response.ok(result.getObjects()).header(TOTAL_COUNT_HEADER, result.getTotal()).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity("The classifier type specified is not valid.").build();
        }
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Creates a new project",
            description = """
                    <p>If a parent project exists, <code>parent.uuid</code> is required</p>
                    <p>
                      When portfolio access control is enabled, one or more teams to grant access
                      to can be provided via <code>accessTeams</code>. Either <code>uuid</code> or
                      <code>name</code> of a team must be specified. Only teams which the authenticated
                      principal is a member of can be assigned. Principals with <strong>ACCESS_MANAGEMENT</strong>
                      permission can assign <em>any</em> team.
                    </p>
                    <p>Requires permission <strong>PORTFOLIO_MANAGEMENT</strong></p>
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "The created project",
                    content = @Content(schema = @Schema(implementation = Project.class))
            ),
            @ApiResponse(responseCode = "400", description = "Bad Request"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "The project version cannot be created as latest version because access to current latest version is forbidden."),
            @ApiResponse(responseCode = "409", description = """
                    <ul>
                      <li>An inactive Parent cannot be selected as parent, or</li>
                      <li>A project with the specified name already exists</li>
                    </ul>""")
    })
    @PermissionRequired(Permissions.Constants.PORTFOLIO_MANAGEMENT)
    public Response createProject(final Project jsonProject) {
        final Validator validator = super.getValidator();
        failOnValidationError(
                validator.validateProperty(jsonProject, "author"),
                validator.validateProperty(jsonProject, "authors"),
                validator.validateProperty(jsonProject, "publisher"),
                validator.validateProperty(jsonProject, "group"),
                validator.validateProperty(jsonProject, "name"),
                validator.validateProperty(jsonProject, "description"),
                validator.validateProperty(jsonProject, "version"),
                validator.validateProperty(jsonProject, "classifier"),
                validator.validateProperty(jsonProject, "cpe"),
                validator.validateProperty(jsonProject, "purl"),
                validator.validateProperty(jsonProject, "swidTagId"),
                validator.validateProperty(jsonProject, "accessTeams")
        );
        if (jsonProject.getClassifier() == null) {
            jsonProject.setClassifier(Classifier.APPLICATION);
        }
        try (final var qm = new QueryManager()) {
            final Project createdProject = qm.callInTransaction(() -> {
                if (qm.doesProjectExist(StringUtils.trimToNull(jsonProject.getName()),
                        StringUtils.trimToNull(jsonProject.getVersion()))) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.CONFLICT)
                            .entity("A project with the specified name already exists.")
                            .build());
                }

                if (jsonProject.isLatest()) {
                    final Project oldLatest = qm.getLatestProjectVersion(jsonProject.getName());
                    if (oldLatest != null && !qm.hasAccess(super.getPrincipal(), oldLatest)) {
                        throw new ClientErrorException(Response
                                .status(Response.Status.FORBIDDEN)
                                .entity("Cannot set this project version to latest. Access to current latest " +
                                        "version is forbidden!")
                                .build());
                    }
                }

                if (jsonProject.getParent() != null && jsonProject.getParent().getUuid() != null) {
                    Project parent = qm.getObjectByUuid(Project.class, jsonProject.getParent().getUuid());
                    jsonProject.setParent(parent);
                }

                final Principal principal = getPrincipal();

                final List<Team> chosenTeams = requireNonNullElseGet(
                        jsonProject.getAccessTeams(), Collections::emptyList);
                jsonProject.setAccessTeams(null);

                for (final Team chosenTeam : chosenTeams) {
                    if (chosenTeam.getUuid() == null && chosenTeam.getName() == null) {
                        throw new ClientErrorException(Response
                                .status(Response.Status.BAD_REQUEST)
                                .entity("""
                                        accessTeams must either specify a UUID or a name,\
                                        but the team at index %d has neither.\
                                        """.formatted(chosenTeams.indexOf(chosenTeam)))
                                .build());
                    }
                }

                if (!chosenTeams.isEmpty()) {
                    final List<Team> userTeams;
                    if (principal instanceof final UserPrincipal userPrincipal) {
                        userTeams = userPrincipal.getTeams();
                    } else if (principal instanceof final ApiKey apiKey) {
                        userTeams = apiKey.getTeams();
                    } else {
                        userTeams = Collections.emptyList();
                    }

                    final boolean isAdmin = qm.hasAccessManagementPermission(principal);
                    final List<Team> visibleTeams = isAdmin ? qm.getTeams() : userTeams;
                    final var visibleTeamByUuid = new HashMap<UUID, Team>(visibleTeams.size());
                    final var visibleTeamByName = new HashMap<String, Team>(visibleTeams.size());
                    for (final Team visibleTeam : visibleTeams) {
                        visibleTeamByUuid.put(visibleTeam.getUuid(), visibleTeam);
                        visibleTeamByName.put(visibleTeam.getName(), visibleTeam);
                    }

                    for (final Team chosenTeam : chosenTeams) {
                        Team visibleTeam = visibleTeamByUuid.getOrDefault(
                                chosenTeam.getUuid(),
                                visibleTeamByName.get(chosenTeam.getName()));

                        if (visibleTeam == null) {
                            throw new ClientErrorException(Response
                                    .status(Response.Status.BAD_REQUEST)
                                    .entity("""
                                            The team with %s can not be assigned because it does not exist, \
                                            or is not accessible to the authenticated principal.\
                                            """.formatted(chosenTeam.getUuid() != null
                                            ? "UUID " + chosenTeam.getUuid()
                                            : "name " + chosenTeam.getName()))
                                    .build());
                        }

                        if (!isPersistent(visibleTeam)) {
                            // Teams sourced from the principal will not be in persistent state
                            // and need to be attached to the persistence context.
                            visibleTeam = qm.getObjectById(Team.class, visibleTeam.getId());
                        }

                        jsonProject.addAccessTeam(visibleTeam);
                    }
                }

                final Project project;
                try {
                    project = qm.createProject(jsonProject, jsonProject.getTags(), true);
                } catch (IllegalArgumentException e) {
                    LOGGER.debug("Failed to create project %s".formatted(jsonProject), e);
                    throw new ClientErrorException(Response
                            .status(Response.Status.CONFLICT)
                            .entity("An inactive Parent cannot be selected as parent")
                            .build());
                } catch (RuntimeException e) {
                    LOGGER.error("Failed to create project %s".formatted(jsonProject), e);
                    throw new ServerErrorException(Response.Status.INTERNAL_SERVER_ERROR);
                }

                qm.updateNewProjectACL(project, principal);
                return project;
            });

            LOGGER.info("Project " + createdProject + " created by " + super.getPrincipal().getName());
            return Response.status(Response.Status.CREATED).entity(createdProject).build();
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Updates a project",
            description = "<p>Requires permission <strong>PORTFOLIO_MANAGEMENT</strong></p>"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "The updated project",
                    content = @Content(schema = @Schema(implementation = Project.class))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "The project version cannot be set as latest version " +
                    "because access to current latest version is forbidden."),
            @ApiResponse(responseCode = "404", description = "The UUID of the project could not be found"),
            @ApiResponse(responseCode = "409", description = """
                    <ul>
                      <li>An inactive Parent cannot be selected as parent, or</li>
                      <li>Project cannot be set to inactive if active children are present, or</li>
                      <li>A project with the specified name already exists, or</li>
                      <li>A project cannot select itself as a parent</li>
                    </ul>""")
    })
    @PermissionRequired(Permissions.Constants.PORTFOLIO_MANAGEMENT)
    public Response updateProject(Project jsonProject) {
        final Validator validator = super.getValidator();
        failOnValidationError(
                validator.validateProperty(jsonProject, "authors"),
                validator.validateProperty(jsonProject, "publisher"),
                validator.validateProperty(jsonProject, "group"),
                validator.validateProperty(jsonProject, "name"),
                validator.validateProperty(jsonProject, "description"),
                validator.validateProperty(jsonProject, "version"),
                validator.validateProperty(jsonProject, "classifier"),
                validator.validateProperty(jsonProject, "cpe"),
                validator.validateProperty(jsonProject, "purl"),
                validator.validateProperty(jsonProject, "swidTagId")
        );
        if (jsonProject.getClassifier() == null) {
            jsonProject.setClassifier(Classifier.APPLICATION);
        }
        try (final var qm = new QueryManager()) {
            final Project updatedProject = qm.callInTransaction(() -> {
                final Project project = qm.getObjectByUuid(Project.class, jsonProject.getUuid());
                if (project == null) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.NOT_FOUND)
                            .entity("The UUID of the project could not be found.")
                            .build());
                }
                if (!qm.hasAccess(super.getPrincipal(), project)) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.FORBIDDEN)
                            .entity("Access to the specified project is forbidden")
                            .build());
                }

                String name = StringUtils.trimToNull(jsonProject.getName());
                // Name cannot be empty or null - prevent it
                if (name == null) {
                    name = project.getName();
                    jsonProject.setName(name);
                }

                // if project is newly set to latest, ensure user has access to current latest version to modify it
                if (jsonProject.isLatest() && !project.isLatest()) {
                    final Project oldLatest = qm.getLatestProjectVersion(name);
                    if (oldLatest != null && !qm.hasAccess(super.getPrincipal(), oldLatest)) {
                        throw new ClientErrorException(Response
                                .status(Response.Status.FORBIDDEN)
                                .entity("Cannot set this project version to latest. Access to current latest " +
                                        "version is forbidden!")
                                .build());
                    }
                }

                final String version = StringUtils.trimToNull(jsonProject.getVersion());
                final Project tmpProject = qm.getProject(name, version);
                if (tmpProject != null && !tmpProject.getUuid().equals(project.getUuid())) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.CONFLICT)
                            .entity("A project with the specified name and version already exists.")
                            .build());
                }

                try {
                    return qm.updateProject(jsonProject, true);
                } catch (IllegalArgumentException e) {
                    LOGGER.debug("Failed to update project %s".formatted(jsonProject.getUuid()), e);
                    throw new ClientErrorException(Response
                            .status(Response.Status.CONFLICT)
                            .entity(e.getMessage())
                            .build());
                }
            });

            LOGGER.info("Project " + updatedProject + " updated by " + super.getPrincipal().getName());
            return Response.ok(updatedProject).build();
        }
    }

    @PATCH
    @Path("/{uuid}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Partially updates a project",
            description = "<p>Requires permission <strong>PORTFOLIO_MANAGEMENT</strong></p>"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "The updated project",
                    content = @Content(schema = @Schema(implementation = Project.class))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "404", description = "The UUID of the project could not be found"),
            @ApiResponse(responseCode = "409", description = """
                    <ul>
                      <li>An inactive Parent cannot be selected as parent, or</li>
                      <li>Project cannot be set to inactive if active children are present, or</li>
                      <li>A project with the specified name already exists, or</li>
                      <li>A project cannot select itself as a parent</li>
                    </ul>""")
    })
    @PermissionRequired(Permissions.Constants.PORTFOLIO_MANAGEMENT)
    public Response patchProject(
            @Parameter(description = "The UUID of the project to modify", schema = @Schema(type = "string", format = "uuid"), required = true)
            @PathParam("uuid") @ValidUuid String uuid,
            Project jsonProject) {
        final Validator validator = getValidator();
        failOnValidationError(
                validator.validateProperty(jsonProject, "author"),
                validator.validateProperty(jsonProject, "authors"),
                validator.validateProperty(jsonProject, "publisher"),
                validator.validateProperty(jsonProject, "group"),
                jsonProject.getName() != null ? validator.validateProperty(jsonProject, "name") : Set.of(),
                validator.validateProperty(jsonProject, "description"),
                validator.validateProperty(jsonProject, "version"),
                validator.validateProperty(jsonProject, "classifier"),
                validator.validateProperty(jsonProject, "cpe"),
                validator.validateProperty(jsonProject, "purl"),
                validator.validateProperty(jsonProject, "swidTagId")
        );

        try (final var qm = new QueryManager()) {
            final Project updatedProject = qm.callInTransaction(() -> {
                Project project = qm.getObjectByUuid(Project.class, uuid);
                if (project == null) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.NOT_FOUND)
                            .entity("The UUID of the project could not be found.")
                            .build());
                }
                if (!qm.hasAccess(super.getPrincipal(), project)) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.FORBIDDEN)
                            .entity("Access to the specified project is forbidden")
                            .build());
                }

                // if project is newly set to latest, ensure user has access to current latest version to modify it
                if (jsonProject.isLatest() && !project.isLatest()) {
                    final var oldName = jsonProject.getName() != null ? jsonProject.getName() : project.getName();
                    final Project oldLatest = qm.getLatestProjectVersion(oldName);
                    if (oldLatest != null && !qm.hasAccess(super.getPrincipal(), oldLatest)) {
                        throw new ClientErrorException(Response
                                .status(Response.Status.FORBIDDEN)
                                .entity("Cannot set this project version to latest. Access to current latest " +
                                        "version is forbidden!")
                                .build());
                    }
                }

                var modified = false;
                project = qm.detachWithGroups(project, List.of(FetchGroup.DEFAULT, Project.FetchGroup.PARENT.name()));
                modified |= setIfDifferent(jsonProject, project, Project::getName, Project::setName);
                modified |= setIfDifferent(jsonProject, project, Project::getVersion, Project::setVersion);
                // if either name or version has been changed, verify that this new combination does not already exist
                if (modified && qm.doesProjectExist(project.getName(), project.getVersion())) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.CONFLICT)
                            .entity("A project with the specified name and version already exists.")
                            .build());
                }
                modified |= setIfDifferent(jsonProject, project, Project::getAuthors, Project::setAuthors);
                modified |= setIfDifferent(jsonProject, project, Project::getPublisher, Project::setPublisher);
                modified |= setIfDifferent(jsonProject, project, Project::getGroup, Project::setGroup);
                modified |= setIfDifferent(jsonProject, project, Project::getDescription, Project::setDescription);
                modified |= setIfDifferent(jsonProject, project, Project::getClassifier, Project::setClassifier);
                modified |= setIfDifferent(jsonProject, project, Project::getCpe, Project::setCpe);
                modified |= setIfDifferent(jsonProject, project, Project::getPurl, Project::setPurl);
                modified |= setIfDifferent(jsonProject, project, Project::getSwidTagId, Project::setSwidTagId);
                modified |= setIfDifferent(jsonProject, project, Project::isActive, Project::setActive);
                modified |= setIfDifferent(jsonProject, project, Project::getManufacturer, Project::setManufacturer);
                modified |= setIfDifferent(jsonProject, project, Project::getSupplier, Project::setSupplier);
                modified |= setIfDifferent(jsonProject, project, Project::isLatest, Project::setIsLatest);
                if (jsonProject.getParent() != null && jsonProject.getParent().getUuid() != null) {
                    final Project parent = qm.getObjectByUuid(Project.class, jsonProject.getParent().getUuid());
                    if (parent == null) {
                        throw new ClientErrorException(Response
                                .status(Response.Status.NOT_FOUND)
                                .entity("The UUID of the parent project could not be found.")
                                .build());
                    }
                    if (!qm.hasAccess(getPrincipal(), parent)) {
                        throw new ClientErrorException(Response
                                .status(Response.Status.FORBIDDEN)
                                .entity("Access to the specified parent project is forbidden")
                                .build());
                    }
                    modified |= project.getParent() == null || !parent.getUuid().equals(project.getParent().getUuid());
                    project.setParent(parent);
                }
                if (isCollectionModified(jsonProject.getTags(), project.getTags())) {
                    modified = true;
                    project.setTags(jsonProject.getTags());
                }
                if (isCollectionModified(jsonProject.getExternalReferences(), project.getExternalReferences())) {
                    modified = true;
                    project.setExternalReferences(jsonProject.getExternalReferences());
                }

                if (!modified) {
                    return null;
                }

                try {
                    return qm.updateProject(project, true);
                } catch (IllegalArgumentException e) {
                    LOGGER.debug("Failed to patch project %s".formatted(uuid));
                    throw new ClientErrorException(Response
                            .status(Response.Status.CONFLICT)
                            .entity(e.getMessage())
                            .build());
                } catch (RuntimeException e) {
                    LOGGER.error("Failed to patch project %s".formatted(uuid), e);
                    throw new ServerErrorException(Response.Status.INTERNAL_SERVER_ERROR);
                }
            });

            if (updatedProject == null) {
                return Response.notModified().build();
            }

            LOGGER.info("Project " + updatedProject + " updated by " + super.getPrincipal().getName());
            return Response.ok(updatedProject).build();
        }
    }

    /**
     * returns `true` if the given [updated] collection should be considered an update of the [original] collection.
     */
    private static <T> boolean isCollectionModified(Collection<T> updated, Collection<T> original) {
       return updated != null && (!Collections.isEmpty(updated) || !Collections.isEmpty(original));
    }

    /**
     * updates the given target object using the supplied setter method with the
     * new value from the source object using the supplied getter method. But
     * only if the new value is not {@code null} and it is not
     * {@link Object#equals(java.lang.Object) equal to} the old value.
     *
     * @param <T> the type of the old and new value
     * @param source the source object that contains the new value
     * @param target the target object that should be updated
     * @param getter the method to retrieve the new value from {@code source}
     * and the old value from {@code target}
     * @param setter the method to set the new value on {@code target}
     * @return {@code true} if {@code target} has been changed, else
     * {@code false}
     */
    private <T> boolean setIfDifferent(final Project source, final Project target, final Function<Project, T> getter, final BiConsumer<Project, T> setter) {
        final T newValue = getter.apply(source);
        if (newValue != null && !newValue.equals(getter.apply(target))) {
            setter.accept(target, newValue);
            return true;
        } else {
            return false;
        }
    }

    @DELETE
    @Path("/{uuid}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Deletes a project",
            description = "<p>Requires permission <strong>PORTFOLIO_MANAGEMENT</strong></p>"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Project removed successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Access to the specified project is forbidden"),
            @ApiResponse(responseCode = "404", description = "The UUID of the project could not be found")
    })
    @PermissionRequired(Permissions.Constants.PORTFOLIO_MANAGEMENT)
    public Response deleteProject(
            @Parameter(description = "The UUID of the project to delete", schema = @Schema(type = "string", format = "uuid"), required = true)
            @PathParam("uuid") @ValidUuid String uuid) {
        try (final var qm = new QueryManager()) {
            qm.runInTransaction(() -> {
                final Project project = qm.getObjectByUuid(Project.class, uuid, Project.FetchGroup.ALL.name());
                if (project == null) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.NOT_FOUND)
                            .entity("The UUID of the project could not be found.")
                            .build());
                }
                if (!qm.hasAccess(super.getPrincipal(), project)) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.FORBIDDEN)
                            .entity("Access to the specified project is forbidden")
                            .build());
                }

                LOGGER.info("Project " + project + " deletion request by " + super.getPrincipal().getName());
                try {
                    qm.recursivelyDelete(project, true);
                } catch (RuntimeException e) {
                    LOGGER.error("Failed to delete project", e);
                    throw new ServerErrorException(Response.Status.INTERNAL_SERVER_ERROR);
                }
            });

            return Response.status(Response.Status.NO_CONTENT).build();
        }
    }

    @PUT
    @Path("/clone")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Clones a project",
            description = "<p>Requires permission <strong>PORTFOLIO_MANAGEMENT</strong></p>"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Token to be used for checking cloning progress",
                    content = @Content(schema = @Schema(implementation = BomUploadResponse.class))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),

            @ApiResponse(responseCode = "403", description = "The project clone cannot be set to latest version " +
                    "because access to current latest version is forbidden."),
            @ApiResponse(responseCode = "404", description = "The UUID of the project could not be found")
    })
    @PermissionRequired(Permissions.Constants.PORTFOLIO_MANAGEMENT)
    public Response cloneProject(CloneProjectRequest jsonRequest) {
        final Validator validator = super.getValidator();
        failOnValidationError(
                validator.validateProperty(jsonRequest, "project"),
                validator.validateProperty(jsonRequest, "version")
        );
        try (final var qm = new QueryManager()) {
            final CloneProjectEvent cloneEvent = qm.callInTransaction(() -> {
                final Project sourceProject = qm.getObjectByUuid(Project.class, jsonRequest.getProject(), Project.FetchGroup.ALL.name());
                if (sourceProject == null) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.NOT_FOUND)
                            .entity("The UUID of the project could not be found.")
                            .build());
                }

                if (!qm.hasAccess(super.getPrincipal(), sourceProject)) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.FORBIDDEN)
                            .entity("Access to the specified project is forbidden")
                            .build());
                }
                if (qm.doesProjectExist(sourceProject.getName(), StringUtils.trimToNull(jsonRequest.getVersion()))) {
                    throw new ClientErrorException(Response
                            .status(Response.Status.CONFLICT)
                            .entity("A project with the specified name and version already exists.")
                            .build());
                }

                // if project is newly set to latest, ensure user has access to current latest version to modify it
                if (jsonRequest.makeCloneLatest() && !sourceProject.isLatest()) {
                    final Project oldLatest = qm.getLatestProjectVersion(sourceProject.getName());
                    if (oldLatest != null && !qm.hasAccess(super.getPrincipal(), oldLatest)) {
                        throw new ClientErrorException(Response
                                .status(Response.Status.FORBIDDEN)
                                .entity("Cannot set this project version to latest. Access to current latest " +
                                        "version is forbidden!")
                                .build());
                    }
                }

                LOGGER.info("Project " + sourceProject + " is being cloned by " + super.getPrincipal().getName());
                return new CloneProjectEvent(jsonRequest);
            });

            Event.dispatch(cloneEvent);
            return Response.ok(Map.of("token", cloneEvent.getChainIdentifier())).build();
        }
    }


    @GET
    @Path("/{uuid}/children")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Returns a list of all children for a project",
            description = "<p>Requires permission <strong>VIEW_PORTFOLIO</strong></p>"
    )
    @PaginatedApi
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "A list of all children for a project",
                    headers = @Header(name = TOTAL_COUNT_HEADER, description = "The total number of projects", schema = @Schema(format = "integer")),
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = Project.class)))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Access to the specified project is forbidden"),
            @ApiResponse(responseCode = "404", description = "The UUID of the project could not be found")
    })
    @PermissionRequired(Permissions.Constants.VIEW_PORTFOLIO)
    public Response getChildrenProjects(@Parameter(description = "The UUID of the project to get the children from", schema = @Schema(type = "string", format = "uuid"), required = true)
                                        @PathParam("uuid") @ValidUuid String uuid,
                                        @Parameter(description = "Optionally excludes inactive projects from being returned", required = false)
                                        @QueryParam("excludeInactive") boolean excludeInactive) {
        try (QueryManager qm = new QueryManager(getAlpineRequest())) {
            final Project project = qm.getObjectByUuid(Project.class, uuid);
            if (project != null) {
                final PaginatedResult result = qm.getChildrenProjects(project.getUuid(), true, excludeInactive);
                if (qm.hasAccess(super.getPrincipal(), project)) {
                    return Response.ok(result.getObjects()).header(TOTAL_COUNT_HEADER, result.getTotal()).build();
                } else {
                    return Response.status(Response.Status.FORBIDDEN).entity("Access to the specified project is forbidden").build();
                }
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("The UUID of the project could not be found.").build();
            }
        }
    }

    @GET
    @Path("/{uuid}/children/classifier/{classifier}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Returns a list of all children for a project by classifier",
            description = "<p>Requires permission <strong>VIEW_PORTFOLIO</strong></p>"
    )
    @PaginatedApi
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "A list of all children for a project by classifier",
                    headers = @Header(name = TOTAL_COUNT_HEADER, description = "The total number of projects", schema = @Schema(format = "integer")),
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = Project.class)))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Access to the specified project is forbidden"),
            @ApiResponse(responseCode = "404", description = "The UUID of the project could not be found")
    })
    @PermissionRequired(Permissions.Constants.VIEW_PORTFOLIO)
    public Response getChildrenProjectsByClassifier(
            @Parameter(description = "The classifier to query on", required = true)
            @PathParam("classifier") String classifierString,
            @Parameter(description = "The UUID of the project to get the children from", schema = @Schema(type = "string", format = "uuid"), required = true)
            @PathParam("uuid") @ValidUuid String uuid,
            @Parameter(description = "Optionally excludes inactive projects from being returned", required = false)
            @QueryParam("excludeInactive") boolean excludeInactive) {
        try (QueryManager qm = new QueryManager(getAlpineRequest())) {
            final Project project = qm.getObjectByUuid(Project.class, uuid);
            if (project != null) {
                final Classifier classifier = Classifier.valueOf(classifierString);
                final PaginatedResult result = qm.getChildrenProjects(classifier, project.getUuid(), true, excludeInactive);
                if (qm.hasAccess(super.getPrincipal(), project)) {
                    return Response.ok(result.getObjects()).header(TOTAL_COUNT_HEADER, result.getTotal()).build();
                } else {
                    return Response.status(Response.Status.FORBIDDEN).entity("Access to the specified project is forbidden").build();
                }
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("The UUID of the project could not be found.").build();
            }
        }
    }

    @GET
    @Path("/{uuid}/children/tag/{tag}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Returns a list of all children for a project by tag",
            description = "<p>Requires permission <strong>VIEW_PORTFOLIO</strong></p>"
    )
    @PaginatedApi
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "A list of all children for a project by tag",
                    headers = @Header(name = TOTAL_COUNT_HEADER, description = "The total number of projects", schema = @Schema(format = "integer")),
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = Project.class)))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Access to the specified project is forbidden"),
            @ApiResponse(responseCode = "404", description = "The UUID of the project could not be found")
    })
    @PermissionRequired(Permissions.Constants.VIEW_PORTFOLIO)
    public Response getChildrenProjectsByTag(
            @Parameter(description = "The tag to query on", required = true)
            @PathParam("tag") String tagString,
            @Parameter(description = "The UUID of the project to get the children from", schema = @Schema(type = "string", format = "uuid"), required = true)
            @PathParam("uuid") @ValidUuid String uuid,
            @Parameter(description = "Optionally excludes inactive projects from being returned", required = false)
            @QueryParam("excludeInactive") boolean excludeInactive) {
        try (QueryManager qm = new QueryManager(getAlpineRequest())) {
            final Project project = qm.getObjectByUuid(Project.class, uuid);
            if (project != null) {
                final Tag tag = qm.getTagByName(tagString);
                final PaginatedResult result = qm.getChildrenProjects(tag, project.getUuid(), true, excludeInactive);
                if (qm.hasAccess(super.getPrincipal(), project)) {
                    return Response.ok(result.getObjects()).header(TOTAL_COUNT_HEADER, result.getTotal()).build();
                } else {
                    return Response.status(Response.Status.FORBIDDEN).entity("Access to the specified project is forbidden").build();
                }
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("The UUID of the project could not be found.").build();
            }
        }
    }

    @GET
    @Path("/withoutDescendantsOf/{uuid}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Returns a list of all projects without the descendants of the selected project",
            description = "<p>Requires permission <strong>VIEW_PORTFOLIO</strong></p>"
    )
    @PaginatedApi
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "A list of all projects without the descendants of the selected project",
                    headers = @Header(name = TOTAL_COUNT_HEADER, description = "The total number of projects", schema = @Schema(format = "integer")),
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = Project.class)))
            ),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Access to the specified project is forbidden"),
            @ApiResponse(responseCode = "404", description = "The UUID of the project could not be found")
    })
    @PermissionRequired(Permissions.Constants.VIEW_PORTFOLIO)
    public Response getProjectsWithoutDescendantsOf(
                                @Parameter(description = "The UUID of the project which descendants will be excluded", schema = @Schema(type = "string", format = "uuid"), required = true)
                                @PathParam("uuid") @ValidUuid String uuid,
                                @Parameter(description = "The optional name of the project to query on", required = false)
                                @QueryParam("name") String name,
                                @Parameter(description = "Optionally excludes inactive projects from being returned", required = false)
                                @QueryParam("excludeInactive") boolean excludeInactive) {
        try (QueryManager qm = new QueryManager(getAlpineRequest())) {
            final Project project = qm.getObjectByUuid(Project.class, uuid);
            if (project != null) {
                if (qm.hasAccess(super.getPrincipal(), project)) {
                    final PaginatedResult result = (name != null) ? qm.getProjectsWithoutDescendantsOf(name, excludeInactive, project) : qm.getProjectsWithoutDescendantsOf(excludeInactive, project);
                    return Response.ok(result.getObjects()).header(TOTAL_COUNT_HEADER, result.getTotal()).build();
                } else{
                    return Response.status(Response.Status.FORBIDDEN).entity("Access to the specified project is forbidden").build();
                }
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("The UUID of the project could not be found.").build();
            }
        }
    }
}