package com.ticketflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 渠道公开数据 vo。
 * <p>
 * 对外（浏览器、第三方渠道）只返回这些字段：公钥本身不是秘密，可以给客户端做加密/验签配置；
 * 而 {@code signSecretKey}、{@code dataSecretKey}、{@code aesKey}、{@code tokenSecret}
 * 这类私钥永远不出现在这个 VO 里——解密渠道配置请走内部接口
 * {@code /channel/data/getSecretByCode}（{@code @InternalApi} 保护）。
 */
@Data
@Schema(title = "ChannelDataPublicVo", description = "渠道公开数据（不含任何私钥）")
public class ChannelDataPublicVo {

    @Schema(name = "id", type = "String", description = "id")
    private Long id;

    @Schema(name = "name", type = "String", description = "名称")
    private String name;

    @Schema(name = "code", type = "String", description = "code码")
    private String code;

    @Schema(name = "introduce", type = "String", description = "介绍")
    private String introduce;

    @Schema(name = "status", type = "Integer", description = "状态 1:正常 0:禁用")
    private Integer status;

    @Schema(name = "signPublicKey", type = "String", description = "rsa签名公钥")
    private String signPublicKey;

    @Schema(name = "dataPublicKey", type = "String", description = "rsa参数公钥")
    private String dataPublicKey;
}
