package com.justabaddream.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
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
public class WarmBedBlock extends BedBlock implements SimpleWaterloggedBlock {

    /** 充能状态：true=可入睡，false=未充能（需充能后才能使用） */
    public static final BooleanProperty CHARGED = BooleanProperty.create("charged");
    /** 是否含水 */
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    protected static final VoxelShape SHAPE = box(0.0D, 0.0D, 0.0D, 16.0D, 9.0D, 16.0D);

    public WarmBedBlock(Properties properties) {
        super(net.minecraft.world.item.DyeColor.WHITE, properties);
        registerDefaultState(defaultBlockState()
                .setValue(CHARGED, true)
                .setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CHARGED, WATERLOGGED);
    }

    @SuppressWarnings("deprecation")
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // 不使用 BlockEntity — 有 BlockEntity 的方块无法被活塞推动，
        // 且床的运行时数据（充能状态等）已由 BlockState 属性承载。
        return null;
    }

    // ========================================================================
    // 含水支持
    // ========================================================================

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null) return null;
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        return state.setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        if (!state.getValue(WATERLOGGED) && fluidState.getType() == Fluids.WATER) {
            if (!level.isClientSide()) {
                level.setBlock(pos, state.setValue(WATERLOGGED, true), Block.UPDATE_ALL);
                level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
            }
            return true;
        }
        return false;
    }

    /** 获取床另一半所在的方向（FOOT 的前方是 HEAD，HEAD 的后方是 FOOT） */
    private static Direction otherHalfDirection(BedPart part, Direction facing) {
        return part == BedPart.HEAD ? facing.getOpposite() : facing;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                   LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // 含水方块的流体更新
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        // 两格方块一致性检查（与原版 BedBlock 相同）：另一半不是床则移除自己
        // 返回 AIR 而非调用 removeBlock，避免触发掉落
        if (!neighborState.is(this)) {
            Direction expectedNeighbor = otherHalfDirection(state.getValue(PART), state.getValue(FACING));
            if (direction == expectedNeighbor) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    // ========================================================================
    // 破坏逻辑：避免创造模式掉落 & 避免两格都掉落
    // ========================================================================

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // 先把另一半的 OCCUPIED 设为 false（与原版 BedBlock 一致）
        BedPart part = state.getValue(PART);
        if (part == BedPart.HEAD) {
            BlockPos footPos = pos.relative(otherHalfDirection(BedPart.HEAD, state.getValue(FACING)));
            BlockState footState = level.getBlockState(footPos);
            if (footState.is(this)) {
                level.setBlock(footPos, footState.setValue(OCCUPIED, false), Block.UPDATE_ALL);
            }
        }
        // 不调用 super.playerWillDestroy — 原版会在这里做额外的移除逻辑，
        // 我们通过 updateShape 自然处理另一半的消失，避免双重掉落
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        // 含水方块的流体处理
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // 含水状态传播到另一半
        if (state.getValue(PART) == BedPart.FOOT) {
            BlockPos headPos = pos.relative(state.getValue(FACING));
            BlockState headState = level.getBlockState(headPos);
            if (headState.is(this)) {
                level.setBlock(headPos, headState.setValue(WATERLOGGED, state.getValue(WATERLOGGED)), Block.UPDATE_ALL);
            }
        }
    }
}
