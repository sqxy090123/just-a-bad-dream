package com.justabaddream.handler;

import com.justabaddream.JABDMod;
import com.justabaddream.block.WarmBedBlock;
import com.justabaddream.block.WarmBedBlockEntity;
import com.justabaddream.capability.DreamStateCapability;
import com.justabaddream.command.BadDreamCommands;
import com.justabaddream.config.JABDConfig;
import com.justabaddream.recipe.WarmBedRecipeSerializer;
import com.justabaddream.registry.JABDMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.event.entity.player.SleepingLocationCheckEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

/**
 * JABD 核心事件处理器。
 *
 * <h3>流程总览</h3>
 * <pre>
 * 玩家右键 温暖的床
 *       │
 *       ├─[trigger=RIGHT_CLICK] ─► 立即 enterDreamState + requestBackup
 *       │
 * 玩家躺下（startSleeping 成功）
 *       │
 *       ├─[trigger=LAY] ─► enterDreamState + requestBackup
 *       │
 * 玩家成功睡醒（WakeUp / finishSleepingTime=100）
 *       │
 *       └─[trigger=WAKE_UP 默认] ─► enterDreamState + requestBackup
 *
 * ════════════════════════════════════════════════════════
 *
 * 玩家 LivingDeathEvent （LOWEST / receiveCanceled=true）
 *       │
 *       └─ 玩家 inDreamState == true
 *              │
 *              ├─ 检测不死图腾（checkTotemOnRealDeath + slot）
 *              │    └─ 有图腾 → 不触发回档（玩家被救）
 *              │
 *              └─ 真正死亡 → 调度 rollbackDelayTicks 后回档
 *                         （先取消死亡，避免玩家被写入死亡 NBT）
 * </pre>
 */
@Mod.EventBusSubscriber(modid = JABDMod.MOD_ID)
public class DreamEventHandler {

    // ========================================================================
    // 调度队列：死亡 → 延迟 tick 后执行回档
    // ========================================================================
    private static final Map<UUID, RollbackTask> PENDING_ROLLBACK = new LinkedHashMap<>();

    private record RollbackTask(UUID playerId, String backupId, int runAtTick,
                                BackupRollbackHandler.RollbackStrategy strategy) {}

    // 回档策略配置 → BackupRollbackHandler 枚举映射
    private static BackupRollbackHandler.RollbackStrategy resolveStrategyFromConfig() {
        JABDConfig.RollbackStrategy cfg = BadDreamCommands.RuntimeOverrides.rollbackStrategy != null
                ? BadDreamCommands.RuntimeOverrides.rollbackStrategy
                : JABDConfig.SERVER.rollbackStrategy.get();
        return switch (cfg) {
            case SILENT -> BackupRollbackHandler.RollbackStrategy.SILENT;
            case SOFT   -> BackupRollbackHandler.RollbackStrategy.SOFT;
            case HARD   -> BackupRollbackHandler.RollbackStrategy.HARD;
        };
    }

    // ========================================================================
    // 1. 床右键 — 用于 RIGHT_CLICK 模式，以及"下届爆炸"配置控制
    // ========================================================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBed(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);

        if (!(state.getBlock() instanceof WarmBedBlock warmBed)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // --- 充能/未充能判定 ---
        boolean charged = state.getValue(WarmBedBlock.CHARGED);
        if (!charged) {
            ItemStack heldItem = player.getMainHandItem();
            // 手持充能物品 → 充能
            if (isRechargeItem(heldItem)) {
                rechargeBed(level, pos, state);
                if (!player.getAbilities().instabuild) heldItem.shrink(1);
                player.sendSystemMessage(Component.translatable("jabbadream.chat.bed_recharged"), true);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
            // 未充能且无充能物品 → 阻止使用
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            player.sendSystemMessage(Component.translatable("jabbadream.chat.bed_uncharged"), true);
            return;
        }

        // --- 爆炸开关：如果配置为 false（下界不炸），此处直接阻止原版 BedBlock 的爆炸分支 ---
        if (!JABDConfig.SERVER.warmBedExplodeInNether.get()) {
            // 通过取消事件并直接走"正常床逻辑"（见下）实现
        }

        // --- RIGHT_CLICK 触发模式 ---
        if (BadDreamCommands.RuntimeOverrides.effectiveTrigger() == JABDConfig.BedTriggerMode.RIGHT_CLICK) {
            triggerDreamEnter(player, findBedHead(level, pos, state), "RIGHT_CLICK");
        }

        // 默认不取消事件，让原版床继续处理（睡觉 / 设置重生点 / 爆炸）
    }

    /** 防止原版床在下界/末地爆炸（当 warmBedExplodeInNether=false 时） */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (state.getBlock() instanceof WarmBedBlock
                && !JABDConfig.SERVER.warmBedExplodeInNether.get()) {
            // 来自 BedBlock#setBedOccupied 或 explosion 分支 — 取消
            event.setCanceled(true);
        }
    }

    // ========================================================================
    // 2. 睡觉躺下 / 睡醒
    // ========================================================================

    /** LAY 模式：玩家尝试在温暖的床上睡觉时触发备份 */
    @SubscribeEvent
    public void onPlayerSleepInBed(PlayerSleepInBedEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var bedPos = event.getOptionalPos();
        if (bedPos.isEmpty()) return;
        BlockState state = player.level().getBlockState(bedPos.get());
        if (!(state.getBlock() instanceof WarmBedBlock)) return;
        if (BadDreamCommands.RuntimeOverrides.effectiveTrigger() == JABDConfig.BedTriggerMode.LAY) {
            triggerDreamEnter(player, findBedHead(player.level(), bedPos.get(), state), "LAY");
        }
    }

    /**
     * WAKE_UP 模式（默认）：玩家成功睡过一觉 → 触发备份 & 进入叠加态。
     * <p>
     * 1.20.1 Forge 提供 {@link PlayerWakeUpEvent}（顶层类）。
     */
    @SubscribeEvent
    public void onPlayerWakeUp(PlayerWakeUpEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        Optional<BlockPos> bedPos = player.getSleepingPos();
        if (bedPos.isEmpty()) return;
        BlockState bs = player.level().getBlockState(bedPos.get());
        if (!(bs.getBlock() instanceof WarmBedBlock warmBed)) return;

        BlockPos headPos = findBedHead(player.level(), bedPos.get(), bs);
        if (headPos != null) {
            BlockEntity be = player.level().getBlockEntity(headPos);
            if (be instanceof WarmBedBlockEntity wbe) {
                wbe.onPlayerWakeUp(player.level().getGameTime());
            }
        }

        if (BadDreamCommands.RuntimeOverrides.effectiveTrigger() == JABDConfig.BedTriggerMode.WAKE_UP) {
            triggerDreamEnter(player, headPos, "WAKE_UP");
        }

        // 一次性使用：睡醒后床变为未充能
        boolean oneTime = BadDreamCommands.RuntimeOverrides.oneTimeUse != null
                ? BadDreamCommands.RuntimeOverrides.oneTimeUse
                : JABDConfig.SERVER.oneTimeUse.get();
        if (oneTime && headPos != null) {
            dischargeBed(player.level(), headPos);
        }
    }

    /** 让温暖的床在任何维度都允许睡觉（当 warmBedExplodeInNether=false 时） */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSleepingCheck(SleepingLocationCheckEvent event) {
        if (event.getEntity().level().isClientSide) return;
        BlockPos pos = event.getSleepingLocation();
        if (pos == null) return;
        BlockState s = event.getEntity().level().getBlockState(pos);
        if (s.getBlock() instanceof WarmBedBlock && !JABDConfig.SERVER.warmBedExplodeInNether.get()) {
            event.setResult(Event.Result.ALLOW);
        }
    }

    // ========================================================================
    // 3. 真正死亡判定 + 回档调度
    // ========================================================================

    /**
     * 死亡事件 — 使用 LOWEST + receiveCanceled=true 作为"最后一道防线"。
     * 当确认叠加态 + 真死（无有效不死图腾保护）时：
     *   (1) setCanceled(true) 阻止原版写入死亡玩家 NBT / 掉落
     *   (2) 安排 rollbackDelayTicks tick 后执行回档
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // 未进入叠加态 → 不处理
        DreamStateCapability.IDreamState state = DreamStateCapability.get(player).resolve().orElse(null);
        if (state == null || !state.isInDreamState()) return;

        // ------ 不死图腾判定 ------
        boolean totemWouldSave = false;
        if (BadDreamCommands.RuntimeOverrides.effectiveCheckTotem()) {
            totemWouldSave = hasValidTotem(player, BadDreamCommands.RuntimeOverrides.effectiveTotemSlot());
        }

        if (totemWouldSave) {
            // 图腾救了一命 → 不算真死，叠加态保留（直到下一次睡眠或主动清除）
            sendHotbar(player, "§e不死图腾触发，叠加态保留。");
            return;
        }

        // ------ 真死！触发回档 ------
        event.setCanceled(true); // 阻止原版死亡流程
        // 恢复生命值，防止下一 tick 再次死亡
        player.setHealth(player.getMaxHealth());
        player.hurtTime = 0;
        player.deathTime = 0;

        String backupId = state.getBackupId();
        int delayTicks = JABDConfig.SERVER.rollbackDelayTicks.get();
        int runAt = (int)(player.server.overworld().getGameTime() + delayTicks);

        // 回档策略：命令 RuntimeOverrides > 配置文件 rollbackStrategy（默认 SILENT）
        BackupRollbackHandler.RollbackStrategy strategy = resolveStrategyFromConfig();
        PENDING_ROLLBACK.put(player.getUUID(), new RollbackTask(player.getUUID(), backupId, runAt, strategy));
        JABDMod.LOGGER.warn("[JABD] 玩家 {} 真正死亡，将在 {} tick 后以 {} 策略回档（backup={}）",
                player.getGameProfile().getName(), delayTicks, strategy, backupId);

        // 多人模式判断（决定要不要广播他人）
        JABDConfig.MultiplayerMode mm = BadDreamCommands.RuntimeOverrides.effectiveMultiplayerMode();
        boolean anyBroadcast = mm == JABDConfig.MultiplayerMode.ONE_FOR_ALL;

        // 不同策略给用户（受害者本人）不同的提示
        if (strategy == BackupRollbackHandler.RollbackStrategy.SILENT) {
            player.displayClientMessage(Component.translatable("jabbadream.chat.rollback.death_self_silent"), true);
        } else {
            player.displayClientMessage(Component.translatable("jabbadream.chat.rollback.death_self_hard"), true);
        }

        if (JABDConfig.COMMON.rollbackBroadcast.get() && anyBroadcast) {
            Component broadcast = (strategy == BackupRollbackHandler.RollbackStrategy.SILENT)
                    ? Component.translatable("jabbadream.chat.rollback.broadcast_silent", player.getGameProfile().getName())
                    : Component.translatable("jabbadream.chat.rollback.broadcast_hard",   player.getGameProfile().getName());
            player.server.getPlayerList().broadcastSystemMessage(broadcast, false);
        }
    }

    // ========================================================================
    // 4. 服务器 tick：执行排队中的回档
    // ========================================================================

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().isStopped()) return;
        if (PENDING_ROLLBACK.isEmpty()) return;

        int now = (int) event.getServer().overworld().getGameTime();
        List<Map.Entry<UUID, RollbackTask>> due = new ArrayList<>();
        for (var it = PENDING_ROLLBACK.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().runAtTick <= now) {
                due.add(e);
                it.remove();
            }
        }
        for (var e : due) {
            UUID pid = e.getKey();
            RollbackTask t = e.getValue();
            ServerPlayer victim = event.getServer().getPlayerList().getPlayer(pid);
            if (victim == null) continue;
            BackupRollbackHandler.triggerRollback(
                    event.getServer(), victim, t.backupId, t.strategy());
        }
    }

    // ========================================================================
    // 5. 惊慌状态（Panic）：阻止入睡
    // ========================================================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPanicPreventSleepingCheck(SleepingLocationCheckEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.hasEffect(JABDMobEffects.PANIC.get())) return;
        event.setResult(Event.Result.DENY);
        sendPanicMessage(player, "jabbadream.chat.panic_sleep");
    }

    // ========================================================================
    // 6. 惊慌状态（Panic）：阻止与功能方块交互
    // ========================================================================

    /**
     * HIGHEST 优先级拦截右键方块：
     * 若玩家带有惊慌效果，且目标方块属于「功能方块」集合 → 取消交互 + 提示"你现在很慌张"。
     * 功能方块集合：通过 BLOCK_CLASS_SET（方块类白名单判断）+ BLOCK_TAG 判断。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onPanicPreventInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.hasEffect(JABDMobEffects.PANIC.get())) return;

        BlockPos pos = event.getPos();
        BlockState state = event.getLevel().getBlockState(pos);
        Block block = state.getBlock();

        // 允许的：纯建筑方块（泥土、石头、原木、玻璃等）不应被阻止。
        // 只要方块属于"功能方块"之一就拦截。
        if (isFunctionalBlock(block)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            sendPanicMessage(player, "jabbadream.chat.panic_interact");
            JABDMod.LOGGER.debug("[JABD-PANIC] 阻止玩家 {} 与 {} 交互",
                    player.getGameProfile().getName(),
                    BuiltInRegistries.BLOCK.getKey(block));
        }
    }

    // ------------------------------------------------------------------------
    // 功能方块判定集合（类名 + 标签双重判定，便于兼容模组方块）
    // ------------------------------------------------------------------------

    /**
     * 发送惊慌提示（hotbar 模式，优先走翻译键 → 国际化文本）
     */
    private static void sendPanicMessage(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    /** 基于 Class：最准确，优先匹配。覆盖全部原版功能方块，且第三方模组对应子类也会命中（如模组自定义工作台继承 CraftingTableBlock）。 */
    @SuppressWarnings("unchecked")
    private static final List<Class<? extends Block>> FUNCTIONAL_BLOCK_CLASSES = List.of(
            // 床（所有 BedBlock 子类，包括 WarmBedBlock 自己）
            BedBlock.class,
            // 工作台
            CraftingTableBlock.class,
            // 熔炉家族
            AbstractFurnaceBlock.class,          // Furnace / BlastFurnace / Smoker
            // 附魔台
            EnchantmentTableBlock.class,
            // 铁砧（含 DamagedAnvilBlock, ChippedAnvilBlock）
            AnvilBlock.class,
            // 酿造台
            BrewingStandBlock.class,
            // 信标
            BeaconBlock.class,
            // 容器类（箱子 / 陷阱箱 / 木桶 / 潜影盒... 全部继承 BaseEntityBlock 其实太广；改用列举 + 标签）
            ChestBlock.class, TrappedChestBlock.class, BarrelBlock.class,
            ShulkerBoxBlock.class, EnderChestBlock.class,
            HopperBlock.class, DispenserBlock.class, DropperBlock.class,
            // 红石/合成辅助
            CraftingTableBlock.class, StonecutterBlock.class, LoomBlock.class,
            CartographyTableBlock.class, FletchingTableBlock.class, SmithingTableBlock.class,
            GrindstoneBlock.class, LecternBlock.class, ComposterBlock.class,
            JukeboxBlock.class, NoteBlock.class, CommandBlock.class, StructureBlock.class,
            JigsawBlock.class, SculkSensorBlock.class, SculkCatalystBlock.class,
            SculkShriekerBlock.class, BeehiveBlock.class, RespawnAnchorBlock.class
    );

    private static boolean isFunctionalBlock(Block block) {
        // 1) Class 判定（命中即返回）
        for (Class<? extends Block> c : FUNCTIONAL_BLOCK_CLASSES) {
            if (c.isInstance(block)) return true;
        }
        // 2) Tag 兜底：MINEABLE_WITH_AXE（箱子、木板）之类不好判；用 BLOCK_TAG 更稳。
        //    模组自带的 MINEABLE / Container 类如果没继承上面的类，至少满足：有 BlockEntity（通常是功能方块）
        //    但纯红石（Lever / Button / PressurePlate）也会被误判 → 我们改为仅"有 Inventory Menu"的 BlockEntity 块
        if (block instanceof EntityBlock eb) {
            // EntityBlock = newBlockEntity 被实现 → 极大概率是功能方块（除了 Sculk、Sign、Bed 等已被 Class 判过）
            // 但 HeadBlock / PistonHeadBlock 等不需要阻止；这里如果玩家右键会打开 GUI 的都是"功能方块"
            // 为了不误判太多，仅排除非 GUI 的 EntityBlock：
            String cn = block.getClass().getSimpleName();
            if (cn.contains("Sign") || cn.contains("Head") || cn.contains("Skull")
                    || cn.contains("Piston") || cn.contains("Banners") || cn.contains("BannerBlock")
                    || cn.contains("Painting") || cn.contains("Frame") || cn.contains("Bamboo")) {
                return false;
            }
            return true;
        }
        return false;
    }

    // ========================================================================
    // 内部工具
    // ========================================================================

    /** 进入梦境现实叠加态（发起异步备份 → 等备份完成写入 Capability） */
    private void triggerDreamEnter(ServerPlayer player, BlockPos headPos, String triggerName) {
        Objects.requireNonNull(player, "player");

        // 若玩家已经处于叠加态 → 先退出（按需求：叠加态持续到下一次睡眠）
        DreamStateCapability.get(player).ifPresent(DreamStateCapability.IDreamState::exitDreamState);

        sendHotbar(player, "§9你躺下，意识开始模糊……（" + triggerName + "）");

        // 异步备份，完毕后把 backupId 写入玩家 Capability
        var future = BackupRollbackHandler.requestBackup(player);
        future.thenAcceptAsync(backupId -> {
            if (backupId == null) {
                sendHotbar(player, "§c[JABD] 备份失败，叠加态未激活。详见日志。");
                return;
            }
            // 写 Capability 必须主线程
            player.server.execute(() -> DreamStateCapability.get(player).ifPresent(s -> {
                s.enterDreamState(player.level().getGameTime(), backupId);
                String name = JABDConfig.COMMON.statusName.get();
                sendHotbar(player, String.format("§a✨ 已进入 §6%s§a（备份ID §e%s§a）", name, backupId));
                JABDMod.LOGGER.info("[JABD] {} → 进入{}，backupId={}",
                        player.getGameProfile().getName(), name, backupId);
            }));
        }, player.server);
    }

    /** 根据床的任意半段（FOOT/HEAD），返回 HEAD 的坐标（用于 BlockEntity 读写） */
    private static BlockPos findBedHead(Level level, BlockPos any, BlockState state) {
        if (state.getBlock() instanceof WarmBedBlock || state.getBlock() instanceof BedBlock) {
            BedPart part = state.getValue(BedBlock.PART);
            if (part == BedPart.HEAD) return any;
            // FOOT → 朝向的反方向两格外
            BlockPos head = any.relative(state.getValue(BedBlock.FACING));
            BlockState headState = level.getBlockState(head);
            if (headState.hasProperty(BedBlock.PART) && headState.getValue(BedBlock.PART) == BedPart.HEAD) {
                return head;
            }
        }
        return any;
    }

    // ========================================================================
    // 充能 / 放电 辅助方法
    // ========================================================================

    /** 将床（HEAD + FOOT）设为未充能状态 */
    private static void dischargeBed(Level level, BlockPos headPos) {
        BlockState headState = level.getBlockState(headPos);
        if (!(headState.getBlock() instanceof WarmBedBlock)) return;
        level.setBlock(headPos, headState.setValue(WarmBedBlock.CHARGED, false), 3);
        // FOOT 在朝向的反方向
        BlockPos footPos = headPos.relative(headState.getValue(BedBlock.FACING).getOpposite());
        BlockState footState = level.getBlockState(footPos);
        if (footState.getBlock() instanceof WarmBedBlock) {
            level.setBlock(footPos, footState.setValue(WarmBedBlock.CHARGED, false), 3);
        }
    }

    /** 将床（当前格 + 另一半）恢复为充能状态 */
    private static void rechargeBed(Level level, BlockPos clickedPos, BlockState clickedState) {
        level.setBlock(clickedPos, clickedState.setValue(WarmBedBlock.CHARGED, true), 3);
        // 找另一半
        BedPart part = clickedState.getValue(BedBlock.PART);
        BlockPos otherPos = (part == BedPart.HEAD)
                ? clickedPos.relative(clickedState.getValue(BedBlock.FACING).getOpposite())
                : clickedPos.relative(clickedState.getValue(BedBlock.FACING));
        BlockState otherState = level.getBlockState(otherPos);
        if (otherState.getBlock() instanceof WarmBedBlock) {
            level.setBlock(otherPos, otherState.setValue(WarmBedBlock.CHARGED, true), 3);
        }
    }

    /** 判断手持物品是否为有效的充能物品 */
    private static boolean isRechargeItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item target = getEffectiveRechargeItem();
        return stack.is(target);
    }

    /**
     * 获取有效的充能物品：
     * RuntimeOverrides > 配置 rechargeItem
     * "auto" → 取 WarmBedRecipeSerializer.effectiveIngredientId()（即合成原料）
     * 自定义 namespace:item → 校验注册表存在；不存在则 fallback 到 nether_star
     */
    private static Item getEffectiveRechargeItem() {
        String configVal = BadDreamCommands.RuntimeOverrides.rechargeItem != null
                ? BadDreamCommands.RuntimeOverrides.rechargeItem
                : JABDConfig.SERVER.rechargeItem.get();
        if ("auto".equalsIgnoreCase(configVal)) {
            // 取合成配方中的特殊物品
            ResourceLocation id = WarmBedRecipeSerializer.effectiveIngredientId();
            return BuiltInRegistries.ITEM.get(id);
        }
        ResourceLocation id = ResourceLocation.tryParse(configVal);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            return BuiltInRegistries.ITEM.get(id);
        }
        JABDMod.LOGGER.warn("[JABD] rechargeItem 配置无效：{}，回退到 auto（合成原料）", configVal);
        return BuiltInRegistries.ITEM.get(WarmBedRecipeSerializer.effectiveIngredientId());
    }

    /** 检测玩家是否持有有效不死图腾（按配置槽位） */
    private static boolean hasValidTotem(ServerPlayer player, JABDConfig.TotemCheckSlot slot) {
        ItemStack main = player.getMainHandItem();
        ItemStack off  = player.getOffhandItem();
        return switch (slot) {
            case MAIN_HAND  -> isTotem(main);
            case OFF_HAND   -> isTotem(off);
            case BOTH_HANDS -> isTotem(main) || isTotem(off);
        };
    }

    private static boolean isTotem(ItemStack s) {
        return s != null && s.is(Items.TOTEM_OF_UNDYING);
    }

    /** 发一条 Hotbar 提示（不占用聊天栏，更贴近原版床反馈） */
    private static void sendHotbar(ServerPlayer player, String msg) {
        player.displayClientMessage(Component.literal(msg).withStyle(s -> s.withColor(ChatFormatting.RESET)), true);
    }

    // ========================================================================
    // 调试：玩家断开时清理回档队列
    // ========================================================================
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            PENDING_ROLLBACK.remove(sp.getUUID());
        }
    }
}
