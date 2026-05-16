"""
database.py — Rigel Browser SQLite database initialisation and access
"""

import sqlite3
from constants import DB_PATH
from crypto import encrypt, decrypt


def init_db():
    con = sqlite3.connect(DB_PATH)
    con.executescript("""
        CREATE TABLE IF NOT EXISTS passwords (
            id       INTEGER PRIMARY KEY AUTOINCREMENT,
            host     TEXT NOT NULL,
            username TEXT NOT NULL,
            password TEXT NOT NULL,
            created  INTEGER DEFAULT (strftime('%s','now'))
        );
        CREATE TABLE IF NOT EXISTS bookmarks (
            id      INTEGER PRIMARY KEY AUTOINCREMENT,
            url     TEXT UNIQUE NOT NULL,
            title   TEXT,
            created INTEGER DEFAULT (strftime('%s','now'))
        );
        CREATE TABLE IF NOT EXISTS history (
            id      INTEGER PRIMARY KEY AUTOINCREMENT,
            url     TEXT NOT NULL,
            title   TEXT,
            visited INTEGER DEFAULT (strftime('%s','now'))
        );
        CREATE TABLE IF NOT EXISTS downloads (
            id       INTEGER PRIMARY KEY AUTOINCREMENT,
            url      TEXT,
            filename TEXT,
            path     TEXT,
            size     INTEGER DEFAULT 0,
            done     INTEGER DEFAULT 0,
            started  INTEGER DEFAULT (strftime('%s','now'))
        );
    """)
    con.commit()
    con.close()


# ── Passwords ──────────────────────────────────────────────────────────────────

def get_credentials(host: str):
    try:
        con = sqlite3.connect(DB_PATH)
        rows = con.execute(
            "SELECT username,password FROM passwords WHERE host=? ORDER BY id DESC", (host,)
        ).fetchall()
        con.close()
        return [{"username": r[0], "password": decrypt(r[1])} for r in rows]
    except Exception:
        return []


def has_credential(host: str, username: str) -> bool:
    con = sqlite3.connect(DB_PATH)
    exists = con.execute(
        "SELECT 1 FROM passwords WHERE host=? AND username=?", (host, username)
    ).fetchone()
    con.close()
    return bool(exists)


def save_password(host: str, username: str, password: str):
    try:
        con = sqlite3.connect(DB_PATH)
        con.execute(
            "INSERT OR REPLACE INTO passwords (host,username,password) VALUES (?,?,?)",
            (host, username, encrypt(password)),
        )
        con.commit()
        con.close()
    except Exception:
        pass


def list_passwords():
    try:
        con = sqlite3.connect(DB_PATH)
        rows = con.execute(
            "SELECT id,host,username FROM passwords ORDER BY host"
        ).fetchall()
        con.close()
        return [{"id": r[0], "host": r[1], "username": r[2]} for r in rows]
    except Exception:
        return []


def delete_password(pw_id: int):
    try:
        con = sqlite3.connect(DB_PATH)
        con.execute("DELETE FROM passwords WHERE id=?", (pw_id,))
        con.commit()
        con.close()
    except Exception:
        pass


# ── Bookmarks ──────────────────────────────────────────────────────────────────

def save_bookmark(url: str, title: str):
    try:
        con = sqlite3.connect(DB_PATH)
        con.execute("INSERT OR IGNORE INTO bookmarks (url,title) VALUES (?,?)", (url, title))
        con.commit()
        con.close()
    except Exception:
        pass


def load_bookmarks():
    try:
        con = sqlite3.connect(DB_PATH)
        rows = con.execute(
            "SELECT url,title,created FROM bookmarks ORDER BY created DESC"
        ).fetchall()
        con.close()
        return [{"url": r[0], "title": r[1], "ts": r[2] * 1000} for r in rows]
    except Exception:
        return []


def delete_bookmark(url: str):
    try:
        con = sqlite3.connect(DB_PATH)
        con.execute("DELETE FROM bookmarks WHERE url=?", (url,))
        con.commit()
        con.close()
    except Exception:
        pass


# ── History ────────────────────────────────────────────────────────────────────

def save_history(url: str, title: str):
    try:
        con = sqlite3.connect(DB_PATH)
        con.execute("INSERT INTO history (url,title) VALUES (?,?)", (url, title))
        con.execute(
            "DELETE FROM history WHERE id NOT IN "
            "(SELECT id FROM history ORDER BY visited DESC LIMIT 5000)"
        )
        con.commit()
        con.close()
    except Exception:
        pass


def load_history():
    try:
        con = sqlite3.connect(DB_PATH)
        rows = con.execute(
            "SELECT url,title,visited FROM history ORDER BY visited DESC LIMIT 200"
        ).fetchall()
        con.close()
        return [{"url": r[0], "title": r[1], "visited": r[2]} for r in rows]
    except Exception:
        return []


def clear_history():
    try:
        con = sqlite3.connect(DB_PATH)
        con.execute("DELETE FROM history")
        con.commit()
        con.close()
    except Exception:
        pass


# ── Downloads ──────────────────────────────────────────────────────────────────

def create_download(url: str, filename: str, path: str) -> int:
    con = sqlite3.connect(DB_PATH)
    con.execute(
        "INSERT INTO downloads (url,filename,path,size,done) VALUES (?,?,?,0,0)",
        (url, filename, path),
    )
    dl_id = con.execute("SELECT last_insert_rowid()").fetchone()[0]
    con.commit()
    con.close()
    return dl_id


def update_download(dl_id: int, size: int, done: int):
    con = sqlite3.connect(DB_PATH)
    con.execute("UPDATE downloads SET size=?,done=? WHERE id=?", (size, done, dl_id))
    con.commit()
    con.close()


def update_download_progress(dl_id: int, done: int):
    con = sqlite3.connect(DB_PATH)
    con.execute("UPDATE downloads SET done=? WHERE id=?", (done, dl_id))
    con.commit()
    con.close()


def list_downloads():
    con = sqlite3.connect(DB_PATH)
    rows = con.execute(
        "SELECT id,url,filename,path,size,done FROM downloads ORDER BY started DESC LIMIT 50"
    ).fetchall()
    con.close()
    return [
        {"id": r[0], "url": r[1], "filename": r[2], "path": r[3], "size": r[4], "done": r[5]}
        for r in rows
    ]


def get_download(dl_id: int):
    con = sqlite3.connect(DB_PATH)
    row = con.execute("SELECT url,filename FROM downloads WHERE id=?", (dl_id,)).fetchone()
    con.close()
    return row
# ── Aliases expected by api.py ─────────────────────────────────────────────────
db_save_bookmark        = save_bookmark
db_load_bookmarks       = load_bookmarks
db_delete_bookmark      = delete_bookmark
db_save_history         = save_history
db_load_history         = load_history
db_clear_history        = clear_history
db_get_credentials      = get_credentials
db_save_password        = save_password
db_list_passwords       = list_passwords
db_delete_password      = delete_password
db_password_exists      = has_credential
db_insert_download      = create_download
db_update_download_size = update_download
db_update_download_done = update_download_progress
db_list_downloads       = list_downloads
db_get_download         = get_download
