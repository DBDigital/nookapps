package com.kbs.trook;

// A generic interface that allows an IFeedParser
// to notify it of stuff that happens during a
// parse.

import java.io.IOException;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParser;

public interface IFeedParserListener
{
    public FeedInfo getFeedInfo();

    public String getStringResource(int v);

    public void setResolvePath(String s);

    public String getResolvePath();

    public void setOpenSearchUrl(String s);

    public void setStanzaSearchUrl(String s);

    public void publishProgress1(FeedInfo.EntryInfo... ei);

    public String fix(String s);

    public void log(String cl, String msg);

    public void log(String cl, String msg, Throwable t);
}
