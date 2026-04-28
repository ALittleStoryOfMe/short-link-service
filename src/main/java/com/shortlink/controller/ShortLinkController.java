// filepath: src/main/java/com/shortlink/controller/ShortLinkController.java
package com.shortlink.controller;

import com.shortlink.dto.ApiResponse;
import com.shortlink.dto.BlindBoxReq;
import com.shortlink.dto.GenerateReq;
import com.shortlink.dto.QueryReq;
import com.shortlink.dto.ResolveResp;
import com.shortlink.model.ShortLinkRecord;
import com.shortlink.service.ShortLinkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 短链服务 REST 控制器，统一路径前缀 {@code /api/short-link}。
 *
 * <h3>接口清单</h3>
 * <pre>
 * POST   /api/short-link/normal            生成普通短链
 * POST   /api/short-link/blindbox          生成盲盒短链
 * GET    /api/short-link/resolve/{code}    解析短码
 * PUT    /api/short-link/{code}/broken     手动标记断链
 * POST   /api/short-link/detect-broken     批量探活检测
 * GET    /api/short-link/query             组合查询
 * </pre>
 *
 * <p>所有接口统一返回 {@link ApiResponse} JSON 结构，HTTP 状态码除参数校验外均为 200。</p>
 */
@RestController
@RequestMapping("/api/short-link")
public class ShortLinkController {

    private final ShortLinkService shortLinkService;

    /**
     * 构造注入，避免字段注入导致的测试困难。
     */
    public ShortLinkController(ShortLinkService shortLinkService) {
        this.shortLinkService = shortLinkService;
    }

    // =========================================================================
    // 生成接口
    // =========================================================================

    /**
     * 生成普通短链（单目标 URL）。
     *
     * <p>请求体示例：</p>
     * <pre>
     * {
     *   "longUrl": "https://www.example.com/very/long/path",
     *   "channel": "wechat"
     * }
     * </pre>
     *
     * @param req 请求体，经 {@code @Valid} 校验
     * @return 包含短链记录信息的统一响应
     */
    @PostMapping("/normal")
    public ApiResponse<ShortLinkRecord> generateNormal(@Valid @RequestBody GenerateReq req) {
        ShortLinkRecord record = shortLinkService.generateNormal(req.getLongUrl(), req.getChannel());
        return ApiResponse.success(record);
    }

    /**
     * 生成盲盒短链（多目标 URL 随机跳转）。
     *
     * <p>请求体示例：</p>
     * <pre>
     * {
     *   "longUrls": ["https://a.com", "https://b.com"],
     *   "maxCount": 100,
     *   "channel": "app"
     * }
     * </pre>
     *
     * @param req 请求体，经 {@code @Valid} 校验
     * @return 包含短链记录信息的统一响应
     */
    @PostMapping("/blindbox")
    public ApiResponse<ShortLinkRecord> generateBlindBox(@Valid @RequestBody BlindBoxReq req) {
        ShortLinkRecord record = shortLinkService.generateBlindBox(
                req.getLongUrls(), req.getMaxCount(), req.getChannel());
        return ApiResponse.success(record);
    }

    // =========================================================================
    // 解析接口
    // =========================================================================

    /**
     * 解析短码，返回目标 URL 及元信息。
     * <p>若短链已断链或解析次数耗尽，由 {@link com.shortlink.exception.GlobalExceptionHandler} 拦截并返回业务错误。</p>
     *
     * @param code 7 位 Base62 短码（路径变量）
     * @return 解析结果
     */
    @GetMapping("/resolve/{code}")
    public ApiResponse<ResolveResp> resolve(@PathVariable("code") String code) {
        ResolveResp resp = shortLinkService.resolve(code);
        return ApiResponse.success(resp);
    }

    // =========================================================================
    // 断链管理接口
    // =========================================================================

    /**
     * 手动标记指定短链为断链状态。
     *
     * @param code   路径变量：短码
     * @param reason 请求参数：断链原因（可选）
     * @return 操作结果
     */
    @PutMapping("/{code}/broken")
    public ApiResponse<Void> markBroken(
            @PathVariable("code") String code,
            @RequestParam(value = "reason", required = false) String reason) {
        shortLinkService.markBroken(code, reason);
        return ApiResponse.success();
    }

    /**
     * 批量探活检测：对所有未断链的短链发起 HEAD 请求，失效则自动标记断链。
     *
     * @return 检测明细列表
     */
    @PostMapping("/detect-broken")
    public ApiResponse<List<Map<String, Object>>> detectBroken() {
        List<Map<String, Object>> results = shortLinkService.batchDetectBroken();
        return ApiResponse.success(results);
    }

    // =========================================================================
    // 查询接口
    // =========================================================================

    /**
     * 组合查询短链记录，所有参数均可选，AND 语义组合过滤。
     *
     * <p>示例请求：</p>
     * <pre>
     * GET /api/short-link/query?channel=wechat&amp;broken=false
     * </pre>
     *
     * @param shortCode 精确匹配短码（可选）
     * @param channel   精确匹配渠道（可选）
     * @param broken    过滤断链状态（可选：true/false）
     * @return 符合条件的记录列表
     */
    @GetMapping("/query")
    public ApiResponse<List<ShortLinkRecord>> query(
            @RequestParam(value = "shortCode", required = false) String shortCode,
            @RequestParam(value = "channel", required = false) String channel,
            @RequestParam(value = "broken", required = false) Boolean broken) {
        QueryReq req = new QueryReq();
        req.setShortCode(shortCode);
        req.setChannel(channel);
        req.setBroken(broken);
        List<ShortLinkRecord> records = shortLinkService.query(req);
        return ApiResponse.success(records);
    }
}
