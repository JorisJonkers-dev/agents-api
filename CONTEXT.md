# Agents

Agents runs coding agents for a signed-in user in persistent workspaces. The user can leave and come back to them later.

## Language

### Projects and Repositories

**Repository**:
A git repository the user can clone into a Workspace.
_Avoid_: Repo link, GitHub link

**Project**:
A named group of Repositories.

**Add Repository**:
Clone one more Repository into an existing Workspace.
_Avoid_: Attach repository

### Workspaces

**Workspace**:
A persistent working directory, and the repositories cloned into it, where Agent Sessions run. Every Agent Session in a Workspace shares the same working tree.
_Avoid_: Runner, Pod, sandbox, environment

**Workspace Status**:
How far a Workspace is along its life. One of Preparing, Ready, Failed or Destroyed. A Workspace is never idle; only its Agent Sessions are.
_Avoid_: Starting, Idle, Pending

**Repo-backed Workspace**:
A Workspace that has a primary Repository cloned into it.

**Scratch Workspace**:
A Workspace with no Repository.
_Avoid_: Scratch session

### Agent Sessions

**Agent Session**:
One agent (Claude, Codex or a shell) working inside a Workspace. It keeps its identity and transcript across the process that runs it.
_Avoid_: Session (unqualified), agent, process, runner

**Agent Kind**:
Which agent an Agent Session runs: Claude, Codex or Shell.

**Agent Login**:
The user's sign-in to the provider behind an Agent Kind. Every Workspace shares it.
_Avoid_: OAuth credential, credentials

**Run Mode**:
How the user drives an Agent Session. Interactive means the user works with it through a terminal. Headless means the agent works through one prompt without a terminal, and the session ends Stopped or Failed.
_Avoid_: Job, headless job

**Running**:
The agent process for an Agent Session is alive. The user can attach to it.

**Awaiting Input**:
A Running Agent Session whose agent is waiting for the user.
_Avoid_: Idle

**Idle Timeout**:
How long an Agent Session can be Awaiting Input with no one Attached before the system Suspends it.
_Avoid_: Idle scale-down

**Suspended**:
The system lost the agent process, by idle reaping, a redeploy or a crash. The transcript and files are kept, and the user can Resume it. A Suspended Agent Session never expires.
_Avoid_: Idle, Stopped, Paused

**Stopped**:
The user ended the Agent Session on purpose. It is kept for a retention period, then deleted.
_Avoid_: Suspended, Destroyed, Archived

**Failed**:
The agent process for an Agent Session could not start, or it ended in error.

**Resume**:
Start the agent process again for a Suspended or Stopped Agent Session. The agent continues its own conversation from where it left off.
_Avoid_: Rebind, Reattach

**Restart**:
Start a fresh agent process for an existing Agent Session, without the agent's previous conversation.
_Avoid_: Resume

**Attach**:
Connect a live terminal to a Running Agent Session.
_Avoid_: Durable attach, connect

**Replay**:
View the Transcript of an Agent Session that is not Running.
_Avoid_: Durable attach

**Transcript**:
The ordered Turns of an Agent Session.
_Avoid_: History, scrollback

**Turn**:
One message in a Transcript, written by the User, the Agent or the System.
_Avoid_: Message, entry

### Conversations

**Conversation**:
A chat between the user and an agent that has no Workspace.
_Avoid_: Chat session, Chat, Session
