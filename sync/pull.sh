#!/bin/bash
# LifeOS — tire l'état du téléphone (USB ou adb wifi) et l'écrit dans le vault.
# Usage : ./pull.sh   (téléphone branché ou connecté en adb wifi)
set -e
cd "$(dirname "$0")"

adb exec-out run-as fr.bellenguez.lifeos cat shared_prefs/lifeos.xml > lifeos-state.xml

python3 - <<'EOF'
import re
from datetime import datetime, date

# Emploi du temps — doit rester le miroir de Schedule.kt
WEEKDAY = ["Routine matin","Boulot été","Repas + décompression","Prépa — maths / physique",
           "Gustos ou Insta","Sport","Repas","Lecture","Bilan du jour"]
SATURDAY = ["Routine matin + lessive 1","Prépa — bloc long","Running / séance longue",
            "Lessive 2","Gustos / Insta","Deep Work","Bilan du jour"]
SUNDAY = ["Routine matin","Natation + Running","Prépa","Lecture","Weekly Reset","Deep Work"]
GOALS = [("Séances sport",6,"séances"),("Prépa prépa",10,"h"),("Gustos / Insta",4,"sessions"),
         ("Lecture",4,"h"),("Jours complets",6,"jours")]

raw = open("lifeos-state.xml").read()
ints = {m[0]: int(m[1]) for m in re.findall(r'<int name="([^"]+)" value="([^"]+)"', raw)}
bools = {m[0]: m[1] == "true" for m in re.findall(r'<boolean name="([^"]+)" value="([^"]+)"', raw)}
strings = {m[0]: m[1] for m in re.findall(r'<string name="([^"]+)">([^<]*)</string>', raw)}
sets = {m[0]: re.findall(r"<string>([^<]*)</string>", m[1])
        for m in re.findall(r'<set name="([^"]+)"\s*/?>(.*?)(?:</set>|(?=<))', raw, re.S)}

today = date.today().isoformat()
dow = date.today().weekday()  # 0 = lundi
blocks = SUNDAY if dow == 6 else SATURDAY if dow == 5 else WEEKDAY
checks = sorted(int(x) for x in sets.get(f"checks-{today}", []))
goals_raw = strings.get(next((k for k in strings if k.startswith("goals-")), ""), "")
goals_done = [int(x) for x in goals_raw.split(",")] if goals_raw else [0]*len(GOALS)

lines = [
    "# État LifeOS (téléphone)",
    "",
    f"> Tiré le {datetime.now().strftime('%d/%m/%Y %H:%M')} via `sync/pull.sh`. Ne pas éditer — regénéré à chaque pull.",
    "",
    f"**Streak : {ints.get('streak', 0)} jours** · Journée validée : {'oui' if bools.get(f'valid-{today}') else 'non'}",
    "",
    "## Blocs du jour",
]
for i, name in enumerate(blocks):
    lines.append(f"- [{'x' if i in checks else ' '}] {name}")
lines += ["", "## Quotas semaine"]
for i, (name, quota, unit) in enumerate(GOALS):
    d = goals_done[i] if i < len(goals_done) else 0
    lines.append(f"- {name} : {d} / {quota} {unit}")
if bools.get("focus_active"):
    lines += ["", f"## ⚠ Focus actif — bloc #{ints.get('focus_idx')} · fuites : {ints.get('focus_escapes', 0)}"]
elif ints.get("focus_escapes"):
    lines += ["", f"Dernier focus : {ints.get('focus_escapes')} fuites."]

open("etat-telephone.md", "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
EOF
