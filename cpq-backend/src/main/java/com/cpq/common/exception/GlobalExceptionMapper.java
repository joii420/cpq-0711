package com.cpq.common.exception;

import com.cpq.common.dto.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.jboss.logging.Logger;

public class GlobalExceptionMapper {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @ServerExceptionMapper
    public Response handleBusinessException(BusinessException e) {
        LOG.warnf("Business error: %s", e.getMessage());
        if (e instanceof com.cpq.common.exception.RowKeyConflictException rce) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(),
                            java.util.Map.of("conflicts", rce.getConflicts())))
                    .build();
        }
        return Response.status(e.getCode())
                .entity(ApiResponse.error(e.getCode(), e.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        LOG.warnf("Validation error: %s", message);
        return Response.status(400)
                .entity(ApiResponse.error(400, message))
                .build();
    }

    /**
     * Bad input: invalid UUID format, negative page index, parse-int failures, etc.
     * Without this, Panache and JAX-RS path-param parsing failures surface as 500.
     */
    @ServerExceptionMapper
    public Response handleIllegalArgument(IllegalArgumentException e) {
        String msg = e.getMessage() == null ? "Invalid argument" : e.getMessage();
        LOG.warnf("Illegal argument: %s", msg);
        return Response.status(400)
                .entity(ApiResponse.error(400, msg))
                .build();
    }

    /**
     * Malformed JSON request body → 400 instead of 500.
     * Covers JsonParseException, JsonMappingException, MismatchedInputException, etc.
     */
    @ServerExceptionMapper
    public Response handleJsonProcessing(JsonProcessingException e) {
        String detail = e.getOriginalMessage() != null ? e.getOriginalMessage() : e.getMessage();
        LOG.warnf("JSON processing error: %s", detail);
        return Response.status(400)
                .entity(ApiResponse.error(400, "Invalid JSON: " + detail))
                .build();
    }

    /**
     * Unsupported Content-Type (e.g. sending form data to a JSON-only endpoint) → 415.
     */
    @ServerExceptionMapper
    public Response handleNotSupported(NotSupportedException e) {
        LOG.warnf("Unsupported media type: %s", e.getMessage());
        return Response.status(415)
                .entity(ApiResponse.error(415, "Unsupported Content-Type"))
                .build();
    }

    /**
     * Method not allowed for the path → 405.
     */
    @ServerExceptionMapper
    public Response handleNotAllowed(NotAllowedException e) {
        LOG.warnf("Method not allowed: %s", e.getMessage());
        return Response.status(405)
                .entity(ApiResponse.error(405, "Method not allowed"))
                .build();
    }

    /**
     * Client requested a media type the resource cannot produce → 406.
     * Without this, RestEasy returns the ApiResponse object's toString() as plain text.
     * Force application/json content-type so the JSON envelope is serialized correctly.
     */
    @ServerExceptionMapper
    public Response handleNotAcceptable(NotAcceptableException e) {
        LOG.warnf("Not acceptable: %s", e.getMessage());
        return Response.status(406)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiResponse.error(406, "Requested media type not supported; this API only produces application/json"))
                .build();
    }

    /**
     * Path not matched → 404 (uniform JSON shape instead of HTML/empty 404).
     */
    @ServerExceptionMapper
    public Response handleNotFound(NotFoundException e) {
        return Response.status(404)
                .entity(ApiResponse.error(404, "Not found"))
                .build();
    }

    /**
     * Database constraint violation (unique key, NOT NULL, FK) — Hibernate wraps PostgreSQL
     * SQLState 23xxx in this exception. Without this mapper, the user sees a generic
     * "HTTP 400 Bad Request" with no actionable message.
     */
    @ServerExceptionMapper
    public Response handleHibernateConstraint(org.hibernate.exception.ConstraintViolationException e) {
        String constraint = e.getConstraintName();
        String detail = constraint != null ? "constraint=" + constraint : "constraint violation";
        // Best-effort: extract Postgres error message
        String dbMsg = e.getSQLException() != null ? e.getSQLException().getMessage() : null;
        if (dbMsg != null && dbMsg.contains("duplicate key") && dbMsg.contains("Key (")) {
            int s = dbMsg.indexOf("Key (");
            int e1 = dbMsg.indexOf(")", s);
            if (s >= 0 && e1 > s) {
                detail = "Duplicate value for " + dbMsg.substring(s, e1 + 1);
            }
        } else if (dbMsg != null && dbMsg.contains("violates foreign key")) {
            detail = "Referenced record does not exist or is in use";
        } else if (dbMsg != null && dbMsg.contains("violates not-null")) {
            detail = "Required field cannot be null";
        }
        LOG.warnf("DB constraint violation: %s | sql=%s", constraint, dbMsg);
        return Response.status(409)
                .entity(ApiResponse.error(409, detail))
                .build();
    }

    /**
     * Other JAX-RS WebApplicationException carry their own response code and entity;
     * preserve the status, but always wrap with our standard envelope.
     */
    @ServerExceptionMapper
    public Response handleWebApplication(WebApplicationException e) {
        int status = e.getResponse() != null ? e.getResponse().getStatus() : 500;
        String msg = e.getMessage() != null ? e.getMessage() : "Request failed";
        LOG.warnf("WebApplicationException %d: %s", status, msg);
        return Response.status(status)
                .entity(ApiResponse.error(status, msg))
                .build();
    }

    @ServerExceptionMapper
    public Response handleGenericException(Exception e) {
        LOG.errorf(e, "Unexpected error");
        return Response.status(500)
                .entity(ApiResponse.error(500, "Internal server error"))
                .build();
    }
}
