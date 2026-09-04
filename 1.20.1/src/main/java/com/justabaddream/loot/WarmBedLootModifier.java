package com.justabaddream.loot;

import com.justabaddream.JABDMod;
import com.justabaddream.config.JABDConfig;
import com.justabaddream.registry.JABDBlocks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.NotNull;

/**
 * 全局战利品修改器：当 lootChests=true 时，在战利品箱中注入温暖的床。
 * 注入概率约 5%（与原版稀有掉落类似）。
 *
 * <p>1.20.1 Forge {@link LootModifier} 提供静态 {@link LootModifier#codecStart} 辅助方法，
 * 用于构造 conditions 字段的 codec — 不应直接引用 {@code LootItemCondition.CODEC}（1.20.1 不存在）。
 */
public class WarmBedLootModifier extends LootModifier {

    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> GLM_REGISTRY =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, JABDMod.MOD_ID);

    /** 强类型 codec — 让 RecordCodecBuilder 推断 T = WarmBedLootModifier（满足 codecStart 的 T extends LootModifier 约束） */
    private static final Codec<WarmBedLootModifier> CODEC_IMPL = RecordCodecBuilder.create(inst ->
            codecStart(inst).apply(inst, WarmBedLootModifier::new)
    );

    public static final RegistryObject<Codec<? extends IGlobalLootModifier>> CODEC = GLM_REGISTRY.register(
            "warm_bed_injection",
            () -> CODEC_IMPL
    );

    public WarmBedLootModifier(LootItemCondition[] conditionsIn) {
        super(conditionsIn);
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot,
                                                          LootContext context) {
        if (!JABDConfig.SERVER.lootChests.get()) return generatedLoot;
        if (context.getRandom().nextFloat() > 0.05f) return generatedLoot;
        generatedLoot.add(new ItemStack(JABDBlocks.WARM_BED.get().asItem()));
        return generatedLoot;
    }

    @Override
    public @NotNull Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
