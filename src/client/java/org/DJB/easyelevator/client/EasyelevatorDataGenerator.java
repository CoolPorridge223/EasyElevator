package org.DJB.easyelevator.client;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/** 数据生成入口（Fabric Data Generation）。
 * 作用：在开发环境通过 runDatagen 任务离线产出模组自带的 JSON 资源/数据（配方、战利品表、方块状态、模型、标签等），
 * 产物写入生成的资源包，从而不必手写这些文件；它不参与游戏运行时逻辑，打包后的模组也不会执行它。
 */
public class EasyelevatorDataGenerator implements DataGeneratorEntrypoint {

    /** 初始化数据生成器。
     * @param fabricDataGenerator Fabric 提供的数据生成器
     * 副作用：无。注意：这里没有注册任何 FabricDataProvider，因此现阶段运行数据生成不会产出任何文件；
     * 入口先保留以便后续新增 provider。
     *
     * <p>1.19.2 的 Fabric API 还没有 1.20 起的 {@code Pack} 概念（{@code fabricDataGenerator.createPack()}），
     * provider 直接在 {@code onInitializeDataGenerator} 里用 {@code addProvider(...)} 登记即可，
     * 因此本方法目前是空的。
     */
    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
    }
}
