package com.cpq.configure.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.dto.Pagination;
import com.cpq.configure.dto.BindingSuggestionDTO;
import com.cpq.configure.dto.ConfirmBindingsRequest;
import com.cpq.configure.dto.ExistingPartMaterialDTO;
import com.cpq.configure.dto.CompositionItemDTO;
import com.cpq.configure.dto.MaterialRecipeConfigDTO;
import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipeElementDTO;
import com.cpq.configure.dto.MaterialRecipePartDTO;
import com.cpq.configure.dto.MaterialRecipeUpsertRequest;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;
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

    /** 配置层的读写底座（发号 / 校验 / 灌元素行）——导入侧与 UI 侧共用，见 M-0a。 */
    @Inject
    MaterialRecipeConfigService configService;

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
        // task-260901 · B-15：elementCodes 与 configCount 用<b>同一条 SQL 的标量子查询</b>取全，
        // 🚫 不许按材质逐条查（列表页 N+1 高风险点）。SQL 条数与材质数无关，恒为 1（withCount 时 2）。
        // ⚠️ elementCodes 查的是 material_recipe_composition（BC-2b）——0 配置的材质也要有值，
        //    否则 AC-17 的列表 tag 会空。
        StringBuilder sql = new StringBuilder(
            "SELECT mr.id, mr.code, mr.symbol, mr.name, mr.spec_label, mr.recipe_type, " +
            "       mr.status, mr.sort_order, mr.created_at, mr.updated_at, " +
            "       mr.allow_custom_content, " +
            // B-21 / AC-36：元素符号取<b>权威链</b> element_no → element 主表，
            //   material_recipe_composition.element_code 只是快照（材质 00262 那行整行串位，
            //   快照里存的是编号 10004）。主表查无时 COALESCE 回退快照，绝不返回空。
            "       (SELECT array_agg(COALESCE(el.element_code, mc.element_code) " +
            "                         ORDER BY mc.sort_order, mc.element_code) " +
            "          FROM material_recipe_composition mc " +
            "          LEFT JOIN element el ON el.element_no = mc.element_no " +
            "         WHERE mc.recipe_id = mr.id) AS element_codes, " +
            "       (SELECT count(*) FROM material_recipe_config cfg " +
            "         WHERE cfg.recipe_id = mr.id AND cfg.status = 'ACTIVE') AS config_count " +
            "FROM material_recipe mr ");
        if (hasKw) {
            sql.append("WHERE (mr.code ILIKE :kw OR mr.symbol ILIKE :kw OR mr.name ILIKE :kw " +
                "OR EXISTS (SELECT 1 FROM material_recipe_composition mc2 " +
                "           LEFT JOIN element el2 ON el2.element_no = mc2.element_no " +
                "           WHERE mc2.recipe_id = mr.id " +
                // 快照与权威值都参与匹配：搜 'Sn' 能搜到 00262（权威），搜 '10004' 也仍能搜到（快照）
                "             AND (mc2.element_code ILIKE :kw OR mc2.element_name ILIKE :kw " +
                "               OR el2.element_code ILIKE :kw OR el2.element_name ILIKE :kw))) ");
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
            d.allowCustomContent = r[10] != null && (Boolean) r[10];
            d.elementCodes = toStringList(r[11]);
            d.configCount = r[12] == null ? 0L : ((Number) r[12]).longValue();
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

    /** GET /material-recipes/{id} — 详情（默认只返 ACTIVE 配置）。 */
    public MaterialRecipeDTO getDetail(UUID id) {
        return getDetail(id, false);
    }

    /**
     * GET /material-recipes/{id}?includeInactiveConfigs= — 详情（task-260901 · B-15）。
     *
     * <p>响应 {@code elements} → {@code configs}（BC-1），另加：
     * <ul>
     *   <li>{@code composition[]} —— 材质的元素组成，<b>配置矩阵列的权威来源</b>（M-0）；</li>
     *   <li>{@code compositionEditable} —— 无 ACTIVE 配置时 true，有配置即 false（M-0b）。</li>
     * </ul>
     * SQL 条数固定 4（材质 / 组成 / 配置 / 配置元素），与配置数无关。
     */
    public MaterialRecipeDTO getDetail(UUID id, boolean includeInactiveConfigs) {
        MaterialRecipe r = MaterialRecipe.findById(id);
        if (r == null) {
            throw new NotFoundException("material_recipe 不存在: " + id);
        }
        MaterialRecipeDTO dto = toDTOLite(r);
        List<MaterialRecipeComposition> comp = configService.listComposition(r.id);
        // B-21 / AC-36：组成的三段展示值全部取自 element 主表（权威链 element_no），
        // 主表查无时回退快照。🚫 只改读，不回写 material_recipe_composition。
        var index = configService.loadElementIndexByNo(
            comp.stream().map(c -> c.elementNo).filter(java.util.Objects::nonNull).toList());
        dto.composition = comp.stream()
            .map(c -> new CompositionItemDTO(
                c.elementNo,
                MaterialRecipeConfigService.authoritative(c.elementNo, index, c.elementCode, false),
                MaterialRecipeConfigService.authoritative(c.elementNo, index, c.elementName, true),
                c.sortOrder))
            .collect(Collectors.toList());
        dto.elementCodes = dto.composition.stream()
            .map(c -> c.elementCode).collect(Collectors.toList());
        dto.configs = configService.listConfigDTOs(r.id, includeInactiveConfigs);
        long activeCount = dto.configs.stream()
            .filter(c -> MaterialRecipeConfig.ACTIVE.equals(c.status)).count();
        dto.configCount = activeCount;
        dto.compositionEditable = activeCount == 0;
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
                // task-260901（BC-3）：元素行已下沉到配置层。料号绑定仍挂在<b>材质</b>层
                // （material_master 无配置维度，§2.2 明确本期不引入），故这里取该材质
                // <b>第一条 ACTIVE 配置</b>（按 seq）作为回显来源，并把它的 configNo 一并返回。
                List<MaterialRecipeConfig> cfgs = configService.listConfigs(recipeId, false);
                if (!cfgs.isEmpty()) {
                    MaterialRecipeConfig first = cfgs.get(0);
                    dto.configNo = first.configNo;
                    List<MaterialRecipeElement> els = MaterialRecipeElement
                        .<MaterialRecipeElement>find("configId = ?1 ORDER BY sortOrder", first.id).list();
                    for (MaterialRecipeElement e : els) {
                        dto.elements.add(new ExistingPartMaterialDTO.Element(
                            e.elementCode, e.elementName, e.defaultPct, e.minPct, e.maxPct, e.isLocked));
                    }
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

    /** 材质名上限（material_recipe.symbol 是 varchar(32)，超长必须在应用层拦，不能让 PG 抛 value too long）。 */
    public static final int SYMBOL_MAX_LEN = 32;

    /**
     * POST /material-recipes —— 新建材质（task-260901 · B-20，服务 AC-33 / AC-34）。
     *
     * <p><b>建材质 + 推导元素组成 + 建配置，一个事务，要么全成要么全不成。</b>步骤：
     * <ol>
     *   <li>逐组校验 Σ≈1 与单值范围；</li>
     *   <li><b>各组元素种类集合互相比对</b>，不全相同 → 400 {@code COMPOSITION_INCONSISTENT_ACROSS_CONFIGS}；</li>
     *   <li>组间内容逐值判重（M-4）→ 409 {@code CONFIG_DUPLICATED_IN_REQUEST}；</li>
     *   <li><b>全过才发材质编号</b>（B-6）、写 composition（取第 1 组的元素与顺序）、逐组发配置编号（B-5）。</li>
     * </ol>
     * 🚨 第 2 步的判据来自 {@link MaterialRecipeRules#findFirstElementSetMismatch} ——
     * 与导入侧 M-5b <b>同一份代码</b>（M-0a），🚫 不许另写一套。
     * 🚨 发号排在全部校验之后 ⇒ 失败不消耗材质编号（AC-34）。
     */
    @Transactional
    public MaterialRecipeDTO create(MaterialRecipeUpsertRequest req) {
        if (req == null) throw new IllegalArgumentException("request body 必填");
        String symbol = normalizeSymbol(req.symbol);
        validateTypeAndStatus(req);
        assertSymbolNotDuplicated(symbol, null);

        if (req.configs == null || req.configs.isEmpty()) {
            throw MaterialRecipeApiException.badRequest("COMPOSITION_EMPTY", "材质必须至少有一个元素");
        }

        // ── 元素解析：把所有组的 elementNo / elementCode 一次性查出来（无 N+1）──
        List<String> allNos = new ArrayList<>();
        List<String> allCodes = new ArrayList<>();
        for (MaterialRecipeUpsertRequest.ConfigUpsert g : req.configs) {
            if (g == null || g.elements == null || g.elements.isEmpty()) {
                throw MaterialRecipeApiException.badRequest("COMPOSITION_EMPTY", "材质必须至少有一个元素");
            }
            for (MaterialRecipeUpsertRequest.ElementUpsert e : g.elements) {
                if (e == null) {
                    throw MaterialRecipeApiException.badRequest("COMPOSITION_EMPTY", "材质必须至少有一个元素");
                }
                if (e.elementNo != null && !e.elementNo.isBlank()) allNos.add(e.elementNo.trim());
                if (e.elementCode != null && !e.elementCode.isBlank()) allCodes.add(e.elementCode.trim());
            }
        }
        Map<String, MaterialRecipeConfigService.ElementRef> index =
            configService.loadElementIndex(allNos, allCodes);

        // ── ① 逐组：解析 + 单值范围 + 组内元素重复 + Σ ──
        List<List<MaterialRecipeConfigService.ResolvedPct>> groups = new ArrayList<>();
        List<Set<String>> keySets = new ArrayList<>();          // 键 = elementNo（UI 侧键域）
        List<List<String>> codeSets = new ArrayList<>();        // 报文展示用（符号）
        for (MaterialRecipeUpsertRequest.ConfigUpsert g : req.configs) {
            List<MaterialRecipeConfigService.ResolvedPct> resolved = new ArrayList<>();
            Set<String> keys = new LinkedHashSet<>();
            List<String> codes = new ArrayList<>();
            BigDecimal sum = BigDecimal.ZERO;
            int so = 1;
            for (MaterialRecipeUpsertRequest.ElementUpsert e : g.elements) {
                String key = (e.elementNo != null && !e.elementNo.isBlank())
                    ? e.elementNo.trim() : (e.elementCode == null ? null : e.elementCode.trim());
                MaterialRecipeConfigService.ElementRef ref = key == null ? null : index.get(key);
                if (ref == null) {
                    throw MaterialRecipeApiException.notFound("ELEMENT_NOT_FOUND", "元素编号不存在：" + key);
                }
                if (!keys.add(ref.elementNo)) {
                    throw MaterialRecipeApiException.badRequest("COMPOSITION_ELEMENT_DUPLICATED",
                        "元素重复：" + ref.elementNo);
                }
                BigDecimal pct = e.effectivePct();
                if (!MaterialRecipeRules.pctInRange(pct, MaterialRecipeRules.HUNDRED)) {
                    throw MaterialRecipeApiException.badRequest("CONFIG_PCT_ILLEGAL",
                        "含量必须大于 0 且不超过 100：" + ref.elementCode);
                }
                sum = sum.add(pct);
                codes.add(ref.elementCode);
                resolved.add(new MaterialRecipeConfigService.ResolvedPct(
                    ref.elementNo, ref.elementCode, ref.elementName, pct, so++));
            }
            if (!MaterialRecipeRules.sumIsOnePct(sum)) {
                throw MaterialRecipeApiException.badRequest("CONFIG_SUM_NOT_ONE",
                    "含量合计必须为 1，实际 " + MaterialRecipeRules.formatRatioSum(
                        sum.divide(MaterialRecipeRules.HUNDRED, 12, java.math.RoundingMode.HALF_UP)));
            }
            groups.add(resolved);
            keySets.add(keys);
            codeSets.add(codes);
        }

        // ── ② 各组元素种类集合互比（M-0a 共享判据，与导入侧同源）──
        int[] mismatch = MaterialRecipeRules.findFirstElementSetMismatch(keySets);
        if (mismatch != null) {
            int i = mismatch[0], j = mismatch[1];
            throw MaterialRecipeApiException.badRequest("COMPOSITION_INCONSISTENT_ACROSS_CONFIGS",
                "配方" + (i + 1) + " 与 配方" + (j + 1) + " 的元素种类不同（配方" + (i + 1) + "="
                    + MaterialRecipeRules.formatSet(codeSets.get(i), ", ") + "，配方" + (j + 1) + "="
                    + MaterialRecipeRules.formatSet(codeSets.get(j), ", ")
                    + "）。同一材质下各配方必须使用相同的元素");
        }

        // ── ③ 组间逐值判重（M-4）──
        for (int i = 0; i < groups.size(); i++) {
            for (int j = i + 1; j < groups.size(); j++) {
                if (MaterialRecipeRules.sameContent(
                        MaterialRecipeConfigService.contentByCodeResolved(groups.get(i)),
                        MaterialRecipeConfigService.contentByCodeResolved(groups.get(j)))) {
                    throw MaterialRecipeApiException.conflict("CONFIG_DUPLICATED_IN_REQUEST",
                        "配方" + (i + 1) + " 与 配方" + (j + 1) + " 的含量完全相同，请删除其中一组");
                }
            }
        }

        // ── ④ 全部校验通过，才发号落库 ──
        MaterialRecipe r = new MaterialRecipe();
        r.code = nextRecipeCode();
        r.symbol = symbol;
        r.name = (req.name == null || req.name.isBlank()) ? symbol : req.name.trim();
        r.specLabel = req.specLabel;
        r.recipeType = req.recipeType == null ? "locked" : req.recipeType;
        r.sortOrder = req.sortOrder == null ? 0 : req.sortOrder;
        r.status = req.status == null ? "ACTIVE" : req.status;
        r.allowCustomContent = req.allowCustomContent != null && req.allowCustomContent;
        r.createdAt = OffsetDateTime.now();
        r.updatedAt = OffsetDateTime.now();
        r.persist();

        // 元素组成 = 第 1 组的元素及其顺序（M-0a ①）
        List<MaterialRecipeConfigService.ResolvedPct> first = groups.get(0);
        int so = 1;
        for (MaterialRecipeConfigService.ResolvedPct e : first) {
            persistComposition(r.id, e.elementNo, e.elementCode, e.elementName, so++);
        }
        Map<String, Integer> orderByNo = new LinkedHashMap<>();
        int oi = 1;
        for (MaterialRecipeConfigService.ResolvedPct e : first) orderByNo.put(e.elementNo, oi++);

        // 逐组发配置号（水位只查一次，之后内存递增 —— 新材质水位必为 0，这里直接从 1 起）
        int seq = 0;
        for (List<MaterialRecipeConfigService.ResolvedPct> g : groups) {
            MaterialRecipeConfig cfg = configService.newConfigWithSeq(r, ++seq, null);
            List<MaterialRecipeConfigService.ResolvedPct> ordered = new ArrayList<>();
            for (MaterialRecipeConfigService.ResolvedPct e : g) {
                ordered.add(new MaterialRecipeConfigService.ResolvedPct(
                    e.elementNo, e.elementCode, e.elementName, e.pct,
                    orderByNo.getOrDefault(e.elementNo, e.sortOrder)));
            }
            ordered.sort((a, b) -> Integer.compare(a.sortOrder, b.sortOrder));
            configService.insertElements(cfg.id, ordered);
        }
        em.flush();
        return getDetail(r.id);
    }

    /**
     * PUT /material-recipes/{id} —— 编辑材质（task-260901 · B-16，服务 AC-16 / AC-24 / AC-28 / AC-31）。
     *
     * <p>编辑态<b>不带配置</b>；配置的增删改一律走配置端点。要点：
     * <ul>
     *   <li>{@code code} 只读（既有契约，TC-E3）；</li>
     *   <li>{@code composition} <b>仅当该材质无 ACTIVE 配置时可变更</b>（M-0b）——有配置时提交了
     *       与现值不同的组成 → 409 {@code COMPOSITION_LOCKED}；<b>传相同值视为未改、放行</b>
     *       （否则前端每次保存材质名都会被拒）。比较按 {@code (elementNo, sortOrder)} 的有序列表判等；</li>
     *   <li>{@code allowCustomContent} 置 true 但材质无 ACTIVE 配置 → 409 {@code CUSTOM_CONTENT_NEEDS_CONFIG}。</li>
     * </ul>
     */
    @Transactional
    public MaterialRecipeDTO update(UUID id, MaterialRecipeUpsertRequest req) {
        MaterialRecipe r = MaterialRecipe.findById(id);
        if (r == null) throw new NotFoundException("material_recipe 不存在: " + id);
        if (req == null) throw new IllegalArgumentException("request body 必填");
        // 材质编号只读（TC-E3 / api.md）：强制沿用既有 code、忽略入参。
        req.code = r.code;

        String symbol = normalizeSymbol(req.symbol);
        validateTypeAndStatus(req);
        assertSymbolNotDuplicated(symbol, id);

        long activeConfigs = configService.countActiveConfigs(id);

        // ① 元素组成（M-0b 两段式）
        if (req.composition != null) {
            List<MaterialRecipeConfigService.ResolvedPct> wanted = resolveComposition(req.composition);
            List<MaterialRecipeComposition> current = configService.listComposition(id);
            if (!compositionEquals(current, wanted)) {
                if (activeConfigs > 0) {
                    throw MaterialRecipeApiException.conflict("COMPOSITION_LOCKED",
                        "该材质已有 " + activeConfigs + " 条含量配置，元素组成不可修改。"
                            + "换元素组成请新建材质，或先删除全部含量配置");
                }
                MaterialRecipeComposition.delete("recipeId", id);
                em.flush();
                int so = 1;
                for (MaterialRecipeConfigService.ResolvedPct e : wanted) {
                    persistComposition(id, e.elementNo, e.elementCode, e.elementName, so++);
                }
            }
        }

        // ② 自定义含量开关
        boolean allowCustom = req.allowCustomContent != null ? req.allowCustomContent : r.allowCustomContent;
        if (allowCustom && activeConfigs == 0) {
            throw MaterialRecipeApiException.conflict("CUSTOM_CONTENT_NEEDS_CONFIG",
                "该材质尚未配置任何含量，无法开启自定义含量");
        }

        r.symbol = symbol;
        r.name = (req.name == null || req.name.isBlank()) ? symbol : req.name.trim();
        r.specLabel = req.specLabel;
        r.recipeType = req.recipeType == null ? r.recipeType : req.recipeType;
        r.sortOrder = req.sortOrder == null ? r.sortOrder : req.sortOrder;
        r.status = req.status == null ? r.status : req.status;
        r.allowCustomContent = allowCustom;
        r.updatedAt = OffsetDateTime.now();
        r.persist();
        em.flush();
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

    // ── task-260901 helpers ──

    /**
     * B-6 材质编号自增：只统计 {@code ^[0-9]{5}$} 的 code 求 max + 1（一次查询 + 纯函数）。
     * 脏值 '992' 不是五位 ⇒ 天然排除，'00993' 与 '992' 永不撞键。
     */
    @SuppressWarnings("unchecked")
    public String nextRecipeCode() {
        List<String> codes = em.createNativeQuery(
                "SELECT code FROM material_recipe WHERE code ~ '^[0-9]{5}$'").getResultList();
        return MaterialRecipeNumbering.nextRecipeCode(codes);
    }

    private void persistComposition(UUID recipeId, String elementNo, String elementCode,
                                    String elementName, int sortOrder) {
        MaterialRecipeComposition c = new MaterialRecipeComposition();
        c.recipeId = recipeId;
        c.elementNo = elementNo;
        c.elementCode = elementCode;
        c.elementName = elementName;
        c.sortOrder = sortOrder;
        c.createdAt = OffsetDateTime.now();
        c.persist();
    }

    /** 把请求里的 composition 解析成 element 主表口径（一次查库）。 */
    private List<MaterialRecipeConfigService.ResolvedPct> resolveComposition(
            List<MaterialRecipeUpsertRequest.CompositionUpsert> input) {
        if (input == null || input.isEmpty()) {
            throw MaterialRecipeApiException.badRequest("COMPOSITION_EMPTY", "材质必须至少有一个元素");
        }
        List<String> nos = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (MaterialRecipeUpsertRequest.CompositionUpsert c : input) {
            if (c == null) {
                throw MaterialRecipeApiException.badRequest("COMPOSITION_EMPTY", "材质必须至少有一个元素");
            }
            if (c.elementNo != null && !c.elementNo.isBlank()) nos.add(c.elementNo.trim());
            if (c.elementCode != null && !c.elementCode.isBlank()) codes.add(c.elementCode.trim());
        }
        Map<String, MaterialRecipeConfigService.ElementRef> index = configService.loadElementIndex(nos, codes);

        List<MaterialRecipeConfigService.ResolvedPct> out = new ArrayList<>(input.size());
        Set<String> seen = new LinkedHashSet<>();
        int so = 1;
        for (MaterialRecipeUpsertRequest.CompositionUpsert c : input) {
            String key = (c.elementNo != null && !c.elementNo.isBlank())
                ? c.elementNo.trim() : (c.elementCode == null ? null : c.elementCode.trim());
            MaterialRecipeConfigService.ElementRef ref = key == null ? null : index.get(key);
            if (ref == null) {
                throw MaterialRecipeApiException.notFound("ELEMENT_NOT_FOUND", "元素编号不存在：" + key);
            }
            if (!seen.add(ref.elementNo)) {
                throw MaterialRecipeApiException.badRequest("COMPOSITION_ELEMENT_DUPLICATED",
                    "元素重复：" + ref.elementNo);
            }
            int order = c.sortOrder == null ? so : c.sortOrder;
            so++;
            out.add(new MaterialRecipeConfigService.ResolvedPct(
                ref.elementNo, ref.elementCode, ref.elementName, null, order));
        }
        out.sort((a, b) -> Integer.compare(a.sortOrder, b.sortOrder));
        // 归一 sortOrder 为 1..n，保证「传相同值视为未改」的比较不受客户端序号写法影响
        List<MaterialRecipeConfigService.ResolvedPct> normalized = new ArrayList<>(out.size());
        int i = 1;
        for (MaterialRecipeConfigService.ResolvedPct e : out) {
            normalized.add(new MaterialRecipeConfigService.ResolvedPct(
                e.elementNo, e.elementCode, e.elementName, null, i++));
        }
        return normalized;
    }

    /** 元素组成判等：按 {@code (elementNo, sortOrder)} 的<b>有序列表</b>比对（M-0b）。 */
    private boolean compositionEquals(List<MaterialRecipeComposition> current,
                                      List<MaterialRecipeConfigService.ResolvedPct> wanted) {
        if (current.size() != wanted.size()) return false;
        List<MaterialRecipeComposition> sorted = new ArrayList<>(current);
        sorted.sort((a, b) -> Integer.compare(a.sortOrder, b.sortOrder));
        for (int i = 0; i < sorted.size(); i++) {
            if (!sorted.get(i).elementNo.equals(wanted.get(i).elementNo)) return false;
        }
        return true;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 必填");
        }
        String t = symbol.trim();
        if (t.length() > SYMBOL_MAX_LEN) {
            throw MaterialRecipeApiException.badRequest("RECIPE_SYMBOL_TOO_LONG",
                "材质名最多 " + SYMBOL_MAX_LEN + " 字符，当前 " + t.length() + " 字符");
        }
        return t;
    }

    /** 材质名即材质身份（D2）：ACTIVE 材质之间不许重名。 */
    private void assertSymbolNotDuplicated(String symbol, UUID selfId) {
        List<MaterialRecipe> dups = MaterialRecipe.<MaterialRecipe>find(
            "symbol = ?1 AND status = 'ACTIVE'", symbol).list();
        for (MaterialRecipe d : dups) {
            if (selfId == null || !d.id.equals(selfId)) {
                throw MaterialRecipeApiException.conflict("RECIPE_SYMBOL_DUPLICATED",
                    "材质名已被材质 " + d.code + " 使用。材质名即材质身份，不允许重名");
            }
        }
    }

    private void validateTypeAndStatus(MaterialRecipeUpsertRequest req) {
        if (req.recipeType != null
            && !List.of("locked", "editable", "partial").contains(req.recipeType)) {
            throw new IllegalArgumentException("recipeType 必须为 locked/editable/partial");
        }
        if (req.status != null && !List.of("ACTIVE", "INACTIVE").contains(req.status)) {
            throw new IllegalArgumentException("status 必须为 ACTIVE/INACTIVE");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object sqlArray) {
        if (sqlArray == null) return new ArrayList<>();
        if (sqlArray instanceof String[] arr) return new ArrayList<>(List.of(arr));
        if (sqlArray instanceof Object[] arr) {
            List<String> out = new ArrayList<>(arr.length);
            for (Object o : arr) if (o != null) out.add(o.toString());
            return out;
        }
        if (sqlArray instanceof java.sql.Array a) {
            try {
                Object inner = a.getArray();
                return toStringList(inner);
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }
        return new ArrayList<>();
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
        d.allowCustomContent = r.allowCustomContent;
        return d;
    }

    private MaterialRecipeElementDTO toElemDTO(MaterialRecipeElement e) {
        MaterialRecipeElementDTO d = new MaterialRecipeElementDTO();
        d.elementNo = e.elementNo;
        d.elementCode = e.elementCode;
        d.elementName = e.elementName;
        d.defaultPct = MaterialRecipeConfigService.pctString(e.defaultPct);
        d.minPct = MaterialRecipeConfigService.pctString(e.minPct);
        d.maxPct = MaterialRecipeConfigService.pctString(e.maxPct);
        d.isLocked = e.isLocked;
        d.sortOrder = e.sortOrder;
        return d;
    }
}
