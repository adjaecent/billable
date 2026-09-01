# billable

Babashka-based invoice generator. Stores data in EDN, generates HTML + PDF via headless Chrome.

## Setup

Requires [Babashka](https://github.com/babashka/babashka) and Google Chrome (or Chromium).

Create a sample settings file and edit it with your details:

```bash
bb init
```

This creates `data/settings.edn`. On macOS the Chrome path is pre-filled; on Linux, update `:chrome-bin` to your Chrome/Chromium path (e.g. `/usr/bin/google-chrome`):

```edn
{:name "Your Name"
 :address "Line 1\nLine 2\nCity, Country"
 :tax-ids [{:label "GSTN" :value "your-gstn"}]
 :phone "+91-00000-00000"
 :email "you@example.com"
 :notes "Payment via wire transfer to HDFC account"
 :chrome-bin "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
 :currencies {...}}
```

`:currencies` is pre-filled with the built-in formats -- see
[Currency formatting](#currency-formatting).

## Commands

### Initialize settings

```bash
bb init
```

Creates a sample `data/settings.edn`. Won't overwrite if one already exists.

### Add a client

```bash
bb client-add --name "Acme Corp" \
              --currency "USD" \
              --address $'123 Business Ave\nNew York, NY 10001\nUnited States' \
              --registration "Company No.=2024/123456/07" \
              --title "Tax Invoice" \
              --from-tax-id "VAT=DE123456789"
```

| Flag             | Required | Description                          |
|------------------|----------|--------------------------------------|
| `--name`         | yes      | Client name                          |
| `--currency`     | yes      | Currency code (USD, EUR, GBP, SGD, INR, ZAR) |
| `--address`      | yes      | Client address (use `$'\n'` for newlines) |
| `--registration` | no       | The client's own identifier, `LABEL=VALUE`; a bare value is labelled `Registration` |
| `--title`        | no       | Invoice heading, uppercased on the invoice (default `Export Invoice`) |
| `--from-tax-id`  | no       | One of *your* tax ids, `LABEL=VALUE`, shown only on this client's invoices; repeatable |

The heading is per-client, so different clients can get `Export Invoice`, `Tax Invoice`, or anything else. To change it for an existing client, edit `:title` in `data/clients.edn`.

### Tax ids

Your tax identifiers print in the From block of the invoice. They are plain
label/value pairs, so any regime works -- GSTN, LUT, VAT, ABN, UID, or none at
all. Put the ones that apply to every invoice in `settings.edn`, and the ones
that apply to a single client (an export LUT, say) on the client under
`:from-tax-ids` -- named for the From block, since these are your ids, not the
client's:

```edn
;; settings.edn -- on every invoice
:tax-ids [{:label "GSTN" :value "29ABCDE1234F1Z5"}]

;; clients.edn -- only this client's invoices
:from-tax-ids [{:label "LUT" :value "XX000000000000X"}]
```

The client's own identifier is separate: `:registration`, printed in the To
block. It is a single label/value map rather than a list, since a client is
identified by one number on an invoice:

```edn
:registration {:label "Company No." :value "HRB 12345"}
```

Omit `:label` and it reads `Registration`. A plain string, as older client
records have, means the same thing.

Either side can be omitted, so you can keep everything on the clients and
nothing in settings. They render settings first, then client, one line each,
in the order written. A label appears at most once: if both sides define
`GSTN`, the client's value wins.

### Currency formatting

Amounts use the symbol and digit grouping the currency is normally written
with, since grouping follows locale rather than the currency itself:

| Currency | Example          |
|----------|------------------|
| USD      | `$1,234.56`      |
| EUR      | `€1,234.56`      |
| GBP      | `£1,234.56`      |
| SGD      | `S$1,234.56`     |
| AED      | `AED 1,234.56`   |
| JPY      | `¥1,235`         |
| INR      | `₹5,55,555.00`   |
| ZAR      | `R1,234.56`      |

`bb init` writes these into `settings.edn` under `:currencies`, and that file
is what the invoice actually renders from -- the table above is only the seed
`init` copies in. Nothing about the list is fixed: add any currency you bill
in, and change how an existing one is written, by editing that map. Each entry
takes these keys:

| Key            | Meaning                                                   |
|----------------|-----------------------------------------------------------|
| `:symbol`      | Prefix for the amount (include a trailing space if wanted) |
| `:group-sep`   | Thousands separator                                        |
| `:decimal-sep` | Decimal separator                                          |
| `:grouping`    | `:western` (threes) or `:indian` (last three, then twos)   |
| `:symbol-after`| `true` puts the symbol after the amount                     |
| `:decimals`    | Digits after the decimal point (JPY uses `0`)               |

Only the keys you are changing are needed; the rest default to `:western`
grouping with `,` and `.` and two decimals. A currency with no entry at all
still works -- it renders as its code followed by the amount, e.g.
`CHF 1,234.56` -- so an entry is only needed when you want a symbol or a
different convention.

```edn
:currencies {"CHF" {:symbol "CHF "}                        ; CHF 1,234.56
             "ZAR" {:symbol "ZAR "}                        ; ZAR 1,234.56
             "SEK" {:symbol "kr" :group-sep " "
                    :decimal-sep "," :symbol-after true}   ; 1 234,56 kr
             "EUR" {:symbol "€" :group-sep "."
                    :decimal-sep "," :symbol-after true}}  ; 1.234,56 €
```

### Create an invoice

Invoices are created with an empty items list. Add items via `inv-update`.

```bash
bb inv-create --client 1 --description "April 2026"
bb inv-create --client 1 --description "Consulting" --issue-date "2026-03-01" --net 45
```

| Flag            | Required | Default | Description                    |
|-----------------|----------|---------|--------------------------------|
| `--client`      | yes      |         | Client ID                      |
| `--id`          | no       | auto    | Override invoice ID            |
| `--description` | no       |         | Invoice subject line           |
| `--issue-date`  | no       | today   | Issue date (YYYY-MM-DD)        |
| `--due-date`    | no       |         | Override due date directly     |
| `--net`         | no       | 30      | Payment terms in days          |
| `--notes`       | no       | from settings.edn | Notes shown at bottom of invoice |

### Update an invoice

Items can only be added one at a time.

```bash
# Add a line item
bb inv-update --id 1 --item-desc "Software Development" --item-amount 10000

# Add another line item
bb inv-update --id 1 --item-desc "Reimbursements" --item-amount 5000

# Change subject
bb inv-update --id 1 --description "April 2026"

# Change dates
bb inv-update --id 1 --issue-date "2026-04-01"
bb inv-update --id 1 --net 45
bb inv-update --id 1 --due-date "2026-06-01"

# Mark ready (ready for sending)
bb inv-update --id 1 --status "ready"

# Mark fully paid (locks the invoice from further updates)
bb inv-update --id 1 --status "paid"

# Record partial payment (must use --status paid simultaneously)
bb inv-update --id 1 --status "paid" --payment-amount 5000

# Record full payment (omit --payment-amount for full amount)
bb inv-update --id 1 --status "paid"
```

| Flag             | Required | Description                                      |
|------------------|----------|--------------------------------------------------|
| `--id`           | yes      | Invoice ID                                       |
| `--item-desc`    | no       | Line item description (use with `--item-amount`)  |
| `--item-amount`  | no       | Line item amount (use with `--item-desc`)         |
| `--status`       | no       | Set status: `draft`, `ready`, or `paid`          |
| `--payment-amount` | no     | Payment received (requires `--status paid`)      |
| `--description`  | no       | Update invoice subject line                      |
| `--issue-date`   | no       | Update issue date (recalculates due date)        |
| `--due-date`     | no       | Override due date directly                       |
| `--net`          | no       | Update payment terms in days (recalculates due date) |
| `--notes`        | no       | Override notes (defaults to settings.edn)        |

### Delete an invoice

Removes the invoice from EDN and deletes its output files.

```bash
bb inv-delete --id 1
```

### List

```bash
bb list clients
bb list invoices
```

## Invoice statuses

- **draft** -- default, fully editable, shows "DRAFT" badge on PDF
- **ready** -- editable, marks invoice as ready to send (sets `ready-at`), no status badge shown on PDF
- **paid** -- locked, no further updates allowed (sets `paid-at`), shows "PAID" badge on PDF

When marking an invoice as paid, you can optionally provide a `--payment-amount` to record partial payments. The invoice will show:
- Subtotal (original amount)
- Payments (negative amount received)
- Amount due (remaining balance)

## Timestamps

Invoices and clients store metadata timestamps (not shown on rendered invoices):

- `:created-at` -- set when a client or invoice is created
- `:updated-at` -- set on each invoice update
- `:ready-at` -- set when status changes to "ready"
- `:paid-at` -- set when status changes to "paid"

## Developer notes

### File structure

```
billable/
  bb.edn                # tasks
  src/
    core.clj            # everything: commands, rendering, formatting
  .clj-kondo/
    config.edn          # linter levels
    imports/            # linter configs from bundled libraries
    .cache/             # gitignored, built by `bb lint-deps`
  templates/
    invoice.html        # invoice template (selmer)
  data/                 # gitignored
    settings.edn        # your (from) details
    clients.edn         # client records
    invoices.edn        # invoice records
  output/               # gitignored
    INV-1.html          # generated invoice HTML
    INV-1.pdf           # generated invoice PDF
```

### Linting

```bash
bb lint
```

Runs [clj-kondo](https://github.com/clj-kondo/clj-kondo) over `src` and
`bb.edn` and exits non-zero on any error or warning. It runs as a babashka
pod, so there is nothing to install. Linter levels live in
`.clj-kondo/config.edn`.

```bash
bb lint-deps
```

Optional, and only needed once per checkout: it indexes the libraries babashka
bundles (`babashka.fs`, `selmer`, and so on) into `.clj-kondo/.cache`, so
`bb lint` also catches misspelled vars and wrong arities in calls to them.
The cache is gitignored, and this step shells out to `bb print-deps`, which
needs a JDK on the `PATH`.

### EDN validation

All EDN files are validated on read. If a file contains malformed EDN, the
command exits with an error message rather than rendering a half-parsed
invoice.
