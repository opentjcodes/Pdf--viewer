// Written by Mudlej. License is GPLv3.
package com.github.barteksc.pdfviewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class ReplayMeasurementTest {

    public static void main(String[] args) throws Exception {
        ReplayMeasurementTest test = new ReplayMeasurementTest();
        test.lines_haveStableMachineReadableFormatAndReplayCorrelation();
        test.lastReportLines_exposesTheFinishedReportWithoutLogging();
        test.activeSession_isConfinedToTheThreadRunningTheReplay();
        test.oneEdit_canRetainMoreThanOneNativeOpen();
        test.report_isAnIndependentSnapshotWithImmutableLines();
        test.zeroEdits_reportsOnlyTheTotal();
        test.partialReplay_omitsUnfinishedEditsButRetainsTheirNativeOpens();
        test.invalidIndexes_preserveTrackingOnBeginAndClearItOnEnd();
        test.finishOlderSession_keepsTheNewerSessionActive();
    }

    @Test
    public void lines_haveStableMachineReadableFormatAndReplayCorrelation() {
        ReplayMeasurement.Session session = ReplayMeasurement.start(123456L, 2);
        session.beginEdit(0, "Delete", 7);
        session.endEdit(0, 210L, true);
        session.beginEdit(1, "Add", 3);
        session.recordNativeOpen(3, 9, 120L, true);
        session.endEdit(1, 340L, true);

        ReplayMeasurement.Report report = ReplayMeasurement.finish(session, 700L, true);

        assertEquals(
                Arrays.asList(
                        "schema=annotation_replay_v1 event=total replay_id=123456 thread=main "
                                + "clock=elapsed_realtime "
                                + "edit_count=2 measured_edit_count=2 edit_types=Add:1,Delete:1 "
                                + "native_open_count=1 duration_ns=700 result=returned",
                        "schema=annotation_replay_v1 event=edit replay_id=123456 thread=main "
                                + "clock=elapsed_realtime "
                                + "edit_index=0 edit_count=2 edit_type=Delete page=7 duration_ns=210 "
                                + "result=returned",
                        "schema=annotation_replay_v1 event=edit replay_id=123456 thread=main "
                                + "clock=elapsed_realtime "
                                + "edit_index=1 edit_count=2 edit_type=Add page=3 duration_ns=340 "
                                + "result=returned",
                        "schema=annotation_replay_v1 event=native_open replay_id=123456 thread=main "
                                + "clock=elapsed_realtime "
                                + "call=pdfiumCore.openPage cold=true native_index=0 edit_index=1 edit_count=2 "
                                + "edit_type=Add page=3 doc_page=9 duration_ns=120 result=returned"
                ),
                report.lines()
        );
    }

    @Test
    public void lastReportLines_exposesTheFinishedReportWithoutLogging() {
        ReplayMeasurement.Session session = ReplayMeasurement.start(42L, 1);
        assertSame(session, ReplayMeasurement.activeSession());
        session.beginEdit(0, "SetNote", 4);
        session.endEdit(0, 11L, false);

        ReplayMeasurement.finish(session, 20L, false);

        assertEquals(
                "schema=annotation_replay_v1 event=total replay_id=42 thread=main "
                        + "clock=elapsed_realtime edit_count=1 "
                        + "measured_edit_count=1 edit_types=SetNote:1 native_open_count=0 "
                        + "duration_ns=20 result=threw",
                ReplayMeasurement.lastReportLines().get(0)
        );
        assertNull(ReplayMeasurement.activeSession());
    }

    @Test
    public void activeSession_isConfinedToTheThreadRunningTheReplay() throws Exception {
        ReplayMeasurement.Session session = ReplayMeasurement.start(100L, 1);
        AtomicReference<ReplayMeasurement.Session> otherThreadSession = new AtomicReference<>();
        Thread otherThread = new Thread(() -> otherThreadSession.set(ReplayMeasurement.activeSession()));

        otherThread.start();
        otherThread.join();

        assertNull(otherThreadSession.get());
        assertSame(session, ReplayMeasurement.activeSession());
        ReplayMeasurement.finish(session, 1L, true);
    }

    @Test
    public void oneEdit_canRetainMoreThanOneNativeOpen() {
        ReplayMeasurement.Session session = ReplayMeasurement.start(200L, 1);
        session.beginEdit(0, "Add", 3);
        session.recordNativeOpen(3, 3, 10L, true);
        session.recordNativeOpen(3, 3, 20L, true);
        session.endEdit(0, 40L, true);

        ReplayMeasurement.Report report = ReplayMeasurement.finish(session, 50L, true);

        assertEquals(2, report.lines().stream().filter(line -> line.contains("event=native_open")).count());
        assertTrue(report.lines().get(0).contains("native_open_count=2"));
        assertTrue(report.lines().get(3).contains("native_index=1"));
    }

    @Test
    public void report_isAnIndependentSnapshotWithImmutableLines() {
        ReplayMeasurement.Session session = ReplayMeasurement.start(300L, 2);
        session.beginEdit(0, "Delete", 7);
        session.recordNativeOpen(7, 9, 5L, false);
        session.endEdit(0, 10L, false);
        ReplayMeasurement.Report report = ReplayMeasurement.finish(session, 20L, false);

        session.beginEdit(0, "Add", 8);
        session.recordNativeOpen(8, 10, 6L, true);
        session.recordNativeOpen(9, 11, 7L, true);
        session.endEdit(0, 30L, true);
        session.beginEdit(1, "SetNote", 4);
        session.endEdit(1, 40L, true);

        List<String> lines = report.lines();
        assertEquals(Arrays.asList(
                "schema=annotation_replay_v1 event=total replay_id=300 thread=main "
                        + "clock=elapsed_realtime edit_count=2 measured_edit_count=1 "
                        + "edit_types=Delete:1 native_open_count=1 duration_ns=20 result=threw",
                "schema=annotation_replay_v1 event=edit replay_id=300 thread=main "
                        + "clock=elapsed_realtime edit_index=0 edit_count=2 edit_type=Delete "
                        + "page=7 duration_ns=10 result=threw",
                "schema=annotation_replay_v1 event=native_open replay_id=300 thread=main "
                        + "clock=elapsed_realtime call=pdfiumCore.openPage cold=true native_index=0 "
                        + "edit_index=0 edit_count=2 edit_type=Delete page=7 doc_page=9 "
                        + "duration_ns=5 result=threw"
        ), lines);
        assertEquals(lines, ReplayMeasurement.lastReportLines());
        try {
            lines.set(0, "changed");
            fail("Report lines must be immutable");
        } catch (UnsupportedOperationException expected) {
        }

        ReplayMeasurement.Report updatedReport = ReplayMeasurement.finish(session, 80L, true);
        assertTrue(updatedReport.lines().get(0).contains(
                "measured_edit_count=2 edit_types=Add:1,SetNote:1 native_open_count=3"));
        assertEquals(lines, report.lines());
        assertEquals(updatedReport.lines(), ReplayMeasurement.lastReportLines());
    }

    @Test
    public void zeroEdits_reportsOnlyTheTotal() {
        ReplayMeasurement.Session session = ReplayMeasurement.start(400L, 0);
        session.recordNativeOpen(1, 2, 3L, true);

        List<String> lines = ReplayMeasurement.finish(session, 0L, true).lines();

        assertEquals(1, lines.size());
        assertEquals(
                "schema=annotation_replay_v1 event=total replay_id=400 thread=main "
                        + "clock=elapsed_realtime edit_count=0 measured_edit_count=0 "
                        + "edit_types=none native_open_count=0 duration_ns=0 result=returned",
                lines.get(0)
        );
    }

    @Test
    public void partialReplay_omitsUnfinishedEditsButRetainsTheirNativeOpens() {
        ReplayMeasurement.Session session = ReplayMeasurement.start(500L, 3);
        session.beginEdit(0, "Delete", 7);
        session.recordNativeOpen(7, 9, 2L, true);
        session.endEdit(0, 10L, true);
        session.beginEdit(1, "Add", 3);
        session.recordNativeOpen(3, 5, 3L, false);

        List<String> lines = ReplayMeasurement.finish(session, 20L, false).lines();

        assertEquals(4, lines.size());
        assertEquals(
                "schema=annotation_replay_v1 event=total replay_id=500 thread=main "
                        + "clock=elapsed_realtime edit_count=3 measured_edit_count=1 "
                        + "edit_types=Delete:1 native_open_count=2 duration_ns=20 result=threw",
                lines.get(0)
        );
        assertTrue(lines.get(1).contains("event=edit"));
        assertTrue(lines.get(1).contains("edit_index=0 edit_count=3"));
        assertTrue(lines.get(3).contains("event=native_open"));
        assertTrue(lines.get(3).contains(
                "native_index=1 edit_index=1 edit_count=3 edit_type=Add page=3 doc_page=5 "
                        + "duration_ns=3 result=threw"));
    }

    @Test
    public void invalidIndexes_preserveTrackingOnBeginAndClearItOnEnd() {
        ReplayMeasurement.Session session = ReplayMeasurement.start(600L, 1);
        session.recordNativeOpen(99, 99, 99L, true);
        for (int invalidIndex : new int[]{-1, 1}) {
            session.beginEdit(0, "Add", 3);
            session.beginEdit(invalidIndex, "Ignored", 99);
            session.recordNativeOpen(3, 7, 10L, true);
            session.endEdit(invalidIndex, 99L, true);
            session.recordNativeOpen(99, 99, 99L, true);
        }

        List<String> lines = ReplayMeasurement.finish(session, 30L, true).lines();

        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains(
                "measured_edit_count=0 edit_types=none native_open_count=2"));
        for (int index = 0; index < 2; index++) {
            assertTrue(lines.get(index + 1).contains(
                    "native_index=" + index + " edit_index=0 edit_count=1 edit_type=Add "
                            + "page=3 doc_page=7 duration_ns=10 result=returned"));
        }
    }

    @Test
    public void finishOlderSession_keepsTheNewerSessionActive() {
        ReplayMeasurement.Session older = ReplayMeasurement.start(700L, 0);
        ReplayMeasurement.Session newer = ReplayMeasurement.start(701L, 0);

        ReplayMeasurement.Report olderReport = ReplayMeasurement.finish(older, 10L, true);

        assertSame(newer, ReplayMeasurement.activeSession());
        assertEquals(olderReport.lines(), ReplayMeasurement.lastReportLines());

        ReplayMeasurement.Report newerReport = ReplayMeasurement.finish(newer, 20L, true);

        assertNull(ReplayMeasurement.activeSession());
        assertEquals(newerReport.lines(), ReplayMeasurement.lastReportLines());
    }
}
