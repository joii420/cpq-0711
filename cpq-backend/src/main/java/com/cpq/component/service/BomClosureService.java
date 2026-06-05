package com.cpq.component.service;

import com.cpq.component.dto.BomClosureResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 核价 BOM 递归展开（P1）—— 料号闭包 + spine 骨架 + 成环清单。
 *
 * <p>给定根料号，由 {@code material_bom_item} 用 PG16 {@code WITH RECURSIVE ... CYCLE} 算出整棵 BOM 树：
 * <ul>
 *   <li>{@code partSet}：根 + 全部子孙料号（去环、去重），喂 {@code :hfPartNos} 外包过滤；</li>
 *   <li>{@code spine}：每行一个节点 occurrence（DAG 重复子件 → 多 occurrence），含 node_id/parent_id/lvl/版本；</li>
 *   <li>{@code cyclePartNos}：成环料号（CYCLE 标记），前端告警「已截断」。</li>
 * </ul>
 *
 * <p>闭包口径（已锁，design §1）：{@code customer_no='_GLOBAL_'}、{@code system_type='PRICING'}、
 * {@code is_current=true}、<b>不约束</b> {@code characteristic}。
 *
 * <p>{@code nodeId} = 边 id 路径（{@code material_bom_item.id} 用 {@code /} 拼接），per-occurrence 唯一；
 * 根 occurrence 的 {@code nodeId}="" 、{@code parentId}=null；根的直接子节点 {@code parentId}=""。
 * 前端建树必须用 {@code parentId → nodeId}（不是料号），否则 DAG 塌成非树。
 *
 * <p>进程级 Caffeine 缓存（TTL 30s，对齐 {@link ComponentDriverService} expandCache），
 * 基础数据导入后调 {@link #evictAll()}。
 *
 * <p>P2 预留：{@code compute(rootPartNo, versionOverrides)} 的 overrides 入参 P1 恒空走纯 CTE。
 *
 * @see com.cpq.component.dto.BomClosureResult
 */
@ApplicationScoped
public class BomClosureService {

    private static final Logger LOG = Logger.getLogger(BomClosureService.class);

    /**
     * 闭包递归 SQL（P1：全程 is_current 默认版本）。
     * 参数 1 = 根料号（material_no = VARCHAR，与文本绑定相容）。
     */
    private static final String CLOSURE_SQL =
        "WITH RECURSIVE bom_tree AS (" +
        "    SELECT 1 AS lvl," +
        "           CAST(? AS varchar)  AS node_no," +
        "           CAST(NULL AS varchar) AS parent_no," +
        "           ARRAY[]::uuid[]     AS edge_path," +
        "           CAST(NULL AS varchar) AS edge_version" +   // 根无入边
        "    UNION ALL" +
        "    SELECT parent.lvl + 1," +
        "           child.component_no," +
        "           child.material_no," +
        "           parent.edge_path || child.id," +
        "           child.bom_version" +                        // 边版本 = 父 BOM 版本(该子件被带入时的版本)
        "    FROM material_bom_item child" +
        "    JOIN bom_tree parent ON child.material_no = parent.node_no" +
        "    WHERE child.customer_no = '_GLOBAL_'" +
        "      AND child.system_type = 'PRICING'" +
        "      AND child.is_current  = true" +
        "      AND child.component_no IS NOT NULL" +
        ") CYCLE node_no SET is_cycle USING cyc_path " +
        "SELECT t.lvl," +
        "       t.node_no   AS hf_part_no," +
        "       t.parent_no AS parent_no," +
        "       array_to_string(t.edge_path, '/') AS node_id," +
        "       array_length(t.edge_path, 1)      AS depth," +
        // 版本语义(用户口径)：非根节点显示「被父件带入时的边版本」(child.bom_version)；
        // 根节点无入边 → 回退自身当前 BOM 版本(LATERAL)。
        "       COALESCE(t.edge_version, v.bom_version) AS bom_version," +
        "       t.is_cycle  AS is_cycle " +
        "FROM bom_tree t " +
        "LEFT JOIN LATERAL (" +
        "    SELECT bom_version FROM material_bom_item" +
        "    WHERE material_no = t.node_no" +
        "      AND customer_no = '_GLOBAL_' AND system_type = 'PRICING' AND is_current = true" +
        "    LIMIT 1" +
        ") v ON true " +
        "ORDER BY t.cyc_path";

    private final Cache<String, BomClosureResult> closureCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(2000)
            .build();

    @Inject
    DataSource dataSource;

    /** 基础数据导入后清空（与 {@link ComponentDriverService#evictAll()} 同时机调用）。 */
    public void evictAll() {
        long before = closureCache.estimatedSize();
        closureCache.invalidateAll();
        LOG.infof("[bom-closure cache] evictAll, entries before=%d", before);
    }

    /**
     * 算根料号的 BOM 闭包（P1）。
     *
     * @param rootPartNo       根料号（= 核价卡片料号）
     * @param versionOverrides P2 预留：{料号→bom_version} 覆盖表；<b>P1 恒按空处理</b>（非空仅记 warn，仍走默认版本）
     * @return 闭包结果；rootPartNo 空 → 返回仅含该（空/单根）的安全空结果
     */
    public BomClosureResult compute(String rootPartNo, Map<String, String> versionOverrides) {
        if (rootPartNo == null || rootPartNo.isBlank()) {
            LOG.warn("[bom-closure] blank rootPartNo, return empty closure");
            return new BomClosureResult();
        }
        if (versionOverrides != null && !versionOverrides.isEmpty()) {
            // P1 不支持版本覆盖（P2 走 Java 逐层迭代展开）；忽略以保证默认最新行为。
            LOG.warnf("[bom-closure] versionOverrides ignored in P1 (size=%d, root=%s)",
                    versionOverrides.size(), rootPartNo);
        }
        String cacheKey = rootPartNo + "|"; // 末段预留 overrides 指纹位（P2）
        BomClosureResult cached = closureCache.getIfPresent(cacheKey);
        if (cached != null) {
            LOG.debugf("[bom-closure cache] HIT root=%s", rootPartNo);
            return cached;
        }
        BomClosureResult result = query(rootPartNo);
        closureCache.put(cacheKey, result);
        LOG.infof("[bom-closure] root=%s -> partSet=%d spine=%d cycles=%d",
                rootPartNo, result.partSet.size(), result.spine.size(), result.cyclePartNos.size());
        return result;
    }

    private BomClosureResult query(String rootPartNo) {
        BomClosureResult result = new BomClosureResult();
        Set<String> partSet = new LinkedHashSet<>();
        Set<String> cycles = new LinkedHashSet<>();
        List<BomClosureResult.SpineNode> spine = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(CLOSURE_SQL)) {
            ps.setString(1, rootPartNo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int lvl = rs.getInt("lvl");
                    String hfPartNo = rs.getString("hf_part_no");
                    String parentNo = rs.getString("parent_no");
                    String nodeId = rs.getString("node_id");      // 根 → "" (空数组)
                    int depthRaw = rs.getInt("depth");
                    boolean depthNull = rs.wasNull();             // 根 array_length(空,1)=NULL
                    String bomVersion = rs.getString("bom_version");
                    boolean isCycle = rs.getBoolean("is_cycle");

                    if (nodeId == null) nodeId = "";
                    String parentId = computeParentId(nodeId, depthNull ? 0 : depthRaw);

                    spine.add(new BomClosureResult.SpineNode(
                            nodeId, parentId, lvl, hfPartNo, parentNo, bomVersion, isCycle));

                    if (isCycle) {
                        if (hfPartNo != null) cycles.add(hfPartNo);
                    } else {
                        if (hfPartNo != null) partSet.add(hfPartNo);
                    }
                }
            }
        } catch (Exception e) {
            // 闭包失败不阻断渲染：返回仅含根的兜底（partSet=[root]），调用方记录降级。
            LOG.errorf(e, "[bom-closure] query failed root=%s, fallback to single-root", rootPartNo);
            BomClosureResult fb = new BomClosureResult();
            fb.partSet.add(rootPartNo);
            fb.spine.add(new BomClosureResult.SpineNode("", null, 1, rootPartNo, null, null, false));
            return fb;
        }

        result.partSet = new ArrayList<>(partSet);
        result.cyclePartNos = new ArrayList<>(cycles);
        result.spine = spine;
        return result;
    }

    /**
     * 由 nodeId（边路径）+ depth 推父身份。
     * <ul>
     *   <li>根（depth=0，edge_path 空）→ parentId = null（无父）；</li>
     *   <li>根的直接子（depth=1）→ parentId = ""（= 根 nodeId）；</li>
     *   <li>更深（depth≥2）→ parentId = nodeId 去掉最后一段。</li>
     * </ul>
     */
    private String computeParentId(String nodeId, int depth) {
        if (depth <= 0) return null;        // 根 occurrence
        if (depth == 1) return "";          // 父 = 根
        int cut = nodeId.lastIndexOf('/');
        return cut < 0 ? "" : nodeId.substring(0, cut);
    }
}
