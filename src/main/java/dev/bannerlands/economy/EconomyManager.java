package dev.bannerlands.economy;

import dev.bannerlands.BannerlandsPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Thin Vault wrapper. The provider is resolved lazily because economy plugins may enable after us. */
public final class EconomyManager {
    private final BannerlandsPlugin plugin;
    private Economy eco;

    public EconomyManager(BannerlandsPlugin plugin) { this.plugin = plugin; }

    private Economy eco() {
        if (eco == null) {
            RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) eco = rsp.getProvider();
        }
        return eco;
    }

    public boolean available() { return eco() != null; }

    public boolean has(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        Economy e = eco();
        return e != null && e.has(p, amount);
    }

    public boolean withdraw(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        Economy e = eco();
        return e != null && e.has(p, amount) && e.withdrawPlayer(p, amount).transactionSuccess();
    }

    public void deposit(OfflinePlayer p, double amount) {
        Economy e = eco();
        if (e != null && amount > 0) e.depositPlayer(p, amount);
    }

    public String format(double amount) {
        Economy e = eco();
        return e != null ? e.format(amount) : String.format("%.2f", amount);
    }
}
