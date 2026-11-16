package com.clipboardbridge;

import android.content.Intent;
import android.service.quicksettings.TileService;

/** Quick Settings tile (Android 7+): one tap sends the current clipboard to the PC. */
public class ClipTileService extends TileService {
    @Override
    public void onClick() {
        try {
            Api.tileLaunch(this, new Intent(this, CaptureActivity.class));
        } catch (Exception ignored) {
        }
    }
}
