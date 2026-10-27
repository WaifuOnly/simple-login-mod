package com.example.simplelogin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class LoginCommands {
    private LoginCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String name : new String[]{"register", "reg"}) {
            dispatcher.register(Commands.literal(name)
                    .executes(ctx -> usage(ctx.getSource(), "/register <password> <confirmPassword>"))
                    .then(Commands.argument("password", StringArgumentType.word())
                            .then(Commands.argument("confirm", StringArgumentType.word())
                                    .executes(ctx -> register(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "password"),
                                            StringArgumentType.getString(ctx, "confirm"))))));
        }

        for (String name : new String[]{"login", "l"}) {
            dispatcher.register(Commands.literal(name)
                    .executes(ctx -> usage(ctx.getSource(), "/login <password>"))
                    .then(Commands.argument("password", StringArgumentType.word())
                            .executes(ctx -> login(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "password")))));
        }

        for (String name : new String[]{"changepassword", "changepass"}) {
            dispatcher.register(Commands.literal(name)
                    .executes(ctx -> usage(ctx.getSource(), "/changepassword <oldPassword> <newPassword>"))
                    .then(Commands.argument("old", StringArgumentType.word())
                            .then(Commands.argument("new", StringArgumentType.word())
                                    .executes(ctx -> changePassword(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "old"),
                                            StringArgumentType.getString(ctx, "new"))))));
        }

        // Console-only admin command: /simplelogin unregister <player>
        LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("simplelogin")
                .requires(source -> source.getEntity() == null)
                .then(Commands.literal("unregister")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    String target = StringArgumentType.getString(ctx, "player");
                                    boolean removed = SimpleLoginMod.store().unregister(target);
                                    String msg = removed
                                            ? "Removed account for " + target + ". They can /register again."
                                            : "No account found for " + target + ".";
                                    ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
                                    return removed ? 1 : 0;
                                })));
        dispatcher.register(admin);
    }

    private static int usage(CommandSourceStack source, String usage) {
        source.sendFailure(Component.literal("Usage: " + usage));
        return 0;
    }

    private static int register(CommandSourceStack source, String password, String confirm) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String name = player.getName().getString();

        if (SimpleLoginMod.isAuthenticated(player)) {
            return fail(source, "You are already logged in.");
        }
        if (SimpleLoginMod.store().isRegistered(name)) {
            return fail(source, "This name is already registered. Use /login <password>.");
        }
        if (!password.equals(confirm)) {
            return fail(source, "Passwords do not match.");
        }
        String problem = validate(password);
        if (problem != null) {
            return fail(source, problem);
        }

        SimpleLoginMod.store().register(name, password);
        SimpleLoginMod.markAuthenticated(player);
        return ok(source, "Registered and logged in. Welcome!");
    }

    private static int login(CommandSourceStack source, String password) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String name = player.getName().getString();

        if (SimpleLoginMod.isAuthenticated(player)) {
            return fail(source, "You are already logged in.");
        }
        if (!SimpleLoginMod.store().isRegistered(name)) {
            return fail(source, "You are not registered yet. Use /register <password> <confirmPassword>.");
        }
        if (!SimpleLoginMod.store().verify(name, password)) {
            int left = SimpleLoginMod.registerFailedAttempt(player);
            if (left <= 0) {
                player.connection.disconnect(Component.literal("Too many wrong passwords."));
                return 0;
            }
            return fail(source, "Wrong password. Attempts left: " + left);
        }

        SimpleLoginMod.markAuthenticated(player);
        SimpleLoginMod.store().touchLogin(name);
        return ok(source, "Logged in successfully. Welcome back!");
    }

    private static int changePassword(CommandSourceStack source, String oldPassword, String newPassword) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String name = player.getName().getString();

        if (!SimpleLoginMod.isAuthenticated(player)) {
            return fail(source, "Log in first with /login <password>.");
        }
        if (!SimpleLoginMod.store().verify(name, oldPassword)) {
            return fail(source, "Your old password is incorrect.");
        }
        if (oldPassword.equals(newPassword)) {
            return fail(source, "The new password must be different from the old one.");
        }
        String problem = validate(newPassword);
        if (problem != null) {
            return fail(source, problem);
        }

        SimpleLoginMod.store().changePassword(name, newPassword);
        return ok(source, "Password changed successfully.");
    }

    private static String validate(String password) {
        if (password.length() < SimpleLoginMod.MIN_PASSWORD_LENGTH) {
            return "Password must be at least " + SimpleLoginMod.MIN_PASSWORD_LENGTH + " characters.";
        }
        if (password.length() > SimpleLoginMod.MAX_PASSWORD_LENGTH) {
            return "Password must be at most " + SimpleLoginMod.MAX_PASSWORD_LENGTH + " characters.";
        }
        return null;
    }

    private static int ok(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
