// Written by Mudlej. License is GPLv3.
package com.github.barteksc.pdfviewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ReplayMeasurement {

    public static final String LOG_TAG = "MjPdfReplay";

    private static final ThreadLocal<Session> activeSession = new ThreadLocal<>();
    private static volatile Report lastReport;

    private ReplayMeasurement() {
    }

    public static Session start(long replayId, int editCount) {
        Session session = new Session(replayId, editCount);
        activeSession.set(session);
        return session;
    }

    static Session activeSession() {
        return activeSession.get();
    }

    public static Report finish(Session session, long totalDurationNanos, boolean returned) {
        if (activeSession.get() == session) {
            activeSession.remove();
        }
        Report report = new Report(session, totalDurationNanos, returned);
        lastReport = report;
        return report;
    }

    public static List<String> lastReportLines() {
        Report report = lastReport;
        return report == null ? Collections.<String>emptyList() : report.lines();
    }

    public static final class Session {

        private final long replayId;
        private final int editCount;
        private final String[] editTypes;
        private final int[] editPages;
        private final long[] editDurationsNanos;
        private final boolean[] measuredEdits;
        private final boolean[] returnedEdits;
        private int[] nativeEditIndexes;
        private int[] nativePages;
        private int[] nativeDocumentPages;
        private long[] nativeDurationsNanos;
        private boolean[] nativeReturned;

        private int currentEditIndex = -1;
        private int nativeOpenCount;

        Session(long replayId, int editCount) {
            this.replayId = replayId;
            this.editCount = editCount;
            editTypes = new String[editCount];
            editPages = new int[editCount];
            editDurationsNanos = new long[editCount];
            measuredEdits = new boolean[editCount];
            returnedEdits = new boolean[editCount];
            int nativeCapacity = Math.max(1, editCount);
            nativeEditIndexes = new int[nativeCapacity];
            nativePages = new int[nativeCapacity];
            nativeDocumentPages = new int[nativeCapacity];
            nativeDurationsNanos = new long[nativeCapacity];
            nativeReturned = new boolean[nativeCapacity];
        }

        public void beginEdit(int editIndex, String editType, int page) {
            if (editIndex < 0 || editIndex >= editCount) {
                return;
            }
            currentEditIndex = editIndex;
            editTypes[editIndex] = editType;
            editPages[editIndex] = page;
        }

        public void endEdit(int editIndex, long durationNanos, boolean returned) {
            if (editIndex >= 0 && editIndex < editCount) {
                editDurationsNanos[editIndex] = durationNanos;
                measuredEdits[editIndex] = true;
                returnedEdits[editIndex] = returned;
            }
            currentEditIndex = -1;
        }

        void recordNativeOpen(int page, int documentPage, long durationNanos, boolean returned) {
            if (currentEditIndex < 0) {
                return;
            }
            ensureNativeCapacity();
            int eventIndex = nativeOpenCount++;
            nativeEditIndexes[eventIndex] = currentEditIndex;
            nativePages[eventIndex] = page;
            nativeDocumentPages[eventIndex] = documentPage;
            nativeDurationsNanos[eventIndex] = durationNanos;
            nativeReturned[eventIndex] = returned;
        }

        private void ensureNativeCapacity() {
            if (nativeOpenCount < nativeEditIndexes.length) {
                return;
            }
            int newCapacity = nativeEditIndexes.length * 2;
            nativeEditIndexes = Arrays.copyOf(nativeEditIndexes, newCapacity);
            nativePages = Arrays.copyOf(nativePages, newCapacity);
            nativeDocumentPages = Arrays.copyOf(nativeDocumentPages, newCapacity);
            nativeDurationsNanos = Arrays.copyOf(nativeDurationsNanos, newCapacity);
            nativeReturned = Arrays.copyOf(nativeReturned, newCapacity);
        }
    }

    public static final class Report {

        private final long replayId;
        private final int editCount;
        private final String[] editTypes;
        private final int[] editPages;
        private final long[] editDurationsNanos;
        private final boolean[] measuredEdits;
        private final boolean[] returnedEdits;
        private final int[] nativeEditIndexes;
        private final int[] nativePages;
        private final int[] nativeDocumentPages;
        private final long[] nativeDurationsNanos;
        private final boolean[] nativeReturned;
        private final long totalDurationNanos;
        private final boolean returned;

        private Report(Session session, long totalDurationNanos, boolean returned) {
            this.replayId = session.replayId;
            this.editCount = session.editCount;
            this.editTypes = Arrays.copyOf(session.editTypes, editCount);
            this.editPages = Arrays.copyOf(session.editPages, editCount);
            this.editDurationsNanos = Arrays.copyOf(session.editDurationsNanos, editCount);
            this.measuredEdits = Arrays.copyOf(session.measuredEdits, editCount);
            this.returnedEdits = Arrays.copyOf(session.returnedEdits, editCount);
            this.nativeEditIndexes = Arrays.copyOf(session.nativeEditIndexes, session.nativeOpenCount);
            this.nativePages = Arrays.copyOf(session.nativePages, session.nativeOpenCount);
            this.nativeDocumentPages = Arrays.copyOf(session.nativeDocumentPages, session.nativeOpenCount);
            this.nativeDurationsNanos = Arrays.copyOf(session.nativeDurationsNanos, session.nativeOpenCount);
            this.nativeReturned = Arrays.copyOf(session.nativeReturned, session.nativeOpenCount);
            this.totalDurationNanos = totalDurationNanos;
            this.returned = returned;
        }

        public List<String> lines() {
            List<String> lines = new ArrayList<>(1 + editCount + nativeEditIndexes.length);
            lines.add(totalLine());
            for (int index = 0; index < editCount; index++) {
                if (measuredEdits[index]) {
                    lines.add(editLine(index));
                }
            }
            for (int index = 0; index < nativeEditIndexes.length; index++) {
                lines.add(nativeOpenLine(index));
            }
            return Collections.unmodifiableList(lines);
        }

        private String totalLine() {
            return prefix("total")
                    + " thread=main"
                    + " clock=elapsed_realtime"
                    + " edit_count=" + editCount
                    + " measured_edit_count=" + measuredEditCount()
                    + " edit_types=" + editTypeCounts()
                    + " native_open_count=" + nativeEditIndexes.length
                    + " duration_ns=" + totalDurationNanos
                    + " result=" + result(returned);
        }

        private String editLine(int index) {
            return prefix("edit")
                    + " thread=main"
                    + " clock=elapsed_realtime"
                    + " edit_index=" + index
                    + " edit_count=" + editCount
                    + " edit_type=" + editTypes[index]
                    + " page=" + editPages[index]
                    + " duration_ns=" + editDurationsNanos[index]
                    + " result=" + result(returnedEdits[index]);
        }

        private String nativeOpenLine(int nativeIndex) {
            int editIndex = nativeEditIndexes[nativeIndex];
            return prefix("native_open")
                    + " thread=main"
                    + " clock=elapsed_realtime"
                    + " call=pdfiumCore.openPage"
                    + " cold=true"
                    + " native_index=" + nativeIndex
                    + " edit_index=" + editIndex
                    + " edit_count=" + editCount
                    + " edit_type=" + editTypes[editIndex]
                    + " page=" + nativePages[nativeIndex]
                    + " doc_page=" + nativeDocumentPages[nativeIndex]
                    + " duration_ns=" + nativeDurationsNanos[nativeIndex]
                    + " result=" + result(nativeReturned[nativeIndex]);
        }

        private String prefix(String event) {
            return "schema=annotation_replay_v1 event=" + event + " replay_id=" + replayId;
        }

        private int measuredEditCount() {
            int count = 0;
            for (boolean measured : measuredEdits) {
                if (measured) {
                    count++;
                }
            }
            return count;
        }

        private String editTypeCounts() {
            Map<String, Integer> counts = new TreeMap<>();
            for (int index = 0; index < editTypes.length; index++) {
                if (!measuredEdits[index]) {
                    continue;
                }
                String type = editTypes[index];
                Integer count = counts.get(type);
                counts.put(type, count == null ? 1 : count + 1);
            }
            if (counts.isEmpty()) {
                return "none";
            }
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (out.length() > 0) {
                    out.append(',');
                }
                out.append(entry.getKey()).append(':').append(entry.getValue());
            }
            return out.toString();
        }

        private static String result(boolean returned) {
            return returned ? "returned" : "threw";
        }
    }
}
