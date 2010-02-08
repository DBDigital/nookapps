package com.kbs.trook;

import java.io.IOException;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParser;

public class RssFeedParser
    implements IFeedParser
{
    public boolean canParse(String rootelement)
    {
        return
            "rss".equals(rootelement) ||
            "RDF".equals(rootelement);
    }

    public void parse(XmlPullParser p, IFeedParserListener fpl)
        throws IOException, XmlPullParserException
    {
        // P.assertStart(p, "rss"); or "RDF"
        p.next();

        while (P.skipToStart(p, null)) {
            String curtag = p.getName();
            // Log.d(TAG, "Current tag is "+curtag);
            if (curtag.equals("channel")) {
                parseChannel(p, fpl);
            }
            else {
                // skip everything else
                // Log.d(TAG, "parse-rss - skipping "+curtag);
                P.skipThisBlock(p);
            }
        }
    }

    private final void parseChannel(XmlPullParser p, IFeedParserListener fpl)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "channel");
        p.next();

        while (P.skipToStart(p, null)) {
            String curtag = p.getName();
            // Log.d(TAG, "Current tag is "+curtag);
            if (curtag.equals("title")) {
                FeedInfo fi = fpl.getFeedInfo();
                if (fi.getTitle() == null) {
                    fi.setTitle(P.collectText(p));
                }
                else {
                    fi.setTitle(fi.getTitle()+", "+
                                P.collectText(p));
                }
            }
            else if (curtag.equals("item")) {
                parseItem(p, fpl);
            }
            else {
                // skip everything else
                // Log.d(TAG, "parse-channel skips "+curtag);
                P.skipThisBlock(p);
            }
        }
    }

    private final void parseItem(XmlPullParser p, IFeedParserListener fpl)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "item");
        p.next();
        FeedInfo fi = fpl.getFeedInfo();
        FeedInfo.EntryInfo ei = new FeedInfo.EntryInfo(fi);
        while (P.skipToStart(p, null)) {
            String curtag = p.getName();
            if (curtag.equals("title")) {
                ei.setTitle(P.collectText(p));
            }
            else if (curtag.equals("link")) {
                FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
                li.setAttribute("href", fpl.fix(P.collectText(p)));
                ei.addLink(li);
            }
            else if (curtag.equals("origLink")) { // pheedo special
                FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
                li.setAttribute("href", fpl.fix(P.collectText(p)));
                // backdoor info to displayer that URL is likely
                // to be the best bet.
                li.setAttribute("preferred", "true");
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
                    fi.addEntry(ei);
                    p.next();
                    fpl.publishProgress1(ei);
                    // Log.d(TAG, "published one entry");
                    return;
                }
            }
            else {
                // Log.d(TAG, "parse-item continues with "+p.getEventType());
            }
        }
    }
}
