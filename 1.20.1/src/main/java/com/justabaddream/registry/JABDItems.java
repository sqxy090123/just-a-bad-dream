package com.justabaddream.registry;

import com.justabaddream.JABDMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品注册。
 * <p>
 * 注：温暖的床对应的 BlockItem 已在 {@link JABDBlocks} 中同步注册到此处，
 * 不需要重复声明。该类用于未来的扩展物品。
 */
public final class JABDItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, JABDMod.MOD_ID);

    // 预留：未来可加入"噩梦碎片"、"清醒护符"等物品
    // public static final RegistryObject<Item> NIGHTMARE_SHARD = ITEMS.register("nightmare_shard",
    //        () -> new Item(new Item.Properties()));

    private JABDItems() {}
}
