package com.kbs.trook;

import android.app.IntentService;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.File;
import java.net.URL;
import java.net.URLConnection;
import java.net.HttpURLConnection;
import java.util.List;

import com.kbs.util.ConnectUtils;

public class DownloadService
    extends IntentService
{

    private String mError = null;

    public DownloadService()
    { this(TAG); }

    public DownloadService(String s)
    { super(s); }

    @Override
    protected void onHandleIntent(Intent intent)
    {
        try { realOnHandleIntent(intent); }
        catch (Throwable th) {
            Log.d(TAG, "onHandleIntent fails", th);
            bail(th.toString());
        }
    }

    private final void realOnHandleIntent(Intent intent)
    {            
        Log.d(TAG, "on-handle-intent: "+intent);

        Uri source = intent.getData();
        Log.d(TAG, "on-handle-intent: src="+source);

        String mime = intent.getType();
        Log.d(TAG, "on-handle-intent: target="+mime);

        String target = intent.getStringExtra(TARGET);
        Log.d(TAG, "on-handle-intent: target="+target);

        FileOutputStream out = null;
        InputStream inp = null;
        if (!ConnectUtils.wifiEnabled(this)) {
            bail("sorry, please turn on wifi");
            return;            
        }

        ConnectUtils.WifiLock wakelock = null;
        wakelock = ConnectUtils.newWifiLock(this, TAG+hashCode());
        if (wakelock == null) {
            bail("sorry, could not create new wifi lock");
            return;
        }
        if (!ConnectUtils.setReferenceCounted(wakelock, true)) {
            bail("Sorry, could not set refcount on lock");
            return;
        }

        if (!ConnectUtils.acquire(wakelock)) {
            bail("Sorry, could not acquire wifi lock");
            return;
        }

        // protect the rest of this with a finally
        boolean ok = false;
        try {
            if (!ConnectUtils.waitForService(this, TIMEOUT_WAIT)) {
                bail("sorry, network was not established");
                return;
            }

            URL url = new URL(source.toString());
            if (target.startsWith("/")) {
                out = new FileOutputStream(target);
            }
            else {
                // do a check here for silly errors with a
                // path separator in here...
                out = openFileOutput(target, MODE_WORLD_READABLE);
            }

            Log.d(TAG, "Opening stream to "+url);
            URLConnection connection = url.openConnection();
            if (!(connection instanceof HttpURLConnection)) {
                bail(url+": sorry, can only download http");
                return;
            }
            HttpURLConnection huc = (HttpURLConnection) connection;
            huc.setRequestProperty
                ("User-agent", "trook-news-reader");
            huc.connect();
            if (huc.getResponseCode() != 200) {
                bail(url+": "+huc.getResponseMessage());
                return;
            }

            inp = huc.getInputStream();
            byte buf[] = new byte[8192];

            int count;

            while ((count = inp.read(buf)) > 0) {
                // Log.d(TAG, "Read "+count+" bytes");
                out.write(buf, 0, count);
            }
            Log.d(TAG, "Finished with "+url);
            out.flush();
            ok = true;
        }
        catch (Exception ex) {
            bail("Sorry, exception "+ex.toString());
            Log.d(TAG, "exception", ex);
            return;
        }
        finally {
            if (inp != null) {
                try { inp.close(); } catch (Throwable ign){}
            }
            if (out != null) {
                try { out.close(); } catch (Throwable ign) {}
            }
            if (!ok) {
                try {
                    makeFileFromTarget(target).delete();
                } catch (Throwable ign) {}
            }
            ConnectUtils.release(wakelock);
        }

        // Finally, if we have something that can view our
        // content -- launch it!

        Log.d(TAG, "Finished downloading");

        File targetfile = makeFileFromTarget(target);

        Intent msg = new Intent(Intent.ACTION_VIEW);
        msg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        msg.setDataAndType(Uri.fromFile(targetfile), mime);
        if (isIntentAvailable(msg)) {
            Log.d(TAG, "About to start activity");
            startActivity(msg);
        }
        else {
            Log.d(TAG, "Quiet exit");
            // bail("Download complete");
        }
    }

    private final File makeFileFromTarget(String target)
    {
        if (target.startsWith("/")) {
            return new File(target);
        }
        else {
            return getFileStreamPath(target);
        }
    }

    private final boolean isIntentAvailable(Intent msg)
    {
        Log.d(TAG, "Checking if I have any defaults for "+msg);
        final PackageManager packageManager = getPackageManager();
        List<ResolveInfo> list =
            packageManager.queryIntentActivities
            (msg, PackageManager.MATCH_DEFAULT_ONLY);
        return list.size() > 0;
    }

    private final void bail(final String msg)
    {
        Log.d(TAG, msg);
        mError = msg;
    }

    @Override
    public void onDestroy()
    {
        if (mError != null) {
            Toast.makeText
                (this, mError, Toast.LENGTH_LONG).show();
        }
    }

    private static int s_id = 1;

    private final static String TAG = "download-service";
    private final static long TIMEOUT_WAIT = 60*1000;
    public final static String TARGET = "download-target";
}