#!/usr/bin/env python3
# Converts shared/burnpony_vectors.json into the flat key=value format read by
# BurnPonyVerify.java. Text-bearing fields are base64-wrapped so multi-line
# and non-ASCII content survives the line-based format.
import base64
import json
import sys

src = sys.argv[1] if len(sys.argv) > 1 else "../shared/burnpony_vectors.json"
dst = sys.argv[2] if len(sys.argv) > 2 else "vectors_flat.txt"

with open(src) as f:
    data = json.load(f)

lines = []
for v in data["vectors"]:
    payload = json.loads(v["plaintextPayload"])
    assert payload["v"] == 1
    lines.append(f"name={v['name']}")
    lines.append(f"keyFragment={v['keyFragment']}")
    lines.append(f"salt={v['salt']}")
    lines.append(f"nonce={v['nonce']}")
    lines.append(f"derivedKey={v['derivedKey']}")
    lines.append(f"ciphertext={v['ciphertext']}")
    lines.append("payloadB64=" + base64.b64encode(v["plaintextPayload"].encode()).decode())
    lines.append("textB64=" + base64.b64encode(payload["t"].encode()).decode())
    lines.append(f"ah={payload['ah']}")
    if v["password"] is not None:
        lines.append("passwordB64=" + base64.b64encode(v["password"].encode()).decode())
    lines.append("")

with open(dst, "w") as f:
    f.write("\n".join(lines))
print(f"wrote {dst}: {len(data['vectors'])} vectors")
