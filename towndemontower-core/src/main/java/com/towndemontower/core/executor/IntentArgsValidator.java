package com.towndemontower.core.executor;



import java.util.HashSet;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * [core] 意图参数输入校验（默认开启，失败即拒绝）。
 *
 * <p>治理链里的 Schema 校验只管"必需参数在不在"，不管"参数里塞了什么"。
 * 于是长度、控制字符、路径穿越这些最基础的输入卫生问题会一路带到沙箱与下游系统。
 * 本类在翻译成意图时做一次<b>与业务无关的通用校验</b>：</p>
 *
 * <ul>
 *   <li><b>长度上限</b>：单个字符串值超过上限即拒（starter 默认 1 MiB），
 *       防止用超长参数打爆日志/快照；上限的作用是"给个边界"，不是判断参数是不是正文，
 *       定得过小会误伤合法的大文本写入；</li>
 *   <li><b>控制字符</b>：拒绝 NUL 及除 {@code \n \r \t} 外的控制字符（防终端注入、日志伪造）；</li>
 *   <li><b>路径穿越</b>：路径类参数出现 {@code ..} 段即拒（防越出工作目录）；</li>
 *   <li><b>参数个数</b>：超过上限即拒（默认 64），防止用海量参数绕过白名单式校验。</li>
 * </ul>
 *
 * <p>校验只做"结构卫生"，不判断业务语义——业务语义由工具自己负责，避免把通用层做成
 * 一堆特例判断。所有拒绝都归一到 {@link IntentFormatException}，由审计层记为
 * {@code INTENT_FORMAT_INVALID}，不泄露内部细节。</p>
 */
public final class IntentArgsValidator {

    /** 被视为"路径"的参数名（小写）。 */
    private static final Set<String> PATH_KEYS =
            new HashSet<>(Arrays.asList("path", "file", "filepath", "target", "targetpath", "source", "dest", "dir", "directory"));

    private IntentArgsValidator() {
    }

    public static void validate(Map<String, Object> args, int maxValueLength, int maxArgs,
                               boolean rejectControlChars, boolean rejectPathTraversal) {
        if (args == null) return;
        if (args.size() > maxArgs) {
            throw new IntentFormatException("too many args: " + args.size() + " > " + maxArgs);
        }
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String key = e.getKey();
            if (key == null || key.trim().isEmpty()) {
                throw new IntentFormatException("blank argument name");
            }
            Object v = e.getValue();
            if (!(v instanceof String)) continue;
            String s = (String) v;
            if (s.length() > maxValueLength) {
                throw new IntentFormatException("arg too long: " + key + " len=" + s.length());
            }
            if (rejectControlChars && hasIllegalControlChar(s)) {
                throw new IntentFormatException("illegal control char in arg: " + key);
            }
            if (rejectPathTraversal && PATH_KEYS.contains(key.toLowerCase(Locale.ROOT)) && hasParentTraversal(s)) {
                throw new IntentFormatException("path traversal rejected: " + key);
            }
        }
    }

    /** 允许 \n \r \t（正文内容需要），其余 C0 控制符与 DEL 一律拒绝。 */
    static boolean hasIllegalControlChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') continue;
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    /** 出现 {@code ..} 作为独立路径段即视为穿越（同时覆盖 {@code ../} 与 {@code ..\}）。 */
    static boolean hasParentTraversal(String s) {
        String v = s.replace('\\', '/');
        for (String seg : v.split("/")) {
            if ("..".equals(seg)) return true;
        }
        return false;
    }
}
