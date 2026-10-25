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
- He rises on the dais: a giant warden everyone fights together, scaled live to the crowd in the hall. He charges (dodge so he hits a pillar and he topples, stunned), leaps and slams, breathes fire, blasts a fire ring, grabs players and hurls them across the hall (hit him hard enough and he drops them), and chains anyone hanging back. Arrows do half damage and his Soulfire Ward burns them. At 75% his gold-armoured champions shield him, at 50% he screams (Nausea, Weakness), at 25% he collapses and feeds (hunger to zero), then frenzies. When he dies he shudders and cracks, crashes over onto the floor and bursts apart in clanging metal, to his own death sound (`moloch:death`).
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

## Portals

Moloch's realm replaces the Nether. Every Overworld portal leads to one arrival point at the start of the Sacred Way, facing the temple. The Exit Gate behind it (built automatically, unbreakable) is the only way out, and returns each player to the portal they came through. Portals can't be lit in the realm, mobs and items can't use portals, and a captor brings their captive through with them (a captive can't use a portal alone). Admin: `/moloch gate [here]`, `/moloch setarrival`.

## God

The ender dragon is replaced by God: a giant grey-bearded old man in white robes with a halo, flying where the (invisible) dragon flies and standing upright when it perches or dies. He has an old man's voice (the villager's, pitched down). Hits on his body pass half their damage to the dragon. The skin is `assets/soulbound/textures/entity/god.png` in the server pack.

## Heaven (the End)

With the Heaven data pack the End becomes God's realm: a golden sky with clouds below the islands, grass and cherry-blossom gardens on cloud-white islands, music from the cherry grove. The server pack retextures end stone as gold-veined marble, purpur as white-and-gold marble (End cities become temples), chorus as gold and the end crystals as holy relics. The plugin builds the Pearly Gates by the arrival platform and God's Throne with a ring of columns around the exit portal. Reset the End (delete `world_the_end`) for the new terrain.
