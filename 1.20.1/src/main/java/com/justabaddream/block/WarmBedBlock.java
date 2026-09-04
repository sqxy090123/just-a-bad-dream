package com.justabaddream.block;

import com.justabaddream.registry.JABDBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 温暖的床方块。
 * <p>
 * 继承原版 {@link BedBlock}，复用：白天/夜晚可睡判断、两格方块（HEAD / FOOT）逻辑、
 * 设置重生点、村民寻床等原版 AI 行为。
 * <p>
 * 新增 {@link #CHARGED} 属性：
 * <ul>
 *     <li>合成时默认 charged=true（充能状态，可入睡）</li>
 *     <li>睡醒后若配置 oneTimeUse=true → 自动变为 charged=false（未充能，无法入睡）</li>
 *     <li>手持充能物品右键未充能床 → 消耗 1 个充能物品，恢复 charged=true</li>
 *     <li>未充能床无法入睡，右键会提示「床未充能」</li>
 * </ul>
 * 玩家"梦境现实叠加态"与存档备份逻辑集中在 {@code DreamEventHandler} 中处理。
 */
public class WarmBedBlock extends BedBlock {

    /** 充能状态：true=可入睡，false=未充能（需充能后才能使用） */
    public static final BooleanProperty CHARGED = BooleanProperty.create("charged");

    protected static final VoxelShape SHAPE = box(0.0D, 0.0D, 0.0D, 16.0D, 9.0D, 16.0D);

    public WarmBedBlock(Properties properties) {
        super(net.minecraft.world.item.DyeColor.WHITE, properties);
        registerDefaultState(defaultBlockState().setValue(CHARGED, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CHARGED);
    }

    @SuppressWarnings("deprecation")
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (state.getValue(PART) == BedPart.HEAD) {
            return JABDBlockEntities.WARM_BED_BLOCK_ENTITY.get().create(pos, state);
        }
        return null;
    }
}
