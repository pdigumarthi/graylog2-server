/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.rest.resources.streams.alerts;

import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.ImmutableMap;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.graylog2.alerts.AbstractAlertCondition;
import org.graylog2.alerts.AlertService;
import org.graylog2.auditlog.jersey.AuditLog;
import org.graylog2.database.NotFoundException;
import org.graylog2.plugin.alarms.AlertCondition;
import org.graylog2.plugin.database.ValidationException;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.rest.models.streams.alerts.AlertConditionListSummary;
import org.graylog2.rest.models.streams.alerts.AlertConditionSummary;
import org.graylog2.rest.models.streams.alerts.requests.CreateConditionRequest;
import org.graylog2.shared.rest.resources.RestResource;
import org.graylog2.shared.security.RestPermissions;
import org.graylog2.streams.StreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiresAuthentication
@Api(value = "AlertConditions", description = "Manage stream alert conditions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Path("/streams/{streamId}/alerts/conditions")
public class StreamAlertConditionResource extends RestResource {
    private static final Logger LOG = LoggerFactory.getLogger(StreamAlertConditionResource.class);

    private final StreamService streamService;
    private final AlertService alertService;

    @Inject
    public StreamAlertConditionResource(StreamService streamService, AlertService alertService) {
        this.streamService = streamService;
        this.alertService = alertService;
    }

    @POST
    @Timed
    @ApiOperation(value = "Create an alert condition")
    @ApiResponses(value = {
        @ApiResponse(code = 404, message = "Stream not found."),
        @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    @AuditLog(object = "alert condition", captureRequestEntity = true, captureResponseEntity = true)
    public Response create(@ApiParam(name = "streamId", value = "The stream id this new alert condition belongs to.", required = true)
                           @PathParam("streamId") String streamid,
                           @ApiParam(name = "JSON body", required = true)
                           @Valid @NotNull CreateConditionRequest ccr) throws NotFoundException, ValidationException {
        checkPermission(RestPermissions.STREAMS_EDIT, streamid);

        final Stream stream = streamService.load(streamid);
        final AlertCondition alertCondition;
        try {
            alertCondition = alertService.fromRequest(ccr, stream, getCurrentUser().getName());
        } catch (AbstractAlertCondition.NoSuchAlertConditionTypeException e) {
            LOG.error("Invalid alarm condition type.", e);
            throw new BadRequestException(e);
        }

        streamService.addAlertCondition(stream, alertCondition);

        final Map<String, String> result = ImmutableMap.of("alert_condition_id", alertCondition.getId());
        final URI alertConditionUri = getUriBuilderToSelf().path(StreamAlertConditionResource.class)
            .path("{conditionId}")
            .build(stream.getId(), alertCondition.getId());

        return Response.created(alertConditionUri).entity(result).build();
    }

    @PUT
    @Timed
    @Path("{conditionId}")
    @ApiOperation(value = "Modify an alert condition")
    @ApiResponses(value = {
        @ApiResponse(code = 404, message = "Stream not found."),
        @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    @AuditLog(object = "alert condition", captureRequestEntity = true, captureResponseEntity = true)
    public void update(@ApiParam(name = "streamId", value = "The stream id the alert condition belongs to.", required = true)
                       @PathParam("streamId") String streamid,
                       @ApiParam(name = "conditionId", value = "The alert condition id.", required = true)
                       @PathParam("conditionId") String conditionid,
                       @ApiParam(name = "JSON body", required = true)
                       @Valid @NotNull CreateConditionRequest ccr) throws NotFoundException, ValidationException {
        checkPermission(RestPermissions.STREAMS_EDIT, streamid);

        final Stream stream = streamService.load(streamid);
        AlertCondition alertCondition = streamService.getAlertCondition(stream, conditionid);

        final AlertCondition updatedCondition;
        try {
            updatedCondition = alertService.updateFromRequest(alertCondition, ccr);
        } catch (AbstractAlertCondition.NoSuchAlertConditionTypeException e) {
            LOG.error("Invalid alarm condition type.", e);
            throw new BadRequestException(e);
        }

        streamService.updateAlertCondition(stream, updatedCondition);
    }

    @GET
    @Timed
    @ApiOperation(value = "Get all alert conditions of this stream")
    @ApiResponses(value = {
        @ApiResponse(code = 404, message = "Stream not found."),
        @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    public AlertConditionListSummary list(@ApiParam(name = "streamId", value = "The stream id this new alert condition belongs to.", required = true)
                                    @PathParam("streamId") String streamid) throws NotFoundException {
        checkPermission(RestPermissions.STREAMS_READ, streamid);

        final Stream stream = streamService.load(streamid);

        final List<AlertCondition> alertConditions = streamService.getAlertConditions(stream);
        final List<AlertConditionSummary> conditionSummaries = alertConditions
            .stream()
            .map((condition) -> AlertConditionSummary.create(condition.getId(),
                condition.getTypeString().toLowerCase(),
                condition.getCreatorUserId(),
                condition.getCreatedAt().toDate(),
                condition.getParameters(),
                alertService.inGracePeriod(condition),
                condition.getTitle()))
            .collect(Collectors.toList());

        return AlertConditionListSummary.create(conditionSummaries);
    }

    @DELETE
    @Timed
    @Path("{conditionId}")
    @ApiOperation(value = "Delete an alert condition")
    @ApiResponses(value = {
        @ApiResponse(code = 404, message = "Stream not found."),
        @ApiResponse(code = 400, message = "Invalid ObjectId.")
    })
    @AuditLog(object = "alert condition")
    public void delete(@ApiParam(name = "streamId", value = "The stream id this new alert condition belongs to.", required = true)
                       @PathParam("streamId") String streamid,
                       @ApiParam(name = "conditionId", value = "The stream id this new alert condition belongs to.", required = true)
                       @PathParam("conditionId") String conditionId) throws NotFoundException {
        checkPermission(RestPermissions.STREAMS_READ, streamid);

        final Stream stream = streamService.load(streamid);
        streamService.removeAlertCondition(stream, conditionId);
    }
}
