package de.blockprotect.module.internal;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChecksumVerifierTest {
    @Test
    void acceptsStandardSha256SidecarLine() throws IOException {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(hash, ChecksumVerifier.parseExpected(hash + "  module.jar"));
    }

    @Test
    void rejectsShortOrMalformedHashes() {
        assertThrows(IOException.class, () -> ChecksumVerifier.parseExpected("not-a-hash"));
    }
}
