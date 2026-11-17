from ftplib import FTP, error_perm
from io import BytesIO
import os
import sys
import time
from uuid import uuid4

from ftp_server import DEMO_USER, database


def main(host, port):
    with FTP() as denied:
        denied.connect(host, port, timeout=10)
        try:
            denied.login(DEMO_USER, uuid4().hex)
        except error_perm as error:
            assert str(error).startswith("530"), error
        else:
            raise AssertionError("FTP accepted an invalid password")

    original = f"test-{uuid4().hex}.bin"
    renamed = f"verified-{original}"
    content = bytes(range(256)) * 8
    with FTP() as ftp:
        ftp.connect(host, port, timeout=10)
        ftp.login(DEMO_USER, os.environ["LAB2_FTP_PASSWORD"])
        ftp.set_pasv(True)
        try:
            ftp.storbinary(f"STOR {original}", BytesIO(content))
            assert original in ftp.nlst()
            ftp.rename(original, renamed)
            received = BytesIO()
            ftp.retrbinary(f"RETR {renamed}", received.write)
            assert received.getvalue() == content
            ftp.delete(renamed)
            assert renamed not in ftp.nlst()
        finally:
            for name in (original, renamed):
                if name in ftp.nlst():
                    ftp.delete(name)

    expected = {("STOR", original, len(content), True), ("RETR", renamed, len(content), True)}
    with database(autocommit=True) as db:
        for _ in range(20):
            transfers = db.execute(
                "SELECT command, file_path, bytes, completed FROM ftp_transfers "
                "WHERE username = %s AND file_path IN (%s, %s)",
                (DEMO_USER, original, renamed),
            ).fetchall()
            if expected.issubset(transfers):
                break
            time.sleep(0.1)
        else:
            raise AssertionError(f"Missing PostgreSQL transfer records: {transfers}")
    print(f"PASS {host}:{port} — authentication, passive LIST/STOR/RETR, rename, delete and PostgreSQL.")


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]))
