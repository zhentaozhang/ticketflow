package com.ticketflow.service.tool;

import cn.hutool.core.collection.CollectionUtil;
import com.ticketflow.vo.SeatVo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 座位匹配工具。实现三级降级匹配策略：同行相邻座位 → 同列相邻座位 → 同票价相邻座位。
 */
public class SeatMatch {
    
    /**
     * 三级降级匹配：
     * 1. 同行相邻座位（相邻列）
     * 2. 同列相邻座位（相邻行）
     * 3. 随机分配
     *
     * @param allSeats 可选座位（已按票档过滤）
     * @param seatCount 需要的座位数
     * @return 匹配到的座位列表
     * @throws RuntimeException 如果找不到满足条件的座位
     */
    public static List<SeatVo> findAdjacentSeatVos(List<SeatVo> allSeats, int seatCount) {
        if (CollectionUtil.isEmpty(allSeats)) {
            throw new RuntimeException("没有可用的座位");
        }
        if (seatCount <= 0) {
            throw new IllegalArgumentException("seatCount 必须大于 0");
        }
        
        // 第一级：同行相邻座位 — 按行分组，同排按列排序，滑动窗口找连续列
        Map<Integer, List<SeatVo>> rowMap = allSeats.stream()
                .collect(Collectors.groupingBy(SeatVo::getRowCode));
        rowMap.values().forEach(row -> row.sort(Comparator.comparingInt(SeatVo::getColCode)));
        
        for (int row : rowMap.keySet().stream().sorted().toList()) {
            List<SeatVo> rowSeats = rowMap.get(row);
            List<SeatVo> result = findConsecutiveSeats(rowSeats, seatCount);
            if (!result.isEmpty()) {
                return result;
            }
        }
        
        // 第二级：同列相邻座位 — 按列分组，同列按行排序，找连续行
        List<SeatVo> sameColResult = findSameColumnSeats(allSeats, seatCount);
        if (!sameColResult.isEmpty()) {
            return sameColResult;
        }
        
        // 第三级：随机分配 — 打乱后取前 seatCount 个
        if (allSeats.size() >= seatCount) {
            List<SeatVo> shuffled = new ArrayList<>(allSeats);
            Collections.shuffle(shuffled);
            return shuffled.subList(0, seatCount);
        }
        
        throw new RuntimeException("没有足够的座位可供分配");
    }
    
    // 同行相邻列检测：滑动窗口，检查窗口内相邻座位 colCode 差 == 1
    private static List<SeatVo> findConsecutiveSeats(List<SeatVo> rowSeats, int seatCount) {
        for (int i = 0; i <= rowSeats.size() - seatCount; i++) {
            boolean ok = true;
            for (int j = 1; j < seatCount; j++) {
                if (rowSeats.get(i + j).getColCode() - rowSeats.get(i + j - 1).getColCode() != 1) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return rowSeats.subList(i, i + seatCount);
            }
        }
        return Collections.emptyList();
    }
    
    // 同列相邻行检测：按列分组，检查窗口内首尾 rowCode 差 == seatCount - 1（即整段连续）
    private static List<SeatVo> findSameColumnSeats(List<SeatVo> allSeats, int seatCount) {
        Map<Integer, List<SeatVo>> colMap = allSeats.stream()
                .collect(Collectors.groupingBy(SeatVo::getColCode));
        colMap.values().forEach(col -> col.sort(Comparator.comparingInt(SeatVo::getRowCode)));
        
        for (int col : colMap.keySet()) {
            List<SeatVo> colSeats = colMap.get(col);
            for (int i = 0; i <= colSeats.size() - seatCount; i++) {
                // 跨度判断：窗口内最远两行 rowCode 差 == seatCount - 1 即连续无缝隙
                if (colSeats.get(i + seatCount - 1).getRowCode() - colSeats.get(i).getRowCode() == seatCount - 1) {
                    return colSeats.subList(i, i + seatCount);
                }
            }
        }
        return Collections.emptyList();
    }
}
