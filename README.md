# Bannerlands – Towny-lite for Paper 1.20+

Towns, nations, diplomacy and chunk-by-chunk flag wars, with WorldGuard regions and Vault economy.

**Tested versions**: 1.20.4-1.21.11

## Build / install
    mvn clean package          # Java 17+, output: target/Bannerlands-1.0.0.jar
Drop the jar in `plugins/`. Needs Vault + an economy plugin + WorldGuard. HikariCP, SQLite and the MySQL
driver are downloaded by Paper itself (`libraries:` in plugin.yml), so the jar stays small.
Edit `plugins/Bannerlands/config.yml` (worlds, prices, progression items, flag war tuning).

## Commands
/town  map | create | claim | unclaim | info | add | bonus | join | leave | kick | rank | trust | untrust | perms (GUI)
       desc | promo | friendlyfire | unlock | invite <town> | invites | accept <id> | deny <id> | delete confirm
/nation create | info | ally | enemy | war | peace | friendlyfire | leave | kick | disband | flag [cancel]

Multi-town owners: commands act on the town you are standing in (if you belong to it), otherwise your oldest town.

## Languages
All text lives in `plugins/Bannerlands/lang/<code>.yml` (generated from the bundled `en.yml` on first start).
Set `language: <code>` in config.yml, copy `en.yml` to e.g. `hu.yml` and translate the values. Keep the `<placeholders>`;
formatting is MiniMessage. Missing keys fall back to English. `/town reload` (needs `Bannerlands.admin`) reloads config + language.

## Design notes
* Event handlers only use ConcurrentHashMap lookups (DataManager / Access). DB reads happen once at start-up,
  writes are queued on one background thread (write-behind). IDs are generated in memory.
* WorldGuard: one ProtectedCuboidRegion per chunk (`Bannerlands_<cx>_<cz>`). Regions ALLOW build/interact/use/chest-access
  and Bannerlands enforces the granular tiers itself (WG members can't express break/place/chest/door per tier).
  Regions deny TNT/creeper/fire while not raided, and carry greeting messages.
* Tiers: Neutral, Trusted (/town trust), Member, Ally (same nation or mutually allied nation), Enemy (enemy/war nation).
* Alliances are mutual (both nations run /nation ally). Enemy is unilateral. War needs a prior /nation enemy.
* Flag war: /nation flag plants a banner in the enemy chunk. Attackers inside the radius (and no defenders) raise the
  BossBar; defenders present = contested; defenders alone push it back; defenders can also break the banner.
  On capture (config `on-capture`): RAID (attackers get full access for N minutes, explosions allowed),
  TRANSFER (chunk goes to the attacking town) or UNCLAIM.

## Known limits / next steps
* A server crash during a siege leaves the banner block behind (it is restored on clean shutdown).
* Animals/armor stands/pistons/fluid flow across claim borders are not covered yet.
