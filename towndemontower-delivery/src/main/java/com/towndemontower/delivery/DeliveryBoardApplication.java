package com.towndemontower.delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * [delivery] 交付进度看板 —— 可独立启动的演示入口。
 *
 * <p>本模块既可以作为一个 starter 被业务方引入，也可以在此直接启动做演示：
 * <pre>
 *   mvn -o spring-boot:run          # 默认 http://127.0.0.1:8080/delivery/
 *   mvn -o spring-boot:run -Dspring-boot.run.arguments=--server.port=9090
 * </pre>
 *
 * <p>启动后：
 * <ul>
 *   <li>看板页面：{@code http://127.0.0.1:{port}/delivery/}（零 CDN 依赖，离线可用）；</li>
 *   <li>REST 接口：{@code http://127.0.0.1:{port}/api/delivery/**}。</li>
 * </ul>
 *
 * <p>演示数据由 {@code com.towndemontower.delivery.seed.DeliverySeedInitializer} 在首次启动时播种，
 * 默认播种<b>本产品（Town Demon-Subduing Tower）自身的真实交付路线图</b>——
 * 打开看板即可看到各版本里程碑的真实进度与状态；
 * 数据落在内存仓储中，重启即重置（如需持久化，设置
 * {@code towndemontower.delivery.storage=file}）。</p>
 */
@SpringBootApplication
public class DeliveryBoardApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryBoardApplication.class, args);
    }
}
