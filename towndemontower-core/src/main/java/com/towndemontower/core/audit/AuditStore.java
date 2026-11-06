package com.towndemontower.core.audit;

import java.util.List;

/**
 * [core] 审计存储接口（存储实现可插拔：内存 / DB / 对象存储）。
 * 关键不变量：只能追加写，不能改删。
 *
 * <p><b>V1.9.2 / I4-b 收权</b>：接口上<b>不再有</b> {@code append(AuditRecord)}。
 * 原因很直接——那条口子允许调用方自带 {@code seq} / {@code prevHash} / {@code hash}
 * 造一条记录塞进链里，存储不校验地照收，于是"链"变成"调用方说了算"：
 * 伪造成功的记录会变成一条<b>看似合法</b>的假证据，不成功的会当场把链弄坏。</p>
 *
 * <p>现在唯一的写入口是 {@link #appendAuto(String, String)}：调用方只提供
 * "发生了什么"（action + payload），序号、前驱哈希、本条哈希全部由存储自己算。
 * 越权构造 {@link AuditRecord} 依然可以，但它进不了链——没有写入口。</p>
 *
 * <p><b>这条约束是编译期的，不是运行时的</b>：同包代码与反射仍能写。它挡的是
 * "顺手把记录拼好扔进来"这类正常代码里的错误用法，挡不住已经拿到进程内执行权的人。
 * 后者只能靠进程隔离，不在本框架能力范围内。</p>
 */
public interface AuditStore {

    /**
     * 唯一写入口：调用方只给 action/payload，序号与哈希由实现计算。
     *
     * @param action  动作名（如 {@code SKILL_INVOKED}）
     * @param payload 结构化的"发生了什么"；实现会把换行与制表符替换成空格
     */
    void appendAuto(String action, String payload);

    List<AuditRecord> all();

    /** 整链重算哈希，校验是否被悄悄篡改；中断即返回 false。 */
    boolean verifyChain();
}
