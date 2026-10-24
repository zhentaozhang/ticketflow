package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户密码更新 dto
 */
@Data
@Schema(title="UserUpdatePasswordDto", description ="修改用户密码")
public class UserUpdatePasswordDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    
    @Schema(name ="id", type ="Long", description ="用户id（由网关按登录态注入，请求体中的值会被忽略）")
    private Long id;
    
    @Schema(name ="oldPassword", type ="String", description ="原密码（校验操作人身份）",requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String oldPassword;

    @Schema(name ="password", type ="String", description ="新密码",requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String password;
    
}
