package com.velopcontrol.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class HistoryStoreTest {
    private HistoryStore store;
    @Before public void start(){RuntimeEnvironment.getApplication().deleteDatabase("mesh-history.db");store=new HistoryStore(RuntimeEnvironment.getApplication());}
    @After public void close(){store.close();}
    @Test public void failedTransactionDoesNotConsumeAssociationChanges(){
        Topology t=Topology.demo();t.demo=false;store.record(t);long initial=count();
        t.clients.get(0).nodeId="demo-4";t.clients.get(1).nodeId="demo-4";
        SQLiteDatabase db=store.getWritableDatabase();db.execSQL("CREATE TRIGGER reject_event BEFORE INSERT ON events WHEN NEW.label = 'Work laptop' BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        assertThrows(SQLiteException.class,()->store.record(t));assertEquals(initial,count());
        db.execSQL("DROP TRIGGER reject_event");store.record(t);assertEquals(initial+2,count());
    }
    @Test public void oldEventsAreHiddenEvenIfNoNewSnapshotArrives(){
        ContentValues row=new ContentValues();row.put("ts",System.currentTimeMillis()-8L*86400000);row.put("device","test");row.put("label","old-label");row.put("node","");row.put("state","Reported connected");store.getWritableDatabase().insertOrThrow("events",null,row);
        assertTrue(store.recent(null).isEmpty());assertTrue(store.recent("test").isEmpty());
    }
    private long count(){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events",null)){c.moveToFirst();return c.getLong(0);}}
}
