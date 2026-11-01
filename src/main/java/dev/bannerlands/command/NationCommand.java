package dev.bannerlands.command;

import dev.bannerlands.BannerlandsPlugin;
import dev.bannerlands.util.Lang;
import dev.bannerlands.data.DataManager;
import dev.bannerlands.model.*;
import dev.bannerlands.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.regex.Pattern;

public final class NationCommand implements TabExecutor {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,20}$");
    private static final List<String> SUBS = List.of("create", "info", "ally", "enemy", "war", "peace", "friendlyfire",
            "leave", "kick", "disband", "flag", "help");

    private final BannerlandsPlugin plugin;
    private final DataManager data;

    public NationCommand(BannerlandsPlugin plugin) { this.plugin = plugin; this.data = plugin.data(); }

    private record Ctx(Town town, Nation nation) { }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        if (a.length == 0) { help(p); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(p, a);
            case "info" -> info(p, a);
            case "ally" -> diplomacy(p, a, Relation.ALLY);
            case "enemy" -> diplomacy(p, a, Relation.ENEMY);
            case "war" -> diplomacy(p, a, Relation.WAR);
            case "peace" -> peace(p, a);
            case "friendlyfire", "ff" -> friendlyFire(p);
            case "leave" -> leave(p);
            case "kick" -> kick(p, a);
            case "disband" -> disband(p, a);
            case "flag", "siege" -> flag(p, a);
            default -> help(p);
        }
        return true;
    }

    private void usage(Player p, String u) { Msg.send(p, "usage", Msg.p("u", u)); }

    private void help(Player p) {
        Msg.send(p, "nation.nation_commands");
        for (String l : new String[]{"create <name>", "info [nation]", "ally <nation>", "enemy <nation>", "war <nation>",
                "peace <nation>", "flag  (start a siege on the chunk you stand in)", "flag cancel", "friendlyfire",
                "leave / kick <town> / disband confirm", "(invite towns with /town invite <town>)"}) {
            p.sendMessage(Msg.raw("nation.nation", Msg.p("l", l)));
        }
    }

    /** The player must be king of their nation's capital. */
    private Ctx leader(Player p) {
        Town t = data.activeTown(p);
        if (t == null) { Msg.send(p, "nation.you_aren_t_in"); return null; }
        Nation n = data.nation(t.getNationId());
        if (n == null) { Msg.send(p, "nation.isn_t_in_a", Msg.p("t", t.getName())); return null; }
        if (!t.getOwner().equals(p.getUniqueId()) || n.getCapitalTownId() != t.getId()) {
            Msg.send(p, "nation.only_the_king_of",
                    Msg.p("c", Optional.ofNullable(data.town(n.getCapitalTownId())).map(Town::getName).orElse("?")));
            return null;
        }
        return new Ctx(t, n);
    }

    private void create(Player p, String[] a) {
        if (a.length < 2) { usage(p, "/nation create <name>"); return; }
        Town t = data.activeTown(p);
        if (t == null || !t.getOwner().equals(p.getUniqueId())) { Msg.send(p, "nation.you_must_be_the"); return; }
        if (t.getNationId() != 0) { Msg.send(p, "nation.this_town_already_belongs"); return; }
        String name = a[1];
        if (!NAME.matcher(name).matches()) { Msg.send(p, "nation.names_must_be_3"); return; }
        if (data.nationByName(name) != null) { Msg.send(p, "nation.that_name_is_taken"); return; }
        double fee = plugin.getConfig().getDouble("nations.create-cost", 0);
        if (!plugin.economy().withdraw(p, fee)) {
            Msg.send(p, "nation.you_need_to_found", Msg.p("m", plugin.economy().format(fee)));
            return;
        }
        data.createNation(name, t);
        Msg.send(p, "nation.nation_founded_with_as",
                Msg.p("n", name), Msg.p("t", t.getName()));
    }

    private void info(Player p, String[] a) {
        Nation n;
        if (a.length >= 2) n = data.nationByName(a[1]);
        else { Town t = data.activeTown(p); n = t == null ? null : data.nation(t.getNationId()); }
        if (n == null) { Msg.send(p, "nation.nation_not_found"); return; }
        List<String> townNames = n.getTowns().stream().map(data::town).filter(Objects::nonNull).map(Town::getName).sorted().toList();
        List<String> allies = new ArrayList<>(), enemies = new ArrayList<>(), wars = new ArrayList<>();
        for (Nation o : data.nations.values()) {
            switch (data.relation(n.getId(), o.getId())) {
                case ALLY -> allies.add(o.getName());
                case ENEMY -> enemies.add(o.getName());
                case WAR -> wars.add(o.getName());
                default -> { }
            }
        }
        Msg.send(p, "nation.capital", Msg.p("n", n.getName()),
                Msg.p("c", Optional.ofNullable(data.town(n.getCapitalTownId())).map(Town::getName).orElse("?")));
        p.sendMessage(Msg.raw("nation.towns", Msg.p("t", String.join(", ", townNames))));
        p.sendMessage(Msg.raw("nation.allies_enemies_at_war",
                Msg.p("a", allies.isEmpty() ? "-" : String.join(", ", allies)),
                Msg.p("e", enemies.isEmpty() ? "-" : String.join(", ", enemies)),
                Msg.p("w", wars.isEmpty() ? "-" : String.join(", ", wars))));
        p.sendMessage(Msg.raw("nation.friendly_fire", Msg.p("f", Lang.onOff(n.isFriendlyFire()))));
    }

    /**
     * ally  -> directed request; becomes an alliance once the other nation does the same.
     * enemy -> unilateral, cancels any alliance.
     * war   -> only possible against a nation you already marked as enemy.
     */
    private void diplomacy(Player p, String[] a, Relation wanted) {
        if (a.length < 2) { usage(p, "/nation " + a[0] + " <nation>"); return; }
        Ctx c = leader(p);
        if (c == null) return;
        Nation target = data.nationByName(a[1]);
        if (target == null || target.getId() == c.nation().getId()) { Msg.send(p, "nation.unknown_nation"); return; }
        long me = c.nation().getId(), other = target.getId();
        Relation now = data.relation(me, other);

        switch (wanted) {
            case ALLY -> {
                if (now == Relation.WAR || now == Relation.ENEMY) { Msg.send(p, "nation.make_peace_with_first", Msg.p("n", target.getName())); return; }
                data.setRelation(me, other, Relation.ALLY);
                if (data.directed(other, me) == Relation.ALLY) {
                    data.broadcastNations(Msg.c("nation.and_are_now_allies",
                            Msg.p("a", c.nation().getName()), Msg.p("b", target.getName())), me, other);
                } else {
                    Msg.send(p, "nation.alliance_request_sent_must",
                            Msg.p("n", target.getName()), Msg.p("me", c.nation().getName()));
                    Player theirKing = Optional.ofNullable(data.town(target.getCapitalTownId())).map(t -> Bukkit.getPlayer(t.getOwner())).orElse(null);
                    if (theirKing != null) Msg.send(theirKing, "nation.proposes_an_alliance_accept", Msg.p("n", c.nation().getName()));
                }
            }
            case ENEMY -> {
                if (now == Relation.WAR) { Msg.send(p, "nation.you_re_already_at"); return; }
                data.setRelation(me, other, Relation.ENEMY);
                if (data.directed(other, me) == Relation.ALLY) data.setRelation(other, me, Relation.NEUTRAL);
                data.broadcastNations(Msg.c("nation.declared_an_enemy_nation",
                        Msg.p("a", c.nation().getName()), Msg.p("b", target.getName())), me, other);
            }
            case WAR -> {
                if (data.directed(me, other) == Relation.WAR) { Msg.send(p, "nation.you_re_already_at_2"); return; }
                if (data.directed(me, other) != Relation.ENEMY) {
                    Msg.send(p, "nation.you_must_mark_as", Msg.p("n", target.getName()));
                    return;
                }
                data.setRelation(me, other, Relation.WAR);
                data.broadcastNations(Msg.c("nation.war_declared_war_on",
                        Msg.p("a", c.nation().getName()), Msg.p("b", target.getName())), me, other);
            }
            default -> { }
        }
    }

    /** Ends war/enmity with a nation (acts as a surrender/white peace; either side may use it). */
    private void peace(Player p, String[] a) {
        if (a.length < 2) { usage(p, "/nation peace <nation>"); return; }
        Ctx c = leader(p);
        if (c == null) return;
        Nation target = data.nationByName(a[1]);
        if (target == null) { Msg.send(p, "nation.unknown_nation"); return; }
        long me = c.nation().getId(), other = target.getId();
        boolean changed = false;
        for (long[] pair : new long[][]{{me, other}, {other, me}}) {
            Relation r = data.directed(pair[0], pair[1]);
            if (r == Relation.WAR || r == Relation.ENEMY) { data.setRelation(pair[0], pair[1], Relation.NEUTRAL); changed = true; }
        }
        if (!changed) { Msg.send(p, "nation.you_re_not_at", Msg.p("n", target.getName())); return; }
        plugin.wars().cancelBetween(me, other);
        data.broadcastNations(Msg.c("nation.and_made_peace",
                Msg.p("a", c.nation().getName()), Msg.p("b", target.getName())), me, other);
    }

    private void friendlyFire(Player p) {
        Ctx c = leader(p);
        if (c == null) return;
        c.nation().setFriendlyFire(!c.nation().isFriendlyFire());
        data.saveNation(c.nation());
        Msg.send(p, "nation.friendly_fire_between_towns",
                Msg.p("n", c.nation().getName()), Msg.p("v", Lang.onOff(c.nation().isFriendlyFire())));
    }

    private void leave(Player p) {
        Town t = data.activeTown(p);
        if (t == null || !t.getOwner().equals(p.getUniqueId())) { Msg.send(p, "nation.only_a_town_s"); return; }
        Nation n = data.nation(t.getNationId());
        if (n == null) { Msg.send(p, "nation.this_town_isn_t"); return; }
        if (n.getCapitalTownId() == t.getId()) { Msg.send(p, "nation.the_capital_can_t"); return; }
        plugin.wars().cancelTown(t.getId());
        data.setTownNation(t, null);
        data.broadcastNations(Msg.c("nation.left_the_nation", Msg.p("t", t.getName())), n.getId());
        Msg.send(p, "nation.your_town_left", Msg.p("n", n.getName()));
    }

    private void kick(Player p, String[] a) {
        if (a.length < 2) { usage(p, "/nation kick <town>"); return; }
        Ctx c = leader(p);
        if (c == null) return;
        Town t = data.townByName(a[1]);
        if (t == null || t.getNationId() != c.nation().getId() || t.getId() == c.town().getId()) { Msg.send(p, "nation.that_town_isn_t"); return; }
        plugin.wars().cancelTown(t.getId());
        data.setTownNation(t, null);
        data.broadcastNations(Msg.c("nation.was_removed_from_the", Msg.p("t", t.getName())), c.nation().getId());
    }

    private void disband(Player p, String[] a) {
        Ctx c = leader(p);
        if (c == null) return;
        if (a.length < 2 || !a[1].equalsIgnoreCase("confirm")) { Msg.send(p, "nation.run_nation_disband_confirm", Msg.p("n", c.nation().getName())); return; }
        String name = c.nation().getName();
        data.deleteNation(c.nation());
        Msg.send(p, "nation.nation_was_disbanded", Msg.p("n", name));
    }

    private void flag(Player p, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("cancel")) {
            int n = plugin.wars().cancelStartedBy(p.getUniqueId());
            Msg.send(p, n > 0 ? "nation.flag_cancelled" : "nation.flag_none", Msg.p("n", n));
            return;
        }
        plugin.wars().start(p);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) return TownCommand.filter(SUBS, a[0]);
        if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "info", "ally", "enemy", "war", "peace" -> { return TownCommand.filter(data.nations.values().stream().map(Nation::getName).toList(), a[1]); }
                case "kick" -> { return TownCommand.filter(data.towns.values().stream().map(Town::getName).toList(), a[1]); }
                case "flag", "siege" -> { return TownCommand.filter(List.of("cancel"), a[1]); }
                default -> { return List.of(); }
            }
        }
        return List.of();
    }
}
