package pl.skidam.automodpack_core.utils;

import java.text.Normalizer;
import java.util.Locale;

public final class CertificateInput {
    private CertificateInput() {
    }

    public static boolean matchesRiskPhrase(String input, String requiredPhrase) {
        return input != null && requiredPhrase != null
                && normalizePhrase(input).equals(normalizePhrase(requiredPhrase));
    }

    public static boolean matchesFingerprint(String input, String serverFingerprint) {
        if (input == null || serverFingerprint == null) {
            return false;
        }

        String normalizedInput = input.replaceAll("[\\s:-]", "").toLowerCase(Locale.ROOT);
        String normalizedFingerprint = serverFingerprint.replaceAll("[\\s:-]", "").toLowerCase(Locale.ROOT);
        return normalizedInput.matches("[0-9a-f]{64}") && normalizedInput.equals(normalizedFingerprint);
    }

    private static String normalizePhrase(String value) {
        String compatible = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return compatible.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
