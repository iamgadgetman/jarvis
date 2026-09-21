# The wiki, kept in the repository

These files **are** the [GitHub wiki](https://github.com/iamgadgetman/jarvis/wiki).
The `wiki` workflow copies this folder over it on every push to `main` that
touches these files, so the pages are versioned with the code they describe
and change in the same commit as the behaviour they document.

**Edit the files here, never the wiki itself.** An edit made on the wiki is
overwritten by the next run.

- One file per page. The file name is the page name: `Voice.md` becomes the
  **Voice** page, and links to it are written `[Voice](Voice)`.
- `_Sidebar.md` is the wiki's sidebar and appears on every page.
- `README.md` (this file) is skipped by the workflow: it explains the folder
  to someone reading the repository, and is not a wiki page.

Wiki links have no `.md` suffix and use the page name with hyphens:
`[Installation & Setup](Installation-and-Setup)`.
