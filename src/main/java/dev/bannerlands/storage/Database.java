package dev.bannerlands.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.bannerlands.BannerlandsPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * HikariCP-backed SQLite/MySQL access.
 * Reads happen once at start-up (synchronously); all runtime writes are queued on a single
 * background thread so the main thread never touches the database.
 */
public final class Database {
    @FunctionalInterface
    public interface RowHandler { void handle(ResultSet rs) throws SQLException; }

    private final BannerlandsPlugin plugin;
    private HikariDataSource ds;
    private ExecutorService exec;

    public Database(BannerlandsPlugin plugin) { this.plugin = plugin; }

    public void init() throws Exception {
        FileConfiguration c = plugin.getConfig();
        HikariConfig hc = new HikariConfig();
        String type = c.getString("database.type", "sqlite").toLowerCase();
        if (type.equals("mysql")) {
            hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hc.setJdbcUrl("jdbc:mysql://" + c.getString("database.mysql.host", "localhost") + ":"
                    + c.getInt("database.mysql.port", 3306) + "/" + c.getString("database.mysql.database", "realms")
                    + "?useSSL=false&characterEncoding=utf8&serverTimezone=UTC");
            hc.setUsername(c.getString("database.mysql.user", "root"));
            hc.setPassword(c.getString("database.mysql.password", ""));
            hc.setMaximumPoolSize(c.getInt("database.mysql.pool-size", 8));
        } else {
            plugin.getDataFolder().mkdirs();
            File f = new File(plugin.getDataFolder(), c.getString("database.sqlite-file", "realms.db"));
            hc.setDriverClassName("org.sqlite.JDBC");
            hc.setJdbcUrl("jdbc:sqlite:" + f.getAbsolutePath());
            hc.setMaximumPoolSize(1); // SQLite = single writer
            hc.setConnectionInitSql("PRAGMA journal_mode=WAL");
        }
        hc.setPoolName("Realms-Hikari");
        ds = new HikariDataSource(hc);
        exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "Realms-DB"));
        runSchema();
    }

    private void runSchema() throws Exception {
        String sql;
        try (InputStream in = plugin.getResource("schema.sql")) {
            if (in == null) throw new IllegalStateException("schema.sql missing from jar");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder clean = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) clean.append(line).append('\n');
        }
        try (Connection con = ds.getConnection(); Statement st = con.createStatement()) {
            for (String stmt : clean.toString().split(";")) {
                if (!stmt.isBlank()) st.execute(stmt.trim());
            }
            // Migration for databases created before bonus chunks existed (fails harmlessly if the column is already there)
            try (Statement alter = con.createStatement()) {
                alter.execute("ALTER TABLE towns ADD COLUMN bonus_claims INT NOT NULL DEFAULT 0");
            } catch (SQLException ignored) { /* column already exists */ }
        }
    }

    /** Asynchronous write. Params are bound as given (UUID -> string, Boolean -> 0/1). */
    public void update(String sql, Object... params) {
        exec.execute(() -> {
            try (Connection con = ds.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
                bind(ps, params);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "DB write failed: " + sql, e);
            }
        });
    }

    /** Synchronous read; only used while loading data on start-up. */
    public void each(String sql, RowHandler handler, Object... params) {
        try (Connection con = ds.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) handler.handle(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("DB read failed: " + sql, e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object o = params[i];
            if (o instanceof UUID u) o = u.toString();
            else if (o instanceof Boolean b) o = b ? 1 : 0;
            ps.setObject(i + 1, o);
        }
    }

    public void close() {
        if (exec != null) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(30, TimeUnit.SECONDS)) plugin.getLogger().warning("DB queue did not drain in time");
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (ds != null) ds.close();
    }
}
