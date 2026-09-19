package de.blockprotect.module.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModuleVersionTest {
    @Test
    void comparesNumericReleaseVersions() {
        assertEquals(0, ModuleVersion.parse("v1.0").compareTo(ModuleVersion.parse("1.0.0")));
        assertEquals(1, ModuleVersion.parse("1.10.0").compareTo(ModuleVersion.parse("1.9.9")));
    }

    @Test
    void rejectsNonReleaseTags() {
        assertThrows(IllegalArgumentException.class, () -> ModuleVersion.parse("main"));
    }
}
