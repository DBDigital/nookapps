package com.kbs.trook;

import java.io.Reader;
import java.io.File;

import android.util.Log;
import android.net.Uri;

import com.kbs.trook.adapter.AsyncDirectoryAdapter;

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

        // We first try to figure out the appropriate
        // adapter for this uri
        Uri auri = Uri.parse(uri);
        if ("file".equals(auri.getScheme())) {
            // If it's a directory, use the DirectoryAdapter
            File f = new File(auri.getPath());
            if (f.isDirectory()) {
                asyncLoadDirectoryAdapter(uri);
                return;
            }
        }

        // Default: try to pick up a Reader from it, for
        // further parsing.

        new AsyncLoadUriTask
            (m_trook, m_cachemgr, new Trook.UriLoadedListener() {
                    public void uriLoaded(String uri, Reader result) {
                        m_trook.asyncLoadFeedFromReader(uri, result);
                    }
                })
            .execute(uri);
    }

    // Only call from UI thread.
    public void asyncLoadDirectoryAdapter(String uri)
    {
        Log.d(TAG, "spawn a directory read task for "+uri);
        new AsyncDirectoryAdapter(m_trook).execute(uri);
    }

    // Only call from UI thread. This will create
    // an async task that will get the OpenSearch file
    // describing any search templates available
    // for the given URI
    public void asyncLoadOpenSearchFromUri
        (final FeedInfo fi, final String master, final String uri)
    {
        Log.d(TAG, "fetching opensearch for "+uri+", for "+master);
        new AsyncLoadUriTask
            (m_trook, m_cachemgr, new Trook.UriLoadedListener() {
                    public void uriLoaded(String ruri, Reader result) {
                        m_trook.asyncParseOpenSearch(fi, master, ruri, result);
                    }
                })
            .execute(uri);
    }

    // This should only be called from the UI thread.
    // It will create an async task that will actually
    // populate the interface.
    public void asyncLoadFeedFromReader(String uri, Reader r)
    { 
        Log.d(TAG, "spawning a reader task for "+uri);

        // I fix nytimes feeds to get the full page,
        // otherwise I don't see the full content.
        //
        // Sorry 'bout this.
        if ((uri != null) &&
            (uri.startsWith("http://www.nytimes.com/services/xml/rss"))) {
            new AsyncFeedParserTask(uri, m_trook, m_nytimesfixer)
                .execute(r);
        }
        else if ("http://www.instapaper.com/special/wikipedia_featured_rss"
                 .equals(uri)) {
            new AsyncFeedParserTask(uri, m_trook, m_wikifixer)
                .execute(r);
        }
        else if ((uri != null) &&
                 (uri.startsWith("http://toolserver.org/"))) {
            new AsyncFeedParserTask(uri, m_trook, m_wikifixer)
                .execute(r);
        }
        else {
            new AsyncFeedParserTask(uri, m_trook)
                .execute(r);
        }
    }

    public void asyncParseOpenSearch
        (FeedInfo fi, String master, String osuri, Reader r)
    {
        Log.d(TAG, "spawning an opensearchparse task for "+osuri+", ->"+master);
        new AsyncOpenSearchParserTask
            (fi, m_trook, osuri)
            .execute(r);
    }

    public void shutdown()
    {
        m_cachemgr.close();
    }

    public void removeCachedContent(String uri)
    { m_cachemgr.clearUri(uri); }

    private final ILinkFixer m_nytimesfixer =
        new ILinkFixer() {
            public String fix(String uri)
            {
                if ((uri != null) &&
                    (uri.startsWith("http://www.nytimes.com/20"))) {
                    if (uri.indexOf('?') > 0) {
                        // can use pagewanted=all, to get images too.
                        Log.d(TAG, "returns "+uri+"&pagewanted=all");
                        return uri+"&pagewanted=all";
                    }
                    else {
                        return uri+"?pagewanted=all";
                    }
                }
                else {
                    return uri;
                }
            }
        };

    private final ILinkFixer m_wikifixer =
        new ILinkFixer() {
            public String fix(String uri)
            {
                if ((uri != null) &&
                    (uri.startsWith("http://en.wikipedia.org/"))) {
                    if (uri.indexOf('?') > 0) {
                        // don't mess with these
                        return uri;
                    }
                    else {
                        return uri+"?printable=yes";
                    }
                }
                else {
                    return uri;
                }
            }
        };


    private final CacheManager m_cachemgr;
    private final Trook m_trook;
    private final static String TAG = "feed-manager";
    private final static int TIMEOUT_WAIT = 60*1000;
}
