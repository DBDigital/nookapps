package com.kbs.trook;

import com.kbs.backport.AsyncTask;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import android.util.Xml;
import android.content.res.Resources;
import android.util.Log;
import java.net.URI;
import org.apache.http.client.utils.URIUtils;

import java.util.Date;
import java.io.Reader;
import java.io.IOException;

public class AsyncFeedParserTask
    extends AsyncTask<Reader,FeedInfo.EntryInfo,String>
    implements IFeedParserListener
{
    public AsyncFeedParserTask(String u, Trook t, ILinkFixer fixer)
    {
        m_basefile = u;
        m_resolvepath = u;
        m_trook = t;
        m_fixer = fixer;
    }
    public AsyncFeedParserTask(String u, Trook t)
    { this(u, t, null); }

    @Override
    protected String doInBackground(Reader... inps)
    {
        if (inps.length != 1) {
            error("Sorry -- some internal error ("+inps.length+")");
            return null;
        }

        m_fi = new FeedInfo(m_basefile);
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
    protected void onProgressUpdate(FeedInfo.EntryInfo... s)
    {
        if (!m_pushedTitle) {
            m_trook.addFeedInfo(m_fi);
            m_pushedTitle = true;
        }

        if (m_opensearchurl != null) {
            m_trook.asyncLoadOpenSearchFromUri
                (m_fi, m_basefile, m_opensearchurl);
            m_opensearchurl = null;
        }
        if (m_stanzasearchurl != null) {
            FeedInfo.SearchInfo si =
                new FeedInfo.SearchInfo
                (m_fi, m_stanzasearchurl);
            m_fi.setSearch(si);
            m_trook.setSearch(si);
            m_stanzasearchurl = null;
        }
        if (s == null) {
            return;
        }

        for (int i=0; i<s.length; i++) {
            m_trook.addFeedEntry(s[i]);
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
        Log.d(TAG, m_basefile +": failed to load\n"+msg);
        m_error = m_basefile +": failed to load\n"+msg;
    }

    public final String fix(String fix)
    {
        if (m_fixer != null) {
            return  m_fixer.fix(fix);
        }
        else {
            return fix;
        }
    }

    private boolean parse(Reader inp)
        throws IOException, XmlPullParserException
    {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(inp);
        P.skipToStart(p, null);
        String rootname = p.getName();
        for (int i=0; i<s_parsers.length; i++) {
            if (s_parsers[i].canParse(rootname)) {
                s_parsers[i].parse(p, this);
                return true;
            }
        }
        Log.d(TAG, "Unknown feed -- bailing");
        error("Sorry, this is not a valid feed");
        return false;
    }

    public FeedInfo getFeedInfo()
    { return m_fi; }

    public String getStringResource(int v)
    { return m_trook.getResources().getString(v); }

    public void log(String cl, String m)
    { Log.d(cl, m); }

    public void log(String cl, String m, Throwable t)
    { Log.d(cl, m, t); }

    public void setResolvePath(String s)
    { m_resolvepath = s; }

    public String getResolvePath()
    { return m_resolvepath; }
 
    public void setStanzaSearchUrl(String s)
    { m_stanzasearchurl = s; }

    public void setOpenSearchUrl(String s)
    { m_opensearchurl = s; }

    public void publishProgress1(FeedInfo.EntryInfo... v)
    { publishProgress(v); }

    private FeedInfo m_fi;
    private boolean m_pushedTitle = false;
    private String m_opensearchurl = null;
    private String m_stanzasearchurl = null;
    private final String m_basefile;
    private String m_resolvepath;
    private final Trook m_trook;
    private String m_error;
    private final ILinkFixer m_fixer;

    private final static String TAG ="async-feed-parser";

    private final static IFeedParser[] s_parsers;
    static
    {
        s_parsers = new IFeedParser[3];
        int idx = 0;

        s_parsers[idx++] = new AtomFeedParser();
        s_parsers[idx++] = new RssFeedParser();
        s_parsers[idx++] = new WikiSearchParser();
    }
}
