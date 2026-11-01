package dev.bannerlands.command;

import dev.bannerlands.BannerlandsPlugin;
import dev.bannerlands.util.Lang;
import dev.bannerlands.data.DataManager;
import dev.bannerlands.gui.TownGui;
import dev.bannerlands.model.*;
import dev.bannerlands.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class TownCommand implements TabExecutor {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,20}$");
    private static final List<String> SUBS = List.of("map","create", "delete", "claim", "unclaim", "info", "add", "join", "leave",
            "kick", "rank", "trust", "untrust", "perms", "desc", "promo", "friendlyfire", "unlock", "invite", "accept",
            "deny", "invites", "help");

    private final BannerlandsPlugin plugin;
    private final DataManager data;
    private final TownGui gui;
    /** target player -> (town id -> expiry millis) for /town add -> /town join */
    private final Map<UUID, Map<Long, Long>> joinOffers = new ConcurrentHashMap<>();

    public TownCommand(BannerlandsPlugin plugin, TownGui gui) {
        this.plugin = plugin; this.data = plugin.data(); this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        if (a.length == 0) { help(p); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(p, a);
            case "delete" -> delete(p, a);
            case "claim" -> claim(p);
            case "unclaim" -> unclaim(p);
            case "info" -> info(p, a);
            case "add" -> add(p, a);
            case "map" -> map(p);
            case "join" -> join(p, a);
            case "leave" -> leave(p);
            case "kick" -> kick(p, a);
            case "rank" -> rank(p, a);
            case "trust" -> trust(p, a, true);
            case "untrust" -> trust(p, a, false);
            case "perms" -> perms(p, a);
            case "desc" -> text(p, a, true);
            case "promo" -> text(p, a, false);
            case "friendlyfire", "ff" -> friendlyFire(p);
            case "unlock" -> unlock(p, a);
            case "invite" -> invite(p, a);
            case "accept" -> respond(p, a, true);
            case "deny" -> respond(p, a, false);
            case "invites" -> listInvites(p);
            case "reload" -> reload(p);
            default -> help(p);
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private void usage(Player p, String u) { Msg.send(p, "usage", Msg.p("u", u)); }

    private Town needTown(Player p) {
        Town t = data.activeTown(p);
        if (t == null) Msg.send(p, "town.you_aren_t_in");
        return t;
    }

    private Town needRole(Player p, Role min) {
        Town t = needTown(p);
        if (t == null) return null;
        Role r = t.role(p.getUniqueId());
        if (r == null || !r.atLeast(min)) {
            Msg.send(p, "town.you_need_to_be", Msg.p("r", Lang.get("role." + min.name().toLowerCase())), Msg.p("t", t.getName()));
            return null;
        }
        return t;
    }

    private String claimCheck(World w, int cx, int cz, Town t) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getStringList("worlds").contains(w.getName())) return "Claims are not allowed in this world.";
        if (data.claims.containsKey(new ChunkKey(w.getUID(), cx, cz))) return "This chunk is already claimed.";
        if (t != null) {
            int count = data.claimCount(t.getId());
            if (count >= data.claimLimit(t)) return "Your town reached its claim limit.";
            if (count > 0 && cfg.getBoolean("towns.require-adjacent", true) && !touches(w, cx, cz, t)) {
                return "New chunks must touch an existing chunk of your town.";
            }
        }
        if (!cfg.getBoolean("towns.allow-claim-over-regions", false) && plugin.regions().overlapsForeignRegion(w, cx, cz)) {
            return "This chunk overlaps a protected WorldGuard region.";
        }
        return null;
    }

    private boolean touches(World w, int cx, int cz, Town t) {
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : d) {
            ClaimedChunk c = data.claims.get(new ChunkKey(w.getUID(), cx + o[0], cz + o[1]));
            if (c != null && c.getTownId() == t.getId()) return true;
        }
        return false;
    }

    private void help(Player p) {
        Msg.send(p, "town.town_commands");
        for (String l : new String[]{
                "map" ,"create <name>", "claim / unclaim", "info [town]", "add <player> / join <town> / leave / kick <player>",
                "rank <player> <mayor|member>", "trust|untrust <player>", "perms  (opens the GUI)",
                "desc <text> / promo <text>", "friendlyfire", "unlock", "invite <town>  (nation invite)",
                "invites / accept <id> / deny <id>", "delete confirm"}) {
            p.sendMessage(Msg.raw("town.town", Msg.p("l", l)));
        }
    }

    // ------------------------------------------------------------------ commands

    private void map(Player p) {
        UUID world = p.getWorld().getUID();
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        int rx = 9, rz = 4; // 19 x 9 chunks
        MiniMessage mm = MiniMessage.miniMessage();
        Set<String> towns = new LinkedHashSet<>();

        p.sendMessage(Msg.raw("map.header", Msg.p("x", cx), Msg.p("z", cz)));
        for (int dz = -rz; dz <= rz; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -rx; dx <= rx; dx++) {
                ChunkKey key = new ChunkKey(world, cx + dx, cz + dz);
                ClaimedChunk c = data.claims.get(key);
                Town t = c == null ? null : data.town(c.getTownId());
                if (t != null) towns.add(t.getName());

                String color;
                if (dx == 0 && dz == 0) color = "gold";                  // you
                else if (t == null) color = "dark_gray";                  // wilderness
                else if (c.isRaided() || plugin.wars().at(key) != null) color = "light_purple"; // siege / raid
                else color = switch (plugin.access().tier(p.getUniqueId(), t)) {
                    case MEMBER -> "green";
                    case TRUSTED -> "aqua";
                    case ALLY -> "yellow";
                    case ENEMY -> "red";
                    default -> "white";
                };

                row.append('<').append(color).append('>');
                if (t != null) row.append("<hover:show_text:'").append(t.getName()).append("'>");
                row.append('\u25a0');
                if (t != null) row.append("</hover>");
                row.append("</").append(color).append('>');
            }
            p.sendMessage(mm.deserialize(row.toString()));
        }
        p.sendMessage(Msg.raw("map.legend"));
        if (!towns.isEmpty()) p.sendMessage(Msg.raw("map.towns", Msg.p("t", String.join(", ", towns))));
    }

    private void create(Player p, String[] a) {
        if (a.length < 2) { usage(p, "/town create <name>"); return; }
        String name = a[1];
        FileConfiguration cfg = plugin.getConfig();
        if (!NAME.matcher(name).matches()) { Msg.send(p, "town.names_must_be_3"); return; }
        if (data.townByName(name) != null) { Msg.send(p, "town.that_name_is_taken"); return; }
        PlayerProfile pr = data.profile(p.getUniqueId(), p.getName());
        int owned = data.ownedCount(p.getUniqueId());
        if (owned >= pr.getTownSlots()) {
            Msg.send(p, "town.you_own_available_town",
                    Msg.p("o", owned), Msg.p("s", pr.getTownSlots()));
            return;
        }
        boolean claimNow = cfg.getBoolean("towns.claim-on-create", true);
        World w = p.getWorld();
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        if (claimNow) {
            String err = claimCheck(w, cx, cz, null);
            if (err != null) { Msg.send(p, "error", Msg.p("e", err)); return; }
        }
        double fee = cfg.getDouble("towns.create-cost", 0);
        if (!plugin.economy().withdraw(p, fee)) {
            Msg.send(p, "town.you_need_to_found", Msg.p("m", plugin.economy().format(fee)));
            return;
        }
        Town t = data.createTown(name, p.getUniqueId());
        if (claimNow) data.claim(t, w.getUID(), cx, cz);
        Msg.send(p, "town.town_founded_use_town", Msg.p("t", name));
    }

    private void delete(Player p, String[] a) {
        Town t = needRole(p, Role.MAYOR);
        if (t == null) return;
        if (a.length < 2 || !a[1].equalsIgnoreCase("confirm")) {
            Msg.send(p, "town.this_permanently_deletes_and", Msg.p("t", t.getName()));
            return;
        }
        String name = t.getName();
        data.deleteTown(t);
        Msg.send(p, "town.town_was_deleted", Msg.p("t", name));
    }

    private void claim(Player p) {
        World w = p.getWorld();
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;

        // Find the correct town for this claim (handles players with multiple towns)
        Town t = findBestTownForClaim(p, w.getUID(), cx, cz);
        if (t == null) {
            Msg.send(p, "town.you_aren_t_in");
            return;
        }

        // Verify the player has at least ASSISTANT role in the target town
        Role r = t.role(p.getUniqueId());
        if (r == null || !r.atLeast(Role.ASSISTANT)) {
            Msg.send(p, "town.you_need_to_be", Msg.p("r", Lang.get("role.assistant")), Msg.p("t", t.getName()));
            return;
        }

        String err = claimCheck(w, cx, cz, t);
        if (err != null) { 
            Msg.send(p, "error", Msg.p("e", err)); 
            return; 
        }

        double cost = plugin.getConfig().getDouble("towns.claim-cost", 0);
        if (!plugin.economy().withdraw(p, cost)) {
            Msg.send(p, "town.you_need_to_claim", Msg.p("m", plugin.economy().format(cost)));
            return;
        }

        data.claim(t, w.getUID(), cx, cz);
        Msg.send(p, "town.chunk_claimed_for", Msg.p("x", cx), Msg.p("z", cz), Msg.p("t", t.getName()));
    }

    private Town findBestTownForClaim(Player p, UUID worldId, int cx, int cz) {
        UUID playerId = p.getUniqueId();
        List<Town> playerTowns = new ArrayList<>();

        // Collect all towns where the player has at least ASSISTANT role
        for (Town town : data.towns.values()) {
            Role role = town.role(playerId);
            if (role != null && role.atLeast(Role.ASSISTANT)) {
                playerTowns.add(town);
            }
        }

        if (playerTowns.isEmpty()) return null;
        if (playerTowns.size() == 1) return playerTowns.get(0);

        // Check immediate neighboring chunks to see if one of their towns already touches this location
        int[][] offsets = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int[] offset : offsets) {
            ChunkKey neighborKey = new ChunkKey(worldId, cx + offset[0], cz + offset[1]);
            ClaimedChunk neighborClaim = data.claims.get(neighborKey);
            if (neighborClaim != null) {
                Town neighborTown = data.town(neighborClaim.getTownId());
                if (playerTowns.contains(neighborTown)) {
                    return neighborTown;
                }
            }
        }

        // Fallback: If no adjacent claim is found (e.g. brand new town expansion), 
        // return the first town in their list that currently has zero claims.
        for (Town town : playerTowns) {
            if (data.claimCount(town.getId()) == 0) {
                return town;
            }
        }

        return playerTowns.get(0);
    }
    private void unclaim(Player p) {
        Town t = needRole(p, Role.ASSISTANT);
        if (t == null) return;
        ChunkKey key = new ChunkKey(p.getWorld().getUID(), p.getLocation().getBlockX() >> 4, p.getLocation().getBlockZ() >> 4);
        ClaimedChunk c = data.claims.get(key);
        if (c == null || c.getTownId() != t.getId()) { Msg.send(p, "town.this_chunk_isn_t", Msg.p("t", t.getName())); return; }
        if (plugin.wars().at(key) != null) { Msg.send(p, "town.you_can_t_unclaim"); return; }
        data.unclaim(c);
        plugin.economy().deposit(p, plugin.getConfig().getDouble("towns.unclaim-refund", 0));
        Msg.send(p, "town.chunk_unclaimed");
    }

    private void info(Player p, String[] a) {
        Town t;
        if (a.length >= 2) t = data.townByName(a[1]);
        else {
            ClaimedChunk c = data.claims.get(new ChunkKey(p.getWorld().getUID(), p.getLocation().getBlockX() >> 4, p.getLocation().getBlockZ() >> 4));
            t = c != null ? data.town(c.getTownId()) : data.activeTown(p);
        }
        if (t == null) { Msg.send(p, "town.town_not_found"); return; }
        Nation n = data.nation(t.getNationId());
        Msg.send(p, "town.king", Msg.p("t", t.getName()), Msg.p("o", data.nameOf(t.getOwner())));
        if (!t.getDescription().isEmpty()) p.sendMessage(Msg.raw("town.msg_3", Msg.p("d", t.getDescription())));
        if (!t.getPromo().isEmpty()) p.sendMessage(Msg.raw("town.msg_4", Msg.p("d", t.getPromo())));
        p.sendMessage(Msg.raw("town.nation_members_chunks_friendly",
                Msg.p("n", n == null ? Lang.get("word.none") : n.getName()), Msg.p("m", t.getMembers().size()),
                Msg.p("c", data.claimCount(t.getId()) + "/" + data.claimLimit(t)), Msg.p("f", Lang.onOff(t.isFriendlyFire()))));
    }

    private void add(Player p, String[] a) {
        if (a.length < 2) { usage(p, "/town add <player>"); return; }
        Town t = needRole(p, Role.ASSISTANT);
        if (t == null) return;
        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) { Msg.send(p, "town.that_player_isn_t"); return; }
        if (t.getMembers().containsKey(target.getUniqueId())) { Msg.send(p, "town.they_re_already_a"); return; }
        joinOffers.computeIfAbsent(target.getUniqueId(), k -> new ConcurrentHashMap<>()).put(t.getId(), System.currentTimeMillis() + 300_000L);
        Component accept = Msg.raw("button.join").clickEvent(ClickEvent.runCommand("/town join " + t.getName()));
        target.sendMessage(Msg.c("town.invites_you_to_join", Msg.p("p", p.getName()), Msg.p("t", t.getName())).append(accept));
        Msg.send(p, "town.invitation_sent_to_valid", Msg.p("n", target.getName()));
    }

    private void join(Player p, String[] a) {
        if (a.length < 2) { usage(p, "/town join <town>"); return; }
        Town t = data.townByName(a[1]);
        Map<Long, Long> offers = joinOffers.get(p.getUniqueId());
        Long expiry = (t == null || offers == null) ? null : offers.remove(t.getId());
        if (expiry == null || expiry < System.currentTimeMillis()) { Msg.send(p, "town.you_have_no_valid"); return; }
        data.addMember(t, p.getUniqueId(), Role.RESIDENT);
        Msg.send(p, "town.you_joined", Msg.p("t", t.getName()));
    }

    private void leave(Player p) {
        Town t = needTown(p);
        if (t == null) return;
        if (t.getOwner().equals(p.getUniqueId())) { Msg.send(p, "town.the_king_can_t"); return; }
        data.removeMember(t, p.getUniqueId());
        Msg.send(p, "town.you_left", Msg.p("t", t.getName()));
    }

    private void kick(Player p, String[] a) {
        if (a.length < 2) { usage(p, "/town kick <player>"); return; }
        Town t = needRole(p, Role.ASSISTANT);
        if (t == null) return;
        UUID u = data.findPlayer(a[1]);
        Role tr = u == null ? null : t.role(u);
        if (tr == null) { Msg.send(p, "town.that_player_isn_t_2"); return; }
        Role mine = t.role(p.getUniqueId());
        if (tr == Role.MAYOR || (tr == Role.ASSISTANT && mine != Role.MAYOR)) { Msg.send(p, "town.you_can_t_kick"); return; }
        data.removeMember(t, u);
        Msg.send(p, "town.was_removed", Msg.p("n", data.nameOf(u)));
    }

    private void rank(Player p, String[] a) {
        if (a.length < 3) { usage(p, "/town rank <player> <assistant|resident>"); return; }
        Town t = needRole(p, Role.MAYOR);
        if (t == null) return;
        UUID u = data.findPlayer(a[1]);
        Role cur = u == null ? null : t.role(u);
        if (cur == null || cur == Role.MAYOR) { Msg.send(p, "town.that_player_can_t"); return; }
        Role r = a[2].equalsIgnoreCase("assistant") ? Role.ASSISTANT : a[2].equalsIgnoreCase("resident") ? Role.RESIDENT : null;
        if (r == null) { usage(p, "/town rank <player> <assistant|resident>"); return; }
        data.setRole(t, u, r);
        Msg.send(p, "town.is_now", Msg.p("n", data.nameOf(u)), Msg.p("r", Lang.get("role." + r.name().toLowerCase())));
    }

    private void trust(Player p, String[] a, boolean on) {
        if (a.length < 2) { usage(p, "/town " + (on ? "trust" : "untrust") + " <player>"); return; }
        Town t = needRole(p, Role.ASSISTANT);
        if (t == null) return;
        UUID u = data.findPlayer(a[1]);
        if (u == null) { Msg.send(p, "town.unknown_player_they_must"); return; }
        data.trust(t, u, on);
        Msg.send(p, on ? "town.trust_added" : "town.trust_removed",
                Msg.p("n", data.nameOf(u)), Msg.p("t", t.getName()));
    }

    private void perms(Player p, String[] a) {
        Town t = needRole(p, Role.ASSISTANT);
        if (t == null) return;
        if (a.length == 1) { gui.openPerms(p, t); return; }
        if (a.length != 4) { usage(p, "/town perms [<tier> <break|place|chest|door> <on|off>]"); return; }
        Tier tier;
        try { tier = Tier.valueOf(a[1].toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { Msg.send(p, "town.tier_must_be_one"); return; }
        Perm perm = Perm.parse(a[2]);
        if (perm == null) { Msg.send(p, "town.permission_must_be_break"); return; }
        boolean on = a[3].equalsIgnoreCase("on") || a[3].equalsIgnoreCase("true") || a[3].equalsIgnoreCase("allow");
        t.set(tier, perm, on);
        data.saveTown(t);
        Msg.send(p, "town.msg_2", Msg.p("tier", Lang.get("tier." + tier.name().toLowerCase())), Msg.p("perm", Lang.get("perm." + perm.name().toLowerCase())), Msg.p("v", Lang.get(on ? "word.allowed" : "word.denied")));
    }

    private void text(Player p, String[] a, boolean desc) {
        if (a.length < 2) { usage(p, "/town " + (desc ? "desc" : "promo") + " <text>"); return; }
        Town t = needRole(p, Role.ASSISTANT);
        if (t == null) return;
        String value = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
        if (value.length() > 128) value = value.substring(0, 128);
        if (desc) t.setDescription(value); else t.setPromo(value);
        data.saveTown(t);
        Msg.send(p, "town.updated");
    }

    private void friendlyFire(Player p) {
        Town t = needRole(p, Role.MAYOR);
        if (t == null) return;
        t.setFriendlyFire(!t.isFriendlyFire());
        data.saveTown(t);
        Msg.send(p, "town.friendly_fire_inside_is", Msg.p("t", t.getName()), Msg.p("v", Lang.onOff(t.isFriendlyFire())));
    }
    
    private void unlock(Player p, String[] a) {
        FileConfiguration cfg = plugin.getConfig();
        PlayerProfile pr = data.profile(p.getUniqueId(), p.getName());
        int next = pr.getTownSlots() + 1;
        if (next > cfg.getInt("towns.max-owned", 3)) { Msg.send(p, "town.you_already_unlocked_every"); return; }
        ConfigurationSection s = cfg.getConfigurationSection("progression.slots." + next);
        if (s == null) { Msg.send(p, "town.slot_isn_t_configured", Msg.p("n", next)); return; }
        
        double money = s.getDouble("money", 0);
        Map<Material, Integer> req = new LinkedHashMap<>();
        for (String entry : s.getStringList("items")) {
            String[] parts = entry.split(":");
            Material m = Material.matchMaterial(parts[0]);
            if (m == null) continue;
            req.merge(m, parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1, Integer::sum);
        }
        
        boolean confirm = a.length > 1 && a[1].equalsIgnoreCase("confirm");
        boolean ok = plugin.economy().has(p, money);
        
        Msg.send(p, "town.town_slot_requires", Msg.p("n", next));
        p.sendMessage(Msg.raw(ok ? "town.unlock_req_ok" : "town.unlock_req_missing", Msg.p("m", plugin.economy().format(money))));
        
        for (Map.Entry<Material, Integer> e : req.entrySet()) {
            boolean has = hasEnoughItems(p, e.getKey(), e.getValue());
            ok &= has;
            p.sendMessage(Msg.raw(has ? "town.unlock_item_ok" : "town.unlock_item_missing", Msg.p("a", e.getValue()), Msg.p("i", e.getKey().name())));
        }

        if (!confirm) { Msg.send(p, "town.hold_the_items_in"); return; }
        if (!ok) { Msg.send(p, "town.you_don_t_meet"); return; }
        if (!plugin.economy().withdraw(p, money)) { Msg.send(p, "town.payment_failed"); return; }

        // Remove the exact required items safely
        for (Map.Entry<Material, Integer> e : req.entrySet()) {
            removeItems(p, e.getKey(), e.getValue());
        }

        pr.setTownSlots(next);
        data.saveProfile(pr);
        Msg.send(p, "town.town_slot_unlocked_you", Msg.p("n", next));
    }

    private boolean hasEnoughItems(Player p, Material mat, int amount) {
        int count = 0;
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && item.getType() == mat) {
                count += item.getAmount();
                if (count >= amount) return true;
            }
        }
        return count >= amount;
    }

    private void removeItems(Player p, Material mat, int amount) {
        ItemStack[] contents = p.getInventory().getContents();
        int toRemove = amount;

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != mat) continue;

            int currentAmount = item.getAmount();
            if (currentAmount <= toRemove) {
                toRemove -= currentAmount;
                p.getInventory().setItem(i, null);
            } else {
                item.setAmount(currentAmount - toRemove);
                toRemove = 0;
            }

            if (toRemove <= 0) break;
        }
    }

    private void invite(Player p, String[] a) {
        if (a.length < 2) { usage(p, "/town invite <townName>"); return; }

        // Find a town owned/managed by the player that is a Nation Capital
        Town capitalTown = data.towns.values().stream()
                .filter(t -> t.getOwner().equals(p.getUniqueId()))
                .filter(t -> {
                    Nation n = data.nation(t.getNationId());
                    return n != null && n.getCapitalTownId() == t.getId();
                })
                .findFirst()
                .orElse(null);

        if (capitalTown == null) {
            Msg.send(p, "town.only_the_king_of");
            return;
        }

        Nation n = data.nation(capitalTown.getNationId());
        Town target = data.townByName(a[1]);
        if (target == null) { Msg.send(p, "town.no_such_town"); return; }

        String err = plugin.invites().invite(p, n, target);
        if (err != null) { Msg.send(p, "error", Msg.p("e", err)); return; }

        boolean online = Bukkit.getPlayer(target.getOwner()) != null;
        Msg.send(p, online ? "town.invite_sent" : "town.invite_queued", Msg.p("t", target.getName()));
    }

    private void respond(Player p, String[] a, boolean accept) {
        if (a.length < 2) { usage(p, "/town " + (accept ? "accept" : "deny") + " <id>"); return; }
        long id;
        try { id = Long.parseLong(a[1]); } catch (NumberFormatException ex) { Msg.send(p, "town.invalid_id"); return; }
        if (accept) plugin.invites().accept(p, id); else plugin.invites().deny(p, id);
    }

    private void reload(Player p) {
        if (!p.hasPermission("bannerlands.admin")) { Msg.send(p, "error", Msg.p("e", "No permission.")); return; }
        plugin.reloadConfig();
        Lang.load(plugin);
        Msg.send(p, "common.reloaded");
    }

    private void listInvites(Player p) {
        int shown = 0;
        for (TownInvite i : data.invites.values()) {
            Town t = data.town(i.getTownId());
            if (t == null || !t.getOwner().equals(p.getUniqueId()) || plugin.invites().expired(i)) continue;
            plugin.invites().send(i, p);
            shown++;
        }
        if (shown == 0) Msg.send(p, "town.no_pending_nation_invitations");
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) return filter(SUBS, a[0]);
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            switch (sub) {
                case "add", "kick", "trust", "untrust", "rank" -> { return filter(onlineNames(), a[1]); }
                case "info", "join", "invite" -> { return filter(data.towns.values().stream().map(Town::getName).toList(), a[1]); }
                case "perms" -> { return filter(Arrays.stream(Tier.values()).map(t -> t.name().toLowerCase()).toList(), a[1]); }
                default -> { return List.of(); }
            }
        }
        if (a.length == 3 && sub.equals("rank")) return filter(List.of("mayor", "member"), a[2]);
        if (a.length == 3 && sub.equals("perms")) return filter(List.of("break", "place", "chest", "door"), a[2]);
        if (a.length == 4 && sub.equals("perms")) return filter(List.of("on", "off"), a[3]);
        return List.of();
    }

    private static List<String> onlineNames() { return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(); }

    static List<String> filter(List<String> src, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return src.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(p)).sorted().toList();
    }
}
