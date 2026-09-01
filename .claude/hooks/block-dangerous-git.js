#!/usr/bin/env node
/*
 * PreToolUse (Bash) guard.
 *
 * Blocks git operations that rewrite history, discard work, or bypass the
 * branch + PR review flow this project uses (see CLAUDE.md).
 *
 * Exit 2  -> blocked; stderr is shown to the model.
 * Exit 0  -> allowed (also the fail-open path if input can't be parsed).
 *
 * Node, not bash+jq: jq is not installed on this machine.
 */
"use strict";

const { execSync } = require("child_process");

let input = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (d) => (input += d));
process.stdin.on("end", () => {
  let cmd = "";
  try {
    cmd = (JSON.parse(input).tool_input || {}).command || "";
  } catch {
    process.exit(0); // unparseable -> don't block
  }
  if (!cmd) process.exit(0);

  const block = (reason) => {
    process.stderr.write(
      "BLOCKED: `" + cmd + "`\n" +
        "Reason: " + reason + "\n" +
        "You do not have authority for this. Ask the user to run it, or to lift the guard.\n"
    );
    process.exit(2);
  };

  const rules = [
    [/git\s+push\s+.*(--force|--force-with-lease|(^|\s)-f(\s|$))/, "force push rewrites remote history"],
    [/git\s+push\s+[^|&;]*\bHEAD:(main|master)\b/, "never push to main — push a feature branch and open a PR"],
    [/git\s+push\s+[^|&;]*\b(main|master)\b/, "never push to main — push a feature branch and open a PR"],
    [/git\s+reset\s+--hard/, "hard reset discards committed and working changes"],
    [/git\s+rebase\b/, "rebase rewrites history — not used in this workflow"],
    [/git\s+clean\s+-[a-zA-Z]*f/, "git clean -f deletes untracked files, including the gitignored notes"],
    [/git\s+branch\s+-[dD]\b/, "branch deletion — let the user do it after a merge"],
    [/git\s+(checkout|restore)\s+\.(\s|$)/, "discards all working-tree changes"],
    [/git\s+add\s+(-A\b|--all\b|\.\s*$)/, "add files by explicit path — never -A or . (gitignored notes must not be staged)"],
  ];
  for (const [re, reason] of rules) if (re.test(cmd)) block(reason);

  if (/git\s+(commit|merge)\b/.test(cmd)) {
    let branch = "";
    try {
      branch = execSync("git branch --show-current", {
        encoding: "utf8",
        cwd: process.env.CLAUDE_PROJECT_DIR || process.cwd(),
      }).trim();
    } catch {
      /* not a repo / git missing -> fall through */
    }
    if (branch === "main" || branch === "master") {
      block("commit/merge on " + branch + " — create a feature branch first; the user merges via PR");
    }
  }

  process.exit(0);
});
