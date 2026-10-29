package dev.dtap.mixin;

import dev.dtap.DTapClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class NetHandlerMixin {
    @Inject(method = "onEntityStatus", at = @At("TAIL"))
    private void dtap$status(EntityStatusS2CPacket packet, CallbackInfo ci) {
        if (packet.getStatus() == 35) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) return;
            Entity e = packet.getEntity(mc.world);
            if (e != null) DTapClient.onPop(e);
        }
    }
}
