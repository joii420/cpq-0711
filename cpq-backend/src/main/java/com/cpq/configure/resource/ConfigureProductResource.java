package com.cpq.configure.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.configure.dto.ConfigureProductRequest;
import com.cpq.configure.dto.ConfigureProductResponse;
import com.cpq.configure.dto.LookupFingerprintRequest;
import com.cpq.configure.dto.LookupFingerprintResponse;
import com.cpq.configure.service.ConfigureProductService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;

/**
 * 报价单选配产品的 REST 入口.
 *
 * <p>POST /api/cpq/configure-product/lookup-fingerprint — P2→P3 之间实时查指纹
 * <p>POST /api/cpq/configure-product/quotations/{quotationId} — P5 确认时一锅端
 *
 * <p>2026-05-18 hotfix: 类级 @Path 从 "/api/cpq/quotations" 改成 "/api/cpq/configure-product",
 *   避免和 QuotationResource (同父级路径) 的 `/{id}` / `/{id}/<literal>` 系列路由产生
 *   RestEasy 路径匹配冲突 → `/configure/lookup-fingerprint` 路由识别 404.
 *   独立父级路径后两条端点都能正常路由.
 */
@Path("/api/cpq/configure-product")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ConfigureProductResource {

    @Inject
    ConfigureProductService service;

    @Inject
    SecurityIdentity identity;

    @POST
    @Path("/lookup-fingerprint")
    public LookupFingerprintResponse lookupFingerprint(LookupFingerprintRequest req) {
        return service.lookupFingerprint(req);
    }

    @POST
    @Path("/quotations/{quotationId}")
    public ConfigureProductResponse configureProduct(@PathParam("quotationId") UUID quotationId,
                                                     ConfigureProductRequest req) {
        UUID operatorId = currentUserId();
        return service.configure(quotationId, req, operatorId);
    }

    /** 从 SecurityIdentity 取当前用户 id; 容错 — 取不到时返 null. */
    UUID currentUserId() {
        try {
            if (identity == null || identity.isAnonymous()) return null;
            String s = identity.getPrincipal().getName();
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
