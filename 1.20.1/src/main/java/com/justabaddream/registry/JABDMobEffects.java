package com.justabaddream.registry;

import com.justabaddream.JABDMod;
import com.justabaddream.effect.PanicMobEffect;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 药水效果注册（Forge 1.18+ 可直接用 DeferredRegister<MobEffect>）。
 */
public final class JABDMobEffects {

    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, JABDMod.MOD_ID);

    /**
     * 惊慌效果：回档"惊醒"后短时间内施加。
     * 默认 30 秒（600 tick）。配置可调（panicDurationTicks）。
     */
    public static final RegistryObject<MobEffect> PANIC = MOB_EFFECTS.register("panic", PanicMobEffect::new);

    private JABDMobEffects() {}
}
