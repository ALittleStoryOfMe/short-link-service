// filepath: src/main/java/com/shortlink/dto/BlindBoxReq.java
package com.shortlink.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;

/**
 * 盲盒短链生成请求 DTO。
 *
 * <p>盲盒模式：每次解析随机返回 {@code longUrls} 中的一个 URL，
 * 用于 A/B 测试、活动落地页随机跳转等场景。</p>
 *
 * <p>禁用 {@code @Data}，提供显式 getter/setter。</p>
 */
public class BlindBoxReq {

    /**
     * 候选目标 URL 列表，至少包含 2 个元素。
     */
    @NotEmpty(message = "longUrls 不能为空")
    @Size(min = 2, message = "盲盒模式至少需要 2 个候选 URL")
    private List<String> longUrls;

    /**
     * 最大解析次数，≤ 0 表示不限次数，> 0 时耗尽后自动断链。
     */
    @Min(value = 0, message = "maxCount 不能为负数")
    private int maxCount;

    /**
     * 业务渠道，可选，默认由 Service 层填充为 "default"。
     */
    private String channel;

    // -------------------------------------------------------------------------
    // Getter / Setter
    // -------------------------------------------------------------------------

    public List<String> getLongUrls() {
        return longUrls;
    }

    public void setLongUrls(List<String> longUrls) {
        this.longUrls = longUrls;
    }

    public int getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = maxCount;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
