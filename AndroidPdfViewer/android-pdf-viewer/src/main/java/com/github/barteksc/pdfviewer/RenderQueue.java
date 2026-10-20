// Written by Mudlej. License is GPLv3.
package com.github.barteksc.pdfviewer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

class RenderQueue {

    enum WaveKind { LOAD, SNAPSHOT }

    interface GenerationSource {
        int generationOf(int page);
    }

    interface IdleProducer {
        RenderTask produce();
    }

    private final GenerationSource generationSource;
    private final List<RenderTask> queued = new ArrayList<>();

    private RenderTask inFlight;
    private int inFlightPriority;
    private boolean inFlightOrphanPending;

    private long liveWaveId;
    private WaveKind liveWaveKind;
    private long nextSeq;
    private boolean interactionActive;
    private boolean stopped;
    private volatile boolean flinging;
    private IdleProducer idleProducer;
    private int debugOpenWaves;

    RenderQueue(GenerationSource generationSource) {
        this.generationSource = generationSource;
    }

    synchronized void beginWave(WaveKind kind) {
        if (stopped) {
            return;
        }
        if (PDFView.isDebugStateEnabled()) {
            debugOpenWaves++;
        }
        liveWaveId++;
        liveWaveKind = kind;
        if (inFlight != null && inFlight.kind != RenderTask.Kind.PREWARM) {
            inFlightOrphanPending = true;
        }
    }

    synchronized void endWave() {
        if (stopped) {
            return;
        }
        if (debugOpenWaves > 0) {
            debugOpenWaves--;
        }
        if (inFlightOrphanPending && inFlight != null) {
            inFlight.cancelFromQueue();
            inFlightOrphanPending = false;
        }
    }

    synchronized boolean debugWaveOpen() {
        return debugOpenWaves > 0;
    }

    synchronized int debugPendingTiles() {
        int pending = 0;
        for (RenderTask task : queued) {
            if (task.kind == RenderTask.Kind.TILE) {
                pending++;
            }
        }
        if (inFlight != null && inFlight.kind == RenderTask.Kind.TILE) {
            pending++;
        }
        return pending;
    }

    synchronized void submit(RenderTask task) {
        submitLocked(task);
    }

    private void submitLocked(RenderTask task) {
        if (stopped) {
            return;
        }
        task.generation = generationSource.generationOf(task.page);

        if (inFlight != null && task.kind != RenderTask.Kind.PREWARM && canAbsorb(inFlight, task)) {
            inFlight.waveId = liveWaveId;
            inFlightOrphanPending = false;
            if (task.priorityClass < inFlightPriority) {
                inFlightPriority = task.priorityClass;
            }
            notifyAll();
            return;
        }
        if ((task.priorityClass == RenderTask.P0 || task.priorityClass == RenderTask.P1)
                && inFlight != null && inFlightPriority == RenderTask.P2) {
            inFlight.cancelFromQueue();
        }
        for (Iterator<RenderTask> iterator = queued.iterator(); iterator.hasNext(); ) {
            RenderTask candidate = iterator.next();
            if (!candidate.equivalentTo(task)) {
                continue;
            }
            if (task.priorityClass < candidate.priorityClass) {
                iterator.remove();
                break;
            }
            candidate.waveId = liveWaveId;
            notifyAll();
            return;
        }

        task.waveId = liveWaveId;
        task.seq = nextSeq++;
        queued.add(task);
        notifyAll();
    }

    private boolean canAbsorb(RenderTask holder, RenderTask incoming) {
        return !holder.isQueueCancelled() && holder.equivalentTo(incoming);
    }

    RenderTask pollNext() {
        while (true) {
            synchronized (this) {
                if (stopped) {
                    return null;
                }
                RenderTask task = selectAndRemove();
                if (task != null) {
                    return task;
                }
            }
            RenderTask produced = produceWhenIdle();
            synchronized (this) {
                if (stopped) {
                    return null;
                }
                if (produced != null) {
                    submitLocked(produced);
                }
                RenderTask task = selectAndRemove();
                if (task != null) {
                    return task;
                }
                if (produced != null) {
                    continue;
                }
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    RenderTask pollNextNow() {
        synchronized (this) {
            if (stopped) {
                return null;
            }
            RenderTask task = selectAndRemove();
            if (task != null) {
                return task;
            }
        }
        RenderTask produced = produceWhenIdle();
        synchronized (this) {
            if (stopped) {
                return null;
            }
            if (produced != null) {
                submitLocked(produced);
            }
            return selectAndRemove();
        }
    }

    private RenderTask produceWhenIdle() {
        IdleProducer producer;
        synchronized (this) {
            if (stopped || interactionActive || flinging || idleProducer == null) {
                return null;
            }
            producer = idleProducer;
        }
        return producer.produce();
    }

    private RenderTask selectAndRemove() {
        RenderTask bestP0 = null;
        RenderTask bestP1 = null;
        RenderTask bestP2 = null;
        Iterator<RenderTask> iterator = queued.iterator();
        while (iterator.hasNext()) {
            RenderTask task = iterator.next();
            if (task.kind != RenderTask.Kind.PREWARM) {
                if (task.waveId != liveWaveId) {
                    iterator.remove();
                    continue;
                }
                if (task.generation != generationSource.generationOf(task.page)) {
                    iterator.remove();
                    continue;
                }
            }
            switch (task.priorityClass) {
                case RenderTask.P0:
                    if (bestP0 == null || task.seq < bestP0.seq) {
                        bestP0 = task;
                    }
                    break;
                case RenderTask.P1:
                    if (bestP1 == null || task.seq < bestP1.seq) {
                        bestP1 = task;
                    }
                    break;
                default:
                    if (bestP2 == null || task.seq < bestP2.seq) {
                        bestP2 = task;
                    }
                    break;
            }
        }

        RenderTask chosen;
        if (bestP0 != null) {
            chosen = bestP0;
        } else if (bestP1 != null) {
            chosen = bestP1;
        } else if (bestP2 != null && !interactionActive) {
            chosen = bestP2;
        } else {
            chosen = null;
        }

        if (chosen != null) {
            queued.remove(chosen);
            inFlight = chosen;
            inFlightPriority = chosen.priorityClass;
            inFlightOrphanPending = false;
        }
        return chosen;
    }

    synchronized void completed(RenderTask task) {
        if (inFlight == task) {
            inFlight = null;
            inFlightOrphanPending = false;
        }
    }

    synchronized void cancelPage(int page) {
        if (inFlight != null && inFlight.page == page) {
            inFlight.cancelFromQueue();
        }
    }

    synchronized void setInteractionActive(boolean active) {
        if (interactionActive == active) {
            return;
        }
        interactionActive = active;
        if (!active) {
            notifyAll();
        }
    }

    synchronized void setFlinging(boolean value) {
        if (flinging == value) {
            return;
        }
        flinging = value;
        if (!value) {
            notifyAll();
        }
    }

    synchronized void setIdleProducer(IdleProducer producer) {
        this.idleProducer = producer;
    }

    synchronized void stop() {
        stopped = true;
        if (inFlight != null) {
            inFlight.cancelFromQueue();
        }
        queued.clear();
        notifyAll();
    }
}
