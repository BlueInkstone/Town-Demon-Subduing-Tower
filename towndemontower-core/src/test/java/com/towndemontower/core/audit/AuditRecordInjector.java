package com.towndemontower.core.audit;

import java.util.List;

/**
 * <b>仅测试用</b>：模拟"已经拿到同包可见性"的攻击者，把构造好的记录塞进链。
 *
 * <p>放在 {@code com.towndemontower.core.audit} 包里，是为了能在 V1.9.2 收权之后
 * 继续验证"整链重算""单点篡改"这两类攻击的检测能力——生产代码里没有对应的公开入口，
 * 这正是 I4-b 要达到的效果。放在 test 源集里，因此不会进发布包。</p>
 *
 * <p>它同时也是一份提醒：{@code appendRecord} 只是包内可见，不是不可见。谁能在同包
 * 写代码、或用反射，谁就能绕开收权。收权是编译期约束，运行期隔离不归这一层管。</p>
 */
public final class AuditRecordInjector {

    private AuditRecordInjector() {
    }

    public static void inject(HashChainAuditStore store, List<AuditRecord> records) {
        for (AuditRecord r : records) {
            store.appendRecord(r);
        }
    }
}
