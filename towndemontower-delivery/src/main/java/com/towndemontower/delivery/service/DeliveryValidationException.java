package com.towndemontower.delivery.service;

/**
 * [delivery] 交付项校验失败异常。携带字段级错误信息，供 Web 层映射为 400。
 */
public class DeliveryValidationException extends RuntimeException {

    private final String field;

    public DeliveryValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** 出错字段名（如 "phaseName"）；无特定字段时为 null。 */
    public String field() {
        return field;
    }
}
