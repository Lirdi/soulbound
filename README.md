# Soulbound

Capture a weakened player with a lead.

**How it works:** when a player is at 3 hearts or less, anyone holding a lead can right-click them to capture them.

- The captive is pulled along on a real lead and can't attack, build, eat, use items, pearl or fly.
- The captive taps **sneak** to struggle free (30 taps by default).
- The lead breaks if the captor takes 3 hearts of damage, lets go of the lead, teleports away, or after 3 minutes.
- The captor walks slower and can't pearl or fly.
- No captures within 48 blocks of spawn. A freed player can't be captured again for 5 minutes.
- Logging out while captured kills you (set `logout.captive: release` to go easy).
- `/soulbound release` lets your captive go. `/soulbound reload` reloads `config.yml` (ops).

Every number lives in `plugins/Soulbound/config.yml`.

## Building the jar
Pick one:

- **GitHub (nothing to install):** put this folder in a GitHub repository. GitHub builds it automatically; open the **Actions** tab, then the latest run, and download **Soulbound-jar**.
- **Windows PC:** install Java 25 (Eclipse Temurin), then double-click `BUILD-ON-WINDOWS.bat`. The jar appears in `build\libs`.

Upload `Soulbound-0.1.0.jar` to your server's `plugins` folder and restart.

Built for Paper 26.3.
