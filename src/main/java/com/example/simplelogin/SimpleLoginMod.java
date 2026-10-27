package com.example.simplelogin;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side login system. Every player who joins is "pending" until they /register or /login.
 * While pending they cannot move, chat, interact, take damage or run any other command.
 */
public class SimpleLoginMod implements ModInitializer {
    public static final String MOD_ID = "simplelogin";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ---- Settings (edit and rebuild) ----
    public static final int MIN_PASSWORD_LENGTH = 6;
    public static final int MAX_PASSWORD_LENGTH = 64;
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int LOGIN_TIMEOUT_SECONDS = 60;
    private static final int REMINDER_INTERVAL_TICKS = 200;

    /** Commands usable before logging in (first word, lower-case, without the slash). */
    private static final Set<String> ALLOWED_BEFORE_LOGIN = Set.of("login", "l", "register", "reg");

    private static final class Pending {
        int ticksLeft = LOGIN_TIMEOUT_SECONDS * 20;
        int failedAttempts = 0;
    }

    private static AccountStore store;
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    public static AccountStore store() {
        return store;
    }

    public static boolean isAuthenticated(ServerPlayer player) {
        return !PENDING.containsKey(player.getUUID());
    }

    public static void markAuthenticated(ServerPlayer player) {
        PENDING.remove(player.getUUID());
        LOGGER.info("{} is now logged in.", player.getName().getString());
    }

    /** Returns attempts left, or 0 if the player has run out (caller should kick). */
    public static int registerFailedAttempt(ServerPlayer player) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null) {
            return MAX_FAILED_ATTEMPTS;
        }
        pending.failedAttempts++;
        return Math.max(0, MAX_FAILED_ATTEMPTS - pending.failedAttempts);
    }

    /** Called from the command mixin. {@code command} has no leading slash. */
    public static boolean isCommandAllowedBeforeLogin(String command) {
        String trimmed = command.stripLeading();
        int space = trimmed.indexOf(' ');
        String first = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
        return ALLOWED_BEFORE_LOGIN.contains(first);
    }

    public static void sendLoginPrompt(ServerPlayer player) {
        String name = player.getName().getString();
        if (store.isRegistered(name)) {
            player.sendSystemMessage(Component.literal("Welcome back! Log in with: /login <password>"));
        } else {
            player.sendSystemMessage(Component.literal("Welcome! Create an account with: /register <password> <confirmPassword>"));
        }
    }

    @Override
    public void onInitialize() {
        store = new AccountStore(FabricLoader.getInstance().getConfigDir().resolve("simplelogin").resolve("accounts.json"));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                LoginCommands.register(dispatcher));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            PENDING.put(player.getUUID(), new Pending());
            LOGGER.info("{} joined and must log in.", player.getName().getString());
            sendLoginPrompt(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                PENDING.remove(handler.getPlayer().getUUID()));

        ServerTickEvents.END_SERVER_TICK.register(SimpleLoginMod::onTick);

        // Block everything else while not logged in.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (isAuthenticated(sender)) {
                return true;
            }
            sender.sendSystemMessage(Component.literal("You must log in before chatting."));
            return false;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> blocked(player));
        UseItemCallback.EVENT.register((player, level, hand) -> blocked(player));
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> blocked(player));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> blocked(player));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> blocked(player));
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                !(player instanceof ServerPlayer sp) || isAuthenticated(sp));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer sp) || isAuthenticated(sp));

        LOGGER.info("Simple Login loaded.");
    }

    private static InteractionResult blocked(Player player) {
        if (player instanceof ServerPlayer sp && !isAuthenticated(sp)) {
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    private static void onTick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Pending pending = PENDING.get(player.getUUID());
            if (pending == null) {
                continue;
            }

            if (--pending.ticksLeft <= 0) {
                player.connection.disconnect(Component.literal("Login timed out. Please rejoin and log in."));
                continue;
            }
            if (pending.ticksLeft % REMINDER_INTERVAL_TICKS == 0) {
                sendLoginPrompt(player);
            }
        }
    }
}
