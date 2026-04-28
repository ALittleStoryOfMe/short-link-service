// filepath: src/main/java/com/shortlink/model/ShortLinkRecord.java
package com.shortlink.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 短链记录实体，代表一条已注册的短链（普通或盲盒）。
 *
 * <h3>线程安全设计</h3>
 * <ul>
 *   <li>{@code resolveCount} 使用 {@link AtomicLong} 计数，无需额外加锁。</li>
 *   <li>{@code remainingCount} 使用 {@link AtomicInteger}，通过
 *       {@code compareAndSet} 保证"检查-减一-标记"三步原子化，
 *       彻底消除 TOCTOU 竞态。</li>
 *   <li>{@code isBroken} 使用 {@link AtomicBoolean}，单次 CAS 完成断链标记。</li>
 *   <li>{@code urls} 在构造时被包装为不可变 List，读取无需同步。</li>
 * </ul>
 */
public class ShortLinkRecord {

    /** 7 位 Base62 短码，全局唯一主键 */
    private final String shortCode;

    /**
     * 目标 URL 列表。
     * <ul>
     *   <li>普通短链：只有 1 个元素。</li>
     *   <li>盲盒短链：≥ 2 个元素，每次解析随机返回一个。</li>
     * </ul>
     * 使用不可变 List 保证发布安全（publication safety）。
     */
    private final List<String> urls;

    /** 业务渠道标识，默认 "default" */
    private final String channel;

    /** 创建时间，不可变，仅做审计用途 */
    private final LocalDateTime createdAt;

    /** 累计解析次数，原子递增，允许并发读写 */
    private final AtomicLong resolveCount;

    /**
     * 最大允许解析次数。
     * <ul>
     *   <li>≤ 0 表示不限次数。</li>
     *   <li>&gt; 0 时，每次解析消耗一次，耗尽后自动断链。</li>
     * </ul>
     */
    private final int maxResolveCount;

    /**
     * 剩余可解析次数（仅 maxResolveCount > 0 时有效）。
     * 使用 {@link AtomicInteger}，通过 CAS 实现无锁减一，
     * 归零时触发断链。
     */
    private final AtomicInteger remainingCount;

    /** 是否为盲盒模式：true 表示每次解析随机返回一个 url */
    private final boolean isBlindBox;

    /**
     * 断链标志。
     * {@code true} 表示该短链已失效，后续解析请求将被拒绝。
     */
    private final AtomicBoolean isBroken;

    /** 断链原因，人工标记或自动检测时写入 */
    private volatile String brokenReason;

    /**
     * 构造一条短链记录。
     *
     * @param shortCode       7 位 Base62 短码
     * @param urls            不可变目标 URL 列表（外部已封装）
     * @param channel         渠道标识
     * @param maxResolveCount 最大解析次数（≤0 表示不限）
     * @param isBlindBox      是否盲盒模式
     */
    public ShortLinkRecord(String shortCode,
                           List<String> urls,
                           String channel,
                           int maxResolveCount,
                           boolean isBlindBox) {
        this.shortCode = shortCode;
        this.urls = urls;
        this.channel = channel;
        this.createdAt = LocalDateTime.now();
        this.resolveCount = new AtomicLong(0L);
        this.maxResolveCount = maxResolveCount;
        // 初始化剩余次数；不限次时设为 -1（哨兵值）
        this.remainingCount = new AtomicInteger(maxResolveCount > 0 ? maxResolveCount : -1);
        this.isBlindBox = isBlindBox;
        this.isBroken = new AtomicBoolean(false);
        this.brokenReason = null;
    }

    // -------------------------------------------------------------------------
    // 核心业务方法
    // -------------------------------------------------------------------------

    /**
     * 解析短链，返回目标 URL。
     *
     * <h3>盲盒逻辑</h3>
     * 盲盒模式下使用 {@link ThreadLocalRandom} 在 urls 中均匀采样，
     * 保证每个线程使用独立的随机数生成器，高并发下无竞争。
     *
     * <h3>次数控制逻辑（无锁 CAS）</h3>
     * <pre>
     * 若 maxResolveCount > 0：
     *   1. CAS 读取 remaining
     *   2. 若 remaining <= 0，说明已被其他线程耗尽 → 标记断链 → 返回 null（调用方处理）
     *   3. 尝试 CAS(remaining, remaining-1)
     *      - 成功：继续返回 URL
     *      - 失败（被抢占）：重试，保证公平消耗
     *   4. 减后值 == 0 时，当前线程负责标记断链
     * </pre>
     *
     * @return 目标 URL；若次数已耗尽返回 null（调用方须检查并抛异常）
     */
    public String resolveUrl() {
        // 需要次数控制时走 CAS 路径
        if (maxResolveCount > 0) {
            while (true) {
                int current = remainingCount.get();
                if (current <= 0) {
                    // 次数已被其他线程耗尽，确保断链标记已设置
                    isBroken.compareAndSet(false, true);
                    return null;
                }
                int next = current - 1;
                if (remainingCount.compareAndSet(current, next)) {
                    // CAS 成功，本线程拿到一次解析机会
                    if (next == 0) {
                        // AI修正: 使用 CAS 设置断链，避免多线程重复写入 brokenReason
                        if (isBroken.compareAndSet(false, true)) {
                            this.brokenReason = "解析次数已耗尽（maxResolveCount=" + maxResolveCount + "）";
                        }
                    }
                    break;
                }
                // CAS 失败，自旋重试
            }
        }

        // 均匀采样目标 URL
        int index = isBlindBox
                ? ThreadLocalRandom.current().nextInt(urls.size())
                : 0;
        return urls.get(index);
    }

    /**
     * 原子标记短链为断链状态。
     * 使用 {@link AtomicBoolean#compareAndSet} 保证只有第一个调用者能写入 reason，
     * 避免并发时 brokenReason 被多次覆盖。
     *
     * @param reason 断链原因描述
     */
    public void markBroken(String reason) {
        if (isBroken.compareAndSet(false, true)) {
            this.brokenReason = reason;
        }
    }

    /**
     * 原子递增解析计数器，供 {@code resolve} 调用成功后统计。
     */
    public void incrementResolveCount() {
        resolveCount.incrementAndGet();
    }

    // -------------------------------------------------------------------------
    // Getter（DTO 禁用 @Data，Model 使用显式 getter）
    // -------------------------------------------------------------------------

    public String getShortCode() {
        return shortCode;
    }

    public List<String> getUrls() {
        return urls;
    }

    public String getChannel() {
        return channel;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public long getResolveCount() {
        return resolveCount.get();
    }

    public int getMaxResolveCount() {
        return maxResolveCount;
    }

    public int getRemainingCount() {
        return remainingCount.get();
    }

    public boolean isBlindBox() {
        return isBlindBox;
    }

    public boolean isBroken() {
        return isBroken.get();
    }

    public String getBrokenReason() {
        return brokenReason;
    }
}
