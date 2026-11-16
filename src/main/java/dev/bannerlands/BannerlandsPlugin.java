package dev.bannerlands;

import dev.bannerlands.util.Lang;
import dev.bannerlands.command.NationCommand;
import dev.bannerlands.command.TownCommand;
import dev.bannerlands.data.Access;
import dev.bannerlands.data.DataManager;
import dev.bannerlands.economy.EconomyManager;
import dev.bannerlands.gui.TownGui;
import dev.bannerlands.invite.InviteManager;
import dev.bannerlands.listener.ProtectionListener;
import dev.bannerlands.listener.TerritoryListener;
import dev.bannerlands.region.RegionService;
import dev.bannerlands.storage.Database;
import dev.bannerlands.war.FlagWarManager;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class BannerlandsPlugin extends JavaPlugin {
    private Database database;
    private DataManager data;
    private EconomyManager economy;
    private RegionService regions;
    private InviteManager invites;
    private FlagWarManager wars;
    private Access access;

    /**
     * Adds config keys that newer versions introduced but the server's existing config.yml doesn't have yet.
     * Text-based: existing lines/comments are never touched, a backup (config.yml.bak) is written first and the result
     * is validated as YAML before it replaces the file.
     * Versioning: the "ver:" key of the server's config is compared with the bundled one. Lower = patched and bumped,
     * equal = untouched, higher = UNSUPPORTED (the plugin must not load with a config from a newer version).
     */
    private enum ConfigResult { UNCHANGED, CHANGED, UNSUPPORTED }

    private ConfigResult patchConfig() {
        java.io.File file = new java.io.File(getDataFolder(), "config.yml");
        if (!file.exists()) return ConfigResult.UNCHANGED;
        try (java.io.InputStream in = getResource("config.yml")) {
            if (in == null) return ConfigResult.UNCHANGED;
            String defaults = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String current = java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            java.util.List<String> liveLines = dev.bannerlands.util.ConfigPatcher.split(current);
            java.util.List<String> defLines = dev.bannerlands.util.ConfigPatcher.split(defaults);
            int liveVer = dev.bannerlands.util.ConfigPatcher.readVersion(liveLines);
            int bundledVer = dev.bannerlands.util.ConfigPatcher.readVersion(defLines);
            if (liveVer > bundledVer) {
                getLogger().severe("config.yml has version " + liveVer + " but this build only supports up to version " + bundledVer
                        + ". The plugin was probably downgraded - use a matching plugin version or restore an older config.yml. Bannerlands will NOT load.");
                return ConfigResult.UNSUPPORTED;
            }
            if (liveVer == bundledVer) return ConfigResult.UNCHANGED;
            java.util.List<String> added = new java.util.ArrayList<>();
            java.util.List<String> patched = dev.bannerlands.util.ConfigPatcher.patch(liveLines, defLines, added);
            boolean bumped = dev.bannerlands.util.ConfigPatcher.setVersion(patched, bundledVer);
            if (added.isEmpty() && !bumped) return ConfigResult.UNCHANGED;
            String result = String.join(current.contains("\r\n") ? "\r\n" : "\n", patched);
            new org.bukkit.configuration.file.YamlConfiguration().loadFromString(result);   // throws if we produced invalid YAML
            java.nio.file.Files.copy(file.toPath(), new java.io.File(getDataFolder(), "config.yml.bak").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Files.writeString(file.toPath(), result, java.nio.charset.StandardCharsets.UTF_8);
            getLogger().info("config.yml updated from version " + liveVer + " to " + bundledVer
                    + (added.isEmpty() ? "" : ", added missing keys: " + String.join(", ", added)) + " (backup: config.yml.bak)");
            return ConfigResult.CHANGED;
        } catch (Exception ex) {
            getLogger().warning("Could not add missing keys to config.yml (defaults are used instead): " + ex.getMessage());
            return ConfigResult.UNCHANGED;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigResult cfgResult = patchConfig();
        if (cfgResult == ConfigResult.UNSUPPORTED) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (cfgResult == ConfigResult.CHANGED) reloadConfig();
        Lang.load(this);
        economy = new EconomyManager(this);
        database = new Database(this);
        try {
            database.init();
            data = new DataManager(this, database);
            regions = new RegionService(this);
            wars = new FlagWarManager(this);
            access = new Access(data);
            invites = new InviteManager(this);
            data.loadAll();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Failed to start Bannerlands", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        TownGui gui = new TownGui(this);
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new ProtectionListener(this), this);
        pm.registerEvents(new TerritoryListener(this), this);
        pm.registerEvents(invites, this);
        pm.registerEvents(gui, this);

        TownCommand town = new TownCommand(this, gui);
        getCommand("town").setExecutor(town);
        getCommand("town").setTabCompleter(town);
        NationCommand nation = new NationCommand(this);
        getCommand("nation").setExecutor(nation);
        getCommand("nation").setTabCompleter(nation);

        // Worlds are loaded by now: make sure every claim has its WorldGuard region
        getServer().getScheduler().runTask(this, regions::verifyAll);
        getServer().getScheduler().runTaskTimer(this, () -> {
            data.expireRaids();
            invites.purgeExpired();
        }, 600L, 600L);
        getLogger().info("Bannerlands enabled: " + data.towns.size() + " towns, " + data.nations.size()
                + " nations, " + data.claims.size() + " claimed chunks.");
    }

    @Override
    public void onDisable() {
        if (wars != null) wars.shutdown();   // restores flag banners
        if (database != null) database.close(); // drains the write queue
    }

    public DataManager data() { return data; }
    public EconomyManager economy() { return economy; }
    public RegionService regions() { return regions; }
    public InviteManager invites() { return invites; }
    public FlagWarManager wars() { return wars; }
    public Access access() { return access; }
}
