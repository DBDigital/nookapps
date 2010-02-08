package com.kbs.trook.tests;

import junit.framework.TestCase;

import java.io.IOException;
import java.io.File;
import java.io.Reader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileInputStream;

import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParser;

import org.xmlpull.mxp1.MXParser;

import com.kbs.trook.P;
import com.kbs.trook.RssFeedParser;
import com.kbs.trook.AtomFeedParser;
import com.kbs.trook.WikiSearchParser;
import com.kbs.trook.IFeedParserListener;
import com.kbs.trook.IFeedParser;
import com.kbs.trook.FeedInfo;

public class ParserTest extends TestCase
    implements IFeedParserListener
{
    // A very crude start which simply attempts
    // to parse all the well-formed feeds, and
    // not throw exceptions. Gotto start somewhere.
    public void testRss()
        throws IOException, XmlPullParserException
    {
        File r = new File(RSS_DIR);
        RssFeedParser parser =
            new RssFeedParser();
        walkRoot(r, parser);
    }

    public void testRdf()
        throws IOException, XmlPullParserException
    {
        File r = new File(RDF_DIR);
        RssFeedParser parser =
            new RssFeedParser();
        walkRoot(r, parser);
    }


    public void testAtom()
        throws IOException, XmlPullParserException
    {
        File r = new File(ATOM_DIR);
        AtomFeedParser parser =
            new AtomFeedParser();
        walkRoot(r, parser);
    }

    public void testWiki()
        throws IOException, XmlPullParserException
    {
        File r = new File(WIKI_DIR);
        WikiSearchParser parser =
            new WikiSearchParser();
        walkRoot(r, parser);
    }

    public void testAll()
        throws IOException, XmlPullParserException
    {
        File r = new File(WFDIR);
        walkAnyRoot(r);
    }

    public FeedInfo getFeedInfo()
    { return m_fi; }

    public String getStringResource(int v)
    { return "x"; }

    public void setResolvePath(String s)
    { m_rs = s; }

    public String getResolvePath()
    { return m_rs; }

    public void setOpenSearchUrl(String s){}

    public void setStanzaSearchUrl(String s){}

    public void publishProgress1(FeedInfo.EntryInfo... ei){}

    public String fix(String s)
    { return s; }

    public void log(String cl, String m)
    { System.err.println(cl+":"+m); }

    public void log(String cl, String m, Throwable t)
    {
        log(cl, m);
        t.printStackTrace();
    }

    private final void walkRoot(File root, IFeedParser parser)
        throws IOException, XmlPullParserException
    {
        assertTrue(root.toString(), root.isDirectory());

        File[] children = root.listFiles();
        for (int i=0; i<children.length; i++) {
            File f = children[i];
            String fname = f.getName();
            if (fname.startsWith(".")) {
                continue;
            }
            if (f.isDirectory()) {
                walkRoot(f, parser);
            }

            if (f.isFile() && fname.endsWith(".xml")) {
                checkFile(f, parser);
            }
        }
    }

    private final void walkAnyRoot(File root)
        throws IOException, XmlPullParserException
    {
        assertTrue(root.toString(), root.isDirectory());

        if (root.toString().indexOf("notyet.") >= 0) {
            return;
        }

        File[] children = root.listFiles();
        for (int i=0; i<children.length; i++) {
            File f = children[i];
            String fname = f.getName();
            if (fname.startsWith(".")) {
                continue;
            }
            if (f.isDirectory()) {
                walkAnyRoot(f);
            }

            if (f.isFile() && fname.endsWith(".xml")) {
                checkAnyFile(f);
            }
        }
    }


    private final void checkFile(File f, IFeedParser parser)
        throws IOException, XmlPullParserException
    {
        System.out.println("Reading "+f);
        Reader r =
            new BufferedReader
            (new InputStreamReader
             (new FileInputStream(f)));

        XmlPullParser p = new MXParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        // p.setNamespaceProcessingEnabled(true);
        p.setInput(r);
        P.skipToStart(p, null);

        String rootname = p.getName();
        assertTrue(rootname+":"+f.toString(), parser.canParse(rootname));

        m_fi = new FeedInfo(f.toString());
        m_rs = f.toString();
        parser.parse(p, this);
        assertTrue(f.toString(), true);
    }


    private final void checkAnyFile(File f)
        throws IOException, XmlPullParserException
    {
        if (f.toString().indexOf("notyet.") >= 0) {
            return;
        }
        System.out.println("Reading "+f);
        Reader r =
            new BufferedReader
            (new InputStreamReader
             (new FileInputStream(f)));

        XmlPullParser p = new MXParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(r);
        P.skipToStart(p, null);

        String rootname = p.getName();
        for (int i=0; i<s_parsers.length; i++) {
            IFeedParser parser = s_parsers[i];
            if (parser.canParse(rootname)) {
                m_fi = new FeedInfo(f.toString());
                parser.parse(p, this);
                assertTrue(f.toString(), true);
                return;
            }
        }
        fail("Failed to find parser for "+f);
    }


    private FeedInfo m_fi;
    private String m_rs;
    private final static String RSS_DIR = "tests/ufp_tests/tests/wellformed/rss";
    private final static String RDF_DIR = "tests/ufp_tests/tests/wellformed/rdf";
    private final static String ATOM_DIR = "tests/ufp_tests/tests/wellformed/atom";
    private final static String WFDIR = "tests/ufp_tests/tests/wellformed";
    private final static String WIKI_DIR = "tests/kbs_tests/wellformed/wikisearch";

    private final static IFeedParser[] s_parsers;
    static
    {
        s_parsers = new IFeedParser[2];
        int idx = 0;

        s_parsers[idx++] = new AtomFeedParser();
        s_parsers[idx++] = new RssFeedParser();
    }
}
