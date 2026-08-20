#!/usr/bin/env python3
"""Publish Owefolk to Google Play: update listing + upload AAB to a track and commit.

Env:
  GOOGLE_PLAY_SERVICE_ACCOUNT_JSON  - raw service account JSON (GitHub secret)
  GOOGLE_PLAY_KEY_FILE              - path to a service account JSON file (local usage)
Args:
  --aab <path>          signed release AAB to upload (optional)
  --track <name>        target track, default "internal"
  --status <status>     release status, default "draft"
  --notes "<text>"      release notes (en-US)
  --commit              commit the edit after staging
"""
import argparse
import json
import os
import sys

from googleapiclient import errors
from googleapiclient.http import MediaFileUpload

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from listing import APP_DETAILS, FULL_DESC, LISTING, SHORT_DESC
from play import PACKAGE, get_service, new_edit


def upload_aab(service, eid, aab):
    media = MediaFileUpload(aab, mimetype="application/octet-stream")
    res = service.edits().bundles().upload(
        packageName=PACKAGE, editId=eid, media_body=media).execute()
    vc = res.get("versionCode")
    print("UPLOADED bundle versionCode:", vc)
    return vc


def update_track(service, eid, track, vc, status, notes):
    release = {
        "name": track.title(),
        "status": status,
        "versionCodes": [vc],
    }
    if notes:
        release["releaseNotes"] = [{"language": "en-US", "text": notes}]
    body = {"releases": [release]}
    res = service.edits().tracks().update(
        packageName=PACKAGE, editId=eid, track=track, body=body).execute()
    print("TRACK UPDATED:", json.dumps(res, default=str)[:800])


def update_listing(service, eid):
    service.edits().details().update(
        packageName=PACKAGE, editId=eid, body=APP_DETAILS).execute()
    print("APP DETAILS OK")
    service.edits().listings().update(
        packageName=PACKAGE, editId=eid, language="en-US", body=LISTING).execute()
    print("LISTING OK:", LISTING["title"], "|", SHORT_DESC[:50])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--aab")
    ap.add_argument("--track", default="internal")
    ap.add_argument("--status", default="draft")
    ap.add_argument("--notes", default="")
    ap.add_argument("--skip-listing", action="store_true")
    ap.add_argument("--commit", action="store_true")
    args = ap.parse_args()

    service = get_service()
    eid = new_edit(service)
    print("EDIT:", eid)

    if not args.skip_listing:
        update_listing(service, eid)

    vc = None
    if args.aab:
        vc = upload_aab(service, eid, args.aab)
        update_track(service, eid, args.track, vc, args.status, args.notes)

    print("SHORT_DESC:", SHORT_DESC, f"({len(SHORT_DESC)} chars)")
    print("FULL_DESC:", len(FULL_DESC), "chars")

    if args.commit:
        try:
            res = service.edits().commit(packageName=PACKAGE, editId=eid).execute()
            print("COMMITTED. Edit id:", eid)
        except errors.HttpError as e:
            print("COMMIT FAILED:", e.resp.status, e.content.decode()[:1500])
            sys.exit(1)
    else:
        print("STAGED EDIT:", eid)
        print("Re-run with --commit to publish, or abandon the edit.")


if __name__ == "__main__":
    main()