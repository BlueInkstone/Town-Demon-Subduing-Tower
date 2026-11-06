package com.towndemontower.core.context;

import com.towndemontower.core.mode.AgentMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [V1.2] 文件持久化版 PipelineStateStore（零依赖，纯文本键值落盘）。
 * 满足 FR-13：当前模式 / 子任务 / 门禁结果 / PlanArtifact 引用 / 模型路由记录可落盘与恢复。
 * 真实环境可换成 DB 实现，接口不变（starter 负责装配）。
 */
public class FilePipelineStateStore implements PipelineStateStore {
    private final Path dir;

    public FilePipelineStateStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    private Path file(String taskId) {
        return dir.resolve(sanitize(taskId) + ".state");
    }

    private static String sanitize(String s) {
        return s == null ? "null" : s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    @Override
    public PipelineState load(String taskId) {
        Path p = file(taskId);
        if (!Files.exists(p)) return null;
        try {
            Map<String, String> m = read(p);
            if (!m.containsKey("taskId")) return null;
            PipelineState s = new PipelineState();
            s.taskId = m.get("taskId");
            String mode = m.get("mode");
            s.mode = (mode == null || mode.isEmpty()) ? null : AgentMode.valueOf(mode);
            String st = m.get("subTasks");
            s.subTasks = (st == null || st.isEmpty()) ? new String[0] : st.split(",");
            s.gateResults = m.get("gateResults");
            s.planArtifactRef = m.get("planArtifactRef");
            s.modelRouteRecord = m.get("modelRouteRecord");
            s.lastDecision = m.get("lastDecision");
            s.updatedAt = m.get("updatedAt");
            return s;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void save(String taskId, PipelineState state) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("taskId", state.taskId);
        m.put("mode", state.mode == null ? "" : state.mode.name());
        m.put("subTasks", state.subTasks == null ? "" : String.join(",", state.subTasks));
        m.put("gateResults", state.gateResults == null ? "" : state.gateResults);
        m.put("planArtifactRef", state.planArtifactRef == null ? "" : state.planArtifactRef);
        m.put("modelRouteRecord", state.modelRouteRecord == null ? "" : state.modelRouteRecord);
        m.put("lastDecision", state.lastDecision == null ? "" : state.lastDecision);
        m.put("updatedAt", state.updatedAt == null ? "" : state.updatedAt);
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet())
                sb.append(e.getKey()).append("=").append(e.getValue()).append("\n");
            Files.write(file(taskId), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("save pipeline state failed", e);
        }
    }

    @Override
    public boolean exists(String taskId) {
        return Files.exists(file(taskId));
    }

    private static Map<String, String> read(Path p) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int idx = line.indexOf('=');
            if (idx > 0) m.put(line.substring(0, idx), line.substring(idx + 1));
        }
        return m;
    }
}
