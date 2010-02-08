package com.kbs.trook;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.IOException;
import android.util.Log;

import java.util.Date;
import java.text.SimpleDateFormat;

// Some simple pull-parser utils

public class P
{
    public final static String collectText(XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        int type;
        StringBuffer sb = new StringBuffer();
        p.require(XmlPullParser.START_TAG, null, null);
        int nest = 0;
        while ((type = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.TEXT) {
                sb.append(p.getText());
            }
            else if (type == XmlPullParser.START_TAG) {
                nest++;
            }
            else if (type == XmlPullParser.END_TAG) {
                if (nest == 0) {
                    break;
                }
                else {
                    nest--;
                }
            }
        }
        p.require(XmlPullParser.END_TAG, null, null);
        p.next();
        return sb.toString();
    }

    public final static String getAttribute(XmlPullParser p, String t)
    {
        for (int i=p.getAttributeCount()-1; i>=0; i--) {
            String k = p.getAttributeName(i);
            if (k.equals(t)) {
                return p.getAttributeValue(i);
            }
        }
        return null;
    }

    public final static boolean skipToStart
        (XmlPullParser p, String tag)
        throws IOException, XmlPullParserException
    {
        int type = p.getEventType();
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                if ((tag == null) ||
                    (p.getName().equals(tag))) {
                    return true;
                }
            }
            type = p.next();
        }
        return false;
    }

    public final static boolean skipToStartWithin
        (XmlPullParser p, String tag, String end)
        throws IOException, XmlPullParserException
    {
        int type = p.getEventType();
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                if ((tag == null) ||
                    (p.getName().equals(tag))) {
                    return true;
                }
            }
            else if (type == XmlPullParser.END_TAG) {
                if ((end != null) &&
                    (p.getName().equals(end))) {
                    p.next();
                    return false;
                }
            }
            type = p.next();
        }
        return false;
    }

    public final static boolean skipThisBlock(XmlPullParser p)
        throws XmlPullParserException, IOException
    {
        p.require(XmlPullParser.START_TAG, null, null);
        int nest = 0;
        int type = p.next();
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.END_TAG) {
                if (nest == 0) {
                    p.next();
                    return true;
                }
                else {
                    nest--;
                }
            }
            else if (type == XmlPullParser.START_TAG) {
                nest++;
            }
            type = p.next();
        }
        return skipToSETag(p);
    }

    public final static boolean skipToSETag(XmlPullParser p)
        throws XmlPullParserException, IOException
    {
        int type = p.getEventType();
        while (type != XmlPullParser.END_DOCUMENT) {
            if ((type == XmlPullParser.START_TAG) ||
                (type == XmlPullParser.END_TAG)) {
                return true;
            }
            type = p.next();
        }
        return false;
    }
    public final static Date parseTime(XmlPullParser p)
        throws IOException, XmlPullParserException
    {
        P.assertStart(p, "updated");
        String datestring = collectText(p).toUpperCase();

        // strip off timezone stuff
        if (!datestring.endsWith("Z")) {
            int idx = datestring.lastIndexOf('-');
            if (idx < 0) {
                idx = datestring.lastIndexOf('+');
                if (idx < 0) {
                    Log.d(TAG, "Could not parse `"+datestring+"'");
                    return new Date();
                }
            }
            datestring = datestring.substring(0, idx);
        }

        datestring = datestring.replaceFirst("\\.\\d*", "");

        try {
            // dateformats are not thread-safe
            synchronized(s_sdf) {
                return s_sdf.parse(datestring);
            }
        }
        catch (java.text.ParseException pe) {
            Log.d(TAG, "Could not parse `"+datestring+"'");
            return new Date();
        }
    }

    public final static void assertStart(XmlPullParser p, String tag)
        throws IOException, XmlPullParserException
    { p.require(XmlPullParser.START_TAG, null, tag); }

    private final static SimpleDateFormat s_sdf;
    static
    {
        s_sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        s_sdf.setLenient(true);
    }

    private final static String TAG = "xml-parse-utils";
}

