package dev.bannerlands.command;

import dev.bannerlands.BannerlandsPlugin;
import dev.bannerlands.util.Lang;
import dev.bannerlands.data.DataManager;
import dev.bannerlands.model.*;
import dev.bannerlands.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class NationCommand implements TabExecutor {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,20}$");
    private static final List<String> SUBS = List.of("create", "info", "ally", "enemy", "war", "peace", "friendlyfire",
            "leave", "kick", "disband", "flag", "rename", "capital", "king", "kingaccept", "kingdeny", "help");

    private final BannerlandsPlugin plugin;
    private final DataManager data;
    /** new king (target) -> pending offer from the current king */
    private final Map<UUID, KingOffer> kingOffers = new ConcurrentHashMap<>();

    public NationCommand(BannerlandsPlugin plugin) { this.plugin = plugin; this.data = plugin.data(); }

    /** {@code town} is the capital of {@code nation}. */
    private record Ctx(Town town, Nation nation) { }
    private record KingOffer(long nationId, UUID from, long expires) { }

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
            case "rename" -> rename(p, a);
            case "capital", "setcapital" -> capital(p, a);
            case "king" -> king(p, a);
            case "kingaccept" -> kingAccept(p);
            case "kingdeny" -> kingDeny(p);
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
                "rename <name>  (king)", "capital <town>  (king: move the capital)", "king <player> / kingaccept / kingdeny  (hand over the crown)", "leave / kick <town> / disband confirm", "(invite towns with /town invite <town>)"}) {
            p.sendMessage(Msg.raw("nation.nation", Msg.p("l", l)));
        }
    }

    /** The player must be the king of a nation (the king is set per nation and is independent from the capital's owner). */
    private Ctx leader(Player p) {
        Nation n = data.kingNation(p);
        if (n == null) {
            Town t = data.activeTown(p);
            if (t == null) Msg.send(p, "nation.you_aren_t_in");
            else if (data.nation(t.getNationId()) == null) Msg.send(p, "nation.isn_t_in_a", Msg.p("t", t.getName()));
            else Msg.send(p, "nation.only_the_king_of");
            return null;
        }
        return new Ctx(data.town(n.getCapitalTownId()), n);
    }

    /** Sends the cooldown message and returns true if the action is still cooling down. */
    private boolean onCooldown(Player p, String key, String configPath) {
        long left = data.cooldownLeft(key, plugin.getConfig().getDouble(configPath, 24));
        if (left <= 0) return false;
        Msg.send(p, "nation.cooldown", Msg.p("t", Msg.duration(left)));
        return true;
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
                    Player theirKing = target.getKing() == null ? null : Bukkit.getPlayer(target.getKing());
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

    /** /nation rename <name>: the king renames the nation. */
    private void rename(Player p, String[] a) {
        if (a.length < 2) { usage(p, "/nation rename <name>"); return; }
        Ctx c = leader(p);
        if (c == null) return;
        String name = a[1];
        if (!NAME.matcher(name).matches()) { Msg.send(p, "nation.names_must_be_3"); return; }
        if (name.equals(c.nation().getName())) { Msg.send(p, "nation.rename_same"); return; }
        Nation clash = data.nationByName(name);
        if (clash != null && clash.getId() != c.nation().getId()) { Msg.send(p, "nation.that_name_is_taken"); return; }
        String cdKey = "nation_rename:" + c.nation().getId();
        if (onCooldown(p, cdKey, "cooldowns.nation-rename-hours")) return;
        double fee = plugin.getConfig().getDouble("nations.rename-cost", 0);
        if (!plugin.economy().withdraw(p, fee)) {
            Msg.send(p, "nation.rename_need_money", Msg.p("m", plugin.economy().format(fee)));
            return;
        }
        String old = c.nation().getName();
        data.renameNation(c.nation(), name);
        data.markCooldown(cdKey);
        Msg.send(p, "nation.renamed", Msg.p("o", old), Msg.p("n", name));
        Set<UUID> sent = new HashSet<>();
        sent.add(p.getUniqueId());
        for (long tid : c.nation().getTowns()) {
            data.broadcastTown(Msg.c("nation.renamed_notice", Msg.p("o", old), Msg.p("n", name)), data.town(tid), sent);
        }
    }

    /** /nation capital <town>: the king moves the capital to another town of the nation. The king does not change. */
    private void capital(Player p, String[] a) {
        if (a.length < 2) { usage(p, "/nation capital <town>"); return; }
        Ctx c = leader(p);
        if (c == null) return;
        Town target = data.townByName(a[1]);
        if (target == null || target.getNationId() != c.nation().getId()) { Msg.send(p, "nation.capital_not_member"); return; }
        if (target.getId() == c.nation().getCapitalTownId()) { Msg.send(p, "nation.capital_already"); return; }
        String cdKey = "nation_capital:" + c.nation().getId();
        if (onCooldown(p, cdKey, "cooldowns.capital-hours")) return;
        data.setCapital(c.nation(), target);
        data.markCooldown(cdKey);
        data.broadcastNations(Msg.c("nation.capital_moved",
                Msg.p("n", c.nation().getName()), Msg.p("t", target.getName())), c.nation().getId());
    }

    /** /nation king <player>: offer the crown to an online member of the nation; they must accept. */
    private void king(Player p, String[] a) {
        if (a.length < 2) { usage(p, "/nation king <player>"); return; }
        Ctx c = leader(p);
        if (c == null) return;
        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) { Msg.send(p, "nation.king_target_offline"); return; }
        if (target.getUniqueId().equals(p.getUniqueId())) { Msg.send(p, "nation.king_self"); return; }
        if (!data.isNationMember(c.nation(), target.getUniqueId())) { Msg.send(p, "nation.king_not_member", Msg.p("p", target.getName())); return; }
        if (onCooldown(p, "nation_king:" + c.nation().getId(), "cooldowns.king-hours")) return;
        long now = System.currentTimeMillis();
        kingOffers.values().removeIf(o -> o.expires() < now);
        kingOffers.put(target.getUniqueId(), new KingOffer(c.nation().getId(), p.getUniqueId(), now + 300_000L));
        Msg.send(p, "nation.king_offer_sent", Msg.p("p", target.getName()), Msg.p("n", c.nation().getName()));
        Component accept = Msg.raw("nation.btn_king_accept").clickEvent(ClickEvent.runCommand("/nation kingaccept"));
        Component deny = Msg.raw("nation.btn_king_deny").clickEvent(ClickEvent.runCommand("/nation kingdeny"));
        target.sendMessage(Msg.c("nation.king_offer", Msg.p("p", p.getName()), Msg.p("n", c.nation().getName())));
        target.sendMessage(Component.text("   ").append(accept).append(Component.text("  ")).append(deny));
    }

    private void kingAccept(Player p) {
        KingOffer o = kingOffers.remove(p.getUniqueId());
        if (o == null || o.expires() < System.currentTimeMillis()) { Msg.send(p, "nation.king_no_offer"); return; }
        Nation n = data.nation(o.nationId());
        // re-check everything: the world may have changed while the offer was open
        if (n == null || !o.from().equals(n.getKing()) || !data.isNationMember(n, p.getUniqueId())) {
            Msg.send(p, "nation.king_offer_invalid");
            return;
        }
        String cdKey = "nation_king:" + n.getId();
        if (onCooldown(p, cdKey, "cooldowns.king-hours")) return;
        data.setKing(n, p.getUniqueId());
        data.markCooldown(cdKey);
        Component msg = Msg.c("nation.king_changed", Msg.p("n", n.getName()), Msg.p("p", p.getName()));
        Set<UUID> sent = new HashSet<>();
        for (long tid : n.getTowns()) data.broadcastTown(msg, data.town(tid), sent);
        Player oldKing = Bukkit.getPlayer(o.from());
        if (oldKing != null && sent.add(oldKing.getUniqueId())) oldKing.sendMessage(msg);
        if (sent.add(p.getUniqueId())) p.sendMessage(msg);
    }

    private void kingDeny(Player p) {
        KingOffer o = kingOffers.remove(p.getUniqueId());
        if (o == null || o.expires() < System.currentTimeMillis()) { Msg.send(p, "nation.king_no_offer"); return; }
        Nation n = data.nation(o.nationId());
        Msg.send(p, "nation.king_declined_you", Msg.p("n", n == null ? "?" : n.getName()));
        Player from = Bukkit.getPlayer(o.from());
        if (from != null) Msg.send(from, "nation.king_declined", Msg.p("p", p.getName()));
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
        if (t == null || t.getNationId() != c.nation().getId() || t.getId() == c.nation().getCapitalTownId()) { Msg.send(p, "nation.that_town_isn_t"); return; }
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
                case "capital", "setcapital" -> {
                    if (!(s instanceof Player p)) return List.of();
                    Town t = data.activeTown(p);
                    Nation n = t == null ? null : data.nation(t.getNationId());
                    if (n == null) return List.of();
                    return TownCommand.filter(n.getTowns().stream().map(data::town).filter(Objects::nonNull).map(Town::getName).toList(), a[1]);
                }
                case "king" -> { return TownCommand.filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), a[1]); }
                case "flag", "siege" -> { return TownCommand.filter(List.of("cancel"), a[1]); }
                default -> { return List.of(); }
            }
        }
        return List.of();
    }
}
