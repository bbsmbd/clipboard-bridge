package com.clipboardbridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** "Send to PC" in the text-selection menu (PROCESS_TEXT) and in the Share menu. */
public class SendActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        CharSequence t = null;
        if (i != null) {
            t = i.getCharSequenceExtra("android.intent.extra.PROCESS_TEXT");
            if (t == null) t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        }
        Prefs p = new Prefs(this);
        android.net.Uri stream = null;
        if (i != null && i.getType() != null && i.getType().startsWith("image/")) {
            stream = (android.net.Uri) i.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (p.paired() && stream != null) {
            ClipContent cc = new ClipContent();
            cc.imageUri = stream;
            cc.mime = i.getType().equals("image/*") ? "image/jpeg" : i.getType();
            cc.send(this, true);
            Toast.makeText(this, BridgeService.state == BridgeService.ST_CONNECTED
                    ? R.string.toast_img_sent : R.string.toast_pending, Toast.LENGTH_SHORT).show();
        } else if (!p.paired()) {
            Toast.makeText(this, R.string.toast_not_paired, Toast.LENGTH_SHORT).show();
        } else if (t == null || t.toString().trim().length() == 0) {
            Toast.makeText(this, R.string.toast_empty, Toast.LENGTH_SHORT).show();
        } else {
            BridgeService.sendText(this, t.toString(), true);
            Toast.makeText(this, BridgeService.state == BridgeService.ST_CONNECTED
                    ? R.string.toast_sent : R.string.toast_pending, Toast.LENGTH_SHORT).show();
        }
        setResult(RESULT_CANCELED);
        finish();
    }
}
