package com.ticketflow.common;

import com.ticketflow.enums.BaseCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一 API 响应体。
 * <p>
 * 作用：
 * 统一所有 REST 接口返回格式，避免不同接口返回结构不一致。
 * <p>
 * 标准格式：
 * <p>
 * {
 * "code": 0,
 * "message": "OK",
 * "data": {}
 * }
 * <p>
 * 字段说明：
 * code    : 响应状态码，0表示成功，其余表示失败
 * message : 响应提示信息
 * data    : 业务数据内容
 * <p>
 * <p>
 * 泛型 T：
 * <p>
 * 用于表示 data 的具体类型。
 * <p>
 * 例如：
 * <p>
 * ApiResponse<User>
 * <p>
 * 表示：
 * <p>
 * data 中存放 User 对象。
 * <p>
 * <p>
 * 静态工厂方法：
 * <p>
 * ok() / ok(T data)
 * → 返回成功响应
 * <p>
 * error()
 * → 返回默认系统错误
 * <p>
 * error(message)
 * → 返回指定错误信息
 * <p>
 * error(code,message)
 * → 返回指定错误码和错误信息
 * <p>
 * error(BaseCode)
 * → 根据错误码枚举生成错误响应
 * <p>
 * error(BaseCode,T data)
 * → 返回错误信息，同时携带业务数据
 */
@Data
@Schema(title = "ApiResponse", description = "数据响应规范结构")
public class ApiResponse<T> implements Serializable {


    /**
     * 响应状态码。
     * <p>
     * 约定：
     * <p>
     * 0     ：请求成功
     * 非0   ：请求失败
     * <p>
     * 前端通常根据 code 判断接口是否成功。
     */
    @Schema(name = "code", type = "Integer", description = "响应码 0:成功 其余:失败")
    private Integer code;


    /**
     * 响应消息。
     * <p>
     * 一般用于返回成功提示或者错误原因。
     */
    @Schema(name = "message", type = "String", description = "错误信息")
    private String message;


    /**
     * 业务数据。
     * <p>
     * 使用泛型 T，
     * 可以适配不同接口返回不同类型的数据。
     * <p>
     * 例如：
     * <p>
     * ApiResponse<User>
     * ApiResponse<List<Order>>
     */
    @Schema(name = "data", description = "响应的具体数据")
    private T data;


    /**
     * 私有构造方法。
     * <p>
     * 不允许外部直接：
     * <p>
     * new ApiResponse<>()
     * <p>
     * 强制使用静态方法：
     * <p>
     * ApiResponse.ok()
     * ApiResponse.error()
     * <p>
     * 保证响应创建方式统一。
     */
    private ApiResponse() {
    }


    /**
     * 根据指定错误码和错误信息创建错误响应。
     * <p>
     * 示例：
     * <p>
     * ApiResponse.error(10001,"用户不存在")
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {

        ApiResponse<T> apiResponse = new ApiResponse<T>();

        apiResponse.code = code;
        apiResponse.message = message;

        return apiResponse;
    }


    /**
     * 创建自定义错误信息响应。
     * <p>
     * 默认错误码：
     * <p>
     * -100
     */
    public static <T> ApiResponse<T> error(String message) {

        ApiResponse<T> apiResponse = new ApiResponse<T>();

        apiResponse.code = -100;
        apiResponse.message = message;

        return apiResponse;
    }


    /**
     * 创建错误响应，并携带业务数据。
     * <p>
     * 示例：
     * ApiResponse.error(10054, argumentErrorList)
     * <p>
     * data 用于保存额外错误信息（如参数校验错误详情）。
     */
    public static <T> ApiResponse<T> error(Integer code, T data) {

        ApiResponse<T> apiResponse = new ApiResponse<T>();

        apiResponse.code = code;
        apiResponse.data = data;

        return apiResponse;
    }


    /**
     * 根据 BaseCode 枚举创建错误响应。
     * <p>
     * BaseCode 中维护：
     * <p>
     * code + message
     * <p>
     * 例如：
     * <p>
     * BaseCode.SYSTEM_ERROR
     * <p>
     * 转换为：
     * <p>
     * {
     * code:-1,
     * message:"系统异常"
     * }
     */
    public static <T> ApiResponse<T> error(BaseCode baseCode) {

        ApiResponse<T> apiResponse = new ApiResponse<T>();

        apiResponse.code = baseCode.getCode();
        apiResponse.message = baseCode.getMsg();

        return apiResponse;
    }


    /**
     * 根据 BaseCode 创建错误响应，并携带业务数据。
     */
    public static <T> ApiResponse<T> error(BaseCode baseCode, T data) {

        ApiResponse<T> apiResponse = new ApiResponse<T>();

        apiResponse.code = baseCode.getCode();
        apiResponse.message = baseCode.getMsg();
        apiResponse.data = data;

        return apiResponse;
    }


    /**
     * 创建默认系统错误响应。
     * <p>
     * 返回：
     * <p>
     * code = -100
     * message = 系统错误，请稍后重试!
     */
    public static <T> ApiResponse<T> error() {

        ApiResponse<T> apiResponse = new ApiResponse<T>();

        apiResponse.code = -100;
        apiResponse.message = "系统错误，请稍后重试!";

        return apiResponse;
    }


    /**
     * 创建成功响应，不携带业务数据。
     * <p>
     * 示例：
     * <p>
     * 删除成功：
     * ApiResponse.ok()
     */
    public static <T> ApiResponse<T> ok() {

        ApiResponse<T> apiResponse = new ApiResponse<T>();

        apiResponse.code = 0;

        return apiResponse;
    }


    /**
     * 创建成功响应，并携带业务数据。
     * <p>
     * 示例：
     * <p>
     * ApiResponse.ok(user)
     * <p>
     * 返回：
     * <p>
     * {
     * code:0,
     * data:user
     * }
     */
    public static <T> ApiResponse<T> ok(T t) {

        ApiResponse<T> apiResponse = new ApiResponse<T>();

        apiResponse.code = 0;
        apiResponse.setData(t);

        return apiResponse;
    }
}