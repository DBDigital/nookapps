package com.kbs.trook;

import java.io.IOException;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParser;

import java.net.URI;
import org.apache.http.client.utils.URIUtils;

import android.util.Log;

public class AtomFeedParser
    implements IFeedParser
{
    public boolean canParse(String rootelement)
    { return "feed".equals(rootelement); }

    public void parse(XmlPullParser p, IFeedParserListener fpl)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "feed");
        p.next();

        FeedInfo.EntryInfo nxt = null;
        FeedInfo fi = fpl.getFeedInfo();

        while (P.skipToStart(p, null)) {
            String curtag = p.getName();
            // Log.d(TAG, "Current tag is "+curtag);
            if (curtag.equals("id")) {
                fi.setId(P.collectText(p));
            }
            else if (curtag.equals("title")) {
                fi.setTitle(P.collectText(p));
            }
            else if (curtag.equals("updated")) {
                fi.setUpdated(P.parseTime(p));
            }
            else if (curtag.equals("icon")) {
                fi.setIconUri(P.collectText(p));
            }
            else if (curtag.equals("author")) {
                parseAuthor(p, fpl);
            }
            else if (curtag.equals("entry")) {
                parseEntry(p, fpl);
            }
            else if (curtag.equals("link")) {
                FeedInfo.LinkInfo li = parseLink(p);
                // Log.d(TAG, "Got link :"+li.toString());
                if ("next".equals(li.getAttribute("rel")) &&
                    (li.getAttribute("href") != null)) {
                    // Log.d(TAG, "Found a next tag!");
                    // Defer this to the end, by making a virtual
                    // entry info that contains just this link.
                    nxt = new FeedInfo.EntryInfo(fi);
                    nxt.setId(li.getAttribute("href"));
                    nxt.addLink(li);
                    String ttl = li.getAttribute("title");
                    if (ttl != null) {
                        nxt.setTitle(ttl);
                    }
                    else {
                        nxt.setTitle
                            (fpl.getStringResource(R.string.next_title));
                    }
                    nxt.setContent("");
                }
                else if ("self".equals(li.getAttribute("rel")) &&
                         (li.getAttribute("href") != null)) {
                    fpl.setResolvePath(li.getAttribute("href"));
                }
                // Feedbooks uses opensearch
                else if (isOpenSearchLink(li)) {
                    // Log.d(TAG, "Found an OpenSearch tag!");
                    try {
                        URI base = new URI(fpl.getResolvePath());
                        URI sref =
                            URIUtils.resolve(base, li.getAttribute("href"));
                        fpl.setOpenSearchUrl(sref.toString());
                        // This is a very goofy way to do this, I'm sorry
                        fpl.publishProgress1((FeedInfo.EntryInfo[])null);
                    }
                    catch (Throwable ig) {
                        Log.d(TAG, "Ignoring search error", ig);
                    }
                }
                // lexcycle/stanza embeds it directly, simpler...
                else if (isStanzaSearchLink(li)) {
                    // Log.d(TAG, "Found a stanza search link");
                    fpl.setStanzaSearchUrl(li.getAttribute("href"));
                    fpl.publishProgress1((FeedInfo.EntryInfo[])null);
                }
            }
            else {
                // skip everything else
                P.skipThisBlock(p);
            }
        }

        // At the end, append a "next" EntryInfo, if we found one
        // along the way.
        if (nxt != null) {
            // Log.d(TAG, "ADDED a next entry!!!");
            fi.addEntry(nxt);
            fpl.publishProgress1(nxt);
        }
    }

    private final void parseAuthor(XmlPullParser p, IFeedParserListener fpl)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "author");
        P.skipThisBlock(p);
    }

    private final void parseEntry(XmlPullParser p, IFeedParserListener fpl)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "entry");

        int type = p.next();

        FeedInfo fi = fpl.getFeedInfo();

        FeedInfo.EntryInfo ei = new FeedInfo.EntryInfo(fi);

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
                    FeedInfo.LinkInfo li = parseLink(p);
                    ei.addLink(li);
                    // Log.d(TAG, "adding link "+li.toString());
                    if (isThumbnailLink(li)) {
                        ei.setIconUri(li.getAttribute("href"));
                    }
                }
                else if ("content".equals(curtag)) {
                    ei.setContent(P.collectText(p));
                }
                else if (curtag.equals("summary")) {
                    ei.setSummary(P.collectText(p));
                }
                else if ("author".equals(curtag)) {
                    parseEntryAuthor(ei, p);
                }
                else {
                    P.skipThisBlock(p);
                }
                type = p.getEventType();
            }
            else if (type == XmlPullParser.END_TAG) {
                if (p.getName().equals("entry")) {
                    fi.addEntry(ei);
                    p.next();
                    fpl.publishProgress1(ei);
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

    private final void parseEntryAuthor(FeedInfo.EntryInfo ei, XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "author");
        int type = p.next();
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                String curtag = p.getName();
                if ("name".equals(curtag)) {
                    ei.setAuthor(P.collectText(p).trim());
                }
                else {
                    P.skipThisBlock(p);
                }
                type = p.getEventType();
            }
            else if (type == XmlPullParser.END_TAG) {
                if (p.getName().equals("author")) {
                    p.next();
                    return;
                }
                else {
                    type = p.next();
                }
            }
            else {
                type = p.next();
            }
        }                
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


    private final static boolean isOpenSearchLink(FeedInfo.LinkInfo li)
    {
        return
            "search"
            .equals(li.getAttribute("rel")) &&
            "application/opensearchdescription+xml"
            .equals(li.getAttribute("type")) &&
            (li.getAttribute("href") != null);
    }

    private final static boolean isStanzaSearchLink(FeedInfo.LinkInfo li)
    {
        return
            "search"
            .equals(li.getAttribute("rel")) &&
            "application/atom+xml"
            .equals(li.getAttribute("type")) &&
            (li.getAttribute("href") != null);
    }

    private final static boolean isThumbnailLink(FeedInfo.LinkInfo li)
    {
        String rel = li.getAttribute("rel");
        return 
            ((rel != null) &&
             ("http://opds-spec.org/thumbnail".equals(rel) ||
              "http://opds-spec.org/opds-cover-image-thumbnail".equals(rel) ||
              "http://opds-spec.org/cover-thumbnail".equals(rel) ||
              "x-stanza-cover-image-thumbnail".equals(rel)));
    }

    private final static String TAG = "atom-feed-parser";
}
