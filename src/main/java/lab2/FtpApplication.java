package lab2;

import java.awt.GridLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import org.apache.ftpserver.ConnectionConfigFactory;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authentication;
import org.apache.ftpserver.ftplet.AuthenticationFailedException;
import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpReply;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;

public final class FtpApplication {
    private static final Path ROOT = Path.of("files").toAbsolutePath().normalize();
    private static final System.Logger LOG = System.getLogger("Lab2FTP");

    public static void main(String[] args) throws InterruptedException {
        var username = new JTextField("postgres", 20);
        var dbPassword = new JPasswordField(20);
        var ftpPassword = new JPasswordField(20);
        var form = new JPanel(new GridLayout(0, 1, 0, 5));
        form.add(new JLabel("User PostgreSQL (127.0.0.1:5432)"));
        form.add(username);
        form.add(new JLabel("Mật khẩu PostgreSQL"));
        form.add(dbPassword);
        form.add(new JLabel("Mật khẩu FTP cho ftp_demo (để trống để giữ mật khẩu cũ)"));
        form.add(ftpPassword);

        while (JOptionPane.showConfirmDialog(null, form, "Lab 2 — FTP Server",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            char[] dbSecret = dbPassword.getPassword();
            char[] ftpSecret = ftpPassword.getPassword();
            FtpServer server;
            try {
                if (username.getText().isBlank()) {
                    throw new IllegalArgumentException("Hãy nhập user PostgreSQL.");
                }
                var database = new FtpDatabase(username.getText().strip(), new String(dbSecret));
                server = start(2121, database, new String(ftpSecret));
            } catch (IOException | SQLException | FtpException | IllegalArgumentException e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "Không khởi động được FTP",
                        JOptionPane.ERROR_MESSAGE);
                continue;
            } finally {
                Arrays.fill(dbSecret, '\0');
                Arrays.fill(ftpSecret, '\0');
                dbPassword.setText("");
                ftpPassword.setText("");
            }
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            System.out.println("FTP Server: TCP 2121 | Passive TCP 50000-50010 | User: ftp_demo");
            System.out.println("FTP root: " + ROOT);
            try {
                new CountDownLatch(1).await();
            } finally {
                server.stop();
            }
            return;
        }
    }

    static FtpServer start(int port, FtpDatabase database, String ftpPassword)
            throws IOException, SQLException, FtpException {
        database.setup(ftpPassword);
        Files.createDirectories(ROOT);

        var data = new DataConnectionConfigurationFactory();
        data.setPassivePorts("50000-50010");
        data.setPassiveIpCheck(true);
        data.setActiveIpCheck(true);
        var listener = new ListenerFactory();
        listener.setPort(port);
        listener.setDataConnectionConfiguration(data.createDataConnectionConfiguration());

        var limits = new ConnectionConfigFactory();
        limits.setAnonymousLoginEnabled(false);
        limits.setMaxLogins(20);
        limits.setMaxLoginFailures(3);

        var factory = new FtpServerFactory();
        factory.setConnectionConfig(limits.createConnectionConfig());
        factory.addListener("default", listener.createListener());
        factory.setUserManager(new DatabaseUsers(database));
        factory.setFtplets(new HashMap<>(Map.of("postgres-audit", new TransferAudit(database))));
        FtpServer server = factory.createServer();
        server.start();
        return server;
    }

    private static final class DatabaseUsers implements UserManager {
        private final FtpDatabase database;

        DatabaseUsers(FtpDatabase database) {
            this.database = database;
        }

        @Override
        public User getUserByName(String name) {
            if (!doesExist(name)) return null;
            var user = new BaseUser();
            user.setName(name);
            user.setEnabled(true);
            user.setHomeDirectory(ROOT.toString());
            user.setAuthorities(List.of(new WritePermission(), new ConcurrentLoginPermission(20, 6)));
            return user;
        }

        @Override public String[] getAllUserNames() { return new String[] {FtpDatabase.USERNAME}; }
        @Override public boolean doesExist(String name) { return FtpDatabase.USERNAME.equals(name); }
        @Override public String getAdminName() { return "admin"; }
        @Override public boolean isAdmin(String name) { return false; }

        @Override
        public void delete(String name) throws FtpException {
            throw new FtpException("Tài khoản được quản lý trong PostgreSQL.");
        }

        @Override
        public void save(User user) throws FtpException {
            throw new FtpException("Tài khoản được quản lý trong PostgreSQL.");
        }

        @Override
        public User authenticate(Authentication request) throws AuthenticationFailedException {
            if (request instanceof UsernamePasswordAuthentication login && doesExist(login.getUsername())) {
                try {
                    if (database.authenticate(login.getUsername(), login.getPassword())) {
                        return getUserByName(login.getUsername());
                    }
                } catch (SQLException e) {
                    LOG.log(System.Logger.Level.ERROR, "PostgreSQL authentication lookup failed", e);
                }
            }
            throw new AuthenticationFailedException("Invalid username or password.");
        }
    }

    private static final class TransferAudit extends DefaultFtplet {
        private final FtpDatabase database;

        TransferAudit(FtpDatabase database) {
            this.database = database;
        }

        @Override
        public FtpletResult afterCommand(FtpSession session, FtpRequest request, FtpReply reply)
                throws FtpException, IOException {
            String command = request.getCommand();
            int status = reply.getCode();
            if (session.getUser() == null || !("STOR".equals(command) || "RETR".equals(command))
                    || (status != 226 && status != 426) || !request.hasArgument()) {
                return FtpletResult.DEFAULT;
            }
            var file = session.getFileSystemView().getFile(request.getArgument());
            String path = file.getAbsolutePath();
            boolean complete = status == 226;
            try {
                database.record(session.getUser().getName(), session.getClientAddress().getAddress().getHostAddress(),
                        command, path.startsWith("/") ? path.substring(1) : path,
                        complete ? file.getSize() : 0, complete);
            } catch (SQLException e) {
                LOG.log(System.Logger.Level.ERROR, "FTP transfer could not be recorded in PostgreSQL", e);
            }
            return FtpletResult.DEFAULT;
        }
    }
}
