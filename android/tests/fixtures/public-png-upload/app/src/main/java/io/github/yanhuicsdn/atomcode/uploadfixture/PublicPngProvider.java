package io.github.yanhuicsdn.atomcode.uploadfixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Not exported: Android enforces explicit read grants across the UID boundary. */
public final class PublicPngProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { Canaries.index(uri); return "image/png"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        int index = Canaries.index(uri);
        if (selection != null || selectionArgs != null || sortOrder != null) throw new IllegalArgumentException("query-options-not-supported");
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = Canaries.NAMES[index];
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = Canaries.bytes(index).length;
            else throw new IllegalArgumentException("unknown-query-column");
        }
        try { FixtureStore.file(getContext(), index); }
        catch (IOException failure) { throw new IllegalStateException("public-canary-not-valid"); }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(row);
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int index = Canaries.index(uri);
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only-public-canary");
        try { return ParcelFileDescriptor.open(FixtureStore.file(getContext(), index).toFile(), ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (IOException failure) { throw new FileNotFoundException("public-canary-not-valid"); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("read-only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read-only"); }
}
