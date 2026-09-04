package com.justabaddream.config;

import com.justabaddream.JABDMod;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * JABD 配置文件（Forge 1.18+ 推荐的 ForgConfigSpec 方案）。
 *
 * <h3>Common 配置（客户端 / 服务端同时加载）：</h3>
 * <ul>
 *   <li>bedTriggerMode — 温暖的床触发叠加态的方式（立刻入睡 / 成功睡醒 / 右键时）</li>
 *   <li>checkTotemOnRealDeath — 真正死亡判定时是否考虑不死图腾（true=有图腾不死；false=无视图腾也算真死）</li>
 *   <li>checkTotemSlots — 检查哪些槽位（主手、副手、主副两手）</li>
 *   <li>rollbackBroadcast — 回档时是否全服广播</li>
 * </ul>
 *
 * <h3>Server 配置（仅存档维度：每个世界单独保存）：</h3>
 * <ul>
 *   <li>backupDirectory — 备份根目录（相对当前存档目录；亦可绝对路径）</li>
 *   <li>maxBackupPerPlayer — 每个玩家保留的最大备份数（超出后删除最旧）</li>
 *   <li>backupCompression — 是否用 ZIP 压缩备份（否则直接复制目录）</li>
 *   <li>warmBedExplodeInNether — 温暖的床在下界/末地是否依旧爆炸（false=可以安全睡觉）</li>
 * </ul>
 *
 * <h3>版本适配：</h3>
 * 1.16.5 仍使用 ForgeConfigSpec；1.12.2 使用 Configuration / Property。
 */
@Mod.EventBusSubscriber(modid = JABDMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class JABDConfig {

    // ========================================================================
    // Common Config
    // ========================================================================
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    // ========================================================================
    // Server Config（per-world）
    // ========================================================================
    public static final ForgeConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    static {
        ForgeConfigSpec.Builder commonBuilder = new ForgeConfigSpec.Builder();
        COMMON = new Common(commonBuilder);
        COMMON_SPEC = commonBuilder.build();

        ForgeConfigSpec.Builder serverBuilder = new ForgeConfigSpec.Builder();
        SERVER = new Server(serverBuilder);
        SERVER_SPEC = serverBuilder.build();
    }

    // ========================================================================
    // 内部类：Common
    // ========================================================================
    public static class Common {

        /** 触发叠加态的方式 */
        public final ForgeConfigSpec.EnumValue<BedTriggerMode> bedTriggerMode;

        /** 真死判定：是否需要检查不死图腾 */
        public final ForgeConfigSpec.BooleanValue checkTotemOnRealDeath;

        /** 检查图腾的槽位范围 */
        public final ForgeConfigSpec.EnumValue<TotemCheckSlot> checkTotemSlots;

        /** 回档时是否向全服发送消息 */
        public final ForgeConfigSpec.BooleanValue rollbackBroadcast;

        /** 叠加态名称（可自定义语言） — 仅用于调试消息 */
        public final ForgeConfigSpec.ConfigValue<String> statusName;

        /** "正在回归现实…"过渡 Title 的淡入/停留/淡出时长（tick） */
        public final ForgeConfigSpec.ConfigValue<int[]> returnRealityTitleTicks;

        /** 温暖的床配方：替换掉任意一个羊毛位置的"特殊物品"（ResourceLocation）。默认 minecraft:nether_star */
        public final ForgeConfigSpec.ConfigValue<String> warmBedIngredient;
        /** 温暖的床配方：需要的特殊物品数量（1~8；默认 1） */
        public final ForgeConfigSpec.IntValue warmBedIngredientCount;

        Common(ForgeConfigSpec.Builder builder) {
            builder.push("trigger");
            bedTriggerMode = builder
                    .comment("温暖的床进入［梦境现实叠加态］的触发方式：",
                            " WAKE_UP  = 成功睡一觉（夜晚→白天）后触发（默认，推荐）",
                            " LAY     = 玩家躺下（开始睡觉）时立刻触发",
                            " RIGHT_CLICK = 玩家右键床时触发（不需要真的睡）")
                    .defineEnum("bedTriggerMode", BedTriggerMode.WAKE_UP);
            builder.pop();

            builder.push("real_death");
            checkTotemOnRealDeath = builder
                    .comment("真正死亡判定是否考虑不死图腾：",
                            " true  = 玩家主/副手持有不死图腾时被保护，不会触发回档（默认）",
                            " false = 无视不死图腾，任何死亡均算作真死并回档")
                    .define("checkTotemOnRealDeath", true);
            checkTotemSlots = builder
                    .comment("不死图腾检查的槽位：",
                            " MAIN_HAND    = 只检查主手",
                            " OFF_HAND     = 只检查副手",
                            " BOTH_HANDS   = 主手或副手任一（默认）")
                    .defineEnum("checkTotemSlots", TotemCheckSlot.BOTH_HANDS);
            builder.pop();

            builder.push("messages");
            rollbackBroadcast = builder
                    .comment("玩家真正死亡触发回档时是否全服广播")
                    .define("rollbackBroadcast", true);
            statusName = builder
                    .define("statusDisplayName", "梦境现实叠加态");
            returnRealityTitleTicks = builder
                    .comment("回档时“正在回归现实…”全屏 Title 的 [淡入, 停留, 淡出] tick 数",
                            " 20 tick = 1 秒；默认 [10, 60, 20] 约 4.5 秒")
                    .define("returnRealityTitleTicks", new int[]{10, 60, 20});
            builder.pop();

            builder.push("warm_bed_crafting");
            warmBedIngredient = builder
                    .comment("温暖的床配方：用「此物品」（namespace:item）替换掉床配方中的任意一格羊毛位置。",
                            " 示例：minecraft:nether_star（默认 ⭐） / minecraft:ghast_tear（恶魂之泪，推荐中难度）",
                            "       minecraft:diamond（钻石，简单） / minecraft:emerald（绿宝石，经济向）",
                            " 修改后需要 /reload 或重进世界，合成配方才会刷新。")
                    .define("warmBedIngredient", "minecraft:nether_star");
            warmBedIngredientCount = builder
                    .comment("温暖的床配方：需要的特殊物品数量（1 到 3）。默认 1 —— 对应原版床中仅替换一格羊毛。",
                            " 若设置 >1，则配方会要求把多格羊毛换成多个该物品；数量不得超过 3（床 3 羊毛）。")
                    .defineInRange("warmBedIngredientCount", 1, 1, 3);
            builder.pop();
        }
    }

    // ========================================================================
    // 内部类：Server
    // ========================================================================
    public static class Server {

        public final ForgeConfigSpec.ConfigValue<String> backupDirectory;
        public final ForgeConfigSpec.IntValue maxBackupPerPlayer;
        public final ForgeConfigSpec.BooleanValue backupCompression;
        public final ForgeConfigSpec.BooleanValue warmBedExplodeInNether;
        public final ForgeConfigSpec.BooleanValue backupOnDedicatedOnly;
        public final ForgeConfigSpec.IntValue rollbackDelayTicks;
        public final ForgeConfigSpec.EnumValue<RollbackStrategy> rollbackStrategy;
        public final ForgeConfigSpec.IntValue panicDurationTicks;
        public final ForgeConfigSpec.EnumValue<MultiplayerMode> multiplayerMode;
        public final ForgeConfigSpec.BooleanValue lootChests;
        public final ForgeConfigSpec.BooleanValue oneTimeUse;
        public final ForgeConfigSpec.ConfigValue<String> rechargeItem;
        public final ForgeConfigSpec.IntValue wakeInvincibilitySeconds;

        Server(ForgeConfigSpec.Builder builder) {
            builder.push("backup");
            backupDirectory = builder
                    .comment("存档备份根目录。可为相对路径（相对当前 world/ 目录）或绝对路径",
                            " 默认：jabbadream_backups （即在 world/jabbadream_backups/ 下）")
                    .define("backupDirectory", "jabbadream_backups");
            maxBackupPerPlayer = builder
                    .comment("每个玩家最多保留的备份数（超出删除最早的）。设置 0 = 不限（不推荐）")
                    .defineInRange("maxBackupPerPlayer", 3, 0, 128);
            backupCompression = builder
                    .comment("true = 以 .zip 压缩存储备份（省空间）；false = 直接复制整个目录（更快）")
                    .define("backupCompression", true);
            backupOnDedicatedOnly = builder
                    .comment("true = 仅在专用服务器（dedicated）上启用备份回档（防止单人世界误操作时的性能损耗）；false = 单人/局域网也启用")
                    .define("backupOnDedicatedOnly", false);
            rollbackDelayTicks = builder
                    .comment("检测到真死 → 回档之间的延迟（tick，20 tick = 1秒）。",
                            " 用于等待某些模组掉落 / 死亡动画完成；SILENT 模式下默认值：2 tick")
                    .defineInRange("rollbackDelayTicks", 2, 0, 200);
            rollbackStrategy = builder
                    .comment("回档策略：",
                            " SILENT = ⭐ 无感回档（默认推荐）：不踢出玩家，播放「正在回归现实…」加载屏，完成后给予「惊慌」效果。",
                            " SOFT   = 回档后踢出所有玩家，提示重新进入服务器。",
                            " HARD   = 回档后执行 System.exit(0)，由启动脚本自动重启。")
                    .defineEnum("rollbackStrategy", RollbackStrategy.SILENT);
            panicDurationTicks = builder
                    .comment("SILENT 回档后「惊慌」效果持续时长（tick，20 tick = 1 秒）。",
                            " 默认 600 tick = 30 秒；惊慌期间无法入睡，无法与大部分功能方块交互。")
                    .defineInRange("panicDurationTicks", 600, 0, 72000);
            multiplayerMode = builder
                    .comment("多人模式下「一人死亡」时其他玩家如何表现：",
                            " ONE_FOR_ALL   = ⭐ 一人噩梦，全员醒来：死亡触发全员 world 回档 + 全员维度假切换 + 全员提示「xxx 做噩梦了」，仅受害者本人获得惊慌 buff。",
                            " PERSONAL_ONLY = 一人噩梦，本人痕迹消散：只恢复该玩家自己的背包/血量/位置等（不覆盖 world 文件、不重置时间与他人、不提示他人），像防熊插件的个人回滚。")
                    .defineEnum("multiplayerMode", MultiplayerMode.ONE_FOR_ALL);
            builder.pop();

            builder.push("bed_behavior");
            warmBedExplodeInNether = builder
                    .comment("true = 温暖的床在下界/末地像原版床一样爆炸；false = 在下界/末地也能安然入睡")
                    .define("warmBedExplodeInNether", true);
            lootChests = builder
                    .comment("是否允许在战利品箱（地牢、废弃矿井、沙漠神殿等）中开出温暖的床。",
                            " 默认 false（不可从战利品箱获取，只能合成）。",
                            " 设为 true 后，温暖的床会以低概率出现在各类战利品箱中。")
                    .define("lootChests", false);
            oneTimeUse = builder
                    .comment("温暖的床是否为一次性使用（用完需充能）。",
                            " true（默认）= 睡醒后床变为「未充能」状态，纹理变灰，无法入睡；",
                            "       需手持充能物品右键床来恢复充能。",
                            " false = 床永远保持充能状态，可反复入睡。")
                    .define("oneTimeUse", true);
            rechargeItem = builder
                    .comment("用于充能温暖床的物品。",
                            " auto（默认）= 自动使用合成配方中的特殊物品（即 warmBedIngredient 配置值）。",
                            " 也可指定 namespace:item（如 minecraft:diamond）；",
                            "       启动时会校验该物品是否存在于注册表中，不存在则回退到 auto。")
                    .define("rechargeItem", "auto");
            wakeInvincibilitySeconds = builder
                    .comment("噩梦惊醒后玩家的无敌时间（秒）。",
                            " 0 = 无无敌（默认）；最大 5 秒。",
                            " 无敌期间免疫一切伤害（Resistance V），避免惊醒瞬间被怪物补刀。")
                    .defineInRange("wakeInvincibilitySeconds", 0, 0, 5);
            builder.pop();
        }
    }

    // ========================================================================
    // 枚举
    // ========================================================================
    public enum BedTriggerMode {
        WAKE_UP, LAY, RIGHT_CLICK
    }

    public enum TotemCheckSlot {
        MAIN_HAND, OFF_HAND, BOTH_HANDS
    }

    /** 与 BackupRollbackHandler.RollbackStrategy 一一对应，用于配置文件的枚举存储。 */
    public enum RollbackStrategy {
        SILENT, SOFT, HARD
    }

    /** 多人模式下回档的传播方式：一人死亡对其他玩家有何影响 */
    public enum MultiplayerMode {
        /** 一人噩梦，全员醒来：world 文件全量回滚 + 全员维度假切换 + 全员 hotbar 提示，仅受害者惊慌 */
        ONE_FOR_ALL,
        /** 一人噩梦，本人痕迹消散：仅受害者单人 NBT 恢复 + 单人维度假切换，不覆盖 world，其他人零感知 */
        PERSONAL_ONLY
    }

    // ========================================================================
    // 事件：重新加载缓存（Forge 会在配置变化时发事件；此处方便未来做缓存失效）
    // ========================================================================
    @SubscribeEvent
    public static void onLoad(ModConfigEvent.Loading event) {
        // 无需额外处理；ForgeConfigSpec.getValue() 始终读的是已加载的值
    }

    @SubscribeEvent
    public static void onReload(ModConfigEvent.Reloading event) {
        JABDMod.LOGGER.info("[JABD] 配置文件已重新加载。");
    }
}
