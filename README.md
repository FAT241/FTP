# Lab 2 — FTP Server và PostgreSQL

Máy chủ dùng Java 21 và Apache FtpServer. Tệp nằm trong thư mục `files/`. PostgreSQL lưu tài khoản FTP và nhật ký truyền tệp, gồm IP máy khách, lệnh, đường dẫn, số byte, thời điểm và trạng thái hoàn tất.

## Chạy trên máy server

1. Cài JDK 21, PostgreSQL và Extension Pack for Java trong VS Code.
2. Mở thư mục có `pom.xml` để VS Code tải thư viện Maven.
3. Mở [FtpApplication.java](src/main/java/lab2/FtpApplication.java), rồi bấm **Run Java** ngay trên hàm `main`.
4. Nhập user và mật khẩu PostgreSQL trên máy này. Dùng user `postgres` nếu cần để chương trình tạo database `lab2_ftp` lần đầu.
5. Đặt mật khẩu FTP cho user `ftp_demo`. Các lần sau, để trống ô FTP nếu muốn giữ mật khẩu hiện tại.
6. Sau khi cửa sổ nhập đóng, xem thông báo **FTP Server: TCP 2121** trong Terminal của VS Code.

## Chạy giao diện FTP Client

1. Giữ server đang chạy. Mở [FtpClientApplication.java](src/main/java/lab2/FtpClientApplication.java) trong VS Code và bấm **Run Java** trên hàm `main`.
2. Nhập `127.0.0.1` nếu client và server cùng máy. Trên máy khác, nhập IPv4 Wi-Fi hoặc Ethernet của máy server. Giữ cổng `2121` và tài khoản `ftp_demo`.
3. Nhập mật khẩu FTP đã đặt khi mở server rồi bấm **Kết nối**.
4. Bấm **Tải lên…** để chọn tệp từ máy. Chọn một tệp trong danh sách server rồi bấm **Tải xuống…** để lưu.

## Kết nối FileZilla từ máy bạn

Dùng **File → Site Manager → New Site** rồi chọn:

| Mục | Giá trị |
|---|---|
| Protocol | FTP — File Transfer Protocol |
| Host | IPv4 Wi-Fi/Ethernet của máy server từ `ipconfig`. Nếu cùng máy, dùng `127.0.0.1`. |
| Port | `2121` |
| Encryption | Only use plain FTP (insecure) |
| Logon Type | Normal |
| User | `ftp_demo` |
| Password | Mật khẩu FTP đã đặt khi chạy `main` |
| Transfer Settings | Passive |

FTP thường gửi thông tin đăng nhập không mã hóa. Dùng tài khoản demo trên Wi-Fi/Ethernet tin cậy. Nếu Windows Firewall chặn máy bạn, cho phép Java nhận kết nối TCP cổng `2121` và `50000–50010` trên mạng Private. Cổng `2121` điều khiển phiên FTP. Dải passive dùng cho danh sách thư mục và truyền tệp. Máy client có thể dùng giao diện Java của project hoặc FileZilla.

## Kiểm tra theo đề

1. Trong FTP Client, tải một tệp lên, làm mới danh sách, rồi tải tệp đó xuống.
2. Trong pgAdmin, mở **Databases → lab2_ftp → Query Tool** và chạy:

   ```sql
   SELECT username, client_ip, command, file_path, bytes, completed, recorded_at
   FROM ftp_transfers
   ORDER BY id DESC;
   ```

3. Kiểm tra có dòng `STOR` và `RETR` với `completed = true`. Có thể xem tài khoản với `SELECT username, created_at FROM ftp_accounts;`.
