package com.ticketflow.initialize.impl.composite;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * 组合模式节点抽象——可嵌套的校验/处理单元。
 * <p>
 * 每个节点有 4 个属性决定其在树中的位置：
 * type()             → 属于哪个校验链（与 CompositeCheckType 枚举对应）
 * executeTier()      → 层级（数字越小越靠前执行）
 * executeOrder()     → 同层级内的执行顺序
 * executeParentOrder() → 挂在哪个父节点下（0 = 根节点）
 * <p>
 * allExecute() 使用 BFS 按层遍历整棵树，逐层执行每个节点的 execute()。
 * 子节点通过 add() 挂载到父节点的 list 中。
 **/
public abstract class AbstractComposite<T> {

    /**
     * 存储子节点的列表
     *
     */
    protected List<AbstractComposite<T>> list = new ArrayList<>();

    /**
     * 执行具体业务的抽象方法，由子类具体实现。
     *
     * @param param 泛型参数，用于业务执行。
     */
    protected abstract void execute(T param);

    /**
     * 获取返回组件的类型
     *
     * @return 返回组件的类型。
     */
    public abstract String type();

    /**
     * 返回父级执行顺序，用于建立层级关系.(根节点的话返回值为0)
     *
     * @return 返回父级执行顺序，用于建立层级关系.(根节点的话返回值为0)
     */
    public abstract Integer executeParentOrder();

    /**
     * 返回组件的执行层级
     *
     * @return 返回组件的执行层级
     */
    public abstract Integer executeTier();

    /**
     * 返回组件在同一层级中的执行顺序
     *
     * @return 返回组件在同一层级中的执行顺序
     */
    public abstract Integer executeOrder();

    /**
     * 将子组件添加到当前组件的子列表中
     *
     * @param abstractComposite 子组件实例
     */
    public void add(AbstractComposite<T> abstractComposite) {
        list.add(abstractComposite);
    }

    /**
     * 按层次结构执行每个组件的业务逻辑
     *
     * @param param 泛型参数，用于业务执行
     */
    public void allExecute(T param) {
        Queue<AbstractComposite<T>> queue = new LinkedList<>();

        queue.add(this);

        while (!queue.isEmpty()) {

            int levelSize = queue.size();

            for (int i = 0; i < levelSize; i++) {

                AbstractComposite<T> current = queue.poll();


                assert current != null;
                current.execute(param);

                queue.addAll(current.list);
            }
        }
    }
}
