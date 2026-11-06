package com.towndemontower.delivery.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [delivery] 内存版交付项仓储（默认实现，零外部依赖，线程安全）。
 */
public class InMemoryDeliveryRepository implements DeliveryRepository {

    private final Map<String, DeliveryItem> items = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    @Override
    public DeliveryItem save(DeliveryItem item) {
        if (item == null) throw new IllegalArgumentException("item must not be null");
        DeliveryItem toSave = (item.id() == null || item.id().isBlank())
                ? item.withId(nextId())
                : item;
        items.put(toSave.id(), toSave);
        return toSave;
    }

    @Override
    public Optional<DeliveryItem> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(items.get(id));
    }

    @Override
    public List<DeliveryItem> findAll() {
        List<DeliveryItem> all = new ArrayList<>(items.values());
        // 按计划完成时间升序，便于看板展示"下一个交付节点"
        all.sort(Comparator.comparing(DeliveryItem::plannedEnd,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return all;
    }

    @Override
    public boolean deleteById(String id) {
        return id != null && items.remove(id) != null;
    }

    @Override
    public int count() {
        return items.size();
    }

    /** 生成形如 DLV-0007 的自增 id。 */
    private String nextId() {
        return String.format("DLV-%04d", seq.incrementAndGet());
    }
}
