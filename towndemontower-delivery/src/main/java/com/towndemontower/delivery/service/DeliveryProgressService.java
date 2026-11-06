package com.towndemontower.delivery.service;

import com.towndemontower.delivery.domain.DeliveryItem;
import com.towndemontower.delivery.domain.DeliveryRepository;
import com.towndemontower.delivery.domain.DeliveryStatus;
import com.towndemontower.delivery.domain.DeliveryStatusResolver;
import com.towndemontower.delivery.domain.DeliverySummary;
import com.towndemontower.delivery.domain.DeliverySummaryCalculator;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * [delivery] 交付进度服务（纯 Java，零 Spring 依赖）。
 *
 * <p>职责：
 * <ul>
 *   <li>新增 / 更新交付项（含字段级校验）；</li>
 *   <li>按状态筛选交付项；</li>
 *   <li>自动计算整体交付进度汇总。</li>
 * </ul>
 *
 * <p><b>关于"当前时间"</b>：服务用 {@link Supplier} 注入时钟（默认系统时钟），
 * 这样单元测试可以注入固定时钟，精确验证"到期即延期"这类与时间相关的规则，
 * 而不必依赖真实时间导致用例不稳定。</p>
 *
 * <p>本类<b>不直接</b>调用 core 治理链——治理接入由 {@code DeliveryGovernanceFacade}
 * 包装本服务完成，使领域逻辑保持零依赖、可独立复用。</p>
 */
public class DeliveryProgressService {

    private final DeliveryRepository repository;
    private final Supplier<Instant> clock;

    public DeliveryProgressService(DeliveryRepository repository) {
        this(repository, Instant::now);
    }

    public DeliveryProgressService(DeliveryRepository repository, Supplier<Instant> clock) {
        if (repository == null) throw new IllegalArgumentException("repository must not be null");
        this.repository = repository;
        this.clock = clock == null ? Instant::now : clock;
    }

    /** 当前时刻（可注入固定时钟）。 */
    public Instant now() {
        return clock.get();
    }

    /**
     * 新增交付项。
     *
     * @param phaseName      交付阶段名称（必填）
     * @param owner          负责人（必填）
     * @param plannedStart   计划开始时间（必填）
     * @param plannedEnd     计划完成时间（必填，且不早于计划开始时间）
     * @param actualEnd      实际完成时间（可空）
     * @param progress       进度百分比（0~100）
     * @param statusOverride 人工指定状态（可空，空则自动推导）
     */
    public DeliveryItem create(String phaseName,
                                String owner,
                                LocalDateTime plannedStart,
                                LocalDateTime plannedEnd,
                                LocalDateTime actualEnd,
                                Integer progress,
                                DeliveryStatus statusOverride) {
        validate(phaseName, owner, plannedStart, plannedEnd, progress);
        DeliveryItem item = new DeliveryItem(null, phaseName.trim(), owner.trim(),
                plannedStart, plannedEnd, actualEnd,
                progress == null ? 0 : progress, statusOverride);
        return repository.save(item);
    }

    /**
     * 更新交付项（部分更新：null 表示"保持原值"）。
     *
     * @throws DeliveryNotFoundException 交付项不存在
     */
    public DeliveryItem update(String id,
                                String phaseName,
                                String owner,
                                LocalDateTime plannedStart,
                                LocalDateTime plannedEnd,
                                LocalDateTime actualEnd,
                                Integer progress,
                                DeliveryStatus statusOverride) {
        return update(id, phaseName, owner, plannedStart, plannedEnd,
                actualEnd, progress, statusOverride, false);
    }

    /**
     * 更新交付项（部分更新：null 表示"保持原值"）。
     *
     * @param clearStatusOverride true 表示<b>清除</b>人工指定的状态，恢复"自动推导"；
     *                             该标志为 true 时忽略 {@code statusOverride} 参数
     * @throws DeliveryNotFoundException 交付项不存在
     */
    public DeliveryItem update(String id,
                                String phaseName,
                                String owner,
                                LocalDateTime plannedStart,
                                LocalDateTime plannedEnd,
                                LocalDateTime actualEnd,
                                Integer progress,
                                DeliveryStatus statusOverride,
                                boolean clearStatusOverride) {
        DeliveryItem existing = repository.findById(id)
                .orElseThrow(() -> new DeliveryNotFoundException(id));

        DeliveryItem merged = existing.with(phaseName, owner, plannedStart, plannedEnd,
                actualEnd, progress, statusOverride);
        if (clearStatusOverride) {
            merged = merged.withStatusOverride(null);
        }

        // 合并后重新做一次整体校验（覆盖"改字段导致计划起止倒挂"等场景）
        validate(merged.phaseName(), merged.owner(), merged.plannedStart(),
                merged.plannedEnd(), merged.progressPercent());

        return repository.save(merged);
    }

    /** 删除交付项。 */
    public void delete(String id) {
        if (!repository.deleteById(id)) {
            throw new DeliveryNotFoundException(id);
        }
    }

    /** 按 id 查询。 */
    public Optional<DeliveryItem> findById(String id) {
        return repository.findById(id);
    }

    /**
     * 列出交付项，可按状态筛选。
     *
     * @param status 状态过滤条件；null 表示不过滤（返回全部）
     */
    public List<DeliveryItem> list(DeliveryStatus status) {
        Instant now = now();
        List<DeliveryItem> all = repository.findAll();
        if (status == null) {
            return all;
        }
        List<DeliveryItem> filtered = new ArrayList<>();
        for (DeliveryItem i : all) {
            if (DeliveryStatusResolver.resolve(i, now) == status) {
                filtered.add(i);
            }
        }
        return filtered;
    }

    /** 自动计算整体交付进度汇总。 */
    public DeliverySummary summary() {
        return DeliverySummaryCalculator.calculate(repository.findAll(), now());
    }

    public int count() {
        return repository.count();
    }

    /** 字段级校验。 */
    private void validate(String phaseName,
                           String owner,
                           LocalDateTime plannedStart,
                           LocalDateTime plannedEnd,
                           Integer progress) {
        if (phaseName == null || phaseName.trim().isEmpty()) {
            throw new DeliveryValidationException("phaseName", "交付阶段名称不能为空");
        }
        if (owner == null || owner.trim().isEmpty()) {
            throw new DeliveryValidationException("owner", "负责人不能为空");
        }
        if (plannedStart == null) {
            throw new DeliveryValidationException("plannedStart", "计划开始时间不能为空");
        }
        if (plannedEnd == null) {
            throw new DeliveryValidationException("plannedEnd", "计划完成时间不能为空");
        }
        if (plannedEnd.isBefore(plannedStart)) {
            throw new DeliveryValidationException("plannedEnd", "计划完成时间不能早于计划开始时间");
        }
        if (progress != null && (progress < 0 || progress > 100)) {
            throw new DeliveryValidationException("progressPercent", "进度百分比必须在 0~100 之间");
        }
    }
}
