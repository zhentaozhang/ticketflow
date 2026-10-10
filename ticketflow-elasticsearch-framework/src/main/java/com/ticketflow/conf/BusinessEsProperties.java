package com.ticketflow.conf;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ES连接配置。读取 elasticsearch.* 前缀的配置项，包含ES集群连接信息和认证参数。
 */
@Data
@ConfigurationProperties(prefix = BusinessEsProperties.PREFIX)
public class BusinessEsProperties {

    public static final String PREFIX = "elasticsearch";

    private String[] ip;

    private String userName;

    private String passWord;

    private Boolean esSwitch = true;

    private Boolean esTypeSwitch = false;

    private Integer connectTimeOut = 40000;

    private Integer socketTimeOut = 40000;

    private Integer connectionRequestTimeOut = 40000;

    private Integer maxConnectNum = 400;

    /**
     * ES 异步 IO reactor 线程数（不等于连接数）。
     * 默认按 CPU 核数 *2，避免沿用 maxConnectNum(400) 造成线程过度创建。
     */
    private Integer ioThreadCount = Runtime.getRuntime().availableProcessors() * 2;
}
