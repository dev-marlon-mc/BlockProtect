package de.blockprotect.module.internal;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Owns every runtime resource created through one ModuleContext. */
public final class ModuleResourceRegistry implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Logger logger;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<BukkitTask> tasks = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Object callbackMonitor = new Object();
    private int runningCallbacks;

    public ModuleResourceRegistry(JavaPlugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean isActive() {
        return active.get();
    }

    public synchronized void registerListener(Listener listener) {
        requireActive();
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    public BukkitTask trackTask(BukkitTask task) {
        Objects.requireNonNull(task, "task");
        if (!active.get()) {
            task.cancel();
            return task;
        }
        tasks.add(task);
        return task;
    }

    public synchronized void registerCloseable(AutoCloseable closeable) {
        Objects.requireNonNull(closeable, "closeable");
        if (!active.get()) {
            closeQuietly(closeable);
            return;
        }
        closeables.add(closeable);
    }

    public synchronized void registerThread(Thread thread) {
        Objects.requireNonNull(thread, "thread");
        if (!active.get()) {
            thread.interrupt();
            return;
        }
        threads.add(thread);
    }

    public Runnable guard(Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            synchronized (callbackMonitor) {
                if (!active.get()) {
                    return;
                }
                runningCallbacks++;
            }
            try {
                task.run();
            } catch (Throwable throwable) {
                logger.severe("Fehler in einem Task des Live-Moduls: " + message(throwable));
            } finally {
                synchronized (callbackMonitor) {
                    runningCallbacks--;
                    callbackMonitor.notifyAll();
                }
            }
        };
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }

        // Stop callbacks first. This prevents a repeating task from producing
        // new work while listeners and external resources are being released.
        for (BukkitTask task : tasks) {
            try {
                task.cancel();
            } catch (RuntimeException exception) {
                logger.warning("Modul-Task konnte nicht beendet werden: " + message(exception));
            }
        }
        tasks.clear();

        waitForCallbacks();

        for (Listener listener : listeners) {
            try {
                HandlerList.unregisterAll(listener);
            } catch (RuntimeException exception) {
                logger.warning("Listener konnte nicht abgemeldet werden: " + message(exception));
            }
        }
        listeners.clear();

        List<AutoCloseable> reverse = new ArrayList<>(closeables);
        java.util.Collections.reverse(reverse);
        for (AutoCloseable closeable : reverse) {
            closeQuietly(closeable);
        }
        closeables.clear();

        for (Thread thread : threads) {
            if (thread.isAlive()) {
                thread.interrupt();
            }
        }
        for (Thread thread : threads) {
            if (!thread.isAlive()) {
                continue;
            }
            try {
                thread.join(1_500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            if (thread.isAlive()) {
                logger.warning("Modul-Thread beendet sich nicht innerhalb des Stop-Timeouts: " + thread.getName());
            }
        }
        threads.clear();
    }

    private void waitForCallbacks() {
        long deadline = System.nanoTime() + 2_000_000_000L;
        synchronized (callbackMonitor) {
            while (runningCallbacks > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    logger.warning("Nicht alle laufenden Modul-Callbacks endeten innerhalb des Stop-Timeouts.");
                    return;
                }
                try {
                    long millis = Math.max(1L, remaining / 1_000_000L);
                    callbackMonitor.wait(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void requireActive() {
        if (!active.get()) {
            throw new IllegalStateException("Modul-Kontext ist bereits beendet");
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception exception) {
            logger.warning("Modul-Ressource konnte nicht geschlossen werden: " + message(exception));
        }
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }
}
