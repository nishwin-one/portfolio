# LedgerCheck

LedgerCheck compares an order export with a payment export. It detects missing and duplicate payments, plus mismatched amounts or currencies, without moving money or calling a payment provider. It is an **AI-assisted portfolio prototype**, not a Stripe integration or a production accounting tool. All example data is synthetic.

## Run it

Use Python 3.10 or newer. No dependencies, API keys, or network access required. On Windows use `py` if `python` is not on PATH.

```sh
python ledgercheck.py examples/orders.csv examples/payments.csv
python ledgercheck.py examples/orders.csv examples/payments.csv --format csv --output report.csv
python -m unittest discover -s tests -v
```

The demo has **7 orders, 10 payment rows, and 1 matched order**. The remaining six orders need review and one payment refers to an unknown order. A failed attempt on an otherwise settled order does not block a match. See the [sample report](examples/expected-issues.csv).

JSON is the default and includes both totals and findings. CSV gives one row per finding for filtering or spreadsheet review, with separate currency and minor-unit amount columns. Multiple payment IDs or statuses in a cell are separated by `|`, which cannot appear in the accepted input values. A clean CSV report contains only its header.

CSV output quotes fields and escapes common leading formula characters as defense in depth; input validation already excludes those characters from IDs. When opening CSV with a spreadsheet import wizard, choose **Text** for ID columns so numeric-looking IDs keep their leading zeros.

By default the report goes to stdout; `--output` writes to a new file and never overwrites an existing file. The command exits 0 after producing a report. Add `--fail-on-issues` to exit 1 when findings exist, for example in a scheduled check. Invalid data or a file error exits 2.

## Data contract

- Orders: `order_id,amount,currency`
- Payments: `payment_id,order_id,amount,currency,status`
- Amounts are non-negative decimal strings with at most two decimal places. Supported currencies: INR, USD, EUR, and GBP. Amounts are compared as integer minor units, not floating-point numbers.
- Status is exactly `settled`, `pending`, or `failed`. Only settled rows are candidates for matching. IDs are case-sensitive: they start with an ASCII letter or digit, contain at most 80 characters, and otherwise allow ASCII letters, digits, `_`, `.`, `:`, and `-`. Every input row is checked, and invalid input stops the entire run.

## Matching rules

1. Group rows by order ID and count payment IDs across the whole export.
2. Flag duplicate order/payment IDs, missing or multiple settlements, and payments that point to nonexistent orders. Never count an ambiguous match as successful.
3. For an unambiguous pair, compare currency first and amount second. Sort the report by order ID so reordered exports produce the same result.

The matching pass indexes both exports in memory rather than repeatedly scanning the payment export. Sorting distinct order IDs makes reports reproducible irrespective of the input order. Integer minor units avoid floating-point rounding, and ambiguous duplicates are surfaced instead of being silently resolved. This version assumes one full settlement per order; refunds, split payments, fees, foreign exchange, provider-specific imports, and bank payouts are out of scope.

## Extend it

A useful next pull request: add a very small adapter that maps a second synthetic payment CSV layout into the normalized schema without changing the matcher. Use fixtures for accepted and unknown statuses, and show that duplicate payment IDs are still caught after normalization. Ask a classmate to review the mapping and document what you revise based on their feedback.
