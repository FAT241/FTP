package lab2;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class FtpDatabase {
    static final String USERNAME = "ftp_demo";
    private static final String DB = "lab2_ftp";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/";

    private final String username;
    private final String password;

    FtpDatabase(String username, String password) {
        this.username = username;
        this.password = password;
    }

    Connection connect() throws SQLException {
        return connect(DB);
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(URL + database + "?connectTimeout=3", username, password);
    }

    void setup(String ftpPassword) throws SQLException {
        try (var db = connect()) {
            // Open the existing database with the supplied account first.
        } catch (SQLException e) {
            if (!"3D000".equals(e.getSQLState())) throw e;
            try (var admin = connect("postgres"); var sql = admin.createStatement()) {
                sql.execute("CREATE DATABASE lab2_ftp");
            } catch (SQLException creationError) {
                if (!"42P04".equals(creationError.getSQLState())) throw creationError;
            }
        }
        try (var db = connect(); var sql = db.createStatement()) {
            db.setAutoCommit(false);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS ftp_accounts (
                        username text PRIMARY KEY,
                        salt bytea NOT NULL,
                        password_hash bytea NOT NULL,
                        created_at timestamptz NOT NULL DEFAULT now()
                    )
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS ftp_transfers (
                        id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        username text NOT NULL REFERENCES ftp_accounts(username),
                        client_ip inet NOT NULL,
                        command text NOT NULL,
                        file_path text NOT NULL,
                        bytes bigint NOT NULL CHECK (bytes >= 0),
                        completed boolean NOT NULL,
                        recorded_at timestamptz NOT NULL DEFAULT now()
                    )
                    """);
            if (!ftpPassword.isBlank()) {
                byte[] salt = new byte[16];
                new SecureRandom().nextBytes(salt);
                try (var insert = db.prepareStatement("""
                        INSERT INTO ftp_accounts(username, salt, password_hash) VALUES (?, ?, ?)
                        ON CONFLICT(username) DO UPDATE SET
                            salt = EXCLUDED.salt, password_hash = EXCLUDED.password_hash
                        """)) {
                    insert.setString(1, USERNAME);
                    insert.setBytes(2, salt);
                    insert.setBytes(3, hash(ftpPassword, salt));
                    insert.executeUpdate();
                }
            } else {
                try (var query = db.prepareStatement("SELECT 1 FROM ftp_accounts WHERE username = ?")) {
                    query.setString(1, USERNAME);
                    try (var account = query.executeQuery()) {
                        if (!account.next()) {
                            throw new IllegalArgumentException("Hãy đặt mật khẩu FTP trong lần chạy đầu.");
                        }
                    }
                }
            }
            db.commit();
        }
    }

    boolean authenticate(String username, String password) throws SQLException {
        if (password == null) return false;
        try (var db = connect(); var sql = db.prepareStatement(
                "SELECT salt, password_hash FROM ftp_accounts WHERE username = ?")) {
            sql.setString(1, username);
            try (var rows = sql.executeQuery()) {
                return rows.next() && MessageDigest.isEqual(rows.getBytes(2), hash(password, rows.getBytes(1)));
            }
        }
    }

    private static byte[] hash(String password, byte[] salt) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, 600_000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    void record(String user, String ip, String command, String path, long size, boolean complete)
            throws SQLException {
        try (var db = connect(); var sql = db.prepareStatement("""
                INSERT INTO ftp_transfers(username, client_ip, command, file_path, bytes, completed)
                VALUES (?, ?::inet, ?, ?, ?, ?)
                """)) {
            sql.setString(1, user);
            sql.setString(2, ip);
            sql.setString(3, command);
            sql.setString(4, path);
            sql.setLong(5, size);
            sql.setBoolean(6, complete);
            sql.executeUpdate();
        }
    }
}
