package com.example.feeddog;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class RulesProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.feeddog.rules";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/rules");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        SharedPreferences prefs = getContext().getSharedPreferences("feeddog_rules", 0);
        String rules = prefs.getString("rules", "[]");
        MatrixCursor cursor = new MatrixCursor(new String[]{"rules"});
        cursor.addRow(new Object[]{rules});
        return cursor;
    }

    @Override
    public String getType(Uri uri) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
