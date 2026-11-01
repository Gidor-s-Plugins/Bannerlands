package dev.bannerlands.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** All player-facing text goes through here: arguments are lang keys, never literal text. */
public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Msg() { }

    /** Message with the configured prefix. */
    public static Component c(String key, TagResolver... r) { return MM.deserialize(Lang.get("prefix") + Lang.get(key), r); }
    /** Message without prefix (GUI items, boss bars, extra lines). */
    public static Component raw(String key, TagResolver... r) { return MM.deserialize(Lang.get(key), r); }
    public static void send(CommandSender to, String key, TagResolver... r) { to.sendMessage(c(key, r)); }

    /** User-supplied values always go through unparsed placeholders so players can't inject MiniMessage tags. */
    public static TagResolver p(String key, Object value) { return Placeholder.unparsed(key, String.valueOf(value)); }
}
