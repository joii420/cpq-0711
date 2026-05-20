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

@ApplicationScoped
public class MaterialRecipeService {

    @Inject
    EntityManager em;

    /** GET /material-recipes — 列表(不带 elements). */
    public List<MaterialRecipeDTO> listActive() {
        return listActive(false);
    }

    /**
     * GET /material-recipes?withCount=true — 列表; withCount=true 时每条 DTO 填 boundPartsCount.
     *
     * <p>实现: 一条聚合 SQL `GROUP BY material_recipe_id` 拉全部 count,内存 join 回 DTO,
     * 避免 N+1 (N 条 recipe N 次 COUNT(*)).
     */
    @SuppressWarnings("unchecked")
    public List<MaterialRecipeDTO> listActive(boolean withCount) {
        List<MaterialRecipeDTO> dtos = MaterialRecipe.<MaterialRecipe>find(
                "status = 'ACTIVE' ORDER BY sortOrder").list()
            .stream().map(this::toDTOLite).collect(Collectors.toList());

        if (!withCount || dtos.isEmpty()) {
            return dtos;
        }

        // 一次性聚合 count
        List<Object[]> rows = em.createNativeQuery(
                "SELECT material_recipe_id, COUNT(*) AS cnt FROM mat_part " +
                "WHERE material_recipe_id IS NOT NULL " +
                "GROUP BY material_recipe_id")
            .getResultList();
        Map<UUID, Long> countByRecipe = rows.stream().collect(Collectors.toMap(
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

        StringBuilder where = new StringBuilder("mp.material_recipe_id = :rid");
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        if (hasKeyword) {
            where.append(" AND (mp.part_no ILIKE :kw OR mp.part_name ILIKE :kw " +
                    "OR COALESCE(mp.specification,'') ILIKE :kw)");
        }
        String pattern = hasKeyword ? "%" + keyword.trim() + "%" : null;

        var countQ = em.createNativeQuery("SELECT COUNT(*) FROM mat_part mp WHERE " + where)
                .setParameter("rid", recipeId);
        if (hasKeyword) countQ.setParameter("kw", pattern);
        Long total = ((Number) countQ.getSingleResult()).longValue();

        var listQ = em.createNativeQuery(
                "SELECT mp.part_no, mp.part_name, mp.specification, mp.size_info, " +
                "       mp.product_type, mp.status_code, mp.unit_weight, " +
                "       mp.material_recipe_id, mr.code, mr.symbol, " +
                "       mp.created_at, mp.updated_at " +
                "FROM mat_part mp " +
                "LEFT JOIN material_recipe mr ON mr.id = mp.material_recipe_id " +
                "WHERE " + where + " " +
                "ORDER BY mp.part_no " +
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
        // 去重 + 校验存在性
        Set<String> distinct = new HashSet<>(partNos);
        return em.createNativeQuery(
                "UPDATE mat_part SET material_recipe_id = :rid, updated_at = NOW() " +
                "WHERE part_no IN (:pns)")
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
                "UPDATE mat_part SET material_recipe_id = NULL, updated_at = NOW() " +
                "WHERE part_no IN (:pns)")
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

        StringBuilder where = new StringBuilder(
                "(mp.part_no ILIKE :kw OR mp.part_name ILIKE :kw " +
                "OR COALESCE(mp.specification,'') ILIKE :kw " +
                "OR COALESCE(mp.size_info,'') ILIKE :kw)");
        if (onlyUnbound) {
            where.append(" AND mp.material_recipe_id IS NULL");
        }

        List<Object[]> rows = em.createNativeQuery(
                "SELECT mp.part_no, mp.part_name, mp.specification, mp.size_info, " +
                "       mp.product_type, mp.status_code, mp.unit_weight, " +
                "       mp.material_recipe_id, mr.code, mr.symbol " +
                "FROM mat_part mp " +
                "LEFT JOIN material_recipe mr ON mr.id = mp.material_recipe_id " +
                "WHERE " + where + " " +
                "ORDER BY mp.part_no " +
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
     * <p>策略(详见 docs/选配与基础数据料号材质关系.md 第五节决策树):
     * <ul>
     *   <li>mat_part.material_recipe_id 非 NULL → 字典派,JOIN material_recipe +
     *       material_recipe_element 取数,recipeBound=true</li>
     *   <li>mat_part.material_recipe_id IS NULL → BOM 派,查
     *       mat_bom (bom_type='ELEMENT', latest part_version) 取数,
     *       recipeBound=false,elements 的 min/max=null,isLocked=true(只读)</li>
     * </ul>
     *
     * @throws NotFoundException 料号不存在
     */
    @SuppressWarnings("unchecked")
    public ExistingPartMaterialDTO getForExistingPart(String hfPartNo) {
        if (hfPartNo == null || hfPartNo.isBlank()) {
            throw new IllegalArgumentException("hfPartNo 必填");
        }

        // 1. 查 mat_part 拿 material_recipe_id (单列 native query 返 List<Object>)
        List<?> partRows = em.createNativeQuery(
                "SELECT material_recipe_id FROM mat_part WHERE part_no = :p")
            .setParameter("p", hfPartNo)
            .getResultList();
        if (partRows.isEmpty()) {
            throw new NotFoundException("料号不存在: " + hfPartNo);
        }
        Object recipeIdObj = partRows.get(0);
        UUID recipeId = recipeIdObj == null ? null : (UUID) recipeIdObj;

        ExistingPartMaterialDTO dto = new ExistingPartMaterialDTO();
        dto.hfPartNo = hfPartNo;

        if (recipeId != null) {
            // 字典派
            MaterialRecipe r = MaterialRecipe.findById(recipeId);
            if (r == null) {
                // 字典被硬删了 — 降级走 BOM 派(material_recipe_id FK 是 SET NULL,
                // 理论不该出现这种情况,但兜底保护)
                fillFromBom(dto, hfPartNo);
            } else {
                dto.recipeBound = true;
                dto.recipeCode = r.code;
                dto.recipeSymbol = r.symbol;
                dto.recipeName = r.name;
                dto.recipeSpec = r.specLabel;
                dto.recipeType = r.recipeType;
                List<MaterialRecipeElement> elems = MaterialRecipeElement.<MaterialRecipeElement>find(
                        "recipeId = ?1 ORDER BY sortOrder", recipeId).list();
                for (MaterialRecipeElement e : elems) {
                    dto.elements.add(new ExistingPartMaterialDTO.Element(
                        e.elementCode, e.elementName, e.defaultPct,
                        e.minPct, e.maxPct, e.isLocked));
                }
            }
        } else {
            // BOM 派
            fillFromBom(dto, hfPartNo);
        }
        return dto;
    }

    /**
     * BOM 派填充:从 mat_bom (bom_type='ELEMENT', 最新 part_version) 拉元素配比.
     *
     * <p>字段映射:
     * <ul>
     *   <li>elementCode = elementName = mat_bom.element_name 原值
     *       (导入数据通常存的是元素代码 "Cu/Zn/Ag",无中文名映射)</li>
     *   <li>pct = mat_bom.composition_pct</li>
     *   <li>minPct/maxPct = null (BOM 派不存约束)</li>
     *   <li>isLocked = true (只读)</li>
     * </ul>
     *
     * <p>已知数据质量问题(Bug #3,见文档第七节):导入流程当前可能把
     * "元素百分比"错写到 element_name 列,composition_pct 留空。本方法
     * 按规范取数,**不做兜底解析** — 待导入流程修干净。
     */
    @SuppressWarnings("unchecked")
    private void fillFromBom(ExistingPartMaterialDTO dto, String hfPartNo) {
        dto.recipeBound = false;
        dto.recipeType = "locked"; // BOM 派统一只读,recipeType=locked 让前端不渲染输入框

        List<Object[]> rows = em.createNativeQuery(
                "SELECT element_name, composition_pct, seq_no " +
                "FROM mat_bom " +
                "WHERE hf_part_no = :p " +
                "  AND bom_type = 'ELEMENT' " +
                "  AND part_version = (" +
                "      SELECT MAX(part_version) FROM mat_bom " +
                "      WHERE hf_part_no = :p AND bom_type = 'ELEMENT')" +
                "ORDER BY seq_no")
            .setParameter("p", hfPartNo)
            .getResultList();

        for (Object[] r : rows) {
            String elemName = r[0] == null ? null : r[0].toString();
            if (elemName == null || elemName.isBlank()) continue;
            BigDecimal pct = r[1] == null ? null : new BigDecimal(r[1].toString());
            dto.elements.add(new ExistingPartMaterialDTO.Element(
                elemName, elemName, pct, null, null, true));
        }
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

        // 2. 一次性拉所有"未绑料号 + 该料号的 mat_bom ELEMENT 行 element_name 集合"
        List<Object[]> rows = em.createNativeQuery(
                "SELECT mp.part_no, mp.part_name, mp.specification, " +
                "       array_agg(DISTINCT mb.element_name) FILTER (WHERE mb.element_name IS NOT NULL) " +
                "FROM mat_part mp " +
                "LEFT JOIN mat_bom mb ON mb.hf_part_no = mp.part_no AND mb.bom_type = 'ELEMENT' " +
                "WHERE mp.material_recipe_id IS NULL " +
                "GROUP BY mp.part_no, mp.part_name, mp.specification " +
                "ORDER BY mp.part_no")
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
                    "UPDATE mat_part SET material_recipe_id = :rid, updated_at = NOW() " +
                    "WHERE part_no IN (:pns)")
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
        r.name = req.name.trim();
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
        r.name = req.name.trim();
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
        if (req.name == null || req.name.isBlank()) throw new IllegalArgumentException("name 必填");
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
