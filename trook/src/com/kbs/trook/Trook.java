package com.kbs.trook;

import android.app.Activity;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.ComponentName;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.inputmethod.InputMethodManager;
import android.view.KeyEvent;
import android.widget.TextView;
import android.widget.EditText;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.app.Dialog;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.Button;
import android.widget.ImageButton;
import android.util.Log;
import android.text.Html;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.os.PowerManager;

import android.widget.ViewAnimator;

import java.io.InputStreamReader;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.Reader;
import java.io.Writer;
import java.io.FileWriter;
import java.io.File;

import java.net.URI;
import org.apache.http.client.utils.URIUtils;
import java.util.Stack;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

import com.kbs.util.ConnectUtils;
import com.kbs.util.NookUtils;

public class Trook extends Activity
{
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        m_webview = (WebView) findViewById(R.id.webview);
        m_webview.setClickable(false);
        m_webview.getSettings().setJavaScriptEnabled(true);
        m_webview.getSettings().setTextSize(WebSettings.TextSize.LARGER);
        m_webview.setWebViewClient(new WVClient());
        m_webview.setOnKeyListener(new WVPager());
        m_va = (ViewAnimator) findViewById(R.id.rootanimator);
        m_va.setAnimateFirstView(false);
        m_status = (TextView) findViewById(R.id.status);
        m_framea = (FrameLayout) findViewById(R.id.framea);
        m_frameb = (FrameLayout) findViewById(R.id.frameb);
        m_feedmanager = new FeedManager(this);
        m_feedviewcache = new FeedViewCache();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        m_powerlock = pm.newWakeLock
            (PowerManager.SCREEN_DIM_WAKE_LOCK, TAG+":"+hashCode());
        m_powerlock.setReferenceCounted(false);


        m_dialog = new OneLineDialog(this, m_webview);
        pushViewFromUri(getFeedRoot());
    }

    private void maybeCreateFeedDirectory()
        throws IOException
    {
        File f = new File(FEED_DIR);
        if (!f.exists()) {
            f.mkdirs();
        }
    }

    private void doFeedDialog()
    {
        m_dialog.showDialog
            ("Load a feed", "Open feed URL", "http://",
             new OneLineDialog.SubmitListener() {
                 public void onSubmit(String url) {
                     if ((url != null) && (url.length() >0)) {
                         Trook.this.pushViewFromUri(url);
                     }
                 }
             });
    }

    private void doSearchDialog(final FeedInfo.SearchInfo si)
    {
        m_dialog.showDialog
            ("Search", "Enter search terms", null,
             new OneLineDialog.SubmitListener() {
                 public void onSubmit(String term) {
                     if ((term != null) && (term.length() >0)) {
                         Trook.this.pushViewFromUri
                             (si.getQueryUriFor(term));
                     }
                 }
             });
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        m_feedmanager.shutdown();
        m_feedviewcache.flush();
        synchronized (m_wifisync) {
            int max = 200; // Just in case

            if (m_wifireflock != null) {
                int i = 0;
                while (ConnectUtils.isHeld(m_wifireflock)
                       && (i++ < max)) {
                    ConnectUtils.release(m_wifireflock);
                }
                m_wifireflock = null;
            }
            if (m_wifitimelock != null) {
                int i = 0;
                while (ConnectUtils.isHeld(m_wifitimelock)
                       && (i++ < max)) {
                    ConnectUtils.release(m_wifitimelock);
                }
            }
            m_wifitimelock = null;
        }                
    }

    // This should only be called from the UI thread
    public final void displayError(String msg)
    {
        statusUpdate(null);
        Toast.makeText
            (getApplicationContext(),
             msg, Toast.LENGTH_LONG).show();
    }
    final void displayShortMessage(String msg)
    {
        Toast.makeText
            (getApplicationContext(),
             msg, Toast.LENGTH_SHORT).show();
    }

    final String getFeedRootPrefs()
    {
        // Priority: first the preferences
        SharedPreferences settings =
            getSharedPreferences(TROOK_PREFS, MODE_WORLD_READABLE);
        return settings.getString(TROOK_ROOT_URI, null);
    }

    final String getFeedRoot()
    {
        String ret = getFeedRootPrefs();
        if (ret == null) {
            // next the file system
            File f = new File(LOCAL_ROOT_XML_PATH);
            if (f.exists()) {
                return "file:///system/media/sdcard/my%20feeds/root.xml";
            }
        }
        return DEFAULT_FEED_URI;
    }

    final void setFeedRootPrefs(String s)
    {
        SharedPreferences settings =
            getSharedPreferences(TROOK_PREFS, MODE_WORLD_READABLE);
        SharedPreferences.Editor editor = settings.edit();
        if (s != null) {
            editor.putString(TROOK_ROOT_URI, s);
        }
        else {
            editor.remove(TROOK_ROOT_URI);
        }
        editor.commit();
    }

    // Only to be called from the UI thread
    final void pushViewFromReader(String uri, Reader r)
    {
        if (pushFeedView(uri)) {
            // happy happy -- have it in our view cache.
            return;
        }

        // Launch out a loader thread
        asyncLoadFeedFromReader(uri, r);
    }

    // Only to be called from UI thread
    final void reloadViewToUri(String uri)
    {
        // special case for our root
        if (DEFAULT_FEED_URI.equals(uri)) {
            return;
        }

        // First remove this view from our cache
        m_feedviewcache.removeFeedView(uri);
        // Next remove it from any stored feed
        // as well
        m_feedmanager.removeCachedContent(uri);
        // replace current view with this uri
        m_curfeedview = makeFeedView(uri);

        // locate it in the current displayed view
        if (m_usinga) {
            m_framea.removeAllViews();
            m_framea.addView(m_curfeedview.m_root);
        }
        else {
            m_frameb.removeAllViews();
            m_frameb.addView(m_curfeedview.m_root);
        }

        // and launch the loader task
        asyncLoadFeedFromUri(uri);
    }

    final void asyncLoadFeedFromUri(String uri)
    {
        m_feedmanager.asyncLoadFeedFromUri(uri);
    }        

    // Only to be called from UI thread
    final void asyncLoadFeedFromReader(String uri, Reader r)
    { m_feedmanager.asyncLoadFeedFromReader(uri, r); }

    // Only to be called from UI thread
    final void asyncLoadOpenSearchFromUri
        (FeedInfo fi, String master, String osuri)
    { m_feedmanager.asyncLoadOpenSearchFromUri(fi, master, osuri); }

    // Only to be called from UI thread
    final void asyncParseOpenSearch
        (FeedInfo fi, String master, String osuri, Reader r)
    { m_feedmanager.asyncParseOpenSearch(fi, master, osuri, r); }

    // Only to be called from UI thread
    final void pushViewFromUri(String uri)
    {
        if (pushFeedView(uri)) {
            // happy happy -- it's in our view cache
            return;
        }

        // Launch out a loader thread
        asyncLoadFeedFromUri(uri);
    }

    // Only to be called from UI thread
    final void popViewToUri(String uri)
    {
        if (popFeedView(uri)) {
            // happy happy -- in my feed cache
            return;
        }
        asyncLoadFeedFromUri(uri);
    }
    // Only from UI thread
    public final void statusUpdate(String s)
    {
        if (s == null) {
            m_status.setVisibility(View.GONE);
        }
        else {
            if (m_status.getVisibility() != View.VISIBLE) {
                m_status.setVisibility(View.VISIBLE);
            }
            // Log.d(TAG, "Setting status to "+s);
            m_status.setText(s);
            m_status.bringToFront();
        }
    }

    // This class maintains two network locks.
    // One is refcounted, and the other is timed, effectively
    // so we have a small amount of breathing room before the next
    // network operation kicks in.
    //
    // All network using code must call this from a SEPARATE
    // [usually asynctask thread, or the UI WILL hang.]
    //
    final WifiStatus acquireAndWaitForWifi()
    {
        // First a sanity check.
        if (!ConnectUtils.wifiEnabled(this)) {
            return new WifiStatus
                (false, "Please enable wifi\nfrom the Settings");
        }

        synchronized (m_wifisync) {
            // Step 1: create both locks as needed
            if (m_wifireflock == null) {
                m_wifireflock =
                    ConnectUtils.newWifiLock(this, TAG+"-refc-"+hashCode());
                if (m_wifireflock == null) {
                    return new WifiStatus
                        (false, "Unable to create wifilock, sorry");
                }
                if (!ConnectUtils.setReferenceCounted(m_wifireflock, true)) {
                    return new WifiStatus
                        (false, "Unable to set refcount on wifilock");
                }
            }
            if (m_wifitimelock == null) {
                m_wifitimelock =
                    ConnectUtils.newWifiLock(this, TAG+"-timed-"+hashCode());
                if (m_wifitimelock == null) {
                    return new WifiStatus
                        (false, "Unable to create wifitimelock, sorry");
                }
                if (!ConnectUtils.setReferenceCounted
                    (m_wifitimelock, false)) {
                    return new WifiStatus
                        (false, "Unable to set refcount on timelock");
                }
            }

            // Step 2: bump up refcount on the refcounted lock
            if (!ConnectUtils.acquire(m_wifireflock)) {
                return new WifiStatus
                    (false, "Unable to acquire reference on wifi lock");
            }

            // Step 3: wait for network to turn on, and be careful
            // to release the refcounted lock on failure
            boolean success = false;
            try {
                success =
                    ConnectUtils.waitForService(this, WIFI_TIMEOUT);
                return new WifiStatus
                    (success, "Network failed to turn on");
            }
            finally {
                if (!success) {
                    ConnectUtils.release(m_wifireflock);
                }
            }
        }
    }


    final void releaseWifi()
    {
        // Here we expect a wifirefc lock and a timelock, otherwise
        // we have a consistency error.

        Log.d(TAG, "releasing wifi, hopefully get a timelock as well");
        synchronized (m_wifisync) {
            // First, acquire a timeout lock just so we give ourselves
            // some time before someone else needs the network
            try {
                ConnectUtils.acquire(m_wifitimelock, WIFI_HOLDON);
            }
            finally {
                // No matter what, remove the reference to the
                // refcounted lock
                ConnectUtils.release(m_wifireflock);
            }
        }
    }

    final void removeCachedView(String uri)
    { m_feedviewcache.removeFeedView(uri); }

    // This should only be called from the UI thread
    public final void addFeedInfo(FeedInfo fi)
    {
        // We only bother updating things that are
        // in our cache.
        m_titles.put(fi.getUri(), fi.getTitle());

        FeedViewCache.FeedView cached =
            m_feedviewcache.getFeedViewNoLRUUpdate(fi.getUri());
        if (cached == null) {
            Log.d(TAG, fi.getUri()+" not cached, ignoring...");
            return;
        }
        cached.m_title.setText(fi.getTitle());
    }

    private final void maybeRemoveRootFeed()
    {
        try {
            File f = new File(LOCAL_ROOT_XML_PATH);
            f.delete();
        }
        catch (Throwable ign) {}
    }

    private final void bookmarkFeed()
    {
        if (m_curfeedview == null) {
            displayError("No feed here");
            return;
        }

        String uri = m_curfeedview.m_uri;
        if ((uri == null) ||
            !(uri.startsWith("http://"))) {
            displayError("Can only bookmark http URLs\n"+uri);
            return;
        }

        String title = sanitizeTitle(m_curfeedview.m_title.getText(), uri);

        Writer w = null;

        try {
            maybeCreateFeedDirectory();
            File tg = new File(FEED_DIR+"/"+title+".bookmark");
            w = new FileWriter(tg);
            w.write(uri);
        }
        catch (Throwable th) {
            Log.d(TAG, "Saving "+uri+" failed", th);
            displayShortMessage("Failed to bookmark\n"+th);
        }
        finally {
            if (w != null) {
                try { w.close(); }
                catch (Throwable ign) {}
            }
        }
    }        

    @Override
    public void onCreateContextMenu
        (ContextMenu menu, View v, ContextMenu.ContextMenuInfo mi)
    {
        super.onCreateContextMenu(menu, v, mi);
        menu.setHeaderTitle(Version.VERSION+" settings");
        menu.add(Menu.NONE, CANCEL_ID, Menu.NONE, "Cancel");
        menu.add(Menu.NONE, BOOKMARK_ID, Menu.NONE,
                 "Bookmark to my feeds");
        menu.add(Menu.NONE, RESET_ID, Menu.NONE,
                 "Restore default feed");
        menu.add(Menu.NONE, CLOSE_ID, Menu.NONE,
                 "Stop application");
    }

    @Override
    public boolean onContextItemSelected(MenuItem item)
    {
        switch (item.getItemId()) {
        case LOAD_ID:
            doFeedDialog();
            return super.onContextItemSelected(item);
        case RESET_ID:
            setFeedRootPrefs(null);
            maybeRemoveRootFeed();
            return super.onContextItemSelected(item);
        case CLOSE_ID:
            finish();
            return true;
        case BOOKMARK_ID:
            bookmarkFeed();
            return super.onContextItemSelected(item);
        default:
            return super.onContextItemSelected(item);
        }
    }

    private final FeedViewCache.FeedView
        makeFeedView(String uri)
    {
        ViewGroup root = (ViewGroup)
            getLayoutInflater().inflate
            (R.layout.feedview, m_va, false);
        TextView title = (TextView)
            root.findViewById(R.id.feed_title);
        title.setText(R.string.loading_text);
        ImageButton reload = (ImageButton)
            root.findViewById(R.id.reload);
        reload.setOnClickListener(new Reloader(uri));

        ImageButton stngs = (ImageButton)
            root.findViewById(R.id.settings);
        stngs.setOnClickListener(m_settings_clicker);
        registerForContextMenu(stngs);

        Button prev = (Button)
            root.findViewById(R.id.prev);

        ImageButton search = (ImageButton)
            root.findViewById(R.id.search);

        String parenturi = m_parents.get(uri);
        String parenttext = null;
        if (parenturi != null) {
            parenttext = m_titles.get(parenturi);
        }

        if (parenturi != null) {
            if (parenttext != null) {
                prev.setText("< "+parenttext);
            }
            else {
                prev.setText(R.string.prev_text);
            }
            prev.setOnClickListener(new Popper(parenturi));
        }
        else {
            Log.d(TAG, "Parent not found for "+uri);
            prev.setVisibility(View.INVISIBLE);
        }

        ViewGroup entries = (ViewGroup)
            root.findViewById(R.id.feed_entries);
        FeedViewCache.FeedView ret =
            new FeedViewCache.FeedView
            (uri, root, title, prev, search, entries);
        m_feedviewcache.putFeedView(ret);
        return ret;
    }

    // Only call from the UI thread
    // This one sets up the feedview in the hidden
    // panel so it can be moved in appropriately.
    //
    // return true if the view is cached, otherwise
    // you'll need to launch a task to actually
    // fill up the contents
    private final boolean placeFeedView(String uri)
    {
        FeedViewCache.FeedView cached =
            m_feedviewcache.getFeedView(uri);
        boolean iscached = true;
        if (cached == null) {
            iscached = false;
            cached = makeFeedView(uri);
        }

        // Drop the feedview into the non-displayed view
        if (cached.m_root.getParent() != null) {
            if (cached.m_root.getParent() instanceof ViewGroup) {
                ((ViewGroup) cached.m_root.getParent()).
                    removeView(cached.m_root);
            }
        }
        if (m_usinga) {
            m_frameb.removeAllViews();
            m_frameb.addView(cached.m_root);
            // Log.d(TAG, "attaching new view to frameA");
            m_usinga = false;
        }
        else {
            m_framea.removeAllViews();
            m_framea.addView(cached.m_root);
            // Log.d(TAG, "attaching new view to frameB");
            m_usinga = true;
        }
        m_curfeedview = cached;
        return iscached;
    }

    final boolean pushFeedView(String uri)
    {
        Log.d(TAG, "Push feed -- "+uri);
        // Update parent info
        if (m_curfeedview != null) {
            m_parents.put(uri, m_curfeedview.m_uri);
        }

        boolean ret = placeFeedView(uri);

        // Now create transitions
        m_va.setInAnimation(this, R.anim.pop_up_in);
        m_va.setOutAnimation(this, R.anim.pop_up_out);
        m_va.showNext();
        return ret;
    }

    final boolean popFeedView(String uri)
    {
        Log.d(TAG, "Someone wants us to pop into "+uri);
        boolean ret = placeFeedView(uri);
        m_va.setInAnimation(this, R.anim.push_down_in);
        m_va.setOutAnimation(this, R.anim.push_down_out);
        m_va.showNext();
        return ret;
    }

    // Only from UI thread
    final void setSearch(final FeedInfo.SearchInfo si)
    {
        FeedInfo fi = si.getFeedInfo();

        Log.d(TAG, "Got a search info!");
        // Only add if we have it cached somewhere
        FeedViewCache.FeedView fv =
            m_feedviewcache.getFeedViewNoLRUUpdate(fi.getUri());
        if (fv == null) {
            // ignore
            return;
        }

        fv.m_search.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Trook.this.doSearchDialog(si);
                }
            });
        fv.m_search.setVisibility(View.VISIBLE);
    }

    // This should only be called from the UI thread
    public final void addFeedEntry(FeedInfo.EntryInfo ei)
    {
        FeedInfo fi = ei.getFeedInfo();
        // Only add if we have it cached somewhere
        FeedViewCache.FeedView fv =
            m_feedviewcache.getFeedViewNoLRUUpdate(fi.getUri());
        if (fv == null) {
            // ignore
            return;
        }

        ViewGroup el = (ViewGroup)
            getLayoutInflater().inflate
            (R.layout.entryview, fv.m_entries, false);
        ImageButton doit = (ImageButton)
            el.findViewById(R.id.doit);

        // Special case for "file" icon uris
        String iuri = ei.getIconUri();
        boolean iuri_set = false;
        if (iuri != null) {
            // Log.d(TAG, "Found icon uri = "+iuri);
            Uri uri = Uri.parse(iuri);
            if ("file".equals(uri.getScheme())) {
                // Surprisingly, this takes a path rather
                // than a URI
                doit.setImageURI(Uri.parse(uri.getPath()));
                iuri_set = true;
            }
        }

        boolean did_something = false;
        String baseuri = ei.getFeedInfo().getUri();

        for (FeedInfo.LinkInfo li : ei.getLinks()) {
            String href = li.getAttribute("href");

            if (href == null) {
                continue;
            }

            String type = li.getAttribute("type");
            if ("application/epub+zip".equals(type)) {
                // doit.setText(R.string.epub_download_text);
                
                doit.setOnClickListener
                    (makeEpubDownloadClicker
                     (baseuri, href, type, ei));
                doit.setImageResource(R.drawable.epub_download);
                did_something = true;
                break;
            }
            else if ("trook/directory".equals(type)) {
                // doit.setText(R.string.directory_text);
                doit.setOnClickListener
                    (new DirectoryClicker(href));
                if (!iuri_set) {
                    doit.setImageResource(R.drawable.directory);
                }
                did_something = true;
                break;
            }
            else if ("trook/epub".equals(type) ||
                     "trook/pdf".equals(type) ||
                     "trook/pdb".equals(type)) {
                // doit.setText(R.string.readbook_text);
                doit.setOnClickListener
                    (new ReadBookClicker(href, type));
                if (!iuri_set) {
                    doit.setImageResource(R.drawable.epub);
                }
                did_something = true;
                break;
            }
            else if ("audio/mp3".equals(type)) {
                // doit.setText(R.string.mp3_download_text);
                doit.setOnClickListener
                    (new DownloadClicker
                     (baseuri, href,
                      "/system/media/sdcard/my music/"+
                      sanitizeUniqueName(baseuri, href)+".mp3", type));
                doit.setImageResource(R.drawable.mp3);
                did_something = true;
                break;
            }
            else if ("application/vnd.android.package-archive".equals(type)) {
                // doit.setText(R.string.apk_download_text);

                doit.setOnClickListener
                    (new DownloadClicker
                     (baseuri, href,
                      sanitizeUniqueName(baseuri, href)+".apk", type));
                doit.setImageResource(R.drawable.apk);
                did_something = true;
                break;
            }                
            else if ("application/atom+xml".equals(type)) {
                // doit.setText(R.string.feed_text);
                doit.setOnClickListener
                    (new LaunchFeed(baseuri, href));
                doit.setImageResource(R.drawable.feed);
                did_something = true;
                break;
            }
            else if ("text/html".equals(type) ||
                     "text/xhtml".equals(type) ||
                     null == type) {
                // doit.setText(R.string.view_text);
                doit.setOnClickListener(new LaunchBrowser(href));
                doit.setImageResource(R.drawable.webkit);
                did_something = true;
                String pr = li.getAttribute("preferred");
                if ("true".equals(pr)) {
                    break;
                }
                else {
                    // keep looking, in case we find a better fit.
                }
            }
            else {
                // Log.d(TAG, "Skipping unknown type: "+type);
            }
        }

        if (!did_something) {
            doit.setVisibility(View.GONE);
        }

        TextView etv = (TextView)
            el.findViewById(R.id.entry);
        String content = "";
        if (ei.getTitle() != null) {
            content += "<b>"+ei.getTitle()+"</b><br/><br/>";
        }
        if (ei.getAuthor() != null) {
            content+="&nbsp;&nbsp;&nbsp;&nbsp;by "+ei.getAuthor()+"<br/><br/>";
        }
        boolean made_summary = false;
        if (ei.getContent() != null) {
            content += ei.getContent();
            made_summary = true;
        }
        else if (ei.getSummary() != null) {
            content += ei.getSummary();
            made_summary = true;
        }
        if (!made_summary) {
            etv.setMaxWidth(140);
        }
        etv.setText(Html.fromHtml(content));
        fv.m_entries.addView(el);
        // Log.d(TAG, "Added view with "+ei.getTitle());
    }

    private final DownloadClicker makeEpubDownloadClicker
        (String baseuri, String href, String type, FeedInfo.EntryInfo ei)
    {
        // Attempt to download a thumbnail icon if something's here
        String[] hrefs;
        String[] mimes;
        String[] targets;

        if (ei.getIconUri() == null) {
            hrefs = new String[1];
            mimes = new String[1];
            targets = new String[1];
        }
        else {
            hrefs = new String[2];
            mimes = new String[2];
            targets = new String[2];

            hrefs[1] = ei.getIconUri();
            mimes[1] = "image";
            targets[1] = makeEpubPath(baseuri, href, ei)+".jpg";
        }

        hrefs[0] = href;
        mimes[0] = "application/epub+zip";
        targets[0] = makeEpubPath(baseuri, href, ei)+".epub";
        return new DownloadClicker
            (baseuri, hrefs, targets, mimes);
    }

    private final static String makeEpubPath
        (String baseuri, String href, FeedInfo.EntryInfo ei)
    {
        // For a slightly less insane way to organize book paths for
        // downloaded content, I attempt to organize things in two
        // levels -- an author, and the title, and create extra
        // numbers at the end if there's a file like this already.

        String author = ei.getAuthor();
        if (author == null) {
            author = "Unknown";
        }

        // Attempt to normalize the author by Lastname, First -- a crude
        // stab, doesn't always work.
        if (author.indexOf(',') < 0) {
            // No commas
            String[] items = author.split("\\s+");
            if (items.length > 1) {
                author = items[items.length-1] + ",";
                for (int i=0; i<items.length-1; i++) {
                    author += " "+items[i];
                }
            }
        }

        String title = ei.getTitle();
        if (title == null) {
            // to have some vague chance of figuring out
            // what book this really is, I fall back to
            // the base URI.
            title = sanitizeUniqueName(baseuri, href);
        }

        return MYDOC_ROOT_PATH+"/"+author+"/"+title;
    }

    private final static String sanitizeUniqueName
        (String baseuri, String href)
    {
        // Egregiously silly way to find a unique name, sorry
        return
            baseuri.replaceAll("[^a-zA-Z0-9]", "")+
            href.replaceAll("[^a-zA-Z0-9]", "");
    }

    @Override
    public void onUserInteraction()
    {
    	super.onUserInteraction();
    	if (m_powerlock != null) {
            m_powerlock.acquire(POWER_DELAY);
        }
    }

    @Override
    public void onResume()
    {
    	super.onResume();
        if (m_powerlock != null) {
            m_powerlock.acquire(POWER_DELAY);
        }
        NookUtils.setAppTitle(this, "Trook");
        m_dialog.closeDialog();
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (m_powerlock != null) {
            m_powerlock.release();
        }
        m_dialog.closeDialog();
    }

    private final void pageUp()
    {
        if (m_webview != null) {
            int cury = m_webview.getScrollY();
            if (cury == 0) { return; }
            int newy = cury - WEB_SCROLL_PX;
            if (newy < 0) { newy = 0; }
            m_webview.scrollTo(0, newy);
        }
    }
    private final void pageDown()
    {
        if (m_webview != null) {
            int cury = m_webview.getScrollY();
            int hmax = m_webview.getContentHeight() - WEB_SCROLL_PX;
            if (hmax < 0) { hmax = 0; }
            int newy = cury + WEB_SCROLL_PX;
            if (newy > hmax) { newy = hmax; }
            if (cury != newy) {
                m_webview.scrollTo(0, newy);
            }
        }
    }

    private final void showWebViewAsync(String href)
    {
        // This is tricky. To avoid hanging the UI,
        // We first launch a task whose essential job
        // is to enable the wifi network, if possible.
        //
        // When it completes, it calls showWebViewNow,
        // which can assume that the network is up,
        // and that we have a refcounted lock.
        //
        // when the page completes loading, the refcount
        // is removed.
        new WebViewTask(this).execute(href);
    }

    final void showWebViewNow(String href)
    { m_webview.loadUrl(href); }

    private final class LaunchFeed
        implements View.OnClickListener
    {
        private LaunchFeed(String base, String href)
        { m_base = base; m_href = href; }

        public void onClick(View v)
        {
            try {
                URI base = new URI(m_base);
                URI ref = URIUtils.resolve(base, m_href);
                // Log.d(TAG, "Found base URL = "+ref);
                Trook.this.pushViewFromUri(ref.toString());
            }
            catch (Throwable th) {
                Log.d(TAG, "launchfeed fails", th);
                Trook.this.displayError("Failed to load "+m_href+"\n"+th);
            }
        }
        private final String m_href;
        private final String m_base;
    }

    private final class DirectoryClicker
        implements View.OnClickListener
    {
        private DirectoryClicker(String diruri)
        { m_diruri = diruri; }

        public void onClick(View v)
        { Trook.this.pushViewFromUri(m_diruri); }

        private final String m_diruri;
    }

    private final class ReadBookClicker
        implements View.OnClickListener
    {
        private ReadBookClicker(String uri, String type)
        {
            m_uri = uri;

            // translations for intent
            if ("trook/epub".equals(type)) {
                m_type = "application/epub";
            }
            else if ("trook/pdf".equals(type)) {
                m_type = "application/pdf";
            }
            else if ("trook/pdb".equals(type)) {
                m_type = "application/pdb";
            }
            else {
                throw new IllegalArgumentException
                    ("Unexpected mime type -- "+type);
            }
        }

        public void onClick(View v)
        {
            Intent ri = new Intent("com.bravo.intent.action.VIEW");
            ri.setDataAndType(Uri.parse(m_uri), m_type);
            try { Trook.this.startActivity(ri); }
            catch (Throwable th) {
                Log.d(TAG, "Unable to read book", th);
                Trook.this.displayError(m_uri+"\n: failed to read\n"+
                                        th.toString());
            }
        }

        private final String m_uri;
        private final String m_type;
    }

    private final class DownloadClicker
        implements View.OnClickListener
    {
        private DownloadClicker
            (String base, String[] hrefs, String[] targets, String mimes[])
        {
            m_base = base;
            m_hrefs = hrefs;
            m_targets = targets;
            m_mimes = mimes;
        }

        private DownloadClicker
            (String base, String href, String target, String mime)
        {
            m_base = base;
            m_hrefs = new String[1]; m_hrefs[0] = href;
            m_targets = new String[1]; m_targets[0] = target;
            m_mimes = new String[1]; m_mimes[0] = mime;
        }

        public void onClick(View v)
        {
            try {
                URI base = new URI(m_base);
                for (int i=0; i<m_hrefs.length; i++) {
                    URI ref = URIUtils.resolve(base, m_hrefs[i]);
                    // Log.d(TAG, "Found base URL = "+ref);

                    Intent dsi = new Intent();
                    dsi.setDataAndType
                        (Uri.parse(ref.toString()), m_mimes[i]);
                    dsi.putExtra(DownloadService.TARGET, m_targets[i]);
                    dsi.setComponent
                        (new ComponentName
                         ("com.kbs.trook", "com.kbs.trook.DownloadService"));
                    startService(dsi);
                }
                displayShortMessage("Starting download in the background");
            }
            catch (Throwable th) {
                Log.d(TAG, "download fails", th);
                Trook.this.displayError("Failed to load "+m_hrefs[0]+"\n"+th);
            }
        }
        private final String[] m_hrefs;
        private final String m_base;
        private final String[] m_targets;
        private final String[] m_mimes;
    }

    private final boolean isIntentAvailable(Intent msg)
    {
        final PackageManager packageManager = getPackageManager();
        List<ResolveInfo> list =
            packageManager.queryIntentActivities
            (msg, PackageManager.MATCH_DEFAULT_ONLY);
        return list.size() > 0;
    }

    private final static String sanitizeTitle(CharSequence title, String uri)
    {
        // Try to use the title if possible.
        if ((title != null) && (title.length() > 0)) {
            String t = title.toString();
            return t.replaceAll("[\\/:]", " - ");
        }
        // Otherwise, just the hostname
        Uri auri = Uri.parse(uri);
        String ret = auri.getHost();
        if (ret == null) {
            ret = "Unknown feed";
        }
        return ret;
    }

    private final class LaunchBrowser
        implements View.OnClickListener
    {
        private LaunchBrowser(String href)
        { m_href = href; }

        public void onClick(View v)
        {
            Intent msg =
                new Intent(Intent.ACTION_VIEW);
            msg.setDataAndType(Uri.parse(m_href), "text/html");

            Log.d(TAG, "checking if there's someone who can process "+
                  msg);
            if (Trook.this.isIntentAvailable(msg)) {
                Trook.this.startActivity(msg);
            }
            else {
                // Fall back to showing within our web view
                Trook.this.showWebViewAsync(m_href);
            }
        }
        private final String m_href;
    }

    private final class Popper
        implements View.OnClickListener
    {
        Popper(String uri)
        { m_uri = uri; }
        public void onClick(View v)
        { Trook.this.popViewToUri(m_uri); }
        private final String m_uri;
    }

    private final class Reloader
        implements View.OnClickListener
    {
        Reloader(String uri)
        { m_uri = uri; }
        public void onClick(View v)
        {
            Trook.this.reloadViewToUri(m_uri);
        }
        private final String m_uri;
    }

    private final class WVClient
        extends WebViewClient
    {
        @Override
        public void onPageFinished(WebView v, String u)
        {
            Trook.this.releaseWifi();
            Trook.this.statusUpdate(null);
        }
    }

    private final class WVPager
        implements View.OnKeyListener
    {
        public boolean onKey(View v, int keyCode, KeyEvent ev)
        {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                case NOOK_PAGE_UP_KEY_LEFT:
            	case NOOK_PAGE_UP_KEY_RIGHT:
                    Trook.this.pageUp();
                    return true;

                case NOOK_PAGE_DOWN_KEY_LEFT:
                case NOOK_PAGE_DOWN_KEY_RIGHT:
                    Trook.this.pageDown();
                    return true;

                default:
                    Log.d(TAG, "Ignore keycode "+keyCode);
                    return false;
                }
            }
            return false;
        }
    }

    public final class WifiStatus
    {
        private WifiStatus(boolean v, String m)
        { m_status = v; m_message = m; }

        public boolean isReady()
        { return m_status; }
        public String getMessage()
        { return m_message; }
        private final boolean m_status;
        private final String m_message;
    }

    private FeedManager m_feedmanager;
    private FeedViewCache m_feedviewcache;
    private FeedViewCache.FeedView m_curfeedview;
    private final Object m_wifisync = new Object();
    private ConnectUtils.WifiLock m_wifireflock;
    private ConnectUtils.WifiLock m_wifitimelock;
    private TextView m_curtitle;
    private ViewAnimator m_va;
    private FrameLayout m_framea;
    private FrameLayout m_frameb;
    private OneLineDialog m_dialog;
    private boolean m_usinga = true;
    private Map<String,String> m_parents = new HashMap<String,String>();
    private Map<String,String> m_titles = new HashMap<String,String>();
    private WebView m_webview;

    private final View.OnClickListener m_settings_clicker =
        new View.OnClickListener() {
            @Override
            public void onClick(View v)
            { Trook.this.doFeedDialog(); }
        };

    public interface UriLoadedListener
    { public void uriLoaded(String uri, Reader r); }

    private TextView m_status;
    private Stack<FeedInfo> m_stack = new Stack<FeedInfo>();
    private final static String TROOK_PREFS = "TrookPreferences";
    private final static String TROOK_ROOT_URI = "trook.rooturi";
    private final static String TAG = "trook";
    private PowerManager.WakeLock m_powerlock = null;
    private final static long POWER_DELAY = 120*1000;
    private final static long WIFI_TIMEOUT = 60*1000;
    private final static long WIFI_HOLDON = 120*1000; // extra grace period
    public final static String DEFAULT_FEED_URI =
        "asset:default_root_feed.xml";
    private final static String LOCAL_ROOT_XML_PATH =
        "/system/media/sdcard/my feeds/root.xml";
    private final static String FEED_DIR =
        "/system/media/sdcard/my feeds";
    private final static String MYDOC_ROOT_PATH =
        "/system/media/sdcard/my documents/";

    // magic {thanks hari!}
    private static final int NOOK_PAGE_UP_KEY_RIGHT = 98;
    private static final int NOOK_PAGE_DOWN_KEY_RIGHT = 97;
    private static final int NOOK_PAGE_UP_KEY_LEFT = 96;
    private static final int NOOK_PAGE_DOWN_KEY_LEFT = 95;

    private static final int WEB_SCROLL_PX = 700;
    private static final int LOAD_ID = 1;
    private static final int CLOSE_ID = 2;
    private static final int CANCEL_ID = 3;
    private static final int RESET_ID = 4;
    private static final int BOOKMARK_ID = 5;
}
