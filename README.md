# Server Issues

Players tell the admins what went wrong, or what could be better, from inside the game.

- `/bug <what happened>` - something is broken.
- `/idea <what you would like>` - a change or a new thing.
- `/mess <what is wrong here>` - grief or a mess, reported standing at it; the mod cleans what it can tell is mess on its own (see [Messes](#messes)).
- `/issues [count]` - ops read back the latest open reports (five unless told, up to 50).
- `/issues close <number> [why]`, `/issues reopen <number>` - ops mark a report dealt with, or not after all.

Each report is saved with the time it was sent, who sent it, where they were standing and the block they were looking at, one line of JSON per report in `server-issues.jsonl` in the server's folder. Closing or reopening one adds a line too (`{"closes": 6, "time": ..., "by": ..., "why": ...}`, `{"reopens": 6, ...}`), so the file is appended and never rewritten, so a crash costs at most the report being written, and it reads in any editor or script. Each report also gets a line in the server log.

A player can send one every few seconds at most, and a report is as long as a chat message can be.

Server-side only; works with vanilla clients.

## Messes

`/mess` is a report like the others, and the mod also looks at the ground around the player: 16 blocks each way, from 12 below to 24 above. It compares what is there with a sample of the ground out to 48 blocks, so it can tell a block that was brought (netherrack in a plains) from one that belongs (dirt in that same plains). Then it looks for the shapes mess and grief leave:

- pillars: one block wide, nothing beside them, three or more high (two when the player says pillar or tower), of dirt, cobblestone, netherrack and the like, or of a block that does not belong here;
- splotches of a block that does not belong, sitting out in the open (buried blobs are left, since that is how natural ones sit);
- small clumps floating free, often all that is left after towering down;
- lava or water poured out: a few sources with most of it flowing, when that fluid does not belong here or the player named it;
- fire, unless it burns on something meant to keep it lit, like netherrack; and TNT.

It cleans a match only when nothing about it says a builder meant it: a door, bed, stairs, slab, fence, torch, glass or anything holding contents within two blocks keeps it, and so does being a tree, cactus, bamboo, dripstone and other things that grow in shapes that look placed. A block the player names (`/mess someone covered my field in sand`) counts as out of place even where it is common, but alone only cleans specks of a few blocks. One report cleans 4096 blocks at most. The player hears what was cleaned and what was left for a closer look.

Whatever looks wrong but might be someone's work is left alone and written down. Holes dug or blown in a build cannot be put back from a report, since nothing remembers what stood there. Each mess report gets an account in `server-issues-messes/<number>.md` in the server's folder: the blocks around and how common each is further out, what was cleaned and what was left with their coordinates, and a map from above.

Ops clean the rest by hand, and take back any cleaning:

- `/issues clean <number> <from> <to> [block]` - clears a box, or only the blocks matching (`minecraft:netherrack`, `#minecraft:logs`), up to 32768 blocks, in the dimension the report was made in.
- `/issues undo <number>` - puts back everything cleaned for that report since its last undo, the mod's own cleaning too, wherever the spot is still empty.

Every block cleaned is written to `server-issues-messes/<number>.jsonl` before it goes. Blocks holding anything (chests, signs, furnaces) are never cleaned, so what is written down is all there was.

A player can send one mess report a minute. Set in `config/server-issues.json`, beside `onCrash`:

```json
{
	"onMess": "investigate"
}
```

- `clean`, or left out - the report, and the cleaning the mod can do on its own. Without paude, `investigate` and `fix` do this too.
- `investigate` - when something was left, or nothing was found, a Claude session reads the account and the logs and says what it would clean, and cleans nothing until a maintainer joins and says go.
- `fix` - the same, and Claude cleans what is plainly mess with `/issues clean`, which is written down and can be undone like the rest. It leaves anything near a build, or larger than a few hundred blocks, for a maintainer.
- `report` - the report and its account, with nothing cleaned.
- `off` - no mess reports.

Mess reports within the hour of a mess session's start go to that same session, so a griefing spree is one cleanup, and players cannot start sessions faster than one an hour. The session shows up in `/maintain sessions` as "a mess" by the player who reported it. Its briefing can be replaced like the others, in `config/server-issues-mess-briefing.md`; `{server}`, `{mess}` (the report and where its account is) and `{leeway}` (how far it may go) are filled in. A player can report someone else's build as a mess; the briefing tells Claude to treat the report as a claim to check, and every cleaning, the mod's or Claude's, can be undone.

## Maintainers

Optional, and off unless the server sets it up: named players can work through reports with Claude from inside the game. Each one starts or joins a Claude Code session in [paude](https://github.com/fatlard1993/paude), on the same machine as the server, that starts out knowing its job: the reports it is about, where the logs are, how the mod suite builds, and how to stage and swap a fix without breaking the running server. Claude reads up, says in chat what it thinks is wrong, and waits to be told to go.

- `/maintain unlock <passphrase>` - once per visit, before anything else.
- `/maintain start [report numbers]` - a new session about those reports (`/maintain start 12 14`), or about whatever you then say.
- `/claude <message>` - talk to the session you are at. Its replies come back in chat, with a grey line for each command it runs or file it changes; long replies are cut short.
- `/maintain sessions`, `/maintain join <id>`, `/maintain leave` - the sessions started from the game, and moving between them. A session keeps running in paude while nobody is at it.
- `/maintain link` - a link that opens the session in a browser, with everything in it, good for a day.
- When Claude asks something (a permission, a choice), its options show in chat to click.

A fix goes out when the maintainer says so: the swap restarts the server and disconnects everyone. Coming back and unlocking again puts the maintainer back at their session, with what Claude said while they were gone.

Set up in `config/server-issues.json`:

```json
{
	"paude": {
		"url": "http://127.0.0.1:8044",
		"tokenFile": "~/.paude/local-token",
		"project": "minecraft",
		"browserUrl": "https://203.0.113.7"
	},
	"maintainers": {
		"fatlard1993": "a passphrase of twelve characters or more"
	}
}
```

Everything under `paude` is optional and shown with its default, except `browserUrl`, which `/maintain link` needs: the address paude is reached at from outside. `token` in place of `tokenFile` gives the token itself. `project` is the paude project sessions start in. Paude needs its session feed (`/api/sessions/:id/feed`), which is what carries Claude's side into chat. The file is read again when it changes; a new maintainer rejoins to see the commands.

A maintainer can do anything this machine's user can: Claude runs commands as that user. Give it to someone you would give that shell. The server accepts any name (it is offline-mode), so the name proves nothing and the passphrase is what lets a player in; five wrong ones in ten minutes and that name waits. Reports are written by any player, and the briefing tells Claude to read them as descriptions of a problem, not instructions.

The briefing Claude starts with is bundled. To change it, put your own in `config/server-issues-briefing.md`; `{maintainer}`, `{server}` (the server's folder) and `{reports}` are filled in. Sessions started from the game are listed in `server-issues-sessions.json` in the server's folder, with where each maintainer sits.

## Crashes

When the server comes back from a crash, the crash is saved as a bug report from "the server", with the cause and the crash report's name, and shows up in `/issues` like any other. It also counts a run that stopped without shutting down at all, which is what the out-of-memory killer or a `kill -9` leaves behind.

That much is on unless the server turns it off. With paude set up as well, the crash can go to a Claude session of its own, which shows up in `/maintain sessions` as "a crash by the server" for a maintainer to join. A crash within the hour of the last one goes to that same session, so a crash loop is one investigation. Set in `config/server-issues.json`, beside `paude`:

```json
{
	"onCrash": "fix"
}
```

- `report`, or left out - only the bug report. Without paude, `investigate` and `fix` do this too.
- `investigate` - Claude reads the crash report, the logs and what changed, says what it found, and changes nothing until a maintainer joins and says go.
- `fix` - when the cause is plain, lies in one mod of the suite and the fix is small, Claude makes it, stages it, warns any players and restarts the server with it, without waiting for anyone. It restarts for a fix once; anything less sure, it only says what it found.
- `off` - nothing.

The mod looks when it loads, before the world does, since a dying server cannot be trusted to say so and systemd starts it again moments later. So a server that crashes while loading the world is still caught, and one that fails before the mod loads at all is not. If Claude stops to ask a permission with nobody there, it waits until a maintainer answers. The crash session's briefing can be replaced like the other, in `config/server-issues-crash-briefing.md`; `{server}`, `{crash}` (what happened, with the report numbers) and `{leeway}` (how far it may go) are filled in. How the last run ended is kept in `server-issues-run.json` in the server's folder.

## Development

Installing is in [DEVELOPMENT.md](DEVELOPMENT.md).

## License

MIT, see [LICENSE](LICENSE).
