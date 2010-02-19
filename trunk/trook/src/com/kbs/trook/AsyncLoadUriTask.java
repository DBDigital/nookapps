package com.kbs.trook;

import android.util.Log;
import android.content.Context;
import com.kbs.backport.AsyncTask;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.File;
import java.io.StringReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.net.URL;
import java.net.URLConnection;
import java.net.HttpURLConnection;


public class AsyncLoadUriTask
    extends AsyncTask<String,String,Reader>
{
    public AsyncLoadUriTask
        (Trook t, CacheManager cmgr, Trook.UriLoadedListener l)
    {
        m_trook = t;
        m_cachemgr = cmgr;
        m_l = l;
    }

    @Override
    protected Reader doInBackground(String... uris)
    {
        if (uris.length != 1) {
            error("Internal error -- need exactly one URL");
            return null;
        }

        m_uri = uris[0];

        // First see if this is a local asset reference
        if (m_uri.startsWith("asset:")) {
            return makeReaderFromAsset();
        }

        // Check for file: urls too
        if (m_uri.startsWith("file:")) {
            // This we want to grab locally
            return makeReaderFromFile();
        }

        // See if we have it in our cache already
        Reader r = maybeCached(m_uri);
        if (r != null) {
            Log.d(TAG, "Returning cached content for "+m_uri);
            return r;
        }

        // Oh well -- have to work for it.
        pp("starting network...");
        Trook.WifiStatus status = m_trook.acquireAndWaitForWifi();
        if (!status.isReady()) {
            error("Feed did not load\n"+m_uri+"\n"+status.getMessage());
            return null;
        }

        // protect the rest in a finally section
        try {
            pp("fetch content...");
            return realRun();
        }
        catch (Throwable th) {
            Log.d(TAG, "Error while fetching "+m_uri, th);
            error("Could not fetch "+m_uri+": "+th);
            return null;
        }
        finally {
            m_trook.releaseWifi();
        }
    }

    private final Reader makeReaderFromFile()
    {
        Log.d(TAG, "Attempting to open file uri: `"+m_uri+"'");
        Uri uri = Uri.parse(m_uri);
        String path = uri.getPath();
        if (path == null) {
            error("Could not find path from `"+m_uri+"'");
            return null;
        }

        File f = new File(path);
        try {
            if (!f.exists()) {
                error("Path not found `"+f+"'");
                return null;
            }

            return
                new BufferedReader
                (new InputStreamReader
                 (new FileInputStream(f)));
        }
        catch (Throwable th) {
            Log.d(TAG, "Failed to open `"+f+"'", th);
            error("Could not open `"+f+": "+th.toString());
            return null;
        }
    }

    private final Reader makeReaderFromAsset()
    {
        String reference =
            m_uri.substring(6); // Strip off "asset:"

        try {
            return
                new BufferedReader
                (new InputStreamReader
                 (m_trook.getResources()
                  .getAssets()
                  .open(reference)));
        }
        catch (IOException ioe) {
            throw new IllegalArgumentException(ioe);
        }
    }

    private final void pp(String m)
    {
        Log.d(TAG, m);
        publishProgress(m);
    }

    private final Reader realRun()
        throws IOException
    {
        URL url = new URL(m_uri);
        InputStream inp = null;
        pp("Fetching...");
        try {
            URLConnection connection = url.openConnection();
            if (!(connection instanceof HttpURLConnection)) {
                error(m_uri+": can only make http calls");
                return null;
            }
            HttpURLConnection huc = (HttpURLConnection) connection;
            connection.setRequestProperty
                ("User-agent", "trook-news-reader");
            huc.connect();
            if (huc.getResponseCode() != 200) {
                error(m_uri+": "+huc.getResponseMessage());
                return null;
            }
            // Read this fully
            ByteArrayOutputStream bout =
                new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            inp = huc.getInputStream();
            int count = 0;
            int totalcount = 0;
            while ((count = inp.read(buf)) > 0) {
                totalcount += count;
                pp("["+totalcount+" bytes]");
                bout.write(buf, 0, count);
            }
            bout.close();
            pp("");
            String contents = bout.toString("utf-8");
            bout = null; // gc, just in case.
            stuffIntoCache(contents);
            return new StringReader(contents);
        }
        finally {
            if (inp != null) {
                try { inp.close(); }
                catch (Throwable th) {}
            }
        }
    }

    @Override
    protected void onProgressUpdate(String... progress)
    {
        for (int i=0; i<progress.length; i++) {
            m_trook.statusUpdate(progress[i]);
        }
    }

    @Override
    protected void onPostExecute(Reader result)
    {
        if (result != null) {
            m_l.uriLoaded(m_uri, result);
        }
        else {
            // Remove any cached views
            m_trook.removeCachedView(m_uri);            
            if (m_error != null) {
                m_trook.displayError(m_error);
            }
        }
    }

    private Reader maybeCached(String uri)
    {
        InputStream is = m_cachemgr.getUri(uri);
        if (is != null) {
            return new InputStreamReader(is);
        }        
        return null;
    }

    private void stuffIntoCache(String contents)
    { m_cachemgr.cacheUri(m_uri, contents); }

    private void error(String msg)
    {
        Log.d(TAG, msg);
        m_error = msg;
    }

    private String m_uri;
    private String m_error = null;
    private final Trook m_trook;
    private final CacheManager m_cachemgr;
    private final Trook.UriLoadedListener m_l;

    private final static String TAG ="async-load-uri";
}
