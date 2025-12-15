package com.ticketflow.service;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.entity.UserMobile;
import com.ticketflow.mapper.UserMobileMapper;
import org.springframework.stereotype.Service;

/**
 * 用户手机号关联服务——UserMobile 表的 MyBatis-Plus Service。
 *
 * 保存用户 ID 与手机号的绑定关系
 */
@Service
public class UserMobileService extends ServiceImpl<UserMobileMapper, UserMobile> {

}
