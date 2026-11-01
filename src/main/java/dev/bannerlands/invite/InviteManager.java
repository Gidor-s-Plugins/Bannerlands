package dev.bannerlands.invite;

import dev.bannerlands.BannerlandsPlugin;
import dev.bannerlands.data.DataManager;
import dev.bannerlands.model.Nation;
import dev.bannerlands.model.Town;
import dev.bannerlands.model.TownInvite;
import dev.bannerlands.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Nation -> town invitations. Invites live in the DB, so if the target town's owner is offline the invite
 * waits and is delivered (clickable) the moment they log back in.
 */
public final class InviteManager implements Listener {
    private final BannerlandsPlugin plugin;

    public InviteManager(BannerlandsPlugin plugin) { this.plugin = plugin; }

    private DataManager data() { return plugin.data(); }

    private long expiryMs() { return plugin.getConfig().getLong("nations.invite-expire-hours", 72) * 3_600_000L; }

    public boolean expired(TownInvite i) { return System.currentTimeMillis() - i.getCreatedAt() > expiryMs(); }

    /** @return null on success, otherwise an error message. */
    public String invite(Player sender, Nation nation, Town target) {
        if (target.getNationId() != 0) return "That town already belongs to a nation.";
        for (TownInvite i : data().invites.values()) {
            if (i.getNationId() == nation.getId() && i.getTownId() == target.getId() && !expired(i)) {
                return "That town already has a pending invite from your nation.";
            }
        }
        TownInvite inv = data().createInvite(nation, target, sender.getUniqueId());
        Player owner = Bukkit.getPlayer(target.getOwner());
        if (owner != null) {
            send(inv, owner);
            inv.setDelivered(true);
            data().saveInvite(inv);
        }
        return null;
    }

    /** Clickable chat popup. */
    public void send(TownInvite inv, Player to) {
        Nation n = data().nation(inv.getNationId());
        Town t = data().town(inv.getTownId());
        if (n == null || t == null) return;
        Component accept = Msg.raw("invite.btn_accept")
                .clickEvent(ClickEvent.runCommand("/town accept " + inv.getId()))
                .hoverEvent(HoverEvent.showText(Msg.raw("invite.hover_accept", Msg.p("n", n.getName()))));
        Component deny = Msg.raw("invite.btn_deny")
                .clickEvent(ClickEvent.runCommand("/town deny " + inv.getId()))
                .hoverEvent(HoverEvent.showText(Msg.raw("invite.hover_deny")));
        to.sendMessage(Msg.c("invite.the_nation_invites_your",
                Msg.p("n", n.getName()), Msg.p("t", t.getName())));
        to.sendMessage(Component.text("   ").append(accept).append(Component.text("  ")).append(deny));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        data().touchProfile(p);
        // small delay so the invite isn't buried under the join spam
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) deliverPending(p); }, 40L);
    }

    public void deliverPending(Player p) {
        UUID u = p.getUniqueId();
        for (TownInvite inv : data().invites.values()) {
            if (inv.isDelivered() || expired(inv)) continue;
            Town t = data().town(inv.getTownId());
            if (t == null || !t.getOwner().equals(u)) continue;
            send(inv, p);
            inv.setDelivered(true);
            data().saveInvite(inv);
        }
    }

    public void accept(Player p, long id) {
        TownInvite inv = data().invites.get(id);
        Town t = inv == null ? null : data().town(inv.getTownId());
        if (inv == null || t == null || !t.getOwner().equals(p.getUniqueId())) {
            Msg.send(p, "invite.that_invitation_doesn_t");
            return;
        }
        Nation n = data().nation(inv.getNationId());
        if (expired(inv) || n == null) {
            data().removeInvite(inv);
            Msg.send(p, "invite.that_invitation_has_expired");
            return;
        }
        if (t.getNationId() != 0) {
            Msg.send(p, "invite.your_town_is_already");
            return;
        }
        data().setTownNation(t, n);
        for (TownInvite other : new ArrayList<>(data().invites.values())) {
            if (other.getTownId() == t.getId()) data().removeInvite(other);
        }
        data().broadcastNations(Msg.c("invite.town_joined_the_nation",
                Msg.p("t", t.getName()), Msg.p("n", n.getName())), n.getId());
    }

    public void deny(Player p, long id) {
        TownInvite inv = data().invites.get(id);
        Town t = inv == null ? null : data().town(inv.getTownId());
        if (inv == null || t == null || !t.getOwner().equals(p.getUniqueId())) {
            Msg.send(p, "invite.that_invitation_doesn_t");
            return;
        }
        data().removeInvite(inv);
        Msg.send(p, "invite.invitation_declined");
        Player sender = Bukkit.getPlayer(inv.getSender());
        if (sender != null) Msg.send(sender, "invite.town_declined_your_nation", Msg.p("t", t.getName()));
    }

    public void purgeExpired() {
        for (TownInvite i : new ArrayList<>(data().invites.values())) {
            if (expired(i)) data().removeInvite(i);
        }
    }
}
