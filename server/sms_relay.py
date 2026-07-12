#!/usr/bin/env python3
import hashlib
import hmac
import json
import os
import re
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


DB_PATH = os.environ.get("SMS_DB_PATH", "/var/lib/sms-relay/messages.sqlite3")
MAX_BODY = 3500
WAKE_WORKER = threading.Event()


def connect_db():
    db = sqlite3.connect(DB_PATH, timeout=10)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA foreign_keys=ON")
    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS config (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            bot_token TEXT NOT NULL,
            whitelist TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS recipients (
            phone TEXT PRIMARY KEY,
            chat_id TEXT NOT NULL UNIQUE,
            user_id TEXT NOT NULL,
            added_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY,
            sender TEXT NOT NULL,
            body TEXT NOT NULL,
            sent_at INTEGER NOT NULL,
            sim_slot INTEGER NOT NULL DEFAULT -1,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS deliveries (
            message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
            chat_id TEXT NOT NULL,
            delivered_at INTEGER,
            attempts INTEGER NOT NULL DEFAULT 0,
            next_attempt INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (message_id, chat_id)
        );
        CREATE TABLE IF NOT EXISTS metadata (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        """
    )
    columns = {row[1] for row in db.execute("PRAGMA table_info(messages)")}
    if "sim_slot" not in columns:
        db.execute("ALTER TABLE messages ADD COLUMN sim_slot INTEGER NOT NULL DEFAULT -1")
    return db


def normalize_phone(value):
    digits = re.sub(r"\D", "", str(value))
    if len(digits) == 11 and digits.startswith("8"):
        digits = "7" + digits[1:]
    if not 8 <= len(digits) <= 15:
        raise ValueError("invalid phone")
    return "+" + digits


def configured(db):
    return db.execute("SELECT bot_token, whitelist FROM config WHERE id = 1").fetchone()


def telegram_call(token, method, payload=None, timeout=15):
    data = json.dumps(payload or {}).encode("utf-8")
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/{method}",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        result = json.loads(response.read())
    if not result.get("ok"):
        raise RuntimeError("Telegram rejected request")
    return result.get("result")


def send_bot_message(token, chat_id, text, request_contact=False):
    payload = {"chat_id": chat_id, "text": text}
    if request_contact:
        payload["reply_markup"] = {
            "keyboard": [[{"text": "Share my phone number", "request_contact": True}]],
            "resize_keyboard": True,
            "one_time_keyboard": True,
        }
    telegram_call(token, "sendMessage", payload)


def process_update(token, update):
    message = update.get("message") or {}
    chat_id = str((message.get("chat") or {}).get("id", ""))
    user_id = str((message.get("from") or {}).get("id", ""))
    if not chat_id or not user_id:
        return

    contact = message.get("contact")
    if contact:
        contact_user = str(contact.get("user_id", ""))
        if not contact_user or contact_user != user_id:
            send_bot_message(token, chat_id, "Please share your own number using the button.", True)
            return
        try:
            phone = normalize_phone(contact.get("phone_number", ""))
        except ValueError:
            send_bot_message(token, chat_id, "The phone number is invalid.", True)
            return
        with connect_db() as db:
            row = configured(db)
            routes = json.loads(row[1]) if row else {}
            allowed = {number for numbers in routes.values() for number in numbers}
            if phone in allowed:
                db.execute("DELETE FROM recipients WHERE phone = ? OR chat_id = ?", (phone, chat_id))
                db.execute(
                    "INSERT INTO recipients (phone, chat_id, user_id, added_at) VALUES (?, ?, ?, ?)",
                    (phone, chat_id, user_id, int(time.time())),
                )
                send_bot_message(token, chat_id, "Access granted. New SMS will arrive here.")
            else:
                db.execute("DELETE FROM recipients WHERE chat_id = ?", (chat_id,))
                send_bot_message(token, chat_id, "This phone number is not on the allowlist.")
        return

    send_bot_message(
        token,
        chat_id,
        "To receive SMS, confirm your phone number.",
        True,
    )


def bot_worker():
    while True:
        try:
            with connect_db() as db:
                row = configured(db)
                offset_row = db.execute(
                    "SELECT value FROM metadata WHERE key = 'telegram_offset'"
                ).fetchone()
            if not row:
                WAKE_WORKER.wait(10)
                WAKE_WORKER.clear()
                continue
            token = row[0]
            offset = int(offset_row[0]) if offset_row else 0
            updates = telegram_call(
                token,
                "getUpdates",
                {"offset": offset, "timeout": 25, "allowed_updates": ["message"]},
                timeout=35,
            )
            for update in updates:
                process_update(token, update)
                offset = max(offset, int(update["update_id"]) + 1)
                with connect_db() as db:
                    db.execute(
                        "INSERT INTO metadata (key, value) VALUES ('telegram_offset', ?) "
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                        (str(offset),),
                    )
        except (OSError, RuntimeError, ValueError, json.JSONDecodeError, urllib.error.URLError):
            time.sleep(5)


def delivery_worker():
    while True:
        now = int(time.time())
        with connect_db() as db:
            db.execute(
                "DELETE FROM messages WHERE created_at < ? AND NOT EXISTS "
                "(SELECT 1 FROM deliveries WHERE deliveries.message_id = messages.id "
                "AND delivered_at IS NULL)",
                (now - 7 * 24 * 60 * 60,),
            )
            row = db.execute(
                "SELECT d.message_id, d.chat_id, d.attempts, m.sender, m.body, m.sent_at, "
                "m.sim_slot, c.bot_token "
                "FROM deliveries d JOIN messages m ON m.id = d.message_id "
                "JOIN config c ON c.id = 1 WHERE d.delivered_at IS NULL AND d.next_attempt <= ? "
                "ORDER BY m.created_at LIMIT 1",
                (now,),
            ).fetchone()
        if row is None:
            WAKE_WORKER.wait(30)
            WAKE_WORKER.clear()
            continue

        message_id, chat_id, attempts, sender, body, sent_at, sim_slot, token = row
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime(sent_at / 1000))
        text = f"New SMS (SIM {sim_slot + 1})\nFrom: {sender}\nTime: {timestamp}\n\n{body}"
        try:
            send_bot_message(token, chat_id, text)
            with connect_db() as db:
                db.execute(
                    "UPDATE deliveries SET delivered_at = ? WHERE message_id = ? AND chat_id = ?",
                    (int(time.time()), message_id, chat_id),
                )
                pending = db.execute(
                    "SELECT 1 FROM deliveries WHERE message_id = ? AND delivered_at IS NULL",
                    (message_id,),
                ).fetchone()
                if not pending:
                    db.execute(
                        "UPDATE messages SET sender = '', body = '', sent_at = 0 WHERE id = ?",
                        (message_id,),
                    )
        except (OSError, RuntimeError, ValueError, json.JSONDecodeError, urllib.error.URLError):
            delay = min(3600, 30 * (2 ** min(attempts, 7)))
            with connect_db() as db:
                db.execute(
                    "UPDATE deliveries SET attempts = attempts + 1, next_attempt = ? "
                    "WHERE message_id = ? AND chat_id = ?",
                    (int(time.time()) + delay, message_id, chat_id),
                )


class SmsHandler(BaseHTTPRequestHandler):
    server_version = "SmsRelay/2.0"

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 2 or length > 24576:
                raise ValueError("invalid length")
            payload = json.loads(self.rfile.read(length))
        except (TypeError, ValueError, json.JSONDecodeError):
            return self.respond(400, {"ok": False, "error": "invalid_request"})

        supplied = self.headers.get("Authorization", "")
        if not supplied.startswith("Bearer "):
            return self.respond(401, {"ok": False, "error": "unauthorized"})
        token = supplied[7:]

        if self.path == "/configure":
            return self.configure(token, payload)
        if self.path == "/sms":
            return self.receive_sms(token, payload)
        return self.respond(404, {"ok": False})

    def configure(self, token, payload):
        try:
            raw_routes = payload["routes"]
            routes = {}
            for slot in (0, 1):
                phones = sorted({normalize_phone(value) for value in raw_routes.get(str(slot), [])})
                if len(phones) > 20:
                    raise ValueError("invalid whitelist")
                routes[str(slot)] = phones
            all_phones = sorted({number for numbers in routes.values() for number in numbers})
            if not all_phones:
                raise ValueError("empty whitelist")
            telegram_call(token, "getMe")
        except (KeyError, TypeError, ValueError, RuntimeError, json.JSONDecodeError,
                urllib.error.URLError):
            return self.respond(400, {"ok": False, "error": "invalid_configuration"})

        with connect_db() as db:
            current = configured(db)
            if current and not hmac.compare_digest(current[0], token):
                return self.respond(403, {"ok": False, "error": "wrong_bot_token"})
            db.execute(
                "INSERT INTO config (id, bot_token, whitelist, updated_at) VALUES (1, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET whitelist = excluded.whitelist, "
                "updated_at = excluded.updated_at",
                (token, json.dumps(routes), int(time.time())),
            )
            placeholders = ",".join("?" for _ in all_phones)
            db.execute(
                f"DELETE FROM recipients WHERE phone NOT IN ({placeholders})", all_phones
            )
        WAKE_WORKER.set()
        return self.respond(200, {"ok": True, "whitelist_count": len(all_phones)})

    def receive_sms(self, token, payload):
        try:
            message_id = str(payload["id"])
            sender = str(payload["sender"])
            body = str(payload["body"])
            sent_at = int(payload["sent_at"])
            sim_slot = int(payload["sim_slot"])
            if not 16 <= len(message_id) <= 64 or not 1 <= len(sender) <= 128:
                raise ValueError("invalid sms")
            if len(body) > MAX_BODY or sent_at < 0 or sim_slot not in (0, 1):
                raise ValueError("invalid sms")
        except (KeyError, TypeError, ValueError):
            return self.respond(400, {"ok": False, "error": "invalid_sms"})

        with connect_db() as db:
            current = configured(db)
            if not current or not hmac.compare_digest(current[0], token):
                return self.respond(401, {"ok": False, "error": "unauthorized"})
            routes = json.loads(current[1])
            allowed = routes.get(str(sim_slot), [])
            if not allowed:
                return self.respond(409, {"ok": False, "error": "no_route_for_sim"})
            placeholders = ",".join("?" for _ in allowed)
            recipients = db.execute(
                f"SELECT chat_id FROM recipients WHERE phone IN ({placeholders})", allowed
            ).fetchall()
            if not recipients:
                return self.respond(409, {"ok": False, "error": "no_recipients"})
            db.execute(
                "INSERT OR IGNORE INTO messages "
                "(id, sender, body, sent_at, sim_slot, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                (message_id, sender, body, sent_at, sim_slot, int(time.time())),
            )
            db.executemany(
                "INSERT OR IGNORE INTO deliveries (message_id, chat_id) VALUES (?, ?)",
                [(message_id, row[0]) for row in recipients],
            )
        WAKE_WORKER.set()
        return self.respond(202, {"ok": True, "id": message_id})

    def do_GET(self):
        if self.path == "/health":
            return self.respond(200, {"ok": True})
        return self.respond(404, {"ok": False})

    def respond(self, status, payload):
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string, *args):
        return


if __name__ == "__main__":
    connect_db().close()
    threading.Thread(target=bot_worker, name="telegram", daemon=True).start()
    threading.Thread(target=delivery_worker, name="delivery", daemon=True).start()
    ThreadingHTTPServer(("127.0.0.1", 8766), SmsHandler).serve_forever()
