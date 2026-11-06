package com.towndemontower.agents.domain;

import java.util.Optional;
import java.util.List;

/**
 * [agents] Agent 档案仓储抽象。领域层只依赖此接口，存储实现可替换
 * （内存 / 文件 / 数据库），与 core {@code PipelineStateStore} 的风格一致。
 */
public interface AgentRepository {

    /** 保存（新增或覆盖）一个 Agent，返回带 ID 的最终实例。 */
    Agent save(Agent agent);

    /** 按 ID 查询。 */
    Optional<Agent> findById(String id);

    /** 按名称查询（忽略首尾空白与大小写），用于"同名不可重复注册"校验。 */
    Optional<Agent> findByName(String name);

    /** 全部 Agent，按 ID 升序。 */
    List<Agent> findAll();

    /** 删除；返回是否确实删除。 */
    boolean deleteById(String id);

    /** Agent 总数。 */
    int count();
}
