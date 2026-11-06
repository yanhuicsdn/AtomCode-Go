package io.github.yanhuicsdn.atomcode;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebViewCompatibilityTest {
    @Test public void acceptsSupportedProviderVersionsIncludingTheMinimum() {
        for (String version : new String[]{"99", "99.0.4844.88", "126.0.6478.71", "140.0.7339.207", " 99.0 "})
            assertTrue(version, WebViewCompatibility.supports(version));
    }

    @Test public void rejectsOldProvidersBeforeTheyLoadTheWebUi() {
        for (String version : new String[]{"58.0.3029.125", "98.0.4758.101", "9", "0.99"})
            assertFalse(version, WebViewCompatibility.supports(version));
    }

    @Test public void cannotAssumeAnUnknownOrMalformedProviderIsSupported() {
        assertFalse(WebViewCompatibility.supports(null));
        for (String version : new String[]{"", " ", "unknown", "99beta", "99.", ".99", "99..0", "-99", "99999999999999999999.0"})
            assertFalse(version, WebViewCompatibility.supports(version));
    }
}
