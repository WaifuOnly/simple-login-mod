package com.example.simplelogin;

import com.mojang.brigadier.CommandDispatcher;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * Adds /back: after dying, a logged-in player can return to where they died.
 * The death point is kept in memory only (cleared on server restart) and is
 * erased after one successful /back.
 */
public class BackMod implements ModInitializer {

    private record DeathPoint(ResourceKey<Level> dimension, double x, double y, double z, float yRot, float xRot) {}

    private static final Map<UUID, DeathPoint> DEATHS = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        ServerLivingEntityEvents.AFTER_DEATH.register((LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) -> {
            if (entity instanceof ServerPlayer player) {
                DEATHS.put(player.getUUID(), new DeathPoint(
                        player.level().dimension(),
                        player.getX(), player.getY(), player.getZ(),
                        player.getYRot(), player.getXRot()));
                player.sendSystemMessage(Component.literal("You died. Type /back to return to where you died."));
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("back").executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            ServerPlayer player = src.getPlayerOrException();

            if (!SimpleLoginMod.isAuthenticated(player)) {
                src.sendFailure(Component.literal("You must log in first."));
                return 0;
            }

            DeathPoint point = DEATHS.get(player.getUUID());
            if (point == null) {
                src.sendFailure(Component.literal("You have no death location to return to."));
                return 0;
            }

            MinecraftServer server = src.getServer();
            ServerLevel level = server.getLevel(point.dimension());
            if (level == null) {
                src.sendFailure(Component.literal("That dimension is no longer available."));
                return 0;
            }

            player.teleportTo(level, point.x(), point.y(), point.z(), Set.of(), point.yRot(), point.xRot(), true);
            DEATHS.remove(player.getUUID());
            src.sendSuccess(() -> Component.literal("Returned to your death location."), false);
            return 1;
        }));
    }
}
