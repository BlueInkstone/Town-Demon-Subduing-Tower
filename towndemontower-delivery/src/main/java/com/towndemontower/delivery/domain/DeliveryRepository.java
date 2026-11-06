package com.towndemontower.delivery.domain;

import java.util.List;
import java.util.Optional;

/**
 * [delivery] 交付项仓储抽象。领域层只依赖此接口，存储实现可替换
 * （内存 / 文件 / 数据库），与 core 的 PipelineStateStore 风格一致。
 */
public interface DeliveryRepository {

    /** 保存（新增或覆盖）一个交付项，返回带 id 的最终实例。 */
    DeliveryItem save(DeliveryItem item);

    /** 按 id 查询。 */
    Optional<DeliveryItem> findById(String id);

    /** 全部交付项。 */
    List<DeliveryItem> findAll();

    /** 删除；返回是否确实删除。 */
    boolean deleteById(String id);

    /** 交付项总数。 */
    int count();
}
