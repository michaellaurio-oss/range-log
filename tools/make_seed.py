"""Builds app/src/personal/assets/seed.json from Michael's shooting records.

It goes in the personal source set only, so the share build (for friends) never ships this history.
"""
import json
from datetime import date

def d(s):
    m, dd, y = s.split('/')
    y = int(y); y = y + 2000 if y < 100 else y
    return (date(y, int(m), int(dd)) - date(1970, 1, 1)).days

guns = [
    (1, "SP01", "CZ 75 SP-01", "Cajun Gunworks upgrade (12/2024)"),
    (2, "PCR", "CZ 75D PCR", ""),
    (3, "1911", "Kimber 1911 TLE II .45", ""),
    (4, "G19.2", "Glock 19 Gen 2", ""),
    (5, "G19.3", "Glock 19 Gen 3", "Glockstore heavy tungsten guide rod assembly (Aug 2024)"),
    (6, "HCP", "Hellcat Pro", "Apex trigger upgrade (12/2024)"),
    (7, ".357", "Colt Trooper Mk III .357", ""),
    (8, ".38", ".38 Special", ""),
    (9, "Mossberg 500", "Mossberg 500 shotgun", ""),
    (10, "Mossberg 590", "Mossberg 590 shotgun", ""),
    (11, "Marlin", "Marlin lever action (.357 / .38)", ""),
    (12, "CZ 75B", "CZ 75B", ""),
]
G = {name: gid for gid, name, _, _ in guns}

# Each session: date, location, session notes, [ (gun, rounds or None, gun notes, [(rounds, yards, note)]) ]
S = [
 ("9/8/26", "", "", [
   ("Marlin", 100, "7 @ 10y (.357), 50 @ 20y (.38), 7 @ 10y (.357), 7 @ 10y (.357), 28 @ 20y (.357)",
    [(7, 10, ".357 Magnum"), (14, 15, ".357 Magnum"), (29, 20, ".357 Magnum"), (50, 20, ".38 Special")]),
 ]),
 ("9/1/26", "", "", [("Mossberg 500", 10, "", []), ("G19.3", 50, "", []), ("1911", 56, "", [])]),
 ("7/25/26", "BLM land, Nevada",
  "Shooting on BLM land in Nevada with Dusty.\nAlso shot: Mossberg 500 (clay), Marlin lever action, "
  "Ruger American (steel @ 175 yds), Sig 226, Sig 320, .22 bolt action.",
  [("SP01", 151, "", []), ("1911", 120, "", []), ("HCP", 30, "", [])]),
 ("5/19/26", "", "Challenge: clean hit on each bubble.\nTarget 1 (HCP & SP01): did it with 31 shots.\n"
  "Target 2 (SP01): left 2 open on 69 shots.",
  [("HCP", 61, "", []), ("SP01", 99, "", [])]),
 ("5/3/26", "", "Shooting with Bob.", [("G19.3", 120, "", []), ("SP01", 140, "", []), ("1911", 128, "", [])]),
 ("4/22/26", "", "", [
   ("Mossberg 500", 20, "#00 buck. First 2 shots @ 15y missed COMPLETELY. Aiming low to get these hits.", []),
   ("1911", 85, "", []),
   ("PCR", 100, "Practiced shooting half-cocked (DA).", []),
 ]),
 ("4/14/26", "", "", [
   ("HCP", 185, "Shooting pretty fast. On silhouette: body/body/head x 3, then shoot hostage w/ last shot.", []),
   ("SP01", 185, "Shooting pretty fast. On silhouette: body/body/head x 3, then shoot hostage w/ last shot.", []),
   ("Mossberg 500", 10, "#00 buck. Orig stock feels too long. Need to remember to cycle after shots.", [(10, 10, "")]),
 ]),
 ("9/13/25", "", "Recorded as two entries: SP01 (90), and SP01 (40) + G19.3 (80).",
  [("SP01", 130, "", []), ("G19.3", 80, "", [])]),
 ("8/21/25", "", "Zeroing red dot on HCP. Gave up, returned.", [
   ("1911", 78, "AWFUL. Too embarrassed to post pics.", [(39, 7.5, ""), (39, 10, "")]),
   ("HCP", 30, "", []),
 ]),
 ("8/6/25", "", "Not unhappy with the 17.5 yds.", [
   ("SP01", 330, "", [(20, 7.5, ""), (30, 10, ""), (30, 15, ""), (50, 17.5, ""),
                      (40, 5, "Warm-up at 5 & 7 yds (no picture)"), (30, 10, ""), (40, 15, ""),
                      (45, 12.5, ""), (45, 15, "")]),
 ]),
 ("7/22/25", "", "meh", [("1911", 78, "", []), ("PCR", 80, "", [])]),
 ("7/15/25", "", "So, despite appearances, G19 much more accurate at 10 than HCP.", [
   ("HCP", 80, "", [(30, 10, ""), (10, 15, "")]),
   ("G19.2", 80, "", [(20, 10, ""), (20, 15, "")]),
 ]),
 ("7/8/25", "", "90% of the 15yd hits on target were from last 2 (of 4) mags.",
  [("SP01", 80, "", []), ("PCR", 80, "", [])]),
 ("7/1/25", "", "", [("1911", None, "FTF solved by smacking slide forward.", [])]),
 ("5/12/25", "", "", [
   ("1911", None, "FTF with one of the extended 1911 mags.", []),
   ("PCR", None, "Trigger finger hurt on the PCR.", []),
 ]),
 ("4/6/25", "", "CCW certification. 10 shots each from 3, 5, 7 yards, both pistols. (I passed)", [
   ("G19.3", 30, "", [(10, 3, ""), (10, 5, ""), (10, 7, "")]),
   ("HCP", 30, "", [(10, 3, ""), (10, 5, ""), (10, 7, "")]),
 ]),
 ("3/18/25", "", "", [("G19.3", None, "", []), (".357", None, "", [])]),
 ("3/11/25", "", "", [("SP01", None, "", []), ("HCP", None, "", [])]),
 ("1/31/25", "", "Breathing makes a huge difference.", [
   ("G19.3", None, "", []),
   ("HCP", None, "Difference from 7A to 7B: right thumb stiff and off slide. Same on 7C, but not same results.", []),
 ]),
 ("1/10/25", "", "", [
   ("Mossberg 590", None, "Painful", []),
   ("HCP", None, "Fired fairly quickly", []),
   ("SP01", None, "Fired fairly quickly", []),
 ]),
 ("1/9/25", "", "***Don't try to strangle the guns with a deathgrip***\nOn HCP at 5yds (#3), I relaxed grip a bit "
  "and improved, applied to 7yd (#4) and DAMN.", [("HCP", None, "", []), ("G19.3", None, "", [])]),
 # Was 9/10/24 in the slides; Michael corrected it to 12/10/24 (the upgrades happened in 12/2024).
 ("12/10/24", "","HCP trigger upgrade. Cajun Gunworks upgrade. Cajun Gunworks is amazing.",
  [("HCP", 50, "", []), ("SP01", 50, "", [])]),
 ("11/15/24", "", "", [("HCP", 50, "", []), ("SP01", 50, "", [])]),
 ("11/9/24", "", "", [("G19.3", 80, "", []), ("HCP", 80, "", [])]),
 ("9/10/24", "", "20 each target. What the fuck happened @ 10 yards?!", [
   ("G19.3", 100, "FTE again. Removing tungsten guide rod.", []),
   ("SP01", 100, "", []),
 ]),
 ("8/28/24", "", "", [
   ("G19.3", 50, "FTE. New guide rod helps? Weight of light definitely helps.", []),
   ("SP01", None, "Disappointing", []),
 ]),
 ("8/21/24", "", "HCP: numbers indicate order of targets.", [
   ("HCP", 50, "Feels great in hand. Snappy as hell. Wedge grip (W).", [(50, 5, "")]),
   (".38", 30, "Target should sit right above sights. Impossible to control shooting DA.", []),
   ("SP01", 50, "Wedge grip works. Left hand tight, right more relaxed. Left arm locked straight, right not "
    "(helps keep sights in front of right eye, as a benefit).", []),
 ]),
 ("8/9/24", "", "", [("G19.3", 80, "50 + 30 shots", []), ("SP01", 80, "50 + 30 shots", [])]),
 ("7/24/24", "", "", [("SP01", 101, "50 + 51 shots. New sights, good. LOW AND LEFT but improving.", [(101, 15, "")])]),
 ("7/9/24", "", "", [("SP01", 104, "50 + 54 shots. Horrible sights. LOW AND LEFT.", [])]),
 ("5/8/24", "", "Loved the CZ (fit, low recoil, but horrible sights). Gonna be getting a CZ SP01 Tactical "
  "(edit: not CA compliant).", [("G19.3", 50, "LOW AND LEFT", []), ("CZ 75B", 50, "", [])]),
 ("4/24/24", "", "", [
   ("G19.3", None, "", []),
   (".357", None, "5yds. 2 shots, reset (10s), 2 shots…. Fucking loud, kinda terrifying.", []),
 ]),
 ("4/22/24", "", "", [("G19.3", None, "8yds", [])]),
 ("4/17/24", "", "", [("G19.2", 100, "FTE (1). Mantis: 81.4 avg, 96.9 max, 54.6 min.", [])]),
 ("4/15/24", "", "Lots of duds in the old ammo.", [("G19.3", None, "", [])]),
]

cleanings = [
 ("SP01", "8/6/25"), ("SP01", "5/4/26"), ("PCR", "8/6/25"),
 ("1911", "5/1/25"), ("1911", "5/6/26"), ("G19.2", "1/1/25"), ("G19.3", "5/1/25"),
 ("HCP", "5/1/25"), ("HCP", "4/14/26"), ("HCP", "8/4/26"),
 (".357", "1/1/25"), (".38", "1/1/25"),
]

out = {"format": "rangelog-backup", "version": 1, "guns": [], "sessions": [], "sessionGuns": [],
       "strings": [], "cleanings": [], "photos": []}
for gid, name, desc, notes in guns:
    out["guns"].append({"id": gid, "name": name, "description": desc, "notes": notes,
                        "cleanInterval": 500, "active": True})
sg_id = 0; st_id = 0
for i, (dt, loc, notes, entries) in enumerate(S, start=1):
    out["sessions"].append({"id": i, "date": d(dt), "location": loc, "notes": notes})
    for gun, rounds, gnotes, strings in entries:
        sg_id += 1
        out["sessionGuns"].append({"id": sg_id, "sessionId": i, "gunId": G[gun], "rounds": rounds, "notes": gnotes})
        for r, y, n in strings:
            st_id += 1
            out["strings"].append({"id": st_id, "sessionGunId": sg_id, "rounds": r, "yards": float(y), "notes": n})
for j, (gun, dt) in enumerate(cleanings, start=1):
    out["cleanings"].append({"id": j, "gunId": G[gun], "date": d(dt), "notes": ""})

import os
path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "personal", "assets", "seed.json")
json.dump(out, open(path, "w"), indent=2, ensure_ascii=False)
tot = sum(e["rounds"] or 0 for e in out["sessionGuns"])
print(len(out["sessions"]), "sessions,", len(out["sessionGuns"]), "gun entries,", tot, "rounds")
# rounds since last cleaning per gun, like the app computes
for gid, name, *_ in guns:
    lc = max([c["date"] for c in out["cleanings"] if c["gunId"] == gid], default=None)
    rows = [(e["rounds"], next(s["date"] for s in out["sessions"] if s["id"] == e["sessionId"]))
            for e in out["sessionGuns"] if e["gunId"] == gid]
    since = sum(r or 0 for r, dt in rows if lc is None or dt > lc)
    print(f"{name:14} total {sum(r or 0 for r,_ in rows):5}  since cleaning {since}")
