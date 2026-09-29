package org.DJB.easyelevator.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.network.ElevatorNetworking;
import java.util.List;

public class ElevatorScreen extends Screen {
    private final int entityId;
    private final List<BlockPos> stops;
    private int page, rows=6;
    public ElevatorScreen(ElevatorNetworking.OpenPanel payload) {
        super(Text.translatable("screen.easyelevator.title")); entityId=payload.entityId();stops=payload.stops();
    }
    @Override protected void init() {
        rows=Math.max(1,Math.min(8,(height-130)/24)); page=Math.min(page,Math.max(0,(stops.size()-1)/rows));
        int left=width/2-110, top=height/2-(rows*24+100)/2;
        for (int i=page*rows;i<Math.min(stops.size(),(page+1)*rows);i++) {
            int index=i; BlockPos stop=stops.get(i);
            addDrawableChild(ButtonWidget.builder(Text.translatable("screen.easyelevator.station",index+1,stop.getY()),button->{
                ClientPlayNetworking.send(new ElevatorNetworking.SelectStop(entityId,stop));
            }).dimensions(left,top+52+(i-page*rows)*24,220,20).build());
        }
        int bottom=top+52+rows*24;
        var previous=addDrawableChild(ButtonWidget.builder(Text.literal("<"),b->{page--;clearAndInit();}).dimensions(left,bottom,40,20).build());
        previous.active=page>0;
        var next=addDrawableChild(ButtonWidget.builder(Text.literal(">"),b->{page++;clearAndInit();}).dimensions(left+180,bottom,40,20).build());
        next.active=(page+1)*rows<stops.size();
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"),b->close()).dimensions(left+60,bottom,100,20).build());
    }
    private CabinEntity cabin() {
        return client!=null && client.world!=null && client.world.getEntityById(entityId) instanceof CabinEntity c?c:null;
    }
    @Override public void tick() {
        var cabin=cabin();
        if (cabin==null || client.player==null || !cabin.containsPassenger(client.player)) close();
    }
    @Override public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        renderBackground(context,mouseX,mouseY,delta);
        int top=height/2-(rows*24+100)/2;
        context.fill(width/2-122,top-8,width/2+122,top+rows*24+84,0xEC171E29);
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,top,0xFFFFFF);
        var cabin=cabin();
        if(cabin!=null) {
            Text status=Text.translatable("screen.easyelevator.status",String.format(java.util.Locale.ROOT,"%.1f",cabin.getY()),
                    Text.translatable("phase.easyelevator."+cabin.phase().name().toLowerCase(java.util.Locale.ROOT)));
            context.drawCenteredTextWithShadow(textRenderer,status,width/2,top+17,0xC3D5E8);
        }
        context.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.easyelevator.count",stops.size(),page+1,Math.max(1,(stops.size()+rows-1)/rows)),width/2,top+33,0xA7B4C5);
        if(stops.isEmpty()) context.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.easyelevator.empty"),width/2,top+64,0xFFC27A);
        super.render(context,mouseX,mouseY,delta);
    }
    @Override public boolean shouldPause() { return false; }
}
