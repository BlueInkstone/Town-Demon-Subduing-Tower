package com.towndemontower.spring;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * [starter/test] 仅在手动验证（AnnotationConfigApplicationContext）时启用 AspectJ 自动代理。
 * 真实 Spring Boot 应用由 spring-boot-starter-aop 的 AopAutoConfiguration 自动开启，无需此类。
 */
@Configuration
@EnableAspectJAutoProxy
public class TestAopConfig {
}
