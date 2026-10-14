# Reikai Roadmap

Forward plan only: what is left to build, in what order. Shipped work is in [CHANGELOG.md](CHANGELOG.md); how each area works is in [docs/dev/subsystems/](docs/dev/subsystems/README.md); what was set aside in [docs/dev/parked.md](docs/dev/parked.md); design still in flight in [docs/dev/plans/](docs/dev/plans/README.md); session state in `Handoff.md` (gitignored). Format and naming rules: [.claude/rules/roadmap-plans.md](.claude/rules/roadmap-plans.md).

## Later

Backlog, grouped by area. Unordered within an area.

### Novels

- **Hide sentences and clean up injected text** `[M]` - long-press a sentence to hide it everywhere, plus a built-in rule set for donation and community pleas, chapter footers, credits, bare links, emails and separator runs, applied wherever chapter text is processed so the page and read-aloud agree. [Doc](docs/dev/plans/novel-text-cleanup.md).
- **Audiobook player and sentence transcript** `[L]` - promote read-aloud to sentence granularity so the notification seeks to a real position, keep it playing after the reader closes, and add a full-screen player whose transcript can be tapped to seek. [Doc](docs/dev/plans/novel-audiobook-player.md).

### UI & design

- **Reikai design refresh (off stock Material 3)** `[L]` - move shape, typography, component styling, spacing and layout off stock Material 3 across the shared `Entry*` surfaces, under whichever theme the reader picked. Exploratory; it starts by seeding tokens in `DESIGN.md`. [Doc](docs/dev/subsystems/content-layer.md).

### Backup & restore

- **Build backup entries in batches** `[M]` - read chapters, categories, tracks and history for a hundred series per query instead of one series at a time, for manga and novels in the shared `backupEntries` driver, as Mihon `771ba33a3` does for manga. Cuts the queries a large backup makes. [Doc](docs/dev/subsystems/data-and-backup.md).

## Parked and not building

Not forward work, so it lives in [docs/dev/parked.md](docs/dev/parked.md): what each item is, why it
is parked or declined, and what would revive it. Reviving one brings a single line back here.
