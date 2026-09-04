package com.justabaddream.command;

import com.justabaddream.JABDMod;
import com.justabaddream.capability.DreamStateCapability;
import com.justabaddream.config.JABDConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.List;

/**
 * /baddream 命令（Brigadier，Forge 1.17+ 推荐）
 *
 * <pre>
 * /baddream status                              — 查看自身叠加态状态
 * /baddream status &lt;玩家&gt;                       — 查看指定玩家状态（权限 2）
 * /baddream clear [玩家]                        — 清除叠加态（默认自己；指定玩家需权限 2）
 * /baddream enter [玩家]                        — 强行进入叠加态（不做备份；用于测试）
 * /baddream config trigger &lt;WAKE_UP|LAY|RIGHT_CLICK&gt;   — 热修改触发方式（权限 2）
 * /baddream config totem &lt;true|false&gt;          — 热修改是否判定不死图腾（权限 2）
 * /baddream config slot &lt;MAIN_HAND|OFF_HAND|BOTH_HANDS&gt; — 热修改图腾检查槽位
 * /baddream restore &lt;备份ID&gt;                    — 手动回档到某个备份（管理员调试用）
 * /baddream list                                — 列出当前玩家的所有可用备份
 * /baddream reload                              — 重新加载配置（权限 3）
 * </pre>
 *
 * 旧版本适配：
 * <ul>
 *     <li>1.16.5：Brigadier 也可用；命令注册事件同为 {@code RegisterCommandsEvent}（但参数类名略有不同）</li>
 *     <li>1.12.2：使用 ICommand / CommandBase，注册在 {@code FMLServerStartingEvent}</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = JABDMod.MOD_ID)
public class BadDreamCommands {

    public static final String PREFIX = "baddream";
    public static final String ALIAS  = "jabd";

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    /** 预留：如未来需要注册自定义参数类型 */
    public static void registerArgumentTypes() { /* no-op in 1.20.x */ }

    // ======================================================================
    // 注册命令树
    // ======================================================================
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal(PREFIX)
                .requires(src -> src.hasPermission(0));

        var alias = Commands.literal(ALIAS)
                .requires(src -> src.hasPermission(0));

        // --- status ---
        var status = Commands.literal("status")
                .executes(ctx -> showStatus(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("players", EntityArgument.players())
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> showStatus(ctx.getSource(), EntityArgument.getPlayers(ctx, "players"))));

        // --- clear ---
        var clear = Commands.literal("clear")
                .executes(ctx -> clearDreamState(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("players", EntityArgument.players())
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> clearDreamState(ctx.getSource(), EntityArgument.getPlayers(ctx, "players"))));

        // --- enter (测试用) ---
        var enter = Commands.literal("enter")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> enterDreamState(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("players", EntityArgument.players())
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> enterDreamState(ctx.getSource(), EntityArgument.getPlayers(ctx, "players"))));

        // --- config ---
        var cfgTrigger = Commands.literal("trigger")
                .then(Commands.argument("mode", StringArgumentType.word())
                        .suggests(SUGGEST_TRIGGER_MODES)
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> setTriggerMode(ctx.getSource(), StringArgumentType.getString(ctx, "mode"))));

        var cfgTotem = Commands.literal("totem")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> setTotemCheck(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled"))));

        var cfgSlot = Commands.literal("slot")
                .then(Commands.argument("slot", StringArgumentType.word())
                        .suggests(SUGGEST_TOTEM_SLOTS)
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> setTotemSlot(ctx.getSource(), StringArgumentType.getString(ctx, "slot"))));

        var cfgStrategy = Commands.literal("strategy")
                .then(Commands.argument("mode", StringArgumentType.word())
                        .suggests(SUGGEST_ROLLBACK_STRATEGIES)
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> setRollbackStrategy(ctx.getSource(),
                                StringArgumentType.getString(ctx, "mode"))));

        var cfgMultiplayer = Commands.literal("multiplayer")
                .then(Commands.argument("mode", StringArgumentType.word())
                        .suggests(SUGGEST_MULTIPLAYER_MODES)
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> setMultiplayerMode(ctx.getSource(),
                                StringArgumentType.getString(ctx, "mode"))));

        var cfgIngredient = Commands.literal("ingredient")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("item", StringArgumentType.word())
                        .suggests(SUGGEST_ITEM_IDS)
                        .executes(ctx -> setWarmBedIngredient(ctx.getSource(),
                                StringArgumentType.getString(ctx, "item"), 0))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 3))
                                .executes(ctx -> setWarmBedIngredient(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"),
                                        IntegerArgumentType.getInteger(ctx, "count")))));

        var cfgRecharge = Commands.literal("recharge")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("item", StringArgumentType.word())
                        .suggests(SUGGEST_ITEM_IDS)
                        .executes(ctx -> setRechargeItem(ctx.getSource(),
                                StringArgumentType.getString(ctx, "item"))));

        var cfgOneTime = Commands.literal("onetime")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("value", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                java.util.List.of("true", "false"), builder))
                        .executes(ctx -> setOneTimeUse(ctx.getSource(),
                                StringArgumentType.getString(ctx, "value"))));

        var config = Commands.literal("config")
                .requires(src -> src.hasPermission(2))
                .then(cfgTrigger).then(cfgTotem).then(cfgSlot)
                .then(cfgStrategy).then(cfgMultiplayer)
                .then(cfgIngredient).then(cfgRecharge).then(cfgOneTime);

        // --- panic：调试惊慌效果 ---
        var panicOn = Commands.literal("on")
                .executes(ctx -> applyPanic(ctx.getSource(),
                        List.of(ctx.getSource().getPlayerOrException()),
                        JABDConfig.SERVER.panicDurationTicks.get() / 20)); // 配置秒数，默认 30 秒
        var panicOff = Commands.literal("off")
                .executes(ctx -> removePanic(ctx.getSource(),
                        List.of(ctx.getSource().getPlayerOrException())));
        var panicSec = Commands.literal("seconds")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 3600))
                        .executes(ctx -> applyPanic(ctx.getSource(),
                                List.of(ctx.getSource().getPlayerOrException()),
                                IntegerArgumentType.getInteger(ctx, "seconds"))));
        var panicPlayers = Commands.argument("players", EntityArgument.players())
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("on").executes(ctx -> applyPanic(ctx.getSource(),
                        EntityArgument.getPlayers(ctx, "players"),
                        JABDConfig.SERVER.panicDurationTicks.get() / 20)))
                .then(Commands.literal("off").executes(ctx -> removePanic(ctx.getSource(),
                        EntityArgument.getPlayers(ctx, "players"))))
                .then(Commands.literal("seconds")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 3600))
                                .executes(ctx -> applyPanic(ctx.getSource(),
                                        EntityArgument.getPlayers(ctx, "players"),
                                        IntegerArgumentType.getInteger(ctx, "seconds")))));

        var panic = Commands.literal("panic")
                .requires(src -> src.hasPermission(2) || (src.getEntity() instanceof ServerPlayer))
                .then(panicOn).then(panicOff).then(panicSec).then(panicPlayers);

        // --- restore (管理员手动回档) ---
        var restore = Commands.literal("restore")
                .requires(src -> src.hasPermission(3))
                .then(Commands.argument("backupId", StringArgumentType.word())
                        .executes(ctx -> restoreBackup(ctx.getSource(), StringArgumentType.getString(ctx, "backupId"))));

        // --- list ---
        var list = Commands.literal("list")
                .executes(ctx -> listBackups(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> listBackups(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"))));

        // --- reload ---
        var reload = Commands.literal("reload")
                .requires(src -> src.hasPermission(3))
                .executes(ctx -> reloadConfig(ctx.getSource()));

        // --- 帮助（无参数） ---
        var help = Commands.literal("help").executes(ctx -> showHelp(ctx.getSource()));

        root.then(status).then(clear).then(enter).then(config).then(panic)
            .then(restore).then(list).then(reload).then(help)
            .executes(ctx -> showHelp(ctx.getSource()));

        alias.then(status).then(clear).then(enter).then(config).then(panic)
             .then(restore).then(list).then(reload).then(help)
             .executes(ctx -> showHelp(ctx.getSource()));

        dispatcher.register(root);
        dispatcher.register(alias);
    }

    // ======================================================================
    // 命令实现
    // ======================================================================

    private static int showStatus(CommandSourceStack src, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            DreamStateCapability.get(p).ifPresentOrElse(state -> {
                String line;
                if (state.isInDreamState()) {
                    line = String.format("§6%s §f— 已处于 §a梦境现实叠加态§f，醒来于 tick §e%s§f，备份ID §e%s",
                            p.getGameProfile().getName(),
                            state.getWakeUpTime(),
                            state.getBackupId() == null ? "§c<无>" : state.getBackupId());
                } else {
                    line = String.format("§6%s §f— 未进入叠加态。", p.getGameProfile().getName());
                }
                src.sendSuccess(() -> Component.literal(line), false);
            }, () -> src.sendFailure(Component.literal("§c无法读取 " + p.getGameProfile().getName() + " 的 Capability！")));
        }
        return 1;
    }

    private static int clearDreamState(CommandSourceStack src, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            DreamStateCapability.get(p).ifPresent(state -> {
                state.exitDreamState();
                src.sendSuccess(() -> Component.literal("§a已清除 " + p.getGameProfile().getName() + " 的梦境现实叠加态。"), true);
            });
        }
        return 1;
    }

    private static int enterDreamState(CommandSourceStack src, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            DreamStateCapability.get(p).ifPresent(state -> {
                state.enterDreamState(p.level().getGameTime(), null);
                src.sendSuccess(() -> Component.literal("§e已为 " + p.getGameProfile().getName() + " 注入叠加态（未做存档备份，仅用于测试）。"), true);
            });
        }
        return 1;
    }

    private static int setTriggerMode(CommandSourceStack src, String mode) {
        try {
            JABDConfig.BedTriggerMode m = JABDConfig.BedTriggerMode.valueOf(mode.toUpperCase());
            // 热修改 — 注意：ForgeConfigSpec 值不可直接写；此处暂通过"命令运行时覆盖"来生效
            RuntimeOverrides.triggerMode = m;
            src.sendSuccess(() -> Component.literal("§a床触发方式已设置为：" + m + "（重启服务器或重进世界后恢复配置文件值）"), true);
            return 1;
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("§c无效模式：" + mode + "。可选值 WAKE_UP / LAY / RIGHT_CLICK"));
            return 0;
        }
    }

    private static int setTotemCheck(CommandSourceStack src, boolean enabled) {
        RuntimeOverrides.checkTotem = enabled;
        src.sendSuccess(() -> Component.literal("§a不死图腾判定：" + (enabled ? "启用" : "禁用") + "（运行时生效）"), true);
        return 1;
    }

    private static int setTotemSlot(CommandSourceStack src, String slot) {
        try {
            JABDConfig.TotemCheckSlot s = JABDConfig.TotemCheckSlot.valueOf(slot.toUpperCase());
            RuntimeOverrides.totemSlot = s;
            src.sendSuccess(() -> Component.literal("§a图腾检查槽位：" + s), true);
            return 1;
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("§c无效槽位：" + slot));
            return 0;
        }
    }

    private static int setRollbackStrategy(CommandSourceStack src, String mode) {
        try {
            JABDConfig.RollbackStrategy s = JABDConfig.RollbackStrategy.valueOf(mode.toUpperCase());
            RuntimeOverrides.rollbackStrategy = s;
            String desc = switch (s) {
                case SILENT -> "⭐ 无感回档（不踢出，回归现实过渡 + 惊慌）";
                case SOFT   -> "踢出玩家提示重启";
                case HARD   -> "System.exit 自动重启";
            };
            src.sendSuccess(() -> Component.literal("§a回档策略：" + s + " — " + desc), true);
            return 1;
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("§c无效策略：" + mode + "。可选 SILENT / SOFT / HARD"));
            return 0;
        }
    }

    private static int setMultiplayerMode(CommandSourceStack src, String mode) {
        try {
            JABDConfig.MultiplayerMode m = JABDConfig.MultiplayerMode.valueOf(mode.toUpperCase());
            RuntimeOverrides.multiplayerMode = m;
            String desc = switch (m) {
                case ONE_FOR_ALL   -> "一人噩梦，全员醒来：world 全量回滚 + 全员过场 + 全员提示，仅受害者本人惊慌。";
                case PERSONAL_ONLY -> "一人噩梦，本人痕迹消散：仅恢复玩家自己，不覆盖 world、不影响他人、不提示他人。";
            };
            src.sendSuccess(() -> Component.literal("§a多人模式：" + m + " — " + desc), true);
            return 1;
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("§c无效多人模式：" + mode + "。可选 ONE_FOR_ALL / PERSONAL_ONLY"));
            return 0;
        }
    }

    /**
     * 设置温暖的床合成特殊原料。
     * @param countOrZero 0 = 不设置 count（使用配置默认）；1~3 = 明确覆盖数量
     */
    private static int setWarmBedIngredient(CommandSourceStack src, String itemStr, int countOrZero) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(itemStr);
        if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
            src.sendFailure(Component.literal("§c无效物品 id：" + itemStr + "。例：minecraft:nether_star / minecraft:ghast_tear / minecraft:diamond / minecraft:emerald"));
            return 0;
        }
        int count;
        if (countOrZero > 0) {
            count = Math.max(1, Math.min(3, countOrZero));
            RuntimeOverrides.warmBedIngredientCount = count;
        } else {
            count = JABDConfig.COMMON.warmBedIngredientCount.get();
            RuntimeOverrides.warmBedIngredientCount = null;
        }
        RuntimeOverrides.warmBedIngredient = id.toString();
        net.minecraft.world.item.ItemStack item = new net.minecraft.world.item.ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id));
        String name = item.getHoverName().getString();
        final int fCount = count;
        src.sendSuccess(() -> Component.literal(String.format(
                "§a温暖的床合成原料已设置为：§5%s × %d§a（%s）。修改配方需 /reload 或重进世界。",
                name, fCount, id)), true);
        return 1;
    }

    private static int setRechargeItem(CommandSourceStack src, String itemStr) {
        if ("auto".equalsIgnoreCase(itemStr)) {
            RuntimeOverrides.rechargeItem = "auto";
            src.sendSuccess(() -> Component.literal(
                    "§a充能物品已设置为：§5auto§a（自动取合成原料）。"), true);
            return 1;
        }
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(itemStr);
        if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
            src.sendFailure(Component.literal("§c无效物品 id：" + itemStr + "。例：minecraft:nether_star / minecraft:diamond / auto"));
            return 0;
        }
        RuntimeOverrides.rechargeItem = id.toString();
        net.minecraft.world.item.ItemStack item = new net.minecraft.world.item.ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id));
        String name = item.getHoverName().getString();
        src.sendSuccess(() -> Component.literal(String.format(
                "§a充能物品已设置为：§5%s§a（%s）。", name, id)), true);
        return 1;
    }

    private static int setOneTimeUse(CommandSourceStack src, String value) {
        boolean v;
        try {
            v = Boolean.parseBoolean(value);
        } catch (Exception e) {
            src.sendFailure(Component.literal("§c无效值：" + value + "。请用 true 或 false"));
            return 0;
        }
        RuntimeOverrides.oneTimeUse = v;
        src.sendSuccess(() -> Component.literal(String.format(
                "§a一次性使用已%s§a。%s",
                v ? "开启" : "关闭",
                v ? "睡醒后床将变为未充能，需用充能物品右键恢复。" : "床永远保持充能，可反复入睡。")), true);
        return 1;
    }

    private static int applyPanic(CommandSourceStack src, Collection<ServerPlayer> players, int seconds) {
        int ticks = Math.max(0, Math.min(seconds * 20, 3600 * 20));
        for (ServerPlayer p : players) {
            p.addEffect(new MobEffectInstance(com.justabaddream.registry.JABDMobEffects.PANIC.get(),
                    ticks, 0, false, true, true));
        }
        final int fSec = seconds;
        src.sendSuccess(() -> Component.literal(String.format(
                "§a已对 %d 名玩家施加惊慌效果（%d 秒）", players.size(), fSec)), true);
        return 1;
    }

    private static int removePanic(CommandSourceStack src, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            p.removeEffect(com.justabaddream.registry.JABDMobEffects.PANIC.get());
        }
        src.sendSuccess(() -> Component.literal(String.format(
                "§a已为 %d 名玩家清除惊慌效果", players.size())), true);
        return 1;
    }

    private static int restoreBackup(CommandSourceStack src, String backupId) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("只有玩家才能执行此命令。"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§e开始手动回档，备份ID=" + backupId + "……"), true);
        boolean ok = com.justabaddream.handler.BackupRollbackHandler.manualRestore(player, backupId);
        if (ok) src.sendSuccess(() -> Component.literal("§a回档调度完成。"), true);
        else    src.sendFailure(Component.literal("§c回档失败：备份不存在或参数错误。"));
        return 1;
    }

    private static int listBackups(CommandSourceStack src, ServerPlayer p) {
        src.sendSuccess(() -> Component.literal("§6" + p.getGameProfile().getName() + " 的可用备份："), false);
        var list = com.justabaddream.handler.BackupRollbackHandler.listBackupsFor(p);
        if (list.isEmpty()) src.sendSuccess(() -> Component.literal("  §7— 暂无备份"), false);
        else list.forEach(id -> src.sendSuccess(() -> Component.literal("  §e- " + id), false));
        return 1;
    }

    private static int reloadConfig(CommandSourceStack src) {
        // Forge 的 config reload 需走 net.minecraftforge.fml.config.ConfigTracker；
        // 此处提供一个"最小实现"：清除运行时覆盖
        RuntimeOverrides.clear();
        src.sendSuccess(() -> Component.literal("§a已清除 /baddream 运行时覆盖，恢复到配置文件值。",
                ChatFormatting.GREEN), true);
        return 1;
    }

    private static int showHelp(CommandSourceStack src) {
        String[] help = {
                "§6===== Just A Bad Dream /baddream 命令 ======",
                "§f/baddream status [玩家]              §7— 查看梦境现实叠加态",
                "§f/baddream clear  [玩家]              §7— 清除叠加态",
                "§f/baddream enter  [玩家]              §7— 强行进入叠加态（测试用，不备份）",
                "§f/baddream config trigger <MODE>      §7— 热修改床触发方式",
                "§f/baddream config totem   <T/F>       §7— 热修改图腾判定开关",
                "§f/baddream config slot    <SLOT>      §7— 热修改图腾检查槽位",
                "§f/baddream config strategy <SILENT|SOFT|HARD>  §7— 热修改回档策略",
                "§f/baddream config multiplayer <ONE_FOR_ALL|PERSONAL_ONLY>  §7— 一人死是否影响他人",
                "§f/baddream config ingredient <namespace:item> [count] §7— 设置温暖的床合成原料 + 数量（1~3）",
                "§f/baddream config recharge <namespace:item|auto>     §7— 设置充能物品（auto=取合成原料）",
                "§f/baddream config onetime <true|false>               §7— 一次性使用开关（用完需充能）",
                "§f/baddream panic [玩家*] on|off       §7— 施加/清除惊慌效果",
                "§f/baddream panic [玩家*] seconds <N>  §7— 施加惊慌 N 秒",
                "§f/baddream restore <ID>               §7— 管理员：手动回档",
                "§f/baddream list    [玩家]             §7— 列出可用备份",
                "§f/baddream reload                     §7— 清除运行时覆盖，恢复配置文件值",
                "§7  带 [玩家*] 的参数：仅管理员可用；无参数对自己生效"
        };
        for (String l : help) src.sendSuccess(() -> Component.literal(l), false);
        return 1;
    }

    // ======================================================================
    // Suggestion Providers
    // ======================================================================
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TRIGGER_MODES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(java.util.Arrays.stream(JABDConfig.BedTriggerMode.values()).map(Enum::name), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TOTEM_SLOTS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(java.util.Arrays.stream(JABDConfig.TotemCheckSlot.values()).map(Enum::name), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ROLLBACK_STRATEGIES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(java.util.Arrays.stream(JABDConfig.RollbackStrategy.values()).map(Enum::name), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_MULTIPLAYER_MODES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(java.util.Arrays.stream(JABDConfig.MultiplayerMode.values()).map(Enum::name), builder);

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ITEM_IDS = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(
                    java.util.stream.StreamSupport.stream(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.spliterator(), false)
                            .map(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)),
                    builder);

    // ======================================================================
    // 运行时覆盖（避免反射写 ForgeConfigSpec 内部值；保持命令可热修改）
    // ======================================================================
    public static final class RuntimeOverrides {
        public static volatile JABDConfig.BedTriggerMode triggerMode = null;
        public static volatile Boolean checkTotem = null;
        public static volatile JABDConfig.TotemCheckSlot totemSlot = null;
        public static volatile JABDConfig.RollbackStrategy rollbackStrategy = null;
        public static volatile JABDConfig.MultiplayerMode multiplayerMode = null;
        public static volatile String warmBedIngredient = null;
        public static volatile Integer warmBedIngredientCount = null;
        public static volatile String rechargeItem = null;
        public static volatile Boolean oneTimeUse = null;

        public static void clear() {
            triggerMode = null;
            checkTotem = null;
            totemSlot = null;
            rollbackStrategy = null;
            multiplayerMode = null;
            warmBedIngredient = null;
            warmBedIngredientCount = null;
            rechargeItem = null;
            oneTimeUse = null;
        }

        public static JABDConfig.BedTriggerMode effectiveTrigger() {
            return triggerMode != null ? triggerMode : JABDConfig.COMMON.bedTriggerMode.get();
        }
        public static boolean effectiveCheckTotem() {
            return checkTotem != null ? checkTotem : JABDConfig.COMMON.checkTotemOnRealDeath.get();
        }
        public static JABDConfig.TotemCheckSlot effectiveTotemSlot() {
            return totemSlot != null ? totemSlot : JABDConfig.COMMON.checkTotemSlots.get();
        }
        public static JABDConfig.MultiplayerMode effectiveMultiplayerMode() {
            return multiplayerMode != null ? multiplayerMode : JABDConfig.SERVER.multiplayerMode.get();
        }
    }
}
