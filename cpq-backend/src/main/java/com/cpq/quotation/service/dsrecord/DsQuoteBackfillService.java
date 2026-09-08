package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.fingerprint.ValueNormalizer;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.support.SqlIdent;
import com.cpq.dataset.versioning.VersionedGroupWriter;
import com.cpq.quotation.dto.backfill.DsBackfillDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 核价通过 → 按 patch 语义把 {@code _record} 回填升版到 {@code ds_quote_*} 主表
 * （task-260907 第二段 · B-10 预览 / B-11 执行 / B-12 取消零副作用 / B-13 免版本表排除）。
 *
 * <h3>服务的 AC</h3>
 * AC-5（预览描述「将写入什么」）· AC-6（确认 → patch 语义升版）· AC-7（取消 → 一个字节不落库）
 * · AC-9（复用 {@code VersionedGroupWriter}）· AC-14（零变更不写不删）· AC-18（并发）· AC-20
 *
 * <h3>🚫 三条不许</h3>
 * <ol>
 *   <li>🚫 <b>不许自己算 {@code row_fingerprint} / 自己写 {@code _history} / 自己定 {@code version_no}</b>
 *       —— 一律走 {@link VersionedGroupWriter}（AC-9；「两套实现必然漂移」是该类类注释的原话）；</li>
 *   <li>🚫 <b>不许在 for 循环里逐轴值调 {@code writeGroup}</b> —— 必须走
 *       {@link VersionedGroupWriter#writeGroups} 批量入口，那正是本项目反复踩过的 N+1 形态；</li>
 *   <li>🚫 <b>不许改 {@code VersionedGroupWriter}</b> —— 它被三套数据集、39 张带版本表共用。</li>
 * </ol>
 *
 * <h3>「来源报价单 id」为什么要在 writer 之后补一条 UPDATE</h3>
 * {@code VersionedGroupWriter.insertAll} 只写 {@code persistedColumns() + INSERT_SYS_COLUMNS}，
 * {@code archive} 只复制 {@code persistedColumns() + ARCHIVE_SYS_COLUMNS}。
 * 「来源报价单 id」走的是 {@code SheetDef} 静态系统列这条路（D-28：绝不能声明成 {@code ColumnDef}，
 * 否则存量报价 Excel 全部导不进去），因此这两处<b>都不会带上它</b>。
 * <p>🚨 漏了这一步的症状极具误导性：<b>列建了、自检过了、导入也不报错，只是值恒为 NULL</b> ——
 * 现场会去查 DDL（「列不是加了吗？」），而真因在写入点的列清单里。
 * <p>本类的做法：writer 返回后按「本次真的写了的轴值」补一条 {@code UPDATE}（1 条 SQL / 表），
 * 并把归档前那一版的值补回 {@code _history}（追溯：这个历史版本由哪张单产生）。
 * 🚫 <b>不改 {@code VersionedGroupWriter}</b>，也就不会波及核价两套（AC-16）。
 */
@ApplicationScoped
public class DsQuoteBackfillService {

    private static final Logger LOG = Logger.getLogger(DsQuoteBackfillService.class);

    /** ⚠️ 受 {@code source varchar(16)} 约束（实测），15 字符。 */
    public static final String SOURCE_QUOTE_BACKFILL = "QUOTE_BACKFILL";
    /** ⚠️ 受 {@code archive_reason varchar(32)} 约束（实测），22 字符。 */
    public static final String REASON_QUOTE_BACKFILL = "QUOTE_BACKFILL_UPGRADE";

    @Inject EntityManager em;
    @Inject DsBackfillCollector collector;
    @Inject DsMainTableReader mainTableReader;
    @Inject VersionedGroupWriter versionedGroupWriter;

    // ==================================================================
    // B-10 预览（只读、无副作用、幂等 —— B-12 取消路径靠的就是这个语义）
    // ==================================================================

    /**
     * 组装 {@code dsBackfill} 段。<b>只读</b>：不写主表、不写 {@code _history}、不写 {@code _record}、
     * 不写 {@code quotation_approval}（AC-7①②③）。
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public DsBackfillDTO preview(UUID quotationId) {
        return previewWithToken(quotationId).dto();
    }

    /**
     * 预览 + 该预览对 {@code previewToken} 的贡献串（D-32）。
     *
     * @param dto      给前端的 {@code dsBackfill} 段
     * @param tokenPart 参与 {@code previewToken} 哈希的规范串（已排序、已数值归一）
     */
    public record PreviewResult(DsBackfillDTO dto, String tokenPart) {}

    /**
     * 一次 {@code collect} 同时产出 DTO 与 token 贡献串 —— 🚫 <b>不要为了拿 token 再 collect 一遍</b>
     * （那是白白多跑一整套 SQL，且两次之间数据可能变，token 与展示内容会对不上）。
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public PreviewResult previewWithToken(UUID quotationId) {
        DsBackfillPlan plan = collector.collect(quotationId);
        return new PreviewResult(toDTO(plan), canonicalize(plan));
    }

    /**
     * D-32 —— 把「本次将写入什么」压成一个确定性字符串，交给
     * {@code QuoteBackfillPreviewService#computeToken} 一起哈希。
     *
     * <h3>为什么必须做</h3>
     * 原 {@code computeToken(plan)} 只遍历<b>老</b> {@code QuoteBackfillPlan.groups}，
     * {@code _record} 完全不在里面 ⇒ 预览之后销售再保存一次、{@code _record} 变了，
     * token 却不变、不报 409。而 {@code _record} <b>恰恰是财务确认的对象</b> ——
     * 「预览后数据又变了」这个保护对新链路等于没有。
     *
     * <h3>口径（与既有 token 算法同款）</h3>
     * 固定排序（表名 → 轴值 → 行规范串）+ 数值归一（走 {@link ValueNormalizer}，与行指纹同一套）
     * + NULL 稳定序列化。
     * <p>🚫 <b>{@code UNCHANGED} 的组不进 token</b> —— 它一行不写，把它算进去只会制造无意义的 409。
     * <p>⚠️ 行规范串<b>排序后</b>再拼：{@code resultRows} 的顺序来自主表读出的 {@code (轴, id)} 序，
     * 升版后 {@code id} 会全换 ⇒ 不排序会让「内容没变但 id 变了」误报 409。
     * <p>📌 {@code nonParticipating} 也纳入：它是财务确认时看到的内容的一部分，
     * 预览后组件配置被改掉（比如某个页签换成手写视图）应当让她重看一遍。
     */
    String canonicalize(DsBackfillPlan plan) {
        if (plan == null) return "";
        if (!plan.applicable) return plan.recordStale == null ? "" : "#stale=" + plan.recordStale.reason();
        StringBuilder sb = new StringBuilder();

        List<DsBackfillPlan.Table> tables = new ArrayList<>(plan.tables);
        tables.sort(java.util.Comparator.comparing(t -> t.sheet.tableName));
        for (DsBackfillPlan.Table t : tables) {
            Map<String, com.cpq.dataset.registry.ColumnDef> defs =
                    DsQuoteRecordService.colDefsOf(t.sheet);
            List<DsBackfillPlan.Group> groups = new ArrayList<>(t.groups);
            groups.sort(java.util.Comparator.comparing(g -> g.axisValue == null ? "" : g.axisValue));
            for (DsBackfillPlan.Group g : groups) {
                if (VersionedGroupWriter.UNCHANGED.equals(g.result)) continue;   // 一行不写 → 不进 token
                sb.append(t.sheet.tableName).append('|').append(g.axisValue).append('|')
                  .append(g.result).append('|').append(g.targetVersionNo).append(';');
                List<String> rows = new ArrayList<>(g.resultRows.size());
                for (Map<String, Object> row : g.resultRows) {                   // 🚫 纯内存，无查询
                    StringBuilder rb = new StringBuilder();
                    for (com.cpq.dataset.registry.ColumnDef c : t.sheet.persistedColumns()) {
                        rb.append(c.name).append('=')
                          .append(ValueNormalizer.normalize(row.get(c.name), c.type, c.scale)).append(',');
                    }
                    rows.add(rb.toString());
                }
                java.util.Collections.sort(rows);
                for (String r : rows) sb.append(r).append(';');
                List<String> un = new ArrayList<>();
                for (DsBackfillPlan.Unanchored u : g.unanchoredRows) {
                    un.add(u.recordId + ":" + u.reason);
                }
                java.util.Collections.sort(un);
                sb.append("~unanchored=").append(String.join(",", un)).append(';');
            }
        }
        List<String> np = new ArrayList<>();
        for (DsSheetBindingResolver.NonParticipating n : plan.nonParticipating) {
            np.add(n.componentId() + ":" + n.reason());
        }
        java.util.Collections.sort(np);
        sb.append("#nonparticipating=").append(String.join(",", np));
        // D-35：过期标记是财务确认时看到的内容的一部分 —— 预览后它出现/消失都应让 token 失效。
        sb.append("#stale=").append(plan.recordStale == null ? "" : plan.recordStale.reason());
        return sb.toString();
    }

    DsBackfillDTO toDTO(DsBackfillPlan plan) {
        DsBackfillDTO dto = new DsBackfillDTO();
        dto.applicable = plan.applicable;
        dto.confirmRequired = true;
        // D-35：过期警告要在**任何 early return 之前**填 —— 它是「不许静默」的那一条。
        if (plan.recordStale != null) {
            DsBackfillDTO.RecordStale rs = new DsBackfillDTO.RecordStale();
            rs.reason = plan.recordStale.reason();
            rs.detail = plan.recordStale.detail();
            rs.detectedAt = plan.recordStale.detectedAt() == null ? null
                    : plan.recordStale.detectedAt().toString();
            dto.recordStale = rs;
            dto.summary.recordStale = true;
        }
        if (!plan.applicable) return dto;

        for (DsBackfillPlan.Table t : plan.tables) {
            DsBackfillDTO.Table td = new DsBackfillDTO.Table();
            td.sheetKey = t.sheet.sheetKey;
            td.sheetName = t.sheet.sheetName;
            td.tableName = t.sheet.tableName;
            for (DsBackfillPlan.Group g : t.groups) {
                // 🚫 UNCHANGED 的组**不过滤**（api.md 硬约束 3 / AC-14③）：
                //    过滤掉之后财务分不清「这张表没变」与「这张表根本没被算进去」。
                DsBackfillDTO.Group gd = new DsBackfillDTO.Group();
                gd.axisValue = g.axisValue;
                gd.customerNo = g.customerNo;
                gd.baseVersionNo = g.baseVersionNo;
                gd.currentVersionNo = g.currentVersionNo;
                gd.targetVersionNo = g.targetVersionNo;
                gd.crossVersion = g.crossVersion;
                gd.result = g.result;
                gd.blockedReason = g.blockedReason;                       // D-37
                for (DsBackfillPlan.Colliding c : g.collidingRows) {
                    DsBackfillDTO.CollidingRow cr = new DsBackfillDTO.CollidingRow();
                    cr.grainKey = c.grainKey;
                    cr.baseRowCount = c.baseRowCount;
                    cr.recordRowCount = c.recordRowCount;
                    gd.collidingRows.add(cr);
                }
                gd.baseRowCount = g.baseRowCount;
                gd.resultRowCount = g.resultRows.size();
                gd.patchedRows = g.patchedRows;
                gd.untouchedRows = g.untouchedRows;          // 🔑 AP-60 守卫，必须出现
                gd.columnScope.patched = new ArrayList<>(g.patchedColumns);
                gd.columnScope.preserved = new ArrayList<>(g.preservedColumns);
                for (DsBackfillPlan.Unanchored u : g.unanchoredRows) {
                    DsBackfillDTO.UnanchoredRow ud = new DsBackfillDTO.UnanchoredRow();
                    ud.recordId = u.recordId;
                    ud.originId = u.originId;
                    ud.baseRowFingerprint = u.baseRowFingerprint;
                    ud.displayValues = u.displayValues;
                    ud.reason = u.reason;
                    gd.unanchoredRows.add(ud);
                }
                td.groups.add(gd);
                dto.summary.axes++;
                if (DsBackfillCollector.BLOCKED.equals(g.result)) dto.summary.blockedGroups++;
                else if (VersionedGroupWriter.UNCHANGED.equals(g.result)) dto.summary.unchangedGroups++;
                else dto.summary.upgradedGroups++;
                dto.summary.unanchoredRows += g.unanchoredRows.size();
            }
            if (!td.groups.isEmpty()) {
                dto.tables.add(td);
                dto.summary.tables++;
            }
        }
        // D-33：不参与的组件必须原样呈给财务，🚫 不许静默
        for (DsSheetBindingResolver.NonParticipating n : plan.nonParticipating) {
            DsBackfillDTO.NonParticipating nd = new DsBackfillDTO.NonParticipating();
            nd.componentId = n.componentId() == null ? null : n.componentId().toString();
            nd.componentName = n.componentName();
            nd.reason = n.reason();
            dto.nonParticipating.add(nd);
        }
        dto.summary.nonParticipatingComponents = dto.nonParticipating.size();

        for (Map.Entry<String, java.util.Set<String>> e : plan.extendColumnOnly.entrySet()) {
            DsBackfillDTO.ExtendOnly eo = new DsBackfillDTO.ExtendOnly();
            eo.sheetKey = e.getKey();
            eo.fields = new ArrayList<>(e.getValue());
            dto.extendColumnOnly.add(eo);
        }
        return dto;
    }

    // ==================================================================
    // B-11 执行（与状态机翻转同一事务：失败整体回滚，报价单保持 SUBMITTED）
    // ==================================================================

    /**
     * 按 patch 语义把本单回填进主表并升版。
     *
     * @param operator 操作人（写 {@code created_by}/{@code updated_by}/{@code archived_by}），可为 null
     * @return 摘要（形状与预览的 {@code dsBackfill.summary} 一致，api.md §2 响应体）
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public DsBackfillDTO.Summary execute(UUID quotationId, String operator) {
        DsBackfillDTO.Summary sum = new DsBackfillDTO.Summary();
        DsBackfillPlan plan = collector.collect(quotationId);
        sum.nonParticipatingComponents = plan.nonParticipating.size();
        sum.recordStale = plan.recordStale != null;
        if (!plan.applicable || plan.tables.isEmpty()) return sum;

        for (DsBackfillPlan.Table t : plan.tables) {
            // 🚫 严禁 for 循环里逐轴值调 writeGroup —— 整张 sheet 一次性交给批量入口。
            Map<String, List<Map<String, Object>>> rowsByAxis = new LinkedHashMap<>();
            for (DsBackfillPlan.Group g : t.groups) {
                // 🚨 C′（D-37）：BLOCKED 组**一个字节不写** —— 连 rowsByAxis 都不放进去，
                //    因为 VersionedGroupWriter 的增量语义是「只碰入参里出现的轴值」，
                //    不放 = 该轴值组不升版、不归档、不删除，是最干净的「跳过」。
                //    ⚠️ 核价通过本身照常进行：C′ 的目的是「别写坏」，不是「别通过」
                //    （主线 2026-09-07 裁决：不拿业务流程给数据层的边界情况陪葬）。
                if (DsBackfillCollector.BLOCKED.equals(g.result)) continue;
                rowsByAxis.put(g.axisValue, g.resultRows);
            }
            if (rowsByAxis.isEmpty()) {                                   // 整张表都被 BLOCKED
                sum.tables++;
                for (DsBackfillPlan.Group g : t.groups) {
                    sum.axes++;
                    if (DsBackfillCollector.BLOCKED.equals(g.result)) sum.blockedGroups++;
                    sum.unanchoredRows += g.unanchoredRows.size();
                }
                continue;
            }

            Map<String, VersionedGroupWriter.Result> results = versionedGroupWriter.writeGroups(
                    t.sheet, rowsByAxis, SOURCE_QUOTE_BACKFILL, REASON_QUOTE_BACKFILL, operator);

            // 本次真的写了的轴值（UNCHANGED 的组一行不写，连 updated_at 都不许动 —— AC-14①）
            List<String> writtenAxes = new ArrayList<>();
            for (Map.Entry<String, VersionedGroupWriter.Result> e : results.entrySet()) {
                if (!VersionedGroupWriter.UNCHANGED.equals(e.getValue().result())) writtenAxes.add(e.getKey());
            }
            stampSourceQuotation(t.sheet, quotationId, plan.customerNo, writtenAxes);   // ≤2 条 SQL / 表

            sum.tables++;
            for (DsBackfillPlan.Group g : t.groups) {
                sum.axes++;
                if (DsBackfillCollector.BLOCKED.equals(g.result)) { sum.blockedGroups++; sum.unanchoredRows += g.unanchoredRows.size(); continue; }
                VersionedGroupWriter.Result r = results.get(g.axisValue);
                String actual = r == null ? g.result : r.result();
                if (VersionedGroupWriter.UNCHANGED.equals(actual)) sum.unchangedGroups++;
                else sum.upgradedGroups++;
                sum.unanchoredRows += g.unanchoredRows.size();
            }
        }
        LOG.infof("[ds-backfill] quotation=%s 回填完成：tables=%d axes=%d upgraded=%d unchanged=%d unanchored=%d",
                quotationId, sum.tables, sum.axes, sum.upgradedGroups, sum.unchangedGroups, sum.unanchoredRows);
        return sum;
    }

    /**
     * B-3 配套：给本次写入的主表新行 + 刚归档的 {@code _history} 行补「来源报价单 id」。
     *
     * <p>见类注释：{@code VersionedGroupWriter} 的 {@code insertAll}/{@code archive} 都不带这一列，
     * 漏了就是「列建了、值恒 NULL」的静默缺陷。
     * <p>🚫 <b>WHERE 命中面明确</b>（{@code CLAUDE.md} §3.2）：主表侧限定 {@code 轴列 IN (本次写的轴值)}
     * （必要时再加 {@code customer_no}）；{@code _history} 侧限定 {@code origin_id IN (归档前那批行的 id)}
     * —— {@code bigserial} 不会重号，且归档后主表原行已被删除，所以每个 {@code origin_id}
     * 在 {@code _history} 里至多对应本次归档的那一行。
     */
    private void stampSourceQuotation(SheetDef sheet, UUID quotationId, String customerNo, List<String> axes) {
        if (axes.isEmpty()) return;
        if (!mainTableReader.hasSourceQuotationId(sheet.tableName)) return;   // B-3 迁移未落库 → 安全跳过
        String col = SheetDef.SOURCE_QUOTATION_COLUMN;
        String axisCol = SqlIdent.of(sheet.axisColumn);

        StringBuilder sql = new StringBuilder("UPDATE ").append(SqlIdent.of(sheet.tableName))
                .append(" SET ").append(col).append(" = :qid WHERE ").append(axisCol).append(" IN (:axes)");
        boolean withCust = mainTableReader.hasCustomerNo(sheet) && customerNo != null && !customerNo.isBlank();
        if (withCust) sql.append(" AND ").append(SheetDef.RECORD_CUSTOMER_COLUMN).append(" = :cust");
        var q = em.createNativeQuery(sql.toString())
                .setParameter("qid", quotationId).setParameter("axes", axes);
        if (withCust) q.setParameter("cust", customerNo);
        int updated = q.executeUpdate();
        LOG.debugf("[ds-backfill] %s.%s 标记来源报价单：轴值 %d 个 / 行 %d", sheet.tableName, col, axes.size(), updated);
    }

    /**
     * 归档行的「来源报价单 id」补写（可选增强，与 AC 无关但符合 A0-1「无岔路」段的追溯意图）。
     *
     * <p>由调用方在 {@link #execute} <b>之前</b>把「归档前主表各行的 (id, source_quotation_id)」交进来，
     * 执行后按 {@code origin_id} 回填 {@code _history}。当前未启用 —— 归档前的值在 B-3 迁移落库
     * 之前恒为 NULL，回填它没有信息量；上游落库后由主线决定是否开启。
     */
    @SuppressWarnings("unused")
    private void stampHistorySourceQuotation(SheetDef sheet, Map<Long, Object> sourceByOriginId) {
        // 刻意留空：见方法注释。🚫 不要在这里写「先查再逐行 UPDATE」——那是 N+1。
    }
}
