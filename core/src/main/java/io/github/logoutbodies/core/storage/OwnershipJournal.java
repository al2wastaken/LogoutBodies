package io.github.logoutbodies.core.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable ownership and at-most-once drop intent. Access only from the server thread. */
public final class OwnershipJournal implements AutoCloseable {
    public enum DropState {
        NONE,
        PREPARED,
        APPLYING,
        DONE,
        AMBIGUOUS
    }

    public record Entry(
            UUID session,
            UUID owner,
            String name,
            String phase,
            byte[] snapshot,
            DropState dropState,
            byte[] drops,
            String reason) {
        public Entry {
            snapshot = snapshot.clone();
            drops = drops.clone();
        }

        @Override
        public byte[] snapshot() {
            return snapshot.clone();
        }

        @Override
        public byte[] drops() {
            return drops.clone();
        }
    }

    private final Connection connection;

    public OwnershipJournal(Path file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var sql = connection.createStatement()) {
            sql.execute("PRAGMA journal_mode=WAL");
            sql.execute("PRAGMA synchronous=FULL");
            sql.execute("PRAGMA busy_timeout=5000");
            try (var result = sql.executeQuery("PRAGMA user_version")) {
                if (result.next() && result.getInt(1) > 1)
                    throw new SQLException("Journal is from a newer plugin");
            }
            sql.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sessions (
                      session TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL,
                      phase TEXT NOT NULL, snapshot BLOB NOT NULL,
                      drop_state TEXT NOT NULL DEFAULT 'NONE', drops BLOB NOT NULL DEFAULT X'',
                      reason TEXT NOT NULL DEFAULT '', updated INTEGER NOT NULL)
                    """);
            sql.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS live_owner ON sessions(owner) WHERE phase <>"
                            + " 'COMPLETE'");
            sql.execute("PRAGMA user_version=1");
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public void begin(UUID session, UUID owner, String name, byte[] snapshot) throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        "INSERT INTO sessions(session,owner,name,phase,snapshot,updated)"
                                + " VALUES(?,?,?,'ACTIVE',?,?)")) {
            sql.setString(1, session.toString());
            sql.setString(2, owner.toString());
            sql.setString(3, name);
            sql.setBytes(4, snapshot);
            sql.setLong(5, System.currentTimeMillis());
            sql.executeUpdate();
        }
    }

    public void checkpoint(UUID session, byte[] snapshot) throws SQLException {
        update(
                "UPDATE sessions SET snapshot=?, updated=? WHERE session=? AND phase='ACTIVE'",
                snapshot,
                session);
    }

    /** One durable transaction per lifecycle sweep, rather than one fsync per body. */
    public void checkpointAll(Map<UUID, byte[]> snapshots) throws SQLException {
        if (snapshots.isEmpty()) return;
        connection.setAutoCommit(false);
        try {
            for (var entry : snapshots.entrySet()) checkpoint(entry.getKey(), entry.getValue());
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void update(String query, byte[] snapshot, UUID session) throws SQLException {
        try (var sql = connection.prepareStatement(query)) {
            sql.setBytes(1, snapshot);
            sql.setLong(2, System.currentTimeMillis());
            sql.setString(3, session.toString());
            requireOne(sql.executeUpdate());
        }
    }

    public void settle(UUID session, byte[] snapshot, String reason) throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        "UPDATE sessions SET snapshot=?,phase='AWAITING_OWNER',reason=?,updated=?"
                                + " WHERE session=? AND phase='ACTIVE'")) {
            sql.setBytes(1, snapshot);
            sql.setString(2, reason);
            sql.setLong(3, System.currentTimeMillis());
            sql.setString(4, session.toString());
            requireOne(sql.executeUpdate());
        }
    }

    public void invalidate(UUID session) throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        "UPDATE sessions SET"
                            + " phase='AWAITING_OWNER',reason='UNCLEAN_SHUTDOWN',updated=? WHERE"
                            + " session=? AND phase='ACTIVE'")) {
            sql.setLong(1, System.currentTimeMillis());
            sql.setString(2, session.toString());
            requireOne(sql.executeUpdate());
        }
    }

    /** Final post-event death state and drop intent commit together, before any world drop. */
    public void prepareDeath(UUID session, byte[] snapshot, byte[] drops) throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        "UPDATE sessions SET"
                            + " snapshot=?,drops=?,phase='AWAITING_OWNER',reason='DIED',drop_state='PREPARED',updated=?"
                            + " WHERE session=? AND phase='ACTIVE'")) {
            sql.setBytes(1, snapshot);
            sql.setBytes(2, drops);
            sql.setLong(3, System.currentTimeMillis());
            sql.setString(4, session.toString());
            requireOne(sql.executeUpdate());
        }
    }

    public boolean claimDrops(UUID session) throws SQLException {
        return transitionDrops(session, DropState.PREPARED, DropState.APPLYING);
    }

    public void finalizeDeathSnapshot(UUID session, byte[] snapshot) throws SQLException {
        update(
                "UPDATE sessions SET snapshot=?,updated=? WHERE session=? AND"
                        + " phase='AWAITING_OWNER' AND drop_state='APPLYING'",
                snapshot,
                session);
    }

    public void finishDrops(UUID session) throws SQLException {
        if (!transitionDrops(session, DropState.APPLYING, DropState.DONE))
            throw new SQLException("Drop operation not claimed");
    }

    public void abandonDrops(UUID session) throws SQLException {
        transitionDrops(session, DropState.APPLYING, DropState.AMBIGUOUS);
    }

    private boolean transitionDrops(UUID session, DropState expected, DropState next)
            throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        "UPDATE sessions SET drop_state=?,updated=? WHERE session=? AND"
                                + " drop_state=?")) {
            sql.setString(1, next.name());
            sql.setLong(2, System.currentTimeMillis());
            sql.setString(3, session.toString());
            sql.setString(4, expected.name());
            return sql.executeUpdate() == 1;
        }
    }

    /** Called after owner.saveData(), before the owner can play. */
    public void complete(UUID session) throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        "UPDATE sessions SET phase='COMPLETE',updated=? WHERE session=? AND"
                                + " phase='AWAITING_OWNER' AND drop_state IN"
                                + " ('NONE','DONE','AMBIGUOUS')")) {
            sql.setLong(1, System.currentTimeMillis());
            sql.setString(2, session.toString());
            requireOne(sql.executeUpdate());
        }
    }

    public void recover() throws SQLException {
        connection.setAutoCommit(false);
        try (var sql = connection.createStatement()) {
            sql.executeUpdate(
                    "UPDATE sessions SET drop_state='AMBIGUOUS' WHERE drop_state='APPLYING'");
            sql.executeUpdate(
                    "UPDATE sessions SET phase='AWAITING_OWNER',reason='UNCLEAN_SHUTDOWN' WHERE"
                            + " phase='ACTIVE'");
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public Optional<Entry> pending(UUID owner) throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        "SELECT * FROM sessions WHERE owner=? AND phase<>'COMPLETE'")) {
            sql.setString(1, owner.toString());
            try (var rows = sql.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    public List<Entry> unfinishedDrops() throws SQLException {
        return query("SELECT * FROM sessions WHERE drop_state='PREPARED' ORDER BY updated");
    }

    public List<Entry> diagnostics() throws SQLException {
        return query(
                "SELECT * FROM sessions WHERE phase<>'COMPLETE' OR drop_state='AMBIGUOUS' OR"
                        + " reason='UNCLEAN_SHUTDOWN' ORDER BY updated DESC LIMIT 100");
    }

    /** Retain uncertain operations indefinitely; bound ordinary completed history. */
    public void pruneCompleted(long beforeMillis) throws SQLException {
        try (var sql =
                connection.prepareStatement(
                        "DELETE FROM sessions WHERE session IN (SELECT session FROM sessions WHERE"
                                + " phase='COMPLETE' AND drop_state<>'AMBIGUOUS' AND"
                                + " reason<>'UNCLEAN_SHUTDOWN' AND updated<? LIMIT 1000)")) {
            sql.setLong(1, beforeMillis);
            sql.executeUpdate();
        }
    }

    private List<Entry> query(String query) throws SQLException {
        List<Entry> entries = new ArrayList<>();
        try (var sql = connection.createStatement();
                var rows = sql.executeQuery(query)) {
            while (rows.next()) entries.add(read(rows));
        }
        return List.copyOf(entries);
    }

    private static Entry read(ResultSet row) throws SQLException {
        return new Entry(
                UUID.fromString(row.getString("session")),
                UUID.fromString(row.getString("owner")),
                row.getString("name"),
                row.getString("phase"),
                row.getBytes("snapshot"),
                DropState.valueOf(row.getString("drop_state")),
                row.getBytes("drops"),
                row.getString("reason"));
    }

    private static void requireOne(int count) throws SQLException {
        if (count != 1) throw new SQLException("Stale or invalid ownership transition");
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
