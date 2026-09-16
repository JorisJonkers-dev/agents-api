# Agent contract

The estate-wide conventions live in one place and are **not duplicated here**:

**https://github.com/JorisJonkers-dev/workspace/blob/main/CLAUDE.md**

Read it before doing anything non-trivial in this repository. It covers the
things that most often go wrong, including:

- **Pull request labels.** The estate uses a prefixed taxonomy — `type:`,
  `area:`, `component:`, `priority:`, `status:`. Plain `bug` / `enhancement` /
  `documentation` do **not** exist, and `gh pr create` fails with
  `'bug' not found`. Run `gh label list --repo <owner>/<repo>` once before
  passing `--label`.
- **Verify the value, not the command.** An exit code, a `Ready` condition or
  an accepted object is not evidence that a consumer sees what you intended.
- Traps around workflow runs, `zsh` word-splitting, and detached submodule
  HEADs.

Duplicating that content into every repository guarantees the copies drift, so
this file stays a pointer. Add repo-specific guidance below.

---

## This repository

### The agents image

One image runs agents-api and holds every tool an Agent Session needs
([ADR 0001](docs/adr/0001-agent-sessions-run-inside-the-api-container.md)).

- **The tools are not listed here.** `Dockerfile` fetches agent-kit's generated
  `installer/setup-container.sh` at a pinned commit and runs it. Tool versions
  move by bumping `AGENT_KIT_REF` and `AGENT_KIT_SETUP_SHA256` together — edit
  the registry in agent-kit, never the script.
- **The runtime stage is Debian, not the BellSoft CRaC image.** That script
  installs with `apt`, and `bellsoft/liberica-runtime-container` is Alpaquita:
  no `apt`, and glibc 2.43, which is newer than any Debian release, so its JDK
  cannot be copied out either. The API's JVM is therefore the Liberica CRaC
  *tarball*, unpacked at `/opt/java/crac` and deliberately **off `PATH`** —
  Agent Sessions get the registry's `temurin-21-jdk` instead.
- **Two users, and that is the whole security model**
  ([ADR 0003](docs/adr/0003-api-and-agents-run-as-separate-users.md)). `api`
  (10001) runs the JVM and owns `/etc/agents-api/secrets` at 0700. `agent`
  (10002) runs Agent Sessions and owns `/workspaces` and `/home/agent`.
  **Nothing runs as root.** `entrypoint.sh` runs as `api` and writes the copies
  of the Vault-mounted secrets itself rather than `chown`ing the injected files,
  which is what keeps `CAP_CHOWN` off the pod's capability list.
- **`run-as-agent` is how the API crosses that line.** `container/run-as-agent.c`
  is compiled into `/usr/local/lib/agents-api/run-as-agent`, mode 0050
  `root:api`, carrying `cap_setuid,cap_setgid+ep`. The target uid is compiled
  in and it empties its capability sets before `exec`, so the one thing it can
  do is start a process as `agent`. `setpriv` would have been shorter and would
  have let anything that can run it become root. That helper is the only reason
  the pod needs `SETUID` and `SETGID`.
- **`allowPrivilegeEscalation: false` silently disables that helper.** It sets
  `no_new_privs`, and the kernel then refuses to raise file capabilities at
  `execve`. The binary still runs and every Agent Session then fails to start
  with `run-as-agent: setgroups: Operation not permitted` — on the helper's
  stderr, which only appears wherever the API forwards it. The pod must keep
  `allowPrivilegeEscalation: true` while dropping every capability but `SETUID`
  and `SETGID`.
- **run-as-agent rebuilds the environment, on purpose.** The JVM holds the
  API's secrets as environment variables, and a `ProcessBuilder` that forgets
  to clear its environment map would hand all of them to the Agent Session.
  The boundary does not rely on the caller: the helper `clearenv()`s and sets
  `HOME`, `USER`, `LOGNAME`, `SHELL`, `PATH` plus a short locale allowlist.
  Anything an Agent Session needs is added there, one variable at a time.
- **No secret is ever baked.** Every Vault key is optional: a file that is not
  mounted is a feature the API turns off, never a failed start. Build-time
  leftovers are deleted **in the same `RUN`** that creates them — a later `rm`
  only whites them out of the merged view, and the layer still ships the files.
- **The injector's own directory is not ours.** `entrypoint.sh` copies
  `/vault/secrets` into `/etc/agents-api/secrets` but the originals stay where
  Vault put them, readable by `agent`, unless the pod sets
  `vault.hashicorp.com/agent-run-as-user: 10001`. The entrypoint tries the
  `chmod` and prints a `WARNING` when it cannot — read that line before
  believing the secrets are contained.
