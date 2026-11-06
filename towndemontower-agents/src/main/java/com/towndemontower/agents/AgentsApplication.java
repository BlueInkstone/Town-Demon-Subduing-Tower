package com.towndemontower.agents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * [agents] Agent 注册表 —— 可独立启动的演示入口。
 *
 * <pre>
 *   mvn -o spring-boot:run          # 默认 http://127.0.0.1:8081/api/agents
 *   java -jar target/towndemontower-agents-0.1.0.jar
 * </pre>
 *
 * <p>启动后：
 * <ul>
 *   <li>REST 接口：{@code http://127.0.0.1:8081/api/agents/**}；</li>
 *   <li>接口文档：{@code http://127.0.0.1:8081/swagger-ui.html}。</li>
 * </ul>
 *
 * <p>演示数据由 {@code AgentSeedInitializer} 播种，仅暴露 REST 接口，不提供页面。</p>
 */
@SpringBootApplication
public class AgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentsApplication.class, args);
    }
}
