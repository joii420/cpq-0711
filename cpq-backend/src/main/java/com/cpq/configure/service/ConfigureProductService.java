package com.cpq.configure.service;

import com.cpq.configure.FingerprintCalculator;
import com.cpq.configure.FingerprintCalculator.ElementInput;
import com.cpq.configure.dto.ConfigureProductRequest;
import com.cpq.configure.dto.ConfigureProductResponse;
import com.cpq.configure.dto.ElementOverride;
import com.cpq.configure.dto.LookupFingerprintRequest;
import com.cpq.configure.dto.LookupFingerprintResponse;
import com.cpq.configure.dto.PartRequest;
import com.cpq.partno.PartNoContext;
import com.cpq.partno.PartNoProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 选配产品服务 — 处理报价单"添加产品 — 选配"抽屉的所有后端逻辑.
 *
 * <p>三大职责:
 * <ol>
 *   <li>{@link #lookupFingerprint} — P2→P3 之间实时查指纹是否命中已有料号</li>
 *   <li>{@link #configure} — P5 确认时一锅端: 落 mat_part/mat_bom/mat_process/mat_composite_process + 返 LineItem</li>
 *   <li>helper: resolvePart / validateCustomPart / insertMatPart / insertElementBom /
 *       insertProcesses / insertAssemblyBom / insertCompositeProcesses / insertLineItem / buildLineItems</li>
 * </ol>
 *
 * <p><b>Schema 偏差说明</b> (相对 T20/T21 原始规格):
 * <ul>
 *   <li>{@code mat_bom}: V153 仅加了 part_version，无 is_current 列；INSERT 语句去掉该列.</li>
 *   <li>{@code quotation_line_item}: 无 quantity 列 (迁移中从未添加)；INSERT 语句去掉该列.
 *       product_id / template_id 在 V30 已改为 nullable — 选配行直接填 product_part_no_snapshot.</li>
 *   <li>{@code mat_part_version_log}: PK 为 (customer_product_no NOT NULL, hf_part_no, version).
 *       选配阶段没有 customer_product_no（料号-客户映射尚未建立），故 {@code initPartVersionBaseline}
 *       无法实现 — 基线行将在后续数据导入（PartVersionService / V156）时由 per-customer 流程写入.
 *       这是架构层设计决定：configure 产生全局料号 (mat_part)，客户绑定由导入流程完成.</li>
 *   <li>{@code insertProcesses}: 原 T20 未实现因 mat_process.customer_id NOT NULL.
 *       P4 批2补丁 (2026-05-13) 修复: configure() 入口从 quotation 表拉 customer_id,
 *       传递到 resolvePart → insertProcesses，现已实现.</li>
 *   <li>{@code ON CONFLICT 指纹}: 原用 ON CONFLICT (part_no) DO NOTHING，已修正为
 *       ON CONFLICT (config_fingerprint) WHERE config_fingerprint IS NOT NULL DO NOTHING
 *       (PG 16 partial unique index inference，对应 V167 建的 uq_mat_part_fingerprint).</li>
 * </ul>
 */
@ApplicationScoped
public class ConfigureProductService {

    @Inject
    EntityManager em;

    @Inject
    FingerprintCalculator fingerprintCalc;

    @Inject
    PartNoProvider partNoProvider;

    // ───────────────────────────────────────────────────────────────────────
    // T19: lookup-fingerprint 端点
    // ───────────────────────────────────────────────────────────────────────

    /**
     * 抽屉 P2 完成时调用 — 算指纹查 DB, 命中则返回已有料号 + 快照,未命中返回 matched=false.
     */
    public LookupFingerprintResponse lookupFingerprint(LookupFingerprintRequest req) {
        if (req == null || req.productType == null) {
            throw new IllegalArgumentException("productType is required");
        }

        String fp;
        if ("SIMPLE".equals(req.productType)) {
            if (req.recipeCode == null || req.elements == null || req.elements.isEmpty()) {
                throw new IllegalArgumentException("SIMPLE: recipeCode + elements required");
            }
            List<ElementInput> elems = req.elements.stream()
                .map(e -> new ElementInput(e.elementCode, e.pct))
                .collect(Collectors.toList());
            fp = fingerprintCalc.simpleFingerprint(req.recipeCode, elems);
        } else if ("COMPOSITE".equals(req.productType)) {
            if (req.childHfPartNos == null || req.childHfPartNos.size() < 2) {
                throw new IllegalArgumentException("COMPOSITE: childHfPartNos size >= 2");
            }
            fp = fingerprintCalc.compositeFingerprint(req.childHfPartNos);
        } else {
            throw new IllegalArgumentException("Unknown productType: " + req.productType);
        }

        String hfPartNo = lookupHfByFingerprint(fp);
        LookupFingerprintResponse resp = new LookupFingerprintResponse();
        if (hfPartNo == null) {
            resp.matched = false;
            return resp;
        }
        resp.matched = true;
        resp.hfPartNo = hfPartNo;
        resp.snapshot = buildSnapshot(hfPartNo);
        return resp;
    }

    @SuppressWarnings("unchecked")
    String lookupHfByFingerprint(String fp) {
        List<Object> rows = em.createNativeQuery(
                "SELECT part_no FROM mat_part WHERE config_fingerprint = :fp")
            .setParameter("fp", fp)
            .getResultList();
        return rows.isEmpty() ? null : (String) rows.get(0);
    }

    @SuppressWarnings("unchecked")
    LookupFingerprintResponse.Snapshot buildSnapshot(String hfPartNo) {
        LookupFingerprintResponse.Snapshot s = new LookupFingerprintResponse.Snapshot();

        // unit_weight from mat_part (PK = part_no)
        List<Object> w = em.createNativeQuery(
                "SELECT unit_weight FROM mat_part WHERE part_no = :p")
            .setParameter("p", hfPartNo).getResultList();
        s.unitWeightGrams = (w.isEmpty() || w.get(0) == null)
            ? null
            : new BigDecimal(w.get(0).toString());

        // mat_process has customer_id in its UNIQUE index; DISTINCT ON seq_no across customers
        List<Object[]> procs = em.createNativeQuery(
                "SELECT DISTINCT ON (seq_no) process_code, seq_no " +
                "FROM mat_process " +
                "WHERE hf_part_no = :p AND is_current = true " +
                "ORDER BY seq_no")
            .setParameter("p", hfPartNo).getResultList();
        s.processes = procs.stream().map(row -> {
            Map<String, Object> m = new HashMap<>();
            m.put("processCode", row[0]);
            m.put("seqNo", row[1]);
            return m;
        }).collect(Collectors.toList());

        // mat_composite_process: column is hf_part_no (renamed V166 from parent_hf_part_no)
        List<Object[]> cprocs = em.createNativeQuery(
                "SELECT def_code, seq_no, participating_parts, param_values " +
                "FROM mat_composite_process " +
                "WHERE hf_part_no = :p AND is_current = true ORDER BY seq_no")
            .setParameter("p", hfPartNo).getResultList();
        s.compositeProcesses = cprocs.stream().map(row -> {
            Map<String, Object> m = new HashMap<>();
            m.put("defCode", row[0]);
            m.put("seqNo", row[1]);
            m.put("participatingParts", row[2]);
            m.put("paramValues", row[3]);
            return m;
        }).collect(Collectors.toList());

        return s;
    }

    // ───────────────────────────────────────────────────────────────────────
    // T20: resolvePart + 校验 + 落库辅助
    // ───────────────────────────────────────────────────────────────────────

    /**
     * 解析单个配件,返回 hf_part_no (即 mat_part.part_no).
     * <ul>
     *   <li>existing 路径: 直接验证存在后返回,不动基础表</li>
     *   <li>custom 命中指纹: 复用,不动基础表</li>
     *   <li>custom 未命中: 新建 mat_part + mat_bom (ELEMENT N 行) + mat_process (若有 processIds)</li>
     * </ul>
     *
     * <p>注意: mat_part_version_log 基线行需要 customer_product_no (NOT NULL PK 成员),
     * configure 阶段不存在此信息，基线由 per-customer 数据导入流程 (V156/PartVersionService) 写入.
     */
    String resolvePart(PartRequest pr, UUID operatorId, UUID customerId, List<String> reused) {
        if ("existing".equals(pr.partMode)) {
            if (pr.existingHfPartNo == null || pr.existingHfPartNo.isBlank()) {
                throw new IllegalArgumentException("existing 模式 existingHfPartNo 必填");
            }
            Object exists = em.createNativeQuery(
                    "SELECT 1 FROM mat_part WHERE part_no = :p")
                .setParameter("p", pr.existingHfPartNo)
                .getResultStream().findFirst().orElse(null);
            if (exists == null) {
                throw new IllegalArgumentException("料号不存在: " + pr.existingHfPartNo);
            }
            return pr.existingHfPartNo;
        }

        if (!"custom".equals(pr.partMode)) {
            throw new IllegalArgumentException(
                "partMode must be 'existing' or 'custom': " + pr.partMode);
        }

        validateCustomPart(pr);

        List<ElementInput> elems = pr.elements.stream()
            .map(e -> new ElementInput(e.elementCode, e.pct))
            .collect(Collectors.toList());
        String fp = fingerprintCalc.simpleFingerprint(pr.recipeCode, elems);

        String existing = lookupHfByFingerprint(fp);
        if (existing != null) {
            reused.add(existing);
            return existing;
        }

        // 未命中指纹 → 新建
        com.cpq.configure.entity.MaterialRecipe recipe =
            com.cpq.configure.entity.MaterialRecipe.findByCodeOrThrow(pr.recipeCode);

        String hfPartNo = partNoProvider.apply(
            new PartNoContext(recipe.symbol, "SIMPLE", operatorId));

        insertMatPart(hfPartNo, "SIMPLE", fp, pr.unitWeightGrams, recipe.id);
        insertElementBom(hfPartNo, pr.elements);

        // 写 mat_process — 需要 customerId (NOT NULL)
        if (pr.processIds != null && !pr.processIds.isEmpty()) {
            if (customerId == null) {
                throw new IllegalArgumentException(
                    "选配 custom 配件含 processIds 但 quotation 无 customer_id");
            }
            insertProcesses(hfPartNo, pr.processIds, customerId);
        }

        // mat_part_version_log 基线行: PK (customer_product_no NOT NULL, hf_part_no, version)
        // configure 阶段无 customer_product_no (客户产品号在数据导入后才存在)
        // 基线由 PartVersionService / V156 在 per-customer 导入流程时写入

        return hfPartNo;
    }

    void validateCustomPart(PartRequest pr) {
        if (pr.recipeCode == null) {
            throw new IllegalArgumentException("custom 模式 recipeCode 必填");
        }
        if (pr.elements == null || pr.elements.isEmpty()) {
            throw new IllegalArgumentException("custom 模式 elements 必填");
        }
        // 元素含量和校验 (±0.01% 容差)
        BigDecimal sum = pr.elements.stream()
            .map(e -> e.pct == null ? BigDecimal.ZERO : e.pct)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.subtract(new BigDecimal("100")).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new IllegalArgumentException("元素含量之和必须 = 100, 当前: " + sum);
        }

        // locked / range 校验
        com.cpq.configure.entity.MaterialRecipe recipe =
            com.cpq.configure.entity.MaterialRecipe.findByCodeOrThrow(pr.recipeCode);
        List<com.cpq.configure.entity.MaterialRecipeElement> defs =
            com.cpq.configure.entity.MaterialRecipeElement.list("recipeId", recipe.id);
        Map<String, com.cpq.configure.entity.MaterialRecipeElement> defByCode = defs.stream()
            .collect(Collectors.toMap(e -> e.elementCode, e -> e));

        for (ElementOverride eo : pr.elements) {
            com.cpq.configure.entity.MaterialRecipeElement def = defByCode.get(eo.elementCode);
            if (def == null) {
                throw new IllegalArgumentException("元素未在 recipe 定义: " + eo.elementCode);
            }
            if (def.isLocked) {
                if (eo.pct.compareTo(def.defaultPct) != 0) {
                    throw new IllegalArgumentException(
                        "元素已锁定,不可修改: " + eo.elementCode);
                }
            } else {
                if (eo.pct.compareTo(def.minPct) < 0 || eo.pct.compareTo(def.maxPct) > 0) {
                    throw new IllegalArgumentException(
                        "元素含量超出范围 [" + def.minPct + ", " + def.maxPct + "]: "
                        + eo.elementCode);
                }
            }
        }
    }

    /**
     * 插入 mat_part 行.
     *
     * <p>使用 ON CONFLICT (config_fingerprint) WHERE config_fingerprint IS NOT NULL DO NOTHING
     * — PG 16 支持对 partial unique index 使用 conflict target + predicate 语法.
     * 对应 V167 创建的 uq_mat_part_fingerprint 索引.
     * 若存在并发相同配置竞争，conflict 处理为幂等忽略.
     */
    void insertMatPart(String hfPartNo, String productType, String fingerprint,
                       BigDecimal unitWeight, UUID materialRecipeId) {
        em.createNativeQuery(
                "INSERT INTO mat_part (part_no, product_type, config_fingerprint, " +
                "unit_weight, material_recipe_id, created_at, updated_at) " +
                "VALUES (:pn, :pt, :fp, :uw, :mri, NOW(), NOW()) " +
                "ON CONFLICT (config_fingerprint) WHERE config_fingerprint IS NOT NULL DO NOTHING")
            .setParameter("pn", hfPartNo)
            .setParameter("pt", productType)
            .setParameter("fp", fingerprint)
            .setParameter("uw", unitWeight)
            .setParameter("mri", materialRecipeId)
            .executeUpdate();
    }

    void insertElementBom(String hfPartNo, List<ElementOverride> elements) {
        // mat_bom: has part_version (V153) but NO is_current column (not in V44 or V153)
        // element_name stores the elementCode; composition_pct stores the percentage
        int seq = 1;
        for (ElementOverride eo : elements) {
            em.createNativeQuery(
                    "INSERT INTO mat_bom (hf_part_no, bom_type, seq_no, element_name, " +
                    "composition_pct, part_version, created_at) " +
                    "VALUES (:p, 'ELEMENT', :sq, :en, :pct, 2000, NOW())")
                .setParameter("p", hfPartNo)
                .setParameter("sq", seq++)
                .setParameter("en", eo.elementCode)
                .setParameter("pct", eo.pct)
                .executeUpdate();
        }
    }

    /**
     * 写 mat_process 工艺行 — P4 批2 补丁实现.
     *
     * <p>从 quotation 获取的 customerId 满足 customer_id NOT NULL 约束.
     * process 字典表的 code 列即对应 mat_process.process_code.
     * mat_process UNIQUE INDEX uq_mat_process_current:
     *   (customer_id, hf_part_no, part_version, seq_no, sub_seq_no) WHERE is_current=true
     * sub_seq_no 为 NULL 时 UNIQUE index 使用 NULL 语义 (每行唯一,不冲突),
     * 故重复调用（指纹命中复用路径）不会再走到这里.
     *
     * <p>注意: insertProcesses 仅在"未命中指纹 → 新建"路径调用，不存在重复写入风险.
     */
    @SuppressWarnings("unchecked")
    void insertProcesses(String hfPartNo, List<UUID> processIds, UUID customerId) {
        int seq = 1;
        for (UUID processId : processIds) {
            List<Object> rows = em.createNativeQuery(
                    "SELECT code FROM process WHERE id = :id")
                .setParameter("id", processId)
                .getResultList();
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("工艺不存在: " + processId);
            }
            String code = (String) rows.get(0);

            // mat_process required columns (from V44 + V153):
            //   customer_id NOT NULL, hf_part_no NOT NULL, version INT DEFAULT 1,
            //   is_current BOOLEAN NOT NULL DEFAULT true, seq_no NOT NULL,
            //   status NOT NULL DEFAULT 'ACTIVE', part_version INT NOT NULL DEFAULT 2000
            // process_code is VARCHAR nullable but we fill it from the process dict
            em.createNativeQuery(
                    "INSERT INTO mat_process " +
                    "(customer_id, hf_part_no, version, is_current, seq_no, " +
                    "process_code, part_version, status, created_at, updated_at) " +
                    "VALUES (:cid, :p, 1, true, :sq, :code, 2000, 'ACTIVE', NOW(), NOW())")
                .setParameter("cid", customerId)
                .setParameter("p", hfPartNo)
                .setParameter("sq", seq++)
                .setParameter("code", code)
                .executeUpdate();
        }
    }

    // T20: resolvePart + validateCustomPart + 落库辅助 — 完成

    // ───────────────────────────────────────────────────────────────────────
    // T21: configure 主入口 + 组合产品 + buildLineItems
    // ───────────────────────────────────────────────────────────────────────

    @jakarta.transaction.Transactional
    public ConfigureProductResponse configure(UUID quotationId,
                                              ConfigureProductRequest req,
                                              UUID operatorId) {
        validateRequest(req);

        // P4 批2 补丁: 从 quotation 拉 customer_id，传给 resolvePart → insertProcesses
        UUID customerId = getCustomerIdFromQuotation(quotationId);

        List<String> childHfPartNos = new ArrayList<>();
        List<String> reused = new ArrayList<>();

        // PASS 1: 解析每个配件
        for (PartRequest pr : req.parts) {
            childHfPartNos.add(resolvePart(pr, operatorId, customerId, reused));
        }

        // PASS 2: 组合产品父级
        String parentHfPartNo = null;
        if ("COMPOSITE".equals(req.productType)) {
            String fp = fingerprintCalc.compositeFingerprint(childHfPartNos);
            parentHfPartNo = lookupHfByFingerprint(fp);
            if (parentHfPartNo == null) {
                parentHfPartNo = partNoProvider.apply(
                    new PartNoContext("COMBO", "COMPOSITE", operatorId));
                // COMPOSITE 父行: material_recipe_id = NULL, unit_weight = NULL
                insertMatPart(parentHfPartNo, "COMPOSITE", fp, null, null);
                insertAssemblyBom(parentHfPartNo, childHfPartNos);
                if (req.compositeProcesses != null && !req.compositeProcesses.isEmpty()) {
                    insertCompositeProcesses(parentHfPartNo, req.compositeProcesses,
                        childHfPartNos, operatorId);
                }
            } else {
                reused.add(parentHfPartNo);
            }
        }

        // PASS 3: line_items
        List<Map<String, Object>> lineItems =
            buildLineItems(quotationId, req, parentHfPartNo, childHfPartNos);

        ConfigureProductResponse resp = new ConfigureProductResponse();
        resp.lineItems = lineItems;
        resp.fingerprintMatched = !reused.isEmpty();
        resp.reusedHfPartNos = reused;
        return resp;
    }

    /**
     * 从 quotation 表获取 customer_id.
     * configure 流程需要 customerId 用于 mat_process 插入 (customer_id NOT NULL 约束).
     */
    @SuppressWarnings("unchecked")
    private UUID getCustomerIdFromQuotation(UUID quotationId) {
        List<Object> rows = em.createNativeQuery(
                "SELECT customer_id FROM quotation WHERE id = :q")
            .setParameter("q", quotationId)
            .getResultList();
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("quotation 不存在: " + quotationId);
        }
        Object cid = rows.get(0);
        return cid == null ? null : UUID.fromString(cid.toString());
    }

    void validateRequest(ConfigureProductRequest req) {
        if (req == null) throw new IllegalArgumentException("request body 必填");
        if (!"SIMPLE".equals(req.productType) && !"COMPOSITE".equals(req.productType)) {
            throw new IllegalArgumentException("productType must be SIMPLE or COMPOSITE");
        }
        if (req.parts == null || req.parts.isEmpty()) {
            throw new IllegalArgumentException("parts 必填");
        }
        if ("SIMPLE".equals(req.productType) && req.parts.size() != 1) {
            throw new IllegalArgumentException("SIMPLE 时 parts.size = 1");
        }
        if ("COMPOSITE".equals(req.productType)) {
            if (req.parts.size() < 2 || req.parts.size() > 8) {
                throw new IllegalArgumentException("COMPOSITE 时 parts.size ∈ [2,8]");
            }
            if (req.compositeProcesses != null) {
                for (com.cpq.configure.dto.CompositeProcessRequest cp : req.compositeProcesses) {
                    if (cp.participatingPartIndexes == null
                            || cp.participatingPartIndexes.size() < 2) {
                        throw new IllegalArgumentException(
                            "组合工艺参与配件 < 2: " + cp.defCode);
                    }
                }
            }
        }
    }

    void insertAssemblyBom(String parentHfPartNo, List<String> childHfPartNos) {
        // mat_bom ASSEMBLY 行: child_part_no stores child part_no (V168 added column)
        // no is_current column in mat_bom
        int seq = 1;
        for (String childPn : childHfPartNos) {
            em.createNativeQuery(
                    "INSERT INTO mat_bom (hf_part_no, bom_type, seq_no, child_part_no, " +
                    "composition_pct, part_version, created_at) " +
                    "VALUES (:p, 'ASSEMBLY', :sq, :c, 100.00, 2000, NOW())")
                .setParameter("p", parentHfPartNo)
                .setParameter("sq", seq++)
                .setParameter("c", childPn)
                .executeUpdate();
        }
    }

    void insertCompositeProcesses(String parentHfPartNo,
                                  List<com.cpq.configure.dto.CompositeProcessRequest> cps,
                                  List<String> childHfPartNos,
                                  UUID operatorId) {
        int seq = 1;
        com.fasterxml.jackson.databind.ObjectMapper om =
            new com.fasterxml.jackson.databind.ObjectMapper();
        for (com.cpq.configure.dto.CompositeProcessRequest cp : cps) {
            // validate def exists and is ACTIVE
            com.cpq.configure.entity.CompositeProcessDef.findByCodeOrThrow(cp.defCode);
            List<String> partsInvolved = cp.participatingPartIndexes.stream()
                .map(childHfPartNos::get)
                .collect(Collectors.toList());
            try {
                // hf_part_no column (V166 renamed from parent_hf_part_no)
                em.createNativeQuery(
                        "INSERT INTO mat_composite_process " +
                        "(hf_part_no, def_code, seq_no, participating_parts, " +
                        "param_values, part_version, is_current, created_at, created_by) " +
                        "VALUES (:p, :d, :sq, CAST(:pp AS jsonb), CAST(:pv AS jsonb), " +
                        "2000, true, NOW(), :op)")
                    .setParameter("p", parentHfPartNo)
                    .setParameter("d", cp.defCode)
                    .setParameter("sq", seq++)
                    .setParameter("pp", om.writeValueAsString(partsInvolved))
                    .setParameter("pv", om.writeValueAsString(
                        cp.params == null ? new HashMap<String, Object>() : cp.params))
                    .setParameter("op", operatorId)
                    .executeUpdate();
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new RuntimeException("JSON 序列化失败", ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> buildLineItems(UUID quotationId,
                                             ConfigureProductRequest req,
                                             String parentHfPartNo,
                                             List<String> childHfPartNos) {
        List<Map<String, Object>> out = new ArrayList<>();

        if ("SIMPLE".equals(req.productType)) {
            String pn = childHfPartNos.get(0);
            UUID id = insertLineItem(quotationId, pn, null, "SIMPLE");
            out.add(buildLineItemDTO(id, pn, "SIMPLE", null));
            return out;
        }

        // COMPOSITE: 父 + N 子
        UUID parentId = insertLineItem(quotationId, parentHfPartNo, null, "COMPOSITE");
        out.add(buildLineItemDTO(parentId, parentHfPartNo, "COMPOSITE", null));

        for (String childPn : childHfPartNos) {
            UUID childId = insertLineItem(quotationId, childPn, parentId, "PART");
            out.add(buildLineItemDTO(childId, childPn, "PART", parentId));
        }
        return out;
    }

    UUID insertLineItem(UUID quotationId, String hfPartNo,
                        UUID parentLineItemId, String compositeType) {
        // quotation_line_item columns confirmed from migrations:
        //   product_id, template_id: nullable since V30
        //   product_part_no_snapshot: VARCHAR(200) added V30
        //   composite_type: VARCHAR(16) NOT NULL DEFAULT 'SIMPLE' added V169
        //   parent_line_item_id: UUID NULL added V169
        //   part_version_locked: INT NOT NULL DEFAULT 2000 added V155
        //   sort_order: INT DEFAULT 0 (original V11)
        //   quantity: NOT present in any migration — omitted
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item " +
                "(id, quotation_id, product_part_no_snapshot, " +
                "parent_line_item_id, composite_type, sort_order, created_at) " +
                "VALUES (:id, :q, :pn, :pp, :ct, 0, NOW())")
            .setParameter("id", id)
            .setParameter("q", quotationId)
            .setParameter("pn", hfPartNo)
            .setParameter("pp", parentLineItemId)
            .setParameter("ct", compositeType)
            .executeUpdate();
        return id;
    }

    Map<String, Object> buildLineItemDTO(UUID id, String hfPartNo,
                                          String compositeType, UUID parentId) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("productPartNo", hfPartNo);
        m.put("compositeType", compositeType);
        m.put("parentLineItemId", parentId);
        return m;
    }
    // T21: configure 主入口 + 组合产品 + buildLineItems — 完成
}
