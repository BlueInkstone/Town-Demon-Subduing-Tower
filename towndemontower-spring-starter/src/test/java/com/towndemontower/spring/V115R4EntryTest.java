package com.towndemontower.spring;

import com.towndemontower.core.bypass.BypassFinding;
import com.towndemontower.core.bypass.BypassScanner;
import com.towndemontower.core.contract.TimeProvider;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Instant;

/**
 * [V1.15] CP10-R4 收口验证：Controller 不再注入业务 Service，R4 警告归零。
 *
 * <p>为什么要有这组断言：R4 是语法级检查（只看 Controller 的构造参数类型名），
 * 唯一能让它归零且不放宽规则的办法，就是让 Controller 真的不依赖 {@code *Service}。
 * 这组用例把这个"真的"钉住——以后谁再把 Service 塞回 Controller 的构造器，这里立刻红。</p>
 */
public class V115R4EntryTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        timeProviderContract();
        controllersDoNotDependOnServices();
        autowiredConstructorIsUnambiguous();
        scannerReportsNoR4();

        System.out.println();
        System.out.println("RESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // 时间契约本身
    // ------------------------------------------------------------------

    static void timeProviderContract() {
        Instant frozen = Instant.parse("2026-01-01T00:00:00Z");
        check("fixed-冻结时刻", TimeProvider.fixed(frozen).now().equals(frozen));
        check("fixed-拒绝 null", throwsIae(() -> TimeProvider.fixed(null)));
        check("of-委托既有时间源", TimeProvider.of(() -> frozen).now().equals(frozen));
        check("of-拒绝 null", throwsIae(() -> TimeProvider.of(null)));
        check("system-可用且非空", TimeProvider.system() != null && TimeProvider.system().now() != null);
    }

    // ------------------------------------------------------------------
    // Controller 依赖面
    // ------------------------------------------------------------------

    static void controllersDoNotDependOnServices() throws Exception {
        assertNoServiceDependency("com.towndemontower.agents.web.AgentController");
        assertNoServiceDependency("com.towndemontower.delivery.web.DeliveryController");
    }

    static void assertNoServiceDependency(String className) throws Exception {
        Class<?> clazz = Class.forName(className);
        Constructor<?>[] ctors = clazz.getDeclaredConstructors();
        check(className + "-有构造器", ctors.length > 0);
        for (Constructor<?> c : ctors) {
            for (Class<?> p : c.getParameterTypes()) {
                check(className + "-构造参数不依赖业务 Service（" + p.getSimpleName() + "）",
                        !p.getSimpleName().endsWith("Service"));
            }
        }
    }

    /**
     * Spring 对"多个构造器且无默认构造器"的类需要显式 {@code @Autowired}，
     * 否则启动期会因歧义失败。这里钉住装配入口那一组。
     */
    static void autowiredConstructorIsUnambiguous() throws Exception {
        check("AgentController-装配构造器已标注 @Autowired",
                hasAutowired("com.towndemontower.agents.web.AgentController"));
        check("DeliveryController-装配构造器已标注 @Autowired",
                hasAutowired("com.towndemontower.delivery.web.DeliveryController"));
    }

    static boolean hasAutowired(String className) throws Exception {
        Class<?> clazz = Class.forName(className);
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 扫描复核：R4 应为 0
    // ------------------------------------------------------------------

    static void scannerReportsNoR4() throws Exception {
        BypassScanner.ScanResult agents =
                new BypassScanner().scan(Path.of("..", "towndemontower-agents", "src", "main", "java"), false);
        BypassScanner.ScanResult delivery =
                new BypassScanner().scan(Path.of("..", "towndemontower-delivery", "src", "main", "java"), false);

        check("扫描-agents 无 R4", r4Count(agents) == 0);
        check("扫描-delivery 无 R4", r4Count(delivery) == 0);
        check("扫描-agents 无违规", agents.violations.isEmpty());
        check("扫描-delivery 无违规", delivery.violations.isEmpty());
    }

    static long r4Count(BypassScanner.ScanResult r) {
        long n = 0;
        for (BypassFinding f : r.warnings) {
            if (BypassScanner.R4.equals(f.ruleId)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------

    interface ThrowingRunnable { void run(); }

    static boolean throwsIae(ThrowingRunnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
