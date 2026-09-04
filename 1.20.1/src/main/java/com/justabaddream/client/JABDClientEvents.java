package com.justabaddream.client;

import com.justabaddream.JABDMod;
import com.justabaddream.command.BadDreamCommands;
import com.justabaddream.config.JABDConfig;
import com.justabaddream.recipe.WarmBedRecipeSerializer;
import com.justabaddream.registry.JABDBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端侧事件：物品栏 tooltip（仅 Dist.CLIENT 加载）。
 * 注册方式：通过 @Mod.EventBusSubscriber(modid, value = Dist.CLIENT) 自动订阅。
 */
@Mod.EventBusSubscriber(modid = JABDMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class JABDClientEvents {

    /** 温暖的床物品 Tooltip：提示合成方式 / 效果 / 惊慌机制。
     *  Line1 动态：基于配置的 ingredient（显示中文/英文物品名，取自 Registry.descriptionId）。 */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().is(JABDBlocks.WARM_BED.get().asItem())) {
            // ---------- Line 1：动态（原料名随配置变）----------
            ItemStack ing = WarmBedRecipeSerializer.effectiveIngredientStack();
            int count = BadDreamCommands.RuntimeOverrides.warmBedIngredientCount != null
                    ? BadDreamCommands.RuntimeOverrides.warmBedIngredientCount
                    : JABDConfig.COMMON.warmBedIngredientCount.get();
            count = Math.max(1, Math.min(3, count));

            MutableComponent ingredientName = ing.getHoverName().copy();
            ingredientName.withStyle(ChatFormatting.LIGHT_PURPLE);

            MutableComponent countText = Component.literal(count > 1 ? ("（×" + count + "）") : "")
                    .withStyle(ChatFormatting.GRAY);

            // 形如：§7将床配方中的羊毛替换为 §5下界之星 §7（×N）合成
            MutableComponent line1 = Component.translatable("jabbadream.tooltip.warm_bed.line1_prefix")
                    .append(ingredientName)
                    .append(countText)
                    .append(Component.translatable("jabbadream.tooltip.warm_bed.line1_suffix"));
            event.getToolTip().add(line1);

            // ---------- Line 2~4：静态 ----------
            event.getToolTip().add(Component.translatable("jabbadream.tooltip.warm_bed.line2"));
            event.getToolTip().add(Component.translatable("jabbadream.tooltip.warm_bed.line3"));
            event.getToolTip().add(Component.translatable("jabbadream.tooltip.warm_bed.line4"));
            // line5 只在 oneTimeUse=true 时显示
            boolean oneTime = com.justabaddream.command.BadDreamCommands.RuntimeOverrides.oneTimeUse != null
                    ? com.justabaddream.command.BadDreamCommands.RuntimeOverrides.oneTimeUse
                    : com.justabaddream.config.JABDConfig.SERVER.oneTimeUse.get();
            if (oneTime) {
                event.getToolTip().add(Component.translatable("jabbadream.tooltip.warm_bed.line5"));
            }
        }
    }
}
