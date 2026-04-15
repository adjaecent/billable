# billable

Babashka-based invoice generator. Stores data in EDN, generates HTML + PDF via headless Chrome.

## Setup

Requires [Babashka](https://github.com/babashka/babashka) and Google Chrome.

Create a sample settings file and edit it with your details:

```bash
bb init
```

This creates `data/settings.edn`:

```edn
{:name "Your Name"
 :address "Line 1\nLine 2\nCity, Country"
 :gstn "your-gstn"
 :lut "your-lut"
 :phone "+91-00000-00000"
 :email "you@example.com"
 :notes "Payment via wire transfer to HDFC account"}
```

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
              --registration "2024/123456/07"
```

| Flag             | Required | Description                          |
|------------------|----------|--------------------------------------|
| `--name`         | yes      | Client name                          |
| `--currency`     | yes      | Currency code (ZAR, USD, INR, EUR, GBP) |
| `--address`      | yes      | Client address (use `$'\n'` for newlines) |
| `--registration` | no       | Registration / company number        |

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
bb inv-update --id 1 --item-desc "Software Development - SAC 998314" --item-amount 190800

# Add another line item
bb inv-update --id 1 --item-desc "Reimbursements" --item-amount 5000

# Change subject
bb inv-update --id 1 --description "April 2026"

# Change dates
bb inv-update --id 1 --issue-date "2026-04-01"
bb inv-update --id 1 --net 45
bb inv-update --id 1 --due-date "2026-06-01"

# Mark ready
bb inv-update --id 1 --status "ready"

# Mark paid (locks the invoice from further updates)
bb inv-update --id 1 --status "paid"
```

| Flag             | Required | Description                                      |
|------------------|----------|--------------------------------------------------|
| `--id`           | yes      | Invoice ID                                       |
| `--item-desc`    | no       | Line item description (use with `--item-amount`)  |
| `--item-amount`  | no       | Line item amount (use with `--item-desc`)         |
| `--status`       | no       | Set status: `draft`, `ready`, or `paid`          |
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

## File structure

```
billable/
  bb.edn
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

## Invoice statuses

- **draft** -- default, fully editable
- **ready** -- editable, marks invoice as ready to send (sets `ready-at`)
- **paid** -- locked, no further updates allowed (sets `paid-at`)

## Timestamps

Invoices and clients store metadata timestamps (not shown on rendered invoices):

- `:created-at` -- set when a client or invoice is created
- `:updated-at` -- set on each invoice update
- `:ready-at` -- set when status changes to "ready"
- `:paid-at` -- set when status changes to "paid"

## EDN validation

All EDN files are validated on read. If a file contains malformed EDN, the command exits with an error message.
