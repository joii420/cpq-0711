package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialRecipeConfigDTO;
import com.cpq.configure.dto.MaterialRecipeConfigUpsertRequest;
import com.cpq.configure.dto.MaterialRecipeElementDTO;
import com.cpq.configure.entity.MaterialRecipe;
import com.cpq.configure.entity.MaterialRecipeComposition;
import com.cpq.configure.entity.MaterialRecipeConfig;
import com.cpq.configure.entity.MaterialRecipeElement;
import com.cpq.configure.exception.MaterialRecipeApiException;
import com.cpq.configure.rules.MaterialRecipeNumbering;
import com.cpq.configure.rules.MaterialRecipeRules;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 含量配置服务（task-260901 · B-14）。
 *
 * <p>本类同时是<b>导入侧与 UI 侧共用的落库/校验底座</b>：
 * {@code MaterialRecipeImportService} 与 {@code MaterialRecipeService#create} 都经由这里发号
 * （{@link MaterialRecipeNumbering}）、判重（{@link MaterialRecipeRules#sameContent}）、
 * 灌元素行，🚫 不许各写一份（M-0a）。
 *
 * <p><b>N+1 纪律</b>：所有批量读取一律「一次 IN 查询 + 内存分组」，本类里没有任何循环体查库。
 */
@ApplicationScoped
public class MaterialRecipeConfigService {

    /** 含量精度：numeric(16,12) */
    public static final int PCT_SCALE = 12;

    @Inject
    EntityManager em;

    // ─────────────────────────────────────────────────────────
    // 元素解析（element 主表）
    // ─────────────────────────────────────────────────────────

    /** element 主表的一行（只取展示与链接需要的三列）。 */
    public static final class ElementRef {
        public final String elementNo;
        public final String elementCode;
        public final String elementName;

        public ElementRef(String elementNo, String elementCode, String elementName) {
            this.elementNo = elementNo;
            this.elementCode = elementCode;
            this.elementName = elementName;
        }
    }

    /**
     * 一次查库把 element 主表按 element_no / element_code 双索引拉进内存（无 N+1）。
     *
     * @return key 既含 element_no 也含 element_code，两种键都能命中同一条 ElementRef
     */
    @SuppressWarnings("unchecked")
    public Map<String, ElementRef> loadElementIndex(Collection<String> nos, Collection<String> codes) {
        Set<String> noSet = nos == null ? Set.of() : new LinkedHashSet<>(nos);
        Set<String> codeSet = codes == null ? Set.of() : new LinkedHashSet<>(codes);
        Map<String, ElementRef> index = new HashMap<>();
        if (noSet.isEmpty() && codeSet.isEmpty()) return index;

        // 条件按需拼（不用 :flag = TRUE 这种写法：PG 对未定型参数会报 could not determine data type）
        List<String> conds = new ArrayList<>(2);
        if (!noSet.isEmpty()) conds.add("element_no IN (:nos)");
        if (!codeSet.isEmpty()) conds.add("element_code IN (:codes)");
        var q = em.createNativeQuery(
            "SELECT element_no, element_code, element_name FROM element WHERE "
                + String.join(" OR ", conds));
        if (!noSet.isEmpty()) q.setParameter("nos", noSet);
        if (!codeSet.isEmpty()) q.setParameter("codes", codeSet);
        List<Object[]> rows = q.getResultList();

        for (Object[] r : rows) {
            ElementRef ref = new ElementRef((String) r[0], (String) r[1], (String) r[2]);
            if (ref.elementNo != null) index.put(ref.elementNo, ref);
            if (ref.elementCode != null) index.putIfAbsent(ref.elementCode, ref);
        }
        return index;
    }

    // ─────────────────────────────────────────────────────────
    // 元素组成（material_recipe_composition）
    // ─────────────────────────────────────────────────────────

    public List<MaterialRecipeComposition> listComposition(UUID recipeId) {
        return MaterialRecipeComposition.<MaterialRecipeComposition>find(
            "recipeId = ?1 ORDER BY sortOrder, elementCode", recipeId).list();
    }

    /**
     * <b>B-21 / AC-36：元素的展示值一律走权威链 {@code element_no → element 主表}。</b>
     *
     * <p>{@code material_recipe_composition.element_code / element_name} 与
     * {@code material_recipe_element.element_code / element_name} 都只是<b>快照</b>
     * （{@code task-0709 · B2} 已定：权威元素链是 {@code element_no}）。历史上有整行串位的脏数据
     * （材质 {@code 00262}：{@code element_no=10004 / element_code=10004 / element_name=Sn}，
     * 而主表 {@code 10004 = Sn / 锡}），直接渲染快照列就会显示成 {@code 10004}。
     *
     * <p>🚫 <b>只改读、不改写</b>：这里不回写任何快照列，库内那一行一个字节不动（AC-36 反向断言）。
     * <p>⚠️ 主表查无（如导入自动建档之前的边界）时<b>回退用快照值</b>，绝不返回空。
     *
     * @param elementNo 组成/元素行上的权威链
     * @param index     {@link #loadElementIndex} 的结果（按 element_no 命中）
     * @param snapshot  快照值，主表查无时的回退
     */
    public static String authoritative(String elementNo, Map<String, ElementRef> index,
                                       String snapshot, boolean wantName) {
        ElementRef ref = (elementNo == null || index == null) ? null : index.get(elementNo);
        String authoritativeValue = ref == null ? null : (wantName ? ref.elementName : ref.elementCode);
        if (authoritativeValue != null && !authoritativeValue.isBlank()) return authoritativeValue;
        return snapshot;
    }

    /** 一次查库把这批 element_no 的主表行拉进内存（B-21 用；无 N+1）。 */
    public Map<String, ElementRef> loadElementIndexByNo(Collection<String> elementNos) {
        return loadElementIndex(elementNos, null);
    }

    /** 批量：一次查全部材质的元素组成，内存按 recipeId 分组（列表页禁 N+1）。 */
    public Map<UUID, List<MaterialRecipeComposition>> listCompositionBatch(Collection<UUID> recipeIds) {
        if (recipeIds == null || recipeIds.isEmpty()) return Map.of();
        List<MaterialRecipeComposition> all = MaterialRecipeComposition
            .<MaterialRecipeComposition>find("recipeId in ?1 ORDER BY sortOrder, elementCode",
                new LinkedHashSet<>(recipeIds)).list();
        Map<UUID, List<MaterialRecipeComposition>> byRecipe = new LinkedHashMap<>();
        for (MaterialRecipeComposition c : all) {
            byRecipe.computeIfAbsent(c.recipeId, k -> new ArrayList<>()).add(c);
        }
        return byRecipe;
    }

    // ─────────────────────────────────────────────────────────
    // 配置读取
    // ─────────────────────────────────────────────────────────

    public List<MaterialRecipeConfig> listConfigs(UUID recipeId, boolean includeInactive) {
        return includeInactive
            ? MaterialRecipeConfig.<MaterialRecipeConfig>find("recipeId = ?1 ORDER BY seq", recipeId).list()
            : MaterialRecipeConfig.<MaterialRecipeConfig>find(
                "recipeId = ?1 AND status = 'ACTIVE' ORDER BY seq", recipeId).list();
    }

    public long countActiveConfigs(UUID recipeId) {
        return MaterialRecipeConfig.count("recipeId = ?1 AND status = 'ACTIVE'", recipeId);
    }

    /**
     * 配置 + 元素一起读出来做 DTO —— <b>两条 SQL</b>（配置一条、元素一条 IN），与配置数无关。
     */
    public List<MaterialRecipeConfigDTO> listConfigDTOs(UUID recipeId, boolean includeInactive) {
        List<MaterialRecipeConfig> configs = listConfigs(recipeId, includeInactive);
        if (configs.isEmpty()) return List.of();
        Map<UUID, List<MaterialRecipeElement>> elementsByConfig =
            loadElementsByConfig(configs.stream().map(c -> c.id).toList());

        // B-21：元素展示值走权威链。所有 element_no 一次查全（1 条 SQL，与配置数无关）。
        Set<String> nos = new LinkedHashSet<>();
        for (List<MaterialRecipeElement> els : elementsByConfig.values()) {
            for (MaterialRecipeElement e : els) if (e.elementNo != null) nos.add(e.elementNo);
        }
        Map<String, ElementRef> index = loadElementIndexByNo(nos);

        List<MaterialRecipeConfigDTO> out = new ArrayList<>(configs.size());
        for (MaterialRecipeConfig c : configs) {
            out.add(toDTO(c, elementsByConfig.getOrDefault(c.id, List.of()), index));
        }
        return out;
    }

    /** 一次 IN 查询取多个配置的元素行，内存分组（无 N+1）。 */
    public Map<UUID, List<MaterialRecipeElement>> loadElementsByConfig(Collection<UUID> configIds) {
        if (configIds == null || configIds.isEmpty()) return Map.of();
        List<MaterialRecipeElement> all = MaterialRecipeElement
            .<MaterialRecipeElement>find("configId in ?1 ORDER BY sortOrder, elementCode",
                new LinkedHashSet<>(configIds)).list();
        Map<UUID, List<MaterialRecipeElement>> byConfig = new LinkedHashMap<>();
        for (MaterialRecipeElement e : all) {
            byConfig.computeIfAbsent(e.configId, k -> new ArrayList<>()).add(e);
        }
        return byConfig;
    }

    /** 兼容签名：无权威索引时按 element_no 现查一次（调用点少、且元素数很小）。 */
    public MaterialRecipeConfigDTO toDTO(MaterialRecipeConfig c, List<MaterialRecipeElement> elements) {
        Set<String> nos = new LinkedHashSet<>();
        for (MaterialRecipeElement e : elements) if (e.elementNo != null) nos.add(e.elementNo);
        return toDTO(c, elements, loadElementIndexByNo(nos));
    }

    public MaterialRecipeConfigDTO toDTO(MaterialRecipeConfig c, List<MaterialRecipeElement> elements,
                                         Map<String, ElementRef> index) {
        MaterialRecipeConfigDTO d = new MaterialRecipeConfigDTO();
        d.id = c.id;
        d.configNo = c.configNo;
        d.seq = c.seq;
        d.remark = c.remark;
        d.status = c.status;
        d.createdAt = c.createdAt;
        BigDecimal total = BigDecimal.ZERO;
        for (MaterialRecipeElement e : elements) {
            MaterialRecipeElementDTO ed = new MaterialRecipeElementDTO();
            ed.elementNo = e.elementNo;
            // B-21 / AC-36：展示值取权威链（element 主表），主表查无时回退快照
            ed.elementCode = authoritative(e.elementNo, index, e.elementCode, false);
            ed.elementName = authoritative(e.elementNo, index, e.elementName, true);
            ed.defaultPct = pctString(e.defaultPct);
            ed.minPct = pctString(e.minPct);
            ed.maxPct = pctString(e.maxPct);
            ed.isLocked = e.isLocked;
            ed.sortOrder = e.sortOrder;
            d.elements.add(ed);
            if (e.defaultPct != null) total = total.add(e.defaultPct);
        }
        d.totalPct = pctString(total);
        return d;
    }

    /**
     * 含量出参统一 scale=12 的纯字符串（api.md §1）。
     * 🚫 <b>不在这里去尾随零</b> —— 去零是渲染层的事，接口与存储保持完整精度（AC-30 反向断言）。
     */
    public static String pctString(BigDecimal v) {
        return v == null ? null : v.setScale(PCT_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    // ─────────────────────────────────────────────────────────
    // 配置 CRUD（B-14）
    // ─────────────────────────────────────────────────────────

    @Transactional
    public MaterialRecipeConfigDTO createConfig(UUID recipeId, MaterialRecipeConfigUpsertRequest req) {
        MaterialRecipe recipe = requireRecipe(recipeId);
        List<ResolvedPct> resolved = validateAgainstComposition(recipe, req, null);
        MaterialRecipeConfig config = allocateConfig(recipe, req == null ? null : trimOrNull(req.remark));
        insertElements(config.id, resolved);
        em.flush();
        return toDTO(config, MaterialRecipeElement
            .<MaterialRecipeElement>find("configId = ?1 ORDER BY sortOrder", config.id).list());
    }

    @Transactional
    public MaterialRecipeConfigDTO updateConfig(UUID recipeId, UUID configId,
                                                MaterialRecipeConfigUpsertRequest req) {
        MaterialRecipe recipe = requireRecipe(recipeId);
        MaterialRecipeConfig config = requireConfig(recipeId, configId);
        List<ResolvedPct> resolved = validateAgainstComposition(recipe, req, configId);

        // 元素行整体重灌（配置内元素是不可变子项；configNo / seq 不动）
        MaterialRecipeElement.delete("configId", configId);
        em.flush();
        insertElements(configId, resolved);
        config.remark = req == null ? null : trimOrNull(req.remark);
        config.updatedAt = OffsetDateTime.now();
        config.persist();
        em.flush();
        return toDTO(config, MaterialRecipeElement
            .<MaterialRecipeElement>find("configId = ?1 ORDER BY sortOrder", configId).list());
    }

    /** DELETE：<b>软删且幂等</b>（M-2）——status→INACTIVE，物理行保留，seq 水位不释放 ⇒ 编号不回收。 */
    @Transactional
    public void deleteConfig(UUID recipeId, UUID configId) {
        MaterialRecipeConfig config = requireConfig(recipeId, configId);
        if (!MaterialRecipeConfig.INACTIVE.equals(config.status)) {
            config.status = MaterialRecipeConfig.INACTIVE;
            config.updatedAt = OffsetDateTime.now();
            config.persist();
        }
    }

    // ─────────────────────────────────────────────────────────
    // 供导入 / 材质 POST 复用的落库原语
    // ─────────────────────────────────────────────────────────

    /** 解析好的一条元素含量（元素身份已落到 element 主表口径）。 */
    public static final class ResolvedPct {
        public final String elementNo;
        public final String elementCode;
        public final String elementName;
        public final BigDecimal pct;      // 100 制
        public final int sortOrder;

        public ResolvedPct(String elementNo, String elementCode, String elementName,
                           BigDecimal pct, int sortOrder) {
            this.elementNo = elementNo;
            this.elementCode = elementCode;
            this.elementName = elementName;
            this.pct = pct;
            this.sortOrder = sortOrder;
        }
    }

    /**
     * <b>B-5 发号</b>：seq = max(该材质<b>全部</b>配置的 seq，含 INACTIVE) + 1，
     * config_no = {@code <材质编号>-%02d}。一次查询 + 纯函数，编号不回收（M-2）。
     */
    public MaterialRecipeConfig allocateConfig(MaterialRecipe recipe, String remark) {
        List<Integer> seqs = MaterialRecipeConfig.<MaterialRecipeConfig>find("recipeId", recipe.id)
            .list().stream().map(c -> c.seq).toList();
        int seq = MaterialRecipeNumbering.nextConfigSeq(seqs);
        MaterialRecipeConfig c = new MaterialRecipeConfig();
        c.recipeId = recipe.id;
        c.seq = seq;
        c.configNo = MaterialRecipeNumbering.formatConfigNo(recipe.code, seq);
        c.status = MaterialRecipeConfig.ACTIVE;
        c.remark = remark;
        c.sortOrder = seq;
        c.createdAt = OffsetDateTime.now();
        c.updatedAt = OffsetDateTime.now();
        c.persist();
        return c;
    }

    /** 用已知水位直接建配置（不再查库）。 */
    public MaterialRecipeConfig newConfigWithSeq(MaterialRecipe recipe, int seq, String remark) {
        MaterialRecipeConfig c = new MaterialRecipeConfig();
        c.recipeId = recipe.id;
        c.seq = seq;
        c.configNo = MaterialRecipeNumbering.formatConfigNo(recipe.code, seq);
        c.status = MaterialRecipeConfig.ACTIVE;
        c.remark = remark;
        c.sortOrder = seq;
        c.createdAt = OffsetDateTime.now();
        c.updatedAt = OffsetDateTime.now();
        c.persist();
        return c;
    }

    /** 灌元素行：一律 is_locked=true / min,max=NULL（M-5 起自定义含量由材质级开关裁决）。 */
    public int insertElements(UUID configId, List<ResolvedPct> elements) {
        OffsetDateTime now = OffsetDateTime.now();
        int n = 0;
        for (ResolvedPct r : elements) {
            MaterialRecipeElement el = new MaterialRecipeElement();
            el.configId = configId;
            el.elementNo = r.elementNo;
            el.elementCode = r.elementCode;
            el.elementName = r.elementName;
            el.defaultPct = r.pct;
            el.minPct = null;
            el.maxPct = null;
            el.isLocked = true;
            el.sortOrder = r.sortOrder;
            el.createdAt = now;
            el.persist();          // Hibernate JDBC batch（batch-size=100）
            n++;
        }
        return n;
    }

    /** 元素含量按符号建索引，供 M-4 逐值判重。 */
    public static Map<String, BigDecimal> contentByCode(List<MaterialRecipeElement> elements) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (MaterialRecipeElement e : elements) m.put(e.elementCode, e.defaultPct);
        return m;
    }

    public static Map<String, BigDecimal> contentByCodeResolved(List<ResolvedPct> elements) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (ResolvedPct e : elements) m.put(e.elementCode, e.pct);
        return m;
    }

    // ─────────────────────────────────────────────────────────
    // 校验（POST / PUT 共用；元素集合必须与材质 composition 逐个相等）
    // ─────────────────────────────────────────────────────────

    private List<ResolvedPct> validateAgainstComposition(MaterialRecipe recipe,
                                                         MaterialRecipeConfigUpsertRequest req,
                                                         UUID excludeConfigId) {
        if (req == null || req.elements == null || req.elements.isEmpty()) {
            throw MaterialRecipeApiException.badRequest("COMPOSITION_EMPTY", "材质必须至少有一个元素");
        }
        List<MaterialRecipeComposition> composition = listComposition(recipe.id);
        if (composition.isEmpty()) {
            throw MaterialRecipeApiException.badRequest("COMPOSITION_EMPTY", "材质必须至少有一个元素");
        }

        // 1) 元素解析（一次查库）
        List<String> nos = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (MaterialRecipeConfigUpsertRequest.ElementInput in : req.elements) {
            if (in == null) throw MaterialRecipeApiException.badRequest("COMPOSITION_EMPTY", "材质必须至少有一个元素");
            if (in.elementNo != null && !in.elementNo.isBlank()) nos.add(in.elementNo.trim());
            if (in.elementCode != null && !in.elementCode.isBlank()) codes.add(in.elementCode.trim());
        }
        Map<String, ElementRef> index = loadElementIndex(nos, codes);
        Map<String, Integer> orderByNo = new HashMap<>();
        for (MaterialRecipeComposition c : composition) orderByNo.put(c.elementNo, c.sortOrder);

        // 2) 逐行解析 + 单值范围 + 重复元素
        List<ResolvedPct> resolved = new ArrayList<>(req.elements.size());
        Set<String> seenNos = new LinkedHashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (MaterialRecipeConfigUpsertRequest.ElementInput in : req.elements) {
            String key = (in.elementNo != null && !in.elementNo.isBlank())
                ? in.elementNo.trim() : (in.elementCode == null ? null : in.elementCode.trim());
            ElementRef ref = key == null ? null : index.get(key);
            if (ref == null) {
                throw MaterialRecipeApiException.notFound("ELEMENT_NOT_FOUND", "元素编号不存在：" + key);
            }
            if (!seenNos.add(ref.elementNo)) {
                throw MaterialRecipeApiException.badRequest("COMPOSITION_ELEMENT_DUPLICATED",
                    "元素重复：" + ref.elementNo);
            }
            BigDecimal pct = in.effectivePct();
            if (!MaterialRecipeRules.pctInRange(pct, MaterialRecipeRules.HUNDRED)) {
                throw MaterialRecipeApiException.badRequest("CONFIG_PCT_ILLEGAL",
                    "含量必须大于 0 且不超过 100：" + ref.elementCode);
            }
            sum = sum.add(pct);
            Integer so = orderByNo.get(ref.elementNo);
            resolved.add(new ResolvedPct(ref.elementNo, ref.elementCode, ref.elementName, pct,
                so == null ? resolved.size() + 1 : so));
        }

        // 3) 元素集合必须与材质元素组成逐个相等（多了 / 少了都 400）
        Set<String> compNos = new LinkedHashSet<>();
        Map<String, String> codeByNo = new LinkedHashMap<>();
        for (MaterialRecipeComposition c : composition) {
            compNos.add(c.elementNo);
            codeByNo.put(c.elementNo, c.elementCode);
        }
        String compText = String.join(", ", codeByNo.values());
        for (String no : seenNos) {
            if (!compNos.contains(no)) {
                ElementRef ref = index.get(no);
                throw MaterialRecipeApiException.badRequest("CONFIG_ELEMENT_SET_MISMATCH",
                    (ref == null ? no : ref.elementCode) + " 不在该材质的元素组成（" + compText + "）中");
            }
        }
        for (String no : compNos) {
            if (!seenNos.contains(no)) {
                throw MaterialRecipeApiException.badRequest("CONFIG_ELEMENT_SET_MISMATCH",
                    "缺少元素 " + codeByNo.get(no) + "，该材质的元素组成是 " + compText);
            }
        }

        // 4) Σ ≈ 100（容差 2 = 0~1 制的 0.02）
        if (!MaterialRecipeRules.sumIsOnePct(sum)) {
            throw MaterialRecipeApiException.badRequest("CONFIG_SUM_NOT_ONE",
                "含量合计必须为 1，实际 " + MaterialRecipeRules.formatRatioSum(
                    sum.divide(MaterialRecipeRules.HUNDRED, 12, RoundingMode.HALF_UP)));
        }

        // 5) 与已有 ACTIVE 配置逐值判重（M-4；PUT 时排除自己）
        List<MaterialRecipeConfig> actives = listConfigs(recipe.id, false);
        Map<UUID, List<MaterialRecipeElement>> byConfig =
            loadElementsByConfig(actives.stream().map(c -> c.id).toList());
        Map<String, BigDecimal> incoming = contentByCodeResolved(resolved);
        for (MaterialRecipeConfig c : actives) {
            if (excludeConfigId != null && excludeConfigId.equals(c.id)) continue;
            if (MaterialRecipeRules.sameContent(incoming,
                    contentByCode(byConfig.getOrDefault(c.id, List.of())))) {
                throw MaterialRecipeApiException.conflict("CONFIG_DUPLICATED",
                    "该含量配比与已有配置 " + c.configNo + " 完全相同");
            }
        }

        resolved.sort((a, b) -> Integer.compare(a.sortOrder, b.sortOrder));
        return resolved;
    }

    // ─────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────

    public MaterialRecipe requireRecipe(UUID recipeId) {
        MaterialRecipe r = MaterialRecipe.findById(recipeId);
        if (r == null) throw new NotFoundException("material_recipe 不存在: " + recipeId);
        return r;
    }

    private MaterialRecipeConfig requireConfig(UUID recipeId, UUID configId) {
        MaterialRecipeConfig c = MaterialRecipeConfig.findById(configId);
        if (c == null || !c.recipeId.equals(recipeId)) {
            throw new NotFoundException("material_recipe_config 不存在: " + configId);
        }
        return c;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 空列表安全的 unmodifiable 包装（内部用）。 */
    static <T> List<T> safe(List<T> in) {
        return in == null ? Collections.emptyList() : in;
    }
}
