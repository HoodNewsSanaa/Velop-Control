package com.velopcontrol.app;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants the Android installer read access to exactly one verified APK. */
public final class UpdateProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private File file(Uri uri) throws FileNotFoundException {if(!"/verified.apk".equals(uri.getPath()))throw new FileNotFoundException();return new File(getContext().getFilesDir(),"updates/verified.apk");}
    public String getType(Uri uri){return "application/vnd.android.package-archive";}
    public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {if(!"r".equals(mode))throw new FileNotFoundException();return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++)values[i]=columns[i].equals(OpenableColumns.DISPLAY_NAME)?"Velop-Control-update.apk":columns[i].equals(OpenableColumns.SIZE)?file.length():null;cursor.addRow(values);return cursor;}catch(FileNotFoundException e){return null;}}
    public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
