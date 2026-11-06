package com.towndemontower.delivery.seed;

import com.towndemontower.delivery.domain.DeliveryRepository;
import com.towndemontower.delivery.service.DeliveryProgressService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * [delivery/seed] 交付看板初始数据播种器。
 *
 * <p>仅在 {@code towndemontower.delivery.demo-data=true} 时生效（默认关闭，
 * 避免污染业务方环境）。开启后在仓储为空时写入初始交付项，
 * 使看板首次打开即有内容可展示。</p>
 *
 * <p><b>两种数据模式</b>（由 {@code towndemontower.delivery.seed-mode} 选择）：</p>
 * <ul>
 *   <li>{@code project}（默认）—— <b>本产品自身的真实交付路线图</b>
 *       （见 {@link ProjectRoadmap}），来自仓库真实时间戳，
 *       打开看板即知"这个产品做到哪一步了"；</li>
 *   <li>{@code sample} —— 通用样例数据（与本产品无关的虚构阶段），
 *       用于演示四种状态形态或作为接入方的模板参考。</li>
 * </ul>
 *
 * <p>两种模式都只在仓储为空时播种，因此 file 存储模式下重启不会覆盖已有数据。</p>
 */
@Configuration
public class DeliverySeedInitializer {

    /** 播种模式：project（本产品真实路线图）/ sample（通用样例）。 */
    public static final String SEED_MODE_PROJECT = "project";
    public static final String SEED_MODE_SAMPLE = "sample";

    @Bean
    @ConditionalOnProperty(prefix = "towndemontower.delivery", name = "demo-data",
            havingValue = "true")
    public ApplicationRunner deliverySeedRunner(DeliveryProgressService service,
                                                DeliveryRepository repository,
                                                com.towndemontower.delivery.config.DeliveryProperties props) {
        return args -> {
            if (repository.count() > 0) {
                return; // 已有数据则不播种（file 模式重启时保留）
            }

            String mode = props.getSeedMode();
            if (SEED_MODE_SAMPLE.equalsIgnoreCase(mode)) {
                seedSample(service);
                System.out.println("[delivery] 演示数据已播种：通用样例 4 条"
                        + "（未开始 / 进行中 / 已延期 / 已完成 各 1 条）");
            } else {
                ProjectRoadmap.seed(service, LocalDateTime.now());
                System.out.println("[delivery] 演示数据已播种：Town Demon-Subduing Tower"
                        + " 真实交付路线图 13 条（6 已完成 / 1 已延期 / 1 进行中 / 5 未开始）");
            }
        };
    }

    /**
     * 通用样例数据：覆盖四种状态形态的虚构交付阶段。
     *
     * <p>计划时间以<b>启动时刻</b>为基准相对生成，
     * 因此无论何时启动，四种状态都能稳定呈现（不会因固定日期过期而全部变成"已延期"）。</p>
     */
    private static void seedSample(DeliveryProgressService service) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);

        // ① 已完成：进度 100% + 实际完成时间
        service.create("Phase 0 需求与架构设计", "谢圣",
                now.minusDays(30), now.minusDays(24),
                now.minusDays(23), 100, null);

        // ② 进行中：已开工（进度 60%），计划完成时间尚未到期
        service.create("Phase 1 核心功能开发", "谢圣",
                now.minusDays(14), now.plusDays(7), null, 60, null);

        // ③ 已延期：计划完成时间已过，进度仅 30%
        service.create("Phase 2 联调与缺陷修复", "李四",
                now.minusDays(20), now.minusDays(3), null, 30, null);

        // ④ 未开始：计划开始时间在未来
        service.create("Phase 3 灰度发布", "王五",
                now.plusDays(15), now.plusDays(30), null, 0, null);
    }
}
