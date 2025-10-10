package com.ticketflow.initialize.impl.composite;

import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 组合（Composite）模式容器——分层编排校验/业务处理链。
 * <p>
 * 将实现了 AbstractComposite 的 Bean 按 type() 分组后，按层级（tier）和
 * 顺序（order）构建成树。调用 execute(type, param) 时按 BFS 逐层执行所有节点：
 * <p>
 * CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK 的校验链示例：
 * Tier 1: ProgramBloomFilterCheckHandler  (布隆过滤器 → 节目是否存在)
 * Tier 2: ProgramRecommendCheckHandler    (推荐校验)
 * Tier 3: ...                             (更多校验)
 * <p>
 * 每个节点的 executeParentOrder() 决定挂在哪个父节点下，
 * executeTier() 决定层级，executeOrder() 决定同层执行顺序。
 **/
public class CompositeContainer<T> {
    // 存储所有校验树：key=type, value=树的根节点
    private final Map<String, AbstractComposite> allCompositeInterfaceMap = new HashMap<>();

    // ── 启动时调用：扫描所有 Bean，按 type 分组，各组建树 ──
    public void init(ConfigurableApplicationContext applicationEvent) {
        Map<String, AbstractComposite> compositeInterfaceMap = applicationEvent.getBeansOfType(AbstractComposite.class);

        Map<String, List<AbstractComposite>> collect = compositeInterfaceMap.values().stream().collect(Collectors.groupingBy(AbstractComposite::type));
        collect.forEach((k, v) -> {
            AbstractComposite root = build(v);
            if (Objects.nonNull(root)) {
                allCompositeInterfaceMap.put(k, root);
            }
        });
    }

    // ── 运行时调用：按 type 找到树，BFS 执行 ──
    public void execute(String type, T param) {
        AbstractComposite compositeInterface = Optional.ofNullable(allCompositeInterfaceMap.get(type))
                .orElseThrow(() -> new TicketFlowFrameException(BaseCode.COMPOSITE_NOT_EXIST));
        // BFS 遍历
        compositeInterface.allExecute(param);
    }

    /**
     * 构建组件树的辅助方法。
     *
     * @param groupedByTier 按层级组织的组件映射。
     * @param currentTier   当前处理的层级。
     */
    private static void buildTree(Map<Integer, Map<Integer, AbstractComposite>> groupedByTier, int currentTier) {
        Map<Integer, AbstractComposite> currentLevelComponents = groupedByTier.get(currentTier);
        Map<Integer, AbstractComposite> nextLevelComponents = groupedByTier.get(currentTier + 1);

        if (currentLevelComponents == null) {
            return;
        }

        if (nextLevelComponents != null) {
            for (AbstractComposite child : nextLevelComponents.values()) {
                Integer parentOrder = child.executeParentOrder();
                if (parentOrder == null || parentOrder == 0) {
                    continue;
                }
                AbstractComposite parent = currentLevelComponents.get(parentOrder);
                if (parent != null) {
                    parent.add(child);
                }
            }
        }
        buildTree(groupedByTier, currentTier + 1);
    }

    /**
     * 根据提供的组件集合构建组件树，并返回根节点。
     *
     * @param components 组件集合。
     * @return 根节点。
     */
    private static AbstractComposite build(Collection<AbstractComposite> components) {
        Map<Integer, Map<Integer, AbstractComposite>> groupedByTier = new TreeMap<>();

        for (AbstractComposite component : components) {
            groupedByTier.computeIfAbsent(component.executeTier(), k -> new HashMap<>(16))
                    .put(component.executeOrder(), component);
        }

        Integer minTier = groupedByTier.keySet().stream().min(Integer::compare).orElse(null);
        if (minTier == null) {
            return null;
        }

        buildTree(groupedByTier, minTier);

        return groupedByTier.get(minTier).values().stream()
                .filter(c -> c.executeParentOrder() == null || c.executeParentOrder() == 0)
                .findFirst()
                .orElse(null);
    }
}
