package de.blockprotect.module.internal;

import java.net.URI;
import java.nio.file.Path;

public record UpdateDescriptor(String moduleId,
                               ModuleVersion version,
                               String source,
                               Path localPath,
                               URI jarUri,
                               URI checksumUri,
                               String jarFileName,
                               String checksumFileName) {
    public boolean remote() {
        return jarUri != null;
    }
}
