package org.DJB.easyelevator.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import org.DJB.easyelevator.logic.CargoLoad;
import org.DJB.easyelevator.screen.CargoScreenHandler;

/** 保留原版物品栏操作，沿用电梯面板的深色配色和清晰背景。 */
public class CargoScreen extends HandledScreen<CargoScreenHandler> {
    public CargoScreen(CargoScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        backgroundWidth = 176;
        backgroundHeight = 210;
        playerInventoryTitleY = 116;
    }

    @Override protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        context.fill(x, y, x + backgroundWidth, y + backgroundHeight, 0xFF161D27);
        context.drawBorder(x, y, backgroundWidth, backgroundHeight, 0xFF4A5A6D);
        context.fill(x + 1, y + 1, x + 175, y + 46, 0xFF1E2733);
        for (var slot : handler.slots) {
            context.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, 0xFF4A5A6D);
            context.fill(x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16, 0xFF0F141B);
        }
        context.fill(x + 8, y + 43, x + 168, y + 45, 0xFF0F141B);
        int used = Math.min(160, handler.cargoItems() * 160 / CargoLoad.MAX_ITEMS);
        context.fill(x + 8, y + 43, x + 8 + used, y + 45, 0xFFFFBC62);
    }

    @Override protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        context.drawText(textRenderer, title, 8, 6, 0xFFEAF2FA, false);
        context.drawText(textRenderer, Text.translatable("screen.easyelevator.cargo_count", handler.cargoItems()),
                8, 19, 0xFFB7C7D9, false);
        context.drawText(textRenderer, Text.translatable("screen.easyelevator.cargo_capacity", handler.passengerLimit()),
                8, 31, 0xFFFFBC62, false);
        context.drawText(textRenderer, playerInventoryTitle, 8, playerInventoryTitleY, 0xFFB7C7D9, false);
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
        if (mouseX >= x + 8 && mouseX < x + 168 && mouseY >= y + 19 && mouseY < y + 46)
            context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(
                    Text.translatable("screen.easyelevator.cargo_rule"), 220), mouseX, mouseY);
    }

    @Override public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x18000000);
        drawBackground(context, delta, mouseX, mouseY);
    }
}
