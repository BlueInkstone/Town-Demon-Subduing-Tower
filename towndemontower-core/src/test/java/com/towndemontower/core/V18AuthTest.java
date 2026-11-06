package com.towndemontower.core;

import com.towndemontower.core.auth.ApiCallerRegistry;
import com.towndemontower.core.auth.AuthMode;
import com.towndemontower.core.auth.CallerContext;
import com.towndemontower.core.security.SecretPolicy;

import java.util.List;

/**
 * [core/test] V1.8 —— REST 调用者认证内核。
 *
 * <p>覆盖四组不变量：</p>
 * <ol>
 *   <li>密钥强度策略为单一事实来源（门禁密钥与接口令牌同规则）；</li>
 *   <li>令牌表解析只认 {@code 名称:令牌}，非法条目被拒且不泄露令牌内容；</li>
 *   <li>认证在"没配令牌"时 fail-closed，不是"没配就放行"；</li>
 *   <li>调用者上下文不串号（set / currentOr / clear）。</li>
 * </ol>
 */
public class V18AuthTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    /** 合格长度 + 足够字符种类的令牌。 */
    static final String T1 = "8f3c9e1b5d2048a6c3a9e7d1b5f0c8a2";
    static final String T2 = "1a9d7b5f3c1e04862b8d6a4f2e0c9b7d";

    public static void main(String[] args) {
        secretPolicy();
        parseAndReject();
        authenticate();
        statusOfRegistry();
        callerContext();
        authModeParse();
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void secretPolicy() {
        check("策略-合格", SecretPolicy.isAcceptable(T1));
        check("策略-空", SecretPolicy.check(null) == SecretPolicy.Problem.BLANK);
        check("策略-空白", SecretPolicy.check("   ") == SecretPolicy.Problem.BLANK);
        check("策略-太短", SecretPolicy.check("Ab3!xY9@qW2#") == SecretPolicy.Problem.TOO_SHORT);
        check("策略-占位值", SecretPolicy.check("changeme") == SecretPolicy.Problem.PLACEHOLDER);
        check("策略-占位值大小写", SecretPolicy.check("ChangeMe") == SecretPolicy.Problem.PLACEHOLDER);
        check("策略-低熵", SecretPolicy.check("a".repeat(64)) == SecretPolicy.Problem.LOW_ENTROPY);
        check("策略-重复占位值仍低熵或占位",
                SecretPolicy.check("changeme".repeat(4)) != SecretPolicy.Problem.OK);
        check("策略-长度下限常量", SecretPolicy.MIN_LENGTH == 32);
        check("策略-字符种类下限常量", SecretPolicy.MIN_DISTINCT_CHARS == 12);
        check("策略-原因可读", SecretPolicy.describe(SecretPolicy.Problem.TOO_SHORT).contains("32"));
    }

    static void parseAndReject() {
        ApiCallerRegistry r = ApiCallerRegistry.parse("ops:" + T1 + ",ci:" + T2);
        check("解析-两条装配", r.size() == 2);
        check("解析-名称有序", r.callerNames().equals(List.of("ops", "ci")));
        check("解析-无拒绝项", r.rejected().isEmpty());
        check("解析-状态OK", r.status() == ApiCallerRegistry.Status.OK);

        ApiCallerRegistry weak = ApiCallerRegistry.parse("ops:short,ci:changeme");
        check("解析-过短令牌被拒", weak.size() == 0);
        check("解析-拒绝项含原因", weak.rejected().get(0).contains("长度不足"));
        check("解析-占位值被拒", weak.rejected().get(1).contains("占位值"));
        check("解析-弱表状态WEAK", weak.status() == ApiCallerRegistry.Status.WEAK);

        ApiCallerRegistry partial = ApiCallerRegistry.parse("ops:" + T1 + ",bad:123");
        check("解析-部分合格", partial.size() == 1);
        check("解析-部分合格状态PARTIAL", partial.status() == ApiCallerRegistry.Status.PARTIAL);

        ApiCallerRegistry malformed = ApiCallerRegistry.parse("noColonHere");
        check("解析-缺分隔被拒", malformed.size() == 0 && !malformed.rejected().isEmpty());
        check("解析-缺令牌被拒", ApiCallerRegistry.parse("ops:").size() == 0);

        ApiCallerRegistry dup = ApiCallerRegistry.parse("ops:" + T1 + ",ops:" + T2);
        check("解析-重名只留首条", dup.size() == 1 && dup.callerNames().get(0).equals("ops"));
        check("解析-重名给出原因", dup.rejected().get(0).contains("名称重复"));

        ApiCallerRegistry badName = ApiCallerRegistry.parse("op@s:" + T1);
        check("解析-非法名称被拒", badName.size() == 0 && !badName.rejected().isEmpty());

        // 令牌内容不得出现在任何可打印面（名称、拒绝原因、toString）
        String dump = String.join("|", partial.rejected()) + "|" + partial.callerNames();
        check("保密-令牌内容不外泄", !dump.contains(T1));

        check("解析-空串为空表", ApiCallerRegistry.parse("").size() == 0);
        check("解析-空表无环境变量标记", ApiCallerRegistry.empty().status() == ApiCallerRegistry.Status.MISSING);
        check("解析-环境未设置为空表", !ApiCallerRegistry.fromEnv().hasCallers()
                || System.getenv(ApiCallerRegistry.TOKENS_ENV) != null);
    }

    static void authenticate() {
        ApiCallerRegistry r = ApiCallerRegistry.parse("ops:" + T1 + ",ci:" + T2);

        check("认证-正常", r.authenticate("Bearer " + T1).allowed());
        check("认证-身份正确", "ops".equals(r.authenticate("Bearer " + T1).caller()));
        check("认证-第二条可辨身份", "ci".equals(r.authenticate("Bearer " + T2).caller()));
        check("认证-方案大小写不敏感", r.authenticate("bearer " + T1).allowed());
        check("认证-多余空格容忍", r.authenticate("  Bearer   " + T1 + "  ").allowed());

        ApiCallerRegistry.Auth noHeader = r.authenticate(null);
        check("认证-缺头拒绝", !noHeader.allowed());
        check("认证-缺头码", "CALLER_UNAUTHENTICATED".equals(noHeader.code()));

        check("认证-空头拒绝", !r.authenticate("   ").allowed());
        check("认证-方案错误拒绝", !r.authenticate("Basic " + T1).allowed());
        check("认证-无方案拒绝", !r.authenticate(T1).allowed());
        check("认证-令牌为空拒绝", !r.authenticate("Bearer ").allowed());
        check("认证-错令牌拒绝", !r.authenticate("Bearer " + T2.toUpperCase()).allowed());
        check("认证-前缀相同但不同令牌拒绝",
                !r.authenticate("Bearer " + T1.substring(0, T1.length() - 1)).allowed());

        ApiCallerRegistry.Auth empty = ApiCallerRegistry.empty().authenticate("Bearer " + T1);
        check("认证-空表拒绝一切", !empty.allowed());
        check("认证-空表码为令牌不可用", "AUTH_TOKEN_UNAVAILABLE".equals(empty.code()));
        check("认证-空表原因提到环境变量",
                empty.reason().contains(ApiCallerRegistry.TOKENS_ENV));
    }

    static void statusOfRegistry() {
        check("状态-空表MISSING", ApiCallerRegistry.empty().status() == ApiCallerRegistry.Status.MISSING);
        check("状态-未设置环境变量不冒充可用", ApiCallerRegistry.fromEnv().status()
                != ApiCallerRegistry.Status.OK);
    }

    static void callerContext() {
        check("上下文-初始为空", CallerContext.current() == null);
        CallerContext.set("ops");
        check("上下文-写入后可读", "ops".equals(CallerContext.current()));
        check("上下文-有值时不取兜底", "ops".equals(CallerContext.currentOr("fallback")));
        CallerContext.clear();
        check("上下文-清理后为空", CallerContext.current() == null);
        check("上下文-空时取兜底", "fallback".equals(CallerContext.currentOr("fallback")));
        CallerContext.set(null);
        check("上下文-写null等于清理", CallerContext.current() == null);
    }

    static void authModeParse() {
        check("模式-loopback", AuthMode.parse("loopback") == AuthMode.LOOPBACK);
        check("模式-LOOPBACK大写", AuthMode.parse("LOOPBACK") == AuthMode.LOOPBACK);
        check("模式-token", AuthMode.parse("token") == AuthMode.TOKEN);
        check("模式-bearer别名", AuthMode.parse("bearer") == AuthMode.TOKEN);
        check("模式-off", AuthMode.parse("off") == AuthMode.OFF);
        check("模式-none别名", AuthMode.parse("none") == AuthMode.OFF);
        check("模式-空为null", AuthMode.parse("") == null);
        check("模式-未知为null", AuthMode.parse("whatever") == null);
    }
}
