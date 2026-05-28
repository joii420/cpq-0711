package com.cpq.configurator.service;

import com.cpq.common.dto.PageResult;
import com.cpq.configurator.entity.ConfiguratorInstance;
import com.cpq.configurator.entity.ConfiguratorOption;
import com.cpq.configurator.entity.ConfiguratorOptionValue;
import com.cpq.configurator.entity.ConfiguratorTemplate;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 选配实例服务。骨架阶段：基础 CRUD + 编号生成。
 *
 * <p>核心业务（提交两步式 link-action / evaluate / 解绑）后续切片实现。
 * 详见 docs/3D产品选配方案.md §5.5 + §7.8 + §9.2
 */
@ApplicationScoped
public class ConfiguratorInstanceService {

    @Inject
    EntityManager em;

    public PageResult<ConfiguratorInstance> list(int page, int size, String status, UUID customerId, UUID templateId) {
        StringBuilder query = new StringBuilder("1=1");
        java.util.List<Object> params = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            params.add(status);
            query.append(" and status = ?").append(params.size());
        }
        if (customerId != null) {
            params.add(customerId);
            query.append(" and customerId = ?").append(params.size());
        }
        if (templateId != null) {
            params.add(templateId);
            query.append(" and templateId = ?").append(params.size());
        }
        var pq = ConfiguratorInstance.find(query.toString(), Sort.by("createdAt").descending(), params.toArray());
        long total = pq.count();
        List<ConfiguratorInstance> rows = pq.page(Page.of(page, size)).list();
        return new PageResult<>(rows, page, size, total);
    }

    public ConfiguratorInstance getById(UUID id) {
        ConfiguratorInstance i = ConfiguratorInstance.findById(id);
        if (i == null) throw new NotFoundException("Configurator instance not found: " + id);
        return i;
    }

    /**
     * 生成实例编号 CI-{yyyyMM}-{seq:04d}（按月归 1）。
     * <p>用 SQL Sequence 单调递增，前端展示用 yyyyMM 当月分组。
     */
    public String generateInstanceCode() {
        String yyyymm = DateTimeFormatter.ofPattern("yyyyMM").format(OffsetDateTime.now(ZoneOffset.UTC));
        Number nextVal = (Number) em.createNativeQuery("SELECT nextval('seq_config_instance_seq')").getSingleResult();
        return String.format("CI-%s-%04d", yyyymm, nextVal.intValue());
    }

    @Transactional
    public ConfiguratorInstance create(ConfiguratorInstance i) {
        i.id = null;
        if (i.instanceCode == null) i.instanceCode = generateInstanceCode();
        if (i.status == null) i.status = "SUBMITTED";
        if (i.expiresAt == null) i.expiresAt = OffsetDateTime.now().plusDays(30);
        i.persist();
        return i;
    }

    @Transactional
    public ConfiguratorInstance update(UUID id, ConfiguratorInstance patch) {
        ConfiguratorInstance i = getById(id);
        if (patch.name != null) i.name = patch.name;
        if (patch.selectedValues != null) i.selectedValues = patch.selectedValues;
        if (patch.computedTotalPrice != null) i.computedTotalPrice = patch.computedTotalPrice;
        return i;
    }

    @Transactional
    public void delete(UUID id) {
        ConfiguratorInstance i = getById(id);
        if ("LINKED".equals(i.status)) {
            throw new IllegalStateException("LINKED instance cannot be deleted, unlink first");
        }
        i.delete();
    }

    /**
     * 简化版 evaluate（仅累加 priceDelta，不做约束求值）。
     *
     * <p>请求 selectedValues 形如 {"OPT-MODEL": "ENH", "OPT-COATING": "NICKEL_05"}。
     * <p>约束求值算法（§3.4.1）后续切片实现。
     */
    public Map<String, Object> evaluate(UUID templateId, Map<String, Object> selectedValues) {
        if (selectedValues == null) selectedValues = new HashMap<>();

        ConfiguratorTemplate t = ConfiguratorTemplate.findById(templateId);
        if (t == null) throw new NotFoundException("Template not found: " + templateId);

        List<ConfiguratorOption> opts = ConfiguratorOption.list("templateId", templateId);
        // 基础价（暂从 metadata 读取，无则默认 0；本方案后续切片接 price_rule 表）
        BigDecimal basePrice = BigDecimal.ZERO;
        if (t.metadata != null && t.metadata.get("base_price") instanceof Number) {
            basePrice = new BigDecimal(t.metadata.get("base_price").toString());
        }
        BigDecimal deltaSum = BigDecimal.ZERO;
        java.util.List<Map<String, Object>> breakdown = new java.util.ArrayList<>();

        for (ConfiguratorOption o : opts) {
            Object selObj = selectedValues.get(o.code);
            if (selObj == null) continue;

            // 兼容 MULTI_SELECT：array / 逗号分隔字符串
            java.util.List<String> codes = new java.util.ArrayList<>();
            if (selObj instanceof java.util.List) {
                for (Object c : (java.util.List<?>) selObj) codes.add(c.toString());
            } else if (selObj.toString().contains(",")) {
                for (String c : selObj.toString().split(",")) {
                    String tok = c.trim(); if (!tok.isEmpty()) codes.add(tok);
                }
            } else {
                codes.add(selObj.toString());
            }

            for (String sel : codes) {
                List<ConfiguratorOptionValue> vals = ConfiguratorOptionValue.list("optionId = ?1 and code = ?2", o.id, sel);
                if (vals.isEmpty()) continue;
                ConfiguratorOptionValue v = vals.get(0);
                BigDecimal delta = v.priceDelta == null ? BigDecimal.ZERO : v.priceDelta;
                deltaSum = deltaSum.add(delta);
                Map<String, Object> line = new HashMap<>();
                line.put("option_code", o.code);
                line.put("option_label", o.label);
                line.put("value_code", v.code);
                line.put("value_label", v.label);
                line.put("delta", delta);
                breakdown.add(line);
            }
        }

        BigDecimal total = basePrice.add(deltaSum);

        Map<String, Object> ret = new HashMap<>();
        ret.put("isValid", true);
        ret.put("conflicts", java.util.Collections.emptyList());
        ret.put("basePrice", basePrice);
        ret.put("deltaSum", deltaSum);
        ret.put("totalPrice", total);
        ret.put("priceBreakdown", breakdown);
        ret.put("fingerprint", computeFingerprint(selectedValues));
        return ret;
    }

    /** SHA-256 fingerprint of canonical selected_values. */
    public String computeFingerprint(Map<String, Object> selectedValues) {
        try {
            TreeMap<String, Object> sorted = new TreeMap<>(selectedValues);
            String canonical = sorted.toString();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * §5.5 link-action: NEW_QUOTATION 暂不接报价单生成 (TODO 后续切片接 quotation 模块)，
     * LINK_EXISTING 仅写 linked_quotation_id，SAVE_DRAFT 不调此端点。
     */
    @Transactional
    public Map<String, Object> linkAction(UUID instanceId, String action, UUID quotationId) {
        ConfiguratorInstance i = getById(instanceId);
        if (!"SUBMITTED".equals(i.status)) {
            throw new IllegalStateException("Only SUBMITTED instance can be linked, current: " + i.status);
        }
        Map<String, Object> ret = new HashMap<>();
        switch (action) {
            case "NEW_QUOTATION": {
                // V247+尾 3: 真实集成 quotation + line_item
                String partNo = "CFG-" + (i.configFingerprint != null
                    ? i.configFingerprint.substring(0, 6).toUpperCase()
                    : "UNKNOWN");
                UUID newQuotationId = UUID.randomUUID();
                UUID newLineItemId = UUID.randomUUID();
                java.util.List<String> warnings = new java.util.ArrayList<>();
                try {
                    em.createNativeQuery("""
                        INSERT INTO quotation (id, customer_id, name, status, created_at, updated_at, created_by, total_amount, currency)
                        VALUES (?1, ?2, ?3, 'DRAFT', NOW(), NOW(), NULL, ?4, 'CNY')
                        """).setParameter(1, newQuotationId)
                          .setParameter(2, i.customerId != null ? i.customerId : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                          .setParameter(3, "由选配 " + i.instanceCode + " 生成")
                          .setParameter(4, i.computedTotalPrice == null ? java.math.BigDecimal.ZERO : i.computedTotalPrice)
                          .executeUpdate();
                } catch (Exception e) {
                    warnings.add("quotation INSERT 失败: " + e.getMessage());
                    newQuotationId = UUID.randomUUID();  // mock fallback
                }

                // line_item 写入（依赖 product / template 表存在）
                try {
                    // 取任一 product_id + template_id 作为兜底（实际场景：先创建虚拟 product 再 INSERT）
                    Object productId = em.createNativeQuery("SELECT id FROM product LIMIT 1").getSingleResult();
                    Object templateId = em.createNativeQuery("SELECT id FROM template LIMIT 1").getSingleResult();
                    em.createNativeQuery("""
                        INSERT INTO quotation_line_item (id, quotation_id, product_id, template_id,
                            product_attribute_values, subtotal, system_discount_rate, final_discount_rate,
                            sort_order, created_at)
                        VALUES (?1, ?2, ?3, ?4, CAST(?5 AS JSONB), ?6, 100, 100, 0, NOW())
                        """).setParameter(1, newLineItemId)
                          .setParameter(2, newQuotationId)
                          .setParameter(3, productId)
                          .setParameter(4, templateId)
                          .setParameter(5, com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                              .writeValueAsString(i.selectedValues == null ? java.util.Map.of() : i.selectedValues))
                          .setParameter(6, i.computedTotalPrice == null ? java.math.BigDecimal.ZERO : i.computedTotalPrice)
                          .executeUpdate();
                } catch (Exception e) {
                    warnings.add("line_item INSERT 容错: " + e.getMessage());
                }

                i.generatedPartNo = partNo;
                i.generatedQuotationId = newQuotationId;
                i.generatedLineItemId = newLineItemId;
                i.linkedQuotationId = newQuotationId;
                i.linkedAt = OffsetDateTime.now();
                i.status = "LINKED";
                ret.put("quotation_id", newQuotationId);
                ret.put("line_item_id", newLineItemId);
                ret.put("part_no", partNo);
                if (!warnings.isEmpty()) ret.put("warnings", warnings);
                ret.put("note", warnings.isEmpty()
                    ? "已真实写入 quotation + quotation_line_item（含 product_attribute_values JSONB）"
                    : "部分写入失败，详见 warnings");
                break;
            }
            case "LINK_EXISTING": {
                if (quotationId == null) throw new IllegalArgumentException("quotation_id required");
                i.linkedQuotationId = quotationId;
                i.linkedAt = OffsetDateTime.now();
                i.status = "LINKED";
                ret.put("quotation_id", quotationId);
                ret.put("note", "Linked to existing quotation. TODO: insert line_item into quotation");
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown action: " + action);
        }
        ret.put("status", i.status);
        ret.put("instance_code", i.instanceCode);
        return ret;
    }

    @Transactional
    public Map<String, Object> unlink(UUID instanceId) {
        ConfiguratorInstance i = getById(instanceId);
        if (!"LINKED".equals(i.status)) {
            throw new IllegalStateException("Only LINKED instance can be unlinked, current: " + i.status);
        }
        UUID prev = i.linkedQuotationId;
        i.linkedQuotationId = null;
        i.linkedAt = null;
        i.status = "SUBMITTED";
        Map<String, Object> ret = new HashMap<>();
        ret.put("status", "SUBMITTED");
        ret.put("unlinked_from", prev);
        ret.put("note", "Line item kept in quotation per §17 weak coupling design");
        return ret;
    }
}
