package com.cpq.semanticgraph.service;

import com.cpq.semanticgraph.entity.*;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 语义图加载器（task-260819 B-2）。
 *
 * <p>启动时全量加载为不可变内存图；语义图写端点保存成功后调用 {@link #reload()}
 * **整体换引用**，不原地改——满足 AC-57「热生效 + 并发安全」：
 * 并发预览读到的永远是某一个完整快照（旧的或新的），不会出现半新半旧的混合结果。
 *
 * <p>N+1 自检：{@link #loadSnapshot()} 内 7 次 {@code listAll()} 调用（每张语义图表各一次），
 * 与图中节点/边/页签数量无关，是常数条 SQL。
 */
@ApplicationScoped
public class SemanticGraphLoader {

    private static final Logger LOG = Logger.getLogger(SemanticGraphLoader.class);

    private final AtomicReference<SemanticGraphSnapshot> current = new AtomicReference<>();

    void onStart(@Observes StartupEvent ev) {
        reload();
    }

    /** 当前不可变快照。调用方（编译器/端点）拿到的引用在快照生命周期内恒定，无需加锁读。 */
    public SemanticGraphSnapshot get() {
        SemanticGraphSnapshot snap = current.get();
        if (snap == null) {
            // 极端情况下（如测试环境未触发 StartupEvent）兜底同步加载一次。
            snap = loadSnapshot();
            current.set(snap);
        }
        return snap;
    }

    /** 保存成功后调用：重新查库并整体换引用。不重启即生效（AC-57①）。 */
    public synchronized SemanticGraphSnapshot reload() {
        SemanticGraphSnapshot next = loadSnapshot();
        current.set(next);
        LOG.infof("Semantic graph reloaded: version=%d nodes=%d edges=%d tabViews=%d",
                next.version, next.nodes.size(), next.edges.size(), next.tabViews.size());
        return next;
    }

    private SemanticGraphSnapshot loadSnapshot() {
        int prevVersion = current.get() == null ? 0 : current.get().version;
        // 7 次查询 = 7 张语义图表，与表内行数无关，常数条 SQL（N+1 自检见类注释）。
        return new SemanticGraphSnapshot(
                prevVersion + 1,
                SemanticNode.listAll(),
                // 🔴 repair-260908（AC-R9/R10/R11）：**必须带 ORDER BY**。
                //
                // 【症状】改一行 roles 的迁移，会把 11 个核价侧数据源在取数配置器里的
                //   字段顺序打乱（实测 V433 后「工序编号/使用特性」被顶到 MATERIAL_BOM 最前）。
                //
                // 【根因链】listAll() 无 ORDER BY ⇒ 走全表顺序扫描 ⇒ 拿到的是 **PostgreSQL 堆顺序**；
                //   SemanticGraphSnapshot 的 columnsByNode 只做 groupingBy(nodeId)、不排序；
                //   FieldTreeBuilder / SemanticCompiler 直接遍历 columnsOf(nodeId) 出字段。
                //   而 MVCC 下 UPDATE 会把行的新版本写到有空闲空间的**别的页**
                //   （实测 V433 改过的 tooling_no 从第 7 页跑到第 2 页，ctid (7,18)→(2,16)），
                //   ⇒ **任何对 semantic_node_column 的 UPDATE 都会静默重排该节点的字段顺序**。
                //   前端 SqlViewBuilderTab.tsx 是 g.fields.map() 原样渲染、不重排 ⇒ 用户直接可见。
                //
                // 【为什么修在这一层而不是 FieldTreeBuilder】SemanticCompiler 里有四处
                //   「对无序列表取 findFirst」——pickColumnByRole / resolveTreeChildColumn /
                //   findSortColumn / filter(!isCode).findFirst()。它们的选择结果同样由堆顺序决定，
                //   只在字段面板那一侧排序治不到。**在加载器给出确定序，四处一并受益。**
                //
                // 🚫 排序键写的是**实体字段名** nodeId / sortOrder（Panache 生成 JPQL ORDER BY），
                //    不是列名 node_id / sort_order —— 写成列名会在运行期抛 SemanticException。
                //    nodeId 打头是为了让 groupingBy 之后每个节点内部仍是 sortOrder 升序
                //    （groupingBy 保序，但只在同 key 内有意义）。
                SemanticNodeColumn.listAll(Sort.by("nodeId").and("sortOrder")),
                SemanticEdge.listAll(),
                SemanticEdgeKey.listAll(),
                SemanticTabView.listAll(),
                SemanticTabViewNode.listAll(),
                SemanticTabViewColumn.listAll()
        );
    }
}
