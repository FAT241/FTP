package lab2;

import java.io.IOException;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

public final class FtpSmokeTest {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 32121;
        var database = new FtpDatabase("lab2_ftp", System.getenv("LAB2_DB_PASSWORD"));
        var server = FtpApplication.start(port, database, "");
        try {
            checkTransfer(host, port, database);
        } finally {
            server.stop();
        }
    }

    private static void checkTransfer(String host, int port, FtpDatabase database) throws Exception {
        String original = "test-" + UUID.randomUUID() + ".bin";
        String renamed = "verified-" + original;
        byte[] sent = new byte[2048];
        new SecureRandom().nextBytes(sent);
        try {
            var denied = FtpClientApplication.openSession(host, port, FtpDatabase.USERNAME, UUID.randomUUID().toString());
            denied.disconnect();
            throw new AssertionError("FTP accepted an incorrect password");
        } catch (IOException expected) {
            check(expected.getMessage().startsWith("530"), "reject wrong password");
        }

        var directory = Files.createTempDirectory("lab2-ftp-client-");
        var source = directory.resolve(original);
        var destination = directory.resolve(renamed);
        var ftp = FtpClientApplication.openSession(host, port, FtpDatabase.USERNAME, System.getenv("LAB2_FTP_PASSWORD"));
        try {
            Files.write(source, sent);
            FtpClientApplication.uploadFile(ftp, source);
            check(Arrays.asList(ftp.listNames()).contains(original), "NLST");
            check(ftp.rename(original, renamed), "rename");
            FtpClientApplication.downloadFile(ftp, renamed, destination);
            check(Arrays.equals(sent, Files.readAllBytes(destination)), "equal bytes");
            check(ftp.deleteFile(renamed), "delete");
            check(!Arrays.asList(ftp.listNames()).contains(renamed), "deleted from directory");
        } finally {
            try {
                if (ftp.isConnected()) {
                    try {
                        ftp.deleteFile(original);
                        ftp.deleteFile(renamed);
                        ftp.logout();
                    } finally {
                        ftp.disconnect();
                    }
                }
            } finally {
                Files.deleteIfExists(source);
                Files.deleteIfExists(destination);
                Files.deleteIfExists(directory);
            }
        }
        try (var db = database.connect(); var sql = db.prepareStatement("""
                SELECT count(*) FROM ftp_transfers WHERE completed AND bytes = ?
                AND ((command = 'STOR' AND file_path = ?) OR (command = 'RETR' AND file_path = ?))
                """)) {
            sql.setInt(1, sent.length);
            sql.setString(2, original);
            sql.setString(3, renamed);
            try (var rows = sql.executeQuery()) {
                check(rows.next() && rows.getInt(1) == 2, "PostgreSQL transfer records");
            }
        }
        System.out.println("PASS " + host + ":" + port + " — authentication, passive FTP and PostgreSQL.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
