"""
End-to-end test of the §4.6 "Map your street" rules, run against the real
database but inside ONE transaction that is always rolled back — nothing it
creates (test users, containers, requests, checks) survives the run.

Each step calls the same RPCs the app calls, as the same Postgres role the app
uses (`authenticated`, with auth.uid() set to a test user), so what passes here
is what the phone will see.

    pip install "psycopg[binary]"
    python3 supabase/tests/map_your_street_test.py

Reads the connection string from supabase/.db-url (git-ignored).
"""
import json
import pathlib
import sys
import uuid

import psycopg

ROOT = pathlib.Path(__file__).resolve().parents[1]
URL = (ROOT / ".db-url").read_text().strip()

# A quiet spot in Skopje (Karpoš, west of the centre). Points are offset in
# metres from it; ~0.0000090 deg lat per metre, ~0.0000121 deg lon at 42°N.
BASE_LAT, BASE_LON = 42.00410, 21.39270


def at(east_m: float = 0, north_m: float = 0) -> tuple[float, float]:
    return BASE_LON + east_m * 0.0000121, BASE_LAT + north_m * 0.0000090


passed = 0
failed = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global passed, failed
    if ok:
        passed += 1
        print(f"  ok    {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


class Db:
    def __init__(self, conn: psycopg.Connection):
        self.conn = conn
        self.cur = conn.cursor()

    def as_postgres(self) -> None:
        self.cur.execute("reset role")
        self.cur.execute("select set_config('request.jwt.claims', '', true)")

    def as_user(self, uid: str | None) -> None:
        self.as_postgres()
        if uid is None:
            self.cur.execute("set local role anon")
            return
        claims = json.dumps({"sub": uid, "role": "authenticated"})
        self.cur.execute("select set_config('request.jwt.claims', %s, true)", (claims,))
        self.cur.execute("set local role authenticated")

    def one(self, sql: str, params=()):
        self.cur.execute(sql, params)
        return self.cur.fetchone()

    def expect(self, name: str, code: str, sql: str, params=()) -> None:
        """The call must fail with SQLSTATE [code]; the transaction survives."""
        self.cur.execute("savepoint s")
        try:
            self.cur.execute(sql, params)
            self.cur.fetchall()
            check(name, False, f"expected {code}, call succeeded")
        except psycopg.Error as e:
            check(name, e.sqlstate == code, f"expected {code}, got {e.sqlstate}: {e}")
        finally:
            self.cur.execute("rollback to savepoint s")


def add(db: Db, pin, kind="big", category="general", device=None, different=False):
    device = device or pin
    return (
        "select * from add_container(%s,%s,%s,%s,%s,%s,%s,%s)",
        (pin[0], pin[1], kind, category, "containers/test/x.jpg",
         device[0], device[1], different),
    )


def main() -> None:
    with psycopg.connect(URL, connect_timeout=20) as conn:
        db = Db(conn)
        try:
            run(db)
        finally:
            conn.rollback()  # always: this test leaves nothing behind
    print(f"\n{passed} passed, {failed} failed — all changes rolled back")
    sys.exit(1 if failed else 0)


def run(db: Db) -> None:
    # --- Test accounts (rolled back with everything else) ----------------------
    db.as_postgres()
    users = {name: str(uuid.uuid4()) for name in ("u1", "u2", "u3", "admin")}
    for name, uid in users.items():
        db.cur.execute(
            "insert into auth.users (id, email, aud, role) values (%s, %s, 'authenticated', 'authenticated')",
            (uid, f"kanta-test-{name}-{uid[:8]}@example.invalid"),
        )
    n = db.one("select count(*) from profiles where id = any(%s::uuid[])", (list(users.values()),))[0]
    check("profile row created for every new account", n == 4, f"got {n}")

    near = db.one(
        "select count(*) from containers where deleted_at is null and st_dwithin(geom, "
        "st_setsrid(st_makepoint(%s,%s),4326)::geography, 120)", at())[0]
    check("test spot has no existing containers within 120 m", near == 0, f"found {near}")

    u1, u2, u3, admin = users["u1"], users["u2"], users["u3"], users["admin"]

    print("\n§4.6 add limit — a normal account adds 2, the 3rd is blocked")
    db.as_user(u1)
    r = db.one("select * from my_add_allowance()")
    check("allowance starts at 2", r == (False, 0, 2), str(r))

    c1 = db.one(*add(db, at(0, 0)))
    check("1st add succeeds, unverified, 1 left", c1[2] is False and c1[3] == 1, str(c1))
    c2 = db.one(*add(db, at(40, 0), kind="small"))
    check("2nd add succeeds, 0 left", c2[2] is False and c2[3] == 0, str(c2))
    db.expect("3rd add is blocked (KA004)", "KA004", *add(db, at(80, 0)))

    r = db.one("select * from my_add_allowance()")
    check("allowance now 0", r[2] == 0, str(r))

    req = db.one(
        "select * from submit_container_request(%s,%s,'big','glass','container_requests/test/y.jpg','by the school')",
        at(80, 0),
    )
    check("…so it goes to review instead: request accepted, 2 left today", req[1] == 2, str(req))
    vis = db.one("select count(*) from containers where code is not null and st_dwithin(geom, "
                 "st_setsrid(st_makepoint(%s,%s),4326)::geography, 3)", at(80, 0))[0]
    check("the request does NOT appear on the map", vis == 0, f"found {vis}")

    for i in range(2):
        db.one("select * from submit_container_request(%s,%s,'small','general','container_requests/test/z.jpg','')",
               at(100 + i * 10, 0))
    db.expect("4th request in a day is blocked (KA006)", "KA006",
              "select * from submit_container_request(%s,%s,'small','general','p.jpg','')", at(130, 0))

    print("\n§4.6 add rules — 30 m, duplicate radius")
    db.as_user(u2)
    db.expect("pin 45 m from the phone is refused (KA005)", "KA005", *add(db, at(0, 60), device=at(0, 105)))
    db.expect("same kind within 10 m asks 'Is it this one?' (KA003)", "KA003", *add(db, at(4, 0)))
    dupe = db.one(*add(db, at(4, 0), different=True))
    check("…and 'No, it's a different one' is accepted", dupe[0] is not None, str(dupe))
    small_ok = db.one(*add(db, at(47, 0), kind="big", category="paper"))
    check("a BIG container 7 m from a SMALL can is not a duplicate", small_ok[0] is not None)

    print("\n§4.6 'Yes, it's here' — 50 m, never your own, verified at 2")
    db.as_user(u1)
    db.expect("the adder cannot confirm their own (KA009)", "KA009",
              "select * from confirm_container_exists(%s,%s,%s)", (c1[0], *at(0, 0)))
    rows = db.one("select can_confirm, is_mine from unverified_nearby(%s,%s,150) where id=%s", (*at(0, 0), c1[0]))
    check("unverified_nearby: adder sees is_mine, no button", rows == (False, True), str(rows))

    db.as_user(u2)
    rows = db.one("select can_confirm, is_mine from unverified_nearby(%s,%s,150) where id=%s", (*at(0, 0), c1[0]))
    check("unverified_nearby: neighbour within 50 m gets the button", rows == (True, False), str(rows))
    rows = db.one("select can_confirm from unverified_nearby(%s,%s,150) where id=%s", (*at(0, 70), c1[0]))
    check("unverified_nearby: 70 m away, no button", rows == (False,), str(rows))
    d = db.one("select added_by_me, i_confirmed, can_confirm_exists from container_detail(%s,%s,%s)",
               (c1[0], *at(0, 10)))
    check("container_detail: button offered at 10 m", d == (False, False, True), str(d))

    db.expect("confirming from 70 m is refused (KA010)", "KA010",
              "select * from confirm_container_exists(%s,%s,%s)", (c1[0], *at(0, 70)))
    r = db.one("select * from confirm_container_exists(%s,%s,%s)", (c1[0], *at(0, 10)))
    check("1st neighbour confirms: still unverified (1)", r[1] is False and r[2] == 1, str(r))
    db.expect("the same neighbour twice is refused (KA008)", "KA008",
              "select * from confirm_container_exists(%s,%s,%s)", (c1[0], *at(0, 10)))
    d = db.one("select can_confirm_exists, i_confirmed from container_detail(%s,%s,%s)", (c1[0], *at(0, 10)))
    check("…and the button is gone for them", d == (False, True), str(d))

    db.as_user(u3)
    r = db.one("select * from confirm_container_exists(%s,%s,%s)", (c1[0], *at(0, 5)))
    check("2nd neighbour confirms: VERIFIED", r[1] is True and r[2] == 2, str(r))
    db.as_postgres()
    trust = db.one("select trust_score from profiles where id=%s", (u1,))[0]
    check("adder gains +1 trust when verified", trust == 1, f"trust {trust}")

    db.as_user(None)
    d = db.one("select added_by_me, can_confirm_exists from container_detail(%s,%s,%s)", (c2[0], *at(40, 0)))
    check("anonymous browsing: detail works, no button", d == (False, False), str(d))

    print("\n§4.6 two 'missing' reports remove an unverified container")
    db.as_user(u2)
    r = db.one("select * from submit_report(%s,'missing','reports/test/m1.jpg','',%s,%s)", (c2[0], *at(40, 0)))
    check("1st 'missing' report filed", r[1] is False, str(r))
    db.as_postgres()
    gone = db.one("select deleted_at is not null from containers where id=%s", (c2[0],))[0]
    check("…one voice is not enough", gone is False)
    db.as_user(u3)
    r = db.one("select * from submit_report(%s,'missing','reports/test/m2.jpg','',%s,%s)", (c2[0], *at(40, 5)))
    check("2nd 'missing' becomes a me-too (§4.3 dedupe)", r[1] is True, str(r))
    db.as_postgres()
    gone = db.one("select deleted_at is not null from containers where id=%s", (c2[0],))[0]
    check("…and the container is removed from the map (0015 fix)", gone is True)
    trust = db.one("select trust_score from profiles where id=%s", (u1,))[0]
    check("adder loses 1 trust", trust == 0, f"trust {trust}")
    db.as_user(u2)
    n = db.one("select count(*) from containers_in_bbox(%s,%s,%s,%s) where id=%s",
               (BASE_LON - 0.01, BASE_LAT - 0.01, BASE_LON + 0.01, BASE_LAT + 0.01, c2[0]))[0]
    check("containers_in_bbox no longer returns it", n == 0)

    print("\nOther RPCs that shared the 0015 name-clash bug")
    db.as_user(u2)
    full = db.one("select * from submit_report(%s,'full','reports/test/f.jpg','',%s,%s)", (c1[0], *at(0, 0)))
    check("submit_report 'full'", full[0] is not None, str(full))
    db.as_user(u3)
    r = db.one("select * from confirm_report(%s,'me_too','')", (full[0],))
    check("confirm_report 'me_too' (Me too, still full)", r[0] == full[0], str(r))
    db.as_user(u1)
    s = db.one("select * from submit_suggestion(%s,%s,'no_container_nearby','','')", at(600, 0))
    check("submit_suggestion", s[0] is not None and s[2] == 1, str(s))
    db.as_user(u2)
    v = db.one("select * from vote_suggestion(%s)", (s[0],))
    check("vote_suggestion counts the vote", v[1] == 2, str(v))

    print("\n§5.4 my reports, impact, before/after (0017)")
    db.as_user(u3)
    r = db.one("select * from confirm_report(%s,'resolved','reports/test/after.jpg')", (full[0],))
    check("a resolved confirmation WITH a photo closes the report (§5.1)", r[1] == "resolved", str(r))
    db.as_user(u2)
    mine = db.one(
        "select container_code, container_kind, kind, state, resolved_photo_path, resolved_at is not null "
        "from my_reports(100) where report_id=%s", (full[0],))
    check("my_reports: resolved, with the after photo for before/after",
          mine is not None and mine[3] == "resolved" and mine[4] == "reports/test/after.jpg" and mine[5], str(mine))
    pos = db.one("select container_lon, container_lat from my_reports(100) where report_id=%s", (full[0],))
    check("my_reports: container position for 'tap → container on the map'",
          abs(pos[0] - at(0, 0)[0]) < 1e-6 and abs(pos[1] - at(0, 0)[1]) < 1e-6, str(pos))
    ids = [row[0] for row in db.cur.execute("select report_id from my_reports(100)").fetchall()]
    db.as_postgres()  # the raw table is not readable by users (0013), which is the point
    others = db.one("select count(*) from reports where id = any(%s::uuid[]) and user_id <> %s", (ids, u2))[0]
    check("my_reports: only the caller's own reports", others == 0 and len(ids) >= 2, f"{others} foreign of {len(ids)}")
    db.as_user(u2)
    imp = db.one("select * from my_impact()")
    check("my_impact: the reporter's emptied container counts", imp[0] == 1, str(imp))
    db.as_user(u3)
    imp = db.one("select * from my_impact()")
    check("my_impact: so does the neighbour who said 'me too'", imp[0] == 1, str(imp))

    print("\n§4.4 / §5.3 suggestions — merge radius, one vote per person (0016)")
    db.as_user(u3)  # has not voted on it (u2 did, above)
    near = db.one("select id, i_voted, round(distance_m) from open_suggestion_near(%s,%s)", at(620, 0))
    check("open_suggestion_near finds the one 20 m away", near is not None and near[0] == s[0], str(near))
    check("…and says the neighbour hasn't voted yet", near[1] is False)
    db.as_user(u1)
    mine = db.one("select i_voted from open_suggestion_near(%s,%s)", at(620, 0))
    check("…while its author has (the author's vote is automatic)", mine == (True,), str(mine))
    far = db.one("select count(*) from open_suggestion_near(%s,%s)", at(700, 0))[0]
    check("nothing within 50 m of a spot 100 m away", far == 0, f"got {far}")
    db.as_user(u2)
    db.expect("voting twice is refused (KA008)", "KA008", "select * from vote_suggestion(%s)", (s[0],))
    db.as_user(u3)
    merged = db.one("select * from submit_suggestion(%s,%s,'always_full','','')", at(610, 0))
    check("a new suggestion 10 m away merges into it, as a vote", merged[0] == s[0] and merged[1] is True and merged[2] == 3, str(merged))
    db.as_user(None)
    d = db.one("select votes, state, i_voted from suggestion_detail(%s)", (s[0],))
    check("suggestion_detail works without an account", d == (3, "open", False), str(d))
    db.as_user(u2)
    other = db.one("select * from submit_suggestion(%s,%s,'dumping_spot','','')", at(900, 0))
    db.as_postgres()
    db.cur.execute("update suggestions set state='sent' where id=%s", (other[0],))
    db.as_user(u1)
    db.expect("a suggestion already sent to the city takes no more votes (KA014)", "KA014",
              "select * from vote_suggestion(%s)", (other[0],))
    gone = db.one("select count(*) from open_suggestion_near(%s,%s)", at(900, 0))[0]
    check("…and is not offered as 'vote for this one instead'", gone == 0)

    db.as_user(u1)
    ms = db.cur.execute("select id, i_authored from my_suggestions(100)").fetchall()
    check("my_suggestions: the author sees their own, marked as theirs", (s[0], True) in ms, str(ms))
    db.as_user(u2)
    ms = dict(db.cur.execute("select id, i_authored from my_suggestions(100)").fetchall())
    check("my_suggestions: a voter sees it too, not as the author", ms.get(s[0]) is False, str(ms))
    check("my_suggestions: and their own", ms.get(other[0]) is True, str(ms))
    db.as_user(None)
    db.expect("my_suggestions needs an account", "42501", "select * from my_suggestions(100)")

    print("\n§4.6 admin — review queue, verify, delete, unlimited adds")
    db.as_user(admin)
    db.expect("a normal account cannot open the queue (KA007)", "KA007",
              "select * from admin_pending_requests(100)")
    db.as_postgres()
    db.cur.execute("update profiles set role='admin' where id=%s", (admin,))

    db.as_user(admin)
    queue = db.cur.execute("select id, kind, category, note from admin_pending_requests(100)").fetchall()
    mine = [q for q in queue if q[3] == "by the school"]
    check("the blocked 3rd container is in the queue", len(mine) == 1, str(queue[:3]))
    rev = db.one("select * from admin_review_request(%s, true)", (req[0],))
    check("approve → becomes a container", rev[1] == "approved" and rev[2] is not None, str(rev))
    db.as_postgres()
    v = db.one("select verified, kind, category, source from containers where id=%s", (rev[2],))
    check("…verified immediately, keeps kind/category", v == (True, "big", "glass", "user"), str(v))
    trust = db.one("select trust_score from profiles where id=%s", (u1,))[0]
    check("requester gains +1 trust", trust == 1, f"trust {trust}")

    db.as_user(admin)
    db.expect("reviewing the same request twice is refused (KA015)", "KA015",
              "select * from admin_review_request(%s, true)", (req[0],))
    other = db.cur.execute("select id from admin_pending_requests(100)").fetchall()
    rej = db.one("select * from admin_review_request(%s, false)", (other[0][0],))
    check("reject closes a request without a container", rej[1] == "rejected" and rej[2] is None, str(rej))

    unv = db.cur.execute("select id from admin_unverified_containers(200)").fetchall()
    ids = {row[0] for row in unv}
    check("unverified list includes the neighbour's duplicate", dupe[0] in ids)
    check("admin_verify_container", db.one("select admin_verify_container(%s)", (dupe[0],))[0] is True)
    check("admin_delete_container (soft)", db.one("select admin_delete_container(%s)", (small_ok[0],))[0] is True)
    db.as_postgres()
    s = db.one("select deleted_at is not null from containers where id=%s", (small_ok[0],))[0]
    check("…row kept, hidden from the map", s is True)

    db.as_user(admin)
    added = [db.one(*add(db, at(-60 - i * 25, -60))) for i in range(3)]
    check("admin adds 3 with no limit, all verified at once",
          all(a[2] is True for a in added), str(added))
    r = db.one("select * from my_add_allowance()")
    check("admin allowance is 'no limit'", r[0] is True)

    print("\n§4.6 area checks and the coverage map")
    db.as_user(u1)
    before = db.one("select should_prompt_area_check(%s,%s)", at(0, 0))[0]
    check("never-checked user in an unchecked area is prompted", before is True)
    db.one("select submit_area_check(%s,%s,'all_present')", at(0, 0))
    after = db.one("select should_prompt_area_check(%s,%s)", at(0, 0))[0]
    check("after checking: not prompted again", after is False)
    db.as_user(u2)
    here = db.one("select should_prompt_area_check(%s,%s)", at(20, 20))[0]
    check("a neighbour in the SAME area is not prompted (area recently checked)", here is False)
    far = db.one("select should_prompt_area_check(%s,%s)", at(3000, 0))[0]
    check("the neighbour 3 km away is prompted", far is True)

    db.as_user(admin)
    cov = db.one("select admin_coverage(90)")[0]
    check("admin_coverage returns GeoJSON with the check",
          cov["type"] == "FeatureCollection" and len(cov["features"]) >= 1, str(cov)[:120])
    db.as_user(u1)
    db.expect("coverage is admin-only (KA007)", "KA007", "select admin_coverage(90)")


if __name__ == "__main__":
    main()
