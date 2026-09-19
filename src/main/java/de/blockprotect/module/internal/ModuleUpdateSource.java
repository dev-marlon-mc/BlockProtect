package de.blockprotect.module.internal;

import java.nio.file.Path;
import java.util.Optional;

public interface ModuleUpdateSource {
    Optional<UpdateDescriptor> findLatest(ModuleDescriptor installed) throws Exception;

    Path download(UpdateDescriptor update) throws Exception;
}
