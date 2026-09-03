package com.cpq.component.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.exception.BusinessException;
import com.cpq.common.security.RoleAllowed;
import com.cpq.component.dto.BatchExpandDriverRequest;
import com.cpq.component.dto.BatchExpandDriverRequest.Task;
import com.cpq.component.dto.BatchExpandDriverResponse;
import com.cpq.component.dto.BatchExpandDriverResponse.Result;
import com.cpq.component.dto.ComponentDTO;
import com.cpq.component.dto.CreateComponentRequest;
import com.cpq.component.dto.ExpandDriverRequest;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.component.service.ComponentImportService;
import com.cpq.component.service.ComponentService;
import com.cpq.datasource.sqlview.BomTreeVarsContext;
import com.cpq.datasource.sqlview.QuotePendingScope;
import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.formula.dataloader.SnapshotRowsContext;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.BomTreeRenderService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Path("/api/cpq/components")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class ComponentResource {

    private static final Logger LOG = Logger.getLogger(ComponentResource.class);

    @Inject
    ComponentService componentService;

    @Inject
    ComponentDriverService componentDriverService;

    @Inject
    ComponentImportService componentImportService;

    /**
     * D-74（生产故障修复）：整单 BOM 料号并集计算——{@code :total_material_no} 唯一权威口径
     * 与 {@code CardSnapshotService}/{@code ConfigureSnapshotService} 共用同一算法
     * （{@link BomTreeRenderService#collectTotalMaterialNoUnion}），不另写第二套。
     */
    @Inject
    BomTreeRenderService bomTreeRenderService;

    @GET
    public ApiResponse<List<ComponentDTO>> list(
            @QueryParam("directoryId") UUID directoryId,
            @QueryParam("keyword") String keyword) {
        return ApiResponse.success(componentService.list(directoryId, keyword));
    }

    @GET
    @Path("/{id}")
    public ApiResponse<ComponentDTO> getById(@PathParam("id") UUID id) {
        return ApiResponse.success(componentService.getById(id));
    }

    /** task-0729 B7（api.md §5.2）：元素列绑定推导预填，供屏 8「推荐」按钮。 */
    @GET
    @Path("/{id}/element-binding-suggest")
    public ApiResponse<com.cpq.component.dto.ElementBindingSuggestDTO> suggestElementBinding(@PathParam("id") UUID id) {
        return ApiResponse.success(componentService.suggestElementBinding(id));
    }

    @POST
    public ApiResponse<ComponentDTO> create(CreateComponentRequest request) {
        return ApiResponse.success(componentService.create(request));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<ComponentDTO> update(
            @PathParam("id") UUID id,
            CreateComponentRequest request) {
        return ApiResponse.success(componentService.update(id, request));
    }

    /**
     * 设置/清空组件的驱动视图（data_driver_path 唯一真源）。
     * sqlViewName=null/空 表示取消驱动。
     */
    @PUT
    @Path("/{id}/driver-view")
    public ApiResponse<ComponentDTO> setDriverView(
            @PathParam("id") UUID id,
            com.cpq.component.dto.SetDriverViewRequest req) {
        return ApiResponse.success(
                componentService.setDriverView(id, req == null ? null : req.sqlViewName));
    }

    @PATCH
    @Path("/{id}/toggle-status")
    public ApiResponse<ComponentDTO> toggleStatus(@PathParam("id") UUID id) {
        return ApiResponse.success(componentService.toggleStatus(id));
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        componentService.delete(id);
        return ApiResponse.success();
    }

    /**
     * C1: 全库 BASIC_DATA path↔视图列名审计（只读，不修改数据）。
     *
     * <p>遍历全库所有组件的 fields[].default_source.path，检出 $view.col 形态中
     * col 与该组件 component_sql_view.declared_columns 不一致的可疑项，
     * 并给出下划线前缀差异的修正建议。
     *
     * <p>用途：手工执行后根据清单在 Task C2 修正存量 path 配置，组件保存校验在 C3 防回归。
     *
     * @return 可疑项列表；全部正常时返回空列表
     */
    @GET
    @Path("/audit-basicdata-paths")
    @RoleAllowed({"SYSTEM_ADMIN", "PRICING_MANAGER"})
    public ApiResponse<List<Map<String, Object>>> auditBasicDataPaths() {
        return ApiResponse.success(componentService.auditBasicDataPaths());
    }

    // task-0806 D11：refresh-template-snapshots 端点整体删除（AC-4）。其唯一实现
    // TemplateService.refreshSnapshotsByComponent（H1）已退役——语义（把组件配置推给已发布
    // 模板）与严格版本化直接冲突。不做 410 过渡，路由直接消失 → 404。
    // 想让新配置生效，走 createNewDraft → 改 → publish 出新版本。

    /**
     * Y1.5 行驱动展开 — 按组件 dataDriverPath 取 N 行,
     * 每行隐式 JOIN 求值所有 BASIC_DATA 字段。
     *
     * 无 dataDriverPath → 返回 rowCount=0 (前端按单行渲染兜底)
     */
    @POST
    @Path("/{id}/expand-driver")
    public ApiResponse<ExpandDriverResponse> expandDriver(
            @PathParam("id") UUID id,
            ExpandDriverRequest req) {
        UUID customerId = req != null ? req.customerId : null;
        String partNo = req != null ? req.partNo : null;
        Integer partVersion = req != null ? req.partVersion : null;
        boolean debugSql = req != null && req.debugSql;
        if (debugSql) com.cpq.datasource.sqlview.SqlDebugContext.begin();
        try {
            ExpandDriverResponse resp = componentDriverService.expand(id, customerId, partNo, partVersion);
            if (debugSql && resp != null) resp.debugSql = com.cpq.datasource.sqlview.SqlDebugContext.drainJoined();
            return ApiResponse.success(resp);
        } finally {
            if (debugSql) com.cpq.datasource.sqlview.SqlDebugContext.drain(); // 清理(若上面已 drain 则 no-op)
        }
    }

    /**
     * 行键候选 — 根据 driver $视图的 declaredColumns 返回每个字段是否可作行键。
     */
    @POST
    @Path("/{id}/row-key-candidates")
    public ApiResponse<com.cpq.component.dto.RowKeyCandidatesResponse> rowKeyCandidates(
            @PathParam("id") UUID id,
            com.cpq.component.dto.RowKeyCandidatesRequest req) {
        var candidates = componentDriverService.computeRowKeyCandidates(
                id,
                req == null ? null : req.dataDriverPath,
                req == null ? null : req.fields);
        return ApiResponse.success(new com.cpq.component.dto.RowKeyCandidatesResponse(candidates));
    }

    /**
     * 批量行驱动展开 — 一次 HTTP 请求服务多个 (componentId, customerId, partNo) 组合。
     *
     * <p>每个 task 独立 try-catch，单个失败不影响其他结果。
     * 自动复用进程级缓存（{@link ComponentDriverService#expand} 内部处理）。
     * 单次 batch 上限 5000 个 task — 与前端 BATCH chunk 对齐，让一张报价单（≤200 行 × 10 组件）
     * 一次 HTTP 完成，减少前后端交互轮次（2026-05-15 从 100 提到 5000）。
     *
     * <p>Response key 格式：componentId:customerId:partNo（null 用 "_" 占位），
     * 与前端 expand-driver 缓存 key 格式一致。
     */
    @POST
    @Path("/batch-expand")
    public ApiResponse<BatchExpandDriverResponse> batchExpand(BatchExpandDriverRequest req) {
        BatchExpandDriverResponse resp = new BatchExpandDriverResponse();
        resp.results = new ArrayList<>();
        if (req == null || req.tasks == null) {
            return ApiResponse.success(resp);
        }
        if (req.tasks.size() > 5000) {
            throw new BusinessException(400, "batch tasks 上限 5000，当前 " + req.tasks.size());
        }
        final boolean debugSql = req.debugSql;
        // 与 tasks 同序预置 results 占位,Phase 1/2 按 index 回填(保证按 index 配对的协议不变)
        for (int i = 0; i < req.tasks.size(); i++) {
            Task t = req.tasks.get(i);
            Result r = new Result();
            r.key = ComponentDriverService.cacheKey(t.componentId, t.customerId, t.partNo, t.partVersion);
            resp.results.add(r);
        }

        // Feature flag — 2026-06-23 默认开(已用 BatchExpandBucketEquivTest 证合桶 ON==OFF 逐位等价:
        //   DataLoader.stableSort 根治视图无 ORDER BY 行序非确定性 → 合桶 expandMulti 与逐 task expand 同序)。
        //   合桶把首次加载 batch-expand 的 N×M 次远程 expand 压到每桶 1 次,直击"导入后进报价单 6-21s"。
        //   kill switch:-Dcpq.batch-expand-bucket=false 或 export CPQ_BATCH_EXPAND_BUCKET=false。
        boolean bucketEnabled = "true".equalsIgnoreCase(
                System.getProperty("cpq.batch-expand-bucket",
                    System.getenv().getOrDefault("CPQ_BATCH_EXPAND_BUCKET", "true")));

        // task-0725 T3-P3：批量预取 usage=QUOTE 的 task 所涉及报价单状态（一次 IN 查，避免逐 task N+1）。
        // usage 缺省/非法/COSTING 的 task 不收集（本就不会调 QuotePendingScope.open，省一次 DB 往返）。
        Map<UUID, String> quoteStatusById = prefetchQuoteStatuses(req.tasks);

        // task-260819 D-74（生产故障修复）：整单一次算好 :total_material_no 料号并集——本入口是
        // ComponentResource.batchExpand（渲染贯穿三入口之一，见 QuotationIdContext 类注释），此前
        // 遗漏 open BomTreeVarsContext（B-19 只覆盖了 CardSnapshotService/ConfigureSnapshotService
        // 两个入口），凡组件 $view 引用 :total_material_no（如 $bom_view）在此入口一律撞 B-20 守卫
        // 直接阻断，编辑页产品行全空。按 quotationId 分组，一次 IN 查 quotation_line_item + 每个
        // distinct quotationId 各发 1 次递归 SQL（N+1 约束：SQL 条数只与 distinct quotationId 数量
        // 成正比，与 tasks 条数无关——批内通常只有 1 个 distinct quotationId）。
        Map<UUID, BomTreeRenderService.MaterialUnionResult> quoteUnions = computeQuoteUnionsForBatch(req.tasks);

        // ── P0(2026-06-26):批量预载 snapshot,杜绝 Phase 1 每 task 一次 SELECT snapshot_rows(N+1)──
        //   收集所有 task 的 lineItemId,一次 IN 查全部 snapshot_rows 塞 ThreadLocal;expand 的 snapshot-read
        //   命中上下文即用、不再逐 task 查库(一单 600+ task 全有快照时:600+ 次远程往返 → 1 次 IN)。
        //   务必 finally clear,避免线程池下个请求误用旧值。
        boolean snapBatchActive = false;
        // [be-profile] 分段埋点(2026-06-26):prefetch / phase1(snapshot 命中) / phase2(实时 expand) 各耗时与计数。
        long _bp0 = System.nanoTime();
        int _preCount = 0;
        if (bucketEnabled) {
            java.util.Set<UUID> lids = new java.util.LinkedHashSet<>();
            for (Task t : req.tasks) if (t.lineItemId != null) lids.add(t.lineItemId);
            if (!lids.isEmpty()) {
                java.util.Map<String, String> _pf = componentDriverService.prefetchSnapshotRows(lids);
                _preCount = _pf.size();
                SnapshotRowsContext.set(_pf);
                snapBatchActive = true;
            }
        }
        long _prefetchMs = (System.nanoTime() - _bp0) / 1_000_000;
        try {
            long _bp1 = System.nanoTime();
            ApiResponse<BatchExpandDriverResponse> out =
                    doBatchExpandPhases(req, resp, bucketEnabled, debugSql, quoteStatusById, quoteUnions);
            // phase1 命中数(driverPath=snapshot)vs phase2(实时 expand)= 诊断 batch-expand 慢在快照读还是实时展开
            int _snapHit = 0;
            for (Result r : resp.results) if (r != null && r.data != null && "snapshot".equals(r.data.driverPath)) _snapHit++;
            long _phasesMs = (System.nanoTime() - _bp1) / 1_000_000;
            LOG.debugf("[be-profile] tasks=%d prefetched=%d snapshotHit=%d realExpand=%d | prefetch=%dms phases=%dms",
                    req.tasks.size(), _preCount, _snapHit, req.tasks.size() - _snapHit, _prefetchMs, _phasesMs);
            return out;
        } finally {
            if (snapBatchActive) SnapshotRowsContext.clear();
        }
    }

    private ApiResponse<BatchExpandDriverResponse> doBatchExpandPhases(
            BatchExpandDriverRequest req, BatchExpandDriverResponse resp,
            boolean bucketEnabled, boolean debugSql, Map<UUID, String> quoteStatusById,
            Map<UUID, BomTreeRenderService.MaterialUnionResult> quoteUnions) {
        // ── Phase 1:每个 task 先试 snapshot,命中直返;未命中收集进 Phase 2 候选 ──
        List<Integer> phase2 = new ArrayList<>();
        for (int i = 0; i < req.tasks.size(); i++) {
            Task t = req.tasks.get(i);
            Result r = resp.results.get(i);
            try {
                QuotationIdContext.set(t.quotationId);
                // task-0725 T3-P3：按 task.usage 决定是否打开报价侧 pending 可见域。usage 缺省/非法
                // 一律不 open（isQuoteUsage 兜底 false），核价侧 task 逐位不受影响（AC-17）。
                boolean _pqOpened = isQuoteUsage(t.usage);
                UUID _pqPrev = _pqOpened
                        ? QuotePendingScope.open(t.quotationId, quoteStatusById.get(t.quotationId))
                        : null;
                try {
                    boolean hasContext = (t.overrideDataDriverPath != null && !t.overrideDataDriverPath.isBlank())
                            || (t.overrideFieldsJson != null && !t.overrideFieldsJson.isBlank())
                            || t.lineItemId != null
                            || t.compositeType != null
                            || (t.childLineItemIds != null && !t.childLineItemIds.isEmpty());
                    if (!bucketEnabled) {
                        // Flag 关 → 维持原逻辑(无 Phase 2)。task-260819 D-74：本分支真发 SQL（expand /
                        // expandWithSnapshot 内部可能落到 SqlViewExecutor），须 open BomTreeVarsContext。
                        boolean _bvOpened = openBomTreeVars(quoteUnions.get(t.quotationId));
                        try {
                            if (debugSql) com.cpq.datasource.sqlview.SqlDebugContext.begin();
                            if (hasContext) {
                                r.data = componentDriverService.expandWithSnapshot(
                                    t.componentId, t.customerId, t.partNo, t.partVersion,
                                    t.overrideDataDriverPath, t.overrideFieldsJson, t.lineItemId, t.compositeType,
                                    t.childLineItemIds);
                            } else {
                                r.data = componentDriverService.expand(t.componentId, t.customerId, t.partNo, t.partVersion);
                            }
                            if (debugSql) r.debugSql = com.cpq.datasource.sqlview.SqlDebugContext.drainJoined();
                            r.status = "OK";
                        } finally {
                            if (_bvOpened) BomTreeVarsContext.clear();
                        }
                        continue;
                    }
                    // Flag 开 → Phase 1 仅【窥探】snapshot:命中直返;未命中绝不实时展开,直接进 Phase 2。
                    //   FIX 1(2026-06-26):原先调 expandWithSnapshot,miss 时它会做一次真展开、结果又因 driverPath≠"snapshot"
                    //   被丢弃、塞进 Phase 2(导入 616 task 全 miss = 18.6s 纯白干,Phase 2 再合桶算一遍)。改用 tryReadSnapshot:
                    //   miss 返 null、不实时展开 → 直接 phase2.add。Phase 2 产出不变(BatchExpandBucketEquivTest 守)。
                    //   BUG-1(2026-07-25):quotation_line_component_data.snapshot_rows 是报价侧专属快照表
                    //   (只由 ConfigureSnapshotService / QuotationTreeService 写)。tryReadSnapshot 本身完全不看
                    //   usage/QuotePendingScope——COSTING task 命中即会拿到报价侧 pending 数据 + __v6_id 锚点,
                    //   破 AC-17。故只在 usage=QUOTE 时才允许窥探快照;COSTING/非法/缺省一律跳过窥探直接进 Phase 2
                    //   走真实展开(此时 scope 关闭,不含 pending 行/__v6_id)。
                    if (hasContext && isQuoteUsage(t.usage)) {
                        ExpandDriverResponse snap = componentDriverService.tryReadSnapshot(t.componentId, t.lineItemId);
                        if (snap != null) {
                            r.data = snap;
                            r.status = "OK";
                            continue;
                        }
                    }
                    phase2.add(i);
                } finally {
                    if (_pqOpened) QuotePendingScope.restore(_pqPrev);
                    QuotationIdContext.clear();
                }
            } catch (Exception e) {
                r.status = "ERROR";
                r.error = e.getMessage();
                // 即便报错也把已捕获的 SQL 带回(record 在 executeQuery 之前已记录),便于排查失败的那条 SQL
                if (debugSql) r.debugSql = com.cpq.datasource.sqlview.SqlDebugContext.drainJoined();
                LOG.warnf("batch-expand[phase1] task %s failed: %s", r.key, e.getMessage());
            }
        }

        if (phase2.isEmpty()) {
            return ApiResponse.success(resp);
        }

        // ── Phase 2:按 bucket key 分组,可合的一次 expandMulti,不可合的逐 task expand ──
        // bucketKey = componentId|customerId|partVersion|effectiveDriverPath|fieldsHash|usage|quotationId[|lineItemId 视图含 :lineItemId 时]
        // 🔒 quotationId 维度(task-0722 返修项3,2026-07-23):防御性加固——真实调用方
        //    useDriverExpansions(lineItems, customerId, quotationId) 每 hook 实例绑死一个 quotationId,
        //    当前不可达同批混单;但一旦其余维度相同、quotationId 不同的 task 混入同一请求(如未来"多单对比视图"),
        //    不加此维度会被合桶后只用 pivot 的 quotationId 求值一次(如 :priceBaseDate),其余 task 静默拿到别单的取价结果。
        // 🔴 usage 维度(task-0725 T3-P3,评审 HIGH):报价/核价永不合并——同批次「其余维度全同、仅 usage 不同」
        //    的两个 task（api.md §2.3 混合示例）若不分桶，canMerge 成立时整桶只 expandMulti 一次、pending 可见域
        //    只能按 pivot 开一次，混桶内必有一侧拿到错误可见性。usage 缺省/非法一律归一化为 "COSTING"（与
        //    isQuoteUsage 判定同一套兜底），garbage 值不会额外裂桶。
        Map<String, List<Integer>> buckets = new LinkedHashMap<>();
        Map<String, String> bucketDriverPath = new HashMap<>();
        for (int idx : phase2) {
            Task t = req.tasks.get(idx);
            String dp = componentDriverService.resolveEffectiveDriverPath(t.componentId, t.overrideDataDriverPath);
            String fieldsTag = t.overrideFieldsJson == null ? "" : Integer.toHexString(t.overrideFieldsJson.hashCode());
            String key = t.componentId + "|" + t.customerId + "|" + t.partVersion + "|" + dp + "|" + fieldsTag
                    + "|u=" + usageTag(t.usage)
                    + "|q=" + (t.quotationId == null ? "" : t.quotationId);
            if (componentDriverService.viewUsesLineItemId(t.componentId, dp)) {
                key += "|li=" + (t.lineItemId == null ? "" : t.lineItemId);
            }
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(idx);
            bucketDriverPath.put(key, dp);
        }

        for (Map.Entry<String, List<Integer>> e : buckets.entrySet()) {
            List<Integer> idxs = e.getValue();
            String dp = bucketDriverPath.get(e.getKey());
            Task pivot = req.tasks.get(idxs.get(0));
            // P3(2026-06-26):去掉 allUniquePartNos 约束 —— 同料号多卡(170 行/77 distinct part)也可合。
            //   expandMulti 传的是 distinct partNos(line 333 .distinct()),分发按 t.partNo 取(同料号多 task 共享
            //   同一只读 resp,下面只 `r.data = part` 不 mutate → AP-37 安全)。DataLoader.stableSort 保
            //   expandMulti==逐 task 行序。与 S2 precomputeQuoteDriverBuckets 同套路(它对 distinct partNo 一次 expandMulti)。
            //   收益:eligible(非 lineItemId 视图)组件的 616 per-task → 合桶,batch-expand 22s→秒级。等价见 BatchExpandBucketEquivTest。
            boolean canMerge = idxs.size() >= 2
                    && !componentDriverService.viewUsesLineItemId(pivot.componentId, dp);
            long _bktStart = System.nanoTime();   // [be-bucket] 分桶耗时埋点
            if (!canMerge) {
                // 不能合 → 桶内逐 task 跑(同原逻辑)
                for (int idx : idxs) {
                    Task t = req.tasks.get(idx);
                    Result r = resp.results.get(idx);
                    runSingleTask(t, r, quoteStatusById, quoteUnions);
                }
                long _ms = (System.nanoTime() - _bktStart) / 1_000_000;
                LOG.debugf("[be-bucket] comp=%s dp=%s merged=false tasks=%d lineItemIdView=%b ms=%d",
                        pivot.componentId, dp, idxs.size(),
                        componentDriverService.viewUsesLineItemId(pivot.componentId, dp), _ms);
                continue;
            }
            // 合并跑一次 SQL 视图,按 hf_part_no 分发回各 task
            try {
                List<String> partNos = idxs.stream()
                        .map(idx -> req.tasks.get(idx).partNo)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
                // 桶内同一 quotationId(同一 batch-expand 请求里所有 task 同单)→ 设到 ThreadLocal
                // 让 mirror 视图能用 :quotationId(统一协议)
                QuotationIdContext.set(pivot.quotationId);
                // task-0725 T3-P3：桶内 usage 已随 bucketKey 统一（|u= 维度），pivot.usage 代表整桶。
                // 按 pivot 是否 QUOTE 决定是否打开 pending 可见域，与 Phase 1/runSingleTask 同款判定。
                boolean _pqOpened = isQuoteUsage(pivot.usage);
                UUID _pqPrev = _pqOpened
                        ? QuotePendingScope.open(pivot.quotationId, quoteStatusById.get(pivot.quotationId))
                        : null;
                // task-260819 D-74：桶内同一 quotationId（bucketKey 已含 |q= 维度），用 pivot 的整单
                // 料号并集即可代表整桶，expandMulti 真发 SQL，须 open。
                boolean _bvOpened = openBomTreeVars(quoteUnions.get(pivot.quotationId));
                Map<String, ExpandDriverResponse> merged;
                try {
                    merged = componentDriverService.expandMulti(
                            pivot.componentId, pivot.customerId, partNos, pivot.partVersion,
                            pivot.overrideDataDriverPath, pivot.overrideFieldsJson);
                } finally {
                    if (_bvOpened) BomTreeVarsContext.clear();
                    if (_pqOpened) QuotePendingScope.restore(_pqPrev);
                    QuotationIdContext.clear();
                }
                for (int idx : idxs) {
                    Task t = req.tasks.get(idx);
                    Result r = resp.results.get(idx);
                    ExpandDriverResponse part = merged.get(t.partNo);
                    if (part == null) {
                        part = new ExpandDriverResponse();
                        part.rows = new ArrayList<>();
                        part.rowCount = 0;
                        part.driverPath = dp;
                    }
                    r.data = part;
                    r.status = "OK";
                }
                long _ms = (System.nanoTime() - _bktStart) / 1_000_000;
                LOG.debugf("[be-bucket] comp=%s dp=%s merged=true partNos=%d 省%d次 ms=%d",
                        pivot.componentId, dp, partNos.size(), partNos.size() - 1, _ms);
            } catch (Exception ex) {
                LOG.warnf("[batch-expand bucket-merge] bucket=%s 失败,fallback 逐 task: %s", e.getKey(), ex.getMessage());
                for (int idx : idxs) {
                    Task t = req.tasks.get(idx);
                    Result r = resp.results.get(idx);
                    runSingleTask(t, r, quoteStatusById, quoteUnions);
                }
            }
        }
        return ApiResponse.success(resp);
    }

    /** Phase 2 桶不可合时的单 task 跑(同原 batchExpand 逻辑),包 QuotationIdContext 让视图能用 :quotationId。 */
    private void runSingleTask(Task t, Result r, Map<UUID, String> quoteStatusById,
                                Map<UUID, BomTreeRenderService.MaterialUnionResult> quoteUnions) {
        try {
            QuotationIdContext.set(t.quotationId);
            // task-0725 T3-P3：按 task.usage 决定是否打开报价侧 pending 可见域（同 Phase 1 判定）。
            boolean _pqOpened = isQuoteUsage(t.usage);
            UUID _pqPrev = _pqOpened
                    ? QuotePendingScope.open(t.quotationId, quoteStatusById.get(t.quotationId))
                    : null;
            // task-260819 D-74：本方法两条分支（expandWithSnapshot 的实时展开兜底 / expand）都可能
            // 真发 SQL 落到 SqlViewExecutor，须 open。
            boolean _bvOpened = openBomTreeVars(quoteUnions.get(t.quotationId));
            try {
                boolean hasContext = (t.overrideDataDriverPath != null && !t.overrideDataDriverPath.isBlank())
                        || (t.overrideFieldsJson != null && !t.overrideFieldsJson.isBlank())
                        || t.lineItemId != null
                        || t.compositeType != null
                        || (t.childLineItemIds != null && !t.childLineItemIds.isEmpty());
                if (hasContext) {
                    // BUG-1(2026-07-25):expandWithSnapshot 内部第一步就是 tryReadSnapshot——读的是同一张
                    // 报价侧专属快照表(quotation_line_component_data.snapshot_rows,只由 ConfigureSnapshotService/
                    // QuotationTreeService 写)。COSTING task 落到这条不可合桶路径(view 含 :lineItemId 或 task 单独成桶)
                    // 时若仍调 expandWithSnapshot,命中即拿到报价侧 pending 数据 + __v6_id 锚点,破 AC-17。
                    // 只有 usage=QUOTE 才允许用快照;COSTING/非法/缺省一律改调不读快照的 9 参 expand 重载,强制走
                    // 实时展开(scope 关闭 → 无 pending 行、无 __v6_id)。
                    r.data = isQuoteUsage(t.usage)
                        ? componentDriverService.expandWithSnapshot(
                            t.componentId, t.customerId, t.partNo, t.partVersion,
                            t.overrideDataDriverPath, t.overrideFieldsJson, t.lineItemId, t.compositeType,
                            t.childLineItemIds)
                        : componentDriverService.expand(
                            t.componentId, t.customerId, t.partNo, t.partVersion,
                            t.overrideDataDriverPath, t.overrideFieldsJson, t.lineItemId, t.compositeType,
                            t.childLineItemIds);
                } else {
                    r.data = componentDriverService.expand(t.componentId, t.customerId, t.partNo, t.partVersion);
                }
                r.status = "OK";
            } finally {
                if (_bvOpened) BomTreeVarsContext.clear();
                if (_pqOpened) QuotePendingScope.restore(_pqPrev);
                QuotationIdContext.clear();
            }
        } catch (Exception e) {
            r.status = "ERROR";
            r.error = e.getMessage();
            LOG.warnf("batch-expand[single] task %s failed: %s", r.key, e.getMessage());
        }
    }

    /**
     * task-0725 T3-P3：{@code task.usage} 归一化判定。{@code "QUOTE"}（大小写不敏感）→ true；
     * 缺省 / 非法值（含 {@code "COSTING"}）→ false（不开 pending 可见域，见 api.md §2.2 不变式）。
     */
    private static boolean isQuoteUsage(String usage) {
        return "QUOTE".equalsIgnoreCase(usage);
    }

    /** task-0725 T3-P3：bucketKey 用的归一化侧别标签（garbage usage 一律并入 "COSTING" 桶，不额外裂桶）。 */
    private static String usageTag(String usage) {
        return isQuoteUsage(usage) ? "QUOTE" : "COSTING";
    }

    /**
     * task-0725 T3-P3：批量预取 usage=QUOTE 的 task 所涉及报价单状态（一次 IN 查，供
     * {@link QuotePendingScope#open} 冻结判定用）。usage 非 QUOTE 的 task 不贡献 quotationId
     * （即使其余维度相同，也不会被误开 pending 域）。
     */
    private Map<UUID, String> prefetchQuoteStatuses(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) return Map.of();
        Set<UUID> ids = new LinkedHashSet<>();
        for (Task t : tasks) {
            if (isQuoteUsage(t.usage) && t.quotationId != null) ids.add(t.quotationId);
        }
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> out = new HashMap<>();
        for (Quotation q : Quotation.<Quotation>list("id in ?1", new ArrayList<>(ids))) {
            out.put(q.id, q.status);
        }
        return out;
    }

    /**
     * task-260819 D-74（生产故障修复）：{@code batchExpand} 渲染入口整单一次算好
     * {@code :total_material_no} 料号并集——口径与 {@code CardSnapshotService}/
     * {@code ConfigureSnapshotService} 完全一致（同一个
     * {@link BomTreeRenderService#collectTotalMaterialNoUnion}，不另写第二套算法，D-50 要
     * 收敛的正是「多套口径分叉」）。
     *
     * <p><b>根因回顾</b>：全工程 {@code BomTreeVarsContext.set(...)} 此前只覆盖了
     * {@code quotation/service} 与 {@code configure/service} 两处渲染入口，唯独漏了
     * {@code ComponentResource.batchExpand} 这条前端编辑页打开时真正会打到的渲染路径——
     * 组件 $view 一旦引用 {@code :total_material_no}（如 D-50「主树供数组」机制产出的
     * {@code $bom_view}），SQL 到这里必定撞 B-20 守卫，产品行全空。
     *
     * <p><b>N+1 约束</b>：本方法只发 <b>1 次</b> {@code quotation_line_item} 的 IN 查询
     * （覆盖本批所有 distinct quotationId），随后每个 distinct quotationId 各发 <b>1 次</b>递归
     * 树 SQL——SQL 总条数 = 1 + distinct(quotationId)，与 {@code tasks} 条数无关（批内绝大多数
     * 场景只有 1 个 distinct quotationId，即打开中的这一张报价单）。
     *
     * @return quotationId → 该单整单 BOM 料号并集；task 无 quotationId（如独立组件预览，不落在
     *         任何报价单上下文里）或该 quotationId 名下没有带 {@code productPartNoSnapshot} 的行时，
     *         不会出现在返回的 map 里——调用方按 {@code union == null} 分支处理（不 open，
     *         维持 B-20 守卫原有的「引用了但拿不到值就报错」行为，见类方法 {@link #openBomTreeVars}）。
     */
    private Map<UUID, BomTreeRenderService.MaterialUnionResult> computeQuoteUnionsForBatch(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) return Map.of();
        Set<UUID> ids = new LinkedHashSet<>();
        for (Task t : tasks) {
            if (t.quotationId != null) ids.add(t.quotationId);
        }
        if (ids.isEmpty()) return Map.of();
        // 唯一一次访问 quotation_line_item 表的查询,无论 tasks 条数多少(N+1 约束)。
        List<QuotationLineItem> allLines = QuotationLineItem.list("quotationId in ?1", new ArrayList<>(ids));
        Map<UUID, List<QuotationLineItem>> byQuotation = new LinkedHashMap<>();
        for (QuotationLineItem li : allLines) {
            if (li.productPartNoSnapshot == null || li.productPartNoSnapshot.isBlank()) continue;
            byQuotation.computeIfAbsent(li.quotationId, k -> new ArrayList<>()).add(li);
        }
        Map<UUID, BomTreeRenderService.MaterialUnionResult> out = new HashMap<>();
        for (Map.Entry<UUID, List<QuotationLineItem>> e : byQuotation.entrySet()) {
            out.put(e.getKey(), bomTreeRenderService.collectTotalMaterialNoUnion(e.getValue(), "QUOTE"));
        }
        return out;
    }

    /**
     * task-260819 D-74：{@code union} 非空时 open {@link BomTreeVarsContext}（RENDER 模式，
     * 携带整单料号并集 + 「后代→根」映射，与 B-19/B-21 既有口径一致），返回是否已 open——
     * 调用方据此决定 finally 是否要 clear（成对，防 ThreadLocal 泄漏串单）。
     *
     * <p>{@code union == null}（该 task 无 quotationId，或该 quotationId 查不到任何带料号的行）
     * 时不 open——🚫 不放宽 B-20 守卫：如果该组件的 $view 确实引用了 {@code :total_material_no}，
     * 后续执行仍会按守卫既有行为抛出「未提供该参数」的可识别错误，而不是静默返回错误数据。
     */
    private static boolean openBomTreeVars(BomTreeRenderService.MaterialUnionResult union) {
        if (union == null) return false;
        BomTreeVarsContext.set(new BomTreeVarsContext.Vars(
                null, union.totalMaterialNo, null,
                BomTreeVarsContext.Mode.RENDER, union.rootsByMaterial, union.materialsByRoot));
        return true;
    }

    /**
     * G4: 目录级存量导入引用补救。
     *
     * <p>扫描指定目录内所有组件的 formulas，将仍指向目录外源组件的跨组件引用
     * 重映射为同目录内对应的副本（base code 一致）。
     *
     * <p>映射规则：
     * <ul>
     *   <li>cross_tab_ref.source（UUID）：若目录外 → 按 base code 找目录内副本 → 更新</li>
     *   <li>component_subtotal.component_code：若 code 不在目录内 → 按 base 找副本 → 更新</li>
     * </ul>
     *
     * <p>同 base 多副本（__imp1/__imp2）时按 code 升序取第一个；无法解析的引用记录为
     * unresolved 并跳过（不中断其他组件处理）。
     *
     * @param dirId  目标目录 UUID
     * @param dryRun true(默认) = 只返回将要重映射的清单，不修改数据库；
     *               false = 实际写库
     */
    @POST
    @Path("/directories/{dirId}/remap-imported-refs")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public ApiResponse<ComponentImportService.DirRemapResult> remapImportedRefs(
            @PathParam("dirId") UUID dirId,
            @QueryParam("dryRun") @DefaultValue("true") boolean dryRun) {
        return ApiResponse.success(
                componentImportService.remapImportedRefsInDirectory(dirId, dryRun));
    }
}
