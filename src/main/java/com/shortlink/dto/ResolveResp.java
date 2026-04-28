// filepath: src/main/java/com/shortlink/dto/ResolveResp.java
package com.shortlink.dto;

/**
 * 短链解析响应 DTO。
 *
 * <p>禁用 {@code @Data}，提供显式 getter/setter。</p>
 */
public class ResolveResp {

    /** 解析得到的目标 URL */
    private String targetUrl;

    /** 短码标识 */
    private String shortCode;

    /** 累计解析次数（包含本次） */
    private long resolveCount;

    /** 是否盲盒模式 */
    private boolean blindBox;

    // -------------------------------------------------------------------------
    // 构造方法
    // -------------------------------------------------------------------------

    public ResolveResp() {
    }

    public ResolveResp(String targetUrl, String shortCode, long resolveCount, boolean blindBox) {
        this.targetUrl = targetUrl;
        this.shortCode = shortCode;
        this.resolveCount = resolveCount;
        this.blindBox = blindBox;
    }

    // -------------------------------------------------------------------------
    // Getter / Setter
    // -------------------------------------------------------------------------

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public long getResolveCount() {
        return resolveCount;
    }

    public void setResolveCount(long resolveCount) {
        this.resolveCount = resolveCount;
    }

    public boolean isBlindBox() {
        return blindBox;
    }

    public void setBlindBox(boolean blindBox) {
        this.blindBox = blindBox;
    }
}
