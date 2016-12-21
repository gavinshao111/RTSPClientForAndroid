package com.leapmotor.gavin.myrtspclient;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Created by gavin on 12/17/16.
 */
public class MyDBHelper extends SQLiteOpenHelper{

    private SQLiteDatabase sqLiteDatabase;

    public MyDBHelper(Context context, String name, int version) {
        super(context, name, null, version);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        this.sqLiteDatabase = db;
        sqLiteDatabase.execSQL("create table search_history(_id integer primary key autoincrement, url text)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }

    public void insert(ContentValues contentValues){
        sqLiteDatabase = getWritableDatabase();
        sqLiteDatabase.insert("search_history", null, contentValues);
        sqLiteDatabase.close();
    }

    public Cursor query(){
        sqLiteDatabase = getReadableDatabase();
        return sqLiteDatabase.query("search_history", null, null, null, null, null, null);
    }

    public void delete(int id){
        sqLiteDatabase = getWritableDatabase();
        sqLiteDatabase.delete("search_history", "_id=?", new String[]{String.valueOf(id)});
    }

    public void close(){
        if (sqLiteDatabase != null)
            sqLiteDatabase.close();
    }
}
