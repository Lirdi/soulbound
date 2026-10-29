package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The inhuman see their own hearts gone wrong: a bruised purple health bar on near-black slots with a green rim.
 *
 * The resource pack redraws the game's hardcore hearts. This tells only the inhuman players' game that the world is
 * hardcore (one flag in the join packet), so they get those hearts and nobody else does. The server itself isn't
 * hardcore: dying and respawning work as normal. The pack also renames the hardcore death screen back to
 * "You Died!" / "Respawn".
 *
 * The flag is only sent when someone joins, so a player who turns inhuman (or is cured) sees the change the next time
 * they connect - or straight away, if inhuman.hearts.reconnect is on and the server accepts transfers.
 *
 * Paper has no API for this, so it's done by reflection on the connection: if anything here doesn't match the
 * server, it logs one warning and the hearts simply stay vanilla.
 */
final class InhumanHearts implements Listener {
    private static final String HANDLER = "soulbound_hearts";
    private final Soulbound plugin;
    private final Set<UUID> marked = ConcurrentHashMap.newKeySet();
    private final Map<Method, Method> ctxMethods = new ConcurrentHashMap<>();
    private volatile boolean warned;

    InhumanHearts(Soulbound plugin) {
        this.plugin = plugin;
        for (String s : plugin.store().data.getStringList("inhuman-hearts")) {
            try { marked.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
    }

    private boolean on() { return plugin.getConfig().getBoolean("inhuman.hearts.enabled", true); }

    void start() {
        try {
            Class<? extends Event> ev = Class.forName("io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent").asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(ev, this, EventPriority.MONITOR, (l, e) -> { if (ev.isInstance(e)) configure(e); }, plugin);
        } catch (ClassNotFoundException ex) {
            plugin.getLogger().warning("Inhuman hearts are off: this Paper build has no connection-configure event.");
        }
    }

    // ---------------------------------------------------------------- who gets them

    /** Keep the list of inhuman players up to date; returns true if it changed. */
    boolean update(Player p, boolean inhuman) {
        boolean changed = inhuman ? marked.add(p.getUniqueId()) : marked.remove(p.getUniqueId());
        if (changed) {
            plugin.store().set("inhuman-hearts", marked.stream().map(UUID::toString).sorted().toList());
            plugin.store().save();
        }
        return changed;
    }

    /** After someone turns inhuman or is cured: tell them, and reconnect them if that's allowed. */
    void changed(Player p, boolean inhuman) {
        if (!on() || !update(p, inhuman)) return;
        boolean reconnect = plugin.getConfig().getBoolean("inhuman.hearts.reconnect", false);
        String line = inhuman ? "Your heart has changed. You'll see it when you next join." : "Your heart is your own again. You'll see it when you next join.";
        if (reconnect) line = inhuman ? "Your heart is changing..." : "Your heart is healing...";
        p.sendMessage(Component.text(line, inhuman ? NamedTextColor.DARK_PURPLE : NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, true));
        if (!reconnect) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            var host = p.getVirtualHost();
            if (host == null) return;
            try { p.transfer(host.getHostString(), host.getPort()); }
            catch (Throwable t) { plugin.getLogger().warning("Couldn't reconnect " + p.getName() + " for their new hearts: " + t.getMessage()); }
        }, 60L);
    }

    // ---------------------------------------------------------------- the join packet

    private void configure(Object event) {
        if (!on()) return;
        try {
            Object conn = call(event, "getConnection");
            Object profile = call(conn, "getProfile");
            Object id = call(profile, "getId");
            if (!(id instanceof UUID uuid) || !marked.contains(uuid)) return;
            Object channel = find(conn, "io.netty.channel.Channel");
            if (channel == null) { warn("no channel found on the connection"); return; }
            ClassLoader netty = channel.getClass().getClassLoader();
            Class<?> chI = Class.forName("io.netty.channel.Channel", false, netty);
            Class<?> pipeI = Class.forName("io.netty.channel.ChannelPipeline", false, netty);
            Class<?> handlerI = Class.forName("io.netty.channel.ChannelHandler", false, netty);
            Class<?> outI = Class.forName("io.netty.channel.ChannelOutboundHandler", false, netty);
            Class<?> ctxI = Class.forName("io.netty.channel.ChannelHandlerContext", false, netty);
            Object pipeline = chI.getMethod("pipeline").invoke(channel);
            if (pipeI.getMethod("get", String.class).invoke(pipeline, HANDLER) != null) return;
            Object handler = Proxy.newProxyInstance(netty, new Class<?>[]{outI}, handler(uuid, ctxI));
            List<?> names = (List<?>) pipeI.getMethod("names").invoke(pipeline);
            if (names.contains("packet_handler")) pipeI.getMethod("addBefore", String.class, String.class, handlerI).invoke(pipeline, "packet_handler", HANDLER, handler);
            else pipeI.getMethod("addLast", String.class, handlerI).invoke(pipeline, HANDLER, handler);
        } catch (Throwable t) {
            warn(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** A netty outbound handler that passes everything through, flipping the hardcore flag on the join packet. */
    private InvocationHandler handler(UUID uuid, Class<?> ctxI) {
        return (proxy, m, args) -> {
            String n = m.getName();
            switch (n) {
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "SoulboundInhumanHearts";
                case "handlerAdded": case "handlerRemoved": return null;
                default: break;
            }
            Object ctx = args[0];
            if (n.equals("write")) {
                Object msg = args[1];
                if (msg != null && msg.getClass().getSimpleName().equals("ClientboundLoginPacket") && marked.contains(uuid)) {
                    try { msg = hardcore(msg); } catch (Throwable t) { warn("couldn't change the join packet: " + t.getMessage()); }
                    args = args.clone();
                    args[1] = msg;
                }
            }
            String target = n.equals("exceptionCaught") ? "fireExceptionCaught" : n;
            Class<?>[] types = Arrays.copyOfRange(m.getParameterTypes(), 1, m.getParameterCount());
            Method fwd = ctxMethods.computeIfAbsent(m, k -> {
                try { return ctxI.getMethod(target, types); } catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
            });
            return fwd.invoke(ctx, Arrays.copyOfRange(args, 1, args.length));
        };
    }

    /** The join packet is a record: rebuild it with hardcore = true. */
    private static Object hardcore(Object rec) throws Exception {
        Class<?> c = rec.getClass();
        if (!c.isRecord()) return rec;
        RecordComponent[] rc = c.getRecordComponents();
        Object[] vals = new Object[rc.length];
        Class<?>[] types = new Class<?>[rc.length];
        int flag = -1, firstBool = -1;
        for (int i = 0; i < rc.length; i++) {
            Method acc = rc[i].getAccessor();
            acc.setAccessible(true);
            vals[i] = acc.invoke(rec);
            types[i] = rc[i].getType();
            if (types[i] == boolean.class) {
                if (firstBool < 0) firstBool = i;
                if (rc[i].getName().equals("hardcore")) flag = i;
            }
        }
        if (flag < 0) flag = firstBool;   // the flag is the packet's first boolean
        if (flag < 0) return rec;
        vals[flag] = true;
        Constructor<?> k = c.getDeclaredConstructor(types);
        k.setAccessible(true);
        return k.newInstance(vals);
    }

    // ---------------------------------------------------------------- reflection helpers

    private static Object call(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == 0) { m.setAccessible(true); return m.invoke(o); }
        }
        for (Class<?> i : o.getClass().getInterfaces()) {
            try { Method m = i.getMethod(name); return m.invoke(o); } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name + " on " + o.getClass().getName());
    }

    private static boolean isA(Class<?> c, String iface) {
        Deque<Class<?>> q = new ArrayDeque<>();
        q.add(c);
        while (!q.isEmpty()) {
            Class<?> k = q.poll();
            if (k == null) continue;
            if (k.getName().equals(iface)) return true;
            q.add(k.getSuperclass());
            q.addAll(Arrays.asList(k.getInterfaces()));
        }
        return false;
    }

    /** Walk the connection's fields (a few levels deep) for the netty channel. */
    private static Object find(Object root, String iface) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{root, 0});
        while (!q.isEmpty()) {
            Object[] e = q.poll();
            Object o = e[0];
            int depth = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (isA(o.getClass(), iface)) return o;
            if (depth >= 4) continue;
            String cn = o.getClass().getName();
            if (cn.startsWith("java.") || o.getClass().isArray() || o.getClass().isPrimitive()) continue;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try { f.setAccessible(true); q.add(new Object[]{f.get(o), depth + 1}); } catch (Throwable ignored) { }
                }
            }
        }
        return null;
    }

    private void warn(String why) {
        if (warned) return;
        warned = true;
        plugin.getLogger().warning("Inhuman hearts couldn't be shown (" + why + "). Their hearts stay vanilla.");
    }
}
