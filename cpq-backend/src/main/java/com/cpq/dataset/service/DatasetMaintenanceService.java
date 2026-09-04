package com.cpq.dataset.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.dataset.dto.*;
import com.cpq.dataset.exception.DatasetValidationException;
import com.cpq.dataset.exception.DatasetVersionConflictException;
import com.cpq.dataset.fingerprint.ValueNormalizer;
import com.cpq.dataset.importer.MasterDataChecker;
import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.DatasetRegistries;
import com.cpq.dataset.registry.DatasetRegistry;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.support.DatasetGroupLock;
import com.cpq.dataset.support.DatasetValues;
import com.cpq.dataset.support.DsMasterTables;
import com.cpq.dataset.support.SqlIdent;
import com.cpq.dataset.versioning.VersionedGroupWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;

import java.util.*;

/**
 * 三套数据集的<b>维护端</b>读写服务（task-260902 · B-9 / B-10）。
 *
 * <p>服务的 AC：AC-25 / AC-26 / AC-27 / AC-28 / AC-29 / AC-30 / AC-32 / AC-41。
 * 契约逐字见 {@code api.md §2~§8}，语义见 {@code 需求文档.md} R-4（升版判定）与 R-8（维护端）。
 *
 * <h2>三条贯穿全类的硬纪律</h2>
 * <ol>
 *   <li>🚫 <b>零 N+1</b>（{@code backend.md} 硬指标）：单个业务操作的 SQL 条数必须是<b>常数</b>，
 *       与料号数 / 行数 / sheet 数<b>无关</b>。跨 sheet 聚合一律拼一条 {@code UNION ALL}
 *       一次取回、内存装配；{@code role=NAME} 名称列一律 <b>LEFT JOIN 批量带出</b>；
 *       主数据存在性走「收集 → 批量 IN → 回头比对」两趟法。🚫 循环体内绝不查库。</li>
 *   <li>🚫 <b>升版逻辑一律交给 {@link VersionedGroupWriter}</b>（B-5）。本类不自己算指纹 / 归档 /
 *       定版本号 —— 与导入共用同一条写入路径，两套实现必然漂移
 *       （{@code PricingSheetRegistry} 类注释自陈的「双写漂移」就是前车之鉴）。</li>
 *   <li>🚨 <b>闸门 A0 · D-13</b>：本类只新建、不改现有代码。既有 {@code PricingMaintenanceService}
 *       有形似方法，<b>刻意不去抽公共件</b> —— 抽取会改到现有类，推翻 D-13 并让 AC-42
 *       「现有页签零回归」失去零改动证据。</li>
 * </ol>
 */
@ApplicationScoped
public class DatasetMaintenanceService {

    @Inject EntityManager em;

    /** 三套 Registry 的分流入口（B-3，后端 #1）。列元数据 / NAME 取数来源 / 长度上限都从这里读。 */
    @Inject DatasetRegistries registries;

    /** 通用版本化写入器（B-5，后端 #2）。🚫 保存端点必须复用它。 */
    @Inject VersionedGroupWriter writer;

    /** 主数据存在性校验（B-6，后端 #2）。保存端与导入端共用同一份判定，防口径漂移。 */
    @Inject MasterDataChecker masterChecker;

    // ==================================================================
    // 定位：{dataset} / {sheetKey}
    // ==================================================================

    /** {@code {dataset}} 三取一，非法值 404（api.md §0）。 */
    public DatasetRegistry registry(String dataset) {
        DatasetRegistry r = registries.byKey(dataset);
        if (r == null) throw new BusinessException(404, "数据集不存在: " + dataset);
        return r;
    }

    /**
     * 抽屉 tab 定位。免版本表（物料 / 电镀方案）<b>不进抽屉</b>（R-8 脚注）：
     * 它们没有版本、没有 {@code _history}，走本端点没有意义 → 一律 404。
     */
    private SheetDef versionedSheet(DatasetRegistry reg, String sheetKey) {
        SheetDef s = reg.byKey(sheetKey);
        if (s == null) throw new BusinessException(404, "sheetKey 不存在: " + sheetKey);
        if (!s.versioned) throw new BusinessException(404, "sheet 无版本，不支持版本化维护: " + sheetKey);
        return s;
    }

    // ==================================================================
    // §2 GET sheets —— 带版本 sheet 元数据（AC-26）
    // ==================================================================

    /**
     * AC-26：抽屉左侧 tab 的<b>数量与顺序完全由本端点决定</b>（基础核价 9 / 详细核价 17），
     * 前端不写死 —— 故这里必须过滤掉免版本 sheet 并保持 sortOrder。
     */
    public DsSheetsResponse listSheets(String dataset) {
        DatasetRegistry reg = registry(dataset);
        List<DsSheetMeta> out = new ArrayList<>();
        for (SheetDef s : reg.versionedSheets()) {
            DsSheetMeta m = new DsSheetMeta();
            m.sheetKey = s.sheetKey;
            m.sheetName = s.sheetName;
            m.sortOrder = s.sortOrder;
            m.axisColumn = s.axisColumn;
            m.axisLabel = s.axisLabel;
            m.columns = s.columns;
            out.add(m);
        }
        return new DsSheetsResponse(out);
    }

    // ==================================================================
    // §3 GET parts —— 料号列表（AC-25）
    // ==================================================================

    /** api.md §3 排序白名单。🚫 {@code sortBy} 原串永不进 SQL —— 一律查表，未命中回退默认序。 */
    private static final Set<String> PARTS_SORT_KEYS =
        Set.of("axisValue", "materialName", "specification", "dimension", "configuredCount", "lastUpdatedAt");

    /**
     * AC-25：列表数据源 = 该数据集的<b>物料表</b>（R-8），行数恒等于
     * {@code SELECT count(*) FROM ds_<集>_material}。
     *
     * <p>⚠️ {@code page} 是 <b>0-based</b>（api.md §3 明写，与现有 {@code /pricing-basic-data/parts}
     * 的 1-based <b>不同</b>）—— 前端两套页签共用组件时最容易在这里错一页，故严格按契约，不做兼容。
     *
     * <p>N+1 自检：count 1 条 + page 1 条 = <b>2 条</b>。{@code configuredCount} 由 9/17 段
     * {@code UNION ALL} 的聚合子查询一次算出，条数与料号数、sheet 数都无关。
     */
    public DsPartsPage listParts(String dataset, String keyword, int page, int size,
                                 String sortBy, String sortDir, Boolean configured) {
        DatasetRegistry reg = registry(dataset);
        List<SheetDef> vs = reg.versionedSheets();
        String axis = SqlIdent.of(reg.axisColumn());
        String matTable = SqlIdent.of(reg.materialTable());
        // api.md §3：productionNo 只在「物料表把生产料号建成了轴以外的独立列」时下发（= 报价数据集）。
        // 🚫 不写死 dataset=='quote' —— 按 Registry 元数据判定，将来哪套数据集加了这一列都自动跟上。
        // 核价两套的轴本身就是 production_no，已由 axisValue 表达，再出一遍是冗余（契约要求整个省略）。
        boolean hasProductionNo = hasNonAxisColumn(reg, "production_no");
        // api.md §3 / 需求文档 R-1.6（D-26）：料号类型是「料号的属性」，三套物料表都建了这一列。
        // 🚫 同样不写死 dataset —— 走 Registry 元数据，将来哪套去掉这一列，键自动跟着消失。
        boolean hasMaterialType = hasNonAxisColumn(reg, "material_type");
        // api.md §3 / 需求文档 R-1.7（D-27）：产品分类【只有报价侧】的物料表建了这一列。
        // 核价两套没有 ⇒ categoryCode / categoryName 两个键整个不出现（AC-61 后半段专门验）。
        boolean hasCategory = hasNonAxisColumn(reg, "category_code");
        int pg = Math.max(0, page);
        int sz = Math.min(Math.max(1, size), 200);
        boolean hasKw = keyword != null && !keyword.isBlank();

        String cfgAgg = "(SELECT av, COUNT(DISTINCT sk) AS c, MAX(uat) AS u FROM ("
            + configuredUnion(vs, axis, null) + ") cfg WHERE av IS NOT NULL GROUP BY av)";
        String from = " FROM " + matTable + " m LEFT JOIN " + cfgAgg + " a ON a.av = m." + axis;
        // 🚫 N+1（AC-61 附加判据）：分类名必须在【同一条 SELECT】里 JOIN 带出，不得逐行查
        //    —— 逐行查会让 SQL 条数变成 2 + 料号数，正是 backend.md 的硬指标反面。
        //    product_category.code 有 UNIQUE 约束 ⇒ LEFT JOIN 不会放大行数。
        //    只挂在分页查询上、不挂 count：总数与 JOIN 无关，少一次连接更省。
        String fromPage = from + (hasCategory ? " LEFT JOIN product_category pc ON pc.code = m.category_code" : "");
        String lastUpdated = "GREATEST(a.u, COALESCE(m.updated_at, m.created_at))";

        // 过滤条件收集：count 与 page 是两条独立 SQL，必须共用同一份 where + 同一份绑定，
        // 否则「总数」与「本页数据」会对不上，翻页数字直接错。
        List<String> preds = new ArrayList<>(2);
        if (hasKw) preds.add("m." + axis + " ILIKE :kw OR COALESCE(m.material_name,'') ILIKE :kw");
        // B-15 / AC-25「配置状态」：configuredCount == totalSheetCount 即已配齐。
        // a.c 是 COUNT(DISTINCT sk)，恒 ≤ 带版本 sheet 数，故 >= 等价于 ==（用 >= 更抗未来加表）。
        // 🚫 SQL 侧过滤，不在内存里筛 —— 内存筛会让 total 变成「过滤前的数」，分页整体错位。
        if (configured != null) preds.add(configured ? "COALESCE(a.c, 0) >= :totalSheets" : "COALESCE(a.c, 0) < :totalSheets");
        String where = andWhere(preds);

        Query cq = em.createNativeQuery("SELECT COUNT(*)" + from + where);
        bindPartsFilters(cq, hasKw, keyword, configured, vs.size());
        long total = ((Number) cq.getSingleResult()).longValue();

        Query pq = em.createNativeQuery(
            "SELECT m." + axis + ", m.material_name, m.specification, m.dimension, m.old_material_no,"
                + " m.unit_weight, COALESCE(a.c, 0), " + lastUpdated
                + (hasProductionNo ? ", m.production_no" : "")
                + (hasMaterialType ? ", m.material_type" : "")
                + (hasCategory ? ", m.category_code, pc.name" : "")
                + fromPage + where
                + partsOrderBy(sortBy, sortDir, axis, lastUpdated)
                + " LIMIT :lim OFFSET :off");
        bindPartsFilters(pq, hasKw, keyword, configured, vs.size());
        pq.setParameter("lim", sz);
        pq.setParameter("off", (long) pg * sz);

        @SuppressWarnings("unchecked")
        List<Object[]> raw = pq.getResultList();
        List<DsPartsPage.Item> items = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            DsPartsPage.Item it = new DsPartsPage.Item();
            it.axisValue = str(r[0]);
            it.materialName = str(r[1]);
            it.specification = str(r[2]);
            it.dimension = str(r[3]);
            it.oldMaterialNo = str(r[4]);
            it.unitWeight = plainDecimal(r[5]);
            it.configuredCount = toIntOr0(r[6]);
            it.totalSheetCount = vs.size();
            it.lastUpdatedAt = toOdt(r[7]);
            // 键的有无按数据集决定：物料表建了这一列就恒 put（值可为 null），没建则整个键不出现。
            // ⚠️ 动态列的下标必须按「实际被拼进 SELECT 的顺序」递进，
            //    不能各自写死常量 —— 再加一个动态列时写死的下标会静默错位（取到隔壁列的值）。
            int dyn = 8;
            if (hasProductionNo) it.putProductionNo(str(r[dyn++]));
            if (hasMaterialType) it.putMaterialType(str(r[dyn++]));
            if (hasCategory) {
                it.putCategoryCode(str(r[dyn++]));
                it.putCategoryName(str(r[dyn++]));   // ← 来自 LEFT JOIN，不是第二条查询
            }
            items.add(it);
        }
        return new DsPartsPage(total, items);
    }

    /**
     * 把 predicate 列表拼成 WHERE 子句。
     *
     * <p>🚨 <b>每个 predicate 各自加一层括号再用 AND 连接</b>，这是硬要求不是洁癖：
     * 关键字条件是裸 {@code A OR B}，直接拼成 {@code A OR B AND C} 会因 AND 优先级高于 OR
     * 被解析成 {@code A OR (B AND C)} —— <b>过滤静默失效、结果反而变多</b>，而且不报错。
     * 逐个加括号让规则与 predicate 内容无关，后续再加条件不会重蹈覆辙
     * （既有 {@code PricingMaintenanceService.andWhere} 踩过同一个坑）。
     */
    private static String andWhere(List<String> preds) {
        if (preds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" WHERE ");
        for (int i = 0; i < preds.size(); i++) {
            if (i > 0) sb.append(" AND ");
            sb.append('(').append(preds.get(i)).append(')');
        }
        return sb.toString();
    }

    /** count 与 page 两条 SQL 共用的参数绑定 —— 两处必须逐字一致，否则总数与分页对不上。 */
    private void bindPartsFilters(Query q, boolean hasKw, String keyword, Boolean configured, int totalSheets) {
        if (hasKw) q.setParameter("kw", "%" + keyword.trim() + "%");
        if (configured != null) q.setParameter("totalSheets", totalSheets);
    }

    /**
     * 该数据集的<b>物料表</b>是否把 {@code columnName} 建成了「轴列之外的独立列」。
     *
     * <p>用于 api.md §3 的 {@code productionNo} / {@code materialType}：报价物料表有独立的「生产料号」列；
     * 核价两套的 {@code production_no} 就是轴列本身，不算独立列。{@code material_type} 三套都有。
     * 判定走 Registry 元数据而非硬编码 datasetKey —— 与建表同源，将来加列自动跟上。
     */
    private boolean hasNonAxisColumn(DatasetRegistry reg, String columnName) {
        if (columnName.equals(reg.axisColumn())) return false;
        SheetDef material = null;
        for (SheetDef s : reg.sheets()) {
            if (reg.materialTable().equals(s.tableName)) { material = s; break; }
        }
        return material != null && material.column(columnName) != null;
    }

    private String partsOrderBy(String sortBy, String sortDir, String axis, String lastUpdated) {
        if (sortBy == null || !PARTS_SORT_KEYS.contains(sortBy)) return " ORDER BY m." + axis + " ASC";
        String col = switch (sortBy) {
            case "materialName" -> "m.material_name";
            case "specification" -> "m.specification";
            case "dimension" -> "m.dimension";
            case "configuredCount" -> "COALESCE(a.c, 0)";
            case "lastUpdatedAt" -> lastUpdated;
            default -> "m." + axis;
        };
        String dir = "desc".equalsIgnoreCase(sortDir) ? "DESC" : "ASC";
        // 尾部固定追加轴列做稳定次序键：同值行在翻页之间跳动是分页排序的经典坑。
        return " ORDER BY " + col + " " + dir + " NULLS LAST, m." + axis + " ASC";
    }

    /**
     * 9 / 17 段 {@code UNION ALL}：每张带版本表投影 (轴值 av, sheetKey sk, 更新时刻 uat, 版本 ver, 来源 src)。
     *
     * <p>零 N+1 的要害：把「每个 sheet 查一次」压成<b>一条</b> SQL。表名、列名来自 Registry
     * （编译期常量，且过 {@link SqlIdent} 白名单），轴值一律走绑定参数。
     */
    private String configuredUnion(List<SheetDef> sheets, String axis, String axisParam) {
        List<String> segs = new ArrayList<>(sheets.size());
        String w = axisParam == null ? "" : " WHERE " + axis + " = :" + axisParam;
        for (SheetDef s : sheets) {
            segs.add("SELECT " + axis + " AS av, '" + s.sheetKey + "' AS sk,"
                + " COALESCE(updated_at, created_at) AS uat, version_no AS ver, source AS src"
                + " FROM " + SqlIdent.of(s.tableName) + w);
        }
        return String.join("\n UNION ALL \n", segs);
    }

    // ==================================================================
    // §4 GET overview —— 抽屉徽标（AC-26 / AC-32）
    // ==================================================================

    /**
     * AC-32 的关键：<b>没有数据的 sheet 也必须出现在 sheets 数组里</b>，
     * 取 {@code rowCount=0} + {@code versionNo=null}，🚫 不抛 404、不省略条目 ——
     * 前端据此渲染空态，而不是错误页 / 红色遮罩（{@code AP-31} / {@code AP-38} 族教训）。
     *
     * <p>N+1 自检：物料信息 1 条 + 跨 sheet 聚合 1 条 = <b>2 条</b>，与 sheet 数无关。
     */
    public DsOverview overview(String dataset, String axisValue) {
        DatasetRegistry reg = registry(dataset);
        List<SheetDef> vs = reg.versionedSheets();
        String axis = SqlIdent.of(reg.axisColumn());

        Query mq = em.createNativeQuery(
            "SELECT material_name FROM " + SqlIdent.of(reg.materialTable()) + " WHERE " + axis + " = :av");
        mq.setParameter("av", axisValue);
        List<?> mrows = mq.getResultList();

        Query q = em.createNativeQuery(
            "SELECT sk, COUNT(*) AS n, MAX(ver) AS v, MAX(uat) AS u, MAX(src) AS s FROM ("
                + configuredUnion(vs, axis, "av") + ") x GROUP BY sk");
        q.setParameter("av", axisValue);
        @SuppressWarnings("unchecked")
        List<Object[]> raw = q.getResultList();
        Map<String, Object[]> bySk = new HashMap<>();
        for (Object[] r : raw) bySk.put(str(r[0]), r);

        // 物料表无此轴值、且 9/17 张带版本表也全无数据 → 完全虚构的料号，404（不返空壳）。
        if (mrows.isEmpty() && bySk.isEmpty()) throw new BusinessException(404, "料号不存在: " + axisValue);

        DsOverview dto = new DsOverview();
        dto.axisValue = axisValue;
        dto.materialName = mrows.isEmpty() ? null : str(mrows.get(0));
        dto.sheets = new ArrayList<>(vs.size());
        for (SheetDef s : vs) {
            DsOverview.SheetStatus st = new DsOverview.SheetStatus();
            st.sheetKey = s.sheetKey;
            Object[] r = bySk.get(s.sheetKey);
            if (r != null) {
                st.rowCount = toIntOr0(r[1]);
                st.versionNo = toInt(r[2]);
                st.lastUpdatedAt = toOdt(r[3]);
                st.source = str(r[4]);
            }
            dto.sheets.add(st);
        }
        return dto;
    }

    // ==================================================================
    // §5 GET rows —— 行数据（AC-29 / AC-32）
    // ==================================================================

    /**
     * 读某轴值某 sheet 的整组行。
     *
     * <ul>
     *   <li><b>AC-32</b>：该轴值从未有过数据 → {@code rows=[]} + {@code versionNo=null}，
     *       {@code isLatest=true} / {@code readOnly=false}（可直接「新增行」录入）。🚫 不抛 404。</li>
     *   <li><b>AC-29</b>：{@code version} 指向历史版本 → 读 {@code _history}，
     *       {@code isLatest=false} + {@code readOnly=true}（前端据此禁用保存 / 新增行）。</li>
     *   <li>{@code role=NAME} 名称列<b>不是 DB 字段</b>，由 LEFT JOIN 主数据实时带出。</li>
     * </ul>
     *
     * <p>N+1 自检：当前版本 1 条 + 行数据 1 条 = <b>2 条</b>，与行数、NAME 列数均无关
     * （多个 NAME 列共用同一个 JOIN，见 {@link #buildNameJoins}）。
     */
    public DsRows readRows(String dataset, String axisValue, String sheetKey, Integer version) {
        DatasetRegistry reg = registry(dataset);
        SheetDef sd = versionedSheet(reg, sheetKey);
        int current = writer.currentVersion(sd, axisValue);   // 0 = 从未有过数据

        boolean latest = (version == null) || (current > 0 && version == current);
        DsRows dto = new DsRows();
        dto.isLatest = latest;
        dto.readOnly = !latest;

        if (latest && current == 0) {
            dto.versionNo = null;                 // AC-32：空态，不是错误
            dto.rows = List.of();
            return dto;
        }

        String table = SqlIdent.of(latest ? sd.tableName : sd.historyTable());
        String idCol = latest ? "id" : "origin_id";
        int targetVersion = latest ? current : version;

        List<String> selects = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        List<ColumnDef> valueCols = new ArrayList<>();
        for (ColumnDef c : sd.persistedColumns()) {
            selects.add("t." + SqlIdent.of(c.name));
            aliases.add(c.name);
            valueCols.add(c);
        }
        selects.add("t.row_fingerprint");
        aliases.add("row_fingerprint");
        valueCols.add(null);                       // 指纹按原样字符串输出

        StringBuilder joins = new StringBuilder();
        buildNameJoins(sd, joins, selects, aliases, valueCols);

        int srcIdx = aliases.size();
        selects.add("t.source");

        Query q = em.createNativeQuery(
            "SELECT " + String.join(", ", selects)
                + " FROM " + table + " t" + joins
                + " WHERE t." + SqlIdent.of(reg.axisColumn()) + " = :av AND t.version_no = :ver"
                + orderBy(sd, idCol));
        q.setParameter("av", axisValue);
        q.setParameter("ver", targetVersion);
        @SuppressWarnings("unchecked")
        List<Object[]> raw = q.getResultList();

        List<Map<String, Object>> rows = new ArrayList<>(raw.size());
        String source = null;
        for (Object[] r : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < aliases.size(); i++) {
                ColumnDef c = valueCols.get(i);
                row.put(aliases.get(i), c == null ? str(r[i]) : renderValue(r[i], c));
            }
            rows.add(row);
            if (source == null) source = str(r[srcIdx]);
        }
        dto.rows = rows;
        dto.source = source;
        dto.versionNo = targetVersion;
        return dto;
    }

    /**
     * {@code role=NAME} 列的 LEFT JOIN 装配。
     *
     * <p>🚨 <b>零 N+1 的要害</b>：名称列一律 JOIN 带出，🚫 绝不「先查行、再按编码逐行查主数据」——
     * 那正是循环体里查库的经典形态，行数一多就是 N 条 SQL。
     *
     * <p>按 {@code (主数据表, 主数据编码列, 本表编码列)} <b>去重复用 JOIN</b>：同一个编码列常要带出
     * 多个名称列（材质料号 → 品名 / 规格 / 尺寸 三列，见字段矩阵），不去重就会对同一张表 JOIN 三次。
     *
     * <p>{@code valueColumn == null}（主数据表里没有这一列，如材质料号的「尺寸」）时选
     * {@code CAST(NULL AS text)} —— <b>键仍然要在</b>，只是值为 null；直接跳过会让前端拿不到这一列而错位。
     */
    private void buildNameJoins(SheetDef sd, StringBuilder joins,
                                List<String> selects, List<String> aliases, List<ColumnDef> valueCols) {
        Map<String, String> joinAlias = new LinkedHashMap<>();
        for (ColumnDef c : sd.nameColumns()) {
            ColumnDef.NameSource src = c.source;
            if (src == null || src.valueColumn() == null) {
                selects.add("CAST(NULL AS text)");
                aliases.add(c.name);
                valueCols.add(null);
                continue;
            }
            String key = src.table() + "|" + src.codeColumnInSrc() + "|" + src.codeColumn();
            String ja = joinAlias.get(key);
            if (ja == null) {
                ja = "nm" + joinAlias.size();
                joinAlias.put(key, ja);
                joins.append(" LEFT JOIN ").append(SqlIdent.of(src.table())).append(" ").append(ja)
                     .append(" ON ").append(ja).append(".").append(SqlIdent.of(src.codeColumnInSrc()))
                     .append(" = t.").append(SqlIdent.of(src.codeColumn()));
            }
            selects.add(ja + "." + SqlIdent.of(src.valueColumn()));
            aliases.add(c.name);
            valueCols.add(null);                   // 名称列一律按字符串输出
        }
    }

    /** 行序：优先 {@code item_seq}（业务项次），再按主键兜底，保证刷新 / 切版本时顺序稳定。 */
    private String orderBy(SheetDef sd, String idCol) {
        boolean hasSeq = sd.column("item_seq") != null;
        return hasSeq ? " ORDER BY t.item_seq NULLS LAST, t." + idCol : " ORDER BY t." + idCol;
    }

    /**
     * DB 值 → JSON 值。
     *
     * <p>numeric 一律<b>字符串</b>（api.md §5：保留库中 scale，避免 JS 精度丢失）；
     * {@code integer} 列（Registry 里 type=NUMBER、pgType=integer）回原生数字，前端「项次」才不会变 "10.000"。
     */
    private Object renderValue(Object v, ColumnDef c) {
        if (v == null) return null;
        if (DatasetValues.isBoolean(c)) return v instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(v));
        if (DatasetValues.isInteger(c)) return v instanceof Number n ? n.longValue() : v;
        if (DatasetValues.isNumeric(c)) return plainDecimal(v);
        return str(v);
    }

    // ==================================================================
    // §6 GET versions —— 版本列表（AC-29）
    // ==================================================================

    /**
     * 当前版（主表）+ 全部历史版（{@code _history}）合并倒序。
     *
     * <p>{@code updated_by} / {@code archived_by} 存的是 user 的 UUID 文本（列宽 {@code varchar(64)}，
     * username 最长 100 装不下），故这里 LEFT JOIN {@code "user"} 换成 username 回传
     * （api.md §6 示例 {@code "updatedBy": "admin"}）；查不到用户则原样回显，不丢信息。
     *
     * <p>N+1 自检：<b>1 条</b> SQL（主表 + 历史表 UNION ALL 后一次聚合），与版本数无关。
     */
    public DsVersions versions(String dataset, String axisValue, String sheetKey) {
        DatasetRegistry reg = registry(dataset);
        SheetDef sd = versionedSheet(reg, sheetKey);
        String axis = SqlIdent.of(reg.axisColumn());
        String table = SqlIdent.of(sd.tableName);
        String hist = SqlIdent.of(sd.historyTable());

        String sql =
            "SELECT x.ver, x.latest, x.n, x.arch_at, x.arch_by, x.arch_reason, x.uat, x.ub, x.src, u.username"
                + " FROM ("
                + "  SELECT version_no AS ver, TRUE AS latest, COUNT(*) AS n,"
                + "         NULL::timestamptz AS arch_at, NULL::varchar AS arch_by, NULL::varchar AS arch_reason,"
                + "         MAX(COALESCE(updated_at, created_at)) AS uat,"
                + "         MAX(COALESCE(updated_by, created_by)) AS ub, MAX(source) AS src"
                + "  FROM " + table + " WHERE " + axis + " = :av GROUP BY version_no"
                + "  UNION ALL"
                + "  SELECT version_no, FALSE, COUNT(*),"
                + "         MAX(archived_at), MAX(archived_by), MAX(archive_reason),"
                + "         MAX(COALESCE(updated_at, created_at)),"
                + "         MAX(COALESCE(updated_by, created_by)), MAX(source)"
                + "  FROM " + hist + " WHERE " + axis + " = :av GROUP BY version_no"
                + ") x LEFT JOIN \"user\" u ON u.id::text = x.ub"
                + " ORDER BY x.ver DESC";
        Query q = em.createNativeQuery(sql);
        q.setParameter("av", axisValue);
        @SuppressWarnings("unchecked")
        List<Object[]> raw = q.getResultList();

        List<DsVersions.VersionInfo> out = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            DsVersions.VersionInfo vi = new DsVersions.VersionInfo();
            vi.versionNo = toIntOr0(r[0]);
            vi.isLatest = Boolean.TRUE.equals(r[1]);
            vi.rowCount = toIntOr0(r[2]);
            vi.archivedAt = toOdt(r[3]);
            vi.archivedBy = str(r[4]);
            vi.archiveReason = str(r[5]);
            vi.updatedAt = toOdt(r[6]);
            vi.updatedBy = r[9] != null ? str(r[9]) : str(r[7]);
            vi.source = str(r[8]);
            out.add(vi);
        }
        return new DsVersions(out);
    }

    // ==================================================================
    // §8 GET lookup —— 主数据下拉（只读，D-16 主数据不拆）
    // ==================================================================

    /**
     * 🚨 api.md §8：<b>可复用现有实现的查询逻辑，但必须新开路径</b> ——
     * 现有 {@code /pricing-basic-data/lookup/{masterType}} 一个字节都不改（AC-43）。
     * 本方法是独立实现，比现有端点多支持 {@code recipe} / {@code customer} 两种 masterType。
     */
    public DsLookupResponse lookup(String dataset, String masterType, String keyword, int limit) {
        registry(dataset);                          // 校验 {dataset} 合法（非法 → 404）
        DsMasterTables.Def def = DsMasterTables.get(masterType);
        if (def == null) throw new BusinessException(400, "masterType 非法: " + masterType);

        int lim = Math.min(Math.max(1, limit), 100);
        boolean hasKw = keyword != null && !keyword.isBlank();
        StringBuilder sql = new StringBuilder("SELECT " + SqlIdent.of(def.codeColumn())
            + ", " + SqlIdent.of(def.nameColumn()) + " FROM " + SqlIdent.of(def.table()) + " WHERE 1=1");
        if (def.extraFilter() != null) sql.append(" AND ").append(def.extraFilter());
        if (hasKw) sql.append(" AND (").append(def.codeColumn()).append(" ILIKE :kw OR COALESCE(")
                      .append(def.nameColumn()).append(",'') ILIKE :kw)");
        sql.append(" ORDER BY ").append(def.codeColumn()).append(" LIMIT :lim");

        Query q = em.createNativeQuery(sql.toString());
        if (hasKw) q.setParameter("kw", "%" + keyword.trim() + "%");
        q.setParameter("lim", lim);
        @SuppressWarnings("unchecked")
        List<Object[]> raw = q.getResultList();
        List<DsLookupResponse.Item> items = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            items.add(new DsLookupResponse.Item(str(r[0]), str(r[1])));
        }
        return new DsLookupResponse(items);
    }

    // ==================================================================
    // §8.5 GET plating-schemes —— 电镀方案只读列表（B-14；AC-49 / AC-50 / AC-51）
    // ==================================================================

    /** 电镀方案在三套 Registry 里的 sheetKey（免版本表，基础核价没有这张表）。 */
    private static final String PLATING_SCHEME = "PLATING_SCHEME";

    /**
     * 电镀方案<b>只读</b>列表（api.md §8.5）。
     *
     * <ul>
     *   <li><b>AC-49</b>：{@code columns} 按数据集下发 —— 报价 10 列（多「网址 / 名称 / 抓取规则」）、
     *       详细核价 8 列（多「密度」）。列定义<b>全部投影自 Registry 的 {@code SheetDef}</b>，
     *       🚫 不在这里手写第二份（手写必然与建表漂移）。</li>
     *   <li><b>AC-50</b>：{@code total} 恒等于 {@code SELECT count(*) FROM ds_<集>_plating_scheme}
     *       （同一时刻基准）。</li>
     *   <li><b>AC-51</b>：只读。本类<b>没有</b>任何电镀方案的写方法 —— 免版本表的写入语义是
     *       「按主键覆盖更新」，只能经导入通道。</li>
     * </ul>
     *
     * <p>{@code cost-basic} 没有电镀方案表 → Registry 里查不到该 sheetKey → <b>404</b>（B-14 明确要求）。
     *
     * <p>🚫 <b>分页与 keyword 过滤一律在 SQL 侧做</b>（B-14）：全量查出来再内存过滤，
     * 表一大就是整表扫 + 整表传输，且 {@code total} 会算错。
     *
     * <p>N+1 自检：count 1 条 + page 1 条 = <b>2 条</b>，与行数、列数无关。
     */
    public DsPlatingSchemes listPlatingSchemes(String dataset, String keyword, int page, int size) {
        DatasetRegistry reg = registry(dataset);
        SheetDef sd = reg.byKey(PLATING_SCHEME);
        // 基础核价（cost-basic）没有电镀方案表 —— 不是「空列表」，是这个数据集压根没有这个概念。
        if (sd == null) {
            throw new BusinessException(404, "该数据集没有电镀方案表: " + dataset);
        }
        String table = SqlIdent.of(sd.tableName);
        int pg = Math.max(0, page);
        int sz = Math.min(Math.max(1, size), 200);
        boolean hasKw = keyword != null && !keyword.isBlank();

        // keyword 匹配「方案编号 / 电镀元素名称」（api.md §8.5）。
        String where = hasKw
            ? " WHERE (scheme_no ILIKE :kw OR COALESCE(plating_element,'') ILIKE :kw)"
            : "";

        Query cq = em.createNativeQuery("SELECT COUNT(*) FROM " + table + where);
        if (hasKw) cq.setParameter("kw", "%" + keyword.trim() + "%");
        long total = ((Number) cq.getSingleResult()).longValue();

        List<ColumnDef> cols = sd.persistedColumns();
        List<String> selects = new ArrayList<>(cols.size());
        for (ColumnDef c : cols) selects.add("t." + SqlIdent.of(c.name));

        Query pq = em.createNativeQuery(
            "SELECT " + String.join(", ", selects) + " FROM " + table + " t"
                + (hasKw ? " WHERE (t.scheme_no ILIKE :kw OR COALESCE(t.plating_element,'') ILIKE :kw)" : "")
                // 按主键排序（方案编号 + 版本 + 项次，R-2）：翻页顺序稳定，同值行不会跳动。
                + " ORDER BY t.scheme_no, t.scheme_version, t.item_seq"
                + " LIMIT :lim OFFSET :off");
        if (hasKw) pq.setParameter("kw", "%" + keyword.trim() + "%");
        pq.setParameter("lim", sz);
        pq.setParameter("off", (long) pg * sz);

        @SuppressWarnings("unchecked")
        List<Object[]> raw = pq.getResultList();
        List<Map<String, Object>> items = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < cols.size(); i++) row.put(cols.get(i).name, renderValue(r[i], cols.get(i)));
            items.add(row);
        }

        List<DsPlatingSchemes.Column> meta = new ArrayList<>(cols.size());
        for (ColumnDef c : cols) meta.add(new DsPlatingSchemes.Column(c.name, c.label, c.type));
        return new DsPlatingSchemes(total, meta, items);
    }

    // ==================================================================
    // §7 PUT rows —— 保存整组（B-10；AC-27 / AC-28 / AC-30 / AC-41）
    // ==================================================================

    /**
     * 整组全量保存，走 R-4 升版判定（与导入<b>同一条写入路径</b>，R-8）。
     *
     * <h3>执行顺序（顺序本身是契约的一部分，不要调换）</h3>
     * <ol>
     *   <li><b>校验</b>（B-6 规则子集，{@code row} = 数组下标 + 1）：只读、不持锁，失败即 400 全量回报；</li>
     *   <li><b>取该表的 advisory lock</b>（与写入器同一把，见 {@link DatasetGroupLock}）；</li>
     *   <li><b>读当前版本</b>；</li>
     *   <li><b>比对 baseVersion</b> → 不等即 409（AC-41）；</li>
     *   <li>调 {@link VersionedGroupWriter#writeGroup} 写入。</li>
     * </ol>
     *
     * <p>🚨 <b>3、4 步必须在锁之后、同一事务内</b>。先读后锁（或不加锁）会留下检查-使用竞态窗口：
     * 两个并发保存都读到 v3、都判定「baseVersion 匹配」、都升到 v4 —— 乐观锁静默失效，
     * <b>没有任何报错</b>，只有事后对不上账。这正是 AC-41 要挡的场景。
     */
    @Transactional
    public DsSaveRowsResult saveRows(String dataset, String axisValue, String sheetKey,
                                     DsSaveRowsRequest req, String operator) {
        DatasetRegistry reg = registry(dataset);
        SheetDef sd = versionedSheet(reg, sheetKey);
        List<Map<String, Object>> body = (req == null || req.rows == null) ? List.of() : req.rows;

        // 护栏：整组清空会让主表该轴值当前版本消失（版本号只剩在 _history 里），
        // 下一次保存的乐观锁会把它误判成「从未有过数据」。整组下线 api.md §7 未定义，不在本期范围。
        if (body.isEmpty()) {
            throw new BusinessException(422, "至少保留一行数据；整组清空不在本期范围");
        }

        // 步骤 1：全量校验，一次性收集全部错误（🚫 不 fail-fast）。
        List<DsValidationError> errors = validate(sd, body);
        if (!errors.isEmpty()) {
            throw new DatasetValidationException(
                "保存校验未通过，共 " + errors.size() + " 处问题，本次未写入任何数据", errors);
        }

        // 步骤 2~4：先入临界区，再读当前版本、再比对 —— 顺序不可调换（见方法注释）。
        DatasetGroupLock.acquire(em, sd.tableName);
        int current = writer.currentVersion(sd, axisValue);            // 0 = 从未有过数据
        int base = (req == null || req.baseVersion == null) ? 0 : req.baseVersion;
        if (base != current) {
            throw new DatasetVersionConflictException(
                current == 0 ? null : current, req == null ? null : req.baseVersion);
        }

        // 步骤 5：装配写入行并交给通用写入器（source=MANUAL → archive_reason=MANUAL_UPGRADE）。
        List<Map<String, Object>> rows = new ArrayList<>(body.size());
        for (Map<String, Object> src : body) rows.add(buildRow(reg, sd, axisValue, src));

        VersionedGroupWriter.Result r = writer.writeGroup(sd, axisValue, rows,
            VersionedGroupWriter.SOURCE_MANUAL, VersionedGroupWriter.REASON_MANUAL_UPGRADE, operator);
        return new DsSaveRowsResult(r.result(), r.versionNo(), r.rowCount(),
            resultMessage(r.result(), r.versionNo()));
    }

    /** api.md §7 的三态文案（前端按 {@code result} 分支给 toast，本文案是兜底展示）。 */
    private String resultMessage(String outcome, int versionNo) {
        return switch (outcome) {
            case "UNCHANGED" -> "数据无变化，未升版";
            case "CREATED" -> "已创建 v" + versionNo;
            default -> "已升版至 v" + versionNo;
        };
    }

    /**
     * 装配一行写入值。
     *
     * <p>三条纪律：
     * <ul>
     *   <li><b>轴列由服务端注入</b>（来自 path 的 {@code axisValue}），前端传什么都覆盖 ——
     *       否则前端改一下轴列就能把数据写到别的料号名下；</li>
     *   <li>{@code role=NAME} 列<b>丢弃</b>（不是 DB 字段）；{@code row_fingerprint} 也丢弃（写入器算）；</li>
     *   <li>空值统一落 {@code null}（与 R-3 指纹口径同源，防虚假升版 —— 「空串」与 NULL 若算出不同
     *       指纹，AC-13「重存不升版」就会红）。</li>
     * </ul>
     *
     * <p>{@code source} / {@code created_by} / {@code updated_by} / {@code updated_at} 由写入器统一写，
     * 这里<b>不重复设置</b>（重复设置会与写入器的列清单冲突，多绑一个参数就整条 INSERT 失败）。
     */
    private Map<String, Object> buildRow(DatasetRegistry reg, SheetDef sd, String axisValue,
                                         Map<String, Object> src) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ColumnDef c : sd.persistedColumns()) {
            row.put(c.name, DatasetValues.coerce(c, src.get(c.name)));
        }
        row.put(reg.axisColumn(), axisValue);
        return row;
    }

    // ==================================================================
    // 保存端校验（B-6 规则子集；reason 取值见 DatasetValidationReasons）
    // ==================================================================

    /**
     * 逐行校验：必填 / 类型 / 长度 / 主数据存在性。
     *
     * <p>🚫 <b>收集全部错误后一次返回</b>，不许遇错即停（与导入 AC-10 同源纪律：前端 F-9 用同一个
     * {@code <ValidationErrorTable>} 渲染，不截断、不「仅显示前 N 条」）。
     *
     * <p>🚨 <b>零 N+1</b>：主数据存在性走「第 1 趟全量收集编码 → 每个 masterType <b>一条</b> IN 查询
     * → 第 2 趟纯内存回头标记」的两趟法。SQL 条数 ≤ masterType 种类数（≤4），与行数<b>无关</b>。
     * 🚫 绝不在行循环里查主数据表。
     *
     * <p>存在性校验一律委托 {@link MasterDataChecker}（B-6，导入端同一实现）——
     * 它刻意<b>不校验</b> {@code material}：料号指向本数据集自己的物料表，
     * 而「投入料号」这类列的编码域是多态的，强校验会把合法数据整批拒收。
     */
    private List<DsValidationError> validate(SheetDef sd, List<Map<String, Object>> rows) {
        List<DsValidationError> errors = new ArrayList<>();
        String sheet = sd.sheetName;

        // ---- 第 1 趟：纯内存校验（必填 / 类型 / 长度），顺带收集待查主数据编码 ----
        Map<String, Set<String>> codesByMaster = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            int rowNo = i + 1;                       // api.md §7：保存端 row = 数组下标 + 1
            for (ColumnDef c : sd.persistedColumns()) {
                // 轴列来自 path，恒非空且由服务端注入，不参与逐行校验。
                if ("AXIS".equals(c.role) || c.name.equals(sd.axisColumn)) continue;

                Object v = row.get(c.name);
                if (ValueNormalizer.isBlank(v)) {
                    if (c.required) {
                        errors.add(new DsValidationError(sheet, rowNo, c.label,
                            DatasetValidationReasons.REQUIRED_EMPTY));
                    }
                    continue;                        // 空值不再做类型 / 长度 / 主数据校验
                }
                String s = ValueNormalizer.toRawString(v);

                if (DatasetValues.isInteger(c)) {
                    if (ValueNormalizer.parseInteger(s) == null) {
                        errors.add(new DsValidationError(sheet, rowNo, c.label,
                            DatasetValidationReasons.NOT_AN_INTEGER));
                        continue;
                    }
                } else if (DatasetValues.isNumeric(c)) {
                    if (ValueNormalizer.parseDecimal(s) == null) {
                        errors.add(new DsValidationError(sheet, rowNo, c.label,
                            DatasetValidationReasons.NOT_A_NUMBER));
                        continue;
                    }
                } else if (DatasetValues.maxLength(c) != null && s.length() > DatasetValues.maxLength(c)) {
                    // AC-40：超长必须报错，🚫 禁止静默截断。
                    errors.add(new DsValidationError(sheet, rowNo, c.label,
                        DatasetValidationReasons.tooLong(DatasetValues.maxLength(c))));
                    continue;
                }

                if (needsMasterCheck(c)) {
                    codesByMaster.computeIfAbsent(c.dropdown.masterType, k -> new LinkedHashSet<>()).add(s);
                }
            }
        }

        // ---- 批量查主数据 ----
        // ⚠️ N+1 自检说明：本循环体内<b>确实</b>有查库，但它遍历的是 <b>masterType 种类</b>
        // （{@code MasterDataChecker.CHECKED_TYPES} 恒 ≤ 4：element / process / recipe / customer），
        // 不是行、不是料号。⇒ SQL 条数上界 = 4，与 N 无关，符合 backend.md 的「与 N 无关」硬指标。
        // 🚫 真正的违规写法是把查询挪进上面那两个 `for (rows)` 循环 —— 那才会随行数线性增长。
        Map<String, Set<String>> existing = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : codesByMaster.entrySet()) {
            existing.put(e.getKey(), masterChecker.existing(e.getKey(), e.getValue()));
        }

        // ---- 第 2 趟：纯内存比对，标出「主数据不存在」的具体行与列 ----
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            int rowNo = i + 1;
            for (ColumnDef c : sd.persistedColumns()) {
                if (!needsMasterCheck(c)) continue;
                Object v = row.get(c.name);
                if (ValueNormalizer.isBlank(v)) continue;
                String s = ValueNormalizer.toRawString(v);
                if (!existing.getOrDefault(c.dropdown.masterType, Set.of()).contains(s)) {
                    errors.add(new DsValidationError(sheet, rowNo, c.label,
                        DatasetValidationReasons.MASTER_MISSING));
                }
            }
        }
        errors.sort(Comparator.comparingInt((DsValidationError e) -> e.row));
        return errors;
    }

    /**
     * 是否需要主数据存在性校验。
     *
     * <p>三个条件缺一不可：① Registry 标了 {@code masterCheck}（{@code .master(...)} 而非
     * {@code .masterNoCheck(...)}）；② 有 MASTER 下拉；③ {@link MasterDataChecker} 支持该类型。
     * ⚠️ 漏判 ① 会把「投入料号」这类多态编码列也拿去校验，合法数据被整批拒收。
     */
    private boolean needsMasterCheck(ColumnDef c) {
        return c.masterCheck
            && c.dropdown != null
            && "MASTER".equals(c.dropdown.kind)
            && masterChecker.supports(c.dropdown.masterType);
    }

    // ==================================================================
    // §3.5 PUT parts/{axisValue} —— 免版本物料表的单列更新（B-20 / D-31 / AC-65）
    // ==================================================================

    /**
     * 免版本物料表的<b>部分更新</b>（D-31 / AC-65）—— 免版本表的<b>第二条写入路径</b>。
     *
     * <h3>🚨 为什么不能复用 {@code PlainTableWriter.upsert}</h3>
     * 那是<b>整行 UPSERT</b>：未传的列会被显式绑成 {@code NULL} 写进去。
     * 用它实现「只改一列」的结果是<b>把这一行其余字段全清空</b>，而且不报错。
     * 所以这里发的是真正的部分更新 {@code UPDATE 表 SET <白名单列> WHERE <轴列>}（AC-65 ③）。
     *
     * <h3>四条语义（AC-65 逐条验）</h3>
     * <ol>
     *   <li><b>白名单</b>：只有 {@link ColumnDef#partEditable} 的列可改，其余一律 400 <b>点名该字段</b>
     *       —— 🚫 不靠「前端不显示」兜底（AC-65 ②）；</li>
     *   <li><b>其余列逐字不变</b>（AC-65 ③）；</li>
     *   <li>{@code source} <b>不动</b> —— 行级来源仍是 {@code IMPORT}，不因改一列翻成 {@code MANUAL}（AC-65 ④）。
     *       🚫 别顺手加 {@code source = 'MANUAL'}：那会让「这行数据是导入来的」这个事实丢失；</li>
     *   <li><b>不接升版</b>：免版本表没有 {@code version_no}，也不写 {@code _history}（AC-65 ⑤）。</li>
     * </ol>
     *
     * <h3>与导入的冲突消解（D-29）</h3>
     * 这里改过的 {@code production_no} 不会被下次导入的空格子打回 ——
     * {@code PlainTableWriter} 对 {@code preserveOnNull} 的列走 {@code COALESCE}（AC-62）。
     * ⚠️ <b>每新开放一个可编辑字段，都要单独回答「下次导入会不会把它打回」</b>，不能默认继承。
     *
     * <h3>🚩 {@code null} 与「键缺席」必须区分（跨端契约，api.md §3.5）</h3>
     * <ul>
     *   <li><b>键存在、值为 {@code null}</b> → 该列写 {@code NULL}（<b>清空</b>）；</li>
     *   <li><b>键不存在</b> → 该列<b>不进</b> {@code UPDATE SET}，一个字节不动。</li>
     * </ul>
     * 🚫 <b>严禁用 {@code map.get(k) != null} 判断「这个字段要不要改」</b> ——
     * {@code {"production_no": null}} 与 {@code {}} 在 {@code get()} 下<b>返回同一个 null</b>，
     * 「清空」会被当成「没传」而<b>静默不改</b>，症状是「点了清空、提示保存成功、值还在」。
     * 本方法遍历 {@code entrySet()}，天然按「键在不在」判定 —— 改写时不要退回 {@code get()}。
     *
     * <h3>键名接受两种写法</h3>
     * DB 列名 {@code production_no}（契约正名）与其小驼峰 {@code productionNo}
     *（= {@code GET /parts} item 里的键名）都接受，解析到同一列。
     * 前端从列表拿到的是小驼峰，写回时若被迫做一次蛇形转换，转错了就是<b>静默改不到</b>。
     * 🚫 白名单强度不因此降低：解析后仍按 {@link ColumnDef#partEditable} 判定，不在白名单一律 400 点名。
     *
     * <p>🚫 N+1：SQL 恒为 2 条（UPDATE 1 + 回读 1），与传入字段数无关。
     *
     * @param patch 字段名 → 新值。键可以是 DB 列名或其小驼峰形式；值为 {@code null} 表示清空该列
     */
    @Transactional
    public DsPartPatchResult updatePart(String dataset, String axisValue,
                                        Map<String, Object> patch, String operator) {
        DatasetRegistry reg = registry(dataset);
        SheetDef material = materialSheet(reg);
        String table = SqlIdent.of(material.tableName);
        String axis = SqlIdent.of(reg.axisColumn());

        if (axisValue == null || axisValue.isBlank()) {
            throw new BusinessException(400, "缺少料号");
        }
        if (patch == null || patch.isEmpty()) {
            throw new BusinessException(400, "未提供任何待更新字段");
        }

        // ── 白名单 + 取值校验（一次列全，与导入 Phase 1 同一口径：先 trim，超长报错不截断）
        List<String> setSql = new ArrayList<>();
        Map<String, Object> bind = new LinkedHashMap<>();
        Map<String, Object> echo = new LinkedHashMap<>();
        int i = 0;
        // 🚩 遍历 entrySet ⇒ 判据是「键在不在」，不是「值是不是 null」。
        //    传了 null 的键会走到下面 coerce → null → SET col = NULL（清空）；没传的键压根不进循环。
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            ColumnDef c = resolveEditableColumn(material, e.getKey());
            if (c == null || !c.persisted || !c.partEditable) {
                // 🚩 AC-65 ②：必须点名字段。笼统的「字段不允许编辑」在前端排查时等于没报。
                throw new BusinessException(400,
                    "字段「" + e.getKey() + "」不允许直接编辑（可编辑字段：" + editableNames(material) + "）");
            }
            Integer max = DatasetValues.maxLength(c);
            if (max != null && !ValueNormalizer.isBlank(e.getValue())
                    && ValueNormalizer.toRawString(e.getValue()).length() > max) {
                throw new BusinessException(400,
                    "列「" + c.label + "」" + DatasetValidationReasons.tooLong(max));
            }
            Object v = DatasetValues.coerce(c, e.getValue());   // 🚫 不截断；空 → null（= 清空该列）
            String pn = "v" + (i++);
            setSql.add(c.name + " = :" + pn);
            bind.put(pn, v);
            echo.put(e.getKey(), v);   // 回显用调用方发来的键名，前端回填时不必再做一次转换
        }

        // 🚫 刻意不写 source —— 保持原值（AC-65 ④）。updated_at / updated_by 是审计列，必须更新。
        String sql = "UPDATE " + table + " SET " + String.join(", ", setSql)
                   + ", updated_at = now(), updated_by = :op WHERE " + axis + " = :axis";
        Query q = em.createNativeQuery(sql);
        for (Map.Entry<String, Object> b : bind.entrySet()) q.setParameter(b.getKey(), b.getValue());
        q.setParameter("op", operator);
        q.setParameter("axis", axisValue);
        int affected = q.executeUpdate();
        if (affected == 0) {
            throw new BusinessException(404, "料号不存在: " + axisValue);
        }

        // 回读审计信息（第 2 条也是最后一条 SQL）
        Object[] back = (Object[]) em.createNativeQuery(
                "SELECT updated_at, source FROM " + table + " WHERE " + axis + " = :axis")
            .setParameter("axis", axisValue)
            .getSingleResult();

        DsPartPatchResult out = new DsPartPatchResult();
        out.dataset = reg.datasetKey();
        out.axisValue = axisValue;
        out.updated = echo;
        out.updatedAt = toOdt(back[0]);
        out.source = str(back[1]);
        return out;
    }

    /** 该数据集的物料表 SheetDef（列表 / 单列更新的落点）。 */
    private SheetDef materialSheet(DatasetRegistry reg) {
        for (SheetDef s : reg.sheets()) {
            if (reg.materialTable().equals(s.tableName)) return s;
        }
        throw new BusinessException(500, "数据集 " + reg.datasetKey() + " 未声明物料表");
    }

    /**
     * 把请求里的字段名解析成物料表的列：先按 DB 列名精确匹配，再按<b>小驼峰</b>匹配
     *（{@code productionNo} → {@code production_no}）。
     *
     * <p>为什么要认小驼峰：{@code GET /parts} 的 item 键是小驼峰，前端写回时若必须自己转蛇形，
     * 转错了的表现是<b>字段名不在白名单 → 400</b>（还算好）或者更糟的静默不改。
     * 两种写法都认，代价只是这一个方法。
     * <p>🚫 <b>不是</b>放宽白名单：解析不到列、或解析到的列没打 {@code partEditable}，调用方一样吃 400。
     */
    private static ColumnDef resolveEditableColumn(SheetDef material, String field) {
        if (field == null || field.isBlank()) return null;
        ColumnDef exact = material.column(field);
        if (exact != null) return exact;
        for (ColumnDef c : material.persistedColumns()) {
            if (toCamel(c.name).equals(field)) return c;
        }
        return null;
    }

    /** {@code production_no} → {@code productionNo}。 */
    private static String toCamel(String snake) {
        StringBuilder sb = new StringBuilder(snake.length());
        boolean up = false;
        for (int i = 0; i < snake.length(); i++) {
            char ch = snake.charAt(i);
            if (ch == '_') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return sb.toString();
    }

    /**
     * 白名单里的列名，用于 400 报文提示「那到底能改什么」。
     * <p>两种写法都列出来（{@code production_no / productionNo}），省得调用方拿到 400 之后
     * 还要猜是不是大小写/下划线的问题。
     */
    private static String editableNames(SheetDef material) {
        List<String> names = new ArrayList<>();
        for (ColumnDef c : material.persistedColumns()) {
            if (c.partEditable) names.add(c.name + " / " + toCamel(c.name));
        }
        return names.isEmpty() ? "无" : String.join("，", names);
    }

    // ==================================================================
    // 私有工具（读端点专用；写入侧的类型转换一律走 DatasetValues，不在这里另起一套）
    // ==================================================================

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static java.time.OffsetDateTime toOdt(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.OffsetDateTime odt) return odt;
        if (o instanceof java.time.Instant ins) return ins.atOffset(java.time.ZoneOffset.UTC);
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        if (o instanceof java.util.Date dt) return dt.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return null;
    }

    private static Integer toInt(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static int toIntOr0(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    /**
     * numeric 列 → <b>字符串</b>（api.md §5：保留库中 scale，避免 JS 精度丢失）。
     *
     * <p>JDBC 读回的 {@link java.math.BigDecimal} 已带列的 scale，{@code toPlainString()} 即为
     * {@code "1.000000000000"}。🚫 禁 double、禁科学计数法（{@code 3E-6} 前端会原样渲染出来）。
     */
    private static String plainDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof java.math.BigDecimal bd) return bd.toPlainString();
        java.math.BigDecimal parsed = ValueNormalizer.parseDecimal(String.valueOf(v));
        return parsed == null ? String.valueOf(v) : parsed.toPlainString();
    }
}
