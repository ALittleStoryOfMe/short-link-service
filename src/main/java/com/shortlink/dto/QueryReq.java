// filepath: src/main/java/com/shortlink/dto/QueryReq.java
package com.shortlink.dto;

/**
 * 短链组合查询请求 DTO（作为 GET 请求的参数对象，字段均为可选）。
 *
 * <p>支持按短码、渠道、断链状态三个维度进行组合过滤（AND 语义）。
 * 所有字段为 null 时返回全量记录。</p>
 *
 * <p>禁用 {@code @Data}，提供显式 getter/setter。</p>
 */
public class QueryReq {

    /**
     * 精确匹配短码（可选）。
     */
    private String shortCode;

    /**
     * 精确匹配渠道（可选）。
     */
    private String channel;

    /**
     * 过滤断链状态（可选）：
     * <ul>
     *   <li>{@code null} —— 不过滤</li>
     *   <li>{@code true} —— 只返回已断链</li>
     *   <li>{@code false} —— 只返回未断链</li>
     * </ul>
     */
    private Boolean broken;

    // -------------------------------------------------------------------------
    // Getter / Setter
    // -------------------------------------------------------------------------

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Boolean getBroken() {
        return broken;
    }

    public void setBroken(Boolean broken) {
        this.broken = broken;
    }
}
