package com.cpq.basicdata.v6.service;

import com.cpq.quotation.dto.CustomerPartCandidateDTO;
import com.cpq.quotation.service.CustomerPartCandidateService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端从「导入候选 + 报价模板」建 quotation_line_item 明细行。
 *
 * <p>与前端 buildLineItemFromTemplate 等价，但只负责 INSERT 主表 quotation_line_item：
 * componentData 子表(quotation_line_component_data) + snapshot_rows 由后置的
 * ConfigureSnapshotService.snapshotQuotation 的 writeSnapshot UPSERT 自建，本类不碰。
 *
 * <p>事务：默认 REQUIRED —— 由调用方(如 V6QuotationCommitService.createQuotation)在其
 * @Transactional 内调用时并入同一事务，保证「建单 + 建行」强一致（不丢单）。
 */
@ApplicationScoped
public class QuotationLineItemMaterializeService {

    @Inject EntityManager em;
    @Inject CustomerPartCandidateService candidateService;

    /** 便捷入口：查候选 → 建行。 */
    @Transactional
    public List<UUID> materializeLines(UUID quotationId, UUID customerId,
                                       UUID importRecordId, UUID templateId) {
        List<CustomerPartCandidateDTO> candidates =
            candidateService.listCandidates(customerId, importRecordId);
        return materializeLinesFromCandidates(quotationId, templateId, candidates);
    }

    /**
     * 纯建行：按候选顺序 INSERT quotation_line_item（sort_order 从 0 递增）。
     *
     * <p><b>task-260825 B-24（第三处 N+1，2026-08-26 用户真机测试暴露）</b>：原实现逐行
     * {@code executeUpdate()}，1845 次往返 × 本环境 DB RTT ≈16.6ms ≈ 30.6s——直接违反
     * {@code backend.md} N+1 硬指标（循环体里出现查询）,也是 AC-13②「建单 POST 5 秒内返回
     * （只做建单+建行）」一直没达成的真正原因（D-5 把物化转后台后，建行本身仍要 30 秒）。
     *
     * <p>改法：<b>分两遍</b>——第一遍在内存里过滤空白 {@code partNo} 行 + 生成 id + 按顺序编号
     * {@code sort_order}（与改动前逐行版本的过滤/编号时机完全一致，只是先攒进 List 不立即写库）；
     * 第二遍按 {@code CHUNK=200}（与同工程 {@code ConfigureSnapshotService#writeRowDataBatchAllLines}
     * 的分块范式一致）拼多行 {@code VALUES (...),(...),...} 批量 INSERT。{@code quotationId}/
     * {@code templateId} 对整批恒定，绑定一次、在 SQL 文本里被多行复用（JPA 原生查询支持同一个
     * 具名参数在文本中出现多次只需 {@code setParameter} 一次）。
     *
     * <p>🔒 逐位等价（未改变的语义，均与改动前逐行版本一致）：
     * <ul>
     *   <li>{@code sort_order} 从 0 严格递增，且顺序与 {@code candidates} 遍历顺序一致
     *       （空白 partNo 行在编号<b>之前</b>被跳过，不占用 sort_order，与原实现一致）；</li>
     *   <li>{@code product_name_snapshot} 三级兜底：{@code partName} → {@code customerPartName}
     *       → {@code partNo}；</li>
     *   <li>{@code part_version_locked} 兜底默认 2000；</li>
     *   <li>返回的 {@code ids} 顺序 = 插入顺序 = 过滤后的 {@code candidates} 顺序（`ids.add` 与
     *       编号在同一遍循环里发生，批量 INSERT 只是把落库动作延后，不改变收集顺序）。</li>
     * </ul>
     */
    @Transactional
    public List<UUID> materializeLinesFromCandidates(UUID quotationId, UUID templateId,
                                                     List<CustomerPartCandidateDTO> candidates) {
        List<UUID> ids = new ArrayList<>();
        if (quotationId == null || candidates == null || candidates.isEmpty()) return ids;

        // 第一遍：过滤空白 partNo + 生成 id + 编号 sort_order（与改动前逐行版本时机一致）。
        // 每行 = [id, partNo, pname, customerPartNo, sortOrder, version]
        List<Object[]> rows = new ArrayList<>();
        int sort = 0;
        for (CustomerPartCandidateDTO c : candidates) {
            if (c == null || c.partNo == null || c.partNo.isBlank()) continue;
            UUID id = UUID.randomUUID();
            String pname = c.partName != null ? c.partName
                    : (c.customerPartName != null ? c.customerPartName : c.partNo);
            int ver = c.currentVersion != null ? c.currentVersion : 2000;
            rows.add(new Object[]{ id, c.partNo, pname, c.customerProductNo, sort, ver });
            ids.add(id);
            sort++;
        }
        if (rows.isEmpty()) return ids;

        // 第二遍：分块批量 INSERT，多行 VALUES 拼接。
        final int CHUNK = 200;
        for (int start = 0; start < rows.size(); start += CHUNK) {
            int end = Math.min(start + CHUNK, rows.size());
            List<Object[]> chunk = rows.subList(start, end);
            StringBuilder valB = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) valB.append(", ");
                valB.append("(:id").append(i).append(", :q, :tid, :pn").append(i)
                    .append(", :pname").append(i).append(", :cpn").append(i)
                    .append(", 'SIMPLE', :sort").append(i).append(", :ver").append(i)
                    .append(", cast('{}' as jsonb), NOW())");
            }
            String sql = "INSERT INTO quotation_line_item " +
                "(id, quotation_id, template_id, product_part_no_snapshot, product_name_snapshot, " +
                " customer_part_no, composite_type, sort_order, part_version_locked, " +
                " product_attribute_values, created_at) VALUES " + valB;
            var query = em.createNativeQuery(sql);
            query.setParameter("q", quotationId);
            query.setParameter("tid", templateId);
            for (int i = 0; i < chunk.size(); i++) {
                Object[] r = chunk.get(i);
                query.setParameter("id" + i, r[0]);
                query.setParameter("pn" + i, r[1]);
                query.setParameter("pname" + i, r[2]);
                query.setParameter("cpn" + i, r[3]);
                query.setParameter("sort" + i, r[4]);
                query.setParameter("ver" + i, r[5]);
            }
            query.executeUpdate();
        }
        return ids;
    }
}
