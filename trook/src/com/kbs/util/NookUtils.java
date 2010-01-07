package com.kbs.util;

import android.content.Context;
import android.content.Intent;

// A collection of random, nook-specific
// stuff

public class NookUtils
{
    public final static String UPDATE_TITLE =
        "com.bravo.intent.UPDATE_TITLE";
    public final static String UPDATE_STATUSBAR =
        "com.bravo.intent.UPDATE_STATUSBAR";

    public final static String STATUSBAR_ICON =
        "Statusbar.icon";
    public final static String STATUSBAR_ACTION =
        "Statusbar.action";

    public final static void setAppTitle(Context ctx, String title)
    {
        Intent msg = new Intent(UPDATE_TITLE);

        msg.putExtra("apptitle", title);
        ctx.sendBroadcast(msg);
    }

    public final static void showPageNumber
        (Context ctx, int curpage, int maxpage)
    {
        Intent msg = new Intent(UPDATE_STATUSBAR);
        msg.putExtra(STATUSBAR_ICON, 7);
        msg.putExtra(STATUSBAR_ACTION, 1);
        msg.putExtra("current", curpage);
        msg.putExtra("max", maxpage);
        ctx.sendBroadcast(msg);
    }
}
