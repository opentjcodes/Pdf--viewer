package com.gitlab.mudlej.MjPdfReader.ui.home.metadata;

import com.gitlab.mudlej.MjPdfReader.ui.home.metadata.IPdfMetadataCallback;

interface IPdfMetadataService {
    void registerCallback(IPdfMetadataCallback callback, long generation, long connectionToken);
    void sampleMemory(long generation, long connectionToken);
    void process(long generation, long connectionToken, String path, long size, long lastModified, int debugBehavior);
    void acknowledge(long generation, long connectionToken, String path, long size, long lastModified, int action);
}
