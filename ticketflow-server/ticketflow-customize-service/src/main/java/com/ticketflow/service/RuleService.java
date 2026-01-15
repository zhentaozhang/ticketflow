package com.ticketflow.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.RuleDto;
import com.ticketflow.dto.RuleGetDto;
import com.ticketflow.dto.RuleStatusDto;
import com.ticketflow.dto.RuleUpdateDto;
import com.ticketflow.entity.DepthRule;
import com.ticketflow.entity.Rule;
import com.ticketflow.enums.RuleStatus;
import com.ticketflow.mapper.DepthRuleMapper;
import com.ticketflow.mapper.RuleMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.vo.RuleVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 规则配置服务——管理 API 限流/防刷规则的增删改查。
 *
 * 规则类型包括普通规则和深度规则，
 * 支持启用/停用、排序、Redis 缓存
 */
@Service
public class RuleService {

    @Autowired
    private RuleMapper ruleMapper;
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private DepthRuleMapper depthRuleMapper;
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Transactional(rollbackFor = Exception.class)
    public void ruleAdd(RuleDto ruleDto) {
        add(ruleDto);
        saveAllRuleCache();
    }
    @Transactional(rollbackFor = Exception.class)
    public Long add(RuleDto ruleDto) {
        delAll();
        Rule rule = new Rule();
        BeanUtils.copyProperties(ruleDto,rule);
        rule.setId(uidGenerator.getUid());
        rule.setCreateTime(DateUtil.date());
        ruleMapper.insert(rule);
        return rule.getId();
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void ruleUpdate(final RuleUpdateDto ruleUpdateDto) {
        update(ruleUpdateDto);
        saveAllRuleCache();
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void update(final RuleUpdateDto ruleUpdateDto) {
        Rule rule = new Rule();
        BeanUtils.copyProperties(ruleUpdateDto,rule);
        ruleMapper.updateById(rule);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void ruleUpdateStatus(final RuleStatusDto ruleStatusDto) {
        updateStatus(ruleStatusDto);
        saveAllRuleCache();
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(final RuleStatusDto ruleStatusDto) {
        Rule rule = new Rule();
        rule.setId(ruleStatusDto.getId());
        rule.setStatus(ruleStatusDto.getStatus());
        ruleMapper.updateById(rule);
        saveAllRuleCache();
    }
    
    public RuleVo get(final RuleGetDto ruleGetDto) {
        RuleVo ruleVo = new RuleVo();
        Optional.ofNullable(ruleMapper.selectById(ruleGetDto.getId())).ifPresent(rule -> {
            BeanUtils.copyProperties(rule,ruleVo);
        });
        return ruleVo;
    }
    
    public RuleVo get() {
        RuleVo ruleVo = new RuleVo();
        Optional.ofNullable(ruleMapper.selectOne(null)).ifPresent(rule -> {
            BeanUtils.copyProperties(rule,ruleVo);
        });
        return ruleVo;
    }
    
    public void delAll(){
        ruleMapper.delAll();
    }
    
    
    // 全量刷新规则缓存：先 delete ALL_RULE_HASH，再 putHash 写入（全量替换而非增量更新，确保陈旧规则不会残留）
    public void saveAllRuleCache(){
        Map<String, Object> map = new HashMap<>(2);
        
        // 从 DB 查询当前 RUN 状态的普通规则（最多一条）
        LambdaQueryWrapper<Rule> ruleQueryWrapper = Wrappers.lambdaQuery(Rule.class).eq(Rule::getStatus,RuleStatus.RUN.getCode());
        Rule rule = ruleMapper.selectOne(ruleQueryWrapper);
        if (Optional.ofNullable(rule).isPresent()) {
            map.put(RedisKeyBuild.createRedisKey(RedisKeyManage.RULE).getRelKey(),rule);
        }
        // 从 DB 查询当前 RUN 状态的深度规则列表
        LambdaQueryWrapper<DepthRule> depthRuleQueryWrapper = Wrappers.lambdaQuery(DepthRule.class).eq(DepthRule::getStatus,RuleStatus.RUN.getCode());
        List<DepthRule> depthRules = depthRuleMapper.selectList(depthRuleQueryWrapper);
        if (CollUtil.isNotEmpty(depthRules)) {
            map.put(RedisKeyBuild.createRedisKey(RedisKeyManage.DEPTH_RULE).getRelKey(),depthRules);
        }
        // 全量删除 + 重新写入，避免旧规则未被覆盖导致限流判断异常
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH));
        if (map.size() > 0 && Objects.nonNull(map.get(RedisKeyBuild.createRedisKey(RedisKeyManage.RULE).getRelKey()))) {
            redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH),map);
        }
    }
}
