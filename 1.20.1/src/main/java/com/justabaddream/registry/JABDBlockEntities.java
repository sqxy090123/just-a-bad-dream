package com.justabaddream.registry;

import com.justabaddream.JABDMod;
import com.justabaddream.block.WarmBedBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * BlockEntity 注册
 */
public final class JABDBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, JABDMod.MOD_ID);

    public static final RegistryObject<BlockEntityType<WarmBedBlockEntity>> WARM_BED_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("warm_bed",
                    () -> BlockEntityType.Builder.of(WarmBedBlockEntity::new, JABDBlocks.WARM_BED.get()).build(null));

    private JABDBlockEntities() {}
}
