package com.cpq.configure.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.dto.Pagination;
import com.cpq.configure.dto.BindingSuggestionDTO;
import com.cpq.configure.dto.ConfirmBindingsRequest;
import com.cpq.configure.dto.ExistingPartMaterialDTO;
import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipeElementDTO;
import com.cpq.configure.dto.MaterialRecipePartDTO;
import com.cpq.configure.dto.MaterialRecipeUpsertRequest;
import com.cpq.configure.entity.MaterialRecipe;
import com.cpq.configure.entity.MaterialRecipeElement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 材质字典服务（V6，AP-53 续 5 全迁完成）。
 *
 * <p><b>2026-05-28 迁移状态（AP-53 续 5：材质字典绑定彻底迁 V6）</b>：
 * <ul>
 *   <li>字典本体 material_recipe / material_recipe_element <b>保留</b>（非 AP-53 废弃表）。</li>
 *   <li>"料号 → 配方"绑定关系从 V44 {@code mat_part.material_recipe_id} 迁到
 *       V6 {@code material_master.material_recipe_id}（V265 加列 + 回填）。</li>
 *   <li>以下方法全部改读写 V6 {@code material_master}（+ element_bom_item），不再触 V44 mat_part / mat_bom：
 *       {@link #getForExistingPart(String)}（选配 Step2，字典派/BOM 派双分支）、
 *       {@code listActive} / {@code listParts} / {@code bindParts} / {@code unbindParts} /
 *       {@code searchPartsForBinding} / {@code suggestBindings} / {@code confirmBindings}；
 *       {@code create / update / deleteSoft / getDetail} 操作字典本体不变。</li>
 * </ul>
 * <p><b>已知约束</b>：material_master 当前仅 V6 已导入料号（远少于 V44 mat_part），
 * 管理页可绑定料号集合受限于 V6 导入进度；suggestBindings 因 element_bom_item.component_no
 * 是纯元素符号而退化（详见该方法注释 + docs/反模式.md AP-53 续 5）。
 */
@ApplicationScoped
public class MaterialRecipeService {

    @Inject
    EntityManager em;

    /**
     * 仅 ACTIVE 材质列表（不带 elements、不带 count）——供选配候选（SelParamCandidateService）等
     * 只需启用项的场景使用。管理端列表请用 {@link #list(String, boolean)}（全状态 + 搜索 + 排序）。
     */
    public List<MaterialRecipeDTO> listActive() {
        return MaterialRecipe.<MaterialRecipe>find("status = 'ACTIVE' ORDER BY sortOrder").list()
            .stream().map(this::toDTOLite).collect(Collectors.toList());
    }

    /**
     * GET /material-recipes?keyword=&withCount= — 管理端列表（task-0708 · B3 改造）。
     *
     * <ul>
     *   <li><b>全状态</b>：返回 ACTIVE + INACTIVE（停用项排在启用项之后，不再从列表消失）。</li>
     *   <li><b>关键字搜索</b>（keyword 可空）：命中 code / symbol / 任一元素 element_code / element_name
     *       中任意一个即返回（元素维度走 EXISTS 子查询，单条 SQL，无 N+1）。</li>
     *   <li><b>排序</b>：启用优先 → 修改时间倒序 → 创建时间倒序。</li>
     *   <li>withCount=true 时一次性聚合填 boundPartsCount（本期前端不展示，保留兼容）。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public List<MaterialRecipeDTO> list(String keyword, boolean withCount) {
        boolean hasKw = keyword != null && !keyword.isBlank();
        StringBuilder sql = new StringBuilder(
            "SELECT mr.id, mr.code, mr.symbol, mr.name, mr.spec_label, mr.recipe_type, " +
            "       mr.status, mr.sort_order, mr.created_at, mr.updated_at " +
            "FROM material_recipe mr ");
        if (hasKw) {
            sql.append("WHERE (mr.code ILIKE :kw OR mr.symbol ILIKE :kw " +
                "OR EXISTS (SELECT 1 FROM material_recipe_element e " +
                "           WHERE e.recipe_id = mr.id " +
                "             AND (e.element_code ILIKE :kw OR e.element_name ILIKE :kw))) ");
        }
        sql.append("ORDER BY (mr.status = 'ACTIVE') DESC, mr.updated_at DESC, mr.created_at DESC");

        var q = em.createNativeQuery(sql.toString());
        if (hasKw) q.setParameter("kw", "%" + keyword.trim() + "%");
        List<Object[]> rows = q.getResultList();

        List<MaterialRecipeDTO> dtos = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            MaterialRecipeDTO d = new MaterialRecipeDTO();
            d.id = r[0] instanceof UUID u ? u : (r[0] != null ? UUID.fromString(r[0].toString()) : null);
            d.code = (String) r[1];
            d.symbol = (String) r[2];
            d.name = (String) r[3];
            d.specLabel = (String) r[4];
            d.recipeType = (String) r[5];
            d.status = (String) r[6];
            d.sortOrder = r[7] == null ? null : ((Number) r[7]).intValue();
            d.createdAt = toOffsetDateTime(r[8]);
            d.updatedAt = toOffsetDateTime(r[9]);
            dtos.add(d);
        }

        if (!withCount || dtos.isEmpty()) {
            return dtos;
        }

        // 一次性聚合 count（V265: 绑定迁 material_master），内存 join 回 DTO，避免 N+1。
        List<Object[]> countRows = em.createNativeQuery(
                "SELECT material_recipe_id, COUNT(*) AS cnt FROM material_master " +
                "WHERE material_recipe_id IS NOT NULL " +
                "GROUP BY material_recipe_id")
            .getResultList();
        Map<UUID, Long> countByRecipe = countRows.stream().collect(Collectors.toMap(
            r -> (UUID) r[0],
            r -> ((Number) r[1]).longValue()
        ));
        for (MaterialRecipeDTO dto : dtos) {
            dto.boundPartsCount = countByRecipe.getOrDefault(dto.id, 0L);
        }
        return dtos;
    }

    /**
     * GET /material-recipes/{id}/parts — 该材质下绑定的 mat_part 分页列表.
     *
     * @param recipeId 材质 id (验证存在性)
     * @param keyword  模糊匹配 part_no / part_name / specification (可空)
     * @param page     从 0 开始
     * @param size     单页条数
     */
    @SuppressWarnings("unchecked")
    public PageResult<MaterialRecipePartDTO> listParts(UUID recipeId, String keyword, int page, int size) {
        if (recipeId == null) {
            throw new IllegalArgumentException("recipeId 必填");
        }
        if (MaterialRecipe.findById(recipeId) == null) {
            throw new NotFoundException("material_recipe 不存在: " + recipeId);
        }
        page = Pagination.clampPage(page);
        size = Pagination.clampSize(size);

        // V265: 绑定迁 material_master（料号字段从 V44 mat_part 列名映射到 V6）
        StringBuilder where = new StringBuilder("mm.material_recipe_id = :rid");
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        if (hasKeyword) {
            where.append(" AND (mm.material_no ILIKE :kw OR mm.material_name ILIKE :kw " +
                    "OR COALESCE(mm.specification,'') ILIKE :kw)");
        }
        String pattern = hasKeyword ? "%" + keyword.trim() + "%" : null;

        var countQ = em.createNativeQuery("SELECT COUNT(*) FROM material_master mm WHERE " + where)
                .setParameter("rid", recipeId);
        if (hasKeyword) countQ.setParameter("kw", pattern);
        Long total = ((Number) countQ.getSingleResult()).longValue();

        // V6 material_master 无 product_type / status_code 维度：productType→NULL、status→'Y'、size_info→dimension
        var listQ = em.createNativeQuery(
                "SELECT mm.material_no, mm.material_name, mm.specification, mm.dimension, " +
                "       NULL AS product_type, 'Y' AS status_code, mm.unit_weight, " +
                "       mm.material_recipe_id, mr.code, mr.symbol, " +
                "       mm.created_at, mm.updated_at " +
                "FROM material_master mm " +
                "LEFT JOIN material_recipe mr ON mr.id = mm.material_recipe_id " +
                "WHERE " + where + " " +
                "ORDER BY mm.material_no " +
                "LIMIT :sz OFFSET :off")
            .setParameter("rid", recipeId)
            .setParameter("sz", size)
            .setParameter("off", page * size);
        if (hasKeyword) listQ.setParameter("kw", pattern);
        List<Object[]> rows = listQ.getResultList();

        List<MaterialRecipePartDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            MaterialRecipePartDTO dto = new MaterialRecipePartDTO();
            dto.partNo = (String) r[0];
            dto.partName = (String) r[1];
            dto.specification = (String) r[2];
            dto.sizeInfo = (String) r[3];
            dto.productType = (String) r[4];
            dto.statusCode = (String) r[5];
            dto.unitWeight = r[6] == null ? null : new BigDecimal(r[6].toString());
            dto.materialRecipeId = r[7] == null ? null : (UUID) r[7];
            dto.materialRecipeCode = (String) r[8];
            dto.materialRecipeSymbol = (String) r[9];
            dto.createdAt = toOffsetDateTime(r[10]);
            dto.updatedAt = toOffsetDateTime(r[11]);
            content.add(dto);
        }
        return new PageResult<>(content, page, size, total);
    }

    /**
     * POST /material-recipes/{id}/bind-parts — 批量把 partNos 绑定到本材质.
     * 允许从其他材质转移过来(覆盖原 material_recipe_id).
     *
     * @return 实际更新行数
     */
    @Transactional
    public int bindParts(UUID recipeId, List<String> partNos) {
        if (recipeId == null) throw new IllegalArgumentException("recipeId 必填");
        if (partNos == null || partNos.isEmpty()) {
            throw new IllegalArgumentException("partNos 至少 1 项");
        }
        if (MaterialRecipe.findById(recipeId) == null) {
            throw new NotFoundException("material_recipe 不存在: " + recipeId);
        }
        // 去重 + 校验存在性（V265: 绑定迁 material_master）
        Set<String> distinct = new HashSet<>(partNos);
        return em.createNativeQuery(
                "UPDATE material_master SET material_recipe_id = :rid, updated_at = NOW() " +
                "WHERE material_no IN (:pns)")
            .setParameter("rid", recipeId)
            .setParameter("pns", distinct)
            .executeUpdate();
    }

    /**
     * POST /material-recipes/{id}/unbind-parts — 批量解绑 (material_recipe_id 置 NULL).
     * (id 占位仅做 URL 风格一致, 实际只看 partNos.)
     */
    @Transactional
    public int unbindParts(List<String> partNos) {
        if (partNos == null || partNos.isEmpty()) {
            throw new IllegalArgumentException("partNos 至少 1 项");
        }
        Set<String> distinct = new HashSet<>(partNos);
        return em.createNativeQuery(
                "UPDATE material_master SET material_recipe_id = NULL, updated_at = NOW() " +
                "WHERE material_no IN (:pns)")
            .setParameter("pns", distinct)
            .executeUpdate();
    }

    /**
     * GET /material-recipes/search-parts — 供「材质管理 → +绑定料号」子 Drawer 搜 mat_part.
     *
     * @param keyword     模糊匹配 part_no / part_name / specification / size_info (必填)
     * @param onlyUnbound true: 只返回 material_recipe_id IS NULL 的料号
     * @param size        上限 (1-200)
     */
    @SuppressWarnings("unchecked")
    public List<MaterialRecipePartDTO> searchPartsForBinding(String keyword, boolean onlyUnbound, int size) {
        if (keyword == null || keyword.isBlank()) {
            return java.util.Collections.emptyList();
        }
        int safeSize = Math.min(Math.max(size, 1), 200);
        String pattern = "%" + keyword.trim() + "%";

        // V265: 绑定迁 material_master（料号字段映射同 listParts）
        StringBuilder where = new StringBuilder(
                "(mm.material_no ILIKE :kw OR mm.material_name ILIKE :kw " +
                "OR COALESCE(mm.specification,'') ILIKE :kw " +
                "OR COALESCE(mm.dimension,'') ILIKE :kw)");
        if (onlyUnbound) {
            where.append(" AND mm.material_recipe_id IS NULL");
        }

        List<Object[]> rows = em.createNativeQuery(
                "SELECT mm.material_no, mm.material_name, mm.specification, mm.dimension, " +
                "       NULL AS product_type, 'Y' AS status_code, mm.unit_weight, " +
                "       mm.material_recipe_id, mr.code, mr.symbol " +
                "FROM material_master mm " +
                "LEFT JOIN material_recipe mr ON mr.id = mm.material_recipe_id " +
                "WHERE " + where + " " +
                "ORDER BY mm.material_no " +
                "LIMIT :sz")
            .setParameter("kw", pattern)
            .setParameter("sz", safeSize)
            .getResultList();

        List<MaterialRecipePartDTO> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            MaterialRecipePartDTO dto = new MaterialRecipePartDTO();
            dto.partNo = (String) r[0];
            dto.partName = (String) r[1];
            dto.specification = (String) r[2];
            dto.sizeInfo = (String) r[3];
            dto.productType = (String) r[4];
            dto.statusCode = (String) r[5];
            dto.unitWeight = r[6] == null ? null : new BigDecimal(r[6].toString());
            dto.materialRecipeId = r[7] == null ? null : (UUID) r[7];
            dto.materialRecipeCode = (String) r[8];
            dto.materialRecipeSymbol = (String) r[9];
            out.add(dto);
        }
        return out;
    }

    /** GET /material-recipes/{id} — 详情(带 elements). */
    public MaterialRecipeDTO getDetail(UUID id) {
        MaterialRecipe r = MaterialRecipe.findById(id);
        if (r == null) {
            throw new NotFoundException("material_recipe 不存在: " + id);
        }
        MaterialRecipeDTO dto = toDTOLite(r);
        dto.elements = MaterialRecipeElement.<MaterialRecipeElement>find(
                "recipeId = ?1 ORDER BY sortOrder", r.id).list()
            .stream().map(this::toElemDTO).collect(Collectors.toList());
        return dto;
    }

    /**
     * GET /quotations/configure/existing-part/{hfPartNo}/material —
     * 选配 Step2 锁定路径取数(用户在 Step1 选了已存在料号后展示元素配比).
     *
     * <p>V6 数据源（AP-53 老表禁用，2026-05-26 重写；2026-05-28 续 5 恢复字典派）：
     * <ul>
     *   <li>料号验证：material_master 替代 mat_part</li>
     *   <li><b>字典派（recipeBound=true）</b>：material_master.material_recipe_id 非空时，
     *       从 material_recipe + material_recipe_element 取（V265 把绑定从 V44 mat_part 迁来）。
     *       这让管理员在「材质管理」给料号绑的配方（如 AgCu90 = Ag90/Cu10 locked）在选配 Step2 正确展示。</li>
     *   <li><b>BOM 派（recipeBound=false）</b>：未绑定字典时回退 element_bom_item.hf_part_no 主件维度
     *       （V245 加列 + V246 characteristic=MAX 过滤），recipeType="locked" 只读，
     *       element code/name 取自 element_bom_item.component_no，min/max 留 null（Q04 Excel 不导入限值）。</li>
     * </ul>
     *
     * @throws NotFoundException 料号不存在
     */
    @SuppressWarnings("unchecked")
    public ExistingPartMaterialDTO getForExistingPart(String hfPartNo) {
        if (hfPartNo == null || hfPartNo.isBlank()) {
            throw new IllegalArgumentException("hfPartNo 必填");
        }

        // 1. 验证料号在 V6 material_master 存在 + 取其绑定的字典 id
        //    单列 native query → getResultList() 返回 List<原始值>（不是 List<Object[]>）。
        List<?> mmRows = em.createNativeQuery(
                "SELECT material_recipe_id FROM material_master WHERE material_no = :p")
            .setParameter("p", hfPartNo)
            .getResultList();
        if (mmRows.isEmpty()) {
            throw new NotFoundException("料号不存在: " + hfPartNo);
        }
        Object ridObj = mmRows.get(0);
        UUID recipeId = (ridObj instanceof UUID u) ? u
                : (ridObj != null ? UUID.fromString(ridObj.toString()) : null);

        ExistingPartMaterialDTO dto = new ExistingPartMaterialDTO();
        dto.hfPartNo = hfPartNo;

        // 2A. 字典派：料号绑定了 material_recipe → 取字典配方 + 元素（含 min/max/isLocked）
        if (recipeId != null) {
            MaterialRecipe mr = MaterialRecipe.findById(recipeId);
            if (mr != null) {
                dto.recipeBound = true;
                dto.recipeCode = mr.code;
                dto.recipeSymbol = mr.symbol;
                dto.recipeName = mr.name;
                dto.recipeSpec = mr.specLabel;
                dto.recipeType = mr.recipeType;  // locked / editable / partial
                List<MaterialRecipeElement> els = MaterialRecipeElement
                    .<MaterialRecipeElement>find("recipeId = ?1 ORDER BY sortOrder", recipeId).list();
                for (MaterialRecipeElement e : els) {
                    dto.elements.add(new ExistingPartMaterialDTO.Element(
                        e.elementCode, e.elementName, e.defaultPct, e.minPct, e.maxPct, e.isLocked));
                }
                return dto;
            }
            // 绑定 id 指向的字典已被硬删（FK SET NULL 之前的脏数据）→ 下沉 BOM 派
        }

        // 2B. BOM 派：未绑字典 → 从 element_bom_item 取最新 characteristic 的元素配比
        dto.recipeBound = false;
        dto.recipeType = "locked";     // 统一只读
        //    （与 V246 composite_child_elements_mirror SQL 同口径，
        //     按 (customer_no, material_no=投入料号) 分组取 MAX(characteristic)）
        List<Object[]> rows = em.createNativeQuery(
                "SELECT ebi.component_no, ebi.content, ebi.seq_no, ebi.material_no " +
                "FROM element_bom_item ebi " +
                // 2026-06-02 统一 element_bom_item 取版本策略: 对齐 ys_view/composite_child_elements_mirror
                //   规范口径 —— is_current=true AND characteristic=MAX(is_current 子集)。原仅 MAX(characteristic)
                //   不过滤 is_current，重复 is_current 数据下可能取到非当前版本的最大 characteristic。内外层均补 is_current。
                "WHERE ebi.system_type='QUOTE' " +
                "  AND ebi.is_current = true " +
                "  AND ebi.hf_part_no = :p " +
                "  AND ebi.characteristic = ( " +
                "      SELECT MAX(ebi2.characteristic) FROM element_bom_item ebi2 " +
                "      WHERE ebi2.system_type='QUOTE' " +
                "        AND ebi2.is_current = true " +
                "        AND ebi2.customer_no = ebi.customer_no " +
                "        AND ebi2.material_no = ebi.material_no " +
                "  ) " +
                "ORDER BY ebi.material_no, ebi.seq_no")
            .setParameter("p", hfPartNo)
            .getResultList();

        for (Object[] r : rows) {
            String elemCode = r[0] == null ? null : r[0].toString();
            if (elemCode == null || elemCode.isBlank()) continue;
            BigDecimal pct = r[1] == null ? null : new BigDecimal(r[1].toString());
            // element_bom_item 没独立的"元素中文名"列；code 和 name 都用 component_no（Cu/Zn/Ag/Ni）
            dto.elements.add(new ExistingPartMaterialDTO.Element(
                elemCode, elemCode, pct, null, null, true));
        }
        return dto;
    }

    // ── 智能推断(Phase 3 新增)──

    /** 末尾数字后缀剥离: "AgCu3" → "AgCu", "AgNi10" → "AgNi", "CuZn36 预镀Cu+Sn" → "CuZn" */
    private static final Pattern TRAILING_DIGITS = Pattern.compile("^([A-Za-z]+)\\d+.*$");

    /**
     * Native query 中 timestamp with time zone 列的兼容转换 —
     * PG JDBC driver 在 Hibernate 6 + Quarkus 3 下可能返回 OffsetDateTime 或 java.sql.Timestamp,
     * 取决于绑定类型. 用 instanceof 兜底两种(参考 VersionedWriter:596-600 同款模式).
     */
    private OffsetDateTime toOffsetDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof OffsetDateTime odt) return odt;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        if (o instanceof java.time.Instant i) return i.atOffset(java.time.ZoneOffset.UTC);
        return null;
    }

    /**
     * GET /material-recipes/suggest-bindings — 扫描所有 material_recipe_id IS NULL 的 mat_part,
     * 根据 mat_bom.element_name 反查 material_recipe,给出绑定建议.
     *
     * <p>算法(三级置信度):
     * <ol>
     *   <li>EXACT_CODE   — element_name = material_recipe.code (大小写敏感)</li>
     *   <li>EXACT_SYMBOL — element_name = material_recipe.symbol</li>
     *   <li>PREFIX_MATCH — element_name 去掉末尾数字后 = material_recipe.symbol
     *       (如 "AgCu3" → 前缀 "AgCu",匹配 symbol="AgCu" 的所有 recipe)</li>
     * </ol>
     *
     * <p>排除纯元素代码(单字母大写开头 + 长度≤2,如 Cu/Ag/Ni/Zn/Pd)— 它们是单质不是合金.
     * <p>排除纯数字字符串(如 "25.85" 这种导入脏数据).
     */
    @SuppressWarnings("unchecked")
    public List<BindingSuggestionDTO> suggestBindings() {
        // 1. 加载字典 (12 条左右,可全表)
        List<MaterialRecipe> dictAll = MaterialRecipe.<MaterialRecipe>find(
                "status = 'ACTIVE'").list();
        if (dictAll.isEmpty()) return java.util.Collections.emptyList();

        Map<String, MaterialRecipe> byCode = dictAll.stream()
            .collect(Collectors.toMap(r -> r.code, r -> r, (a, b) -> a));
        // symbol 可能重复(AgCu85/AgCu90 共用 symbol=AgCu),聚为 multi-map
        Map<String, List<MaterialRecipe>> bySymbol = dictAll.stream()
            .collect(Collectors.groupingBy(r -> r.symbol));

        // 2. 一次性拉所有"未绑料号 + 该料号的 element_bom_item 元素集合"（V265: 迁 V6）
        //    ⚠️ AP-53 续 5 已知退化：V6 element_bom_item.component_no 是纯元素符号(Cu/Ag/Ni)，
        //    会被下方 isPureElementSymbol 全部跳过 → 候选基本为空。手动绑定仍可用；
        //    更优的 V6 线索源（material_type/material_name → 配方映射）另立 ticket。
        List<Object[]> rows = em.createNativeQuery(
                "SELECT mm.material_no, mm.material_name, mm.specification, " +
                "       array_agg(DISTINCT ebi.component_no) FILTER (WHERE ebi.component_no IS NOT NULL) " +
                "FROM material_master mm " +
                "LEFT JOIN element_bom_item ebi ON ebi.hf_part_no = mm.material_no AND ebi.system_type = 'QUOTE' " +
                "WHERE mm.material_recipe_id IS NULL " +
                "GROUP BY mm.material_no, mm.material_name, mm.specification " +
                "ORDER BY mm.material_no")
            .getResultList();

        List<BindingSuggestionDTO> suggestions = new ArrayList<>();
        for (Object[] r : rows) {
            String partNo = (String) r[0];
            String partName = (String) r[1];
            String specification = (String) r[2];
            String[] elemNames = r[3] == null ? new String[0] : (String[]) r[3];

            BindingSuggestionDTO sug = new BindingSuggestionDTO();
            sug.partNo = partNo;
            sug.partName = partName;
            sug.specification = specification;
            sug.sourceHints = new ArrayList<>();
            sug.candidates = new ArrayList<>();

            // 候选去重 (recipeId 不重复, 取最高置信度)
            LinkedHashMap<UUID, BindingSuggestionDTO.Candidate> seenByRecipe = new LinkedHashMap<>();

            for (String elemRaw : elemNames) {
                if (elemRaw == null || elemRaw.isBlank()) continue;
                String elem = elemRaw.trim();
                // 跳过纯数字 / 单元素代码
                if (isPureNumber(elem)) continue;
                if (isPureElementSymbol(elem)) continue;

                sug.sourceHints.add(elem);

                // 1. EXACT_CODE
                MaterialRecipe codeHit = byCode.get(elem);
                if (codeHit != null) {
                    upsertCandidate(seenByRecipe, codeHit, "EXACT_CODE", elem);
                    continue;
                }

                // 2. EXACT_SYMBOL
                List<MaterialRecipe> symbolHits = bySymbol.get(elem);
                if (symbolHits != null) {
                    for (MaterialRecipe r1 : symbolHits) {
                        upsertCandidate(seenByRecipe, r1, "EXACT_SYMBOL", elem);
                    }
                    continue;
                }

                // 3. PREFIX_MATCH (剥离末尾数字, 匹配 symbol)
                java.util.regex.Matcher m = TRAILING_DIGITS.matcher(elem);
                if (m.matches()) {
                    String prefix = m.group(1);
                    List<MaterialRecipe> prefixHits = bySymbol.get(prefix);
                    if (prefixHits != null) {
                        for (MaterialRecipe r2 : prefixHits) {
                            upsertCandidate(seenByRecipe, r2, "PREFIX_MATCH", elem);
                        }
                    }
                }
            }

            // 去重 hints
            sug.sourceHints = new ArrayList<>(new java.util.LinkedHashSet<>(sug.sourceHints));
            sug.candidates = new ArrayList<>(seenByRecipe.values());

            // 候选不为空 OR 有 hints 才返回(纯无线索的料号也展示,让人工绑)
            if (!sug.candidates.isEmpty() || !sug.sourceHints.isEmpty()) {
                suggestions.add(sug);
            }
        }
        return suggestions;
    }

    /** 候选 upsert: 同 recipe 重复时保留置信度更高的 */
    private static final Map<String, Integer> CONFIDENCE_RANK = Map.of(
        "EXACT_CODE", 3, "EXACT_SYMBOL", 2, "PREFIX_MATCH", 1);

    private void upsertCandidate(LinkedHashMap<UUID, BindingSuggestionDTO.Candidate> seen,
                                 MaterialRecipe r, String confidence, String matchedOn) {
        BindingSuggestionDTO.Candidate existing = seen.get(r.id);
        int newRank = CONFIDENCE_RANK.getOrDefault(confidence, 0);
        if (existing == null || CONFIDENCE_RANK.getOrDefault(existing.confidence, 0) < newRank) {
            seen.put(r.id, new BindingSuggestionDTO.Candidate(
                r.id, r.code, r.symbol, r.name, confidence, matchedOn));
        }
    }

    /** 纯数字 (含小数): "25.85", "100" */
    private boolean isPureNumber(String s) {
        return s.matches("^-?\\d+(\\.\\d+)?$");
    }

    /**
     * 纯元素代码: 1-2 字符大写开头 (Cu/Ag/Ni/Zn/Pd/Au/Sn 等) — 它们是单质不是合金,
     * 不可能命中 material_recipe (字典是合金) 且会产生干扰建议.
     */
    private boolean isPureElementSymbol(String s) {
        if (s.length() > 2) return false;
        // 单字 / 两字, 首大写其余小写
        return s.matches("^[A-Z][a-z]?$");
    }

    /**
     * POST /material-recipes/confirm-bindings — 批量执行(partNo → recipeId)绑定.
     *
     * <p>不校验 partNo 当前是否已绑(允许覆盖),不校验是否在 suggestions 列表里
     * (允许管理员手动指定 partNo + recipeId 任意组合).
     *
     * @return 实际更新行数
     */
    @Transactional
    public int confirmBindings(ConfirmBindingsRequest req) {
        if (req == null || req.items == null || req.items.isEmpty()) {
            throw new IllegalArgumentException("items 至少 1 项");
        }
        // 按 recipeId 分组,每组一条 UPDATE IN
        Map<UUID, List<String>> byRecipe = new HashMap<>();
        for (ConfirmBindingsRequest.Item it : req.items) {
            if (it == null || it.partNo == null || it.recipeId == null) {
                throw new IllegalArgumentException("item.partNo 和 item.recipeId 必填");
            }
            byRecipe.computeIfAbsent(it.recipeId, k -> new ArrayList<>()).add(it.partNo);
        }
        int total = 0;
        for (Map.Entry<UUID, List<String>> entry : byRecipe.entrySet()) {
            // 校验 recipe 存在
            if (MaterialRecipe.findById(entry.getKey()) == null) {
                throw new NotFoundException("material_recipe 不存在: " + entry.getKey());
            }
            total += em.createNativeQuery(
                    "UPDATE material_master SET material_recipe_id = :rid, updated_at = NOW() " +
                    "WHERE material_no IN (:pns)")
                .setParameter("rid", entry.getKey())
                .setParameter("pns", new HashSet<>(entry.getValue()))
                .executeUpdate();
        }
        return total;
    }

    // ── CRUD methods ──

    @Transactional
    public MaterialRecipeDTO create(MaterialRecipeUpsertRequest req) {
        validateUpsert(req, null);
        MaterialRecipe r = new MaterialRecipe();
        r.code = req.code.trim();
        r.symbol = req.symbol.trim();
        r.name = req.name == null ? null : req.name.trim();
        r.specLabel = req.specLabel;
        r.recipeType = req.recipeType;
        r.sortOrder = req.sortOrder == null ? 0 : req.sortOrder;
        r.status = req.status == null ? "ACTIVE" : req.status;
        r.createdAt = OffsetDateTime.now();
        r.updatedAt = OffsetDateTime.now();
        r.persist();

        if (req.elements != null) {
            int seq = 1;
            for (MaterialRecipeUpsertRequest.ElementUpsert e : req.elements) {
                insertElement(r.id, e, seq++);
            }
        }
        return getDetail(r.id);
    }

    @Transactional
    public MaterialRecipeDTO update(UUID id, MaterialRecipeUpsertRequest req) {
        validateUpsert(req, id);
        MaterialRecipe r = MaterialRecipe.findById(id);
        if (r == null) throw new NotFoundException("material_recipe 不存在: " + id);

        r.code = req.code.trim();
        r.symbol = req.symbol.trim();
        r.name = req.name == null ? null : req.name.trim();
        r.specLabel = req.specLabel;
        r.recipeType = req.recipeType;
        r.sortOrder = req.sortOrder == null ? r.sortOrder : req.sortOrder;
        r.status = req.status == null ? r.status : req.status;
        r.updatedAt = OffsetDateTime.now();
        r.persist();

        // 完全替换 elements (简单可靠;副作用:配过的 element id 会变 — 业务上 element 是 immutable 子项)
        MaterialRecipeElement.delete("recipeId", id);
        if (req.elements != null) {
            int seq = 1;
            for (MaterialRecipeUpsertRequest.ElementUpsert e : req.elements) {
                insertElement(id, e, seq++);
            }
        }
        return getDetail(id);
    }

    @Transactional
    public void deleteSoft(UUID id) {
        MaterialRecipe r = MaterialRecipe.findById(id);
        if (r == null) throw new NotFoundException("material_recipe 不存在: " + id);
        r.status = "INACTIVE";
        r.updatedAt = OffsetDateTime.now();
        r.persist();
    }

    private void insertElement(UUID recipeId,
                               MaterialRecipeUpsertRequest.ElementUpsert e,
                               int seq) {
        MaterialRecipeElement el = new MaterialRecipeElement();
        el.recipeId = recipeId;
        el.elementCode = e.elementCode.trim();
        el.elementName = e.elementName == null ? e.elementCode.trim() : e.elementName.trim();
        el.defaultPct = e.defaultPct;
        el.minPct = e.minPct;
        el.maxPct = e.maxPct;
        el.isLocked = e.isLocked != null && e.isLocked;
        el.sortOrder = e.sortOrder == null ? seq : e.sortOrder;
        el.createdAt = OffsetDateTime.now();
        el.persist();
    }

    private void validateUpsert(MaterialRecipeUpsertRequest req, UUID idForUpdate) {
        if (req == null) throw new IllegalArgumentException("request body 必填");
        if (req.code == null || req.code.isBlank()) throw new IllegalArgumentException("code 必填");
        if (req.symbol == null || req.symbol.isBlank()) throw new IllegalArgumentException("symbol 必填");
        // name 本期改为可空（决策#2：导入/新建置 NULL、UI 隐藏、DB 列保留供下游 COALESCE 引用）。
        if (req.recipeType == null
            || !List.of("locked", "editable", "partial").contains(req.recipeType)) {
            throw new IllegalArgumentException("recipeType 必须为 locked/editable/partial");
        }
        if (req.status != null
            && !List.of("ACTIVE", "INACTIVE").contains(req.status)) {
            throw new IllegalArgumentException("status 必须为 ACTIVE/INACTIVE");
        }

        // code 唯一性
        String trimmed = req.code.trim();
        MaterialRecipe dup = MaterialRecipe.find("code = ?1", trimmed).firstResult();
        if (dup != null && (idForUpdate == null || !dup.id.equals(idForUpdate))) {
            throw new IllegalArgumentException("code 已存在: " + trimmed);
        }

        // 校验 elements
        if (req.elements == null || req.elements.isEmpty()) {
            throw new IllegalArgumentException("elements 至少 1 项");
        }
        Set<String> codes = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (MaterialRecipeUpsertRequest.ElementUpsert e : req.elements) {
            if (e.elementCode == null || e.elementCode.isBlank()) {
                throw new IllegalArgumentException("element.elementCode 必填");
            }
            if (e.defaultPct == null) {
                throw new IllegalArgumentException("element.defaultPct 必填: " + e.elementCode);
            }
            if (!codes.add(e.elementCode.trim())) {
                throw new IllegalArgumentException("element.elementCode 重复: " + e.elementCode);
            }
            sum = sum.add(e.defaultPct);

            boolean locked = e.isLocked != null && e.isLocked;
            // recipeType=locked: 所有元素必须 is_locked=true, min/max 必须 NULL
            if ("locked".equals(req.recipeType)) {
                if (!locked) throw new IllegalArgumentException(
                    "recipeType=locked 时所有元素必须 isLocked=true: " + e.elementCode);
                if (e.minPct != null || e.maxPct != null) throw new IllegalArgumentException(
                    "locked 元素 min/max 必须为 NULL: " + e.elementCode);
            }
            // recipeType=editable: 所有元素必须 is_locked=false, min/max 必须有
            if ("editable".equals(req.recipeType)) {
                if (locked) throw new IllegalArgumentException(
                    "recipeType=editable 时所有元素必须 isLocked=false: " + e.elementCode);
                if (e.minPct == null || e.maxPct == null) throw new IllegalArgumentException(
                    "editable 元素必须填 min/max: " + e.elementCode);
                if (e.minPct.compareTo(e.maxPct) > 0) throw new IllegalArgumentException(
                    "min > max: " + e.elementCode);
            }
            // recipeType=partial: 每个元素分别 — locked 元素 min/max=NULL, unlocked 元素 min/max 必填
            if ("partial".equals(req.recipeType)) {
                if (locked) {
                    if (e.minPct != null || e.maxPct != null) throw new IllegalArgumentException(
                        "partial 中 locked 元素 min/max 必须为 NULL: " + e.elementCode);
                } else {
                    if (e.minPct == null || e.maxPct == null) throw new IllegalArgumentException(
                        "partial 中 unlocked 元素必须填 min/max: " + e.elementCode);
                    if (e.minPct.compareTo(e.maxPct) > 0) throw new IllegalArgumentException(
                        "min > max: " + e.elementCode);
                }
            }
        }
        // 默认含量之和必须为 100(±0.01)
        if (sum.subtract(new BigDecimal("100")).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new IllegalArgumentException(
                "元素 default_pct 之和必须 = 100, 当前: " + sum);
        }
    }

    private MaterialRecipeDTO toDTOLite(MaterialRecipe r) {
        MaterialRecipeDTO d = new MaterialRecipeDTO();
        d.id = r.id;
        d.code = r.code;
        d.symbol = r.symbol;
        d.name = r.name;
        d.specLabel = r.specLabel;
        d.recipeType = r.recipeType;
        d.status = r.status;
        d.sortOrder = r.sortOrder;
        d.createdAt = r.createdAt;
        d.updatedAt = r.updatedAt;
        return d;
    }

    private MaterialRecipeElementDTO toElemDTO(MaterialRecipeElement e) {
        MaterialRecipeElementDTO d = new MaterialRecipeElementDTO();
        d.elementCode = e.elementCode;
        d.elementName = e.elementName;
        d.defaultPct = e.defaultPct;
        d.minPct = e.minPct;
        d.maxPct = e.maxPct;
        d.isLocked = e.isLocked;
        d.sortOrder = e.sortOrder;
        return d;
    }
}
