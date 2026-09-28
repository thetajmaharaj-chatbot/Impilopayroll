package za.co.impilodrilling.payroll
import android.app.*
import android.content.*
import android.database.sqlite.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.*
import android.view.*
import android.widget.*
import java.io.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Employee(val id:Long,val name:String)
data class DayEntry(val date:LocalDate,val rate:Double,val memo:String)

class PayrollDb(c:Context):SQLiteOpenHelper(c,"impilo_payroll.db",null,1){
 override fun onCreate(d:SQLiteDatabase){d.execSQL("CREATE TABLE employees(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL)");d.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT,employee_id INTEGER NOT NULL,work_date TEXT NOT NULL,rate REAL NOT NULL,memo TEXT NOT NULL,UNIQUE(employee_id,work_date))")}
 override fun onUpgrade(d:SQLiteDatabase,o:Int,n:Int){}
 fun employees():List<Employee>{val a=mutableListOf<Employee>();readableDatabase.rawQuery("SELECT id,name FROM employees ORDER BY name",null).use{c->while(c.moveToNext())a.add(Employee(c.getLong(0),c.getString(1)))};return a}
 fun addEmployee(n:String){writableDatabase.execSQL("INSERT INTO employees(name) VALUES(?)",arrayOf(n.trim()))}
 fun entry(e:Long,d:LocalDate):DayEntry?{readableDatabase.rawQuery("SELECT rate,memo FROM entries WHERE employee_id=? AND work_date=?",arrayOf(e.toString(),d.toString())).use{c->return if(c.moveToFirst())DayEntry(d,c.getDouble(0),c.getString(1))else null}}
 fun save(e:Long,d:LocalDate,r:Double,m:String){writableDatabase.execSQL("INSERT INTO entries(employee_id,work_date,rate,memo) VALUES(?,?,?,?) ON CONFLICT(employee_id,work_date) DO UPDATE SET rate=excluded.rate,memo=excluded.memo",arrayOf(e,d.toString(),r,m.trim()))}
 fun month(e:Long,m:YearMonth):List<DayEntry>{val a=mutableListOf<DayEntry>();readableDatabase.rawQuery("SELECT work_date,rate,memo FROM entries WHERE employee_id=? AND work_date>=? AND work_date<=? ORDER BY work_date",arrayOf(e.toString(),m.atDay(1).toString(),m.atEndOfMonth().toString())).use{c->while(c.moveToNext())a.add(DayEntry(LocalDate.parse(c.getString(0)),c.getDouble(1),c.getString(2)))};return a}
}

class MainActivity:Activity(){
 lateinit var db:PayrollDb;lateinit var root:LinearLayout;var emp:Employee?=null;var month=YearMonth.now()
 override fun onCreate(b:Bundle?){super.onCreate(b);db=PayrollDb(this);root=LinearLayout(this);root.orientation=LinearLayout.VERTICAL;root.setPadding(24,24,24,24);setContentView(ScrollView(this).apply{addView(root)});employees()}
 fun tv(s:String,z:Float=18f)=TextView(this).apply{text=s;textSize=z;setPadding(4,10,4,10)}
 fun btn(s:String,f:()->Unit)=Button(this).apply{text=s;setOnClickListener{f()}}
 fun money(v:Double)=String.format(Locale("en","ZA"),"R%,.2f",v)
 fun employees(){emp=null;root.removeAllViews();root.addView(tv("Impilo Payroll",26f));root.addView(tv("Employees"));db.employees().forEach{e->root.addView(btn(e.name){emp=e;month=YearMonth.now();calendar()})};root.addView(btn("+ Add employee"){val i=EditText(this);i.hint="Employee name";AlertDialog.Builder(this).setTitle("Add employee").setView(i).setPositiveButton("Add"){_,_->if(i.text.toString().isNotBlank()){db.addEmployee(i.text.toString());employees()}}.setNegativeButton("Cancel",null).show()})}
 fun calendar(){val e=emp?:return;root.removeAllViews();root.addView(btn("‹ Employees"){employees()});root.addView(tv(e.name,26f));val nav=LinearLayout(this);nav.addView(btn("‹"){month=month.minusMonths(1);calendar()});nav.addView(tv(month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))));nav.addView(btn("›"){month=month.plusMonths(1);calendar()});root.addView(nav);val map=db.month(e.id,month).associateBy{it.date};val g=GridLayout(this);g.columnCount=7;listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun").forEach{g.addView(tv(it,12f))};repeat(month.atDay(1).dayOfWeek.value-1){g.addView(tv(""))};for(n in 1..month.lengthOfMonth()){val d=month.atDay(n);val x=map[d];val label=if(x==null)n.toString()+"\n—" else n.toString()+"\n"+money(x.rate).replace(".00","");g.addView(btn(label){edit(e,d)})};root.addView(g);val total=map.values.sumOf{it.rate};val paid=map.values.count{it.rate>0};val zero=map.values.count{it.rate==0.0};root.addView(tv("Assessed: "+map.size+"   Paid days: "+paid+"   R0 days: "+zero+"\nMONTH TOTAL: "+money(total),20f));root.addView(btn("Generate Impilo Payroll PDF"){pdf(e,month,map.values.sortedBy{it.date})})}
 fun edit(e:Employee,d:LocalDate){val old=db.entry(e.id,d);val box=LinearLayout(this);box.orientation=LinearLayout.VERTICAL;box.setPadding(30,0,30,0);val rate=EditText(this);rate.hint="Rate for day (R)";rate.inputType=8194;rate.setText(old?.rate?.toString()?:"");val memo=EditText(this);memo.hint="Inspection / work memo";memo.minLines=3;memo.setText(old?.memo?:"");box.addView(rate);box.addView(memo);AlertDialog.Builder(this).setTitle(e.name+" • "+d.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))).setView(box).setPositiveButton("Save"){_,_->val r=rate.text.toString().toDoubleOrNull();if(r!=null&&r>=0){db.save(e.id,d,r,memo.text.toString());calendar()}else Toast.makeText(this,"Enter a valid rate. Use R0 when no pay is due.",Toast.LENGTH_LONG).show()}.setNegativeButton("Cancel",null).show()}
 fun pdf(e:Employee,m:YearMonth,list:List<DayEntry>){if(list.isEmpty()){Toast.makeText(this,"No entries to export.",Toast.LENGTH_SHORT).show();return};val doc=PdfDocument();val page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,1).create());val c=page.canvas;val p=Paint(1);fun draw(s:String,x:Float,y:Float,z:Float,b:Boolean=false){p.textSize=z;p.typeface=if(b)Typeface.DEFAULT_BOLD else Typeface.DEFAULT;p.color=Color.BLACK;c.drawText(s,x,y,p)};draw("IMPILO DRILLING",40f,55f,24f,true);draw("Employee Monthly Payroll Statement",40f,80f,14f,true);draw("Employee: "+e.name,40f,115f,11f);draw("Payroll month: "+m.format(DateTimeFormatter.ofPattern("MMMM yyyy")),40f,135f,11f);draw("Pay date: 1 "+m.plusMonths(1).format(DateTimeFormatter.ofPattern("MMMM yyyy")),40f,155f,11f);c.drawLine(40f,170f,555f,170f,p);draw("Date",40f,192f,10f,true);draw("Rate",125f,192f,10f,true);draw("Inspection / Work Memo",215f,192f,10f,true);var y=215f;list.forEach{x->if(y<755f){draw(x.date.format(DateTimeFormatter.ofPattern("dd MMM")),40f,y,9f);draw(money(x.rate),125f,y,9f);draw(x.memo.take(55),215f,y,9f);y+=22f}};c.drawLine(40f,770f,555f,770f,p);draw("TOTAL SALARY: "+money(list.sumOf{it.rate}),340f,795f,14f,true);draw("Impilo Drilling • 083 419 2100 • info@impilodrilling.co.za • impilodrilling.co.za",40f,820f,8f);doc.finishPage(page);val dir=File(getExternalFilesDir(null),"Payroll");dir.mkdirs();val f=File(dir,e.name.replace(" ","_")+"_"+m+"_Payroll.pdf");FileOutputStream(f).use{doc.writeTo(it)};doc.close();Toast.makeText(this,"PDF saved: "+f.absolutePath,Toast.LENGTH_LONG).show()}
}
