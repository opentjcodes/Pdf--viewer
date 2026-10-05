package com.gitlab.mudlej.MjPdfReader.ui.home.metadata;

interface IPdfMetadataCallback {
    void onReady(
        long generation,
        long connectionToken,
        int workerPid,
        long workerInstance,
        long baselineNativePssBytes,
        long nativePssBytes,
        long availableBytes,
        long thresholdBytes,
        long totalBytes,
        boolean lowMemory
    );
    void onMemorySample(
        long generation,
        long connectionToken,
        int workerPid,
        long workerInstance,
        long baselineNativePssBytes,
        long nativePssBytes,
        long availableBytes,
        long thresholdBytes,
        long totalBytes,
        boolean lowMemory
    );
    void onResult(
        long generation,
        long connectionToken,
        String path,
        long size,
        long lastModified,
        String hash,
        int pageCount,
        String failureCode,
        int workerPid,
        long workerInstance,
        long baselineNativePssBytes,
        int maxActiveDocuments,
        long nativePssBytes,
        long availableBytes,
        long thresholdBytes,
        long totalBytes,
        boolean lowMemory
    );
    void onAcknowledged(long generation, long connectionToken);
}
