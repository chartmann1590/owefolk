import json
import os
import tempfile

from google.auth import load_credentials_from_file
from googleapiclient import discovery, errors

BASE = os.path.dirname(os.path.abspath(__file__))
PACKAGE = "com.charles.owefolk"

SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]


def key_path():
    env = os.environ.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON")
    if env:
        path = os.path.join(tempfile.gettempdir(), "owefolk-play-service-account.json")
        with open(path, "w") as f:
            f.write(env)
        return path
    key_file = os.environ.get("GOOGLE_PLAY_KEY_FILE")
    if key_file and os.path.exists(key_file):
        return key_file
    local = os.path.join(BASE, "..", "secrets", "google-play-service-account.json")
    if os.path.exists(local):
        return local
    raise SystemExit("No Play service account found (GOOGLE_PLAY_SERVICE_ACCOUNT_JSON, GOOGLE_PLAY_KEY_FILE, or secrets/google-play-service-account.json)")


def get_service():
    creds, _ = load_credentials_from_file(key_path(), scopes=SCOPES)
    return discovery.build("androidpublisher", "v3", credentials=creds)


def new_edit(service):
    return service.edits().insert(packageName=PACKAGE, body={}).execute()["id"]


def dump_json(obj):
    return json.dumps(obj, indent=2, default=str)