package com.kbs.trook;

import com.kbs.backport.AsyncTask;
import android.util.Log;

public class WebViewTask
    extends AsyncTask<String,String,String>
{
    WebViewTask(Trook tr)
    {  m_trook = tr; }

    @Override
    protected String doInBackground(String... uris)
    {
        Log.d(TAG, "backgrounded with "+Thread.currentThread());
        if (uris.length != 1) {
            error("Internal error "+uris.length);
            return null;
        }

        m_uri = uris[0];

        publishProgress("starting wifi...");
        Trook.WifiStatus status = m_trook.acquireAndWaitForWifi();
        if (!status.isReady()) {
            error("web page failed\n"+m_uri+"\n"+status.getMessage());
            return null;
        }

        return "ok";
    }

    @Override
    protected void onProgressUpdate(String... progress)
    {
        for (int i=0; i<progress.length; i++) {
            m_trook.statusUpdate(progress[i]);
        }
    }

    @Override
    protected void onPostExecute(String result)
    {
        if (result != null) {
            m_trook.statusUpdate("Fetching web page...");
            m_trook.showWebViewNow(m_uri);
        }
        else {
            if (m_error != null) {
                m_trook.displayError(m_error);
            }
        }
    }

    private void error(String msg)
    {
        Log.d(TAG, msg);
        m_error = msg;
    }

    private String m_uri;
    private String m_error = null;
    private final Trook m_trook;
    private final static String TAG = "async-web-view";
}
