package com.justabaddream;

import com.justabaddream.capability.DreamStateCapability;
import com.justabaddream.command.BadDreamCommands;
import com.justabaddream.config.JABDConfig;
import com.justabaddream.handler.DreamEventHandler;
import com.justabaddream.recipe.WarmBedRecipeSerializer;
import com.justabaddream.loot.WarmBedLootModifier;
import com.justabaddream.registry.JABDItems;
import com.justabaddream.registry.JABDBlocks;
import com.justabaddream.registry.JABDBlockEntities;
import com.justabaddream.registry.JABDCreativeModeTabs;
import com.justabaddream.registry.JABDMobEffects;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Just A Bad Dream (JABD) — 主模组入口
 * <p>
 * 功能：
 * 1. 新增"温暖的床"方块（合成时将床配方中的任意一个羊毛替换为下界之星）
 * 2. 玩家在温暖的床上睡过一觉 → 进入［梦境现实叠加态］→ 后台自动全量备份存档
 * 3. 状态持续到下一次睡眠；若玩家在叠加态期间真正死亡 → 自动回档到醒来后的存档快照
 * <p>
 * 触发方式、不死图腾判定、备份目录等均可通过配置文件或 /baddream 命令调整。
 */
@Mod(JABDMod.MOD_ID)
public class JABDMod {

    public static final String MOD_ID = "jabbadream";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JABDMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;

        // ---- 配置 ----
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, JABDConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, JABDConfig.SERVER_SPEC);

        // ---- 注册 ----
        JABDBlocks.BLOCKS.register(modBus);
        JABDItems.ITEMS.register(modBus);
        JABDBlockEntities.BLOCK_ENTITIES.register(modBus);
        JABDCreativeModeTabs.CREATIVE_MODE_TABS.register(modBus);
        JABDMobEffects.MOB_EFFECTS.register(modBus);
        WarmBedRecipeSerializer.RECIPE_SERIALIZERS.register(modBus);
        WarmBedLootModifier.GLM_REGISTRY.register(modBus);

        // ---- LifeCycle ----
        modBus.addListener(this::onCommonSetup);

        // ---- Forge 事件 ----
        forgeBus.register(new DreamEventHandler());
        forgeBus.register(new DreamStateCapability()); // Capability 注册

        LOGGER.info("[JABD] Just A Bad Dream 已加载 — 愿你好梦……即便只是噩梦一场。");
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            DreamStateCapability.register();
            BadDreamCommands.registerArgumentTypes();
        });
    }
}
