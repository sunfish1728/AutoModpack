package pl.skidam.automodpack_core.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificateInputTest {
    @Test
    void riskPhraseAcceptsImeAndWhitespaceVariants() {
        assertTrue(CertificateInput.matchesRiskPhrase("  i  ACCEPT\u3000the risk  ", "I accept the risk"));
        assertTrue(CertificateInput.matchesRiskPhrase("Ｉ ａｃｃｅｐｔ ｔｈｅ ｒｉｓｋ", "I accept the risk"));
        assertTrue(CertificateInput.matchesRiskPhrase("我接受風險", "我接受風險"));
        assertFalse(CertificateInput.matchesRiskPhrase("I accept risk", "I accept the risk"));
    }

    @Test
    void fingerprintAcceptsCommonFormattingWithoutChangingDigits() {
        String fingerprint = "8152c3e9f92117a4dc7e8d07dd1a9ec02a9b6b554fcf9b6a9e8d150e74346e20";
        String formatted = "81:52:C3:E9:F9:21:17:A4:DC:7E:8D:07:DD:1A:9E:C0:2A:9B:6B:55:4F:CF:9B:6A:9E:8D:15:0E:74:34:6E:20";

        assertTrue(CertificateInput.matchesFingerprint(formatted, fingerprint));
        assertFalse(CertificateInput.matchesFingerprint(fingerprint.substring(2), fingerprint));
    }
}
