package com.kbs.trook;

import java.io.Reader;

import android.util.Log;

import com.kbs.backport.AsyncTask;

public class FeedManager
{
    public FeedManager(Trook t)
    {
        m_trook = t;
        m_cachemgr = new CacheManager(t);
    }

    // Should only be called from the UI thread.
    // It will create an async task that handles
    // the mechanics of loading things from the
    // network and/or from cached filesystem data.
    public void asyncLoadFeedFromUri(String uri)
    {
        Log.d(TAG, "spawning a uri task for "+uri);
        new AsyncLoadUriTask(m_trook, m_cachemgr).execute(uri);
    }

    // This should only be called from the UI thread.
    // It will create an async task that will actually
    // populate the interface.
    public void asyncLoadFeedFromReader(String uri, Reader r)
    { 
        Log.d(TAG, "spawning a reader task for "+uri);
        new AsyncFeedParserTask(uri, m_trook).execute(r);
    }

    public void shutdown()
    {
        m_cachemgr.close();
    }

    public void removeCachedContent(String uri)
    { m_cachemgr.clearUri(uri); }

    private final CacheManager m_cachemgr;
    private final Trook m_trook;
    private final static String TAG = "feed-manager";
    private final static int TIMEOUT_WAIT = 60*1000;
}
