package com.cpq.configure.service;

import com.cpq.configure.FingerprintCalculator;
import com.cpq.configure.FingerprintCalculator.ElementInput;
import com.cpq.configure.dto.ConfigureProductRequest;
import com.cpq.configure.dto.ConfigureProductResponse;
import com.cpq.configure.dto.ElementOverride;
import com.cpq.configure.dto.LookupFingerprintRequest;
import com.cpq.configure.dto.LookupFingerprintResponse;
import com.cpq.configure.dto.PartRequest;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import com.cpq.partno.PartNoContext;
import com.cpq.partno.PartNoProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    @Inject
    VersionedV6Writer versionedWriter;

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
            // lookup 端点不关心 processIds 维度, 仍按 2-arg 老形态查询 (兼容老 fingerprint)
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
        // 2026-06-02 指纹权威切 V6：material_master.config_fingerprint 全局唯一（uq_material_master_fingerprint）
        List<Object> rows = em.createNativeQuery(
                "SELECT material_no FROM material_master WHERE config_fingerprint = :fp")
            .setParameter("fp", fp)
            .getResultList();
        return rows.isEmpty() ? null : (String) rows.get(0);
    }

    @SuppressWarnings("unchecked")
    LookupFingerprintResponse.Snapshot buildSnapshot(String hfPartNo) {
        LookupFingerprintResponse.Snapshot s = new LookupFingerprintResponse.Snapshot();

        // unit_weight from material_master (V6, material_no = hfPartNo)
        List<Object> w = em.createNativeQuery(
                "SELECT unit_weight FROM material_master WHERE material_no = :p")
            .setParameter("p", hfPartNo).getResultList();
        s.unitWeightGrams = (w.isEmpty() || w.get(0) == null)
            ? null
            : new BigDecimal(w.get(0).toString());

        // 工序: V6 unit_price（自制加工费，is_current）；DISTINCT ON seq_no 跨客户取工序列表
        List<Object[]> procs = em.createNativeQuery(
                "SELECT DISTINCT ON (seq_no) operation_no, seq_no FROM unit_price " +
                "WHERE finished_material_no = :p AND cost_type = '自制加工费' AND is_current = true ORDER BY seq_no")
            .setParameter("p", hfPartNo).getResultList();
        s.processes = procs.stream().map(row -> {
            Map<String, Object> m = new HashMap<>();
            m.put("processCode", row[0]); // operation_no → processCode
            m.put("seqNo", row[1]);
            return m;
        }).collect(Collectors.toList());

        // 组合工艺: V6 capacity（QUOTE_ASSEMBLY，is_current）；V6 不存 participatingParts/paramValues → 降级 null
        List<Object[]> cprocs = em.createNativeQuery(
                "SELECT process_no, seq_no FROM capacity " +
                "WHERE material_no = :p AND resource_group_no = 'QUOTE_ASSEMBLY' AND is_current = true ORDER BY seq_no")
            .setParameter("p", hfPartNo).getResultList();
        s.compositeProcesses = cprocs.stream().map(row -> {
            Map<String, Object> m = new HashMap<>();
            m.put("defCode", row[0]); // process_no → defCode
            m.put("seqNo", row[1]);
            m.put("participatingParts", null); // V6 capacity 不存此字段，已知降级
            m.put("paramValues", null);        // V6 capacity 不存此字段，已知降级
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
    String resolvePart(PartRequest pr, UUID operatorId, UUID customerId, String customerCode, List<String> reused) {
        if ("existing".equals(pr.partMode)) {
            if (pr.existingHfPartNo == null || pr.existingHfPartNo.isBlank()) {
                throw new IllegalArgumentException("existing 模式 existingHfPartNo 必填");
            }
            // 存在性校验：V6 material_master 优先，V44 mat_part 兜底（修 B-2）。
            // 指纹复用(lookupHfByFingerprint 查 V44 mat_part)命中的历史选配料号可能只在 V44、
            // 尚未回填 V6 → 此前只查 material_master 会误报"料号不存在"。
            @SuppressWarnings("unchecked")
            List<Object[]> v6rows = em.createNativeQuery(
                    "SELECT material_recipe_id, unit_weight FROM material_master WHERE material_no = :p")
                .setParameter("p", pr.existingHfPartNo)
                .getResultList();
            if (v6rows.isEmpty()) {
                // V6 不存在 → 料号不存在（Phase 3 后 material_master 为权威，V44 mat_part 已停写）
                throw new IllegalArgumentException("料号不存在: " + pr.existingHfPartNo);
            }
            // 跨客户复用: V6 材质/元素按 customer_no 存。指纹命中已有料号(前端自动切 partMode=existing)
            // 复用到新客户的报价单时,当前客户名下可能无 element_bom_item/material_bom_item → 材质/元素 Tab 空。
            // (上面的 backfillV6FromV44 仅在 material_master 缺失且 V44 有时才跑,对 V6 原生自定义料号不补。)
            // 这里无条件为当前客户补齐:复制元素行 + 自定义材质料号补自指物料行。幂等。
            backfillV6MaterialsForCustomer(pr.existingHfPartNo, customerCode);
            // existing 模式无 processIds: 老行为, 直接复用物理对象
            if (pr.processIds == null || pr.processIds.isEmpty()) {
                // hotfix: mat_process 按 customer_id 隔离, 新客户复用老料号时本客户 mat_process 0 行
                // → ImplicitJoinRewriter 注入 customer_id 谓词查不到 → 工序 Tab 加载中.
                // 如果当前客户尚无该料号的 mat_process 数据, 从任意已有客户复制一份给当前 customerId.
                if (customerId != null) {
                    backfillProcessesForNewCustomer(pr.existingHfPartNo, customerId);
                }
                return pr.existingHfPartNo;
            }
            // Bug B 修复: existing+processIds 路径按 quotation_line_item_id 隔离工序。
            // 若前端传了 quotationLineItemId，则仅删/写该 lineItem 专属行，不影响其他 lineItem
            // 或主数据（quotation_line_item_id IS NULL）的工序。
            // 老路径兼容：quotationLineItemId = null → 仅删/写当前 customer 的主数据行（原有行为）。
            // V6 unit_price 版本化写入（覆盖当前 customer 工序）
            // per-lineItem 工序渲染由 insertQuotationLineProcesses 负责，加工费由 unit_price 视图提供
            insertProcessSimpleUnitPriceV6(pr.existingHfPartNo, pr.processIds, customerCode);
            // 仍返老 hfPartNo, 卡片显示用户选的料号
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
        // 料号身份 = 材质 + 元素（不含工序）：工序已改 per-quote(quotation_line_process)，
        // 同材质+元素不同工序 = 同一料号。与 lookup-fingerprint(2参)一致，避免 P2 命中、提交却不复用。
        String fp = fingerprintCalc.simpleFingerprint(pr.recipeCode, elems);

        // Phase 3 后：material_master 为指纹权威（V44 写入已停止，lookupHfByFingerprint 直接查 V6）
        com.cpq.configure.entity.MaterialRecipe recipe =
            com.cpq.configure.entity.MaterialRecipe.findByCodeOrThrow(pr.recipeCode);
        String existing = lookupHfByFingerprint(fp);
        String hfPartNo;
        if (existing != null) {
            reused.add(existing);
            hfPartNo = existing;
        } else {
            // 未命中指纹 → 新建（V6 主写）
            hfPartNo = partNoProvider.apply(
                new PartNoContext(recipe.symbol, "SIMPLE", operatorId));
            // 写 V6 unit_price 工序 — 需要 customerCode (NOT NULL)
            if (pr.processIds != null && !pr.processIds.isEmpty()) {
                if (customerCode == null || customerCode.isBlank()) {
                    throw new IllegalArgumentException(
                        "选配 custom 配件含 processIds 但 quotation 无 customerCode");
                }
                insertProcessSimpleUnitPriceV6(hfPartNo, pr.processIds, customerCode);
            }
        }

        // V6 双写（AP-53 续 6 Phase 1）：无论新建/复用都确保 material_master + element_bom_item 有本料号，
        // 让 composite_child_elements_mirror 视图渲染元素含量（渲染基线零改）。幂等 ON CONFLICT DO NOTHING —
        // 复用的历史料号（V44-only）借此补齐 V6，否则渲染仍空。
        insertMaterialMasterV6(hfPartNo, recipe.symbol, pr.unitWeightGrams, recipe.id, fp);
        insertElementBomV6(hfPartNo, customerCode, pr.elements);
        // [选配-材质] mirror(composite_child_materials_mirror)读 material_bom_item
        // (characteristic IS NULL + customer_no + 父料号)。自定义材质料号无 BOM 物料行 → 材质 Tab 空。
        // 补一行"自指物料行",让 mirror 返 1 行 =「选中的材质本身」(material_name 列=component_usage_type
        // =recipe.symbol,如 AgSnO₂),与有料号产品同一组件/同一视图 SQL/同一按行快照存储 → 渲染一致。
        insertMaterialBomItemV6(hfPartNo, customerCode, recipe.symbol);

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

    // insertMatPart 已在 Phase 3 移除（V44 mat_part 写入停用）

    /**
     * Phase 3 切 V6：existing 路径新客户复用老料号时，确保当前客户在 unit_price 中有工序数据。
     *
     * <p>unit_price 按 customer_no（客户编码字符串）隔离，新客户首次复用 → 该客户名下无工序行
     * → 工序 Tab 空。幂等：当前客户已有 is_current=true 行时跳过。
     * 无数据时从该料号任一现有客户复制工序（operation_no/seq_no/currency/unit），
     * 写成当前客户的新版本（writeVersionedGroup）。
     */
    @SuppressWarnings("unchecked")
    void backfillProcessesForNewCustomer(String hfPartNo, java.util.UUID currentCustomerId) {
        // 把 currentCustomerId(UUID) 转成 customer_no（unit_price 用 code 字符串）
        List<Object> cc = em.createNativeQuery(
                "SELECT code FROM customer WHERE id = :id")
            .setParameter("id", currentCustomerId).getResultList();
        if (cc.isEmpty() || cc.get(0) == null) return;
        String currentCustomerCode = cc.get(0).toString();

        // 已有则跳过
        Object existsObj = em.createNativeQuery(
                "SELECT 1 FROM unit_price WHERE finished_material_no = :p AND customer_no = :c " +
                "AND cost_type = '自制加工费' AND is_current = true LIMIT 1")
            .setParameter("p", hfPartNo).setParameter("c", currentCustomerCode)
            .getResultStream().findFirst().orElse(null);
        if (existsObj != null) return;

        // 取该料号任一已有客户的当前工序（按最新版本），复制成 currentCustomerCode
        List<Object[]> src = em.createNativeQuery(
                "SELECT operation_no, seq_no, currency, unit FROM unit_price " +
                "WHERE finished_material_no = :p AND cost_type = '自制加工费' AND is_current = true " +
                "  AND customer_no = (SELECT customer_no FROM unit_price WHERE finished_material_no = :p " +
                "     AND cost_type = '自制加工费' AND is_current = true ORDER BY version_no DESC LIMIT 1) " +
                "ORDER BY seq_no").setParameter("p", hfPartNo).getResultList();
        if (src.isEmpty()) return;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : src) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operation_no", r[0]);
            m.put("seq_no", r[1]);
            m.put("currency", r[2] != null ? r[2] : "CNY");
            m.put("unit", r[3] != null ? r[3] : "KG");
            rows.add(m);
        }
        Map<String, Object> gk = new LinkedHashMap<>();
        gk.put("system_type", "QUOTE");
        gk.put("price_type", "MATERIAL");
        gk.put("cost_type", "自制加工费");
        gk.put("customer_no", currentCustomerCode);
        gk.put("code", hfPartNo);
        gk.put("finished_material_no", hfPartNo);
        versionedWriter.writeVersionedGroup(new VersionedGroupSpec(
            "unit_price", "version_no", gk,
            List.of("operation_no", "seq_no", "currency", "unit"), rows));
        System.out.printf("[configure backfill] customerCode=%s hfPartNo=%s backfilled %d unit_price rows%n",
                currentCustomerCode, hfPartNo, rows.size());
    }

    // readElementsFromMatBom 和 copyElementBom 已在 Phase 3 移除（V44 mat_bom 死代码）

    // insertElementBom 已在 Phase 3 移除（V44 mat_bom 写入停用）

    // ─────────────────────────────────────────────────────────────────────
    // V6 落库（AP-53 续 6 Phase 1）— 复刻 import 行形状，让现有 mirror 视图零改渲染。
    // id/created_at/updated_at 由 DB 默认 (gen_random_uuid / now)；用 ON CONFLICT DO NOTHING 幂等。
    // customer_no 用 customer.code（mirror 视图按此过滤）。工序/组合工艺承载 = Phase 2。
    // ─────────────────────────────────────────────────────────────────────

    /** V6: 料号身份 → material_master（config_fingerprint 供选配复用，与 V44 同语义）。 */
    void insertMaterialMasterV6(String partNo, String materialType, BigDecimal unitWeight,
                                UUID materialRecipeId, String fingerprint) {
        em.createNativeQuery(
                "INSERT INTO material_master (material_no, material_type, unit_weight, " +
                "material_recipe_id, config_fingerprint) " +
                "VALUES (:mn, :mt, :uw, :mri, :fp) " +
                "ON CONFLICT DO NOTHING")
            .setParameter("mn", partNo)
            .setParameter("mt", materialType)
            .setParameter("uw", unitWeight)
            .setParameter("mri", materialRecipeId)
            .setParameter("fp", fingerprint)
            .executeUpdate();
    }

    /**
     * V6: 元素配比 → element_bom_item。
     * hf_part_no = material_no = 料号本身；characteristic 固定 '2000'（单版本），
     * 让 composite_child_elements_mirror 的 MAX(characteristic) per (customer_no, material_no) 命中本料号。
     */
    void insertElementBomV6(String partNo, String customerCode, List<ElementOverride> elements) {
        if (customerCode == null || customerCode.isBlank()) return; // 无客户无法满足 customer_no NOT NULL / 渲染过滤
        int seq = 1;
        for (ElementOverride eo : elements) {
            em.createNativeQuery(
                    "INSERT INTO element_bom_item (system_type, customer_no, hf_part_no, material_no, " +
                    "characteristic, seq_no, component_no, content) " +
                    "VALUES ('QUOTE', :cn, :p, :p, '2000', :sq, :code, :pct) " +
                    "ON CONFLICT DO NOTHING")
                .setParameter("cn", customerCode)
                .setParameter("p", partNo)
                .setParameter("sq", seq++)
                .setParameter("code", eo.elementCode)
                .setParameter("pct", eo.pct)
                .executeUpdate();
        }
    }

    /**
     * 自定义材质料号补一行 material_bom_item「自指物料行」,使 composite_child_materials_mirror
     * 返回 1 行 =「选中的材质本身」。
     *
     * <p>背景: [选配-材质] mirror 从 material_bom_item(characteristic IS NULL + customer_no + 父料号)
     * 取物料行 join material_master;mirror 的 material_name 列 = COALESCE(component_usage_type,
     * mm.material_type, mm.material_name)。自定义材质料号只写了 material_master + element_bom_item,
     * 无 material_bom_item → 材质 Tab 空(元素含量正常,因其读 element_bom_item)。
     *
     * <p>补的行: material_no=component_no=料号(自指),characteristic=NULL(匹配 mirror),
     * customer_no=客户,component_usage_type=materialType(recipe.symbol,如 AgSnO₂)→ 材质名称列显示该材质。
     * 与有料号产品同一组件 / 同一视图 SQL / 同一按行快照存储 → 渲染一致。
     * 幂等(WHERE NOT EXISTS),复用历史料号也安全。
     */
    void insertMaterialBomItemV6(String partNo, String customerCode, String materialType) {
        if (customerCode == null || customerCode.isBlank()) return; // customer_no NOT NULL + mirror 按 customer 过滤
        em.createNativeQuery(
                "INSERT INTO material_bom_item (id, system_type, customer_no, material_no, characteristic, " +
                "seq_no, component_no, component_usage_type, created_at, updated_at) " +
                "SELECT gen_random_uuid(), 'QUOTE', :cn, :p, NULL, 1, :p, :mt, NOW(), NOW() " +
                "WHERE NOT EXISTS (SELECT 1 FROM material_bom_item " +
                "  WHERE material_no = :p AND customer_no = :cn AND system_type = 'QUOTE' AND characteristic IS NULL)")
            .setParameter("cn", customerCode)
            .setParameter("p", partNo)
            .setParameter("mt", materialType)
            .executeUpdate();
    }

    /**
     * 跨客户复用料号时,为当前报价单客户补齐 V6 材质/元素数据(element_bom_item + material_bom_item)。
     *
     * <p>背景: V6 这两表按 customer_no 存。自定义材质配置走指纹复用时,前端把 partMode 切成 'existing'
     * (ConfigureProductDrawer.reuseExistingPart),后端走 existing 分支;若该料号 material_master 已存在,
     * existing 分支会跳过所有 V6 回填 → 当前客户名下无 element_bom_item/material_bom_item →
     * [选配-材质]/[选配-元素含量] 空(刷新也空)。
     *
     * <p>修法(幂等,当前客户已有则跳过):
     * <ol>
     *   <li>元素: 从任一来源客户复制该料号的 QUOTE 元素行 → 当前客户;</li>
     *   <li>材质: 自定义材质料号(material_master.material_recipe_id 非空)补「自指物料行」,
     *       component_usage_type 取 recipe.symbol(而非可能为脏值 'SIMPLE' 的 material_type)→
     *       mirror 的 material_name 列显示该材质(如 AgNi)一行。</li>
     * </ol>
     */
    void backfillV6MaterialsForCustomer(String partNo, String customerCode) {
        if (customerCode == null || customerCode.isBlank()) return;
        // 1) 元素: 从任一来源客户复制 → 当前客户(当前客户无该料号元素行时整体复制)
        em.createNativeQuery(
                "INSERT INTO element_bom_item (system_type, customer_no, hf_part_no, material_no, characteristic, seq_no, component_no, content) " +
                "SELECT 'QUOTE', :cn, src.hf_part_no, src.material_no, src.characteristic, src.seq_no, src.component_no, src.content " +
                "FROM element_bom_item src " +
                "WHERE src.material_no = :p AND src.system_type = 'QUOTE' " +
                "  AND src.customer_no = (SELECT customer_no FROM element_bom_item WHERE material_no = :p AND system_type = 'QUOTE' ORDER BY created_at LIMIT 1) " +
                "  AND NOT EXISTS (SELECT 1 FROM element_bom_item t WHERE t.material_no = :p AND t.customer_no = :cn AND t.system_type = 'QUOTE')")
            .setParameter("cn", customerCode)
            .setParameter("p", partNo)
            .executeUpdate();
        // 2) 材质: 自定义材质料号补自指物料行(取 recipe.symbol 作 material_name)
        em.createNativeQuery(
                "INSERT INTO material_bom_item (id, system_type, customer_no, material_no, characteristic, seq_no, component_no, component_usage_type, created_at, updated_at) " +
                "SELECT gen_random_uuid(), 'QUOTE', :cn, mm.material_no, NULL, 1, mm.material_no, COALESCE(mr.symbol, mm.material_type), NOW(), NOW() " +
                "FROM material_master mm LEFT JOIN material_recipe mr ON mr.id = mm.material_recipe_id " +
                "WHERE mm.material_no = :p AND mm.material_recipe_id IS NOT NULL " +
                "  AND NOT EXISTS (SELECT 1 FROM material_bom_item t WHERE t.material_no = :p AND t.customer_no = :cn AND t.system_type = 'QUOTE' AND t.characteristic IS NULL)")
            .setParameter("cn", customerCode)
            .setParameter("p", partNo)
            .executeUpdate();
    }

    // backfillV44FromV6 和 backfillV6FromV44 已在 Phase 3 移除（V44 双写桥停用）

    /**
     * V6: 组合子件 → material_bom_item（characteristic='ASSEMBLY'，component_no=子料号）。
     * 让 zcj_bom / composite_child_materials_mirror 渲染子配件清单。
     * composition_qty = 用户在选配「配件数量」步骤填的组成用量（正整数，默认 1）。
     * 指纹复用同一父料号时也更新数量（ON CONFLICT DO UPDATE，匹配 uq_material_bom_item 表达式索引）。
     */
    void insertMaterialBomAssemblyV6(String parentPartNo, String customerCode,
                                     List<String> childPartNos, List<Integer> quantities) {
        if (customerCode == null || customerCode.isBlank()) return;
        int seq = 1;
        for (int i = 0; i < childPartNos.size(); i++) {
            String childPn = childPartNos.get(i);
            int qty = (quantities != null && i < quantities.size() && quantities.get(i) != null
                       && quantities.get(i) >= 1)
                ? quantities.get(i) : 1;
            em.createNativeQuery(
                    "INSERT INTO material_bom_item (system_type, customer_no, material_no, " +
                    "characteristic, seq_no, component_no, composition_qty) " +
                    "VALUES ('QUOTE', :cn, :p, 'ASSEMBLY', :sq, :c, :q) " +
                    "ON CONFLICT (system_type, customer_no, material_no, " +
                    "COALESCE(characteristic, ''), COALESCE(seq_no, 0), " +
                    "COALESCE(component_no, ''), COALESCE(part_no, '')) " +
                    "DO UPDATE SET composition_qty = EXCLUDED.composition_qty")
                .setParameter("cn", customerCode)
                .setParameter("p", parentPartNo)
                .setParameter("sq", seq++)
                .setParameter("c", childPn)
                .setParameter("q", qty)
                .executeUpdate();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // V6 落库 Phase 2（选配 COMBO 补全，设计方案 §6 / 用户方案 B1/B2/B3）
    //   B1 material_bom 主从版本化（ASSEMBLY 子配件 + MATERIAL 各子件材质自指）
    //   B2 工序 → unit_price（自制加工费，按配件分组版本化）
    //   B3 组合工艺 → capacity（QUOTE_ASSEMBLY，按 COMBO 整组版本化）
    // 统一走 VersionedV6Writer：内容相同复用、不同 max+1 升版、is_current 翻转。
    // 渲染 driver 不切（仍读 per-quote / mirror）；本期仅承载 V6 数据。
    // ─────────────────────────────────────────────────────────────────────

    /**
     * B1: COMBO 的 material_bom 主从版本化写入（替代 raw insertMaterialBomAssemblyV6）。
     * 两组主从：
     *   - ASSEMBLY 组：bom_type=ASSEMBLY / 子行 characteristic='ASSEMBLY'，component_no=子料号 + composition_qty；
     *   - MATERIAL 组：bom_type=MATERIAL / 子行 characteristic=NULL，component_no=子料号 + component_usage_type=子件材质名。
     * 主表 material_bom 各补一行（bom_version 2000 起 + is_current）；子表 material_bom_item 升版翻转 + 清残留。
     */
    void writeCombomaterialBomV6(String parentHfPartNo, String customerCode,
                                 List<String> childHfPartNos, List<Integer> childQtys) {
        if (customerCode == null || customerCode.isBlank()
                || childHfPartNos == null || childHfPartNos.isEmpty()) return;

        // ── ASSEMBLY 组：子配件清单
        List<Map<String, Object>> assemblyRows = new ArrayList<>();
        for (int i = 0; i < childHfPartNos.size(); i++) {
            int qty = (childQtys != null && i < childQtys.size() && childQtys.get(i) != null
                       && childQtys.get(i) >= 1) ? childQtys.get(i) : 1;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("seq_no", i + 1);
            r.put("component_no", childHfPartNos.get(i));
            r.put("composition_qty", new BigDecimal(qty));
            assemblyRows.add(r);
        }
        // 主表分组键须含 characteristic='ASSEMBLY'：uq_material_bom_v6 = (system_type, customer_no,
        // material_no, bom_version, COALESCE(characteristic,'')) 不含 bom_type → 仅靠 characteristic 隔离
        // 同一 COMBO 的 MATERIAL(NULL) / ASSEMBLY 两个主表行（对齐 Q12 import 约定），否则两主表行撞唯一键 → 409。
        Map<String, Object> asmMasterGk = bomGroupKey(customerCode, parentHfPartNo, "bom_type", "ASSEMBLY");
        asmMasterGk.put("characteristic", "ASSEMBLY");
        versionedWriter.writeVersionedMasterDetail(
            "material_bom", "bom_version",
            asmMasterGk, null,
            "material_bom_item", "bom_version",
            bomGroupKey(customerCode, parentHfPartNo, "characteristic", "ASSEMBLY"),
            List.of("seq_no", "component_no", "composition_qty"), assemblyRows);

        // ── MATERIAL 组：各子件材质自指（子行 characteristic=NULL，渲染走 materials mirror 的 IS NULL 分支）
        List<Map<String, Object>> materialRows = new ArrayList<>();
        for (int i = 0; i < childHfPartNos.size(); i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("seq_no", i + 1);
            r.put("component_no", childHfPartNos.get(i));
            r.put("component_usage_type", readChildMaterialUsageType(childHfPartNos.get(i), customerCode));
            materialRows.add(r);
        }
        versionedWriter.writeVersionedMasterDetail(
            "material_bom", "bom_version",
            bomGroupKey(customerCode, parentHfPartNo, "bom_type", "MATERIAL"), null,
            "material_bom_item", "bom_version",
            bomGroupKey(customerCode, parentHfPartNo, "characteristic", null),
            List.of("seq_no", "component_no", "component_usage_type"), materialRows);
    }

    /** material_bom / material_bom_item 分组键：QUOTE + customer + material_no + 一个区分列（值允许 null）。 */
    private Map<String, Object> bomGroupKey(String customerCode, String materialNo,
                                            String distinguishCol, Object distinguishVal) {
        Map<String, Object> gk = new LinkedHashMap<>();
        gk.put("system_type", "QUOTE");
        gk.put("customer_no", customerCode);
        gk.put("material_no", materialNo);
        gk.put(distinguishCol, distinguishVal);   // characteristic=NULL 时 writer 用 IS NOT DISTINCT FROM 安全匹配
        return gk;
    }

    /** 读子件自身 is_current 材质自指行 component_usage_type；缺则回退 recipe.symbol / material_type。 */
    @SuppressWarnings("unchecked")
    String readChildMaterialUsageType(String childPartNo, String customerCode) {
        List<Object> r = em.createNativeQuery(
                "SELECT component_usage_type FROM material_bom_item " +
                "WHERE material_no = :p AND customer_no = :cn AND system_type = 'QUOTE' " +
                "  AND characteristic IS NULL AND is_current = true LIMIT 1")
            .setParameter("p", childPartNo).setParameter("cn", customerCode).getResultList();
        if (!r.isEmpty() && r.get(0) != null && !r.get(0).toString().isBlank()) return r.get(0).toString();
        List<Object> r2 = em.createNativeQuery(
                "SELECT COALESCE(mr.symbol, mm.material_type) FROM material_master mm " +
                "LEFT JOIN material_recipe mr ON mr.id = mm.material_recipe_id " +
                "WHERE mm.material_no = :p LIMIT 1")
            .setParameter("p", childPartNo).getResultList();
        return (!r2.isEmpty() && r2.get(0) != null) ? r2.get(0).toString() : null;
    }

    /**
     * B2: 工序 → unit_price（自制加工费）。每个配件一组版本化：
     * 分组键 (system_type=QUOTE, price_type=MATERIAL, cost_type=自制加工费, customer_no, code=配件料号,
     * finished_material_no=COMBO)，行集 = 各工序（operation_no=process.code）。pricing_price 留 NULL（子项3）。
     * currency = process_master.standard_currency（空→CNY）；unit = standard_unit（空→KG，对齐导入存量）。
     */
    @SuppressWarnings("unchecked")
    void insertProcessUnitPriceV6(String parentHfPartNo, String customerCode,
                                  List<PartRequest> parts, List<String> childHfPartNos) {
        if (customerCode == null || customerCode.isBlank()) return;
        for (int i = 0; i < childHfPartNos.size(); i++) {
            PartRequest pr = (parts != null && i < parts.size()) ? parts.get(i) : null;
            if (pr == null || pr.processIds == null || pr.processIds.isEmpty()) continue;
            String childPn = childHfPartNos.get(i);
            List<Map<String, Object>> rows = new ArrayList<>();
            int seq = 1;
            for (UUID processId : pr.processIds) {
                List<Object> codes = em.createNativeQuery(
                        "SELECT code FROM process WHERE id = :id")
                    .setParameter("id", processId).getResultList();
                if (codes.isEmpty() || codes.get(0) == null) {
                    throw new IllegalArgumentException("工艺不存在: " + processId);
                }
                String opNo = codes.get(0).toString();
                String currency = "CNY";
                String unit = "KG";
                List<Object[]> pm = em.createNativeQuery(
                        "SELECT standard_currency, standard_unit FROM process_master WHERE process_no = :c")
                    .setParameter("c", opNo).getResultList();
                if (!pm.isEmpty()) {
                    Object[] m = pm.get(0);
                    if (m[0] != null && !m[0].toString().isBlank()) currency = m[0].toString();
                    if (m[1] != null && !m[1].toString().isBlank()) unit = m[1].toString();
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("operation_no", opNo);
                r.put("seq_no", seq++);
                r.put("currency", currency);
                r.put("unit", unit);
                rows.add(r);
            }
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "QUOTE");
            gk.put("price_type", "MATERIAL");
            gk.put("cost_type", "自制加工费");
            gk.put("customer_no", customerCode);
            gk.put("code", childPn);
            gk.put("finished_material_no", parentHfPartNo);
            versionedWriter.writeVersionedGroup(new VersionedGroupSpec(
                "unit_price", "version_no", gk,
                List.of("operation_no", "seq_no", "currency", "unit"), rows));
        }
    }

    /**
     * 2026-06-02 缺口 B：简单料号工序 → V6 unit_price（镜像组合版 insertProcessUnitPriceV6）。
     * 简单料号无父子，group key 的 code = finished_material_no = hfPartNo。
     */
    @SuppressWarnings("unchecked")
    void insertProcessSimpleUnitPriceV6(String hfPartNo, List<UUID> processIds, String customerCode) {
        if (customerCode == null || customerCode.isBlank()) return;
        if (processIds == null || processIds.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>();
        int seq = 1;
        for (UUID processId : processIds) {
            List<Object> codes = em.createNativeQuery(
                    "SELECT code FROM process WHERE id = :id")
                .setParameter("id", processId).getResultList();
            if (codes.isEmpty() || codes.get(0) == null) {
                throw new IllegalArgumentException("工艺不存在: " + processId);
            }
            String opNo = codes.get(0).toString();
            String currency = "CNY";
            String unit = "KG";
            List<Object[]> pm = em.createNativeQuery(
                    "SELECT standard_currency, standard_unit FROM process_master WHERE process_no = :c")
                .setParameter("c", opNo).getResultList();
            if (!pm.isEmpty()) {
                Object[] m = pm.get(0);
                if (m[0] != null && !m[0].toString().isBlank()) currency = m[0].toString();
                if (m[1] != null && !m[1].toString().isBlank()) unit = m[1].toString();
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("operation_no", opNo);
            r.put("seq_no", seq++);
            r.put("currency", currency);
            r.put("unit", unit);
            rows.add(r);
        }
        Map<String, Object> gk = new LinkedHashMap<>();
        gk.put("system_type", "QUOTE");
        gk.put("price_type", "MATERIAL");
        gk.put("cost_type", "自制加工费");
        gk.put("customer_no", customerCode);
        gk.put("code", hfPartNo);
        gk.put("finished_material_no", hfPartNo);
        versionedWriter.writeVersionedGroup(new VersionedGroupSpec(
            "unit_price", "version_no", gk,
            List.of("operation_no", "seq_no", "currency", "unit"), rows));
    }

    /**
     * B3: 组合工艺 → capacity（对标导入 §14 组装加工费）。按 COMBO 整组版本化：
     * 分组键 (material_no=COMBO, resource_group_no=QUOTE_ASSEMBLY)，行集 = 各 def_code。
     * process_no=def_code、process_name=def.name（缺回退 def_code）、production_type=BATCH_FIXED、
     * currency=CNY、fixed_cost 留 NULL（子项3，单价由 INPUT 层维护）。
     */
    @SuppressWarnings("unchecked")
    void insertCompositeProcessCapacityV6(String parentHfPartNo,
                                          List<com.cpq.configure.dto.CompositeProcessRequest> cps) {
        if (parentHfPartNo == null || cps == null || cps.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>();
        int seq = 1;
        for (com.cpq.configure.dto.CompositeProcessRequest cp : cps) {
            List<Object> nm = em.createNativeQuery(
                    "SELECT name FROM composite_process_def WHERE code = :c")
                .setParameter("c", cp.defCode).getResultList();
            String procName = (!nm.isEmpty() && nm.get(0) != null) ? nm.get(0).toString() : cp.defCode;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("process_no", cp.defCode);
            r.put("process_name", procName);
            r.put("production_type", "BATCH_FIXED");
            r.put("currency", "CNY");
            r.put("seq_no", seq++);
            rows.add(r);
        }
        Map<String, Object> gk = new LinkedHashMap<>();
        gk.put("material_no", parentHfPartNo);
        gk.put("resource_group_no", "QUOTE_ASSEMBLY");
        versionedWriter.writeVersionedGroup(new VersionedGroupSpec(
            "capacity", "calc_version", gk,
            List.of("process_no", "process_name", "production_type", "currency", "seq_no"), rows));
    }

    /**
     * per-quote 工序落库（替代共享 material_bom_item 写法）— 把用户选的工序写进报价行专属的
     * {@code quotation_line_process}（line_item_id × process_id），由
     * {@code selopt_line_processes} 视图按 {@code :lineItemId} 过滤渲染到"选配-工序列表"Tab。
     *
     * <p>per-quote 隔离：只影响当前报价行,不混入导入工序,也不影响别的报价单/基础数据。
     * <ul>
     *   <li>每次按 lineItemId 重建（先删后插），支持重新配置覆盖。</li>
     *   <li>process_id 直接用 process 字典 UUID（满足 FK quotation_line_process→process）；
     *       视图侧再 JOIN process_master 取工序中文名。</li>
     *   <li>必须在 line_item 已创建后调用（FK quotation_line_process→quotation_line_item）。</li>
     * </ul>
     * lineItemId 为空（前端未传报价行 id）时跳过：无行维度无法 per-quote 落库。
     */
    void insertQuotationLineProcesses(UUID lineItemId, List<UUID> processIds) {
        if (lineItemId == null) return;
        em.createNativeQuery("DELETE FROM quotation_line_process WHERE line_item_id = :lid")
            .setParameter("lid", lineItemId)
            .executeUpdate();
        if (processIds == null || processIds.isEmpty()) return;
        for (UUID processId : processIds) {
            em.createNativeQuery(
                    "INSERT INTO quotation_line_process (id, line_item_id, process_id) " +
                    "VALUES (gen_random_uuid(), :lid, :pid)")
                .setParameter("lid", lineItemId)
                .setParameter("pid", processId)
                .executeUpdate();
        }
    }

    /**
     * 工具方法: 安全解析 UUID 字符串, 非法或 null 返回 null.
     */
    private static UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
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
        // V6 (AP-53 续 6 Phase 1): V6 BOM 表 customer_no 用 customer.code（非 UUID），派生一次贯穿落库
        String customerCode = getCustomerCodeFromCustomerId(customerId);

        List<String> childHfPartNos = new ArrayList<>();
        List<String> reused = new ArrayList<>();

        // PASS 1: 解析每个配件
        for (PartRequest pr : req.parts) {
            childHfPartNos.add(resolvePart(pr, operatorId, customerId, customerCode, reused));
        }

        // PASS 2: 组合产品父级
        String parentHfPartNo = null;
        if ("COMPOSITE".equals(req.productType)) {
            String fp = fingerprintCalc.compositeFingerprint(childHfPartNos);
            parentHfPartNo = lookupHfByFingerprint(fp);
            if (parentHfPartNo == null) {
                parentHfPartNo = partNoProvider.apply(
                    new PartNoContext("COMBO", "COMPOSITE", operatorId));
                // COMPOSITE 父行: V6 主写，V44 insertMatPart/insertAssemblyBom/insertCompositeProcesses 已移除
            } else {
                reused.add(parentHfPartNo);
            }
            // V6 双写（AP-53 续 6 Phase 1）：无论新建/复用都确保父料号 + 子件 ASSEMBLY → material_master / material_bom_item，
            // 让 zcj_bom / composite_child_materials_mirror 视图渲染子配件清单（渲染基线零改）。幂等 ON CONFLICT（material_master DO NOTHING / material_bom_item DO UPDATE composition_qty）。
            insertMaterialMasterV6(parentHfPartNo, "COMPOSITE", null, null, fp);
            List<Integer> childQtys = req.parts.stream()
                .map(pr -> (pr.quantity == null || pr.quantity < 1) ? 1 : pr.quantity)
                .collect(Collectors.toList());
            // V6 落库 Phase 2（选配 COMBO 补全，设计 §6 / 用户方案 B1/B2/B3）：统一走 VersionedV6Writer
            // (内容相同复用 / 不同 max+1 升版 / is_current 翻转)。替换 raw insertMaterialBomAssemblyV6。
            writeCombomaterialBomV6(parentHfPartNo, customerCode, childHfPartNos, childQtys);
            insertProcessUnitPriceV6(parentHfPartNo, customerCode, req.parts, childHfPartNos);
            insertCompositeProcessCapacityV6(parentHfPartNo, req.compositeProcesses);
        }

        // PASS 3: line_items (解法 B: 传 req.tempId 给 buildLineItems 作 parent line item id)
        UUID tempId = parseUuidOrNull(req.tempId);
        List<Map<String, Object>> lineItems =
            buildLineItems(quotationId, req, parentHfPartNo, childHfPartNos, tempId);

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

    /**
     * V6 (AP-53 续 6 Phase 1): 由 customer_id(UUID) 取 customer.code。
     * V6 BOM 表（material_bom_item / element_bom_item）的 customer_no 用 code 而非 UUID，
     * 且渲染 mirror 视图按 customer_no = :customerCode 过滤。
     */
    @SuppressWarnings("unchecked")
    private String getCustomerCodeFromCustomerId(UUID customerId) {
        if (customerId == null) return null;
        List<Object> rows = em.createNativeQuery(
                "SELECT code FROM customer WHERE id = :c")
            .setParameter("c", customerId)
            .getResultList();
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
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

    // insertAssemblyBom 已在 Phase 3 移除（V44 mat_bom ASSEMBLY 写入停用）
    // insertCompositeProcesses 已在 Phase 3 移除（V44 mat_composite_process 写入停用）

    /**
     * per-quote 组合工艺写入(取代 mat_composite_process 作渲染源)。
     * 把 configure 请求里"参与配件下标"解析成子件料号,写进 quotation_line_composite_process
     * (按 line_item_id 隔离),并返回解析后的步骤列表 —— 供配置响应带回前端,使 saveDraft
     * 全量重建(换 line id)后能从 draft payload 重写,跨保存存活(同 quotation_line_process 机制)。
     */
    List<Map<String, Object>> insertCompositeProcessesPerQuote(
            UUID lineItemId,
            List<com.cpq.configure.dto.CompositeProcessRequest> cps,
            List<String> childHfPartNos) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (lineItemId == null || cps == null || cps.isEmpty()) return out;
        com.fasterxml.jackson.databind.ObjectMapper om =
            new com.fasterxml.jackson.databind.ObjectMapper();
        int seq = 1;
        for (com.cpq.configure.dto.CompositeProcessRequest cp : cps) {
            com.cpq.configure.entity.CompositeProcessDef.findByCodeOrThrow(cp.defCode);
            List<String> partsInvolved = cp.participatingPartIndexes.stream()
                .map(childHfPartNos::get)
                .collect(Collectors.toList());
            Map<String, Object> params = cp.params == null ? new HashMap<>() : cp.params;
            int thisSeq = seq++;
            try {
                em.createNativeQuery(
                        "INSERT INTO quotation_line_composite_process " +
                        "(line_item_id, def_code, seq_no, participating_parts, param_values) " +
                        "VALUES (:lid, :d, :sq, CAST(:pp AS jsonb), CAST(:pv AS jsonb))")
                    .setParameter("lid", lineItemId)
                    .setParameter("d", cp.defCode)
                    .setParameter("sq", thisSeq)
                    .setParameter("pp", om.writeValueAsString(partsInvolved))
                    .setParameter("pv", om.writeValueAsString(params))
                    .executeUpdate();
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new RuntimeException("JSON 序列化失败", ex);
            }
            Map<String, Object> dto = new HashMap<>();
            dto.put("defCode", cp.defCode);
            dto.put("seqNo", thisSeq);
            dto.put("participatingParts", partsInvolved);
            dto.put("paramValues", params);
            out.add(dto);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> buildLineItems(UUID quotationId,
                                             ConfigureProductRequest req,
                                             String parentHfPartNo,
                                             List<String> childHfPartNos) {
        return buildLineItems(quotationId, req, parentHfPartNo, childHfPartNos, null);
    }

    /**
     * 解法 B: 重载版本，支持前端传入 tempId 作为主 line item UUID。
     * SIMPLE: tempId = 该唯一 line item 的 id；
     * COMPOSITE: tempId = 父 line item 的 id，子 line item 仍自动生成。
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> buildLineItems(UUID quotationId,
                                             ConfigureProductRequest req,
                                             String parentHfPartNo,
                                             List<String> childHfPartNos,
                                             UUID tempId) {
        List<Map<String, Object>> out = new ArrayList<>();

        if ("SIMPLE".equals(req.productType)) {
            String pn = childHfPartNos.get(0);
            UUID id = insertLineItem(quotationId, pn, null, "SIMPLE", tempId);
            // per-quote 工序：选配工序写报价行专属 quotation_line_process（行已建，满足 FK）
            PartRequest simplePr = (req.parts != null && !req.parts.isEmpty()) ? req.parts.get(0) : null;
            insertQuotationLineProcesses(id, simplePr != null ? simplePr.processIds : null);
            out.add(buildLineItemDTO(id, pn, "SIMPLE", null, simplePr != null ? simplePr.processIds : null));
            return out;
        }

        // COMPOSITE: 父 + N 子 (父用 tempId; 子 line item 自动生成，
        // 各子件的 quotationLineItemId 通过 PartRequest.quotationLineItemId 传入)
        UUID parentId = insertLineItem(quotationId, parentHfPartNo, null, "COMPOSITE", tempId);
        // per-quote 组合工艺:写本报价行专属表(取代 mat_composite_process 作渲染源),并把解析后的
        // 工艺步骤带回父行 DTO,供前端透传到 saveDraft 跨保存存活(全量重建换 line id 后重写)。
        Map<String, Object> parentDto = buildLineItemDTO(parentId, parentHfPartNo, "COMPOSITE", null);
        List<Map<String, Object>> cprocs =
            insertCompositeProcessesPerQuote(parentId, req.compositeProcesses, childHfPartNos);
        parentDto.put("compositeProcesses", cprocs);
        out.add(parentDto);

        for (int i = 0; i < childHfPartNos.size(); i++) {
            String childPn = childHfPartNos.get(i);
            // 子件 line item: 优先用对应 PartRequest.quotationLineItemId 作子 id（前端可选传）
            PartRequest childPr = (req.parts != null && i < req.parts.size()) ? req.parts.get(i) : null;
            UUID childTempId = (childPr != null) ? parseUuidOrNull(childPr.quotationLineItemId) : null;
            UUID childId = insertLineItem(quotationId, childPn, parentId, "PART", childTempId);
            // per-quote 工序：子件行的选配工序写 quotation_line_process
            insertQuotationLineProcesses(childId, childPr != null ? childPr.processIds : null);
            out.add(buildLineItemDTO(childId, childPn, "PART", parentId, childPr != null ? childPr.processIds : null));
        }
        return out;
    }

    UUID insertLineItem(UUID quotationId, String hfPartNo,
                        UUID parentLineItemId, String compositeType) {
        return insertLineItem(quotationId, hfPartNo, parentLineItemId, compositeType, null);
    }

    /**
     * 解法 B: 支持前端传入 tempId 作为 line_item.id，使前端提交前即知道 id 值，
     * 无需二次 id 映射。若 tempId 为 null，退回 UUID.randomUUID() 生成行为（向后兼容）。
     *
     * <p>quotation_line_item columns confirmed from migrations:
     * <ul>
     *   <li>product_id, template_id: nullable since V30</li>
     *   <li>product_part_no_snapshot: VARCHAR(200) added V30</li>
     *   <li>composite_type: VARCHAR(16) NOT NULL DEFAULT 'SIMPLE' added V169</li>
     *   <li>parent_line_item_id: UUID NULL added V169</li>
     *   <li>part_version_locked: INT NOT NULL DEFAULT 2000 added V155</li>
     *   <li>sort_order: INT DEFAULT 0 (original V11)</li>
     *   <li>quantity: NOT present in any migration — omitted</li>
     * </ul>
     */
    UUID insertLineItem(UUID quotationId, String hfPartNo,
                        UUID parentLineItemId, String compositeType, UUID tempId) {
        UUID id = (tempId != null) ? tempId : UUID.randomUUID();
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
        return buildLineItemDTO(id, hfPartNo, compositeType, parentId, null);
    }

    Map<String, Object> buildLineItemDTO(UUID id, String hfPartNo,
                                          String compositeType, UUID parentId, List<UUID> processIds) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("productPartNo", hfPartNo);
        m.put("compositeType", compositeType);
        m.put("parentLineItemId", parentId);
        // 选配工序回传前端,使其能在 saveDraft 回写 quotation_line_process(工序跨保存存活)
        m.put("processIds", processIds != null ? processIds : java.util.List.of());
        return m;
    }
    // T21: configure 主入口 + 组合产品 + buildLineItems — 完成
}
