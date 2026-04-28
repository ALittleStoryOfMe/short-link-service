// filepath: src/main/java/com/shortlink/ShortLinkApplication.java
package com.shortlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 短链微服务启动类。
 * <p>
 * 纯内存、线程安全、无外部依赖（无 Redis / MySQL / 配置中心）。
 * 存储层由 {@code ConcurrentHashMap} 承担，重启后数据清空。
 * </p>
 */
@SpringBootApplication
public class ShortLinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortLinkApplication.class, args);
    }
}
