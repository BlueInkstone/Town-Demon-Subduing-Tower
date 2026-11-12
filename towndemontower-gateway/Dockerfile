# 墨铠网关（镇妖塔网关形态）—— 多阶段构建，镜像不含源码与构建产物。
#
# 构建（在仓库根目录执行）：
#   docker build -f towndemontower-gateway/Dockerfile -t inkarmor-gateway:0.1.0 .
#
# 运行：
#   docker run --rm -p 8080:8080 \
#     -e INKARMOR_API_TOKEN=你的令牌 \
#     -v "$(pwd)/data/audit:/app/data/audit" \
#     inkarmor-gateway:0.1.0
#
# 注意：本文件已按「thin jar + lib/」分发包结构改写，但当前无 docker 环境的机器上未实测。
# 改动要点（原版三处会直接失败）：
#   1. 仓库没有根 pom，`-pl towndemontower-gateway -am` 无法工作 → 改为按模块 `-f` 顺序构建
#   2. 原版带 `-o`（离线），空镜像里没有本地仓库缓存 → 去掉离线模式
#   3. 原版只拷 jar 不拷 lib/，Manifest 里的 `Class-Path: lib/` 找不到 → 必须一起拷
# 另外只拷 src 不拷 target：增量产物里的旧字节码会被打进镜像（在 Java 8 上启动即崩）。

# ---- 构建阶段 ----
# 构建用 JDK 17：本仓库标准是 core/gateway 编译到 Java 8（class 52），JDK 17 的 javac 支持 --release 8。
# 不要换回 JDK 21/25 —— Spring 侧模块统一到 Java 17（class 61），跨模块产物一律不高于 17。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# core 先进本地仓库：网关按坐标依赖它，不是同仓聚合模块
COPY towndemontower-core/pom.xml towndemontower-core/pom.xml
COPY towndemontower-core/src towndemontower-core/src
RUN mvn -q -f towndemontower-core/pom.xml clean install -DskipTests

COPY towndemontower-gateway/pom.xml towndemontower-gateway/pom.xml
COPY towndemontower-gateway/src towndemontower-gateway/src
RUN mvn -q -f towndemontower-gateway/pom.xml clean package -DskipTests

# ---- 运行阶段 ----
# 分发包字节码为 Java 8（class 52），运行时用 JRE 8 即可
FROM eclipse-temurin:8-jre
WORKDIR /app

COPY --from=build /src/towndemontower-gateway/target/towndemontower-gateway-0.1.0.jar /app/gateway.jar
COPY --from=build /src/towndemontower-gateway/target/lib /app/lib

# 审计卷：进入裁决链的请求会落盘哈希链审计（入口层拒绝不落，见 docs/39 §8）
VOLUME ["/app/data/audit"]

# 配置只认环境变量（application.properties 不被读取）；端口变量名是 PORT，不是 SERVER_PORT
ENV INKARMOR_API_TOKEN=change-me-in-production \
    INKARMOR_GATEWAY_AUDIT_PATH=/app/data/audit/gateway.log \
    PORT=8080

EXPOSE 8080
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/gateway.jar"]
