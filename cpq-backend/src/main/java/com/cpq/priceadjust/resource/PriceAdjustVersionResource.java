package com.cpq.priceadjust.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.configure.entity.Element;
import com.cpq.priceadjust.dto.GenerateVersionRequest;
import com.cpq.priceadjust.dto.VersionDTO;
import com.cpq.priceadjust.dto.VersionItemDTO;
import com.cpq.priceadjust.entity.ElementPriceVersion;
import com.cpq.priceadjust.entity.ElementPriceVersionItem;
import com.cpq.priceadjust.entity.MaterialPriceReview;
import com.cpq.priceadjust.service.PriceAdjustVersionGenerationService;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * task-0729 B3 · 价格版本端点（api.md §1.11 / §1.12 / §1.13）。
 */
@Path("/api/cpq/price-adjust/versions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PriceAdjustVersionResource {

    @Inject PriceAdjustVersionGenerationService versionService;

    /** §1.11 手动「立即生成一次」——与定时任务走完全相同的服务方法（验收 #5/#67③）。 */
    @POST
    @Path("/generate")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public Response generate(GenerateVersionRequest req) {
        if (req == null || req.customerNo == null || req.customerNo.isBlank()) {
            throw new BusinessException(400, "customerNo 不能为空");
        }
        PriceAdjustVersionGenerationService.GenerateResult r =
            versionService.generateVersionAndEnqueueBudget(req.customerNo, req.confirmSupersede, "MANUAL", null);

        VersionDTO dto = new VersionDTO();
        dto.versionId = r.versionId;
        dto.versionNo = r.versionNo;
        dto.baseDate = r.baseDate;
        dto.itemCount = r.itemCount;
        dto.budgetJobId = r.budgetJobId;
        dto.budgetStatus = r.budgetStatus;
        return Response.status(201).entity(ApiResponse.success(dto)).build();
    }

    /** §1.12 版本轨迹（屏 1 底部）。进度摘要实时派生，不落库（§11.3.3(2)）。 */
    @GET
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PageResult<VersionDTO>> list(
            @QueryParam("customerNo") String customerNo,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 20;

        String query = customerNo != null && !customerNo.isBlank() ? "customerNo = ?1" : null;
        long total = query != null
            ? ElementPriceVersion.count(query, customerNo)
            : ElementPriceVersion.count();
        List<ElementPriceVersion> rows = query != null
            ? ElementPriceVersion.find(query, Sort.by("createdAt").descending(), customerNo)
                .page(Page.of(page - 1, size)).list()
            : ElementPriceVersion.findAll(Sort.by("createdAt").descending())
                .page(Page.of(page - 1, size)).list();

        List<VersionDTO> content = rows.stream().map(this::toDto).toList();
        return ApiResponse.success(new PageResult<>(content, page, size, total));
    }

    /** §1.13 版本明细（元素级），用于屏 1 展开查看。 */
    @GET
    @Path("/{versionId}/items")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<PageResult<VersionItemDTO>> items(@PathParam("versionId") UUID versionId) {
        ElementPriceVersion version = ElementPriceVersion.findById(versionId);
        if (version == null) throw new BusinessException(404, "版本不存在: " + versionId);

        List<ElementPriceVersionItem> items = ElementPriceVersionItem.listByVersion(versionId);
        Map<String, String> nameByCode = new LinkedHashMap<>();
        for (ElementPriceVersionItem it : items) {
            if (!nameByCode.containsKey(it.elementCode)) {
                Element el = Element.find("elementCode", it.elementCode).firstResult();
                nameByCode.put(it.elementCode, el != null ? el.elementName : it.elementCode);
            }
        }

        List<VersionItemDTO> content = items.stream().map(it -> {
            VersionItemDTO dto = new VersionItemDTO();
            dto.elementCode = it.elementCode;
            dto.elementName = nameByCode.get(it.elementCode);
            dto.currentPrice = it.currentPrice;
            dto.previousPrice = it.previousPrice;
            dto.changeRate = it.changeRate;
            dto.currency = it.currency;
            dto.priceUnit = it.priceUnit;
            dto.noPrice = Boolean.TRUE.equals(it.noPrice);
            dto.inheritedFromPrevious = Boolean.TRUE.equals(it.inheritedFromPrevious);
            return dto;
        }).toList();

        return ApiResponse.success(new PageResult<>(content, 1, content.size(), content.size()));
    }

    private VersionDTO toDto(ElementPriceVersion v) {
        VersionDTO dto = new VersionDTO();
        dto.versionId = v.id;
        dto.versionNo = v.versionNo;
        dto.baseDate = v.baseDate;
        dto.status = v.status;
        dto.triggerType = v.triggerType;
        dto.createdAt = v.createdAt;
        dto.itemCount = (int) ElementPriceVersionItem.count("versionId", v.id);

        VersionDTO.Progress p = new VersionDTO.Progress();
        p.total = MaterialPriceReview.count("versionId", v.id);
        p.approved = MaterialPriceReview.count("versionId = ?1 and status = ?2", v.id, MaterialPriceReview.STATUS_APPROVED);
        p.rejected = MaterialPriceReview.count("versionId = ?1 and status = ?2", v.id, MaterialPriceReview.STATUS_REJECTED);
        p.pending = MaterialPriceReview.count("versionId = ?1 and status = ?2", v.id, MaterialPriceReview.STATUS_PENDING);
        p.budgeting = MaterialPriceReview.count(
            "versionId = ?1 and status = ?2 and budgetStatus in (?3, ?4)",
            v.id, MaterialPriceReview.STATUS_PENDING, MaterialPriceReview.BUDGET_QUEUED, MaterialPriceReview.BUDGET_COMPUTING);
        dto.progress = p;
        return dto;
    }
}
