# Deploy-key setup for `{{LINK_NAME}}`

This guide walks through provisioning a GitHub deploy key that's
scoped to **only** `{{REPO_URL}}`. The private half lives in Vault
at `{{VAULT_KEY_PATH}}`; agent-runner Pods that mount this Project
can clone, push and (with write access) open PRs against this repo
without ever seeing a personal access token.

## 1. Generate an ed25519 key locally

Run this on your laptop (`{{LINK_NAME}}` becomes the file name, no
extension):

```sh
ssh-keygen -t ed25519 \
  -f ./{{LINK_NAME}}-deploy \
  -C "{{LINK_NAME}}@agents" \
  -N ""
```

That produces two files in your current directory:

- `./{{LINK_NAME}}-deploy` — the **private** key. Keep it. You will
  paste it back into the wizard in step 3.
- `./{{LINK_NAME}}-deploy.pub` — the **public** key. You will paste
  this both into GitHub (step 2) and back into the wizard
  (step 3).

ed25519 is mandatory here. The runner image only loads ed25519-style
hostkeys by default, and ed25519 is what GitHub recommends as well.

## 2. Add the public key as a GitHub deploy key

1. Open the **Deploy keys** page for this repository:
   <{{DEPLOY_KEY_PAGE_URL}}>
2. Click **Add deploy key**.
3. **Title:** `agents — {{LINK_NAME}}`
4. **Key:** paste the entire contents of `./{{LINK_NAME}}-deploy.pub`.
5. **Allow write access:** check this box **only** if agents in this
   project should be able to push branches and open PRs. Leave it
   unchecked for read-only / inspection workspaces.
6. Click **Add key**.

GitHub will accept the key immediately. You can verify the
fingerprint by running

```sh
ssh-keygen -lf ./{{LINK_NAME}}-deploy.pub
```

locally — the `SHA256:…` value should match the **Fingerprint**
column that the agents UI displays after step 3.

For the official GitHub reference, see
<https://docs.github.com/en/authentication/connecting-to-github-with-ssh/managing-deploy-keys>.

## 3. Paste both keys into the wizard

In the agents UI, paste:

- The **entire** contents of `./{{LINK_NAME}}-deploy` (multiple
  lines, including the `-----BEGIN OPENSSH PRIVATE KEY-----` and
  `-----END OPENSSH PRIVATE KEY-----` lines) into the **Private
  key** textarea.
- The **single line** from `./{{LINK_NAME}}-deploy.pub` into the
  **Public key** field.

Optionally also paste the output of `ssh-keyscan github.com` into
the **known_hosts** field. If you skip it the API uses the bundled
GitHub host-key entries — they rotate rarely (GitHub last rolled
the RSA key in March 2023), and the runner image re-scans on boot
as belt-and-braces.

## 4. After the wizard finishes

- The key pair is written to Vault under `{{VAULT_KEY_PATH}}` and
  the row for this link gains a `Fingerprint` column. From this
  point you can delete the local `./{{LINK_NAME}}-deploy*` files,
  or keep them as a personal backup — either way the cluster has
  what it needs.
- Workspaces created against this Project will mount the key from
  Vault into the runner Pod at
  `/var/run/secrets/agents/github-deploy-key/`. `gh` and `git`
  commands inside the agent already know to use it; no further
  configuration is required.

## Rotating the key later

Generate a new keypair, attach it via the wizard (the old one will
be overwritten in Vault), then delete the old public key from
GitHub's Deploy keys page. The agents UI shows the
`fingerprint` so you can match what's in Vault against what's in
GitHub before pulling the rug.
