package com.justabaddream.registry;

import com.justabaddream.JABDMod;
import com.justabaddream.block.WarmBedBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * 方块注册（Forge 1.18.2+ DeferredRegister 方式）
 */
public final class JABDBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, JABDMod.MOD_ID);

    /**
     * 温暖的床 — 替换原版 BedBlock，继承原版床的所有行为（睡觉、爆炸、重生点等）
     * 但在成功睡过一觉后触发"梦境现实叠加态"与存档备份。
     */
    public static final RegistryObject<Block> WARM_BED = register("warm_bed",
            () -> new WarmBedBlock(BlockBehaviour.Properties.of().strength(0.2F).noOcclusion()));

    // ==================================================================

    private static <T extends Block> RegistryObject<T> register(String name, Supplier<T> blockSupplier) {
        RegistryObject<T> block = BLOCKS.register(name, blockSupplier);
        // 同步注册 BlockItem（与 JABDItems 中保持一致，避免重复）
        JABDItems.ITEMS.register(name,
                () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private JABDBlocks() {}
}
