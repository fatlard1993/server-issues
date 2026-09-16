# Server Issues

Players tell the admins what went wrong, or what could be better, from inside the game.

- `/bug <what happened>` - something is broken.
- `/idea <what you would like>` - a change or a new thing.
- `/issues [count]` - ops read back the latest reports (five unless told, up to 50).

Each report is saved with the time it was sent, who sent it, where they were standing and the
block they were looking at, one line of JSON per report in `server-issues.jsonl` in the server's
folder. It is appended and never rewritten, so a crash costs at most the report being written,
and it reads in any editor or script. Each report also gets a line in the server log.

A player can send one every few seconds at most, and a report is as long as a chat message can be.

Server-side only; works with vanilla clients.

## Development

Installing is in [DEVELOPMENT.md](DEVELOPMENT.md).

## License

MIT, see [LICENSE](LICENSE).
