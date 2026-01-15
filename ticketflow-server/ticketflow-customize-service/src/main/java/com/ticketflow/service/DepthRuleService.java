package com.ticketflow.service;

import cn.hutool.core.date.DateUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.dto.DepthRuleDto;
import com.ticketflow.dto.DepthRuleStatusDto;
import com.ticketflow.dto.DepthRuleUpdateDto;
import com.ticketflow.entity.DepthRule;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.RuleStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.DepthRuleMapper;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.StringUtil;
import com.ticketflow.vo.DepthRuleVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 深度规则服务——管理细粒度 API 流量规则。
 *
 * 深度规则依附于普通规则，
 * 定义更精确的匹配条件（路径、参数、频率等）
 */
@Service
public class DepthRuleService {

    @Autowired
    private DepthRuleMapper depthRuleMapper;
    
    @Autowired
    private RuleService ruleService;
    @Autowired
    private UidGenerator uidGenerator;
    
    @Transactional(rollbackFor = Exception.class)
    public void depthRuleAdd(DepthRuleDto depthRuleDto) {
        check(depthRuleDto.getStartTimeWindow(),depthRuleDto.getEndTimeWindow());
        add(depthRuleDto);
        ruleService.saveAllRuleCache();
    }
    
    // 新增规则的时间窗口不能与任何已启用规则重叠（与 AllRuleService.checkTime 逻辑一致，但只查 DB 已存在的规则）
    public void check(String startTimeWindow, String endTimeWindow){
        if (StringUtil.isEmpty(startTimeWindow) || StringUtil.isEmpty(endTimeWindow)) {
            return;
        }
        LambdaQueryWrapper<DepthRule> queryWrapper = Wrappers.lambdaQuery(DepthRule.class).eq(DepthRule::getStatus, RuleStatus.RUN.getCode());
        List<DepthRule> depthRules = depthRuleMapper.selectList(queryWrapper);
        for (final DepthRule depthRule : depthRules) {
            long checkStartTimeWindowTimestamp = getTimeWindowTimestamp(startTimeWindow);
            long checkEndTimeWindowTimestamp = getTimeWindowTimestamp(endTimeWindow);
            long startTimeWindowTimestamp = getTimeWindowTimestamp(depthRule.getStartTimeWindow());
            long endTimeWindowTimestamp = getTimeWindowTimestamp(depthRule.getEndTimeWindow());
            // 区间相交判断：新规则的起点或终点落在已有规则区间内则冲突
            boolean checkStartLimitTimeResult = checkStartTimeWindowTimestamp >= startTimeWindowTimestamp && checkStartTimeWindowTimestamp <= endTimeWindowTimestamp;
            boolean checkEndLimitTimeResult = checkEndTimeWindowTimestamp >= startTimeWindowTimestamp && checkEndTimeWindowTimestamp <= endTimeWindowTimestamp;
            if (checkStartLimitTimeResult || checkEndLimitTimeResult) {
                throw new TicketFlowFrameException(BaseCode.API_RULE_TIME_WINDOW_INTERSECT);
            }
        }
    }
    
    public long getTimeWindowTimestamp(String timeWindow){
        String today = DateUtil.today();
        return DateUtil.parse(today + " " + timeWindow).getTime();
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void add(DepthRuleDto depthRuleDto) {
        DepthRule depthRule = new DepthRule();
        BeanUtils.copyProperties(depthRuleDto,depthRule);
        depthRule.setId(uidGenerator.getUid());
        depthRule.setCreateTime(DateUtils.now());
        depthRuleMapper.insert(depthRule);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void depthRuleUpdate(final DepthRuleUpdateDto depthRuleUpdateDto) {
        update(depthRuleUpdateDto);
        ruleService.saveAllRuleCache();
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void update(final DepthRuleUpdateDto depthRuleUpdateDto) {
        DepthRule depthRule = new DepthRule();
        BeanUtils.copyProperties(depthRuleUpdateDto,depthRule);
        depthRuleMapper.updateById(depthRule);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void depthRuleUpdateStatus(final DepthRuleStatusDto depthRuleStatusDto) {
        updateStatus(depthRuleStatusDto);
        ruleService.saveAllRuleCache();
    }
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(final DepthRuleStatusDto depthRuleStatusDto) {
        DepthRule depthRule = new DepthRule();
        depthRule.setId(depthRuleStatusDto.getId());
        depthRule.setStatus(depthRuleStatusDto.getStatus());
        depthRuleMapper.updateById(depthRule);
    }
    
    public List<DepthRuleVo> selectList() {
        List<DepthRule> depthRules = depthRuleMapper.selectList(null);
        List<DepthRuleVo> depthRuleVos = depthRules.stream().map(depthRule -> {
            DepthRuleVo depthRuleVo = new DepthRuleVo();
            BeanUtils.copyProperties(depthRule, depthRuleVo);
            return depthRuleVo;
        }).collect(Collectors.toList());
        return depthRuleVos;
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void delAll(){
        depthRuleMapper.delAll();
    }
}
