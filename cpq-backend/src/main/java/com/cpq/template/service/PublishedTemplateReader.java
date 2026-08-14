package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponentSnapshot;
import com.cpq.template.exception.TemplateNotFrozenException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * task-0806：已发布模板渲染配置的<b>唯一</b>读取入口。
 *
 * <p>渲染期一切「这个模板有哪些页签、每个页签什么配置」的问题都问它——收口 10+ 处曾各自
 * 手写 {@code JOIN component} / {@code Component.findById} 的 native SQL（本 bug 潜伏至今的
 * 原因之一）。
 *
 * <p><b>接口形状必须是批量的</b>：把 CLAUDE.md「单个业务操作 SQL 条数与 N 无关」的铁律焊死
 * 在签名里。🚫 <b>禁止</b>新增 {@code tabOf(templateId, sortOrder)} 这类单条查询方法被调用方
 * 放进循环——那就是把 N+1 重新引回来。若某调用点确实只要一条，让它拿 {@link #allTabsOf}
 * 的结果在内存里挑（见 {@link #findTab}）。
 *
 * <p><b>miss 语义（B20 / FR-8 / D16~D19，2026-08-07 由「一律 500」改为两分——原 D3「存量一次性
 * 对齐后冻死」已被 D16 推翻）</b>：
 * <ul>
 *   <li><b>零行</b>：模板 status ∈ (PUBLISHED, ARCHIVED) 且查不到<b>任何</b>快照行 →
 *       <b>不是错误</b>，是「尚未按新语义重新发布」的正常过渡态（D17）。抛
 *       {@link TemplateNotFrozenException}（HTTP 409，{@code code=TEMPLATE_NOT_FROZEN}），
 *       调用方必须让它一路冒泡到 HTTP 响应——<b>禁止捕获后回落活表</b> {@code component} /
 *       {@code template_component}，那正是本次改造要消灭的行为。判断"是否已冻结"用
 *       {@link #isFrozen}。</li>
 *   <li><b>部分缺行</b>：已有部分快照行，但某个具体 sortOrder 缺失（{@link #findTab} 未命中）→
 *       快照被破坏（后门 / 裸 SQL 删的），仍然抛 {@link BusinessException}(500)（行为不变，D19）。
 *       由于本类保证「frozen 状态下 {@link #allTabsOf} 绝不返回空列表」（空列表在到达调用方之前
 *       已经在本类内部抛出 {@link TemplateNotFrozenException}），{@link #findTab} 收到非空
 *       {@code allTabs} 却找不到目标 sortOrder，只可能是这一种情况，不会跟「零行未冻结」混淆。</li>
 * </ul>
 * 两者<b>不可混为一谈</b>——混了会让「数据被后门/裸 SQL 删了」被误判成「还没发布」，
 * 丢失本次改造最想要的那个报警。DRAFT 模板本就不写快照（草稿期一律走活表，不在本类职责内），
 * {@link #allTabsOf} 对 DRAFT 静默返回空列表，不算 miss。
 */
@ApplicationScoped
public class PublishedTemplateReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 该模板全部页签快照，按 sortOrder 升序。一次查询 + 一次 {@link Template#findById}
     * 做「表行数 vs {@code components_snapshot} jsonb 数组长度」一致性校验（B21，见
     * {@link #verifyConsistentWithJsonb}）——两者由同一次 {@code publish()}/{@code archive()}
     * 派生自同一份 rows，正常状态下恒相等，不额外增查询（额外那一次 {@code findById} 是本方法
     * 唯一新增的开销，且与数据行数/页签数无关，符合 CLAUDE.md SQL 条数与 N 无关的铁律）。
     */
    public List<TemplateComponentSnapshot> allTabsOf(UUID templateId) {
        if (templateId == null) return List.of();
        List<TemplateComponentSnapshot> rows = TemplateComponentSnapshot.list(
                "templateId = ?1 ORDER BY sortOrder ASC", templateId);
        verifyConsistentWithJsonb(templateId, rows);
        return rows;
    }

    /**
     * B20：该模板是否已完成「全量冻结重新发布」（{@code template_component_snapshot} 非空）。
     * 一次 {@code COUNT} 查询。PUBLISHED/ARCHIVED 且返回 {@code false} = 过渡期「未冻结」
     * （D17）；DRAFT 恒 {@code false}，但草稿本就不适用冻结语义（不在本类职责内）。
     *
     * <p>供调用方在真正需要「区分未冻结 vs 正常」的展示场景（如体检/诊断类只读端点）主动判断，
     * 而不必依赖异常控制流。渲染路径仍应直接调用 {@link #allTabsOf} 系列方法——未冻结时它们会
     * 自己抛 {@link TemplateNotFrozenException}，调用方不需要也不应该先查一次 {@code isFrozen}
     * 再查一次 {@code allTabsOf}（多一次往返查询）。
     */
    public boolean isFrozen(UUID templateId) {
        if (templateId == null) return false;
        return TemplateComponentSnapshot.count("templateId", templateId) > 0;
    }

    /**
     * repair-0814：这批模板里<b>尚未冻结</b>的那些（{@link #isFrozen} 的批量版，判定口径逐字相同）。
     *
     * <p>「未冻结」= {@code status ∉ {PUBLISHED, ARCHIVED}}（DRAFT 等，渲染期直读活表配置）
     * <b>或</b> {@code status ∈ {PUBLISHED, ARCHIVED}} 但快照零行（D17 过渡态）。
     * 换个说法：<b>返回的这些模板，其渲染仍会受活表 component 配置影响</b>——这正是调用方关心的事。
     *
     * <p><b>为什么需要批量版</b>：调用方（{@code ComponentService.assertNotReferencedByCostingTemplate}）
     * 面对的是「一个组件被 N 张模板引用」，逐张调 {@link #isFrozen} 就是 {@code CLAUDE.md} 明令禁止的
     * N+1。本方法 <b>SQL 条数恒为 2（与 N 无关）</b>：一次 {@code IN} 查 template，一次 {@code IN}
     * 查快照行；配对在内存里做。
     *
     * <p><b>为什么口径必须共用本类而不是调用方自己写 SQL</b>：AP-52「契约不对齐」——
     * 「什么叫已冻结」在全仓必须只有一个定义（{@link #isFrozenStatus} + 快照非空），
     * 散落第二份必然随时间漂移。
     *
     * <p>找不到的 templateId（模板已删除等脏引用）直接跳过，不计入结果——它既不会渲染，
     * 也就谈不上「受活配置影响」。
     *
     * @param templateIds 待判定的模板 id（允许含重复 / null 集合）
     * @return 其中尚未冻结的模板实体（含 name/version/status，供调用方组织错误文案）；顺序与入参一致
     */
    public List<Template> unfrozenAmong(Collection<UUID> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) return List.of();
        List<UUID> ids = templateIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return List.of();

        Map<UUID, Template> byId = loadTemplatesByIds(ids);                       // SQL #1
        // SQL #2：一次 IN 取回这批模板的快照行，内存归并出「哪些模板有快照」。
        // 只关心「有没有」，故不做 COUNT 分组——行数是每模板页签数量级（十几行），
        // 与「查询条数与 N 无关」这条硬指标无关。
        List<TemplateComponentSnapshot> snapshotRows =
                TemplateComponentSnapshot.list("templateId in ?1", ids);
        Set<UUID> withSnapshot = new HashSet<>();
        for (TemplateComponentSnapshot s : snapshotRows) withSnapshot.add(s.templateId);

        List<Template> out = new ArrayList<>();
        for (UUID id : ids) {
            Template t = byId.get(id);
            if (t == null) continue;                                             // 脏引用：模板不存在
            if (!isFrozenStatus(t.status) || !withSnapshot.contains(id)) out.add(t);
        }
        return out;
    }

    /** driver 组件（data_driver_path 非空）。委派 {@link #allTabsOf}，不新增查询。 */
    public List<TemplateComponentSnapshot> driverCompsOf(UUID templateId) {
        List<TemplateComponentSnapshot> out = new ArrayList<>();
        for (TemplateComponentSnapshot s : allTabsOf(templateId)) {
            if (s.dataDriverPath != null && !s.dataDriverPath.isBlank()) out.add(s);
        }
        return out;
    }

    /** 树页签（tab_type = 'BOM'）。委派 {@link #allTabsOf}，不新增查询。 */
    public List<TemplateComponentSnapshot> treeTabsOf(UUID templateId) {
        List<TemplateComponentSnapshot> out = new ArrayList<>();
        for (TemplateComponentSnapshot s : allTabsOf(templateId)) {
            if ("BOM".equals(s.tabType)) out.add(s);
        }
        return out;
    }

    /** 是否存在 bom_recursive_expand = true 的页签。委派 {@link #allTabsOf}，不新增查询。 */
    public boolean hasRecursiveExpand(UUID templateId) {
        for (TemplateComponentSnapshot s : allTabsOf(templateId)) {
            if (Boolean.TRUE.equals(s.bomRecursiveExpand)) return true;
        }
        return false;
    }

    /**
     * 多模板批量（供整单场景，避免逐模板查）。一次 {@code IN} 查询取全部模板的全部页签，
     * 按 templateId 分桶；每个请求的 templateId 若无结果，按模板状态判定：不存在→404，
     * PUBLISHED/ARCHIVED 但零行→{@link TemplateNotFrozenException}（B20，未冻结不是错误），
     * DRAFT→静默放入空列表。
     *
     * <p>N+1 纪律：缺失的 templateId 集合用<b>一次</b> {@code IN} 查询批量取模板状态
     * （{@link #loadTemplatesByIds}），不逐个 {@code Template.findById}——过渡期内「整单多张
     * 产品卡各绑不同模板、其中好几个都还没重新发布」是预期场景，SQL 条数不能随缺失模板数增长。
     */
    public Map<UUID, List<TemplateComponentSnapshot>> allTabsOfMany(Collection<UUID> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) return Map.of();
        List<UUID> distinct = templateIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();

        List<TemplateComponentSnapshot> all = TemplateComponentSnapshot.list(
                "templateId IN ?1 ORDER BY templateId, sortOrder ASC", distinct);
        Map<UUID, List<TemplateComponentSnapshot>> byTemplate = new LinkedHashMap<>();
        for (TemplateComponentSnapshot s : all) {
            byTemplate.computeIfAbsent(s.templateId, k -> new ArrayList<>()).add(s);
        }
        List<UUID> missing = distinct.stream().filter(tid -> !byTemplate.containsKey(tid)).toList();
        if (!missing.isEmpty()) {
            Map<UUID, Template> existing = loadTemplatesByIds(missing);
            for (UUID tid : missing) {
                Template t = existing.get(tid);
                if (t == null) {
                    throw new BusinessException(404, "Template not found: " + tid);
                }
                if (isFrozenStatus(t.status)) {
                    throw new TemplateNotFrozenException(tid, t.status);
                }
                byTemplate.put(tid, List.of());
            }
        }
        return byTemplate;
    }

    /**
     * 从 {@link #allTabsOf} 的结果里按 sortOrder 精确取一条（内存挑选，不新增查询）。
     * 调用方应先在循环外调一次 {@link #allTabsOf} 拿到完整列表，避免在循环里调用本方法。
     *
     * <p>由类注释所述不变量——frozen 状态下 {@link #allTabsOf} 绝不会把空列表交到调用方
     * 手上（会先抛 {@link TemplateNotFrozenException}）——本方法收到非空 {@code allTabs}
     * 却找不到目标 sortOrder，只可能是「快照被破坏」（D19），故仍然抛 500，不会跟「未冻结」混淆。
     *
     * @return 命中的快照行；templateId 非 PUBLISHED/ARCHIVED 且未命中时返回 null（DRAFT 场景）
     * @throws BusinessException 500，模板已发布/归档但该 sortOrder 缺失快照行（D19：快照损坏）
     */
    public TemplateComponentSnapshot findTab(List<TemplateComponentSnapshot> allTabs, UUID templateId, int sortOrder) {
        if (allTabs != null) {
            for (TemplateComponentSnapshot s : allTabs) {
                if (s.sortOrder != null && s.sortOrder == sortOrder) return s;
            }
        }
        Template t = Template.findById(templateId);
        if (t != null && isFrozenStatus(t.status)) {
            throw new BusinessException(500,
                    "模板快照缺失：templateId=" + templateId + ", sortOrder=" + sortOrder
                    + "。已发布模板存在部分页签快照但该页签缺失，快照可能被破坏，请检查 template_component_snapshot");
        }
        return null;
    }

    // ---- 内部工具 ----

    private static boolean isFrozenStatus(String status) {
        return "PUBLISHED".equals(status) || "ARCHIVED".equals(status);
    }

    private Map<UUID, Template> loadTemplatesByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        List<Template> list = Template.list("id in ?1", ids);
        Map<UUID, Template> map = new LinkedHashMap<>();
        for (Template t : list) map.put(t.id, t);
        return map;
    }

    /**
     * B21（缺口二）：新表行数 vs {@code template.components_snapshot} jsonb 数组长度一致性校验。
     * 两者都由同一次 {@code TemplateService.publish()}/{@code archive()}（{@code persistSnapshotRows}
     * + {@code deriveComponentsSnapshotJson}，见该类 :228-231/347-348/517-518）派生自<b>同一份</b>
     * {@code snapshotRows} 列表，正常状态下恒相等；不相等只可能是发布流程之外的直接篡改
     * （后门 / 裸 SQL 删了某张表的部分行），正是 D19「快照损坏」要抓的情况。
     *
     * <p>此前 D19 的检测点只有 {@link #findTab}（按具体 sortOrder 精确匹配缺失），但
     * {@code findTab} 目前零生产调用方——检测机制形同虚设。改为在<b>人人都会经过</b>的
     * {@link #allTabsOf} 里做这道比较，让每一次渲染都在校验，不依赖某条恰好调用
     * {@code findTab} 的路径。
     *
     * <table>
     *   <caption>判定矩阵</caption>
     *   <tr><th>表行数</th><th>jsonb 长度</th><th>判定</th></tr>
     *   <tr><td>0</td><td>0</td><td>未冻结（D17，正常过渡态）→ {@link TemplateNotFrozenException}（409）</td></tr>
     *   <tr><td>N</td><td>N</td><td>正常，放行</td></tr>
     *   <tr><td>N</td><td>M（N≠M，含一侧为 0）</td><td>快照损坏（D19）→ {@link BusinessException}（500）</td></tr>
     * </table>
     *
     * <p>DRAFT 模板（本类职责外，草稿期一律走活表）跳过整套校验，含 rows 为空的正常情形。
     */
    private void verifyConsistentWithJsonb(UUID templateId, List<TemplateComponentSnapshot> rows) {
        Template t = Template.findById(templateId);
        if (t == null) {
            if (rows.isEmpty()) {
                throw new BusinessException(404, "Template not found: " + templateId);
            }
            // 理论不可达：template_component_snapshot.template_id 有 ON DELETE CASCADE FK，
            // 表有行则模板必存在；此处宽松放行而非抛错，不引入新的失败面。
            return;
        }
        if (!isFrozenStatus(t.status)) {
            return; // DRAFT：本类职责外，静默放行
        }
        int tableLen = rows.size();
        int jsonbLen = jsonbArrayLength(t.componentsSnapshot);
        if (tableLen == 0 && jsonbLen == 0) {
            throw new TemplateNotFrozenException(templateId, t.status);
        }
        if (tableLen != jsonbLen) {
            throw new BusinessException(500,
                    "模板快照损坏：templateId=" + templateId
                    + "，template_component_snapshot 行数=" + tableLen
                    + "，components_snapshot jsonb 长度=" + jsonbLen
                    + "。两者应恒相等（均由同一次 publish()/archive() 的 persistSnapshotRows 派生），"
                    + "出现不相等说明其中一侧被发布流程之外的方式改动过（后门 / 裸 SQL），请检查两张表");
        }
        // tableLen == jsonbLen 且非 (0,0) —— 正常，放行
    }

    /** 解析 {@code components_snapshot} jsonb 文本的数组长度；null/blank/非数组/解析失败均记 0。 */
    private static int jsonbArrayLength(String json) {
        if (json == null || json.isBlank()) return 0;
        try {
            JsonNode node = MAPPER.readTree(json);
            return node.isArray() ? node.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
