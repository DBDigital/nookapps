package com.kbs.trook.adapter;

import com.kbs.backport.AsyncTask;
import com.kbs.trook.FeedInfo;
import com.kbs.trook.Trook;
import com.kbs.trook.IMimeConstants;

import java.util.Date;
import java.io.IOException;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.SortedSet;
import java.util.TreeSet;

import android.util.Log;
import android.net.Uri;

public class AsyncDirectoryAdapter
    extends AsyncTask<String,FeedInfo.EntryInfo,String>
{
    public AsyncDirectoryAdapter(Trook t)
    { m_trook = t; }

    @Override
    protected String doInBackground(String... uris)
    {
        if (uris.length != 1) {
            error("Sorry -- some internal error ("+uris.length+")");
            return null;
        }

        Uri uri = Uri.parse(uris[0]);
        if (!("file".equals(uri.getScheme()))) {
            error(uris[0]+" -- only handles file uris");
            return null;
        }

        m_basedir = new File(uri.getPath());
        if (!m_basedir.isDirectory()) {
            error("not a directory");
            return null;
        }

        m_fi = new FeedInfo(uris[0]);

        try {
            listdir();
            return "ok";
        }
        catch (Throwable th) {
            Log.d(TAG, "Failed to list "+m_basedir, th);
            error("reading failed\n"+th.toString());
            return null;
        }
    }

    @Override
    protected void onProgressUpdate(FeedInfo.EntryInfo... s)
    {
        if (!m_pushedTitle) {
            Log.d(TAG, "setting feed title");
            m_trook.addFeedInfo(m_fi);
            m_pushedTitle = true;
        }

        if (s == null) {
            return;
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
        Log.d(TAG, m_basedir +": failed to load\n"+msg);
        m_error = ((m_basedir!=null)?m_basedir.toString():"")
            +": failed to load\n"+msg;
    }

    private final void listdir()
        throws IOException
    {
        // Step 1: update the base information
        m_fi.setTitle(m_basedir.getName());
        publishProgress((FeedInfo.EntryInfo[])null);

        // Step 2: look for certain suffixes, and assume
        // that such suffixes indicate something meaningful.

        // Store such candidates in a sorted set for later
        // traversal
        File[] children = m_basedir.listFiles();
        if (children == null) {
            return;
        }

        SortedSet<File> candidates = new TreeSet<File>();

        for (int i=0; i<children.length; i++) {
            if (isInteresting(children[i])) {
                candidates.add(children[i]);
            }
        }

        for (File candidate: candidates) {
            processCandidate(candidate);
        }
        return;
    }

    private final boolean isInteresting(File f)
        throws IOException
    {
        String n = f.getName();

        // Non . directories are interesting
        if (f.isDirectory()) {
            return !n.startsWith(".");
        }

        // Pick out the suffix
        int idx = n.lastIndexOf('.');
        if (idx <= 0) {
            return false;
        }

        String suffix = n.substring(idx+1);

        return
            "epub".equalsIgnoreCase(suffix) ||
            "pdf".equalsIgnoreCase(suffix) ||
            "pdb".equalsIgnoreCase(suffix) ||
            "xml".equalsIgnoreCase(suffix) ||
            "apk".equalsIgnoreCase(suffix) ||
            "mp3".equalsIgnoreCase(suffix) ||
            "bookmark".equalsIgnoreCase(suffix);
    }

    private final void processCandidate(File f)
    {
        // Directories are modeled as feeds that
        // can be further investigated
        // Log.d(TAG, "I need to look at "+f);

        if (f.isDirectory()) {
            processDirectory(f);
            return;
        }
        String name = f.getName();
        int idx = name.lastIndexOf('.');
        if (idx <= 0) {
            return;
        }
        String suffix = name.substring(idx+1);
        String prefix = name.substring(0, idx);
        if (suffix.equalsIgnoreCase("bookmark")) {
            processBookmark(f, prefix);
        }
        else {
            String m = suffixToMime(suffix);
            if (m != null) {
                processAsMime(f, prefix, m);
            }
        }
    }

    private final void processAsMime(File f, String name, String mime)
    {
        FeedInfo.EntryInfo ei = new FeedInfo.EntryInfo(m_fi);
        ei.setTitle(name);
        String uriref = Uri.fromFile(f).toString();
        FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
        li.setAttribute("href", uriref);
        li.setAttribute("type", mime);
        ei.addLink(li);
        // This is the Calibre convention [seems to be what the
        // nook uses as well]
        File p = f.getParentFile();
        if (p != null) {
            // Try a few choices
            File im;
            for (int i=0; i<IMG_SUFFIXES.length; i++) {
                im = new File(p, name+IMG_SUFFIXES[i]);
                if (im.canRead()) {
                    ei.setIconUri(Uri.fromFile(im).toString());
                    break;
                }
            }
        }

        m_fi.addEntry(ei);
        publishProgress(ei);
    }

    private final String suffixToMime(String s)
    {
        if (s == null) { return null; }

        s = s.toLowerCase();
        if (s.equals("epub")) {
            return IMimeConstants.MIME_EPUB_ZIP;
        }
        if (s.equals("pdf")) {
            return IMimeConstants.MIME_PDF;
        }
        if (s.equals("pdb")) {
            return IMimeConstants.MIME_PDB;
        }
        if (s.equals("apk")) {
            return IMimeConstants.MIME_APK;
        }
        if (s.equals("mp3")) {
            return IMimeConstants.MIME_MP3;
        }
        if (s.equals("xml")) {
            return IMimeConstants.MIME_ATOM_XML; // optimistic
        }
        Log.d(TAG, "Unknown suffix "+s);
        return null;
    }

    private final void processXml(File f, String name, String suffix)
    {
        FeedInfo.EntryInfo ei = new FeedInfo.EntryInfo(m_fi);
        ei.setTitle(name);
        String uriref = Uri.fromFile(f).toString();
        FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
        li.setAttribute("href", uriref);
        li.setAttribute("type", "application/atom+xml"); // optimistic
        ei.addLink(li);
        // This is the Calibre convention [seems to be what the
        // nook uses as well]
        File p = f.getParentFile();
        if (p != null) {
            // Try a few choices
            File im;
            for (int i=0; i<IMG_SUFFIXES.length; i++) {
                im = new File(p, name+IMG_SUFFIXES[i]);
                if (im.canRead()) {
                    ei.setIconUri(Uri.fromFile(im).toString());
                    break;
                }
            }
        }

        m_fi.addEntry(ei);
        publishProgress(ei);
    }

    private final void processBookmark(File f, String name)
    {
        FeedInfo.EntryInfo ei = new FeedInfo.EntryInfo(m_fi);
        ei.setTitle(name);
        BufferedReader r = null;
        String uri;
        try {
            r = new BufferedReader(new FileReader(f));
            uri = r.readLine();
        }
        catch (Throwable th) {
            // Ignore errors silently
            Log.d(TAG, "Failed to read "+f, th);
            return;
        }
        finally {
            if (r != null) {
                try {r.close();}
                catch (Throwable ign) {}
            }
        }

        FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
        li.setAttribute("href", uri);
        li.setAttribute("type", "application/atom+xml");
        ei.addLink(li);
        // This is the Calibre convention [seems to be what the
        // nook uses as well]
        File p = f.getParentFile();
        if (p != null) {
            // Try a few choices
            File im;
            for (int i=0; i<IMG_SUFFIXES.length; i++) {
                im = new File(p, name+IMG_SUFFIXES[i]);
                if (im.canRead()) {
                    ei.setIconUri(Uri.fromFile(im).toString());
                    break;
                }
            }
        }

        m_fi.addEntry(ei);
        publishProgress(ei);
    }

    private final void processDirectory(File f)
    {

        // First check for any special directories.
        // Right now, only look for "library" directories,
        // which are named "xxx.library", and must contain
        // a file <dir>/_catalog/catalog.xml
        String dn = f.getName();
        if (dn.endsWith(".library")) {
            // Verify if we have the magic file.
            File magic = new File(f, "_catalog/catalog.xml");
            if (magic.canRead()) {
                // Use this file as an OPDS browser for this
                // directory, rather than the standard one.
                String n = dn.substring(0, dn.length()-8);
                processAsMime(magic, n, IMimeConstants.MIME_ATOM_XML_LIBRARY);
                return;
            }
        }

        // This is a regular directory
        FeedInfo.EntryInfo ei = new FeedInfo.EntryInfo(m_fi);
        ei.setTitle(dn);

        String uriref = Uri.fromFile(f).toString();

        FeedInfo.LinkInfo li = new FeedInfo.LinkInfo();
        li.setAttribute("href", uriref);
        li.setAttribute("type", IMimeConstants.MIME_TROOK_DIRECTORY);
        ei.addLink(li);
        m_fi.addEntry(ei);
        publishProgress(ei);
    }

    private FeedInfo m_fi;
    private boolean m_pushedTitle = false;
    private File m_basedir;
    private final Trook m_trook;
    private String m_error;

    private final static String IMG_SUFFIXES[] = {
        ".jpg",
        ".jpeg",
        ".png",
        ".gif"};

    private final static String TAG ="async-dir-adapter";
}
