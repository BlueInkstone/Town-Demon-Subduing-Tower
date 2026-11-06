package com.towndemontower.delivery.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * [delivery/web] 看板页面入口控制器。
 *
 * <p>Spring Boot 的静态资源处理默认<b>不做</b>"目录 → index.html"的 welcome-page 解析，
 * 因此直接访问 {@code /delivery/} 会返回 404（{@code /delivery/index.html} 才是 200）。
 * 为了让演示时既能用短地址也能用完整地址，这里显式做重定向：</p>
 * <ul>
 *   <li>{@code /} → {@code /delivery/index.html}</li>
 *   <li>{@code /delivery/} → {@code /delivery/index.html}</li>
 *   <li>{@code /delivery} → {@code /delivery/index.html}</li>
 * </ul>
 */
@Controller
public class DeliveryBoardPageController {

    /** 看板页面路径（静态资源位于 classpath:/static/delivery/index.html）。 */
    private static final String BOARD_PAGE = "/delivery/index.html";

    @GetMapping({"/", "/delivery", "/delivery/"})
    public String board() {
        return "redirect:" + BOARD_PAGE;
    }
}
