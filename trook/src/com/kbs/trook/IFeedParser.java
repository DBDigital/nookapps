package com.kbs.trook;

// A generic interface that promises to
// parse a given feed. Please don't store
// any state -- I'll be reusing this instance
// in multiple threads.

import java.io.IOException;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParser;

public interface IFeedParser
{
    // Take a quick look, and tell me if you have
    // a decent chance at parsing this feed.
    public boolean canParse(String rootelement);

    // Go for it, and use the AsyncFeedParserTask to notify
    // when interesting stuff happens. [The AsyncFeedParserTask
    // should be abstracted away as well, but this is an intermediate
    // step.]
    public void parse(XmlPullParser p, IFeedParserListener parent)
        throws IOException, XmlPullParserException;
}
