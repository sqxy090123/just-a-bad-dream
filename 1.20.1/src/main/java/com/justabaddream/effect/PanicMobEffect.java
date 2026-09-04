package com.justabaddream.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 惊慌（Panic）自定义药水效果。
 * <p>
 * 行为（不是靠 MobEffect 原生 tick 机制，而是由事件统一实现，便于配置/修改）：
 * <ul>
 *   <li>无法入睡</li>
 *   <li>无法与大多数功能方块交互（床、工作台、熔炉、铁砧、附魔台、酿造台、箱子类、锻造台、切石机、讲台、砂轮、织布机、信标、末影箱、命令方块等）</li>
 *   <li>交互时提示 "你现在很慌张" / "You are in a panic"</li>
 *   <li>视觉附加：效果为 HARMFUL 负面，粒子颜色偏"惊恐白"，带有速度 I（手抖呼吸急促的视觉隐喻）</li>
 * </ul>
 *
 * 该效果通过 {@link MobEffectCategory#HARMFUL} 标记为有害效果，
 * 可以被牛奶（milk bucket）正常清除。
 */
public class PanicMobEffect extends MobEffect {

    public PanicMobEffect() {
        super(MobEffectCategory.HARMFUL, 0xFFFFFF);  // 惊恐白
        // 轻微速度加成（≈+8%）模拟呼吸急促/发抖的视觉感。
        // UUID 为 JABD 独立生成，避免与原版 SPEED / SLOWNESS 冲突。
        addAttributeModifier(Attributes.MOVEMENT_SPEED, "9F71C5D3-31A1-49B9-9F8B-8D5234AA2921",
                0.08D, AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    @Override
    public boolean isBeneficial() { return false; }
}
