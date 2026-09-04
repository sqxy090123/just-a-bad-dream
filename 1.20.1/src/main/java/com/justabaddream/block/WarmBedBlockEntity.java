package com.justabaddream.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 温暖的床方块实体。
 * <p>
 * 目前只做数据承载：
 * <ul>
 *   <li>记录最后一次"触发成功睡眠"的游戏时间（用于命令排查）</li>
 *   <li>预留扩展：未来可加入"睡眠次数"、"绑定玩家 UUID"、"梦境类型"等</li>
 * </ul>
 */
public class WarmBedBlockEntity extends BlockEntity {

    private long lastWakeUpTime = -1L;
    private int totalDreamCount = 0;

    public WarmBedBlockEntity(BlockPos pos, BlockState state) {
        super(com.justabaddream.registry.JABDBlockEntities.WARM_BED_BLOCK_ENTITY.get(), pos, state);
    }

    public long getLastWakeUpTime() {
        return lastWakeUpTime;
    }

    public void onPlayerWakeUp(long gameTime) {
        this.lastWakeUpTime = gameTime;
        this.totalDreamCount++;
        setChanged();
    }

    public int getTotalDreamCount() {
        return totalDreamCount;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong("LastWakeUpTime", lastWakeUpTime);
        tag.putInt("TotalDreamCount", totalDreamCount);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.lastWakeUpTime = tag.getLong("LastWakeUpTime");
        this.totalDreamCount = tag.getInt("TotalDreamCount");
    }
}
