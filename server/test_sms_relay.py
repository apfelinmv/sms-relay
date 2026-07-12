import importlib.util
import os
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


if __name__ == "__main__":
    unittest.main()
