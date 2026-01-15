package com.ticketflow.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import com.ticketflow.util.StringUtil;
import com.ticketflow.dto.AllRuleDto;
import com.ticketflow.dto.DepthRuleDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.RuleStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.vo.AllDepthRuleVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 全量规则聚合服务——组合普通规则+深度规则，构建完整的规则匹配链。
 *
 * 在同一个事务中创建/更新两级规则，
 * 一个规则可以绑定多个深度规则
 */
@Service
public class AllRuleService {
    
    @Autowired
    private RuleService ruleService;
    
    @Autowired
    private DepthRuleService depthRuleService;
    
    @Transactional(rollbackFor = Exception.class)
    public void add(final AllRuleDto allRuleDto) {
        ruleService.add(allRuleDto.getRuleDto());
        depthRuleService.delAll();
        // 逐个添加深度规则，添加前检查时间窗口是否与已有规则重叠（排除自身）
        List<DepthRuleDto> depthRuleDtoList = allRuleDto.getDepthRuleDtoList();
        if (CollUtil.isNotEmpty(depthRuleDtoList)) {
            for (int i = 0; i < depthRuleDtoList.size(); i++) {
                DepthRuleDto depthRuleDto = depthRuleDtoList.get(i);
                checkTime(depthRuleDto.getStartTimeWindow(),depthRuleDto.getEndTimeWindow(),filterDepthRuleDtoList(depthRuleDtoList,i));
                depthRuleService.add(depthRuleDto);
            }
        }
        // 全量规则写入 Redis Hash（删除旧 Hash + 重新 putHash）
        ruleService.saveAllRuleCache();
    }
    
    // 时间窗口重叠检测：检查新规则的 [start, end] 是否与已有 RUN 状态的深度规则区间相交
    public void checkTime(String startTimeWindow, String endTimeWindow, List<DepthRuleDto> depthRuleDtoList){
        if (StringUtil.isEmpty(startTimeWindow) || StringUtil.isEmpty(endTimeWindow)) {
            return;
        }
        // 只检查状态为 RUN（或未设置状态视为启用）的规则
        depthRuleDtoList = depthRuleDtoList.stream().filter(depthRuleDto -> {
            if (depthRuleDto.getStatus() != null) {
                if (depthRuleDto.getStatus().equals(RuleStatus.RUN.getCode())) {
                    return true;
                }else {
                    return false;
                }
            }else {
                return true;
            }
        }).collect(Collectors.toList());
        for (final DepthRuleDto depthRuleDto : depthRuleDtoList) {
            long checkStartTimeWindowTimestamp = getTimeWindowTimestamp(startTimeWindow);
            long checkEndTimeWindowTimestamp = getTimeWindowTimestamp(endTimeWindow);
            long startTimeWindowTimestamp = getTimeWindowTimestamp(depthRuleDto.getStartTimeWindow());
            long endTimeWindowTimestamp = getTimeWindowTimestamp(depthRuleDto.getEndTimeWindow());
            // 区间相交判断：[checkStart, checkEnd] 与 [start, end] 有交集则抛出异常
            boolean checkStartLimitTimeResult = checkStartTimeWindowTimestamp >= startTimeWindowTimestamp && checkStartTimeWindowTimestamp <= endTimeWindowTimestamp;
            boolean checkEndLimitTimeResult = checkEndTimeWindowTimestamp >= startTimeWindowTimestamp && checkEndTimeWindowTimestamp <= endTimeWindowTimestamp;
            if (checkStartLimitTimeResult || checkEndLimitTimeResult) {
                throw new TicketFlowFrameException(BaseCode.API_RULE_TIME_WINDOW_INTERSECT);
            }
        }
    }
    
    public List<DepthRuleDto> filterDepthRuleDtoList(List<DepthRuleDto> depthRuleDtoList, int coord){
        List<DepthRuleDto> fiterDepthRuleDtoList = new ArrayList<>();
        for (int i = 0; i < depthRuleDtoList.size(); i++) {
            if (i != coord) {
                fiterDepthRuleDtoList.add(depthRuleDtoList.get(i));
            }
        }
        return fiterDepthRuleDtoList;
    }
    public long getTimeWindowTimestamp(String timeWindow){
        String today = DateUtil.today();
        return DateUtil.parse(today + " " + timeWindow).getTime();
    }
    
    public AllDepthRuleVo get() {
        AllDepthRuleVo allDepthRuleVo = new AllDepthRuleVo();
        allDepthRuleVo.setRuleVo(ruleService.get());
        allDepthRuleVo.setDepthRuleVoList(depthRuleService.selectList());
        return allDepthRuleVo;
    }
}
