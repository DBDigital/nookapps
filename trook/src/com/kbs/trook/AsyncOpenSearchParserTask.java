package com.kbs.trook;

import com.kbs.backport.AsyncTask;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import android.util.Xml;
import android.util.Log;

import java.util.Date;
import java.io.Reader;
import java.io.IOException;

public class AsyncOpenSearchParserTask
    extends AsyncTask<Reader,FeedInfo.SearchInfo,String>
{
    public AsyncOpenSearchParserTask(FeedInfo fi, Trook t, String uri)
    {
        m_fi = fi;
        m_trook = t;
        m_uri = uri;
    }

    @Override
    protected String doInBackground(Reader... inps)
    {
        Log.d(TAG, "Starting search parse for "+m_uri);
        if (inps.length != 1) {
            error("Sorry -- some internal error ("+inps.length+")");
            return null;
        }

        try {
            if (parse(inps[0])) {
                return "ok";
            }
            else {
                return null;
            }
        }
        catch (Throwable th) {
            Log.d(TAG, "Failed to parse feed", th);
            error("Sorry, failed to parse feed\n"+th.toString());
            return null;
        }
        finally {
            try { inps[0].close(); }
            catch (Throwable ignore) {}
        }
    }

    @Override
    protected void onProgressUpdate(FeedInfo.SearchInfo... s)
    {
        if (s == null) {
            return;
        }

        for (int i=0; i<s.length; i++) {
            m_trook.setSearch(s[i]);
        }
    }

    @Override
    protected void onPostExecute(String v)
    {
        m_trook.statusUpdate(null);
        if ((v == null) && (m_error != null)) {
            m_trook.displayError(m_error);
        }
    }

    private final void error(String msg)
    {
        Log.d(TAG, m_uri +": failed to load\n"+msg);
        m_error = m_uri +": failed to load\n"+msg;
    }

    private final void parseOpenSearchDescription(XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "OpenSearchDescription");
        p.next();

        while (P.skipToStart(p, null)) {
            String curtag = p.getName();
            // Log.d(TAG, "Current tag is "+curtag);
            if (curtag.equals("Url")) {
                parseUrl(p);
            }
            else {
                // skip everything else
                Log.d(TAG, "parse-search - skipping "+curtag);
                P.skipThisBlock(p);
            }
        }
    }

    private final void parseUrl(XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "Url");
        if ("application/atom+xml".equals(P.getAttribute(p, "type")) &&
            (P.getAttribute(p, "template") != null)) {
            FeedInfo.SearchInfo si =
                new FeedInfo.SearchInfo
                (m_fi, P.getAttribute(p, "template"));
            m_fi.setSearch(si);
            publishProgress(si);
        }
        p.next();
    }

    private boolean parse(Reader inp)
        throws IOException, XmlPullParserException
    {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(inp);
        P.skipToStart(p, null);
        parseOpenSearchDescription(p);
        return true;
    }

    private FeedInfo m_fi;
    private final Trook m_trook;
    private final String m_uri;
    private String m_error;

    private final static String TAG ="async-open-search-parser";
}
