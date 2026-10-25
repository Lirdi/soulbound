package net.soulbound;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Rabbit;

import java.util.UUID;

/** One player holding another on a lead. */
final class Capture {
    final UUID captor;
    final UUID captive;
    long endsAt;
    final long startedAt;
    final BossBar captorBar;
    final BossBar captiveBar;
    /** Invisible anchor riding the captive, so a real lead can be drawn to it. */
    Rabbit anchor;
    int struggles;
    double captorDamage;
    /** Being held in the fire pit for the sacrifice. */
    boolean ritual;

    Capture(UUID captor, UUID captive, long startedAt, long endsAt, BossBar captorBar, BossBar captiveBar) {
        this.captor = captor;
        this.captive = captive;
        this.startedAt = startedAt;
        this.endsAt = endsAt;
        this.captorBar = captorBar;
        this.captiveBar = captiveBar;
    }
}
