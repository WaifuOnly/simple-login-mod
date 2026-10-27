package com.example.simplelogin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Persists accounts to config/simplelogin/accounts.json (keyed by lower-cased player name). */
public final class AccountStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Account>>() {
    }.getType();

    public static final class Account {
        public String hash;
        public long registeredAt;
        public long lastLogin;
    }

    private final Path file;
    private final Map<String, Account> accounts = new HashMap<>();

    public AccountStore(Path file) {
        this.file = file;
        load();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private synchronized void load() {
        accounts.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Account> loaded = GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                accounts.putAll(loaded);
            }
        } catch (IOException | RuntimeException e) {
            // Refuse to start with a silently-empty account list: that would let anyone re-register.
            throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(accounts, MAP_TYPE, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            SimpleLoginMod.LOGGER.error("Failed to save accounts", e);
        }
    }

    public synchronized boolean isRegistered(String name) {
        return accounts.containsKey(key(name));
    }

    public synchronized boolean verify(String name, String password) {
        Account account = accounts.get(key(name));
        return account != null && PasswordHasher.verify(password, account.hash);
    }

    public synchronized void register(String name, String password) {
        Account account = new Account();
        account.hash = PasswordHasher.hash(password);
        account.registeredAt = System.currentTimeMillis();
        account.lastLogin = account.registeredAt;
        accounts.put(key(name), account);
        save();
    }

    public synchronized void changePassword(String name, String newPassword) {
        Account account = accounts.get(key(name));
        if (account == null) {
            return;
        }
        account.hash = PasswordHasher.hash(newPassword);
        save();
    }

    public synchronized void touchLogin(String name) {
        Account account = accounts.get(key(name));
        if (account != null) {
            account.lastLogin = System.currentTimeMillis();
            save();
        }
    }

    public synchronized boolean unregister(String name) {
        boolean removed = accounts.remove(key(name)) != null;
        if (removed) {
            save();
        }
        return removed;
    }
}
