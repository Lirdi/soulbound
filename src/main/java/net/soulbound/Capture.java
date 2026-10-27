package net.soulbound;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Rabbit;

import java.util.UUID;

/** One player holding another: chained on a lead, perhaps hooded, perhaps carried. */
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
    /** Slung over the captor's shoulder: the captive rides them, hidden, and a body lies across the shoulder. */
    boolean carried;
    /** Thrown towards the fire: until this tick the chain lets them fly. */
    long thrownUntil;
    org.bukkit.entity.Mannequin body;
    /** A sack over the head: blind, nameless. */
    boolean hooded;
    org.bukkit.entity.ItemDisplay hoodShow, shackleShow;
    String prevTeam;
    net.kyori.adventure.text.Component prevListName;

    Capture(UUID captor, UUID captive, long startedAt, long endsAt, BossBar captorBar, BossBar captiveBar) {
        this.captor = captor;
        this.captive = captive;
        this.startedAt = startedAt;
        this.endsAt = endsAt;
        this.captorBar = captorBar;
        this.captiveBar = captiveBar;
    }
}
