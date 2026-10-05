package org.DJB.easyelevator.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.DJB.easyelevator.Easyelevator;

/**
 * 玻璃专用渲染层：观光轿厢的玻璃墙/玻璃门与"铁框玻璃"的楼层门共用同一套参数，只是贴图不同。
 *
 * <p>参数逐项照抄原版 {@code RenderLayer.getEntityTranslucent(TEXTURE)}（同一个着色器、同样的半透明混合、
 * 同样的禁止剔除），唯一区别是把写掩码从默认的"颜色 + 深度"改成 {@link RenderPhase#COLOR_MASK}
 * （<b>只写颜色、不写深度</b>）。
 *
 * <p>为什么必须自己建层：原版 {@code entity_translucent} 会写深度缓冲，而世界渲染的固定顺序是
 * <b>实体 → 方块实体</b>（{@code WorldRenderer.render}：先 "entities" 再 "blockentities"）。
 * 观光轿厢的玻璃离观察者更近，它一旦写入深度，之后才绘制的楼层门（方块实体渲染器）就会被整片剔除，
 * 现象是"坐在观光轿厢里看不见每层的电梯门"。只写颜色后，玻璃不再遮挡任何后画的东西，
 * 而它自己仍受深度测试约束（会被舱体不透明件正确遮挡）——与原版对方块半透明层（玻璃、水）的处理一致。
 *
 * <p>层名必须唯一：{@code RenderLayer} 用名字做相等性判定与缓冲分组，重名会与原版层共用缓冲。
 */
public final class GlassLayers {
    private GlassLayers() { }

    /** 实体图集（观光轿厢的玻璃格就在这张图里）。 */
    public static final Identifier CABIN_TEXTURE=Easyelevator.id("textures/entity/cabin.png");
    /** 方块玻璃贴图（铁框玻璃门用）。 */
    public static final Identifier DOOR_TEXTURE=Easyelevator.id("textures/block/blank_glass.png");

    /** 观光轿厢的玻璃墙/玻璃门。 */
    public static final RenderLayer CABIN=of("easyelevator_cabin_glass",CABIN_TEXTURE);
    /** 楼层门的铁框玻璃门扇（贴图是方块玻璃）。 */
    public static final RenderLayer DOOR=of("easyelevator_door_glass",DOOR_TEXTURE);

    /**
     * 建一个"只写颜色"的实体半透明层。
     *
     * @param name 层名，必须全局唯一
     * @param texture 该层绑定的贴图
     * @return 渲染层
     */
    private static RenderLayer of(String name,Identifier texture) {
        return RenderLayer.of(name,VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                VertexFormat.DrawMode.QUADS,1536,true,true,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(RenderPhase.ENTITY_TRANSLUCENT_PROGRAM)
                        .texture(new RenderPhase.Texture(texture,false,false))
                        .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                        .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                        .cull(RenderPhase.DISABLE_CULLING)
                        .lightmap(RenderPhase.ENABLE_LIGHTMAP)
                        .overlay(RenderPhase.ENABLE_OVERLAY_COLOR)
                        .writeMaskState(RenderPhase.COLOR_MASK)
                        .build(true));
    }
}
