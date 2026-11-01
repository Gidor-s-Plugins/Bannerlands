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

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
