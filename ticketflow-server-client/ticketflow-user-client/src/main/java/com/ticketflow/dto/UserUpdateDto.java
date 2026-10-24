package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户更新 dto
 */
@Data
@Schema(title="UserUpdateDto", description ="修改用户")
public class UserUpdateDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    
    @Schema(name ="id", type ="Long", description ="用户id（由网关按登录态注入，请求体中的值会被忽略）")
    private Long id;
    
    @Schema(name ="name", type ="String", description ="用户名字")
    private String name;

    @Schema(name ="relName", type ="String", description ="用户真实名字")
    private String relName;
    
    @Schema(name ="gender", type ="Integer", description ="性别 1:男 2:女")
    private Integer gender;
    
    @Schema(name ="mobile", type ="String", description ="手机号")
    private String mobile;
    
    @Schema(name ="idNumber", type ="String", description ="身份证号码")
    private String idNumber;
    
}
