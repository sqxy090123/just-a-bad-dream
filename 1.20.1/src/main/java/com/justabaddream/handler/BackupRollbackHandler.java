package com.justabaddream.handler;

import com.justabaddream.JABDMod;
import com.justabaddream.config.JABDConfig;
import com.justabaddream.config.JABDConfig.MultiplayerMode;
import com.justabaddream.config.JABDConfig.RollbackStrategy;
import com.justabaddream.registry.JABDMobEffects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 存档备份 / 回档核心处理器。
 * <p>
 * 设计目标：
 * <ul>
 *   <li>在温暖的床"触发"时（默认是成功睡醒后），后台异步将当前整个 {@code world/} 存档目录
 *       （包括 region、playerdata、data、advancements、stats、poi、entities、level.dat 等）
 *       全量拷贝至备份目录；以"玩家 UUID + 时间戳"作为一个备份 ID 目录。</li>
 *   <li>当该玩家"真正死亡"时，先停止存档 IO、保存改动、再用备份覆盖 world 目录完成回档。</li>
 *   <li>回档策略默认 SOFT（踢出玩家提示需重启）；可通过配置或命令切换为 HARD（System.exit 自动重启）。</li>
 * </ul>
 *
 * <h3>并发安全</h3>
 * 备份使用独立线程池 {@link #BACKUP_EXECUTOR}，防止主线程卡顿。
 * 回档必须主线程同步执行（因为要关闭/重启 IO 层）。
 *
 * <h3>版本适配</h3>
 * <ul>
 *   <li>1.20.1 / 1.19.x：{@code server.storageSource}（LevelStorageSource.LevelStorageAccess）</li>
 *   <li>1.18.2：与 1.19.x 基本一致；1.17.x 仍为 {@code DimensionSource}。</li>
 *   <li>1.16.5：存档访问使用 {@code MinecraftServer#saveDataFolder} + File API；没有 LevelResource。</li>
 *   <li>1.12.2：{@code MinecraftServer#getFile(String)}，结构为 world/DIM-1/region 等。</li>
 * </ul>
 */
public class BackupRollbackHandler {

    /** 后台备份线程池 — 2 线程足够（备份 IO 密集，避免占用太多 CPU）*/
    private static final ExecutorService BACKUP_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "JABD-Backup-Worker");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final DateTimeFormatter BACKUP_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** 每玩家备份队列：避免短时间重复点击床产生 N 个备份（仅保留最后一次任务） */
    private static final Map<UUID, CompletableFuture<String>> PENDING_BACKUPS =
            Collections.synchronizedMap(new WeakHashMap<>());

    // =========================================================================
    // 对外 API：触发备份
    // =========================================================================

    /**
     * 在温暖的床触发时调用 — 异步执行全量备份。
     *
     * @param player  目标玩家（备份按玩家分目录、每玩家保留 N 份）
     * @return 备份 future；完成后返回新的 backupId（可用于写入玩家 Capability）
     */
    public static CompletableFuture<String> requestBackup(@NotNull ServerPlayer player) {
        if (!isBackupAllowed(player.server)) {
            return CompletableFuture.completedFuture(null);
        }

        // 若已有同玩家的进行中备份：直接复用其结果
        synchronized (PENDING_BACKUPS) {
            CompletableFuture<String> existing = PENDING_BACKUPS.get(player.getUUID());
            if (existing != null && !existing.isDone()) return existing;

            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return doBackup(player);
                } catch (Throwable t) {
                    JABDMod.LOGGER.error("[JABD] 备份失败，玩家 {}：", player.getGameProfile().getName(), t);
                    return null;
                }
            }, BACKUP_EXECUTOR).whenComplete((id, err) -> PENDING_BACKUPS.remove(player.getUUID()));

            PENDING_BACKUPS.put(player.getUUID(), future);
            return future;
        }
    }

    // =========================================================================
    // 对外 API：查询备份
    // =========================================================================

    /** 列出玩家可用备份的 ID（按时间倒序） */
    public static List<String> listBackupsFor(@NotNull ServerPlayer player) {
        Path backupRoot = resolvePlayerBackupDir(player);
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(backupRoot)) return ids;
        try (Stream<Path> s = Files.list(backupRoot)) {
            s.filter(Files::isDirectory)
             .map(p -> p.getFileName().toString())
             .sorted(Comparator.reverseOrder())
             .forEach(ids::add);
        } catch (IOException ignored) {}
        return ids;
    }

    // =========================================================================
    // 对外 API：手动回档（/baddream restore <id>）
    // =========================================================================

    public static boolean manualRestore(@NotNull ServerPlayer player, @NotNull String backupId) {
        Path dir = resolvePlayerBackupDir(player).resolve(backupId);
        if (!Files.isDirectory(dir)) {
            // 也可能是 zip
            Path zip = dir.getParent().resolve(backupId + ".zip");
            if (!Files.isRegularFile(zip)) return false;
        }
        triggerRollback(player.server, player, backupId, RollbackStrategy.SOFT);
        return true;
    }

    // =========================================================================
    // 核心：备份执行
    // =========================================================================

    @Nullable
    private static String doBackup(ServerPlayer player) throws IOException {
        MinecraftServer server = player.server;
        // 备份根目录：world/jabbadream_backups/players/<uuid>/<timestamp>
        Path backupRoot = resolvePlayerBackupDir(player);
        Files.createDirectories(backupRoot);

        String backupId = LocalDateTime.now().format(BACKUP_TS) + "_"
                        + Integer.toHexString(System.identityHashCode(server)).substring(0, 4);
        Path target = backupRoot.resolve(backupId);

        // 先保存一次当前世界，确保备份点内容就是"玩家醒来后"的准确快照
        server.executeBlocking(() -> saveAllNow(server));

        Path worldRoot = getWorldRootPath(server);

        boolean useZip = JABDConfig.SERVER.backupCompression.get();
        if (useZip) {
            Path zipFile = backupRoot.resolve(backupId + ".zip");
            zipDirectory(worldRoot, zipFile, target.getFileName().toString());
            // 为保持 API 一致（回档时按目录判断），保留一个空目录指向 zip
            Files.createDirectories(target);
            Files.writeString(target.resolve(".zip"), zipFile.getFileName().toString());
        } else {
            copyDirectory(worldRoot, target);
        }

        // 清理超出上限的旧备份
        int max = JABDConfig.SERVER.maxBackupPerPlayer.get();
        if (max > 0) pruneOldBackups(backupRoot, max);

        JABDMod.LOGGER.info("[JABD] 备份完成：玩家={} id={} size~={}",
                player.getGameProfile().getName(), backupId, dirSizeHuman(target));
        return backupId;
    }

    // =========================================================================
    // 核心：回档触发（必须主线程调用）
    // =========================================================================

    public enum RollbackStrategy {
        /**
         * ⭐ 无感回档（默认）：
         * 1. 立即从备份快照还原目标玩家的背包/血量/位置/经验（NBT 级）；
         * 2. 播放「正在回归现实…」全屏 Title 并冻结玩家；
         * 3. 后台线程用备份覆盖 world 文件；
         * 4. 对所有玩家执行"维度假切换"（= 客户端触发「正在加入世界中 / Loading Terrain」屏）；
         * 5. 对触发玩家施加「惊慌」效果。
         */
        SILENT,
        /** 还原 world 文件后踢出全部玩家并告知需要重启 */
        SOFT,
        /** 还原后调用 System.exit(0)，由启动脚本/面板自动重启服务器 */
        HARD
    }

    /**
     * 执行回档 — 按配置选择策略（默认 SILENT）。
     *
     * @param server    服务器实例
     * @param who       触发回档的玩家（用于消息）
     * @param backupId  目标备份 ID（若为空则取其 Capability 中记录的）
     * @param strategy  回档策略（SILENT / SOFT / HARD）
     */
    public static void triggerRollback(@NotNull MinecraftServer server,
                                       @Nullable ServerPlayer who,
                                       @Nullable String backupId,
                                       @NotNull RollbackStrategy strategy) {
        // 若传入的是 null，读取配置的默认策略（一般还是 SILENT）
        JABDMod.LOGGER.warn("[JABD] ======== 开始执行存档回档（策略={}） ========", strategy);
        JABDMod.LOGGER.warn("[JABD] 触发者: {} | 备份ID: {}",
                who == null ? "<admin>" : who.getGameProfile().getName(), backupId);

        Path backupSrc = findBackupSource(server, who, backupId);
        if (backupSrc == null) {
            JABDMod.LOGGER.error("[JABD] 回档中止：找不到可用备份源");
            return;
        }

        if (strategy == RollbackStrategy.SILENT) {
            triggerSilentRollback(server, who, backupSrc);
        } else {
            triggerHardRollback(server, who, backupSrc, strategy);
        }
    }

    // ---------------------------------------------------------------
    // SILENT 无感回档（3 阶段）—— 按 multiplayerMode 分化两种模式：
    //   ONE_FOR_ALL   : 一人噩梦，全员醒来（world 全量回滚 + 全员维度假切换 + 全员提示，仅受害者惊慌）
    //   PERSONAL_ONLY : 一人噩梦，本人痕迹消散（不碰 world 文件、不动他人，只回滚自己 + 单人维度假切换）
    // ---------------------------------------------------------------
    private static void triggerSilentRollback(@NotNull MinecraftServer server,
                                              @Nullable ServerPlayer who,
                                              @NotNull Path backupSrc) {
        // 取多人模式（命令运行时覆盖 > 配置文件）
        MultiplayerMode mm = com.justabaddream.command.BadDreamCommands.RuntimeOverrides.effectiveMultiplayerMode();

        // ===== Phase A：即时玩家 NBT 还原 + 过渡屏（只对"谁触发"这个受害者）=====
        if (who != null) {
            boolean playerNbtRestored = restorePlayerNbtFromBackup(who, backupSrc);
            if (!playerNbtRestored) {
                // 至少回满血和饥饿，避免接下来死亡
                who.setHealth(who.getMaxHealth());
                who.getFoodData().setFoodLevel(20);
                who.getFoodData().setSaturation(20);
                who.hurtTime = 0;
                who.deathTime = 0;
                who.setDeltaMovement(Vec3.ZERO);
            }
            // 显示「正在回归现实…」Title
            sendReturnRealityTitle(who);
            // 冻结：无重力 + 清除速度 + 立即同步血量/经验/背包
            freezePlayer(who);
            // 退出叠加态
            com.justabaddream.capability.DreamStateCapability.get(who)
                    .ifPresent(com.justabaddream.capability.DreamStateCapability.IDreamState::exitDreamState);
        }

        final ServerPlayer finalWho = who;
        final MultiplayerMode mode = mm;

        // ===== Phase B：后台异步 world 文件覆盖（仅 ONE_FOR_ALL 需要；PERSONAL_ONLY 直接跳过）=====
        CompletableFuture.supplyAsync(() -> {
            try {
                if (mode == MultiplayerMode.ONE_FOR_ALL) {
                    // 同步保存一次，随后异步覆盖文件（注意：SILENT 不关 storageSource，
                    // 避免丢玩家连接；ServerLevel.chunkSource 会在"维度假切换"时自动 flush + reload）
                    server.executeBlocking(() -> saveAllNow(server));
                    Path worldRoot = getWorldRootPath(server);
                    restoreBackupOverWorld(backupSrc, worldRoot);
                }
                // PERSONAL_ONLY：不碰 world 文件 —— 方块、时间、他人状态都保持不变
                return true;
            } catch (Throwable t) {
                JABDMod.LOGGER.error("[JABD] SILENT 回档：{} 阶段失败",
                        mode == MultiplayerMode.ONE_FOR_ALL ? "文件覆盖" : "跳过文件", t);
                return false;
            }
        }, BACKUP_EXECUTOR).thenAcceptAsync(ok -> {
            // ===== Phase C：回到主线程，执行维度假切换 + 惊慌效果（按 multiplayerMode 区分）=====
            server.execute(() -> {
                if (!ok) {
                    if (finalWho != null)
                        finalWho.sendSystemMessage(Component.literal("§c[JABD] 回档时出现异常，可能未完全恢复。"));
                }

                if (mode == MultiplayerMode.ONE_FOR_ALL) {
                    // 全员醒来：对所有在线玩家触发维度假切换
                    //   - 受害者本人 → 惊慌 + "惊醒"提示
                    //   - 其他人     → 只显示 "<受害者> 做噩梦了……" hotbar 提示（不给惊慌）
                    String victimName = (finalWho != null) ? finalWho.getGameProfile().getName() : "某玩家";
                    Component otherMsg = Component.translatable("jabbadream.chat.one_for_all.wake_broadcast", victimName)
                            .withStyle(s -> s.withColor(0xE8B76F));

                    List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
                    for (ServerPlayer p : players) {
                        ServerPlayer alive = forceReloadSinglePlayer(server, p);
                        if (alive == null) continue;
                        if (finalWho != null && alive.getUUID().equals(finalWho.getUUID())) {
                            // 本人
                            applyPanic(alive);
                            applyWakeInvincibility(alive);
                            alive.sendSystemMessage(Component.translatable("jabbadream.chat.rollback.wake_self"), true);
                        } else {
                            // 其他人：只提示，不给惊慌；也给他们"正在回归现实"的 Title（像一起被惊醒）
                            sendReturnRealityTitle(alive);
                            alive.sendSystemMessage(otherMsg, true);
                        }
                    }
                    JABDMod.LOGGER.info("[JABD] SILENT 回档（ONE_FOR_ALL）完成：玩家 {} → 全员醒来 + 仅受害者惊慌。", victimName);
                } else {
                    // PERSONAL_ONLY：仅受害者一人维度假切换 + 惊慌；其他人完全零感知
                    if (finalWho != null) {
                        ServerPlayer alive = forceReloadSinglePlayer(server, finalWho);
                        if (alive != null) {
                            applyPanic(alive);
                            applyWakeInvincibility(alive);
                            alive.sendSystemMessage(Component.translatable("jabbadream.chat.rollback.wake_self"), true);
                        }
                        JABDMod.LOGGER.info("[JABD] SILENT 回档（PERSONAL_ONLY）完成：玩家 {} 的痕迹已消散（世界与他人未动）。",
                                finalWho.getGameProfile().getName());
                    }
                }
            });
        }, BACKUP_EXECUTOR);
    }

    /** 对单个玩家执行「维度假切换」：返回新的（存活的）ServerPlayer 实例，失败返回 null。 */
    @Nullable
    private static ServerPlayer forceReloadSinglePlayer(MinecraftServer server, ServerPlayer p) {
        try {
            // 1.20.1 PlayerList#respawn(ServerPlayer, boolean keepEverything)
            ServerPlayer respawned = server.getPlayerList().respawn(p, true);
            // 解除冻结（恢复重力），如果是被回档触发者由 applyPanic 再加状态
            respawned.setNoGravity(false);
            p.setNoGravity(false);
            return respawned;
        } catch (Throwable t) {
            JABDMod.LOGGER.warn("[JABD] 为玩家 {} 触发『加入世界中』过场失败", p.getGameProfile().getName(), t);
            // 兜底：用 teleportTo 相同位置 + 清空速度，让客户端重渲区块
            p.teleportTo(p.getX(), p.getY(), p.getZ());
            p.setDeltaMovement(Vec3.ZERO);
            return p;
        }
    }

    /** 对所有在线玩家执行「维度假切换」（保留以兼容 /baddream rollback 手动命令调用） */
    @SuppressWarnings("unused")
    private static void forceReloadAllPlayers(MinecraftServer server) {
        for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
            forceReloadSinglePlayer(server, p);
        }
    }

    /** 从备份快照的 playerdata/<uuid>.dat 还原玩家（背包、血量、位置、XP、饥饿、药水、末影箱、装备、EnderChest…） */
    private static boolean restorePlayerNbtFromBackup(@NotNull ServerPlayer who, Path backupSrc) {
        try {
            Path datFile;
            if (backupSrc.toString().endsWith(".zip")) {
                datFile = extractSingleFromZip(backupSrc,
                        "playerdata/" + who.getUUID() + ".dat", who.server);
            } else {
                datFile = backupSrc.resolve("playerdata").resolve(who.getUUID() + ".dat");
            }
            if (datFile == null || !Files.isRegularFile(datFile)) return false;

            CompoundTag nbt = NbtIo.readCompressed(datFile.toFile());
            if (nbt == null) return false;

            // 保留当前连接信息（避免 UUID 覆盖导致断连）
            UUID uuid = who.getUUID();
            String name = who.getGameProfile().getName();

            who.load(nbt);

            // 强制覆盖回真实 UUID / 名字
            // (load() 不会覆写 gameProfile，但确保一下)
            return true;
        } catch (Throwable t) {
            JABDMod.LOGGER.warn("[JABD] 还原玩家 NBT 失败：{}", who.getGameProfile().getName(), t);
            return false;
        }
    }

    /**
     * 发送全屏 Title：「正在回归现实…」+ 小标题「加载地形中」，配合淡入/停留/淡出 tick。
     * 直接使用网络包以保证 1.20.1 行为稳定（不依赖 ServerPlayer 重载方法名差异）。
     */
    private static void sendReturnRealityTitle(ServerPlayer who) {
        List<? extends Integer> ticks = JABDConfig.COMMON.returnRealityTitleTicks.get();
        int fadeIn  = ticks.size() > 0 ? ticks.get(0) : 10;
        int stay    = ticks.size() > 1 ? ticks.get(1) : 60;
        int fadeOut = ticks.size() > 2 ? ticks.get(2) : 20;

        Component title    = Component.translatable("jabbadream.title.return_reality")
                .withStyle(s -> s.withColor(0x6FD7FF).withBold(true));
        Component subtitle = Component.translatable("jabbadream.title.return_reality.sub")
                .withStyle(s -> s.withColor(0xC8C8C8));

        // 动画时序 → 副标题 → 主标题（顺序：时序必须先 setTimes，否则被默认 10/70/20 覆盖）
        who.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        who.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        who.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    /** 冻结玩家（无重力 + 速度归零 + 立即同步血量/经验） */
    private static void freezePlayer(ServerPlayer who) {
        who.setNoGravity(true);
        who.setDeltaMovement(Vec3.ZERO);
        // 立刻同步给客户端，避免插值导致继续掉落
        who.connection.send(new ClientboundSetEntityMotionPacket(who));
        who.connection.send(new ClientboundSetHealthPacket(
                who.getHealth(), who.getFoodData().getFoodLevel(), who.getFoodData().getSaturationLevel()));
        who.connection.send(new ClientboundSetExperiencePacket(
                who.experienceProgress, who.totalExperience, who.experienceLevel));
        // 3 秒后取消冻结（此时"维度假切换"应已完成）
        who.server.execute(() -> {
            // 用 scheduled future 不阻塞主线程 tick 计数
        });
        // 简单做法：60 tick（3s）后由 forceReloadAllPlayers 天然重置
    }

    /** 施加「惊慌」状态，时长取自配置 panicDurationTicks */
    private static void applyPanic(ServerPlayer who) {
        int duration = JABDConfig.SERVER.panicDurationTicks.get();
        if (duration <= 0) return;
        // amplifier=0 (LEVEL I)
        who.addEffect(new MobEffectInstance(JABDMobEffects.PANIC.get(), duration, 0, false, true, true));
    }

    /** 惊醒后施加短暂无敌（Resistance V = 100% 伤害减免） */
    private static void applyWakeInvincibility(ServerPlayer who) {
        int seconds = JABDConfig.SERVER.wakeInvincibilitySeconds.get();
        if (seconds <= 0) return;
        int ticks = seconds * 20;
        // amplifier=4 (Resistance V) → 100% 伤害减免；ambient=false, particles=false, icon=true
        who.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, ticks, 4, false, false, true));
        JABDMod.LOGGER.info("[JABD] 玩家 {} 获得惊醒无敌 {} 秒（{} ticks）",
                who.getGameProfile().getName(), seconds, ticks);
    }

    /** 从 zip 中抽取单个文件到临时文件；返回 null 表示找不到 */
    @Nullable
    private static Path extractSingleFromZip(Path zipFile, String entryName, MinecraftServer server) throws IOException {
        Path tmpDir = Files.createTempDirectory("jabd-nbt-");
        Path out = tmpDir.resolve(Paths.get(entryName).getFileName());
        try (var zis = new java.util.zip.ZipInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(zipFile)))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                // 去掉 zip 根前缀
                int slash = name.indexOf('/');
                String stripped = (slash >= 0) ? name.substring(slash + 1) : name;
                if (entryName.equals(stripped)) {
                    Files.createDirectories(out.getParent());
                    try (var fos = Files.newOutputStream(out)) { zis.transferTo(fos); }
                    zis.closeEntry();
                    return out;
                }
                zis.closeEntry();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // SOFT / HARD 硬回档（原有逻辑）
    // ---------------------------------------------------------------
    private static void triggerHardRollback(@NotNull MinecraftServer server,
                                            @Nullable ServerPlayer who,
                                            @NotNull Path backupSrc,
                                            RollbackStrategy strategy) {
        JABDMod.LOGGER.warn("[JABD] ======== 硬回档（策略={}） ========", strategy);

        saveAllNow(server);
        closeStorageAccess(server);

        Path worldRoot = getWorldRootPath(server);
        try {
            restoreBackupOverWorld(backupSrc, worldRoot);
        } catch (IOException e) {
            JABDMod.LOGGER.error("[JABD] 回档期间文件复制失败，服务器可能处于不一致状态，请手动恢复", e);
        }

        if (who != null) {
            com.justabaddream.capability.DreamStateCapability.get(who)
                    .ifPresent(com.justabaddream.capability.DreamStateCapability.IDreamState::exitDreamState);
        }

        if (strategy == RollbackStrategy.SOFT) {
            kickAllAndHalt(server, "§6[Just A Bad Dream]§f\n§c存档已回档。§r请重新进入服务器。");
        } else {
            kickAllAndHalt(server, "§6[Just A Bad Dream]§r 服务器即将重启以完成回档。");
            new Thread(() -> {
                try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                System.exit(0);
            }, "JABD-Halt").start();
        }
    }

    // =========================================================================
    // 内部工具
    // =========================================================================

    /** 配置允许才执行备份（专用服/单人开关） */
    private static boolean isBackupAllowed(MinecraftServer server) {
        if (server == null) return false;
        if (JABDConfig.SERVER.backupOnDedicatedOnly.get() && !server.isDedicatedServer()) return false;
        return true;
    }

    /** 每玩家备份目录：world/jabbadream_backups/players/<uuid>/   —— 或以配置的根目录为准 */
    static Path resolvePlayerBackupDir(ServerPlayer player) {
        MinecraftServer server = player.server;
        Path worldRoot = getWorldRootPath(server);
        String cfgDir = JABDConfig.SERVER.backupDirectory.get();
        Path cfgPath = Paths.get(cfgDir);
        Path backupRoot = cfgPath.isAbsolute() ? cfgPath : worldRoot.resolve(cfgPath);
        return backupRoot.resolve("players").resolve(player.getUUID().toString());
    }

    /** 获取当前 world/ 目录（服务端主存档根） */
    @NotNull
    public static Path getWorldRootPath(MinecraftServer server) {
        try {
            // 1.18+ LevelResource.ROOT 返回 world 目录的绝对路径
            return server.getWorldPath(LevelResource.ROOT);
        } catch (Throwable t) {
            // 回退：基于 run 目录 + "world"
            Path serverPath = FMLPaths.GAMEDIR.get();
            return serverPath.resolve("saves").resolve("world");
        }
    }

    /** 主线程内阻塞式保存所有世界 */
    static void saveAllNow(MinecraftServer server) {
        try {
            server.saveEverything(true, true, true);
        } catch (Throwable t) {
            JABDMod.LOGGER.warn("[JABD] saveEverything 出现异常，继续尝试后续步骤", t);
        }
    }

    /** 关闭存档 IO 层（确保没有进程持有 mca/region 文件句柄） */
    static void closeStorageAccess(MinecraftServer server) {
        try {
            // 1.20.1 storageSource 是 protected — 用反射关闭
            java.lang.reflect.Field f = MinecraftServer.class.getDeclaredField("storageSource");
            f.setAccessible(true);
            Object ss = f.get(server);
            if (ss != null) {
                ss.getClass().getMethod("close").invoke(ss);
            }
        } catch (Throwable t) {
            JABDMod.LOGGER.warn("[JABD] 关闭 storageSource 失败，仍将尝试覆盖文件", t);
        }
        // 关闭所有 ServerLevel 的 chunk source
        server.getAllLevels().forEach(level -> {
            try {
                level.getChunkSource().close();
            } catch (Throwable ignored) {}
        });
    }

    /** 找到备份源（可能是目录或 .zip） */
    @Nullable
    private static Path findBackupSource(MinecraftServer server, ServerPlayer who, String backupId) {
        String effectiveId = backupId;
        if (effectiveId == null && who != null) {
            effectiveId = com.justabaddream.capability.DreamStateCapability.get(who)
                    .map(com.justabaddream.capability.DreamStateCapability.IDreamState::getBackupId)
                    .orElse(null);
        }
        if (effectiveId == null || who == null) return null;

        Path playerDir = resolvePlayerBackupDir(who);
        Path dir = playerDir.resolve(effectiveId);
        Path zip = playerDir.resolve(effectiveId + ".zip");
        if (Files.isDirectory(dir)) {
            // 优先使用 zip 表示（目录里可能放了 .zip 指针）
            Path zipPtr = dir.resolve(".zip");
            if (Files.isRegularFile(zipPtr)) {
                try {
                    String zipName = Files.readString(zipPtr).trim();
                    Path realZip = playerDir.resolve(zipName);
                    if (Files.isRegularFile(realZip)) return realZip;
                } catch (IOException ignored) {}
            }
            return dir;
        }
        if (Files.isRegularFile(zip)) return zip;
        return null;
    }

    /** 将备份（目录或 zip）覆盖回 world */
    private static void restoreBackupOverWorld(Path backupSrc, Path worldRoot) throws IOException {
        // 先删除当前 world 中的文件（保留备份目录自身）
        deleteWorldContents(worldRoot);

        if (backupSrc.toString().endsWith(".zip")) {
            unzipDirectory(backupSrc, worldRoot);
        } else {
            copyDirectory(backupSrc, worldRoot);
        }
        JABDMod.LOGGER.info("[JABD] 已将备份 {} 还原至 world", backupSrc);
    }

    /** 删除 world 的全部内容（除了 jabbadream_backups 自身避免递归删除） */
    private static void deleteWorldContents(Path worldRoot) throws IOException {
        String skipName = Paths.get(JABDConfig.SERVER.backupDirectory.get()).getFileName().toString();
        if (!Files.isDirectory(worldRoot)) return;
        try (Stream<Path> entries = Files.list(worldRoot)) {
            for (Path p : (Iterable<Path>) entries::iterator) {
                if (p.getFileName().toString().equals(skipName)) continue;
                deleteRecursively(p);
            }
        }
    }

    /** 踢出全部玩家并阻塞住服务器的主循环（等价于"停服但不退出"） */
    private static void kickAllAndHalt(MinecraftServer server, String reason) {
        server.execute(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.connection.disconnect(net.minecraft.network.chat.Component.literal(reason));
            }
            // 让服务器 tick 进入 no-op 状态；此处不强制 close，避免二次触发回档 IO
        });
    }

    // =========================================================================
    // 文件系统工具
    // =========================================================================

    static void copyDirectory(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Path target = dst.resolve(src.relativize(dir));
                        Files.createDirectories(target);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        // 跳过 session.lock（MC 占用）与我们自己的备份目录（递归）
                        String name = file.getFileName().toString();
                        if ("session.lock".equals(name)) return FileVisitResult.CONTINUE;
                        Path target = dst.resolve(src.relativize(file));
                        try {
                            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        } catch (IOException io) {
                            // 回退：FileChannel.transferTo（避免 FileChannel 实现在不同 JDK 的差异）
                            if (!Files.isDirectory(target.getParent())) Files.createDirectories(target.getParent());
                            try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ);
                                 FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE,
                                         StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                                long size = in.size();
                                long pos = 0;
                                while (pos < size) pos += in.transferTo(pos, size - pos, out);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    static void zipDirectory(Path srcDir, Path zipFile, String rootEntryName) throws IOException {
        Files.createDirectories(zipFile.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(zipFile)))) {
            zos.setLevel(Deflater.BEST_SPEED); // 速度优先，备份 IO 比压缩率重要
            Files.walkFileTree(srcDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    if ("session.lock".equals(name)) return FileVisitResult.CONTINUE;
                    String rel = rootEntryName + "/" + srcDir.relativize(file).toString().replace('\\', '/');
                    ZipEntry e = new ZipEntry(rel);
                    zos.putNextEntry(e);
                    try (var fis = Files.newInputStream(file)) { fis.transferTo(zos); }
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String rel = rootEntryName + "/" + srcDir.relativize(dir).toString().replace('\\', '/');
                    if (!rel.endsWith("/")) rel += "/";
                    ZipEntry e = new ZipEntry(rel);
                    zos.putNextEntry(e);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    static void unzipDirectory(Path zipFile, Path dstDir) throws IOException {
        Files.createDirectories(dstDir);
        try (var zis = new java.util.zip.ZipInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                // 去掉首个根目录（例如 "20240101_120000_ab12/..." 里的前缀）
                String name = e.getName();
                int firstSlash = name.indexOf('/');
                String stripped = (firstSlash >= 0) ? name.substring(firstSlash + 1) : name;
                if (stripped.isEmpty()) { zis.closeEntry(); continue; }
                Path target = dstDir.resolve(stripped).normalize();
                if (!target.startsWith(dstDir)) {
                    JABDMod.LOGGER.warn("[JABD] 跳过 ZipEntry 非法路径：{}", e.getName());
                    zis.closeEntry();
                    continue;
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var fos = Files.newOutputStream(target)) { zis.transferTo(fos); }
                }
                zis.closeEntry();
            }
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 限制每玩家保留备份数量（按时间从旧到新删） */
    private static void pruneOldBackups(Path playerDir, int maxKeep) throws IOException {
        record Entry(String id, long mtime, Path dir, Path zip) implements Comparable<Entry> {
            @Override public int compareTo(Entry o) { return Long.compare(this.mtime, o.mtime); }
        }
        List<Entry> all = new ArrayList<>();
        try (Stream<Path> list = Files.list(playerDir)) {
            list.forEach(p -> {
                String name = p.getFileName().toString();
                try {
                    if (Files.isDirectory(p)) {
                        all.add(new Entry(name, Files.getLastModifiedTime(p).toMillis(), p, null));
                    } else if (name.endsWith(".zip")) {
                        String id = name.substring(0, name.length() - 4);
                        all.add(new Entry(id, Files.getLastModifiedTime(p).toMillis(), null, p));
                    }
                } catch (IOException ignored) {}
            });
        }
        Collections.sort(all);
        int toDelete = all.size() - maxKeep;
        for (int i = 0; i < toDelete; i++) {
            Entry e = all.get(i);
            try {
                if (e.dir != null) deleteRecursively(e.dir);
                if (e.zip != null) Files.deleteIfExists(e.zip);
            } catch (IOException ex) {
                JABDMod.LOGGER.warn("[JABD] 清理旧备份失败：{}", e.id, ex);
            }
        }
    }

    private static String dirSizeHuman(Path p) {
        final long[] size = {0};
        try (Stream<Path> s = Files.walk(p)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                try { size[0] += Files.size(f); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        long bytes = size[0];
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024*1024) return String.format("%.1f KiB", bytes/1024.0);
        if (bytes < 1024L*1024*1024) return String.format("%.1f MiB", bytes/(1024.0*1024));
        return String.format("%.1f GiB", bytes/(1024.0*1024*1024));
    }
}
