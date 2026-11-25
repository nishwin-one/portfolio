import csv
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from ledgercheck import ISSUE_COLUMNS, ORDER_COLUMNS, PAYMENT_COLUMNS, read_rows, reconcile, render_issues_csv

ROOT = Path(__file__).resolve().parents[1]
EXAMPLES = ROOT / "examples"


class LedgerCheckTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "input.csv"

    def load(self, text, columns=ORDER_COLUMNS):
        self.path.write_text(text, encoding="utf-8")
        return read_rows(self.path, columns)

    def cli(self, *args, orders=None):
        return subprocess.run([
            sys.executable, str(ROOT / "ledgercheck.py"), str(orders or EXAMPLES / "orders.csv"),
            str(EXAMPLES / "payments.csv"), *args
        ], capture_output=True, text=True)

    def test_demo_counts(self):
        result = reconcile(read_rows(EXAMPLES / "orders.csv", ORDER_COLUMNS),
                           read_rows(EXAMPLES / "payments.csv", PAYMENT_COLUMNS))
        self.assertEqual(result["summary"], {
            "orders": 7, "payment_rows": 10, "matched_orders": 1, "orders_needing_review": 6,
            "issue_counts": {"amount_mismatch": 1, "currency_mismatch": 1, "duplicate_payment_id": 2,
                             "missing_settlement": 1, "multiple_settlements": 1, "unknown_order": 1},
        })

    def test_minor_units_exact_in_any_column_order_and_bom(self):
        rows = self.load("\ufeffcurrency,order_id,amount\nINR,order-1,0.29\n")
        self.assertEqual(rows[0].minor_units, 29)

    def test_rejects_invalid_amounts(self):
        for value in ["0.001", "-1", "nan", "1e2", "0005", "10000000000"]:
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, r":2: amount"):
                self.load(f"order_id,amount,currency\na,{value},INR\n")

    def test_rejects_bad_id_currency_status_and_extra_field(self):
        for row, message in [
            ("=x,5,INR", "order_id"), ("a,5,JPY", "currency"), ("a,5,INR,more", "fields"),
        ]:
            with self.subTest(row=row), self.assertRaisesRegex(ValueError, message):
                self.load("order_id,amount,currency\n" + row)
        with self.assertRaisesRegex(ValueError, "status"):
            self.load("payment_id,order_id,amount,currency,status\na,b,5,INR,refunded\n", PAYMENT_COLUMNS)

    def test_malformed_csv_and_duplicate_headers(self):
        for text in ['order_id,amount,amount\n', 'order_id,amount,currency\n"unclosed,2,INR\n']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.load(text)

    def test_duplicate_orders_are_not_matched(self):
        orders = self.load("order_id,amount,currency\na,5,USD\na,5,USD\n")
        payments = self.load("payment_id,order_id,amount,currency,status\np,a,5,USD,settled\n", PAYMENT_COLUMNS)
        result = reconcile(orders, payments)
        self.assertEqual(result["summary"]["matched_orders"], 0)
        self.assertEqual(result["issues"], [{"code": "duplicate_order_id", "order_id": "a", "row_count": 2}])

    def test_report_order_does_not_depend_on_input(self):
        orders = read_rows(EXAMPLES / "orders.csv", ORDER_COLUMNS)
        payments = read_rows(EXAMPLES / "payments.csv", PAYMENT_COLUMNS)
        self.assertEqual(reconcile(orders, payments), reconcile(orders[::-1], payments[::-1]))

    def test_cli_modes_and_exit_codes(self):
        normal = self.cli()
        self.assertEqual(normal.returncode, 0)
        self.assertEqual(json.loads(normal.stdout)["summary"]["matched_orders"], 1)
        self.assertEqual(self.cli("--fail-on-issues").returncode, 1)
        self.path.write_text("garbage\n")
        invalid = self.cli(orders=self.path)
        self.assertEqual((invalid.returncode, invalid.stdout), (2, ""))
        self.assertIn("expected columns", invalid.stderr)

    def test_output_file_cannot_overwrite_existing_file(self):
        target = self.path.with_suffix(".json")
        self.assertEqual(self.cli("--output", str(target)).returncode, 0)
        before = target.read_bytes()
        self.assertEqual(self.cli("--output", str(target)).returncode, 2)
        self.assertEqual(target.read_bytes(), before)

    def test_empty_exports_are_clean(self):
        orders = self.load("order_id,amount,currency\n")
        result = reconcile(orders, [])
        self.assertEqual(result["issues"], [])
        self.assertEqual(result["summary"]["orders"], 0)

    def test_csv_cli_matches_fixture_and_keeps_issue_exit_code(self):
        result = self.cli("--format", "csv", "--fail-on-issues")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stderr, "")
        self.assertEqual(result.stdout, (EXAMPLES / "expected-issues.csv").read_text(encoding="utf-8"))
        rows = list(csv.DictReader(io.StringIO(result.stdout)))
        self.assertEqual(len(rows), 7)
        self.assertEqual(list(rows[0]), list(ISSUE_COLUMNS))
        self.assertEqual((rows[0]["code"], rows[0]["observed_statuses"]), ("missing_settlement", "pending"))
        self.assertEqual((rows[1]["expected_minor"], rows[1]["actual_minor"]), ("550", "549"))
        self.assertEqual((rows[1]["expected_currency"], rows[1]["actual_currency"]), ("USD", "USD"))
        self.assertEqual(rows[2]["payment_ids"], "pay_e|pay_f")
        self.assertEqual((rows[3]["expected_currency"], rows[3]["actual_currency"]), ("USD", "EUR"))
        self.assertEqual(rows[4]["payment_ids"], "pay_h")

    def test_reordered_exports_produce_same_csv(self):
        orders = read_rows(EXAMPLES / "orders.csv", ORDER_COLUMNS)
        payments = read_rows(EXAMPLES / "payments.csv", PAYMENT_COLUMNS)
        self.assertEqual(render_issues_csv(reconcile(orders, payments)["issues"]),
                         render_issues_csv(reconcile(orders[::-1], payments[::-1])["issues"]))

    def test_csv_serialization_escapes_special_characters_and_formula_prefixes(self):
        # Simulate future normalization rules yielding IDs that today's strict parser rejects.
        issues = [{"order_id": 'x,"y"\rz', "code": "unknown_order", "payment_ids": [' =SUM(1,2)', '@A1']},
                  {"order_id": '\t+1', "code": "duplicate_order_id", "row_count": 2}]
        rows = list(csv.DictReader(io.StringIO(render_issues_csv(issues), newline="")))
        self.assertEqual(rows[0]["order_id"], 'x,"y"\rz')
        self.assertEqual(rows[0]["payment_ids"], "' =SUM(1,2)|@A1")
        self.assertEqual(rows[1]["order_id"], "'\t+1")
        self.assertEqual(rows[1]["row_count"], "2")

    def test_csv_clean_report_contains_only_header(self):
        clean_orders = self.path
        clean_payments = self.path.with_name("payments.csv")
        clean_orders.write_text("order_id,amount,currency\n", encoding="utf-8")
        clean_payments.write_text("payment_id,order_id,amount,currency,status\n", encoding="utf-8")
        result = subprocess.run([
            sys.executable, str(ROOT / "ledgercheck.py"), str(clean_orders), str(clean_payments),
            "--format", "csv", "--fail-on-issues"
        ], capture_output=True, text=True)
        self.assertEqual((result.returncode, result.stderr), (0, ""))
        self.assertEqual(list(csv.reader(io.StringIO(result.stdout))), [list(ISSUE_COLUMNS)])

    def test_csv_output_writes_named_file_and_does_not_overwrite(self):
        target = self.path.with_suffix(".csv")
        result = self.cli("--format", "csv", "--output", str(target))
        self.assertEqual((result.returncode, result.stdout, result.stderr), (0, "", ""))
        before = target.read_bytes()
        self.assertNotIn(b"\r\n", before)
        self.assertEqual(before.decode("utf-8"),
                         (EXAMPLES / "expected-issues.csv").read_text(encoding="utf-8"))
        self.assertEqual(self.cli("--format", "csv", "--output", str(target)).returncode, 2)
        self.assertEqual(target.read_bytes(), before)

    def test_invalid_csv_input_does_not_create_an_output_report(self):
        self.path.write_text("order_id,amount,currency\n=@A1,5,INR\n", encoding="utf-8")
        report = self.path.with_name("report.csv")
        result = self.cli("--format", "csv", "--output", str(report), orders=self.path)
        self.assertEqual((result.returncode, result.stdout), (2, ""))
        self.assertIn("invalid order_id", result.stderr)
        self.assertFalse(report.exists())


if __name__ == "__main__":
    unittest.main()
