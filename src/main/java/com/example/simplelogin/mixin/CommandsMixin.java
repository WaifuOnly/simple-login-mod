package com.example.simplelogin.mixin;

import com.example.simplelogin.SimpleLoginMod;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Single choke point for every command a player runs: blocks all but /login and /register until logged in. */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void simplelogin$blockBeforeLogin(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
        if (!(parseResults.getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (SimpleLoginMod.isAuthenticated(player) || SimpleLoginMod.isCommandAllowedBeforeLogin(command)) {
            return;
        }
        player.sendSystemMessage(Component.literal("You must log in first."));
        SimpleLoginMod.sendLoginPrompt(player);
        ci.cancel();
    }
}
