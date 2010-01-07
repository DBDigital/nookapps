package com.kbs.trook;

import com.kbs.backport.AsyncTask;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import android.util.Xml;
import android.util.Log;

import java.util.Date;
import java.io.Reader;
import java.io.IOException;

public class AsyncFeedParserTask
    extends AsyncTask<Reader,FeedInfo.EntryInfo,String>
{
    public AsyncFeedParserTask(String u, Trook t)
    {
        m_basefile = u;
        m_trook = t;
    }

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

    private final void parseRss(XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "rss");
        p.next();

        while (P.skipToStart(p, null)) {
            String curtag = p.getName();
            // Log.d(TAG, "Current tag is "+curtag);
            if (curtag.equals("channel")) {
                parseChannel(p);
            }
            else {
                // skip everything else
                // Log.d(TAG, "parse-rss - skipping "+curtag);
                P.skipThisBlock(p);
            }
        }
    }

    private final void parseChannel(XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "channel");
        p.next();

        while (P.skipToStart(p, null)) {
            String curtag = p.getName();
            // Log.d(TAG, "Current tag is "+curtag);
            if (curtag.equals("title")) {
                if (m_fi.getTitle() == null) {
                    m_fi.setTitle(P.collectText(p));
                }
                else {
                    m_fi.setTitle(m_fi.getTitle()+", "+
                                  P.collectText(p));
                }
            }
            else if (curtag.equals("item")) {
                parseItem(p);
            }
            else {
                // skip everything else
                // Log.d(TAG, "parse-channel skips "+curtag);
                P.skipThisBlock(p);
            }
        }
    }

    private final void parseItem(XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "item");
        p.next();
        FeedInfo.EntryInfo ei = new FeedInfo.EntryInfo(m_fi);
        while (P.skipToStart(p, null)) {
            String curtag = p.getName();
            if (curtag.equals("title")) {
                ei.setTitle(P.collectText(p));
            }
            else if (curtag.equals("link")) {
                FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
                li.setAttribute("href", P.collectText(p));
                ei.addLink(li);
            }
            else if (curtag.equals("description")) {
                ei.setContent(P.collectText(p));
            }
            else if (curtag.equals("content") &&
                     "image".equals
                     (P.getAttribute(p, "medium")) &&
                     (P.getAttribute(p, "url") != null)) {
                ei.setIconUri(P.getAttribute(p, "url"));
                P.skipThisBlock(p);
            }
            else if (curtag.equals("content") &&
                     "audio".equals
                     (P.getAttribute(p, "medium")) &&
                     ( "audio/mp3".equals(P.getAttribute(p,"type"))||
                       "audio/mpeg".equals(P.getAttribute(p, "type"))) &&
                     (P.getAttribute(p, "url") != null)) {
                FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
                li.setAttribute("href", P.getAttribute(p, "url"));
                li.setAttribute("type", "audio/mp3");
                ei.addLink(li);
                P.skipThisBlock(p);
            }
            else if (curtag.equals("enclosure") &&
                     ("audio/mp3".equals(P.getAttribute(p, "type")) ||
                      "audio/mpeg".equals(P.getAttribute(p, "type"))) &&
                     (P.getAttribute(p, "url")) != null) {
                FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
                li.setAttribute("href", P.getAttribute(p, "url"));
                li.setAttribute("type", "audio/mp3");
                ei.addLink(li);
                P.skipThisBlock(p);
            }
            else {
                P.skipThisBlock(p);
            }
            P.skipToSETag(p);
            if (p.getEventType() == XmlPullParser.END_TAG) {
                // Log.d(TAG, "parse-item in end tag with "+p.getName());
                if (p.getName().equals("item")) {
                    m_fi.addEntry(ei);
                    p.next();
                    publishProgress(ei);
                    // Log.d(TAG, "published one entry");
                    return;
                }
            }
            else {
                // Log.d(TAG, "parse-item continues with "+p.getEventType());
            }
        }
    }

    private final void parseFeed(XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "feed");
        p.next();
        while (P.skipToStart(p, null)) {
            String curtag = p.getName();
            // Log.d(TAG, "Current tag is "+curtag);
            if (curtag.equals("id")) {
                m_fi.setId(P.collectText(p));
            }
            else if (curtag.equals("title")) {
                m_fi.setTitle(P.collectText(p));
            }
            else if (curtag.equals("updated")) {
                m_fi.setUpdated(P.parseTime(p));
            }
            else if (curtag.equals("icon")) {
                m_fi.setIconUri(P.collectText(p));
            }
            else if (curtag.equals("author")) {
                parseAuthor(p);
            }
            else if (curtag.equals("entry")) {
                parseEntry(p);
            }
            else {
                // skip everything else
                P.skipThisBlock(p);
            }
        }
    }

    private final void parseAuthor(XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "author");
        P.skipThisBlock(p);
    }

    private final void parseEntry(XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "entry");

        int type = p.next();

        FeedInfo.EntryInfo ei = new FeedInfo.EntryInfo(m_fi);

        while (type != XmlPullParser.END_DOCUMENT) {

            if (type == XmlPullParser.START_TAG) {
                String curtag = p.getName();
                // Log.d(TAG, "entry: tag = "+curtag);
                if ("title".equals(curtag)) {
                    ei.setTitle(P.collectText(p));
                }
                else if ("updated".equals(curtag)) {
                    ei.setUpdated(P.parseTime(p));
                }
                else if ("id".equals(curtag)) {
                    ei.setId(P.collectText(p));
                }
                else if ("link".equals(curtag)) {
                    ei.addLink(parseLink(p));
                }
                else if ("content".equals(curtag)) {
                    ei.setContent(P.collectText(p));
                }
                else if (curtag.equals("summary")) {
                    ei.setSummary(P.collectText(p));
                }
                else {
                    P.skipThisBlock(p);
                }
                type = p.getEventType();
            }
            else if (type == XmlPullParser.END_TAG) {
                if (p.getName().equals("entry")) {
                    m_fi.addEntry(ei);
                    p.next();
                    publishProgress(ei);
                    return;
                }
                else {
                    Log.d(TAG, "hmm, weird. end-tag "+p.getName());
                    // Unexpected -- but just skip
                    type = p.next();
                }
            }
            else {
                // skip
                type = p.next();
            }
        }
        // hm, reached end without parsing -- just return
        Log.d(TAG, "Bopped off the end of an entry");
    }

    private final FeedInfo.LinkInfo parseLink(XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "link");
        FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
        for (int i=p.getAttributeCount()-1; i>=0; i--) {
            li.setAttribute(p.getAttributeName(i),
                            p.getAttributeValue(i));
        }
        P.skipThisBlock(p);
        return li;
    }

    private boolean parse(Reader inp)
        throws IOException, XmlPullParserException
    {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(inp);
        P.skipToStart(p, null);
        // Here we switch between rss and atom
        if (p.getName().equals("feed")) {
            parseFeed(p);
            return true;
        }
        else if (p.getName().equals("rss")) {
            parseRss(p);
            return true;
        }
        else {
            Log.d(TAG, "Unknown feed -- bailing");
            error("Sorry, this is not a valid feed");
            return false;
        }            
    }

    private FeedInfo m_fi;
    private boolean m_pushedTitle = false;
    private final String m_basefile;
    private final Trook m_trook;
    private String m_error;

    private final static String TAG ="async-feed-parser";
}
