package com.cpq.semanticgraph.service;

import com.cpq.semanticgraph.entity.*;
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
                SemanticNodeColumn.listAll(),
                SemanticEdge.listAll(),
                SemanticEdgeKey.listAll(),
                SemanticTabView.listAll(),
                SemanticTabViewNode.listAll(),
                SemanticTabViewColumn.listAll()
        );
    }
}
