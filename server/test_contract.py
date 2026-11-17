#!/usr/bin/env python3
"""Contract test for the FlowLite sync server. Usage: test_contract.py BASE_URL ENV_FILE"""
import json, sys, urllib.parse, time, uuid, urllib.request, urllib.error

base, env_file = sys.argv[1].rstrip("/"), sys.argv[2]
env = dict(l.strip().split("=", 1) for l in open(env_file) if "=" in l)

def call(method, path, body=None, token=None):
    req = urllib.request.Request(base + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **({"Authorization": token} if token else {})})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")

st, auth = call("POST", "/api/collections/users/auth-with-password",
                {"identity": env["FLOWLITE_EMAIL"], "password": env["FLOWLITE_PASSWORD"]})
assert st == 200, (st, auth)
tok, uid = auth["token"], auth["record"]["id"]

assert call("GET", "/api/collections/transcripts/records")[0] in (200,) and \
    call("GET", "/api/collections/transcripts/records")[1]["items"] == [], "anonymous list must be empty"
assert call("POST", "/api/collections/users/records", {"email": "x@y.z", "password": "12345678aA", "passwordConfirm": "12345678aA"})[0] in (400, 403), "self signup must fail"

rid, now = str(uuid.uuid4()), int(time.time() * 1000)
rec = {"id": rid, "owner": uid, "text": "Hello", "raw_text": "um hello", "device": "linux",
       "updated_at": now, "created_at": now, "deleted": False}
st, r = call("POST", "/api/collections/transcripts/records", rec, tok); assert st == 200 and r["id"] == rid, (st, r)
cursor = r["synced_at"]
assert call("POST", "/api/collections/transcripts/records", rec, tok)[0] == 400, "duplicate id create must fail"
assert call("POST", "/api/collections/transcripts/records", dict(rec, id=str(uuid.uuid4()), owner="someoneelse"), tok)[0] in (400, 403)

st, r = call("PATCH", f"/api/collections/transcripts/records/{rid}", {"text": "older", "updated_at": now - 1}, tok)
assert st == 409, ("stale update must be rejected", st, r)
time.sleep(0.01)
st, r = call("PATCH", f"/api/collections/transcripts/records/{rid}", {"text": "newer", "updated_at": now + 1, "deleted": True}, tok)
assert st == 200 and r["text"] == "newer" and r["deleted"] is True, (st, r)
assert call("DELETE", f"/api/collections/transcripts/records/{rid}", None, tok)[0] in (403, 404), "hard delete must be blocked"

q = urllib.parse.quote(f'synced_at > "{cursor}"')
st, r = call("GET", f"/api/collections/transcripts/records?filter={q}&sort=synced_at&perPage=500", None, tok)
assert st == 200 and [i["id"] for i in r["items"]] == [rid], ("cursor pull", r)

did = str(uuid.uuid5(uuid.NAMESPACE_URL, f"flowlite:contract-test:{rid}"))  # unique per run
st, r = call("POST", "/api/collections/dictionary/records", {"id": did, "owner": uid, "word": "Kubernetes", "updated_at": now, "deleted": True}, tok)
assert st == 200, (st, r)
st, r = call("POST", "/api/collections/settings/records", {"id": str(uuid.uuid4()), "owner": uid, "key": "contract_test", "value_json": "en", "updated_at": now, "deleted": True}, tok)
assert st == 200, (st, r)
print("contract OK")
