package starking.eccles.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * Task history. Stores only the goal the user typed, the outcome, and a short result message:
 * never screen contents, screenshots, or anything the agent read from other apps.
 */
public class EcclesData extends SQLiteOpenHelper {

	private static final String DB= "eccles_agent.db";
	private static final int VERSION= 1;
	private static final int KEEP= 200;

	public static class Row {
		public long id,started,finished;
		public int steps;
		public String goal,status,message,provider;
	}

	public EcclesData(Context c){
		super(c,DB,null,VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase db){
		db.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, goal TEXT NOT NULL, "
			+ "status TEXT NOT NULL, message TEXT, started INTEGER NOT NULL, finished INTEGER, "
			+ "steps INTEGER DEFAULT 0, provider TEXT)");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db,int oldV,int newV){
	}

	public long begin(String goal,String provider){
		ContentValues v= new ContentValues();
		v.put("goal",goal);
		v.put("status","running");
		v.put("started",System.currentTimeMillis());
		v.put("provider",provider);
		long id= getWritableDatabase().insert("tasks",null,v);
		getWritableDatabase().execSQL("DELETE FROM tasks WHERE id NOT IN (SELECT id FROM tasks ORDER BY id DESC LIMIT " + KEEP + ")");
		return id;
	}

	public void finish(long id,String status,String message,int steps){
		if(id < 0) return;
		ContentValues v= new ContentValues();
		v.put("status",status);
		v.put("message",message);
		v.put("finished",System.currentTimeMillis());
		v.put("steps",steps);
		getWritableDatabase().update("tasks",v,"id=?",new String[]{String.valueOf(id)});
	}

	public List<Row> recent(int limit){
		List<Row> out= new ArrayList<>();
		try(Cursor c= getReadableDatabase().rawQuery(
			"SELECT id,goal,status,message,started,finished,steps,provider FROM tasks ORDER BY id DESC LIMIT ?",
			new String[]{String.valueOf(limit)})){
			while(c.moveToNext()){
				Row r= new Row();
				r.id= c.getLong(0);
				r.goal= c.getString(1);
				r.status= c.getString(2);
				r.message= c.getString(3);
				r.started= c.getLong(4);
				r.finished= c.isNull(5) ? 0 : c.getLong(5);
				r.steps= c.getInt(6);
				r.provider= c.getString(7);
				out.add(r);
			}
		}
		return out;
	}

	public void clear(){
		getWritableDatabase().delete("tasks",null,null);
	}
}
