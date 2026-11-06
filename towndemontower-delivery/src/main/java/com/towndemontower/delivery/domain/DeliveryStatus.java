package com.towndemontower.delivery.domain;

/**
 * [delivery] 交付项状态。四个取值与看板筛选一一对应。
 *
 * <p>状态分两类来源：
 * <ul>
 *   <li><b>自动推导</b>（{@link DeliveryStatusResolver}）：由进度百分比与计划完成时间推导，
 *       随时间推移可自行从"进行中"变为"已延期"，无需人工改状态。</li>
 *   <li><b>人工覆盖</b>：交付项可显式指定状态（如项目实际提前完成、或需要挂起），
 *       覆盖值优先于自动推导结果。</li>
 * </ul>
 */
public enum DeliveryStatus {

    /** 未开始：进度 0%，且未到计划开始时间。 */
    NOT_STARTED("未开始"),

    /** 进行中：已有进度但未达 100%，且未超出计划完成时间。 */
    IN_PROGRESS("进行中"),

    /** 已延期：未完成（进度 < 100%），且已超过计划完成时间。 */
    DELAYED("已延期"),

    /** 已完成：进度达到 100%，或已填写实际完成时间。 */
    COMPLETED("已完成");

    private final String label;

    DeliveryStatus(String label) {
        this.label = label;
    }

    /** 中文标签，供看板与接口展示。 */
    public String label() {
        return label;
    }

    /** 是否为终态（不再随时间变化）。 */
    public boolean isFinal() {
        return this == COMPLETED;
    }

    /**
     * 宽松解析：接受枚举名（不区分大小写）、中文标签或中文标签的英文空格形式。
     * 解析失败返回 null（由调用方决定报错文案），避免领域层抛异常。
     */
    public static DeliveryStatus parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        for (DeliveryStatus st : values()) {
            if (st.name().equalsIgnoreCase(s)) return st;
            if (st.label.equals(s)) return st;
            if (st.label.replace(" ", "").equals(s.replace(" ", ""))) return st;
        }
        return null;
    }
}
