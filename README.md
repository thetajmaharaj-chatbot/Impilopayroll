# Impilo Payroll
Android payroll calendar for Impilo Drilling.

## Core workflow
- Add an employee.
- Open the employee's monthly calendar.
- Tap a day and manually enter the day's rate and inspection/work memo.
- Blank = not yet assessed. R0 = deliberately assessed at zero.
- Monthly salary = sum of all manually entered daily rates.
- Generate an A4 employee payroll PDF for payday on the 1st of the following month.

GitHub Actions builds a debug APK on every push to main.
