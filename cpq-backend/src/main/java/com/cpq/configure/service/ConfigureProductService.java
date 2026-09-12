package com.cpq.configure.service;

import com.cpq.basicdata.v6.BomCharacteristic;
import com.cpq.configure.SalesFingerprintCalculator;
import com.cpq.configure.SalesFingerprintCalculator.ElementPct;
import com.cpq.configure.SalesFingerprintCalculator.EnabledParam;
import com.cpq.configure.dto.ConfigureProductRequest;
import com.cpq.configure.dto.ConfigureProductResponse;
import com.cpq.configure.dto.ElementOverride;
import com.cpq.configure.dto.LookupFingerprintRequest;
import com.cpq.configure.dto.LookupFingerprintResponse;
import com.cpq.configure.dto.MaterialSelection;
import com.cpq.configure.dto.PartRequest;
import com.cpq.configure.dto.ReusedProductInfoDTO;
import com.cpq.configure.dto.SalesConfigContext;
import com.cpq.partno.PartNoContext;
import com.cpq.partno.PartNoProvider;
import com.cpq.seltemplate.dto.EffectiveTemplateDTO;
import com.cpq.seltemplate.service.EffectiveTemplateService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *       product_id / template_id 在 V30 已改为 nullable — 选配行填 product_part_no_snapshot 而不填 product_id.
 *       ⚠️ <b>2026-09-10 更正（task-260910 · D-38，修 BL-0202）</b>：本行原文曾被读成
 *       「选配行有意不挂模板」——<b>那是误读</b>。template_id <b>现在必须写</b>
 *       （值 = 本报价单的 customer_template_id），否则选配产品卡<b>刷新后</b>渲染不出组件结构。
 *       理由与四条证据见 {@link #insertLineItem(UUID, String, UUID, String, UUID)} 的 javadoc.</li>
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

    private static final Logger LOG = Logger.getLogger(ConfigureProductService.class);

    @Inject
    EntityManager em;

    @Inject
    PartNoProvider partNoProvider;

    /** task-260903 · 阶段 A：选配产出写 {@code ds_quote_*} 新表体系（取代下面那组 {@code *V6} 方法）。 */
    @Inject
    SelDsQuoteWriter dsWriter;

    /**
     * 操作人 UUID → {@code ds_quote_*.created_by/updated_by}（{@code varchar(64)}）。
     * 🚫 不能用 {@code String.valueOf} —— 它把 null 变成字面量 "null" 存进库。
     */
    private static String opOf(UUID operatorId) {
        return operatorId == null ? null : operatorId.toString();
    }

    /**
     * 选配 Plan 3b (T3): 有效模板解析服务 — buildSalesConfigContext 用于载入
     * enabled 参数类型集 (PROCESS 是否作为槽位), 与 T2 SalesFingerprintCalculator 配合
     * 组装客户维度指纹上下文。与生产侧发号逻辑互不影响 (T4/T5 消费, 本 Task 只装配)。
     */
    @Inject
    EffectiveTemplateService effectiveTemplateService;

    /**
     * 选配 Plan 3b (T4): 销售侧客户维度发号 — 取代生产侧全局指纹发号
     * (lookupHfByFingerprint + partNoProvider) 用于 custom SIMPLE 配件。
     */
    @Inject
    com.cpq.basicdata.v6.service.QuoteMaterialNoAllocator quoteAllocator;

    @Inject
    SalesFingerprintCalculator salesFp;

    @Inject
    com.cpq.configure.service.SalesSignatureRepository sigRepo;

    /** task-260901 · B-17：材质含量配置的读取底座（发号/校验/元素行读写都在它那儿）。 */
    @Inject
    MaterialRecipeConfigService materialRecipeConfigService;

    // ───────────────────────────────────────────────────────────────────────
    // T19 → task-0712 缺口2(3a): lookup-fingerprint 端点
    // ───────────────────────────────────────────────────────────────────────

    /**
     * 抽屉 P2 完成时调用 — 算<b>销售侧客户维度指纹</b>查 {@code sel_part_signature}, 命中则返回已有
     * 报价料号 + 快照,未命中返回 matched=false. 还原原型"确认前实时🆕新建/✅命中 SP-xxxx"。
     *
     * <p><b>task-0712 缺口2(3a) 定稿</b>：取代 T19 遗留的「查生产侧全局指纹 {@code
     * material_master.config_fingerprint}」桩实现（3b 后选配落库该列恒为 NULL，桩对新选配恒返
     * matched=false，见本方法历史 TODO(3a)）。3a 起改查与提交端 {@code configure() → resolvePart}
     * <b>完全同源</b>的销售侧客户维度指纹（{@link SalesFingerprintCalculator} + {@link
     * SalesSignatureRepository}），保证「预览命中」= 「提交命中」，不再产生误导性的恒 false。
     *
     * <p><b>入参形态对齐提交端</b> {@link ConfigureProductRequest}：{@code customerNo + parts +
     * compositeProcesses}，复用 {@link #projectEnabledParams} 投影 + {@link #effectiveEnabledTypes}
     * 客户模板 enabled 集判定，与 {@link #buildSalesConfigContext} 同一套逻辑（非重造）。
     *
     * <p><b>SIMPLE/COMPOSITE 判定</b>：与 {@link #validateRequest} 同口径 —— Σ{@code
     * parts[].quantity}（null/&lt;1 兜底 1）＝1 时 SIMPLE，否则 COMPOSITE。
     *
     * <p><b>COMPOSITE 必须无副作用</b>（不可 mint 料号）：先对每个子件按 partMode 分别求「若提交会
     * 得到的料号」——
     * <ul>
     *   <li>{@code existing} 子件：已知存在，直接取 {@code existingHfPartNo}（与 {@link #resolvePart}
     *       existing 分支同语义：existing 从不参与销售指纹计算，直接复用用户选中的料号）；</li>
     *   <li>{@code custom} 子件：算其 SIMPLE 销售指纹 → {@code sigRepo.lookup} <b>只查不铸</b>
     *       （不调用 {@code quoteAllocator.mintAndRegister} / {@code sigRepo.insertOrReadExisting}）。
     *       未命中 = 提交时会新建该子件 → 整个组合父级必是新组合（父指纹里会出现一个之前不存在的
     *       子件号）→ <b>直接早退 matched=false</b>，不再往下算父指纹（无需查，父级一定新建）。</li>
     * </ul>
     * 全部子件都解析出「已存在的料号」后，才用这些料号 + 装配数量 + 组合工艺组父级指纹查
     * {@code sel_part_signature}（同 {@link #configure} PASS 2 的父级判复用算法）。
     *
     * <p><b>事务</b>：全程只读（{@code sigRepo.lookup} 为纯 SELECT，非 {@code
     * insertOrReadExisting}）；显式 {@code @Transactional} 仅为保证 EntityManager/Panache 查询
     * 在有效事务上下文中执行，不产生任何写操作。
     */
    @jakarta.transaction.Transactional
    public LookupFingerprintResponse lookupFingerprint(LookupFingerprintRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body 必填");
        }
        if (req.customerNo == null || req.customerNo.isBlank()) {
            throw new IllegalArgumentException("lookup-fingerprint: customerNo 必填(3a 销售侧客户维度指纹预览)");
        }
        if (req.parts == null || req.parts.isEmpty()) {
            throw new IllegalArgumentException("lookup-fingerprint: parts 必填");
        }

        // task-260901 · B-17（task-260902 起下沉到 material 级）：预览端与提交端必须用同一份
        // 已解析的 elements 算指纹，否则「预览命中」≠「提交命中」，回到 3a 之前那种误导性的恒 false。
        List<String> lfDefCodes = req.compositeProcesses == null ? List.of()
            : req.compositeProcesses.stream().map(cp -> cp.defCode).collect(Collectors.toList());
        prepareParts(req.customerNo, req.parts, lfDefCodes, false);   // 预览端：不强制总重（见方法注释）

        int totalQty = req.parts.stream()
            .mapToInt(pr -> (pr.quantity == null || pr.quantity < 1) ? 1 : pr.quantity)
            .sum();
        boolean isComposite = totalQty >= 2;

        Set<String> enabledTypes = effectiveEnabledTypes(req.customerNo);
        LookupFingerprintResponse resp = new LookupFingerprintResponse();

        if (!isComposite) {
            String matched = lookupResolvedPartNo(req.customerNo, req.parts.get(0), enabledTypes);
            if (matched == null) {
                resp.matched = false;
                return resp;
            }
            resp.matched = true;
            resp.hfPartNo = matched;
            resp.matchedPartNo = matched;
            resp.snapshot = buildSnapshot(req.customerNo, matched);
            return resp;
        }

        // COMPOSITE：逐子件只查不铸；任一子件未命中 = 提交时将新建该子件 → 组合体必新建，早退。
        List<String> childQuotePartNos = new ArrayList<>();
        List<Integer> childQtys = new ArrayList<>();
        for (PartRequest pr : req.parts) {
            String childPn = lookupResolvedPartNo(req.customerNo, pr, enabledTypes);
            if (childPn == null) {
                resp.matched = false;
                return resp;
            }
            childQuotePartNos.add(childPn);
            childQtys.add((pr.quantity == null || pr.quantity < 1) ? 1 : pr.quantity);
        }

        List<String> compositeProcessCodes = req.compositeProcesses == null ? List.of()
            : req.compositeProcesses.stream().map(cp -> cp.defCode).collect(Collectors.toList());
        var parentSig = salesFp.computeComposite(req.customerNo, childQuotePartNos, childQtys, compositeProcessCodes);
        String parentHit = sigRepo.lookup(req.customerNo, SalesFingerprintCalculator.STRUCTURE_VERSION, parentSig.hash());
        if (parentHit == null) {
            resp.matched = false;
            return resp;
        }
        resp.matched = true;
        resp.hfPartNo = parentHit;
        resp.matchedPartNo = parentHit;
        resp.snapshot = buildSnapshot(req.customerNo, parentHit);
        return resp;
    }

    /**
     * 单个配件「若提交会得到的料号」的只读解析 —— {@link #resolvePart} 的无副作用镜像版本，
     * 供 {@link #lookupFingerprint} 3a 预览专用。existing 直接取用户选中料号；custom 只算指纹 +
     * 查表，命中返命中号，未命中返回 {@code null}（不 mint、不落库、不登记签名）。
     */
    private String lookupResolvedPartNo(String customerNo, PartRequest pr, Set<String> enabledTypes) {
        // task-260902：外购件与已有零件都不参与销售指纹 —— 直接返回用户选中的既有料号。
        if (pr.isOutsourced()) {
            if (pr.outsourcedPartNo == null || pr.outsourcedPartNo.isBlank()) {
                throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                    "OUTSOURCED_PART_REQUIRED", "外购件必须选择料号");
            }
            return pr.outsourcedPartNo;
        }
        if (pr.isExistingMode()) {
            if (pr.existingHfPartNo == null || pr.existingHfPartNo.isBlank()) {
                throw new IllegalArgumentException("existing 模式 existingHfPartNo 必填");
            }
            return pr.existingHfPartNo;
        }
        if (!pr.isNewMode()) {
            throw new IllegalArgumentException("partMode must be 'existing' or 'new': " + pr.partMode);
        }
        // 材质解析已由入口 prepareParts 完成（预览端与提交端同源，见 task-260901 B-17 不变量）。
        List<EnabledParam> enabledParams = projectEnabledParams(pr, enabledTypes);
        var sig = computeSimpleSignature(customerNo, pr, enabledParams);
        return sigRepo.lookup(customerNo, SalesFingerprintCalculator.STRUCTURE_VERSION, sig.hash());
    }

    /**
     * task-260902 · B-5 / B-21：算 SIMPLE 销售指纹，并把 {@code assertNoDelimiter} 抛出的
     * {@link IllegalArgumentException} 转成 400 {@code PART_TEXT_INVALID_CHAR}。
     *
     * <p>不转的话，用户在品名里打了个冒号就会拿到 500（而不是一条能看懂的校验提示）。
     * 前置的 {@code assertPartText} 已经拦过一道，本处是兜底（客户码等其它入参同样可能触发）。
     */
    private SalesFingerprintCalculator.Signature computeSimpleSignature(
            String customerNo, PartRequest pr, List<EnabledParam> enabledParams) {
        try {
            return salesFp.computeSimple(customerNo, pr.name, pr.spec, pr.dimension,
                pr.unitWeightGrams, enabledParams);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("分隔符")) {
                throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                    "PART_TEXT_INVALID_CHAR", e.getMessage());
            }
            throw e;
        }
    }

    /**
     * <b>task-260910 · B-6（AC-22）</b>：指纹预览快照。
     *
     * <p><b>本次删掉了两段查询</b>（api.md §2.4，实测<b>全前端零消费方</b>，查完即丢）：
     * <ul>
     *   <li>{@code unitWeightGrams} —— 原查 {@code v_compat_material_master.unit_weight}；</li>
     *   <li>{@code compositeProcesses} —— 原查已废弃的 V6 {@code capacity}。</li>
     * </ul>
     * 🚫 不要「顺手补回来」：{@code LookupFingerprintResponse.Snapshot} 的两个字段已同步删除，
     * 补回来会先编译不过。要加就走契约变更（api.md）。
     *
     * <p>{@code processes} 的数据源从 V6 {@code unit_price} 切到 {@code ds_quote_self_process_fee}。
     * ⚠️ 原 SQL 用 {@code DISTINCT ON (seq_no)} <b>跨客户</b>取；新表有 {@code customer_no} 复合轴
     * ⇒ 改为<b>按客户精确取</b>，{@code DISTINCT ON} 不再需要（也不该有：它会随机丢掉同项次的行）。
     *
     * <p>AC-22 的第二条断言（确认页显示已有产品的<b>真实落库顺序</b>）就靠这里的
     * {@code ORDER BY operation_item_seq} —— 落库侧刻意不排序（见
     * {@link #appendSelfProcessFeeRows}），所以这个顺序就是「第一次写入时用户选的顺序」。
     */
    @SuppressWarnings("unchecked")
    LookupFingerprintResponse.Snapshot buildSnapshot(String customerNo, String hfPartNo) {
        LookupFingerprintResponse.Snapshot s = new LookupFingerprintResponse.Snapshot();

        List<Object[]> procs = em.createNativeQuery(
                "SELECT operation_no, operation_item_seq FROM ds_quote_self_process_fee " +
                "WHERE customer_no = :c AND material_no = :p ORDER BY operation_item_seq, item_seq")
            .setParameter("c", customerNo)
            .setParameter("p", hfPartNo).getResultList();
        s.processes = procs.stream().map(row -> {                          // 循环体内零查库（纯 DTO 组装）
            Map<String, Object> m = new HashMap<>();
            m.put("processCode", row[0]); // operation_no → processCode
            m.put("seqNo", row[1]);
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
     *   <li>custom 未命中: 新建 mat_part + mat_bom (ELEMENT N 行) + mat_process (若有 processNos)</li>
     * </ul>
     *
     * <p>注意: mat_part_version_log 基线行需要 customer_product_no (NOT NULL PK 成员),
     * configure 阶段不存在此信息，基线由 per-customer 数据导入流程 (V156/PartVersionService) 写入.
     */
    String resolvePart(PartRequest pr, UUID operatorId, UUID customerId, String customerCode,
                        List<String> reused, SalesConfigContext salesCtx, ConfigureCatalog cat) {

        // ── task-260902 · B-7：外购件（AC-5 / AC-16）──
        // 不铸新号、不进销售指纹：外购件就是料号库里已有的那个料号本身。
        // 落库只补一行「自指物料行」把它标成 characteristic='OUTSOURCED'
        // （实测该值全表 0 行 —— 这是从未落地过的路径，不是「已有但没接」）。
        if (pr.isOutsourced()) {
            String outNo = pr.outsourcedPartNo;
            if (outNo == null || outNo.isBlank()) {
                throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                    "OUTSOURCED_PART_REQUIRED", "外购件必须选择料号");
            }
            String[] meta = cat.outsourcedByNo.get(outNo);
            if (meta == null) {
                throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                    "OUTSOURCED_PART_REQUIRED", "外购件料号不存在: " + outNo);
            }
            // 🆕 task-260903 · A-4 / A-5（A-AC-7）：外购件身份 + 自指物料行改落 ds_quote_*。
            // upsertMaterial 是 ON CONFLICT DO NOTHING —— 外购件料号本就存在于料号库时，
            // 不会反向覆盖导入侧已有的品名/类型。
            // 🆕 A-AC-11（2026-09-04 用户裁决）：新建料号的产品分类默认「默认分类」(000000)。
            //    ⚠️ ON CONFLICT DO NOTHING ⇒ 库里早有的外购件料号不会被回填，这是刻意的
            //    （不让选配顺手改写导入侧的数据），A-AC-11 只约束本流程新建的行。
            // 🆕 task-260907 · B-4：客户号从本方法既有的 customerCode 透传（= customer.code）。
            dsWriter.upsertMaterial(customerCode, outNo, meta[0], null, null, null,
                SelDsQuoteWriter.TYPE_OUTSOURCED, SelDsQuoteWriter.CATEGORY_DEFAULT, opOf(operatorId));
            dsWriter.writeOutsourcedSelfRow(customerCode, outNo, opOf(operatorId));
            // 🆕 task-260910 · B-3（AC-3）：外购件工序改落 ds_quote_assembly_fee（组装加工费）。
            //    🚨 D-6 是**业务计算口径变更**，不是等价搬运：费用类别由「自制加工费」→「组装加工费」。
            //    ⇒ 改回 insertProcessSimpleUnitPriceV6 会把外购件工序算进自制成本，报价金额会错。
            if (pr.processNos != null && !pr.processNos.isEmpty()) {
                insertOutsourcedProcessAssemblyFee(outNo, pr.processNos, customerCode, cat, opOf(operatorId));
            }
            return outNo;
        }

        if (pr.isExistingMode()) {
            if (pr.existingHfPartNo == null || pr.existingHfPartNo.isBlank()) {
                throw new IllegalArgumentException("existing 模式 existingHfPartNo 必填");
            }
            // 🆕 task-260910 · B-10（AC-5 / AC-7）：存在性校验切 ds_quote_material 并按客户收窄（D-2）。
            //    原实现查 v_compat_material_master（无客户维度）⇒ 别客户的料号也算「存在」。
            //    ⚠️ 只取存在性，不取任何列值 —— 原实现 SELECT 的 material_recipe_id / unit_weight
            //    两列从来没被消费（v6rows 只参与 isEmpty() 判断），新表也没有 material_recipe_id。
            @SuppressWarnings("unchecked")
            List<Object> existsRows = em.createNativeQuery(
                    "SELECT 1 FROM ds_quote_material WHERE customer_no = :cn AND material_no = :p LIMIT 1")
                .setParameter("cn", customerCode)
                .setParameter("p", pr.existingHfPartNo)
                .getResultList();
            if (existsRows.isEmpty()) {
                throw new IllegalArgumentException(
                    "料号不存在(客户 " + customerCode + " 名下): " + pr.existingHfPartNo);
            }
            // 跨客户复用: V6 材质/元素按 customer_no 存。指纹命中已有料号(前端自动切 partMode=existing)
            // 复用到新客户的报价单时,当前客户名下可能无 element_bom_item/material_bom_item → 材质/元素 Tab 空。
            // 这里无条件为当前客户补齐:复制元素行 + 复制物料行。幂等。
            backfillV6MaterialsForCustomer(pr.existingHfPartNo, customerCode);
            // existing 模式无 processNos: 老行为, 直接复用物理对象
            if (pr.processNos == null || pr.processNos.isEmpty()) {
                // task-260910 · B-5: ds_quote_self_process_fee 按 customer_no 隔离，新客户复用老料号时
                // 本客户名下 0 行 → 工序页签空。若当前客户尚无该料号的工序数据，
                // 从任意已有客户复制一份给当前客户（🚫 不搬单价，见方法注释）。
                if (customerId != null) {
                    backfillProcessesForNewCustomer(pr.existingHfPartNo, customerId, opOf(operatorId));
                }
                return pr.existingHfPartNo;
            }
            // 🆕 task-260910 · B-1：ds_quote_self_process_fee 版本化写入（覆盖当前 customer 工序）
            // per-lineItem 工序渲染由 insertQuotationLineProcesses 负责，加工费由组件 SQL 直读新表
            insertProcessSimpleUnitPriceV6(pr.existingHfPartNo, pr.processNos, customerCode, cat, opOf(operatorId));
            // 仍返老 hfPartNo, 卡片显示用户选的料号
            return pr.existingHfPartNo;
        }

        if (!pr.isNewMode()) {
            throw new IllegalArgumentException(
                "partMode must be 'existing' or 'new': " + pr.partMode);
        }

        // ── 新建零件（三层：零件 → 材质 1..N）──
        // 材质已在入口 prepareParts 解析完毕（含量物化 + 占比校验），此处不再查库。
        List<MaterialSelection> mats = pr.effectiveMaterials();

        // 选配 Plan 3b (T4): 生产侧全局指纹发号 → 销售侧客户维度指纹发号 swap。
        // R6: 报价料号内嵌客户四位码，无客户码 mintAndRegister 发不了号 — 强制非空。
        if (customerCode == null || customerCode.isBlank()) {
            throw new IllegalArgumentException(
                "选配新建零件需要 customerCode（报价料号内嵌客户码），quotation 无客户不能发号");
        }

        // 销售侧客户维度指纹判复用（v2：含 PART/WEIGHT/多材质占比）
        var sig = computeSimpleSignature(salesCtx.customerNo, pr, salesCtx.enabledParamsFor(pr));
        String hit = sigRepo.lookup(salesCtx.customerNo, SalesFingerprintCalculator.STRUCTURE_VERSION, sig.hash());
        if (hit != null) {
            // R3: 命中复用 → 在任何落库之前 return（同客户同结构，数据首次已落，幂等不重复落库/累加）
            reused.add(hit);
            return hit;
        }

        // ⚠️ 不变量：mintAndRegister + insertOrReadExisting + 下方 V6 落库必须同处 configure 的
        // 同一事务（REQUIRED，勿改 REQUIRES_NEW）——保证「签名可见 ⇔ V6 数据可见」，否则并发败者
        // 复用先赢号时先赢 V6 未提交 → Tab 静默空。
        String hfPartNo = quoteAllocator.mintAndRegister(salesCtx.customerNo, salesCtx.yyMm);
        String registered = sigRepo.insertOrReadExisting(
            salesCtx.customerNo, SalesFingerprintCalculator.STRUCTURE_VERSION, sig.hash(), sig.text(),
            hfPartNo, "SIMPLE");
        if (registered == null) {
            throw new IllegalStateException(
                "sel_part_signature 冲突但回读为空: fp=" + sig.hash());
        }
        if (!registered.equals(hfPartNo)) {
            reused.add(registered);
            return registered; // 并发败者：先赢者已落库，复用其号，跳过本次落库
        }

        // 先赢者：写 ds_quote_self_process_fee 工序 — 需要 customerCode (NOT NULL，上方已校验非空)
        // ⚠️ AC-19③：seq_no 按 processNos 数组原始顺序赋值（不排序）。指纹侧排序、落库侧不排序，
        //    两者有意不对称 —— 换序复用同一料号，但显示顺序沿用第一次写入的那一次。
        if (pr.processNos != null && !pr.processNos.isEmpty()) {
            insertProcessSimpleUnitPriceV6(hfPartNo, pr.processNos, customerCode, cat, opOf(operatorId));
        }

        // V6 双写（AP-53 续 6 Phase 1）：确保 material_master + element_bom_item 有本料号。
        // R1: config_fingerprint 传 null — 客户维度发号后同一 material_master 可能被多个客户各自的
        // 报价料号复用，若沿用生产侧全局指纹会撞 uq_material_master_fingerprint 全局唯一索引 → 500。
        //
        // 🚨 B-9（闸门 A0 裁决）：material_type 归位为**料号类型**，写字面量 '零件'，
        //    不再写 recipe.symbol（材质名）。材质名的权威落点是 material_bom_item.component_usage_type。
        // 🚨 B-18（用户裁决）：material_recipe_id 不再作为料件材质的判据 ——
        //    多材质时写 NULL（材质权威改为 material_bom_item 的 N 行）；
        //    单材质时保留 recipe.id，使 AC-13「与旧的单材质行为等价」成立。
        UUID singleRecipeId = null;
        if (mats.size() == 1) {
            com.cpq.configure.entity.MaterialRecipe only = cat.recipeByCode.get(mats.get(0).recipeCode);
            singleRecipeId = (only == null) ? null : only.id;
        }
        // 🆕 task-260903 · A-1（A-AC-1①）：料号主档改落 ds_quote_material，停写 material_master。
        //    A-5（A-AC-7）：material_type 写「零件」。
        //    ⚠️ singleRecipeId 不再有落点 —— 新表没有 material_recipe_id 列。它在 V6 时代的用途
        //    （单材质料号的材质判据）已被 B-18 用户裁决废除，材质权威是 ds_quote_material_bom 的 N 行。
        //    🆕 A-AC-11（2026-09-04 用户裁决）：category_code 一律写「默认分类」000000。
        dsWriter.upsertMaterial(customerCode, hfPartNo, pr.name, pr.spec, pr.dimension, pr.unitWeightGrams,
            SelDsQuoteWriter.TYPE_PART, SelDsQuoteWriter.CATEGORY_DEFAULT, opOf(operatorId));
        // 🆕 task-260903 · A-2 / A-3 / A-4：物料行与元素行改落 ds_quote_*，停写 V6。
        //
        // 🚨 与 V6 最关键的形态差异：新表的轴**只有 material_no**，没有 characteristic /
        //    customer_no 维度。V6 时代同一料号的不同 characteristic 是各自独立的组，可以分多次写；
        //    这里必须**一次 writeGroup 把整组行全给出**，分两次调 = 第二次把第一次的行当成删除。
        // 📌 A-9（A-AC-5）：新料号在库中不存在 ⇒ writeGroup 走 CREATED 分支，version_no 恒为 1。
        //    这也覆盖了原 A-AC-9「复用路径不调写入器」要防的坏后果 —— 复用在上面 hit 分支就
        //    早退了，根本走不到这里。🚫 后人不要在这里加任何版本号干预。
        dsWriter.writeMaterialBomGroup(customerCode, hfPartNo, buildRecipeBomRows(hfPartNo, mats), opOf(operatorId));
        dsWriter.writeElementBomGroup(customerCode, hfPartNo, buildElementBomRows(hfPartNo, mats), opOf(operatorId));

        // mat_part_version_log 基线行: PK (customer_product_no NOT NULL, hf_part_no, version)
        // configure 阶段无 customer_product_no (客户产品号在数据导入后才存在)
        // 基线由 PartVersionService / V156 在 per-customer 导入流程时写入

        return hfPartNo;
    }

    // ───────────────────────────────────────────────────────────────────────
    // 选配 Plan 3b (T3): SalesConfigContext 装配 — 客户维度 EnabledParam 投影
    // ───────────────────────────────────────────────────────────────────────

    /**
     * 在 configure 入口一次性组装销售侧客户维度上下文，供 T4/T5 消费。
     *
     * <p>customerCode 为空（quotation 未绑定客户）时跳过模板加载，enabledTypes 留空
     * （PROCESS 槽位仅按各 part 自身 processNos 是否非空决定，不影响 MATERIAL/ELEMENT 恒定槽位）。
     */
    private SalesConfigContext buildSalesConfigContext(String customerCode, ConfigureProductRequest req) {
        String yyMm = YearMonth.now().format(DateTimeFormatter.ofPattern("yyMM"));

        Set<String> enabledTypes = effectiveEnabledTypes(customerCode);

        Map<PartRequest, List<EnabledParam>> byPart = new IdentityHashMap<>();
        if (req.parts != null) {
            for (PartRequest pr : req.parts) {
                // existing 模式 / 外购件都不走销售指纹（直接复用既有料号，无需投影）；保留空 List。
                // 🚨 task-260902 · B-1：判定必须走 pr.isNewMode()（同时认 "new" 与老值 "custom"）。
                //    这里若还写死 "custom".equals(...)，前端改发 "new" 之后每个配件都会拿到空投影，
                //    computeSimple 立刻抛「enabled 参数集不能为空（防指纹坍缩）」—— 全链 500。
                if (pr.isOutsourced() || !pr.isNewMode()) {
                    byPart.put(pr, List.of());
                    continue;
                }
                byPart.put(pr, projectEnabledParams(pr, enabledTypes));
            }
        }

        return new SalesConfigContext(customerCode, yyMm, SalesFingerprintCalculator.STRUCTURE_VERSION, byPart);
    }

    /**
     * 客户维度 enabled 参数类型集（{@code sel_param_type.code} 集合）—— 由
     * {@link #buildSalesConfigContext}（提交端 configure）与 {@link #lookupFingerprint}（3a 预览端）
     * 共用，保证两端「PROCESS 是否为槽位」的判定同口径。customerCode 为空（quotation 未绑定客户 /
     * 预览请求未带客户码场景理论不该发生，上游各自校验非空）时返回空集。
     */
    private Set<String> effectiveEnabledTypes(String customerCode) {
        Set<String> enabledTypes = new HashSet<>();
        if (customerCode != null && !customerCode.isBlank()) {
            EffectiveTemplateDTO eff = effectiveTemplateService.getEffective(customerCode);
            for (EffectiveTemplateDTO.Param p : eff.params) {
                enabledTypes.add(p.paramTypeCode);
            }
        }
        return enabledTypes;
    }

    /**
     * 按 PartRequest 投影出该配件的 EnabledParam 集 — 防坍缩核心。
     *
     * <p><b>防坍缩规则</b>: {@link EffectiveTemplateService#getEffective} 对无模板客户返回空
     * params。若严格「仅 enabled 驱动槽位」，空集 → 指纹串仅 {@code v1|CUST=xxx|}
     * → 该客户所有选配坍缩成同一报价料号。故:
     * <ul>
     *   <li><b>MATERIAL 恒为槽位</b>（防坍缩底线）— custom 模式强制有 recipeCode + elements，
     *       天然非空底线，永不坍缩。</li>
     *   <li><b>ELEMENT 恒为槽位</b>（防坍缩底线）— 同上。</li>
     *   <li><b>PROCESS 属可选槽位</b> — 仅当模板 enabled 或用户实际选了工序时才进槽
     *       （enabledTypes 含 PROCESS 或 pr.processNos 非空）。</li>
     * </ul>
     * 模板 enabled 集的完整用途（决定落库分发写哪些表）留给 3c。
     */
    private List<EnabledParam> projectEnabledParams(PartRequest pr, Set<String> enabledTypes) {
        List<EnabledParam> out = new ArrayList<>();

        // ── MATERIAL 恒为槽位（v2：一个槽位装 N 个材质）──
        // 🚨 防坍缩底线重新论证（api.md §4.5）：v1 靠 MATERIAL + ELEMENT 两个恒定槽位兜底；
        //    v2 删掉 ELE= 后，坍缩风险从「元素丢失」变为「某个材质整组丢失」，
        //    故守卫改为：materials 必须非空、且每项元素非空 —— 任一为空即 fail-fast，
        //    不静默产出一个会让该客户所有选配撞同一个料号的指纹。
        List<MaterialSelection> mats = pr.effectiveMaterials();
        if (mats.isEmpty()) {
            throw new IllegalArgumentException(
                "custom 配件必须至少有一个材质（防指纹坍缩）: part=" + pr.name);
        }
        List<SalesFingerprintCalculator.MaterialPct> matPcts = new ArrayList<>(mats.size());
        for (MaterialSelection ms : mats) {                       // 循环体内零查库
            if (ms.elements == null || ms.elements.isEmpty()) {
                throw new IllegalArgumentException(
                    "材质元素含量为空（防指纹坍缩）: recipeCode=" + ms.recipeCode);
            }
            List<ElementPct> els = new ArrayList<>(ms.elements.size());
            for (ElementOverride eo : ms.elements) {              // 循环体内零查库
                if (eo == null || eo.elementCode == null || eo.elementCode.isBlank()) {
                    // fail-fast: 脏 elements 若静默透传会在下游 sorted/assertNoDelimiter 裸 NPE → 500。
                    throw new IllegalArgumentException(
                        "custom 配件元素项非法(null 或 elementCode 空): recipeCode=" + ms.recipeCode);
                }
                els.add(new ElementPct(eo.elementCode, eo.pct));
            }
            matPcts.add(new SalesFingerprintCalculator.MaterialPct(
                ms.recipeCode, ms.ratio == null ? RATIO_TOTAL : ms.ratio, els));
        }
        out.add(EnabledParam.material(matPcts));

        // ⛔ ELEMENT 槽位在 v2 中删除（元素含量已折进 MAT= 的括号内按材质分组，api.md §4.5）。

        // PROCESS 条件槽位
        boolean hasProcessNos = pr.processNos != null && !pr.processNos.isEmpty();
        if (enabledTypes.contains("PROCESS") || hasProcessNos) {
            out.add(new EnabledParam("PROCESS", null, null, resolveProcessCodes(pr.processNos)));
        }

        return out;
    }

    /**
     * task-0712 缺口1(工序 id 契约修复, 方案A): processNos 恒等返回 + fail-fast 校验存在于
     * {@code process_master}。取代旧 "processIds(UUID) → SELECT code FROM process WHERE id"
     * 查表逻辑 —— 标识域已统一为 process_no, 无需再经 process(V4) 表转译(F4: process.code ==
     * process_master.process_no, F9: process(V4) 是冻结快照, 新导入工序只进 process_master)。
     */
    private List<String> resolveProcessCodes(List<String> processNos) {
        // 🚫 B-19②：原实现在 for 循环里逐个 `SELECT 1 FROM process_master WHERE process_no=:pn`
        //    —— 三层模型把工序变成允许重复的无界有序列表，N 被放大 ⇒ 违反 backend.md 硬指标。
        //    存在性校验已上移到 prepareParts → assertProcessNosKnown（走 ConfigureCatalog 的
        //    一次 IN 批量装载），本方法退化为恒等返回，零查库。
        if (processNos == null || processNos.isEmpty()) return List.of();
        return processNos;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // task-260902 · 三层模型：请求解析 + 校验（B-1 / B-10 / B-20 / B-21 / B-24）
    //
    // 层级：产品(客户产品编号) → 配件 1..N → { 零件[品名/规格/尺寸/总重 + 材质 1..N(带占比)] | 外购件 } → 工序
    // ═══════════════════════════════════════════════════════════════════════

    /** 品名/规格/尺寸的库列宽 —— {@code material_master} 三列实查均为 {@code varchar(100)}（AC-23）。 */
    static final int PART_TEXT_MAX_LEN = 100;

    /** 指纹规范串的分隔符集合，与 {@code SalesFingerprintCalculator.assertNoDelimiter} 同口径。 */
    private static final char[] FP_DELIMITERS = {'|', '=', ',', ':', '∅'};

    /** 材质占比合计的目标值（AC-4 / AC-15a / AC-15b）。 */
    private static final BigDecimal RATIO_TOTAL = new BigDecimal("100");

    /** {@code material_master.material_type} 的料号类型值域 —— 闸门 A0 裁决「归位为料号类型」。 */
    static final String MATERIAL_TYPE_PART = "零件";        // 零件
    static final String MATERIAL_TYPE_OUTSOURCED = "外购件"; // 外购件
    static final String MATERIAL_TYPE_FINISHED = "成品";    // 成品（COMPOSITE 父料号，B-17②）

    /** 工序主数据的内存投影（catalog 装载，落库时零查库）。 */
    record ProcessMeta(String processNo, String processName, String category,
                       String currency, String unit, BigDecimal defectRate) {}

    /**
     * 本次请求用到的<b>全部主数据快照</b> —— 一次性批量装载，供解析 / 校验 / 落库共用。
     *
     * <p>🚫 <b>它存在的唯一理由是 N+1</b>（{@code backend.md §1}）：三层模型把材质变成 1..N、
     * 工序变成允许重复的无界有序列表，若沿用「逐材质 {@code findByCodeOrThrow} + {@code listConfigs}
     * + 元素行」「逐工序 {@code SELECT … FROM process_master}」的老写法，SQL 条数会随材质数 / 工序数
     * 线性增长。装载后所有循环体都是纯内存查 Map。
     */
    static final class ConfigureCatalog {
        final Map<String, com.cpq.configure.entity.MaterialRecipe> recipeByCode = new LinkedHashMap<>();
        final Map<UUID, List<com.cpq.configure.entity.MaterialRecipeConfig>> activeConfigsByRecipeId = new LinkedHashMap<>();
        final Map<UUID, List<com.cpq.configure.entity.MaterialRecipeElement>> elementsByConfigId = new LinkedHashMap<>();
        final Map<UUID, Set<String>> compositionCodesByRecipeId = new LinkedHashMap<>();
        final Map<String, ProcessMeta> processByNo = new LinkedHashMap<>();
        /** 外购件料号 → {material_name, material_type}；未命中 = 料号不存在。 */
        final Map<String, String[]> outsourcedByNo = new LinkedHashMap<>();

        ProcessMeta process(String processNo) { return processByNo.get(processNo); }
    }

    /**
     * 装载本次请求的主数据快照 —— <b>固定 6 条 SQL，与材质数 / 工序数 / 配件数无关</b>。
     *
     * @param customerNo        客户编号（{@code customer.code}）—— ⑥ 外购件料号按客户隔离，必填
     *                          （task-260910 · B-10 / D-2）
     * @param parts             本次请求的全部配件
     * @param compositeDefCodes 组合工艺 defCode（值 = {@code process_master.process_no}），可为 null
     */
    @SuppressWarnings("unchecked")
    ConfigureCatalog loadCatalog(String customerNo, List<PartRequest> parts, List<String> compositeDefCodes) {
        ConfigureCatalog cat = new ConfigureCatalog();
        if (parts == null) parts = List.of();

        Set<String> recipeCodes = new LinkedHashSet<>();
        Set<String> processNos = new LinkedHashSet<>();
        Set<String> outsourcedNos = new LinkedHashSet<>();
        for (PartRequest pr : parts) {
            if (pr == null) continue;
            if (pr.processNos != null) {
                for (String pn : pr.processNos) if (pn != null && !pn.isBlank()) processNos.add(pn);
            }
            if (pr.isOutsourced()) {
                if (pr.outsourcedPartNo != null && !pr.outsourcedPartNo.isBlank()) outsourcedNos.add(pr.outsourcedPartNo);
                continue;   // 外购件不带材质
            }
            if (!pr.isNewMode()) continue;   // existing 不解析材质
            for (MaterialSelection ms : pr.effectiveMaterials()) {
                if (ms != null && ms.recipeCode != null && !ms.recipeCode.isBlank()) recipeCodes.add(ms.recipeCode);
            }
        }
        if (compositeDefCodes != null) {
            for (String d : compositeDefCodes) if (d != null && !d.isBlank()) processNos.add(d);
        }

        // ① 材质
        if (!recipeCodes.isEmpty()) {
            List<com.cpq.configure.entity.MaterialRecipe> recipes = com.cpq.configure.entity.MaterialRecipe
                .<com.cpq.configure.entity.MaterialRecipe>find("code in ?1 AND status = 'ACTIVE'", recipeCodes).list();
            for (com.cpq.configure.entity.MaterialRecipe r : recipes) cat.recipeByCode.put(r.code, r);
        }
        Set<UUID> recipeIds = new LinkedHashSet<>();
        for (com.cpq.configure.entity.MaterialRecipe r : cat.recipeByCode.values()) recipeIds.add(r.id);

        // ② ACTIVE 含量配置
        Set<UUID> configIds = new LinkedHashSet<>();
        if (!recipeIds.isEmpty()) {
            List<com.cpq.configure.entity.MaterialRecipeConfig> cfgs = com.cpq.configure.entity.MaterialRecipeConfig
                .<com.cpq.configure.entity.MaterialRecipeConfig>find(
                    "recipeId in ?1 AND status = 'ACTIVE' ORDER BY seq", recipeIds).list();
            for (com.cpq.configure.entity.MaterialRecipeConfig c : cfgs) {
                cat.activeConfigsByRecipeId.computeIfAbsent(c.recipeId, k -> new ArrayList<>()).add(c);
                configIds.add(c.id);
            }
        }

        // ③ 配置下的元素行
        if (!configIds.isEmpty()) {
            List<com.cpq.configure.entity.MaterialRecipeElement> els = com.cpq.configure.entity.MaterialRecipeElement
                .<com.cpq.configure.entity.MaterialRecipeElement>find(
                    "configId in ?1 ORDER BY sortOrder", configIds).list();
            for (com.cpq.configure.entity.MaterialRecipeElement e : els) {
                cat.elementsByConfigId.computeIfAbsent(e.configId, k -> new ArrayList<>()).add(e);
            }
        }

        // ④ 元素组成（自定义含量的允许元素集，M-0）
        if (!recipeIds.isEmpty()) {
            Map<UUID, List<com.cpq.configure.entity.MaterialRecipeComposition>> byRecipe =
                materialRecipeConfigService.listCompositionBatch(recipeIds);
            for (Map.Entry<UUID, List<com.cpq.configure.entity.MaterialRecipeComposition>> e : byRecipe.entrySet()) {
                Set<String> codes = new LinkedHashSet<>();
                for (com.cpq.configure.entity.MaterialRecipeComposition c : e.getValue()) codes.add(c.elementCode);
                cat.compositionCodesByRecipeId.put(e.getKey(), codes);
            }
        }

        // ⑤ 工序主数据（含组合工艺 defCode）
        if (!processNos.isEmpty()) {
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT process_no, process_name, process_category, standard_currency, standard_unit, " +
                    "       default_defect_rate FROM process_master WHERE process_no IN (:nos)")
                .setParameter("nos", processNos).getResultList();
            for (Object[] r : rows) {
                cat.processByNo.put(r[0].toString(), new ProcessMeta(
                    r[0].toString(),
                    r[1] == null ? null : r[1].toString(),
                    r[2] == null ? null : r[2].toString(),
                    (r[3] == null || r[3].toString().isBlank()) ? null : r[3].toString(),
                    (r[4] == null || r[4].toString().isBlank()) ? null : r[4].toString(),
                    r[5] == null ? null : new BigDecimal(r[5].toString())));
            }
        }

        // ⑥ 外购件料号
        //    🆕 task-260910 · B-10（AC-5 / AC-7）：v_compat_material_master → ds_quote_material，
        //       并加 customer_no 过滤（D-2）。⚠️ 加客户维度后本查询同时成了「该客户下存在性校验」：
        //       别的客户名下的外购件料号在这里查不到 ⇒ resolvePart 会按 OUTSOURCED_PART_REQUIRED
        //       拒收，这正是 D-2 想要的（不许把别客户的料号选进来）。
        if (!outsourcedNos.isEmpty()) {
            if (customerNo == null || customerNo.isBlank()) {
                throw new IllegalArgumentException(
                    "外购件料号校验需要客户编号（customerNo）——报价侧料号库按客户隔离");
            }
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT material_no, material_name, material_type FROM ds_quote_material " +
                    "WHERE customer_no = :cn AND material_no IN (:nos)")
                .setParameter("cn", customerNo)
                .setParameter("nos", outsourcedNos).getResultList();
            for (Object[] r : rows) {
                cat.outsourcedByNo.put(r[0].toString(), new String[]{
                    r[1] == null ? null : r[1].toString(), r[2] == null ? null : r[2].toString()});
            }
        }
        return cat;
    }

    /**
     * 提交端 / 预览端<b>共用</b>的配件解析入口：归一 {@code partType}/{@code partMode} → 校验 →
     * 把材质来源（标准配置 {@code configNo} / 自定义 {@code elements}）物化成元素含量。
     *
     * <p><b>必须跑在指纹计算之前</b>，否则「预览命中」≠「提交命中」（task-260901 B-17 的既有不变量）。
     * 幂等：{@link MaterialSelection#materialResolved} 下沉到 material 级，重复调用不会误报
     * {@code MATERIAL_SOURCE_AMBIGUOUS}（评审 P2-15）。
     */
    ConfigureCatalog prepareParts(String customerNo, List<PartRequest> parts, List<String> compositeDefCodes) {
        return prepareParts(customerNo, parts, compositeDefCodes, true);
    }

    /**
     * @param submitting {@code true}=提交端（{@code configure}），跑全部校验；
     *                   {@code false}=预览端（{@code lookupFingerprint}），跳过
     *                   {@code PART_WEIGHT_REQUIRED}。
     *                   <p>⚠️ 这条区别是刻意的：api.md §1.2 的校验规则表挂在<b>主提交端点</b>下，
     *                   预览是用户还在填表途中调的（P2→P3 之间），此时总重可能尚未录入 ——
     *                   在预览就 400 会把「实时查是否命中」这个交互直接打断。
     *                   代价是：没填重量时预览按 {@code WEIGHT=0} 算，与最终提交的指纹不同 ⇒
     *                   <b>可能预览未命中而提交命中</b>（偏保守方向，不会造成错误复用）。
     */
    ConfigureCatalog prepareParts(String customerNo, List<PartRequest> parts, List<String> compositeDefCodes,
                                  boolean submitting) {
        ConfigureCatalog cat = loadCatalog(customerNo, parts, compositeDefCodes);
        if (parts != null) {
            for (PartRequest pr : parts) preparePart(pr, cat, submitting);   // 循环体内零查库（全部走 catalog）
        }
        if (compositeDefCodes != null) {
            for (String d : compositeDefCodes) {                  // 循环体内零查库
                if (d == null || d.isBlank()) continue;
                if (cat.process(d) == null) {
                    throw new IllegalArgumentException("组合工艺未找到(process_master.process_no): " + d);
                }
            }
        }
        return cat;
    }

    /** 单个配件的归一 + 校验 + 材质物化（循环体内零查库，全部走 {@link ConfigureCatalog}）。 */
    void preparePart(PartRequest pr, ConfigureCatalog cat) { preparePart(pr, cat, true); }

    void preparePart(PartRequest pr, ConfigureCatalog cat, boolean submitting) {
        if (pr == null) throw new IllegalArgumentException("parts 项不能为 null");
        if (pr.partType == null || pr.partType.isBlank()) pr.partType = "PART";
        if (!"PART".equals(pr.partType) && !"OUTSOURCED".equals(pr.partType)) {
            throw new IllegalArgumentException("partType 只能是 PART 或 OUTSOURCED: " + pr.partType);
        }

        // ── 外购件（AC-5 / AC-16）──
        if (pr.isOutsourced()) {
            if (pr.outsourcedPartNo == null || pr.outsourcedPartNo.isBlank()) {
                throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                    "OUTSOURCED_PART_REQUIRED", "外购件必须选择料号");
            }
            if (!cat.outsourcedByNo.containsKey(pr.outsourcedPartNo)) {
                throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                    "OUTSOURCED_PART_REQUIRED", "外购件料号不存在: " + pr.outsourcedPartNo);
            }
            assertProcessNosKnown(pr, cat);
            return;
        }

        if (pr.isExistingMode()) {         // 已有零件：不解析材质，工序仍要校验
            assertProcessNosKnown(pr, cat);
            return;
        }
        if (!pr.isNewMode()) {
            throw new IllegalArgumentException("partMode must be 'existing' or 'new': " + pr.partMode);
        }

        // ── 新建零件（AC-3）──
        assertPartText(pr.name, "品名");
        assertPartText(pr.spec, "规格");
        assertPartText(pr.dimension, "尺寸");

        if (submitting && (pr.unitWeightGrams == null || pr.unitWeightGrams.compareTo(BigDecimal.ZERO) <= 0)) {
            throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                "PART_WEIGHT_REQUIRED", "零件总重必须大于 0");
        }

        List<MaterialSelection> mats = pr.effectiveMaterials();
        if (mats.isEmpty()) {
            throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                "PART_HAS_NO_MATERIAL", "请至少添加一个材质");
        }

        // 同一 part 内材质不可重复（AC-17；前端灰显是防错，后端拦是正确性）
        Set<String> seen = new LinkedHashSet<>();
        for (MaterialSelection ms : mats) {
            if (ms == null || ms.recipeCode == null || ms.recipeCode.isBlank()) {
                throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                    "PART_HAS_NO_MATERIAL", "材质项缺少 recipeCode");
            }
            if (!seen.add(ms.recipeCode)) {
                throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                    "MATERIAL_DUPLICATED", "同一零件内材质重复: " + ms.recipeCode);
            }
        }

        // 占比合计必须正好 100（AC-4 / AC-15a / AC-15b）
        // 🚨 判等只能用 BigDecimal.compareTo：AC-15b 那组（0.000000000001 + 99.999999999998 +
        //    0.000000000001）在 double 下等于 99.99999999999999，浮点实现会**错误拒绝这个合法输入**。
        //    ⚠️ AC-15a 那组在 double 下恰好等于 100，拦不住浮点实现 —— 两条都跑才有分辨力。
        BigDecimal sum = BigDecimal.ZERO;
        for (MaterialSelection ms : mats) {
            sum = sum.add(ms.ratio == null ? BigDecimal.ZERO : ms.ratio);
        }
        if (sum.compareTo(RATIO_TOTAL) != 0) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("actualSum", sum.stripTrailingZeros().toPlainString());
            detail.put("expected", "100");
            throw new com.cpq.configure.exception.MaterialRecipeApiException(
                400, "MATERIAL_RATIO_SUM_INVALID",
                "材质占比合计为 " + sum.stripTrailingZeros().toPlainString() + "%，需要正好 100%", detail);
        }

        for (MaterialSelection ms : mats) resolveMaterial(pr, ms, cat);   // 循环体内零查库
        assertProcessNosKnown(pr, cat);
    }

    /** 品名/规格/尺寸的字符校验：超长（AC-23）与指纹分隔符（§4.3 / B-21）。 */
    private void assertPartText(String value, String label) {
        if (value == null) return;
        if (value.length() > PART_TEXT_MAX_LEN) {
            // 🚫 绝不落库截断：截断后指纹算的是截断值、实际内容是另一个
            //    ⇒ 两个不同产品会算出相同指纹 ⇒ 静默错价（与 §4.3 的 '/' 分隔符同型事故）。
            throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                "PART_TEXT_TOO_LONG", label + "最多 " + PART_TEXT_MAX_LEN + " 个字符，实际 " + value.length());
        }
        for (char c : FP_DELIMITERS) {
            if (value.indexOf(c) >= 0) {
                throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                    "PART_TEXT_INVALID_CHAR", label + "不能包含字符 '" + c + "'（指纹规范串分隔符）");
            }
        }
    }

    /** 工序编号存在性（catalog 已批量装载，本方法零查库）。 */
    private void assertProcessNosKnown(PartRequest pr, ConfigureCatalog cat) {
        if (pr.processNos == null) return;
        for (String pn : pr.processNos) {      // 循环体内零查库
            if (pn == null || pn.isBlank()) throw new IllegalArgumentException("工序编号不能为空");
            if (cat.process(pn) == null) throw new IllegalArgumentException("工序不存在: " + pn);
        }
    }

    /**
     * task-260901 · B-17 → task-260902 下沉到 material 级：解析并校验「这个<b>材质</b>的含量从哪来」。
     *
     * <p>规则（api.md §1.2 + M-5）：
     * <ol>
     *   <li>材质无任何 ACTIVE 配置 → 409 {@code RECIPE_HAS_NO_CONFIG}（AC-5b。
     *       <b>本条排在互斥校验之前</b>：0 配置的材质前端根本给不出 {@code configNo}，
     *       若先判互斥会把 AC-5b 变成 400 AMBIGUOUS，掩盖真正原因）；</li>
     *   <li>{@code configNo} 与 {@code elements} <b>恰好给一个</b> → 否则 400 {@code MATERIAL_SOURCE_AMBIGUOUS}；</li>
     *   <li>给 {@code configNo}：按编号取该配置的元素，物化进 {@code ms.elements}；</li>
     *   <li>给 {@code elements}（自定义含量，AC-21/AC-22）：<b>先看材质级开关</b> ——
     *       {@code allowCustomContent=false} 直接 403，<b>不进元素级 is_locked 判断</b>（M-5）。
     *       🚫 自定义含量<b>不回流</b> {@code material_recipe_config}/{@code material_recipe_element}
     *       （AC-21②，对齐 task-260901 D-5）—— 本方法只改内存里的 {@code ms.elements}，不写材质库。</li>
     * </ol>
     *
     * <p>⚠️ <b>含量刻度的双口径兼容</b>（task-260901 既有行为，本次原样保留）：按 Σ 判刻度 ——
     * Σ ≤ 10 视为 0~1 制（×100 归一），否则视为 100 制。两种合法输入的 Σ 相距 100 倍，无歧义区。
     * 归一发生在指纹计算之前，故同一份配比无论用哪种刻度提交，指纹与落库结果都一致。
     * <b>该歧义已上报主线（api.md §1.2 未写明 materials[i].elements 的单位）。</b>
     */
    void resolveMaterial(PartRequest pr, MaterialSelection ms, ConfigureCatalog cat) {
        if (ms.materialResolved) return;                    // 幂等：物化后再调不重复校验

        com.cpq.configure.entity.MaterialRecipe recipe = cat.recipeByCode.get(ms.recipeCode);
        if (recipe == null) {
            throw com.cpq.configure.exception.MaterialRecipeApiException.notFound(
                "RECIPE_NOT_FOUND", "材质不存在或已停用：" + ms.recipeCode);
        }
        List<com.cpq.configure.entity.MaterialRecipeConfig> actives =
            cat.activeConfigsByRecipeId.getOrDefault(recipe.id, List.of());
        if (actives.isEmpty()) {
            throw com.cpq.configure.exception.MaterialRecipeApiException.conflict(
                "RECIPE_HAS_NO_CONFIG", "该材质尚未配置含量");
        }

        boolean hasConfigNo = ms.configNo != null && !ms.configNo.isBlank();
        boolean hasElements = ms.elements != null && !ms.elements.isEmpty();
        if (hasConfigNo == hasElements) {
            throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                "MATERIAL_SOURCE_AMBIGUOUS", "请选择标准配置或自定义含量之一");
        }

        // ── 标准配置：物化成 elements ──
        if (hasConfigNo) {
            String wanted = ms.configNo.trim();
            com.cpq.configure.entity.MaterialRecipeConfig picked = null;
            for (com.cpq.configure.entity.MaterialRecipeConfig c : actives) {   // 内存遍历
                if (wanted.equals(c.configNo)) { picked = c; break; }
            }
            if (picked == null) {
                throw com.cpq.configure.exception.MaterialRecipeApiException.notFound(
                    "CONFIG_NOT_FOUND", "含量配置不存在或已停用：" + wanted);
            }
            List<com.cpq.configure.entity.MaterialRecipeElement> els =
                cat.elementsByConfigId.getOrDefault(picked.id, List.of());
            List<ElementOverride> out = new ArrayList<>(els.size());
            for (com.cpq.configure.entity.MaterialRecipeElement e : els) {      // 内存遍历
                out.add(new ElementOverride(e.elementCode, e.defaultPct));      // 库内即 100 制
            }
            ms.elements = out;
            ms.materialResolved = true;
            return;
        }

        // ── 自定义含量：材质级开关优先（M-5）──
        if (!recipe.allowCustomContent) {
            // 📌 错误码按 api.md §1.2（task-260902 契约）为 RECIPE_CUSTOM_NOT_ALLOWED；
            //    task-260901 交付的旧码是 CUSTOM_CONTENT_NOT_ALLOWED，文案保持一致以免前端回归。
            //    该码差异已上报主线。
            throw com.cpq.configure.exception.MaterialRecipeApiException.forbidden(
                "RECIPE_CUSTOM_NOT_ALLOWED", "该材质不支持自定义含量");
        }

        BigDecimal raw = BigDecimal.ZERO;
        for (ElementOverride eo : ms.elements) {                                // 内存遍历
            if (eo == null || eo.elementCode == null || eo.elementCode.isBlank()) {
                throw new IllegalArgumentException(
                    "custom 配件元素项非法(null 或 elementCode 空): recipeCode=" + ms.recipeCode);
            }
            raw = raw.add(eo.pct == null ? BigDecimal.ZERO : eo.pct);
        }
        boolean ratioScale = raw.compareTo(new BigDecimal("10")) <= 0;    // 见上方刻度说明
        BigDecimal ratioSum = ratioScale ? raw
            : raw.divide(new BigDecimal("100"), 12, java.math.RoundingMode.HALF_UP);
        if (ratioSum.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.0001")) > 0) {
            throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                "CUSTOM_CONTENT_SUM_NOT_ONE",
                "含量合计必须为 1，实际 " + com.cpq.configure.rules.MaterialRecipeRules.formatRatioSum(ratioSum));
        }

        // 元素必须在材质的**元素组成**里（M-0：组成是材质的显式属性）
        Set<String> compCodes = cat.compositionCodesByRecipeId.getOrDefault(recipe.id, Set.of());
        // min/max 参考取该材质第一条 ACTIVE 配置的元素定义（元素级限值挂在元素行上；
        // 自定义含量不绑定某条配置，故取 seq 最小的那条作参考。实测存量 min/max 全为 NULL，此分支当前不生效）。
        Map<String, com.cpq.configure.entity.MaterialRecipeElement> defByCode =
            cat.elementsByConfigId.getOrDefault(actives.get(0).id, List.<com.cpq.configure.entity.MaterialRecipeElement>of())
                .stream().collect(Collectors.toMap(e -> e.elementCode, e -> e, (a, b) -> a));

        List<ElementOverride> normalized = new ArrayList<>(ms.elements.size());
        for (ElementOverride eo : ms.elements) {                                // 内存遍历
            if (!compCodes.contains(eo.elementCode)) {
                throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                    "CUSTOM_CONTENT_ELEMENT_UNKNOWN", "元素未在材质中定义：" + eo.elementCode);
            }
            BigDecimal pct100 = eo.pct == null ? BigDecimal.ZERO
                : (ratioScale ? eo.pct.multiply(new BigDecimal("100")) : eo.pct);
            com.cpq.configure.entity.MaterialRecipeElement def = defByCode.get(eo.elementCode);
            // M-5：allowCustomContent=true 时 is_locked 不再单独生效，只看 min/max（若有）。
            if (def != null && def.minPct != null && def.maxPct != null) {
                if (pct100.compareTo(def.minPct) < 0 || pct100.compareTo(def.maxPct) > 0) {
                    throw new IllegalArgumentException(
                        "元素含量超出范围 [" + def.minPct + ", " + def.maxPct + "]: " + eo.elementCode);
                }
            }
            normalized.add(new ElementOverride(eo.elementCode, pct100));
        }
        ms.elements = normalized;
        ms.materialResolved = true;
    }

    // insertMatPart 已在 Phase 3 移除（V44 mat_part 写入停用）

    /**
     * <b>task-260910 · B-5（AC-1）</b>：existing 路径新客户复用老料号时，确保当前客户在
     * {@code ds_quote_self_process_fee} 中有工序数据。
     *
     * <p>{@code ds_quote_self_process_fee} 的轴是 {@code (customer_no, material_no)}，新客户首次复用
     * ⇒ 该客户名下无工序行 ⇒ 工序页签空。幂等：当前客户已有行时跳过。
     * 无数据时从该料号任一现有客户复制工序（{@code operation_no / operation_item_seq /
     * input_material_no / currency / pricing_unit}），写成当前客户的新组。
     *
     * <p>🚫 <b>{@code value}（单价）与 {@code ratio_pct} 刻意不复制</b> —— 单价是<b>按客户</b>谈的，
     * 跨客户搬价格是业务错误。老实现（{@code unit_price}）同样只搬 operation_no/seq/currency/unit，
     * 这里逐字保持该口径。
     *
     * <p>📌 <b>原方法头那句「hotfix: {@code mat_process} 按 customer_id 隔离」已删除</b>：
     * 实测全工程 {@code mat_process} 表名位引用 = 0，方法体查的一直是 {@code unit_price}
     * （现为 {@code ds_quote_self_process_fee}）。那句注释会把下一个人引到一张根本不存在写点的表上。
     */
    @SuppressWarnings("unchecked")
    void backfillProcessesForNewCustomer(String hfPartNo, java.util.UUID currentCustomerId, String operator) {
        // 把 currentCustomerId(UUID) 转成 customer_no（报价侧新表一律用 customer.code 字符串）
        List<Object> cc = em.createNativeQuery(
                "SELECT code FROM customer WHERE id = :id")
            .setParameter("id", currentCustomerId).getResultList();
        if (cc.isEmpty() || cc.get(0) == null) return;
        String currentCustomerCode = cc.get(0).toString();

        // 已有则跳过
        Object existsObj = em.createNativeQuery(
                "SELECT 1 FROM ds_quote_self_process_fee WHERE material_no = :p AND customer_no = :c LIMIT 1")
            .setParameter("p", hfPartNo).setParameter("c", currentCustomerCode)
            .getResultStream().findFirst().orElse(null);
        if (existsObj != null) return;

        // 取该料号任一已有客户的当前工序（按最新版本挑出那一个客户），复制成 currentCustomerCode
        List<Object[]> src = em.createNativeQuery(
                "SELECT operation_no, operation_item_seq, input_material_no, currency, pricing_unit "
              + "FROM ds_quote_self_process_fee "
              + "WHERE material_no = :p "
              + "  AND customer_no = (SELECT customer_no FROM ds_quote_self_process_fee "
              + "     WHERE material_no = :p ORDER BY version_no DESC, item_seq LIMIT 1) "
              + "ORDER BY item_seq").setParameter("p", hfPartNo).getResultList();
        if (src.isEmpty()) return;

        List<Map<String, Object>> rows = new ArrayList<>(src.size());
        int seq = 1;
        for (Object[] r : src) {                                          // 循环体内零查库：纯内存搬运
            Integer opSeq = r[1] == null ? null : ((Number) r[1]).intValue();
            Map<String, Object> row = SelDsQuoteWriter.selfProcessFeeRow(
                hfPartNo, seq++,
                // 投入料号：源行为空时兜回料号自身（D-4：SIMPLE 时零件料号 = 自己）
                r[2] == null ? hfPartNo : r[2].toString(),
                r[0] == null ? null : r[0].toString(),
                r[3] == null ? "CNY" : r[3].toString(),
                r[4] == null ? "KG" : r[4].toString());
            if (opSeq != null) row.put("operation_item_seq", opSeq);      // 源行的工序项次原样保留
            rows.add(row);
        }
        dsWriter.writeSelfProcessFeeGroup(currentCustomerCode, hfPartNo, rows, operator);
        System.out.printf("[configure backfill] customerCode=%s hfPartNo=%s backfilled %d "
                + "ds_quote_self_process_fee rows%n", currentCustomerCode, hfPartNo, rows.size());
    }

    // readElementsFromMatBom 和 copyElementBom 已在 Phase 3 移除（V44 mat_bom 死代码）

    // insertElementBom 已在 Phase 3 移除（V44 mat_bom 写入停用）

    // ─────────────────────────────────────────────────────────────────────
    // V6 落库（AP-53 续 6 Phase 1）— 复刻 import 行形状，让现有 mirror 视图零改渲染。
    // id/created_at/updated_at 由 DB 默认 (gen_random_uuid / now)；用 ON CONFLICT DO NOTHING 幂等。
    // customer_no 用 customer.code（mirror 视图按此过滤）。工序/组合工艺承载 = Phase 2。
    // ─────────────────────────────────────────────────────────────────────

    // ═══════════════════════════════════════════════════════════════════════
    // task-260903 · 阶段 A：ds_quote_* 行集组装（纯内存，循环体内零查库）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * A-2（A-AC-1② / A-AC-6）：SIMPLE 零件的材质行 —— 每材质一行，{@code output_material_type='RECIPE'}。
     *
     * <p>逐列对照被它取代的 {@code insertMaterialBomItemV6}：
     * <pre>
     *   V6 seq_no              → item_seq
     *   V6 component_no        → input_material_no（材质料号 recipe.code，不是销售料号自指）
     *   V6 component_usage_type→ 【不再存】兼容视图 LEFT JOIN material_recipe ON code=input_material_no
     *                            取 symbol 现算（B-2）。少了这一列反而消除了一处冗余。
     *   V6 material_ratio      → material_ratio（numeric(26,12)，A-AC-1② 要求存满 12 位小数）
     *   V6 characteristic      → output_material_type = 'RECIPE'
     *   V6 rough_weight / net_weight / weight_unit / scrap_rate / defect_rate
     *                          → 【刻意留 NULL】选配侧在 V6 时代本来就写 NULL，留 NULL 才是行为等价。
     *                            🚫 不许"补全"——这些列的语义有「百分比 vs 小数」歧义，硬填制造新 bug。
     * </pre>
     */
    List<Map<String, Object>> buildRecipeBomRows(String partNo, List<MaterialSelection> materials) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (materials == null) return rows;
        int seq = 1;
        for (MaterialSelection ms : materials) {          // 循环体内零查库：纯内存组装
            rows.add(SelDsQuoteWriter.materialBomRow(
                partNo, seq++, ms.recipeCode, SelDsQuoteWriter.OUT_RECIPE, null, ms.ratio));
        }
        return rows;
    }

    /**
     * A-3（A-AC-1③）：元素含量行 —— 该料号<b>所有材质</b>的元素行合成一组。
     *
     * <p>🚨 V6 时代是「每材质一组」（material_part_no 是组键的一维），新表里 material_part_no
     * 降级成普通列、轴只剩 material_no ⇒ 必须合并成一次写入。前端仍靠 material_part_no
     * 把元素分回各自的材质，语义不丢。
     *
     * <p>{@code item_seq} 跨材质连续编号（它不参与指纹，只决定显示顺序）。
     */
    List<Map<String, Object>> buildElementBomRows(String partNo, List<MaterialSelection> materials) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (materials == null) return rows;
        int seq = 1;
        for (MaterialSelection ms : materials) {          // 循环体内零查库
            List<ElementOverride> els = ms.elements == null ? List.<ElementOverride>of() : ms.elements;
            for (ElementOverride eo : els) {              // 循环体内零查库
                rows.add(SelDsQuoteWriter.elementBomRow(partNo, ms.recipeCode, seq++, eo.elementCode, eo.pct));
            }
        }
        return rows;
    }

    /**
     * A-2 / A-4（A-AC-6）：COMPOSITE 父料号的 BOM —— ASSEMBLY 行与 RECIPE 行<b>合成一组</b>。
     *
     * <p>取代 {@code writeCombomaterialBomV6} 的两次 {@code writeVersionedMasterDetail}：
     * <pre>
     *   ASSEMBLY 组 → item_seq 1..N，input_material_no=子件料号，component_qty=装配用量
     *                 （component_qty 经兼容视图映射成 V6 composition_qty，53 段模板引用它）
     *   RECIPE   组 → item_seq N+1..2N，input_material_no=子件料号
     * </pre>
     * 两类行的 {@code output_material_type} 不同 ⇒ 行指纹天然不同，同组共存不会互相吞掉。
     *
     * <p>🚩 <b>已知保真度损失（回报已登记）</b>：V6 的 RECIPE 组还存了
     * {@code component_usage_type = 子件的材质名}（由 {@code readChildMaterialUsageType} 逐子件查库得到，
     * 那本身是个 N+1）。新表没有这一列，而兼容视图的 {@code component_usage_type} 是按
     * {@code input_material_no} JOIN {@code material_recipe} 现算的 —— 这里的 input_material_no 是
     * <b>子件报价料号</b>而非材质料号，JOIN 落空 ⇒ 组合产品父卡片上子件的材质名会从
     * 「AgNi10」降级到 COALESCE 兜底（子件品名）。🚫 不要为此把材质名硬塞进别的列。
     */
    List<Map<String, Object>> buildCompositeBomRows(String parentPartNo, List<String> childPartNos,
                                                    List<Integer> childQtys) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (childPartNos == null) return rows;
        int seq = 1;
        for (int i = 0; i < childPartNos.size(); i++) {   // 循环体内零查库
            int qty = (childQtys != null && i < childQtys.size() && childQtys.get(i) != null
                       && childQtys.get(i) >= 1) ? childQtys.get(i) : 1;
            rows.add(SelDsQuoteWriter.materialBomRow(parentPartNo, seq++, childPartNos.get(i),
                SelDsQuoteWriter.OUT_ASSEMBLY, new BigDecimal(qty), null));
        }
        for (String child : childPartNos) {               // 循环体内零查库
            rows.add(SelDsQuoteWriter.materialBomRow(parentPartNo, seq++, child,
                SelDsQuoteWriter.OUT_RECIPE, null, null));
        }
        return rows;
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
        // 🚨 task-260903 · A-7 的**唯一豁免点**，请勿当成漏改。
        //
        // A-7 要求停写 V6 五表，本方法却仍在写 element_bom_item / material_bom_item / material_bom。
        // 直接删会造成回归：本方法存在的理由是 V6 那两张表**按 customer_no 分片存**，
        // 跨客户复用一个 V6 时代的老料号时必须为新客户补一份，否则材质/元素页签空
        // （task-260902 B-17① 刚修过这个 bug）。但对**新体系料号**这套复制没有存在意义
        // —— 它们的材质/元素由 ds_quote_material_bom / ds_quote_element_bom 经兼容视图供数 ——
        // 而**存量 V6 料号**仍然必需，且存量 V6 数据本任务明确不迁移（需求文档 §5.1）。
        //
        // ⇒ 折中：只对「不在 ds_quote_material 里的料号」执行，即纯 V6 存量料号。
        //    选配 A 阶段起铸的料号一律落 ds_quote_material ⇒ 这里对它们直接返回，
        //    A-AC-2「V6 五表零新增」在选配新建路径上成立。
        //
        // 🚨 repair-260908 · C-4：下面这条判定<b>刻意不带 customer_no</b>，别当成漏改。
        //    （原注释写着「新表体系没有 customer_no 维度」——那句话已被 V425 推翻，故删除；
        //      留着过期的**理由**比留着过期的断言更危险，下一个人会照它做判断。）
        //    它问的不是「这个客户有没有这个料号」，而是「这个料号属于哪个体系」——**体系归属是
        //    料号级属性，不是客户级属性**，所以跨客户命中正是想要的：
        //      ① 只要该料号在新体系里存在过，它的材质/元素就该由 ds_quote_* 供数；
        //         此时当前客户缺数据，正确修法是补 ds_quote_* 行，而不是往 V6 写（那违反 A-7）；
        //      ② 更硬的理由：兼容视图 v_compat_material_bom_item / v_compat_element_bom_item 的
        //         反连接是 `NOT EXISTS (… x.material_no = b.material_no AND x.customer_no = cs.customer_no)`
        //         —— 一旦这里为 (客户B, 料号P) 写下 V6 行，视图对 (客户B, 料号P) 的**整段新表投影会被
        //         直接吞掉**，材质/元素页签反而从「有数据」变成「只剩那几行 V6 补丁」。
        //         即：加客户条件不是变严，是把渲染打坏。
        Object inNewModel = em.createNativeQuery(
                "SELECT 1 FROM ds_quote_material WHERE material_no = :p LIMIT 1")
            .setParameter("p", partNo).getResultList().stream().findFirst().orElse(null);
        if (inNewModel != null) return;   // 新体系料号：由 ds_quote_* 供数，不许再写 V6（见上方 C-4 说明）
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
        // 2) 材质：🚨 task-260902 · B-17① —— 改为**按 material_bom_item 整组复制**，
        //    不再「按 material_recipe_id 重建」。
        //    原实现 `SELECT … FROM material_master WHERE material_recipe_id IS NOT NULL … seq_no=1`
        //    **硬编码只产出 1 行**，而指纹命中复用时前端正是把 partMode 切成 existing ⇒ AC-7 必然走到这里
        //    ⇒ 多材质料号跨客户复用时材质会从 N 个**静默塌回 1 个**。
        //    连带（B-18 用户裁决）：material_recipe_id 不再是料件材质的判据，多材质时它本就是 NULL，
        //    旧 SQL 的 `IS NOT NULL` 谓词会让这些料号一行都补不出来。
        em.createNativeQuery(
                "INSERT INTO material_bom_item (id, system_type, customer_no, material_no, characteristic, " +
                "  bom_version, is_current, seq_no, component_no, component_usage_type, material_ratio, created_at, updated_at) " +
                "SELECT gen_random_uuid(), 'QUOTE', :cn, src.material_no, src.characteristic, " +
                "  src.bom_version, true, src.seq_no, src.component_no, src.component_usage_type, src.material_ratio, NOW(), NOW() " +
                "FROM material_bom_item src " +
                "WHERE src.material_no = :p AND src.system_type = 'QUOTE' AND src.is_current = true " +
                "  AND src.characteristic = 'RECIPE' " +
                "  AND src.customer_no = (SELECT customer_no FROM material_bom_item WHERE material_no = :p " +
                "        AND system_type = 'QUOTE' AND is_current = true AND characteristic = 'RECIPE' " +
                "        ORDER BY created_at LIMIT 1) " +
                "  AND NOT EXISTS (SELECT 1 FROM material_bom_item t WHERE t.material_no = :p AND t.customer_no = :cn " +
                "        AND t.system_type = 'QUOTE' AND t.characteristic = 'RECIPE' AND t.is_current = true)")
            .setParameter("cn", customerCode)
            .setParameter("p", partNo)
            .executeUpdate();
        // 2b) 主表 material_bom 同步补一行（子表整组复制后没有对应主行会让后续 writeVersionedMasterDetail
        //     的 max(version) 取不到基准）。幂等：本客户已有则不插。
        em.createNativeQuery(
                "INSERT INTO material_bom (id, system_type, customer_no, material_no, bom_type, characteristic, " +
                "  bom_version, is_current, created_at, updated_at) " +
                "SELECT gen_random_uuid(), 'QUOTE', :cn, src.material_no, src.bom_type, src.characteristic, " +
                "  src.bom_version, true, NOW(), NOW() " +
                "FROM material_bom src " +
                "WHERE src.material_no = :p AND src.system_type = 'QUOTE' AND src.is_current = true " +
                "  AND src.bom_type = 'MATERIAL' AND src.characteristic IS NULL " +
                "  AND src.customer_no = (SELECT customer_no FROM material_bom WHERE material_no = :p " +
                "        AND system_type = 'QUOTE' AND is_current = true AND bom_type = 'MATERIAL' " +
                "        AND characteristic IS NULL ORDER BY created_at LIMIT 1) " +
                "  AND NOT EXISTS (SELECT 1 FROM material_bom t WHERE t.material_no = :p AND t.customer_no = :cn " +
                "        AND t.system_type = 'QUOTE' AND t.bom_type = 'MATERIAL' AND t.characteristic IS NULL " +
                "        AND t.is_current = true)")
            .setParameter("cn", customerCode)
            .setParameter("p", partNo)
            .executeUpdate();
    }

    // backfillV44FromV6 和 backfillV6FromV44 已在 Phase 3 移除（V44 双写桥停用）

    // ─────────────────────────────────────────────────────────────────────
    // 选配 COMBO 落库 —— task-260903（A-*）+ task-260910（B-1/B-2/B-3）后已全部切到报价侧新表：
    //   B1 物料 BOM   → ds_quote_material_bom       （task-260903 A-2/A-4）
    //   B2 工序       → ds_quote_self_process_fee   （task-260910 B-1，原 V6 unit_price）
    //   B3 组合工艺   → ds_quote_assembly_fee       （task-260910 B-2，原 V6 capacity）
    //   B3' 外购件工序 → ds_quote_assembly_fee      （task-260910 B-3，🚨 费用类别一并变更，D-6）
    // 统一走 VersionedGroupWriter（经 SelDsQuoteWriter）：整组指纹相同则 UNCHANGED 一行不写、
    // 不同则归档 + max(当前,历史)+1 升版。🚫 已无 VersionedV6Writer 引用（B-4 自检点）。
    // ─────────────────────────────────────────────────────────────────────


    /** material_bom / material_bom_item 分组键：QUOTE + customer + material_no + 一个区分列（值允许 null）。 */
    private Map<String, Object> bomGroupKey(String customerCode, String materialNo,
                                            String distinguishCol, Object distinguishVal) {
        Map<String, Object> gk = new LinkedHashMap<>();
        gk.put("system_type", "QUOTE");
        gk.put("customer_no", customerCode);
        gk.put("material_no", materialNo);
        gk.put(distinguishCol, distinguishVal);   // 值可空；writer 用 IS NOT DISTINCT FROM 做 NULL 安全匹配
        return gk;
    }

    /** 读子件自身 is_current 材质自指行 component_usage_type；缺则回退 recipe.symbol / material_type。 */
    @SuppressWarnings("unchecked")

    /**
     * B2 → <b>task-260910 · B-1（AC-1 / AC-4）改写</b>：COMPOSITE 的子件工序落
     * {@code ds_quote_self_process_fee}（原 V6 {@code unit_price}，{@code cost_type='自制加工费'}）。
     *
     * <p><b>老 → 新的分组键坍缩，这是本方法最容易写错的地方</b>：
     * 老 {@code unit_price} 的组键有 6 维
     * （{@code system_type/price_type/cost_type/customer_no/code=子件/finished_material_no=父}），
     * 每个子件<b>各自一组</b>；新表的轴只有 {@code (customer_no, material_no)} 两维
     * ⇒ 全部子件的工序行<b>同属父料号这一个组</b>，靠 {@code input_material_no}=子件料号区分
     * （D-4，AC-4 的断言原文：2 行、{@code material_no} 均为父、{@code input_material_no}
     * 分别是 C1/C2）。
     * ⇒ 🚫 <b>只能调一次 {@code writeSelfProcessFeeGroup}</b>；按子件循环调 = 后一次把前一次的行
     * 当成删除、整组重写（老代码那种 {@code groups.put(gk, rows)} 形态在新表下是错的）。
     *
     * <p>{@code item_seq} / {@code operation_item_seq} 按<b>跨子件的全局行序</b> 1..N 递增
     * （新表 {@code item_seq} 是 {@code required=true} 的通用项次）。
     * {@code value}（单价）留 NULL；{@code currency} = {@code process_master.standard_currency}（空→CNY）；
     * {@code pricing_unit} = {@code standard_unit}（空→KG，对齐导入存量）。
     * fail-fast: {@code process_no} 未命中 {@code process_master} 视为非法工序，抛出而非静默兜默认值。
     */
    void insertProcessUnitPriceV6(String parentHfPartNo, String customerCode,
                                  List<PartRequest> parts, List<String> childHfPartNos,
                                  ConfigureCatalog cat, String operator) {
        if (customerCode == null || customerCode.isBlank()) return;
        if (parentHfPartNo == null || parentHfPartNo.isBlank()) return;
        // 🚫 B-19①：工序主数据一律走 ConfigureCatalog（入口一次 IN 批量装载），
        //    循环体内**零查库**；原实现在双重循环里逐工序 SELECT process_master。
        List<Map<String, Object>> rows = new ArrayList<>();
        int seq = 1;
        for (int i = 0; i < childHfPartNos.size(); i++) {                 // 循环体内零查库
            PartRequest pr = (parts != null && i < parts.size()) ? parts.get(i) : null;
            if (pr == null || pr.processNos == null || pr.processNos.isEmpty()) continue;
            // D-4：投入料号 = 子件料号（轴列 material_no 是父料号）
            seq = appendSelfProcessFeeRows(rows, parentHfPartNo, childHfPartNos.get(i),
                pr.processNos, cat, seq);
        }
        if (rows.isEmpty()) return;
        dsWriter.writeSelfProcessFeeGroup(customerCode, parentHfPartNo, rows, operator);
    }

    /**
     * 把 {@code processNos} 追加成 {@code ds_quote_self_process_fee} 行 —— 循环体内零查库
     * （工序元数据全部走 {@link ConfigureCatalog}，入口一次 IN 批量装载）。
     *
     * <p>🚨 <b>AC-19③ / B-6</b>：项次按数组<b>原始顺序</b> 递增，🚫 不排序。
     * 指纹侧 {@code PRC=} 是排序后拼接（顺序不进指纹、换序复用同一料号），落库与显示侧认顺序 ——
     * 两侧有意不对称，改动时不要为了「统一」把这一边也排序（AC-22 的第二条断言正靠这个不对称）。
     *
     * <p><b>追加式</b>（而不是「返回一个新 list」）是为了让 COMPOSITE 的多个子件行能拼进
     * <b>同一个组</b>、项次跨子件连续 —— 见 {@link #insertProcessUnitPriceV6} 的分组键坍缩说明。
     *
     * @param materialNo      轴列值（SIMPLE = 料号自身；COMPOSITE = 父料号）
     * @param inputMaterialNo 投入料号 = <b>零件料号</b>（D-4；SIMPLE = 料号自身，COMPOSITE = 子件料号）
     * @param startSeq        本批第一行的项次
     * @return 下一个可用项次（= startSeq + 本批行数）
     */
    private int appendSelfProcessFeeRows(List<Map<String, Object>> rows, String materialNo,
                                         String inputMaterialNo, List<String> processNos,
                                         ConfigureCatalog cat, int startSeq) {
        int seq = startSeq;
        for (String opNo : processNos) {                                  // 循环体内零查库
            ProcessMeta pm = cat.process(opNo);
            if (pm == null) throw new IllegalArgumentException("工序不存在: " + opNo);
            rows.add(SelDsQuoteWriter.selfProcessFeeRow(materialNo, seq++, inputMaterialNo, opNo,
                pm.currency() != null ? pm.currency() : "CNY",
                pm.unit() != null ? pm.unit() : "KG"));
        }
        return seq;
    }

    /**
     * 2026-06-02 缺口 B → <b>task-260910 · B-1（AC-1）改写</b>：单料号工序落
     * {@code ds_quote_self_process_fee}（镜像组合版 {@link #insertProcessUnitPriceV6}）。
     *
     * <p>SIMPLE 无父子 ⇒ 轴列 {@code material_no} 与投入料号 {@code input_material_no}
     * <b>同为 {@code hfPartNo}</b>（D-4：「投入料号装的就是零件料号」，SIMPLE 时零件就是它自己）——
     * 逐字对应老 {@code unit_price} 组键里 {@code code = finished_material_no = hfPartNo} 那一行。
     *
     * <p>task-0712 缺口1: {@code operation_no} = {@code processNo} 直取，不再经 process(V4) UUID 转译；
     * fail-fast: {@code process_no} 未命中 {@code process_master} 视为非法工序。
     */
    void insertProcessSimpleUnitPriceV6(String hfPartNo, List<String> processNos, String customerCode,
                                        ConfigureCatalog cat, String operator) {
        if (customerCode == null || customerCode.isBlank()) return;
        if (processNos == null || processNos.isEmpty()) return;
        // 🚫 B-19①：循环体内零查库（工序元数据走 ConfigureCatalog 的一次 IN 批量装载）。
        List<Map<String, Object>> rows = new ArrayList<>(processNos.size());
        appendSelfProcessFeeRows(rows, hfPartNo, hfPartNo, processNos, cat, 1);
        dsWriter.writeSelfProcessFeeGroup(customerCode, hfPartNo, rows, operator);
    }

    /**
     * 🆕 <b>task-260910 · B-3（AC-3）</b>：外购件的工序落 {@code ds_quote_assembly_fee}。
     *
     * <p>🚨 <b>这不是等价搬运，是业务口径变更</b>（D-6，用户原话：「外购件的工序新规则不存
     * {@code unit_price} 了，这张表已经废弃了，存储 {@code ds_quote} 下的组装加工费的源的表中」）——
     * 费用类别由「<b>自制</b>加工费」变成「<b>组装</b>加工费」，报价金额的计算口径随之改变。
     * 🚫 不要因为「外购件工序看起来还是工序」而把它改回 {@link #insertProcessSimpleUnitPriceV6}。
     *
     * <p>轴值 = 外购件料号本身（外购件不铸新号）。{@code assembly_fee} 恒 0（D-5）。
     * ⚠️ 与 B-2 的组合工艺共用 {@code ds_quote_assembly_fee}，但轴值不同（那边是父料号）⇒ 互不覆盖。
     */
    void insertOutsourcedProcessAssemblyFee(String outsourcedPartNo, List<String> processNos,
                                            String customerCode, ConfigureCatalog cat, String operator) {
        if (customerCode == null || customerCode.isBlank()) return;
        if (outsourcedPartNo == null || outsourcedPartNo.isBlank()) return;
        if (processNos == null || processNos.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(processNos.size());
        int seq = 1;
        for (String opNo : processNos) {                                  // 循环体内零查库
            ProcessMeta pm = cat.process(opNo);
            if (pm == null) throw new IllegalArgumentException("工序不存在: " + opNo);
            rows.add(SelDsQuoteWriter.assemblyFeeRow(outsourcedPartNo, seq++, opNo,
                pm.currency() != null ? pm.currency() : "CNY", pm.unit(), pm.defectRate()));
        }
        dsWriter.writeAssemblyFeeGroup(customerCode, outsourcedPartNo, rows, operator);
    }

    /**
     * B3 → <b>task-260910 · B-2（AC-2）改写</b>: 组合工艺落 {@code ds_quote_assembly_fee}
     * （原 V6 {@code capacity}，{@code resource_group_no='QUOTE_ASSEMBLY'}）。
     * 按 COMBO 整组版本化：轴 = {@code (customer_no, 父料号)}，行集 = 各 {@code process_no}。
     *
     * <p><b>标识锚点 = {@code process_master.process_no}</b>（不再是 {@code composite_process_def.code}）：
     * {@code cp.defCode} 即前端从 {@code GET /composite-processes} 候选选中的
     * {@code process_master.process_no}（如 MRO-AS-0001），与指纹 CPROC /
     * {@code quotation_line_composite_process.def_code} 三处（连同候选端点、前端选值共五处）同一标识
     * （AP-44 精神，PR 自检硬项）。
     *
     * <p><b>列映射</b>（api.md §4）：{@code assembly_operation}←{@code process_no}；
     * {@code item_seq}←行序；{@code currency} 空兜 CNY；{@code pricing_unit}←
     * {@code process_master.standard_unit}（⚠️ 老列名是 {@code capacity_unit}）；
     * {@code defect_rate}←{@code default_defect_rate}（ASSEMBLY 现网 4 行均空 → 落库 NULL）。
     * <p>🚨 {@code assembly_fee} <b>恒写 0</b>（D-5，该列 {@code required=true}，选配不采集单价）；
     * 判据「选配占位 vs 真实 0 元」靠 {@code source='MANUAL'}（AC-2③）。
     * <p>🚫 老 {@code capacity} 的 {@code process_name} / {@code production_type} 在新表<b>无落点</b>，
     * 不许硬塞进别的列（前者可由 {@code assembly_operation} JOIN {@code process_master} 现算，本期不做；
     * 后者老实现写死常量 {@code BATCH_FIXED}，无消费方）。
     *
     * <p>未在 process_master(ASSEMBLY) 命中时不在此处 fail-fast（沿用防御式回退：currency=CNY、
     * 其余 NULL）——真正的存在性校验由同一事务内的
     * {@link #insertCompositeProcessesPerQuote} 通过 {@code process_master} 查找兜底，
     * 非法 defCode 会在那里抛出并回滚本次全部落库（事务原子性，AP-53/B2.4 不变量）。
     */
    void insertCompositeProcessCapacityV6(String parentHfPartNo, String customerCode,
                                          List<com.cpq.configure.dto.CompositeProcessRequest> cps,
                                          ConfigureCatalog cat, String operator) {
        if (parentHfPartNo == null || cps == null || cps.isEmpty()) return;
        if (customerCode == null || customerCode.isBlank()) return;
        // 🚫 B-19 同源治理：原实现在循环里逐条 SELECT process_master；改走 ConfigureCatalog（零查库）。
        List<Map<String, Object>> rows = new ArrayList<>(cps.size());
        int seq = 1;
        for (com.cpq.configure.dto.CompositeProcessRequest cp : cps) {   // 循环体内零查库
            ProcessMeta pm = cat.process(cp.defCode);
            String currency = "CNY";
            String pricingUnit = null;
            BigDecimal defectRate = null;
            // 沿用防御式回退：未在 process_master(ASSEMBLY) 命中时不在此 fail-fast，
            // 真正的存在性校验由同一事务内的 insertCompositeProcessesPerQuote 兜底（命中即整体回滚）。
            if (pm != null) {
                if (pm.currency() != null) currency = pm.currency();
                pricingUnit = pm.unit();
                defectRate = pm.defectRate();
            }
            rows.add(SelDsQuoteWriter.assemblyFeeRow(parentHfPartNo, seq++, cp.defCode,
                currency, pricingUnit, defectRate));
        }
        dsWriter.writeAssemblyFeeGroup(customerCode, parentHfPartNo, rows, operator);
    }

    /**
     * per-quote 工序落库（替代共享 material_bom_item 写法）— 把用户选的工序写进报价行专属的
     * {@code quotation_line_process}（line_item_id × process_no）。
     *
     * <p>task-0712 缺口1(工序 id 契约修复, 方案A加法式变体, V336): {@code process_no} 取代
     * {@code process_id} 作为写入列——标识锚点统一为 {@code process_master.process_no}，
     * FK {@code quotation_line_process_process_no_fkey} → {@code process_master(process_no)}
     * 兜底拒绝非法工序编号。{@code process_id} 列保留但不再写（新行恒为 NULL），收缩阶段
     * (合并 master 时)再做删列迁移。
     *
     * <p>🚩 <b>task-260910 · B-22：下面这段注释已过时，2026-09-10 实测推翻，保留原文只为留痕</b>
     * ——「本表当前无任何 SELECT/视图读取」<b>不成立</b>：{@code QuotationService} 现在在读它
     * （{@code quotation_line_process} 实测 24 行），选配工序的回读/回显走的就是这条路。
     * ⇒ 🚫 <b>不要再按「本表无人读」为由改写/停写它</b>。
     * <p><s>实测(2026-07-14 架构评审 F8)确认：本表当前无任何 SELECT/视图读取</s>——"选配-工序列表"类
     * Tab 当年的渲染走 {@code v_composite_child_processes} 物理 PG 视图，该视图直接读
     * {@code unit_price.operation_no}/{@code material_bom_item.operation_no}
     * （由 {@link #insertProcessSimpleUnitPriceV6}/{@link #insertProcessUnitPriceV6} 写入）。
     * ⚠️ 那条渲染路径本身也已作废：task-260910 S-1 起工序落
     * {@code ds_quote_self_process_fee} / {@code ds_quote_assembly_fee}，
     * 且 {@code v_composite_child_processes} 实测已无活引用（需求文档 §2.2）。
     *
     * <p>per-quote 隔离：只影响当前报价行,不混入导入工序,也不影响别的报价单/基础数据。
     * <ul>
     *   <li>每次按 lineItemId 重建（先删后插），支持重新配置覆盖。</li>
     *   <li>process_no 直接写工序编号字符串，不再经 process(V4) UUID 转译。</li>
     *   <li>必须在 line_item 已创建后调用（FK quotation_line_process→quotation_line_item）。</li>
     * </ul>
     * lineItemId 为空（前端未传报价行 id）时跳过：无行维度无法 per-quote 落库。
     */
    void insertQuotationLineProcesses(UUID lineItemId, List<String> processNos) {
        if (lineItemId == null) return;
        em.createNativeQuery("DELETE FROM quotation_line_process WHERE line_item_id = :lid")
            .setParameter("lid", lineItemId)
            .executeUpdate();
        if (processNos == null || processNos.isEmpty()) return;
        // 🚫 B-19③：原实现逐条 INSERT（工序数 = N ⇒ N 次往返）。改为一条 unnest 批量插入，
        //    DB 往返恒为 1，与工序数无关。
        // 🆕 B-22：seq_no 按 processNos **数组下标 +1** 赋值 —— 本表原本一个顺序列都没有，
        //    读出点只能 `ORDER BY id`（gen_random_uuid ⇒ 随机），AC-11「工序顺序回填」
        //    会「今天绿、下周红」。
        List<String> nos = new ArrayList<>(processNos);
        List<Integer> seqs = new ArrayList<>(nos.size());
        for (int i = 0; i < nos.size(); i++) seqs.add(i + 1);            // 循环体内零查库
        em.createNativeQuery(
                "INSERT INTO quotation_line_process (id, line_item_id, process_no, seq_no) " +
                "SELECT gen_random_uuid(), :lid, t.pn, t.sq " +
                "FROM unnest(CAST(:pns AS varchar[]), CAST(:sqs AS int[])) AS t(pn, sq)")
            .setParameter("lid", lineItemId)
            .setParameter("pns", nos.toArray(new String[0]))
            .setParameter("sqs", seqs.toArray(new Integer[0]))
            .executeUpdate();
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


    // ═══════════════════════════════════════════════════════════════════════
    // task-260902 · 客户产品编号（B-2 / B-8 / B-16 / B-23）
    // ═══════════════════════════════════════════════════════════════════════

    /** {@code sel_product_no} 的唯一索引名 —— 并发下的 23505 靠它归因（B-23）。 */
    private static final String UQ_SPN = "uq_spn_cust_prod";

    /**
     * task-260903 · A-10（A-AC-10）：{@code ds_quote_customer_part} 的唯一索引名。
     * A-6 停写 {@code sel_product_no} 后，并发同编号的仲裁点从 {@code uq_spn_cust_prod}
     * 移到这里，23505 归因也必须跟着换 —— 否则并发冲突会漏成 500。
     */
    private static final String UQ_DQCP = "uq_ds_quote_customer_part";

    /**
     * task-260902 · B-2（AC-1 / AC-2）：客户产品编号必填 + 占用前置检查。
     *
     * <p>占用口径 = <b>{@code sel_product_no}（选配来的） ∪ {@code material_customer_map}（导入来的）</b>
     * —— 两个来源都是「这个客户已经有这个产品编号了」，任一命中都必须挡住并指路「从产品库添加」。
     *
     * <p>📌 <b>检查防不住竞态，索引才能</b>（评审 P2-16 / AC-24）：本方法是 SELECT-then-INSERT 的前半段，
     * 只提供快速反馈；正确性由 {@code uq_spn_cust_prod} 唯一索引 + {@link #insertSelProductNo}
     * 的 23505 异常映射保证。
     */
    @SuppressWarnings("unchecked")
    void assertCustomerProductNoAvailable(String customerCode, String customerProductNo) {
        if (customerProductNo == null || customerProductNo.isBlank()) {
            throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                "CUSTOMER_PRODUCT_NO_REQUIRED", "请填写客户产品编号");
        }
        if (customerCode == null || customerCode.isBlank()) return;   // 无客户无从判重（上游另有校验）
        Object[] hit = findProductNoOwner(customerCode, customerProductNo);
        if (hit != null) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("hfPartNo", hit[0]);
            detail.put("createdAt", hit[1] == null ? null : hit[1].toString());
            throw new com.cpq.configure.exception.MaterialRecipeApiException(
                409, "CUSTOMER_PRODUCT_NO_TAKEN",
                "该编号已存在，请从产品库添加", detail);
        }
    }

    /**
     * 查客户产品编号的现有归属：返回 {@code [quote_part_no, created_at]}，未占用返回 null。
     * 一条 UNION SQL（{@code sel_product_no} ∪ {@code material_customer_map}），零 N+1。
     */
    @SuppressWarnings("unchecked")
    Object[] findProductNoOwner(String customerCode, String customerProductNo) {
        List<Object[]> rows = em.createNativeQuery(
                // 🆕 task-260903 · A-6：并上 ds_quote_customer_part（选配新落点）。
                // sel_product_no 仍留在 UNION 里 —— 它虽已停写，存量 14 行仍是真实占用，
                // 摘掉会让那些编号被判成「可用」，二次分配给别的料号。
                "SELECT material_no AS part_no, created_at FROM ds_quote_customer_part " +
                "WHERE customer_no = :cn AND customer_product_no = :pn " +
                "UNION ALL " +
                "SELECT quote_part_no, created_at FROM sel_product_no " +
                "WHERE customer_no = :cn AND customer_product_no = :pn " +
                "UNION ALL " +
                "SELECT material_no, created_at FROM material_customer_map " +
                "WHERE system_type = 'QUOTE' AND customer_no = :cn AND customer_product_no = :pn " +
                "LIMIT 1")
            .setParameter("cn", customerCode)
            .setParameter("pn", customerProductNo)
            .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * task-260902 · B-2（api.md §2.1）：{@code GET /quotations/configure/check-product-no} 的服务实现。
     * 前端在步骤 1 debounce 400ms 后调用，不阻塞输入，只驱动提示与「下一步」禁用态。
     */
    public Map<String, Object> checkProductNo(String customerNo, String productNo) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (customerNo == null || customerNo.isBlank() || productNo == null || productNo.isBlank()) {
            out.put("taken", false);
            return out;
        }
        Object[] hit = findProductNoOwner(customerNo, productNo);
        if (hit == null) {
            out.put("taken", false);
            return out;
        }
        out.put("taken", true);
        out.put("hfPartNo", hit[0]);
        out.put("createdAt", hit[1] == null ? null : hit[1].toString());
        return out;
    }

    /**
     * task-260902 · B-8 / B-16（AC-12 / AC-12b）：写 {@code sel_product_no} 一行。
     *
     * <p>🚨 <b>B-23 / AC-24（并发）</b>：本 INSERT 就是并发的仲裁点 —— 两个会话同时提交同一编号时，
     * 后者会阻塞在 {@code uq_spn_cust_prod} 上直到先者提交，然后拿到 23505。
     * 这里把它<b>映射成同一个 409 {@code CUSTOMER_PRODUCT_NO_TAKEN}</b>，
     * 🚫 不许漏成 500（前置 SELECT 只是快速反馈，挡不住竞态）。
     */
    void insertSelProductNo(String customerCode, String customerProductNo, String customerProductName,
                            String quotePartNo, UUID quotationId, UUID operatorId) {
        if (customerCode == null || customerCode.isBlank()) return;
        if (customerProductNo == null || customerProductNo.isBlank()) return;
        if (quotePartNo == null || quotePartNo.isBlank()) return;
        try {
            // 🆕 task-260903 · A-6（A-AC-3）：改落 ds_quote_customer_part，sel_product_no 退役
            //    （保留表与存量数据，仅停写 —— 对齐选配模板下线的做法）。
            // 🚨 A-10（A-AC-10）：这里必须是**裸 INSERT**。并发同编号时后者阻塞在
            //    uq_ds_quote_customer_part 上直到前者提交，然后拿到 23505，本方法映射成 409。
            //    🚫 不许改成 PlainTableWriter / ON CONFLICT DO UPDATE —— 那会让两个并发请求
            //    都「成功」，后者静默覆盖前者的料号归属，A-AC-10 的「只有 1 行」断言直接失效。
            // ⚠️ quotation_id 在新表没有对应列（ds_quote_* 是基础资料层，不挂单据维度）。
            //    该列在 sel_product_no 时代仅供追溯，无消费方，故不迁移。
            dsWriter.insertCustomerPart(customerCode, customerProductNo, customerProductName,
                quotePartNo, opOf(operatorId));
        } catch (RuntimeException e) {
            if (isUniqueViolation(e, UQ_DQCP) || isUniqueViolation(e, UQ_SPN)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("customerProductNo", customerProductNo);
                throw new com.cpq.configure.exception.MaterialRecipeApiException(
                    409, "CUSTOMER_PRODUCT_NO_TAKEN", "该编号已存在，请从产品库添加", detail);
            }
            throw e;
        }
    }

    /**
     * 沿异常 cause 链判「是不是 PG 唯一约束冲突（SQLState 23505）」，可选再判约束名。
     * Hibernate 会把它包成 {@code PersistenceException → ConstraintViolationException → PSQLException}，
     * 只看最外层类型判不出来。
     */
    static boolean isUniqueViolation(Throwable e, String constraintNameFragment) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sqle && "23505".equals(sqle.getSQLState())) {
                String msg = String.valueOf(sqle.getMessage());
                return constraintNameFragment == null || msg.contains(constraintNameFragment);
            }
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && constraintNameFragment != null && name.contains(constraintNameFragment)) return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // task-260902 · B-7：外购件候选（api.md §2.2）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * {@code GET /quotations/configure/outsourced-parts} 的服务实现
     * （<b>task-260910 · B-9 改写</b>，api.md §2.2，AC-6）。
     *
     * <p>判据：{@code WHERE ds_quote_material.customer_no = :customerNo AND material_type = '外购件'}。
     * 该判据依赖 task-260903 B-9（选配写入侧不再把材质名塞进 {@code material_type}）已落地，
     * 否则同一列里混着材质名，判据不成立。
     *
     * <h3>🔑 为什么必须带客户（AC-6 是阳性可证伪的）</h3>
     * 实测 5 个外购件料号（{@code S0003} 铆钉配件 / {@code S0007} 弹簧件A / {@code S0011} 密封圈B /
     * {@code S0014} 绝缘座C / {@code T260907-M2} 测试主件B）<b>同时挂在 {@code CUST-0001} 与
     * {@code CUST-0004} 下，共 10 行</b>（2026-09-10 实查：每个 material_no 各 2 行）。
     * 不带客户过滤 ⇒ 列表出双份，而且用户会看到别的客户的料号。
     *
     * <p>📌 <b>本方法原先靠兼容视图 {@code v_compat_material_master} 的 {@code DISTINCT ON}
     * 收敛跨客户重号</b>（V435 / {@code repair-260908 C-1}）。直连新表等于绕过那次修复
     * ⇒ 客户过滤是它的<b>替代品</b>，不是补充。
     * 🚫 <b>不许再加 {@code DISTINCT}</b>：{@code uq_ds_quote_material(customer_no, material_no)}
     * 保证同一客户下每个料号最多一行，重号问题已被客户过滤彻底解决；
     * 再加 DISTINCT 是给已解决的问题打第二个补丁，而且会掩盖将来真正的重复。
     *
     * <p>⚠️ 返回 0 条是<b>正常业务状态</b> —— 候选条数完全取决于该客户名下有多少料号被标成
     * 「外购件」，共享开发库上它随导入随时变。⇒ 🚫 <b>不要把某个具体条数写进判据或断言</b>；
     * 前端必须渲染空态而非「加载中…」（AP-31 族）。
     *
     * <p>N+1：恒 2 条 SQL（count + data），与候选数无关。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listOutsourcedParts(String customerNo, String keyword, int page, int size) {
        // D-2：客户编号必填。🚫 缺失时不许静默跨客户查。
        if (customerNo == null || customerNo.isBlank()) {
            throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                "CUSTOMER_NO_REQUIRED", "外购件候选必须携带客户编号(customerNo)");
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        boolean hasKw = keyword != null && !keyword.isBlank();
        String pattern = hasKw ? "%" + keyword.trim() + "%" : null;

        String where = "customer_no = :cn AND material_type = :t"
            + (hasKw ? " AND (material_no ILIKE :kw OR COALESCE(material_name,'') ILIKE :kw)" : "");

        var countQ = em.createNativeQuery("SELECT COUNT(*) FROM ds_quote_material WHERE " + where)
            .setParameter("cn", customerNo)
            .setParameter("t", MATERIAL_TYPE_OUTSOURCED);
        if (hasKw) countQ.setParameter("kw", pattern);
        long total = ((Number) countQ.getSingleResult()).longValue();

        var dataQ = em.createNativeQuery(
                "SELECT material_no, material_name, specification, unit_weight " +
                "FROM ds_quote_material WHERE " + where + " ORDER BY material_no")
            .setParameter("cn", customerNo)
            .setParameter("t", MATERIAL_TYPE_OUTSOURCED);
        if (hasKw) dataQ.setParameter("kw", pattern);
        dataQ.setFirstResult((safePage - 1) * safeSize);
        dataQ.setMaxResults(safeSize);
        List<Object[]> rows = dataQ.getResultList();

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Object[] r : rows) {                                  // 循环体内零查库（纯 DTO 组装）
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("materialNo", r[0]);
            m.put("materialName", r[1]);
            m.put("specification", r[2]);
            m.put("unitWeight", r[3] == null ? null : new BigDecimal(r[3].toString()));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("items", items);
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // task-260902 · B-11：命中复用时带出销售产品信息（api.md §1.3 / AC-7 状态 C）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 命中复用时带出销售产品信息（api.md §1.3 / task-260902 AC-7 状态 C）。
     *
     * <p><b>固定 2 条 SQL</b>（料号身份 1 条 + 材质构成 1 条），与材质数无关。
     *
     * <h3>🆕 task-260910 · B-10 / B-11 补漏（主线回流，S-2 引用面漏项）</h3>
     * 本方法原有<b>两处</b>老表引用，都在本次切掉：
     * <ol>
     *   <li>🔴 <b>身份段</b>原查 {@code v_compat_material_master} —— <b>已实测造成 HTTP 500</b>：
     *       该兼容视图在 {@code cpq_db_test} 里<b>已不存在</b>（V439「drop compat views」
     *       {@code success=t} 真执行过），在 {@code cpq_db_0724} 里<b>还活着</b>
     *       （2026-09-10 实查 3 个 {@code v_compat_*} 视图仍在）⇒ dev 库上被视图的意外存活掩盖，
     *       test 库上「第 2 次提交同指纹（命中复用）」必现 {@code 42P01 relation does not exist}。
     *       ⚠️ 这条是本任务里「迁移历史与库状态不一致」的典型代价：<b>迁移 success=t 不等于对象已消失</b>，
     *       所以判断「还能不能读它」只能看代码要不要，不能看 dev 库能不能查通。
     *       ⇒ 改读 {@code ds_quote_material} + {@code customer_no}（D-2）。
     *       📌 原先靠视图的 {@code DISTINCT ON} 收敛跨客户重号（V435 / repair-260908 C-1）——
     *       带上客户过滤后 {@code uq_ds_quote_material(customer_no, material_no)} 保证最多一行，
     *       🚫 <b>不要再加 {@code DISTINCT}</b>。</li>
     *   <li>🔴 <b>材质段</b>原查 {@code material_bom_item}（用户 D-1 那批已裁定弃用的表），
     *       且用 {@code characteristic = 'RECIPE'} 当材质判据 —— {@code characteristic} 正是
     *       {@code output_material_type} 在 V6 的对应物，<b>D-1 明确禁止用它判材质</b>
     *       （实测该列 8 种值，{@code 成品} 2645 行，是用户自填业务字段）。
     *       ⇒ 改读 {@code ds_quote_material_bom}，判据换成
     *       <b>「{@code input_material_no} 能 JOIN 上 {@code material_recipe.code}」</b> ——
     *       与 {@link ConfigureSearchResource#searchParts} 的 B-7 判据<b>同源</b>，
     *       🚫 不许在这里写第二套材质判据。</li>
     * </ol>
     *
     * <h3>📌 task-260910 · D-14：曾经的「材质构成返空」缺口已消失</h3>
     * 方案 ②（原 D-7）曾让选配只把 BOM 写进 {@code ds_quote_material_bom_record}、主表等核价通过
     * 才回填 ⇒ 本方法（只在<b>命中复用</b>时被调，而被复用的料号往往正是「刚建、还没核价」那个）
     * 会<b>返空材质数组</b>，当时留了甲/乙/丙三个候选方向待裁决。
     * <p><b>D-14 让选配回到直写主表 ⇒ 该缺口自动消失</b>：料号一建出来主表就有 BOM 行，
     * 本方法直读主表即可拿到完整材质构成，无需任何 {@code _record} 兜底。
     * 🚫 因此<b>不要</b>在这里加 {@code _record} 读取 —— 现在它不解决任何问题，只会重复一份
     * 取数语义（{@code VersionedGroupWriter} 类注释原话：「两套实现必然漂移」）。
     *
     * @param customerCode 客户编号（{@code customer.code}）。为空时两段查询都返空 ⇒ DTO 只带
     *                     {@code hfPartNo}（🚫 不抛异常：本方法是「顺带带出展示信息」的加法式功能，
     *                     不该让它把整个选配提交打成 500 —— 这正是缺陷 1 的教训）
     */
    @SuppressWarnings("unchecked")
    ReusedProductInfoDTO buildReusedProductInfo(String hfPartNo, String customerCode) {
        if (hfPartNo == null || hfPartNo.isBlank()) return null;
        ReusedProductInfoDTO dto = new ReusedProductInfoDTO();
        dto.hfPartNo = hfPartNo;

        // ① 料号身份（1 条 SQL）。ds_quote_material 按 (customer_no, material_no) 唯一 ⇒ 最多 1 行。
        List<Object[]> mm = em.createNativeQuery(
                "SELECT m.material_name, m.specification, m.dimension, m.unit_weight, " +
                "       (SELECT min(sps.created_at) FROM sel_part_signature sps " +
                "          WHERE sps.quote_part_no = m.material_no) " +
                "FROM ds_quote_material m " +
                "WHERE m.customer_no = :cn AND m.material_no = :p")
            .setParameter("cn", customerCode)
            .setParameter("p", hfPartNo).getResultList();
        if (!mm.isEmpty()) {
            Object[] r = mm.get(0);
            dto.partName = r[0] == null ? null : r[0].toString();
            dto.specification = r[1] == null ? null : r[1].toString();
            dto.dimension = r[2] == null ? null : r[2].toString();
            dto.unitWeight = r[3] == null ? null : new BigDecimal(r[3].toString());
            dto.firstCreatedAt = toOffsetDateTime(r[4]);
        }

        // ② 材质构成（1 条 SQL）。判据 = JOIN material_recipe 命中（D-1），
        //    🚫 不带 output_material_type / characteristic 条件。
        List<Object[]> mats = em.createNativeQuery(
                "SELECT b.input_material_no, COALESCE(mr.symbol, mr.name), b.material_ratio " +
                "FROM ds_quote_material_bom b " +
                "JOIN material_recipe mr ON mr.code = b.input_material_no " +
                "WHERE b.customer_no = :cn AND b.material_no = :p " +
                "ORDER BY b.item_seq")
            .setParameter("cn", customerCode)
            .setParameter("p", hfPartNo)
            .getResultList();
        for (Object[] r : mats) {                                  // 循环体内零查库（纯 DTO 组装）
            dto.materials.add(new ReusedProductInfoDTO.Material(
                r[0] == null ? null : r[0].toString(),
                r[1] == null ? null : r[1].toString(),
                r[2] == null ? null : new BigDecimal(r[2].toString())));
        }
        return dto;
    }

    /**
     * task-260910 · E-3（裁决 D-25）：native query 里 {@code TIMESTAMPTZ} 列（如
     * {@code sel_part_signature.created_at} 的 {@code min()} 聚合）经 Hibernate 6 + PG JDBC
     * 驱动实测返回 {@link java.time.Instant}（🔑 <b>不是</b> {@link java.time.OffsetDateTime}）；
     * 同工程内 {@code VariableLabelService} / {@code MaterialRecipeService} 等已有同型兜底，
     * 这里复用同一口径（{@code Instant} / {@code Timestamp} / {@code OffsetDateTime} 三态兜底）。
     * 🚫 <b>不静默吞未知类型</b>：命中不了以上三态时打 warn 日志带上实际 class 名，而不是无声返 null
     * （原实现 {@code r[4] instanceof OffsetDateTime} 判失败就是这个静默吞的反面教材）。
     */
    private static java.time.OffsetDateTime toOffsetDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.OffsetDateTime odt) return odt;
        if (o instanceof java.time.Instant ins) return ins.atOffset(java.time.ZoneOffset.UTC);
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        LOG.warnf("toOffsetDateTime: 未识别的时间类型 class=%s value=%s，按 null 处理（需要补充兜底分支）",
                o.getClass().getName(), o);
        return null;
    }

    // ───────────────────────────────────────────────────────────────────────
    // T21: configure 主入口 + 组合产品 + buildLineItems
    // ───────────────────────────────────────────────────────────────────────

    /**
     * 选配提交的<b>唯一</b>入口 —— 只做一件事：按 {@code bindExistingMaterialNo} 分流。
     *
     * <p>📌 <b>task-260910 · D-14</b>：这里曾有一个 ThreadLocal 报价单作用域
     * （为方案 ② 把 quotationId 旁路传给写入器，因为 {@code _record.quotation_id} NOT NULL）。
     * D-14 让选配回到<b>直写主表</b>，写入侧不再需要 quotationId ⇒ 作用域连同 {@code try/finally}
     * 一并摘除。🚫 不要为了「以后可能用得上」把它加回来。
     *
     * <h3>🆕 task-260910 · B-18（AC-18 / AC-20）：绑定路径</h3>
     * {@code bindExistingMaterialNo} 非空 ⇒ 用户手上已有一个合适的销售料号，只要把客户产品编号
     * 绑上去。<b>跳过</b> {@code prepareParts} / 指纹 / 发号 / BOM 与元素写入
     * （料号与它的 BOM 本来就在库里，🚫 不许重铸、也不许重写别人的 BOM）。
     */
    @jakarta.transaction.Transactional
    public ConfigureProductResponse configure(UUID quotationId,
                                              ConfigureProductRequest req,
                                              UUID operatorId) {
        if (req != null && req.bindExistingMaterialNo != null && !req.bindExistingMaterialNo.isBlank()) {
            return configureByBinding(quotationId, req, operatorId);
        }
        return configureBySelection(quotationId, req, operatorId);
    }

    /**
     * <b>task-260910 · B-18 / B-19 / B-20（AC-18 / AC-19 / AC-20）</b>：直接绑定已有销售料号。
     *
     * <h3>落库面（api.md §2.3，🚫 不多不少）</h3>
     * <table>
     *   <tr><td>{@code ds_quote_customer_part}</td><td>✅ 1 行（{@code source='MANUAL'}）</td></tr>
     *   <tr><td>{@code quotation_line_item}</td><td>✅ 1 行</td></tr>
     *   <tr><td>{@code ds_quote_material}</td><td>❌ 零新增（料号已存在，不铸新号）</td></tr>
     *   <tr><td>{@code ds_quote_material_bom} / {@code _element_bom}（主表与 {@code _record}）</td>
     *       <td>❌ 零新增（沿用该料号既有数据）</td></tr>
     *   <tr><td>{@code sel_part_signature}</td><td>❌ 零新增（不进指纹）</td></tr>
     *   <tr><td>{@code quote_material_no_seq} / {@code quote_customer_code}</td><td>❌ 零新增（不发号）</td></tr>
     * </table>
     *
     * <h3>B-19：{@code _record} 不用特殊处理</h3>
     * 该料号已有主表行 ⇒ 提交时 {@code syncRecordsForFlow} 的投影会锚到那些行
     * （{@code origin_id} 非空），回填判 {@code UNCHANGED} 不升版。
     * 🚫 绑定路径<b>不许</b>凭空往 {@code _record} 里补行 —— 主表已有数据，再写一份
     * {@code origin_id=NULL} 的 {@code _record} 会让回填按新增追加 ⇒ 整组翻倍。
     *
     * <h3>B-20：编号占用复用同一条防线</h3>
     * 前置 {@link #assertCustomerProductNoAvailable}（快速反馈）+ {@link #insertSelProductNo} 的
     * 23505 → 409 映射（竞态下的正确性）。🚫 不写第二套。
     *
     * <h3>🚫 N+1</h3>
     * 固定 4 条 SQL：客户 id 1 + 客户码 1 + 编号占用 1 + 料号存在性 1，再加落库 2 条
     * （{@code quotation_line_item} 1 + {@code ds_quote_customer_part} 1）。零循环。
     */
    @SuppressWarnings("unchecked")
    ConfigureProductResponse configureByBinding(UUID quotationId,
                                                 ConfigureProductRequest req,
                                                 UUID operatorId) {
        String materialNo = req.bindExistingMaterialNo.trim();
        // 🚫 互斥：两者同时非空说明前端把两条路混了，静默取一条会让用户以为配件也提交了。
        if (req.parts != null && !req.parts.isEmpty()) {
            throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                "BIND_AND_PARTS_EXCLUSIVE",
                "「直接绑定已有销售料号」与「选配配件」互斥，请只选一种");
        }
        UUID customerId = getCustomerIdFromQuotation(quotationId);
        String customerCode = getCustomerCodeFromCustomerId(customerId);

        // AC-19：编号占用仍被硬拦（与选配路径同一条防线）。
        assertCustomerProductNoAvailable(customerCode, req.customerProductNo);

        // AC-18 边界：料号必须在**该客户**的 ds_quote_material 里（D-2 客户维度）。
        List<Object> exists = em.createNativeQuery(
                "SELECT 1 FROM ds_quote_material WHERE customer_no = :cn AND material_no = :mn")
            .setParameter("cn", customerCode)
            .setParameter("mn", materialNo)
            .getResultList();
        if (exists.isEmpty()) {
            throw com.cpq.configure.exception.MaterialRecipeApiException.badRequest(
                "BIND_MATERIAL_NOT_FOUND",
                "销售料号不存在（客户 " + customerCode + " 名下）: " + materialNo);
        }

        UUID lineItemId = insertLineItem(quotationId, materialNo, null, "SIMPLE",
            parseUuidOrNull(req.tempId), req.customerProductNo);
        insertSelProductNo(customerCode, req.customerProductNo, req.customerProductName,
            materialNo, quotationId, operatorId);

        ConfigureProductResponse resp = new ConfigureProductResponse();
        resp.lineItems = new ArrayList<>(List.of(
            buildLineItemDTO(lineItemId, materialNo, "SIMPLE", null, List.of())));
        resp.fingerprintMatched = false;          // 🚫 不进指纹（api.md §2.3）
        resp.reusedHfPartNos = List.of();
        resp.productType = "SIMPLE";
        resp.structureVersion = SalesFingerprintCalculator.STRUCTURE_VERSION;
        resp.reusedProductInfo = null;
        return resp;
    }

    ConfigureProductResponse configureBySelection(UUID quotationId,
                                                   ConfigureProductRequest req,
                                                   UUID operatorId) {
        // B2.3: 后端裁决的有效 productType（Σqty 兜底），全程用它分发，不再信 req.productType。
        String effectiveType = validateRequest(req);

        // task-260901 · B-17（task-260902 下沉到 material 级）：材质来源（标准配置 configNo /
        // 自定义 elements）必须在**指纹计算之前**解析，否则 buildSalesConfigContext 会拿着空的
        // elements 算指纹 → 不同配置坍缩成同一个报价料号。
        //
        // ⚠️ 校验顺序刻意为「先配件、后客户产品编号」：configureProduct 的既有调用方
        // （task-260901 的接口层用例）不带 customerProductNo，先判编号会把材质类错误
        // （403 / 409 / 400 AMBIGUOUS）全部掩盖成「编号必填」，那些用例验的就不再是它们要验的东西。
        List<String> defCodes = req.compositeProcesses == null ? List.of()
            : req.compositeProcesses.stream().map(cp -> cp.defCode).collect(Collectors.toList());

        // P4 批2 补丁: 从 quotation 拉 customer_id，传给 resolvePart → insertProcesses
        // 🆕 task-260910 · B-10：客户码的派生**上移到 prepareParts 之前** —— loadCatalog ⑥
        //    （外购件料号）已改按 customer_no 隔离取数（D-2），拿不到客户码就查不出候选。
        //    ⚠️ 顺序变化只影响「quotation 不存在」这一种错误的抛出时机（现在更早），
        //    原注释所说「先配件、后客户产品编号」的校验顺序（材质错误 vs 编号占用）不受影响 ——
        //    assertCustomerProductNoAvailable 仍在 prepareParts 之后。
        UUID customerId = getCustomerIdFromQuotation(quotationId);
        // 报价侧新表 customer_no 用 customer.code（非 UUID），派生一次贯穿落库
        String customerCode = getCustomerCodeFromCustomerId(customerId);

        ConfigureCatalog catalog = prepareParts(customerCode, req.parts, defCodes);

        // 选配 Plan 3b (T3): 客户维度销售上下文 — 每 part 的 EnabledParam 投影，
        // 供 SalesFingerprintCalculator.computeSimple/computeComposite 计算客户维度指纹。
        // T4 起 resolvePart(SIMPLE custom 分支) 已消费 salesCtx 做销售侧发号判复用；
        // COMPOSITE 分支消费为 T5 范围。
        SalesConfigContext salesCtx = buildSalesConfigContext(customerCode, req);

        List<String> childHfPartNos = new ArrayList<>();
        List<String> reused = new ArrayList<>();

        // task-260902 · B-2 / AC-1 / AC-2：客户产品编号必填 + 占用硬拦（前端拦是体验，后端拦是正确性）。
        assertCustomerProductNoAvailable(customerCode, req.customerProductNo);

        // PASS 1: 解析每个配件
        for (PartRequest pr : req.parts) {
            childHfPartNos.add(resolvePart(pr, operatorId, customerId, customerCode, reused, salesCtx, catalog));
        }

        // PASS 2: 组合产品父级
        String parentHfPartNo = null;
        if ("COMPOSITE".equals(effectiveType)) {
            // 选配 Plan 3b (T5): 生产侧全局指纹发号 → 销售侧客户维度指纹发号 swap（同 T4 SIMPLE）。
            // R6: 组合体也强制 customerCode 非空 — 父报价料号内嵌客户四位码；组合体可能全 existing
            // 子件（未走 resolvePart custom 分支）却仍需为父级发号，故此处独立校验。
            if (customerCode == null || customerCode.isBlank()) {
                throw new IllegalArgumentException(
                    "选配 COMPOSITE 组合体需要 customerCode（报价料号内嵌客户码），quotation 无客户不能发号");
            }

            // 销售侧客户维度组合体指纹（childQuotePartNos + childQtys 配对排序集合 + compositeProcessCodes
            // + customerCode），取代生产侧 compositeFingerprint 全局复用。
            // code review Important #1: 指纹必须纳入装配用量与组合工艺，否则同客户同子件集但 qty/工序
            // 不同会误命中复用 → 命中即跳过父级落库 → 静默丢弃新 qty/工序 → 错价。
            List<Integer> childQtys = req.parts.stream()
                .map(pr -> (pr.quantity == null || pr.quantity < 1) ? 1 : pr.quantity)
                .collect(Collectors.toList());
            // B6: cp.defCode 语义已变为 process_master.process_no（架构决策 2-2A），算法不变，
            // 仅口径值域变化（CPROC token 现为工序编号，如 MRO-AS-0001）。
            List<String> compositeProcessCodes = req.compositeProcesses == null ? List.of()
                : req.compositeProcesses.stream().map(cp -> cp.defCode).collect(Collectors.toList());
            var sig = salesFp.computeComposite(salesCtx.customerNo, childHfPartNos, childQtys, compositeProcessCodes);
            String hit = sigRepo.lookup(salesCtx.customerNo, SalesFingerprintCalculator.STRUCTURE_VERSION, sig.hash());
            if (hit != null) {
                // R3: 命中复用父级 → 整体跳过父级落库（数据首次已落，幂等，勿重复累加，守 AP-51）
                reused.add(hit);
                parentHfPartNo = hit;
            } else {
                // ⚠️ 不变量（同 T4）：mintAndRegister + insertOrReadExisting + 下方父级 V6 落库须同处
                // configure 同一事务（REQUIRED，勿改 REQUIRES_NEW）——保证签名可见 ⇔ V6 数据可见，
                // 否则并发败者复用先赢父号时先赢 V6 未提交 → Tab 空。
                parentHfPartNo = quoteAllocator.mintAndRegister(salesCtx.customerNo, salesCtx.yyMm);
                String registered = sigRepo.insertOrReadExisting(
                    salesCtx.customerNo, SalesFingerprintCalculator.STRUCTURE_VERSION, sig.hash(), sig.text(),
                    parentHfPartNo, "COMPOSITE");
                if (registered == null) {
                    throw new IllegalStateException(
                        "sel_part_signature 冲突但回读为空(COMPOSITE): fp=" + sig.hash());
                }
                if (!registered.equals(parentHfPartNo)) {
                    // 并发败者：先赢者已落父级 V6，复用其父号，弃己 mint 号(孤儿可接受)，跳过本次落库
                    reused.add(registered);
                    parentHfPartNo = registered;
                } else {
                    // 先赢者：落父级 V6（R1: config_fingerprint=null，防跨客户撞全局唯一索引）+ 组合
                    // BOM + 工序 + 组合工艺。childQtys/compositeProcessCodes 已在上方指纹计算前算好，直接复用。
                    // V6 双写（AP-53 续 6 Phase 1）：确保父料号 + 子件 ASSEMBLY → material_master /
                    // material_bom_item，让 zcj_bom / composite_child_materials_mirror 视图渲染子配件
                    // 清单（渲染基线零改）。幂等 ON CONFLICT（material_master DO NOTHING /
                    // material_bom_item DO UPDATE composition_qty）。
                    // 🚨 B-17②（§4.3 的「一列两义」其实是一列三义）：这里原本写字面量 "COMPOSITE"
                    //    —— 那是**产品结构类型**，不是料号类型。B-9 把选配写入侧的 material_type 归位为
                    //    料号类型后，本处必须一并归位，否则 material_type 仍混着第三种语义，
                    //    §S-6 的外购件判据（material_type='外购件'）以及导入侧的类型分布都会被污染。
                    //    组合产品的父料号在**业务语义**上是可对外报价的成品；但新表值域里没有「成品」，
                    //    且 2026-09-04 用户裁决要求主产品入库按「零件」存 ⇒ 落库值 = 零件（见下）。
                    // ⚠️ v_composite_child_materials 的第二 UNION 分支 COALESCE(mm.material_type, mm.material_name)
                    //    对本料号不生效：buildCompositeBomRows 会给父料号写 RECIPE 行
                    //    ⇒ 父料号命中第一分支。⚠️ task-260903 起 component_usage_type 由兼容视图
                    //    按 input_material_no JOIN material_recipe 现算，而这里的 input_material_no
                    //    是子件报价料号 ⇒ JOIN 落空，材质名降级到 COALESCE 兜底（见 buildCompositeBomRows）。
                    // 🆕 task-260903 · A-1：父料号主档改落 ds_quote_material。
                    // 🚨 2026-09-04 用户裁决（需求文档 A-AC-7③）**覆盖**了原来的「传 null」处置：
                    //    用户原话「选配的数据的主产品在入库时,物料表中主产品的类型应该是[零件]」
                    //    ⇒ 组合产品父料号写 TYPE_PART。
                    //    🚩 「V6 的第三态『成品』在新表值域（零件/外购件）里没有位置」这个**事实**仍成立，
                    //       变的是处置：从「留 NULL」改为「按用户裁决归入零件」。
                    //    🚫 原注释「不许拿『零件』凑数」已作废，不要依据它改回 null ——
                    //       A-AC-7 的判据是：选配铸出的料号里 material_type IS NULL 的行数 = 0。
                    // 🆕 A-AC-11：category_code 一律写「默认分类」000000。
                    dsWriter.upsertMaterial(customerCode, parentHfPartNo, null, null, null, null,
                        SelDsQuoteWriter.TYPE_PART, SelDsQuoteWriter.CATEGORY_DEFAULT, opOf(operatorId));
                    // 选配 COMBO 落库：统一走 VersionedGroupWriter（经 SelDsQuoteWriter）——
                    // 整组指纹相同则一行不写 / 不同则归档 + max(当前,历史)+1 升版。
                    // 🆕 task-260903 · A-2 / A-4（A-AC-6）：父级 BOM 改落 ds_quote_material_bom。
                    // 🚨 ASSEMBLY 行与 RECIPE 行**必须合并成一次 writeGroup** —— V6 时代它们是
                    //    characteristic 区分的两个独立组，新表里同属 material_no 这一个轴值。
                    dsWriter.writeMaterialBomGroup(customerCode, parentHfPartNo,
                        buildCompositeBomRows(parentHfPartNo, childHfPartNos, childQtys), opOf(operatorId));
                    insertProcessUnitPriceV6(parentHfPartNo, customerCode, req.parts, childHfPartNos,
                        catalog, opOf(operatorId));
                    insertCompositeProcessCapacityV6(parentHfPartNo, customerCode, req.compositeProcesses,
                        catalog, opOf(operatorId));
                }
            }
        }

        // PASS 3: line_items (解法 B: 传 req.tempId 给 buildLineItems 作 parent line item id)
        UUID tempId = parseUuidOrNull(req.tempId);
        List<Map<String, Object>> lineItems =
            buildLineItems(quotationId, req, parentHfPartNo, childHfPartNos, tempId, effectiveType, catalog);

        // task-260902 · B-8 / AC-12 / AC-12b：写「客户产品编号 → 销售料号」映射。
        // 🚫 不写 material_customer_map —— 方案甲的核心就是不动 mcm（uq_mcm_quote_no 同时是
        //    upsertQuote 的 ON CONFLICT target 与跨客户串号防线，且 4 个组件视图的 JOIN 不含编号维度）。
        // ⚠️ 复用场景（AC-7 命中指纹）**同样写一行** —— 这正是 AC-12b 要的「一料号多编号」。
        String productPartNo = "COMPOSITE".equals(effectiveType) ? parentHfPartNo
            : (childHfPartNos.isEmpty() ? null : childHfPartNos.get(0));
        insertSelProductNo(customerCode, req.customerProductNo, req.customerProductName,
            productPartNo, quotationId, operatorId);

        ConfigureProductResponse resp = new ConfigureProductResponse();
        resp.lineItems = lineItems;
        resp.fingerprintMatched = !reused.isEmpty();
        resp.reusedHfPartNos = reused;
        resp.productType = effectiveType;
        resp.structureVersion = SalesFingerprintCalculator.STRUCTURE_VERSION;   // B-11
        if (!reused.isEmpty()) {
            resp.reusedProductInfo = buildReusedProductInfo(reused.get(0), customerCode);  // B-11 / AC-7 状态 C
        }
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

    /**
     * B2.3（✅ 架构决策1-A 定稿，backtask）: 校验请求 + 按 Σqty 兜底裁决 SIMPLE/COMPOSITE，
     * 不盲信前端 {@code req.productType}（前后端同口径）。
     *
     * <ul>
     *   <li>Σqty = Σ parts[].quantity（null/&lt;1 兜底为 1，与 {@link #configure} 内 childQtys 同口径）；
     *       Σqty==1 → SIMPLE；Σqty≥2 → COMPOSITE。</li>
     *   <li>单行 qty≥2（parts.size()==1 但 Σqty≥2）= 父 COMPOSITE + 1 个去重子件
     *       composition_qty=qty（D12/D17），不展开成多子件——与 {@code computeComposite} 现役口径 +
     *       导入 §3 ASSEMBLY 同形，直接复用 {@code configure} 既有 COMPOSITE 分支代码
     *       （该分支对 N=1 子件天然兼容，无需单独分支）。</li>
     *   <li>放开两闸门（本决策唯一新增改点）：① COMPOSITE 下限从 parts.size()&gt;=2 改为 Σqty&gt;=2
     *       （parts.size() 上限 ≤8 保留，指去重子件行数，与 productType 无关全程校验）；
     *       ② 组合工艺 participatingPartIndexes 硬校验从 &gt;=2 放开为非空即可
     *       （允许"单去重子件 qty≥2"绑组合工艺，否则单行 qty2 选组合工艺会 400）。</li>
     * </ul>
     *
     * @return 后端裁决后的有效 productType（"SIMPLE" 或 "COMPOSITE"），供 {@link #configure} 后续分发。
     *
     * <p>🆕 <b>task-260910 · B-18（AC-20）</b>：<b>绑定路径不经过本方法</b> ——
     * {@link #configure} 在分流时就把 {@code bindExistingMaterialNo} 非空的请求交给
     * {@link #configureByBinding}。本方法「{@code parts} 必填」这条硬校验因此对绑定路径
     * 天然不成立，🚫 <b>不要在这里加 {@code bindExistingMaterialNo} 的旁路条件</b> ——
     * 那会让「选配路径漏传 parts」也被放行（本方法是选配路径唯一的 parts 非空防线）。
     */
    String validateRequest(ConfigureProductRequest req) {
        if (req == null) throw new IllegalArgumentException("request body 必填");
        if (!"SIMPLE".equals(req.productType) && !"COMPOSITE".equals(req.productType)) {
            throw new IllegalArgumentException("productType must be SIMPLE or COMPOSITE");
        }
        if (req.parts == null || req.parts.isEmpty()) {
            throw new IllegalArgumentException("parts 必填");
        }
        if (req.parts.size() > 8) {
            throw new IllegalArgumentException("parts.size 上限 8（去重子件行数）");
        }

        int totalQty = req.parts.stream()
            .mapToInt(pr -> (pr.quantity == null || pr.quantity < 1) ? 1 : pr.quantity)
            .sum();
        String effectiveType = (totalQty == 1) ? "SIMPLE" : "COMPOSITE";

        if ("COMPOSITE".equals(effectiveType) && req.compositeProcesses != null) {
            for (com.cpq.configure.dto.CompositeProcessRequest cp : req.compositeProcesses) {
                if (cp.participatingPartIndexes == null || cp.participatingPartIndexes.isEmpty()) {
                    throw new IllegalArgumentException("组合工艺参与配件为空: " + cp.defCode);
                }
            }
        }
        return effectiveType;
    }

    // insertAssemblyBom 已在 Phase 3 移除（V44 mat_bom ASSEMBLY 写入停用）
    // insertCompositeProcesses 已在 Phase 3 移除（V44 mat_composite_process 写入停用）

    /**
     * B6（架构决策 2-2A 定稿）: 校验组合工艺标识存在于工序库 {@code process_master}(ASSEMBLY)，
     * 取代旧 {@code CompositeProcessDef.findByCodeOrThrow}。非法/不存在 → fail-fast 400，
     * 与 {@link #insertCompositeProcessCapacityV6} 同处一个事务，命中即整体回滚（B2.4 不变量）。
     */
    private void assertAssemblyProcessExists(String processNo, ConfigureCatalog cat) {
        // 🚫 B-19 同源治理：改走 ConfigureCatalog（入口一次 IN 批量装载），循环体内零查库。
        // B-12：分类值域接受 ASSEMBLY 与中文「组装」两种写法，理由见
        // CompositeProcessService.ASSEMBLY_CATEGORIES 的注释（库里实值是中文）。
        ProcessMeta pm = cat.process(processNo);
        if (pm == null || !CompositeProcessService.ASSEMBLY_CATEGORIES.contains(pm.category())) {
            throw new IllegalArgumentException(
                "组合工艺未找到或非 ASSEMBLY 工序(process_master.process_no): " + processNo);
        }
    }

    /**
     * per-quote 组合工艺写入(取代 mat_composite_process 作渲染源)。
     * 把 configure 请求里"参与配件下标"解析成子件料号,写进 quotation_line_composite_process
     * (按 line_item_id 隔离),并返回解析后的步骤列表 —— 供配置响应带回前端,使 saveDraft
     * 全量重建(换 line id)后能从 draft payload 重写,跨保存存活(同 quotation_line_process 机制)。
     *
     * <p>B6（架构决策 2-2A 定稿）: 存在性校验由 {@code CompositeProcessDef.findByCodeOrThrow}
     * 改为 {@link #assertAssemblyProcessExists}（{@code process_master} ASSEMBLY），
     * {@code def_code} 列语义随之变为"工序编号"（值 = {@code process_master.process_no}）。
     */
    List<Map<String, Object>> insertCompositeProcessesPerQuote(
            UUID lineItemId,
            List<com.cpq.configure.dto.CompositeProcessRequest> cps,
            List<String> childHfPartNos,
            ConfigureCatalog cat) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (lineItemId == null || cps == null || cps.isEmpty()) return out;
        com.fasterxml.jackson.databind.ObjectMapper om =
            new com.fasterxml.jackson.databind.ObjectMapper();
        int seq = 1;
        for (com.cpq.configure.dto.CompositeProcessRequest cp : cps) {
            assertAssemblyProcessExists(cp.defCode, cat);
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

    /**
     * 解法 B: 支持前端传入 tempId 作为主 line item UUID。
     * SIMPLE: tempId = 该唯一 line item 的 id；
     * COMPOSITE: tempId = 父 line item 的 id，子 line item 仍自动生成。
     *
     * <p>B2.3: {@code effectiveType} 由 {@link #validateRequest} 按 Σqty 裁决后传入
     * （不再读 {@code req.productType}，防止前端声明与后端裁决不一致时静默走错分支）。
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> buildLineItems(UUID quotationId,
                                             ConfigureProductRequest req,
                                             String parentHfPartNo,
                                             List<String> childHfPartNos,
                                             UUID tempId,
                                             String effectiveType,
                                             ConfigureCatalog cat) {
        List<Map<String, Object>> out = new ArrayList<>();

        if ("SIMPLE".equals(effectiveType)) {
            String pn = childHfPartNos.get(0);
            UUID id = insertLineItem(quotationId, pn, null, "SIMPLE", tempId, req.customerProductNo);
            // per-quote 工序：选配工序写报价行专属 quotation_line_process（行已建，满足 FK）
            PartRequest simplePr = (req.parts != null && !req.parts.isEmpty()) ? req.parts.get(0) : null;
            insertQuotationLineProcesses(id, simplePr != null ? simplePr.processNos : null);
            out.add(buildLineItemDTO(id, pn, "SIMPLE", null, simplePr != null ? simplePr.processNos : null));
            return out;
        }

        // COMPOSITE: 父 + N 子 (父用 tempId; 子 line item 自动生成，
        // 各子件的 quotationLineItemId 通过 PartRequest.quotationLineItemId 传入)
        UUID parentId = insertLineItem(quotationId, parentHfPartNo, null, "COMPOSITE", tempId,
            req.customerProductNo);
        // per-quote 组合工艺:写本报价行专属表(取代 mat_composite_process 作渲染源),并把解析后的
        // 工艺步骤带回父行 DTO,供前端透传到 saveDraft 跨保存存活(全量重建换 line id 后重写)。
        Map<String, Object> parentDto = buildLineItemDTO(parentId, parentHfPartNo, "COMPOSITE", null);
        List<Map<String, Object>> cprocs =
            insertCompositeProcessesPerQuote(parentId, req.compositeProcesses, childHfPartNos, cat);
        parentDto.put("compositeProcesses", cprocs);
        out.add(parentDto);

        for (int i = 0; i < childHfPartNos.size(); i++) {
            String childPn = childHfPartNos.get(i);
            // 子件 line item: 优先用对应 PartRequest.quotationLineItemId 作子 id（前端可选传）
            PartRequest childPr = (req.parts != null && i < req.parts.size()) ? req.parts.get(i) : null;
            UUID childTempId = (childPr != null) ? parseUuidOrNull(childPr.quotationLineItemId) : null;
            UUID childId = insertLineItem(quotationId, childPn, parentId, "PART", childTempId,
                req.customerProductNo);
            // per-quote 工序：子件行的选配工序写 quotation_line_process
            insertQuotationLineProcesses(childId, childPr != null ? childPr.processNos : null);
            out.add(buildLineItemDTO(childId, childPn, "PART", parentId, childPr != null ? childPr.processNos : null));
        }
        return out;
    }

    UUID insertLineItem(UUID quotationId, String hfPartNo,
                        UUID parentLineItemId, String compositeType) {
        return insertLineItem(quotationId, hfPartNo, parentLineItemId, compositeType, null, null);
    }

    /**
     * 向后兼容重载：不传客户产品编号 ⇒ {@code customer_part_no} 写 NULL
     * （行为与 repair-260911 B-1 之前逐字一致）。
     */
    UUID insertLineItem(UUID quotationId, String hfPartNo,
                        UUID parentLineItemId, String compositeType, UUID tempId) {
        return insertLineItem(quotationId, hfPartNo, parentLineItemId, compositeType, tempId, null);
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
     *
     * <h4>task-260910 · D-38（修 {@code BL-0202}）：本行必须写 {@code template_id}</h4>
     *
     * <p><b>缺陷形态（准确版）</b>：选配确认后<b>当场能渲染</b>（前端
     * {@code QuotationWizard.tsx:1837} 有 {@code li.templateId || customerTemplateId} 的
     * <b>内存</b>兜底），<b>但一刷新就渲染不出来</b> —— 那个兜底从未被持久化，重新进编辑页
     * 拿到的 {@code lineItem.templateId} 仍是 NULL，{@code QuotationWizard.tsx:659} 的
     * {@code if (!li.templateId) return li;} 直接跳过 enrich ⇒ {@code componentType} 补不上 ⇒
     * {@code QuotationStep2.tsx} 的 {@code .filter(c => c?.componentType === 'NORMAL')} 过滤后为空 ⇒
     * 卡片内容区落到「请通过添加产品选择模板后自动加载组件结构」、产品小计 ¥0。
     *
     * <p><b>为什么原先「留空」不是有意设计</b>（四条证据，2026-09-10 查证）：
     * <ol>
     *   <li>V30 的标题是「导入 v4 改造」，注释只为 {@code product_id} 辩护（导入产品不在
     *       {@code product} 表）；{@code template_id} 那行是跟着放开的、没有自己的理由。且 V30
     *       远早于选配（T20/T21），当时不可能为选配行设计什么。</li>
     *   <li>V30 亲手服务的导入链路至今照写 {@code template_id}
     *       （{@code QuotationLineItemMaterializeService:100-103} 的列清单第三列）
     *       ⇒ 放开约束换来的是「可以为空」，不是「应该为空」。</li>
     *   <li>同一个兜底在 {@code QuotationService:557}（saveDraft 逐行）与 {@code :3041}（batch）
     *       早已存在，注释里连「选配产品行」都点名了。但 AC-12 明写「全程不点保存草稿」
     *       ⇒ 那道兜底在本路径上<b>永不执行</b>，所以必须提前到落行这一刻。</li>
     *   <li>后端渲染管线本来就按报价单级模板算这一行（{@code CardSnapshotService:737}/{@code :1166}
     *       传的是 {@code q.customerTemplateId}，不是 {@code li.templateId}）；实测选配行的
     *       {@code quotation_line_component_data} 的 {@code component_id} 5/5 全部命中该模板的
     *       {@code components_snapshot} ⇒ <b>写 FK 是把已生效的事实补登记，不是给选配行强安模板</b>。</li>
     * </ol>
     *
     * <p><b>取值与空值语义</b>：值 = {@code quotation.customer_template_id}，用 INSERT 内联子查询取
     * （同表同主键，<b>不额外发查询</b>，SQL 条数与行数无关）。报价单还没有模板时写入 NULL、
     * <b>不抛异常</b> —— 「先加产品后选模板」是产品上允许的路径
     * （{@code CreateQuotationRequest.java:32}「留空则后续在报价单 Step2 中由用户手工选择」），
     * 用户在 Step2 选定模板后由 {@code QuotationService:557} 的既有兜底补齐，链路自愈。
     *
     * <p>🚦 <b>经用户裁决接受的行为变化（2026-09-10，不是顺带副作用，请勿当回归去「修」）</b>：
     * {@code template_id} 非空后，选配行开始参与
     * {@code MaterialVersionUpgradeService:356/568} 的调价重算
     * （原先 {@code CardSnapshotService:2832} 在 {@code templateId == null} 处直接返回空）。
     * 裁决理由：导入行一直在参与，选配行不参与只是本缺陷的副作用，与 D-14「两侧功能保持一致」相符。
     *
     * <p>📌 <b>存量数据按用户裁决「只修新建路径、不回填」</b>：不追加回填迁移、不跑批量 UPDATE。
     * 存量 DRAFT 行靠 saveDraft 既有兜底自愈；已审批单保持现状（详见任务回报 §2）。
     *
     * <h4>repair-260911 · B-1（裁决 {@code R-3}，AC-R1）：本行还必须写 {@code customer_part_no}</h4>
     *
     * <p><b>缺陷形态</b>：选配加完产品，卡片「产品」页签的「客户产品编号」<b>永远空白</b>
     * （用户 2026-09-11 真机验收第 1 条）。
     *
     * <p><b>根因链路</b>：组件「产品」({@code 221dc766}) 的视图把客编做成<b>行级维度</b>谓词
     * {@code LEFT JOIN ds_quote_customer_part dqcp ON … AND dqcp.customer_product_no = ANY(:customerProductNos)}；
     * 该占位符的值由 {@code SqlViewExecutor#enrichRowScopeSets} →
     * {@code SELECT DISTINCT customer_part_no FROM quotation_line_item WHERE quotation_id = ?} 取得
     * （「语义图列 {@code customer_product_no} → 明细行列 {@code customer_part_no}」的映射见
     * {@code RowScopeSupport.LINE_ITEM_COLUMN_ALIAS}（{@link com.cpq.semanticgraph.service.RowScopeSupport}））。
     * 本方法原先<b>不写这一列</b> ⇒ 整单集合为空 ⇒ {@code = ANY(ARRAY[]::text[])} 恒 false ⇒
     * JOIN 不匹配 ⇒ {@code _客户料号_客户产品编号} 为 NULL ⇒ 卡片空白。
     * {@link com.cpq.component.service.RowScopeProjector} 的逐行投影同样取这一列，也一并落空。
     *
     * <p><b>实证（2026-09-11 全库口径，选配建的行）</b>：从没点过保存草稿且编号为空 <b>35</b> 行；
     * <b>点过</b>保存草稿且编号仍为空 <b>4</b> 行 ⇒ {@code saveDraft} <b>不补</b>这一列
     * （与 {@code template_id} 不同 —— 那个有 {@code QuotationService:557} 的兜底）
     * ⇒ 必须在落行这一刻写。
     *
     * <p><b>取值</b>：{@code ConfigureProductRequest#customerProductNo}，即选配第 1 步用户填的
     * 客户产品编号，与 {@link #insertSelProductNo} 写进 {@code ds_quote_customer_part} 的<b>同一个值</b>
     * —— 两边同源才能 JOIN 得上。<b>空值语义与 {@code template_id} 同款：写 NULL、不抛异常</b>。
     *
     * <p><b>四条落行路径全覆盖</b>（都经本方法）：绑定已有销售料号（{@link #configureByBinding}）·
     * SIMPLE · COMPOSITE 父 · COMPOSITE 子（{@code PART}）。子件行也写同一个客编 ——
     * 它们是同一个客户产品的组成部分，且整单 {@code DISTINCT} 集合不因此改变。
     *
     * <p>🚫 <b>不回填存量</b>（{@code R-3} 同 {@code D-38} 口径）：不加迁移、不跑批量 UPDATE。
     */
    UUID insertLineItem(UUID quotationId, String hfPartNo,
                        UUID parentLineItemId, String compositeType, UUID tempId,
                        String customerPartNo) {
        UUID id = (tempId != null) ? tempId : UUID.randomUUID();
        // R-3 / B-1: 空白 → NULL（与 insertSelProductNo 的 isBlank 早退同口径），
        //            🚫 不写空串 —— RowScopeSupport 的集合谓词把空串当成一个真实值。
        String cpn = (customerPartNo == null || customerPartNo.isBlank()) ? null : customerPartNo.trim();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item " +
                "(id, quotation_id, product_part_no_snapshot, " +
                "parent_line_item_id, composite_type, sort_order, created_at, template_id, " +
                "customer_part_no) " +
                // D-38: template_id 取本报价单的 customer_template_id（内联子查询，不多发查询）；
                //       报价单尚未选模板时得到 NULL —— 与改动前一致，不抛异常。
                "VALUES (:id, :q, :pn, :pp, :ct, 0, NOW(), " +
                "        (SELECT customer_template_id FROM quotation WHERE id = :q), :cpn)")
            .setParameter("id", id)
            .setParameter("q", quotationId)
            .setParameter("pn", hfPartNo)
            .setParameter("pp", parentLineItemId)
            .setParameter("ct", compositeType)
            .setParameter("cpn", cpn)
            .executeUpdate();
        return id;
    }

    Map<String, Object> buildLineItemDTO(UUID id, String hfPartNo,
                                          String compositeType, UUID parentId) {
        return buildLineItemDTO(id, hfPartNo, compositeType, parentId, null);
    }

    Map<String, Object> buildLineItemDTO(UUID id, String hfPartNo,
                                          String compositeType, UUID parentId, List<String> processNos) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("productPartNo", hfPartNo);
        m.put("compositeType", compositeType);
        m.put("parentLineItemId", parentId);
        // task-0712 缺口1: 选配工序回传前端(process_master.process_no 字符串列表，
        // 取代旧 process(V4) UUID)，使其能在 saveDraft 回写 quotation_line_process(工序跨保存存活)
        m.put("processNos", processNos != null ? processNos : java.util.List.of());
        return m;
    }
    // T21: configure 主入口 + 组合产品 + buildLineItems — 完成
}
