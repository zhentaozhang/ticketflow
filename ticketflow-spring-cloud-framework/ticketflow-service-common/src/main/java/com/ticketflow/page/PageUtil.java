package com.ticketflow.page;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketflow.dto.BasePageDto;
import com.github.pagehelper.PageInfo;

import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通用分页转换工具。
 * 将 MyBatis-Plus IPage 转换为标准 PageVo 返回给前端
 */
public class PageUtil {

    /**
     * 组装分页参数
     *
     */
    public static <T> IPage<T> getPageParams(BasePageDto basePageDto) {
        return getPageParams(basePageDto.getPageNumber(), basePageDto.getPageSize());
    }

    /**
     * 组装分页参数
     *
     */
    public static <T> IPage<T> getPageParams(int pageNumber, int pageSize) {
        return new Page<>(pageNumber, pageSize);
    }

    /**
     * 转换分页对象
     *
     * @param pageInfo PageInfo类型的分页对象
     * @param function 分页中的数据加工接口
     * @param <OLD>    旧数据实体类型
     * @param <NEW>    新数据实体类型
     * 把一个分页对象 PageInfo<OLD> 转换成另一个分页对象 PageVo<NEW>，同时把分页列表中的每个 OLD 类型对象转换成 NEW 类型对象。
     */
    public static <OLD, NEW> PageVo<NEW> convertPage(PageInfo<OLD> pageInfo, Function<? super OLD, ? extends NEW> function) {
        return new PageVo<>(pageInfo.getPageNum(),
                pageInfo.getPageSize(),
                pageInfo.getTotal(),
                pageInfo.getList().stream().map(function).collect(Collectors.toList()));
    }

    /**
     * 转换分页对象
     *
     * @param iPage    IPage类型的分页对象
     * @param function 分页中的数据加工接口
     * @param <OLD>    旧数据实体类型
     * @param <NEW>    新数据实体类型
     *
     */
    public static <OLD, NEW> PageVo<NEW> convertPage(IPage<OLD> iPage, Function<? super OLD, ? extends NEW> function) {
        return new PageVo<>(iPage.getCurrent(),
                iPage.getSize(),
                iPage.getTotal(),
                iPage.getRecords().stream().map(function).collect(Collectors.toList()));
    }
}