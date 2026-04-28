// filepath: src/main/java/com/shortlink/dto/GenerateReq.java
package com.shortlink.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * 普通短链生成请求 DTO。
 *
 * <p>禁用 {@code @Data}，提供显式 getter/setter。</p>
 */
public class GenerateReq {

    /**
     * 目标长链 URL，非空且格式合法（http/https 开头）。
     */
    @NotBlank(message = "longUrl 不能为空")
    @Pattern(
            regexp = "^(https?://).{1,2000}$",
            message = "longUrl 必须以 http:// 或 https:// 开头，且长度不超过 2000"
    )
    private String longUrl;

    /**
     * 业务渠道，可选，默认由 Service 层填充为 "default"。
     */
    private String channel;

    // -------------------------------------------------------------------------
    // Getter / Setter
    // -------------------------------------------------------------------------

    public String getLongUrl() {
        return longUrl;
    }

    public void setLongUrl(String longUrl) {
        this.longUrl = longUrl;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
