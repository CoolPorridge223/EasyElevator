package org.DJB.easyelevator.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

/**
 * 原版无色玻璃：直接复用 Minecraft 贴图（也能跟随资源包替换）。
 * 采用标准实体 cutout 管线，透明像素丢弃，其余像素正常接受环境光。
 * 不再用整面蓝色 alpha 混合，也不使用自定义 translucent 着色器。
 * 透明区域不写深度，因此不会遮掉随后绘制的楼层门、实体或水面。
 * 玻璃提交正反两个法线相反的面，背面剔除保证每次只显示一面。
 * 这是移动实体的玻璃外观，不冒充光影包的地形玻璃材质 ID。
 */
public final class GlassLayers {
    private GlassLayers() { }

    public static final Identifier CABIN_TEXTURE=Identifier.ofVanilla("textures/block/glass.png");
    public static final Identifier DOOR_TEXTURE=CABIN_TEXTURE;
    public static final RenderLayer CABIN=RenderLayer.getEntityCutout(CABIN_TEXTURE);
    public static final RenderLayer DOOR=CABIN;
}
