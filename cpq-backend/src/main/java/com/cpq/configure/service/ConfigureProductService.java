package com.cpq.configure.service;

import com.cpq.configure.FingerprintCalculator;
import com.cpq.configure.FingerprintCalculator.ElementInput;
import com.cpq.configure.SalesFingerprintCalculator;
import com.cpq.configure.SalesFingerprintCalculator.ElementPct;
import com.cpq.configure.SalesFingerprintCalculator.EnabledParam;
import com.cpq.configure.dto.ConfigureProductRequest;
import com.cpq.configure.dto.ConfigureProductResponse;
import com.cpq.configure.dto.ElementOverride;
import com.cpq.configure.dto.LookupFingerprintRequest;
import com.cpq.configure.dto.LookupFingerprintResponse;
import com.cpq.configure.dto.PartRequest;
import com.cpq.configure.dto.SalesConfigContext;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import com.cpq.partno.PartNoContext;
import com.cpq.partno.PartNoProvider;
import com.cpq.seltemplate.dto.EffectiveTemplateDTO;
import com.cpq.seltemplate.service.EffectiveTemplateService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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

    // ───────────────────────────────────────────────────────────────────────
    // T19: lookup-fingerprint 端点
    // ───────────────────────────────────────────────────────────────────────

    /**
     * 抽屉 P2 完成时调用 — 算指纹查 DB, 命中则返回已有料号 + 快照,未命中返回 matched=false.
     *
     * <p><b>选配 Plan 3b (T6) 端点处置决策 = 方案 (b) 过渡</b>（集成设计 §4.3）：
     * 本端点仍查生产侧全局指纹 {@code material_master.config_fingerprint}
     * （见 {@link #lookupHfByFingerprint}）。3b 后选配 custom/COMPOSITE 落库的
     * {@code config_fingerprint} 一律为 NULL（R1，客户维度报价料号不进生产侧全局去重），
     * 故本端点对<b>新选配报价料号恒返 matched=false</b>，只可能命中历史 CFG- 料号。
     *
     * <p>影响可接受：P2 仅失去「实时复用提示」，真正的客户维度去重在提交时
     * （{@code configure → resolvePart} 的销售指纹 {@code sel_part_signature} lookup）仍生效，
     * 同客户同选配提交时会命中复用同一报价料号、不重复落库。
     *
     * <p>TODO(3a)：若要恢复 P2 实时「客户维度复用提示」，需前端 P2 在
     * {@link LookupFingerprintRequest} 携带 customerNo，本端点改查
     * {@code SalesSignatureRepository.lookup(customerNo, "v1", 销售指纹)}
     * （与提交时去重同源）。当前 3b 为后端专属、未改前端请求契约，故先 (b) 过渡。
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
     *   <li>custom 未命中: 新建 mat_part + mat_bom (ELEMENT N 行) + mat_process (若有 processNos)</li>
     * </ul>
     *
     * <p>注意: mat_part_version_log 基线行需要 customer_product_no (NOT NULL PK 成员),
     * configure 阶段不存在此信息，基线由 per-customer 数据导入流程 (V156/PartVersionService) 写入.
     */
    String resolvePart(PartRequest pr, UUID operatorId, UUID customerId, String customerCode,
                        List<String> reused, SalesConfigContext salesCtx) {
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
            // existing 模式无 processNos: 老行为, 直接复用物理对象
            if (pr.processNos == null || pr.processNos.isEmpty()) {
                // hotfix: mat_process 按 customer_id 隔离, 新客户复用老料号时本客户 mat_process 0 行
                // → ImplicitJoinRewriter 注入 customer_id 谓词查不到 → 工序 Tab 加载中.
                // 如果当前客户尚无该料号的 mat_process 数据, 从任意已有客户复制一份给当前 customerId.
                if (customerId != null) {
                    backfillProcessesForNewCustomer(pr.existingHfPartNo, customerId);
                }
                return pr.existingHfPartNo;
            }
            // Bug B 修复: existing+processNos 路径按 quotation_line_item_id 隔离工序。
            // 若前端传了 quotationLineItemId，则仅删/写该 lineItem 专属行，不影响其他 lineItem
            // 或主数据（quotation_line_item_id IS NULL）的工序。
            // 老路径兼容：quotationLineItemId = null → 仅删/写当前 customer 的主数据行（原有行为）。
            // V6 unit_price 版本化写入（覆盖当前 customer 工序）
            // per-lineItem 工序渲染由 insertQuotationLineProcesses 负责，加工费由 unit_price 视图提供
            insertProcessSimpleUnitPriceV6(pr.existingHfPartNo, pr.processNos, customerCode);
            // 仍返老 hfPartNo, 卡片显示用户选的料号
            return pr.existingHfPartNo;
        }

        if (!"custom".equals(pr.partMode)) {
            throw new IllegalArgumentException(
                "partMode must be 'existing' or 'custom': " + pr.partMode);
        }

        validateCustomPart(pr);

        // Phase 3 后：material_master 为指纹权威（V44 写入已停止，lookupHfByFingerprint 直接查 V6）
        com.cpq.configure.entity.MaterialRecipe recipe =
            com.cpq.configure.entity.MaterialRecipe.findByCodeOrThrow(pr.recipeCode);

        // 选配 Plan 3b (T4): 生产侧全局指纹发号 → 销售侧客户维度指纹发号 swap。
        // R6: 报价料号内嵌客户四位码，无客户码 mintAndRegister 发不了号 — 强制非空。
        if (customerCode == null || customerCode.isBlank()) {
            throw new IllegalArgumentException(
                "选配 custom 配件需要 customerCode（报价料号内嵌客户码），quotation 无客户不能发号");
        }

        // 销售侧客户维度指纹判复用（取代生产侧全局指纹 lookupHfByFingerprint）
        var sig = salesFp.computeSimple(salesCtx.customerNo, salesCtx.enabledParamsFor(pr));
        String hit = sigRepo.lookup(salesCtx.customerNo, SalesFingerprintCalculator.STRUCTURE_VERSION, sig.hash());
        if (hit != null) {
            // R3: 命中复用 → 在任何落库之前 return（同客户同结构，数据首次已落，幂等不重复落库/累加）
            reused.add(hit);
            return hit;
        }

        // ⚠️ 不变量：mintAndRegister + insertOrReadExisting + 下方 V6 落库必须同处 configure 的
        // 同一事务（REQUIRED，勿改 REQUIRES_NEW）——保证「签名可见 ⇔ V6 数据可见」，否则并发败者
        // 复用先赢号时先赢 V6 未提交 → Tab 静默空。
        // 并发同客户同选配时，败者会在此 INSERT 阻塞到先赢者 configure 事务提交
        // （quoting 单人操作场景并发低，可接受）。
        // 未命中 → 铸报价料号
        String hfPartNo = quoteAllocator.mintAndRegister(salesCtx.customerNo, salesCtx.yyMm);
        // 登记销售指纹；并发败者 (ON CONFLICT DO NOTHING) 回读到先赢者号 → 弃己 mint 号(孤儿可接受)，
        // 复用先赢号且跳过落库
        String registered = sigRepo.insertOrReadExisting(
            salesCtx.customerNo, SalesFingerprintCalculator.STRUCTURE_VERSION, sig.hash(), sig.text(),
            hfPartNo, "SIMPLE");
        if (registered == null) {
            // 理论上不可达：sel_part_signature 无删除 + 回读键 (customer_no, structure_version,
            // config_fingerprint) 精确命中先赢行；出现即代表不变量被破坏，fail-fast 而非 NPE。
            throw new IllegalStateException(
                "sel_part_signature 冲突但回读为空: fp=" + sig.hash());
        }
        if (!registered.equals(hfPartNo)) {
            reused.add(registered);
            return registered; // 并发败者：先赢者已落库，复用其号，跳过本次落库
        }

        // 先赢者：写 V6 unit_price 工序 — 需要 customerCode (NOT NULL，上方已校验非空)
        if (pr.processNos != null && !pr.processNos.isEmpty()) {
            insertProcessSimpleUnitPriceV6(hfPartNo, pr.processNos, customerCode);
        }

        // V6 双写（AP-53 续 6 Phase 1）：确保 material_master + element_bom_item 有本料号，
        // 让 composite_child_elements_mirror 视图渲染元素含量（渲染基线零改）。幂等 ON CONFLICT DO NOTHING。
        // R1: config_fingerprint 传 null — 客户维度发号后同一 material_master 可能被多个客户各自的
        // 报价料号复用，若沿用生产侧全局指纹会撞 uq_material_master_fingerprint 全局唯一索引 → 500。
        insertMaterialMasterV6(hfPartNo, recipe.symbol, pr.unitWeightGrams, recipe.id, null);
        // B2.1③: material_part_no(材质料号) = recipe.code（材质库编号，如 00001…；task-0708 V318 起
        // material_recipe.code 即材质库业务键，与 element_bom.material_part_no 同口径，V315 已纳入唯一键）。
        insertElementBomV6(hfPartNo, customerCode, recipe.code, pr.elements);
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

        Set<String> enabledTypes = new HashSet<>();
        if (customerCode != null && !customerCode.isBlank()) {
            EffectiveTemplateDTO eff = effectiveTemplateService.getEffective(customerCode);
            for (EffectiveTemplateDTO.Param p : eff.params) {
                enabledTypes.add(p.paramTypeCode);
            }
        }

        Map<PartRequest, List<EnabledParam>> byPart = new IdentityHashMap<>();
        if (req.parts != null) {
            for (PartRequest pr : req.parts) {
                // existing 模式不走销售指纹（复用既有料号，无需投影）；保留空 List。
                if (!"custom".equals(pr.partMode)) {
                    byPart.put(pr, List.of());
                    continue;
                }
                byPart.put(pr, projectEnabledParams(pr, enabledTypes));
            }
        }

        return new SalesConfigContext(customerCode, yyMm, SalesFingerprintCalculator.STRUCTURE_VERSION, byPart);
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

        // MATERIAL 恒为槽位
        out.add(new EnabledParam("MATERIAL", pr.recipeCode, null, null));

        // ELEMENT 恒为槽位
        // fail-fast 守卫: buildSalesConfigContext 跑在 validateCustomPart 之前, 脏 elements
        // (null 项 / elementCode 空) 若静默透传会在下游 sorted/assertNoDelimiter 裸 NPE → 500。
        // 与 SalesFingerprintCalculator 分隔符校验同风格: 脏数据 fail-fast 暴露成干净 400，不静默 filter。
        // pct 允许为 null（T2 normalize 已兜底 null→0）。
        List<ElementPct> elementPcts = pr.elements == null ? List.of()
            : pr.elements.stream()
                .map(eo -> {
                    if (eo == null || eo.elementCode == null || eo.elementCode.isBlank()) {
                        throw new IllegalArgumentException(
                            "custom 配件元素项非法(null 或 elementCode 空): recipeCode=" + pr.recipeCode);
                    }
                    return new ElementPct(eo.elementCode, eo.pct);
                })
                .collect(Collectors.toList());
        out.add(new EnabledParam("ELEMENT", null, elementPcts, null));

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
    @SuppressWarnings("unchecked")
    private List<String> resolveProcessCodes(List<String> processNos) {
        if (processNos == null || processNos.isEmpty()) return List.of();
        for (String processNo : processNos) {
            List<Object> rows = em.createNativeQuery(
                    "SELECT 1 FROM process_master WHERE process_no = :pn")
                .setParameter("pn", processNo).getResultList();
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("工序不存在: " + processNo);
            }
        }
        return processNos;
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
        gk.put("price_type", "PROCESS");
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
     * V6（B2 落库改造，backtask §4/B2.1③）: 元素配比 → {@code element_bom}(头) + {@code element_bom_item}(子)。
     * 等价导入落库（对齐 {@code Q04ElementBomHandler}）：
     * <ul>
     *   <li>头/子 groupKey = (system_type=QUOTE, customer_no, material_no=partNo, material_part_no=材质料号)；
     *       masterVersionColumn=childVersionColumn="characteristic"（由 {@link VersionedV6Writer} 自动分配，
     *       首次落 "2000"，与原硬编码值等价，但成为真实可递增的版本列）。</li>
     *   <li>子行额外带 {@code hf_part_no = partNo}（自指）——渲染基线（AP-53）: {@code v_composite_child_elements}
     *       / {@code composite_child_elements_mirror} 第一分支要求 {@code hf_part_no IS NOT NULL} 直接按渲染
     *       料号命中，本料号"成品=材质自身"，hf_part_no 与 material_no 同值。</li>
     *   <li>{@code scrap_rate}/{@code composition_qty}/{@code issue_unit}/{@code base_qty}（§4 doc 对应列）：
     *       选配阶段 {@link ConfigureProductRequest} 未采集这些字段，留 NULL（不臆造数值，列均可空）。</li>
     * </ul>
     */
    void insertElementBomV6(String partNo, String customerCode, String materialPartNo,
                            List<ElementOverride> elements) {
        if (customerCode == null || customerCode.isBlank()) return; // 无客户无法满足 customer_no NOT NULL / 渲染过滤

        Map<String, Object> masterGk = new LinkedHashMap<>();
        masterGk.put("system_type", "QUOTE");
        masterGk.put("customer_no", customerCode);
        masterGk.put("material_no", partNo);
        masterGk.put("material_part_no", materialPartNo);

        Map<String, Object> childGk = new LinkedHashMap<>(masterGk);
        childGk.put("hf_part_no", partNo);

        List<Map<String, Object>> rows = new ArrayList<>();
        int seq = 1;
        for (ElementOverride eo : elements) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("seq_no", seq++);
            r.put("component_no", eo.elementCode);
            r.put("content", eo.pct);
            r.put("scrap_rate", null);
            r.put("composition_qty", null);
            r.put("issue_unit", null);
            r.put("base_qty", null);
            rows.add(r);
        }

        versionedWriter.writeVersionedMasterDetail(
            "element_bom", "characteristic", masterGk, Map.of("bom_type", "MATERIAL"),
            "element_bom_item", "characteristic", childGk,
            List.of("seq_no", "component_no", "content", "scrap_rate", "composition_qty", "issue_unit", "base_qty"),
            rows);
    }

    /**
     * V6（B2 落库改造，backtask §3/B2.1②）: 自定义材质料号的物料BOM → {@code material_bom}(头，本次新增) +
     * {@code material_bom_item}(子，补全列)，1 行「自指物料行」=「选中的材质本身」。
     *
     * <p><b>保持既有渲染语义（backtask 明确要求不臆造复杂 BOM）</b>：SIMPLE 单材质料号的物料构成
     * 就是「该材质自身」，不展开成分子/工艺路线。[选配-材质] mirror（{@code v_composite_child_materials}/
     * {@code composite_child_materials_mirror}）从 {@code material_bom_item}(characteristic IS DISTINCT
     * FROM 'ASSEMBLY' + customer_no + 父料号) 取物料行 join material_master；mirror 的 material_name 列 =
     * COALESCE(component_usage_type, mm.material_type, ...)。
     *
     * <p>头表 {@code material_bom}：system_type=QUOTE / customer_no / material_no=partNo /
     * bom_type=MATERIAL（对齐 {@code MaterialBomMergeHandler} 的 MATERIAL 分支，masterVersionColumn=
     * "bom_version"，characteristic 不置值 → DB NULL）。
     *
     * <p>子表 {@code material_bom_item} 行：seq_no=1 / component_no=partNo(自指) /
     * component_usage_type=materialType(recipe.symbol，如 AgSnO₂，供材质名称列渲染) /
     * {@code rough_weight}/{@code net_weight}/{@code weight_unit}/{@code scrap_rate}/{@code defect_rate}
     * （§3 doc 对应列）：{@link ConfigureProductRequest} 未采集材料毛重/净重/损耗率/不良率，留 NULL
     * （列均可空，不臆造数值——若业务需要这些值参与核价，需 architect + 业务另行确认取数来源）。
     *
     * <p>幂等复用 {@link VersionedV6Writer#writeVersionedMasterDetail} 的内容比对（子行集不变则不升版不写）。
     */
    void insertMaterialBomItemV6(String partNo, String customerCode, String materialType) {
        if (customerCode == null || customerCode.isBlank()) return; // customer_no NOT NULL + mirror 按 customer 过滤

        Map<String, Object> masterGk = bomGroupKey(customerCode, partNo, "bom_type", "MATERIAL");
        Map<String, Object> childGk = bomGroupKey(customerCode, partNo, "characteristic", null);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq_no", 1);
        row.put("component_no", partNo);
        row.put("component_usage_type", materialType);
        row.put("rough_weight", null);
        row.put("net_weight", null);
        row.put("weight_unit", null);
        row.put("scrap_rate", null);
        row.put("defect_rate", null);

        versionedWriter.writeVersionedMasterDetail(
            "material_bom", "bom_version", masterGk, null,
            "material_bom_item", "bom_version", childGk,
            List.of("seq_no", "component_no", "component_usage_type",
                    "rough_weight", "net_weight", "weight_unit", "scrap_rate", "defect_rate"),
            List.of(row));
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
                "  AND NOT EXISTS (SELECT 1 FROM material_bom_item t WHERE t.material_no = :p AND t.customer_no = :cn AND t.system_type = 'QUOTE' AND t.characteristic IS NULL AND t.is_current = true)")
            .setParameter("cn", customerCode)
            .setParameter("p", partNo)
            .executeUpdate();
    }

    // backfillV44FromV6 和 backfillV6FromV44 已在 Phase 3 移除（V44 双写桥停用）

    // ─────────────────────────────────────────────────────────────────────
    // V6 落库 Phase 2（选配 COMBO 补全，设计方案 §6 / 用户方案 B1/B2/B3）
    //   B1 material_bom 主从版本化（ASSEMBLY 子配件 + MATERIAL 各子件材质自指）
    //   B2 工序 → unit_price（自制加工费，按配件分组版本化）
    //   B3 组合工艺 → capacity（QUOTE_ASSEMBLY，按 COMBO 整组版本化）
    // 统一走 VersionedV6Writer：内容相同复用、不同 max+1 升版、is_current 翻转。
    // 渲染 driver 不切（仍读 per-quote / mirror）；本期仅承载 V6 数据。
    // ─────────────────────────────────────────────────────────────────────

    /**
     * B1: COMBO 的 material_bom 主从版本化写入（替代早期 raw insert 写法）。
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
     * 分组键 (system_type=QUOTE, price_type=PROCESS, cost_type=自制加工费, customer_no, code=配件料号,
     * finished_material_no=COMBO)，行集 = 各工序（operation_no=process_no，task-0712 缺口1 起直取，
     * 不再经 process(V4) UUID 转译）。pricing_price 留 NULL（子项3）。
     * currency = process_master.standard_currency（空→CNY）；unit = standard_unit（空→KG，对齐导入存量）。
     * fail-fast: process_no 未命中 process_master 视为非法工序，抛出而非静默兜默认值。
     */
    @SuppressWarnings("unchecked")
    void insertProcessUnitPriceV6(String parentHfPartNo, String customerCode,
                                  List<PartRequest> parts, List<String> childHfPartNos) {
        if (customerCode == null || customerCode.isBlank()) return;
        for (int i = 0; i < childHfPartNos.size(); i++) {
            PartRequest pr = (parts != null && i < parts.size()) ? parts.get(i) : null;
            if (pr == null || pr.processNos == null || pr.processNos.isEmpty()) continue;
            String childPn = childHfPartNos.get(i);
            List<Map<String, Object>> rows = new ArrayList<>();
            int seq = 1;
            for (String opNo : pr.processNos) {
                String currency = "CNY";
                String unit = "KG";
                List<Object[]> pm = em.createNativeQuery(
                        "SELECT standard_currency, standard_unit FROM process_master WHERE process_no = :c")
                    .setParameter("c", opNo).getResultList();
                if (pm.isEmpty()) {
                    throw new IllegalArgumentException("工序不存在: " + opNo);
                }
                Object[] m = pm.get(0);
                if (m[0] != null && !m[0].toString().isBlank()) currency = m[0].toString();
                if (m[1] != null && !m[1].toString().isBlank()) unit = m[1].toString();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("operation_no", opNo);
                r.put("seq_no", seq++);
                r.put("currency", currency);
                r.put("unit", unit);
                rows.add(r);
            }
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "QUOTE");
            gk.put("price_type", "PROCESS");
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
     * task-0712 缺口1: operation_no = processNo 直取，不再经 process(V4) UUID 转译；
     * fail-fast: process_no 未命中 process_master 视为非法工序。
     */
    @SuppressWarnings("unchecked")
    void insertProcessSimpleUnitPriceV6(String hfPartNo, List<String> processNos, String customerCode) {
        if (customerCode == null || customerCode.isBlank()) return;
        if (processNos == null || processNos.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>();
        int seq = 1;
        for (String opNo : processNos) {
            String currency = "CNY";
            String unit = "KG";
            List<Object[]> pm = em.createNativeQuery(
                    "SELECT standard_currency, standard_unit FROM process_master WHERE process_no = :c")
                .setParameter("c", opNo).getResultList();
            if (pm.isEmpty()) {
                throw new IllegalArgumentException("工序不存在: " + opNo);
            }
            Object[] m = pm.get(0);
            if (m[0] != null && !m[0].toString().isBlank()) currency = m[0].toString();
            if (m[1] != null && !m[1].toString().isBlank()) unit = m[1].toString();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("operation_no", opNo);
            r.put("seq_no", seq++);
            r.put("currency", currency);
            r.put("unit", unit);
            rows.add(r);
        }
        Map<String, Object> gk = new LinkedHashMap<>();
        gk.put("system_type", "QUOTE");
        gk.put("price_type", "PROCESS");
        gk.put("cost_type", "自制加工费");
        gk.put("customer_no", customerCode);
        gk.put("code", hfPartNo);
        gk.put("finished_material_no", hfPartNo);
        versionedWriter.writeVersionedGroup(new VersionedGroupSpec(
            "unit_price", "version_no", gk,
            List.of("operation_no", "seq_no", "currency", "unit"), rows));
    }

    /**
     * B3（B2 落库改造，backtask §14/B2.1⑤，B6 架构决策 2-2A 定稿后收敛）: 组合工艺 → capacity
     * （对标导入 §14 组装加工费）。按 COMBO 整组版本化：分组键
     * (material_no=COMBO, resource_group_no=QUOTE_ASSEMBLY)，行集 = 各 process_no。
     *
     * <p><b>标识锚点 = {@code process_master.process_no}</b>（不再是 {@code composite_process_def.code}）：
     * {@code cp.defCode} 即前端从 {@code GET /composite-processes} 候选选中的
     * {@code process_master.process_no}（如 MRO-AS-0001），与指纹 CPROC /
     * {@code quotation_line_composite_process.def_code} 三处（连同候选端点、前端选值共五处）同一标识
     * （AP-44 精神，PR 自检硬项）。
     *
     * <p>{@code process_name} 读 {@code process_master.process_name}（缺回退 process_no）；
     * {@code currency} 空兜 CNY；{@code capacity_unit}(⚠️ 非 {@code unit})/{@code default_defect_rate}
     * 直接透传 {@code process_master}（ASSEMBLY 现网 4 行均空 → 落库为 NULL，与自制加工费口径一致）；
     * {@code fixed_cost} 留 NULL（单价由后续 INPUT 层维护，选配阶段未采集）。
     *
     * <p>未在 process_master(ASSEMBLY) 命中时不在此处 fail-fast（沿用防御式回退：process_name=
     * process_no、currency=CNY、其余 NULL）——真正的存在性校验由同一事务内的
     * {@link #insertCompositeProcessesPerQuote} 通过 {@code process_master} 查找兜底，
     * 非法 defCode 会在那里抛出并回滚本次全部落库（事务原子性，AP-53/B2.4 不变量）。
     */
    @SuppressWarnings("unchecked")
    void insertCompositeProcessCapacityV6(String parentHfPartNo,
                                          List<com.cpq.configure.dto.CompositeProcessRequest> cps) {
        if (parentHfPartNo == null || cps == null || cps.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>();
        int seq = 1;
        for (com.cpq.configure.dto.CompositeProcessRequest cp : cps) {
            List<Object[]> pm = em.createNativeQuery(
                    "SELECT process_name, standard_currency, standard_unit, default_defect_rate " +
                    "FROM process_master WHERE process_no = :c AND process_category = 'ASSEMBLY'")
                .setParameter("c", cp.defCode).getResultList();
            String procName = cp.defCode;
            String currency = "CNY";
            String capacityUnit = null;
            BigDecimal defectRate = null;
            if (!pm.isEmpty()) {
                Object[] m = pm.get(0);
                if (m[0] != null && !m[0].toString().isBlank()) procName = m[0].toString();
                if (m[1] != null && !m[1].toString().isBlank()) currency = m[1].toString();
                if (m[2] != null && !m[2].toString().isBlank()) capacityUnit = m[2].toString();
                if (m[3] != null) defectRate = new BigDecimal(m[3].toString());
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("process_no", cp.defCode);
            r.put("process_name", procName);
            r.put("production_type", "BATCH_FIXED");
            r.put("currency", currency);
            r.put("seq_no", seq++);
            r.put("fixed_cost", null);
            r.put("capacity_unit", capacityUnit);
            r.put("default_defect_rate", defectRate);
            rows.add(r);
        }
        Map<String, Object> gk = new LinkedHashMap<>();
        gk.put("system_type", "QUOTE");   // V290 护栏：capacity 必须按 system_type 隔离
        gk.put("material_no", parentHfPartNo);
        gk.put("resource_group_no", "QUOTE_ASSEMBLY");
        versionedWriter.writeVersionedGroup(new VersionedGroupSpec(
            "capacity", "calc_version", gk,
            List.of("process_no", "process_name", "production_type", "currency", "seq_no",
                    "fixed_cost", "capacity_unit", "default_defect_rate"), rows));
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
     * <p>实测(2026-07-14 架构评审 F8)确认：本表当前无任何 SELECT/视图读取——"选配-工序列表"类
     * Tab 实际渲染走 {@code v_composite_child_processes} 物理 PG 视图，该视图直接读
     * {@code unit_price.operation_no}/{@code material_bom_item.operation_no}
     * （由 {@link #insertProcessSimpleUnitPriceV6}/{@link #insertProcessUnitPriceV6} 写入），
     * 与本表完全解耦。本表目前是纯粹的 per-quote 工序选择记录(供后续读回/展示用)。
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
        for (String processNo : processNos) {
            em.createNativeQuery(
                    "INSERT INTO quotation_line_process (id, line_item_id, process_no) " +
                    "VALUES (gen_random_uuid(), :lid, :pn)")
                .setParameter("lid", lineItemId)
                .setParameter("pn", processNo)
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
        // B2.3: 后端裁决的有效 productType（Σqty 兜底），全程用它分发，不再信 req.productType。
        String effectiveType = validateRequest(req);

        // P4 批2 补丁: 从 quotation 拉 customer_id，传给 resolvePart → insertProcesses
        UUID customerId = getCustomerIdFromQuotation(quotationId);
        // V6 (AP-53 续 6 Phase 1): V6 BOM 表 customer_no 用 customer.code（非 UUID），派生一次贯穿落库
        String customerCode = getCustomerCodeFromCustomerId(customerId);

        // 选配 Plan 3b (T3): 客户维度销售上下文 — 每 part 的 EnabledParam 投影，
        // 供 SalesFingerprintCalculator.computeSimple/computeComposite 计算客户维度指纹。
        // T4 起 resolvePart(SIMPLE custom 分支) 已消费 salesCtx 做销售侧发号判复用；
        // COMPOSITE 分支消费为 T5 范围。
        SalesConfigContext salesCtx = buildSalesConfigContext(customerCode, req);

        List<String> childHfPartNos = new ArrayList<>();
        List<String> reused = new ArrayList<>();

        // PASS 1: 解析每个配件
        for (PartRequest pr : req.parts) {
            childHfPartNos.add(resolvePart(pr, operatorId, customerId, customerCode, reused, salesCtx));
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
                    insertMaterialMasterV6(parentHfPartNo, "COMPOSITE", null, null, null); // R1
                    // V6 落库 Phase 2（选配 COMBO 补全，设计 §6 / 用户方案 B1/B2/B3）：统一走
                    // VersionedV6Writer（内容相同复用 / 不同 max+1 升版 / is_current 翻转）。
                    writeCombomaterialBomV6(parentHfPartNo, customerCode, childHfPartNos, childQtys);
                    insertProcessUnitPriceV6(parentHfPartNo, customerCode, req.parts, childHfPartNos);
                    insertCompositeProcessCapacityV6(parentHfPartNo, req.compositeProcesses);
                }
            }
        }

        // PASS 3: line_items (解法 B: 传 req.tempId 给 buildLineItems 作 parent line item id)
        UUID tempId = parseUuidOrNull(req.tempId);
        List<Map<String, Object>> lineItems =
            buildLineItems(quotationId, req, parentHfPartNo, childHfPartNos, tempId, effectiveType);

        ConfigureProductResponse resp = new ConfigureProductResponse();
        resp.lineItems = lineItems;
        resp.fingerprintMatched = !reused.isEmpty();
        resp.reusedHfPartNos = reused;
        resp.productType = effectiveType;
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
    @SuppressWarnings("unchecked")
    private void assertAssemblyProcessExists(String processNo) {
        List<Object> rows = em.createNativeQuery(
                "SELECT 1 FROM process_master WHERE process_no = :c AND process_category = 'ASSEMBLY'")
            .setParameter("c", processNo).getResultList();
        if (rows.isEmpty()) {
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
            List<String> childHfPartNos) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (lineItemId == null || cps == null || cps.isEmpty()) return out;
        com.fasterxml.jackson.databind.ObjectMapper om =
            new com.fasterxml.jackson.databind.ObjectMapper();
        int seq = 1;
        for (com.cpq.configure.dto.CompositeProcessRequest cp : cps) {
            assertAssemblyProcessExists(cp.defCode);
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
                                             String effectiveType) {
        List<Map<String, Object>> out = new ArrayList<>();

        if ("SIMPLE".equals(effectiveType)) {
            String pn = childHfPartNos.get(0);
            UUID id = insertLineItem(quotationId, pn, null, "SIMPLE", tempId);
            // per-quote 工序：选配工序写报价行专属 quotation_line_process（行已建，满足 FK）
            PartRequest simplePr = (req.parts != null && !req.parts.isEmpty()) ? req.parts.get(0) : null;
            insertQuotationLineProcesses(id, simplePr != null ? simplePr.processNos : null);
            out.add(buildLineItemDTO(id, pn, "SIMPLE", null, simplePr != null ? simplePr.processNos : null));
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
            insertQuotationLineProcesses(childId, childPr != null ? childPr.processNos : null);
            out.add(buildLineItemDTO(childId, childPn, "PART", parentId, childPr != null ? childPr.processNos : null));
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
