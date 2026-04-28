// filepath: src/main/java/com/shortlink/exception/GlobalExceptionHandler.java
package com.shortlink.exception;

import com.shortlink.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常拦截器，统一将异常转换为 {@link ApiResponse} JSON 结构响应。
 *
 * <h3>拦截优先级</h3>
 * <ol>
 *   <li>{@link BusinessException} —— 业务规则违反，返回对应 code + 详细 message</li>
 *   <li>{@link MethodArgumentNotValidException} —— 参数校验失败，提取所有字段错误信息</li>
 *   <li>{@link Exception} —— 兜底，防止框架内部异常裸露</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常。
     * HTTP 状态码固定为 200（接口约定），错误信息通过 {@code code} 字段区分。
     *
     * @param ex 业务异常
     * @return 统一响应体
     */
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException ex) {
        log.warn("业务异常: code={}, message={}", ex.getCode(), ex.getMessage());
        return ApiResponse.fail(ex.getCode(), ex.getMessage());
    }

    /**
     * 处理 {@code @Valid} 参数校验失败。
     * 将所有字段错误拼接为可读字符串返回。
     *
     * @param ex Spring MVC 参数校验异常
     * @return 统一响应体，HTTP 400
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        String message = fieldErrors.stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return ApiResponse.fail(400, "参数校验失败: " + message);
    }

    /**
     * 兜底异常处理，捕获所有未被上层处理的异常，防止内部细节泄露到客户端。
     *
     * @param ex 任意异常
     * @return 统一响应体，HTTP 500
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception ex) {
        log.error("系统内部异常", ex);
        return ApiResponse.fail(500, "系统内部错误，请联系管理员");
    }
}
