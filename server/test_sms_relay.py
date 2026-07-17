import importlib.util
import json
import os
import sqlite3
import tempfile
import unittest


MODULE_PATH = os.path.join(os.path.dirname(__file__), "sms_relay.py")
SPEC = importlib.util.spec_from_file_location("sms_relay", MODULE_PATH)
relay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(relay)


class RecipientRevocationTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        relay.DB_PATH = os.path.join(self.temp_dir.name, "relay.sqlite3")
        with relay.connect_db() as db:
            db.execute(
                "INSERT INTO recipients (phone, chat_id, user_id, added_at) "
                "VALUES ('+10000000001', 'chat-1', 'user-1', 1), "
                "('+10000000002', 'chat-2', 'user-2', 1)"
            )
            db.execute(
                "INSERT INTO messages (id, sender, body, sent_at, sim_slot, created_at) "
                "VALUES ('message-0000001', 'Bank', 'code 1234', 1, 0, 1)"
            )
            db.execute(
                "INSERT INTO deliveries (message_id, chat_id) "
                "VALUES ('message-0000001', 'chat-1'), ('message-0000001', 'chat-2')"
            )

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_revocation_cancels_only_revoked_pending_delivery(self):
        with relay.connect_db() as db:
            relay.apply_recipient_allowlist(db, ['+10000000002'])
            recipients = db.execute("SELECT chat_id FROM recipients").fetchall()
            deliveries = db.execute("SELECT chat_id FROM deliveries").fetchall()
            body = db.execute("SELECT body FROM messages").fetchone()[0]

        self.assertEqual([('chat-2',)], recipients)
        self.assertEqual([('chat-2',)], deliveries)
        self.assertEqual("code 1234", body)

    def test_revoking_every_pending_recipient_erases_sms_body(self):
        with relay.connect_db() as db:
            relay.apply_recipient_allowlist(db, ['+19999999999'])
            pending = db.execute("SELECT count(*) FROM deliveries").fetchone()[0]
            sender, body, sent_at = db.execute(
                "SELECT sender, body, sent_at FROM messages"
            ).fetchone()

        self.assertEqual(0, pending)
        self.assertEqual(("", "", 0), (sender, body, sent_at))


class MultiDeviceRoutingTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        relay.DB_PATH = os.path.join(self.temp_dir.name, "relay.sqlite3")
        relay.connect_db().close()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_new_device_does_not_replace_legacy_routes(self):
        legacy = {"0": ["+10000000001"], "1": []}
        samsung = {"0": ["+10000000002"], "1": []}
        with relay.connect_db() as db:
            relay.save_configuration(db, "token", legacy)
            relay.save_configuration(
                db, "token", samsung, "android-samsung01", "Mom Samsung"
            )
            legacy_source = relay.routes_for_device(db, "")
            samsung_source = relay.routes_for_device(db, "android-samsung01")
            allowed = relay.allowed_phone_set(db)

        self.assertEqual(("Android", legacy), legacy_source)
        self.assertEqual(("Mom Samsung", samsung), samsung_source)
        self.assertEqual({"+10000000001", "+10000000002"}, allowed)

    def test_each_device_updates_only_its_own_routes(self):
        samsung = {"0": ["+10000000002"], "1": []}
        second = {"0": [], "1": ["+10000000003"]}
        samsung_updated = {"0": ["+10000000004"], "1": []}
        with relay.connect_db() as db:
            relay.save_configuration(
                db, "token", samsung, "android-samsung01", "Mom Samsung"
            )
            relay.save_configuration(
                db, "token", second, "android-second000", "Second phone"
            )
            relay.save_configuration(
                db, "token", samsung_updated, "android-samsung01", "Mom Samsung"
            )
            second_source = relay.routes_for_device(db, "android-second000")
            allowed = relay.allowed_phone_set(db)

        self.assertEqual(("Second phone", second), second_source)
        self.assertEqual({"+10000000003", "+10000000004"}, allowed)

    def test_claim_legacy_moves_routes_without_revoking_recipient(self):
        routes = {"0": ["+10000000001"], "1": []}
        with relay.connect_db() as db:
            relay.save_configuration(db, "token", routes)
            db.execute(
                "INSERT INTO recipients (phone, chat_id, user_id, added_at) "
                "VALUES ('+10000000001', 'chat-1', 'user-1', 1)"
            )
            relay.save_configuration(
                db, "token", routes, "android-tecno0001", "Tecno", True
            )
            legacy_routes = json.loads(relay.configured(db)[1])
            device_source = relay.routes_for_device(db, "android-tecno0001")
            recipients = db.execute("SELECT phone FROM recipients").fetchall()

        self.assertEqual({"0": [], "1": []}, legacy_routes)
        self.assertEqual(("Tecno", routes), device_source)
        self.assertEqual([("+10000000001",)], recipients)

    def test_updating_one_device_preserves_other_device_recipient(self):
        first = {"0": ["+10000000001"], "1": []}
        second = {"0": ["+10000000002"], "1": []}
        first_updated = {"0": ["+10000000003"], "1": []}
        with relay.connect_db() as db:
            relay.save_configuration(db, "token", first, "android-first0000", "First")
            relay.save_configuration(db, "token", second, "android-second000", "Second")
            db.execute(
                "INSERT INTO recipients (phone, chat_id, user_id, added_at) VALUES "
                "('+10000000001', 'chat-1', 'user-1', 1), "
                "('+10000000002', 'chat-2', 'user-2', 1)"
            )
            relay.save_configuration(
                db, "token", first_updated, "android-first0000", "First"
            )
            recipients = db.execute(
                "SELECT phone FROM recipients ORDER BY phone"
            ).fetchall()

        self.assertEqual([("+10000000002",)], recipients)

    def test_wrong_bot_token_cannot_add_device(self):
        routes = {"0": ["+10000000001"], "1": []}
        with relay.connect_db() as db:
            relay.save_configuration(db, "token", routes)
            with self.assertRaises(PermissionError):
                relay.save_configuration(
                    db, "other-token", routes, "android-attacker00", "Attacker"
                )
            devices = db.execute("SELECT count(*) FROM devices").fetchone()[0]

        self.assertEqual(0, devices)


class SchemaMigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        relay.DB_PATH = os.path.join(self.temp_dir.name, "relay.sqlite3")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_existing_messages_gain_device_columns_without_data_loss(self):
        with sqlite3.connect(relay.DB_PATH) as db:
            db.execute(
                "CREATE TABLE messages (id TEXT PRIMARY KEY, sender TEXT NOT NULL, "
                "body TEXT NOT NULL, sent_at INTEGER NOT NULL, "
                "sim_slot INTEGER NOT NULL DEFAULT -1, created_at INTEGER NOT NULL)"
            )
            db.execute(
                "INSERT INTO messages VALUES "
                "('message-legacy01', 'Bank', 'code', 1, 0, 1)"
            )

        with relay.connect_db() as db:
            columns = {row[1] for row in db.execute("PRAGMA table_info(messages)")}
            message = db.execute(
                "SELECT sender, body, device_id, device_name FROM messages"
            ).fetchone()

        self.assertIn("device_id", columns)
        self.assertIn("device_name", columns)
        self.assertEqual(("Bank", "code", "", ""), message)


class SmsEndpointCompatibilityTest(unittest.TestCase):
    class HandlerProbe:
        receive_sms = relay.SmsHandler.receive_sms

        def respond(self, status, payload):
            return status, payload

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        relay.DB_PATH = os.path.join(self.temp_dir.name, "relay.sqlite3")
        legacy = {"0": ["+10000000001"], "1": []}
        samsung = {"0": ["+10000000002"], "1": []}
        with relay.connect_db() as db:
            relay.save_configuration(db, "token", legacy)
            relay.save_configuration(
                db, "token", samsung, "android-samsung01", "Mom Samsung"
            )
            db.execute(
                "INSERT INTO recipients (phone, chat_id, user_id, added_at) VALUES "
                "('+10000000001', 'chat-1', 'user-1', 1), "
                "('+10000000002', 'chat-2', 'user-2', 1)"
            )
        self.handler = self.HandlerProbe()

    def tearDown(self):
        self.temp_dir.cleanup()

    def payload(self, message_id, device_id=None):
        payload = {
            "id": message_id,
            "sender": "Bank",
            "body": "code",
            "sent_at": 1,
            "sim_slot": 0,
        }
        if device_id is not None:
            payload["device_id"] = device_id
        return payload

    def test_legacy_tecno_request_still_uses_legacy_route(self):
        result = self.handler.receive_sms(
            "token", self.payload("message-legacy-0001")
        )
        with relay.connect_db() as db:
            message = db.execute(
                "SELECT device_id, device_name FROM messages"
            ).fetchone()
            deliveries = db.execute("SELECT chat_id FROM deliveries").fetchall()

        self.assertEqual((202, {"ok": True, "id": "message-legacy-0001"}), result)
        self.assertEqual(("", "Android"), message)
        self.assertEqual([("chat-1",)], deliveries)

    def test_new_samsung_request_uses_only_samsung_route(self):
        result = self.handler.receive_sms(
            "token", self.payload("message-samsung-001", "android-samsung01")
        )
        with relay.connect_db() as db:
            message = db.execute(
                "SELECT device_id, device_name FROM messages"
            ).fetchone()
            deliveries = db.execute("SELECT chat_id FROM deliveries").fetchall()

        self.assertEqual((202, {"ok": True, "id": "message-samsung-001"}), result)
        self.assertEqual(("android-samsung01", "Mom Samsung"), message)
        self.assertEqual([("chat-2",)], deliveries)

    def test_unknown_device_is_rejected(self):
        result = self.handler.receive_sms(
            "token", self.payload("message-unknown-001", "android-unknown000")
        )
        self.assertEqual((409, {"ok": False, "error": "unknown_device"}), result)


if __name__ == "__main__":
    unittest.main()
