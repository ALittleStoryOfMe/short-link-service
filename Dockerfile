# =============================================================================
# 多阶段构建：Stage 1 - Maven 编译打包
# 基础镜像使用 maven:3.8-openjdk-8-slim，体积小、构建快
# =============================================================================
FROM maven:3.8-openjdk-8-slim AS builder

# 设置工作目录
WORKDIR /build

# 优先复制 pom.xml，利用 Docker 层缓存：
# 依赖未变化时跳过 mvn dependency:go-offline，加速重复构建
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# 再复制源码并打包（跳过测试，测试由 CI 流水线单独执行）
COPY src ./src
RUN mvn clean package -DskipTests -B -q

# =============================================================================
# Stage 2 - 运行时镜像
# 使用轻量级 openjdk:8-jre-slim，不含编译工具，减小最终镜像体积
# =============================================================================
FROM openjdk:8-jre-slim AS runtime

# 标签元信息
LABEL maintainer="shortlink-team" \
      app="short-link-service" \
      version="1.0.0"

# 创建非 root 运行用户，提高安全性
RUN groupadd -r appgroup && useradd -r -g appgroup -s /sbin/nologin appuser

# 应用工作目录
WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder /build/target/short-link-service-1.0.0-SNAPSHOT.jar app.jar

# 创建日志目录并授权
RUN mkdir -p /app/logs && chown -R appuser:appgroup /app

# 切换到非 root 用户
USER appuser

# 暴露服务端口
EXPOSE 8080

# 健康检查：每 30s 检测一次，连续 3 次失败标记为 unhealthy
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/short-link/query || exit 1

# JVM 启动参数：
#   UseContainerSupport  自动感知容器 CPU / 内存限制（JDK8u191+）
#   MaxRAMPercentage     堆最多使用容器内存的 75%
#   /dev/urandom         加速 SecureRandom，避免容器启动卡顿
ENTRYPOINT ["java", \
  "-server", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=200", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dfile.encoding=UTF-8", \
  "-jar", "app.jar"]
