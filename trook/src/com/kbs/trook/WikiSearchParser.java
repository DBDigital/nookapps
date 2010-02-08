package com.kbs.trook;

import java.io.IOException;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParser;

import java.net.URI;
import java.net.URLDecoder;

// This seem useful enough to just have a special
// parser for wikipedia. Heads-up -- need to keep on
// tracking the api... nothing is set in store, oh well.

public class WikiSearchParser
    implements IFeedParser
{
    public boolean canParse(String rootelement)
    { return "api".equals(rootelement); }

    public void parse(XmlPullParser p, IFeedParserListener fpl)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "api");
        FeedInfo fi = fpl.getFeedInfo();

        fi.setTitle(guessTitleFrom(fpl));
        p.next();
        guessAndSetStanzaSearchUrl(fpl);

        FeedInfo.EntryInfo nxt = null;

        while (P.skipToStart(p, null)) {
            String curtag = p.getName();
            if (curtag.equals("query")) {
                parseQuery(p, fpl);
            }
            else if (curtag.equals("query-continue")) {
                nxt = parseQueryContinue(p, fpl);
            }
            else {
                // skip everything else
                P.skipThisBlock(p);
            }
        }

        // At the end, append a "next" EntryInfo, if we found one
        // along the way.
        if (nxt != null) {
            fi.addEntry(nxt);
            fpl.publishProgress1(nxt);
        }
    }

    private FeedInfo.EntryInfo parseQueryContinue
        (XmlPullParser p, IFeedParserListener fpl)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "query-continue");
        p.next();

        FeedInfo fi = fpl.getFeedInfo();

        FeedInfo.EntryInfo ret = null;
        while (P.skipToStartWithin(p, "search", "query-continue")) {
            String offset = P.getAttribute(p, "sroffset");
            if (offset != null) {
                String n = guessNextFor(fpl, offset);
                if (n != null) {
                    ret = new FeedInfo.EntryInfo(fi);
                    FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
                    li.setAttribute("type", IMimeConstants.MIME_ATOM_XML);
                    li.setAttribute("href", n);
                    ret.setId(n);
                    ret.addLink(li);
                    ret.setTitle("More results");
                }
            }
            p.next();
        }
        return ret;
    }

    private void parseQuery(XmlPullParser p, IFeedParserListener fpl)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "query");
        p.next();

        FeedInfo fi = fpl.getFeedInfo();

        while (P.skipToStartWithin(p, null, "query")) {
            String curtag = p.getName();
            if (curtag.equals("searchinfo")) {
                String cnt = P.getAttribute(p, "totalhits");
                if (cnt != null) {
                    // tbd
                }
                P.skipThisBlock(p);
            }
            else if (curtag.equals("search")) {
                parseSearch(p, fpl);
            }
            else {
                // skip everything else
                P.skipThisBlock(p);
            }
        }
    }

    private void parseSearch(XmlPullParser p, IFeedParserListener fpl)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "search");
        p.next();

        while (P.skipToStartWithin(p, null, "search")) {
            String curtag = p.getName();
            if (curtag.equals("p")) {
                addEntry(p, fpl);
                p.next();
            }
            else {
                // skip everything else
                P.skipThisBlock(p);
            }
        }
    }

    private void addEntry(XmlPullParser p, IFeedParserListener fpl)
        throws XmlPullParserException, IOException
    {
        
        FeedInfo fi = fpl.getFeedInfo();

        String title = P.getAttribute(p, "title");
        String content = P.getAttribute(p, "snippet");
        String size = P.getAttribute(p, "size");

        if (title != null) {
            String href = guessHrefFor(fpl, title);
            if (href == null) { return; }
            FeedInfo.EntryInfo ei =
                new FeedInfo.EntryInfo(fi);
            ei.setTitle(title);
            ei.setId(href);
            FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
            li.setAttribute("href", href);
            li.setAttribute("type", IMimeConstants.MIME_HTML);
            ei.addLink(li);
            if (content != null) {
                // attempt a bit of hand-fixed parsing.
                content = content.replaceAll
                    ("<span class='searchmatch'>", "<b>");
                content = content.replaceAll
                    ("</span>", "</b>");
                ei.setContent(content);
            }
            fi.addEntry(ei);
            fpl.publishProgress1(ei);
            // fpl.log(TAG, "Added link with "+href);
        }
    }

    private String guessHrefFor(IFeedParserListener fpl, String title)
    {
        // return a fragment that hopefully will resolve to
        // something that works.
        String n = "/wiki/"+title.replace(" ", "_")+"?printable=yes";
        String b = fpl.getResolvePath();
        if (b == null) { return null; }

        try {
            URI base = new URI(fpl.getResolvePath());
            URI hr = base.resolve(n);
            return hr.toString();
        }
        catch (Throwable ign) {
            fpl.log(TAG, "failed on "+title, ign);
            return null;
        }
    }

    private String guessNextFor(IFeedParserListener fpl, String offset)
    {
        // return a fragment that hopefully will resolve to
        // something that works.
        String base = fpl.getResolvePath();

        if (base == null) { return null; }
        int idx = base.indexOf('?');
        if (idx < 0) { return null; }
        if (idx >= base.length()) { return null; }

        String qpart = base.substring(idx+1);
        String v[] = qpart.split("&");
        if ((v == null) || (v.length == 0)) { return null; }

        StringBuffer nqpart = new StringBuffer();
        boolean found = false;

        for (int i=0; i<v.length; i++) {
            String kv[] = v[i].split("=");
            if (kv.length != 2) { return null; }

            if (kv[0].equals("sroffset")) {
                kv[1] = offset;
                found = true;
            }
            if (i > 0) { nqpart.append("&"); }
            nqpart.append(kv[0]);
            nqpart.append("=");
            nqpart.append(kv[1]);
        }
        if (!found) {
            nqpart.append("&sroffset=");
            nqpart.append(offset);
        }
        return base.substring(0, idx)+"?"+nqpart;
    }

    private String guessTitleFrom(IFeedParserListener fpl)
    {
        String rp = fpl.getResolvePath();
        String ret = "Search results";
        if (rp == null) { return ret; }

        try { rp = URLDecoder.decode(rp, "utf-8"); }
        catch (Throwable th) { return ret; }

        // look for a magic string
        int sidx = rp.indexOf("srsearch=");
        if (sidx < 0) { return ret; }

        sidx += 9;
        // sanity check
        if (sidx >= rp.length()) { return ret; }
        int eidx = rp.indexOf('&', sidx);
        if (eidx < 0) { eidx = rp.length(); }

        return "Search: "+rp.substring(sidx, eidx);
    }

    private void guessAndSetStanzaSearchUrl(IFeedParserListener fpl)
    {
        String b = fpl.getResolvePath();
        if (b == null) { return; }

        try {
            URI base = new URI(b);
            URI hr =
                base.resolve
                ("/w/api.php?action=query&amp;list=search&amp;srsearch=%7BsearchTerms%7D&amp;format=xml");
            String href =
                URLDecoder.decode
                (hr.toString().replaceAll("&amp;", "&"), "utf-8");
            fpl.setStanzaSearchUrl(href);
        }
        catch (Throwable ign) {
            fpl.log(TAG, "failed on "+b, ign);
        }
    }

    private final static String TAG = "wiki-search-results-parser";
}
