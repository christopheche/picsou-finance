package com.picsou.adapter.util;

import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bitcoin HD wallet utilities: BIP32 key derivation, address generation, and input parsing.
 *
 * Supports:
 *   - xpub  (extended public key, read as a BIP84 account)
 *   - zpub  (BIP84 extended public key — same as xpub with different version bytes)
 *   - Output script descriptors: wpkh([fingerprint/path]xpub.../derivation/*)#checksum
 *
 * All derived addresses are native SegWit P2WPKH (bc1q...). That is the only script type
 * this code can encode, so formats that denote another one — {@code ypub} (BIP49
 * P2SH-P2WPKH, {@code 3...} addresses), {@code pkh(} (legacy P2PKH, {@code 1...}) and
 * {@code sh(} (P2SH-wrapped) — are rejected by {@link #rejectUnsupportedScriptType} rather
 * than derived: probing bc1q addresses such a wallet never used answers "no history" for
 * every one of them, which reads as a 0 balance — a wrong number, not an error.
 */
public final class BitcoinKeyUtils {

    private BitcoinKeyUtils() {}

    // secp256k1 curve parameters
    private static final org.bouncycastle.asn1.x9.X9ECParameters CURVE =
            CustomNamedCurves.getByName("secp256k1");
    private static final ECPoint G = CURVE.getG();
    private static final BigInteger N = CURVE.getN();

    // BIP32 version bytes
    private static final byte[] XPUB_VERSION = {0x04, (byte) 0x88, (byte) 0xb2, 0x1e};
    private static final byte[] ZPUB_VERSION = {0x04, (byte) 0xb2, 0x47, 0x46};

    // BIP44 gap limit: stop scanning after this many consecutive unused addresses
    public static final int GAP_LIMIT = 20;

    /** Parsed xpub: chain code + compressed public key (33 bytes). */
    public record Xpub(byte[] chainCode, byte[] pubKey) {}

    // ─── Input detection ──────────────────────────────────────────────────────

    /**
     * Returns true if the input is an extended public key or output descriptor of a script type
     * this class can derive, not a plain address. Formats of another script type are neither
     * extended keys here nor plain addresses — see {@link #rejectUnsupportedScriptType}.
     */
    public static boolean isExtendedKey(String input) {
        String t = input.trim();
        return t.startsWith("xpub")
                || t.startsWith("zpub")
                || t.startsWith("wpkh(");
    }

    /**
     * Rejects an extended key or descriptor whose script type is not P2WPKH. A {@code ypub}
     * denotes BIP49 P2SH-wrapped SegWit ({@code 3...}), {@code pkh(} legacy P2PKH ({@code 1...})
     * and {@code sh(} any P2SH wrapping; none of them can be derived as {@code bc1q}, and
     * deriving them anyway would scan addresses the wallet never used and report 0 BTC.
     * Also rejects a {@code wpkh(} descriptor whose embedded key is a {@code ypub}, since the
     * key and the descriptor then disagree about the script type.
     *
     * @throws IllegalArgumentException naming the supported formats; the message never echoes
     *         the key itself
     */
    public static void rejectUnsupportedScriptType(String input) {
        String t = input.trim();
        String unsupported = null;
        if (t.startsWith("ypub")) {
            unsupported = "ypub (BIP49 P2SH-wrapped SegWit)";
        } else if (t.startsWith("pkh(")) {
            unsupported = "pkh( descriptor (legacy P2PKH)";
        } else if (t.startsWith("sh(")) {
            unsupported = "sh( descriptor (P2SH-wrapped)";
        } else if (t.startsWith("wpkh(") && t.contains("ypub")) {
            unsupported = "wpkh( descriptor around a ypub";
        }
        if (unsupported != null) {
            throw new IllegalArgumentException("Unsupported Bitcoin key format " + unsupported
                    + ": only native SegWit is derived -- use a plain address, an xpub/zpub (BIP84) "
                    + "or a wpkh(...) descriptor");
        }
    }

    // ─── Normalization ────────────────────────────────────────────────────────

    /**
     * Normalizes any supported extended key or descriptor format to a standard xpub string.
     *
     * <ul>
     *   <li>zpub → swaps version bytes to xpub format</li>
     *   <li>wpkh([fingerprint/path]xpub.../chain/*)#checksum → extracts the xpub</li>
     * </ul>
     *
     * Call {@link #rejectUnsupportedScriptType} first: a ypub or pkh( input is not normalized,
     * it is refused.
     */
    public static String normalizeToXpub(String input) {
        String t = input.trim();
        if (t.startsWith("wpkh(")) {
            return extractXpubFromDescriptor(t);
        }
        if (t.startsWith("zpub")) {
            return swapVersionToXpub(t);
        }
        return t; // already xpub
    }

    private static String extractXpubFromDescriptor(String descriptor) {
        // Find the start of xpub/zpub inside the descriptor
        int start = -1;
        for (String prefix : List.of("xpub", "zpub")) {
            int idx = descriptor.indexOf(prefix);
            if (idx >= 0 && (start < 0 || idx < start)) start = idx;
        }
        if (start < 0) throw new IllegalArgumentException("No xpub/zpub found in descriptor");

        // The key ends at the first non-Base58 character
        int end = start;
        while (end < descriptor.length() && isBase58Char(descriptor.charAt(end))) end++;

        String key = descriptor.substring(start, end);
        if (key.startsWith("zpub")) return swapVersionToXpub(key);
        return key;
    }

    private static boolean isBase58Char(char c) {
        return "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".indexOf(c) >= 0;
    }

    private static String swapVersionToXpub(String key) {
        byte[] decoded = base58DecodeChecked(key);
        System.arraycopy(XPUB_VERSION, 0, decoded, 0, 4);
        return base58EncodeChecked(decoded);
    }

    // ─── BIP32 key derivation ─────────────────────────────────────────────────

    /**
     * Parses an xpub string into its chain code and compressed public key.
     * BIP32 serialization: version(4) + depth(1) + fingerprint(4) + child_number(4)
     *                    + chain_code(32) + key(33) = 78 bytes
     */
    public static Xpub parseXpub(String xpub) {
        byte[] data = base58DecodeChecked(xpub);
        if (data.length != 78) throw new IllegalArgumentException("Invalid xpub length: " + data.length);
        return new Xpub(
                Arrays.copyOfRange(data, 13, 45),   // chain code
                Arrays.copyOfRange(data, 45, 78)    // compressed public key
        );
    }

    /**
     * BIP32 public child key derivation (non-hardened only).
     * CKDpub((K, c), i): HMAC-SHA512(key=c, data=K||i) → secp256k1 point addition.
     */
    public static Xpub deriveChild(Xpub parent, int index) {
        if (index < 0) throw new IllegalArgumentException("Hardened derivation not supported for public keys");
        try {
            ByteBuffer buf = ByteBuffer.allocate(37);
            buf.put(parent.pubKey());
            buf.putInt(index);

            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(parent.chainCode(), "HmacSHA512"));
            byte[] I = mac.doFinal(buf.array());

            byte[] IL = Arrays.copyOfRange(I, 0, 32);
            byte[] IR = Arrays.copyOfRange(I, 32, 64);

            BigInteger ilInt = new BigInteger(1, IL);
            if (ilInt.compareTo(N) >= 0) throw new IllegalStateException("Derived key is invalid (IL >= N)");

            ECPoint parentPoint = CURVE.getCurve().decodePoint(parent.pubKey());
            ECPoint childPoint = G.multiply(ilInt).add(parentPoint).normalize();
            if (childPoint.isInfinity()) throw new IllegalStateException("Derived key is the point at infinity");

            return new Xpub(IR, childPoint.getEncoded(true));
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("BIP32 child key derivation failed at index " + index, ex);
        }
    }

    // ─── Address generation ───────────────────────────────────────────────────

    /**
     * Derives the P2WPKH (native SegWit) address for a compressed public key.
     * Address format: bc1q... (Bech32, witness version 0, 20-byte hash).
     */
    public static String toP2WPKHAddress(byte[] compressedPubKey) {
        try {
            byte[] hash160 = hash160(compressedPubKey);
            return bech32Encode("bc", (byte) 0, hash160);
        } catch (Exception ex) {
            throw new RuntimeException("P2WPKH address generation failed", ex);
        }
    }

    private static byte[] hash160(byte[] data) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] sha256Hash = sha256.digest(data);

        RIPEMD160Digest ripemd = new RIPEMD160Digest();
        ripemd.update(sha256Hash, 0, sha256Hash.length);
        byte[] result = new byte[20];
        ripemd.doFinal(result, 0);
        return result;
    }

    // ─── Base58Check ──────────────────────────────────────────────────────────

    private static final String BASE58_CHARS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    public static byte[] base58DecodeChecked(String input) {
        byte[] decoded = base58Decode(input);
        if (decoded.length < 4) throw new IllegalArgumentException("Input too short for Base58Check");
        byte[] payload  = Arrays.copyOfRange(decoded, 0, decoded.length - 4);
        byte[] checksum = Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);
        byte[] expected = Arrays.copyOfRange(sha256d(payload), 0, 4);
        if (!Arrays.equals(checksum, expected)) {
            throw new IllegalArgumentException("Invalid Base58Check checksum");
        }
        return payload;
    }

    public static String base58EncodeChecked(byte[] payload) {
        byte[] checksum = Arrays.copyOfRange(sha256d(payload), 0, 4);
        byte[] full = new byte[payload.length + 4];
        System.arraycopy(payload, 0, full, 0, payload.length);
        System.arraycopy(checksum, 0, full, payload.length, 4);
        return base58Encode(full);
    }

    private static byte[] base58Decode(String input) {
        BigInteger value = BigInteger.ZERO;
        for (char c : input.toCharArray()) {
            int digit = BASE58_CHARS.indexOf(c);
            if (digit < 0) throw new IllegalArgumentException("Invalid Base58 character: " + c);
            value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        int leadingZeros = 0;
        for (char c : input.toCharArray()) {
            if (c == '1') leadingZeros++; else break;
        }
        byte[] bytes = value.toByteArray();
        // Strip leading zero sign byte added by BigInteger if needed
        if (bytes.length > 1 && bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        byte[] result = new byte[leadingZeros + bytes.length];
        System.arraycopy(bytes, 0, result, leadingZeros, bytes.length);
        return result;
    }

    private static String base58Encode(byte[] input) {
        BigInteger value = new BigInteger(1, input);
        StringBuilder sb = new StringBuilder();
        BigInteger base = BigInteger.valueOf(58);
        while (value.signum() > 0) {
            BigInteger[] dr = value.divideAndRemainder(base);
            sb.append(BASE58_CHARS.charAt(dr[1].intValue()));
            value = dr[0];
        }
        for (byte b : input) {
            if (b == 0) sb.append('1'); else break;
        }
        return sb.reverse().toString();
    }

    private static byte[] sha256d(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(md.digest(data));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // ─── Bech32 ───────────────────────────────────────────────────────────────

    private static final String BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    private static String bech32Encode(String hrp, byte witnessVersion, byte[] program) {
        byte[] converted = convertBits(program, 8, 5, true);
        byte[] payload = new byte[1 + converted.length];
        payload[0] = witnessVersion;
        System.arraycopy(converted, 0, payload, 1, converted.length);

        byte[] checksum = bech32Checksum(hrp, payload);
        StringBuilder sb = new StringBuilder(hrp).append('1');
        for (byte b : payload) sb.append(BECH32_CHARSET.charAt(b & 0x1f));
        for (byte b : checksum) sb.append(BECH32_CHARSET.charAt(b & 0x1f));
        return sb.toString();
    }

    private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0, bits = 0, maxv = (1 << toBits) - 1;
        List<Byte> out = new ArrayList<>();
        for (byte b : data) {
            acc = (acc << fromBits) | (b & ((1 << fromBits) - 1));
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                out.add((byte) ((acc >> bits) & maxv));
            }
        }
        if (pad && bits > 0) out.add((byte) ((acc << (toBits - bits)) & maxv));
        byte[] result = new byte[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

    private static byte[] bech32Checksum(String hrp, byte[] data) {
        byte[] enc = bech32HrpExpand(hrp, data);
        long polymod = bech32Polymod(enc) ^ 1L;
        byte[] checksum = new byte[6];
        for (int i = 0; i < 6; i++) checksum[i] = (byte) ((polymod >> (5 * (5 - i))) & 0x1f);
        return checksum;
    }

    private static byte[] bech32HrpExpand(String hrp, byte[] data) {
        byte[] result = new byte[hrp.length() * 2 + 1 + data.length + 6];
        for (int i = 0; i < hrp.length(); i++) {
            result[i] = (byte) (hrp.charAt(i) >> 5);
            result[i + hrp.length() + 1] = (byte) (hrp.charAt(i) & 0x1f);
        }
        result[hrp.length()] = 0;
        System.arraycopy(data, 0, result, hrp.length() * 2 + 1, data.length);
        return result;
    }

    private static long bech32Polymod(byte[] values) {
        long[] GEN = {0x3b6a57b2L, 0x26508e6dL, 0x1ea119faL, 0x3d4233ddL, 0x2a1462b3L};
        long chk = 1L;
        for (byte v : values) {
            byte top = (byte) (chk >> 25);
            chk = ((chk & 0x1ffffffL) << 5) ^ (v & 0xffL);
            for (int i = 0; i < 5; i++) {
                if (((top >> i) & 1) == 1) chk ^= GEN[i];
            }
        }
        return chk;
    }
}
