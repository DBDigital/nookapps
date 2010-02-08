package com.kbs.trook;

import android.util.Log;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
import java.net.URLEncoder;
import java.net.URLDecoder;

public class FeedInfo
{
    public FeedInfo(String uri)
    { m_uri = uri; }

    public void setTitle(String t)
    { m_title = t; }
    public String getTitle()
    { return m_title; }

    public String getUri()
    { return m_uri; }

    public void addEntry(EntryInfo ei)
    { m_entries.add(ei); }
    public List<EntryInfo> getEntries()
    { return m_entries; }

    public void setSearch(SearchInfo si)
    { m_si = si; }
    public SearchInfo getSearchInfo()
    { return m_si; }

    public void setId(String s)
    { m_id = s; }
    public String getId()
    { return m_id; }

    public void setUpdated(Date d)
    { m_updated = d; }
    public Date getUpdated()
    { return m_updated; }

    public void setIconUri(String s)
    { m_icon = s; }
    public String getIconUri()
    { return m_icon; }

    private final String m_uri;
    private String m_title = null;
    private String m_id = null;
    private Date m_updated = null;
    private String m_icon = null;
    private SearchInfo m_si = null;
    private List<EntryInfo> m_entries =
        new LinkedList<EntryInfo>();

    private final static String TAG = "feed-info";

    public final static class EntryInfo
    {
        public EntryInfo(FeedInfo fi)
        { m_fi = fi; }
        public String getAuthor()
        { return m_author; }
        public void setAuthor(String s)
        { m_author = s; }
        public FeedInfo getFeedInfo()
        { return m_fi; }
        public String getTitle()
        { return m_title; }
        public void setTitle(String s)
        { m_title = s; }
        public String getId()
        { return m_id; }
        public void setId(String s)
        { m_id = s; }
        public Date getUpdate()
        { return m_updated; }
        public void setUpdated(Date d)
        { m_updated = d; }
        public String getContent()
        { return m_content; }
        public void setContent(String s)
        { m_content = s; }
        public String getSummary()
        { return m_summary; }
        public void setSummary(String s)
        { m_summary = s; }
        public List<LinkInfo> getLinks()
        { return m_links; }
        public void addLink(LinkInfo li)
        { m_links.add(li); }
        public void setIconUri(String s)
        { m_iconuri = s; }
        public String getIconUri()
        { return m_iconuri; }

        private final FeedInfo m_fi;
        private String m_title;
        private String m_id;
        private String m_content;
        private String m_summary;
        private String m_iconuri;
        private String m_author = null;
        private List<LinkInfo> m_links =
            new LinkedList<LinkInfo>();
        private Date m_updated;
    }

    public final static class SearchInfo
    {
        public SearchInfo(FeedInfo p, String template)
        {
            m_fi = p;
            String t = template;
            try { t = URLDecoder.decode(template, "utf-8"); }
            catch (IOException ign) { }
            m_template = t;
        }

        public FeedInfo getFeedInfo()
        { return m_fi; }

        public String getQueryUriFor(String query)
        {
            String qe;
            try { qe = URLEncoder.encode(query, "utf-8"); }
            catch (IOException ioe) {
                qe = query;
            }
            
            return
                m_template.replace("{searchTerms}", qe);
        }

        private final String m_template;
        private final FeedInfo m_fi;
    }

    public final static class LinkInfo
    {
        public String getRel()
        {
            String ret = m_attrs.get("rel");
            if (ret != null) {
                return ret;
            }
            return "alternate";
        }
        public String getAttribute(String key)
        { return m_attrs.get(key); }
        public void setAttribute(String key, String val)
        { m_attrs.put(key, val); }
        public String toString()
        {
            StringBuffer sb = new StringBuffer("link: ");
            for (String k: m_attrs.keySet()) {
                sb.append(" "+k+"="+m_attrs.get(k));
            }
            return sb.toString();
        }

        private Map<String,String> m_attrs=
            new HashMap<String,String>();
    }
}
