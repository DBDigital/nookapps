package com.kbs.trook;

import android.app.Activity;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.ComponentName;
import android.view.View;
import android.view.ViewGroup;
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
import android.widget.CheckBox;
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

        m_preferences = getSharedPreferences(TROOK_PREFS, MODE_WORLD_READABLE);
        m_webview = (WebView) findViewById(R.id.webview);
        m_webview.setClickable(false);
        m_webview.getSettings().setJavaScriptEnabled(true);
        m_webview.getSettings().setUserAgent(1);
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
        m_powerdelay = NookUtils.getScreenSaverDelay(this);

        m_dialog = new OneLineDialog(this, m_webview);

        // Setup the settings panel
        m_settingsview = (ViewGroup)
            getLayoutInflater().inflate
            (R.layout.settingsview, m_va, false);
        ViewGroup sentries = (ViewGroup)
            m_settingsview.findViewById(R.id.settings_entries);
        addButtonSetting
            (sentries, "Open...", "",
             new View.OnClickListener() {
                 public void onClick(View v)
                 { doFeedDialog(); }
             });
        addButtonSetting
            (sentries, "Bookmark", "Bookmarks saved under \"My Feeds\"",
             new View.OnClickListener() {
                 public void onClick(View v)
                 { bookmarkFeed(); }
             });
        addButtonSetting
            (sentries, "Reset", "Restore default root feed",
             new View.OnClickListener() {
                 public void onClick(View v)
                 { setFeedRootPrefs(null); maybeRemoveRootFeed(); }
             });
        m_3g_checkbox = addCheckboxSetting
            (sentries, is3GEnabled(), "Use 3G (with a new sim)",
             new View.OnClickListener() {
                 public void onClick(View v)
                 { set3GEnabled(m_3g_checkbox.isChecked()); }
             });
        addButtonSetting
            (sentries, "Exit", "Stop Trook",
             new View.OnClickListener() {
                 public void onClick(View v)
                 { finish(); }
             });

        // make sure I'm able to get back!
        Button sd = (Button)
            m_settingsview.findViewById(R.id.settings_done);
        sd.setOnClickListener
            (new View.OnClickListener() {
                    public void onClick(View v) {
                        unflipSettingsView();
                    }
                });
        pushViewFromUri(getFeedRoot());
    }

    private void maybeCreateFeedDirectory()
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

    final String getFeedRoot()
    {
        // Priority: first from any preferences
        String ret = getPrefsString(TROOK_ROOT_URI, null);
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
        SharedPreferences.Editor editor = m_preferences.edit();
        if (s != null) {
            editor.putString(TROOK_ROOT_URI, s);
        }
        else {
            editor.remove(TROOK_ROOT_URI);
        }
        editor.commit();
    }

    final void set3GEnabled(boolean v)
    {
        SharedPreferences.Editor editor = m_preferences.edit();
        editor.putBoolean(TROOK_3G_ENABLED, v);
        editor.commit();
    }

    final boolean is3GEnabled()
    { return getPrefsBoolean(TROOK_3G_ENABLED, false); }

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
        boolean use3g = is3GEnabled();
        // First a sanity check.
        if (!ConnectUtils.wifiEnabled(this, use3g)) {
            if (use3g) {
                return new WifiStatus
                    (false, "Please exit airplane_mode\nfrom the Settings");
            }
            else {
                return new WifiStatus
                    (false, "Please enable wifi\nfrom the Settings");
            }
        }

        synchronized (m_wifisync) {
            // Step 1: create both locks as needed
            if (m_wifireflock == null) {
                m_wifireflock =
                    ConnectUtils.newWifiLock
                    (this, TAG+"-refc-"+hashCode(), use3g);
                if (m_wifireflock == null) {
                    return new WifiStatus
                        (false, "Unable to create network lock, sorry");
                }
                if (!ConnectUtils.setReferenceCounted(m_wifireflock, true)) {
                    return new WifiStatus
                        (false, "Unable to set refcount on network lock");
                }
            }
            if (m_wifitimelock == null) {
                m_wifitimelock =
                    ConnectUtils.newWifiLock
                    (this, TAG+"-timed-"+hashCode(), use3g);
                if (m_wifitimelock == null) {
                    return new WifiStatus
                        (false, "Unable to create networktimelock, sorry");
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
                    (false, "Unable to acquire reference on network lock");
            }

            // Step 3: wait for network to turn on, and be careful
            // to release the refcounted lock on failure
            boolean success = false;
            try {
                success =
                    ConnectUtils.waitForService
                    (this,
                     getPrefsLong
                     (PREFS_WIFI_TIMEOUT, DEFAULT_WIFI_TIMEOUT),
                     use3g);
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

        Log.d(TAG, "releasing network, hopefully get a timelock as well");
        synchronized (m_wifisync) {
            // First, acquire a timeout lock just so we give ourselves
            // some time before someone else needs the network
            try {
                ConnectUtils.acquire
                    (m_wifitimelock,
                     getPrefsLong
                     (PREFS_WIFI_HOLDON, DEFAULT_WIFI_HOLDON));
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

    private Button addButtonSetting
        (ViewGroup vg,
         String buttonTitle,
         String description,
         View.OnClickListener cb)
    {
        ViewGroup root = (ViewGroup)
            getLayoutInflater().inflate
            (R.layout.buttonsetting, vg, false);
        Button b = (Button)
            root.findViewById(R.id.button);
        TextView desc = (TextView)
            root.findViewById(R.id.description);
        b.setText(buttonTitle);
        b.setOnClickListener(cb);
        desc.setText(description);
        vg.addView(root);
        return b;
    }

    private CheckBox addCheckboxSetting
        (ViewGroup vg,
         boolean state,
         String description,
         View.OnClickListener cb)
    {
        ViewGroup root = (ViewGroup)
            getLayoutInflater().inflate
            (R.layout.checkboxsetting, vg, false);
        CheckBox b = (CheckBox)
            root.findViewById(R.id.checkbox);
        TextView desc = (TextView)
            root.findViewById(R.id.description);
        b.setChecked(state);
        b.setOnClickListener(cb);
        desc.setText(description);
        vg.addView(root);
        return b;
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

        setOtherFeedView(cached.m_root);
        m_curfeedview = cached;
        return iscached;
    }

    private void setOtherFeedView(View v)
    {
        if (v.getParent() != null) {
            if (v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).
                    removeView(v);
            }
        }

        if (m_usinga) {
            m_frameb.removeAllViews();
            m_frameb.addView(v);
            m_usinga = false;
        }
        else {
            m_framea.removeAllViews();
            m_framea.addView(v);
            m_usinga = true;
        }
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

    // Flip around the current panel, replacing it with
    // the settings view
    final void flipToSettingsView()
    {
        setOtherFeedView(m_settingsview);

        // but -- create a flip transition rather
        // than a slide transition.
        m_va.setInAnimation(this, R.anim.flip_in);
        m_va.setOutAnimation(this, R.anim.flip_out);

        m_va.showNext();
    }

    // Unflip the settings view, can assume that the correct
    // panel is already set on the back side.
    final void unflipSettingsView()
    {
        if (m_usinga) {
            m_usinga = false;
        }
        else {
            m_usinga = true;
        }
        m_va.showNext();
    }

    final boolean popFeedView(String uri)
    {
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
        // Log.d(TAG, "adding feed entry "+ei.getId());

        FeedInfo fi = ei.getFeedInfo();
        // Only add if we have it cached somewhere
        FeedViewCache.FeedView fv =
            m_feedviewcache.getFeedViewNoLRUUpdate(fi.getUri());
        if (fv == null) {
            // ignore
            return;
        }

        URI base = null;
        try { base = new URI(fi.getUri()); }
        catch (Throwable ign) {
            Log.d(TAG, "Ignoring base error ", ign);
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
            // If the uri is relative, and the base is a "file" scheme,
            // first make the uri absolute.
            if (!(uri.isAbsolute()) &&
                (base != null) &&
                ("file".equals(base.getScheme()))) {
                uri = Uri.parse(URIUtils.resolve(base, iuri).toString());
            }

            // Log.d(TAG, "And the icon uri is "+uri);
            if ("file".equals(uri.getScheme())) {
                // Surprisingly, this takes a path rather
                // than a URI
                doit.setImageURI(Uri.parse(uri.getPath()));
                iuri_set = true;
            }
            else {
                // Log.d(TAG, "Could not set image uri because "+uri.getScheme());
            }
        }

        boolean did_something = false;
        String baseuri = ei.getFeedInfo().getUri();

        for (FeedInfo.LinkInfo li : ei.getLinks()) {
            String href = li.getAttribute("href");
            
            if (href == null) {
                continue;
            }

            URI uriref;
            try { uriref = URIUtils.resolve(new URI(baseuri), href); }
            catch (Throwable th) {
                Log.d(TAG, "Ignoring link "+href, th);
                continue;
            }

            String type = li.getAttribute("type");
            // Log.d(TAG, "Examining type for "+uriref+" = "+type);

            if (isABook(type) ||
                isAnAudio(type) ||
                isAPackage(type)) {

                // These are downloadable/viewable media, so switch
                // to view/download, depending on whether the href
                // is remote or local

                boolean islocal = "file".equals(uriref.getScheme());

                // Log.d(TAG, uriref+" -- local= "+islocal);

                if (islocal) {
                    doit.setOnClickListener
                        (new MimeViewClicker(uriref, type));
                    if (!iuri_set) {
                        doit.setImageResource
                            (getDefaultIconResource(type, islocal));
                    }
                }
                else {
                    doit.setOnClickListener
                        (makeMimeDownloadClicker(baseuri, href, type, ei));
                    doit.setImageResource
                        (getDefaultIconResource(type, islocal));
                }
                did_something = true;
                break;
            }
            else if (IMimeConstants.MIME_TROOK_DIRECTORY.equals(type)) {
                // Verify I really have a directory here
                Uri chk = Uri.parse(href);
                if (chk == null) { return; }
                File fchk = new File(chk.getPath());
                if (!fchk.isDirectory()) { return; }

                doit.setOnClickListener
                    (new DirectoryClicker(href));
                if (!iuri_set) {
                    doit.setImageResource(R.drawable.directory);
                }
                did_something = true;
                break;
            }
            else if (IMimeConstants.MIME_ATOM_XML.equals(type)) {
                doit.setOnClickListener
                    (new LaunchFeed(baseuri, href));
                if (!iuri_set) {
                    doit.setImageResource(R.drawable.feed);
                }
                did_something = true;
                // Keep looking, in case there's a better fit.
                // break;
            }
            else if (IMimeConstants.MIME_ATOM_XML_LIBRARY.equals(type)) {
                doit.setOnClickListener
                    (new LaunchFeed(baseuri, href));
                if (!iuri_set) {
                    doit.setImageResource(R.drawable.library);
                }
                did_something = true;
                // Keep looking, in case there's a better fit.
                // break;
            }
            else if (IMimeConstants.MIME_HTML.equals(type) ||
                     IMimeConstants.MIME_XHTML.equals(type) ||
                     null == type) {
                doit.setOnClickListener(new LaunchBrowser(href));
                if (!iuri_set) {
                    doit.setImageResource(R.drawable.webkit);
                }
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

    private final int getDefaultIconResource(String type, boolean islocal)
    {
        if (IMimeConstants.MIME_EPUB_ZIP.equals(type) ||
            IMimeConstants.MIME_EPUB.equals(type)) {
            return islocal?R.drawable.epub:R.drawable.epub_download;
        }

        if (IMimeConstants.MIME_PDF.equals(type)) {
            return islocal?R.drawable.pdf:R.drawable.pdf_download;
        }
        
        if (IMimeConstants.MIME_PDB.equals(type)) {
            return islocal?R.drawable.pdb:R.drawable.pdb_download;
        }

        if (IMimeConstants.MIME_MP3.equals(type)) {
            return islocal?R.drawable.mp3:R.drawable.mp3_download;
        }

        if (IMimeConstants.MIME_APK.equals(type)) {
            return islocal?R.drawable.apk:R.drawable.apk_download;
        }

        // random catch-all thing
        Log.d(TAG, "Unknown icon type "+type);
        return R.drawable.epub;
    }

    private final DownloadClicker makeMimeDownloadClicker
        (String baseuri, String href, String type, FeedInfo.EntryInfo ei)
    {
        String[] hrefs;
        String[] mimes;
        String[] targets;

        // Attempt to download a thumbnail icon if something's here
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
            targets[1] = makeDownloadPath(baseuri, href, ei, type, ".jpg");
        }

        hrefs[0] = href;
        mimes[0] = type;
        targets[0] = makeDownloadPath(baseuri, href, ei, type, null);
        return new DownloadClicker
            (baseuri, hrefs, targets, mimes);
    }

    private final String makeDownloadPath
        (String baseuri, String href, FeedInfo.EntryInfo ei,
         String type, String suffix)
    {
        // For a slightly less insane way to make paths to downloaded items
        // consistent, by some personal definition of consistency:
        //
        // I first group downloads by type, and attempt to put things
        // into any B&N buckets (eg: books and music). For other
        // types, I make up a root location.
        //
        // Next, I place things in a 2-level hierarchy -- by author, and
        // then by title. If there's no author, it's called "unknown". If
        // there's no title, I make one up by mashing up the URL. If there's
        // a file already with that exact path (typically a re-download, or
        // a bug in my attempt to make a path) I prepend a number to avoid
        // overwriting the file of interest.
        //
        // Let me know if you have other suggestions on how to manage
        // this.

        String author = ei.getAuthor();
        if (author == null) {
            author = "Unknown";
        }

        // Determine a top-level root.
        String root;
        String type_suffix;
        if (isABook(type)) {
            root =
                getPrefsString
                (PREFS_DOC_DOWNLOAD_ROOT,
                 DEFAULT_DOC_DOWNLOAD_ROOT);
            if (IMimeConstants.MIME_PDF.equals(type)) {
                type_suffix = ".pdf";
            }
            else if (IMimeConstants.MIME_PDB.equals(type)) {
                type_suffix = ".pdb";
            }
            else {
                type_suffix = ".epub"; // oh well.
            }
            // ah, what an ugly function name
            author = fileSafe(lastNameify(author));
        }
        else if (isAnAudio(type)) {
            root =
                getPrefsString
                (PREFS_MUSIC_DOWNLOAD_ROOT,
                 DEFAULT_MUSIC_DOWNLOAD_ROOT);
            type_suffix = ".mp3";
        }
        else if (isAPackage(type)) {
            root =
                getPrefsString
                (PREFS_PACKAGE_DOWNLOAD_ROOT,
                 DEFAULT_PACKAGE_DOWNLOAD_ROOT);
            type_suffix = ".apk";
        }
        else {
            root =
                getPrefsString
                (PREFS_FALLBACK_DOWNLOAD_ROOT,
                 DEFAULT_FALLBACK_DOWNLOAD_ROOT);
            type_suffix = "";
        }

        String title = ei.getTitle();
        if (title == null) {
            // to have some vague chance of figuring out
            // where this came from, I fall back to
            // the base URI.
            title = sanitizeUniqueName(baseuri, href);
        }
        else {
            title = fileSafe(title);
        }

        if (suffix == null) {
            return root + "/"+author+"/"+title+type_suffix;
        }
        else {
            return root +"/"+author+"/"+title+suffix;
        }
    }

    private final static String lastNameify(String author)
    {
        // Juvenile attempt to normalize the author by
        // Lastname, It Doesn't Always Work, Does It?
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
        return author;
    }

    private final String getPrefsString(String k, String dflt)
    { return m_preferences.getString(k, dflt); }

    private final long getPrefsLong(String k, long dflt)
    { return m_preferences.getLong(k, dflt); }

    private final boolean getPrefsBoolean(String k, boolean dflt)
    { return m_preferences.getBoolean(k, dflt); }

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
            m_powerlock.acquire(m_powerdelay);
        }
    }

    @Override
    public void onResume()
    {
    	super.onResume();
        if (m_powerlock != null) {
            m_powerlock.acquire(m_powerdelay);
        }
        NookUtils.setAppTitle(this, Version.VERSION);
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

    private final class MimeViewClicker
        implements View.OnClickListener
    {
        private MimeViewClicker(URI uri, String type)
        {
            m_uri = uri;

            // translations for intent
            if (IMimeConstants.MIME_EPUB_ZIP.equals(type)) {
                m_type = IMimeConstants.MIME_EPUB;
            }
            else {
                m_type = type;
            }

            if (isABook(type)) {
                m_intent = "com.bravo.intent.action.VIEW";
            }
            else {
                m_intent = Intent.ACTION_VIEW;
            }
        }

        public void onClick(View v)
        {
            Intent ri = new Intent(m_intent);

            // pdb files seem to have a bug -- they seem to
            // require decoded uris.

            String suri;
            if (IMimeConstants.MIME_PDB.equals(m_type)) {
                suri = Uri.decode(m_uri.toString());
            }
            else {
                suri = m_uri.toString();
            }

            ri.setDataAndType(Uri.parse(suri), m_type);
            try { Trook.this.startActivity(ri); }
            catch (Throwable th) {
                Log.d(TAG, "Unable to view "+m_uri+", ("+m_type+")", th);
                Trook.this.displayError(m_uri+"\n: failed to view\n"+
                                        th.toString());
            }
        }

        private final URI m_uri;
        private final String m_type;
        private final String m_intent;
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

    private final static String fileSafe(String s)
    { return s.replaceAll("[\\/:]", " - "); }

    private final static String sanitizeTitle(CharSequence title, String uri)
    {
        // Try to use the title if possible.
        if ((title != null) && (title.length() > 0)) {
            return fileSafe(title.toString());
        }
        // Otherwise, just the hostname
        Uri auri = Uri.parse(uri);
        String ret = auri.getHost();
        if (ret == null) {
            ret = "Unknown feed";
        }
        return ret;
    }

    private final static boolean isABook(String type)
    {
        return
            IMimeConstants.MIME_EPUB_ZIP.equals(type) ||
            IMimeConstants.MIME_EPUB.equals(type) ||
            IMimeConstants.MIME_PDF.equals(type) ||
            IMimeConstants.MIME_PDB.equals(type);
    }

    private final static boolean isAnAudio(String type)
    {
        return
            IMimeConstants.MIME_MP3.equals(type);
    }

    private final static boolean isAPackage(String type)
    {
        return
            IMimeConstants.MIME_APK.equals(type);
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
            msg.setDataAndType(Uri.parse(m_href), IMimeConstants.MIME_HTML);

            Log.d(TAG, "checking if there's someone who can process "+
                  msg);
            // 1.3 -- native browser doesn't exit activity, it goes all the
            // way back to root. ugh.
            //if (Trook.this.isIntentAvailable(msg)) {
            if (false) {
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

    private SharedPreferences m_preferences;
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
    private ViewGroup m_settingsview;
    private CheckBox m_3g_checkbox;
    private boolean m_usinga = true;
    private Map<String,String> m_parents = new HashMap<String,String>();
    private Map<String,String> m_titles = new HashMap<String,String>();
    private WebView m_webview;

    private final View.OnClickListener m_settings_clicker =
        new View.OnClickListener() {
            @Override
            public void onClick(View v)
            { Trook.this.flipToSettingsView(); }
        };

    public interface UriLoadedListener
    { public void uriLoaded(String uri, Reader r); }

    private TextView m_status;
    private Stack<FeedInfo> m_stack = new Stack<FeedInfo>();
    public final static String TROOK_PREFS = "TrookPreferences";
    private final static String TROOK_ROOT_URI = "trook.rooturi";
    public final static String TROOK_3G_ENABLED = "trook.enable3g";
    private final static String TAG = "trook";
    private PowerManager.WakeLock m_powerlock = null;
    private long m_powerdelay = NookUtils.DEFAULT_SCREENSAVER_DELAY;

    // Wait this long to turn on wifi
    private final static String PREFS_WIFI_TIMEOUT = "trook.wifi.timeout";
    private final static long DEFAULT_WIFI_TIMEOUT = 60*1000;

    // keep wifi up for this long after any network operation
    private final static String PREFS_WIFI_HOLDON = "trook.wifi.holdon";
    private final static long DEFAULT_WIFI_HOLDON = 300*1000;

    public final static String DEFAULT_FEED_URI =
        "asset:default_root_feed.xml";
    private final static String LOCAL_ROOT_XML_PATH =
        "/system/media/sdcard/my feeds/root.xml";
    private final static String FEED_DIR =
        "/system/media/sdcard/my feeds";

    // various download areas
    private final static String PREFS_DOC_DOWNLOAD_ROOT =
        "trook.doc.download.root";
    private final static String DEFAULT_DOC_DOWNLOAD_ROOT =
        "/system/media/sdcard/my documents/Downloads";
    private final static String PREFS_MUSIC_DOWNLOAD_ROOT =
        "trook.music.download.root";
    private final static String DEFAULT_MUSIC_DOWNLOAD_ROOT =
        "/system/media/sdcard/my music/Downloads";
    private final static String PREFS_PACKAGE_DOWNLOAD_ROOT =
        "trook.packages.download.root";
    private final static String DEFAULT_PACKAGE_DOWNLOAD_ROOT =
        "/system/media/sdcard/my packages";
    private final static String PREFS_FALLBACK_DOWNLOAD_ROOT =
        "trook.fallback.download.root";
    private final static String DEFAULT_FALLBACK_DOWNLOAD_ROOT =
        "/system/media/sdcard/my documents/Downloads";

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

    // 

}
