package dev.bannerlands.util;

import dev.bannerlands.BannerlandsPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;


public final class Lang {
    private static volatile YamlConfiguration current = new YamlConfiguration();
    private static volatile YamlConfiguration fallback = new YamlConfiguration();

    private Lang() { }

    public static void load(BannerlandsPlugin plugin) {
        File dir = new File(plugin.getDataFolder(), "lang");
        dir.mkdirs();
        File en = new File(dir, "en.yml");
        for (String lang : new String[]{"en", "hu"}) {
            if (!new File(dir, lang + ".yml").exists()) plugin.saveResource("lang/" + lang + ".yml", false);
        }

        var in = plugin.getResource("lang/en.yml");
        if (in != null) fallback = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));

        String code = plugin.getConfig().getString("language", "en");
        File file = new File(dir, code + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("Language file lang/" + code + ".yml not found, using en.");
            file = en;
        }
        current = YamlConfiguration.loadConfiguration(file);
    }

    /** Raw MiniMessage string for a key (current language, then English, then a visible marker). */
    public static String get(String key) {
        String s = current.getString(key);
        if (s == null) s = fallback.getString(key);
        return s != null ? s : "<red>[missing lang key: " + key + "]";
    }

    public static String onOff(boolean b) { return get(b ? "word.enabled" : "word.disabled"); }
}
