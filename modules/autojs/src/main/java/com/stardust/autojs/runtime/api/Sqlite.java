package com.stardust.autojs.runtime.api;

import android.content.Context;

import com.stardust.autojs.core.database.Database;

/**
 * Auto.js-compatible {@code sqlite} module: open(name[, version]) and CRUD/tx on
 * the returned Database. Aligns with the Auto.js Pro surface (sqlite is available
 * there) while staying on the existing core/database implementation.
 */
public class Sqlite {

    private final Context mContext;

    public Sqlite(Context context) {
        mContext = context.getApplicationContext();
    }

    public Database open(String name) {
        return open(name, 0);
    }

    public Database open(String name, int version) {
        return new Database(mContext, name, version);
    }
}
