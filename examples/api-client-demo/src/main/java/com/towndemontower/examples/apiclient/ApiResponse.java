package com.towndemontower.examples.apiclient;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 一次调用的原始结果：HTTP 状态 + 响应头 + 原始文本 + 解析后的 JSON 树。
 *
 * <p><b>为什么要保留原始文本</b>：解析 JSON 失败时（网关返回 HTML 错误页、空 body、
 * 文本/纯文本响应），如果没有 {@link #body()} 就只能报"JSON 解析失败"，排查时看不到现场。
 * 所以 {@link ApiClient} 永远先存文本，再尝试解析。</p>
 *
 * <p><b>信封识别</b>：本项目的管理接口统一返回 {@code {code,message,data,error}}。
 * {@link #isEnvelope()} 只做"看起来像信封"的结构判断（有 code 字段），
 * 具体"code 非 0 要抛异常"由中间件 {@link Middlewares#unifiedEnvelope()} 决定——
 * 响应对象本身不做业务判断，保持"数据容器"的单一职责。</p>
 */
public final class ApiResponse {

    private final int status;
    private final Map<String, List<String>> headers;
    private final String body;
    private final JsonNode json;

    public ApiResponse(int status, Map<String, List<String>> headers, String body, JsonNode json) {
        this.status = status;
        this.headers = headers == null ? Map.of() : headers;
        this.body = body == null ? "" : body;
        this.json = json;
    }

    public int status() { return status; }

    public Map<String, List<String>> headers() { return headers; }

    public String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    /** 原始响应文本，永远非 null。 */
    public String body() { return body; }

    /** 解析后的 JSON 树；非 JSON 响应为 {@code null}。 */
    public JsonNode json() { return json; }

    public boolean httpSuccess() { return status >= 200 && status < 300; }

    /** 是否是 {@code {code,message,data,error}} 信封。 */
    public boolean isEnvelope() {
        return json != null && json.isObject() && json.has("code");
    }

    /** 业务码：信封取 {@code code}，非信封退化为 HTTP 状态码。 */
    public int code() {
        if (isEnvelope() && json.get("code").canConvertToInt()) return json.get("code").asInt();
        return status;
    }

    /**
     * 统一取数据：信封取 {@code data}，非信封直接返回整棵树。
     * 这样调用方不必关心"这家接口有没有包一层"。
     */
    public JsonNode data() {
        if (isEnvelope()) return json.get("data");
        return json;
    }

    public String message() {
        return isEnvelope() && json.hasNonNull("message") ? json.get("message").asText() : null;
    }

    public String error() {
        return isEnvelope() && json.hasNonNull("error") ? json.get("error").asText() : null;
    }

    /** 供中间件改写响应（例如把上游的裸数组规范成信封）。 */
    public ApiResponse withJson(JsonNode replaced) {
        return new ApiResponse(status, headers, body, replaced);
    }

    /** 日志用：只打印结构与长度，不打印业务数据。 */
    public String describe() {
        String shape = json == null ? "text" : (json.isArray() ? "array[" + json.size() + "]" : "object");
        return "HTTP " + status + " code=" + code() + " body=" + body.length() + "B " + shape;
    }
}
