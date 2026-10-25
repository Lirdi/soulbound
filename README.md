# Soulbound

Capture weakened players, sacrifice them to Moloch, and fight Moloch himself. Works with the Sacrifice data pack (Moloch's realm, the temple and the arena under the pyramid).

**How it works:** when a player is at 3 hearts or less, anyone holding a lead can right-click them to capture them.

- The captive is pulled along on a real lead and can't attack, build, eat, use items, pearl or fly.
- The captive taps **sneak** to struggle free (30 taps by default).
- The lead breaks if the captor takes 3 hearts of damage, lets go of the lead, teleports away, or after 3 minutes.
- The captor walks slower and can't pearl or fly.
- No captures within 48 blocks of spawn. A freed player can't be captured again for 5 minutes.
- Logging out while captured kills you (set `logout.captive: release` to go easy).
- `/soulbound release` lets your captive go. `/soulbound reload` reloads `config.yml` (ops).

- While captured, a player can only use a short list of commands (messages, reports). The capture timer pauses at the temple.

## Sacrifice and the curse
- Drag your captive into the fire pit at the top of the temple. A 10-second ritual starts; anyone who hits the captor hard enough in that time breaks it.
- When it completes, the captive dies (keeping their items), the captor gets Moloch's reward, and the victim comes back with **Moloch's Curse**: Weakness and Mining Fatigue for 45 minutes of play, plus Slowness for the first 10.
- Limits: the same player once every 48 hours, each captor once a day, 3 a day on the whole server, and no one with under 5 hours of playtime.

## Moloch's Hunger and the awakening
- Sacrifices and gold or mob offerings in the pit fill Moloch's Hunger (0 to 100).
- When it's full, his awakening is booked for the next Friday or Saturday at 8pm (London time), with at least 6 hours' notice, and announced.
- The fight opens with waves of his servants (cultists, ember spirits, temple guards) through the arena doorways; clear them all and he rises on the dais: a giant warden everyone fights together. Fire rings, reinforcements and fire rain as his health drops. He only targets players and never blinds them with Darkness.
- Bring him down in 30 minutes and everyone who hurt him gets diamonds and XP, with extra for the top damage dealer. Fail and a blood moon hangs over his realm for 24 hours (stronger mobs).
- No other wardens exist in the world (ancient-city shriekers summon nothing).

## Commands
- `/moloch`: Moloch's state, his Hunger, when he rises, your curse.
- Admin: `/moloch awaken` (start the fight now), `/moloch stop`, `/moloch hunger <0-100>`, `/moloch curse <player> [minutes]`, `/moloch uncurse <player>`, `/moloch reload`.
- `/soulbound give <player> [amount]` gives capture leashes.

Every number lives in `plugins/Soulbound/config.yml`. Moloch's sounds (`moloch:awaken`, `moloch:drums`, `moloch:furnace`) come from the server resource pack.

## Building the jar
Pick one:

- **GitHub (nothing to install):** put this folder in a GitHub repository. GitHub builds it automatically; open the **Actions** tab, then the latest run, and download **Soulbound-jar**.
- **Windows PC:** install Java 25 (Eclipse Temurin), then double-click `BUILD-ON-WINDOWS.bat`. The jar appears in `build\libs`.

Upload `Soulbound-0.2.1.jar` to your server's `plugins` folder and restart.

Built for Paper 26.3.
