"""Compare a merchant's orders with a normalized payment export."""
import argparse
import csv
import io
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}")
MONEY = re.compile(r"(?:0|[1-9][0-9]{0,9})(?:\.[0-9]{1,2})?")
CURRENCIES = frozenset(("INR", "USD", "EUR", "GBP"))
STATUSES = frozenset(("settled", "pending", "failed"))
ORDER_COLUMNS = ("order_id", "amount", "currency")
PAYMENT_COLUMNS = ("payment_id", "order_id", "amount", "currency", "status")
ISSUE_COLUMNS = (
    "order_id", "code", "payment_ids", "observed_statuses", "row_count",
    "expected_currency", "actual_currency", "expected_minor", "actual_minor",
)


@dataclass(frozen=True)
class Transaction:
    order_id: str
    minor_units: int
    currency: str
    payment_id: str = ""
    status: str = ""


def read_rows(path, columns):
    """Fail closed on malformed records; report which file and row needs repair."""
    path = Path(path)
    with path.open(encoding="utf-8-sig", newline="") as file:
        reader = csv.reader(file, strict=True)
        try:
            header = next(reader, None)
            if header is None or len(header) != len(columns) or set(header) != set(columns):
                raise ValueError(f"{path}: expected columns {', '.join(columns)}")
            records = []
            for values in reader:
                if not values:
                    continue
                location = f"{path}:{reader.line_num}"
                if len(values) != len(columns):
                    raise ValueError(f"{location}: expected {len(columns)} fields")
                row = dict(zip(header, (value.strip() for value in values)))
                for key in ("order_id", "payment_id"):
                    if key in row and not ID.fullmatch(row[key]):
                        raise ValueError(f"{location}: invalid {key}")
                if not MONEY.fullmatch(row["amount"]):
                    raise ValueError(f"{location}: amount must be non-negative with up to two decimals")
                if row["currency"] not in CURRENCIES:
                    raise ValueError(f"{location}: unsupported currency")
                if "status" in row and row["status"] not in STATUSES:
                    raise ValueError(f"{location}: status must be settled, pending, or failed")
                records.append(Transaction(
                    row["order_id"], int(Decimal(row["amount"]) * 100), row["currency"],
                    row.get("payment_id", ""), row.get("status", "")
                ))
            return records
        except (csv.Error, UnicodeError) as exc:
            raise ValueError(f"{path}: CSV parse error near line {reader.line_num}: {exc}") from exc


def group(rows, field):
    index = defaultdict(list)
    for row in rows:
        index[getattr(row, field)].append(row)
    return index


def reconcile(orders, payments):
    orders_by_id = group(orders, "order_id")
    payments_by_order = group(payments, "order_id")
    payment_counts = Counter(row.payment_id for row in payments)
    issues = []
    matched = 0

    def flag(code, order_id, **details):
        issues.append({"code": code, "order_id": order_id, **details})

    for order_id in sorted(orders_by_id.keys() | payments_by_order.keys()):
        expected = orders_by_id.get(order_id, [])
        observed = payments_by_order.get(order_id, [])
        settled = [row for row in observed if row.status == "settled"]
        duplicates = sorted({row.payment_id for row in observed if payment_counts[row.payment_id] > 1})
        if duplicates:
            flag("duplicate_payment_id", order_id, payment_ids=duplicates)
        if not expected:
            flag("unknown_order", order_id, payment_ids=sorted({row.payment_id for row in observed}))
            continue
        if len(expected) > 1:
            flag("duplicate_order_id", order_id, row_count=len(expected))
        if len(settled) > 1:
            flag("multiple_settlements", order_id, payment_ids=sorted({row.payment_id for row in settled}))
        if not settled:
            flag("missing_settlement", order_id, observed_statuses=sorted({row.status for row in observed}))
        if duplicates or len(expected) != 1 or len(settled) != 1:
            continue
        order, payment = expected[0], settled[0]
        if order.currency != payment.currency:
            flag("currency_mismatch", order_id, payment_id=payment.payment_id,
                 expected=order.currency, actual=payment.currency)
        elif order.minor_units != payment.minor_units:
            flag("amount_mismatch", order_id, payment_id=payment.payment_id,
                 currency=order.currency, expected_minor=order.minor_units, actual_minor=payment.minor_units)
        else:
            matched += 1

    return {
        "summary": {
            "orders": len(orders_by_id),
            "payment_rows": len(payments),
            "matched_orders": matched,
            "orders_needing_review": len(orders_by_id) - matched,
            "issue_counts": dict(sorted(Counter(item["code"] for item in issues).items())),
        },
        "issues": issues,
    }


def spreadsheet_cell(value):
    """Neutralize common formula prefixes before handing text to the CSV writer."""
    if isinstance(value, str) and (
        value.startswith(("\t", "\r", "\n"))
        or value.lstrip().startswith(("=", "+", "-", "@"))
    ):
        return "'" + value
    return value


def render_issues_csv(issues):
    """Flatten the issue list into one row per finding, without report totals."""
    buffer = io.StringIO(newline="")
    writer = csv.DictWriter(buffer, fieldnames=ISSUE_COLUMNS,
                            lineterminator="\n", quoting=csv.QUOTE_ALL)
    writer.writeheader()
    for issue in issues:
        payment_ids = issue.get("payment_ids", [])
        if "payment_id" in issue:
            payment_ids = [issue["payment_id"]]
        row = {
            "order_id": issue["order_id"],
            "code": issue["code"],
            "payment_ids": "|".join(payment_ids),
            "observed_statuses": "|".join(issue.get("observed_statuses", [])),
            "row_count": issue.get("row_count", ""),
            "expected_minor": issue.get("expected_minor", ""),
            "actual_minor": issue.get("actual_minor", ""),
        }
        if issue["code"] == "currency_mismatch":
            row["expected_currency"] = issue["expected"]
            row["actual_currency"] = issue["actual"]
        elif issue["code"] == "amount_mismatch":
            row["expected_currency"] = row["actual_currency"] = issue["currency"]
        writer.writerow({key: spreadsheet_cell(value) for key, value in row.items()})
    return buffer.getvalue()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("orders", type=Path, help="Expected orders CSV")
    parser.add_argument("payments", type=Path, help="Normalized payments CSV")
    parser.add_argument("--format", choices=("json", "csv"), default="json",
                        help="Report format (default: json; csv includes issues only)")
    parser.add_argument("--output", type=Path, help="Write the report to a new file instead of stdout")
    parser.add_argument("--fail-on-issues", action="store_true", help="Exit 1 when exceptions are found")
    args = parser.parse_args(argv)
    try:
        orders = read_rows(args.orders, ORDER_COLUMNS)
        payments = read_rows(args.payments, PAYMENT_COLUMNS)
        result = reconcile(orders, payments)
        rendered = (render_issues_csv(result["issues"]) if args.format == "csv"
                    else json.dumps(result, indent=2) + "\n")
        if args.output:
            with args.output.open("x", encoding="utf-8", newline="") as output:
                output.write(rendered)
        else:
            print(rendered, end="")
    except (OSError, ValueError, UnicodeError) as exc:
        print(f"ledgercheck: {exc}", file=sys.stderr)
        return 2
    return int(args.fail_on_issues and bool(result["issues"]))


if __name__ == "__main__":
    raise SystemExit(main())
