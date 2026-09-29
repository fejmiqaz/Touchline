"""Scheduled Neon export and encrypted email delivery. No third-party Python packages."""
import hashlib
import os
from pathlib import Path
import smtplib
import ssl
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from email.message import EmailMessage

OUTPUT = Path("backups")
MAX_ATTACHMENT_BYTES = 18 * 1024 * 1024  # Leave room for base64 and MIME overhead.


def required(name):
    value = os.environ.get(name, "")
    if not value.strip():
        raise ValueError(f"Missing configuration: {name}")
    return value


def run_url():
    return (f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/"
            f"{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/"
            f"{os.environ.get('GITHUB_RUN_ID', '')}")


def docker_command(*command):
    # Pass environment variable names only: passwords never appear in process arguments.
    result = ["docker", "run", "--rm", "-i"]
    for name in ("PGHOST", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSSLMODE", "PGCONNECT_TIMEOUT"):
        result += ["--env", name]
    return result + [os.environ.get("POSTGRES_IMAGE", "postgres:18"), *command]


def backup():
    for name in ("PGHOST", "PGDATABASE", "PGUSER", "PGPASSWORD"):
        required(name)
    passphrase = required("BACKUP_PASSPHRASE")
    if len(passphrase) < 20 or "\n" in passphrase or "\r" in passphrase:
        raise ValueError("BACKUP_PASSPHRASE must have at least 20 characters and occupy one line.")
    OUTPUT.mkdir(exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    encrypted = OUTPUT / f"touchline-{stamp}.dump.gpg"
    partial = encrypted.with_suffix(".partial")
    try:
        with tempfile.TemporaryDirectory(prefix="touchline-backup-") as temporary:
            dump = Path(temporary) / "database.dump"
            with dump.open("wb") as handle:
                subprocess.run(docker_command("pg_dump", "--format=custom", "--no-owner", "--no-acl"),
                               stdout=handle, stderr=subprocess.PIPE, check=True, timeout=900)
            if dump.stat().st_size == 0:
                raise ValueError("Database export was empty.")
            # Archive structure check only; a full restore drill remains necessary.
            with dump.open("rb") as handle:
                subprocess.run(docker_command("pg_restore", "--list"), stdin=handle,
                               stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True, timeout=120)
            subprocess.run(["gpg", "--batch", "--yes", "--pinentry-mode", "loopback",
                            "--passphrase-fd", "0", "--symmetric", "--cipher-algo", "AES256",
                            "--output", str(partial), str(dump)],
                           input=passphrase.encode("utf-8"), stdout=subprocess.DEVNULL,
                           stderr=subprocess.PIPE, check=True, timeout=300)
            partial.replace(encrypted)
    finally:
        partial.unlink(missing_ok=True)
    print("Database export validated and encrypted. Plaintext temporary files removed.")


def message(attachment=None):
    email = EmailMessage()
    email["From"] = required("SMTP_USERNAME")
    email["To"] = required("BACKUP_EMAIL_TO")
    if attachment is None:
        email["Subject"] = "Touchline: database backup needs attention"
        email.set_content("The daily backup or its delivery failed. Do not assume a new backup exists.\n"
                          "If encryption completed, an encrypted artifact may be available in the run.\n"
                          f"Review the GitHub Actions run: {run_url()}\n")
        return email
    if attachment.stat().st_size > MAX_ATTACHMENT_BYTES:
        raise ValueError("Encrypted backup exceeds the 18 MiB email limit. Download the encrypted artifact from this run and configure larger backup storage.")
    data = attachment.read_bytes()
    email["Subject"] = "Touchline: daily encrypted database backup"
    email.set_content(
        "Attached is a PostgreSQL custom-format backup encrypted with your backup passphrase.\n"
        "Keep the passphrase separately; it is not included in this email.\n"
        f"File: {attachment.name}\nSHA-256: {hashlib.sha256(data).hexdigest()}\n"
        f"Run: {run_url()}\n"
        "Restore instructions: docs/database-backups.md in your repository.\n"
        "The archive structure was checked; this does not replace a full restore test.\n")
    email.add_attachment(data, maintype="application", subtype="octet-stream", filename=attachment.name)
    return email


def deliver(email):
    host = required("SMTP_HOST")
    port = int(os.environ.get("SMTP_PORT", "465"))
    username, password = required("SMTP_USERNAME"), required("SMTP_PASSWORD")
    context = ssl.create_default_context()
    if port == 465:
        client = smtplib.SMTP_SSL(host, port, timeout=60, context=context)
    elif port == 587:
        client = smtplib.SMTP(host, port, timeout=60)
    else:
        raise ValueError("Use SMTP port 465 (TLS) or 587 (STARTTLS).")
    with client:
        if port == 587:
            client.ehlo()
            client.starttls(context=context)
            client.ehlo()
        client.login(username, password)
        refused = client.send_message(email)
        if refused:
            raise RuntimeError("SMTP rejected one or more recipients.")
    print("Email accepted by the SMTP server. Inbox delivery is not guaranteed; check your mailbox.")


def main():
    mode = sys.argv[1] if len(sys.argv) == 2 else ""
    if mode == "backup":
        backup()
    elif mode == "send":
        files = list(OUTPUT.glob("*.gpg"))
        if len(files) != 1:
            raise ValueError("Expected exactly one encrypted backup.")
        deliver(message(files[0]))
    elif mode == "failure":
        deliver(message())
    else:
        raise ValueError("Usage: database_backup.py backup|send|failure")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # SMTP/subprocess exceptions can contain connection details. Never print credentials.
        if isinstance(error, ValueError):
            print(str(error), file=sys.stderr)
        else:
            print(f"Backup step failed ({type(error).__name__}); check service availability and configured secrets.", file=sys.stderr)
        sys.exit(1)
