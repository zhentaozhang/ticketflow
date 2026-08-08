package com.ticketflow.initialize.impl.composite;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractCompositeTest {

    @Test
    void allExecuteShouldTraverseTreeInBreadthFirstOrder() {
        TestNode root = new TestNode("root");
        TestNode childA = new TestNode("A");
        TestNode childB = new TestNode("B");
        TestNode childA1 = new TestNode("A1");
        TestNode childB1 = new TestNode("B1");
        root.add(childA);
        root.add(childB);
        childA.add(childA1);
        childB.add(childB1);
        List<String> executionLog = new ArrayList<>();

        root.allExecute(new ExecutionContext(executionLog));

        assertEquals(List.of("root", "A", "B", "A1", "B1"), executionLog);
    }

    @Test
    void allExecuteShouldExecuteSiblingsInAdditionOrder() {
        TestNode root = new TestNode("root");
        TestNode first = new TestNode("first");
        TestNode second = new TestNode("second");
        TestNode third = new TestNode("third");
        root.add(first);
        root.add(second);
        root.add(third);
        List<String> executionLog = new ArrayList<>();

        root.allExecute(new ExecutionContext(executionLog));

        assertEquals(List.of("root", "first", "second", "third"), executionLog);
    }

    @Test
    void allExecuteShouldExecuteRootOnlyWhenNoChildren() {
        TestNode root = new TestNode("solo");
        List<String> executionLog = new ArrayList<>();

        root.allExecute(new ExecutionContext(executionLog));

        assertEquals(List.of("solo"), executionLog);
    }

    @Test
    void allExecuteShouldTraverseNestedSubtrees() {
        TestNode root = new TestNode("root");
        TestNode child = new TestNode("C");
        TestNode grandChild = new TestNode("GC");
        TestNode greatGrandChild = new TestNode("GGC");
        root.add(child);
        child.add(grandChild);
        grandChild.add(greatGrandChild);
        List<String> executionLog = new ArrayList<>();

        root.allExecute(new ExecutionContext(executionLog));

        assertEquals(List.of("root", "C", "GC", "GGC"), executionLog);
        assertTrue(executionLog.size() == 4);
    }

    static class ExecutionContext {
        final List<String> log;

        ExecutionContext(List<String> log) {
            this.log = log;
        }
    }

    static class TestNode extends AbstractComposite<ExecutionContext> {
        private final String name;

        TestNode(String name) {
            this.name = name;
        }

        @Override
        protected void execute(ExecutionContext param) {
            param.log.add(name);
        }

        @Override
        public String type() {
            return name;
        }

        @Override
        public Integer executeParentOrder() {
            return 0;
        }

        @Override
        public Integer executeTier() {
            return 0;
        }

        @Override
        public Integer executeOrder() {
            return 0;
        }
    }
}
