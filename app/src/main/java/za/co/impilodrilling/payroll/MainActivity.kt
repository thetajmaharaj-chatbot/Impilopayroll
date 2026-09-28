package za.co.impilodrilling.payroll

import android.app.*
import android.content.*
import android.content.res.ColorStateList
import android.database.sqlite.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.*
import java.io.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Employee(val id: Long, val name: String)
data class DayEntry(val date: LocalDate, val rate: Double, val memo: String)

class PayrollDb(c: Context) : SQLiteOpenHelper(c, "impilo_payroll.db", null, 1) {
    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE employees(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL)")
        d.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT,employee_id INTEGER NOT NULL,work_date TEXT NOT NULL,rate REAL NOT NULL,memo TEXT NOT NULL,UNIQUE(employee_id,work_date))")
    }

    override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}

    fun employees(): List<Employee> {
        val a = mutableListOf<Employee>()
        readableDatabase.rawQuery("SELECT id,name FROM employees ORDER BY name", null).use { c ->
            while (c.moveToNext()) a.add(Employee(c.getLong(0), c.getString(1)))
        }
        return a
    }

    fun addEmployee(n: String) {
        writableDatabase.execSQL("INSERT INTO employees(name) VALUES(?)", arrayOf(n.trim()))
    }

    fun entry(e: Long, d: LocalDate): DayEntry? {
        readableDatabase.rawQuery(
            "SELECT rate,memo FROM entries WHERE employee_id=? AND work_date=?",
            arrayOf(e.toString(), d.toString())
        ).use { c ->
            return if (c.moveToFirst()) DayEntry(d, c.getDouble(0), c.getString(1)) else null
        }
    }

    fun save(e: Long, d: LocalDate, r: Double, m: String) {
        writableDatabase.execSQL(
            "INSERT INTO entries(employee_id,work_date,rate,memo) VALUES(?,?,?,?) ON CONFLICT(employee_id,work_date) DO UPDATE SET rate=excluded.rate,memo=excluded.memo",
            arrayOf(e, d.toString(), r, m.trim())
        )
    }

    fun month(e: Long, m: YearMonth): List<DayEntry> {
        val a = mutableListOf<DayEntry>()
        readableDatabase.rawQuery(
            "SELECT work_date,rate,memo FROM entries WHERE employee_id=? AND work_date>=? AND work_date<=? ORDER BY work_date",
            arrayOf(e.toString(), m.atDay(1).toString(), m.atEndOfMonth().toString())
        ).use { c ->
            while (c.moveToNext()) {
                a.add(DayEntry(LocalDate.parse(c.getString(0)), c.getDouble(1), c.getString(2)))
            }
        }
        return a
    }
}

class MainActivity : Activity() {
    private lateinit var db: PayrollDb
    private lateinit var root: LinearLayout
    private var emp: Employee? = null
    private var month = YearMonth.now()
    private var selectionMode = false
    private val selectedDays = linkedSetOf<LocalDate>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        db = PayrollDb(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
        employees()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, z: Float = 18f) = TextView(this).apply {
        text = s
        textSize = z
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun btn(s: String, f: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        minHeight = dp(44)
        setOnClickListener { f() }
    }

    private fun money(v: Double) = String.format(Locale("en", "ZA"), "R%,.2f", v)

    private fun employees() {
        emp = null
        selectionMode = false
        selectedDays.clear()
        root.removeAllViews()
        root.addView(tv("Impilo Payroll", 26f))
        root.addView(tv("Employees"))
        db.employees().forEach { e ->
            root.addView(btn(e.name) {
                emp = e
                month = YearMonth.now()
                calendar()
            })
        }
        root.addView(btn("+ Add employee") {
            val i = EditText(this)
            i.hint = "Employee name"
            AlertDialog.Builder(this)
                .setTitle("Add employee")
                .setView(i)
                .setPositiveButton("Add") { _, _ ->
                    if (i.text.toString().isNotBlank()) {
                        db.addEmployee(i.text.toString())
                        employees()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
    }

    private fun calendar() {
        val e = emp ?: return
        root.removeAllViews()

        root.addView(btn("‹ Employees") { employees() })
        root.addView(tv(e.name, 26f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nav.addView(btn("‹") {
            month = month.minusMonths(1)
            selectionMode = false
            selectedDays.clear()
            calendar()
        }, LinearLayout.LayoutParams(dp(54), dp(50)))

        nav.addView(TextView(this).apply {
            text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(50), 1f))

        nav.addView(btn("›") {
            month = month.plusMonths(1)
            selectionMode = false
            selectedDays.clear()
            calendar()
        }, LinearLayout.LayoutParams(dp(54), dp(50)))
        root.addView(nav)

        val entries = db.month(e.id, month)
        val map = entries.associateBy { it.date }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
            header.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, dp(30), 1f))
        }
        root.addView(header)

        val allCells = mutableListOf<LocalDate?>()
        repeat(month.atDay(1).dayOfWeek.value - 1) { allCells.add(null) }
        for (n in 1..month.lengthOfMonth()) allCells.add(month.atDay(n))
        while (allCells.size % 7 != 0) allCells.add(null)

        allCells.chunked(7).forEach { week ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            week.forEach { d ->
                if (d == null) {
                    row.addView(Space(this), LinearLayout.LayoutParams(0, dp(62), 1f))
                } else {
                    val x = map[d]
                    val amount = if (x == null) "—" else if (x.rate == 0.0) "R0" else money(x.rate).replace(".00", "")
                    val b = Button(this).apply {
                        text = d.dayOfMonth.toString() + "\n" + amount
                        textSize = 11f
                        isAllCaps = false
                        gravity = Gravity.CENTER
                        minWidth = 0
                        minimumWidth = 0
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(0, 0, 0, 0)
                        if (selectedDays.contains(d)) {
                            backgroundTintList = ColorStateList.valueOf(Color.rgb(46, 125, 50))
                            setTextColor(Color.WHITE)
                        } else if (x != null) {
                            backgroundTintList = ColorStateList.valueOf(Color.rgb(232, 240, 254))
                            setTextColor(Color.rgb(20, 20, 20))
                        }
                        setOnClickListener {
                            if (selectionMode) {
                                if (x == null) {
                                    Toast.makeText(this@MainActivity, "Enter a rate and memo on this day before copying it.", Toast.LENGTH_SHORT).show()
                                } else {
                                    if (selectedDays.contains(d)) selectedDays.remove(d) else selectedDays.add(d)
                                    calendar()
                                }
                            } else {
                                edit(e, d)
                            }
                        }
                    }
                    val lp = LinearLayout.LayoutParams(0, dp(62), 1f).apply {
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    }
                    row.addView(b, lp)
                }
            }
            root.addView(row)
        }

        if (!selectionMode) {
            root.addView(btn("Select days to copy to team") {
                selectionMode = true
                selectedDays.clear()
                calendar()
            })
        } else {
            root.addView(tv("Selected days: " + selectedDays.size, 16f))
            root.addView(btn("Select all entered days this month") {
                selectedDays.clear()
                selectedDays.addAll(entries.map { it.date })
                calendar()
            })
            root.addView(btn("Paste selected days to employees") {
                copySelectedDaysToEmployees(e)
            })
            root.addView(btn("Cancel selection") {
                selectionMode = false
                selectedDays.clear()
                calendar()
            })
        }

        val total = entries.sumOf { it.rate }
        val paid = entries.count { it.rate > 0 }
        val zero = entries.count { it.rate == 0.0 }
        root.addView(tv(
            "Assessed: " + entries.size +
                "   Paid days: " + paid +
                "   R0 days: " + zero +
                "\nMONTH TOTAL: " + money(total),
            19f
        ))

        root.addView(btn("Generate Impilo Payroll PDF") {
            pdf(e, month, entries.sortedBy { it.date })
        })
    }

    private fun copySelectedDaysToEmployees(source: Employee) {
        if (selectedDays.isEmpty()) {
            Toast.makeText(this, "Select at least one recorded day.", Toast.LENGTH_SHORT).show()
            return
        }

        val targets = db.employees().filter { it.id != source.id }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Add another employee first.", Toast.LENGTH_SHORT).show()
            return
        }

        val names = targets.map { it.name }.toTypedArray()
        val checked = BooleanArray(targets.size)

        AlertDialog.Builder(this)
            .setTitle("Paste " + selectedDays.size + " day(s) from " + source.name)
            .setMessage("Choose the employees working the same job. Existing entries on those dates will be replaced. Each copied entry can still be edited separately afterward.")
            .setMultiChoiceItems(names, checked) { _, which, value ->
                checked[which] = value
            }
            .setPositiveButton("Paste") { _, _ ->
                val chosen = targets.filterIndexed { index, _ -> checked[index] }
                if (chosen.isEmpty()) {
                    Toast.makeText(this, "No employees selected.", Toast.LENGTH_SHORT).show()
                } else {
                    var copied = 0
                    selectedDays.sorted().forEach { d ->
                        val sourceEntry = db.entry(source.id, d)
                        if (sourceEntry != null) {
                            chosen.forEach { target ->
                                db.save(target.id, d, sourceEntry.rate, sourceEntry.memo)
                                copied++
                            }
                        }
                    }
                    Toast.makeText(
                        this,
                        "Copied " + selectedDays.size + " day(s) to " + chosen.size + " employee(s).",
                        Toast.LENGTH_LONG
                    ).show()
                    selectionMode = false
                    selectedDays.clear()
                    calendar()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun edit(e: Employee, d: LocalDate) {
        val old = db.entry(e.id, d)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val rate = EditText(this).apply {
            hint = "Rate for day (R)"
            inputType = 8194
            setText(old?.rate?.toString() ?: "")
        }
        val memo = EditText(this).apply {
            hint = "Inspection / work memo"
            minLines = 3
            gravity = Gravity.TOP
            setText(old?.memo ?: "")
        }
        box.addView(rate)
        box.addView(memo)

        AlertDialog.Builder(this)
            .setTitle(e.name + " • " + d.format(DateTimeFormatter.ofPattern("dd MMM yyyy")))
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val r = rate.text.toString().toDoubleOrNull()
                if (r != null && r >= 0) {
                    db.save(e.id, d, r, memo.text.toString())
                    calendar()
                } else {
                    Toast.makeText(this, "Enter a valid rate. Use R0 when no pay is due.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pdf(e: Employee, m: YearMonth, list: List<DayEntry>) {
        if (list.isEmpty()) {
            Toast.makeText(this, "No entries to export.", Toast.LENGTH_SHORT).show()
            return
        }

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        fun draw(s: String, x: Float, y: Float, z: Float, bold: Boolean = false) {
            p.textSize = z
            p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            p.color = Color.BLACK
            c.drawText(s, x, y, p)
        }

        draw("IMPILO DRILLING", 40f, 50f, 23f, true)
        draw("Employee Monthly Payroll Statement", 40f, 74f, 13f, true)
        draw("Employee: " + e.name, 40f, 105f, 10f)
        draw("Payroll month: " + m.format(DateTimeFormatter.ofPattern("MMMM yyyy")), 40f, 123f, 10f)
        draw("Pay date: 1 " + m.plusMonths(1).format(DateTimeFormatter.ofPattern("MMMM yyyy")), 40f, 141f, 10f)

        c.drawLine(40f, 155f, 555f, 155f, p)
        draw("Date", 40f, 173f, 9f, true)
        draw("Rate", 120f, 173f, 9f, true)
        draw("Inspection / Work Memo", 205f, 173f, 9f, true)

        var y = 191f
        list.take(31).forEach { x ->
            draw(x.date.format(DateTimeFormatter.ofPattern("dd MMM")), 40f, y, 8f)
            draw(money(x.rate), 120f, y, 8f)
            draw(x.memo.replace("\n", " ").take(62), 205f, y, 8f)
            y += 17f
        }

        c.drawLine(40f, 735f, 555f, 735f, p)
        draw("Days assessed: " + list.size, 40f, 757f, 9f)
        draw("Paid days: " + list.count { it.rate > 0 }, 155f, 757f, 9f)
        draw("R0 days: " + list.count { it.rate == 0.0 }, 255f, 757f, 9f)
        draw("TOTAL SALARY: " + money(list.sumOf { it.rate }), 330f, 783f, 13f, true)
        draw("Impilo Drilling • 083 419 2100 • info@impilodrilling.co.za • impilodrilling.co.za", 40f, 818f, 7.5f)

        doc.finishPage(page)

        val fileName = e.name.replace(" ", "_") + "_" + m + "_Payroll.pdf"
        try {
            val savedTo = savePdfToDocuments(fileName, doc)
            Toast.makeText(this, "Payroll PDF saved to " + savedTo, Toast.LENGTH_LONG).show()
        } catch (ex: Exception) {
            Toast.makeText(this, "Could not save PDF: " + (ex.message ?: "unknown error"), Toast.LENGTH_LONG).show()
        } finally {
            doc.close()
        }
    }

    private fun savePdfToDocuments(fileName: String, doc: PdfDocument): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Impilo Payroll")
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: throw IOException("Unable to create PDF in Documents")
            contentResolver.openOutputStream(uri)?.use { out ->
                doc.writeTo(out)
            } ?: throw IOException("Unable to open PDF file")
            return "Documents/Impilo Payroll/" + fileName
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "Impilo Payroll"
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Unable to create Documents folder")
        val file = File(dir, fileName)
        FileOutputStream(file).use { doc.writeTo(it) }
        return file.absolutePath
    }
}
