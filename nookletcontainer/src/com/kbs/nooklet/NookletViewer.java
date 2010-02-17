package com.kbs.nooklet;

// This one is the wrapper around the nooklet, which loads up the
// files, provides access to a small set of android-dy functions in
// javascript, manages nooklet lifecycle events etc.

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.KeyEvent;
import android.widget.Toast;
import android.widget.Button;
import android.os.PowerManager;
import android.util.Log;
import android.net.Uri;
import android.util.Xml;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.kbs.util.NookUtils;

import java.io.IOException;
import java.io.File;
import java.io.Reader;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParser;

public class NookletViewer extends Activity
    implements View.OnKeyListener
{
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        Button backB = (Button)
            findViewById(R.id.back);
        backB.setOnClickListener
            (new View.OnClickListener()
                { public void onClick(View x)
                    { finish(); }});

        // First get hold of the path to our nooklet.xml,
        // otherwise life is not good.

        NookletInfo ni = discoverInfo();
        if (ni == null) {
            // ah well -- die.
            Log.d(TAG, "Unable to discover info");
            return;
        }

        // Build up our view containers, random initializations, etc.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        m_powerlock = pm.newWakeLock
            (PowerManager.SCREEN_DIM_WAKE_LOCK, "nookletviewer:"+hashCode());
        m_powerlock.setReferenceCounted(false);
        m_powerdelay = NookUtils.getScreenSaverDelay(this);

        m_title = ni.getTitle();

        // set up the two html containers
        WebView tv = (WebView) findViewById(R.id.touchview);
        tv.clearCache(true);
        tv.setClickable(true);
        tv.getSettings().setJavaScriptEnabled(true);

        WebView ev = (WebView) findViewById(R.id.einkview);
        ev.clearCache(true);
        ev.setClickable(false);
        ev.getSettings().setJavaScriptEnabled(true);
        ev.setOnKeyListener(this);

        // stuff js variables
        m_ctx = new Ctx(ev, tv, ni.getBase());
        tv.addJavascriptInterface(m_ctx, "nook");
        ev.addJavascriptInterface(m_ctx, "nook");

        if (ni.getTouch() != null) {
            tv.loadUrl(Uri.fromFile(ni.getTouch()).toString());
        }
        if (ni.getEink() != null) {
            ev.loadUrl(Uri.fromFile(ni.getEink()).toString());
        }
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
        if (m_title != null) {
            NookUtils.setAppTitle(this, m_title);
        }
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (m_powerlock != null) {
            m_powerlock.release();
        }

        // give the nooklet a chance to save itself
        WebView wv = m_ctx.getEink();
        wv.loadUrl("javascript:(nooklet&&nooklet.save&&nooklet.save())");
        wv = m_ctx.getTouch();
        wv.loadUrl("javascript:(nooklet&&nooklet.save&&nooklet.save())");
    }

    public boolean onKey(View v, int keyCode, KeyEvent ev)
    {
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
            case NOOK_PAGE_UP_KEY_LEFT:
            case NOOK_PAGE_UP_KEY_RIGHT:
            case NOOK_PAGE_DOWN_KEY_LEFT:
            case NOOK_PAGE_DOWN_KEY_RIGHT:
                xferEvent(keyCode);
                break;
            default:
                break;
            }
        }
        return false;
    }

    private void xferEvent(int keyCode)
    {
        if (m_ctx == null) { return; }
        if (m_ctx.getEink() != null) {
            m_ctx.getEink().loadUrl
                ("javascript:(nooklet&&nooklet.onKey&&nooklet.onKey("+
                 keyCode+"))");
        }
        if (m_ctx.getTouch() != null) {
            m_ctx.getTouch().loadUrl
                ("javascript:(nooklet&&nooklet.onKey&&nooklet.onKey("+
                 keyCode+"))");
        }            
    }

    private NookletInfo discoverInfo()
    {
        Uri data = getIntent().getData();
        // some sanity checks
        if (!"file".equals(data.getScheme())) {
            error("I can only process files -- "+data);
            return null;
        }

        String path = data.getPath();
        if (path == null) {
            error("Missing path "+data);
            return null;
        }
        File f = new File(path);
        if (!f.canRead()) {
            error("Unable to read "+data);
            return null;
        }

        XmlPullParser p = Xml.newPullParser();
        Reader r = null;
        double version = -1;
        try {
            r =
                new BufferedReader
                (new InputStreamReader
                 (new FileInputStream(f)));
            p.setInput(r);

            P.skipToStart(p, null);
            P.assertStart(p, "nooklet");
            p.next();
            while (P.skipToStart(p, null)) {
                String curtag = p.getName();
                if (curtag.equals("version")) {
                    version =
                        Double.parseDouble
                        (P.collectText(p).trim());
                }
                else {
                    P.skipThisBlock(p);
                }
            }
            if (version < 0) {
                error("Unable to detect version");
                return null;
            }

            if (version > (CURRENT_VERSION+0.001)) {
                error("Sorry, unable to run version "+version+" nooklet");
                return null;
            }
        }
        catch (Throwable th) {
            Log.d(TAG, "bad issue", th);
            error(data+":"+th.toString());
            return null;
        }
        finally {
            if (r != null) {
                try { r.close(); }
                catch (Throwable ign) {}
            }
        }

        // Now create an appropriate NookletInfo file
        // Check for naming-convention files.
        File parent = f.getParentFile();
        NookletInfo ret;

        if (parent != null) {
            ret = new NookletInfo(version, parent);
            File t = new File(parent, "touch.html");
            if (t.canRead()) {
                ret.setTouch(t);
            }
            File e = new File(parent, "eink.html");
            if (e.canRead()) {
                ret.setEink(e);
            }
        }
        else {
            ret = new NookletInfo(version, null);
        }
        return ret;
    }

    private void error(String msg)
    {
        Log.d(TAG, msg);
        Toast.makeText
            (getApplicationContext(),
             msg, Toast.LENGTH_LONG).show();
    }


    private String m_title = null;
    private Ctx m_ctx;

    private PowerManager.WakeLock m_powerlock = null;
    private long m_powerdelay = NookUtils.DEFAULT_SCREENSAVER_DELAY;
    private final static String TAG = "nooklet-viewer";
    private final static int CURRENT_VERSION = 1;

    // helper classes

    private class NookletInfo
    {
        NookletInfo(double v, File p)
        {
            m_version = v;
            m_basedir = p;
            if (p == null) {
                m_title = "";
            }
            else {
                m_title = p.getName();
            }
        }


        void setEink(File f)
        { m_eink = f; }
        void setTouch(File f)
        { m_touch = f; }
        File getEink()
        { return m_eink; }
        File getTouch()
        { return m_touch; }
        String getTitle()
        { return m_title; }
        File getBase()
        { return m_basedir; }

        private final double m_version;
        private final String m_title;
        private final File m_basedir;
        private File m_touch = null;
        private File m_eink = null;
    }

    // this class is used from javascript.
    public class Ctx
    {
        Ctx(WebView eink, WebView touch, File basedir)
        {
            m_eink = eink;
            m_touch = touch;
            m_basedir = basedir;
        }

        public WebView getEink()
        { return m_eink; }

        public WebView getTouch()
        { return m_touch; }

        public void writeData(String k, String v)
        {
            k = k.replaceAll("/", "_");
            File data = new File(m_basedir, "nooklet.data."+k+".txt");
            FileOutputStream fi = null;
            try {
                byte[] buf = v.getBytes("utf-8");
                fi = new FileOutputStream(data);
                fi.write(buf);
            }
            catch (Throwable th) {
                Log.d(TAG, "Failed to write "+data, th);
                error(data+":"+th.toString());
            }
            finally {
                if (fi != null) {
                    try {fi.close();}
                    catch (Throwable ign) {}
                }
            }
        }

        public void log(String t, String s)
        { Log.d(t, s); }

        public double getContainerVersion()
        { return CURRENT_VERSION; }

        public String readData(String k)
        {
            if (m_basedir == null) { return ""; }
            if (k == null) { return ""; }
            k = k.replaceAll("/", "_");

            File data = new File(m_basedir, "nooklet.data."+k+".txt");
            if (!data.canRead()) {
                return "";
            }

            String contents = null;
            FileInputStream fi = null;
            try {
                fi = new FileInputStream(data);
                byte[] buf = new byte[fi.available()];
                fi.read(buf);
                contents = new String(buf, "utf-8");
            }
            catch (Throwable th) {
                Log.d(TAG, "Error reading "+data, th);
                error(data+":"+th.toString());
                return "";
            }
            finally {
                if (fi != null) {
                    try { fi.close(); }
                    catch (Throwable ign) {}
                }
            }
            if (contents == null) { return ""; }
            return contents;
        }

        private final WebView m_eink;
        private final WebView m_touch;
        private final File m_basedir;
    }
    private static final int NOOK_PAGE_UP_KEY_RIGHT = 98;
    private static final int NOOK_PAGE_DOWN_KEY_RIGHT = 97;
    private static final int NOOK_PAGE_UP_KEY_LEFT = 96;
    private static final int NOOK_PAGE_DOWN_KEY_LEFT = 95;
}
