package de.blockprotect.modules.audit;

import de.blockprotect.audit.AuditRecorder;
import de.blockprotect.audit.BlockAuditModule;
import de.blockprotect.audit.ContainerAuditModule;
import de.blockprotect.audit.EntityAuditModule;
import de.blockprotect.audit.InspectionState;
import de.blockprotect.audit.InteractionAuditModule;
import de.blockprotect.module.BlockProtectModule;
import de.blockprotect.module.ModuleContext;

/**
 * The gameplay audit suite is a normal module entrypoint. Every listener is
 * registered through ModuleContext so the core can unregister it on stop.
 */
public final class AuditSuiteModule implements BlockProtectModule {
    @Override
    public void enable(ModuleContext context) {
        AuditRecorder recorder = context.recorder();
        InspectionState inspectionState = context.service(InspectionState.class);
        context.registerListener(new BlockAuditModule(recorder));
        context.registerListener(new ContainerAuditModule(recorder));
        context.registerListener(new EntityAuditModule(recorder));
        context.registerListener(new InteractionAuditModule(recorder, inspectionState));
        context.logger().info("Audit-Suite aktiviert.");
    }

    @Override
    public void disable() {
        // Listener and scheduler cleanup is intentionally owned by the core
        // resource registry, even if a module throws during this callback.
    }
}
