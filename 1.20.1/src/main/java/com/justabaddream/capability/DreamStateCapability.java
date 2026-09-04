package com.justabaddream.capability;

import com.justabaddream.JABDMod;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 玩家"梦境现实叠加态"Capability。
 * <p>
 * 状态字段：
 * <ul>
 *     <li>{@code inDreamState} — 是否处于叠加态（睡过温暖的床 → true；下次睡眠 / 真正死亡回档后 → false）</li>
 *     <li>{@code wakeUpTime} — 最近一次从温暖的床醒来的游戏 tick（用于命令排查）</li>
 *     <li>{@code backupId} — 当前叠加态对应的存档快照 ID（备份目录名），死亡时使用该 ID 回档</li>
 *     <li>{@code wakeUpPosition} — 醒来时的位置、维度（用于命令"查询"，或未来扩展）</li>
 * </ul>
 *
 * <h3>Forge 版本适配备忘</h3>
 * <ul>
 *     <li>1.16.5：使用 {@code CapabilityManager.INSTANCE.register} + {@code ICapabilitySerializable<CompoundNBT>}；类名 CompoundNBT</li>
 *     <li>1.12.2：无 Capability 注解，使用 ICapabilityProvider/ICapabilitySerializable；事件名 AttachCapabilityEvent.Entity</li>
 * </ul>
 */
public class DreamStateCapability {

    public static final Capability<IDreamState> DREAM_STATE_CAP = CapabilityManager.get(new CapabilityToken<>() {});

    public static final ResourceLocation CAP_ID = new ResourceLocation(JABDMod.MOD_ID, "dream_state");

    // ============================================================================
    // 接口
    // ============================================================================
    public interface IDreamState {

        /** 当前是否处于［梦境现实叠加态］ */
        boolean isInDreamState();

        /** 进入叠加态，记录醒来时间与备份 ID */
        void enterDreamState(long wakeUpGameTime, String backupId);

        /**
         * 退出叠加态（下次睡眠、命令清除、成功回档后均调用）
         */
        void exitDreamState();

        /** 最近一次进入叠加态时的游戏 tick（level#getGameTime） */
        long getWakeUpTime();

        /** 绑定的存档快照 ID（备份目录名）；未进入时为 {@code null} */
        @Nullable
        String getBackupId();

        /** 序列化 NBT */
        CompoundTag serializeNBT();

        /** 反序列化 NBT */
        void deserializeNBT(CompoundTag tag);
    }

    // ============================================================================
    // 默认实现
    // ============================================================================
    public static class DefaultDreamState implements IDreamState {

        private boolean inDreamState = false;
        private long wakeUpTime = -1L;
        @Nullable private String backupId = null;

        @Override public boolean isInDreamState() { return inDreamState; }

        @Override
        public void enterDreamState(long wakeUpGameTime, String backupId) {
            this.inDreamState = true;
            this.wakeUpTime = wakeUpGameTime;
            this.backupId = backupId;
        }

        @Override
        public void exitDreamState() {
            this.inDreamState = false;
            this.wakeUpTime = -1L;
            this.backupId = null;
        }

        @Override public long getWakeUpTime() { return wakeUpTime; }

        @Nullable @Override public String getBackupId() { return backupId; }

        @Override
        public CompoundTag serializeNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("InDreamState", inDreamState);
            tag.putLong("WakeUpTime", wakeUpTime);
            if (backupId != null) tag.putString("BackupId", backupId);
            return tag;
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
            this.inDreamState = tag.getBoolean("InDreamState");
            this.wakeUpTime = tag.getLong("WakeUpTime");
            this.backupId = tag.contains("BackupId") ? tag.getString("BackupId") : null;
        }
    }

    // ============================================================================
    // Provider
    // ============================================================================
    public static class Provider implements ICapabilitySerializable<CompoundTag> {

        private final DefaultDreamState instance = new DefaultDreamState();
        private final LazyOptional<IDreamState> holder = LazyOptional.of(() -> instance);

        @NotNull
        @Override
        public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
            return DREAM_STATE_CAP.orEmpty(cap, holder);
        }

        @Override
        public CompoundTag serializeNBT() { return instance.serializeNBT(); }

        @Override
        public void deserializeNBT(CompoundTag nbt) { instance.deserializeNBT(nbt); }
    }

    // ============================================================================
    // 注册（目前是占位；Forge 1.18+ 会在 CapabilityManager.get(new CapabilityToken<>(){}) 处自动完成）
    // ============================================================================
    public static void register() {
        // 1.20.1 不需要显式 register；保留此方法便于旧版本适配覆盖
    }

    // ============================================================================
    // 事件：Attach + Clone（死亡保留）
    // ============================================================================

    @SubscribeEvent
    public void onAttachPlayerCap(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            event.addCapability(CAP_ID, new Provider());
        }
    }

    /**
     * 玩家"死亡/从末地返回"的 Clone 事件：需要把旧玩家身上的叠加态复制到新玩家。
     * <p>
     * 注意：本模组会在"真正死亡"的 LivingDeathEvent（LOWEST receiveCanceled=true）
     * 里直接触发回档；Clone 仅用于普通的维度切换等场景，确保状态不丢失。
     */
    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        if (event.isWasDeath()) return; // 真死亡：我们直接回档，不需要复制
        event.getOriginal().getCapability(DREAM_STATE_CAP).ifPresent(oldState ->
                event.getEntity().getCapability(DREAM_STATE_CAP).ifPresent(newState -> {
                    CompoundTag tag = oldState.serializeNBT();
                    newState.deserializeNBT(tag);
                }));
    }

    // ============================================================================
    // 工具方法（便于其它类取到玩家状态）
    // ============================================================================

    public static LazyOptional<IDreamState> get(Player player) {
        return player.getCapability(DREAM_STATE_CAP);
    }
}
