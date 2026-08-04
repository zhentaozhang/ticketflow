package com.ticketflow.property;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gateway 核心配置，通过 @Value 绑定 application.yml 中的属性。
 *
 *   apiRestrictPaths — 需要频率限制的路径，匹配成功则请求走 ApiRestrictService
 *   checkTokenPaths  — 需要 token 校验的路径（默认包含订单/用户/购票人等核心接口）
 *   checkSkipParmeterPaths — 跳过参数签名校验的路径（如支付宝回调 /alipay/notify）
 *   allowNormalAccess — 是否允许非签名访问（false 时仅 VERIFY_VALUE 请求可通行）
 *   userIdPaths — 需要提取 userId 的路径（即使 token 非必需也会尝试解析）
 */
@Data
@Component
public class GatewayProperty {
    /**
     * 需要做频率限制的路径
     */
    @Value("${api.limit.paths:#{null}}")
    private String[] apiRestrictPaths;
    
    @Value("${skip.check.token.paths:/**/program/order/create/v1,/**/program/order/create/v2,/**/program/order/create/v3," +
            "/**/program/order/create/v4,/**/ticket/user/add,/**/ticket/user/delete,/**/ticket/user/list,/**/user/authentication," +
            "/**/user/update,/**/user/update/email,/**/user/update/mobile,/**/user/update/password," +
            "/**/order/cancel,/**/order/create,/**/order/pay,/**/order/select/list,/**/order/get,/**/order/cancel}")
    private String[] checkTokenPaths;
    
    @Value("${skip.check.parmeter.paths:/**/alipay/notify,/**/wx/notify}")
    private String[] checkSkipParmeterPaths;
    
    @Value("${allow.normal.access:true}")
    private boolean allowNormalAccess;
    
    @Value("${userId.paths:/**/program/detail,/**/program/detail/v1,/**/program/detail/v2}")
    private String[] userIdPaths;
}
