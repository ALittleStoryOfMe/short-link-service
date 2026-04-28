// filepath: src/main/java/com/shortlink/exception/BusinessException.java
package com.shortlink.exception;

/**
 * 业务异常，用于表达可预期的业务规则违反场景（如短码不存在、短链已断链等）。
 * <p>
 * 与 {@link RuntimeException} 解绑 checked exception 语义，
 * 由 {@link GlobalExceptionHandler} 统一拦截并返回结构化 JSON 响应。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>
 * throw new BusinessException(404, "短链不存在：" + shortCode);
 * </pre>
 */
public class BusinessException extends RuntimeException {

    /** 业务错误码，与 HTTP 状态码解耦（可自定义语义，如 40001、40401） */
    private final int code;

    /**
     * 构造业务异常。
     *
     * @param code    业务错误码
     * @param message 错误描述（直接作为 API 响应 msg 字段）
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 获取业务错误码。
     *
     * @return 错误码
     */
    public int getCode() {
        return code;
    }
}
