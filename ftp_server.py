import argparse
import hashlib
import hmac
import logging
import os
from pathlib import Path

import psycopg
from psycopg import sql
from pyftpdlib.authorizers import AuthenticationFailed, DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import ThreadedFTPServer


ROOT = Path(__file__).resolve().parent / "files"
DB_NAME = "lab2_ftp"
DEMO_USER = "ftp_demo"
FTP_PORT = 2121
PASSIVE_PORTS = range(50000, 50011)
PBKDF2_ROUNDS = 600_000
LOG = logging.getLogger("lab2")


def database(**overrides):
    settings = {
        "host": "127.0.0.1",
        "port": 5432,
        "dbname": DB_NAME,
        "user": DB_NAME,
        "password": os.environ["LAB2_DB_PASSWORD"],
        "connect_timeout": 3,
    }
    return psycopg.connect(**(settings | overrides))


def password_hash(password, salt):
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PBKDF2_ROUNDS)


def provision():
    with database(
        dbname="postgres",
        user="postgres",
        password=os.environ["LAB2_PG_ADMIN_PASSWORD"],
        autocommit=True,
    ) as db:
        owner = db.execute(
            "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = %s", (DB_NAME,)
        ).fetchone()
        if owner and owner[0] != DB_NAME:
            raise RuntimeError("Database lab2_ftp đã có owner khác.")
        role = db.execute("SELECT 1 FROM pg_roles WHERE rolname = %s", (DB_NAME,)).fetchone()
        if not role:
            db.execute(
                sql.SQL("CREATE ROLE {} LOGIN PASSWORD {}").format(
                    sql.Identifier(DB_NAME), sql.Literal(os.environ["LAB2_DB_PASSWORD"])
                )
            )
        if not owner:
            db.execute(
                sql.SQL("CREATE DATABASE {} OWNER {}").format(
                    sql.Identifier(DB_NAME), sql.Identifier(DB_NAME)
                )
            )

    with database() as db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS ftp_accounts (
                username text PRIMARY KEY,
                salt bytea NOT NULL,
                password_hash bytea NOT NULL,
                created_at timestamptz NOT NULL DEFAULT now()
            )
        """)
        db.execute("""
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
        """)
        salt = os.urandom(16)
        db.execute(
            "INSERT INTO ftp_accounts (username, salt, password_hash) VALUES (%s, %s, %s) "
            "ON CONFLICT (username) DO NOTHING",
            (DEMO_USER, salt, password_hash(os.environ["LAB2_FTP_PASSWORD"], salt)),
        )
        salt, saved_hash = db.execute(
            "SELECT salt, password_hash FROM ftp_accounts WHERE username = %s", (DEMO_USER,)
        ).fetchone()
        if not hmac.compare_digest(saved_hash, password_hash(os.environ["LAB2_FTP_PASSWORD"], salt)):
            raise RuntimeError("Mật khẩu FTP lưu trên Windows khác với tài khoản trong database.")
    ROOT.mkdir(exist_ok=True)
    LOG.info("PostgreSQL đã sẵn sàng. Database: %s, thư mục FTP: %s", DB_NAME, ROOT)


class DatabaseAuthorizer(DummyAuthorizer):
    def validate_authentication(self, username, password, handler):
        if not self.has_user(username):
            raise AuthenticationFailed("Invalid username or password.")
        try:
            with database() as db:
                account = db.execute(
                    "SELECT salt, password_hash FROM ftp_accounts WHERE username = %s",
                    (username,),
                ).fetchone()
        except psycopg.Error:
            LOG.exception("PostgreSQL authentication lookup failed")
            raise AuthenticationFailed("Authentication temporarily unavailable.") from None
        if not account or not hmac.compare_digest(account[1], password_hash(password, account[0])):
            raise AuthenticationFailed("Invalid username or password.")


class LabFTPHandler(FTPHandler):
    banner = "Lab 2 FTP Server"
    passive_ports = PASSIVE_PORTS

    def log_transfer(self, cmd, filename, receive, completed, elapsed, bytes):
        super().log_transfer(cmd, filename, receive, completed, elapsed, bytes)
        ftp_path = Path(filename).resolve().relative_to(ROOT).as_posix()
        try:
            with database() as db:
                db.execute(
                    "INSERT INTO ftp_transfers "
                    "(username, client_ip, command, file_path, bytes, completed) "
                    "VALUES (%s, %s, %s, %s, %s, %s)",
                    (self.username, self.remote_ip, cmd, ftp_path, bytes, completed),
                )
        except psycopg.Error:
            LOG.exception("FTP transfer could not be recorded in PostgreSQL")


def serve(host, port):
    with database() as db:
        if not db.execute(
            "SELECT 1 FROM ftp_accounts WHERE username = %s", (DEMO_USER,)
        ).fetchone():
            raise RuntimeError("Chạy ./run.ps1 setup để tạo tài khoản FTP.")
        db.execute("SELECT id FROM ftp_transfers LIMIT 0")

    authorizer = DatabaseAuthorizer()
    authorizer.add_user(DEMO_USER, "", str(ROOT), perm="elrfdmw")
    LabFTPHandler.authorizer = authorizer
    server = ThreadedFTPServer((host, port), LabFTPHandler)
    server.max_cons = 20
    server.max_cons_per_ip = 6
    LOG.info("FTP: %s:%s | Passive TCP: 50000-50010", host, port)
    server.serve_forever()


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description="Lab 2 FTP Server")
    parser.add_argument("mode", choices=("setup", "server"))
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=FTP_PORT)
    args = parser.parse_args()
    if args.mode == "setup":
        provision()
    else:
        serve(args.host, args.port)


if __name__ == "__main__":
    main()
