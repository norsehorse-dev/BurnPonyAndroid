//
// BurnPonyVerify.java
// In-session verification harness for the BurnPony Android port.
//
// This is a mechanical transliteration of core/src/main/kotlin/com/burnpony/
// core/BurnPonyCrypto.kt + Base64Codec.kt into plain Java, using the SAME JCA
// primitives the Kotlin core calls (Mac HmacSHA256, SecretKeyFactory
// PBKDF2WithHmacSHA256, Cipher AES/GCM/NoPadding with GCMParameterSpec).
// It runs against the canonical cross-implementation vectors to prove the
// algorithm, the deterministic serializer, and the JCA usage byte-exact
// before the Kotlin JUnit suite can be executed on a machine with the
// Android toolchain. Reads vectors from vectors_flat.txt (generated from
// shared/burnpony_vectors.json by make_flat_vectors.py).
//

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class BurnPonyVerify {

    static final String INFO_STRING = "BurnPony-v1-key";
    static final int PBKDF2_ITERATIONS = 600000;
    static final int KEY_LENGTH = 32;
    static final int SALT_LENGTH = 16;
    static final int NONCE_LENGTH = 12;
    static final int TAG_LENGTH_BITS = 128;

    static int passed = 0;
    static int failed = 0;

    // ---- Base64Codec transliteration ----

    static final String ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    static final int[] REVERSE = new int[128];
    static {
        Arrays.fill(REVERSE, -1);
        for (int i = 0; i < ALPHABET.length(); i++) REVERSE[ALPHABET.charAt(i)] = i;
    }

    static String b64Encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 3 <= data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            out.append(ALPHABET.charAt((n >> 18) & 0x3F));
            out.append(ALPHABET.charAt((n >> 12) & 0x3F));
            out.append(ALPHABET.charAt((n >> 6) & 0x3F));
            out.append(ALPHABET.charAt(n & 0x3F));
            i += 3;
        }
        int rem = data.length - i;
        if (rem == 1) {
            int n = (data[i] & 0xFF) << 16;
            out.append(ALPHABET.charAt((n >> 18) & 0x3F));
            out.append(ALPHABET.charAt((n >> 12) & 0x3F));
            out.append("==");
        } else if (rem == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            out.append(ALPHABET.charAt((n >> 18) & 0x3F));
            out.append(ALPHABET.charAt((n >> 12) & 0x3F));
            out.append(ALPHABET.charAt((n >> 6) & 0x3F));
            out.append('=');
        }
        return out.toString();
    }

    static byte[] b64Decode(String s) {
        if (s.length() % 4 != 0) return null;
        if (s.isEmpty()) return new byte[0];
        int padding = 0;
        if (s.endsWith("==")) padding = 2;
        else if (s.endsWith("=")) padding = 1;
        if (s.substring(0, s.length() - padding).indexOf('=') >= 0) return null;
        byte[] out = new byte[s.length() / 4 * 3 - padding];
        int o = 0;
        for (int i = 0; i < s.length(); i += 4) {
            boolean last = i + 4 == s.length();
            Integer c0 = val(s.charAt(i));
            Integer c1 = val(s.charAt(i + 1));
            Integer c2 = (last && padding == 2) ? Integer.valueOf(0) : val(s.charAt(i + 2));
            Integer c3 = (last && padding >= 1) ? Integer.valueOf(0) : val(s.charAt(i + 3));
            if (c0 == null || c1 == null || c2 == null || c3 == null) return null;
            if (last && padding == 2 && (c1 & 0x0F) != 0) return null;
            if (last && padding == 1 && (c2 & 0x03) != 0) return null;
            int n = (c0 << 18) | (c1 << 12) | (c2 << 6) | c3;
            out[o++] = (byte) ((n >> 16) & 0xFF);
            if (!(last && padding == 2)) out[o++] = (byte) ((n >> 8) & 0xFF);
            if (!(last && padding >= 1)) out[o++] = (byte) (n & 0xFF);
        }
        return out;
    }

    static Integer val(char c) {
        if (c >= 128) return null;
        int v = REVERSE[c];
        return v < 0 ? null : Integer.valueOf(v);
    }

    // ---- Fragment codec ----

    static String fragmentEncode(byte[] key) {
        String s = b64Encode(key).replace('+', '-').replace('/', '_');
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '=') end--;
        return s.substring(0, end);
    }

    static byte[] fragmentDecode(String fragment) {
        StringBuilder b = new StringBuilder(fragment.replace('-', '+').replace('_', '/'));
        while (b.length() % 4 != 0) b.append('=');
        byte[] key = b64Decode(b.toString());
        if (key == null || key.length != KEY_LENGTH) throw new RuntimeException("bad fragment key");
        return key;
    }

    // ---- Canonical password ----

    static boolean isTrimmable(char c) {
        int code = c;
        if (code == 0x09 || code == 0x0A || code == 0x0B || code == 0x0C || code == 0x0D) return true;
        if (code == 0x20 || code == 0xA0 || code == 0x1680) return true;
        if (code >= 0x2000 && code <= 0x200A) return true;
        if (code == 0x2028 || code == 0x2029 || code == 0x202F || code == 0x205F) return true;
        if (code == 0x3000 || code == 0xFEFF) return true;
        return false;
    }

    static String canonicalPassword(String password) {
        String nfc = Normalizer.normalize(password, Normalizer.Form.NFC);
        int start = 0;
        int end = nfc.length();
        while (start < end && isTrimmable(nfc.charAt(start))) start++;
        while (end > start && isTrimmable(nfc.charAt(end - 1))) end--;
        return nfc.substring(start, end);
    }

    // ---- PBKDF2 (both paths) ----

    static byte[] pbkdf2Platform(String canonicalPassword, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(
            canonicalPassword.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH * 8);
        return factory.generateSecret(spec).getEncoded();
    }

    static byte[] pbkdf2Fallback(byte[] passwordUtf8, byte[] salt) throws Exception {
        if (passwordUtf8.length == 0) throw new RuntimeException("empty password");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(passwordUtf8, "HmacSHA256"));
        mac.update(salt);
        mac.update(new byte[] {0, 0, 0, 1});
        byte[] u = mac.doFinal();
        byte[] result = u.clone();
        for (int i = 2; i <= PBKDF2_ITERATIONS; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < result.length; j++) result[j] ^= u[j];
        }
        return result;
    }

    // ---- HKDF ----

    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] okm = new byte[length];
        byte[] previous = new byte[0];
        int generated = 0;
        int counter = 1;
        while (generated < length) {
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter);
            previous = mac.doFinal();
            int toCopy = Math.min(previous.length, length - generated);
            System.arraycopy(previous, 0, okm, generated, toCopy);
            generated += toCopy;
            counter++;
        }
        return okm;
    }

    static byte[] deriveKey(byte[] fragmentKey, byte[] salt, String password, boolean useFallbackPbkdf2) throws Exception {
        if (fragmentKey.length != KEY_LENGTH) throw new RuntimeException("bad fragment key");
        byte[] ikm = fragmentKey;
        if (password != null) {
            String canonical = canonicalPassword(password);
            byte[] stretched = useFallbackPbkdf2
                ? pbkdf2Fallback(canonical.getBytes(StandardCharsets.UTF_8), salt)
                : pbkdf2Platform(canonical, salt);
            ikm = new byte[fragmentKey.length + stretched.length];
            System.arraycopy(fragmentKey, 0, ikm, 0, fragmentKey.length);
            System.arraycopy(stretched, 0, ikm, fragmentKey.length, stretched.length);
        }
        return hkdfSha256(ikm, salt, INFO_STRING.getBytes(StandardCharsets.UTF_8), KEY_LENGTH);
    }

    // ---- Deterministic payload serializer ----

    static String jsonEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    static String payloadJson(String text, int autoHideSeconds) {
        return "{\"v\":1,\"t\":\"" + jsonEscape(text) + "\",\"ah\":" + autoHideSeconds + "}";
    }

    // ---- AES-GCM ----

    static byte[] gcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
        return cipher.doFinal(plaintext);
    }

    static byte[] gcmDecrypt(byte[] key, byte[] nonce, byte[] ct) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
        return cipher.doFinal(ct);
    }

    // ---- Test plumbing ----

    static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label);
        }
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        List<Map<String, String>> vectors = readFlat("vectors_flat.txt");

        for (Map<String, String> v : vectors) {
            String name = v.get("name");
            byte[] fragmentKey = fragmentDecode(v.get("keyFragment"));
            byte[] salt = b64Decode(v.get("salt"));
            byte[] nonce = b64Decode(v.get("nonce"));
            byte[] expectedKey = b64Decode(v.get("derivedKey"));
            byte[] expectedCt = b64Decode(v.get("ciphertext"));
            byte[] expectedPayload = b64Decode(v.get("payloadB64"));
            String password = v.containsKey("passwordB64")
                ? new String(b64Decode(v.get("passwordB64")), StandardCharsets.UTF_8) : null;
            String text = new String(b64Decode(v.get("textB64")), StandardCharsets.UTF_8);
            int ah = Integer.parseInt(v.get("ah"));

            // Base64 codec against JDK reference on every field
            check(name + " b64 codec matches JDK (salt)",
                Arrays.equals(salt, java.util.Base64.getDecoder().decode(v.get("salt")))
                    && b64Encode(salt).equals(v.get("salt")));
            check(name + " b64 codec matches JDK (ct)",
                Arrays.equals(expectedCt, java.util.Base64.getDecoder().decode(v.get("ciphertext")))
                    && b64Encode(expectedCt).equals(v.get("ciphertext")));

            // Fragment round trip
            check(name + " fragment decode length", fragmentKey.length == 32);
            check(name + " fragment re-encode", fragmentEncode(fragmentKey).equals(v.get("keyFragment")));

            // Deterministic serializer: byte-exact against plaintextPayload
            check(name + " serializer byte-exact",
                Arrays.equals(utf8(payloadJson(text, ah)), expectedPayload));

            // Key derivation, platform PBKDF2 path
            byte[] derived = deriveKey(fragmentKey, salt, password, false);
            check(name + " derived key (platform PBKDF2)", Arrays.equals(derived, expectedKey));

            // Key derivation, fallback PBKDF2 path (the API 24/25 code path)
            byte[] derivedFallback = deriveKey(fragmentKey, salt, password, true);
            check(name + " derived key (fallback PBKDF2)", Arrays.equals(derivedFallback, expectedKey));

            // Byte-exact encryption reproduction
            byte[] ct = gcmEncrypt(derived, nonce, expectedPayload);
            check(name + " ciphertext byte-exact", Arrays.equals(ct, expectedCt));

            // Full-pipeline encryption from (text, ah) inputs
            byte[] ct2 = gcmEncrypt(derived, nonce, utf8(payloadJson(text, ah)));
            check(name + " encrypt pipeline byte-exact", Arrays.equals(ct2, expectedCt));

            // Decryption
            byte[] pt = gcmDecrypt(derived, nonce, expectedCt);
            check(name + " decrypt byte-exact", Arrays.equals(pt, expectedPayload));
        }

        // ---- Negative: wrong password on v3 must fail authentication ----
        Map<String, String> v3 = vectors.get(2);
        byte[] fragmentKey3 = fragmentDecode(v3.get("keyFragment"));
        byte[] salt3 = b64Decode(v3.get("salt"));
        byte[] nonce3 = b64Decode(v3.get("nonce"));
        byte[] ct3 = b64Decode(v3.get("ciphertext"));
        boolean wrongPasswordFailed = false;
        try {
            gcmDecrypt(deriveKey(fragmentKey3, salt3, "wrong horse battery staple", false), nonce3, ct3);
        } catch (Exception e) {
            wrongPasswordFailed = true;
        }
        check("negative wrong password fails", wrongPasswordFailed);

        // Missing password (pw=true envelope, none supplied → derive without password)
        boolean missingPasswordFailed = false;
        try {
            gcmDecrypt(deriveKey(fragmentKey3, salt3, null, false), nonce3, ct3);
        } catch (Exception e) {
            missingPasswordFailed = true;
        }
        check("negative missing password fails", missingPasswordFailed);

        // Tampered ciphertext
        Map<String, String> v1 = vectors.get(0);
        byte[] fk1 = fragmentDecode(v1.get("keyFragment"));
        byte[] salt1 = b64Decode(v1.get("salt"));
        byte[] nonce1 = b64Decode(v1.get("nonce"));
        byte[] tampered = b64Decode(v1.get("ciphertext"));
        tampered[5] ^= 0x01;
        boolean tamperFailed = false;
        try {
            gcmDecrypt(deriveKey(fk1, salt1, null, false), nonce1, tampered);
        } catch (Exception e) {
            tamperFailed = true;
        }
        check("negative tampered ct fails", tamperFailed);

        // Wrong fragment key
        byte[] wrongKey = fk1.clone();
        wrongKey[0] ^= 0x01;
        boolean wrongKeyFailed = false;
        try {
            gcmDecrypt(deriveKey(wrongKey, salt1, null, false), nonce1, b64Decode(v1.get("ciphertext")));
        } catch (Exception e) {
            wrongKeyFailed = true;
        }
        check("negative wrong fragment key fails", wrongKeyFailed);

        // ---- Canonicalization matrix (against v3/v4 derived keys) ----
        Map<String, String> v4 = vectors.get(3);
        byte[] fk4 = fragmentDecode(v4.get("keyFragment"));
        byte[] salt4 = b64Decode(v4.get("salt"));
        byte[] key4 = b64Decode(v4.get("derivedKey"));
        String pw4 = new String(b64Decode(v4.get("passwordB64")), StandardCharsets.UTF_8);

        String nfd4 = Normalizer.normalize(pw4, Normalizer.Form.NFD);
        check("canonicalization NFD input matches", Arrays.equals(deriveKey(fk4, salt4, nfd4, false), key4));
        check("canonicalization trailing space matches", Arrays.equals(deriveKey(fk4, salt4, pw4 + " ", false), key4));
        check("canonicalization leading whitespace matches", Arrays.equals(deriveKey(fk4, salt4, "\t\n " + pw4, false), key4));
        check("canonicalization BOM wrap matches", Arrays.equals(deriveKey(fk4, salt4, "\uFEFF" + pw4 + "\uFEFF", false), key4));
        check("canonicalization NBSP trim matches", Arrays.equals(deriveKey(fk4, salt4, " " + pw4 + " ", false), key4));

        String pw3 = new String(b64Decode(v3.get("passwordB64")), StandardCharsets.UTF_8);
        byte[] key3 = b64Decode(v3.get("derivedKey"));
        check("canonicalization capitalization rejected",
            !Arrays.equals(deriveKey(fragmentKey3, salt3, pw3.toUpperCase(), false), key3));
        check("canonicalization interior space rejected",
            !Arrays.equals(deriveKey(fragmentKey3, salt3, pw3.replace(" ", "  "), false), key3));

        // ---- Random round trip (both PBKDF2 paths agree on random inputs) ----
        SecureRandom rng = new SecureRandom();
        for (int i = 0; i < 3; i++) {
            byte[] fk = new byte[32];
            byte[] st = new byte[16];
            rng.nextBytes(fk);
            rng.nextBytes(st);
            String pw = "round-trip-pässword-" + i;
            check("random pbkdf2 paths agree #" + i,
                Arrays.equals(deriveKey(fk, st, pw, false), deriveKey(fk, st, pw, true)));
        }

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static List<Map<String, String>> readFlat(String path) throws Exception {
        List<Map<String, String>> out = new ArrayList<>();
        Map<String, String> current = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                if (!current.isEmpty()) { out.add(current); current = new HashMap<>(); }
                continue;
            }
            int eq = line.indexOf('=');
            current.put(line.substring(0, eq), line.substring(eq + 1));
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }
}
