package com.towndemontower.core.routing;

/**
 * [core] 模型画像（语言中立）。costTier 用于快慢分离路由。
 */
public class ModelProfile {
    public final String name;
    public final String provider;
    public final String capability; // strong / fast / small / guardian
    public final String costTier;

    public ModelProfile(String name, String provider, String capability, String costTier) {
        this.name = name;
        this.provider = provider;
        this.capability = capability;
        this.costTier = costTier;
    }

    @Override
    public String toString() {
        return name + "(" + capability + "," + costTier + ")";
    }
}
