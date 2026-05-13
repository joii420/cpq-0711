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
 * <p>POST /api/cpq/quotations/configure/lookup-fingerprint — P2→P3 之间实时查指纹
 * <p>POST /api/cpq/quotations/{quotationId}/configure-product — P5 确认时一锅端
 */
@Path("/api/cpq/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ConfigureProductResource {

    @Inject
    ConfigureProductService service;

    @Inject
    SecurityIdentity identity;

    @POST
    @Path("/configure/lookup-fingerprint")
    public LookupFingerprintResponse lookupFingerprint(LookupFingerprintRequest req) {
        return service.lookupFingerprint(req);
    }

    @POST
    @Path("/{quotationId}/configure-product")
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
