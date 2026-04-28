// filepath: src/main/java/com/shortlink/service/ShortLinkService.java
package com.shortlink.service;

import com.google.common.collect.ImmutableList;
import com.shortlink.dto.QueryReq;
import com.shortlink.dto.ResolveResp;
import com.shortlink.exception.BusinessException;
import com.shortlink.model.ShortLinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 短链核心业务服务。
 *
 * <h3>存储层设计</h3>
 * 使用 {@link ConcurrentHashMap} 作为纯内存存储，Key 为 7 位 Base62 短码，
 * Value 为 {@link ShortLinkRecord}。重启后数据清空，适用于演示 / 轻量场景。
 *
 * <h3>短码生成策略</h3>
 * <ol>
 *   <li>全局 {@link AtomicLong} 计数器自增，保证单调递增，无重复。</li>
 *   <li>将计数值转换为 Base62 字符串（0-9a-zA-Z），左侧补零至 7 位。</li>
 *   <li>最多重试 3 次，若仍冲突（理论上不可能）则抛异常。</li>
 * </ol>
 *
 * <h3>并发安全保证</h3>
 * <ul>
 *   <li>写操作使用 {@code ConcurrentHashMap.putIfAbsent}，避免重复覆盖。</li>
 *   <li>读操作直接 {@code get}，ConcurrentHashMap 提供可见性保证。</li>
 *   <li>次数控制、断链标记均委托给 {@link ShortLinkRecord} 内部的 CAS 操作。</li>
 * </ul>
 */
@Service
public class ShortLinkService {

    private static final Logger log = LoggerFactory.getLogger(ShortLinkService.class);

    /** Base62 字符集：0-9a-zA-Z，共 62 个字符 */
    private static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** 短码固定长度 7 位 */
    private static final int CODE_LENGTH = 7;

    /** 冲突最大重试次数（理论上不会触发，保留防御性设计） */
    private static final int MAX_RETRY = 3;

    /** HEAD 探活请求超时时间（毫秒） */
    private static final int DETECT_TIMEOUT_MS = 3000;

    /**
     * URL 格式正则：必须以 http:// 或 https:// 开头。
     * 预编译 Pattern，避免每次调用重复编译。
     */
    private static final Pattern URL_PATTERN = Pattern.compile("^(https?://).{1,2000}$");

    /**
     * 全局短码计数器，自增值作为 Base62 编码的输入。
     * 使用 {@code static final} 保证跨请求唯一性。
     */
    private static final AtomicLong COUNTER = new AtomicLong(0L);

    /**
     * 核心存储：短码 → 短链记录。
     * 线程安全，支持高并发读写。
     */
    private final ConcurrentHashMap<String, ShortLinkRecord> store = new ConcurrentHashMap<>();

    // =========================================================================
    // 公开业务方法
    // =========================================================================

    /**
     * 生成普通短链（单目标 URL）。
     *
     * @param longUrl 目标长链，非空且格式合法
     * @param channel 渠道标识，为空时默认 "default"
     * @return 生成的短链记录
     * @throws BusinessException 参数非法时抛出
     */
    public ShortLinkRecord generateNormal(String longUrl, String channel) {
        // 参数校验（双重保险，Controller 层已通过 @Valid 校验）
        validateUrl(longUrl);
        String resolvedChannel = resolveChannel(channel);

        String shortCode = generateUniqueCode();
        // 封装为单元素不可变 List，与盲盒逻辑统一
        List<String> urls = ImmutableList.of(longUrl);
        ShortLinkRecord record = new ShortLinkRecord(shortCode, urls, resolvedChannel, 0, false);

        // putIfAbsent 保证幂等写入；正常流程不会冲突
        ShortLinkRecord existing = store.putIfAbsent(shortCode, record);
        if (existing != null) {
            // 极端情况：短码已被占用（计数器保证单调，此分支理论不可达）
            log.error("短码冲突（不可达分支）: {}", shortCode);
            throw new BusinessException(500, "短码生成冲突，请重试");
        }
        log.info("普通短链已创建: shortCode={}, channel={}", shortCode, resolvedChannel);
        return record;
    }

    /**
     * 生成盲盒短链（多目标 URL 随机跳转）。
     *
     * @param longUrls 候选 URL 列表，数量 ≥ 2
     * @param maxCount 最大解析次数，≤ 0 表示不限
     * @param channel  渠道标识，为空时默认 "default"
     * @return 生成的短链记录
     * @throws BusinessException 参数非法时抛出
     */
    public ShortLinkRecord generateBlindBox(List<String> longUrls, Integer maxCount, String channel) {
        // 校验候选 URL 数量
        if (longUrls == null || longUrls.size() < 2) {
            throw new BusinessException(400, "盲盒模式至少需要 2 个候选 URL");
        }
        // 逐一校验每个 URL 格式
        for (String url : longUrls) {
            validateUrl(url);
        }
        String resolvedChannel = resolveChannel(channel);
        int resolvedMaxCount = (maxCount == null || maxCount < 0) ? 0 : maxCount;

        String shortCode = generateUniqueCode();
        // 封装为不可变 List，防止外部修改影响内部状态
        List<String> immutableUrls = ImmutableList.copyOf(longUrls);
        ShortLinkRecord record = new ShortLinkRecord(shortCode, immutableUrls, resolvedChannel, resolvedMaxCount, true);

        store.putIfAbsent(shortCode, record);
        log.info("盲盒短链已创建: shortCode={}, urlCount={}, maxCount={}, channel={}",
                shortCode, longUrls.size(), resolvedMaxCount, resolvedChannel);
        return record;
    }

    /**
     * 解析短码，返回目标 URL 及元信息。
     *
     * @param shortCode 7 位短码
     * @return 解析响应 DTO
     * @throws BusinessException 短码不存在或已断链时抛出
     */
    public ResolveResp resolve(String shortCode) {
        ShortLinkRecord record = getRecordOrThrow(shortCode);

        // 先检查断链状态（快速失败，避免消耗次数）
        if (record.isBroken()) {
            throw new BusinessException(410, "短链已断链: " + shortCode
                    + "，原因: " + record.getBrokenReason());
        }

        // 调用线程安全的 resolveUrl()，内部处理次数控制和断链标记
        String targetUrl = record.resolveUrl();

        if (targetUrl == null) {
            // resolveUrl 返回 null 说明次数已耗尽（本次未拿到机会）
            throw new BusinessException(410, "短链解析次数已耗尽: " + shortCode);
        }

        // 累计解析计数
        record.incrementResolveCount();

        return new ResolveResp(targetUrl, shortCode, record.getResolveCount(), record.isBlindBox());
    }

    /**
     * 手动标记短链为断链状态。
     *
     * @param shortCode 短码
     * @param reason    断链原因
     * @throws BusinessException 短码不存在时抛出
     */
    public void markBroken(String shortCode, String reason) {
        ShortLinkRecord record = getRecordOrThrow(shortCode);
        record.markBroken(StringUtils.hasText(reason) ? reason : "手动标记断链");
        log.info("短链已手动标记断链: shortCode={}, reason={}", shortCode, reason);
    }

    /**
     * 批量探活检测：对所有未断链的短链发起 HTTP HEAD 请求，
     * 异常或响应码 ≥ 400 时自动标记断链。
     *
     * <h3>并发策略</h3>
     * 遍历 store 的 values()，对每条记录同步发起 HEAD 请求。
     * 若需要大规模并发，可改为线程池；此处保持简单实现。
     *
     * @return 检测明细列表，每条包含短码、URL、状态、是否新标记断链
     */
    public List<Map<String, Object>> batchDetectBroken() {
        List<Map<String, Object>> results = new ArrayList<>();

        for (ShortLinkRecord record : store.values()) {
            // 跳过已断链记录，避免重复检测
            if (record.isBroken()) {
                continue;
            }

            // 取第一个 URL 作为探活目标（盲盒取任意一个均可）
            String targetUrl = record.getUrls().get(0);
            Map<String, Object> detail = new HashMap<>();
            detail.put("shortCode", record.getShortCode());
            detail.put("targetUrl", targetUrl);

            boolean newlyBroken = false;
            String detectResult;

            try {
                int statusCode = sendHeadRequest(targetUrl);
                if (statusCode >= 400) {
                    String reason = "HTTP 探活返回 " + statusCode;
                    record.markBroken(reason);
                    newlyBroken = true;
                    detectResult = "BROKEN(status=" + statusCode + ")";
                } else {
                    detectResult = "OK(status=" + statusCode + ")";
                }
            } catch (Exception e) {
                String reason = "HTTP 探活异常: " + e.getMessage();
                record.markBroken(reason);
                newlyBroken = true;
                detectResult = "BROKEN(exception=" + e.getMessage() + ")";
                log.warn("探活请求异常: shortCode={}, url={}, error={}", record.getShortCode(), targetUrl, e.getMessage());
            }

            detail.put("detectResult", detectResult);
            detail.put("newlyBroken", newlyBroken);
            results.add(detail);
        }

        log.info("批量探活完成，共检测 {} 条，本次新增断链数: {}",
                results.size(),
                results.stream().filter(m -> Boolean.TRUE.equals(m.get("newlyBroken"))).count());
        return results;
    }

    /**
     * 组合查询短链记录，支持短码、渠道、断链状态三维过滤（AND 语义）。
     *
     * <p>遍历 ConcurrentHashMap 的 values() 快照，不会阻塞写入操作，线程安全。</p>
     *
     * @param req 查询请求，所有字段均为可选
     * @return 符合条件的记录列表
     */
    public List<ShortLinkRecord> query(QueryReq req) {
        return store.values().stream()
                .filter(r -> {
                    // 按短码精确匹配（非空时生效）
                    if (StringUtils.hasText(req.getShortCode())) {
                        return req.getShortCode().equals(r.getShortCode());
                    }
                    return true;
                })
                .filter(r -> {
                    // 按渠道精确匹配（非空时生效）
                    if (StringUtils.hasText(req.getChannel())) {
                        return req.getChannel().equals(r.getChannel());
                    }
                    return true;
                })
                .filter(r -> {
                    // 按断链状态过滤（非 null 时生效）
                    if (req.getBroken() != null) {
                        return req.getBroken() == r.isBroken();
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 包级可见方法（供测试直接访问）
    // =========================================================================

    /**
     * 获取当前存储的记录总数（供测试验证）。
     */
    public int storeSize() {
        return store.size();
    }

    // =========================================================================
    // 私有工具方法
    // =========================================================================

    /**
     * 生成全局唯一 7 位 Base62 短码。
     * <ol>
     *   <li>自增全局计数器（AtomicLong 保证线程安全）。</li>
     *   <li>转换为 Base62 字符串，左侧补 '0' 至 7 位。</li>
     *   <li>若出现冲突（重启前已存在相同短码），最多重试 3 次。</li>
     * </ol>
     *
     * @return 唯一短码
     * @throws BusinessException 重试次数耗尽时抛出
     */
    private String generateUniqueCode() {
        for (int i = 0; i < MAX_RETRY; i++) {
            long seq = COUNTER.getAndIncrement();
            String code = toBase62(seq);
            if (!store.containsKey(code)) {
                return code;
            }
            log.warn("短码冲突，第 {} 次重试: {}", i + 1, code);
        }
        throw new BusinessException(500, "短码生成失败，已重试 " + MAX_RETRY + " 次");
    }

    /**
     * 将长整数转换为固定 7 位的 Base62 字符串（左侧补 '0'）。
     *
     * @param num 非负整数
     * @return 7 位 Base62 编码字符串
     */
    private String toBase62(long num) {
        if (num == 0) {
            return padLeft("0", CODE_LENGTH);
        }
        StringBuilder sb = new StringBuilder();
        long n = num;
        while (n > 0) {
            sb.append(BASE62_CHARS.charAt((int) (n % 62)));
            n /= 62;
        }
        // reverse：低位先写，需反转
        return padLeft(sb.reverse().toString(), CODE_LENGTH);
    }

    /**
     * 左侧补 '0' 直到达到目标长度；若已超出则截取末尾 {@code length} 位。
     *
     * @param s      源字符串
     * @param length 目标长度
     * @return 对齐后的字符串
     */
    private String padLeft(String s, int length) {
        if (s.length() >= length) {
            // AI修正: 超出目标长度时截取末尾，保证短码位数固定
            return s.substring(s.length() - length);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length - s.length(); i++) {
            sb.append('0');
        }
        sb.append(s);
        return sb.toString();
    }

    /**
     * 校验 URL 格式：非空 + 正则匹配 http/https。
     *
     * @param url 目标 URL
     * @throws BusinessException 格式非法时抛出
     */
    private void validateUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(400, "URL 不能为空");
        }
        if (!URL_PATTERN.matcher(url).matches()) {
            throw new BusinessException(400, "URL 格式非法（必须以 http:// 或 https:// 开头）: " + url);
        }
    }

    /**
     * 渠道默认值处理：空值时返回 "default"。
     *
     * @param channel 原始渠道值
     * @return 非空渠道标识
     */
    private String resolveChannel(String channel) {
        return StringUtils.hasText(channel) ? channel : "default";
    }

    /**
     * 从 store 获取记录，不存在时抛出 404 业务异常。
     *
     * @param shortCode 短码
     * @return 对应记录
     * @throws BusinessException 不存在时抛出
     */
    private ShortLinkRecord getRecordOrThrow(String shortCode) {
        if (!StringUtils.hasText(shortCode)) {
            throw new BusinessException(400, "短码不能为空");
        }
        ShortLinkRecord record = store.get(shortCode);
        if (record == null) {
            throw new BusinessException(404, "短链不存在: " + shortCode);
        }
        return record;
    }

    /**
     * 向目标 URL 发送 HTTP HEAD 请求，返回响应状态码。
     * 连接超时和读取超时均为 {@value #DETECT_TIMEOUT_MS} ms。
     *
     * @param targetUrl 目标 URL
     * @return HTTP 响应码
     * @throws Exception 网络异常或 URL 格式错误时抛出
     */
    private int sendHeadRequest(String targetUrl) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(DETECT_TIMEOUT_MS);
            conn.setReadTimeout(DETECT_TIMEOUT_MS);
            // 禁止自动重定向，避免重定向到错误页被误判为成功
            conn.setInstanceFollowRedirects(false);
            conn.connect();
            return conn.getResponseCode();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 仅供测试使用：清空所有存储数据，重置为初始状态。
     */
    public void clearStore() {
        store.clear();
    }

    /**
     * 仅供测试使用：直接向 store 放入一条记录（绕过短码生成逻辑）。
     *
     * @param record 待放入的记录
     */
    public void putRecord(ShortLinkRecord record) {
        store.put(record.getShortCode(), record);
    }
}
