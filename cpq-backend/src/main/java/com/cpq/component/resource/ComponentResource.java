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
import com.cpq.formula.dataloader.QuotationIdContext;
import com.cpq.formula.dataloader.SnapshotRowsContext;
import com.cpq.template.service.TemplateService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    TemplateService templateService;

    @Inject
    ComponentImportService componentImportService;

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

    /**
     * H1: 手工触发: 同步所有引用该组件的模板 snapshot.
     * 组件 update 已自动调用本同款逻辑; 此端点用于:
     *  - 历史模板 (V184 之前发布) 修复
     *  - 数据迁移补偿
     *  - 管理工具脚本
     * 返回受影响的 template id 列表.
     */
    @POST
    @Path("/{id}/refresh-template-snapshots")
    @RoleAllowed({"PRICING_MANAGER", "SYSTEM_ADMIN"})
    public ApiResponse<List<UUID>> refreshTemplateSnapshots(@PathParam("id") UUID id) {
        return ApiResponse.success(templateService.refreshSnapshotsByComponent(id));
    }

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
            ApiResponse<BatchExpandDriverResponse> out = doBatchExpandPhases(req, resp, bucketEnabled, debugSql);
            // phase1 命中数(driverPath=snapshot)vs phase2(实时 expand)= 诊断 batch-expand 慢在快照读还是实时展开
            int _snapHit = 0;
            for (Result r : resp.results) if (r != null && r.data != null && "snapshot".equals(r.data.driverPath)) _snapHit++;
            long _phasesMs = (System.nanoTime() - _bp1) / 1_000_000;
            LOG.infof("[be-profile] tasks=%d prefetched=%d snapshotHit=%d realExpand=%d | prefetch=%dms phases=%dms",
                    req.tasks.size(), _preCount, _snapHit, req.tasks.size() - _snapHit, _prefetchMs, _phasesMs);
            return out;
        } finally {
            if (snapBatchActive) SnapshotRowsContext.clear();
        }
    }

    private ApiResponse<BatchExpandDriverResponse> doBatchExpandPhases(
            BatchExpandDriverRequest req, BatchExpandDriverResponse resp,
            boolean bucketEnabled, boolean debugSql) {
        // ── Phase 1:每个 task 先试 snapshot,命中直返;未命中收集进 Phase 2 候选 ──
        List<Integer> phase2 = new ArrayList<>();
        for (int i = 0; i < req.tasks.size(); i++) {
            Task t = req.tasks.get(i);
            Result r = resp.results.get(i);
            try {
                QuotationIdContext.set(t.quotationId);
                try {
                    boolean hasContext = (t.overrideDataDriverPath != null && !t.overrideDataDriverPath.isBlank())
                            || (t.overrideFieldsJson != null && !t.overrideFieldsJson.isBlank())
                            || t.lineItemId != null
                            || t.compositeType != null
                            || (t.childLineItemIds != null && !t.childLineItemIds.isEmpty());
                    if (!bucketEnabled) {
                        // Flag 关 → 维持原逻辑(无 Phase 2)
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
                        continue;
                    }
                    // Flag 开 → Phase 1 仅试 snapshot;hasContext 才有 snapshot 命中机会
                    if (hasContext) {
                        ExpandDriverResponse snap = componentDriverService.expandWithSnapshot(
                            t.componentId, t.customerId, t.partNo, t.partVersion,
                            t.overrideDataDriverPath, t.overrideFieldsJson, t.lineItemId, t.compositeType,
                            t.childLineItemIds);
                        if (snap != null && "snapshot".equals(snap.driverPath)) {
                            r.data = snap;
                            r.status = "OK";
                            continue;
                        }
                    }
                    phase2.add(i);
                } finally {
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
        // bucketKey = componentId|customerId|partVersion|effectiveDriverPath|fieldsHash[|lineItemId 视图含 :lineItemId 时]
        Map<String, List<Integer>> buckets = new LinkedHashMap<>();
        Map<String, String> bucketDriverPath = new HashMap<>();
        for (int idx : phase2) {
            Task t = req.tasks.get(idx);
            String dp = componentDriverService.resolveEffectiveDriverPath(t.componentId, t.overrideDataDriverPath);
            String fieldsTag = t.overrideFieldsJson == null ? "" : Integer.toHexString(t.overrideFieldsJson.hashCode());
            String key = t.componentId + "|" + t.customerId + "|" + t.partVersion + "|" + dp + "|" + fieldsTag;
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
            if (!canMerge) {
                // 不能合 → 桶内逐 task 跑(同原逻辑)
                for (int idx : idxs) {
                    Task t = req.tasks.get(idx);
                    Result r = resp.results.get(idx);
                    runSingleTask(t, r);
                }
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
                Map<String, ExpandDriverResponse> merged;
                try {
                    merged = componentDriverService.expandMulti(
                            pivot.componentId, pivot.customerId, partNos, pivot.partVersion,
                            pivot.overrideDataDriverPath, pivot.overrideFieldsJson);
                } finally {
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
                LOG.infof("[batch-expand bucket-merge] componentId=%s partNos=%d → 1 SQL view exec (省 %d 次)",
                        pivot.componentId, partNos.size(), partNos.size() - 1);
            } catch (Exception ex) {
                LOG.warnf("[batch-expand bucket-merge] bucket=%s 失败,fallback 逐 task: %s", e.getKey(), ex.getMessage());
                for (int idx : idxs) {
                    Task t = req.tasks.get(idx);
                    Result r = resp.results.get(idx);
                    runSingleTask(t, r);
                }
            }
        }
        return ApiResponse.success(resp);
    }

    /** Phase 2 桶不可合时的单 task 跑(同原 batchExpand 逻辑),包 QuotationIdContext 让视图能用 :quotationId。 */
    private void runSingleTask(Task t, Result r) {
        try {
            QuotationIdContext.set(t.quotationId);
            try {
                boolean hasContext = (t.overrideDataDriverPath != null && !t.overrideDataDriverPath.isBlank())
                        || (t.overrideFieldsJson != null && !t.overrideFieldsJson.isBlank())
                        || t.lineItemId != null
                        || t.compositeType != null
                        || (t.childLineItemIds != null && !t.childLineItemIds.isEmpty());
                if (hasContext) {
                    r.data = componentDriverService.expandWithSnapshot(
                        t.componentId, t.customerId, t.partNo, t.partVersion,
                        t.overrideDataDriverPath, t.overrideFieldsJson, t.lineItemId, t.compositeType,
                        t.childLineItemIds);
                } else {
                    r.data = componentDriverService.expand(t.componentId, t.customerId, t.partNo, t.partVersion);
                }
                r.status = "OK";
            } finally {
                QuotationIdContext.clear();
            }
        } catch (Exception e) {
            r.status = "ERROR";
            r.error = e.getMessage();
            LOG.warnf("batch-expand[single] task %s failed: %s", r.key, e.getMessage());
        }
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
