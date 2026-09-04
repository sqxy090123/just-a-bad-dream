package com.justabaddream.loot;

import com.justabaddream.JABDMod;
import com.justabaddream.config.JABDConfig;
import com.justabaddream.registry.JABDBlocks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.NotNull;

/**
 * 全局战利品修改器：当 lootChests=true 时，在战利品箱中注入温暖的床。
 * 注入概率约 5%（与原版稀有掉落类似）。
 */
public class WarmBedLootModifier extends LootModifier {

    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> GLM_REGISTRY =
            DeferredRegister.create(net.minecraftforge.registries.ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, JABDMod.MOD_ID);

    public static final RegistryObject<Codec<? extends IGlobalLootModifier>> CODEC = GLM_REGISTRY.register(
            "warm_bed_injection",
            () -> RecordCodecBuilder.create(inst ->
                    inst.group(
                            LootItemCondition.CODEC.listOf()
                                    .fieldOf("conditions")
                                    .forGetter(lm -> ((WarmBedLootModifier) lm).conditions)
                    ).apply(inst, WarmBedLootModifier::new)
            )
    );

    private final java.util.List<LootItemCondition> conditions;

    public WarmBedLootModifier(LootItemCondition[] conditionsIn) {
        super(conditionsIn);
        this.conditions = java.util.List.of(conditionsIn);
    }

    public WarmBedLootModifier(java.util.List<LootItemCondition> conditionsIn) {
        super(conditionsIn.toArray(new LootItemCondition[0]));
        this.conditions = conditionsIn;
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
