package com.kbs.trook;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.os.SystemClock;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.Random;

public class CacheManager
{
    private final static String DBNAME = "trook.db";
    private final static int DBVERSION = 1;
    private final static String CACHE_TABLE_NAME = "cache";
    private final static String CACHE_ID = "_id";
    private final static String CACHE_URL = "url";
    private final static String CACHE_UPDATE_DATE = "update_date";
    private final static String CACHE_PATH = "path";
    private final static String TAG="cache-manager";

    private final static String CACHE_QUERY_STMT =
        "select "+CACHE_ID+","+CACHE_PATH+","+CACHE_UPDATE_DATE+
        " from "+CACHE_TABLE_NAME+" where "+CACHE_URL+"=?";
    private final static String CACHE_DELETE_STMT =
        "delete from "+CACHE_TABLE_NAME+" where "+CACHE_ID+"=?";
    private final static String CACHE_UPDATE_STMT =
        "update "+CACHE_TABLE_NAME+" set "+CACHE_UPDATE_DATE+"=? where "
        +CACHE_ID+"=?";
    private final static String CACHE_INSERT_STMT =
        "insert into "+CACHE_TABLE_NAME+"("+
        CACHE_URL+","+CACHE_PATH+","+CACHE_UPDATE_DATE+") values (?,?,?)";

    private final static class DB
        extends SQLiteOpenHelper
    {
        DB(Context ctx)
        { super(ctx, DBNAME, null, DBVERSION); }

        @Override
        public void onCreate(SQLiteDatabase db)
        {
            db.execSQL
                ("create table "+CACHE_TABLE_NAME + "("+
                 CACHE_ID+" integer primary key autoincrement," +
                 CACHE_URL+" text not null,"+
                 CACHE_UPDATE_DATE+" long not null,"+
                 CACHE_PATH+" text not null,"+
                 "unique ("+CACHE_URL+"),"+
                 "unique ("+CACHE_PATH+"))");
        }
        @Override
        public void onUpgrade
            (SQLiteDatabase db, int ov, int nv) {
            Log.w(TAG, "Upgrading database from version " + ov + " to "
                    + nv + ", which will destroy all old data");
            db.execSQL("DROP TABLE IF EXISTS "+CACHE_TABLE_NAME);
            onCreate(db);
        }
    }

    public CacheManager(Context ctx)
    {
        m_ctx = ctx;
        m_helper = new DB(ctx);
    }

    public void close()
    { m_helper.close(); }

    public void cacheUri(String uri, String content)
    {
        try {
            // First see if we have an entry
            SQLiteDatabase db = m_helper.getReadableDatabase();
            String[] qp = {uri};
            Cursor c = db.rawQuery(CACHE_QUERY_STMT, qp);
            String localpath = null;
            long update_ts = -1;
            long id = -1;

            if (c.moveToFirst()) {
                id = c.getLong(0);
                localpath = c.getString(1);
                update_ts = c.getLong(2);
            }
            c.close();

            File lp;

            if (localpath != null) {
                lp = new File(localpath);
                lp.delete();
            }
            else {
                lp = makeRandomFile(m_ctx);
            }

            // Write out the file
            OutputStreamWriter fout =
                new OutputStreamWriter
                (new FileOutputStream(lp));

            fout.write(content);
            fout.close();

            if (id == -1) {
                Object iq[] = {uri,lp.toString(),
                               new Long(System.currentTimeMillis())};
                db.execSQL(CACHE_INSERT_STMT, iq);
            }
            else {
                Object uq[] = {new Long(id),
                               new Long(System.currentTimeMillis())};
                db.execSQL(CACHE_UPDATE_STMT, uq);
            }
        }
        catch (Throwable th) {
            Log.d(TAG, "Ignoring exception while caching", th);
        }
    }

    public void clearUri(String uri)
    {
        try {
            // First see if we have an entry
            SQLiteDatabase db = m_helper.getReadableDatabase();
            String[] qp = {uri};
            Cursor c = db.rawQuery(CACHE_QUERY_STMT, qp);
            String localpath = null;
            long update_ts = -1;
            long id = -1;

            if (c.moveToFirst()) {
                id = c.getLong(0);
                localpath = c.getString(1);
                update_ts = c.getLong(2);
            }
            c.close();

            if (localpath == null) {
                // did not find it.
                return;
            }

            File lp = new File(localpath);
            lp.delete();
            Object dq[] = {new Long(id)};
            db.execSQL(CACHE_DELETE_STMT, dq);
            return;
        }
        catch (Throwable th) {
            Log.d(TAG, "Ignoring exception while caching", th);
            return;
        }
    }

    public InputStream getUri(String uri)
    {
        try {
            // First see if we have an entry
            SQLiteDatabase db = m_helper.getReadableDatabase();
            String[] qp = {uri};
            Cursor c = db.rawQuery(CACHE_QUERY_STMT, qp);
            String localpath = null;
            long update_ts = -1;
            long id = -1;
            
            if (c.moveToFirst()) {
                id = c.getLong(0);
                localpath = c.getString(1);
                update_ts = c.getLong(2);
            }
            c.close();

            if (localpath == null) {
                // did not find it.
                return null;
            }

            File lp = new File(localpath);

            // Check if the timestamp is too old, or
            // for some reason I cannot read the file

            // Timestamping is a less useful technique. The lesser of
            // evils is to just allow the user to explictly update it.

            /*
            if ((update_ts <
                 (System.currentTimeMillis() - 86400*1000)) ||
                (!lp.canRead())) {
            */

            if (!lp.canRead()) {
                // Delete file and db entry
                lp.delete();
                Object dq[] = {new Long(id)};
                db.execSQL(CACHE_DELETE_STMT, dq);
                return null;
            }

            // Finally, return an inputstream
            return new BufferedInputStream
                (new FileInputStream(lp));
        }
        catch (Throwable th) {
            Log.d(TAG, "Ignoring exception while caching", th);
            return null;
        }
    }


    private final static File makeRandomFile(Context ctx)
    {
        File f;
        do {
            f = new File(ctx.getCacheDir(), s_random.nextInt()+".file");
        } while (f.exists());
        return f;
    }

    private final static Random s_random =
        new Random(SystemClock.uptimeMillis());

    private final Context m_ctx;
    private final DB m_helper;
}