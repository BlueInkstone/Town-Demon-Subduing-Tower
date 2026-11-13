package com.towndemontower.agents.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * [agents/web] 花名册页面入口。
 *
 * <p>此前 agents 模块只提供 REST 接口，访问 {@code /agents/} 直接 404——查人只能靠 curl，
 * 而"应然 vs 实然权限"这种对比恰恰是最需要一眼看出来的东西。本控制器只做短地址到静态页的
 * 重定向，页面本身是 {@code classpath:/static/agents/index.html}（单文件、零构建、离线可用）。</p>
 *
 * <p>原因与 delivery 看板一致：Spring Boot 的静态资源处理不做"目录 → index.html"的
 * welcome-page 解析，{@code /agents/} 会 404，{@code /agents/index.html} 才是 200。</p>
 *
 * <p><b>页面是只读的</b>：注册、改档、状态流转这些写操作都要经过治理链（风险闸门 / 审批 / 审计），
 * 不在浏览器里直接发请求——否则等于给治理开了一个绕过口子。</p>
 */
@Controller
public class AgentsRosterPageController {

    /** 花名册页面路径（静态资源位于 classpath:/static/agents/index.html）。 */
    private static final String ROSTER_PAGE = "/agents/index.html";

    @GetMapping({"/", "/agents", "/agents/"})
    public String roster() {
        return "redirect:" + ROSTER_PAGE;
    }
}
