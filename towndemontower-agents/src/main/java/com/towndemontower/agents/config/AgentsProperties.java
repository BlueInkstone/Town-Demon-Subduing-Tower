package com.towndemontower.agents.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * [agents] 注册表配置属性（前缀 {@code towndemontower.agents}）。
 */
@ConfigurationProperties(prefix = "towndemontower.agents")
public class AgentsProperties {

    /** 总开关；默认开启。 */
    private boolean enabled = true;

    /** 是否把注册表写操作接入 core 治理链（审计哈希链 + 风险闸门）；默认开启。 */
    private boolean governed = true;

    /** 注册表自身的 agentId（RBAC 主体，需持有 agents:read / agents:write）。 */
    private String agentId = "agents-api-agent";

    /** 持久化方式：{@code memory}（默认，内存）或 {@code file}（落盘）。 */
    private String storage = "memory";

    /** file 模式下的数据文件路径。 */
    private String dataFile = "agents.data";

    /** 是否在首次启动时播种演示数据；默认关闭（演示时打开）。 */
    private boolean demoData = false;

    /**
     * 播种数据模式（配合 {@code demoData=true}）：
     * {@code default}（默认）—— 覆盖四状态五角色的演示数据；
     * {@code sample} —— 精简样例（四种状态各一）。
     */
    private String seedMode = "default";

    /**
     * REST 调用者认证模式（V1.8）：
     * {@code loopback}（默认）—— 读放行、写只允许本机；
     * {@code token} —— 所有 /api/** 必须带 Bearer 令牌，令牌只读环境变量
     * {@code TOWNDEMONTOWER_API_TOKENS}，未配置即全部拒绝；
     * {@code off} —— 不校验（仅限外层网关已鉴权的部署）。
     */
    private String authMode = "loopback";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isGoverned() {
        return governed;
    }

    public void setGoverned(boolean governed) {
        this.governed = governed;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getStorage() {
        return storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }

    public String getDataFile() {
        return dataFile;
    }

    public void setDataFile(String dataFile) {
        this.dataFile = dataFile;
    }

    public boolean isDemoData() {
        return demoData;
    }

    public void setDemoData(boolean demoData) {
        this.demoData = demoData;
    }

    public String getSeedMode() {
        return seedMode;
    }

    public void setSeedMode(String seedMode) {
        this.seedMode = seedMode;
    }

    public String getAuthMode() {
        return authMode;
    }

    public void setAuthMode(String authMode) {
        this.authMode = authMode;
    }
}
