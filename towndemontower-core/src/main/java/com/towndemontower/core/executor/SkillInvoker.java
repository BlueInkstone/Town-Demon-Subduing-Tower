package com.towndemontower.core.executor;

import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillDescriptor;

/**
 * [core] 真正执行工具的逻辑（沙箱内）。最内层装饰器调用它产出输出。
 */
@FunctionalInterface
public interface SkillInvoker {
    Object invoke(SkillDescriptor d, IntentObject intent);
}
