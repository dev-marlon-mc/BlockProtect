package de.blockprotect.modules.sessions;

import de.blockprotect.audit.AuditRecorder;
import de.blockprotect.audit.SessionAuditModule;
import de.blockprotect.module.BlockProtectModule;
import de.blockprotect.module.ModuleContext;

/** Independent lifecycle for player join/quit/kick auditing. */
public final class SessionModule implements BlockProtectModule {
    @Override
    public void enable(ModuleContext context) {
        context.registerListener(new SessionAuditModule(context.recorder()));
        context.logger().info("Session-Audit aktiviert.");
    }

    @Override
    public void disable() {
        // Resources are centrally unregistered by ModuleContext.
    }
}
