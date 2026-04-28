// filepath: src/main/java/com/shortlink/dto/ApiResponse.java
package com.shortlink.dto;

/**
 * 统一 API 响应包装类。
 *
 * <pre>
 * {
 *   "code": 200,
 *   "msg":  "success",
 *   "data": { ... }
 * }
 * </pre>
 *
 * <p>禁用 {@code @Data}，使用显式 getter/setter 以满足规范要求。</p>
 *
 * @param <T> 业务数据类型
 */
public class ApiResponse<T> {

    /** 业务响应码：200 表示成功，其他值表示业务/系统错误 */
    private int code;

    /** 响应描述信息 */
    private String msg;

    /** 业务数据载体，失败时为 null */
    private T data;

    // -------------------------------------------------------------------------
    // 私有构造，强制通过工厂方法创建实例
    // -------------------------------------------------------------------------

    private ApiResponse() {
    }

    private ApiResponse(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    // -------------------------------------------------------------------------
    // 工厂方法
    // -------------------------------------------------------------------------

    /**
     * 构造成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功响应体
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    /**
     * 构造成功响应（无数据体）。
     *
     * @return 成功响应体
     */
    public static ApiResponse<Void> success() {
        return new ApiResponse<>(200, "success", null);
    }

    /**
     * 构造失败响应。
     *
     * @param code    错误码
     * @param message 错误描述
     * @param <T>     数据类型（失败时为 Void）
     * @return 失败响应体
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    // -------------------------------------------------------------------------
    // Getter / Setter
    // -------------------------------------------------------------------------

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
