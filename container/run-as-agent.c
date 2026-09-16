/*
 * run-as-agent — start one process as the `agent` user, with a clean
 * environment.
 *
 * Agent Sessions share the API container but must not share the API's
 * privileges (ADR 0003). The JVM runs as `api` and holds no capabilities of
 * its own; this helper carries cap_setuid,cap_setgid as file capabilities and
 * is executable only by the `api` group, so the pod's SETUID/SETGID grant buys
 * exactly one transition — api to agent — and nothing else.
 *
 * The uid and gid are compiled in on purpose. setpriv would do the same work,
 * but it takes the target from its arguments, which would let anything that
 * can run it become root.
 *
 * The environment is rebuilt rather than inherited. The JVM holds the API's
 * secrets as environment variables, and this is the one place that knows a
 * privilege boundary is being crossed; leaving the caller to scrub its own
 * environment would put the whole boundary at the mercy of a ProcessBuilder
 * that forgets to. Anything an Agent Session legitimately needs is added to
 * PASSTHROUGH below, deliberately and one at a time.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <grp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/capability.h>
#include <sys/types.h>
#include <unistd.h>

#define AGENT_UID 10002
#define AGENT_GID 10002
#define AGENT_HOME "/home/agent"
#define AGENT_SHELL "/bin/bash"
#define AGENT_PATH "/usr/local/bin:/usr/bin:/bin"

/* Locale and terminal settings carry no authority and break tools when lost. */
static const char *const PASSTHROUGH[] = {
    "TERM", "TZ", "LANG", "LC_ALL", "LC_CTYPE", "COLORTERM", NULL,
};

static int fail(const char *what) {
    fprintf(stderr, "run-as-agent: %s: %s\n", what, strerror(errno));
    return 71; /* EX_OSERR */
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: run-as-agent <command> [argument...]\n");
        return 64; /* EX_USAGE */
    }

    /* Copy the survivors out before clearenv frees the strings behind them. */
    char *kept[sizeof(PASSTHROUGH) / sizeof(PASSTHROUGH[0])] = {NULL};
    for (size_t i = 0; PASSTHROUGH[i] != NULL; i++) {
        const char *value = getenv(PASSTHROUGH[i]);
        if (value != NULL && (kept[i] = strdup(value)) == NULL) return fail("strdup");
    }

    gid_t groups[1] = {AGENT_GID};
    if (setgroups(1, groups) != 0) return fail("setgroups");
    if (setgid(AGENT_GID) != 0) return fail("setgid");
    if (setuid(AGENT_UID) != 0) return fail("setuid");

    /* The uid change alone does not drop file capabilities — the kernel only
     * clears them on a transition away from uid 0, and we were never root — so
     * the agent would inherit cap_setuid and could walk straight back. */
    cap_t empty = cap_init();
    if (empty == NULL) return fail("cap_init");
    if (cap_set_proc(empty) != 0) return fail("cap_set_proc");
    cap_free(empty);

    /* Prove it rather than assume it: if this succeeds we are still root-capable. */
    if (setuid(0) == 0 || geteuid() != AGENT_UID || getuid() != AGENT_UID) {
        fprintf(stderr, "run-as-agent: privileges were not dropped\n");
        return 71;
    }

    if (clearenv() != 0) return fail("clearenv");
    if (setenv("HOME", AGENT_HOME, 1) != 0) return fail("setenv HOME");
    if (setenv("USER", "agent", 1) != 0) return fail("setenv USER");
    if (setenv("LOGNAME", "agent", 1) != 0) return fail("setenv LOGNAME");
    if (setenv("SHELL", AGENT_SHELL, 1) != 0) return fail("setenv SHELL");
    if (setenv("PATH", AGENT_PATH, 1) != 0) return fail("setenv PATH");
    for (size_t i = 0; PASSTHROUGH[i] != NULL; i++) {
        if (kept[i] != NULL && setenv(PASSTHROUGH[i], kept[i], 1) != 0) {
            return fail("setenv");
        }
    }

    /* PATH is ours now, so the lookup is over the paths above and not the
     * caller's. */
    execvp(argv[1], &argv[1]);
    return fail("exec");
}
