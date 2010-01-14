package com.kbs.trook;

import android.app.Dialog;
import android.view.View;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.TextView;
import android.content.Context;
import android.view.inputmethod.InputMethodManager;

public class OneLineDialog
    extends Dialog
{
    public OneLineDialog(Context c, View keyboardhanger)
    {
        super(c);
        m_context = c;
        m_kbh = keyboardhanger;
        setContentView(R.layout.onelinedialog);
        setTitle("");
        setCancelable(true);
        setCanceledOnTouchOutside(true);
        m_title = (TextView)
            findViewById(R.id.dialog_title);
        m_title.setText("");
        m_et = (EditText)
            findViewById(R.id.dialog_et);
        m_kl = new KL();
        m_et.setOnKeyListener(m_kl);
    }

    public void showDialog
        (String header, String title, String dflt, SubmitListener sl)
    {
        setTitle(header);
        m_title.setText(title);
        if (dflt != null) {
            m_et.setText("");
            m_et.append(dflt);
        }
        m_et.requestFocus();
        m_kl.setSubmitListener(sl);
        show();
        showSoftKeyboard();
    }

    public void closeDialog()
    {
        if (isShowing()) {
            cancel();
            hideSoftKeyboard();
        }
    }

    private final void showSoftKeyboard()
    {
        InputMethodManager imm =
            (InputMethodManager)
            m_context.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(m_kbh,InputMethodManager.SHOW_FORCED);
    }

    private final void hideSoftKeyboard()
    {
        InputMethodManager imm =
            (InputMethodManager)
            m_context.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(m_kbh.getWindowToken(), 0);
    }

    public interface SubmitListener
    { public void onSubmit(String text); }

    private final class KL
        implements View.OnKeyListener
    {
        private void setSubmitListener(SubmitListener sl)
        { m_sl = sl; }

        public boolean onKey(View v, int kc, KeyEvent ke)
        {
            if (ke.getAction() == KeyEvent.ACTION_UP) {
                if (v instanceof EditText) {
                    EditText et = (EditText) v;
                    if (kc == SOFT_KEYBOARD_CLEAR ) {
                        et.setText("");
                    }
                    else if (kc == SOFT_KEYBOARD_SUBMIT) {
                        String text = et.getText().toString();
                        if (m_sl != null) {
                            m_sl.onSubmit(text);
                        }
                        m_sl = null;
                        OneLineDialog.this.dismiss();
                    }
                    else if (kc ==  SOFT_KEYBOARD_CANCEL) {
                        OneLineDialog.this.dismiss();
                    }
                }
            }
            return false;
        }
        private SubmitListener m_sl = null;
    }

    private final EditText m_et;
    private final TextView m_title;
    private final Context m_context;
    private final KL m_kl;
    private final View m_kbh;

    private static final int SOFT_KEYBOARD_CLEAR=-13;
    private static final int SOFT_KEYBOARD_SUBMIT=-8;
    private static final int SOFT_KEYBOARD_CANCEL=-3;
}