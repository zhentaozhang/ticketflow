package com.ticketflow.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ticketflow.dto.ShardingMigrationDto;
import com.ticketflow.entity.Order;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.OrderTicketUserMapper;
import com.ticketflow.mapper.OrderTicketUserRecordMapper;
import com.ticketflow.service.ShardingMigrationService.MigrationStatistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2库4表 → 2库8表 扩容迁移逻辑测试。
 * <p>
 * 路由规则（基因法）：表索引 = orderNumber & (tableCount-1)；
 * 库索引 = (orderNumber &gt;&gt; log2(tableCount)) &amp; (dbCount-1)
 * <p>
 * 测试用 orderNumber：
 * - 0    → 旧:db0/t0  新:db0/t0  位置不变（跳过）
 * - 4    → 旧:db1/t0  新:db0/t4  变化
 * - 12   → 旧:db1/t0  新:db1/t4  变化（库不变表变）
 * - 36   → 旧:db1/t0  新:db0/t4  变化（与 4 同目标，应合并同一批）
 */
@ExtendWith(MockitoExtension.class)
class ShardingMigrationServiceTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderTicketUserMapper orderTicketUserMapper;
    @Mock
    private OrderTicketUserRecordMapper orderTicketUserRecordMapper;

    @InjectMocks
    private ShardingMigrationService shardingMigrationService;

    private ShardingMigrationDto dto;

    @BeforeEach
    void setUp() {
        dto = new ShardingMigrationDto();
        dto.setOldDatabaseCount(2);
        dto.setOldTableCount(4);
        dto.setNewDatabaseCount(2);
        dto.setNewTableCount(8);
        dto.setBatchSize(10);

        // 另外两张订单主表返回空批次，避免 selectList 返回 null 触发 NPE
        when(orderTicketUserMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(orderTicketUserRecordMapper.selectList(any())).thenReturn(Collections.emptyList());
    }

    private Order order(long id, long orderNumber) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNumber(orderNumber);
        return order;
    }

    @SafeVarargs
    private final void stubOrderBatches(List<Order>... batches) {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        // 批次耗尽后返回空列表，保证 migrateTableData 的 while 循环能正常终止
        when(orderMapper.selectList(any())).thenAnswer(invocation -> {
            int idx = counter.getAndIncrement();
            return idx < batches.length ? batches[idx] : Collections.emptyList();
        });
    }

    @Test
    void migrate_dryRun_onlyCounts_noWrites() {
        dto.setDryRun(true);
        Order skipped = order(1L, 0L);
        Order moved1 = order(2L, 4L);
        Order moved2 = order(3L, 12L);
        stubOrderBatches(List.of(skipped), List.of(moved1, moved2));

        MigrationStatistics statistics = shardingMigrationService.migrate(dto);

        assertEquals(3, statistics.totalScanned);
        assertEquals(1, statistics.totalSkipped);
        assertEquals(2, statistics.totalMigrated);
        verify(orderMapper, never()).batchInsertIgnore(any());
        verify(orderMapper, never()).physicalDeleteByIds(any());
    }

    @Test
    void migrate_realMode_insertsToTargetAndDeletesFromSource() {
        dto.setDryRun(false);
        Order skipped = order(1L, 0L);
        Order movedA = order(2L, 4L);
        Order movedB = order(4L, 36L);
        Order movedC = order(3L, 12L);
        stubOrderBatches(List.of(skipped), List.of(movedA, movedB, movedC));

        MigrationStatistics statistics = shardingMigrationService.migrate(dto);

        assertEquals(4, statistics.totalScanned);
        assertEquals(1, statistics.totalSkipped);
        assertEquals(3, statistics.totalMigrated);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Order>> insertCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderMapper, times(2)).batchInsertIgnore(insertCaptor.capture());
        List<List<Order>> insertedBatches = insertCaptor.getAllValues();
        // 相同目标（db0/t4）的 movedA、movedB 合并为一批，movedC（db1/t4）单独一批
        Set<Integer> batchSizes = insertedBatches.stream().map(List::size).collect(Collectors.toSet());
        assertEquals(Set.of(1, 2), batchSizes);
        Set<Long> insertedIds = new HashSet<>();
        insertedBatches.forEach(batch -> batch.forEach(o -> insertedIds.add(o.getId())));
        assertEquals(Set.of(2L, 4L, 3L), insertedIds);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> deleteCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderMapper, times(2)).physicalDeleteByIds(deleteCaptor.capture());
        Set<Long> deletedIds = new HashSet<>();
        deleteCaptor.getAllValues().forEach(deletedIds::addAll);
        assertEquals(Set.of(2L, 4L, 3L), deletedIds);
    }

    @Test
    void migrate_batches_advanceCursorWithLastId() {
        dto.setDryRun(true);
        Order first1 = order(1L, 0L);
        Order first2 = order(2L, 4L);
        Order second = order(3L, 12L);
        stubOrderBatches(List.of(first1, first2), List.of(second));

        MigrationStatistics statistics = shardingMigrationService.migrate(dto);

        assertEquals(3, statistics.totalScanned);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<Order>> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(orderMapper, atLeast(2)).selectList(wrapperCaptor.capture());
        List<QueryWrapper<Order>> wrappers = wrapperCaptor.getAllValues();

        // 第一批：lastId=0，不带 gt 条件
        String firstSegment = wrappers.get(0).getCustomSqlSegment();
        assertFalse(firstSegment.contains(">"));

        // 第二批：lastId=2，带 gt(id, 2)
        String secondSegment = wrappers.get(1).getCustomSqlSegment();
        assertTrue(secondSegment.contains("id"));
        assertTrue(secondSegment.contains(">"));
        assertTrue(wrappers.get(1).getParamNameValuePairs().containsValue(2L));
    }
}
