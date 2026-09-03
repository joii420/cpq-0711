package com.cpq.common.exception;

import com.cpq.common.dto.ApiResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Map;
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
        if (e instanceof RowKeyConflictException rce) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(),
                            Map.of("conflicts", rce.getConflicts())))
                    .build();
        }
        // task-260901 B-3b：保存草稿乐观并发冲突 —— 前端据 reason=STALE_VERSION 弹「刷新页面」。
        if (e instanceof StaleVersionException sve) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(), Map.of(
                            "reason", StaleVersionException.REASON,
                            "currentVersion", sve.getCurrentVersion())))
                    .build();
        }
        // task-0806 阶段① API-3：提交闸门 —— 未落定对账差异 / 在飞写，见 ReconcilePendingException。
        if (e instanceof ReconcilePendingException rpe) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(), Map.of(
                            "reason", rpe.getReason(),
                            "conflicts", rpe.getConflicts())))
                    .build();
        }
        if (e instanceof TreeConflictException tce) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(),
                            Map.of("conflictTabs", tce.getConflictTabs())))
                    .build();
        }
        // repair-0803：公式循环引用 —— 下发结构化环链路供前端弹抽屉。
        // errorType 是前端的唯一判定依据（禁止按 message 文本匹配，见 api.md §4）。
        if (e instanceof FormulaCycleException fce) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(), Map.of(
                            "errorType", "FORMULA_CYCLE",
                            "cycles", fce.getCycles())))
                    .build();
        }
        if (e instanceof com.cpq.priceadjust.exception.PendingVersionExistsException pvee) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(), Map.of(
                            "code", "PENDING_VERSION_EXISTS",
                            "pendingVersionNo", pvee.getPendingVersionNo(),
                            "pendingReviewCount", pvee.getPendingReviewCount(),
                            "approvedReviewCount", pvee.getApprovedReviewCount())))
                    .build();
        }
        if (e instanceof com.cpq.priceadjust.exception.StrategyNoElementsException) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(),
                            Map.of("code", "STRATEGY_NO_ELEMENTS")))
                    .build();
        }
        if (e instanceof com.cpq.priceadjust.exception.ReviewNotReadyException rnre) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(), Map.of(
                            "code", rnre.getErrorCode(),
                            "invalidItems", rnre.getInvalidItems())))
                    .build();
        }
        if (e instanceof com.cpq.priceadjust.exception.MaterialRemovalNeedsConfirmException mrnce) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(), Map.of(
                            "code", "REMOVAL_NEEDS_CONFIRM",
                            "removedMaterialNos", mrnce.getRemovedMaterialNos(),
                            "pendingReviewCount", mrnce.getPendingReviewCount(),
                            "unlockedQuotationCount", mrnce.getUnlockedQuotationCount())))
                    .build();
        }
        if (e instanceof com.cpq.priceadjust.exception.ElementUnselectNeedsConfirmException eunce) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(), Map.of(
                            "code", "UNSELECT_NEEDS_CONFIRM",
                            "removedElementCodes", eunce.getRemovedElementCodes(),
                            "unlockedQuotationCount", eunce.getUnlockedQuotationCount())))
                    .build();
        }
        // task-0806 B20（D16~D17）：模板 PUBLISHED/ARCHIVED 但快照零行 —— 过渡期「未冻结」，
        // 不是故障。code=TEMPLATE_NOT_FROZEN 是前端的唯一判定依据（禁止按 message 文本匹配）。
        if (e instanceof com.cpq.template.exception.TemplateNotFrozenException tnfe) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(), Map.of(
                            "code", com.cpq.template.exception.TemplateNotFrozenException.CODE,
                            "templateId", String.valueOf(tnfe.getTemplateId()),
                            "templateStatus", String.valueOf(tnfe.getTemplateStatus()))))
                    .build();
        }
        // task-260819 B-3：语义图四道保存期校验未通过（api.md §1.2）。
        // ⚠️ 本端点族的错误响应**不套** ApiResponse{code:int,message,data} 信封——api.md §1.2 的
        // 示例与 code/failedCheck/message/detail/checks 全部是**响应体顶层字段**（code 是字符串
        // 枚举值如 "SEMANTIC_VALIDATION_FAILED"，不是 HTTP 状态码），与本文件其余分支的约定不同，
        // 是这个新端点族自己的显式契约（前端与测试代理都按此契约读取，不能悄悄改回信封）。
        if (e instanceof com.cpq.semanticgraph.exception.SemanticValidationException sve) {
            return Response.status(e.getCode())
                    .entity(Map.of(
                            "code", "SEMANTIC_VALIDATION_FAILED",
                            "failedCheck", sve.getFailedCheck() == null ? "" : sve.getFailedCheck(),
                            "message", sve.getMessage() == null ? "" : sve.getMessage(),
                            "detail", sve.getDetail() == null ? Map.of() : sve.getDetail(),
                            "checks", sve.getChecks() == null ? List.of() : sve.getChecks()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        // task-260819 B-3：走写端点删除仍被引用的节点（AC-54②，与库层 FK 兜底是两条独立防线）。
        if (e instanceof com.cpq.semanticgraph.exception.SemanticNodeReferencedException snre) {
            return Response.status(e.getCode())
                    .entity(Map.of(
                            "code", "FK_STILL_REFERENCED",
                            "message", snre.getMessage() == null ? "" : snre.getMessage(),
                            "detail", Map.of(
                                    "referencingEdges", snre.getReferencingEdges() == null ? List.of() : snre.getReferencingEdges(),
                                    "referencingTabViews", snre.getReferencingTabViews() == null ? List.of() : snre.getReferencingTabViews())))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        // task-260819 B-11/12/13：取数配置器 builder 端点族——响应裸体，不套 ApiResponse 信封
        // （api.md §1.5③），字段直接平铺进 body。
        if (e instanceof com.cpq.builder.exception.BuilderApiException bae) {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("code", bae.getErrorCode());
            body.put("message", bae.getMessage() == null ? "" : bae.getMessage());
            body.putAll(bae.getExtra());
            return Response.status(e.getCode())
                    .entity(body)
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        // task-260901：材质模块错误码（api.md §2）。errorCode 放 data.code，前端按它判定，
        // 禁止按 message 文本匹配。
        if (e instanceof com.cpq.configure.exception.MaterialRecipeApiException mrae) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(),
                            Map.of("code", mrae.getErrorCode())))
                    .build();
        }
        // task-260902：用户模块错误码（api.md B-5）。同上，errorCode 放 data.code。
        if (e instanceof com.cpq.system.exception.UserApiException uae) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(),
                            Map.of("code", uae.getErrorCode())))
                    .build();
        }
        if (e instanceof com.cpq.component.exception.ComponentElementBindingRequiredException cebre) {
            return Response.status(e.getCode())
                    .entity(ApiResponse.error(e.getCode(), e.getMessage(), Map.of(
                            "code", "COMPONENT_ELEMENT_BINDING_REQUIRED",
                            "missingFields", cebre.getMissingFields())))
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
