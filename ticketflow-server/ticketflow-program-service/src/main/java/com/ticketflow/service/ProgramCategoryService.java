package com.ticketflow.service;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.ParentProgramCategoryDto;
import com.ticketflow.dto.ProgramCategoryAddDto;
import com.ticketflow.dto.ProgramCategoryDto;
import com.ticketflow.entity.ProgramCategory;
import com.ticketflow.mapper.ProgramCategoryMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.annotion.ServiceLock;
import com.ticketflow.vo.ProgramCategoryVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.ticketflow.core.DistributedLockConstants.PROGRAM_CATEGORY_LOCK;

/**
 * 节目分类服务。
 * 管理父子分类层级，缓存到 Redis 供首页导航使用。
 * 修改时 @ServiceLock 保证分类数据的一致性
 */
@Service
public class ProgramCategoryService extends ServiceImpl<ProgramCategoryMapper, ProgramCategory> {

    @Autowired
    private ProgramCategoryMapper programCategoryMapper;

    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private RedisCache redisCache;

    /**
     * 查询所有节目分类。
     *
     * @return 节目分类 Vo 列表
     */
    public List<ProgramCategoryVo> selectAll() {
        QueryWrapper<ProgramCategory> lambdaQueryWrapper = Wrappers.emptyWrapper();
        List<ProgramCategory> programCategoryList = programCategoryMapper.selectList(lambdaQueryWrapper);
        return BeanUtil.copyToList(programCategoryList, ProgramCategoryVo.class);
    }

    /**
     * 按类型查询节目分类。
     *
     * @param programCategoryDto 分类查询参数
     * @return 节目分类 Vo 列表
     */
    public List<ProgramCategoryVo> selectByType(ProgramCategoryDto programCategoryDto) {
        LambdaQueryWrapper<ProgramCategory> lambdaQueryWrapper = Wrappers.lambdaQuery(ProgramCategory.class)
                .eq(ProgramCategory::getType, programCategoryDto.getType());
        List<ProgramCategory> programCategories = programCategoryMapper.selectList(lambdaQueryWrapper);
        return BeanUtil.copyToList(programCategories, ProgramCategoryVo.class);
    }

    /**
     * 按父分类 ID 查询子分类列表。
     *
     * @param parentProgramCategoryDto 父分类查询参数
     * @return 子分类 Vo 列表
     */
    public List<ProgramCategoryVo> selectByParentProgramCategoryId(ParentProgramCategoryDto parentProgramCategoryDto) {
        LambdaQueryWrapper<ProgramCategory> lambdaQueryWrapper = Wrappers.lambdaQuery(ProgramCategory.class)
                .eq(ProgramCategory::getParentId, parentProgramCategoryDto.getParentProgramCategoryId());
        List<ProgramCategory> programCategories = programCategoryMapper.selectList(lambdaQueryWrapper);
        return BeanUtil.copyToList(programCategories, ProgramCategoryVo.class);
    }

    /**
     * 批量保存节目分类（写锁保护）。
     * 写入 DB 后同步更新 Redis Hash，供首页导航快速读取。
     *
     * @param programCategoryAddDtoList 分类新增参数列表
     */
    @Transactional(rollbackFor = Exception.class)
    @ServiceLock(lockType = LockType.Write, name = PROGRAM_CATEGORY_LOCK, keys = {"all"})
    public void saveBatch(final List<ProgramCategoryAddDto> programCategoryAddDtoList) {
        List<ProgramCategory> programCategoryList = programCategoryAddDtoList.stream().map((programCategoryAddDto) -> {
            ProgramCategory programCategory = new ProgramCategory();
            BeanUtil.copyProperties(programCategoryAddDto, programCategory);
            programCategory.setId(uidGenerator.getUid());
            return programCategory;
        }).collect(Collectors.toList());

        if (CollectionUtil.isNotEmpty(programCategoryList)) {
            this.saveBatch(programCategoryList);
            // 写入 Redis Hash 供首页导航快速读取（Key=分类ID，Value=分类对象）
            Map<String, ProgramCategory> programCategoryMap = programCategoryList.stream().collect(
                    Collectors.toMap(p -> String.valueOf(p.getId()), p -> p, (v1, v2) -> v2));
            redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_CATEGORY_HASH), programCategoryMap);
        }

    }

    /**
     * 从 Redis Hash 获取单个分类。
     * 缓存未命中时触发全量初始化回填。
     *
     * @param programCategoryId 分类 ID
     * @return 节目分类实体
     */
    public ProgramCategory getProgramCategory(Long programCategoryId) {
        // 从 Redis Hash 中获取单个分类，缓存未命中时全量初始化回填
        ProgramCategory programCategory = redisCache.getForHash(RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_CATEGORY_HASH), String.valueOf(programCategoryId), ProgramCategory.class);
        if (Objects.isNull(programCategory)) {
            Map<String, ProgramCategory> programCategoryMap = programCategoryRedisDataInit();
            return programCategoryMap.get(String.valueOf(programCategoryId));
        }
        return programCategory;
    }

    /**
     * 全量初始化分类数据到 Redis Hash。
     * 写锁防止并发重复加载导致缓存不一致。
     *
     * @return 分类 ID 到实体的 Map
     */
    @ServiceLock(lockType = LockType.Write, name = PROGRAM_CATEGORY_LOCK, keys = {"#all"})
    public Map<String, ProgramCategory> programCategoryRedisDataInit() {
        // 写锁保护：全量从 DB 加载分类数据写入 Redis Hash（避免并发重复加载导致缓存不一致）
        Map<String, ProgramCategory> programCategoryMap = new HashMap<>(64);
        QueryWrapper<ProgramCategory> lambdaQueryWrapper = Wrappers.emptyWrapper();
        List<ProgramCategory> programCategoryList = programCategoryMapper.selectList(lambdaQueryWrapper);
        if (CollectionUtil.isNotEmpty(programCategoryList)) {
            programCategoryMap = programCategoryList.stream().collect(
                    Collectors.toMap(p -> String.valueOf(p.getId()), p -> p, (v1, v2) -> v2));
            redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_CATEGORY_HASH), programCategoryMap);
        }
        return programCategoryMap;
    }
}
