package com.jdkshen.aijspro.ui.floating;

import android.content.Context;
import android.view.View;

public interface CircularMenuFloaty {

    View inflateActionView(Context context, CircularMenuWindow window);

    CircularActionMenu inflateMenuItems(Context context, CircularMenuWindow window);
}
