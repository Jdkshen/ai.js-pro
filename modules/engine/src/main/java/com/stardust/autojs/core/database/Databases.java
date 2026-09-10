package com.stardust.autojs.core.database;

public class Databases {

    /**
     * Legacy no-op helper kept for binary compatibility; use
     * {@code new Database(context, name[, version])} instead.
     */
    public static Database openDatabase(String name, int version, String desc, long size){
        return null;
    }



}
