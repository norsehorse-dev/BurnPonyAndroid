const BP_INFO = "BurnPony-v1-key";
const BP_PBKDF2_ITERATIONS = 600000;

function bpB64Decode(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function bpB64Encode(bytes) {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

function bpFragmentDecode(fragment) {
  let s = fragment.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4 !== 0) s += "=";
  const key = bpB64Decode(s);
  if (key.length !== 32) throw new Error("bad key length");
  return key;
}

function bpFragmentEncode(keyBytes) {
  return bpB64Encode(keyBytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function bpCanonicalPassword(password) {
  return password.normalize("NFC").replace(/^[\s\uFEFF]+|[\s\uFEFF]+$/g, "");
}

async function bpDeriveKey(fragmentKey, salt, password) {
  let ikm = fragmentKey;
  if (password !== null && password !== undefined) {
    const pwKey = await crypto.subtle.importKey(
      "raw", new TextEncoder().encode(bpCanonicalPassword(password)), "PBKDF2", false, ["deriveBits"]);
    const stretched = new Uint8Array(await crypto.subtle.deriveBits(
      { name: "PBKDF2", hash: "SHA-256", salt: salt, iterations: BP_PBKDF2_ITERATIONS },
      pwKey, 256));
    const combined = new Uint8Array(64);
    combined.set(fragmentKey, 0);
    combined.set(stretched, 32);
    ikm = combined;
  }
  const hkdfKey = await crypto.subtle.importKey("raw", ikm, "HKDF", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: salt, info: new TextEncoder().encode(BP_INFO) },
    hkdfKey, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}

async function bpEncrypt(text, autoHideSeconds, fragmentKey, salt, nonce, password) {
  const payload = JSON.stringify({ v: 1, t: text, ah: autoHideSeconds });
  const key = await bpDeriveKey(fragmentKey, salt, password);
  const ct = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce }, key, new TextEncoder().encode(payload)));
  return {
    v: 1,
    pw: password !== null && password !== undefined,
    salt: bpB64Encode(salt),
    nonce: bpB64Encode(nonce),
    ct: bpB64Encode(ct)
  };
}

async function bpDecrypt(envelope, fragmentKey, password) {
  if (envelope.v !== 1) throw new Error("unsupported envelope version");
  const salt = bpB64Decode(envelope.salt);
  const nonce = bpB64Decode(envelope.nonce);
  const ct = bpB64Decode(envelope.ct);
  const key = await bpDeriveKey(fragmentKey, salt, envelope.pw ? password : null);
  const pt = new Uint8Array(await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: nonce }, key, ct));
  const payload = JSON.parse(new TextDecoder().decode(pt));
  if (payload.v !== 1) throw new Error("unsupported payload version");
  return payload;
}
