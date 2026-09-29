"""abtest.py — pairwise 'which would you cast?' among the top candidates, both orders (Gemini 3.1 Pro)."""
import itertools, json, os, pathlib, sys
from judge import ask, aud, LAB
TOP = sys.argv[1:]
S = {"type": "object", "properties": {"winner": {"type": "string", "enum": ["A", "B"]}, "why": {"type": "string"}}, "required": ["winner", "why"]}
Q = ("Two candidate narrators read the same script for a ~100 s documentary-style explainer about iTantra, an offline multilingual "
     "voice walkie-talkie for India, shown to ISRO scientists and Smart India Hackathon judges. Which ONE would you cast as the narrator? "
     "Weigh credibility, warmth, naturalness, clarity of every word, and fit for an Indian national-technology audience. Answer A or B and why (max 30 words).")
wins = {k: 0 for k in TOP}; log = []
for a, b in itertools.permutations(TOP, 2):
    r = ask([{"type": "text", "text": "Candidate A:"}, aud(LAB / "cand" / f"{a}.wav"), {"type": "text", "text": "Candidate B:"}, aud(LAB / "cand" / f"{b}.wav"), {"type": "text", "text": Q}], S)
    w = a if r["winner"] == "A" else b; wins[w] += 1; log.append({"A": a, "B": b, "winner": w, "why": r["why"]})
    print(f"A={a} B={b} -> {w} | {r['why']}", flush=True)
print("WINS", wins); (LAB / "abtest.json").write_text(json.dumps({"wins": wins, "log": log}, indent=1))
