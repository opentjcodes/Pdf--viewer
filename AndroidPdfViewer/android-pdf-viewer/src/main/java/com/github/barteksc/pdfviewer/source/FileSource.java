/*
 * Copyright (C) 2016 Bartosz Schiller.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer.source;

import android.content.Context;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.io.File;
import java.io.IOException;

public class FileSource implements DocumentSource {

    private File file;

    public FileSource(File file) {
        this.file = file;
    }

    @Override
    public PdfDocument createDocument(Context context, PdfiumCore core, String password) throws IOException {
        ParcelFileDescriptor pfd = openReadDescriptor(context, file);
        return core.newDocument(pfd, password);
    }

    public static ParcelFileDescriptor openReadDescriptor(Context context, File file) throws IOException {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            Uri collection = MediaStore.Files.getContentUri("external");
            try (Cursor cursor = context.getContentResolver().query(collection,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DATA + " = ?",
                    new String[]{file.getCanonicalPath()}, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(0));
                    ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
                    if (descriptor != null) {
                        return descriptor;
                    }
                }
            } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
