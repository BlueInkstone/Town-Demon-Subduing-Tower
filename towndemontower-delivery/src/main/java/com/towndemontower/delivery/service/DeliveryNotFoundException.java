package com.towndemontower.delivery.service;

/**
 * [delivery] 交付项不存在异常（Web 层映射为 404）。
 */
public class DeliveryNotFoundException extends RuntimeException {

    private final String id;

    public DeliveryNotFoundException(String id) {
        super("delivery item not found: " + id);
        this.id = id;
    }

    public String id() {
        return id;
    }
}
