package com.ticketflow.threadlocal;

import com.ticketflow.constant.Constant;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;

/**
 * 请求身份解析工具。
 * <p>
 * 身份唯一来源是网关注入的下游请求头：Gateway 的 RequestValidationFilter 会先剥离客户端
 * 伪造的 userId/code 头，再用 token 校验得到的权威 userId 覆盖，随后由下游
 * BaseParameterFilter 写入 {@link BaseParameterHolder}。
 * <p>
 * 因此业务代码禁止再从请求体/查询参数读取用户身份（可被篡改导致越权），
 * 必须通过 {@link #requireUserId()} 获取。
 */
public final class RequestIdentity {

    private RequestIdentity() {
    }

    /**
     * 获取当前请求的登录用户 id。
     * <p>
     * 取不到网关注入的 user_id 头时直接拒绝请求：宁可让未鉴权调用失败，
     * 也不能在缺失身份时退化成"客户端说是谁就是谁"。
     */
    public static Long requireUserId() {
        String userId = BaseParameterHolder.getParameter(Constant.USER_ID);
        if (userId == null || userId.trim().isEmpty()) {
            throw new TicketFlowFrameException(BaseCode.USER_NOT_LOGIN);
        }
        try {
            return Long.valueOf(userId.trim());
        } catch (NumberFormatException e) {
            throw new TicketFlowFrameException(BaseCode.HEAD_ARGUMENT_EMPTY);
        }
    }

    /**
     * 获取网关注入的渠道 code（下游请求头 code）。
     * <p>
     * 会话 key 由 code + userId 共同构成，因此会话失效/回查都需要 code。
     * 缺失时返回 null（非网关链路），调用方需自行决定是拒绝还是跳过。
     */
    public static String getCode() {
        String code = BaseParameterHolder.getParameter(Constant.CODE);
        return code == null || code.trim().isEmpty() ? null : code.trim();
    }

    /**
     * 获取当前请求的登录用户 id，缺失时返回 null。
     * <p>
     * 仅用于可选登录态场景（例如匿名可访问但登录后行为不同的接口），写操作一律用
     * {@link #requireUserId()}。
     */
    public static Long getUserId() {
        String userId = BaseParameterHolder.getParameter(Constant.USER_ID);
        if (userId == null || userId.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(userId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
