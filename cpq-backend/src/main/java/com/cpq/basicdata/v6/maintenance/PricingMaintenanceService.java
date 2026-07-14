package com.cpq.basicdata.v6.maintenance;

import com.cpq.basicdata.v6.maintenance.dto.*;
import com.cpq.basicdata.v6.util.DecimalScale;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import com.cpq.common.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 核价基础数据维护 · 读写服务（task-0712 · backtask §B3/§B4）。
 *
 * <p>纪律：所有多表聚合一律用原生 SQL 一次取回、Java 内存装配，<b>禁止在循环里查库</b>（沿用 tesk-0709 约束）。
 * groupKey / content 结构一律取自 {@link PricingSheetRegistry}（与各 P*Handler 同源）。
 */
@ApplicationScoped
public class PricingMaintenanceService {

    @Inject EntityManager em;
    @Inject PricingSheetRegistry registry;
    @Inject VersionedV6Writer writer;

    /** master 逻辑名 → [表, 编码列, 名称列]（NAME join / lookup 共用）。 */
    private static final Map<String, String[]> MASTER = Map.of(
        "process",  new String[]{"process_master",  "process_no",   "process_name"},
        "element",  new String[]{"element",         "element_code", "element_name"},
        "material", new String[]{"material_master", "material_no",  "material_name"});

    // ==================================================================
    // §1 料号列表：有核价数据的销售料号
    // ==================================================================
    public PartListPage listParts(String keyword, int page, int size) {
        int pg = Math.max(1, page);
        int sz = Math.min(Math.max(1, size), 200);
        String cfg = partsCfgUnion();
        boolean hasKw = keyword != null && !keyword.isBlank();
        String kwClause = hasKw
            ? " WHERE a.mno ILIKE :kw OR COALESCE(mm.material_name,'') ILIKE :kw"
            : "";

        // total（去重料号数）
        String countSql =
            "SELECT COUNT(*) FROM (" +
            "  SELECT a.mno FROM (SELECT mno, COUNT(DISTINCT sk) c, MAX(uat) u FROM (" + cfg +
            "  ) cfg WHERE mno IS NOT NULL GROUP BY mno) a" +
            "  LEFT JOIN material_master mm ON mm.material_no = a.mno" + kwClause +
            ") t";
        Query cq = em.createNativeQuery(countSql);
        if (hasKw) cq.setParameter("kw", "%" + keyword.trim() + "%");
        long total = ((Number) cq.getSingleResult()).longValue();

        String pageSql =
            "SELECT a.mno, mm.material_name, mm.specification, mm.dimension, a.c, a.u FROM (" +
            "  SELECT mno, COUNT(DISTINCT sk) c, MAX(uat) u FROM (" + cfg +
            "  ) cfg WHERE mno IS NOT NULL GROUP BY mno) a" +
            "  LEFT JOIN material_master mm ON mm.material_no = a.mno" + kwClause +
            " ORDER BY a.u DESC NULLS LAST, a.mno" +
            " LIMIT :lim OFFSET :off";
        Query pq = em.createNativeQuery(pageSql);
        if (hasKw) pq.setParameter("kw", "%" + keyword.trim() + "%");
        pq.setParameter("lim", sz);
        pq.setParameter("off", (long) (pg - 1) * sz);

        List<Object[]> rows = pq.getResultList();
        List<PartListPage.PartListItem> items = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            PartListPage.PartListItem it = new PartListPage.PartListItem();
            it.materialNo = str(r[0]);
            it.materialName = str(r[1]);
            it.specification = str(r[2]);
            it.dimension = str(r[3]);
            it.configuredCount = ((Number) r[4]).intValue();
            it.totalSheets = registry.all().size();
            it.lastUpdatedAt = toOdt(r[5]);
            items.add(it);
        }
        return new PartListPage(total, pg, sz, items);
    }

    /** 16 段 UNION ALL：每组投影 (锚→mno, sheetKey→sk, updated_at→uat)，仅 is_current + PRICING [+ price_type]。 */
    private String partsCfgUnion() {
        List<String> segs = new ArrayList<>();
        for (PricingSheetDef d : registry.all()) {
            StringBuilder w = new StringBuilder("is_current = TRUE AND system_type = 'PRICING'");
            if (d.priceTypeConst != null) w.append(" AND price_type = '").append(d.priceTypeConst).append("'");
            segs.add("SELECT " + d.anchorColumn + " AS mno, '" + d.sheetKey + "' AS sk, updated_at AS uat FROM "
                + d.tableName + " WHERE " + w);
        }
        return String.join("\n UNION ALL \n", segs);
    }

    // ==================================================================
    // §2 Sheet 元数据（16 组列定义）
    // ==================================================================
    public List<SheetMetaDTO> listSheets() {
        List<SheetMetaDTO> out = new ArrayList<>();
        for (PricingSheetDef d : registry.all()) {
            SheetMetaDTO m = new SheetMetaDTO();
            m.sheetKey = d.sheetKey;
            m.tabName = d.tabName;
            m.group = d.group;
            m.order = d.order;
            m.masterDetail = d.masterDetail;
            m.salesPartAnchor = d.anchorColumn;
            m.columns = d.columns;
            out.add(m);
        }
        return out;
    }

    // ==================================================================
    // §3 料号概览：16 组当前状态
    // ==================================================================
    public OverviewDTO overview(String materialNo) {
        OverviewDTO dto = new OverviewDTO();
        dto.materialNo = materialNo;
        Object[] mi = materialInfo(materialNo);
        if (mi != null) {
            dto.materialName = str(mi[0]);
            dto.specification = str(mi[1]);
            dto.dimension = str(mi[2]);
        }

        // 一条 UNION ALL：每组该料号所有行 (sk, ver, is_current, updated_at)，内存聚合。
        List<String> segs = new ArrayList<>();
        for (PricingSheetDef d : registry.all()) {
            StringBuilder w = new StringBuilder(d.anchorColumn + " = :mno AND system_type = 'PRICING'");
            if (d.priceTypeConst != null) w.append(" AND price_type = '").append(d.priceTypeConst).append("'");
            segs.add("SELECT '" + d.sheetKey + "' AS sk, " + d.versionColumn + "::text AS ver, is_current AS cur, updated_at AS uat FROM "
                + d.tableName + " WHERE " + w);
        }
        String sql = "SELECT sk, COUNT(*) AS n, COUNT(DISTINCT ver) AS vc, " +
            "MAX(ver) FILTER (WHERE cur) AS cur_ver, MAX(uat) AS last_uat FROM (" +
            String.join("\n UNION ALL \n", segs) + ") v GROUP BY sk";
        Query q = em.createNativeQuery(sql);
        q.setParameter("mno", materialNo);
        List<Object[]> rows = q.getResultList();

        Map<String, Object[]> bySk = new HashMap<>();
        for (Object[] r : rows) bySk.put(str(r[0]), r);

        List<OverviewDTO.SheetStatus> sheets = new ArrayList<>();
        for (PricingSheetDef d : registry.all()) {
            OverviewDTO.SheetStatus s = new OverviewDTO.SheetStatus();
            s.sheetKey = d.sheetKey;
            Object[] r = bySk.get(d.sheetKey);
            if (r == null || ((Number) r[1]).longValue() == 0) {
                s.hasData = false;
                s.versionCount = 0;
            } else {
                s.hasData = true;
                s.versionCount = ((Number) r[2]).intValue();
                s.currentVersion = str(r[3]);
                s.lastUpdatedAt = toOdt(r[4]);
            }
            sheets.add(s);
        }
        dto.sheets = sheets;
        // 完全不存在的料号（既无 material_master 主档、又 16 组全无核价数据）→ 404，不返回空壳。
        boolean anyData = sheets.stream().anyMatch(s -> s.hasData);
        if (mi == null && !anyData) throw new BusinessException(404, "料号不存在: " + materialNo);
        return dto;
    }

    // ==================================================================
    // §4 读取某组数据（当前版 / 历史版）
    // ==================================================================
    public RowsDTO readRows(String materialNo, String sheetKey, String version) {
        PricingSheetDef d = def(sheetKey);
        if (!materialExists(materialNo)) throw new BusinessException(404, "料号不存在: " + materialNo);
        RowsDTO dto = new RowsDTO();
        dto.sheetKey = sheetKey;
        dto.materialNo = materialNo;
        dto.editable = (version == null);   // 历史版恒只读（C7）

        String table = d.masterDetail ? d.childTable : d.tableName;
        String verCol = d.masterDetail ? d.childVersionColumn : d.versionColumn;
        LinkedHashMap<String, Object> gk = d.masterDetail ? d.childGroupKey(materialNo) : d.completeGroupKey(materialNo);

        // SELECT: 数据列(t.col) + NAME(join 带出)，附 __ver / __cur
        List<String> selects = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        StringBuilder joins = new StringBuilder();
        int ji = 0;
        for (var c : d.columns) {
            if ("NAME".equals(c.role)) continue;   // 由 MASTER/MASTER_2HOP join 提供
            selects.add("t." + c.name + " AS " + c.name);
            aliases.add(c.name);
            if (c.dropdown != null && "MASTER".equals(c.dropdown.kind)) {
                String[] mst = MASTER.get(c.dropdown.master);
                String ja = "m" + (ji++);
                joins.append(" LEFT JOIN ").append(mst[0]).append(" ").append(ja)
                     .append(" ON ").append(ja).append(".").append(mst[1]).append(" = t.").append(c.name);
                selects.add(ja + "." + mst[2] + " AS " + c.dropdown.nameColumn);
                aliases.add(c.dropdown.nameColumn);
            } else if (c.dropdown != null && "MASTER_2HOP".equals(c.dropdown.kind)) {
                // 两跳 join（task-0712 · childtask-1 · B2）：t.<col> → bridgeTable.bridgeKey → bridgeTable.bridgeFk → nameTable.nameTablePk
                var dd = c.dropdown;
                String jb = "b" + (ji++);
                String jn = "n" + (ji++);
                joins.append(" LEFT JOIN ").append(dd.bridgeTable).append(" ").append(jb)
                     .append(" ON ").append(jb).append(".").append(dd.bridgeKey).append(" = t.").append(c.name);
                joins.append(" LEFT JOIN ").append(dd.nameTable).append(" ").append(jn)
                     .append(" ON ").append(jn).append(".").append(dd.nameTablePk)
                     .append(" = ").append(jb).append(".").append(dd.bridgeFk);
                selects.add(jn + "." + dd.nameValueCol + " AS " + dd.nameColumn);
                aliases.add(dd.nameColumn);
            }
        }
        selects.add("t." + verCol + "::text AS __ver");
        selects.add("t.is_current AS __cur");

        StringBuilder where = new StringBuilder();
        Map<String, Object> binds = new LinkedHashMap<>();
        appendGkWhere(where, binds, gk, "t");
        if (version != null) {
            where.append(" AND t.").append(verCol).append(" = :__reqver");
            binds.put("__reqver", version);
        } else {
            where.append(" AND t.is_current = TRUE");
        }

        String orderBy = buildOrderBy(d);
        String sql = "SELECT " + String.join(", ", selects) + " FROM " + table + " t" + joins
            + " WHERE " + where + orderBy;
        Query q = em.createNativeQuery(sql);
        binds.forEach(q::setParameter);
        List<Object[]> raw = q.getResultList();

        List<Map<String, Object>> rows = new ArrayList<>(raw.size());
        Set<String> verSet = new LinkedHashSet<>();
        boolean anyCurrent = false;
        int verIdx = aliases.size(), curIdx = aliases.size() + 1;
        for (Object[] r : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < aliases.size(); i++) {
                String col = aliases.get(i);
                Object val = r[i];
                Integer scale = d.decimalScales.get(col);
                // DECIMAL 列一律以"按列 scale 定标的字符串"返回（api.md §4）：BigDecimal 保 scale、禁 double、禁科学计数。
                if (scale != null && val != null) val = scaledString(val, scale);
                row.put(col, val);
            }
            rows.add(row);
            if (r[verIdx] != null) verSet.add(str(r[verIdx]));
            if (Boolean.TRUE.equals(r[curIdx])) anyCurrent = true;
        }
        dto.rows = rows;
        dto.version = verSet.size() == 1 ? verSet.iterator().next() : (version != null ? version : null);
        dto.isCurrent = (version == null) || anyCurrent;

        // 主从 BOM：附主表信息（MATERIAL_BOM 单组 → production_no；ELEMENT_BOM 合并多材质料号 → 省略）
        if (d.masterDetail && !d.extraAnchorColumns.contains("material_part_no")) {
            dto.masterInfo = masterInfo(d, materialNo, version);
        }
        return dto;
    }

    private Map<String, Object> masterInfo(PricingSheetDef d, String materialNo, String version) {
        LinkedHashMap<String, Object> gk = d.completeGroupKey(materialNo);
        StringBuilder where = new StringBuilder();
        Map<String, Object> binds = new LinkedHashMap<>();
        appendGkWhere(where, binds, gk, "t");
        if (version != null) { where.append(" AND t.").append(d.versionColumn).append(" = :__reqver"); binds.put("__reqver", version); }
        else where.append(" AND t.is_current = TRUE");
        String sql = "SELECT t." + d.versionColumn + "::text AS bom_version"
            + (d.masterDescriptorColumns.contains("production_no") ? ", t.production_no" : "")
            + ", t.bom_type FROM " + d.tableName + " t WHERE " + where + " LIMIT 1";
        Query q = em.createNativeQuery(sql);
        binds.forEach(q::setParameter);
        List<Object[]> r = q.getResultList();
        if (r.isEmpty()) return null;
        Object[] row = r.get(0);
        Map<String, Object> mi = new LinkedHashMap<>();
        mi.put("bomVersion", str(row[0]));
        int idx = 1;
        if (d.masterDescriptorColumns.contains("production_no")) mi.put("productionNo", str(row[idx++]));
        mi.put("bomType", str(row[idx]));
        return mi;
    }

    // ==================================================================
    // §5 版本列表
    // ==================================================================
    public VersionsDTO versions(String materialNo, String sheetKey) {
        PricingSheetDef d = def(sheetKey);
        // BOM 用主表版本列；单表用自身。completeGroupKey 已排除 extraAnchor(material_part_no) → ELEMENT_BOM 合并所有材质料号。
        LinkedHashMap<String, Object> gk = d.completeGroupKey(materialNo);
        StringBuilder where = new StringBuilder();
        Map<String, Object> binds = new LinkedHashMap<>();
        appendGkWhere(where, binds, gk, "t");

        String sql =
            "SELECT ver, cur, src, full_name, uat FROM (" +
            "  SELECT t." + d.versionColumn + "::text AS ver, t.is_current AS cur, t.source AS src, " +
            "         t.updated_by AS ub, t.updated_at AS uat, " +
            "         ROW_NUMBER() OVER (PARTITION BY t." + d.versionColumn +
            "           ORDER BY t.is_current DESC, t.updated_at DESC NULLS LAST) AS rn " +
            "  FROM " + d.tableName + " t WHERE " + where +
            ") x LEFT JOIN \"user\" u ON u.id = x.ub WHERE x.rn = 1 " +
            "ORDER BY (CASE WHEN ver ~ '^[0-9]+$' THEN ver::int END) DESC NULLS LAST, ver DESC";
        Query q = em.createNativeQuery(sql);
        binds.forEach(q::setParameter);
        List<Object[]> raw = q.getResultList();

        List<VersionsDTO.VersionInfo> out = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            VersionsDTO.VersionInfo vi = new VersionsDTO.VersionInfo();
            vi.version = str(r[0]);
            vi.isCurrent = Boolean.TRUE.equals(r[1]);
            vi.source = str(r[2]);
            vi.operator = r[3] != null ? str(r[3]) : "系统导入";
            vi.operatedAt = toOdt(r[4]);
            out.add(vi);
        }
        return new VersionsDTO(out);
    }

    // ==================================================================
    // §7 主表候选下拉
    // ==================================================================
    public LookupResponse lookup(String masterType, String keyword, int limit) {
        String[] m = MASTER.get(masterType);
        if (m == null) throw new BusinessException(400, "masterType 非法: " + masterType);
        int lim = Math.min(Math.max(1, limit), 100);
        boolean hasKw = keyword != null && !keyword.isBlank();
        StringBuilder sql = new StringBuilder(
            "SELECT " + m[1] + " AS code, " + m[2] + " AS name FROM " + m[0] + " WHERE 1=1");
        if ("element".equals(masterType)) sql.append(" AND status = 'ACTIVE'");
        if (hasKw) sql.append(" AND (" + m[1] + " ILIKE :kw OR " + m[2] + " ILIKE :kw)");
        sql.append(" ORDER BY " + m[1] + " LIMIT :lim");
        Query q = em.createNativeQuery(sql.toString());
        if (hasKw) q.setParameter("kw", "%" + keyword.trim() + "%");
        q.setParameter("lim", lim);
        List<Object[]> raw = q.getResultList();
        List<LookupResponse.LookupItem> items = new ArrayList<>(raw.size());
        for (Object[] r : raw) items.add(new LookupResponse.LookupItem(str(r[0]), str(r[1])));
        return new LookupResponse(items);
    }

    // ==================================================================
    // §6 保存整组（编辑升版，C5/C6/C9）
    // ==================================================================
    @Transactional
    public SaveGroupResult saveGroup(String materialNo, String sheetKey, SaveGroupRequest req, UUID userId) {
        PricingSheetDef d = def(sheetKey);
        // 料号存在性校验（backtask §B4 步骤1）：防止对完全虚构的料号从零 CREATED 落库、污染 /parts 列表。
        if (!materialExists(materialNo)) throw new BusinessException(404, "料号不存在: " + materialNo);
        List<Map<String, Object>> body = (req == null) ? null : req.rows;
        // 护栏（C5）：至少留一行；整组下线走专门 API（不在本期）
        if (body == null || body.isEmpty())
            throw new BusinessException(422, "至少保留一行数据；整组下线（清空版本组）走专门 API，不在本期");

        // 已导入数据的编码体系可能与主表/枚举不相交（tesk-0709 历史遗留）；MASTER/ENUM 校验只针对
        // "相对当前持久化版本而言新增/改动过的值"，未改动的历史值放行——否则连原样保存都会被拒（阻断缺陷复盘）。
        List<String> checkableCols = checkableColumns(d);
        Map<String, Set<String>> existingByCol = loadExistingColumnValues(d, materialNo, checkableCols);
        validateMasters(d, body, existingByCol);   // MASTER 编码列存在性批量校验（零 N+1），仅校验新增/改动值
        validateEnums(d, body, existingByCol);     // ENUM 列合法性校验（api.md §6），仅校验新增/改动值；BOOLEAN 仍全量校验

        if (d.masterDetail && d.extraAnchorColumns.contains("material_part_no"))
            return saveElementBom(d, materialNo, req, userId);
        if (d.masterDetail)
            return saveMaterialBom(d, materialNo, req, userId);
        return saveSingleTable(d, materialNo, req, userId);
    }

    /** 单表组保存：复用批量 writeVersionedGroups（单元素），descriptor=[production_no?, source, updated_by] 与导入同源。 */
    private SaveGroupResult saveSingleTable(PricingSheetDef d, String materialNo, SaveGroupRequest req, UUID userId) {
        LinkedHashMap<String, Object> gk = d.completeGroupKey(materialNo);
        acquireGroupLock(d.tableName, gk);   // 先入临界区，再读+校验（消除 TOCTOU）
        String curV = loadCurrentVersion(d.tableName, d.versionColumn, gk);
        assertOptimisticLock(req.expectedCurrentVersion, curV);

        boolean hasProd = d.descriptorColumns.contains("production_no");
        Map<List<String>, String> prodByKey = hasProd ? loadProductionNoByRowKey(d, gk) : Map.of();

        List<Map<String, Object>> newRows = new ArrayList<>();
        for (Map<String, Object> fr : req.rows) {
            Map<String, Object> row = extractContentRow(d, fr);
            if (hasProd) row.put("production_no", prodByKey.get(rowKeyOf(d, fr)));  // 从现有当前版按行键继承
            row.put("source", "MANUAL");
            row.put("updated_by", userId);
            newRows.add(row);
        }
        List<String> descriptors = new ArrayList<>();
        if (hasProd) descriptors.add("production_no");
        descriptors.add("source");
        descriptors.add("updated_by");

        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        groups.put(gk, newRows);
        Map<Map<String, Object>, String> out = writer.writeVersionedGroups(
            d.tableName, d.versionColumn, d.contentColumns, null, descriptors, groups);
        return result(curV, out.get(gk));
    }

    /** MATERIAL_BOM 保存：主表落 source/updated_by + production_no(per-material)；子表整批比对升版。 */
    private SaveGroupResult saveMaterialBom(PricingSheetDef d, String materialNo, SaveGroupRequest req, UUID userId) {
        LinkedHashMap<String, Object> masterGk = d.completeGroupKey(materialNo);
        LinkedHashMap<String, Object> childGk = d.childGroupKey(materialNo);
        acquireGroupLock(d.tableName, masterGk);   // 先入临界区（与 writeVersionedMasterDetails 的 mPrefix 锁同 key），再读+校验
        String curV = loadCurrentVersion(d.tableName, d.versionColumn, masterGk);
        assertOptimisticLock(req.expectedCurrentVersion, curV);

        List<Map<String, Object>> childRows = new ArrayList<>();
        String prodNo = null;
        for (Map<String, Object> fr : req.rows) {
            Map<String, Object> row = extractContentRow(d, fr);   // childContent 含 production_no
            childRows.add(row);
            if (prodNo == null && row.get("production_no") != null) prodNo = String.valueOf(row.get("production_no"));
        }
        Map<String, Object> masterFixed = new LinkedHashMap<>();
        masterFixed.put("source", "MANUAL");
        masterFixed.put("updated_by", userId);
        Map<String, Object> masterContent = new LinkedHashMap<>();
        if (prodNo != null) masterContent.put("production_no", prodNo);

        List<VersionedV6Writer.MasterDetailItem> items = List.of(
            new VersionedV6Writer.MasterDetailItem(masterGk, childGk, childRows, masterContent));
        Map<Map<String, Object>, String> out = writer.writeVersionedMasterDetails(
            d.tableName, d.versionColumn, masterFixed,
            d.childTable, d.childVersionColumn, d.contentColumns, items);
        return result(curV, out.get(masterGk));
    }

    /**
     * ELEMENT_BOM 保存：合并展示按 material_part_no 分组各自升版（技术经理裁定"单 tab 合并展示"）。
     * <p>多材质料号 = 多版本线，无单一当前版，故不做 expectedCurrentVersion 严格乐观锁；并发由写入器
     * 的 pg_advisory_xact_lock 逐组串行化兜底。三态按各组综合：全新→CREATED / 任一升版→UPGRADED / 全同→UNCHANGED。
     */
    private SaveGroupResult saveElementBom(PricingSheetDef d, String materialNo, SaveGroupRequest req, UUID userId) {
        LinkedHashMap<String, List<Map<String, Object>>> byPart = new LinkedHashMap<>();
        for (Map<String, Object> fr : req.rows) {
            Object p = fr.get("material_part_no");
            String part = (p == null || String.valueOf(p).isBlank()) ? "" : String.valueOf(p);
            byPart.computeIfAbsent(part, k -> new ArrayList<>()).add(fr);
        }

        Map<String, Object> masterFixed = new LinkedHashMap<>();
        masterFixed.put("bom_type", "MATERIAL");
        masterFixed.put("source", "MANUAL");
        masterFixed.put("updated_by", userId);

        List<VersionedV6Writer.MasterDetailItem> items = new ArrayList<>();
        List<String> partOf = new ArrayList<>();
        Map<String, String> curByPart = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byPart.entrySet()) {
            String part = e.getKey().isEmpty() ? null : e.getKey();
            LinkedHashMap<String, Object> masterGk = elementGk(d, materialNo, part);
            LinkedHashMap<String, Object> childGk = new LinkedHashMap<>(masterGk);
            curByPart.put(e.getKey(), loadCurrentVersion(d.tableName, d.versionColumn, masterGk));
            List<Map<String, Object>> childRows = new ArrayList<>();
            for (Map<String, Object> fr : e.getValue()) childRows.add(extractContentRow(d, fr));
            items.add(new VersionedV6Writer.MasterDetailItem(masterGk, childGk, childRows));
            partOf.add(e.getKey());
        }

        Map<Map<String, Object>, String> out = writer.writeVersionedMasterDetails(
            d.tableName, d.versionColumn, masterFixed,
            d.childTable, d.childVersionColumn, d.contentColumns, items);

        boolean anyExisting = false, anyUpgrade = false;
        String repVersion = null;
        for (int i = 0; i < items.size(); i++) {
            String before = curByPart.get(partOf.get(i));
            String after = out.get(items.get(i).masterGroupKey);
            if (before != null) anyExisting = true;
            if (!Objects.equals(before, after)) anyUpgrade = true;
            repVersion = after;
        }
        String state = !anyExisting ? "CREATED" : (anyUpgrade ? "UPGRADED" : "UNCHANGED");
        return new SaveGroupResult(state, items.size() == 1 ? repVersion : null, true);
    }

    // ---- 保存辅助 ----

    /** 从前端行提取 content 列值 + decimal 按 DB 列 scale 归一（防虚假升版，同 tesk-0709 精度纪律）。 */
    private Map<String, Object> extractContentRow(PricingSheetDef d, Map<String, Object> fr) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String col : d.contentColumns) {
            Object v = fr.get(col);
            Integer scale = d.decimalScales.get(col);
            if (scale != null) v = DecimalScale.at(asDecimal(v), scale);
            row.put(col, v);
        }
        return row;
    }

    private List<String> rowKeyOf(PricingSheetDef d, Map<String, Object> fr) {
        List<String> k = new ArrayList<>(d.rowKeyColumns.size());
        for (String c : d.rowKeyColumns) { Object v = fr.get(c); k.add(v == null ? "" : String.valueOf(v)); }
        return k;
    }

    private Map<List<String>, String> loadProductionNoByRowKey(PricingSheetDef d, Map<String, Object> gk) {
        StringBuilder where = new StringBuilder();
        Map<String, Object> binds = new LinkedHashMap<>();
        appendGkWhere(where, binds, gk, "t");
        StringBuilder sel = new StringBuilder();
        for (String c : d.rowKeyColumns) sel.append("t.").append(c).append(", ");
        String sql = "SELECT " + sel + "t.production_no FROM " + d.tableName + " t WHERE " + where + " AND t.is_current = TRUE";
        Query q = em.createNativeQuery(sql);
        binds.forEach(q::setParameter);
        List<Object[]> raw = q.getResultList();
        int n = d.rowKeyColumns.size();
        Map<List<String>, String> m = new HashMap<>();
        for (Object[] r : raw) {
            List<String> key = new ArrayList<>(n);
            for (int i = 0; i < n; i++) key.add(r[i] == null ? "" : String.valueOf(r[i]));
            m.put(key, str(r[n]));
        }
        return m;
    }

    /**
     * 进入版本组写入临界区：取与 {@link VersionedV6Writer} 内部逐字一致的 pg_advisory_xact_lock。
     * <p>消除乐观锁 TOCTOU（backtask §B4 "避免 TOCTOU"）：先入锁再读当前版本、再校验 expectedCurrentVersion，
     * 使并发过期写入串行化后能读到已提交的新版本从而正确 409。lockKey 必须与 writer 的 advisoryLockPrefix
     * 完全一致（= table + "|" + gk 各值 String.valueOf 以 "|" 连接），否则锁的不是同一把、TOCTOU 仍在。
     * PG 事务级 advisory lock 同事务内可重入，writer 稍后再取同 key 不阻塞、不死锁。
     */
    private void acquireGroupLock(String table, Map<String, Object> gk) {
        StringBuilder key = new StringBuilder(table);
        for (Object v : gk.values()) key.append("|").append(String.valueOf(v));
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:k))")
          .setParameter("k", key.toString()).getSingleResult();
    }

    private String loadCurrentVersion(String table, String verCol, Map<String, Object> gk) {
        StringBuilder where = new StringBuilder();
        Map<String, Object> binds = new LinkedHashMap<>();
        appendGkWhere(where, binds, gk, "t");
        Query q = em.createNativeQuery(
            "SELECT t." + verCol + "::text FROM " + table + " t WHERE " + where + " AND t.is_current = TRUE LIMIT 1");
        binds.forEach(q::setParameter);
        List<?> r = q.getResultList();
        return r.isEmpty() ? null : str(r.get(0));
    }

    private void assertOptimisticLock(String expected, String curV) {
        String e = (expected == null || expected.isBlank()) ? null : expected;
        if (!Objects.equals(e, curV))
            throw new BusinessException(409,
                "当前版本已被他人升级或状态不一致，请刷新后重试（期望=" + e + "，实际=" + curV + "）");
    }

    private SaveGroupResult result(String curV, String retV) {
        String state = (curV == null) ? "CREATED" : (Objects.equals(retV, curV) ? "UNCHANGED" : "UPGRADED");
        return new SaveGroupResult(state, retV, true);
    }

    private LinkedHashMap<String, Object> elementGk(PricingSheetDef d, String materialNo, String part) {
        LinkedHashMap<String, Object> gk = new LinkedHashMap<>();
        gk.put("system_type", "PRICING");
        gk.putAll(d.fixedGroupKey);              // customer_no=_GLOBAL_
        gk.put(d.anchorColumn, materialNo);      // material_no
        gk.put("material_part_no", part);        // 可为 null
        return gk;
    }

    private static final Map<String, String> MASTER_ZH =
        Map.of("process", "工序号", "element", "元素代码", "material", "料号");

    /** MASTER/ENUM 校验涉及的列名（SUBDIM+MASTER 下拉列 / ENUM 下拉列），用于批量取"当前版本已有值"。 */
    private List<String> checkableColumns(PricingSheetDef d) {
        List<String> cols = new ArrayList<>();
        for (ColumnDef c : d.columns) {
            boolean isMaster = "SUBDIM".equals(c.role) && c.dropdown != null && "MASTER".equals(c.dropdown.kind);
            boolean isEnum = c.dropdown != null && "ENUM".equals(c.dropdown.kind) && c.dropdown.options != null;
            if (isMaster || isEnum) cols.add(c.name);
        }
        return cols;
    }

    /**
     * 批量取"该组当前持久化版本"里 {@code colNames} 各列的 distinct 值（一次查询，零 N+1）。
     * <p>单表组：查 {@code d.tableName}，groupKey = completeGroupKey(materialNo)；主从组（含 ELEMENT_BOM
     * 合并展示）：查 {@code d.childTable}，groupKey = childGroupKey(materialNo)（不含 material_part_no，
     * 与 readRows 合并语义一致）。均限定 is_current=TRUE；从零新建时无当前版 → 各列返回空集，
     * 等价于"全部值都是新值"，回退为原严格全量校验（C9/C12 新建走下拉的既有语义保留）。
     */
    private Map<String, Set<String>> loadExistingColumnValues(PricingSheetDef d, String materialNo, List<String> colNames) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String c : colNames) result.put(c, new HashSet<>());
        if (colNames.isEmpty()) return result;

        String table = d.masterDetail ? d.childTable : d.tableName;
        LinkedHashMap<String, Object> gk = d.masterDetail ? d.childGroupKey(materialNo) : d.completeGroupKey(materialNo);
        StringBuilder where = new StringBuilder();
        Map<String, Object> binds = new LinkedHashMap<>();
        appendGkWhere(where, binds, gk, "t");
        where.append(" AND t.is_current = TRUE");

        List<String> selects = new ArrayList<>();
        for (String c : colNames) selects.add("t." + c);
        Query q = em.createNativeQuery("SELECT " + String.join(", ", selects) + " FROM " + table + " t WHERE " + where);
        binds.forEach(q::setParameter);
        List<?> raw = q.getResultList();

        int n = colNames.size();
        for (Object o : raw) {
            Object[] r = (n == 1) ? new Object[]{o} : (Object[]) o;
            for (int i = 0; i < n; i++) {
                if (r[i] != null) result.get(colNames.get(i)).add(String.valueOf(r[i]));
            }
        }
        return result;
    }

    /**
     * MASTER 编码列存在性批量校验：每 master 一次 IN 查询（零 N+1）；缺失 → 400。
     * <p><b>已导入数据兼容（阻断缺陷修复）</b>：编码体系可能与主表不相交（如导入工序码 Z002 与
     * {@code process_master} 的 MRO-AS-0001 完全不重叠），若逐行强校验，连"原样保存/只改价格"都会 400。
     * 改为按列做集合差——{@code incomingCodes - existingByCol[col]} 才需要校验是否存在于主表；未改动的
     * 历史值（哪怕不在主表里）直接放行。新建场景 existingByCol 为空集，等价于校验全部值（严格口径不变）。
     */
    private void validateMasters(PricingSheetDef d, List<Map<String, Object>> rows, Map<String, Set<String>> existingByCol) {
        Map<String, Set<String>> byMaster = new LinkedHashMap<>();
        for (ColumnDef c : d.columns) {
            if (!"SUBDIM".equals(c.role) || c.dropdown == null || !"MASTER".equals(c.dropdown.kind)) continue;
            Set<String> existing = existingByCol.getOrDefault(c.name, Set.of());
            for (Map<String, Object> row : rows) {
                Object v = row.get(c.name);
                if (v == null || String.valueOf(v).isBlank()) continue;
                String sv = String.valueOf(v);
                if (existing.contains(sv)) continue;   // 该列历史已有此值，未改动 → 放行，不重复校验
                byMaster.computeIfAbsent(c.dropdown.master, k -> new LinkedHashSet<>()).add(sv);
            }
        }
        for (Map.Entry<String, Set<String>> e : byMaster.entrySet()) {
            String[] m = MASTER.get(e.getKey());
            Set<String> vals = e.getValue();
            if (vals.isEmpty()) continue;
            String statusFilter = "element".equals(e.getKey()) ? " AND status = 'ACTIVE'" : "";
            Query q = em.createNativeQuery("SELECT " + m[1] + " FROM " + m[0] + " WHERE " + m[1] + " IN (:vals)" + statusFilter);
            q.setParameter("vals", vals);
            Set<String> exist = new HashSet<>();
            for (Object o : q.getResultList()) exist.add(String.valueOf(o));
            List<String> missing = new ArrayList<>();
            for (String v : vals) if (!exist.contains(v)) missing.add(v);
            if (!missing.isEmpty())
                throw new BusinessException(400,
                    MASTER_ZH.getOrDefault(e.getKey(), e.getKey()) + "不存在于主表: " + String.join(", ", missing));
        }
    }

    /**
     * ENUM/BOOLEAN 列合法性校验（api.md §6 护栏，严格口径）：
     * <ul>
     *   <li>{@code kind=ENUM} 列：非空值若相对该组当前持久化版本是"新增/改动"值，则必须在该列 options
     *       （含 CHECK 约束枚举，如 production_type∈UNIT/BATCH/BATCH_FIXED、currency/unit、calc_type∈材料/元素、
     *       P22 电镀费类型∈电镀加工费/电镀材料费）内，否则 400；未改动的历史值（哪怕不在 options 内，如导入遗留）放行。</li>
     *   <li>{@code BOOLEAN} 列（是否有效 is_effective）：非空值必须是布尔类型，否则 400——类型校验不受历史值影响，逐行全量校验。</li>
     * </ul>
     * 空值放行（列可空）。前端一律用固定 options 下拉、不再允许自定义输入（原"未知可输入回退"措辞已废止）。
     */
    private void validateEnums(PricingSheetDef d, List<Map<String, Object>> rows, Map<String, Set<String>> existingByCol) {
        for (ColumnDef c : d.columns) {
            boolean isEnum = c.dropdown != null && "ENUM".equals(c.dropdown.kind) && c.dropdown.options != null;
            boolean isBool = "BOOLEAN".equals(c.type);
            if (!isEnum && !isBool) continue;
            Set<String> existing = isEnum ? existingByCol.getOrDefault(c.name, Set.of()) : Set.of();
            for (Map<String, Object> row : rows) {
                Object v = row.get(c.name);
                if (v == null || String.valueOf(v).isBlank()) continue;   // 空值放行（列可空）
                if (isEnum) {
                    String sv = String.valueOf(v);
                    if (existing.contains(sv)) continue;   // 该列历史已有此值，未改动 → 放行
                    if (!c.dropdown.options.contains(sv))
                        throw new BusinessException(400, "列[" + c.label + "/" + c.name + "] 值非法: '" + v
                            + "'；合法值 " + c.dropdown.options);
                } else if (!(v instanceof Boolean)) {
                    throw new BusinessException(400, "列[" + c.label + "/" + c.name
                        + "] 应为布尔值 true/false，收到: '" + v + "'");
                }
            }
        }
    }

    // ==================================================================
    // 私有工具
    // ==================================================================
    PricingSheetDef def(String sheetKey) {
        PricingSheetDef d = registry.get(sheetKey);
        if (d == null) throw new BusinessException(404, "sheetKey 不存在: " + sheetKey);
        return d;
    }

    private Object[] materialInfo(String materialNo) {
        Query q = em.createNativeQuery(
            "SELECT material_name, specification, dimension FROM material_master WHERE material_no = :mno");
        q.setParameter("mno", materialNo);
        List<Object[]> r = q.getResultList();
        return r.isEmpty() ? null : r.get(0);
    }

    /**
     * 料号是否可维护（backtask §B4 步骤1）：存在于 material_master，<b>或</b>已有任一核价版本组的当前数据。
     * <p>后一支路是必要的：FEE 组导入（P13–P23 写 unit_price）不 upsert material_master，
     * 这些"有核价数据但无主档"的料号是合法维护对象，不能因缺主档被拒；只有两者皆无的完全虚构料号才判不存在。
     */
    private boolean materialExists(String materialNo) {
        if (materialNo == null || materialNo.isBlank()) return false;
        Number mm = (Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM material_master WHERE material_no = :m")
            .setParameter("m", materialNo).getSingleResult();
        if (mm.longValue() > 0) return true;
        Query q = em.createNativeQuery("SELECT 1 FROM (" + partsCfgUnion() + ") cfg WHERE mno = :m LIMIT 1")
            .setParameter("m", materialNo);
        return !q.getResultList().isEmpty();
    }

    /** NULL 安全 groupKey WHERE：null 值走 IS NULL（不绑参，避免原生查询 null 类型推断失败）。 */
    private void appendGkWhere(StringBuilder where, Map<String, Object> binds,
                               Map<String, Object> gk, String alias) {
        int i = 0;
        for (Map.Entry<String, Object> e : gk.entrySet()) {
            if (i++ > 0) where.append(" AND ");
            if (e.getValue() == null) {
                where.append(alias).append(".").append(e.getKey()).append(" IS NULL");
            } else {
                String p = "g_" + e.getKey();
                where.append(alias).append(".").append(e.getKey()).append(" IS NOT DISTINCT FROM :").append(p);
                binds.put(p, e.getValue());
            }
        }
    }

    private String buildOrderBy(PricingSheetDef d) {
        List<String> cols = new ArrayList<>();
        for (String c : d.extraAnchorColumns) cols.add("t." + c);
        for (String c : d.rowKeyColumns) cols.add("t." + c);
        return cols.isEmpty() ? "" : " ORDER BY " + String.join(", ", cols);
    }

    static String str(Object o) { return o == null ? null : o.toString(); }

    static OffsetDateTime toOdt(Object o) {
        if (o == null) return null;
        if (o instanceof OffsetDateTime odt) return odt;
        if (o instanceof Instant ins) return ins.atOffset(ZoneOffset.UTC);
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        if (o instanceof java.util.Date dt) return dt.toInstant().atOffset(ZoneOffset.UTC);
        return null;
    }

    static BigDecimal asDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        String s = o.toString().trim();
        return s.isEmpty() ? null : new BigDecimal(s);
    }

    /** DECIMAL 列序列化为定标字符串：按 DB 列 scale setScale + toPlainString（如 "1.230000"）；禁 double、禁科学计数（3E-6）。 */
    static String scaledString(Object v, int scale) {
        BigDecimal bd = DecimalScale.at(asDecimal(v), scale);
        return bd == null ? null : bd.toPlainString();
    }
}
