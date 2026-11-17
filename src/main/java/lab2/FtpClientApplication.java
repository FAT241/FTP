package lab2;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

public final class FtpClientApplication extends JFrame {
    private final JTextField host = new JTextField("127.0.0.1", 16);
    private final JTextField port = new JTextField("2121", 5);
    private final JTextField user = new JTextField("ftp_demo", 12);
    private final JPasswordField password = new JPasswordField(14);
    private final JButton connect = new JButton("Kết nối");
    private final JButton disconnect = new JButton("Ngắt kết nối");
    private final JButton refresh = new JButton("Làm mới");
    private final JButton parent = new JButton("Thư mục cha");
    private final JButton open = new JButton("Mở thư mục");
    private final JButton upload = new JButton("Tải lên…");
    private final JButton download = new JButton("Tải xuống…");
    private final JLabel path = new JLabel("Tệp trên server");
    private final JLabel status = new JLabel("Nhập mật khẩu FTP để kết nối.");
    private final DefaultListModel<FTPFile> files = new DefaultListModel<>();
    private final JList<FTPFile> list = new JList<>(files);
    private FTPClient session;
    private boolean busy;

    private record Listing(String directory, FTPFile[] entries) {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FtpClientApplication().setVisible(true));
    }

    private FtpClientApplication() {
        super("Lab 2 — FTP Client");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(700, 460));
        var content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        setContentPane(content);

        var form = new JPanel(new GridBagLayout());
        addField(form, 0, 0, "Máy chủ", host);
        addField(form, 2, 0, "Cổng", port);
        addField(form, 0, 1, "Tài khoản", user);
        addField(form, 2, 1, "Mật khẩu FTP", password);
        var sessionActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        sessionActions.add(connect);
        sessionActions.add(disconnect);
        var top = new JPanel(new BorderLayout(0, 8));
        top.add(form, BorderLayout.CENTER);
        top.add(sessionActions, BorderLayout.SOUTH);
        content.add(top, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getAccessibleContext().setAccessibleName("Tệp và thư mục trên FTP Server");
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> owner, Object value, int row,
                    boolean selected, boolean hasFocus) {
                super.getListCellRendererComponent(owner, value, row, selected, hasFocus);
                FTPFile file = (FTPFile) value;
                setText(file.getName() + (file.isDirectory() ? " /" : "   ·   " + file.getSize() + " byte"));
                setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
                return this;
            }
        });
        var remote = new JPanel(new BorderLayout(0, 6));
        remote.add(path, BorderLayout.NORTH);
        remote.add(new JScrollPane(list), BorderLayout.CENTER);
        content.add(remote, BorderLayout.CENTER);

        var actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        for (JButton button : List.of(refresh, parent, open, upload, download)) actions.add(button);
        var bottom = new JPanel(new BorderLayout(0, 9));
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);

        connect.addActionListener(e -> connect());
        password.addActionListener(e -> { if (connect.isEnabled()) connect(); });
        disconnect.addActionListener(e -> disconnect());
        refresh.addActionListener(e -> refresh());
        parent.addActionListener(e -> changeDirectory(".."));
        open.addActionListener(e -> changeDirectory(list.getSelectedValue().getName()));
        upload.addActionListener(e -> chooseUpload());
        download.addActionListener(e -> chooseDownload());
        list.addListSelectionListener(e -> updateControls());
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && open.isEnabled()) open.doClick();
            }
        });
        updateControls();
        setSize(760, 500);
        setLocationRelativeTo(null);
    }

    private static void addField(JPanel panel, int x, int y, String label, JComponent field) {
        var caption = new JLabel(label);
        caption.setLabelFor(field);
        var cell = new GridBagConstraints();
        cell.gridx = x;
        cell.gridy = y;
        cell.insets = new Insets(3, 4, 3, 4);
        cell.anchor = GridBagConstraints.LINE_START;
        panel.add(caption, cell);
        cell.gridx++;
        cell.fill = GridBagConstraints.HORIZONTAL;
        cell.weightx = x == 0 ? 1 : 0.5;
        panel.add(field, cell);
    }

    private void connect() {
        String address = host.getText().strip();
        String username = user.getText().strip();
        char[] secret = password.getPassword();
        int serverPort;
        try {
            serverPort = Integer.parseInt(port.getText().strip());
            if (serverPort < 1 || serverPort > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Arrays.fill(secret, '\0');
            status.setText("Cổng phải nằm trong khoảng 1–65535.");
            port.requestFocusInWindow();
            return;
        }
        if (address.isEmpty() || username.isEmpty() || secret.length == 0) {
            Arrays.fill(secret, '\0');
            status.setText("Nhập máy chủ, tài khoản và mật khẩu FTP.");
            return;
        }
        password.setText("");
        perform("Đang kết nối…", () -> {
            try {
                return openSession(address, serverPort, username, new String(secret));
            } finally {
                Arrays.fill(secret, '\0');
            }
        }, client -> {
            session = client;
            refresh();
        });
    }

    static FTPClient openSession(String address, int port, String username, String password) throws IOException {
        rejectLineBreaks(username);
        rejectLineBreaks(password);
        var client = new FTPClient();
        client.setConnectTimeout(5000);
        client.setDefaultTimeout(10000);
        client.setDataTimeout(Duration.ofSeconds(20));
        client.setControlEncoding("UTF-8");
        try {
            client.connect(address, port);
            require(client, FTPReply.isPositiveCompletion(client.getReplyCode()));
            require(client, client.login(username, password));
            client.enterLocalPassiveMode();
            require(client, client.setFileType(FTP.BINARY_FILE_TYPE));
            return client;
        } catch (IOException e) {
            if (client.isConnected()) {
                try {
                    client.disconnect();
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
    }

    private void disconnect() {
        var client = session;
        session = null;
        files.clear();
        path.setText("Tệp trên server");
        perform("Đang ngắt kết nối…", () -> {
            try {
                client.logout();
            } finally {
                client.disconnect();
            }
            return "Đã ngắt kết nối.";
        }, status::setText);
    }

    private void refresh() {
        var client = session;
        perform("Đang đọc danh sách tệp…", () -> listing(client), result -> showListing(result, "Đã kết nối."));
    }

    private void changeDirectory(String directory) {
        var client = session;
        perform("Đang mở thư mục…", () -> {
            rejectLineBreaks(directory);
            require(client, client.changeWorkingDirectory(directory));
            return listing(client);
        }, result -> showListing(result, "Đã mở thư mục."));
    }

    private static Listing listing(FTPClient client) throws IOException {
        String directory = client.printWorkingDirectory();
        require(client, directory != null);
        FTPFile[] entries = client.listFiles();
        require(client, FTPReply.isPositiveCompletion(client.getReplyCode()));
        return new Listing(directory, entries);
    }

    private void showListing(Listing result, String message) {
        path.setText("Máy chủ: " + result.directory());
        files.clear();
        for (FTPFile file : result.entries()) {
            if (!file.getName().equals(".") && !file.getName().equals("..")) files.addElement(file);
        }
        status.setText(message);
    }

    private void chooseUpload() {
        var chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path source = chooser.getSelectedFile().toPath();
        String filename = source.getFileName().toString();
        if (Arrays.stream(files.toArray()).map(FTPFile.class::cast).anyMatch(file -> filename.equals(file.getName()))
                && !confirmOverwrite(filename)) return;
        var client = session;
        perform("Đang tải lên " + filename + "…", () -> {
            uploadFile(client, source);
            return listing(client);
        }, result -> showListing(result, "Đã tải lên " + filename));
    }

    private void chooseDownload() {
        String remote = list.getSelectedValue().getName();
        var chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File(remote.replace('\\', '/').replaceFirst("^.*/", "")));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path destination = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (Files.exists(destination) && !confirmOverwrite(destination.getFileName().toString())) return;
        var client = session;
        perform("Đang tải xuống " + remote + "…", () -> {
            downloadFile(client, remote, destination);
            return "Đã lưu vào " + destination;
        }, status::setText);
    }

    private boolean confirmOverwrite(String filename) {
        return JOptionPane.showConfirmDialog(this, "Ghi đè «" + filename + "»?", "Tệp đã tồn tại",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    static void uploadFile(FTPClient client, Path source) throws IOException {
        String filename = source.getFileName().toString();
        rejectLineBreaks(filename);
        try (var input = Files.newInputStream(source)) {
            require(client, client.storeFile(filename, input));
        }
    }

    static void downloadFile(FTPClient client, String remote, Path destination) throws IOException {
        rejectLineBreaks(remote);
        Path temp = Files.createTempFile(destination.toAbsolutePath().getParent(), ".ftp-", ".part");
        try {
            try (var output = Files.newOutputStream(temp)) {
                require(client, client.retrieveFile(remote, output));
            }
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void rejectLineBreaks(String argument) throws IOException {
        if (argument.indexOf('\r') >= 0 || argument.indexOf('\n') >= 0) {
            throw new IOException("Tài khoản, mật khẩu hoặc tên tệp chứa ký tự xuống dòng.");
        }
    }

    private static void require(FTPClient client, boolean success) throws IOException {
        if (!success) {
            String reply = client.getReplyString();
            throw new IOException(reply == null ? "FTP Server không phản hồi." : reply.strip());
        }
    }

    private <T> void perform(String pending, Callable<T> operation, Consumer<T> success) {
        busy = true;
        status.setText(pending);
        updateControls();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return operation.call(); }

            @Override protected void done() {
                busy = false;
                try {
                    success.accept(get());
                } catch (ExecutionException e) {
                    status.setText("Lỗi: " + e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    status.setText("Thao tác bị gián đoạn.");
                } finally {
                    updateControls();
                }
            }
        }.execute();
    }

    private void updateControls() {
        boolean ready = !busy && session != null;
        FTPFile selected = list.getSelectedValue();
        connect.setEnabled(!busy && session == null);
        for (JTextField field : List.of(host, port, user, password)) field.setEditable(connect.isEnabled());
        for (JButton button : List.of(disconnect, refresh, parent, upload)) button.setEnabled(ready);
        open.setEnabled(ready && selected != null && selected.isDirectory());
        download.setEnabled(ready && selected != null && selected.isFile());
    }
}
