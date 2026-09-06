package com.picsou.adapter.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Derivation is checked against the BIP84 test vectors (mnemonic "abandon" x11 + "about",
 * account 0): the zpub, its xpub re-encoding, and the first receiving / change addresses.
 */
class BitcoinKeyUtilsTest {

    static final String ZPUB =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs";
    static final String XPUB =
        "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V";
    static final String DESCRIPTOR = "wpkh([73c5da0a/84h/0h/0h]" + ZPUB + "/0/*)#abcdefgh";

    static final String RECEIVE_0 = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
    static final String RECEIVE_1 = "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g";
    static final String CHANGE_0 = "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el";

    // A ypub with the same payload: only the version bytes differ, so it parses -- the point
    // is that it must be refused before anyone gets to parse it.
    static final String YPUB =
        "ypub6XR9pJPUsVBFKweLeV85HtwdxjjmKEuUr6djm9mNdkh47X7ASsD6byaXFotRAKByFoWgSzCuoTjaYdrv2yoJroLAPtBuHFjVm5vNmhyNehE";

    @Test
    void isExtendedKey_acceptsXpubZpubAndWpkhDescriptor_notPlainAddresses() {
        assertThat(BitcoinKeyUtils.isExtendedKey(XPUB)).isTrue();
        assertThat(BitcoinKeyUtils.isExtendedKey("  " + ZPUB + " ")).isTrue();
        assertThat(BitcoinKeyUtils.isExtendedKey(DESCRIPTOR)).isTrue();
        assertThat(BitcoinKeyUtils.isExtendedKey(RECEIVE_0)).isFalse();
        assertThat(BitcoinKeyUtils.isExtendedKey("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2")).isFalse();
    }

    @Test
    void isExtendedKey_doesNotClaimUnsupportedScriptTypes() {
        // These are neither derivable here nor plain addresses; they are rejected, not routed.
        assertThat(BitcoinKeyUtils.isExtendedKey(YPUB)).isFalse();
        assertThat(BitcoinKeyUtils.isExtendedKey("pkh([d34db33f/44h/0h/0h]" + XPUB + "/0/*)")).isFalse();
    }

    @Test
    void rejectUnsupportedScriptType_refusesYpub_namingTheSupportedFormats() {
        assertThatThrownBy(() -> BitcoinKeyUtils.rejectUnsupportedScriptType(YPUB))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ypub")
            .hasMessageContaining("xpub/zpub")
            .hasMessageContaining("wpkh(")
            // Never echo the key.
            .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(YPUB.substring(4)));
    }

    @Test
    void rejectUnsupportedScriptType_refusesLegacyAndP2shDescriptors() {
        assertThatThrownBy(() -> BitcoinKeyUtils.rejectUnsupportedScriptType("pkh([d34db33f/44h/0h/0h]" + XPUB + "/0/*)"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pkh(");
        assertThatThrownBy(() -> BitcoinKeyUtils.rejectUnsupportedScriptType("sh(wpkh([d34db33f/49h/0h/0h]" + XPUB + "/0/*))"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sh(");
        assertThatThrownBy(() -> BitcoinKeyUtils.rejectUnsupportedScriptType("wpkh([d34db33f/49h/0h/0h]" + YPUB + "/0/*)"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ypub");
    }

    @Test
    void rejectUnsupportedScriptType_acceptsEverySupportedFormat() {
        assertThatCode(() -> BitcoinKeyUtils.rejectUnsupportedScriptType(XPUB)).doesNotThrowAnyException();
        assertThatCode(() -> BitcoinKeyUtils.rejectUnsupportedScriptType(ZPUB)).doesNotThrowAnyException();
        assertThatCode(() -> BitcoinKeyUtils.rejectUnsupportedScriptType(DESCRIPTOR)).doesNotThrowAnyException();
        assertThatCode(() -> BitcoinKeyUtils.rejectUnsupportedScriptType(RECEIVE_0)).doesNotThrowAnyException();
    }

    @Test
    void normalizeToXpub_swapsZpubVersionBytes_toTheBip84VectorXpub() {
        assertThat(BitcoinKeyUtils.normalizeToXpub(ZPUB)).isEqualTo(XPUB);
        assertThat(BitcoinKeyUtils.normalizeToXpub(XPUB)).isEqualTo(XPUB);
    }

    @Test
    void normalizeToXpub_extractsTheKeyFromAWpkhDescriptor() {
        assertThat(BitcoinKeyUtils.normalizeToXpub(DESCRIPTOR)).isEqualTo(XPUB);
        assertThat(BitcoinKeyUtils.normalizeToXpub("wpkh(" + XPUB + "/0/*)")).isEqualTo(XPUB);
    }

    @Test
    void normalizeToXpub_descriptorWithoutAKey_throws() {
        assertThatThrownBy(() -> BitcoinKeyUtils.normalizeToXpub("wpkh([73c5da0a/84h/0h/0h]/0/*)"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No xpub/zpub");
    }

    @Test
    void parseXpub_rejectsABadChecksum() {
        String corrupted = XPUB.substring(0, XPUB.length() - 1) + (XPUB.endsWith("V") ? "W" : "V");

        assertThatThrownBy(() -> BitcoinKeyUtils.parseXpub(corrupted))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    void parseXpub_rejectsAWrongLengthPayload() {
        // A valid Base58Check string that is not 78 bytes: a plain P2PKH address (21 bytes).
        assertThatThrownBy(() -> BitcoinKeyUtils.parseXpub("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("length");
    }

    @Test
    void deriveChild_andToP2WPKHAddress_matchTheBip84Vectors() {
        BitcoinKeyUtils.Xpub root = BitcoinKeyUtils.parseXpub(XPUB);
        BitcoinKeyUtils.Xpub external = BitcoinKeyUtils.deriveChild(root, 0);
        BitcoinKeyUtils.Xpub change = BitcoinKeyUtils.deriveChild(root, 1);

        assertThat(BitcoinKeyUtils.toP2WPKHAddress(BitcoinKeyUtils.deriveChild(external, 0).pubKey())).isEqualTo(RECEIVE_0);
        assertThat(BitcoinKeyUtils.toP2WPKHAddress(BitcoinKeyUtils.deriveChild(external, 1).pubKey())).isEqualTo(RECEIVE_1);
        assertThat(BitcoinKeyUtils.toP2WPKHAddress(BitcoinKeyUtils.deriveChild(change, 0).pubKey())).isEqualTo(CHANGE_0);
    }

    @Test
    void deriveChild_refusesHardenedIndexes() {
        BitcoinKeyUtils.Xpub root = BitcoinKeyUtils.parseXpub(XPUB);

        assertThatThrownBy(() -> BitcoinKeyUtils.deriveChild(root, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Hardened");
    }
}
