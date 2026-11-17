# Lab 2 — FTP Server và PostgreSQL

Máy chủ dùng Python 3.11 và pyftpdlib. Tệp nằm trong thư mục `files/`. PostgreSQL lưu tài khoản FTP và nhật ký truyền tệp, gồm IP máy khách, lệnh, đường dẫn, số byte, thời điểm và trạng thái hoàn tất.

## Chạy trên máy server

Lần đầu, cài Python 3.11 và PostgreSQL. Mở PowerShell 7 tại thư mục dự án:

```powershell
./run.ps1 setup
./run.ps1 server
```

Bước setup dùng mật khẩu quản trị `postgres` để tạo database và role ứng dụng riêng `lab2_ftp`. PostgreSQL chạy nội bộ tại `127.0.0.1:5432`. Mật khẩu ứng dụng và tài khoản FTP được tạo riêng, lưu mã hóa theo tài khoản Windows trong `%LOCALAPPDATA%\Lab2FTP`. Chạy lệnh sau để xem tài khoản FTP dùng khi trình diễn:

```powershell
./run.ps1 account
```

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
| Password | Mật khẩu từ `./run.ps1 account` trên máy server |
| Transfer Settings | Passive |

FTP thường gửi thông tin đăng nhập không mã hóa. Dùng tài khoản demo trên Wi-Fi/Ethernet tin cậy. Nếu Windows Firewall chặn máy bạn, cho phép Python nhận kết nối TCP cổng `2121` và `50000–50010` trên mạng Private. Cổng `2121` điều khiển phiên FTP. Dải passive dùng cho danh sách thư mục và truyền tệp. Máy client chỉ cần FileZilla.

Nếu hai máy cùng mạng tin cậy và Windows đang để profile Public, chuyển Wi-Fi đó sang Private trong Windows Settings. Nếu cần tạo rule cho Lab 2, mở PowerShell bằng quyền Administrator trên máy server:

```powershell
New-NetFirewallRule -DisplayName 'Lab 2 FTP LAN' -Direction Inbound -Action Allow `
  -Protocol TCP -LocalPort 2121,'50000-50010' -Profile Private -RemoteAddress LocalSubnet
```

## Kiểm tra theo đề

1. Trên FileZilla, tải một tệp lên, làm mới danh sách, rồi tải tệp đó xuống.
2. Trong pgAdmin, mở **Databases → lab2_ftp → Query Tool** và chạy:

   ```sql
   SELECT username, client_ip, command, file_path, bytes, completed, recorded_at
   FROM ftp_transfers
   ORDER BY id DESC;
   ```

3. Kiểm tra có dòng `STOR` và `RETR` với `completed = true`. Có thể xem tài khoản với `SELECT username, created_at FROM ftp_accounts;`.

Khi server đang chạy, `./run.ps1 test` kiểm tra đăng nhập, passive, tải lên, tải xuống đúng byte, đổi tên, xóa tệp và bản ghi PostgreSQL. Lệnh này xóa tệp thử sau khi chạy và giữ lại nhật ký truyền trong database.
