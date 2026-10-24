package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户邮箱更新 dto
 */
@Data
@Schema(title="UserUpdateEmailDto", description ="修改用户邮箱")
public class UserUpdateEmailDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    
    @Schema(name ="id", type ="Long", description ="用户id（由网关按登录态注入，请求体中的值会被忽略）")
    private Long id;
    
    @Schema(name ="email", type ="String", description ="邮箱",requiredMode= RequiredMode.REQUIRED)
    @NotBlank
    private String email;
    
}
