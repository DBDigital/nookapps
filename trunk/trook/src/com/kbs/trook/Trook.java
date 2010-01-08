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
import android.util.Log;
import android.text.Html;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.os.PowerManager;

import android.widget.ViewAnimator;

import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.Reader;
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

        m_feed_url_dialog = new Dialog(this);
        m_feed_url_dialog.setContentView(R.layout.feed_url_dialog);
        m_feed_url_dialog.setTitle("Set root feed");
        m_feed_url_dialog.setCancelable(true);
        m_feed_url_dialog.setCanceledOnTouchOutside(true);

        m_feed_url_et = (EditText)
            m_feed_url_dialog.findViewById(R.id.feed_url);
        m_feed_url_et.setOnKeyListener(new FeedUrlListener());

        String rootUri = getFeedRoot();

        // populate with whatever is our root URL
        if (rootUri != null) {
            pushViewFromUri(rootUri);
        }
        else {
            pushViewFromReader
                (DEFAULT_FEED_XML,
                 new BufferedReader
                 (new InputStreamReader
                  (getResources()
                   .openRawResource
                   (R.raw.default_root_feed))));
        }
    }

    private void doDialog()
    {
        String s = getFeedRoot();
        if (s == null) { s = "http://"; }
        m_feed_url_et.setText(s);
        m_feed_url_et.requestFocus();
        m_feed_url_dialog.show();
        InputMethodManager imm =
            (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        imm.showSoftInput(m_webview,InputMethodManager.SHOW_FORCED);
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
    final void displayError(String msg)
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
        SharedPreferences settings =
            getSharedPreferences(TROOK_PREFS, MODE_WORLD_READABLE);
        return settings.getString(TROOK_ROOT_URI, null);
    }

    final void setFeedRoot(String s)
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
        if (DEFAULT_FEED_XML.equals(uri)) {
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
        // Special case
        if (DEFAULT_FEED_XML.equals(uri)) {
            Reader r =
                new BufferedReader
                (new InputStreamReader
                 (getResources()
                  .openRawResource
                  (R.raw.default_root_feed)));
            asyncLoadFeedFromReader(uri, r);
            return;
        }

        m_feedmanager.asyncLoadFeedFromUri(uri);
    }        

    // Only to be called from UI thread
    final void asyncLoadFeedFromReader(String uri, Reader r)
    { m_feedmanager.asyncLoadFeedFromReader(uri, r); }

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
    final void statusUpdate(String s)
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
    final void addFeedInfo(FeedInfo fi)
    {
        // We only bother updating things that are
        // in our cache.
        m_titles.put(fi.getUri(), fi.getTitle());

        FeedViewCache.FeedView cached =
            m_feedviewcache.getFeedView(fi.getUri());
        if (cached == null) {
            return;
        }
        cached.m_title.setText(fi.getTitle());
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
            (uri, root, title, prev, entries);
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

    // This should only be called from the UI thread
    final void addFeedEntry(FeedInfo.EntryInfo ei)
    {
        FeedInfo fi = ei.getFeedInfo();
        // Only add if we have it cached somewhere
        FeedViewCache.FeedView fv =
            m_feedviewcache.getFeedView(fi.getUri());
        if (fv == null) {
            // ignore
            return;
        }

        ViewGroup el = (ViewGroup)
            getLayoutInflater().inflate
            (R.layout.entryview, fv.m_entries, false);
        Button doit = (Button)
            el.findViewById(R.id.doit);
        ImageView ib = (ImageView)
            el.findViewById(R.id.icon);

        // Check if we have any epub download links

        /*
          This is too simpleminded to get icons. TBD
        String iuri = ei.getIconUri();
        if (iuri != null) {
            try {
                URI base = new URI(fi.getUri());
                URI ref = URIUtils.resolve(base, iuri);
                ib.setImageURI(Uri.parse(ref.toString()));
            }
            catch (Throwable th) {
                iuri = null;
            }
        }

        */

        boolean did_something = false;
        String baseuri = ei.getFeedInfo().getUri();

        for (FeedInfo.LinkInfo li : ei.getLinks()) {
            String href = li.getAttribute("href");

            if (href == null) {
                continue;
            }

            String type = li.getAttribute("type");
            if ("application/epub+zip".equals(type)) {
                doit.setText(R.string.epub_download_text);
                doit.setOnClickListener
                    (new DownloadClicker
                     (baseuri, href,
                      "/system/media/sdcard/my documents/"+
                      sanitizeUniqueName(baseuri, href)+".epub", type));
                ib.setImageResource(R.drawable.epub);
                did_something = true;
                break;
            }
            else if ("audio/mp3".equals(type)) {
                doit.setText(R.string.mp3_download_text);
                doit.setOnClickListener
                    (new DownloadClicker
                     (baseuri, href,
                      "/system/media/sdcard/my music/"+
                      sanitizeUniqueName(baseuri, href)+".mp3", type));
                ib.setImageResource(R.drawable.mp3);
                did_something = true;
                break;
            }
            else if ("application/vnd.android.package-archive".equals(type)) {
                doit.setText(R.string.apk_download_text);

                doit.setOnClickListener
                    (new DownloadClicker
                     (baseuri, href,
                      sanitizeUniqueName(baseuri, href)+".apk", type));
                ib.setImageResource(R.drawable.apk);
                did_something = true;
                break;
            }                
            else if ("application/atom+xml".equals(type)) {
                doit.setText(R.string.feed_text);
                doit.setOnClickListener
                    (new LaunchFeed(baseuri, href));
                ib.setImageResource(R.drawable.feed);
                did_something = true;
                break;
            }
            else if ("text/html".equals(type) ||
                     "text/xhtml".equals(type) ||
                     null == type) {
                doit.setText(R.string.view_text);
                doit.setOnClickListener(new LaunchBrowser(href));
                ib.setImageResource(R.drawable.webkit);
                did_something = true;
                // but keep looking, in case we find a better fit.
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
            content += "<h1>"+ei.getTitle()+"</h1>";
        }
        if (ei.getContent() != null) {
            content += ei.getContent();
        }
        else if (ei.getSummary() != null) {
            content += ei.getSummary();
        }
        etv.setText(Html.fromHtml(content));
        fv.m_entries.addView(el);
        // Log.d(TAG, "Added view with "+ei.getTitle());
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
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (m_powerlock != null) {
            m_powerlock.release();
        }
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

    private final class DownloadClicker
        implements View.OnClickListener
    {
        private DownloadClicker
            (String base, String href, String target, String mime)
        {
            m_base = base;
            m_href = href;
            m_target = target;
            m_mime = mime;
        }

        public void onClick(View v)
        {
            try {
                URI base = new URI(m_base);
                URI ref = URIUtils.resolve(base, m_href);
                // Log.d(TAG, "Found base URL = "+ref);

                Intent dsi = new Intent();
                dsi.setDataAndType
                    (Uri.parse(ref.toString()), m_mime);
                dsi.putExtra(DownloadService.TARGET, m_target);
                dsi.setComponent
                    (new ComponentName
                     ("com.kbs.trook", "com.kbs.trook.DownloadService"));
                startService(dsi);
                displayShortMessage("Starting download in the background");
            }
            catch (Throwable th) {
                Log.d(TAG, "download fails", th);
                Trook.this.displayError("Failed to load "+m_href+"\n"+th);
            }
        }
        private final String m_href;
        private final String m_base;
        private final String m_target;
        private final String m_mime;
    }

    private final boolean isIntentAvailable(Intent msg)
    {
        final PackageManager packageManager = getPackageManager();
        List<ResolveInfo> list =
            packageManager.queryIntentActivities
            (msg, PackageManager.MATCH_DEFAULT_ONLY);
        return list.size() > 0;
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

    private class FeedUrlListener
        implements View.OnKeyListener
    {
	public boolean onKey(View view, int keyCode, KeyEvent keyEvent)
        {
            if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                if (view instanceof EditText) {
                    EditText et = (EditText) view;
                    if (keyCode == SOFT_KEYBOARD_CLEAR ) {
                        et.setText("");
                    }
                    else if (keyCode == SOFT_KEYBOARD_SUBMIT) {
                        String text = et.getText().toString();
                        if ((text != null) && (text.length() >0)) {
                            Trook.this.setFeedRoot(text);
                            Trook.this.pushViewFromUri(text);
                        }
                        else {
                            Trook.this.setFeedRoot(null);
                        }
                        Trook.this.m_feed_url_dialog.dismiss();
                    }
                    else if (keyCode ==  SOFT_KEYBOARD_CANCEL) {
                        Trook.this.m_feed_url_dialog.dismiss();
                    }
                }
            }
            return false;
	}
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
    private Dialog m_feed_url_dialog;
    private EditText m_feed_url_et;
    private boolean m_usinga = true;
    private Map<String,String> m_parents = new HashMap<String,String>();
    private Map<String,String> m_titles = new HashMap<String,String>();
    private WebView m_webview;
    private final View.OnClickListener m_settings_clicker =
        new View.OnClickListener() {
            @Override
            public void onClick(View v)
            { Trook.this.doDialog(); }
        };

    private TextView m_status;
    private Stack<FeedInfo> m_stack = new Stack<FeedInfo>();
    private final static String TROOK_PREFS = "TrookPreferences";
    private final static String TROOK_ROOT_URI = "trook.rooturi";
    private final static String TAG = "trook";
    private PowerManager.WakeLock m_powerlock = null;
    private final static long POWER_DELAY = 120*1000;
    private final static long WIFI_TIMEOUT = 60*1000;
    private final static long WIFI_HOLDON = 120*1000; // extra grace period
    private final static String DEFAULT_FEED_XML = "default_root_feed.xml";

    // magic {thanks hari!}
    private static final int NOOK_PAGE_UP_KEY_RIGHT = 98;
    private static final int NOOK_PAGE_DOWN_KEY_RIGHT = 97;
    private static final int NOOK_PAGE_UP_KEY_LEFT = 96;
    private static final int NOOK_PAGE_DOWN_KEY_LEFT = 95;
    private static final int SOFT_KEYBOARD_CLEAR=-13;
    private static final int SOFT_KEYBOARD_SUBMIT=-8;
    private static final int SOFT_KEYBOARD_CANCEL=-3;

    private static final int WEB_SCROLL_PX = 700;
}
