#!/usr/bin/env python3
"""Creates a neutral demo project + a Local Diff Review store for Marketplace screenshots (no personal data).

    python tools/screenshots/make_demo.py [--root C:/demo] [--sandbox-config <config_runIde dir>]

Creates under --root:
    orders-service/        a small git project (master + feature/order-retries)
    review-store/work/     a review group (git repo) holding one review with comments and progress
    review-notes.git       bare repo used as the group's remote (so the UI shows a synced group)
and points the sandbox IDE's settings at review-store and auto-opens the review for orders-service.
"""
import argparse, hashlib, json, os, shutil, subprocess, uuid
from datetime import datetime, timezone
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--root", default="C:/demo")
ap.add_argument("--sandbox-config", default=str(Path(__file__).resolve().parents[2] / ".intellijPlatform/sandbox/local-review-plugin/PY-2026.2.3/config_runIde"))
args = ap.parse_args()
ROOT = Path(args.root)


def git(cwd, *a, check=True):
    r = subprocess.run(["git", *a], cwd=cwd, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise SystemExit(f"git {' '.join(a)} failed in {cwd}: {r.stderr}")
    return r.stdout


def write(p, text):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8", newline="\n")


def sha1(s):
    return hashlib.sha1(s.encode()).hexdigest()


if ROOT.exists():
    def _rm(f, p, e):
        os.chmod(p, 0o700); f(p)
    shutil.rmtree(ROOT, onerror=_rm)
ROOT.mkdir(parents=True)

# ---- the demo project -----------------------------------------------------------------------------------------------
proj = ROOT / "orders-service"
git(ROOT, "init", "-q", "-b", "master", str(proj))
for k, v in (("user.name", "Dana Demo"), ("user.email", "dana@example.com"), ("commit.gpgsign", "false")):
    git(proj, "config", k, v)

write(proj / ".gitignore", ".idea/\n__pycache__/\n")
write(proj / "AGENTS.md", """# AGENTS.md

Guidance for AI coding agents working on orders-service.

## Conventions

- Use type hints on public functions.
- Keep all payment calls behind `PaymentGateway`.
- Add a test for every new service method.
""")
write(proj / "orders/__init__.py", "")
write(proj / "orders/payment.py", '''class GatewayError(Exception):
    pass


class PaymentGateway:
    def charge(self, customer_id, amount):
        raise NotImplementedError

    def refund(self, charge_id):
        raise NotImplementedError
''')
write(proj / "orders/service.py", '''import logging

from .payment import PaymentGateway

log = logging.getLogger(__name__)


class OrderService:
    def __init__(self, gateway: PaymentGateway, repo):
        self.gateway = gateway
        self.repo = repo

    def place_order(self, customer_id, items):
        total = sum(i.price * i.qty for i in items)
        charge = self.gateway.charge(customer_id, total)
        order = self.repo.save(customer_id, items, charge.id)
        log.info("order %s placed", order.id)
        return order

    def cancel_order(self, order_id):
        order = self.repo.get(order_id)
        self.gateway.refund(order.charge_id)
        self.repo.delete(order_id)
''')
write(proj / "orders/helpers.py", '''def money(cents):
    return f"{cents / 100:.2f}"


def slug(text):
    return text.lower().replace(" ", "-")
''')
write(proj / "orders/legacy_export.py", '''# Old CSV export, superseded by the reporting service.
def export_csv(orders):
    return "\\n".join(",".join(map(str, o)) for o in orders)
''')
write(proj / "tests/test_service.py", '''from orders.service import OrderService


def test_place_order_charges_total(fake_gateway, fake_repo):
    svc = OrderService(fake_gateway, fake_repo)
    order = svc.place_order("c1", [Item(price=500, qty=2)])
    assert fake_gateway.charged == 1000
    assert order.customer_id == "c1"
''')
git(proj, "add", "-A"); git(proj, "commit", "-q", "-m", "Initial order service")

git(proj, "checkout", "-q", "-b", "feature/order-retries")
write(proj / "orders/retry.py", '''import time

MAX_RETRIES = 5


def with_retries(fn, *args):
    for attempt in range(MAX_RETRIES):
        try:
            return fn(*args)
        except Exception:
            time.sleep(2 ** attempt)
    return None
''')
write(proj / "orders/service.py", '''import logging

from .payment import PaymentGateway
from .retry import with_retries

log = logging.getLogger(__name__)


class OrderService:
    def __init__(self, gateway: PaymentGateway, repo, cache=None):
        self.gateway = gateway
        self.repo = repo
        self.cache = cache or {}

    def place_order(self, customer_id, items):
        total = sum(i.price * i.qty for i in items)
        charge = with_retries(self.gateway.charge, customer_id, total)
        order = self.repo.save(customer_id, items, charge.id)
        self.cache[order.id] = order
        log.info("order %s placed", order.id)
        return order

    def cancel_order(self, order_id):
        order = self.cache.get(order_id) or self.repo.get(order_id)
        with_retries(self.gateway.refund, order.charge_id)
        self.repo.delete(order_id)
        self.cache.pop(order_id, None)
''')
git(proj, "rm", "-q", "orders/legacy_export.py")
git(proj, "mv", "orders/helpers.py", "orders/text.py")
with open(proj / "orders/text.py", "a", newline="\n") as f:
    f.write('\n\ndef truncate(text, n):\n    return text[:n]\n')
git(proj, "add", "-A"); git(proj, "commit", "-q", "-m", "Retry payment calls and cache orders")
git(proj, "checkout", "-q", "feature/order-retries")

# ---- the review store -----------------------------------------------------------------------------------------------
store = ROOT / "review-store"
grp = store / "work"
remote = ROOT / "review-notes.git"
git(ROOT, "init", "-q", "--bare", str(remote))
git(remote, "symbolic-ref", "HEAD", "refs/heads/main")
grp.mkdir(parents=True)
git(grp, "init", "-q"); git(grp, "symbolic-ref", "HEAD", "refs/heads/main")
for k, v in (("user.name", "Dana Demo"), ("user.email", "dana@example.com"), ("commit.gpgsign", "false"), ("core.autocrlf", "false")):
    git(grp, "config", k, v)
now = datetime.now(timezone.utc)
iso = lambda d: d.isoformat().replace("+00:00", "Z")
write(grp / "group.json", json.dumps({"schemaVersion": 1, "id": str(uuid.uuid4()), "createdAt": iso(now)}, indent=2) + "\n")
write(grp / ".gitignore", "*.tmp\n")

base = git(proj, "rev-parse", "master").strip()
head = git(proj, "rev-parse", "feature/order-retries").strip()
merge_base = git(proj, "merge-base", base, head).strip()
rid = now.astimezone().strftime("%Y%m%d-%H%M") + "-order-retries-vs-master"
rdir = grp / "orders-service" / rid
review = {
    "schemaVersion": 1, "id": rid, "title": "Order retries vs master",
    "repo": {"name": "orders-service", "lastKnownPath": str(proj).replace("/", "\\")},
    "baseRef": "master", "headRef": "feature/order-retries", "mergeBase": True,
    "revisions": [{"n": 1, "at": iso(now), "baseSha": base, "headSha": head, "effectiveBaseSha": merge_base}],
    "createdAt": iso(now), "updatedAt": iso(now),
}
write(rdir / "review.json", json.dumps(review, indent=2) + "\n")


def head_lines(path):
    return git(proj, "show", f"{head}:{path}").splitlines()


def lines_anchor(path, needle, n=1, side="HEAD"):
    ls = head_lines(path)
    i = next(k for k, l in enumerate(ls) if needle in l)
    snippet = ls[i:i + n]
    return {"scope": "LINES", "path": path, "side": side, "startLine": i + 1, "endLine": i + n, "snippet": snippet,
            "before": ls[max(0, i - 3):i], "after": ls[i + n:i + n + 3], "revision": 1}


def comment(kind, body, anchor, status="OPEN", rule=None, minutes=0):
    t = iso(now.replace(microsecond=0) - __import__("datetime").timedelta(minutes=60 - minutes))
    cid = str(uuid.uuid4())
    c = {"schemaVersion": 1, "id": cid, "kind": kind, "status": status, "body": body, "anchor": anchor,
         "outdated": False, "createdAt": t, "updatedAt": t}
    if rule:
        c["rule"] = rule
    write(rdir / "comments" / f"{cid}.json", json.dumps(c, indent=2) + "\n")


comment("CHANGE_REQUEST", "Catching every exception hides real payment failures. Catch GatewayError only, log it, and re-raise after the last attempt.",
        lines_anchor("orders/retry.py", "except Exception"), rule="Never catch a bare Exception without logging it and re-raising.", minutes=5)
comment("QUESTION", "Why exponential backoff with a blocking sleep here? This runs inside the request handler and will tie up a worker. Is a background retry intended?",
        lines_anchor("orders/retry.py", "time.sleep"), minutes=9)
comment("CHANGE_REQUEST", "Magic number: please move the retry count into settings so it can be tuned per environment.",
        lines_anchor("orders/retry.py", "MAX_RETRIES = 5"), rule="Put tunable numbers in settings, not module-level constants.", minutes=14)
comment("NOTE", "cache is never invalidated if the order is updated elsewhere. Fine for now, but worth a follow-up.",
        lines_anchor("orders/service.py", "self.cache[order.id] = order"), minutes=21)
comment("QUESTION", "Is removing the legacy CSV export covered by a migration note for the reporting team?",
        {"scope": "REVIEW", "side": "HEAD", "revision": 1}, minutes=30)
comment("NOTE", "Small and focused change, easy to follow.",
        {"scope": "FILE", "path": "orders/text.py", "side": "HEAD", "revision": 1}, minutes=40)
comment("CHANGE_REQUEST", "Add a test that the second retry succeeds after one GatewayError.",
        lines_anchor("orders/service.py", "with_retries(self.gateway.charge"), status="RESOLVED", minutes=45)

# progress: mark two files viewed (fingerprint = sha1 of "<status>|<old>|<new>|<oldBlob>|<newBlob>")
raw = git(proj, "diff", "--raw", "-z", "--no-abbrev", "-M", merge_base, head).split("\0")
i = 0
changes = []
while i < len(raw) and raw[i]:
    meta = raw[i].lstrip(":").split(" "); i += 1
    ob, nb, code = meta[2], meta[3], meta[4]
    z = "0" * 40
    ob = "" if ob == z else ob; nb = "" if nb == z else nb
    if code[0] in "RC":
        old, new = raw[i], raw[i + 1]; i += 2
    else:
        p = raw[i]; i += 1
        old, new = (None if code[0] == "A" else p), (None if code[0] == "D" else p)
    status = {"A": "ADDED", "D": "DELETED", "R": "RENAMED", "C": "COPIED", "T": "TYPE_CHANGED"}.get(code[0], "MODIFIED")
    changes.append((status, old, new, ob, nb))
for status, old, new, ob, nb in changes:
    path = new or old
    if path in ("orders/legacy_export.py", "orders/text.py"):
        # Kotlin prints a missing path as "null" in the fingerprint string.
        fp = sha1(f"{status}|{old if old is not None else 'null'}|{new if new is not None else 'null'}|{ob}|{nb}")
        key = sha1(path)[:16]
        write(rdir / "progress" / f"{key}.json", json.dumps({"schemaVersion": 1, "path": path, "viewedFingerprint": fp, "viewedHunks": [], "updatedAt": iso(now)}, indent=2) + "\n")
write(rdir / ".gitignore", "*.tmp\n")

git(grp, "add", "-A"); git(grp, "commit", "-q", "-m", "Create group 'work' with demo review")
git(grp, "remote", "add", "origin", str(remote))
git(grp, "push", "-q", "origin", "main")

# ---- point the sandbox IDE at the store and auto-open the review --------------------------------------------------------
cfg = Path(args.sandbox_config)
write(cfg / "options/local-review.xml",
      f'<application>\n  <component name="LocalReviewSettings">\n    <option name="storeRoot" value="{str(store).replace(chr(92), "/")}" />\n  </component>\n</application>\n')
key = sha1(str(proj).replace("\\", "/"))[:16]
write(store / ".state/last-opened" / f"{key}.txt", str(rdir))
print("demo project :", proj)
print("review store :", store)
print("review dir   :", rdir)
print("sandbox cfg  :", cfg)
