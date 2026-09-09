package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.registry.ColumnDef;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * D-43 · <b>跨产品卡片归一</b>：同一张单里 N 个产品行共用一个销售料号时，把「同一条主表行被投影
 * N 份」收敛成一份（task-260907 第二段 · 2026-09-08 主线亲验抓到的 P0，用户裁决修法甲）。
 *
 * <h3>缺陷（实证，逐行钉死）</h3>
 * {@code _record} 的组粒度是 {@code (quotation_id, 轴值)} —— 这是<b>对的</b>，见
 * {@link DsQuoteRecordService} 类注释：同一张单里两张卡片用同一个销售料号时，它们表征的是
 * <b>同一个主数据组</b>，所以必须一起重算，否则第二张卡片的投影会在第一张保存时被整组删掉。
 * <p>但 {@code DsQuoteRecordService} 组装 {@code byAxis} 时是<b>逐张卡片直接拼接</b>的，
 * 没有按主表行身份归一 ⇒ 同一条主表行被投影 N 份
 * ⇒ {@link DsRecordProjector#anchor} 的 {@code usedBase}（{@code IdentityHashMap} 集合）
 * 保证每条基底行只被认领一次 ⇒ <b>第一份拿到 {@code origin_id}，其余 N-1 份必然 {@code NULL}</b>
 * ⇒ 回填侧把 {@code origin_id=NULL} 的当新增行追加 ⇒ <b>整组 ×N</b>。
 *
 * <pre>
 *   实测（导入建单，udv=0，用户一个字没改）：
 *     合计 base=43 → result=71               净增 28 行，26 个组里 14 个变大
 *     物料与元素BOM FG01: base=3 → result=6   patched=3 untouched=0 unanchored=3
 *     来料其他费用   FG01: base=2 → result=4   （FG01 的 12 个组全部翻倍）
 *     物料与元素BOM FG02: base=2 → result=2   UNCHANGED   ← 分辨点：FG02 只有 1 个产品行
 *   明细行 FG01 × 2 个产品行 / FG02 × 1 个；主表 FG01=3 行 → _record FG01=6 行(=3×2)
 * </pre>
 *
 * <h3>⚠️ 与 C′（{@code GRAIN_KEY_COLLISION}）不是同一个洞</h3>
 * C′ 的判据是「<b>基底</b>里某粒度键出现多于一次」。此处基底每个粒度键都只有一行、歧义在
 * {@code _record} 侧 ⇒ {@code blockedGroups=0}，C′ 拦不住它。🚫 别把本缺陷当成 C′ 的回归。
 *
 * <h3>🔑 挂点必须在 {@link DsRecordProjector#anchor} <b>之前</b></h3>
 * {@code anchor} 的 {@code usedBase} 正是把重复投影变成 {@code origin_id=NULL} 的地方；
 * 在它之后去重就<b>分不清「重复投影」和「真·用户新增行」</b>了，而后者必须保留（D-36）。
 *
 * <h3>🚨 绝对不许收敛同一张卡片内部的行</h3>
 * 那是 {@code AP-60}「4 行被对齐成 1 行」的原始形态，比本缺陷严重得多（静默删数据）。
 * 同一张卡片里合法地存在两行粒度键相同的行（{@code AC-20④} / {@code AC-21} 的场景，
 * {@code ds_quote_element_bom} 的粒度键实测有 14 组重复）—— 这两行<b>必须都留下</b>。
 * <p>⇒ 身份键里带<b>「本卡片内该粒度键的第几次出现」</b>这一维（{@link #occurrenceKey}）：
 * 同卡片内两行拿到 {@code #0} / {@code #1} ⇒ 身份不同 ⇒ 结构上<b>不可能</b>互相收敛。
 * 身份冲突只可能发生在<b>不同 {@code lineItemId}</b> 之间，即「跨卡片」。
 *
 * <h3>仲裁（用户 2026-09-08 裁决）</h3>
 * 冲突时保留 {@code quotation_line_item.sort_order} <b>最小</b>的那张卡片贡献的行；
 * 相等时按 {@code lineItemId} 的字典序做 tie-break ⇒ <b>同样输入每次得到同样结果</b>，
 * 🚫 不依赖任何 map 迭代序 / 行到达序。
 * <p>⚠️ 仲裁对象是「两张<b>卡片</b>对同一行给了不同值」，所以用的是
 * {@code quotation_line_item.sort_order}（卡片之间的先后），
 * 🚫 <b>不是</b> {@code quotation_line_component_data.sort_order}（组件在卡片内的次序）。
 *
 * <h3>🚫 N+1</h3>
 * <b>纯内存</b>。没有 repository 调用、没有 {@code em.createNativeQuery}、没有懒加载 getter。
 * 卡片排序由调用方在既有的「明细行」那条 SQL 里一并取出，🚫 不新增任何一条 SQL。
 */
final class DsRecordCardDeduper {

    private static final Logger LOG = Logger.getLogger(DsRecordCardDeduper.class);

    /** 键分段符：与 {@code RowFingerprints.SEPARATOR} 同族的不可见字符，不会出现在业务值里。 */
    private static final char SEP = '\u001E';

    private DsRecordCardDeduper() {}

    /**
     * 就地归一 {@code rows}（一个 {@code (table, axisValue)} 组的全部投影行）。
     *
     * <p>🔑 <b>不变量</b>：返回后，对任一 {@code (table, axis)}，代表同一条主表行的记录至多一条。
     * <p>🔑 <b>N=1 时逐位不变</b>：只有一张卡片贡献行 ⇒ 直接原样返回（快路径），
     * 连日志都不打 —— 绝大多数单据走的是这条路。
     *
     * @param rows          该组的投影行，<b>会被就地修改</b>（顺序保持：留下来的行相对次序不变）
     * @param lineSortOrder {@code lineItemId → quotation_line_item.sort_order}（{@code null} 值 =
     *                      库里是 NULL，按最大值处理 ⇒ 有显式排序的卡片优先）
     * @param table         目标表名（日志用）
     * @param axisValue     轴值（日志用）
     * @param colDefs       该 sheet 的列定义（走与行指纹同一套 {@code ValueNormalizer} 归一）
     * @param matchColumns  页签表征的全部物理列 —— 粒度列不可用时的<b>降级</b>身份键
     * @param grainColumns  该 sheet 的有效粒度列 —— 首选身份键（独立于用户改的值）
     * @return 被丢弃的重复投影行数（0 = 本组没有跨卡片重复）
     */
    static int dedupe(List<DsRecordRow> rows, Map<UUID, Integer> lineSortOrder,
                      String table, String axisValue, Map<String, ColumnDef> colDefs,
                      Collection<String> matchColumns, Collection<String> grainColumns) {
        if (rows == null || rows.size() < 2) return 0;

        // ── 快路径：只有一张卡片贡献行 ⇒ 不可能有跨卡片重复。N=1 逐位不变的保证就在这里。────
        Set<UUID> cards = new LinkedHashSet<>();
        for (DsRecordRow r : rows) cards.add(r.lineItemId);
        if (cards.size() < 2) return 0;

        // ── 身份键的列集：粒度列优先，为空则降级到页签表征的全部列 ──────────────────────────
        // ⚠️ 降级<b>必须留痕</b>：DsSheetBindingResolver 已实证「有效粒度列为空」会让锚定第③层
        //    静默失效；这里若也静默降级，同一个配置问题就会在两处各产生一个查不出原因的症状。
        Collection<String> keyColumns = grainColumns;
        boolean degraded = keyColumns == null || keyColumns.isEmpty();
        if (degraded) {
            keyColumns = matchColumns;
            if (keyColumns == null || keyColumns.isEmpty()) {
                // 连表征列都没有 ⇒ 算不出任何行身份。此时<b>宁可不去重</b>（多一行是可见的、
                // 财务能在 unanchoredRows 里看到；收敛错了是静默删数据 = AP-60）。
                LOG.warnf("[ds-record][dedupe] %s 轴值=%s：该组有 %d 张卡片共 %d 行投影，"
                                + "但**粒度列与表征列都为空** ⇒ 算不出行身份，本组跳过跨卡片归一。"
                                + "后果：同一条主表行仍会被投影多份 ⇒ 回填时该组可能膨胀。"
                                + "排查方向：semantic_node.grain_columns 与组件取数配置里勾选的列。",
                        table, axisValue, cards.size(), rows.size());
                return 0;
            }
            LOG.warnf("[ds-record][dedupe] %s 轴值=%s：**有效粒度列为空** ⇒ 跨卡片归一降级为按"
                            + "「页签表征的全部列」(%s) 做身份键。降级后对「两张卡片对同一行填了不同值」"
                            + "的情形归一不到（内容不同 ⇒ 身份不同），该组仍可能膨胀。"
                            + "排查方向：semantic_node.grain_columns 与组件取数配置里勾选的列。",
                    table, axisValue, matchColumns);
        }

        // ── 第 1 趟：算每行的身份键（纯内存）────────────────────────────────────────────
        List<String> identities = new ArrayList<>(rows.size());
        Map<String, Integer> occurrence = new LinkedHashMap<>();
        Map<String, DsRecordRow> winner = new LinkedHashMap<>();
        for (DsRecordRow r : rows) {
            // 身份值取「拍快照那一刻的 driver 原值」，与 anchor() 同口径；没有 driver 侧的行退回
            // 当前值。粒度列本来就不是用户可编辑的字段，两者取值一致。
            Map<String, Object> keyValues = r.anchorValues.isEmpty() ? r.columnValues : r.anchorValues;
            String grainKey = DsRecordProjector.contentKey(keyValues, colDefs, keyColumns);
            int n = occurrence.merge(occurrenceKey(r, grainKey), 1, Integer::sum) - 1;
            String identity = r.componentId + String.valueOf(SEP) + grainKey + SEP + '#' + n;
            identities.add(identity);
            DsRecordRow cur = winner.get(identity);
            if (cur == null || wins(r, cur, lineSortOrder)) winner.put(identity, r);
        }

        // ── 第 2 趟：保留每个身份的赢家，顺序不变 ─────────────────────────────────────
        List<DsRecordRow> keep = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            if (winner.get(identities.get(i)) == rows.get(i)) keep.add(rows.get(i));
        }
        int dropped = rows.size() - keep.size();
        if (dropped == 0) return 0;
        rows.clear();
        rows.addAll(keep);
        LOG.infof("[ds-record][dedupe] %s 轴值=%s：%d 张卡片共 %d 行投影 → 归一为 %d 行"
                        + "（丢弃 %d 行重复投影；身份键=%s%s）。"
                        + "🔑 同一条主表行只留 sort_order 最小的那张卡片贡献的那一份，"
                        + "否则 anchor 只会认领一份、其余 origin_id=NULL ⇒ 回填按新增追加 ⇒ 整组 ×N。",
                table, axisValue, cards.size(), keep.size() + dropped, keep.size(), dropped,
                keyColumns, degraded ? "（降级：粒度列为空）" : "");
        return dropped;
    }

    /**
     * 「本卡片内该粒度键的第几次出现」的分组键 —— <b>含 {@code lineItemId}</b>。
     *
     * <p>🚨 这是「🚫 不许收敛同一张卡片内部的行」的<b>结构性</b>保证：出现序按卡片各算各的，
     * 于是同卡片内两行同粒度键必得 {@code #0} / {@code #1} ⇒ 身份不同 ⇒ 永远不会互相收敛。
     */
    private static String occurrenceKey(DsRecordRow r, String grainKey) {
        return r.lineItemId + String.valueOf(SEP) + r.componentId + SEP + grainKey;
    }

    /**
     * 仲裁：{@code candidate} 是否该取代 {@code current}。
     * <p>① {@code quotation_line_item.sort_order} 小者胜（NULL 视作最大 ⇒ 有显式排序的卡片优先）；
     * ② 相等则 {@code lineItemId} 字典序小者胜 —— 只为<b>确定性</b>，🚫 不带任何业务含义。
     */
    private static boolean wins(DsRecordRow candidate, DsRecordRow current, Map<UUID, Integer> lineSortOrder) {
        int a = sortOrderOf(candidate, lineSortOrder);
        int b = sortOrderOf(current, lineSortOrder);
        if (a != b) return a < b;
        if (candidate.lineItemId == null) return false;
        if (current.lineItemId == null) return true;
        return candidate.lineItemId.compareTo(current.lineItemId) < 0;
    }

    private static int sortOrderOf(DsRecordRow r, Map<UUID, Integer> lineSortOrder) {
        Integer v = (r.lineItemId == null || lineSortOrder == null) ? null : lineSortOrder.get(r.lineItemId);
        return v == null ? Integer.MAX_VALUE : v;
    }
}
