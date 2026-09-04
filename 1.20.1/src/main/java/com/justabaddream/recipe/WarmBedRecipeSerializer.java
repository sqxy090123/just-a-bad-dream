package com.justabaddream.recipe;

import com.justabaddream.JABDMod;
import com.justabaddream.command.BadDreamCommands;
import com.justabaddream.config.JABDConfig;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.ItemLike;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义 shaped 配方序列化器：
 * 读 JSON 时行为与 minecraft:crafting_shaped 完全一致，但会把 「N」 格 ingredient
 * 替换为 JABDConfig.COMMON.warmBedIngredient + warmBedIngredientCount 对应数量的 Ingredient。
 *
 * 同时支持 JSON 中 「specialKey」 字段自定义特殊字符（默认 'N'）。
 */
public class WarmBedRecipeSerializer implements RecipeSerializer<ShapedRecipe> {

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.RECIPE_SERIALIZERS, JABDMod.MOD_ID);

    public static final RegistryObject<RecipeSerializer<ShapedRecipe>> WARM_BED_SERIALIZER =
            RECIPE_SERIALIZERS.register("warm_bed", () -> new WarmBedRecipeSerializer());

    // ------------------------------------------------------------------
    // 内部：为 ShapedRecipe 构造所需的 Pattern / Ingredient 映射数据类
    // ------------------------------------------------------------------
    private record RawShaped(
            String group,
            CraftingBookCategory category,
            List<String> pattern,
            Map<String, Ingredient> key,
            ItemStack result,
            boolean showNotification,
            Character specialKey
    ) {}

    private static final Codec<Character> CHAR_CODEC = Codec.STRING.comapFlatMap(
            s -> s.length() == 1 ? DataResult.success(s.charAt(0))
                    : DataResult.error(() -> "Invalid key character: '" + s + "' (must be 1 char)"),
            Object::toString
    );

    private static final MapCodec<RawShaped> RAW_CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
            Codec.STRING.optionalFieldOf("group", "").forGetter(RawShaped::group),
            CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC).forGetter(RawShaped::category),
            Codec.STRING.listOf().fieldOf("pattern").forGetter(RawShaped::pattern),
            Codec.unboundedMap(Codec.STRING.xmap(s -> s.charAt(0), Object::toString), Ingredient.CODEC_NONEMPTY)
                    .fieldOf("key").forGetter(r -> convertKeyMap(r.key)),
            ItemStack.CODEC.fieldOf("result").forGetter(RawShaped::result),
            Codec.BOOL.optionalFieldOf("show_notification", true).forGetter(RawShaped::showNotification),
            CHAR_CODEC.optionalFieldOf("specialKey", 'N').forGetter(RawShaped::specialKey)
    ).apply(b, (g, cat, p, k, r, sn, sk) -> {
        Map<String, Ingredient> keyStr = new HashMap<>();
        k.forEach((ch, ing) -> keyStr.put(String.valueOf(ch), ing));
        return new RawShaped(g, cat, p, keyStr, r, sn, sk);
    }));

    @SuppressWarnings("unchecked")
    private static Map<String, Ingredient> convertKeyMap(Map<Character, Ingredient> charMap) {
        Map<String, Ingredient> out = new HashMap<>();
        charMap.forEach((c, i) -> out.put(String.valueOf(c), i));
        return out;
    }

    // ------------------------------------------------------------------
    // 解析/编解码 入口
    // ------------------------------------------------------------------
    @Override
    public MapCodec<ShapedRecipe> codec() {
        return RAW_CODEC.flatXmap(
                raw -> {
                    try {
                        ShapedRecipe recipe = buildShapedFromRaw(raw);
                        return DataResult.success(recipe);
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        return DataResult.error(e::getMessage);
                    }
                },
                shaped -> DataResult.error(() -> "WarmBed recipes are not serializable back to JSON (ingredient is runtime-configurable)")
        );
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, ShapedRecipe> streamCodec() {
        // 网络传输：直接使用 vanilla shaped recipe 的默认 stream codec
        // （shaped recipe 在服务端合成时结果已经是一个 shapedRecipe 对象，写回没问题）
        return RecipeSerializer.SHAPED_RECIPE.streamCodec();
    }

    // ------------------------------------------------------------------
    // 把 RawShaped + 配置 ingredient → vanilla ShapedRecipe
    // ------------------------------------------------------------------
    private static ShapedRecipe buildShapedFromRaw(RawShaped raw) {
        // 1) 从配置 + 命令 RuntimeOverrides 拿到"有效的特殊物品"
        ResourceLocation itemId = effectiveIngredientId();
        int count = BadDreamCommands.RuntimeOverrides.warmBedIngredientCount != null
                ? BadDreamCommands.RuntimeOverrides.warmBedIngredientCount
                : JABDConfig.COMMON.warmBedIngredientCount.get();
        count = Math.max(1, Math.min(3, count)); // clamp 1~3

        Item special = BuiltInRegistries.ITEM.getOptional(itemId)
                .or(() -> BuiltInRegistries.ITEM.getOptional(ResourceLocation.withDefaultNamespace("nether_star")))
                .orElseThrow();

        // 2) 拷贝原始 key Map；将 specialKey 替换为配置物品 × count 合成 Ingredient（允许多个堆叠；实际上 shaped 合成每个格子都是 1 个 Ingredient）
        //    对于 count>1：将配置"多个该物品"理解成"把多格羊毛替换"——因此我们把接下来 count-1 个原 key.W（羊毛）位置也替换成 special。
        Map<String, Ingredient> keyMap = new HashMap<>(raw.key());
        String specialKeyStr = String.valueOf(raw.specialKey());
        Ingredient specialIng = Ingredient.of(new ItemLike[]{special}); // 每个 shaped 格子 1 个单位（原版 shaped 无法要"2个同格"——需要多格）
        keyMap.put(specialKeyStr, specialIng);

        // count>1：在 pattern 中找多余的 'W' 或 '#'（羊毛占位），将其替换为同样的 ingredient
        if (count > 1) {
            // 从 pattern 里逐行扫描，遇到未被占用的羊毛（W/# 键）时，把它的映射也替换成特殊物品
            // 并且把 pattern 对应字符改为 'N' 或自定义字符：因为 shaped 配方本质是 pattern <-> key 一一对应，
            // 简单起见：我们直接修改 keyMap，把 W/# 映射到"羊毛或特殊物品"的 OR Ingredient（其实只能是其中之一）
            // — 但这样玩家可以用羊毛 OR 物品，不对。
            //
            // 正确做法：把 pattern 中的下 count-1 个 W/# 改成 specialKeyChar，
            // 但 specialKeyChar 已经映射到 Ingredient(special)，因此就强制多格要求物品 X 了。
            List<String> mutablePattern = new java.util.ArrayList<>(raw.pattern());
            int replaced = 1;
            for (int row = 0; row < mutablePattern.size() && replaced < count; row++) {
                char[] chars = mutablePattern.get(row).toCharArray();
                for (int c = 0; c < chars.length && replaced < count; c++) {
                    char ch = chars[c];
                    if (ch == 'W' || ch == '#') {
                        chars[c] = raw.specialKey();
                        replaced++;
                    }
                }
                mutablePattern.set(row, new String(chars));
            }
            // 此时 pattern 已修改，'N' key 仍然是 specialIng。
            raw = new RawShaped(raw.group(), raw.category(), mutablePattern, keyMap, raw.result(), raw.showNotification(), raw.specialKey());
        } else {
            raw = new RawShaped(raw.group(), raw.category(), raw.pattern(), keyMap, raw.result(), raw.showNotification(), raw.specialKey());
        }

        // 3) 用 vanilla ShapedRecipe 的公开构造方式：走 ShapedRecipe 内部构造（它是 public 吗？）
        //    1.20.1: ShapedRecipe 是 public final，有 public constructor — 需要 width/height/ingredients/result...
        //    但最稳的办法是借助 "ShapedRecipe.Serializer.fromJson" 已经有的逻辑
        //    — 然而我们没法调用内部方法，因此我们手动从 pattern + keyMap 解析 width/height/ingredients[]
        return buildShapedManual(raw);
    }

    // Manual construction mirroring vanilla ShapedRecipe.Serializer behavior.
    private static ShapedRecipe buildShapedManual(RawShaped raw) {
        List<String> pattern = raw.pattern();
        int height = pattern.size();
        int width = 0;
        for (String s : pattern) width = Math.max(width, s.length());

        Map<String, Ingredient> keyMap = new HashMap<>(raw.key());
        // 空格永远是 EMPTY
        keyMap.put(" ", Ingredient.EMPTY);

        NonNullList<Ingredient> ingredients = NonNullList.withSize(width * height, Ingredient.EMPTY);
        for (int row = 0; row < pattern.size(); row++) {
            String line = pattern.get(row);
            for (int col = 0; col < line.length(); col++) {
                String chStr = String.valueOf(line.charAt(col));
                Ingredient ing = keyMap.get(chStr);
                if (ing == null) throw new IllegalArgumentException(
                        "Pattern references undefined key '" + chStr + "' in warm_bed recipe");
                ingredients.set(col + row * width, ing);
            }
        }

        return new ShapedRecipe(raw.group(), raw.category(), width, height, ingredients, raw.result(), raw.showNotification());
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
