package dev.bannerlands.gui;

import dev.bannerlands.BannerlandsPlugin;
import dev.bannerlands.util.Lang;
import dev.bannerlands.data.DataManager;
import dev.bannerlands.model.Perm;
import dev.bannerlands.model.Role;
import dev.bannerlands.model.Tier;
import dev.bannerlands.model.Town;
import dev.bannerlands.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chest GUI for town leaders.
 *  - Permissions view: one row per tier (Neutral/Trusted/Member/Ally/Enemy), one toggle per permission.
 *  - Members view: promote/demote mayors (king only), kick (shift-click).
 */
public final class TownGui implements Listener {
    private static final Tier[] TIERS = Tier.values();
    private static final Perm[] PERMS = Perm.values();
    private static final int PAGE = 45;

    private enum View { PERMS, RESIDENTS }

    private static final class GuiHolder implements InventoryHolder {
        final long townId; final View view; final int page; Inventory inv;
        GuiHolder(long townId, View view, int page) { this.townId = townId; this.view = view; this.page = page; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final BannerlandsPlugin plugin;
    private final DataManager data;

    public TownGui(BannerlandsPlugin plugin) { this.plugin = plugin; this.data = plugin.data(); }

    // ------------------------------------------------------------------ item helpers

    private static ItemStack item(Material m, Component name, Component... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        it.setItemMeta(meta);
        return it;
    }

    private static Material tierIcon(Tier t) {
        return switch (t) {
            case NEUTRAL -> Material.LIGHT_GRAY_WOOL;
            case TRUSTED -> Material.LIGHT_BLUE_WOOL;
            case MEMBER -> Material.GREEN_WOOL;
            case ALLY -> Material.YELLOW_WOOL;
            case ENEMY -> Material.RED_WOOL;
        };
    }

    private static Material permIcon(Perm p) {
        return switch (p) {
            case BREAK -> Material.IRON_PICKAXE;
            case PLACE -> Material.GRASS_BLOCK;
            case CHEST -> Material.CHEST;
            case DOOR -> Material.OAK_DOOR;
        };
    }

    private ItemStack toggle(Town t, Tier tier, Perm perm) {
        boolean on = t.has(tier, perm);
        return item(on ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                Msg.raw(on ? "gui.toggle_on" : "gui.toggle_off", Msg.p("perm", Lang.get("perm." + perm.name().toLowerCase()))),
                Msg.raw("gui.tier_line", Msg.p("tier", Lang.get("tier." + tier.name().toLowerCase()))), Msg.raw("gui.click_to_toggle"));
    }

    // ------------------------------------------------------------------ views

    public void openPerms(Player p, Town t) {
        GuiHolder h = new GuiHolder(t.getId(), View.PERMS, 0);
        Inventory inv = Bukkit.createInventory(h, 54, Msg.raw("gui.title_perms", Msg.p("t", t.getName())));
        h.inv = inv;
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);
        inv.setItem(0, item(Material.BOOK, Msg.raw("gui.header_name", Msg.p("t", t.getName())),
                Msg.raw("gui.click_a_square_to"), Msg.raw("gui.an_action_for_that")));
        for (int i = 0; i < PERMS.length; i++) {
            inv.setItem(2 + i, item(permIcon(PERMS[i]), Msg.raw("gui.perm_header", Msg.p("perm", Lang.get("perm." + PERMS[i].name().toLowerCase())))));
        }
        inv.setItem(7, item(Material.WRITABLE_BOOK, Msg.raw("gui.members_ranks"), Msg.raw("gui.promote_demote_kick")));
        inv.setItem(8, item(Material.BARRIER, Msg.raw("gui.close")));
        for (int r = 0; r < TIERS.length; r++) {
            int row = (r + 1) * 9;
            inv.setItem(row, item(tierIcon(TIERS[r]), Msg.raw("gui.tier_label", Msg.p("tier", Lang.get("tier." + TIERS[r].name().toLowerCase())))));
            for (int c = 0; c < PERMS.length; c++) inv.setItem(row + 2 + c, toggle(t, TIERS[r], PERMS[c]));
        }
        p.openInventory(inv);
    }

    private List<UUID> sortedMembers(Town t) {
        return t.getMembers().entrySet().stream()
                .sorted(Comparator.<Map.Entry<UUID, Role>>comparingInt(e -> e.getValue().ordinal())
                        .thenComparing(e -> data.nameOf(e.getKey()), String.CASE_INSENSITIVE_ORDER))
                .map(Map.Entry::getKey).toList();
    }

    public void openMembers(Player p, Town t, int page) {
        List<UUID> list = sortedMembers(t);
        int pages = Math.max(1, (list.size() + PAGE - 1) / PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        GuiHolder h = new GuiHolder(t.getId(), View.RESIDENTS, page);
        Inventory inv = Bukkit.createInventory(h, 54, Msg.raw("gui.title_members", Msg.p("t", t.getName()), Msg.p("page", page + 1), Msg.p("pages", pages)));
        h.inv = inv;
        for (int i = 0; i < PAGE; i++) {
            int idx = page * PAGE + i;
            if (idx >= list.size()) break;
            UUID u = list.get(idx);
            Role role = t.role(u);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(u));
            meta.displayName(Component.text(data.nameOf(u)).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Msg.raw("gui.rank_line", Msg.p("rank", Lang.get("role." + role.name().toLowerCase()))).decoration(TextDecoration.ITALIC, false),
                    Msg.raw("gui.click_toggle_mayor_member").decoration(TextDecoration.ITALIC, false),
                    Msg.raw("gui.shift_click_kick").decoration(TextDecoration.ITALIC, false)));
            head.setItemMeta(meta);
            inv.setItem(i, head);
        }
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = PAGE; i < 54; i++) inv.setItem(i, pane);
        if (page > 0) inv.setItem(45, item(Material.ARROW, Msg.raw("gui.previous_page")));
        if (page < pages - 1) inv.setItem(53, item(Material.ARROW, Msg.raw("gui.next_page")));
        inv.setItem(49, item(Material.COMPARATOR, Msg.raw("gui.back_to_permissions")));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof GuiHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof GuiHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        Town t = data.town(h.townId);
        Role role = t == null ? null : t.role(p.getUniqueId());
        boolean admin = p.hasPermission("bannerlands.admin");
        if (t == null || (!admin && (role == null || !role.atLeast(Role.ASSISTANT)))) { p.closeInventory(); return; }

        int slot = e.getRawSlot();
        if (h.view == View.PERMS) clickPerms(p, t, h, slot);
        else clickMembers(p, t, h, slot, role, e.isShiftClick());
    }

    private void clickPerms(Player p, Town t, GuiHolder h, int slot) {
        if (slot == 8) { p.closeInventory(); return; }
        if (slot == 7) { later(() -> openMembers(p, t, 0)); return; }
        int row = slot / 9, col = slot % 9;
        if (row < 1 || row > TIERS.length || col < 2 || col >= 2 + PERMS.length) return;
        Tier tier = TIERS[row - 1];
        Perm perm = PERMS[col - 2];
        t.set(tier, perm, !t.has(tier, perm));
        data.saveTown(t);
        h.inv.setItem(slot, toggle(t, tier, perm));
    }

    private void clickMembers(Player p, Town t, GuiHolder h, int slot, Role actor, boolean shift) {
        if (slot == 49) { later(() -> openPerms(p, t)); return; }
        if (slot == 45) { later(() -> openMembers(p, t, h.page - 1)); return; }
        if (slot == 53) { later(() -> openMembers(p, t, h.page + 1)); return; }
        if (slot < 0 || slot >= PAGE) return;
        List<UUID> list = sortedMembers(t);
        int idx = h.page * PAGE + slot;
        if (idx >= list.size()) return;
        UUID target = list.get(idx);
        Role tr = t.role(target);
        if (tr == null || tr == Role.MAYOR) return;
        boolean king = actor == Role.MAYOR || p.hasPermission("bannerlands.admin");

        if (shift) {
            if (!king && tr != Role.RESIDENT) { Msg.send(p, "gui.only_the_king_can"); return; }
            data.removeMember(t, target);
            Msg.send(p, "gui.was_removed_from_the", Msg.p("n", data.nameOf(target)));
            Player kicked = Bukkit.getPlayer(target);
            if (kicked != null) Msg.send(kicked, "gui.you_were_removed_from", Msg.p("t", t.getName()));
        } else {
            if (!king) { Msg.send(p, "gui.only_the_king_can_2"); return; }
            data.setRole(t, target, tr == Role.ASSISTANT ? Role.RESIDENT : Role.ASSISTANT);
        }
        later(() -> openMembers(p, t, h.page));
    }

    private void later(Runnable r) { plugin.getServer().getScheduler().runTask(plugin, r); }
}
