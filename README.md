# Simple Login (Fabric, server-side)

Adds `/register`, `/login` and `/changepassword` for players joining a server.
Targets Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.164.0+26.3, Java 25.

## Build
1. Install JDK 25.
2. In this folder run `gradle wrapper` once if there is no `gradlew` (or just use `gradle build`).
3. `./gradlew build` -> jar is in `build/libs/simple-login-1.0.0.jar` (not the `-sources` jar).
4. Put the jar and Fabric API in the server's `mods/` folder.

## Commands
| Command | Who | Purpose |
|---|---|---|
| `/register <password> <confirm>` (`/reg`) | new players | create account |
| `/login <password>` (`/l`) | registered players | log in |
| `/changepassword <old> <new>` (`/changepass`) | logged-in players | change password |
| `/simplelogin unregister <player>` | server console only | delete an account |

## Behaviour
- Until logged in: cannot move, chat, break/place/interact, attack, take damage, or run any other command.
- Kicked after 60 s without logging in, or after 5 wrong passwords.
- Passwords stored as salted PBKDF2-HMAC-SHA256 in `config/simplelogin/accounts.json`.
- Settings (min length, timeout, attempts) are constants at the top of `SimpleLoginMod.java`.

## Known limits
- Vanilla may print `/login <password>` to the server log/console as an issued command. Restrict log access.
- No IP-based auto-login or Mojang-account detection; every player must log in each session.
