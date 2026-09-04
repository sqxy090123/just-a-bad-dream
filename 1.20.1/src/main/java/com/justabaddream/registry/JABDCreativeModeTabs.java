package com.justabaddream.registry;

import com.justabaddream.JABDMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 创造模式物品栏标签（1.19.3+ 的新 CreativeModeTab 注册方式）
 */
public final class JABDCreativeModeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, JABDMod.MOD_ID);

    public static final RegistryObject<CreativeModeTab> JABD_TAB = CREATIVE_MODE_TABS.register("jabbadream_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.jabbadream"))
                    .icon(() -> new ItemStack(JABDBlocks.WARM_BED.get()))
                    .displayItems((params, output) -> {
                        output.accept(JABDBlocks.WARM_BED.get());
                    })
                    .build());

    private JABDCreativeModeTabs() {}
}
