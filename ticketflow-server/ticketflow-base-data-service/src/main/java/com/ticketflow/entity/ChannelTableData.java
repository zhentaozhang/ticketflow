package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serializable;

/**
 * 渠道。第三方渠道商配置，
 * 包含签名密钥(RSA/AES)和Token密钥，用于渠道接入认证。
 * 数据表: d_channel_data
 */
@Data
@TableName("d_channel_data")
public class ChannelTableData extends BaseTableData implements Serializable {
    /**
     * id
     *
     */
    private Long id;

    /**
     * 名称
     *
     */
    private String name;

    /**
     * 编码
     *
     */
    private String code;

    /**
     * 介绍描述
     *
     */
    private String introduce;

    /**
     * rsa签名公钥
     *
     */
    private String signPublicKey;

    /**
     * rsa签名秘钥
     *
     */
    private String signSecretKey;

    /**
     * aes秘钥
     *
     */
    private String aesKey;

    /**
     * rsa参数公钥
     *
     */
    private String dataPublicKey;

    /**
     * rsa参数私钥
     *
     */
    private String dataSecretKey;

    /**
     * token秘钥
     *
     */
    private String tokenSecret;

}