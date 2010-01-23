package com.kbs.trook;

// This class maintains an LRU set of Views
// that correspond to a feed view.

import java.util.ArrayList;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Button;
import android.widget.ImageButton;
import android.util.Log;

public class FeedViewCache
{
    public void flush()
    { m_views.clear(); }

    public FeedView getFeedView(String uri)
    {
        // scoot through our list, and move it
        // up in front
        synchronized (m_views) {
            int vmax = m_views.size();
            for (int i=0; i<vmax; i++) {
                FeedView fv = m_views.get(i);
                if (fv.m_uri.equals(uri)) {
                    if (i > 0) {
                        m_views.remove(i);
                        m_views.add(0, fv);
                    }
                    return fv;
                }
            }
        }
        return null;
    }

    public FeedView getFeedViewNoLRUUpdate(String uri)
    {
        // scoot through our list, but don't move it
        // up in front even if found
        synchronized (m_views) {
            int vmax = m_views.size();
            for (int i=0; i<vmax; i++) {
                FeedView fv = m_views.get(i);
                if (fv.m_uri.equals(uri)) {
                    return fv;
                }
            }
        }
        return null;
    }


    public void removeFeedView(String uri)
    {
        synchronized (m_views) {
            int vmax = m_views.size();
            for (int i=0; i<vmax; i++) {
                FeedView fv = m_views.get(i);
                if (fv.m_uri.equals(uri)) {
                    m_views.remove(i);
                    return;
                }
            }
        }
    }

    public void putFeedView(FeedView fv)
    {
        synchronized (m_views) {
            m_views.add(0, fv);
            // Remove any duplicates, and also
            // remove elements at the end
            int vmax = m_views.size();
            int idx = 1;
            while (idx < vmax) {
                FeedView cur = m_views.get(idx);
                if (fv.m_uri.equals(cur.m_uri)) {
                    m_views.remove(idx);
                    vmax--;
                }
                if (idx > MAX) {
                    m_views.remove(idx);
                    vmax--;
                }
                else {
                    idx++;
                }
            }
        }
    }

    private final ArrayList<FeedView> m_views =
        new ArrayList<FeedView>();
    private final static int MAX = 12;

    public final static class FeedView
    {
        public FeedView
            (String uri, View root, TextView title,
             Button prev, ImageButton search, ViewGroup entries)
        {
            m_uri = uri;
            m_root = root;
            m_title = title;
            m_prev = prev;
            m_search = search;
            m_entries = entries;
        }

        public String toString()
        { return "fv ["+m_uri+"]"; }

        public final String m_uri;
        public final View m_root;
        public final TextView m_title;
        public final Button m_prev;
        public final ImageButton m_search;
        public final ViewGroup m_entries;
    }
    private final static String TAG = "feed-view-cache";
}
