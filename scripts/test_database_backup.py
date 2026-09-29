import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, MagicMock
import database_backup as backup


class BackupTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {
            "SMTP_USERNAME": "sender@example.com", "SMTP_PASSWORD": "private-password",
            "BACKUP_EMAIL_TO": "recipient@example.com", "SMTP_HOST": "smtp.example.com",
            "SMTP_PORT": "465", "PGHOST": "example.neon.tech", "PGDATABASE": "neondb",
            "PGUSER": "owner", "PGPASSWORD": "database-password",
            "BACKUP_PASSPHRASE": "a-long-independent-backup-passphrase"}, clear=False)
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_email_contains_only_encrypted_attachment_and_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "sample.dump.gpg"
            file.write_bytes(b"encrypted contents")
            email = backup.message(file)
            attachments = list(email.iter_attachments())
            self.assertEqual(len(attachments), 1)
            self.assertEqual(attachments[0].get_filename(), file.name)
            self.assertIn("SHA-256:", email.get_body().get_content())
            self.assertNotIn(os.environ["BACKUP_PASSPHRASE"], str(email))

    def test_large_attachment_fails_before_delivery(self):
        file = MagicMock()
        file.stat.return_value.st_size = backup.MAX_ATTACHMENT_BYTES + 1
        with self.assertRaisesRegex(ValueError, "18 MiB"):
            backup.message(file)
        file.read_bytes.assert_not_called()

    def test_short_passphrase_rejected_before_export(self):
        os.environ["BACKUP_PASSPHRASE"] = "short"
        with patch.object(backup.subprocess, "run") as run:
            with self.assertRaises(ValueError):
                backup.backup()
            run.assert_not_called()

    def test_credentials_do_not_appear_in_docker_arguments(self):
        command = backup.docker_command("pg_dump")
        self.assertNotIn("database-password", command)
        self.assertIn("PGPASSWORD", command)

    def test_starttls_happens_before_login(self):
        os.environ["SMTP_PORT"] = "587"
        with patch.object(backup.smtplib, "SMTP") as smtp:
            client = smtp.return_value
            client.send_message.return_value = {}
            backup.deliver(backup.message())
            calls = [call[0] for call in client.mock_calls]
            self.assertLess(calls.index("starttls"), calls.index("login"))

    def test_dump_failure_does_not_encrypt_or_email(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(backup, "OUTPUT", Path(directory)), \
                patch.object(backup.subprocess, "run", side_effect=RuntimeError("export failed")) as run:
            with self.assertRaises(RuntimeError):
                backup.backup()
            self.assertEqual(run.call_count, 1)
            self.assertEqual(list(Path(directory).glob("*.gpg")), [])

    def test_success_encrypts_before_publishing_and_cleans_plaintext(self):
        plaintext = []
        def simulate(command, **kwargs):
            if "pg_dump" in command:
                kwargs["stdout"].write(b"PGDMP-test")
                plaintext.append(Path(kwargs["stdout"].name))
            elif command[0] == "gpg":
                self.assertNotIn(os.environ["BACKUP_PASSPHRASE"], command)
                Path(command[command.index("--output") + 1]).write_bytes(b"encrypted")
        with tempfile.TemporaryDirectory() as directory, patch.object(backup, "OUTPUT", Path(directory)), \
                patch.object(backup.subprocess, "run", side_effect=simulate) as run:
            backup.backup()
            self.assertEqual(run.call_count, 3)
            self.assertEqual(len(list(Path(directory).glob("*.gpg"))), 1)
            self.assertFalse(plaintext[0].exists())


if __name__ == "__main__":
    unittest.main()
