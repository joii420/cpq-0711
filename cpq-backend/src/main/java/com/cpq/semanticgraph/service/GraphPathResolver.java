package com.cpq.semanticgraph.service;

import com.cpq.semanticgraph.entity.SemanticEdge;

import java.util.*;

/**
 * 语义图路径求解（task-260819 B-3 / B-5 共用）。
 *
 * <p>支持任意跳数；从锚点出发，若到某目标节点存在 &ge;2 条不同的简单路径，
 * 编译器/保存期校验都不猜——分别对应 AC-10（编译期报错）与 AC-55（保存期拒绝）。
 *
 * <p>N+1 自检：全部在传入的不可变 {@link SemanticGraphSnapshot} 内存索引上做 DFS，
 * 不查库；单次调用的图遍历规模由节点/边总数（常数级，17/22）界定，与调用方业务数据量无关。
 */
public final class GraphPathResolver {

    private GraphPathResolver() {}

    /** 一条路径：按经过顺序排列的节点 key 列表（含起点与终点）。 */
    public static final class Path {
        public final List<UUID> nodeIds;
        public final List<SemanticEdge> edges;
        Path(List<UUID> nodeIds, List<SemanticEdge> edges) {
            this.nodeIds = nodeIds;
            this.edges = edges;
        }
    }

    /**
     * 找出从 {@code fromNodeId} 到 {@code toNodeId} 的全部简单路径（不重复经过同一节点）。
     * 图规模是常数级（17 节点/22 边），穷举 DFS 足够快，不需要更复杂的算法。
     */
    public static List<Path> findAllPaths(SemanticGraphSnapshot snap, UUID fromNodeId, UUID toNodeId) {
        List<Path> result = new ArrayList<>();
        Deque<UUID> stack = new ArrayDeque<>();
        Deque<SemanticEdge> edgeStack = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        stack.push(fromNodeId);
        visited.add(fromNodeId);
        dfs(snap, fromNodeId, toNodeId, stack, edgeStack, visited, result);
        return result;
    }

    private static void dfs(SemanticGraphSnapshot snap, UUID current, UUID target,
                             Deque<UUID> stack, Deque<SemanticEdge> edgeStack,
                             Set<UUID> visited, List<Path> result) {
        if (current.equals(target) && stack.size() > 1) {
            result.add(new Path(new ArrayList<>(reversedList(stack)), new ArrayList<>(reversedList(edgeStack))));
            return;
        }
        for (SemanticEdge e : snap.edgesFrom(current)) {
            UUID next = e.toNodeId;
            if (visited.contains(next)) continue; // 简单路径，不走回头路
            visited.add(next);
            stack.push(next);
            edgeStack.push(e);
            dfs(snap, next, target, stack, edgeStack, visited, result);
            edgeStack.pop();
            stack.pop();
            visited.remove(next);
        }
    }

    private static <T> List<T> reversedList(Deque<T> deque) {
        List<T> list = new ArrayList<>(deque);
        Collections.reverse(list);
        return list;
    }

    /** 是否存在歧义（&ge;2 条不同路径）。 */
    public static boolean isAmbiguous(SemanticGraphSnapshot snap, UUID fromNodeId, UUID toNodeId) {
        return findAllPaths(snap, fromNodeId, toNodeId).size() >= 2;
    }
}
