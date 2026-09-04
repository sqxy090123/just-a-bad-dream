package com.justabaddream.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.justabaddream.JABDMod;
import com.justabaddream.command.BadDreamCommands;
import com.justabaddream.config.JABDConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 自定义 shaped 配方序列化器（1.20.1 API：fromJson/fromNetwork/toNetwork）：
 * 读 JSON 时行为与 minecraft:crafting_shaped 完全一致，但会把 「N」 格 ingredient
 * 替换为 JABDConfig.COMMON.warmBedIngredient 对应的 Ingredient。
 *
 * 同时支持 JSON 中 「specialKey」 字段自定义特殊字符（默认 'N'）。
 * 网络传输直接委托给原版 ShapedRecipe 序列化器。
 */
public class WarmBedRecipeSerializer implements RecipeSerializer<ShapedRecipe> {

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.RECIPE_SERIALIZERS, JABDMod.MOD_ID);

    public static final RegistryObject<RecipeSerializer<ShapedRecipe>> WARM_BED_SERIALIZER =
            RECIPE_SERIALIZERS.register("warm_bed", () -> new WarmBedRecipeSerializer());

    // ------------------------------------------------------------------
    // 1.20.1 RecipeSerializer API：fromJson / fromNetwork / toNetwork
    // ------------------------------------------------------------------

    @Override
    public ShapedRecipe fromJson(ResourceLocation id, JsonObject json) {
        // 1) 读取 specialKey（默认 'N'）
        char specialKey = 'N';
        if (json.has("specialKey")) {
            String sk = GsonHelper.getAsString(json, "specialKey", "N");
            if (sk.length() == 1) specialKey = sk.charAt(0);
        }

        // 2) 从配置 + 命令 RuntimeOverrides 拿到"有效的特殊物品"
        ResourceLocation itemId = effectiveIngredientId();
        Item special = BuiltInRegistries.ITEM.getOptional(itemId)
                .or(() -> BuiltInRegistries.ITEM.getOptional(ResourceLocation.withDefaultNamespace("nether_star")))
                .orElseThrow();

        // 3) 修改 key 中的 specialKey → 指向配置物品
        JsonObject keyMap = json.getAsJsonObject("key");
        if (keyMap != null && keyMap.has(String.valueOf(specialKey))) {
            JsonObject specialEntry = new JsonObject();
            specialEntry.addProperty("item", BuiltInRegistries.ITEM.getKey(special).toString());
            keyMap.add(String.valueOf(specialKey), specialEntry);
        }

        // 4) count>1：把 pattern 中下 count-1 个 W/# 替换为 specialKey
        int count = BadDreamCommands.RuntimeOverrides.warmBedIngredientCount != null
                ? BadDreamCommands.RuntimeOverrides.warmBedIngredientCount
                : JABDConfig.COMMON.warmBedIngredientCount.get();
        count = Math.max(1, Math.min(3, count));

        if (count > 1 && json.has("pattern")) {
            JsonArray pattern = json.getAsJsonArray("pattern");
            int replaced = 1; // 原 N 已经算一个
            for (int row = 0; row < pattern.size() && replaced < count; row++) {
                String line = pattern.get(row).getAsString();
                char[] chars = line.toCharArray();
                for (int c = 0; c < chars.length && replaced < count; c++) {
                    if (chars[c] == 'W' || chars[c] == '#') {
                        chars[c] = specialKey;
                        replaced++;
                    }
                }
                pattern.set(row, new JsonPrimitive(new String(chars)));
            }
        }

        // 5) 委托给原版 shaped recipe 解析（修改后的 JSON）
        return RecipeSerializer.SHAPED_RECIPE.fromJson(id, json);
    }

    @Override
    public ShapedRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
        // 网络传输：直接委托给原版 shaped recipe 序列化器
        return RecipeSerializer.SHAPED_RECIPE.fromNetwork(id, buf);
    }

    @Override
    public void toNetwork(FriendlyByteBuf buf, ShapedRecipe recipe) {
        RecipeSerializer.SHAPED_RECIPE.toNetwork(buf, recipe);
    }

    // ------------------------------------------------------------------
    // 有效的特殊物品 ResLoc（RuntimeOverrides > Config > 校验合法性 > fallback nether_star）
    // ------------------------------------------------------------------
    public static final ResourceLocation FALLBACK_INGREDIENT = ResourceLocation.withDefaultNamespace("nether_star");

    /** 返回"有效的"合成特殊物品：命令覆盖 > 配置；若不存在则回退到下界之星并 warn。 */
    public static ResourceLocation effectiveIngredientId() {
        String configured = BadDreamCommands.RuntimeOverrides.warmBedIngredient != null
                ? BadDreamCommands.RuntimeOverrides.warmBedIngredient
                : JABDConfig.COMMON.warmBedIngredient.get();
        try {
            ResourceLocation id = ResourceLocation.tryParse(configured);
            if (id == null) {
                JABDMod.LOGGER.warn("[JABD] warmBedIngredient 配置格式非法：{}，回退到 minecraft:nether_star", configured);
                return FALLBACK_INGREDIENT;
            }
            if (!BuiltInRegistries.ITEM.containsKey(id)) {
                JABDMod.LOGGER.warn("[JABD] warmBedIngredient 在物品注册表中不存在：{}，回退到 minecraft:nether_star", configured);
                return FALLBACK_INGREDIENT;
            }
            return id;
        } catch (Exception e) {
            JABDMod.LOGGER.warn("[JABD] warmBedIngredient 解析异常：{} -> {}", configured, e.getMessage());
            return FALLBACK_INGREDIENT;
        }
    }

    /** 同上，返回 ItemStack 形式（含数量 1，用于 Tooltip） */
    public static ItemStack effectiveIngredientStack() {
        Item item = BuiltInRegistries.ITEM.getOptional(effectiveIngredientId())
                .orElse(BuiltInRegistries.ITEM.get(FALLBACK_INGREDIENT));
        return new ItemStack(item, 1);
    }
}
