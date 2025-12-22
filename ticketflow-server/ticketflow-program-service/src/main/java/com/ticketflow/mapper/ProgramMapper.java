package com.ticketflow.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ticketflow.dto.ProgramListDto;
import com.ticketflow.dto.ProgramPageListDto;
import com.ticketflow.entity.Program;
import com.ticketflow.entity.ProgramJoinShowTime;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 节目表 Mapper 接口。
 *
 * extends BaseMapper<Program>：
 *   MyBatis Plus 内置了 insert/deleteById/updateById/selectById/selectList 等方法，
 *   这里不用写任何代码就拥有了这些基础 CRUD。
 *
 * 额外自定义了两个查询方法（因为逻辑太复杂，内置方法搞不定）：
 *   - selectHomeList：主页分类列表，每个分类取前 7 条，用 union all 拼接
 *   - selectPage：分页查询，left join 演出时间表，支持按分类/地区/日期筛选 + 排序
 *   它们的 SQL 写在 resources/mapper/ProgramMapper.xml 里。
 */
public interface ProgramMapper extends BaseMapper<Program> {

    /**
     * 主页每个分类取前 7 条节目。
     * 循环 parentProgramCategoryIds，每条用 union all 连起来。
     * ES 查不到时才走这里（DB 兜底）。
     *
     * @param programListDto 里面装着 parentProgramCategoryIds 和 areaId
     * @return 节目列表
     */
    List<Program> selectHomeList(@Param("programListDto") ProgramListDto programListDto);

    /**
     * 分页查询节目列表（含演出时间）。
     * 连表：d_program LEFT JOIN d_program_show_time。
     * 支持按 areaId、programCategoryId、parentProgramCategoryId、日期范围过滤。
     * 支持按热度/开场时间/上架时间排序。
     *
     * @param page               MyBatis Plus 分页对象，自动帮你拼 COUNT + LIMIT
     * @param programPageListDto 筛选条件（地区、分类、日期范围、排序方式）
     * @return 分页结果（带 total 和 records）
     */
    IPage<ProgramJoinShowTime> selectPage(IPage<ProgramJoinShowTime> page,
                                          @Param("programPageListDto") ProgramPageListDto programPageListDto);
}
