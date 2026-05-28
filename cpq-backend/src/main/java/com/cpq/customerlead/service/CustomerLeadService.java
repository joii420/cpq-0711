package com.cpq.customerlead.service;

import com.cpq.common.dto.PageResult;
import com.cpq.customerlead.entity.CustomerLead;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户线索服务（v0.4 P1 客户身份处理 SOP，§17.5）。
 *
 * <p>骨架阶段：CRUD + 编号生成。审核动作（BIND_EXISTING / CREATE_NEW / REJECT）后续切片实现。
 */
@ApplicationScoped
public class CustomerLeadService {

    @Inject
    EntityManager em;

    public PageResult<CustomerLead> list(int page, int size, String status, String phone) {
        StringBuilder query = new StringBuilder("1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            params.add(status);
            query.append(" and status = ?").append(params.size());
        }
        if (phone != null && !phone.isBlank()) {
            params.add(phone);
            query.append(" and contactPhone = ?").append(params.size());
        }
        var pq = CustomerLead.find(query.toString(), Sort.by("createdAt").descending(), params.toArray());
        long total = pq.count();
        List<CustomerLead> rows = pq.page(Page.of(page, size)).list();
        return new PageResult<>(rows, page, size, total);
    }

    public CustomerLead getById(UUID id) {
        CustomerLead l = CustomerLead.findById(id);
        if (l == null) throw new NotFoundException("Customer lead not found: " + id);
        return l;
    }

    public String generateLeadCode() {
        String yyyymm = DateTimeFormatter.ofPattern("yyyyMM").format(OffsetDateTime.now(ZoneOffset.UTC));
        Number nextVal = (Number) em.createNativeQuery("SELECT nextval('seq_customer_lead_seq')").getSingleResult();
        return String.format("LEAD-%s-%04d", yyyymm, nextVal.intValue());
    }

    @Transactional
    public CustomerLead create(CustomerLead l) {
        l.id = null;
        if (l.leadCode == null) l.leadCode = generateLeadCode();
        if (l.status == null) l.status = "PENDING_REVIEW";
        l.persist();
        return l;
    }

    /**
     * §17.5 审核三选一动作。
     *
     * <p>BIND_EXISTING: 绑定到已有 customer，更新 lead 状态 + 同步关联实例的 customer_id
     * <p>CREATE_NEW: 新建 customer 主档（TODO 后续切片接 customer 模块）+ 同上
     * <p>REJECT: 标记为 REJECTED + 关联实例 EXPIRED
     */
    @Transactional
    public Map<String, Object> review(UUID leadId, String action,
                                       UUID boundCustomerId, String reviewNote, UUID reviewedBy) {
        CustomerLead l = getById(leadId);
        if (!"PENDING_REVIEW".equals(l.status)) {
            throw new IllegalStateException("Only PENDING_REVIEW lead can be reviewed, current: " + l.status);
        }
        Map<String, Object> ret = new java.util.HashMap<>();

        switch (action) {
            case "BIND_EXISTING": {
                if (boundCustomerId == null) throw new IllegalArgumentException("bound_customer_id required");
                l.boundCustomerId = boundCustomerId;
                l.status = "CONVERTED";
                l.reviewAction = "BIND_EXISTING";
                // 同步关联实例的 customer_id
                int updated = em.createNativeQuery(
                    "UPDATE product_config_instance SET customer_id = ?1 WHERE customer_lead_id = ?2"
                ).setParameter(1, boundCustomerId).setParameter(2, leadId).executeUpdate();
                ret.put("updated_instances", updated);
                ret.put("bound_customer_id", boundCustomerId);
                break;
            }
            case "CREATE_NEW": {
                // TODO 后续切片：调用 customer 模块创建新 customer 主档
                // 临时实现：返回 lead 信息建议管理员手工建 customer 后再 BIND_EXISTING
                ret.put("note", "TODO: integrate with customer module to create new customer");
                ret.put("lead_info", Map.of(
                    "contactName", l.contactName,
                    "contactPhone", l.contactPhone,
                    "contactEmail", l.contactEmail == null ? "" : l.contactEmail,
                    "companyName", l.companyName == null ? "" : l.companyName
                ));
                // 暂不修改 status，等真实集成
                throw new IllegalStateException("CREATE_NEW not yet implemented — manually create customer in customer module then BIND_EXISTING");
            }
            case "REJECT": {
                l.status = "REJECTED";
                l.reviewAction = "REJECT";
                // 关联实例置 EXPIRED
                int updated = em.createNativeQuery(
                    "UPDATE product_config_instance SET status = 'EXPIRED' WHERE customer_lead_id = ?1 AND status NOT IN ('LINKED','EXPIRED')"
                ).setParameter(1, leadId).executeUpdate();
                ret.put("expired_instances", updated);
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown action: " + action);
        }
        l.reviewedBy = reviewedBy;
        l.reviewedAt = OffsetDateTime.now();
        l.reviewNote = reviewNote;
        ret.put("status", l.status);
        ret.put("lead_code", l.leadCode);
        return ret;
    }
}
